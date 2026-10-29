package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.PressurePayload
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.model.PressureReading
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.ifEmpty

class PressureService : Service() {

    // android sensor manager used to access device sensors
    private lateinit var sensorManager: SensorManager

    // reference to the device pressure (barometer) sensor
    private var pressureSensor: Sensor? = null

    // shared repository used to store latest pressure + history
    private val dataRepository: DataRepository
        get() = MainActivity.dataRepository

    // local log file used for debugging and validation
    private var logFile: File? = null

    // timestamp of the last detected dunk event (used to prevent spamming)
    private var lastDunkTs = 0L

    private var lastShallowDunkTs = 0L
    private var submergeBaseline: Float? = null

    private var lastSubmergeTs = 0L
    private var submergeStartTs: Long? = null
    private var isCurrentlySubmerged = false

    private var dunkUid: String? = null
    private var shallowDunkUid: String? = null
    private var submergeUid: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Monotonically increasing sample counter. Reset to 0 on service creation so
     * downstream CEP consumers can detect service restarts (new sampleIndex < previous).
     */
    private var sampleIndex = 0L

    private var enableImmersionAlerts: Boolean = false
    private var enableLowAtmPressureAlert: Boolean = false
    private var enableHighAtmPressureAlert: Boolean = false
    private var lowPressureThreshold: Int = DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
    private var highPressureThreshold: Int = DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
    private var lowPressureAlertUid: String? = null
    private var highPressureAlertUid: String? = null


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PressureService onCreate()")

        // mark service as running (used by ServiceRegistry)
        isRunning.set(true)

