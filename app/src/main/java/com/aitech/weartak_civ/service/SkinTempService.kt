/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.PpgPayload
import com.aitech.weartak_civ.cep.model.payloads.SkinTempPayload
import com.aitech.weartak_civ.cep.model.payloads.Spo2Payload
import com.aitech.weartak_civ.cep.model.payloads.HrvPayload
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.android.service.health.tracking.data.PpgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.aitech.weartak_civ.data.CrmDataRepository.CrmSamplingState
import com.aitech.weartak_civ.service.crm.CrmPpgPreprocessor
import com.aitech.weartak_civ.service.crm.CrmSessionCsvLogger
import kotlinx.coroutines.flow.first
import kotlin.math.pow

class SkinTempService : Service() {
    companion object {
        private const val TAG = "WearTAK-SkinTService"
        private const val NOTIF_ID = 177

        var tempF: Float = -1f
        val skinTempLiveData = MutableLiveData<String>("N/A")
        val isRunning = AtomicBoolean(false)
        var DISPATCH_INTERVAL_MS = 60 * 1_000L
        private const val MIN_SKIN_TEMP_INTERVAL_MS = 10_000L
        val LOG_FILENAME = "skin_temp.log"
        val context: Context = mainActivity.applicationContext
        const val ACTION_START_CRM_PPG =
            "com.aitech.weartak_civ.action.START_CRM_PPG"

        const val ACTION_STOP_CRM_PPG =
            "com.aitech.weartak_civ.action.STOP_CRM_PPG"

        val ppgChannelType = PpgType.GREEN
        val ppgHealthTrackerType = HealthTrackerType.PPG_ON_DEMAND
        val ppgSet = ValueKey.PpgSet.PPG_GREEN
        val ppgOnDemandFrequency = 100
    }

    @Volatile
    private var crmPpgSamplingRequested: Boolean = false

    @Volatile
    private var crmPpgSamplingActive: Boolean = false

    private val sharedRepo: DataRepository
        get() = MainActivity.dataRepository

    private val crmRepo: CrmDataRepository by lazy {
        try {
            (applicationContext as WearTakApplication).crmRepo
        } catch (_: Throwable) {
            CrmDataRepository(sharedRepo)
        }
    }
    private lateinit var healthService: HealthTrackingService
    private lateinit var skinTracker: HealthTracker
    private lateinit var spo2Tracker: HealthTracker
    private lateinit var hrTracker: HealthTracker
    private var hrSampleConsumer: ((HeartRateSample) -> Unit)? = null
    private var hrFailureConsumer: (() -> Unit)? = null
    private var hrListenerAttached = false
    @Volatile private var destroyed = false
    private val hrSource = object : SamsungHeartRateSource {
        override fun start(onSample: (HeartRateSample) -> Unit, onFailure: () -> Unit): Boolean {
            if (destroyed) return false
            hrSampleConsumer = onSample
            hrFailureConsumer = onFailure
            return startHrTracker()
        }

        override fun stop() = stopHrTracker()
    }

    private fun withdrawHrSource() {
        stopHrTracker()
        if (HeartRateDelivery.samsungSource.value === hrSource) {
            HeartRateDelivery.samsungSource.value = null
        }
    }
    // PPG capability model
    private var ppgTracker: HealthTracker? = null
    private var ppgTrackerType: HealthTrackerType? = null
    private var ppgAvailable: Boolean = false
    /*
     * Rationale for pausing PPG_CONTINUOUS during SpO2:
     * - On Samsung wearables, the optical stack can exhibit resource contention when a continuous PPG
     *   listener is active concurrently with an on‑demand SpO2 measurement. This often manifests as
     *   SpO2 remaining in interim states (e.g., 0/WAIT, -4, -6) and failing to reach STATUS==2 before timeout.
     * - To eliminate contention, we temporarily pause the continuous PPG listener immediately before
     *   starting the SpO2 measurement, and resume it as soon as SpO2 completes, errors, or times out.
     * - This preserves a stable rolling PPG amplitude for cold‑stress detection while restoring reliable
     *   SpO2 completion behavior.
     */
    @Volatile private var ppgPausedForSpo2: Boolean = false
    // Monotonic timestamp of last SpO2 attempt (attempt recorded when session is started)
    @Volatile private var lastSpo2AttemptElapsedMs: Long = 0L
    // Whether the last SpO2 session ended due to TIMEOUT (for additive backoff)
    @Volatile private var lastSpo2EndedAsTimeout: Boolean = false
    // Short on‑demand SpO2 session timeout and backoff
    /*
     * Session model alignment (Samsung SpO2 codelab):
     * - SpO2 is acquired as a short, on‑demand "measurement session": attach a listener, observe STATUS on
     *   each callback, only read SPO2 when STATUS==2 (MEASUREMENT_COMPLETED), then promptly stop/unset.
     * - Non‑terminal interim statuses (e.g., CALCULATING) should not prolong the session beyond a strict timeout.
     * - TIMEOUT is considered terminal by SHS; in our logs it manifests as STATUS=-6. We end the session
     *   immediately on this status and apply a longer backoff to prevent repeated churn under poor conditions.
     * - PPG continuous is paused for the duration of the SpO2 session to avoid optical contention,
     *   then resumed regardless of the exit path (complete/error/timeout).
     */
    private val SPO2_TIMEOUT_MS: Long = 30_000L
    private val SPO2_BACKOFF_AFTER_TIMEOUT_MS: Long = 5 * 60_000L
    // Strict 1-hour minimum gap between SpO2 attempts (monotonic)
    private val SPO2_MIN_GAP_MS: Long = 30_000L
    private val SPO2_PPG_RED_SUSTAIN_REQUIRED_MS: Long = 30_000L
    private val SPO2_EMIT_FRESHNESS_MS: Long = 35_000L
    // SHS "TIMEOUT" appears as status = -6
    private val SPO2_STATUS_TIMEOUT: Int = -6
    @Volatile private var lastActivityConfidence: Int = 0
    // Track last SpO2 outcome for backoff and diagnostics
    @Volatile private var lastSpo2OutcomeWasTimeout: Boolean = false
    @Volatile private var lastSpo2OutcomeWallMs: Long = 0L
    // Rolling 20s PPG window + amplitude cache
    private data class TimedSample(val tUptimeMs: Long, val v: Float)
    private val ppgWindow = ArrayDeque<TimedSample>(4096)
    private val PPG_WINDOW_MS = 20_000L
    @Volatile private var lastPpgSampleCount: Int = 0
    private var lastPpgComputeUptimeMs: Long = 0L
    private val PPG_COMPUTE_PERIOD_MS = 2_000L
    // PPG raw logging throttle
    @Volatile private var lastPpgRaw: Int? = null
    private var lastPpgRawLogElapsedMs: Long = 0L
    private val PPG_RAW_LOG_PERIOD_MS = 2_000L
    private var lastCrmPpgSourceLogElapsedMs: Long = 0L

    // CEP emitter loop
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var emitJob: Job? = null
    private val EMIT_INTERVAL_MS = 1000L

    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var isHandlerRunning = false
    private var deviceWorn: Boolean = false
    @Volatile private var isHrRunning = false
    private var hrGeneration = 0L

    // Running flags for trackers
    @Volatile private var spo2Running: Boolean = false
    @Volatile private var ppgRunning: Boolean = false

    // Cached latest values
    @Volatile private var lastSkinTempC: Float? = null
    @Volatile private var lastSpo2Pct: Double? = null
    @Volatile private var lastPpgAmp: Float? = null
    @Volatile private var lastPpgQuality: Float? = null
    @Volatile private var ppgRedFirstContinuouslyOkSinceMs: Long = 0L
    @Volatile private var ppgRedSustainedAtPauseMs: Long = 0L
    @Volatile private var ppgPauseStartedAtMs: Long = 0L
    @Volatile private var lastSpo2EmittedTs: Long = 0L
    @Volatile private var lastSpo2InvalidReason: String? = null
    @Volatile private var lastSpo2ResetReason: String? = null
    private lateinit var spo2Sequencer: Spo2Sequencer

    private var nextFireMs = 0L          // wall-clock target for the next run
    // One monotonic clock – the same one Handler uses
    private val nowUptime get() = SystemClock.uptimeMillis()

    private val crmCsvLogger: CrmSessionCsvLogger by lazy {
        (applicationContext as WearTakApplication).crmCsvLogger
    }

