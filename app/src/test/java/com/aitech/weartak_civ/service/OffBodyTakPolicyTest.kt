package com.aitech.weartak_civ.service

import org.junit.Assert.*
import org.junit.Test

class OffBodyTakPolicyTest {
    @Test fun debugOverrideBypassesOnlyOffWristAndDisablingRestoresPowerAndWristPolicy() {
        for (worn in listOf(false, true, null)) {
            for (powered in listOf(false, true)) {
                val policy = OffBodyTakPolicy()
                policy.observe(worn, powered)
                policy.open(0)
                policy.observe(worn, powered, stayConnectedOffWrist = true)
                assertFalse(policy.batching)
                assertTrue(policy.allows(Long.MAX_VALUE))
                assertFalse(policy.observe(worn, powered, stayConnectedOffWrist = true))
                policy.observe(worn, powered, stayConnectedOffWrist = false)
                assertEquals(worn == false && !powered, policy.batching)
                assertEquals(!policy.batching, policy.allows(1))
            }
        }
    }

    @Test fun chargingOffWristIsContinuousAndUnpluggingStartsClosedBatching() {
        val policy = OffBodyTakPolicy()
        policy.observe(false)
        policy.open(0)
        assertTrue(policy.observe(false, chargingOrDocked = true))
        assertFalse(policy.batching)
        assertTrue(policy.allows(Long.MAX_VALUE))
        assertFalse(policy.observe(false, chargingOrDocked = true))
        assertTrue(policy.observe(false, chargingOrDocked = false))
        assertTrue(policy.batching)
        assertFalse(policy.allows(1))
        policy.open(180_000)
        assertTrue(policy.allows(209_999))
        assertFalse(policy.allows(210_000))
    }

    @Test fun powerChangesDoNotBatchWornOrUnknownState() {
        val policy = OffBodyTakPolicy()
        for (worn in listOf(true, null)) {
            for (powered in listOf(false, true, false)) {
                assertFalse(policy.observe(worn, powered))
                assertFalse(policy.batching)
                assertTrue(policy.allows(Long.MAX_VALUE))
            }
        }
    }

    @Test fun unknownAndWornNeverBatch() {
        val policy = OffBodyTakPolicy()
        assertTrue(policy.allows(0))
        assertFalse(policy.observe(null))
        assertFalse(policy.observe(true))
        assertTrue(policy.allows(Long.MAX_VALUE))
    }

    @Test fun offWristRequiresWindowAndCapsEvenAnUnconnectedAttempt() {
        val policy = OffBodyTakPolicy()
        assertTrue(policy.observe(false))
        assertFalse(policy.allows(0))
        policy.open(180_000)
        assertTrue(policy.allows(209_999))
        assertFalse(policy.allows(210_000))
        assertFalse(policy.allows(210_001))
        policy.close()
        assertFalse(policy.allows(180_000))
        assertEquals(180_000L, OffBodyTakPolicy.INTERVAL_MS)
        assertEquals(30_000L, OffBodyTakPolicy.WINDOW_MS)
    }

    @Test fun duplicateObservationDoesNotExtendWindowAndWristOnIsImmediate() {
        val policy = OffBodyTakPolicy()
        policy.observe(false)
        policy.open(1_000)
        assertFalse(policy.observe(false))
        assertFalse(policy.allows(31_000))
        assertTrue(policy.observe(true))
        assertTrue(policy.allows(31_000))
        policy.close()
        assertTrue(policy.allows(31_000))
    }

    @Test fun sensorInvalidationRestoresContinuousAndNewOffWristStartsClosed() {
        val policy = OffBodyTakPolicy()
        policy.observe(false)
        policy.open(0)
        assertTrue(policy.observe(null))
        assertTrue(policy.allows(99_000))
        assertTrue(policy.observe(false))
        assertFalse(policy.allows(1))
    }

    @Test fun diagnosticOrdinalsRemainAppendOnly() {
        assertEquals(41, DiagnosticEvent.MOTION_CONFIRMATION.ordinal)
        assertEquals(42, DiagnosticEvent.POWER_CONTEXT.ordinal)
        assertEquals(43, DiagnosticEvent.DEVICE_SETTING.ordinal)
        assertEquals(44, DiagnosticEvent.RESOURCE_LIFETIME.ordinal)
        assertEquals(45, DiagnosticEvent.MISSION_LIST.ordinal)
        assertEquals(46, DiagnosticEvent.OFF_BODY_TAK.ordinal)
    }
}
