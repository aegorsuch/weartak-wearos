/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.DEFAULT_ON_FOOT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_CONSTANT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_STATIONARY_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_VEHICLE_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.atomic.AtomicBoolean


class ReportingIntervalStrategyService : Service() {
    private val TAG = "WearTAK-" + ReportingIntervalStrategyService::class.java.simpleName
    private lateinit var dataRepository: DataRepository
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var enableDynamicReportingStrategy = false
    private var constantReportingInterval = DEFAULT_CONSTANT_REPORTING_INTERVAL
    private var whileAlertingReportingInterval = DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
    private var onFootReportingInterval = DEFAULT_ON_FOOT_REPORTING_INTERVAL
    private var vehicleReportingInterval = DEFAULT_VEHICLE_REPORTING_INTERVAL
    private var stationaryReportingInterval = DEFAULT_STATIONARY_REPORTING_INTERVAL
    private var dynamicReportingInterval = DEFAULT_CONSTANT_REPORTING_INTERVAL
    private var reportingInterval = DEFAULT_CONSTANT_REPORTING_INTERVAL
    private var isAlerting = false
    private var latestSpeedMps: Double? = null
    private var latestActivity = ActivityRecognized.UNKNOWN
    private var diagnosticSpeedUsable: Boolean? = null
    private val policyDiagnostics = ReportingPolicyDiagnostics()
    private val stationaryLocationPolicy = StationaryLocationPolicy()
    private val interactive = MutableStateFlow(true)
    private val navigating = MutableStateFlow(false)
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateInteractive()
    }

    private fun updateInteractive() {
        val current = getSystemService(PowerManager::class.java).isInteractive
        if (current && !interactive.value) dataRepository.noteLocationMotion()
        interactive.value = current
    }

    private fun startIdleContextCollector() {
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        updateInteractive()
        serviceScope.launch {
            dataRepository.latestDeviceWornObservation.collect { worn ->
                if (worn == true) dataRepository.noteLocationMotion()
            }
        }
        val bloodhounds = (application as com.aitech.weartak_civ.WearTakApplication).bhRepo
        serviceScope.launch {
            combine(bloodhounds.activeUid, bloodhounds.routeFollow) { target, route ->
                target != null || route != null
            }.collect { navigating.value = it }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ReportingIntervalStrategyService onCreate()")
        isRunning.getAndSet(true)
        startForegroundServiceCompat(REPORTING_INTERVAL_STRATEGY_SERVICE_NOTIFICATION_ID, importance = NotificationManager.IMPORTANCE_LOW)
        dataRepository = MainActivity.dataRepository  // single instance of data repository shared by all instances
        startIdleContextCollector()
        initializeVariables()
        startDiagnosticsCollector()
        startEnableDynamicStrategyCollector()
        startDynamicReportingIntervalCollector()
        startConstantReportingIntervalCollector()
        startIsAlertingCollector()
        startSpeedCollector()
        startActivityCollector()
        startMotionCollector()
        startStationaryLocationCollector()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "ReportingIntervalStrategyService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ReportingIntervalStrategyService onDestroy()")
        isRunning.set(false)
        stopForegroundServiceCompat()
        // TODO: should we serviceJob.cancel()
        serviceScope.cancel()
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        CotDispatchService.stationaryLocation = false
    }

    private fun initializeVariables() {
        runBlocking {
            enableDynamicReportingStrategy = dataRepository.getEnableDynamicReportingStrategy.first()
            constantReportingInterval = dataRepository.getConstantReportingInterval.first()
            whileAlertingReportingInterval = dataRepository.getWhileAlertingReportingInterval.first()
            onFootReportingInterval = dataRepository.getOnFootReportingInterval.first()
            vehicleReportingInterval = dataRepository.getVehicleReportingInterval.first()
            stationaryReportingInterval = dataRepository.getStationaryReportingInterval.first()
            isAlerting = dataRepository.isAlerting.first()
            latestActivity = if (dataRepository.getEnableActRecService.first() &&
                dataRepository.getEnableLocationService.first()) dataRepository.latestActivityRecognized.value
                else ActivityRecognized.UNKNOWN

            val activity = currentActivity()
            dynamicReportingInterval = calcDynamicReportingInterval(isAlerting, policySpeed(),
                whileAlertingReportingInterval, onFootReportingInterval, vehicleReportingInterval,
                stationaryReportingInterval, activity)
            reportingInterval = if (enableDynamicReportingStrategy) { dynamicReportingInterval } else { constantReportingInterval }
            CotDispatchService.desiredLocationObservationIntervalSecs =
                desiredObservationInterval(activity)
            recordPolicySelection(activity, CotDispatchService.desiredLocationObservationIntervalSecs)
            dataRepository.storeReportingUpdateInterval(reportingInterval)
            Log.d(TAG, "initializeVariables enableDynamicReportingStrategy($enableDynamicReportingStrategy): " +
                    "reportingInterval=$reportingInterval w/ constantReportingInterval=$constantReportingInterval, dynamicReportingInterval=$dynamicReportingInterval " +
                    "(speedMps=$latestSpeedMps, isAlerting=$isAlerting, alerting=$whileAlertingReportingInterval, onFoot=$onFootReportingInterval, vehicle=$vehicleReportingInterval, stationary=$stationaryReportingInterval)")
            if (reportingInterval != CotDispatchService.desiredReportingIntervalSecs) {
                Log.d(TAG, "initializeVariables reportingInterval($reportingInterval) != CotDispatchService.reportingUpdateIntervalSecs(${CotDispatchService.desiredReportingIntervalSecs}). Sending UPDATE_INTERVAL_CHANGED")
                CotDispatchService.desiredReportingIntervalSecs = reportingInterval
                EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, reportingInterval))
            }
        }
    }

    private suspend fun calcReportingIntervalAndSendUpdate() {
        val previousInterval = reportingInterval
        reportingInterval = if (enableDynamicReportingStrategy) { dynamicReportingInterval } else { constantReportingInterval }
        val activity = currentActivity()
        val observationInterval = desiredObservationInterval(activity)
        val observationChanged = observationInterval != CotDispatchService.desiredLocationObservationIntervalSecs
        CotDispatchService.desiredLocationObservationIntervalSecs = observationInterval
        recordPolicySelection(activity, observationInterval)
        if (reportingInterval != previousInterval) dataRepository.storeReportingUpdateInterval(reportingInterval)
        //Log.d(TAG, "calcReportingIntervalAndSendUpdate enableDynamicReportingStrategy($enableDynamicReportingStrategy): " +
        //            "reportingInterval=$reportingInterval w/ constantReportingInterval=$constantReportingInterval, dynamicReportingInterval=$dynamicReportingInterval " +
        if (reportingInterval != CotDispatchService.desiredReportingIntervalSecs || observationChanged) {
            //Log.d(TAG, "calcReportingIntervalAndSendUpdate reportingInterval($reportingInterval) != CotDispatchService.reportingUpdateIntervalSecs(${CotDispatchService.reportingUpdateIntervalSecs}). Sending UPDATE_INTERVAL_CHANGED")
            CotDispatchService.desiredReportingIntervalSecs = reportingInterval
            EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, reportingInterval))
            // Activity can change acquisition while valid speed keeps the reporting rate unchanged.
            if (observationChanged && enableDynamicReportingStrategy)
                EventBus.getDefault().post(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
        }
    }

    private fun recordPolicySelection(activity: ActivityRecognized, observationInterval: Int) {
        policyDiagnostics.update(enableDynamicReportingStrategy, isAlerting, policySpeed(),
            activity, reportingInterval, observationInterval,
            recording = AppDiagnostics.recordingEnabled?.value == true,
            motion = if (enableDynamicReportingStrategy && !isAlerting)
                dataRepository.stationaryMotion.state.value.takeIf {
                    it.stationary || it.movingUntilNanos > SystemClock.elapsedRealtimeNanos()
                } else null)?.let {
            AppDiagnostics.record(DiagnosticEvent.REPORT_POLICY, it)
        }
    }

    private fun startDiagnosticsCollector() {
        val recording = AppDiagnostics.recordingEnabled ?: return
        serviceScope.launch {
            recording.collect {
                recordPolicySelection(currentActivity(), CotDispatchService.desiredLocationObservationIntervalSecs)
            }
        }
    }

    private suspend fun calcDynamicReportingIntervalAndSendUpdate() {
        val dynamicRI = calcDynamicReportingInterval(isAlerting, policySpeed(),
            whileAlertingReportingInterval, onFootReportingInterval, vehicleReportingInterval,
            stationaryReportingInterval, currentActivity())
        if (dynamicRI != dynamicReportingInterval) {
            Log.d(TAG, "dynamicReportingInterval changed from $dynamicReportingInterval to $dynamicRI")
            dynamicReportingInterval = dynamicRI
        }
        calcReportingIntervalAndSendUpdate()
    }

    private fun startEnableDynamicStrategyCollector() {
        serviceScope.launch {
            dataRepository.enableDynamicReportingStrategy.collect { dynRS ->
                if ((null != dynRS) && (enableDynamicReportingStrategy != dynRS)) {
                    Log.d(TAG, "startEnableDynamicStrategyCollector: enableDynamicReportingStrategy changed from $enableDynamicReportingStrategy to $dynRS")
                    enableDynamicReportingStrategy = dynRS
                    calcReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    private fun startConstantReportingIntervalCollector() {
        serviceScope.launch {
            dataRepository.constantReportingInterval.collect { constantRI ->
                if ((null != constantRI) && (constantReportingInterval != constantRI)) {
                    Log.d(TAG, "constantReportingInterval changed from $constantReportingInterval to $constantRI")
                    constantReportingInterval = constantRI
                    calcReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    private fun startDynamicReportingIntervalCollector() {
        serviceScope.launch {
            dataRepository.whileAlertingReportingInterval.collect { alertingRI ->
                if ((null != alertingRI) && (whileAlertingReportingInterval != alertingRI)) {
                    Log.d(TAG, "whileAlertingReportingInterval changed from $whileAlertingReportingInterval to $alertingRI")
                    whileAlertingReportingInterval = alertingRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
        serviceScope.launch {
            dataRepository.onFootReportingInterval.collect { onFootRI ->
                if ((null != onFootRI) && (onFootReportingInterval != onFootRI)) {
                    Log.d(TAG, "onFootReportingInterval changed from $onFootReportingInterval to $onFootRI")
                    onFootReportingInterval = onFootRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
        serviceScope.launch {
            dataRepository.vehicleReportingInterval.collect { vehicleRI ->
                if ((null != vehicleRI) && (vehicleReportingInterval != vehicleRI)) {
                    Log.d(TAG, "vehicleReportingInterval changed from $vehicleReportingInterval to $vehicleRI")
                    vehicleReportingInterval = vehicleRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
        serviceScope.launch {
            dataRepository.stationaryReportingInterval.collect { stationaryRI ->
                if ((null != stationaryRI) && (stationaryReportingInterval != stationaryRI)) {
                    Log.d(TAG, "stationaryReportingInterval changed from $stationaryReportingInterval to $stationaryRI")
                    stationaryReportingInterval = stationaryRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    private fun startSpeedCollector() {
        serviceScope.launch {
            combine(LocationService.speedObservation, dataRepository.getLocationInput,
                dataRepository.getEnableLocationService, dataRepository.locationInputStatus
            ) { observation, _, _, _ -> observation }.collectLatest { observation ->
                latestSpeedMps = currentReportingSpeed(dataRepository)
                recordSpeedValidity()
                calcDynamicReportingIntervalAndSendUpdate()
                if (latestSpeedMps != null && observation != null) {
                    val remainingMillis = (observation.validUntilElapsedNanos -
                        SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                    // A one-shot expiry does not keep polling or acquire another fix.
                    delay(remainingMillis.coerceAtLeast(0) + 1)
                    latestSpeedMps = null
                    recordSpeedValidity()
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }

        }
    }

    private fun currentActivity(): ActivityRecognized {
        val recognized = if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED) latestActivity else ActivityRecognized.UNKNOWN
        if (LocationAcquisitionPolicy.moving(recognized)) return recognized
        if (recentMotion()) return ActivityRecognized.UNKNOWN
        return if (enableDynamicReportingStrategy && !isAlerting)
            dataRepository.stationaryMotion.activity(recognized, SystemClock.elapsedRealtimeNanos()) else recognized
    }

    private fun policySpeed(): Double? =
        if (recentMotion()) null
        else if (LocationAcquisitionPolicy.moving(latestActivity))
            latestSpeedMps?.takeIf { it >= SpeedReportingPolicy.STATIONARY_SPEED_MPS }
        else if (enableDynamicReportingStrategy && !isAlerting)
            dataRepository.stationaryMotion.speed(latestSpeedMps, SystemClock.elapsedRealtimeNanos())
        else latestSpeedMps

    private fun startMotionCollector() {
        serviceScope.launch {
            combine(dataRepository.stationaryMotion.state, dataRepository.locationMotionNanos) {
                state, _ -> state
            }.collectLatest { state ->
                calcDynamicReportingIntervalAndSendUpdate()
                if (state.movingUntilNanos > 0) {
                    val remaining = (state.movingUntilNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                    delay(remaining.coerceAtLeast(0) + 1)
                    dataRepository.stationaryMotion.expire(state, SystemClock.elapsedRealtimeNanos())
                }
            }
        }
    }

    private fun recentMotion(): Boolean = dataRepository.locationMotionNanos.value.let {
        it > 0 && SystemClock.elapsedRealtimeNanos() - it < StationaryLocationPolicy.DWELL_NANOS
    }

    private fun startStationaryLocationCollector() {
        serviceScope.launch {
            val activity = combine(dataRepository.latestActivityRecognized,
                dataRepository.latestActivityConfidence, dataRepository.getEnableActRecService) {
                recognized, confidence, enabled ->
                (if (enabled) recognized else ActivityRecognized.UNKNOWN) to confidence
            }
            val availability = combine(dataRepository.getEnableLocationService,
                dataRepository.isAlerting, dataRepository.getLocationInput) { enabled, alerting, input ->
                Triple(enabled, alerting, input)
            }
            val idleContext = combine(interactive, dataRepository.latestDeviceWornObservation, navigating) {
                screen, worn, navigation -> Triple(screen, worn, navigation)
            }
            combine(activity, availability, dataRepository.stationaryMotion.state,
                dataRepository.locationMotionNanos, idleContext) { recognized, available, motion, lastMotion, idle ->
                { now: Long ->
                    val activityPermitted = ContextCompat.checkSelfPermission(this@ReportingIntervalStrategyService,
                        Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                    stationaryLocationPolicy.observe(now, motion.stationary,
                        if (activityPermitted) recognized.first else ActivityRecognized.UNKNOWN,
                        recognized.second, available.first &&
                            (ContextCompat.checkSelfPermission(this@ReportingIntervalStrategyService,
                                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                (available.third != LocationInput.INTERNAL_GNSS &&
                                    ContextCompat.checkSelfPermission(this@ReportingIntervalStrategyService,
                                        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)),
                        available.second, lastMotion, idle.first, idle.second, idle.third)
                }
            }.collectLatest { evaluate ->
                do {
                    CotDispatchService.stationaryIntervalSeconds =
                        if (evaluate(SystemClock.elapsedRealtimeNanos()))
                            stationaryLocationPolicy.minimumIntervalSeconds else 0
                    val next = stationaryLocationPolicy.nextEvaluationNanos ?: break
                    delay(((next - SystemClock.elapsedRealtimeNanos()) / 1_000_000L).coerceAtLeast(0) + 1)
                } while (true)
            }
        }
    }

    private fun desiredObservationInterval(activity: ActivityRecognized): Int =
        if (!enableDynamicReportingStrategy) constantReportingInterval
        else SpeedReportingPolicy.activityIntervalSeconds(activity, isAlerting,
            whileAlertingReportingInterval, onFootReportingInterval, vehicleReportingInterval,
            stationaryReportingInterval)

    private fun startActivityCollector() {
        serviceScope.launch {
            combine(dataRepository.latestActivityRecognized, dataRepository.getEnableActRecService,
                dataRepository.getEnableLocationService
            ) { activity, activityEnabled, locationEnabled ->
                if (activityEnabled && locationEnabled) activity else ActivityRecognized.UNKNOWN
            }.collect { activity ->
                latestActivity = activity
                calcDynamicReportingIntervalAndSendUpdate()
            }
        }
    }

    private fun recordSpeedValidity() {
        val usable = latestSpeedMps != null
        if (usable == diagnosticSpeedUsable) return
        diagnosticSpeedUsable = usable
        AppDiagnostics.record(DiagnosticEvent.SPEED_VALIDITY,
            mapOf(DiagnosticMetric.RESULT to if (usable) 1L else 0L))
    }

    private suspend fun currentReportingSpeed(repo: DataRepository): Double? {
        val observation = LocationService.speedObservation.value ?: return null
        val location = observation.location
        val enabled = repo.getEnableLocationService.first()
        val permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED || (location.provider != LocationInput.INTERNAL_GNSS.provider &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED)
        return SpeedReportingPolicy.validSpeed(
            location.speed.toDouble().takeIf { location.hasSpeed() },
            location.elapsedRealtimeNanos,
            SystemClock.elapsedRealtimeNanos(),
            enabled && permitted && repo.isCurrentLocationInput(location) &&
                repo.locationInputStatus.value in setOf(
                    LocationInputStatus.READY,
                    LocationInputStatus.ACQUIRING,
                ),
            observation.validUntilElapsedNanos,
        )
    }

    private fun startIsAlertingCollector() {
        serviceScope.launch {
            dataRepository.isAlerting.collect { alert ->
                if (isAlerting != alert) {
                    Log.d(TAG, "isAlerting changed from $isAlerting to $alert")
                    isAlerting = alert
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    companion object {
        val isRunning = AtomicBoolean(false)
        private const val REPORTING_INTERVAL_STRATEGY_SERVICE_NOTIFICATION_ID = 1123

        fun calcDynamicReportingInterval(alerting: Boolean, speedMps: Double?, alertingRI: Int, onFootRI: Int, vehicleRI: Int, stationaryRI: Int,
            activity: ActivityRecognized = ActivityRecognized.UNKNOWN): Int =
            SpeedReportingPolicy.intervalSeconds(speedMps, alerting, alertingRI, onFootRI, vehicleRI, stationaryRI, activity)
    }
}