        // start as foreground service (required for long-running background sensor usage)
        startForegroundServiceCompat(
            notificationId = PRESSURE_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        // initialize log file
        //logFile = File("/sdcard/Download/pressure_test.log")

        startPressureCollectors()
        // begin listening to pressure sensor
        startPressureSensor()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PressureService onStartCommand()")

        // service should not restart automatically if killed
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PressureService onDestroy()")

        // mark service as stopped
        isRunning.set(false)
        serviceScope.cancel()
        // unregister sensor listener
        stopPressureSensor()
        // remove foreground notification
        stopForegroundServiceCompat()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // stop service if app task is removed
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // initializes pressure sensor and registers callback listener
    private fun startPressureSensor() {
        Log.d(TAG, "PressureService startPressureSensor()")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        if (pressureSensor != null) {
            // register listener at normal update rate
            DiagnosticResources.acquire(DiagnosticResource.PRESSURE, sensorEventListener,
                periodUs = 200_000, successful = { it }) {
                sensorManager.registerListener(sensorEventListener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            Log.d(TAG, "Pressure sensor callback registered")
        } else {
            // device does not have a barometer
            Log.e(TAG, "Pressure sensor is null. device likely has no barometer")
            //Utils.writeToLogFileLocation(logFile, "pressure sensor is null (no barometer?)")
        }
    }

    // stops pressure sensor updates
    private fun stopPressureSensor() {
        DiagnosticResources.release(DiagnosticResource.PRESSURE, sensorEventListener) {
            sensorManager.unregisterListener(sensorEventListener)
        }
    }

    private fun startEnableImmersionAlertsCollector() {
        serviceScope.launch {
            dataRepository.effectiveEnableImmersionAlerts.distinctUntilChanged().collect { it ->
                Log.i(TAG, "startEnableImmersionAlertsCollector: getEnableImmersionAlerts=$it")
                enableImmersionAlerts = it
                if (!enableImmersionAlerts) {
                    if (null != submergeUid) {
                        dataRepository.clearPhysioAndAutoGenAlertRequestUid(submergeUid!!)
                        submergeUid = null
                    }
                    if (null != shallowDunkUid) {
                        dataRepository.removePhysioAndAutoGenAlertByUid(shallowDunkUid!!)
                        shallowDunkUid = null
                    }
                    if (null != dunkUid) {
                        dataRepository.removePhysioAndAutoGenAlertByUid(dunkUid!!)
                        dunkUid = null
                    }
                }
            }
        }
    }
    private fun startPressureCollectors() {
        startEnableImmersionAlertsCollector()
        serviceScope.launch {
            dataRepository.effectiveEnableLowAtmPressureAlert.distinctUntilChanged().collect { it ->
                Log.i(TAG, "getEnableLowAtmPressureAlert=$it")
                enableLowAtmPressureAlert = it
                if (!enableLowAtmPressureAlert) {
                    if (null != lowPressureAlertUid) {
                        Log.i(TAG, "Clearing Low Pressure Alert")
                        dataRepository.clearPhysioAndAutoGenAlertRequestUid(lowPressureAlertUid!!)
                        lowPressureAlertUid = null
                    }
                }
            }
        }
        serviceScope.launch {
            dataRepository.getLowAtmPressureAlertThreshold.distinctUntilChanged().collect { it ->
                Log.i(TAG, "getLowAtmPressureAlertThreshold=$it")
                lowPressureThreshold = it
            }
        }
        serviceScope.launch {
            dataRepository.effectiveEnableHighAtmPressureAlert.distinctUntilChanged().collect { it ->
                Log.i(TAG, "getEnableHighAtmPressureAlert=$it")
                enableHighAtmPressureAlert = it
                if (!enableHighAtmPressureAlert) {
                    if (null != highPressureAlertUid) {
                        Log.i(TAG, "Clearing High Pressure Alert")
                        dataRepository.clearPhysioAndAutoGenAlertRequestUid(highPressureAlertUid!!)
                        highPressureAlertUid = null
                    }
                }
            }
        }
        serviceScope.launch {
            dataRepository.getHighAtmPressureAlertThreshold.distinctUntilChanged().collect { it ->
                Log.i(TAG, "getHighAtmPressureAlertThreshold=$it")
                highPressureThreshold = it
            }
        }
    }

    // sensor callback listener
    private val sensorEventListener = object : SensorEventListener {
        private var accuracyVal = -1

        // called when sensor accuracy changes
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            accuracyVal = accuracy
            Log.i(TAG, "PressureService onAccuracyChanged(): accuracy=$accuracy")
        }

        // called whenever new pressure data is available
        override fun onSensorChanged(event: SensorEvent) {
            // ignore non-pressure events
            if (event.sensor.type != Sensor.TYPE_PRESSURE) return

            // build pressure reading model
            val reading = PressureReading(
                pressure = event.values[0],   // pressure value in hPa
                timestamp = System.currentTimeMillis()
            )

            // store latest reading + update history (capped at 20)
            runBlocking {
                dataRepository.storeLatestPressure(reading)
            }

            // Emit to CEP bus so sample-window features (SampleWindow, SampleDelta, SampleSlope)
            // can compute over the pressure ring in FeatureStore.
            val cepEvt = CepEvent(
                topic = Topics.PRESSURE,
                payload = PressurePayload(
                    hpa = reading.pressure,
                    accuracy = accuracyVal,
                    sampleIndex = sampleIndex++,
                    sourceId = "baro_0"
                ),
                ts = reading.timestamp
            )
            if (!CepBus.tryEmit(cepEvt)) {
                serviceScope.launch { CepBus.emit(cepEvt) }
            }

            /*
            val history = dataRepository.pressureHistory.value
            val now = reading.timestamp

            // confirmed dunk first
            val dunkConfirmed = isDunkDetected(history)
            if (dunkConfirmed) {
                if ((now - lastDunkTs) > DUNK_COOLDOWN_MS) {
                    lastDunkTs = now
                    Log.i(TAG, "dunk detected")
                    //Utils.writeToLogFileLocation(logFile, "DUNK DETECTED: latest=${history.lastOrNull()} size=${history.size}")
                    if (enableImmersionAlerts && (null == dunkUid)) {
                        dunkUid = UUID.randomUUID().toString()
                        runBlocking {
                            dataRepository.appendPhysioAndAutoGenAlert(
                                DataRepository.CustomEmergencyAlert(
                                    uuid = dunkUid!!,
                                    alertState = DataRepository.AlertState.ALERT,
                                    category = "CAT_IMMERSION_WARN",
                                    description = "Probable immersion warning",
                                    priority = 2,
                                    sendSms = false,
                                    timeMillis = now,
                                    enqueuedToBeSent = false
                                )
                            )
                        }
                    }
                }
            } else {
                if (enableImmersionAlerts && (null != dunkUid)) {
                    runBlocking {
                        dataRepository.removePhysioAndAutoGenAlertByUid(dunkUid!!)
                        dunkUid = null
                    }
                }
                // shallow dunk only if not confirmed
                if (isShallowDunkDetected(history)) {
                    if ((now - lastShallowDunkTs) > SHALLOW_DUNK_COOLDOWN_MS) {
                        lastShallowDunkTs = now
                        Log.i(TAG, "shallow dunk detected")
                        //Utils.writeToLogFileLocation(logFile, "SHALLOW DUNK: delta=$delta latest=${history.lastOrNull()} size=${history.size}")
                        if (enableImmersionAlerts && (null == shallowDunkUid)) {
                            shallowDunkUid = UUID.randomUUID().toString()
                            runBlocking {
                                dataRepository.appendPhysioAndAutoGenAlert(
                                    DataRepository.CustomEmergencyAlert(
                                        uuid = shallowDunkUid!!,
                                        alertState = DataRepository.AlertState.ALERT,
                                        category = "CAT_IMMERSION_WARN",
                                        description = "Probable shallow immersion warning",
                                        priority = 2,
                                        sendSms = false,
                                        timeMillis = now,
                                        enqueuedToBeSent = false
                                    )
                                )
                            }
                        }
                    }
                } else {
                    if (enableImmersionAlerts && (null != shallowDunkUid)) {
                        runBlocking {
                            dataRepository.removePhysioAndAutoGenAlertByUid(shallowDunkUid!!)
                            shallowDunkUid = null
                        }
                    }
                }
            }
            handleSubmergeDetection(history, now)
            generateLowHighPressureAlerts(history, now)

             */


            // debug logging for verification
            //Log.i(TAG, "PressureService onSensorChanged(): acc=$accuracyVal, pressure=${reading.pressure} hpa, historySize=${history.size}")
            //Utils.writeToLogFileLocation(logFile, "acc=$accuracyVal, pressure=${reading.pressure}, latest=${dataRepository.latestPressure.value}, historySize=${history.size}")
        }
    }

    private fun generateLowHighPressureAlerts(history: List<PressureReading>, now: Long) {
        if (history.size < 5) return
        val avgPressure = history.takeLast(5).map { it.pressure }.average().toFloat()
        if (enableLowAtmPressureAlert) {
            if (avgPressure < lowPressureThreshold) {
                if (null == lowPressureAlertUid) {
                    Log.i(TAG, "Low pressure alert being sent out")
                    lowPressureAlertUid = UUID.randomUUID().toString()
                    runBlocking {
                        dataRepository.addPhysioAndAutoGenAlertRequest(
                            uid = lowPressureAlertUid!!,
                            category = "CAT_LOW_PRESSURE_ALERT",
                            description = "Low Atm Pressure Alert",
                            priority = 1,
                            sendSms = false,
                            timeMillis = now
                        )
                    }
                }
            } else {
                if (null != lowPressureAlertUid) {
                    Log.i(TAG, "Low pressure clear being sent out")
                    runBlocking {
                        dataRepository.clearPhysioAndAutoGenAlertRequestUid(lowPressureAlertUid!!)
                        lowPressureAlertUid = null
                    }
                }
            }
        }
        if (enableHighAtmPressureAlert) {
            if (avgPressure > highPressureThreshold) {
                if (null == highPressureAlertUid) {
                    Log.i(TAG, "High pressure alert being sent out")
                    highPressureAlertUid = UUID.randomUUID().toString()
                    runBlocking {
                        dataRepository.addPhysioAndAutoGenAlertRequest(
                            uid = highPressureAlertUid!!,
                            category = "CAT_HIGH_PRESSURE_ALERT",
                            description = "High Atm Pressure Alert",
                            priority = 1,
                            sendSms = false,
                            timeMillis = now
                        )
                    }
                }
            } else {
                if (null != highPressureAlertUid) {
                    Log.i(TAG, "High pressure clear being sent out")
                    runBlocking {
                        dataRepository.clearPhysioAndAutoGenAlertRequestUid(highPressureAlertUid!!)
                        highPressureAlertUid = null
                    }
                }
            }
        }
    }

    private fun getBaselinePressure(history: List<PressureReading>): Float {
        // need at least 10 samples
        if (history.size < 10) return 0f

        // use 5 stable samples before the latest readings
        val baselineWindow = history.dropLast(5).takeLast(5)

        // average pressure
        return baselineWindow.map { it.pressure }.average().toFloat()
    }

    private fun handleSubmergeDetection(history: List<PressureReading>, now: Long) {
        if (history.size < 10) return

        val latest = history.last()

        // set a stable baseline before the rise starts
        if (submergeBaseline == null) {
            val candidate = getBaselinePressure(history)
            val latestPressure = history.last().pressure

            if (latestPressure - candidate < SUBMERGE_THRESHOLD_HPA) {
                submergeBaseline = candidate
            }
        }

        val baseline = submergeBaseline ?: latest.pressure
        val deltaFromBaseline = latest.pressure - baseline

        if (deltaFromBaseline >= SUBMERGE_THRESHOLD_HPA) {
            if (submergeStartTs == null) {
                submergeStartTs = now
            }

            val duration = now - (submergeStartTs ?: now)

            if ((duration >= SUBMERGE_MIN_DURATION_MS) &&
                !isCurrentlySubmerged &&
                ((now - lastSubmergeTs) > SUBMERGE_COOLDOWN_MS)) {
                isCurrentlySubmerged = true
                lastSubmergeTs = now

                Log.i(TAG, "submerge detected")
                //Utils.writeToLogFileLocation(logFile, "SUBMERGE DETECTED: baseline=$baseline latest=${latest.pressure} delta=$deltaFromBaseline durationMs=$duration")
                if (enableImmersionAlerts && (null == submergeUid)) {
                    submergeUid = UUID.randomUUID().toString()
                    runBlocking {
                        dataRepository.addPhysioAndAutoGenAlertRequest(
                            uid = submergeUid!!,
                            category = "CAT_IMMERSION_ALERT",
                            description = "Immersion alert",
                            priority = 1,
                            sendSms = false,
                            timeMillis = now
                        )
                    }
                }
            }
        } else {
            // pressure returned near baseline
            submergeStartTs = null
            submergeBaseline = null
            isCurrentlySubmerged = false
            if (enableImmersionAlerts && (null != submergeUid)) {
                runBlocking {
                    dataRepository.clearPhysioAndAutoGenAlertRequestUid(submergeUid!!)
                    submergeUid = null
                }
            }
        }
    }


    // determines whether pressure change indicates a shallow dunk event
    private fun isShallowDunkDetected(history: List<PressureReading>): Boolean {
        // need enough samples to compare
        if (history.size < 10) return false

        val latest = history.last()
        val earlier = history[history.size - 5]
        val delta = latest.pressure - earlier.pressure

        return delta in SHALLOW_DUNK_MIN_HPA_CHANGE..SHALLOW_DUNK_MAX_HPA_CHANGE
    }

    // determines whether pressure change indicates a dunk event (deeper)
    private fun isDunkDetected(history: List<PressureReading>): Boolean {
        // need enough samples to compare
        if (history.size < 10) return false

        val latest = history.last()
        val earlier = history[history.size - 5]
        // calculate pressure change over recent samples
        val delta = latest.pressure - earlier.pressure

        return delta > DUNK_HPA_CHANGE
    }

    companion object {
        private val TAG = "WearTAK-" + PressureService::class.java.simpleName

        // foreground notification id
        private const val PRESSURE_SERVICE_NOTIFICATION_ID = 104

        // minimum time between dunk detections (milliseconds)
        private const val DUNK_COOLDOWN_MS = 5000L
        private const val DUNK_HPA_CHANGE = 5f

        // minimum time between shallow dunk detections (milliseconds)
        private const val SHALLOW_DUNK_COOLDOWN_MS = 3000L
        private const val SHALLOW_DUNK_MIN_HPA_CHANGE = 2f
        private const val SHALLOW_DUNK_MAX_HPA_CHANGE = 4f


        private const val SUBMERGE_THRESHOLD_HPA = 5f
        private const val SUBMERGE_MIN_DURATION_MS = 60_000L
        private const val SUBMERGE_COOLDOWN_MS = 10_000L

        // running state used by ServiceRegistry
        val isRunning = AtomicBoolean(false)
    }
}
