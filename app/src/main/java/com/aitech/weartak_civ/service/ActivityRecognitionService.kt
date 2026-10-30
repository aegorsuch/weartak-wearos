/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.HealthEvent
import com.aitech.weartak_civ.manager.RestingHrManager
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.clearPassiveListenerCallback
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.ActivityPayload
import com.aitech.weartak_civ.cep.model.payloads.SleepPayload
import com.aitech.weartak_civ.CAT_FALL_DETECTED
import com.aitech.weartak_civ.DESC_GOOGLE_SDK_FALL_DETECTED
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
import java.util.UUID


class ActivityRecognitionService : Service() {
    companion object {
        private const val TAG = "WearTAK-ActRecService"
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val ACTION_RECOGNITION = "com.aitech.weartak_civ.ACTION_RECOGNITION"
        private const val NOTIF_ID = 192
        private const val LOG_FILENAME = "/sdcard/Download/activity_detection.log"
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        internal val PASSIVE_DAILY_DATA_TYPES: Set<DataType<*, *>> = setOf(
            DataType.STEPS_DAILY, DataType.FLOORS_DAILY, DataType.ELEVATION_GAIN_DAILY
        )
        enum class ActivityRecognized {
            RUNNING, WALKING, TILTING, STILL, BICYCLING, IN_VEHICLE, ON_FOOT, UNKNOWN
        }
    }

    //private val logFile by lazy { File(LOG_FILENAME) }

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var callbackIntent: PendingIntent
    // App-owned repository: the system may (re)start this service before MainActivity initializes its copy.
    private val repo: DataRepository by lazy { (applicationContext as WearTakApplication).repo }

    private val healthClient by lazy { HealthServices.getClient(this) }
    private val passiveMonitoringClient by lazy { healthClient.passiveMonitoringClient }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var passiveMonitoringEnabled = false
    private var passiveListenerRegistered = false

    private var cumulativeSteps:     Long?   = null
    private var cumulativeFloors:    Double? = null
    private var cumulativeElevation: Double? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        repo.resetActivityRecognition()
        Log.i(TAG, "ActivityRecognitionService onCreate()")
        //writeToLogFileLocation(logFile, logStr)
        activityRecognitionClient = ActivityRecognition.getClient(this)
        // Refused foreground promotion: the service is stopping; register nothing.
        if (!startForegroundServiceCompat(NOTIF_ID, importance = android.app.NotificationManager.IMPORTANCE_LOW)) return

        // Instead of a BroadcastReceiver, send the Intent back to this Service:
        val intent = Intent(this, ActivityRecognitionService::class.java).setAction(ACTION_RECOGNITION)

        callbackIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        scope.launch {
            if (!isRunning.get() || !recognitionEnabled()) return@launch
            if (ContextCompat.checkSelfPermission(this@ActivityRecognitionService,
                Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                DiagnosticResources.request(DiagnosticResource.ACTIVITY, callbackIntent) {
                    activityRecognitionClient.requestActivityUpdates(UPDATE_INTERVAL_MS, callbackIntent)
                }
                    .addOnSuccessListener {
                        DiagnosticResources.begin(DiagnosticResource.ACTIVITY, callbackIntent, periodUs = UPDATE_INTERVAL_MS * 1000)
                        if (!isRunning.get()) {
                            DiagnosticResources.note(DiagnosticResource.ACTIVITY, callbackIntent, ResourceAction.STALE_CALLBACK)
                            DiagnosticResources.note(DiagnosticResource.ACTIVITY, callbackIntent, ResourceAction.UNCERTAIN)
                        }
                        Log.i(TAG, "Activity updates requested every $UPDATE_INTERVAL_MS ms")
                    }
                    .addOnFailureListener {
                        DiagnosticResources.note(DiagnosticResource.ACTIVITY, callbackIntent, ResourceAction.FAILED)
                        Log.e(TAG, "Failed to request activity updates", it)
                    }
            } else {
                Log.w(TAG, "Cannot request activity updates: permission not granted")
            }
        }

        scope.launch {
            if (!repo.getEnablePhysiologicalServices.first()) return@launch
            CepBus.emit(
                CepEvent(
                    topic = Topics.ACTIVITY,
                    payload = ActivityPayload(state = "UNKNOWN", confidence = 0)
                )
            )

            CepBus.emit(
                CepEvent(
                    topic = Topics.SLEEP,
                    payload = SleepPayload(is_sleeping = false)
                )
            )
        }

        startPassiveMonitoringCollector()
    }

