package com.aitech.weartak_civ.presentation

import android.location.Location
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.LocalContentColor
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.TopDisplayMetric
import java.util.Locale
import java.text.Collator

internal fun topMetricChoices(context: Context): List<Pair<TopDisplayMetric, String>> {
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    return TopDisplayMetric.entries.map { it to context.getString(it.label) }
        .sortedWith { a, b -> collator.compare(a.second, b.second) }
}

internal fun topMetricValue(
    metric: TopDisplayMetric,
    monitoring: Boolean,
    worn: Boolean,
    heartRate: Int?,
    exertion: Float,
    skinTemp: Float?,
    steps: Long?,
    locationEnabled: Boolean,
    location: Location?,
    disabled: String,
    offWrist: String,
    noFix: String,
    wornKnown: Boolean = true,
): String {
    if (metric.physiological && !monitoring) return disabled
    if (metric.physiological && metric != TopDisplayMetric.STEPS && !worn)
        return if (wornKnown) offWrist else "---"
    return when (metric) {
        TopDisplayMetric.EXERTION -> if (exertion.isFinite() && exertion >= 0) "${(exertion * 100).toInt()}%" else "---"
        TopDisplayMetric.HEART_RATE -> heartRate?.takeIf { it > 0 }?.toString() ?: "---"
        TopDisplayMetric.SKIN_TEMP -> skinTemp?.takeIf { it.isFinite() }?.let { "${it.formatDecimal(1)}°" } ?: "---"
        TopDisplayMetric.STEPS -> steps?.toString() ?: "---"
        TopDisplayMetric.LAT_LONG, TopDisplayMetric.MGRS -> {
            if (!locationEnabled || location == null || location.time <= 0 ||
                !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
                !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return noFix
            if (metric == TopDisplayMetric.LAT_LONG) {
                String.format(Locale.US, "%.5f/%.5f", location.latitude, location.longitude)
            } else if (location.latitude in -80.0..84.0) {
                Utils.latLonToMgrs(location.latitude, location.longitude)
            } else noFix
        }
    }
}

@Composable
internal fun FittedTopMetricText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.body1.copy(
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
    minFontSize: TextUnit = 0.25.sp,
    maxLines: Int = 1
) {
    BasicText(
        text = value,
        modifier = modifier.fillMaxWidth(),
        style = style.copy(
            color = if (style.color == Color.Unspecified) LocalContentColor.current else style.color,
            lineHeight = TextUnit.Unspecified
        ),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minFontSize, maxFontSize = style.fontSize, stepSize = 0.25.sp
        )
    )
}

@Composable
internal fun topMetricValue(metric: TopDisplayMetric, repo: DataRepository): String {
    ObserveLiveHeartRate(metric == TopDisplayMetric.HEART_RATE)
    val monitoring by repo.getEnablePhysiologicalServices.collectAsState(initial = MainActivity.enablePhysiologicalServices)
    val wornObservation by repo.latestDeviceWornObservation.collectAsState()
    val worn = wornObservation == true
    val heartRate by repo.latestHeartRate.collectAsState()
    val exertion by repo.latestExertion.collectAsState()
    val skinTemp by repo.latestSkinTemp.collectAsState()
    val daily by repo.latestDailyMetrics.collectAsState()
    val location by repo.latestLocation.collectAsState()
    val locationEnabled by repo.getEnableLocationService.collectAsState(initial = MainActivity.enableLocationService)
    val value = topMetricValue(metric, monitoring, worn, heartRate, exertion.toFloat(),
        skinTemp?.toFloat(), daily?.steps?.toLong(), locationEnabled, location,
        stringResource(R.string.disabled), stringResource(R.string.physio_not_worn),
        stringResource(R.string.metric_no_fix), wornKnown = wornObservation != null)
    return if (metric == TopDisplayMetric.HEART_RATE && monitoring && worn && heartRate?.let { it > 0 } == true) {
        value + stringResource(R.string.bpm)
    } else value
}
