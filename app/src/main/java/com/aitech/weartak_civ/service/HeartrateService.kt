/*
 * Copyright (C) 2024, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.CAT_PHYSIOLOGICAL_ALERT
import com.aitech.weartak_civ.DESC_HIGH_HEART_RATE_ALERT
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.HR_ALERT_THRESHOLDS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.requestPhysioStatusAndAlertingComplicationUpdate
import com.google.common.collect.EvictingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class HeartrateService: Service() {
    // TODO: Change to https://developer.android.com/reference/android/hardware/Sensor
    private lateinit var sensorManager: SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heartRateSensor: Sensor? = null
    var permission: Boolean = false
    private var monitoringEnabled = false
    private var sensorRegistered = false
    private var active = false
    private var registrationStartedAtNanos = 0L
    private var registeredLatencyUs: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var samsungSource: SamsungHeartRateSource? = null
    private var observedSamsungSource: SamsungHeartRateSource? = null
    private var failedSamsungSource: SamsungHeartRateSource? = null
    @Volatile private var sourceGeneration = 0
    @Volatile private var sampleSession = 0
    private var awaitingAndroidFlush = false
    private var androidFlushed = false
    private var latestMeasuredAtMillis = 0L
    private var lastComplicationUpdateMillis = 0L

    private var offWristHrClearJob: Job? = null

    private var userAge: Int? = null
    private var restingHeartRate: Int? = null

    private val sharedRepository: DataRepository
        get() = MainActivity.dataRepository

    private fun observeMetrics() {
        sharedRepository.physiologicalMetricAge
            .combine(sharedRepository.restingHeartRate) { age, resting ->
                age to resting
            }
            .filter { (age, resting) -> age != null && resting != null }
            .onEach { (age, resting) ->
                userAge = age!!
                restingHeartRate = resting!!
            }
            .launchIn(scope)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HeartrateService onCreate()")
        isRunning.set(true)
        active = true
        startForegroundServiceCompat(
            notificationId = HEARTRATE_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        observeMetrics()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        scope.launch {
            combine(HeartRateDelivery.samsungSource, HeartRateDelivery.live) { source, live ->
                source to live
            }.collect { reconcileSensors() }
        }
        scope.launch {
            combine(sharedRepository.getEnablePhysiologicalServices,
                sharedRepository.getEnableHeartRateService,
                sharedRepository.latestIsDeviceWorn) { monitoring, heartRate, worn ->
                Triple(monitoring, heartRate, worn)
            }.collect { (monitoring, heartRate, worn) ->
                monitoringEnabled = monitoring && heartRate
                reconcileSensors()
                if (!worn) {
                    hr = -1
                    hrStringLiveData.postValue("N/A")
                    sharedRepository.storeLatestHeartRate(-1)
                    requestPhysioStatusAndAlertingComplicationUpdate(applicationContext)
                }
            }
        }
        scope.launch {
            while (active) {
                delay(5_000L)
                reconcileSensors()
            }
        }
        scope.launch {
            sharedRepository.latestExertion.collect { Log.d(TAG, "Exertion updated: $it") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HeartrateService onStartCommand()")
        reconcileSensors()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        //RestingHrManager.stop()
        //cancelOffWristHrClearJob()
        active = false
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "HeartrateService onDestroy()")
        isRunning.set(false)
        stopSensors()
        stopForegroundServiceCompat()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun reconcileSensors() {
        val worn = sharedRepository.latestIsDeviceWorn.value
        permission = checkPermissions()
        if (!active || !monitoringEnabled || !worn || !permission) {
            stopSensors()
            return
        }
        val available = HeartRateDelivery.samsungSource.value
        if (available !== observedSamsungSource) {
            observedSamsungSource = available
            failedSamsungSource = null
        }
        if (samsungSource != null &&
            (samsungSource !== available || samsungSource === failedSamsungSource) &&
            !stopSamsungSource()) return
        if (samsungSource != null) return
        if (available != null && available !== failedSamsungSource) {
            if (!drainAndroidSensor()) return
            stopAndroidSensor()
            val generation = ++sourceGeneration
            samsungSource = available
            val started = available.start(
                onSample = { sample ->
                    val session = sampleSession
                    if (generation == sourceGeneration && samsungSource === available) {
                        mainHandler.post {
                            // Keep samples already received before a source handover, not late callbacks.
                            if (session == sampleSession) acceptSample(sample)
                        }
                    }
                },
                onFailure = {
                    mainHandler.post {
                        if (generation == sourceGeneration && samsungSource === available) {
                            failedSamsungSource = available
                            reconcileSensors()
                        }
                    }
                }
            )
            if (started) return
            failedSamsungSource = available
            if (!stopSamsungSource()) return
        }
        startSensors()
    }

    private fun cancelOffWristHrClearJob() {
        offWristHrClearJob?.cancel()
        offWristHrClearJob = null
    }

    private fun scheduleOffWristHrAlertClear() {
        cancelOffWristHrClearJob()
        offWristHrClearJob = scope.launch {
            delay(OFF_WRIST_HR_ALERT_CLEAR_DELAY_MS)
            if (sharedRepository.latestIsDeviceWorn.value) return@launch
            clearActiveHrOriginAlerts()
        }
    }

    private suspend fun clearActiveHrOriginAlerts() {
        val activeHrAlerts = sharedRepository.physioAndAutoGenAlertList.value.filter {
            it.alertState == DataRepository.AlertState.ALERT && HR_ALERT_CATEGORIES.contains(it.category)
        }

        if (activeHrAlerts.isEmpty()) return

        Log.i(TAG, "Clearing ${activeHrAlerts.size} active HR alert(s) after sustained off-wrist")
        activeHrAlerts.forEach { alert ->
            sharedRepository.clearPhysioAndAutoGenAlertRequestUid(alert.uuid)
        }
    }

    private fun startSensors() {
        val latencyUs = if (HeartRateDelivery.live.value) 0 else MAX_REPORT_LATENCY_US
        if (sensorRegistered && registeredLatencyUs == latencyUs) return
        val newSession = !sensorRegistered
        if (sensorRegistered) {
            if (!drainAndroidSensor()) return
            // Android rejects duplicate registration; drain before changing latency.
            stopAndroidSensor()
        }
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (null != heartRateSensor) {
            try {
                if (newSession) registrationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                val registered = DiagnosticResources.acquire(DiagnosticResource.HEART_RATE, sensorEventListener,
                    periodUs = SAMPLING_PERIOD_US.toLong(), successful = { it }) {
                    sensorManager.registerListener(sensorEventListener, heartRateSensor, SAMPLING_PERIOD_US, latencyUs)
                }
                if (registered) {
                    sensorRegistered = true
                    registeredLatencyUs = latencyUs
                }
                AppDiagnostics.record(DiagnosticEvent.SENSOR, mapOf(
                    DiagnosticMetric.KIND to 1L, DiagnosticMetric.ACTIVE to if (sensorRegistered) 1L else 0L))
                if (sensorRegistered) Log.i(TAG, "HeartrateService heartRateSensor callback registered")
                else Log.w(TAG, "Heart-rate listener registration refused; will reconcile while permitted")
            } catch (error: SecurityException) {
                sensorRegistered = false
                Log.w(TAG, "Heart-rate permission revoked during registration", error)
                AppDiagnostics.record(DiagnosticEvent.SENSOR, mapOf(
                    DiagnosticMetric.KIND to 1L, DiagnosticMetric.RESULT to -1L))
            }
        } else {
            Log.e(TAG, "HeartrateService heartRateSensor is null. CANNOT read heartrate")
            AppDiagnostics.record(DiagnosticEvent.SENSOR, mapOf(
                DiagnosticMetric.KIND to 1L, DiagnosticMetric.RESULT to 0L))
        }
    }

    private fun stopSensors() {
        ++sampleSession
        stopSamsungSource()
        stopAndroidSensor()
    }

    private fun drainAndroidSensor(): Boolean {
        if (!sensorRegistered || androidFlushed) return true
        if (awaitingAndroidFlush) return false
        awaitingAndroidFlush = true
        try {
            if (sensorManager.flush(sensorEventListener)) return false
        } catch (error: SecurityException) {
            permission = false
            Log.w(TAG, "Heart-rate permission revoked during FIFO flush", error)
            stopAndroidSensor()
            return false
        }
        awaitingAndroidFlush = false
        Log.w(TAG, "HR FIFO flush unsupported; batching cannot be guaranteed on this sensor")
        return true
    }

    private fun stopSamsungSource(): Boolean {
        ++sourceGeneration
        if (samsungSource?.stop() == false) {
            failedSamsungSource = samsungSource
            Log.e(TAG, "Cannot detach Samsung HR; refusing to start a second HR source")
            return false
        }
        samsungSource = null
        return true
    }

    private fun stopAndroidSensor() {
        awaitingAndroidFlush = false
        androidFlushed = false
        if (!sensorRegistered) return
        Log.d(TAG, "HeartrateService stopSensors(): active=$active monitoring=$monitoringEnabled worn=${sharedRepository.latestIsDeviceWorn.value} permission=$permission")
        DiagnosticResources.release(DiagnosticResource.HEART_RATE, sensorEventListener) {
            sensorManager.unregisterListener(sensorEventListener)
        }
        sensorRegistered = false
        registeredLatencyUs = null
        AppDiagnostics.record(DiagnosticEvent.SENSOR, mapOf(
            DiagnosticMetric.KIND to 1L, DiagnosticMetric.ACTIVE to 0L))
    }

    private fun acceptSample(sample: HeartRateSample) {
        if (!active || !monitoringEnabled || !checkPermissions() ||
            !sharedRepository.latestIsDeviceWorn.value) return
        val now = System.currentTimeMillis()
        if (sample.bpm <= 0 || sample.measuredAtMillis <= 0 || sample.measuredAtMillis > now) {
            Log.w(TAG, "Ignoring invalid HR sample: $sample")
            return
        }
        val newest = sample.measuredAtMillis >= latestMeasuredAtMillis
        if (newest) {
            latestMeasuredAtMillis = sample.measuredAtMillis
            hr = sample.bpm
            hrStringLiveData.value = sample.bpm.toString()
            sharedRepository.recordHeartRateSample(sample.bpm, sample.measuredAtMillis)
        }
        scope.launch {
            CepBus.emit(CepEvent(
                topic = Topics.HEART_RATE,
                payload = HeartRatePayload(bpm = sample.bpm.toDouble(), ts = sample.measuredAtMillis),
                ts = sample.measuredAtMillis
            ))
            if (sample.measuredAtMillis >= latestMeasuredAtMillis) {
                sharedRepository.storeLatestHeartRate(sample.bpm, sample.measuredAtMillis)
            }
        }
        if (lastComplicationUpdateMillis == 0L || now - lastComplicationUpdateMillis >= 30_000L) {
            lastComplicationUpdateMillis = now
            requestPhysioStatusAndAlertingComplicationUpdate(applicationContext)
        }
    }

    private val sensorEventListener = object : SensorEventListener2 {
        var accuracyVal = -1

        override fun onFlushCompleted(sensor: Sensor?) {
            if (!awaitingAndroidFlush) return
            mainHandler.post {
                if (!awaitingAndroidFlush) return@post
                awaitingAndroidFlush = false
                androidFlushed = true
                reconcileSensors()
                androidFlushed = false
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "onAccuracyChanged called w/ accuracy: $accuracy")
            accuracyVal = accuracy
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!active || !sensorRegistered || !monitoringEnabled ||
                !sharedRepository.latestIsDeviceWorn.value) return
            val elapsedNanos = SystemClock.elapsedRealtimeNanos()
            if (event.timestamp < registrationStartedAtNanos || event.timestamp > elapsedNanos) return
            if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
                val bpm = event.values.firstOrNull() ?: return
                if (accuracyVal != SensorManager.SENSOR_STATUS_ACCURACY_HIGH || !bpm.isFinite()) return
                val measuredAt = System.currentTimeMillis() - (elapsedNanos - event.timestamp) / 1_000_000L
                acceptSample(HeartRateSample(bpm.toInt(), measuredAt))
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, "android.permission.BODY_SENSORS") == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val TAG = "WearTAK-" + HeartrateService::class.java.simpleName
        private const val HEARTRATE_SERVICE_NOTIFICATION_ID = 105
        private const val OFF_WRIST_HR_ALERT_CLEAR_DELAY_MS = 10_000L
        private const val SAMPLING_PERIOD_US = 1_000_000   // Every 1 seconds; NOTE: Could also be SensorManager.SENSOR_DELAY_NORMAL as it does not seem to matter. The HR sensor is polled every 1 second.
        internal const val MAX_REPORT_LATENCY_US = 30_000_000
        private val HR_ALERT_CATEGORIES = setOf(
            "CAT_HR_LOW",
            "CAT_HR_REST_BRADY",
            "CAT_HR_HIGH",
            "CAT_HR_ALERT",
            "CAT_HR_EMERGENCY"
        )
        var hr: Int = -1
        val hrStringLiveData = MutableLiveData("N/A")
        val isRunning = AtomicBoolean(false)
    }
}