    private fun startPassiveMonitoringCollector() {
        scope.launch {
            combine(repo.getEnablePhysiologicalServices, repo.getEnableActRecService) {
                monitoring, activity -> monitoring && activity
            }.distinctUntilChanged().collectLatest { enabled ->
                passiveMonitoringEnabled = enabled &&
                    ContextCompat.checkSelfPermission(this@ActivityRecognitionService,
                        Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
                if (!passiveMonitoringEnabled) {
                    if (passiveListenerRegistered) {
                        passiveListenerRegistered = false
                        DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.LOCAL_DISABLED)
                        DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.REMOVAL_REQUESTED)
                        try {
                            withContext(NonCancellable) { passiveMonitoringClient.clearPassiveListenerCallback() }
                            DiagnosticResources.end(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback)
                        } catch (cancelled: CancellationException) {
                            DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.UNCERTAIN)
                            throw cancelled
                        } catch (error: Exception) {
                            DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.UNCERTAIN)
                            Log.e(TAG, "Failed to clear passive listener; disabled callbacks remain rejected", error)
                        }
                    }
                } else registerPassiveMonitoring()
            }
        }
    }

    private suspend fun registerPassiveMonitoring() {
        try {
            val caps = passiveMonitoringClient.getCapabilities()
            if (!canAcceptPassiveData() || !repo.getEnablePhysiologicalServices.first() ||
                !repo.getEnableActRecService.first()) return
            Log.i(TAG, "Passive caps => ${caps.supportedDataTypesPassiveMonitoring}")
            val wanted = PASSIVE_DAILY_DATA_TYPES.intersect(caps.supportedDataTypesPassiveMonitoring)
            if (wanted.isEmpty()) {
                Log.w(TAG, "No requested passive data types supported")
                return
            }
            val cfg = PassiveListenerConfig.builder()
                .setDataTypes(wanted)
                .setHealthEventTypes(setOf(HealthEvent.Type.FALL_DETECTED))
                .build()
            passiveMonitoringClient.setPassiveListenerCallback(cfg, passiveDailyListenerCallback)
            DiagnosticResources.begin(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback)
            if (!canAcceptPassiveData()) {
                DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.STALE_CALLBACK)
                DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.LOCAL_DISABLED)
            }
            passiveListenerRegistered = true
            Log.d(TAG, "Registered passive listener for $wanted")
        } catch (cancelled: CancellationException) {
            DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.UNCERTAIN)
            throw cancelled
        } catch (t: Exception) {
            DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.FAILED)
            Log.e(TAG, "Failed to register passive listener", t)
        }
    }

    private fun clearPassiveMonitoring() {
        if (!passiveListenerRegistered) return
        passiveListenerRegistered = false
        DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.LOCAL_DISABLED)
        Futures.addCallback(DiagnosticResources.request(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, removal = true) {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync()
        },
            object : FutureCallback<Void> {
                override fun onSuccess(result: Void?) {
                    DiagnosticResources.end(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback)
                }
                override fun onFailure(error: Throwable) {
                    DiagnosticResources.note(DiagnosticResource.PASSIVE_HEALTH, passiveDailyListenerCallback, ResourceAction.UNCERTAIN)
                    Log.e(TAG, "Failed to clear passive listener; disabled callbacks remain rejected", error)
                }
            }, ContextCompat.getMainExecutor(this))
    }

    private fun canAcceptPassiveData(): Boolean =
        passiveMonitoringEnabled && isRunning.get() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) ==
                PackageManager.PERMISSION_GRANTED

    private suspend fun recognitionEnabled(): Boolean =
        repo.getEnableActRecService.first() && (repo.getEnablePhysiologicalServices.first() ||
            (repo.getEnableDynamicReportingStrategy.first() && repo.getEnableLocationService.first()))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ActivityRecognitionService onStartCommand()")
        // Handle every ACTION_RECOGNITION intent
        if (intent?.action == ACTION_RECOGNITION &&
            ActivityRecognitionResult.hasResult(intent)) {

            val result = ActivityRecognitionResult.extractResult(intent)
            val act = result?.mostProbableActivity
            val type = act?.type ?: DetectedActivity.UNKNOWN
            val detectedActivity = when (type) {
                DetectedActivity.RUNNING    -> ActivityRecognized.RUNNING
                DetectedActivity.WALKING    -> ActivityRecognized.WALKING
                DetectedActivity.TILTING    -> ActivityRecognized.TILTING
                DetectedActivity.STILL      -> ActivityRecognized.STILL
                DetectedActivity.ON_BICYCLE -> ActivityRecognized.BICYCLING
                DetectedActivity.IN_VEHICLE -> ActivityRecognized.IN_VEHICLE
                DetectedActivity.ON_FOOT    -> ActivityRecognized.ON_FOOT
                DetectedActivity.UNKNOWN    -> ActivityRecognized.UNKNOWN
                else                        -> ActivityRecognized.UNKNOWN
            }
            //val line = "Detected ${detectedActivity.name}  confidence=${act?.confidence ?: 100}"
            //Log.i(TAG, line)
            //writeToLogFileLocation(logFile, line)

            // TODO: Need to look at confidence and do sthg if confidence is less than 50? Maybe store in a data class
            if (isRunning.get() && (act?.confidence ?: 0) >= 50) {
                val cepState = when (detectedActivity) {
                    ActivityRecognized.RUNNING          -> "RUNNING"
                    ActivityRecognized.WALKING,
                    ActivityRecognized.ON_FOOT          -> "WALKING"
                    ActivityRecognized.STILL            -> "STILL"
                    ActivityRecognized.BICYCLING        -> "CYCLING"
                    ActivityRecognized.IN_VEHICLE       -> "DRIVING"
                    ActivityRecognized.TILTING,
                    ActivityRecognized.UNKNOWN          -> "UNKNOWN"
                }

                //Log.i(TAG, "CEP Activity: $cepState")
                ioScope.launch {
                    if (!isRunning.get() || !recognitionEnabled() ||
                        ContextCompat.checkSelfPermission(this@ActivityRecognitionService,
                            Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return@launch
                    // Emit to CEP inside coroutine scope
                    val monitoring = repo.getEnablePhysiologicalServices.first()
                    if (monitoring) CepBus.emit(
                        CepEvent(
                            topic = Topics.ACTIVITY,
                            payload = ActivityPayload(state = cepState, confidence = act?.confidence ?: 0)
                        )
                    )
                    if (monitoring) CepBus.emit(
                        CepEvent(
                            topic = Topics.SLEEP,
                            payload = SleepPayload(is_sleeping = false)
                        )
                    )

                    // Persist in repo (backend-only)
                    //RestingHrManager.onActivityRecognized(detectedActivity.name)
                    repo.storeLatestActivityRecognized(detectedActivity)
                    repo.storeLatestActivityConfidence(act?.confidence ?: 100)
                }
            }
            return START_NOT_STICKY
        }

        return START_STICKY          // keep service alive
    }

    override fun onDestroy() {
        stopForegroundServiceCompat()
        super.onDestroy()
        Log.i(TAG, "ActivityRecognitionService onDestroy()")

        if (!isRunning.getAndSet(false)) return
        passiveMonitoringEnabled = false
        scope.cancel()
        ioScope.cancel()
        repo.resetActivityRecognition()
        clearPassiveMonitoring()
        if (!::activityRecognitionClient.isInitialized || !::callbackIntent.isInitialized) return
        DiagnosticResources.note(DiagnosticResource.ACTIVITY, callbackIntent, ResourceAction.LOCAL_DISABLED)
        try {
            DiagnosticResources.request(DiagnosticResource.ACTIVITY, callbackIntent, removal = true) {
                activityRecognitionClient.removeActivityUpdates(callbackIntent)
            }.addOnSuccessListener {
                DiagnosticResources.end(DiagnosticResource.ACTIVITY, callbackIntent)
                callbackIntent.cancel()
            }.addOnFailureListener {
                DiagnosticResources.note(DiagnosticResource.ACTIVITY, callbackIntent, ResourceAction.UNCERTAIN)
                callbackIntent.cancel()
                Log.e(TAG, "Failed to remove activity updates; callback intent cancelled", it)
            }
        } catch (denied: SecurityException) {
            DiagnosticResources.note(DiagnosticResource.ACTIVITY, callbackIntent, ResourceAction.UNCERTAIN)
            callbackIntent.cancel()
            Log.w(TAG, "Activity permission revoked during removal; callback intent cancelled", denied)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val passiveDailyListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            if (!canAcceptPassiveData()) return
            // Read the optional points
            // Each call may contain 0-many data points for each requested type
            dataPoints.getData(DataType.STEPS_DAILY).firstOrNull()?.value?.toLong()?.let { cumulativeSteps = it }
            dataPoints.getData(DataType.FLOORS_DAILY).firstOrNull()?.value?.toDouble()?.let { cumulativeFloors = it }
            dataPoints.getData(DataType.ELEVATION_GAIN_DAILY).firstOrNull()?.value?.toDouble()?.let { cumulativeElevation = it }

            // DEBUG LOG: shows the cached snapshot
            val logStr = "passiveDailyListenerCallback: Steps: $cumulativeSteps, Floors: $cumulativeFloors, Elevation: $cumulativeElevation m"
            //Log.i(TAG, logStr)
            //writeToLogFileLocation(logFile, logStr)

            // Push snapshot to repository
            scope.launch {
                if (!canAcceptPassiveData() || !repo.getEnablePhysiologicalServices.first() ||
                    !repo.getEnableActRecService.first()) return@launch
                val nowHour = Instant.now().truncatedTo(ChronoUnit.HOURS)
                //Log.d(TAG, "passiveDailyListenerCallback: nowHour: $nowHour; Steps: $cumulativeSteps, Floors: $cumulativeFloors, Elevation: $cumulativeElevation m")
                repo.updateDailyMetrics(
                    timestampHour = nowHour,
                    steps         = cumulativeSteps,
                    floors        = cumulativeFloors,
                    elevationM    = cumulativeElevation
                )
            }
        }

        override fun onHealthEventReceived(event: HealthEvent) {
            if (!canAcceptPassiveData()) return
            Log.d(TAG, "HealthEvent: ${event.type} at ${event.eventTime}")
            if (event.type == HealthEvent.Type.FALL_DETECTED) {
                Log.w(TAG, "FALL_DETECTED @ ${event.eventTime}")

                // Post Fall Emergency Event
                scope.launch {
                    if (!canAcceptPassiveData() || !repo.getEnablePhysiologicalServices.first() ||
                        !repo.getEnableActRecService.first()) return@launch
                    // TODO: There is no CLEAR event & do we want a separate flow for fall detect & we are not addPhysioAlertToFront
                    repo.addPhysioAndAutoGenAlertRequest(UUID.randomUUID().toString(), CAT_FALL_DETECTED, DESC_GOOGLE_SDK_FALL_DETECTED, 1, true, System.currentTimeMillis() )
                }
            }
            super.onHealthEventReceived(event)
        }
    }
}
