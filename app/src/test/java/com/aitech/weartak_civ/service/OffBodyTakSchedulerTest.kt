package com.aitech.weartak_civ.service

import android.app.AlarmManager
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OffBodyTakSchedulerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private var connects = 0
    private var closes = 0
    private val waiting = mutableListOf<Boolean>()
    private lateinit var scheduler: OffBodyTakScheduler

    @Before fun setup() {
        scheduler = OffBodyTakScheduler(context, { connects++ }, { closes++ }, waiting::add)
    }

    @After fun cleanup() {
        scheduler.stop()
        unmockkAll()
    }

    @Test fun recordsSleepWindowAndContinuousTransitionsWithoutPayloads() {
        mockkObject(AppDiagnostics.Companion)
        every { AppDiagnostics.record(any(), any()) } just Runs
        scheduler.update(false, true)
        alarms.nextScheduledAlarm.onAlarmListener.onAlarm()
        scheduler.update(true, true)
        listOf(0L, 2L, 1L).forEach { mode ->
            verify { AppDiagnostics.record(DiagnosticEvent.OFF_BODY_TAK, match {
                it[DiagnosticMetric.MODE] == mode &&
                    it[DiagnosticMetric.INTERVAL_SECONDS] == 180L &&
                    it[DiagnosticMetric.DURATION_MS] == 30_000L
            }) }
        }
    }

    @Test fun debugOverrideReconnectsOffWristAndCancelsStaleAlarmsAndWindowCloses() {
        scheduler.update(false, true)
        val stale = alarms.nextScheduledAlarm
        stale.onAlarmListener.onAlarm()
        assertTrue(scheduler.allows())
        scheduler.update(false, true, stayConnectedOffWrist = true)
        assertFalse(waiting.last())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        val beforeCloses = closes
        val beforeConnects = connects
        stale.onAlarmListener.onAlarm()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertTrue(scheduler.allows())
        assertEquals(beforeCloses, closes)
        assertEquals(beforeConnects, connects)
        scheduler.update(false, true, stayConnectedOffWrist = true)
        assertEquals(beforeConnects, connects)

        val disabledAt = SystemClock.elapsedRealtime()
        scheduler.update(false, true, stayConnectedOffWrist = false)
        assertFalse(scheduler.allows())
        assertTrue(waiting.last())
        assertEquals(beforeCloses + 1, closes)
        assertEquals(disabledAt + 180_000L, alarms.nextScheduledAlarm.triggerAtTime)
    }

    @Test fun debugOverrideWhileWaitingReconnectsImmediatelyAndRestoresAfterEligibilityChanges() {
        scheduler.update(false, true)
        assertFalse(scheduler.allows())
        scheduler.update(false, true, stayConnectedOffWrist = true)
        assertTrue(scheduler.allows())
        assertEquals(1, connects)
        assertFalse(waiting.last())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        scheduler.update(false, false, stayConnectedOffWrist = true)
        scheduler.update(false, true, stayConnectedOffWrist = true)
        assertEquals(2, connects)
        scheduler.stop()
        scheduler.update(false, true, stayConnectedOffWrist = true)
        assertFalse(scheduler.allows())
        assertEquals(2, connects)
    }

    @Test fun waitingStateTracksOnlyEligibleGapsAndClearsDuringWindowsPowerAndShutdown() {
        scheduler.update(false, true)
        assertEquals(true, waiting.last())
        alarms.nextScheduledAlarm.onAlarmListener.onAlarm()
        assertEquals(false, waiting.last())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(true, waiting.last())
        scheduler.update(false, true, chargingOrDocked = true)
        assertEquals(false, waiting.last())
        scheduler.update(false, true, chargingOrDocked = false)
        assertEquals(true, waiting.last())
        scheduler.update(false, false)
        assertEquals(false, waiting.last())
        scheduler.update(false, true)
        assertEquals(true, waiting.last())
        scheduler.stop()
        assertEquals(false, waiting.last())
    }

    @Test fun offWristSchedulesInexactOpportunityAndBoundsWindow() {
        val start = SystemClock.elapsedRealtime()
        scheduler.update(false, true)
        assertFalse(scheduler.allows())
        assertEquals(1, closes)
        val alarm = alarms.nextScheduledAlarm
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertEquals(start + 180_000L, alarm.triggerAtTime)
        assertNotEquals(0L, alarm.windowLengthMs)
        alarm.onAlarmListener.onAlarm()
        assertEquals(1, connects)
        assertTrue(scheduler.allows())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertFalse(scheduler.allows())
        assertEquals(2, closes)
        scheduler.stop()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun chargingReconnectsImmediatelyAndUnpluggingResumesOffWristBatching() {
        scheduler.update(false, true)
        val stale = alarms.nextScheduledAlarm
        scheduler.update(false, true, chargingOrDocked = true)
        assertTrue(scheduler.allows())
        assertEquals(1, connects)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        stale.onAlarmListener.onAlarm()
        scheduler.update(false, true, chargingOrDocked = true)
        assertEquals(1, connects)

        val unpluggedAt = SystemClock.elapsedRealtime()
        scheduler.update(false, true, chargingOrDocked = false)
        assertFalse(scheduler.allows())
        assertEquals(2, closes)
        val next = alarms.nextScheduledAlarm
        assertEquals(unpluggedAt + 180_000L, next.triggerAtTime)
        next.onAlarmListener.onAlarm()
        assertTrue(scheduler.allows())
        assertEquals(2, connects)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertFalse(scheduler.allows())
        assertEquals(3, closes)
    }

    @Test fun chargingDuringOpenWindowCancelsItsCloseWithoutExtendingAWakeLock() {
        scheduler.update(false, true)
        alarms.nextScheduledAlarm.onAlarmListener.onAlarm()
        scheduler.update(false, true, chargingOrDocked = true)
        val before = closes
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertTrue(scheduler.allows())
        assertEquals(before, closes)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun onWristPowerChangesDoNotDisconnectOrScheduleWindows() {
        scheduler.update(true, true)
        val before = connects
        scheduler.update(true, true, chargingOrDocked = true)
        scheduler.update(true, true, chargingOrDocked = false)
        assertEquals(before, connects)
        assertEquals(0, closes)
        assertTrue(scheduler.allows())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun wristOnCancelsAlarmsAndIgnoresStaleDelivery() {
        scheduler.update(false, true)
        val stale = alarms.nextScheduledAlarm
        scheduler.update(true, true)
        assertTrue(scheduler.allows())
        assertEquals(1, connects)
        stale.onAlarmListener.onAlarm()
        assertEquals(1, connects)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        scheduler.stop()
    }

    @Test fun disabledCompanionOrNetworkGateCancelsWindowAndFutureWork() {
        scheduler.update(false, true)
        val alarm = alarms.nextScheduledAlarm
        alarm.onAlarmListener.onAlarm()
        scheduler.update(false, false)
        assertFalse(scheduler.allows())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        val before = connects
        alarm.onAlarmListener.onAlarm()
        assertEquals(before, connects)
        scheduler.update(false, true)
        assertFalse(scheduler.allows())
        assertEquals(1, alarms.scheduledAlarms.size)
        scheduler.stop()
    }

    @Test fun duplicateObservationsAndShutdownCannotRestartWork() {
        scheduler.update(false, true)
        val alarm = alarms.scheduledAlarms.first()
        scheduler.update(false, true)
        assertEquals(1, alarms.scheduledAlarms.size)
        scheduler.stop()
        scheduler.update(false, true)
        alarm.onAlarmListener.onAlarm()
        assertEquals(0, connects)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun unknownRestoresContinuousAndCancelsWindowClose() {
        scheduler.update(false, true)
        alarms.nextScheduledAlarm.onAlarmListener.onAlarm()
        scheduler.update(null, true)
        val before = closes
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertTrue(scheduler.allows())
        assertEquals(before, closes)
        assertEquals(2, connects)
        scheduler.stop()
    }
}
