package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.CrmInferenceState
import com.aitech.weartak_civ.data.CrmInferenceStatus
import com.aitech.weartak_civ.data.CrmThresholdRanges
import org.junit.Assert.*
import org.junit.Test

class CrmHomeWarningTest {
    private val thresholds = CrmThresholdRanges(33, 66)
    private val now = 100_000L
    private fun inference(score: Int) = CrmInferenceState(CrmInferenceStatus.ACCEPTED,
        score / 100f, updatedAtMillis = now)
    private fun warning(state: CrmInferenceState, enabled: Boolean = true, worn: Boolean = true,
        at: Long = now) = crmHomeWarning(state, thresholds, enabled, worn, at)

    @Test fun boundariesPreserveConfiguredThresholdsAndGoodClearsWarning() {
        listOf(0, 32, 33).forEach {
            assertEquals(CrmHomeWarning(it, CrmDisplayState.POOR), warning(inference(it)))
            assertEquals(AlertChipState.Emergency, warning(inference(it))!!.chipState)
        }
        listOf(34, 65, 66).forEach {
            assertEquals(CrmHomeWarning(it, CrmDisplayState.MODERATE), warning(inference(it)))
            assertEquals(AlertChipState.Warning, warning(inference(it))!!.chipState)
        }
        listOf(67, 100).forEach { assertNull(warning(inference(it))) }
        assertEquals(CrmDisplayState.MODERATE,
            crmHomeWarning(inference(25), CrmThresholdRanges(20, 70), true, true, now)!!.status)
        assertEquals(CrmThresholdRanges(33, 66), thresholds)
    }

    @Test fun freshnessIncludesThirtySecondsButRejectsStaleFutureAndMissingTimestamps() {
        assertNotNull(warning(inference(25), at = now + 30_000))
        assertNull(warning(inference(25), at = now + 30_001))
        assertNull(warning(inference(25), at = now - 1))
        assertNull(warning(inference(25).copy(updatedAtMillis = 0)))
    }

    @Test fun onlyEnabledWornAcceptedFiniteReadingsCanWarn() {
        assertNull(warning(inference(25), enabled = false))
        assertNull(warning(inference(25), worn = false))
        CrmInferenceStatus.entries.filter { it != CrmInferenceStatus.ACCEPTED }.forEach {
            assertNull(warning(inference(25).copy(status = it)))
        }
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f).forEach {
            assertNull(warning(inference(25).copy(value = it)))
        }
    }

    @Test fun everyPriorityCombinationPreservesEmergencyAndRedStatusWithoutElevatingDitto() {
        AlertChipState.entries.forEach { physio ->
            listOf(false, true).forEach { detection ->
                listOf(null, CrmDisplayState.GOOD, CrmDisplayState.MODERATE, CrmDisplayState.POOR).forEach { crm ->
                    listOf(false, true).forEach { ditto ->
                        val expected = when {
                            physio == AlertChipState.Emergency -> TopMetricAlertSource.PHYSIO
                            crm == CrmDisplayState.POOR -> TopMetricAlertSource.CRM
                            physio == AlertChipState.Warning -> TopMetricAlertSource.PHYSIO
                            detection -> TopMetricAlertSource.DETECTION
                            crm == CrmDisplayState.MODERATE -> TopMetricAlertSource.CRM
                            ditto -> TopMetricAlertSource.DITTO_BLE
                            else -> TopMetricAlertSource.NONE
                        }
                        assertEquals("$physio/$detection/$crm/$ditto", expected,
                            resolveTopMetricAlert(physio, detection, crm, ditto))
                    }
                }
            }
        }
    }
}
