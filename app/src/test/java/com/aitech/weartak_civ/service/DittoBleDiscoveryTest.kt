package com.aitech.weartak_civ.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DittoBleDiscoveryTest {
    private class Fixture(
        val scope: TestScope,
        jitter: Double = 0.5,
        initialRecovery: BleRecoveryState = BleRecoveryState.ACTIVE
    ) {
        val changes = mutableListOf<Boolean>()
        val starts = mutableListOf<Long>()
        val records = mutableListOf<BleDiscoveryRecord>()
        val warnings = mutableListOf<BleLinkWarning>()
        val recoveries = mutableListOf<BleRecoveryState>()
        var succeeds = true
        val discovery = DittoBleDiscovery(scope, {
            if (succeeds) {
                changes += it
                if (it) starts += scope.testScheduler.currentTime
            }
            succeeds
        }, { records += it }, elapsedClock = { scope.testScheduler.currentTime }, jitter = { jitter },
            initialRecovery = initialRecovery, recoveryChanged = { recoveries += it },
            warningChanged = { warnings += it })

        val backoffs get() = records.filter { it.action == BleDiscoveryAction.BACKOFF }
        val warning get() = warnings.lastOrNull() ?: BleLinkWarning.NONE

        init {
            discovery.peersChanged(emptySet(), 0)
        }

        fun at(ms: Long) {
            scope.advanceTimeBy(ms - scope.testScheduler.currentTime)
            scope.runCurrent()
        }
    }

    @Test fun idleRetriesFollowTheCappedLadderUntilTheThirtyMinuteBudget() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(29_999)
        assertEquals(listOf(true), f.changes)
        f.at(30_000)
        assertEquals(listOf(true, false), f.changes)
        f.at(DAY_MS)
        assertEquals(listOf(0L, 60_000L, 150_000L, 270_000L, 420_000L, 570_000L, 720_000L,
            870_000L, 1_020_000L, 1_170_000L, 1_320_000L, 1_470_000L, 1_620_000L, 1_770_000L),
            f.starts)
        assertEquals(listOf(30_000L, 60_000L, 90_000L, 120_000L, 120_000L, 120_000L),
            f.backoffs.take(6).map { it.delayMs })
        assertEquals(listOf(0, 1, 2, 3, 3, 3), f.backoffs.take(6).map { it.backoffLevel })
        assertTrue(f.backoffs.all { it.reason == BleDiscoveryReason.WINDOW_EXPIRED })
        assertTrue(f.backoffs.all { it.delayMs <= DittoBleDiscovery.RETRY_MAX_DELAY_MS })
        assertTrue(f.changes.zipWithNext().all { (a, b) -> a != b })
        assertTrue(f.starts.drop(4).zipWithNext().all { (a, b) -> b - a == 150_000L })
        // Steady-state duty one 30 s window per 150 s (~20%); front-loaded short gaps make the
        // whole 30 min budget 14 windows = 420 s (~23%), then a hard stop.
        assertEquals(420_000L, f.starts.size * DittoBleDiscovery.WINDOW_MS)
        assertFalse(f.changes.last())
        assertEquals(listOf(BleLinkWarning.DISCONNECTED), f.warnings)
        assertEquals(listOf(BleRecoveryState.TIMED_OUT), f.recoveries)
        assertTrue(f.records.filter { it.action == BleDiscoveryAction.START }.drop(1)
            .all { it.reason == BleDiscoveryReason.RETRY })
        f.discovery.close()
    }

    @Test fun jitterBoundariesAreTenPercentAndClampedToTheTwoMinuteCap() = runTest {
        mapOf(
            0.0 to listOf(27_000L, 54_000L, 81_000L, 108_000L, 108_000L, 108_000L),
            1.0 to listOf(33_000L, 66_000L, 99_000L, 120_000L, 120_000L, 120_000L),
        ).forEach { (jitter, expected) ->
            val f = Fixture(this, jitter)
            f.discovery.setAllowed(true)
            runCurrent()
            f.at(f.scope.testScheduler.currentTime + 800_000)
            assertEquals(expected, f.backoffs.take(6).map { it.delayMs })
            assertTrue(f.starts[1] - f.starts[0] <= 63_000L)
            // Every closed-window-to-next-window gap stays at or under 120 s.
            assertTrue(f.starts.zipWithNext().all { (a, b) ->
                b - a - DittoBleDiscovery.WINDOW_MS <= DittoBleDiscovery.RETRY_MAX_DELAY_MS })
            f.discovery.close()
        }
    }

    @Test fun randomJitterStaysInsideNominalRangesAndNeverExceedsCap() = runTest {
        val values = generateSequence(0.0) { (it + 0.137) % 1.0 }.iterator()
        val records = mutableListOf<BleDiscoveryRecord>()
        val discovery = DittoBleDiscovery(this, { true }, { records += it },
            elapsedClock = { testScheduler.currentTime }, jitter = { values.next() })
        discovery.peersChanged(emptySet(), 0)
        discovery.setAllowed(true)
        runCurrent()
        repeat(45) {
            advanceTimeBy(DittoBleDiscovery.NO_LINK_BUDGET_MS)
            runCurrent()
            discovery.retryNow()
        }
        val backoffs = records.filter { it.action == BleDiscoveryAction.BACKOFF }
        assertTrue(backoffs.size > 500)
        backoffs.forEach {
            val nominal = DittoBleDiscovery.RETRY_DELAYS_MS[it.backoffLevel]
            assertTrue("$it", it.delayMs in (nominal * 9 / 10)..minOf(nominal * 11 / 10,
                DittoBleDiscovery.RETRY_MAX_DELAY_MS))
        }
        assertTrue(backoffs.map { it.delayMs }.toSet().size > 10)
        discovery.close()
    }

    @Test fun anyDirectBleLinkKeepsDiscoveryOnAndPartialLossDoesNotStopIt() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(29_999)
        f.discovery.peersChanged(setOf("ble-1", "ble-2", "lan"), 2)
        f.at(600_000)
        assertEquals(listOf(true), f.changes)
        f.discovery.peersChanged(setOf("ble-2", "lan"), 1)
        f.at(1_200_000)
        assertEquals(listOf(true), f.changes)
        assertTrue(f.backoffs.isEmpty())
        f.discovery.close()
    }

    @Test fun lastBleLinkLossReopensImmediatelyAndResetsLadderDuringCooldown() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(580_000) // ladder climbed; window 570-600 s open
        f.discovery.peersChanged(setOf("ble"), 1)
        f.at(900_000)
        assertEquals(true, f.changes.last())
        f.discovery.peersChanged(setOf("lan"), 0)
        runCurrent()
        f.at(929_999)
        assertTrue(f.changes.last())
        f.at(930_000)
        assertFalse(f.changes.last())
        assertEquals(30_000L, f.backoffs.last().delayMs)
        f.at(960_000)
        assertTrue(f.changes.last())
        f.at(990_000)
        assertFalse(f.changes.last())
        // Inside the topology cooldown: a new link and its loss still reopen at once.
        f.discovery.peersChanged(setOf("ble"), 1)
        runCurrent()
        assertTrue(f.changes.last())
        f.discovery.peersChanged(emptySet(), 0)
        f.at(1_019_999)
        assertTrue(f.changes.last())
        f.at(1_020_000)
        assertFalse(f.changes.last())
        assertEquals(30_000L, f.backoffs.last().delayMs)
        f.discovery.close()
    }

    @Test fun connectionGainedCancelsPendingRetryAndResetsLadder() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(610_000) // ladder at cap; retry pending for 720 s
        assertFalse(f.changes.last())
        f.discovery.peersChanged(setOf("ble"), 1)
        runCurrent()
        assertTrue(f.changes.last())
        val size = f.changes.size
        f.at(DAY_MS)
        assertEquals("connected: no retry timer may toggle BLE", size, f.changes.size)
        f.discovery.peersChanged(emptySet(), 0)
        f.at(DAY_MS + 30_000)
        assertFalse(f.changes.last())
        assertEquals(30_000L, f.backoffs.last().delayMs)
        f.at(DAY_MS + 60_000)
        assertTrue(f.changes.last())
        f.discovery.close()
    }

    @Test fun adapterOffCancelsPendingWorkAndOnBypassesCooldownWithReset() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(30_000)
        f.discovery.setRadioAvailable(false)
        BleDiscoveryReason.entries.forEach { f.discovery.request(it) }
        f.discovery.peersChanged(setOf("lan-new"), 0)
        f.at(40_000)
        f.at(45_000)
        assertEquals(listOf(true, false), f.changes)
        f.discovery.setRadioAvailable(true) // within the 300 s topology cooldown
        runCurrent()
        assertEquals(listOf(true, false, true), f.changes)
        assertEquals(BleDiscoveryReason.RADIO_ON, f.records.last().reason)
        f.at(75_000)
        assertEquals(30_000L, f.backoffs.last().delayMs)
        f.at(110_000)
        assertTrue(f.changes.last())
        f.discovery.setRadioAvailable(false) // mid-window
        runCurrent()
        assertFalse(f.changes.last())
        assertEquals(BleDiscoveryAction.STOP to BleDiscoveryReason.RADIO_OFF,
            f.records.last().action to f.records.last().reason)
        val size = f.changes.size
        f.at(DAY_MS)
        assertEquals(size, f.changes.size)
        f.discovery.close()
    }

    @Test fun adapterOffWhileLinkedStopsAndStaleLinkCountCannotReopenIt() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        f.discovery.peersChanged(setOf("ble"), 1)
        runCurrent()
        f.discovery.setRadioAvailable(false)
        f.discovery.peersChanged(setOf("ble", "lan"), 1)
        f.discovery.peersChanged(emptySet(), 0)
        f.at(600_000)
        assertEquals(listOf(true, false), f.changes)
        f.discovery.setRadioAvailable(true)
        runCurrent()
        assertEquals(listOf(true, false, true), f.changes)
        f.at(630_000)
        assertEquals(listOf(true, false, true, false), f.changes)
        f.discovery.close()
    }

    @Test fun rejectedEnableIsRetriedOnTheLadderInsteadOfSwallowed() = runTest {
        val f = Fixture(this)
        f.succeeds = false
        f.discovery.setAllowed(true)
        runCurrent()
        assertTrue(f.changes.isEmpty())
        assertTrue(f.records.none { it.action == BleDiscoveryAction.START })
        assertEquals(BleDiscoveryReason.ENABLE_FAILED to 30_000L,
            f.backoffs.last().reason to f.backoffs.last().delayMs)
        f.at(30_000) // first retry also missed
        assertEquals(BleDiscoveryReason.ENABLE_FAILED to 60_000L,
            f.backoffs.last().reason to f.backoffs.last().delayMs)
        f.succeeds = true
        f.at(90_000)
        assertEquals(listOf(true), f.changes)
        assertEquals(BleDiscoveryReason.RETRY, f.records.last().reason)
        f.at(120_000)
        assertEquals(listOf(true, false), f.changes)
        assertEquals(90_000L, f.backoffs.last().delayMs)
        f.discovery.close()
    }

    @Test fun rejectedStopIsRetriedAndNeverReportedAsStopped() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.succeeds = false
        f.at(30_000)
        assertTrue(f.records.none { it.action == BleDiscoveryAction.STOP })
        assertEquals(BleDiscoveryReason.STOP_FAILED, f.backoffs.last().reason)
        f.succeeds = true
        f.at(60_000)
        assertEquals(listOf(true, false), f.changes)
        assertEquals(BleDiscoveryAction.BACKOFF to BleDiscoveryReason.WINDOW_EXPIRED,
            f.records.last().action to f.records.last().reason)
        f.at(120_000)
        assertEquals(listOf(true, false, true), f.changes)
        f.succeeds = false
        f.discovery.setAllowed(false)
        assertTrue(f.changes.last())
        f.succeeds = true
        f.at(f.scope.testScheduler.currentTime + 480_000)
        assertEquals(listOf(true, false, true, false), f.changes)
        assertEquals(BleDiscoveryReason.GATED, f.records.last().reason)
        f.at(DAY_MS)
        assertEquals(4, f.changes.size)
        f.discovery.close()
    }

    @Test fun topologyCooldownSuppressesLanChurnButNeverTheRetryTimer() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(30_000)
        (31_000L until 60_000L step 1_000L).forEach {
            f.at(it)
            f.discovery.peersChanged(setOf("lan-$it"), 0)
            runCurrent()
        }
        assertEquals(listOf(true, false), f.changes)
        f.at(60_000)
        assertTrue(f.changes.last())
        // Expiry at 600 s renews the cooldown to 900 s; the capped retry still fires at 720 s.
        f.at(620_000)
        f.discovery.peersChanged(setOf("lan-a"), 0)
        runCurrent()
        assertFalse(f.changes.last())
        f.at(720_000)
        assertTrue(f.changes.last())
        assertEquals(BleDiscoveryAction.START to BleDiscoveryReason.RETRY,
            f.records.last().action to f.records.last().reason)
        f.discovery.peersChanged(setOf("lan-b"), 0)
        f.at(750_000)
        assertFalse(f.changes.last())
        assertEquals("LAN churn must not reset the ladder", 120_000L, f.backoffs.last().delayMs)
        f.discovery.close()
    }

    @Test fun explicitTriggersBypassCooldownResetLadderAndCannotExtendAWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(600_000) // ladder at cap
        listOf(BleDiscoveryReason.UI, BleDiscoveryReason.WRIST_ON, BleDiscoveryReason.SETTINGS).forEach {
            val opened = f.scope.testScheduler.currentTime
            f.discovery.request(it)
            runCurrent()
            assertTrue(f.changes.last())
            repeat(29) { second ->
                f.at(opened + (second + 1) * 1_000L)
                f.discovery.request(it)
                f.discovery.peersChanged(emptySet(), 0)
            }
            f.at(opened + DittoBleDiscovery.WINDOW_MS)
            assertFalse(f.changes.last())
            assertEquals(30_000L, f.backoffs.last().delayMs)
        }
        f.discovery.close()
    }

    @Test fun gatingCancelsRetriesAndTriggersCannotOverrideIt() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        f.discovery.peersChanged(setOf("ble"), 1)
        f.discovery.setAllowed(false)
        f.discovery.peersChanged(emptySet(), 0)
        BleDiscoveryReason.entries.forEach { f.discovery.request(it) }
        f.discovery.setRadioAvailable(false)
        f.discovery.setRadioAvailable(true)
        f.at(DAY_MS)
        assertEquals(listOf(true, false), f.changes)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(DAY_MS + 30_000)
        assertEquals(listOf(true, false, true, false), f.changes)
        assertEquals(30_000L, f.backoffs.last().delayMs)
        f.discovery.close()
    }

    @Test fun closingCancelsWindowAndRetryAndRejectsLateCallbacks() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.discovery.close()
        f.discovery.close()
        f.discovery.setAllowed(true)
        f.discovery.setRadioAvailable(false)
        f.discovery.setRadioAvailable(true)
        f.discovery.peersChanged(setOf("ble"), 1)
        f.discovery.request(BleDiscoveryReason.UI)
        f.at(DAY_MS)
        assertEquals(listOf(true, false), f.changes)
        assertEquals(BleDiscoveryAction.STOP to BleDiscoveryReason.CLOSED,
            f.records.last().action to f.records.last().reason)
        assertTrue(f.backoffs.isEmpty())

        val idle = Fixture(this)
        idle.discovery.setAllowed(true)
        runCurrent()
        idle.at(DAY_MS + 30_000) // retry pending
        val size = idle.records.size
        idle.discovery.close()
        idle.at(2 * DAY_MS)
        assertEquals(size, idle.records.size)
        assertFalse(idle.changes.last())
    }

    @Test fun aCancelledOldTimerCannotStopAReplacementWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(20_000)
        f.discovery.setAllowed(false)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(30_000)
        assertEquals(listOf(true, false, true), f.changes)
        f.at(50_000)
        assertEquals(listOf(true, false, true, false), f.changes)
        f.discovery.close()
    }

    @Test fun lanPeersNeverKeepBleOnAndIdenticalSnapshotsDoNotChangeTheSchedule() = runTest {
        val f = Fixture(this)
        f.discovery.peersChanged(setOf("lan"), 0)
        f.discovery.setAllowed(true)
        runCurrent()
        (1L..1_200L).forEach {
            f.at(it * 1_000L)
            f.discovery.peersChanged(setOf("lan"), 0)
        }
        assertEquals(listOf(0L, 60_000L, 150_000L, 270_000L, 420_000L, 570_000L, 720_000L,
            870_000L, 1_020_000L, 1_170_000L), f.starts)
        f.discovery.close()
    }

    @Test fun diagnosticOrdinalsAreAppendOnly() {
        assertEquals(41, DiagnosticEvent.MOTION_CONFIRMATION.ordinal)
        assertEquals(42, DiagnosticEvent.POWER_CONTEXT.ordinal)
        assertEquals(43, DiagnosticEvent.DEVICE_SETTING.ordinal)
        assertEquals(44, DiagnosticEvent.RESOURCE_LIFETIME.ordinal)
        assertEquals(45, DiagnosticEvent.MISSION_LIST.ordinal)
        assertEquals(46, DiagnosticEvent.OFF_BODY_TAK.ordinal)
        assertEquals(47, DiagnosticEvent.BLE_DISCOVERY.ordinal)
        assertEquals(listOf("START", "STOP", "BACKOFF", "WARNING_ON", "WARNING_OFF"),
            BleDiscoveryAction.entries.map { it.name })
        assertEquals(listOf("STARTUP", "UI", "WRIST_ON", "SETTINGS", "PRESENCE", "WINDOW_EXPIRED",
            "GATED", "CLOSED", "RETRY", "RADIO_ON", "RADIO_OFF", "BLE_LINK_LOST", "ENABLE_FAILED",
            "STOP_FAILED", "NO_LINK_TIMEOUT", "BLE_LINK_GAINED", "RETRY_NOW", "USER_PAUSE",
            "DITTO_RESET"), BleDiscoveryReason.entries.map { it.name })
        // Persisted by name.
        assertEquals(listOf("ACTIVE", "TIMED_OUT", "USER_PAUSED"), BleRecoveryState.entries.map { it.name })
    }

    @Test fun budgetRestoreRulesForRestartRebootAndCorruptValues() {
        val budget = DittoBleDiscovery.NO_LINK_BUDGET_MS
        val active = BleRecoveryState.ACTIVE
        fun r(state: BleRecoveryState, used: Long) = BleBudgetRestore(state, used)
        // Only a genuinely absent record is a fresh budget.
        assertEquals(r(active, 0L), restoreBleBudget(null, 5_000, 3))
        assertEquals(r(BleRecoveryState.TIMED_OUT, budget), restoreBleBudget("TIMED_OUT", 0, 3))
        assertEquals(r(BleRecoveryState.USER_PAUSED, budget), restoreBleBudget("USER_PAUSED", 0, null))
        // Same boot: counting downtime charged; frozen budget not charged.
        assertEquals(r(active, 700_000L), restoreBleBudget("v1;ACTIVE;600000;1;1000;3", 101_000, 3))
        assertEquals(r(active, 600_000L), restoreBleBudget("v1;ACTIVE;600000;0;1000;3", 101_000, 3))
        // Reboot: no refund, no reboot-downtime charge (even if elapsedRealtime is now higher).
        assertEquals(r(active, 600_000L), restoreBleBudget("v1;ACTIVE;600000;1;900000;3", 5_000, 4))
        assertEquals(r(active, 600_000L), restoreBleBudget("v1;ACTIVE;600000;1;1000;3", 900_000, 4))
        // Unknown boot identity: backwards elapsedRealtime is a reboot (carry, no refund)...
        assertEquals(r(active, 600_000L), restoreBleBudget("v1;ACTIVE;600000;1;900000;-1", 5_000, null))
        assertEquals(r(active, 600_000L), restoreBleBudget("v1;ACTIVE;600000;1;900000;3", 5_000, null))
        // ...but a reboot whose elapsedRealtime already passed the checkpoint is
        // indistinguishable from same boot and is conservatively charged, never refunded.
        assertEquals(r(active, 700_000L), restoreBleBudget("v1;ACTIVE;600000;1;900000;-1", 1_000_000, null))
        assertEquals(r(active, 700_000L), restoreBleBudget("v1;ACTIVE;600000;1;900000;-1", 1_000_000, 4))
        assertEquals(r(BleRecoveryState.TIMED_OUT, budget),
            restoreBleBudget("v1;ACTIVE;600000;1;1000;-1", 2_000_000, null))
        // Spent or over-budget records time out; nothing exceeds the cap.
        assertEquals(r(BleRecoveryState.TIMED_OUT, budget), restoreBleBudget("v1;ACTIVE;1700000;1;0;3", 100_000, 3))
        assertEquals(r(BleRecoveryState.TIMED_OUT, budget), restoreBleBudget("v1;ACTIVE;9999999;0;0;3", 0, 3))
        // Malformed, unknown-version or unreadable records fail closed as paused, never fresh.
        val corrupt = BleBudgetRestore(BleRecoveryState.USER_PAUSED, budget, corrupt = true)
        listOf(
            "", "?", BLE_BUDGET_UNREADABLE, "garbage", "garbage;x", "active", "ACTIVE",
            "ACTIVE;600000;1;1000;3", // pre-version layout
            "v2;ACTIVE;600000;1;1000;3", "v1;TIMED_OUT;600000;1;1000;3",
            "v1;ACTIVE;600000;1;1000", "v1;ACTIVE;600000;1;1000;3;9",
            "v1;ACTIVE;x;1;1000;3", "v1;ACTIVE;-5;1;1000;3",
            "v1;ACTIVE;600000;2;1000;3", "v1;ACTIVE;600000;;1000;3",
            "v1;ACTIVE;600000;1;-1;3", "v1;ACTIVE;600000;1;x;3",
            "v1;ACTIVE;600000;1;1000;-2", "v1;ACTIVE;600000;1;1000;x",
        ).forEach { assertEquals(it, corrupt, restoreBleBudget(it, 5_000, 3)) }
        assertEquals(null, encodeBleBudget(BleBudgetSnapshot(active, 0, false, 9), 3))
        assertEquals("v1;ACTIVE;0;1;9;3", encodeBleBudget(BleBudgetSnapshot(active, 0, true, 9), 3))
        assertEquals("v1;ACTIVE;5;0;9;-1", encodeBleBudget(BleBudgetSnapshot(active, 5, false, 9), null))
        assertEquals("USER_PAUSED", encodeBleBudget(BleBudgetSnapshot(BleRecoveryState.USER_PAUSED, 5, false, 9), 3))
        // Round trip.
        val encoded = encodeBleBudget(BleBudgetSnapshot(active, 42_000, true, 1_000), 3)
        assertEquals(r(active, 52_000), restoreBleBudget(encoded, 11_000, 3))
    }

    @Test fun budgetExpiresAtExactlyThirtyMinutesAndCancelsTheInFlightWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(1_799_999) // window 1770-1800 s open
        assertTrue(f.changes.last())
        assertTrue(f.warnings.isEmpty())
        f.at(1_800_000)
        assertFalse(f.changes.last())
        assertEquals(BleDiscoveryAction.STOP to BleDiscoveryReason.NO_LINK_TIMEOUT,
            f.records.first { it.action == BleDiscoveryAction.STOP && it.reason == BleDiscoveryReason.NO_LINK_TIMEOUT }
                .let { it.action to it.reason })
        assertEquals(BleDiscoveryAction.WARNING_ON to BleDiscoveryReason.NO_LINK_TIMEOUT,
            f.records.last().action to f.records.last().reason)
        assertEquals(listOf(BleLinkWarning.DISCONNECTED), f.warnings)
        val size = f.changes.size
        f.at(DAY_MS)
        assertEquals(size, f.changes.size)
        f.discovery.close()
    }

    @Test fun ineligiblePeriodsFreezeTheBudgetAndJitteredRetriesAreClippedAtItsEnd() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(50_000)
        f.discovery.setRadioAvailable(false) // 50 s used
        f.at(3_600_000)
        assertTrue(f.warnings.isEmpty())
        f.discovery.setRadioAvailable(true) // 1750 s left: ends at 5_350_000
        runCurrent()
        f.at(5_349_999)
        assertTrue(f.warnings.isEmpty())
        // Window at +1620 s expires at +1650 s; its 120 s retry would land past the budget.
        assertEquals(3_600_000L + 1_620_000L, f.starts.last())
        assertEquals(120_000L, f.backoffs.last().delayMs)
        f.at(5_350_000)
        assertEquals(listOf(BleLinkWarning.DISCONNECTED), f.warnings)
        assertEquals(3_600_000L + 1_620_000L, f.starts.last())
        assertFalse(f.changes.last())
        f.discovery.close()
    }

    @Test fun automaticEventsNeverOverrideTimeoutAndWarningIsHiddenWhileIneligible() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(1_800_000)
        val size = f.changes.size
        BleDiscoveryReason.entries.forEach { f.discovery.request(it) }
        f.discovery.peersChanged(setOf("lan-a"), 0)
        f.discovery.peersChanged(setOf("lan-b"), 0)
        f.discovery.setRadioAvailable(false)
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.discovery.setRadioAvailable(true)
        assertEquals(BleLinkWarning.DISCONNECTED, f.warning)
        f.discovery.setAllowed(false)
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.discovery.setAllowed(true)
        f.at(DAY_MS)
        assertEquals(size, f.changes.size)
        assertEquals(BleLinkWarning.DISCONNECTED, f.warning)
        assertEquals(listOf(BleRecoveryState.TIMED_OUT), f.recoveries)
        f.discovery.close()
        assertEquals(BleLinkWarning.NONE, f.warning)
    }

    @Test fun retryNowStartsANewBudgetWithAnImmediateWindowAndShortestGap() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(2_000_000)
        f.discovery.retryNow()
        runCurrent()
        assertTrue(f.changes.last())
        assertEquals(BleDiscoveryReason.RETRY_NOW, f.records.first { it.action == BleDiscoveryAction.START &&
            f.records.indexOf(it) > f.records.indexOfFirst { r -> r.action == BleDiscoveryAction.WARNING_ON } }.reason)
        assertEquals(listOf(BleLinkWarning.DISCONNECTED, BleLinkWarning.NONE), f.warnings)
        assertEquals(listOf(BleRecoveryState.TIMED_OUT, BleRecoveryState.ACTIVE), f.recoveries)
        f.at(2_030_000)
        assertEquals(30_000L, f.backoffs.last().delayMs)
        f.at(3_799_999)
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.at(3_800_000)
        assertEquals(BleLinkWarning.DISCONNECTED, f.warning)
        assertFalse(f.changes.last())
        f.discovery.close()
    }

    @Test fun retryNowWhileIneligibleWaitsForAutomaticEligibilityReturn() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(1_800_000)
        f.discovery.setRadioAvailable(false)
        val size = f.changes.size
        f.discovery.retryNow()
        runCurrent()
        assertEquals(size, f.changes.size)
        f.discovery.setRadioAvailable(true)
        runCurrent()
        assertTrue(f.changes.last())
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.discovery.close()
    }

    @Test fun pauseRetriesStaysPausedUntilRetryNowAndKeepsWorkingLinks() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        f.discovery.peersChanged(setOf("ble", "lan"), 1)
        runCurrent()
        f.discovery.pauseRetries() // a working link is never torn down
        assertEquals(listOf(true), f.changes)
        assertTrue(f.recoveries.isEmpty())
        f.discovery.peersChanged(setOf("lan"), 0)
        f.at(1_800_000 + 600_000)
        assertEquals(BleLinkWarning.DISCONNECTED, f.warning)
        f.discovery.pauseRetries()
        assertEquals(BleLinkWarning.PAUSED, f.warning)
        assertEquals(BleRecoveryState.USER_PAUSED, f.recoveries.last())
        val size = f.changes.size
        BleDiscoveryReason.entries.forEach { f.discovery.request(it) }
        f.discovery.setRadioAvailable(false)
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.discovery.setRadioAvailable(true)
        f.discovery.setAllowed(false)
        f.discovery.setAllowed(true)
        f.discovery.peersChanged(setOf("lan-new"), 0)
        f.at(DAY_MS)
        assertEquals(size, f.changes.size)
        assertEquals(BleLinkWarning.PAUSED, f.warning)
        f.discovery.retryNow()
        runCurrent()
        assertTrue(f.changes.last())
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.discovery.close()
    }

    @Test fun pauseDuringAnActiveBudgetCancelsTheWindowAndPendingRetry() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(10_000)
        f.discovery.pauseRetries()
        assertEquals(listOf(true, false), f.changes)
        assertEquals(BleDiscoveryReason.USER_PAUSE, f.records.first { it.action == BleDiscoveryAction.STOP }.reason)
        f.at(DAY_MS)
        assertEquals(listOf(true, false), f.changes)
        assertEquals(listOf(BleLinkWarning.PAUSED), f.warnings)
        f.discovery.close()
    }

    @Test fun realBleLinkEndsTimeoutAndItsLossStartsAFreshBudget() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(1_800_000)
        f.discovery.peersChanged(setOf("ble"), 1)
        runCurrent()
        assertTrue(f.changes.last())
        assertEquals(BleLinkWarning.NONE, f.warning)
        assertEquals(BleRecoveryState.ACTIVE, f.recoveries.last())
        f.at(5_000_000)
        f.discovery.peersChanged(emptySet(), 0)
        runCurrent()
        assertTrue(f.changes.last())
        f.at(6_799_999)
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.at(6_800_000)
        assertEquals(BleLinkWarning.DISCONNECTED, f.warning)
        f.discovery.close()
    }

    @Test fun explicitDittoResetClearsPauseAndGateReturnOpensAWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(1_800_000)
        f.discovery.pauseRetries()
        f.discovery.setAllowed(false) // user turns Ditto off
        f.discovery.resetRecovery()
        assertEquals(BleRecoveryState.ACTIVE, f.recoveries.last())
        f.discovery.setAllowed(true)
        runCurrent()
        assertTrue(f.changes.last())
        assertEquals(BleLinkWarning.NONE, f.warning)
        f.discovery.close()
    }

    @Test fun persistedTimeoutSurvivesRecreationWithoutOpeningAWindow() = runTest {
        val f = Fixture(this, initialRecovery = BleRecoveryState.TIMED_OUT)
        f.discovery.setAllowed(true)
        BleDiscoveryReason.entries.forEach { f.discovery.request(it) }
        f.at(DAY_MS)
        assertTrue(f.changes.isEmpty())
        assertEquals(listOf(BleLinkWarning.DISCONNECTED), f.warnings)
        val paused = Fixture(this, initialRecovery = BleRecoveryState.USER_PAUSED)
        paused.discovery.setAllowed(true)
        assertEquals(listOf(BleLinkWarning.PAUSED), paused.warnings)
        assertTrue(paused.changes.isEmpty())
        f.discovery.close()
        paused.discovery.close()
    }

    @Test fun rejectedStopAtTimeoutIsRetriedWhilePausedAndNeverReopens() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.at(1_790_000)
        f.succeeds = false
        f.at(1_800_000)
        assertTrue(f.changes.last())
        assertEquals(BleLinkWarning.DISCONNECTED, f.warning)
        assertEquals(BleDiscoveryReason.STOP_FAILED, f.backoffs.last().reason)
        f.succeeds = true
        f.at(DAY_MS)
        assertFalse(f.changes.last())
        assertEquals(BleDiscoveryReason.NO_LINK_TIMEOUT,
            f.records.last { it.action == BleDiscoveryAction.STOP }.reason)
        assertEquals(1, f.changes.count { !it } - f.changes.dropLast(1).count { !it })
        f.discovery.close()
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1_000L
    }
}
