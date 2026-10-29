/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.complication.PhysioStatusAndAlertingComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class PhysioAlertsActivity : ComponentActivity() {
    companion object {
        val TAG = "WearTAK-PhysioAlertsActivity"
        lateinit var dataRepository: DataRepository
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                val settingsVm: WearTAKSettingsViewModel = viewModel()
                PhysioAlertsApp({ finish() }, this, settingsVm)
            }
        }
    }
}

@Composable
fun PhysioAlertsApp(exitActivity: () -> Unit, context: Context, settingsVm: WearTAKSettingsViewModel) {
    val repo = MainActivity.dataRepository
    val license by repo.getLicense.collectAsState(initial = "")
    val navController = rememberSwipeDismissableNavController()
    val selected by settingsVm.savedSelectedPhysioMetric.collectAsState()
    val monitoring by settingsVm.savedEnablePhysiologicalServices.collectAsState()
    val alertsEnabled by settingsVm.savedEnablePhysiologicalAlerts.collectAsState()
    val environmentalEnabled by settingsVm.savedEnableEnvironmentalAlerts.collectAsState()
    val alerts by repo.physioAndAutoGenAlertList.collectAsState()
    LaunchedEffect(license) {
        if (!isLicenseValid()) {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
            exitActivity()
        }
    }
    val choices = topMetricChoices(context)
    WearTAKPageWithBackArrow(
        title = stringResource(R.string.top_display_metric),
        navController = navController,
        exitActivity = true,
        exitFunction = {
            requestPhysioStatusAndAlertingComplicationUpdate(context)
            exitActivity()
        },
        columnContent = {
            item {
                MetricMonitoringControls(monitoring, alertsEnabled, environmentalEnabled,
                    settingsVm::storeEnablePhysiologicalServices,
                    settingsVm::storeEnablePhysiologicalAlerts,
                    settingsVm::storeEnableEnvironmentalAlerts,
                    onThresholds = {
                        context.startActivity(settingsShortcutIntent(context, "physiological_alerts"))
                    },
                    onEnvironmental = {
                        context.startActivity(settingsShortcutIntent(context, "environmental_alerts"))
                    })
            }
            items(choices.size) { index ->
                val (metric, label) = choices[index]
                TopMetricChoice(label, topMetricValue(metric, repo), selected == metric.key) {
                    settingsVm.storeSelectedPhysioMetric(metric.key)
                }
            }
            items(alerts.size) { index ->
                val alert = alerts[index]
                if (alert.alertState == DataRepository.AlertState.ALERT) {
                    val color = when (alert.priority) {
                        1 -> Color.Red
                        2 -> Color.Yellow
                        else -> Color.Unspecified
                    }
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {},
                        colors = ChipDefaults.outlinedChipColors(),
                        label = { Text(alertCategoryFromString(alert.category), color = color) },
                        secondaryLabel = {
                            Column {
                                Text(alert.description)
                                Text("${utcToHoursMinutes(alert.timeMillis)} ${utcToDate(alert.timeMillis)}")
                            }
                        })
                }
            }
        })
}

@Composable
internal fun TopMetricChoice(label: String, value: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FittedTopMetricText("$label $value", modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body1)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun MetricMonitoringControls(
    monitoring: Boolean,
    alertsEnabled: Boolean,
    environmentalEnabled: Boolean,
    onMonitoring: (Boolean) -> Unit,
    onAlerts: (Boolean) -> Unit,
    onEnvironmentalToggle: (Boolean) -> Unit,
    onThresholds: () -> Unit,
    onEnvironmental: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricSettingsControl(
            title = stringResource(R.string.environmental_alerts),
            enabled = environmentalEnabled,
            onToggle = onEnvironmentalToggle,
            onClick = onEnvironmental
        )
        MetricAlertControls(alertsEnabled, onAlerts, onThresholds)
        MetricSettingsControl(
            title = stringResource(R.string.physiological_monitoring),
            enabled = monitoring,
            onToggle = onMonitoring,
            onClick = { onMonitoring(!monitoring) }
        )
    }
}

