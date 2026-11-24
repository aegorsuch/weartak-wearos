package com.aitech.weartak_civ.service

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SkinTempSpo2StillnessTest {
    private lateinit var service: SkinTempService
    private val handler = Handler(Looper.getMainLooper())
    private val ppgControlHandler = Handler(Looper.getMainLooper())
    private val tracker = mockk<HealthTracker>(relaxed = true)
    private val ppg = mockk<HealthTracker>(relaxed = true)
    private val repo = mockk<DataRepository>(relaxed = true)
    private val motion = StationaryMotionPolicy()
    private val activity = MutableStateFlow(ActivityRecognized.STILL)
    private val confidence = MutableStateFlow<Int?>(100)

    @Before fun setup() {
        val main = mockk<MainActivity>(relaxed = true)
        every { main.applicationContext } returns RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = main
        MainActivity.dataRepository = repo
        every { repo.stationaryMotion } returns motion
        every { repo.latestActivityRecognized } returns activity
        every { repo.latestActivityConfidence } returns confidence
        every { repo.latestPressure } returns MutableStateFlow(null)
        mockkObject(AppDiagnostics)
        every { AppDiagnostics.record(any(), any()) } just Runs
        mockkObject(CepProvider)
        every { CepProvider.getOrCreateEngine(any()) } returns mockk(relaxed = true)
        every { CepProvider.getProfile(any()) } returns mockk(relaxed = true)
        service = Robolectric.buildService(SkinTempService::class.java).get()
        set("backgroundHandler", handler)
        set("ppgControlHandler", ppgControlHandler)
        set("spo2Tracker", tracker)
        set("ppgTracker", ppg)
        set("ppgTrackerType", HealthTrackerType.PPG_CONTINUOUS)
        set("ppgRunning", true)
        set("deviceWorn", true)
        set("ppgAvailable", true)
        set("spo2Sequencer", mockk<Spo2Sequencer>(relaxed = true))
    }

    @After fun cleanup() {
        if (get("crmPpgSamplingActive") == true) {
            crmAction("stopCrmPpgSampling")
            drain()
        }
        handler.removeCallbacksAndMessages(null)
        ppgControlHandler.removeCallbacksAndMessages(null)
        (get("serviceScope") as CoroutineScope).cancel()
        unmockkObject(AppDiagnostics)
        unmockkObject(CepProvider)
    }

    private fun get(name: String): Any? =
        SkinTempService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun set(name: String, value: Any) {
        SkinTempService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun opportunity() {
        SkinTempService::class.java.getDeclaredMethod("startSpo2ThenPpgCycle")
            .apply { isAccessible = true }.invoke(service)
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private fun crmAction(name: String) {
        SkinTempService::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(service)
    }

    @Test fun crmStopBeforeQueuedStartDoesNotStartTracker() {
        set("ppgTrackerType", HealthTrackerType.PPG_ON_DEMAND)
        set("ppgRunning", false)
        crmAction("startCrmPpgSampling")
        crmAction("stopCrmPpgSampling")
        drain()
        verify { ppg wasNot Called }
        assertEquals(false, get("crmPpgSamplingActive"))
    }

    @Test fun crmRapidStopAndRestartRetainsOnlyNewSamplingSession() {
        set("ppgTrackerType", HealthTrackerType.PPG_ON_DEMAND)
        set("ppgRunning", false)
        crmAction("startCrmPpgSampling")
        crmAction("stopCrmPpgSampling")
        crmAction("startCrmPpgSampling")
        drain()
        verify(exactly = 2) { ppg.setEventListener(any()) }
        verify(exactly = 1) { ppg.unsetEventListener() }
        assertEquals(true, get("crmPpgSamplingRequested"))
        assertEquals(true, get("crmPpgSamplingActive"))
    }

    @Test fun clearingSkinCallbacksDoesNotCancelCrmStart() {
        set("ppgTrackerType", HealthTrackerType.PPG_ON_DEMAND)
        set("ppgRunning", false)
        crmAction("startCrmPpgSampling")
        handler.removeCallbacksAndMessages(null)
        drain()
        verify(exactly = 1) { ppg.setEventListener(any()) }
        assertEquals(true, get("crmPpgSamplingActive"))
    }

    @Test fun queuedPpgCleanupReleasesOriginalTrackerAfterReplacement() {
        val replacement = mockk<HealthTracker>(relaxed = true)
        crmAction("stopPpgTracker")
        set("ppgTracker", replacement)
        drain()
        verify(exactly = 1) { ppg.unsetEventListener() }
        verify { replacement wasNot Called }
    }

    @Test fun duplicateWornEventDoesNotCancelSpo2TimeoutOrWaitingCrm() {
        startOnDemand()
        crmAction("startCrmPpgSampling")
        service.onIsDeviceWornEvent(IsDeviceWornEvent(true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        verify(exactly = 1) { tracker.unsetEventListener() }
        verify(exactly = 1) { ppg.setEventListener(any()) }
        assertEquals(true, get("crmPpgSamplingActive"))
    }

    @Test fun crmWaitsForActiveSpo2AndPpgBurstBeforeSampling() {
        val listener = startOnDemand()
        crmAction("startCrmPpgSampling")
        val point = mockk<DataPoint>()
        every { point.getValue(ValueKey.SpO2Set.STATUS) } returns 2
        every { point.getValue(ValueKey.SpO2Set.SPO2) } returns 98
        listener.onDataReceived(listOf(point))
        drain()
        verify(exactly = 1) { ppg.setEventListener(any()) }
        assertEquals(false, get("crmPpgSamplingActive"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        verify(exactly = 1) { ppg.unsetEventListener() }
        verify(exactly = 2) { ppg.setEventListener(any()) }
        assertEquals(true, get("crmPpgSamplingActive"))
    }

    @Test fun stalePpgPhaseCannotStartAfterWatchIsRemoved() {
        set("ppgTrackerType", HealthTrackerType.PPG_ON_DEMAND)
        set("ppgRunning", false)
        set("deviceWorn", false)
        crmAction("startPpgPhase")
        drain()
        verify { ppg wasNot Called }
    }

    @Test fun queuedSkinStartCannotRunAfterWatchIsRemoved() {
        val skin = mockk<HealthTracker>(relaxed = true)
        set("skinTracker", skin)
        crmAction("startSkinTracker")
        set("deviceWorn", false)
        drain()
        verify { skin wasNot Called }
    }

    @Test fun confirmedMotionSkipsWithoutSpendingAttemptOrTouchingOpticalTrackersAndRetriesNextOpportunity() {
        motion.confirm(SystemClock.elapsedRealtimeNanos() + 60_000_000_000L, null)
        opportunity()
        drain()
        verify { AppDiagnostics.record(DiagnosticEvent.SPO2_SKIPPED_MOTION,
            mapOf(DiagnosticMetric.SKIPPED to 1L)) }
        verify { tracker wasNot Called; ppg wasNot Called }
        assertEquals(0L, get("lastSpo2AttemptElapsedMs"))
        assertEquals(false, get("lastSpo2EndedAsTimeout"))
        assertEquals(0L, get("lastSpo2OutcomeWallMs"))
        assertNull(get("spo2TimeoutRunnable"))
        assertEquals("IDLE", get("measureState").toString())
        assertEquals(true, get("ppgRunning"))
        coVerify(exactly = 0) { repo.storeLatestSpo2(any()) }

        motion.hold()
        drain()
        verify { tracker wasNot Called }
        opportunity()
        drain()
        verify(exactly = 1) { tracker.setEventListener(any()) }
        verify(exactly = 1) { ppg.unsetEventListener() }
        assertEquals("SPO2_RUNNING", get("measureState").toString())
        assertNotNull(get("spo2TimeoutRunnable"))
    }

    @Test fun motionArrivingAfterSchedulingIsCheckedBeforeTrackerStarts() {
        opportunity()
        motion.confirm(SystemClock.elapsedRealtimeNanos() + 60_000_000_000L, 2.0)
        drain()
        verify { tracker wasNot Called; ppg wasNot Called }
        assertEquals(0L, get("lastSpo2AttemptElapsedMs"))
    }

    @Test fun skipPreservesPreviousTimeoutAndReadingState() {
        activity.value = ActivityRecognized.UNKNOWN
        set("lastSpo2AttemptElapsedMs", 10L)
        set("lastSpo2EndedAsTimeout", true)
        set("lastSpo2Pct", 97.0)
        opportunity()
        drain()
        assertEquals(10L, get("lastSpo2AttemptElapsedMs"))
        assertEquals(true, get("lastSpo2EndedAsTimeout"))
        assertEquals(97.0, get("lastSpo2Pct"))
        verify { tracker wasNot Called; ppg wasNot Called }
    }

    @Test fun stillOpportunityPreservesTimeoutBackoffAndCrmExclusion() {
        motion.hold()
        set("lastSpo2AttemptElapsedMs", SystemClock.elapsedRealtime())
        set("lastSpo2EndedAsTimeout", true)
        opportunity()
        drain()
        verify { tracker wasNot Called }
        set("lastSpo2AttemptElapsedMs", 0L)
        set("crmPpgSamplingRequested", true)
        opportunity()
        drain()
        verify { tracker wasNot Called; ppg wasNot Called }
        assertEquals(0L, get("lastSpo2AttemptElapsedMs"))
    }

    private fun startOnDemand(): HealthTracker.TrackerEventListener {
        set("ppgTrackerType", HealthTrackerType.PPG_ON_DEMAND)
        set("ppgRunning", false)
        opportunity()
        drain()
        val listener = slot<HealthTracker.TrackerEventListener>()
        verify(exactly = 1) { tracker.setEventListener(capture(listener)) }
        return listener.captured
    }

    private fun assertTimedOutWithoutPpg() {
        verify(exactly = 1) { tracker.unsetEventListener() }
        verify { ppg wasNot Called }
        assertNull(get("spo2TimeoutRunnable"))
        assertNull(get("ppgTimeoutRunnable"))
        assertEquals(false, get("ppgRunning"))
        assertEquals(true, get("lastSpo2EndedAsTimeout"))
        assertEquals("IDLE", get("measureState").toString())
    }

    @Test fun deadlineUnsetsSpo2AtThirtySecondsWithoutStartingPpgBurst() {
        startOnDemand()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(29_999))
        verify(exactly = 0) { tracker.unsetEventListener() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTimedOutWithoutPpg()
    }

    @Test fun samsungTimeoutStatusUnsetsImmediatelyAndCancelsDeadlineWithoutStartingPpg() {
        val listener = startOnDemand()
        val point = mockk<DataPoint>()
        every { point.getValue(ValueKey.SpO2Set.STATUS) } returns -6
        listener.onDataReceived(listOf(point))
        drain()
        assertTimedOutWithoutPpg()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        verify(exactly = 1) { tracker.unsetEventListener() }
    }

    @Test fun completedSpo2RetainsBoundedTwentySecondPpgBurst() {
        motion.hold()
        activity.value = ActivityRecognized.UNKNOWN
        confidence.value = 0
        val listener = startOnDemand()
        val point = mockk<DataPoint>()
        every { point.getValue(ValueKey.SpO2Set.STATUS) } returns 2
        every { point.getValue(ValueKey.SpO2Set.SPO2) } returns 98
        listener.onDataReceived(listOf(point))
        drain()
        verify(exactly = 1) { tracker.unsetEventListener() }
        verify(exactly = 1) { ppg.setEventListener(any()) }
        val sequencer = get("spo2Sequencer") as Spo2Sequencer
        verify { sequencer.onValid(
            any(), 98.0, any(), "UNKNOWN", any(), true, any(), true) }
        assertEquals("PPG_RUNNING", get("measureState").toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        verify(exactly = 1) { ppg.unsetEventListener() }
        assertEquals("IDLE", get("measureState").toString())
        assertNull(get("ppgTimeoutRunnable"))
    }

    @Test fun samsungErrorUnsetsImmediatelyWithoutStartingPpg() {
        startOnDemand().onError(HealthTracker.TrackerError.values().first())
        drain()
        assertTimedOutWithoutPpg()
    }
}
