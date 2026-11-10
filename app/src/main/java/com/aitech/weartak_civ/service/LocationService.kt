/*
 * Copyright (C) 2024, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.runTimerJob
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.Utils.startGlobalTimer
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : Service() {
    private enum class Acquisition { PERIODIC, ONE_SHOT, INTERACTION, MOTION_CONFIRMATION }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var currentInput: LocationInput? = null
    private var generation = 0L
    private val listeners = mutableMapOf<Acquisition, LocationListener>()
    private val fusedCallbacks = mutableMapOf<Acquisition, LocationCallback>()
    private val fixConsumers = mutableMapOf<Acquisition, (Location) -> FixCompletion?>()
    private val fixCompletionMutex = Mutex()
    private var lastCompletedFix: Location? = null
    private var lastNormalFix: Location? = null
    private val requestVersions = mutableMapOf<Acquisition, Long>()
    private val timeouts = mutableMapOf<Acquisition, Runnable>()
    private val interactionGate = InteractionPliRefreshGate()
    private var periodic = false
    private var periodicLowPower = false
    private var dynamicReporting = false
    private var acquisitionIntervalMillis = 0L
    private var observationBaseIntervalMillis = 0L
    private val acquisitionPolicy = LocationAcquisitionPolicy()
    private var activity = ActivityRecognized.UNKNOWN
    private var observationPowerPolicy = false to false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockStartedMs = 0L
    private var motionSensorManager: SensorManager? = null
    private var motionTrigger: TriggerEventListener? = null
    private var stationaryAnchor: Location? = null
    private var motionVerifying = false
    private var motionEpoch = 0L
    private var motionWakeLock: PowerManager.WakeLock? = null
    private var motionWakeLockStartedMs = 0L
    private var motionRequestedNanos = 0L
    private var sharedMotionRequest = false
    private var receiverRegistered = false
    private val binder = LocationServiceBinder()

    private data class FixCompletion(
        val kind: Acquisition,
        val generation: Long,
        val input: LocationInput,
        val diagnosticContext: Map<DiagnosticMetric, Long>,
        val durationMillis: Long,
        val reasons: Set<PliRefreshReason>?,
        val cached: Boolean,
        val motionEpoch: Long,
    )

    private val providerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) restartLocationUpdates()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        _instance = this
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        motionSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        startForegroundServiceCompat(notificationId = LOCATION_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW)
        ContextCompat.registerReceiver(this, providerChanges,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        EventBus.getDefault().register(this)
        scope.launch {
            combine(dataRepository.getLocationInput, dataRepository.getEnableDynamicReportingStrategy) {
                input, dynamic -> input to dynamic
            }.distinctUntilChanged().collect { (input, dynamic) ->
                currentInput = input
                dynamicReporting = dynamic
                restartLocationUpdates()
            }
        }
        startAcquisitionActivityCollector()
        startMotionCollectors()
    }

    private fun startAcquisitionActivityCollector() {
        scope.launch {
            combine(dataRepository.latestActivityRecognized, dataRepository.getEnableActRecService) {
                recognized, enabled ->
                if (enabled && ContextCompat.checkSelfPermission(this@LocationService,
                        Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                    recognized else ActivityRecognized.UNKNOWN
            }.distinctUntilChanged().collect {
                if (activity != it && it != ActivityRecognized.STILL) dataRepository.noteLocationMotion()
                activity = it
                if (LocationAcquisitionPolicy.moving(it)) clearMotion()
                if (!dataRepository.stationaryMotion.state.value.stationary &&
                    LocationAcquisitionPolicy.moving(it)) resetAcquisitionBackoff()
                refreshPeriodicProfile()
            }
        }
    }

    private fun startMotionCollectors() {
        scope.launch {
            combine(dataRepository.getEnableLocationService, dataRepository.isAlerting,
                dataRepository.phoneManagedSettingsLocked) { enabled, alerting, managed ->
                enabled && !alerting && !managed
            }.distinctUntilChanged().collect { allowed ->
                if (!allowed) clearMotion()
            }
        }
        scope.launch {
            dataRepository.stationaryMotion.state.collectLatest { state ->
                if (state.movingUntilNanos > 0) {
                    delay(((state.movingUntilNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L)
                        .coerceAtLeast(0) + 1)
                    dataRepository.stationaryMotion.expire(state, SystemClock.elapsedRealtimeNanos())
                }
            }
        }
    }

    private suspend fun motionEligible(): Boolean =
        isRunning.get() &&
            currentInput?.let(::permitted) == true &&
            dataRepository.getEnableLocationService.first() &&
            dataRepository.getLocationInput.first() == currentInput &&
            dataRepository.manualAlertList.value.none { it.alertState == com.aitech.weartak_civ.data.DataRepository.AlertState.ALERT } &&
            !dataRepository.phoneManagedSettingsLocked.first()

    private fun recognizedActivity(): ActivityRecognized =
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED) activity else ActivityRecognized.UNKNOWN

    private fun motionDiagnostic(result: Long) {
        AppDiagnostics.record(DiagnosticEvent.MOTION_CONFIRMATION, mapOf(
            DiagnosticMetric.RESULT to result,
            DiagnosticMetric.ACTIVE to if (dataRepository.stationaryMotion.state.value.stationary) 1L else 0L
        ))
    }

    private fun cancelMotionTrigger() {
        val listener = motionTrigger
        motionTrigger = null
        if (listener == null) return
        val canceled = try {
            motionSensorManager?.cancelTriggerSensor(listener,
                motionSensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)) == true
        } catch (error: SecurityException) {
            Log.w(TAG, "Significant motion trigger permission revoked during cleanup", error)
            false
        }
        if (!canceled) {
            Log.w(TAG, "Could not cancel significant motion trigger; stale callbacks remain rejected")
        }
    }

    private fun clearMotion() {
        motionEpoch++
        motionVerifying = false
        cancelMotionTrigger()
        cancelRequest(Acquisition.MOTION_CONFIRMATION)
        stationaryAnchor = null
        dataRepository.stationaryMotion.clear()
    }

    private fun armMotion(): Boolean {
        if (motionVerifying || motionTrigger != null) return true
        val manager = motionSensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensor == null) {
            motionDiagnostic(5)
            clearMotion()
            return false
        }
        val epoch = motionEpoch
        val requestGeneration = generation
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                handler.post {
                    if (motionTrigger !== this || epoch != motionEpoch || requestGeneration != generation) return@post
                    motionTrigger = null
                    motionVerifying = true
                    scope.launch {
                        if (epoch != motionEpoch || requestGeneration != generation) return@launch
                        val eligible = motionEligible()
                        if (epoch != motionEpoch || requestGeneration != generation) return@launch
                        if (!eligible) {
                            clearMotion()
                            return@launch
                        }
                        dataRepository.noteLocationMotion()
                        motionDiagnostic(1)
                        motionRequestedNanos = SystemClock.elapsedRealtimeNanos()
                        val power = getSystemService(POWER_SERVICE) as PowerManager
                        motionWakeLockStartedMs = SystemClock.elapsedRealtime()
                        try {
                            motionWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "id:motion-confirm").apply {
                                acquire(ACQUISITION_TIMEOUT_MILLIS)
                            }
                        } catch (error: SecurityException) {
                            Log.w(TAG, "Cannot hold CPU for motion confirmation", error)
                            motionDiagnostic(4)
                            clearMotion()
                            return@launch
                        }
                        AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK, mapOf(
                            DiagnosticMetric.KIND to 2L, DiagnosticMetric.ACTIVE to 1L,
                            DiagnosticMetric.DURATION_MS to ACQUISITION_TIMEOUT_MILLIS))
                        requestUpdates(Acquisition.MOTION_CONFIRMATION)
                    }
                }
            }
        }
        motionTrigger = listener
        val armed = try {
            checkNotNull(manager).requestTriggerSensor(listener, sensor)
        } catch (error: SecurityException) {
            Log.w(TAG, "Significant motion trigger permission unavailable", error)
            false
        }
        if (!armed) {
            Log.w(TAG, "Significant motion trigger arming failed; retaining incumbent reporting policy")
            motionDiagnostic(6)
            clearMotion()
            return false
        }
        motionDiagnostic(0)
        return true
    }

    private suspend fun finishMotion() {
        val finishingEpoch = motionEpoch
        cancelRequest(Acquisition.MOTION_CONFIRMATION)
        motionVerifying = false
        val eligible = motionEligible()
        if (finishingEpoch != motionEpoch) return
        if (!eligible) {
            clearMotion()
        } else if (dataRepository.stationaryMotion.state.value.stationary) {
            armMotion()
        }
    }

    private suspend fun resolveMotion(location: Location, ordinary: Boolean, fresh: Boolean): Boolean {
        val fixEpoch = motionEpoch
        val fixGeneration = generation
        val eligible = motionEligible()
        if (fixEpoch != motionEpoch || fixGeneration != generation) return false
        if (!eligible) {
            if (dataRepository.stationaryMotion.state.value != StationaryMotionPolicy.State()) clearMotion()
            return false
        }
        val policy = dataRepository.stationaryMotion
        val accuracy = location.accuracy.toDouble().takeIf { location.hasAccuracy() }
        val speed = location.speed.toDouble().takeIf { location.hasSpeed() }
        val uncertainty = location.speedAccuracyMetersPerSecond.toDouble().takeIf { location.hasSpeedAccuracy() }
        val recognized = recognizedActivity()
        if (policy.state.value.stationary) {
            val anchor = stationaryAnchor
            if (fresh && (!motionVerifying || location.elapsedRealtimeNanos >= motionRequestedNanos) &&
                StationaryMotionPolicy.moved(accuracy, speed, uncertainty,
                    anchor?.distanceTo(location)?.toDouble(), anchor?.accuracy?.toDouble())) {
                val resolvingEpoch = motionEpoch
                val resolvingGeneration = generation
                val movingActivity = if (LocationAcquisitionPolicy.moving(recognized)) recognized else ActivityRecognized.UNKNOWN
                val desired = SpeedReportingPolicy.activityIntervalSeconds(movingActivity, false,
                    dataRepository.getWhileAlertingReportingInterval.first(),
                    dataRepository.getOnFootReportingInterval.first(), dataRepository.getVehicleReportingInterval.first(),
                    dataRepository.getStationaryReportingInterval.first())
                if (!motionEligible() || resolvingEpoch != motionEpoch || resolvingGeneration != generation) return false
                val interval = SpeedReportingPolicy.observationIntervalSeconds(true, desired,
                    CotDispatchService.actualReportingIntervalSecs, PowerManagerService.observationPowerThrottled,
                    PowerManagerService.observationDockThrottled)
                cancelMotionTrigger()
                stationaryAnchor = null
                CotDispatchService.desiredLocationObservationIntervalSecs = desired
                cancelRequest(Acquisition.MOTION_CONFIRMATION)
                motionVerifying = false
                policy.confirm(SystemClock.elapsedRealtimeNanos() +
                    (interval * 1_000L + ACQUISITION_TIMEOUT_MILLIS) * 1_000_000L,
                    StationaryMotionPolicy.reliableSpeed(speed, uncertainty))
                motionDiagnostic(2)
                return true
            }
            motionDiagnostic(7)
            if (ordinary && StationaryMotionPolicy.usableAccuracy(accuracy) &&
                LocationAcquisitionPolicy.stationary(speed, recognized)) stationaryAnchor = Location(location)
            return false
        }
        if (ordinary) {
            policy.clear()
            if (StationaryMotionPolicy.usableAccuracy(accuracy) &&
                LocationAcquisitionPolicy.stationary(speed, recognized)) {
                stationaryAnchor = Location(location)
                if (armMotion()) policy.hold()
            }
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun permitted(input: LocationInput): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || (input != LocationInput.INTERNAL_GNSS &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED)
    }

    private fun inputAvailable(updateStatus: Boolean = true): Boolean {
        val input = currentInput
        val manager = locationManager
        val status = try {
            when {
                input == null || manager == null -> LocationInputStatus.UNAVAILABLE
                !permitted(input) -> LocationInputStatus.PERMISSION_REQUIRED
                input == LocationInput.FUSED && fusedClient == null -> LocationInputStatus.UNAVAILABLE
                input == LocationInput.FUSED && !manager.isLocationEnabled -> LocationInputStatus.DISABLED
                input == LocationInput.FUSED -> LocationInputStatus.ACQUIRING
                input.provider !in manager.allProviders -> LocationInputStatus.UNAVAILABLE
                !manager.isProviderEnabled(input.provider) -> LocationInputStatus.DISABLED
                else -> LocationInputStatus.ACQUIRING
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Selected input permission revoked during availability check", error)
            LocationInputStatus.PERMISSION_REQUIRED
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Selected input removed during availability check", error)
            LocationInputStatus.UNAVAILABLE
        }
        if (updateStatus) dataRepository.locationInputStatus.value = status
        if (status != LocationInputStatus.ACQUIRING)
            Log.i(TAG, "Selected location input ${input?.provider} is $status; no fallback")
        return status == LocationInputStatus.ACQUIRING
    }

    private fun cancelRequest(kind: Acquisition) {
        fixConsumers.remove(kind)
        requestVersions[kind] = (requestVersions[kind] ?: 0L) + 1L
        timeouts.remove(kind)?.let(handler::removeCallbacks)
        val shared = kind == Acquisition.MOTION_CONFIRMATION && sharedMotionRequest
        if (kind == Acquisition.MOTION_CONFIRMATION) sharedMotionRequest = false
        val listener = listeners.remove(kind)?.takeUnless { shared }
        val callback = fusedCallbacks.remove(kind)?.takeUnless { shared }
        if (listener != null || callback != null) AppDiagnostics.record(DiagnosticEvent.LOCATION_STOPPED,
            mapOf(DiagnosticMetric.KIND to kind.ordinal.toLong()))
        try {
            listener?.let { registered -> locationManager?.let { manager ->
                DiagnosticResources.release(DiagnosticResource.LOCATION_NATIVE, registered) { manager.removeUpdates(registered) }
            } }
            callback?.let { registered ->
                DiagnosticResources.note(DiagnosticResource.LOCATION_FUSED, registered, ResourceAction.LOCAL_DISABLED)
                DiagnosticResources.request(DiagnosticResource.LOCATION_FUSED, registered, removal = true) {
                    fusedClient?.removeLocationUpdates(registered)
                }?.addOnSuccessListener {
                    DiagnosticResources.end(DiagnosticResource.LOCATION_FUSED, registered)
                }?.addOnFailureListener { error ->
                    DiagnosticResources.note(DiagnosticResource.LOCATION_FUSED, registered, ResourceAction.UNCERTAIN)
                    Log.w(TAG, "Could not remove $kind fused request; late callbacks remain rejected", error)
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Location permission revoked during $kind cleanup; late callbacks remain rejected", error)
        }
        if (kind == Acquisition.INTERACTION) interactionGate.cancel()
        if (kind == Acquisition.ONE_SHOT) {
            wakeLock?.let { lock ->
                val held = lock.isHeld
                if (held) DiagnosticResources.release(DiagnosticResource.LOCATION_CPU, lock) { lock.release() }
                else DiagnosticResources.end(DiagnosticResource.LOCATION_CPU, lock, expired = true)
                AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK, mapOf(
                    DiagnosticMetric.ACTIVE to 0L, DiagnosticMetric.RESULT to if (held) 1L else 0L,
                    DiagnosticMetric.DURATION_MS to (SystemClock.elapsedRealtime() - wakeLockStartedMs)
                        .coerceIn(0, ACQUISITION_TIMEOUT_MILLIS)))
            }
            wakeLock = null
        }
        if (kind == Acquisition.MOTION_CONFIRMATION) {
            motionWakeLock?.let { lock ->
                val held = lock.isHeld
                if (held) lock.release()
                AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK, mapOf(
                    DiagnosticMetric.KIND to 2L, DiagnosticMetric.ACTIVE to 0L,
                    DiagnosticMetric.RESULT to if (held) 1L else 0L,
                    DiagnosticMetric.DURATION_MS to (SystemClock.elapsedRealtime() - motionWakeLockStartedMs)
                        .coerceIn(0, ACQUISITION_TIMEOUT_MILLIS)))
            }
            motionWakeLock = null
        }
    }

    private fun cancelRequests() {
        clearMotion()
        generation++
        lastCompletedFix = null
        lastNormalFix = null
        Acquisition.entries.forEach(::cancelRequest)
    }

    private fun completeFix(received: Location) {
        val location = Location(received)
        val completions = fixConsumers.values.toList().mapNotNull { it(location) }
        val first = completions.firstOrNull() ?: return
        scope.launch {
            fixCompletionMutex.withLock {
                if (!dataRepository.getEnableLocationService.first() ||
                    first.generation != generation || currentInput != first.input || !permitted(first.input)) return@withLock
                val motionOnly = completions.all { it.kind == Acquisition.MOTION_CONFIRMATION }
                if (motionOnly && first.motionEpoch != motionEpoch) return@withLock
                val ordinary = completions.any { it.kind == Acquisition.PERIODIC || it.kind == Acquisition.ONE_SHOT }
                val confirmed = resolveMotion(location, ordinary, completions.none { it.cached })
                if (first.generation != generation || currentInput != first.input ||
                    (motionOnly && first.motionEpoch != motionEpoch)) return@withLock
                if (motionOnly && !confirmed) {
                    completions.forEach { completion ->
                        AppDiagnostics.record(DiagnosticEvent.LOCATION_ACCEPTED, completion.diagnosticContext +
                            (DiagnosticMetric.DURATION_MS to completion.durationMillis))
                    }
                    finishMotion()
                    return@withLock
                }
                val newFix = lastCompletedFix != location
                if (newFix) dataRepository.storeLatestLocation(location)
                if (!dataRepository.isCurrentLocationInput(location) || first.generation != generation ||
                    currentInput != first.input || !permitted(first.input)) return@withLock
                if (newFix) {
                    lastCompletedFix = Location(location)
                    (application as? com.aitech.weartak_civ.WearTakApplication)
                        ?.locationDiagnostics?.observeExistingFix(location)
                }
                dataRepository.locationInputStatus.value = LocationInputStatus.READY
                val wasBackedOff = acquisitionPolicy.backoffLevel > 0
                acquisitionPolicy.reset()
                completions.forEach { completion ->
                    AppDiagnostics.record(DiagnosticEvent.LOCATION_ACCEPTED, completion.diagnosticContext +
                        (DiagnosticMetric.DURATION_MS to completion.durationMillis))
                }
                val normal = completions.any { it.kind == Acquisition.PERIODIC || it.kind == Acquisition.ONE_SHOT } &&
                    lastNormalFix != location
                if (normal) lastNormalFix = lastCompletedFix
                if (normal || confirmed || completions.any { it.kind == Acquisition.ONE_SHOT }) {
                    if (dynamicReporting) scheduleNextDynamicObservation()
                    else if (wasBackedOff) scheduleConstantObservation()
                }
                if (first.generation != generation || currentInput != first.input ||
                    !dataRepository.isCurrentLocationInput(location)) return@withLock
                if (newFix || normal) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val expected = nextObservationDeadlineNanos.value ?: (now +
                        PowerManagerService.locationObservationIntervalSeconds(dynamicReporting) * 1_000_000_000L)
                    val expiry = maxOf(now, expected) + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
                    // A cached observation must not grant an old speed a new full validity window.
                    val cachedExpiry = location.elapsedRealtimeNanos +
                        (PowerManagerService.locationObservationIntervalSeconds(dynamicReporting) * 1_000L +
                            ACQUISITION_TIMEOUT_MILLIS) * 1_000_000L
                    speedObservation.value = SpeedObservation(location,
                        if (completions.any { it.cached })
                            minOf(expiry, cachedExpiry) else expiry)
                    refreshPeriodicProfile()
                }
                if (normal || confirmed) EventBus.getDefault().post(LocationRcvd())
                completions.firstOrNull { it.reasons != null }?.reasons?.let { reasons ->
                    EventBus.getDefault().post(InteractionLocationRcvd(location, reasons))
                }
                if (completions.any { it.kind == Acquisition.MOTION_CONFIRMATION && it.motionEpoch == motionEpoch })
                    finishMotion()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestUpdates(kind: Acquisition, intervalMillis: Long = 0) {
        val requestGeneration = generation
        val requestVersion = requestVersions[kind] ?: 0L
        val input = currentInput
        val enabled = dataRepository.getEnableLocationService.first()
        if (!isRunning.get() || requestGeneration != generation || currentInput != input ||
            requestVersion != (requestVersions[kind] ?: 0L)) return
        if (!enabled) {
            cancelRequest(kind)
            if (kind == Acquisition.MOTION_CONFIRMATION) clearMotion()
            dataRepository.locationInputStatus.value = LocationInputStatus.OFF
            return
        }
        if (!inputAvailable(kind != Acquisition.MOTION_CONFIRMATION)) {
            if (kind == Acquisition.MOTION_CONFIRMATION) finishMotion()
            return
        }
        if (input == null) return
        val manager = locationManager ?: return
        val requestedNanos = if (kind == Acquisition.MOTION_CONFIRMATION) motionRequestedNanos
            else SystemClock.elapsedRealtimeNanos()
        if (kind == Acquisition.MOTION_CONFIRMATION &&
            SystemClock.elapsedRealtimeNanos() - requestedNanos >= ACQUISITION_TIMEOUT_MILLIS * 1_000_000L) {
            motionDiagnostic(3)
            finishMotion()
            return
        }
        val lowPower = input == LocationInput.FUSED && kind != Acquisition.INTERACTION &&
            kind != Acquisition.MOTION_CONFIRMATION &&
            stationary()
        if (kind == Acquisition.PERIODIC) periodicLowPower = lowPower
        val priority = if (lowPower) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
        val maxAgeMillis = if (lowPower) LocationAcquisitionPolicy.STATIONARY_MAX_AGE_MILLIS else 0L
        val deadlineNanos = when (kind) {
            Acquisition.PERIODIC -> Long.MAX_VALUE
            Acquisition.ONE_SHOT -> requestedNanos + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
            Acquisition.INTERACTION -> requestedNanos + InteractionPliRefreshGate.TIMEOUT_MILLIS * 1_000_000L
            Acquisition.MOTION_CONFIRMATION -> requestedNanos + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
        }
        val diagnosticContext = mapOf(
            DiagnosticMetric.SOURCE to input.ordinal.toLong(),
            DiagnosticMetric.KIND to kind.ordinal.toLong(),
            DiagnosticMetric.BACKOFF_LEVEL to acquisitionPolicy.backoffLevel.toLong(),
            DiagnosticMetric.PRIORITY to priority.toLong(),
            DiagnosticMetric.MAX_AGE_MS to maxAgeMillis
        )
        AppDiagnostics.record(DiagnosticEvent.LOCATION_REQUEST, diagnosticContext +
            (DiagnosticMetric.INTERVAL_SECONDS to intervalMillis / 1_000L))
        fun acceptLocation(location: Location, active: Boolean): FixCompletion? {
            if (!active || requestGeneration != generation ||
                currentInput != input || !permitted(input) || location.provider != input.provider) return null
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (nowNanos > deadlineNanos || !LocationAcquisitionPolicy.freshEnough(
                    location.elapsedRealtimeNanos, requestedNanos, nowNanos, maxAgeMillis) || location.time <= 0 ||
                (location.elapsedRealtimeNanos < requestedNanos && !dataRepository.isCurrentLocationInput(location)) ||
                !location.altitude.isFinite() ||
                !InteractionPliRefreshGate.usable(location.latitude, location.longitude,
                    location.accuracy, location.hasAccuracy())) {
                Log.i(TAG, "Ignoring unusable or pre-request ${input.provider} fix")
                AppDiagnostics.record(DiagnosticEvent.LOCATION_REJECTED, diagnosticContext)
                return null
            }
            val reasons = if (kind == Acquisition.INTERACTION) {
                interactionGate.consume(location.elapsedRealtimeNanos, nowNanos, true) ?: return null
            } else null
            if (kind != Acquisition.PERIODIC) cancelRequest(kind)
            return FixCompletion(kind, requestGeneration, input, diagnosticContext,
                (nowNanos - requestedNanos) / 1_000_000L, reasons, location.elapsedRealtimeNanos < requestedNanos,
                motionEpoch)
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listeners[kind] === this) completeFix(location)
            }

            override fun onProviderDisabled(provider: String) {
                if (listeners[kind] === this && provider == input.provider &&
                    requestGeneration == generation) restartLocationUpdates()
            }
            override fun onProviderEnabled(provider: String) {
                if (listeners[kind] === this && provider == input.provider &&
                    requestGeneration == generation) restartLocationUpdates()
            }
            @Deprecated("Required on older Android versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (fusedCallbacks[kind] === this) result.lastLocation?.let(::completeFix)
            }
        }
        fixConsumers[kind] = { location ->
            acceptLocation(location, if (input == LocationInput.FUSED) fusedCallbacks[kind] === fusedCallback
                else listeners[kind] === listener)
        }
        if (input == LocationInput.FUSED) fusedCallbacks[kind] = fusedCallback
        else listeners[kind] = listener
        val shareInteraction = kind == Acquisition.MOTION_CONFIRMATION &&
            fixConsumers.containsKey(Acquisition.INTERACTION)
        if (kind == Acquisition.MOTION_CONFIRMATION) sharedMotionRequest = shareInteraction
        if (kind != Acquisition.PERIODIC) {
            val timeout = Runnable {
                if (!isRunning.get() || requestGeneration != generation ||
                    requestVersion != (requestVersions[kind] ?: 0L)) return@Runnable
                cancelRequest(kind)
                val completedVersion = requestVersions[kind]
                scope.launch {
                    val serviceEnabled = dataRepository.getEnableLocationService.first()
                    if (!isRunning.get() || requestGeneration != generation ||
                        completedVersion != requestVersions[kind]) return@launch
                    if (!serviceEnabled || !permitted(input)) {
                        if (kind == Acquisition.MOTION_CONFIRMATION) clearMotion()
                        dataRepository.locationInputStatus.value = if (serviceEnabled)
                            LocationInputStatus.PERMISSION_REQUIRED else LocationInputStatus.OFF
                        return@launch
                    }
                    Log.i(TAG, "$kind ${input.provider} request timed out without a usable fix")
                    AppDiagnostics.record(DiagnosticEvent.LOCATION_TIMEOUT, diagnosticContext +
                        (DiagnosticMetric.DURATION_MS to (SystemClock.elapsedRealtimeNanos() - requestedNanos) / 1_000_000L))
                    if (kind == Acquisition.MOTION_CONFIRMATION) {
                        motionDiagnostic(3)
                        finishMotion()
                        return@launch
                    }
                    dataRepository.locationInputStatus.value = LocationInputStatus.REQUEST_FAILED
                    if (kind == Acquisition.ONE_SHOT) observationFailed()
                }
            }
            timeouts[kind] = timeout
            handler.postDelayed(timeout, if (kind == Acquisition.INTERACTION)
                InteractionPliRefreshGate.TIMEOUT_MILLIS else
                ((deadlineNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L).coerceAtLeast(0))
        }
        if (shareInteraction) {
            AppDiagnostics.record(DiagnosticEvent.LOCATION_COALESCED, diagnosticContext)
            return
        }
        try {
            if (input == LocationInput.FUSED) {
                val client = checkNotNull(fusedClient) { "Fused client missing after availability check" }
                val request = LocationRequest.Builder(priority,
                    intervalMillis.coerceAtLeast(1_000L))
                    .setMinUpdateIntervalMillis(0L)
                    .setMaxUpdateAgeMillis(maxAgeMillis)
                    .setWaitForAccurateLocation(!lowPower)
                    .apply {
                        if (kind != Acquisition.PERIODIC) setDurationMillis(
                            ((deadlineNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L).coerceAtLeast(1))
                    }
                    .build()
                DiagnosticResources.request(DiagnosticResource.LOCATION_FUSED, fusedCallback) {
                    client.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                }.addOnSuccessListener {
                    DiagnosticResources.begin(DiagnosticResource.LOCATION_FUSED, fusedCallback,
                        periodUs = intervalMillis.coerceAtLeast(1_000L) * 1000)
                    if (fusedCallbacks[kind] !== fusedCallback || requestGeneration != generation) {
                        DiagnosticResources.note(DiagnosticResource.LOCATION_FUSED, fusedCallback, ResourceAction.STALE_CALLBACK)
                        DiagnosticResources.note(DiagnosticResource.LOCATION_FUSED, fusedCallback, ResourceAction.UNCERTAIN)
                    }
                }
                    .addOnFailureListener { error ->
                        DiagnosticResources.note(DiagnosticResource.LOCATION_FUSED, fusedCallback, ResourceAction.FAILED)
                        scope.launch {
                            val serviceEnabled = dataRepository.getEnableLocationService.first()
                            if (!isRunning.get() || fusedCallbacks[kind] !== fusedCallback ||
                                requestGeneration != generation) return@launch
                            Log.w(TAG, "Fused location request failed; no fallback", error)
                            AppDiagnostics.record(DiagnosticEvent.LOCATION_FAILURE, diagnosticContext)
                            cancelRequest(kind)
                            if (kind == Acquisition.MOTION_CONFIRMATION) {
                                motionDiagnostic(4)
                                finishMotion()
                                return@launch
                            }
                            dataRepository.locationInputStatus.value = when {
                                !serviceEnabled -> LocationInputStatus.OFF
                                error is SecurityException || !permitted(input) -> LocationInputStatus.PERMISSION_REQUIRED
                                else -> LocationInputStatus.REQUEST_FAILED
                            }
                            if (serviceEnabled && kind == Acquisition.ONE_SHOT &&
                                error !is SecurityException && permitted(input)) observationFailed()
                        }
                    }
                    .addOnCanceledListener {
                        DiagnosticResources.note(DiagnosticResource.LOCATION_FUSED, fusedCallback, ResourceAction.FAILED)
                        if (fusedCallbacks[kind] !== fusedCallback || requestGeneration != generation) return@addOnCanceledListener
                        Log.i(TAG, "$kind fused request cancelled")
                        cancelRequest(kind)
                        if (kind == Acquisition.MOTION_CONFIRMATION) scope.launch { finishMotion() }
                        if (dynamicReporting && kind == Acquisition.ONE_SHOT)
                            scope.launch { scheduleNextDynamicObservation() }
                    }
            } else {
                DiagnosticResources.acquire(DiagnosticResource.LOCATION_NATIVE, listener, periodUs = intervalMillis * 1000) {
                    manager.requestLocationUpdates(input.provider, intervalMillis, 0f, listener, Looper.getMainLooper())
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Selected input permission revoked: ${input.provider}", error)
            AppDiagnostics.record(DiagnosticEvent.LOCATION_FAILURE, diagnosticContext +
                (DiagnosticMetric.RESULT to 1L))
            cancelRequest(kind)
            if (kind == Acquisition.MOTION_CONFIRMATION) {
                clearMotion()
                return
            }
            dataRepository.locationInputStatus.value = LocationInputStatus.PERMISSION_REQUIRED
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Selected input unavailable: ${input.provider}", error)
            AppDiagnostics.record(DiagnosticEvent.LOCATION_FAILURE, diagnosticContext +
                (DiagnosticMetric.RESULT to 2L))
            cancelRequest(kind)
            if (kind == Acquisition.MOTION_CONFIRMATION) {
                motionDiagnostic(4)
                finishMotion()
                return
            }
            dataRepository.locationInputStatus.value = LocationInputStatus.UNAVAILABLE
            if (kind == Acquisition.ONE_SHOT) observationFailed()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onInteractionPliRefresh(event: InteractionPliRefresh) {
        if (!isRunning.get()) return
        scope.launch {
            dataRepository.noteLocationMotion()
            resetAcquisitionBackoff()
            if (!inputAvailable()) {
                cancelRequest(Acquisition.INTERACTION)
                return@launch
            }
            if (!interactionGate.request(event.reason, SystemClock.elapsedRealtimeNanos())) {
                AppDiagnostics.record(DiagnosticEvent.LOCATION_COALESCED)
                return@launch
            }
            requestUpdates(Acquisition.INTERACTION)
        }
    }

    private fun restartLocationUpdates() {
        com.aitech.weartak_civ.Utils.cancelGlobalTimer(this)
        acquisitionPolicy.reset()
        cancelRequests()
        nextObservationDeadlineNanos.value = null
        speedObservation.value = null
        observationPowerPolicy = PowerManagerService.observationPowerThrottled to
            PowerManagerService.observationDockThrottled
        if (!isRunning.get()) return
        val restartGeneration = generation
        scope.launch {
            if (!dataRepository.getEnableLocationService.first()) {
                dataRepository.locationInputStatus.value = LocationInputStatus.OFF
                return@launch
            }
            if (restartGeneration != generation) return@launch
            val interval = PowerManagerService.locationObservationIntervalSeconds(dynamicReporting)
            acquisitionIntervalMillis = interval * 1_000L
            observationBaseIntervalMillis = acquisitionIntervalMillis
            periodic = interval < MAX_PERIODIC_GPS_UPDATE_INTERVAL
            if (dynamicReporting)
                nextObservationDeadlineNanos.value = SystemClock.elapsedRealtimeNanos() +
                    acquisitionIntervalMillis * 1_000_000L
            if (periodic) requestUpdates(Acquisition.PERIODIC,
                acquisitionIntervalMillis)
            else {
                requestUpdates(Acquisition.ONE_SHOT)
                if (!dynamicReporting) runTimerJob(acquisitionIntervalMillis)
            }
        }
    }

    private fun setObservationDeadline(deadlineNanos: Long) {
        nextObservationDeadlineNanos.value = deadlineNanos
        val observation = speedObservation.value ?: return
        val expiry = deadlineNanos + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
        if (expiry < observation.validUntilElapsedNanos)
            speedObservation.value = observation.copy(validUntilElapsedNanos = expiry)
    }

    private suspend fun scheduleNextDynamicObservation(
        anchorNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        val scheduleGeneration = generation
        if (!isRunning.get() || !dynamicReporting || !dataRepository.getEnableLocationService.first()) return
        if (scheduleGeneration != generation) return
        observationBaseIntervalMillis = PowerManagerService.locationObservationIntervalSeconds(true) * 1_000L
        val intervalMillis = acquisitionPolicy.intervalMillis(observationBaseIntervalMillis)
        AppDiagnostics.record(DiagnosticEvent.OBSERVATION_SCHEDULED,
            mapOf(DiagnosticMetric.INTERVAL_SECONDS to intervalMillis / 1_000L,
                DiagnosticMetric.BACKOFF_LEVEL to acquisitionPolicy.backoffLevel.toLong()))
        val deadline = anchorNanos + intervalMillis * 1_000_000L
        setObservationDeadline(deadline)
        if (intervalMillis < MAX_PERIODIC_GPS_UPDATE_INTERVAL * 1_000L) {
            com.aitech.weartak_civ.Utils.cancelGlobalTimer(this)
            if (!periodic || intervalMillis != acquisitionIntervalMillis) {
                // Change a subscription only after its observation completed, never on speed expiry.
                cancelRequest(Acquisition.PERIODIC)
                periodic = true
                acquisitionIntervalMillis = intervalMillis
                requestUpdates(Acquisition.PERIODIC, intervalMillis)
            }
        } else {
            cancelRequest(Acquisition.PERIODIC)
            periodic = false
            acquisitionIntervalMillis = intervalMillis
            val remainingMillis = (deadline - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
            if (remainingMillis > 0) runTimerJob(remainingMillis)
            else acquireWakelockAndRequestLocation()
        }
    }

    private fun stationary(): Boolean {
        if (CotDispatchService.stationaryLocation) return true
        if (dataRepository.stationaryMotion.state.value.stationary) return true
        if (dataRepository.stationaryMotion.state.value.movingUntilNanos > SystemClock.elapsedRealtimeNanos()) return false
        val speed = speedObservation.value?.let { observation ->
            val location = observation.location
            SpeedReportingPolicy.validSpeed(location.speed.toDouble().takeIf { location.hasSpeed() },
                location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(),
                dataRepository.isCurrentLocationInput(location), observation.validUntilElapsedNanos)
        }
        return LocationAcquisitionPolicy.stationary(speed, recognizedActivity())
    }

    private suspend fun refreshPeriodicProfile() {
        if (!periodic || currentInput != LocationInput.FUSED || periodicLowPower == stationary()) return
        cancelRequest(Acquisition.PERIODIC)
        requestUpdates(Acquisition.PERIODIC, acquisitionIntervalMillis)
    }

    private fun scheduleConstantObservation() {
        val interval = acquisitionPolicy.intervalMillis(
            PowerManagerService.locationObservationIntervalSeconds(false) * 1_000L)
        AppDiagnostics.record(DiagnosticEvent.OBSERVATION_SCHEDULED, mapOf(
            DiagnosticMetric.INTERVAL_SECONDS to interval / 1_000L,
            DiagnosticMetric.BACKOFF_LEVEL to acquisitionPolicy.backoffLevel.toLong()))
        runTimerJob(interval)
    }

    private fun observationFailed() {
        acquisitionPolicy.failed()
        val failedGeneration = generation
        scope.launch {
            if (!isRunning.get() || !dataRepository.getEnableLocationService.first()) return@launch
            if (failedGeneration != generation) return@launch
            if (dynamicReporting) scheduleNextDynamicObservation() else scheduleConstantObservation()
        }
    }

    private suspend fun resetAcquisitionBackoff() {
        val resetGeneration = generation
        val backedOff = acquisitionPolicy.backoffLevel > 0
        acquisitionPolicy.reset()
        if (!backedOff || !isRunning.get() || !dataRepository.getEnableLocationService.first()) return
        if (resetGeneration != generation) return
        if (listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) return
        if (dynamicReporting) scheduleNextDynamicObservation() else scheduleConstantObservation()
    }

    fun acquireWakelockAndRequestLocation() {
        handler.post {
            val requestGeneration = generation
            val requestVersion = requestVersions[Acquisition.ONE_SHOT] ?: 0L
            if (listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) {
                AppDiagnostics.record(DiagnosticEvent.LOCATION_COALESCED)
                return@post
            }
            scope.launch {
                val enabled = dataRepository.getEnableLocationService.first()
                if (!isRunning.get() || periodic || !enabled || requestGeneration != generation) return@launch
                if (requestVersion != (requestVersions[Acquisition.ONE_SHOT] ?: 0L) ||
                    listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) {
                    AppDiagnostics.record(DiagnosticEvent.LOCATION_COALESCED)
                    return@launch
                }
                cancelRequest(Acquisition.ONE_SHOT)
                if (!dynamicReporting) startGlobalTimer(acquisitionPolicy.intervalMillis(
                    PowerManagerService.locationObservationIntervalSeconds(false) * 1_000L))
                if (!inputAvailable()) return@launch
                val power = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "id:gps").apply {
                    DiagnosticResources.acquire(DiagnosticResource.LOCATION_CPU, this, timeoutMs = 6_000) {
                        acquire(6_000)
                    }
                }
                wakeLockStartedMs = SystemClock.elapsedRealtime()
                AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK, mapOf(
                    DiagnosticMetric.ACTIVE to 1L, DiagnosticMetric.DURATION_MS to 6_000L))
                requestUpdates(Acquisition.ONE_SHOT)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onLocationUpdateRequiredEvent(event: LocationUpdateRequired) = restartLocationUpdates()

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPowerManagerEvent(event: PowerManagerEvent) {
        if (event.eventType == PowerManagerEvent.CHANGED_UPDATE_INTERVAL) {
            val policy = PowerManagerService.observationPowerThrottled to PowerManagerService.observationDockThrottled
            if (!dynamicReporting) {
                scope.launch {
                    val interval = PowerManagerService.locationObservationIntervalSeconds(false) * 1_000L
                    if (acquisitionIntervalMillis == interval) return@launch
                    val faster = interval < acquisitionIntervalMillis
                    acquisitionPolicy.reset()
                    cancelRequest(Acquisition.PERIODIC)
                    acquisitionIntervalMillis = interval
                    observationBaseIntervalMillis = interval
                    periodic = interval < MAX_PERIODIC_GPS_UPDATE_INTERVAL * 1_000L
                    if (periodic) {
                        com.aitech.weartak_civ.Utils.cancelGlobalTimer(this@LocationService)
                        requestUpdates(Acquisition.PERIODIC, interval)
                    }
                    else {
                        runTimerJob(interval)
                        if (faster) acquireWakelockAndRequestLocation()
                    }
                }
                return
            }
            val intervalMillis = PowerManagerService.locationObservationIntervalSeconds(true) * 1_000L
            if (observationPowerPolicy == policy && observationBaseIntervalMillis == intervalMillis) return
            observationPowerPolicy = policy
            observationBaseIntervalMillis = intervalMillis
            acquisitionPolicy.reset()
            // Keep native pending requests and their deadlines; their completion uses the new profile.
            if (listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) return
            val anchor = nextObservationDeadlineNanos.value?.minus(acquisitionIntervalMillis * 1_000_000L)
                ?: SystemClock.elapsedRealtimeNanos()
            scope.launch { scheduleNextDynamicObservation(anchor) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocationServiceBinder : Binder() {
        val service: LocationService get() = this@LocationService
    }

    override fun onDestroy() {
        isRunning.set(false)
        com.aitech.weartak_civ.Utils.cancelGlobalTimer(this)
        cancelRequests()
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(providerChanges)
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        dataRepository.locationInputStatus.value = LocationInputStatus.OFF
        nextObservationDeadlineNanos.value = null
        speedObservation.value = null
        locationManager = null
        fusedClient = null
        _instance = null
        stopForegroundServiceCompat()
        super.onDestroy()
    }

    companion object {
        const val ACQUISITION_TIMEOUT_MILLIS = 6_000L
        data class SpeedObservation(val location: Location, val validUntilElapsedNanos: Long)
        val speedObservation = MutableStateFlow<SpeedObservation?>(null)
        val nextObservationDeadlineNanos = MutableStateFlow<Long?>(null)
        private const val TAG = "WearTAK-LocationService"
        private const val LOCATION_SERVICE_NOTIFICATION_ID = 1234
        private const val MAX_PERIODIC_GPS_UPDATE_INTERVAL = 10
        var lat: Double? = null
        var lon: Double? = null
        var hae: Double? = null
        var ce: Float? = null
        var le: Float? = null
        val isRunning = AtomicBoolean(false)
        @Volatile private var _instance: LocationService? = null
        fun getInstance(): LocationService? = _instance
    }
}