    // One function that always schedules the NEXT reading
    private fun scheduleNextReading() {
        if (!deviceWorn) return                     // skip if watch off-wrist
        val target = nowUptime + DISPATCH_INTERVAL_MS        // absolute uptime timestamp
        nextFireMs = target                         // purely for diagnostics
        backgroundHandler.postAtTime(::startSkinTracker, target)
//        writeToLogFileLocation(
//            logFile,
//            "Next reading scheduled for ${DISPATCH_INTERVAL_MS/1_000}s (at $target)"
//        )
    }

    private val logFile: File by lazy {
        File(filesDir, LOG_FILENAME).apply {
            if (!exists()) parentFile?.mkdirs()
        }
    }

    // SDK flush drains screen-off data; it does not configure hardware batching.
    private var screenOffFlushEnabled = false
    private val SCREEN_OFF_FLUSH_INTERVAL_MS = 30_000L
    private val FLUSH_START_DELAY_MS = 5_000L
    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val isInteractiveNow get() = powerManager.isInteractive

	// ---- HRV / RMSSD buffer + params (mirrors WEAR-383) ----
	private val nnBuffer = ArrayDeque<Int>(128)
	private val MIN_NN_FOR_RMSSD = 10
	private val MAX_NN_BUFFER = 60
	private val NN_MIN_MS = 300
	private val NN_MAX_MS = 2000
	@Volatile private var lastNonZeroRmssdMs: Double = 0.0
    private var lastRmssdElapsedMs = 0L
    private var lastRmssdWallMs = 0L
    private var lastHrvEmittedElapsedMs = 0L
    private val HRV_FRESHNESS_MS = 120_000L

    // ---- Strict measurement state machine for SpO2 → PPG ----
    private enum class MeasurementState { IDLE, SPO2_RUNNING, PPG_RUNNING }
    private val measureState = AtomicReference(MeasurementState.IDLE)
    private var spo2TimeoutRunnable: Runnable? = null
    private var ppgTimeoutRunnable: Runnable? = null
    @Volatile private var lastSpo2Ts: Long? = null
    @Volatile private var lastSpo2Status: Int? = null
    @Volatile private var lastPpgAmplitude: Double? = null
    @Volatile private var lastPpgTs: Long? = null

    fun deleteLogFile() {
        if (!logFile.exists()) {
            Log.i(TAG, "Log file does not exist")
            return
        }

        val deleted = logFile.delete()
        if (deleted) {
            Log.i(TAG, "Log file deleted")
        } else {
            Log.e(TAG, "Failed to delete log file")
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            if (destroyed) return
            skinTracker = healthService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            // Initialize on-demand SpO2 and PPG trackers as a hub
            try {
                spo2Tracker = healthService.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
                Log.i(TAG, "SpO2 tracker initialized (on-demand)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to init SpO2 tracker", t)
            }
            // Initialize HR continuous tracker
            if (BuildConfig.SAMSUNG_HRV_ENABLED) {
                try {
                    hrTracker = healthService.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                    Log.i(TAG, "HRV tracker initialized (HEART_RATE_CONTINUOUS)")
                    HeartRateDelivery.samsungSource.value = hrSource
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to init HRV tracker", error)
                }
            }
            // PPG capability detection with fallbacks
            initPpgCapability()
            // Emit inputs_ok flag for cold stress into CEP FeatureStore
            pushColdInputsOkFlag()
            Log.i(TAG, "Health SDK Connected")
            if (deviceWorn) {
//                startPpgContinuousIfSelected()
                // HeartrateService owns the HR tracker; this only applies its flush policy.
                if (!isInteractiveNow) {
                    enableScreenOffFlushLoop()
                    flushHr("initial_screen_off")
                } else {
                    disableScreenOffFlushLoop()
                }
                startSkinTracker()     // only kick off if worn
                scheduleNextReading()
                startEmitLoopIfNeeded()
            }

            if (crmPpgSamplingRequested) {
                startCrmPpgSampling()
            }

        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "SkinTempService onConnectionFailed(): HealthPlatform connection failed", e)
            if (e.hasResolution()) {
                try { e.resolve(null) } catch (ex: Exception) { Log.e(TAG, "SkinTempService Resolution failed", ex) }
            }
        }