@Composable
internal fun MetricAlertControls(enabled: Boolean, onToggle: (Boolean) -> Unit, onThresholds: () -> Unit) {
    val thresholds = stringResource(R.string.metric_alert_thresholds)
    MetricSettingsControl(
        title = stringResource(R.string.physiological_alerts),
        enabled = enabled,
        onToggle = onToggle,
        onClick = onThresholds,
        actionDescription = thresholds
    )
}

@Composable
internal fun MetricSettingsControl(
    title: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    actionDescription: String? = null
) {
    SplitToggleChip(
        checked = enabled,
        onCheckedChange = onToggle,
        onClick = onClick,
        label = {
            FittedTopMetricText(title,
                modifier = if (actionDescription != null) Modifier.semantics {
                    contentDescription = actionDescription
                } else Modifier,
                minFontSize = 10.sp, maxLines = 2)
        },
        colors = ToggleChipDefaults.splitToggleChipColors(
            backgroundColor = if (enabled) MaterialTheme.colors.primary.copy(alpha = 0.5f)
                .compositeOver(MaterialTheme.colors.surface) else MaterialTheme.colors.surface,
            checkedToggleControlColor = MaterialTheme.colors.secondary,
            uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        toggleControl = { Switch(checked = enabled, onCheckedChange = null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    )
}

fun List<DataRepository.CustomEmergencyAlert>.alertChipState(isEnabled: Boolean?): Utils.AlertChipState = when {
    isEmpty() || isEnabled == false -> Utils.AlertChipState.None
    any { it.alertState == DataRepository.AlertState.ALERT && it.priority == 1 && isEnabled == true } -> Utils.AlertChipState.Emergency
    all { (it.alertState == DataRepository.AlertState.ALERT && it.priority == 2 || it.priority == 3) && isEnabled == true } -> Utils.AlertChipState.Warning
    else -> Utils.AlertChipState.None
}

fun alertCategoryFromString(category: String): String = when (category) {
    "CAT_COLD_FROSTBITE_OVERRIDE" -> "Cold Override: Frostbite Risk"
    "CAT_COLD_TACTILE_OVERRIDE" -> "Cold Override: Tactile Failure"
    "CAT_COLD_SKIN20_OVERRIDE" -> "Cold Override: Severe Peripheral Vasoconstriction"
    "CAT_COLD_DEXTERITY" -> "Cold Stress: Dexterity Impairment"
    "CAT_COLD_WARN", "CAT_COLD_ALERT", "CAT_COLD_EMERGENCY" -> "Cold Stress"
    "CAT_HR_HIGH" -> "High Resting HR"
    "CAT_HR_EMERGENCY", "CAT_HR_ALERT" -> "High Exertion"
    "CAT_HR_LOW", "CAT_HR_REST_LOW" -> "Low Resting HR"
    "CAT_IMMERSION_WARN" -> "Immersion Warning"
    "CAT_IMMERSION_ALERT" -> "Immersion Alert"
    "CAT_SPO2_WARN", "CAT_SPO2_ALERT" -> "Low SpO2"
    "CAT_SPO2_EMERGENCY" -> "Severe Hypoxemia"
    "CAT_LOW_PRESSURE_ALERT" -> "Low Pressure Alert"
    "CAT_HIGH_PRESSURE_ALERT" -> "High Pressure Alert"
    else -> "ERROR"
}

fun Float.formatDecimal(numberOfDecimals: Int = 2): String = "%.${numberOfDecimals}f".format(this)
fun Double.formatDecimal(numberOfDecimals: Int = 2): String = "%.${numberOfDecimals}f".format(this)

fun requestPhysioStatusAndAlertingComplicationUpdate(context: Context) {
    ComplicationDataSourceUpdateRequester.create(context,
        ComponentName(context, PhysioStatusAndAlertingComplicationService::class.java)).requestUpdateAll()
}
