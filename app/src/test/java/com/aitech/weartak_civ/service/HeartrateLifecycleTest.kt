package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.greenrobot.eventbus.EventBus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [RecordingHrSensorManager::class])
class HeartrateLifecycleTest {
    private lateinit var repo: DataRepository
    private lateinit var sensors: ShadowSensorManager
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(3_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
    }

    @Before fun setup() = runBlocking {
        HeartRateDelivery.samsungSource.value = null
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val activity = mockk<MainActivity>()
        every { activity.applicationContext } returns app
        MainActivity.mainActivity = activity
        repo = DataRepository(app)
        MainActivity.dataRepository = repo
        repo.storeEnablePhysiologicalServices(true)
        repo.storeEnableHeartRateService(true)
        repo.storeLatestIsDeviceWorn(true, "test-sensor")
        shadowOf(app).grantPermissions(Manifest.permission.BODY_SENSORS)
        sensors = shadowOf(app.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_HEART_RATE))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT))
        Unit
    }

    private fun service(): HeartrateService =
        Robolectric.buildService(HeartrateService::class.java).create().get()

    private fun event(type: Int, value: Float): SensorEvent {
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        return ShadowSensorManager.createSensorEvent(1, type).apply {
            values[0] = value
            timestamp = SystemClock.elapsedRealtimeNanos()
        }
    }

    private fun send(listener: SensorEventListener, event: SensorEvent) {
        listener.onAccuracyChanged(event.sensor, 3)
        listener.onSensorChanged(event)
    }

    @Test fun repositoryWornStateIsAuthoritativeAndRepeatedWakeKeepsTheSameListener() {
        val service = service()
        try {
            await { sensors.listeners.size == 1 }
            val listener = sensors.listeners.single()
            repeat(5) { service.onStartCommand(null, 0, it) }
            EventBus.getDefault().post(IsDeviceWornEvent(false))
            service.onTaskRemoved(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(listener), sensors.listeners)
            assertTrue(repo.latestIsDeviceWorn.value)
            assertTrue(HeartrateService.isRunning.get())
            runBlocking { repo.storeLatestIsDeviceWorn(false, "actual-sensor") }
            await { sensors.listeners.isEmpty() }
            runBlocking { repo.storeLatestIsDeviceWorn(true, "actual-sensor") }
            await { sensors.listeners.size == 1 }
        } finally { service.onDestroy() }
    }

    @Test fun refusedRegistrationRecoversButMonitoringOffAndRevokedPermissionNeverRestart() {
        sensors.forceListenersToFail = true
        val service = service()
        try {
            await { service.permission }
            assertTrue(sensors.listeners.isEmpty())
            sensors.forceListenersToFail = false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            await { sensors.listeners.size == 1 }
            runBlocking { repo.storeEnablePhysiologicalServices(false) }
            await { sensors.listeners.isEmpty() }
            repeat(3) { service.onStartCommand(null, 0, it) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertTrue(sensors.listeners.isEmpty())
            shadowOf(app).denyPermissions(Manifest.permission.BODY_SENSORS)
            runBlocking { repo.storeEnablePhysiologicalServices(true) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(sensors.listeners.isEmpty())
            shadowOf(app).grantPermissions(Manifest.permission.BODY_SENSORS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            await { sensors.listeners.size == 1 }
            runBlocking { repo.storeEnableHeartRateService(false) }
            await { sensors.listeners.isEmpty() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertTrue(sensors.listeners.isEmpty())
        } finally { service.onDestroy() }
    }

    @Test fun destroyedInstanceRejectsLateSamplesAndUnknownIsNotAConfirmedOffWristObservation() {
        val service = service()
        await { sensors.listeners.size == 1 }
        val listener = sensors.listeners.single()
        service.onDestroy()
        val before = repo.latestHeartRate.value
        send(listener, event(Sensor.TYPE_HEART_RATE, 112f))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, repo.latestHeartRate.value)
        assertTrue(sensors.listeners.isEmpty())
        assertFalse(HeartrateService.isRunning.get())
        runBlocking { repo.invalidateDeviceWornObservation() }
        assertNull(repo.latestDeviceWornObservation.value)
        assertFalse(repo.latestIsDeviceWorn.value)
        runBlocking { repo.storeLatestIsDeviceWorn(false, "actual-sensor") }
        assertEquals(false, repo.latestDeviceWornObservation.value)
    }

    @Test fun offBodyRecreationWaitsForRealObservationAndRejectsInvalidOrDestroyedCallbacks() {
        fun create() = Robolectric.buildService(OffBodyDetectService::class.java).create().get()
        fun send(service: OffBodyDetectService, value: Float) =
            send(service.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, value))
        val first = create()
        assertNull(repo.latestDeviceWornObservation.value)
        send(first, Float.NaN)
        send(first, 2f)
        assertNull(repo.latestDeviceWornObservation.value)
        send(first, 1f)
        assertEquals(true, repo.latestDeviceWornObservation.value)
        first.onTaskRemoved(null)
        assertEquals(true, repo.latestDeviceWornObservation.value)
        first.onDestroy()
        assertNull(repo.latestDeviceWornObservation.value)
        val second = create()
        try {
            send(first, 0f)
            assertNull(repo.latestDeviceWornObservation.value)
            send(second, 1f)
            assertTrue(repo.latestIsDeviceWorn.value)
            send(first, 0f)
            assertTrue(repo.latestIsDeviceWorn.value)
            send(second, 0f)
            assertEquals(false, repo.latestDeviceWornObservation.value)
        } finally { second.onDestroy() }
    }

    @Test fun hrRecreationRejectsOldGenerationSamplesButAcceptsRealCurrentSamples() {
        val first = service()
        await { sensors.listeners.size == 1 }
        val old = sensors.listeners.single()
        first.onDestroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val second = service()
        try {
            await { sensors.listeners.size == 1 }
            val current = sensors.listeners.single()
            val sample = event(Sensor.TYPE_HEART_RATE, 112f)
            val before = repo.latestHeartRate.value
            send(old, sample)
            assertEquals(before, repo.latestHeartRate.value)
            send(current, sample)
            assertEquals(112, repo.latestHeartRate.value)
            runBlocking { repo.storeLatestIsDeviceWorn(false, "actual-sensor") }
            await { sensors.listeners.isEmpty() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            runBlocking { repo.storeLatestIsDeviceWorn(true, "actual-sensor") }
            await { sensors.listeners.size == 1 }
            val invalidated = repo.latestHeartRate.value
            send(current, sample)
            assertEquals(invalidated, repo.latestHeartRate.value)
        } finally { second.onDestroy() }
    }

    @Test fun restartWaitsForActualWornStateButAcceptsFrameworkInitialOnChangeObservation() {
        val retained = event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 1f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val offBody = Robolectric.buildService(OffBodyDetectService::class.java).create().get()
        val heartRate = service()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(repo.latestDeviceWornObservation.value)
            assertFalse(repo.latestIsDeviceWorn.value)
            assertEquals(listOf(offBody.sensorEventListener), sensors.listeners)
            send(offBody.sensorEventListener, retained)
            assertEquals(true, repo.latestDeviceWornObservation.value)
            await { sensors.listeners.size == 2 }
            send(offBody.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 0f))
            await { sensors.listeners.size == 1 }
            assertFalse(repo.latestIsDeviceWorn.value)
            send(offBody.sensorEventListener, retained)
            assertFalse("An older observation must not overwrite a later off-wrist state", repo.latestIsDeviceWorn.value)
        } finally {
            heartRate.onDestroy()
            offBody.onDestroy()
        }
    }

    @Test fun offBodyRejectsImpossibleTimestampsAndRetainedOffWristDoesNotStartHeartRate() {
        val retained = event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val offBody = Robolectric.buildService(OffBodyDetectService::class.java).create().get()
        val heartRate = service()
        try {
            send(offBody.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 1f)
                .apply { timestamp = -1L })
            send(offBody.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 1f)
                .apply { timestamp += 1_000_000_000L })
            assertNull(repo.latestDeviceWornObservation.value)
            send(offBody.sensorEventListener, retained)
            assertEquals(false, repo.latestDeviceWornObservation.value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(listOf(offBody.sensorEventListener), sensors.listeners)
        } finally {
            heartRate.onDestroy()
            offBody.onDestroy()
        }
    }

    @Test fun backgroundRequestsThirtySecondsAndLiveDemandUpdatesSameListenerWithoutDroppingFifo() {
        val service = service()
        var live: AutoCloseable? = null
        try {
            await { sensors.listeners.size == 1 }
            val manager = sensors as RecordingHrSensorManager
            val listener = sensors.listeners.single()
            assertEquals(listOf(1_000_000 to 30_000_000), manager.registrations)
            val pending = event(Sensor.TYPE_HEART_RATE, 83f)
            live = HeartRateDelivery.requestLive()
            await { manager.registrations.last().second == 0 }
            assertEquals(listener, sensors.listeners.single())
            send(listener, pending)
            assertEquals(83, repo.latestHeartRate.value)
            assertEquals("83", HeartrateService.hrStringLiveData.value)
            live.close()
            await { manager.registrations.last().second == 30_000_000 }
            repeat(3) { service.onStartCommand(null, 0, it) }
            assertEquals(3, manager.registrations.size)
            assertEquals(listener, sensors.listeners.single())
        } finally {
            live?.close()
            service.onDestroy()
        }
    }

    @Test fun physiologicalAlertsUseImmediateDeliveryAndRestoreBatchingWhenDisabled() {
        runBlocking { repo.storeEnablePhysiologicalAlerts(false) }
        val service = service()
        try {
            await { sensors.listeners.size == 1 }
            val manager = sensors as RecordingHrSensorManager
            assertEquals(1_000_000 to HeartrateService.MAX_REPORT_LATENCY_US, manager.registrations.last())

            runBlocking { repo.storeEnablePhysiologicalAlerts(true) }
            await { manager.registrations.last().second == 0 }

            runBlocking { repo.storeEnablePhysiologicalAlerts(false) }
            await { manager.registrations.last().second == HeartrateService.MAX_REPORT_LATENCY_US }

            assertEquals(
                listOf(
                    1_000_000 to HeartrateService.MAX_REPORT_LATENCY_US,
                    1_000_000 to 0,
                    1_000_000 to HeartrateService.MAX_REPORT_LATENCY_US
                ),
                manager.registrations
            )
        } finally {
            service.onDestroy()
        }
    }

    @Test fun historicalBurstPreservesEveryValueAndMeasurementTimeAndPublishesNewestSnapshot() = runBlocking {
        val service = service()
        try {
            await { sensors.listeners.size == 1 }
            val listener = sensors.listeners.single()
            val samples = (0 until 60).map { index ->
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                event(Sensor.TYPE_HEART_RATE, (70 + index / 2).toFloat())
            }
            val deliveredAt = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtimeNanos()
            val received = mutableListOf<CepEvent<out CepPayload>>()
            val collector = launch(Dispatchers.Unconfined) {
                CepBus.events.filter { it.topic == Topics.HEART_RATE }.take(60).toList(received)
            }
            val timestampBounds = samples.map { sample ->
                val before = System.currentTimeMillis() - (elapsed - sample.timestamp) / 1_000_000L
                send(listener, sample)
                val after = System.currentTimeMillis() - (elapsed - sample.timestamp) / 1_000_000L
                before..after
            }
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(3_000) { collector.join() }
            assertEquals(samples.map { it.values[0].toDouble() },
                received.map { (it.payload as HeartRatePayload).bpm })
            received.zip(timestampBounds).forEach { (actual, bounds) ->
                assertTrue("Timestamp must reflect sensor age, not callback time", actual.ts in bounds)
                assertEquals(actual.ts, (actual.payload as HeartRatePayload).ts)
            }
            assertEquals(99, repo.latestHeartRate.value)
            val pli = repo.selfStatusInputs().first().heartRate!!
            assertEquals(99, pli.value)
            assertEquals(received.last().ts, pli.measuredAtMillis)
            assertTrue("Oldest sample retains its age", received.first().ts < deliveredAt - 50_000)
        } finally { service.onDestroy() }
    }

    @Test fun latencyChangeWaitsForFifoAndDoesNotAttemptDuplicateRegistration() {
        val service = service()
        var live: AutoCloseable? = null
        try {
            await { sensors.listeners.size == 1 }
            val manager = sensors as RecordingHrSensorManager
            manager.deferFlush = true
            val listener = sensors.listeners.single()
            val pending = event(Sensor.TYPE_HEART_RATE, 86f)
            live = HeartRateDelivery.requestLive()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(1_000_000 to 30_000_000), manager.registrations)
            assertEquals(0, manager.unregistrations)
            send(listener, pending)
            assertEquals(86, repo.latestHeartRate.value)
            (listener as SensorEventListener2).onFlushCompleted(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1_000_000 to 0, manager.registrations.last())
            assertEquals(1, manager.unregistrations)
            assertEquals(listOf(listener), sensors.listeners)
        } finally {
            live?.close()
            service.onDestroy()
        }
    }

    private inner class FakeSamsungSource : SamsungHeartRateSource {
        var running = false
        var refuse = false
        var refuseStop = false
        lateinit var sample: (HeartRateSample) -> Unit
        lateinit var failure: () -> Unit
        override fun start(onSample: (HeartRateSample) -> Unit, onFailure: () -> Unit): Boolean {
            assertTrue("Android must be stopped before Samsung attaches", sensors.listeners.isEmpty())
            sample = onSample
            failure = onFailure
            running = !refuse
            return running
        }
        override fun stop(): Boolean {
            if (refuseStop) return false
            running = false
            return true
        }
    }

    @Test fun sdkHandoverDrainsAndroidThenFailureFallsBackAndRejectsLateSdkCallbacks() {
        val service = service()
        val sdk = FakeSamsungSource()
        try {
            await { sensors.listeners.size == 1 }
            val manager = sensors as RecordingHrSensorManager
            manager.deferFlush = true
            val android = sensors.listeners.single()
            HeartRateDelivery.samsungSource.value = sdk
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(sdk.running)
            assertEquals(listOf(android), sensors.listeners)
            send(android, event(Sensor.TYPE_HEART_RATE, 84f))
            (android as SensorEventListener2).onFlushCompleted(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(sdk.running)
            assertTrue(sensors.listeners.isEmpty())
            sdk.sample(HeartRateSample(101, System.currentTimeMillis()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(101, repo.latestHeartRate.value)
            sdk.failure()
            await { !sdk.running && sensors.listeners.size == 1 }
            sdk.sample(HeartRateSample(180, System.currentTimeMillis()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(101, repo.latestHeartRate.value)
            repeat(3) { service.onStartCommand(null, 0, it) }
            assertFalse(sdk.running)
            HeartRateDelivery.samsungSource.value = null
            shadowOf(Looper.getMainLooper()).idle()
            manager.deferFlush = false
            HeartRateDelivery.samsungSource.value = sdk
            await { sdk.running && sensors.listeners.isEmpty() }
        } finally {
            service.onDestroy()
            HeartRateDelivery.samsungSource.value = null
        }
    }

    @Test fun sdkRefusalAndDisconnectUseAndroidAndMonitoringOffStopsBothSources() {
        val sdk = FakeSamsungSource().apply { refuse = true }
        HeartRateDelivery.samsungSource.value = sdk
        val service = service()
        try {
            await { sensors.listeners.size == 1 }
            assertFalse(sdk.running)
            val replacement = FakeSamsungSource()
            HeartRateDelivery.samsungSource.value = replacement
            await { replacement.running && sensors.listeners.isEmpty() }
            HeartRateDelivery.samsungSource.value = null
            await { !replacement.running && sensors.listeners.size == 1 }
            runBlocking { repo.storeEnableHeartRateService(false) }
            await { sensors.listeners.isEmpty() }
            assertFalse(replacement.running)
        } finally {
            service.onDestroy()
            HeartRateDelivery.samsungSource.value = null
        }
    }

    @Test fun sdkSamplesRespectPermissionOffWristAndDestroyedService() {
        val sdk = FakeSamsungSource()
        HeartRateDelivery.samsungSource.value = sdk
        val service = service()
        try {
            await { sdk.running }
            sdk.sample(HeartRateSample(91, System.currentTimeMillis()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(91, repo.latestHeartRate.value)
            shadowOf(app).denyPermissions(Manifest.permission.BODY_SENSORS)
            sdk.sample(HeartRateSample(180, System.currentTimeMillis()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(91, repo.latestHeartRate.value)
            service.onStartCommand(null, 0, 0)
            assertFalse(sdk.running)
            shadowOf(app).grantPermissions(Manifest.permission.BODY_SENSORS)
            service.onStartCommand(null, 0, 0)
            await { sdk.running }
            runBlocking { repo.storeLatestIsDeviceWorn(false, "test-sensor") }
            await { !sdk.running }
            sdk.sample(HeartRateSample(181, System.currentTimeMillis()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(-1, repo.latestHeartRate.value)
        } finally { service.onDestroy() }
        sdk.sample(HeartRateSample(182, System.currentTimeMillis()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(-1, repo.latestHeartRate.value)
        HeartRateDelivery.samsungSource.value = null
    }

    @Test fun failedSdkDetachNeverStartsSecondSourceAndReconciliationRetriesCleanup() {
        val sdk = FakeSamsungSource()
        HeartRateDelivery.samsungSource.value = sdk
        val service = service()
        try {
            await { sdk.running }
            sdk.refuseStop = true
            sdk.failure()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(sdk.running)
            assertTrue(sensors.listeners.isEmpty())
            sdk.refuseStop = false
            service.onStartCommand(null, 0, 0)
            await { !sdk.running && sensors.listeners.size == 1 }
        } finally {
            sdk.refuseStop = false
            service.onDestroy()
            HeartRateDelivery.samsungSource.value = null
        }
    }

    @Test fun sdkSamplesAlreadyDeliveredBeforeDisconnectAreNotLostWhileLateCallbacksAreRejected() {
        val sdk = FakeSamsungSource()
        HeartRateDelivery.samsungSource.value = sdk
        val service = service()
        try {
            await { sdk.running }
            sdk.sample(HeartRateSample(97, System.currentTimeMillis()))
            HeartRateDelivery.samsungSource.value = null
            service.onStartCommand(null, 0, 0)
            assertFalse(sdk.running)
            sdk.sample(HeartRateSample(180, System.currentTimeMillis()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(97, repo.latestHeartRate.value)
            assertEquals(1, sensors.listeners.size)
        } finally {
            service.onDestroy()
            HeartRateDelivery.samsungSource.value = null
        }
    }
}

@Implements(SensorManager::class)
class RecordingHrSensorManager : ShadowSensorManager() {
    val registrations = mutableListOf<Pair<Int, Int>>()
    var deferFlush = false
    var unregistrations = 0

    @Implementation
    public override fun registerListener(listener: SensorEventListener, sensor: Sensor,
        samplingPeriodUs: Int, maxReportLatencyUs: Int): Boolean {
        registrations.add(samplingPeriodUs to maxReportLatencyUs)
        check(!hasListener(listener, sensor)) { "Android rejects duplicate sensor registration" }
        return super.registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs)
    }

    @Implementation
    public override fun unregisterListener(listener: SensorEventListener) {
        unregistrations++
        super.unregisterListener(listener)
    }

    @Implementation
    public override fun flush(listener: SensorEventListener): Boolean =
        if (deferFlush) true else super.flush(listener)
}
