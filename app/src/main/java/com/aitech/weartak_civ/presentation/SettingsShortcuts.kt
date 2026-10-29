package com.aitech.weartak_civ.presentation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import com.aitech.weartak_civ.R

private val settingsShortcutPages = setOf("environmental_alerts", "physiological_alerts", "reporting_strategy")

internal fun settingsShortcutIntent(context: Context, route: String): Intent {
    require(route in settingsShortcutPages)
    return Intent(context, WearTAKSettingsActivity::class.java)
        .putExtra(WearTAKSettingsActivity.EXTRA_OPEN_SETTINGS_PAGE, route)
}

internal fun settingsShortcutRoute(intent: Intent): String? =
    intent.getStringExtra(WearTAKSettingsActivity.EXTRA_OPEN_SETTINGS_PAGE)?.takeIf { it in settingsShortcutPages }

internal fun homeMetricIntent(context: Context): Intent =
    Intent(context, PhysioAlertsActivity::class.java)

internal fun alertPreferenceEntries(context: Context, page: String): List<Pair<String, String>> {
    val entries = when (page) {
        "alerting_preferences" -> listOf(
            "battery_alerts" to R.string.battery_alerts,
            "environmental_alerts" to R.string.environmental_alerts,
            "physiological_alerts" to R.string.physiological_alerts
        )
        "environmental_alerts" -> listOf(
            "atm_pressure_alerts" to R.string.atm_pressure_alerts,
            "immersion_alerts" to R.string.immersion_alerts
        )
        "physiological_alerts" -> listOf(
            "exertion_alerts" to R.string.exertion_alerts,
            "heart_rate_alerts" to R.string.heart_rate_alerts
        )
        else -> error("Unsupported alert preference page: $page")
    }
    val collator = java.text.Collator.getInstance(context.resources.configuration.locales[0])
    return entries.map { (route, label) -> route to context.getString(label) }
        .sortedWith { left, right -> collator.compare(left.second, right.second) }
}

@Composable
internal fun ReportingRateLocationIcon(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = stringResource(R.string.open_reporting_rate)
    Box(modifier.size(48.dp).clickable(onClickLabel = label, role = Role.Button) {
        context.startActivity(settingsShortcutIntent(context, "reporting_strategy"))
    }, contentAlignment = Alignment.Center) {
        Icon(painterResource(if (enabled) R.drawable.location_on else R.drawable.location_off),
            contentDescription = label, modifier = Modifier.size(20.dp),
            tint = if (enabled) Color.White else Color.DarkGray)
    }
}