        override fun onConnectionEnded() {
            noteSamsungCleanupUncertain()
            Log.w(TAG, "SkinTempService onConnectionEnded(): HealthPlatform DISCONNECTED")
            // Clean up when disconnected
            stopSkinTracker(true)
            stopSpo2Tracker()
            stopPpgTracker()
            withdrawHrSource()
            AppDiagnostics.updateSamsungHrContinuousRunning(false)
            stopEmitLoop()
        }
    }

    private fun noteSamsungCleanupUncertain() {
        if (::skinTracker.isInitialized) DiagnosticResources.note(DiagnosticResource.SAMSUNG_SKIN, skinTracker, ResourceAction.UNCERTAIN, onlyIfActive = true)
        if (::spo2Tracker.isInitialized) DiagnosticResources.note(DiagnosticResource.SAMSUNG_SPO2, spo2Tracker, ResourceAction.UNCERTAIN, onlyIfActive = true)
        if (::hrTracker.isInitialized) DiagnosticResources.note(DiagnosticResource.SAMSUNG_HR, hrTracker, ResourceAction.UNCERTAIN, onlyIfActive = true)
        ppgTracker?.let { DiagnosticResources.note(DiagnosticResource.SAMSUNG_PPG, it, ResourceAction.UNCERTAIN, onlyIfActive = true) }
    }

    private val trackerEventListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(dataPoints: List<DataPoint>) {
            // 1️⃣  Stop sensor immediately (but keep queue)
            stopSkinTracker(false)

            // 2️⃣  Collect temp data point  ──────────────────────────────
            dataPoints.firstOrNull()?.let { dp ->
                val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)

                if (status >= 0) {
                    val objectTemp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                    val ambientTemp = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                    lastSkinTempC = objectTemp
                    tempF = objectTemp.toFahrenheit()
                    //Log.i(TAG, "SkinTempService skinTemp: object=${"%.1f".format(tempF)}°F (${objectTemp}°C) ambient=${ambientTemp}°C")

                    val logLine =
                        "SkinTempService status=$status objectF=${tempF}°F objectC=${objectTemp}°C " +
                                "ambientF=${ambientTemp.toFahrenheit()}°F ambientC=${ambientTemp}°C"
                    //writeToLogFileLocation(logFile, logLine)
                } else {
                    tempF = -1f
                    skinTempLiveData.postValue("N/A")
                }

                val sampledTempF = tempF
                CoroutineScope(Dispatchers.IO).launch {
                    sharedRepo.storeLatestSkinTemp(sampledTempF, dp.timestamp)

                }
            }

            // Only attempt SpO2 when idle; startSpo2ThenPpgCycle() also applies rate-limit/backoff.
            if (deviceWorn && measureState.get() == MeasurementState.IDLE) {
                startSpo2ThenPpgCycle()
            }
            // 3️⃣  Schedule the next run *once*, with the helper
            scheduleNextReading()
        }

        override fun onFlushCompleted() {
            // No-op for on-demand service
        }

        fun Float.toFahrenheit(): Float = this * 9f/5f + 32f

        override fun onError(error: HealthTracker.TrackerError) {
            if (::skinTracker.isInitialized) DiagnosticResources.note(DiagnosticResource.SAMSUNG_SKIN, skinTracker, ResourceAction.UNCERTAIN)
            Log.e(TAG, "SkinTempService HealthTracker.TrackerError: $error")
        }

    }

    // ---- HEART RATE CONTINUOUS + IBI listener ----
    private var hrEventListener = createHrEventListener(0L)

    private fun createHrEventListener(generation: Long) = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            synchronized(nnBuffer) {
                if (!BuildConfig.SAMSUNG_HRV_ENABLED || !deviceWorn ||
                    !isHrRunning || generation != hrGeneration) return
                for (dp in dataPoints) {
                    try {
                        val measuredAt = dp.timestamp
                        val age = System.currentTimeMillis() - measuredAt
                        if (measuredAt <= 0L || age < 0L) {
                            Log.w(TAG, "Ignoring HRV point with invalid timestamp: $measuredAt")
                            continue
                        }
                        val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                        val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                        if (status == 1 && hr > 0) {
                            hrSampleConsumer?.invoke(HeartRateSample(hr, measuredAt))
                        }
                        val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                        val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                        if (!addValidNnIntervals(ibiList, ibiStatusList)) continue
                        val rmssd = computeRmssdMs()
                        if (rmssd != null && rmssd > 0.0 && rmssd.isFinite()) {
                            lastNonZeroRmssdMs = rmssd
                            lastRmssdElapsedMs = SystemClock.elapsedRealtime() - age
                            lastRmssdWallMs = measuredAt
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "HR parse error", error)
                    }
                }
            }
        }

        override fun onFlushCompleted() {
            //Log.i(TAG, "HR flush completed t=${SystemClock.elapsedRealtime()}")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            if (::hrTracker.isInitialized) DiagnosticResources.note(DiagnosticResource.SAMSUNG_HR, hrTracker, ResourceAction.UNCERTAIN)
            Log.e(TAG, "HR tracker error: $error")
            val failure = synchronized(nnBuffer) {
                if (generation != hrGeneration || !isHrRunning) return
                hrFailureConsumer
            }
            stopHrTracker()
            failure?.invoke()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: Intent) {
            val interactive = isInteractiveNow
            //Log.w(TAG, "SCREEN EVENT: ${intent.action} | interactive=$interactive | deviceWorn=$deviceWorn | hrRunning=$isHrRunning")
            if (!deviceWorn) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    enableScreenOffFlushLoop()
                    flushHr("screen_off_event")
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    disableScreenOffFlushLoop()
                }
            }
        }
    }

    private fun pausePpgContinuousForSpo2() {
        if (ppgTrackerType != HealthTrackerType.PPG_CONTINUOUS) return
        if (!ppgRunning) return
        if (ppgPausedForSpo2) return
        try {
            val now = System.currentTimeMillis()
            ppgPauseStartedAtMs = now
            ppgRedSustainedAtPauseMs = if (ppgRedFirstContinuouslyOkSinceMs == 0L) 0L else (now - ppgRedFirstContinuouslyOkSinceMs).coerceAtLeast(0L)
            ppgTracker?.let { tracker -> DiagnosticResources.release(DiagnosticResource.SAMSUNG_PPG, tracker) { tracker.unsetEventListener() } }
            ppgRunning = false
            ppgPausedForSpo2 = true
            //Log.i(TAG, "[PPG] Paused PPG_CONTINUOUS for SpO2")
        } catch (t: Throwable) {
            Log.e(TAG, "[PPG] Failed to pause PPG_CONTINUOUS for SpO2", t)
        }
    }

    private fun resumePpgContinuousAfterSpo2() {
        if (!deviceWorn) {
            ppgPausedForSpo2 = false
            return
        }
        if (!ppgPausedForSpo2) return
        ppgPausedForSpo2 = false
        ppgPauseStartedAtMs = 0L
        startPpgContinuousIfSelected()
        //Log.i(TAG, "[PPG] Resumed PPG_CONTINUOUS after SpO2")
    }

    // Initialize PPG capability: prefer continuous if available, fall back to on-demand
    private fun initPpgCapability() {
        val ppgValueSet: Set<PpgType> = hashSetOf(ppgChannelType)
//        val candidates = listOf(
//            HealthTrackerType.PPG_CONTINUOUS,
//            HealthTrackerType.PPG_ON_DEMAND
//        )
        val candidates = listOf(ppgHealthTrackerType)

        ppgTracker = null
        ppgTrackerType = null
        ppgAvailable = false

        for (type in candidates) {
            try {
                val tracker = healthService.getHealthTracker(type, ppgValueSet)
                ppgTracker = tracker
                ppgTrackerType = type
                ppgAvailable = true
                Log.i(TAG, "[PPG] Using tracker type=$type with PpgTypeSet=$ppgValueSet")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "[PPG] Failed to create tracker for type=$type (will try next)", t)
            }
        }

        if (!ppgAvailable) {
            Log.e(TAG, "[PPG] No supported PPG tracker available. Cold stress detection cannot run.")
        }
    }

    private fun startPpgContinuousIfSelected(): Boolean {
        val tracker = ppgTracker ?: return false

        if (!ppgAvailable) return false
        if (ppgTrackerType != HealthTrackerType.PPG_CONTINUOUS) return false
        if (ppgRunning) return true

        return try {
            DiagnosticResources.acquire(DiagnosticResource.SAMSUNG_PPG, tracker) { tracker.setEventListener(ppgEventListener) }
            ppgRunning = true
            Log.i(TAG, "[PPG] Continuous listener started")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "[PPG] Failed to start continuous listener", t)
            ppgAvailable = false
            ppgTracker = null
            ppgTrackerType = null
            false
        }
    }

    private fun startCrmPpgSampling() {
        crmPpgSamplingRequested = true

        if (crmPpgSamplingActive) {
            crmRepo.updateSamplingState(CrmSamplingState.SAMPLING)
            return
        }

        if (!deviceWorn) {
            crmPpgSamplingActive = false
            crmRepo.updateSamplingState(CrmSamplingState.NOT_WORN)
            publishCrmPpgUnavailable("device_not_worn")
            return
        }

        if (!ppgAvailable || ppgTracker == null || ppgTrackerType == null) {
            crmPpgSamplingActive = false
            crmRepo.updateSamplingState(CrmSamplingState.UNAVAILABLE)
            publishCrmPpgUnavailable("ppg_unavailable")
            return
        }

        if (measureState.get() != MeasurementState.IDLE) {
            crmRepo.updateSamplingState(CrmSamplingState.STARTING)
            Log.i(
                TAG,
                "[CRM_PPG] Waiting for measurement cycle; state=${measureState.get()}"
            )
            return
        }

        backgroundHandler.post {
            val started = if (
                ppgTrackerType == HealthTrackerType.PPG_ON_DEMAND
            ) {
                startPpgStreamOnDemand()
            } else {
                Log.e(
                    TAG,
                    "[CRM_PPG] Wrong tracker type. Expected PPG_ON_DEMAND, actual=$ppgTrackerType"
                )
                false
            }

            if (started) {
                crmPpgSamplingActive = true

                serviceScope.launch {
                    val callsignAtSessionStart = sharedRepo.getCallsign.first()
                    val wearTakInfo = sharedRepo.wearTakInfo

                    crmCsvLogger.startSession(
                        channel = "ValueKey.PpgSet.PPG_GREEN",
                        trackerType = ppgTrackerType.toString(),
                        nominalSampleRateHz = ppgOnDemandFrequency,
                        wearTakUid = MainActivity.device_uid,
                        callsignAtSessionStart = callsignAtSessionStart,
                        appVersionName = wearTakInfo.versionNumber,
                        appVersionCode = wearTakInfo.versionCode,
                        gitBranch = wearTakInfo.gitBranch,
                        gitCommit = wearTakInfo.gitCommit,
                        buildType = wearTakInfo.buildType,
                    )
                }

                crmRepo.updateSamplingState(CrmSamplingState.SAMPLING)
                Log.i(TAG, "[CRM_PPG] Sampling started tracker=$ppgTrackerType")
            } else {
                crmPpgSamplingActive = false
                crmRepo.updateSamplingState(CrmSamplingState.ERROR)
                Log.e(TAG, "[CRM_PPG] Failed to start tracker=$ppgTrackerType")
            }
        }
    }

    private fun stopCrmPpgSampling() {
        val wasCrmSamplingActive = crmPpgSamplingActive

        crmPpgSamplingRequested = false
        crmPpgSamplingActive = false

        backgroundHandler.post {
            try {
                if (
                    wasCrmSamplingActive &&
                    ppgTrackerType == HealthTrackerType.PPG_ON_DEMAND
                ) {
                    stopPpgStreamOnDemand()
                }

                crmCsvLogger.stopSession("user_stopped")
                crmRepo.updateSamplingState(CrmSamplingState.IDLE)

                Log.i(
                    TAG,
                    "[CRM_PPG] Sampling stopped tracker=$ppgTrackerType active=$wasCrmSamplingActive"
                )
            } catch (t: Throwable) {
                crmRepo.updateSamplingState(CrmSamplingState.ERROR)
                Log.e(TAG, "[CRM_PPG] Failed to stop sampling", t)
            }
        }
    }

    private fun startPpgStreamOnDemand(): Boolean {
        val tracker = ppgTracker ?: return false

        if (!ppgAvailable) return false
        if (ppgTrackerType != HealthTrackerType.PPG_ON_DEMAND) return false
        if (ppgRunning) return true

        return try {
            ppgStartUptimeMs = 0L
            ppgSamples.clear()

            DiagnosticResources.acquire(DiagnosticResource.SAMSUNG_PPG, tracker) { tracker.setEventListener(ppgEventListener) }
            ppgRunning = true

            Log.i(TAG, "[PPG] On-demand listener started")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "[PPG] Failed to start on-demand listener", t)
            false
        }
    }

    private fun stopPpgStreamOnDemand() {
        if (ppgTrackerType != HealthTrackerType.PPG_ON_DEMAND) return
        if (!ppgRunning) return

        try {
            ppgTracker?.let { tracker -> DiagnosticResources.release(DiagnosticResource.SAMSUNG_PPG, tracker) { tracker.unsetEventListener() } }
            ppgRunning = false
            Log.i(TAG, "[PPG] On Demand listener stopped")
        } catch (t: Throwable) {
            Log.e(TAG, "[PPG] Failed to stop On Demand listener", t)
        }
    }

    // Emit cold-stress inputs_ok flag to FeatureStore (numeric 1.0/0.0)
    private fun pushColdInputsOkFlag() {
        val ok = if (ppgAvailable) 1.0 else 0.0
        runCatching {
            CepProvider.getOrCreateEngine(applicationContext).ingest("features.cold.inputs_ok", ok)
            /*
            if (ok == 0.0) {
                Log.w(TAG, "[COLD] inputs_ok=false (PPG_UNAVAILABLE) — gating cold stress")
            } else {
                Log.i(TAG, "[COLD] inputs_ok=true")
            } */
        }.onFailure { t ->
            Log.e(TAG, "[COLD] Failed to push inputs_ok flag", t)
        }
    }

    // ---- Strict SpO2 phase (only accept STATUS == 2, 40s timeout) ----
    private val spo2StrictListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            backgroundHandler.post {
                if (measureState.get() != MeasurementState.SPO2_RUNNING) return@post
                if (!::spo2Tracker.isInitialized) return@post
                try {
                    dataPoints.forEach { dp ->
                        val status: Int? = try { dp.getValue(ValueKey.SpO2Set.STATUS) } catch (_: Throwable) { null }
                        if (status == null) return@forEach
                        if (status == 2) {
                            val spo2Val: Int? = try { dp.getValue(ValueKey.SpO2Set.SPO2) } catch (_: Throwable) { null }
                            lastSpo2Status = status
                            lastSpo2Pct = spo2Val?.toDouble()
                            lastSpo2Ts = System.currentTimeMillis()
                            val ts = lastSpo2Ts ?: System.currentTimeMillis()
                            val activityState = when (sharedRepo.latestActivityRecognized.value) {
                                ActivityRecognitionService.Companion.ActivityRecognized.WALKING,
                                ActivityRecognitionService.Companion.ActivityRecognized.ON_FOOT -> "WALKING"
                                ActivityRecognitionService.Companion.ActivityRecognized.RUNNING -> "LIGHT_ACTIVITY"
                                ActivityRecognitionService.Companion.ActivityRecognized.STILL -> "STILL"
                                else -> "UNKNOWN"
                            }
                            val motionOk = spo2MotionOk()
                            val ppgRedSustainedMs = ppgRedSustainedForValidation(ts)
                            val altitudeBucket = currentAltitudeBucket()
                            val cepEngine = CepProvider.getOrCreateEngine(applicationContext)
                            val spo2Ops = createSpo2EngineOps(cepEngine)
                            cepEngine.ingest("activity.confidence", lastActivityConfidence.toDouble(), ts)
                            cepEngine.ingest("spo2.status_ok", 1.0, ts)
                            cepEngine.ingest("spo2.ppg_red_sustained_ms", ppgRedSustainedMs.toDouble(), ts)
                            if (lastSpo2Pct != null) {
                                spo2Sequencer.onValid(
                                    ops = spo2Ops,
                                    pct = lastSpo2Pct!!,
                                    ts = ts,
                                    activityState = activityState,
                                    altitudeBucket = altitudeBucket,
                                    motionOk = motionOk,
                                    ppgRedSustainedMs = ppgRedSustainedMs,
                                    statusOk = true,
                                )
                                lastSpo2InvalidReason = null
                                lastSpo2ResetReason = null
                                serviceScope.launch { sharedRepo.storeLatestSpo2(lastSpo2Pct) }
                                serviceScope.launch { updateSpo2DebugState() }
                            }
                            lastSpo2OutcomeWasTimeout = false
                            lastSpo2EndedAsTimeout = false
                            lastSpo2OutcomeWallMs = System.currentTimeMillis()
                            Log.i(TAG, "[SPO2] Completed status=$status spo2=${lastSpo2Pct ?: -1.0}")
                            stopSpo2Session("completed")
                            transitionToPpgPhase()
                            return@post
                        } else if (status == SPO2_STATUS_TIMEOUT) {
                            lastSpo2Ts = System.currentTimeMillis()
                            stopSpo2Session("status_timeout")
                            handleSpo2InvalidOutcome(
                                reason = InvalidReason.TIMEOUT,
                                ts = lastSpo2Ts ?: System.currentTimeMillis(),
                                status = status,
                                endedAsTimeout = true
                            )
                            Log.w(TAG, "[SPO2] TIMEOUT status=$status → stopping session early")
                            transitionToPpgPhase()
                            return@post
                        } else {
                            if (lastSpo2Status != status) {
                                //Log.i(TAG, "[SPO2] Interim status=$status …")
                                lastSpo2Status = status
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[SPO2] onDataReceived error", t)
                    stopSpo2Session("error")
                    handleSpo2InvalidOutcome(
                        reason = InvalidReason.ERROR,
                        ts = System.currentTimeMillis(),
                        status = -1,
                        endedAsTimeout = true
                    )
                    transitionToPpgPhase()
                }
            }
        }
        override fun onFlushCompleted() { Log.d(TAG, "[SPO2] onFlushCompleted") }
        override fun onError(error: HealthTracker.TrackerError) {
            if (::spo2Tracker.isInitialized) DiagnosticResources.note(DiagnosticResource.SAMSUNG_SPO2, spo2Tracker, ResourceAction.UNCERTAIN)
            backgroundHandler.post {
                if (measureState.get() != MeasurementState.SPO2_RUNNING) return@post
                Log.e(TAG, "[SPO2] Error: $error")
                stopSpo2Session("onError:$error")
                handleSpo2InvalidOutcome(
                    reason = InvalidReason.ERROR,
                    ts = System.currentTimeMillis(),
                    status = -1,
                    endedAsTimeout = true
                )
                transitionToPpgPhase()
            }
        }
    }

    private fun spo2MotionOk(): Boolean = spo2StillnessAllowed(
        sharedRepo.stationaryMotion.state.value,
        sharedRepo.latestActivityRecognized.value,
        sharedRepo.latestActivityConfidence.value,
        SystemClock.elapsedRealtimeNanos(),
    )

    private fun startSpo2ThenPpgCycle() = backgroundHandler.post {
        if (!deviceWorn || measureState.get() != MeasurementState.IDLE) return@post
        if (crmPpgSamplingRequested || crmPpgSamplingActive) {
            Log.d(
                TAG,
                "[SPO2] Skipping scheduled cycle while CRM PPG sampling is active"
            )
            return@post
        }
        // Read current state on the tracker thread, not the asynchronously cached activity.
        // A skip is retried only at the next scheduled opportunity and spends no attempt.
        if (!spo2MotionOk()) {
            AppDiagnostics.record(DiagnosticEvent.SPO2_SKIPPED_MOTION,
                mapOf(DiagnosticMetric.SKIPPED to 1L))
            Log.d(TAG, "[SPO2] Not still; deferring to next scheduled opportunity")
            return@post
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val baseReadyAt = lastSpo2AttemptElapsedMs + SPO2_MIN_GAP_MS
        val timeoutPenalty = if (lastSpo2EndedAsTimeout) SPO2_BACKOFF_AFTER_TIMEOUT_MS else 0L
        val readyAt = baseReadyAt + timeoutPenalty
        if (lastSpo2AttemptElapsedMs != 0L && nowElapsed < readyAt) {
            val remaining = readyAt - nowElapsed
            Log.d(TAG, "[SPO2] Skipping (throttle/backoff). remainingMs=$remaining timeoutBackoffApplied=$lastSpo2EndedAsTimeout")
            return@post
        }
        if (!measureState.compareAndSet(MeasurementState.IDLE, MeasurementState.SPO2_RUNNING)) {
            Log.d(TAG, "[MEAS] Cycle request ignored, state=${measureState.get()}")
            return@post
        }
        lastSpo2AttemptElapsedMs = nowElapsed
        Log.i(TAG, "[MEAS] Cycle start → SPO2_RUNNING")
        // Pause continuous PPG to avoid optical contention
        pausePpgContinuousForSpo2()
        Log.d(TAG, "[PPG] Paused for SPO2")
        if (!::spo2Tracker.isInitialized) {
            Log.w(TAG, "[SPO2] Tracker unavailable, skipping to PPG")
            // Ensure PPG is resumed if we paused
            resumePpgContinuousAfterSpo2()
            Log.d(TAG, "[PPG] Resumed after SPO2")
            measureState.set(MeasurementState.PPG_RUNNING)
            startPpgPhase()
            return@post
        }
        try {
            DiagnosticResources.acquire(DiagnosticResource.SAMSUNG_SPO2, spo2Tracker, timeoutMs = SPO2_TIMEOUT_MS) {
                spo2Tracker.setEventListener(spo2StrictListener)
            }
            Log.i(TAG, "[SPO2] Started (timeout ${SPO2_TIMEOUT_MS/1000}s)")
        } catch (ex: Throwable) {
            Log.e(TAG, "[SPO2] start failed", ex)
            // Ensure PPG is resumed if we paused
            resumePpgContinuousAfterSpo2()
            Log.d(TAG, "[PPG] Resumed after SPO2")
            measureState.set(MeasurementState.PPG_RUNNING)
            startPpgPhase()
            return@post
        }
        spo2TimeoutRunnable = Runnable {
            if (measureState.get() == MeasurementState.SPO2_RUNNING) {
                Log.w(TAG, "[SPO2] Timeout reached; stopping optical cycle")
                stopSpo2Session("timeout_runnable")
                handleSpo2InvalidOutcome(
                    reason = InvalidReason.TIMEOUT,
                    ts = System.currentTimeMillis(),
                    status = SPO2_STATUS_TIMEOUT,
                    endedAsTimeout = true
                )
                transitionToPpgPhase()
            }
        }
        backgroundHandler.postDelayed(spo2TimeoutRunnable!!, SPO2_TIMEOUT_MS)
    }

    private fun transitionToPpgPhase() {
        // A failed SpO2 attempt must not spend another on-demand optical burst.
        if (lastSpo2EndedAsTimeout) {
            finishMeasurementCycle()
            return
        }
        if (!measureState.compareAndSet(MeasurementState.SPO2_RUNNING, MeasurementState.PPG_RUNNING)) {
            Log.d(TAG, "[MEAS] Unexpected state ${measureState.get()} when transitioning to PPG")
            measureState.set(MeasurementState.PPG_RUNNING)
        }
        startPpgPhase()
    }

    private fun spo2TimeoutCleanup() {
        spo2TimeoutRunnable?.let { backgroundHandler.removeCallbacks(it) }
        spo2TimeoutRunnable = null
    }
    private fun stopSpo2Session(reason: String) {
        spo2TimeoutCleanup()
        if (::spo2Tracker.isInitialized) {
            try {
                DiagnosticResources.release(DiagnosticResource.SAMSUNG_SPO2, spo2Tracker) { spo2Tracker.unsetEventListener() }
            } catch (t: Throwable) {
                Log.e(TAG, "[SPO2] unset error", t)
            }
        }
        lastSpo2OutcomeWallMs = System.currentTimeMillis()
        Log.i(TAG, "[SPO2] Session end ($reason)")
        // Resume continuous PPG after SpO2 completes/ends
        resumePpgContinuousAfterSpo2()
        Log.d(TAG, "[PPG] Resumed after SPO2")
    }
    private fun stopSpo2Tracker() {
        if (!::spo2Tracker.isInitialized) return
        backgroundHandler.post {
            try {
                DiagnosticResources.release(DiagnosticResource.SAMSUNG_SPO2, spo2Tracker) { spo2Tracker.unsetEventListener() }
                spo2Running = false
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop SpO2 tracker", t)
            }
        }
    }

    // ---- PPG burst (green) ----
    private var ppgStartUptimeMs: Long = 0L
    private var ppgBurstDurationMs: Long = 0L
    private val ppgSamples = PpgSampleBuffer()

    private val ppgEventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if (!deviceWorn) {
                Log.d(TAG, "Ignoring PPG callback while device is not worn.")
                return
            }
            val now = SystemClock.uptimeMillis()
            if (ppgStartUptimeMs == 0L) ppgStartUptimeMs = now
            try {
                dataPoints.forEach { dp ->
                    // Capture raw PPG sample (PPG_GREEN is Int)
                    try {
                        val raw: Int = dp.getValue(ppgSet)
                        val sampleUptimeMs = SystemClock.uptimeMillis()
                        lastPpgRaw = raw
                        // Maintain rolling 20s window
                        appendPpgSampleAndPrune(raw, sampleUptimeMs)
//                        publishCrmRawPpgSample(sampleUptimeMs, raw)
                        if (crmPpgSamplingActive) {
                            if (crmPpgSamplingActive) {
                                crmCsvLogger.logRawPpg(
                                    uptimeMillis = sampleUptimeMs,
                                    rawValue = raw.toFloat(),
                                    channel = ppgSet.toString(),
                                    trackerType = ppgTrackerType?.name ?: "UNKNOWN",
                                    nominalSampleRateHz = ppgOnDemandFrequency,
                                    deviceWorn = deviceWorn,
                                )

                                publishCrmRawPpgSample(
                                    sampleUptimeMs = sampleUptimeMs,
                                    raw = raw,
                                )
                            }
                            publishCrmRawPpgSample(
                                sampleUptimeMs = sampleUptimeMs,
                                raw = raw,
                            )
                        }
                        // For on-demand burst, also store in burst buffer
                        if (
                            measureState.get() == MeasurementState.PPG_RUNNING &&
                            ppgTrackerType == HealthTrackerType.PPG_ON_DEMAND
                        ) {
                            ppgSamples.add(raw.toFloat())
                        }
                    } catch (_: Throwable) {
                        // ignore bad datapoint
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed reading PPG datapoints", t)
            }

            // Throttled rolling amplitude compute (useful in continuous mode)
            maybeComputePpgThrottled()
            // Throttled raw value logging (avoid log spam)
            maybeLogPpgRaw()

            // Stop-after-duration is ONLY for on-demand (burst) mode.
            if (
                ppgTrackerType == HealthTrackerType.PPG_ON_DEMAND &&
                measureState.get() == MeasurementState.PPG_RUNNING
            ) {
                if (now - ppgStartUptimeMs >= ppgBurstDurationMs) {
                    completePpgPhase("window end")
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            ppgTracker?.let { DiagnosticResources.note(DiagnosticResource.SAMSUNG_PPG, it, ResourceAction.UNCERTAIN) }
            Log.e(TAG, "PPG TrackerError: $error")

            if (crmPpgSamplingActive) {
                crmPpgSamplingRequested = false
                crmPpgSamplingActive = false

                crmRepo.updateSamplingState(CrmSamplingState.ERROR)
                publishCrmPpgUnavailable("tracker_error:$error")
            }

            if (measureState.get() == MeasurementState.PPG_RUNNING) {
                completePpgPhase("error")
            } else if (ppgTrackerType == HealthTrackerType.PPG_ON_DEMAND) {
                stopPpgStreamOnDemand()
            }
        }
    }

    private fun startPpgPhase() {
        backgroundHandler.post {
            val t = ppgTracker
            if (!ppgAvailable || t == null) {
                Log.e(TAG, "[PPG] Unavailable — cannot proceed; cold stress disabled")
                pushColdInputsOkFlag()
                finishMeasurementCycle()
                return@post
            }
            // If tracker type is continuous, we assume listener already running — do not touch burst timers, just wait and compute
            if (ppgTrackerType == HealthTrackerType.PPG_CONTINUOUS) {
                Log.i(TAG, "[PPG] Continuous mode — waiting 20s then computing amplitude (do not stop listener)")
                ppgTimeoutRunnable = Runnable {
                    if (measureState.get() == MeasurementState.PPG_RUNNING) {
                        computeAndCachePpgAmplitudeIfPossible("continuous phase end")
                        ppgTimeoutCleanup()
                        finishMeasurementCycle()
                    }
                }
                backgroundHandler.postDelayed(ppgTimeoutRunnable!!, 20_000L)
                return@post
            } else {
                // ON_DEMAND burst path — reset timers/buffers
                ppgStartUptimeMs = 0L
                ppgBurstDurationMs = 20_000L
                ppgSamples.clear()
                // On-demand: start 20s burst
                try {
                    DiagnosticResources.acquire(DiagnosticResource.SAMSUNG_PPG, t, timeoutMs = 20_000) {
                        t.setEventListener(ppgEventListener)
                    }
                    ppgRunning = true
                    Log.i(TAG, "[PPG] Started (20s window)")
                } catch (ex: Throwable) {
                    Log.e(TAG, "[PPG] start failed", ex)
                    finishMeasurementCycle()
                    return@post
                }
                // Ensure completion if no data callbacks arrive
                ppgTimeoutRunnable = Runnable {
                    if (measureState.get() == MeasurementState.PPG_RUNNING) {
                        completePpgPhase("timeout")
                    }
                }
                backgroundHandler.postDelayed(ppgTimeoutRunnable!!, 20_000L)
            }
        }
    }

    private fun stopPpgTracker() {
        if (ppgTracker == null) return
        backgroundHandler.post {
            try {
                ppgTracker?.let { tracker -> DiagnosticResources.release(DiagnosticResource.SAMSUNG_PPG, tracker) { tracker.unsetEventListener() } }
                ppgRunning = false
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop PPG tracker", t)
            }
        }
    }

    // ---- HR tracker lifecycle + screen-off flush helpers ----
    private fun startHrTracker(): Boolean = synchronized(nnBuffer) {
        if (destroyed || !BuildConfig.SAMSUNG_HRV_ENABLED || !::hrTracker.isInitialized) return false
        if (isHrRunning) return true
        if (hrListenerAttached) return false
        try {
            // HeartrateService owns monitoring, worn-state, and permission gating for both sources.
            isHrRunning = true
            hrEventListener = createHrEventListener(++hrGeneration)
            hrListenerAttached = true
            DiagnosticResources.acquire(DiagnosticResource.SAMSUNG_HR, hrTracker) {
                hrTracker.setEventListener(hrEventListener)
            }
            AppDiagnostics.updateSamsungHrContinuousRunning(true)
            Log.i(TAG, "HRV tracker started (HEART_RATE_CONTINUOUS; SDK has no batching control)")
            if (!isInteractiveNow) enableScreenOffFlushLoop()
            true
        } catch (error: Exception) {
            isHrRunning = false
            disableScreenOffFlushLoop()
            Log.e(TAG, "Failed to start HRV tracker", error)
            false
        }
    }

    private fun stopHrTracker(): Boolean = synchronized(nnBuffer) {
        isHrRunning = false
        hrSampleConsumer = null
        hrFailureConsumer = null
        nnBuffer.clear()
        lastNonZeroRmssdMs = 0.0
        lastRmssdElapsedMs = 0L
        lastRmssdWallMs = 0L
        lastHrvEmittedElapsedMs = 0L
        ++hrGeneration
        disableScreenOffFlushLoop()
        if (hrListenerAttached && ::hrTracker.isInitialized) {
            try {
                DiagnosticResources.release(DiagnosticResource.SAMSUNG_HR, hrTracker) {
                    hrTracker.unsetEventListener()
                }
                hrListenerAttached = false
                AppDiagnostics.updateSamsungHrContinuousRunning(false)
                Log.i(TAG, "HRV tracker stopped")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to stop HRV tracker", error)
                return false
            }
        }
        true
    }

    internal fun latestHrvPayload(nowElapsedMs: Long): HrvPayload? = synchronized(nnBuffer) {
        if (!BuildConfig.SAMSUNG_HRV_ENABLED || !isHrRunning || !deviceWorn ||
            lastRmssdElapsedMs <= lastHrvEmittedElapsedMs ||
            nowElapsedMs - lastRmssdElapsedMs !in 0..HRV_FRESHNESS_MS ||
            lastNonZeroRmssdMs <= 0.0 || !lastNonZeroRmssdMs.isFinite()) return null
        HrvPayload(rmssd_ms = lastNonZeroRmssdMs, ts = lastRmssdWallMs)
    }

    // ---- HRV helpers (mirrors WEAR-383) ----
    private fun addValidNnIntervals(ibiList: List<Int>?, ibiStatusList: List<Int>?): Boolean {
        if (ibiList.isNullOrEmpty()) return false
        var added = false
        val hasStatus = !ibiStatusList.isNullOrEmpty() && ibiStatusList.size == ibiList.size
        for (i in ibiList.indices) {
            val ibi = ibiList[i]
            val statusOk = if (hasStatus) (ibiStatusList!![i] == 0) else true
            if (!statusOk) continue
            if (ibi < NN_MIN_MS || ibi > NN_MAX_MS) continue
            nnBuffer.addLast(ibi)
            added = true
            while (nnBuffer.size > MAX_NN_BUFFER) nnBuffer.removeFirst()
        }
        return added
    }

	private fun computeRmssdMs(): Double? {
		if (nnBuffer.size < MIN_NN_FOR_RMSSD) return null
		var sumSq = 0.0
		var n = 0
		var prev = nnBuffer.first()
		for (i in 1 until nnBuffer.size) {
			val cur = nnBuffer.elementAt(i)
			val diff = (cur - prev).toDouble()
			sumSq += diff * diff
			n++
			prev = cur
		}
		if (n <= 0) return null
		return kotlin.math.sqrt(sumSq / n)
	}

    private val hrFlushRunnable = object : Runnable {
        override fun run() {
            if (!BuildConfig.SAMSUNG_HRV_ENABLED || !screenOffFlushEnabled || !deviceWorn || !isHrRunning) return
            flushHr("screen_off_timer")
            backgroundHandler.postDelayed(this, SCREEN_OFF_FLUSH_INTERVAL_MS)
        }
    }

    private fun enableScreenOffFlushLoop() {
        if (!BuildConfig.SAMSUNG_HRV_ENABLED || !deviceWorn || !isHrRunning || screenOffFlushEnabled) return
        screenOffFlushEnabled = true
        Log.w(TAG, "SCREEN-OFF FLUSH: ENABLED intervalMs=$SCREEN_OFF_FLUSH_INTERVAL_MS")
        backgroundHandler.removeCallbacks(hrFlushRunnable)
        backgroundHandler.postDelayed(hrFlushRunnable, FLUSH_START_DELAY_MS)
    }

    private fun disableScreenOffFlushLoop() {
        if (!screenOffFlushEnabled) return
        screenOffFlushEnabled = false
        Log.w(TAG, "SCREEN-OFF FLUSH: DISABLED")
        backgroundHandler.removeCallbacks(hrFlushRunnable)
    }

    private fun flushHr(reason: String) {
        if (!BuildConfig.SAMSUNG_HRV_ENABLED || !isHrRunning || !deviceWorn || !::hrTracker.isInitialized) return
        Log.i(TAG, "FLUSH-REQUEST: reason=$reason t=${SystemClock.elapsedRealtime()}")
        try { hrTracker.flush() } catch (t: Throwable) { Log.e(TAG, "flushHr failed reason=$reason", t) }
    }

    private fun ppgTimeoutCleanup() {
        ppgTimeoutRunnable?.let { backgroundHandler.removeCallbacks(it) }
        ppgTimeoutRunnable = null
    }

    // Rolling window helpers
    private fun appendPpgSampleAndPrune(rawInt: Int, now: Long = SystemClock.uptimeMillis()) {
        val v = rawInt.toFloat()
        synchronized(ppgWindow) {
            ppgWindow.addLast(TimedSample(now, v))
            val cutoff = now - PPG_WINDOW_MS
            while (ppgWindow.isNotEmpty() && ppgWindow.first().tUptimeMs < cutoff) {
                ppgWindow.removeFirst()
            }
            lastPpgSampleCount = ppgWindow.size
        }
    }
    private fun computeAndCachePpgAmplitudeIfPossible(reason: String) {
        val values = synchronized(ppgWindow) { ppgWindow.map { it.v } }
        if (values.isEmpty()) {
            lastPpgAmplitude = null
            lastPpgTs = System.currentTimeMillis()
            Log.w(TAG, "[PPG] No samples in window; amplitude=null ($reason)")
            return
        }
        // Robust amplitude using 5th–95th percentile
        val amp = computeAmplitudeP5P95(values)
        lastPpgAmplitude = amp
        lastPpgTs = System.currentTimeMillis()
        // reuse quality heuristic
        lastPpgQuality = (amp.toFloat() / (amp.toFloat() + 50f)).coerceIn(0f, 1f)
        val quality = lastPpgQuality ?: 0f
        if (amp.toFloat() > 0f && quality >= 0.5f) {
            if (ppgRedFirstContinuouslyOkSinceMs == 0L) {
                ppgRedFirstContinuouslyOkSinceMs = lastPpgTs ?: System.currentTimeMillis()
            }
        } else {
            ppgRedFirstContinuouslyOkSinceMs = 0L
        }
        //Log.i(TAG, "[PPG] amplitude=$amp samples=${ppgWindow.size} ($reason)")
    }
    private fun maybeComputePpgThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPpgComputeUptimeMs >= PPG_COMPUTE_PERIOD_MS) {
            lastPpgComputeUptimeMs = now
            computeAndCachePpgAmplitudeIfPossible("throttled")
        }
    }

    private fun maybeLogPpgRaw() {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastPpgRawLogElapsedMs < PPG_RAW_LOG_PERIOD_MS) return
        lastPpgRawLogElapsedMs = nowElapsed
        val raw = lastPpgRaw
        val amp = lastPpgAmplitude
        val winSize = lastPpgSampleCount
        val logLine = "[PPG] raw=${raw ?: -1} window=$winSize amp=${amp ?: -1.0}"
        //Log.i(TAG, logLine)
        //writeToLogFileLocation(logFile, logLine)
    }

    private fun publishCrmRawPpgSample(sampleUptimeMs: Long, raw: Int) {
        val accepted = crmRepo.tryPublishRawPpgSample(
            uptimeMillis = sampleUptimeMs,
            rawValue = raw.toFloat(),
            sourceAvailable = ppgAvailable,
            deviceWorn = deviceWorn
        )
        maybeLogCrmPpgSource(raw, accepted)
    }

    private fun publishCrmPpgUnavailable(reason: String) {
        crmRepo.tryPublishPpgSourceUnavailable(
            uptimeMillis = SystemClock.uptimeMillis(),
            reason = reason
        )
    }

    private fun maybeLogCrmPpgSource(raw: Int, accepted: Boolean) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastCrmPpgSourceLogElapsedMs < PPG_RAW_LOG_PERIOD_MS) return
        lastCrmPpgSourceLogElapsedMs = nowElapsed
        Log.d(
            TAG,
            "[CRM_PPG_SOURCE] raw=$raw emitted=$accepted worn=$deviceWorn available=$ppgAvailable tracker=$ppgTrackerType"
        )
    }

    private fun completePpgPhase(reason: String) {
        if (android.os.Looper.myLooper() != backgroundHandler.looper) {
            backgroundHandler.post { completePpgPhase(reason) }
            return
        }
        if (measureState.get() != MeasurementState.PPG_RUNNING) return
        // Only stop the tracker for on-demand mode; keep continuous listener running
        if (ppgTrackerType != HealthTrackerType.PPG_CONTINUOUS) {
            stopPpgTracker()
        }
        ppgTimeoutCleanup()
        // Compute amplitude from appropriate buffer
        if (ppgTrackerType == HealthTrackerType.PPG_CONTINUOUS) {
            computeAndCachePpgAmplitudeIfPossible(reason)
        } else {
            val samples = ppgSamples.drain()
            if (samples.isNotEmpty()) {
                val min = samples.minOrNull() ?: 0f
                val max = samples.maxOrNull() ?: 0f
                val amp = (max - min).toDouble()
                lastPpgAmplitude = if (amp.isFinite()) amp else null
                lastPpgTs = System.currentTimeMillis()
                lastPpgQuality = (amp.toFloat() / (amp.toFloat() + 50f)).coerceIn(0f, 1f)
                Log.i(TAG, "[PPG] Completed ($reason) samples=${samples.size} amp=${lastPpgAmplitude ?: -1.0} q=${lastPpgQuality}")
            } else {
                lastPpgAmplitude = null
                lastPpgTs = System.currentTimeMillis()
                lastPpgQuality = 0f
                Log.w(TAG, "[PPG] No samples collected ($reason)")
            }
        }
        finishMeasurementCycle()
        ppgStartUptimeMs = 0L
    }

    private fun finishMeasurementCycle() {
        measureState.set(MeasurementState.IDLE)
        Log.i(TAG, "[MEAS] Cycle end → IDLE")

        if (crmPpgSamplingRequested && !crmPpgSamplingActive) {
            startCrmPpgSampling()
        }
    }
    private fun computeAmplitudeP5P95(samples: List<Float>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        fun pct(p: Double): Double {
            val idx = ((p / 100.0) * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
            val i = idx.toInt()
            val frac = idx - i
            val a = sorted[i].toDouble()
            val b = sorted[(i + 1).coerceAtMost(sorted.size - 1)].toDouble()
            return a + (b - a) * frac
        }
        val p5 = pct(5.0)
        val p95 = pct(95.0)
        return (p95 - p5).coerceAtLeast(0.0)
    }

    private fun computeQuality(amplitude: Float): Float {
        // Simple heuristic: normalize amplitude to [0..1] with soft cap
        val norm = amplitude / 1.0f
        return norm.coerceIn(0f, 1f)
    }

    private fun createSpo2EngineOps(cepEngine: CepEngine) = buildSpo2EngineOps(
        engine = cepEngine,
        onPublish = { payload ->
            serviceScope.launch { sharedRepo.storeLatestSpo2TierEvent(payload) }
            serviceScope.launch {
                val evt = CepEvent(Topics.SPO2_TIER_EVENT, payload, payload.ts)
                if (!CepBus.tryEmit(evt)) {
                    CepBus.emit(evt)
                }
            }
        }
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SkinTempService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(NOTIF_ID, importance = android.app.NotificationManager.IMPORTANCE_LOW)
        deviceWorn = sharedRepo.latestIsDeviceWorn.value
        spo2Sequencer = Spo2Sequencer(
            profile = CepProvider.getProfile(applicationContext),
        )

        deleteLogFile()
        CoroutineScope(Dispatchers.IO).launch{
            sharedRepo.reportingUpdateIntervalSecs.collect { secs ->
                val newIntervalMs = (secs * 1_000L).coerceAtLeast(MIN_SKIN_TEMP_INTERVAL_MS)
                if (newIntervalMs != DISPATCH_INTERVAL_MS) {
//                    writeToLogFileLocation(logFile, "Dispatch Interval Changed from ${DISPATCH_INTERVAL_MS/1000}s to ${newIntervalMs/1000}s"); Log.i(TAG, "Reporting interval updated -> $secs s")
                    DISPATCH_INTERVAL_MS = newIntervalMs

                    if (deviceWorn) {
                        backgroundHandler.removeCallbacksAndMessages(null)
                        scheduleNextReading()
                        startSkinTracker()
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                sharedRepo.latestActivityConfidence.collect { conf ->
                    lastActivityConfidence = conf ?: 0
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Activity confidence flow collect failed", t)
            }
        }

        // Start a dedicated thread for handling callbacks
        handlerThread = HandlerThread("SkinTempThread").apply { start() }
        backgroundHandler = Handler(handlerThread.looper)

        // Connect to the Samsung Health Tracking Service
        healthService = HealthTrackingService(connectionListener, applicationContext)
        healthService.connectService()

        // Listen for wrist wear state changes
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        // Screen on/off receiver
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, f)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register screen receiver", t)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "SkinTempService onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_CRM_PPG -> {
                startCrmPpgSampling()
            }

            ACTION_STOP_CRM_PPG -> {
                stopCrmPpgSampling()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        Log.d(TAG, "SkinTempService onDestroy()")
        isRunning.set(false)
        deviceWorn = false
        // Stop emitter loop
        try {
            emitJob?.cancel()
            serviceScope.cancel()
        } catch (_: Throwable) { }
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) { }

        // Ensure listener is removed and thread stopped
        stopSkinTracker(true)
        stopSpo2Tracker()
        stopPpgTracker()
        withdrawHrSource()
        if (::spo2Sequencer.isInitialized) {
            spo2Sequencer.reset(createSpo2EngineOps(CepProvider.getOrCreateEngine(applicationContext)), ResetReason.SERVICE_STOP)
            lastSpo2InvalidReason = ResetReason.SERVICE_STOP.name
            lastSpo2ResetReason = ResetReason.SERVICE_STOP.name
            CoroutineScope(Dispatchers.IO).launch { clearSpo2RepositoryState() }
        }
        spo2TimeoutCleanup()
        ppgTimeoutCleanup()
        measureState.set(MeasurementState.IDLE)
        // Push cold-stress inputs flag (PPG will be unavailable after destroy)
        ppgAvailable = false
        crmPpgSamplingRequested = false
        crmPpgSamplingActive = false
        crmRepo.updateSamplingState(CrmSamplingState.IDLE)
        pushColdInputsOkFlag()
        noteSamsungCleanupUncertain()
        healthService.disconnectService()
        AppDiagnostics.updateSamsungHrContinuousRunning(false)
        handlerThread.quitSafely()
        stopForegroundServiceCompat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Whenever the device is put on, we want fresh skin temp, not wait for the dispatch interval
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onIsDeviceWornEvent(event: IsDeviceWornEvent) {
        val nowWorn = event.isDeviceWorn
        Log.d(TAG, "Wear event: new=$nowWorn")
        // Clear old callbacks so they don’t pile up
        backgroundHandler.removeCallbacksAndMessages(null)

        if (nowWorn != deviceWorn) {
            if (nowWorn) {
                deviceWorn = true
                // HR source ownership follows the authoritative repository worn state.
                if (!isInteractiveNow) {
                    enableScreenOffFlushLoop()
                    flushHr("worn_event_screen_off")
                } else {
                    disableScreenOffFlushLoop()
                }
                startSkinTracker()    // immediate snapshot
                scheduleNextReading()
                startEmitLoopIfNeeded()
                if (crmPpgSamplingRequested) {
                    startCrmPpgSampling()
                }
                //writeToLogFileLocation(logFile, "deviceWorn=true.")
            } else {
                deviceWorn = false
                spo2Sequencer.reset(createSpo2EngineOps(CepProvider.getOrCreateEngine(applicationContext)), ResetReason.OFF_WRIST)
                lastSpo2InvalidReason = ResetReason.OFF_WRIST.name
                lastSpo2ResetReason = ResetReason.OFF_WRIST.name
                ppgRedFirstContinuouslyOkSinceMs = 0L
                ppgRedSustainedAtPauseMs = 0L
                ppgPauseStartedAtMs = 0L
                clearSpo2RepositoryStateAsync()
                stopSkinTracker(true)     // shut it down
                stopSpo2Tracker()
                stopPpgTracker()
                stopEmitLoop()
                spo2TimeoutCleanup()
                ppgTimeoutCleanup()
                measureState.set(MeasurementState.IDLE)
                publishCrmPpgUnavailable("device_not_worn")
                crmPpgSamplingActive = false
                if (crmPpgSamplingRequested) {
                    crmRepo.updateSamplingState(CrmSamplingState.NOT_WORN)
                }
                crmCsvLogger.stopSession("device_not_worn")

                //writeToLogFileLocation(logFile, "deviceWorn=false.")
            }
        }
    }

    private fun startSkinTracker() {
        if (!::skinTracker.isInitialized || isHandlerRunning) return
        isHandlerRunning = true
        backgroundHandler.post {
            DiagnosticResources.acquire(DiagnosticResource.SAMSUNG_SKIN, skinTracker) { skinTracker.setEventListener(trackerEventListener) }
        }
    }

    private fun stopSkinTracker(clearQueue: Boolean = false) {
        if (!isHandlerRunning) return
        isHandlerRunning = false
        backgroundHandler.post {
            DiagnosticResources.release(DiagnosticResource.SAMSUNG_SKIN, skinTracker) { skinTracker.unsetEventListener() }
            if (clearQueue) backgroundHandler.removeCallbacksAndMessages(null)
        }
    }

    // CEP emission loop
    private fun startEmitLoopIfNeeded() {
        if (emitJob?.isActive == true) return
        emitJob = serviceScope.launch {
            while (isActive) {
                emitLatestCep()
                delay(EMIT_INTERVAL_MS)
            }
        }
    }

    private fun stopEmitLoop() {
        try {
            emitJob?.cancel()
            emitJob = null
        } catch (_: Throwable) { }
    }

    private fun ppgRedSustainedForValidation(nowMs: Long): Long {
        return if (ppgPausedForSpo2 && ppgPauseStartedAtMs != 0L) {
            ppgRedSustainedAtPauseMs
        } else if (ppgRedFirstContinuouslyOkSinceMs == 0L) {
            0L
        } else {
            (nowMs - ppgRedFirstContinuouslyOkSinceMs).coerceAtLeast(0L)
        }
    }

    private suspend fun clearSpo2RepositoryState() {
        sharedRepo.storeLatestSpo2(null)
        sharedRepo.storeLatestSpo2TierEvent(null)
        sharedRepo.storeLatestSpo2DebugState(null)
    }

    private fun clearSpo2RepositoryStateAsync() {
        serviceScope.launch { clearSpo2RepositoryState() }
    }

    private fun handleSpo2InvalidOutcome(
        reason: InvalidReason,
        ts: Long,
        status: Int,
        endedAsTimeout: Boolean,
    ) {
        lastSpo2Status = status
        lastSpo2Pct = null
        lastSpo2OutcomeWasTimeout = endedAsTimeout
        lastSpo2EndedAsTimeout = endedAsTimeout
        lastSpo2OutcomeWallMs = System.currentTimeMillis()
        spo2Sequencer.onInvalid(createSpo2EngineOps(CepProvider.getOrCreateEngine(applicationContext)), ts, reason)
        lastSpo2InvalidReason = reason.name
        // Clear transient UI/debug-facing state so invalid outcomes do not keep stale SpO2/tier snapshots.
        serviceScope.launch { sharedRepo.storeLatestSpo2(null) }
        serviceScope.launch { sharedRepo.storeLatestSpo2TierEvent(null) }
        serviceScope.launch { updateSpo2DebugState() }
    }

    private fun currentAltitudeBucket(): Int {
        val pressureHpa = sharedRepo.latestPressure.value?.pressure?.toDouble() ?: return 0
        if (!pressureHpa.isFinite() || pressureHpa <= 0.0) return 0
        val altitudeMeters = 44330.0 * (1.0 - (pressureHpa / 1013.25).pow(0.1903))
        return when {
            altitudeMeters < 1000.0 -> 0
            altitudeMeters < 2500.0 -> 1
            altitudeMeters < 4000.0 -> 2
            else -> 3
        }
    }

    private suspend fun updateSpo2DebugState() {
        val profile = CepProvider.getProfile(applicationContext)
        val family = profile.spo2BaselineFamily()
        val bucket = profile.spo2BaselineAltitudeBucket()
        val baseline = profile.spo2Baseline(family, bucket)
        val provisional = profile.spo2BaselineProvisional(family, bucket)
        val snapshot = spo2Sequencer.snapshot()
        sharedRepo.storeLatestSpo2DebugState(
            DataRepository.Spo2DebugState(
                activeBaselinePct = baseline,
                activeBaselineFamily = family,
                activeAltitudeBucket = bucket,
                activeBaselineProvisional = provisional,
                ringSize = snapshot.ringSize,
                lastInvalidReason = lastSpo2InvalidReason,
                lastStateUpdateTs = System.currentTimeMillis(),
                lastResetReason = lastSpo2ResetReason,
            )
        )
    }

    private suspend fun emitLatestCep() {
        try {
            val now = System.currentTimeMillis()
            val spo2Ts = lastSpo2Ts
            lastSpo2Pct?.let { pct ->
                if (spo2Ts != null && spo2Ts > lastSpo2EmittedTs && (now - spo2Ts) <= SPO2_EMIT_FRESHNESS_MS) {
                    val ppgRedSustainedMs = ppgRedSustainedForValidation(now)
                    CepBus.emit(
                        CepEvent(
                            topic = Topics.SPO2,
                            payload = Spo2Payload(
                                pct = pct,
                                ts = spo2Ts,
                                statusOk = (lastSpo2Status == 2),
                                motionOk = spo2MotionOk(),
                                ppgRedSustainedMs = ppgRedSustainedMs,
                                ppgAmplitude = lastPpgAmp,
                                ppgQuality = lastPpgQuality,
                            ),
                            ts = spo2Ts
                        )
                    )
                    lastSpo2EmittedTs = spo2Ts
                    sharedRepo.storeLatestSpo2(pct)
                }
            }
			// Emit latest HRV (RMSSD) if available
            latestHrvPayload(SystemClock.elapsedRealtime())?.let { hrv ->
				CepBus.emit(
					CepEvent(
						topic = Topics.HRV,
                        payload = hrv,
                        ts = hrv.ts
					)
				)
                synchronized(nnBuffer) {
                    if (lastRmssdWallMs == hrv.ts) lastHrvEmittedElapsedMs = lastRmssdElapsedMs
                }
			}
            lastPpgAmplitude?.let { amp ->
                CepBus.emit(
                    CepEvent(
                        topic = Topics.PPG,
                        payload = PpgPayload(
                            amplitude = amp.toFloat(),
                            quality = (lastPpgQuality ?: 0f),
                            wavelength = "green",
                            ppgRedSustainedMs = ppgRedSustainedForValidation(now),
                            ts = now,
                        )
                    )
                )
            }
            lastSkinTempC?.let { c ->
                 CepBus.emit(
                     CepEvent(
                         topic = Topics.SKIN_TEMP,
                         payload = SkinTempPayload(
                             c_peripheral = listOf(c.toDouble()),
                             labels = listOf("hand")
                         )
                     )
                 )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "CEP emit loop error", t)
        }
    }
}
