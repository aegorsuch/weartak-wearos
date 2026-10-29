package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopMetricSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun alertMenusSortLocalizedLabelsWithoutChangingEntryAssociations() {
        val app = RuntimeEnvironment.getApplication()
        val expected = mapOf(
            "alerting_preferences" to listOf(
                "battery_alerts" to R.string.battery_alerts,
                "environmental_alerts" to R.string.environmental_alerts,
                "physiological_alerts" to R.string.physiological_alerts),
            "environmental_alerts" to listOf(
                "atm_pressure_alerts" to R.string.atm_pressure_alerts,
                "immersion_alerts" to R.string.immersion_alerts),
            "physiological_alerts" to listOf(
                "exertion_alerts" to R.string.exertion_alerts,
                "heart_rate_alerts" to R.string.heart_rate_alerts)
        )
        for (locale in listOf(java.util.Locale.US, java.util.Locale.GERMAN, java.util.Locale.FRENCH)) {
            val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(locale) }
            val localized = app.createConfigurationContext(config)
            val collator = java.text.Collator.getInstance(locale)
            expected.forEach { (page, associations) ->
                val result = alertPreferenceEntries(localized, page)
                assertEquals(associations.associate { it.first to localized.getString(it.second) }, result.toMap())
                assertTrue(result.zipWithNext().all { (a, b) -> collator.compare(a.second, b.second) <= 0 })
            }
        }
        assertEquals(listOf("battery_alerts", "environmental_alerts", "physiological_alerts"),
            alertPreferenceEntries(app, "alerting_preferences").map { it.first })
        assertEquals(listOf("exertion_alerts", "heart_rate_alerts"),
            alertPreferenceEntries(app, "physiological_alerts").map { it.first })
    }

    @Test fun alertThresholdNavigationAndSwitchAreIndependentEvenWhenOff() = verifyAlertControls()

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largeRoundPreservesIndependentTargets() = verifyAlertControls()

    private fun verifyAlertControls() {
        var enabled = false
        var navigation = 0
        compose.setContent {
            WearTAKCIVTheme { MetricAlertControls(enabled, { enabled = it }, { navigation++ }) }
        }
        compose.onNodeWithContentDescription("Change alert thresholds").assertIsDisplayed().performClick()
        compose.onNodeWithText("Change alert thresholds").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, navigation); assertFalse(enabled) }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(1, navigation); assertTrue(enabled) }
    }

    @Test fun selectedMetricRowsKeepNameAndLiveValueInlineAndRemainRadioSelectable() {
        var selections = 0
        compose.setContent {
            WearTAKCIVTheme {
                Column {
                    TopMetricChoice("Heart Rate", "Disabled", true) { selections++ }
                    TopMetricChoice("Lat/Long", "No fix", false) { selections++ }
                }
            }
        }
        compose.onNodeWithText("Heart Rate Disabled").assertIsSelected()
        compose.onNodeWithText("Disabled").assertDoesNotExist()
        compose.onNodeWithText("Off-Wrist").assertDoesNotExist()
        compose.onNodeWithText("No fix").assertDoesNotExist()
        compose.onNodeWithText("Lat/Long No fix").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun fullPrecisionCoordinatesFitNarrowReadoutOnOneLine() {
        val values = listOf("-79.12345/-179.98765", "60XWU1234598765",
            "Lat/Long -79.12345/-179.98765", "MGRS 60XWU1234598765", "Exertion 45%",
            "Heart Rate Off-Wrist", "Skin Temp Disabled")
        compose.setContent {
            WearTAKCIVTheme {
                Column {
                    values.forEach { value ->
                        Box(Modifier.width(96.dp)) { FittedTopMetricText(value) }
                    }
                }
            }
        }
        values.forEach { value ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().didOverflowWidth)
            assertFalse(layouts.single().isLineEllipsized(0))
        }
    }

    @Test fun locationStatusIconOpensExistingReportingRatePageWithoutTogglingLocation() {
        val app = RuntimeEnvironment.getApplication()
        compose.setContent { WearTAKCIVTheme { ReportingRateLocationIcon(false) } }
        compose.onNodeWithContentDescription("Open Reporting").performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(WearTAKSettingsActivity::class.java.name, intent.component!!.className)
        assertEquals("reporting_strategy", settingsShortcutRoute(intent))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("physiological_alerts", settingsShortcutRoute(settingsShortcutIntent(app, "physiological_alerts")))
        assertEquals("environmental_alerts", settingsShortcutRoute(settingsShortcutIntent(app, "environmental_alerts")))
    }

    @Test fun everyMetricAndAlertStateKeepsThePickerDestination() {
        val app = RuntimeEnvironment.getApplication()
        val selected = mutableStateOf(TopDisplayMetric.MGRS)
        val hasAlert = mutableStateOf(false)
        compose.setContent {
            val context = LocalContext.current
            HomeTopMetricCard(
                selected.value.name, "No fix", selected.value, R.drawable.heart_rate,
                Utils.AlertChipState.None, if (hasAlert.value) "Alert" else null,
                { context.startActivity(homeMetricIntent(context)) }
            )
        }
        for (metric in TopDisplayMetric.entries) {
            for (alert in listOf(false, true)) {
                compose.runOnIdle { selected.value = metric; hasAlert.value = alert }
                compose.onNode(hasClickAction()).performClick()
                val intent = shadowOf(app).nextStartedActivity
                assertEquals(PhysioAlertsActivity::class.java.name, intent.component!!.className)
                assertNull(settingsShortcutRoute(intent))
            }
        }
    }

    @Test fun mgrsCanReturnToHeartRateAndReachMonitoringControls() = verifyCoordinateEscape(TopDisplayMetric.MGRS)
    @Test fun latLongCanReturnToHeartRateAndReachMonitoringControls() = verifyCoordinateEscape(TopDisplayMetric.LAT_LONG)

    private fun verifyCoordinateEscape(initial: TopDisplayMetric) {
        val app = RuntimeEnvironment.getApplication()
        val selected = mutableStateOf(initial)
        val pickerOpen = mutableStateOf(false)
        var thresholds = 0
        var monitoringChanges = 0
        var alertChanges = 0
        compose.setContent {
            WearTAKCIVTheme {
                if (pickerOpen.value) {
                    Column {
                        MetricMonitoringControls(false, false, false,
                            { monitoringChanges++ }, { alertChanges++ }, {}, { thresholds++ }, {})
                        TopMetricChoice("Heart Rate", "Disabled", selected.value == TopDisplayMetric.HEART_RATE) {
                            selected.value = TopDisplayMetric.HEART_RATE
                        }
                    }
                } else {
                    HomeTopMetricCard(initial.name, "No fix", initial, R.drawable.heart_rate,
                        Utils.AlertChipState.None, null, {
                            assertEquals(PhysioAlertsActivity::class.java.name,
                                homeMetricIntent(app).component!!.className)
                            pickerOpen.value = true
                        })
                }
            }
        }
        compose.onNode(hasClickAction()).performClick()
        compose.onNodeWithText("Physiological Monitoring").assertIsDisplayed()
        compose.onNodeWithText("Physiological Alerts").assertIsDisplayed()
        compose.onNodeWithContentDescription("Change alert thresholds").performClick()
        compose.onNodeWithText("Heart Rate Disabled").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(TopDisplayMetric.HEART_RATE, selected.value)
            assertEquals(1, thresholds)
            assertEquals(0, monitoringChanges)
            assertEquals(0, alertChanges)
        }
    }
}
