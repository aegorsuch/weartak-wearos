package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.BleLinkWarning
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w300dp-h300dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DittoBleRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun sharedPriorityKeepsDittoBelowAllOtherWarningDomains() {
        assertEquals(TopMetricAlertSource.NONE,
            resolveTopMetricAlert(Utils.AlertChipState.None, false, null))
        listOf(BleLinkWarning.DISCONNECTED, BleLinkWarning.PAUSED).forEach { warning ->
            val ditto = warning != BleLinkWarning.NONE
            listOf(Utils.AlertChipState.Warning, Utils.AlertChipState.Emergency).forEach { physio ->
                assertEquals(TopMetricAlertSource.PHYSIO,
                    resolveTopMetricAlert(physio, true, CrmDisplayState.MODERATE, ditto))
            }
            assertEquals(TopMetricAlertSource.DETECTION,
                resolveTopMetricAlert(Utils.AlertChipState.None, true, CrmDisplayState.MODERATE, ditto))
            listOf(CrmDisplayState.POOR, CrmDisplayState.MODERATE).forEach { crm ->
                assertEquals(TopMetricAlertSource.CRM,
                    resolveTopMetricAlert(Utils.AlertChipState.None, false, crm, ditto))
            }
            assertEquals(TopMetricAlertSource.DITTO_BLE,
                resolveTopMetricAlert(Utils.AlertChipState.None, false, null, ditto))
            assertEquals(R.string.ditto_edge_sync_offline, dittoBleWarningLabel(warning))
        }
        assertNull(dittoBleWarningLabel(BleLinkWarning.NONE))
    }

    @Test fun warningUsesUserChosenCopyAndNeverClaimsMeshHealth() {
        assertEquals("Ditto Edge Sync Offline", context.getString(R.string.ditto_edge_sync_offline))
        assertEquals("Retry", context.getString(R.string.ditto_edge_sync_retry))
        listOf(R.string.ditto_edge_sync_offline, R.string.ditto_edge_sync_retry).forEach {
            val text = context.getString(it)
            assertFalse(text, text.contains("mesh", ignoreCase = true))
            assertFalse(text, text.contains("all peers", ignoreCase = true))
        }
    }

    @Test fun everyPrimaryAndSecondaryCombinationRoutesToItsOwnDomain() {
        val physio = mutableStateOf(Utils.AlertChipState.None)
        val detections = mutableStateOf(0)
        val crm = mutableStateOf<CrmDisplayState?>(null)
        val ditto = mutableStateOf(false)
        val actions = mutableListOf<String>()
        compose.setContent {
            val warnings = buildList {
                if (physio.value != Utils.AlertChipState.None) {
                    add(HomeStatusWarning("physio", TopMetricAlertSource.PHYSIO,
                        "Physio", physio.value) { actions += "physio" })
                }
                repeat(detections.value) {
                    add(HomeStatusWarning("detector-$it", TopMetricAlertSource.DETECTION,
                        "Detection", Utils.AlertChipState.Warning) { actions += "detection" })
                }
                crm.value?.takeIf { it != CrmDisplayState.GOOD }?.let {
                    add(HomeStatusWarning("crm", TopMetricAlertSource.CRM, "CRM",
                        if (it == CrmDisplayState.POOR) Utils.AlertChipState.Emergency else Utils.AlertChipState.Warning
                    ) { actions += "crm" })
                }
                if (ditto.value) {
                    add(HomeStatusWarning("ditto", TopMetricAlertSource.DITTO_BLE,
                        "Ditto", Utils.AlertChipState.Warning) { actions += "ditto" })
                }
            }
            WearTAKCIVTheme {
                HomeTopMetricCard("HR", "72bpm", TopDisplayMetric.HEART_RATE, R.drawable.heart_rate,
                    physio.value, if (physio.value == Utils.AlertChipState.None) null else "Physio",
                    { actions += "physio" }, warnings, { actions += "picker" })
            }
        }
        Utils.AlertChipState.entries.forEach { p ->
            (0..2).forEach { count ->
                listOf(null, CrmDisplayState.GOOD, CrmDisplayState.MODERATE, CrmDisplayState.POOR).forEach { c ->
                    listOf(false, true).forEach { d ->
                        compose.runOnIdle {
                            physio.value = p; detections.value = count; crm.value = c; ditto.value = d
                            actions.clear()
                        }
                        val source = when {
                            p == Utils.AlertChipState.Emergency -> TopMetricAlertSource.PHYSIO
                            c == CrmDisplayState.POOR -> TopMetricAlertSource.CRM
                            p == Utils.AlertChipState.Warning -> TopMetricAlertSource.PHYSIO
                            count > 0 -> TopMetricAlertSource.DETECTION
                            c == CrmDisplayState.MODERATE -> TopMetricAlertSource.CRM
                            d -> TopMetricAlertSource.DITTO_BLE
                            else -> TopMetricAlertSource.NONE
                        }
                        val primary = when (source) {
                            TopMetricAlertSource.CRM -> compose.onNodeWithTag("crm-warning-indicator")
                            TopMetricAlertSource.DETECTION -> compose.onNodeWithTag("audio-detection-warning")
                            TopMetricAlertSource.DITTO_BLE -> compose.onNodeWithTag("ditto_ble_warning_indicator")
                            TopMetricAlertSource.PHYSIO -> compose.onNodeWithText("Physio")
                            TopMetricAlertSource.NONE -> compose.onNodeWithText("72bpm")
                        }
                        primary.performClick()
                        val expected = when (source) {
                            TopMetricAlertSource.NONE, TopMetricAlertSource.PHYSIO -> "physio"
                            TopMetricAlertSource.CRM -> "crm"
                            TopMetricAlertSource.DITTO_BLE -> "ditto"
                            TopMetricAlertSource.DETECTION -> if (count == 2) "picker" else "detection"
                        }
                        assertEquals("$p/$count/$c/$d", listOf(expected), actions)
                        val sources = buildSet {
                            if (p != Utils.AlertChipState.None) add(TopMetricAlertSource.PHYSIO)
                            if (count > 0) add(TopMetricAlertSource.DETECTION)
                            if (c == CrmDisplayState.POOR || c == CrmDisplayState.MODERATE) add(TopMetricAlertSource.CRM)
                            if (d) add(TopMetricAlertSource.DITTO_BLE)
                        }
                        if (sources.any { it != source }) {
                            compose.onNodeWithTag("home-secondary-warnings").performClick()
                            assertEquals(listOf(expected, "picker"), actions)
                        } else compose.onNodeWithTag("home-secondary-warnings").assertDoesNotExist()
                    }
                }
            }
        }
    }

    @Test fun topMetricWarningFitsAtFontScale13WithTouchTarget() {
        val labels = listOf(context.getString(R.string.ditto_edge_sync_offline),
            "Ditto Edge Sync sin conexi\u00f3n", "Ditto Edge Sync \u00e7evrimd\u0131\u015f\u0131")
        var taps = 0
        val state = mutableStateOf(labels.first())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(340f / 160, 1.3f)) {
                WearTAKCIVTheme {
                    Box(Modifier.requiredSize((454f / (340f / 160)).dp)) {
                        HomeTopMetricCard("HR", "72", TopDisplayMetric.HEART_RATE, R.drawable.heart_rate,
                            Utils.AlertChipState.None, null, {},
                            listOf(HomeStatusWarning("ditto", TopMetricAlertSource.DITTO_BLE,
                                state.value, Utils.AlertChipState.Warning) { taps++ }))
                    }
                }
            }
        }
        val gutter = 40f * 340 / 160
        labels.forEachIndexed { index, label ->
            state.value = label
            compose.waitForIdle()
            val node = compose.onNodeWithText(label, useUnmergedTree = true).assertExists()
            val layouts = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertFalse("$label overflows", layouts.single().hasVisualOverflow)
            val chip = compose.onNodeWithTag("ditto_ble_warning_indicator")
            val bounds = chip.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 48f * 340 / 160 - 1)
            assertTrue("$label must stay clear of side status glyphs: $bounds",
                bounds.left >= gutter - 1 && bounds.right <= 454f - gutter + 1)
            chip.performClick()
            assertEquals(index + 1, taps)
        }
    }

    @Test fun dialogIsTitleOnlyWithRetryAndBackArrowOnOneRow() {
        var retry = 0
        var dismissed = 0
        val warning = mutableStateOf(BleLinkWarning.DISCONNECTED)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(340f / 160, 1.3f)) {
                WearTAKCIVTheme {
                    DittoBleRecoveryDialog(warning.value, { retry++ }, { dismissed++ })
                }
            }
        }
        listOf(BleLinkWarning.DISCONNECTED, BleLinkWarning.PAUSED).forEach { state ->
            warning.value = state
            compose.waitForIdle()
            compose.onNodeWithTag("ditto_ble_recovery_dialog").assertExists()
            compose.onNode(hasText(context.getString(R.string.ditto_edge_sync_offline)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
            val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }
            assertEquals("no helper text", listOf(context.getString(R.string.ditto_edge_sync_offline),
                context.getString(R.string.ditto_edge_sync_retry)), texts)
            val retryNode = compose.onNodeWithTag("ditto_ble_retry_now").assertHasClickAction()
            val back = compose.onNode(hasClickAction() and
                hasContentDescription(context.getString(R.string.route_back)))
            val r = retryNode.fetchSemanticsNode().boundsInRoot
            val b = back.fetchSemanticsNode().boundsInRoot
            val min = 48f * 340 / 160 - 1
            assertTrue("retry target $r", r.height >= min)
            assertTrue("back target $b", b.height >= min && b.width >= min)
            assertTrue("one row: $r $b", b.top < r.bottom && r.top < b.bottom && b.left >= r.right)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(context.getString(R.string.ditto_edge_sync_retry), useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("ditto_ble_pause_retries").assertDoesNotExist()
        }
        compose.onNodeWithTag("ditto_ble_retry_now").performClick()
        compose.onNode(hasClickAction() and hasContentDescription(context.getString(R.string.route_back)))
            .performClick()
        assertEquals(1, retry)
        assertEquals("Back only dismisses", 1, dismissed)
        warning.value = BleLinkWarning.NONE
        compose.waitForIdle()
        compose.onNodeWithTag("ditto_ble_recovery_dialog").assertDoesNotExist()
    }

    @Test fun settingsChipKeepsRetryReachableWhenSupersededAndHidesWhenNone() {
        var opened = 0
        val warning = mutableStateOf(BleLinkWarning.PAUSED)
        compose.setContent {
            WearTAKCIVTheme { DittoBleRecoverySettingsChip(warning.value) { opened++ } }
        }
        compose.onNodeWithTag("ditto_ble_recovery_settings")
            .assertTextContains(context.getString(R.string.ditto_edge_sync_offline))
            .performClick()
        assertEquals(1, opened)
        warning.value = BleLinkWarning.NONE
        compose.waitForIdle()
        compose.onNodeWithTag("ditto_ble_recovery_settings").assertDoesNotExist()
    }
}
