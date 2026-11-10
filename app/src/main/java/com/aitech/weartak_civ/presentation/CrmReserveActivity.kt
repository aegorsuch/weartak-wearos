package com.aitech.weartak_civ.presentation

/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.CrmInferenceStatus
import com.aitech.weartak_civ.data.CrmThresholdRanges
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.CrmUiState
import com.aitech.weartak_civ.presentation.viewmodel.CrmViewModel
import com.aitech.weartak_civ.presentation.viewmodel.CrmViewModelFactory
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.aitech.weartak_civ.data.CrmDataRepository.CrmSamplingState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentSize

class CrmReserveActivity : ComponentActivity() {

    private val app: WearTakApplication
        get() = application as WearTakApplication

    private val crmViewModel: CrmViewModel by viewModels {
        CrmViewModelFactory(
            crmDataRepository = app.crmRepo,
            dataRepository = app.repo,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearTAKCIVTheme {
                BackHandler {
                    finish()
                }

                val uiState by crmViewModel.uiState.collectAsStateWithLifecycle()
                val samplingState by crmViewModel.samplingState.collectAsStateWithLifecycle()
                val waveformSamples by crmViewModel.waveformSamples.collectAsStateWithLifecycle()

                CrmReserveScreen(
                    uiState = uiState,
                    samplingState = samplingState,
                    waveformSamples = waveformSamples,
                    onSamplingClick = crmViewModel::onSamplingButtonClick,
                    onBackClick = { finish() },
                )
            }
        }
    }
}


@Composable
fun CrmReserveScreen(
    uiState: CrmUiState,
    samplingState: CrmDataRepository.CrmSamplingState,
    modifier: Modifier = Modifier,
    waveformSamples: List<Float>,
    onSamplingClick: () -> Unit,
    onBackClick: (() -> Unit)? = null,
) {
    val nowMillis = rememberNowMillisTicker()

    var showWaveform by remember {
        mutableStateOf(false)
    }

    val ageLabel = crmAgeLabel(
        nowMillis = nowMillis,
        lastAcceptedAtMillis = uiState.lastAcceptedAtMillis,
    )

    val backgroundColor = crmBackgroundColor(
        displayState = uiState.displayState,
        hasValidScore = uiState.hasValidScore,
    )

    val foregroundColor = crmForegroundColor(
        displayState = uiState.displayState,
        hasValidScore = uiState.hasValidScore,
    )

    val statusText = crmStatusText(
        uiState = uiState,
        samplingState = samplingState,
        ageLabel = ageLabel,
    )

    val samplingButtonText = when (samplingState) {
        CrmSamplingState.STARTING -> "Starting..."
        CrmSamplingState.SAMPLING -> "Stop"
        CrmSamplingState.STOPPING -> "Stopping..."
        CrmSamplingState.NOT_WORN -> "Not Worn"
        CrmSamplingState.UNAVAILABLE -> "PPG Unavailable"
        CrmSamplingState.ERROR -> "Retry Session"
        CrmSamplingState.IDLE -> "Start"
    }

//    val samplingButtonColor = when (samplingState) {
//        CrmSamplingState.IDLE,
//        CrmSamplingState.NOT_WORN -> Color.Gray
//        CrmSamplingState.ERROR -> CrmGoodColor
//
//        CrmSamplingState.STARTING,
//        CrmSamplingState.SAMPLING,
//        CrmSamplingState.STOPPING -> CrmPoorColor
//
//        CrmSamplingState.UNAVAILABLE -> Color.Gray
//    }


    val samplingButtonEnabled = samplingState != CrmSamplingState.STARTING &&
            samplingState != CrmSamplingState.STOPPING &&
            samplingState != CrmSamplingState.UNAVAILABLE


    val samplingButtonColor = when (samplingState) {
        CrmSamplingState.IDLE,
        CrmSamplingState.NOT_WORN,
        CrmSamplingState.ERROR -> Color(0xFF146C2E)

        CrmSamplingState.STARTING,
        CrmSamplingState.SAMPLING,
        CrmSamplingState.STOPPING -> Color(0xFF9E1623)

        CrmSamplingState.UNAVAILABLE -> Color(0xFF55575A)
    }

    val samplingButtonBorderColor = Color.White.copy(
        alpha = if (samplingButtonEnabled) 0.95f else 0.55f
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 34.dp,
                    end = 34.dp,
                    top = 10.dp,
                    bottom = 8.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (showWaveform) "PPG SIGNAL" else "CRM",
                color = foregroundColor.copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                textAlign = TextAlign.Center,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (showWaveform) {
                    CrmPpgWaveformContent(
                        samples = waveformSamples,
                        samplingState = samplingState,
                        foregroundColor = foregroundColor,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CrmScoreContent(
                        uiState = uiState,
                        statusText = statusText,
                        foregroundColor = foregroundColor,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        foregroundColor.copy(alpha = 0.18f)
                    )
                    .border(
                        width = 1.dp,
                        color = foregroundColor.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable {
                        showWaveform = !showWaveform
                    }
                    .padding(
                        horizontal = 14.dp,
                        vertical = 5.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (showWaveform) {
                        "Show CRM"
                    } else {
                        "Show PPG"
                    },
                    color = foregroundColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            Box(
                modifier = Modifier
                    .padding(
                        top = 6.dp,
                        bottom = 8.dp,
                    )
                    .clip(RoundedCornerShape(50))
                    .background(
                        samplingButtonColor.copy(
                            alpha = if (samplingButtonEnabled) 1f else 0.65f
                        )
                    )
                    .border(
                        width = 2.dp,
                        color = samplingButtonBorderColor,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(enabled = samplingButtonEnabled) {
                        onSamplingClick()
                    }
                    .padding(
                        horizontal = 18.dp,
                        vertical = 8.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = samplingButtonText,
                    color = Color.White.copy(
                        alpha = if (samplingButtonEnabled) 1f else 0.75f
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (onBackClick != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(36.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(foregroundColor.copy(alpha = 0.18f))
                    .clickable { onBackClick() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = foregroundColor,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (uiState.hasValidScore) {
            CrmThresholdReserveBar(
                score = uiState.score,
                hasValidScore = true,
                thresholds = uiState.thresholds,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 5.dp)
                    .width(28.dp)
                    .height(132.dp),
            )
        }
    }
}

private fun crmStatusText(
    uiState: CrmUiState,
    samplingState: CrmSamplingState,
    ageLabel: String,
): String {
    if (uiState.hasValidScore) {
        return if (ageLabel.isNotBlank()) {
            "${uiState.signalLabel} • $ageLabel"
        } else {
            uiState.signalLabel
        }
    }

    return when (samplingState) {
        CrmSamplingState.STARTING ->
            "Starting PPG measurement"

        CrmSamplingState.SAMPLING ->
            "Collecting PPG samples"

        CrmSamplingState.STOPPING ->
            "Stopping measurement"

        CrmSamplingState.NOT_WORN ->
            "Wear device to begin CRM"

        CrmSamplingState.UNAVAILABLE ->
            "PPG sensor unavailable"

        CrmSamplingState.ERROR ->
            "CRM measurement error"

        CrmSamplingState.IDLE -> {
            when (uiState.status) {
                CrmInferenceStatus.NOT_WORN ->
                    "Wear device to begin CRM"

                CrmInferenceStatus.REJECTED ->
                    uiState.rejectionReason
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "Adjust strap." }
                        ?: "Adjust strap."

                CrmInferenceStatus.ERROR ->
                    "CRM unavailable"

                else ->
                    "Press Start begin CRM"
            }
        }
    }
}

@Composable
private fun CrmScoreContent(
    uiState: CrmUiState,
    statusText: String,
    foregroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = uiState.displayScoreText,
            color = foregroundColor,
            fontSize = 58.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 58.sp,
            textAlign = TextAlign.Center,
        )

        Text(
            text = uiState.displayLabel,
            color = foregroundColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )

        Text(
            text = statusText,
            color = foregroundColor.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CrmPpgWaveformContent(
    samples: List<Float>,
    samplingState: CrmSamplingState,
    foregroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (samples.size < 2) {
            Text(
                text = when (samplingState) {
                    CrmSamplingState.STARTING ->
                        "Starting PPG measurement"

                    CrmSamplingState.SAMPLING ->
                        "Waiting for PPG samples"

                    CrmSamplingState.STOPPING ->
                        "Stopping measurement"

                    CrmSamplingState.NOT_WORN ->
                        "Wear device to view signal"

                    CrmSamplingState.UNAVAILABLE ->
                        "PPG sensor unavailable"

                    CrmSamplingState.ERROR ->
                        "PPG measurement error"

                    CrmSamplingState.IDLE ->
                        "Press Start to view PPG"
                },
                color = foregroundColor.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp),
            )

            return@Box
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 4.dp,
                    vertical = 14.dp,
                ),
        ) {
            drawPpgWaveform(
                samples = samples,
                color = foregroundColor,
            )
        }

        Text(
            text = "${samples.size} samples",
            color = foregroundColor.copy(alpha = 0.65f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp),
        )
    }
}

private fun DrawScope.drawPpgWaveform(
    samples: List<Float>,
    color: Color,
) {
    if (samples.size < 2) return

    val minimum = samples.minOrNull() ?: return
    val maximum = samples.maxOrNull() ?: return

    val valueRange = (maximum - minimum)
        .takeIf { it > 0.000001f }
        ?: 1f

    val horizontalPadding = 2.dp.toPx()
    val verticalPadding = 8.dp.toPx()

    val drawableWidth =
        (size.width - horizontalPadding * 2f)
            .coerceAtLeast(1f)

    val drawableHeight =
        (size.height - verticalPadding * 2f)
            .coerceAtLeast(1f)

    drawLine(
        color = color.copy(alpha = 0.18f),
        start = Offset(
            x = horizontalPadding,
            y = size.height / 2f,
        ),
        end = Offset(
            x = size.width - horizontalPadding,
            y = size.height / 2f,
        ),
        strokeWidth = 1.dp.toPx(),
    )

    val path = Path()

    samples.forEachIndexed { index, sample ->
        val horizontalProgress =
            index.toFloat() /
                    samples.lastIndex.coerceAtLeast(1).toFloat()

        val normalizedValue =
            ((sample - minimum) / valueRange)
                .coerceIn(0f, 1f)

        val x =
            horizontalPadding +
                    horizontalProgress * drawableWidth

        val y =
            verticalPadding +
                    (1f - normalizedValue) * drawableHeight

        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 2.dp.toPx(),
        ),
    )
}

@Composable
private fun CrmThresholdReserveBar(
    score: Int?,
    hasValidScore: Boolean,
    thresholds: CrmThresholdRanges,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier,
    ) {
        drawThresholdBar(
            score = score,
            hasValidScore = hasValidScore,
            thresholds = thresholds,
        )
    }
}

private fun DrawScope.drawThresholdBar(
    score: Int?,
    hasValidScore: Boolean,
    thresholds: CrmThresholdRanges,
) {
    val barWidth = 9.dp.toPx()
    val tickWidth = 15.dp.toPx()
    val markerSize = 7.dp.toPx()

    val barLeft = 0f
    val barHeight = size.height

    fun scoreToY(value: Int): Float {
        return barHeight * (1f - value.coerceIn(0, 100) / 100f)
    }

    val poorBoundaryY = scoreToY(thresholds.poorMax)
    val moderateBoundaryY = scoreToY(thresholds.moderateMax)

    drawVerticalRoundedSegment(
        color = CrmGoodColor,
        left = barLeft,
        top = 0f,
        bottom = moderateBoundaryY,
        width = barWidth,
    )

    drawVerticalRoundedSegment(
        color = CrmModerateColor,
        left = barLeft,
        top = moderateBoundaryY,
        bottom = poorBoundaryY,
        width = barWidth,
    )

    drawVerticalRoundedSegment(
        color = CrmPoorColor,
        left = barLeft,
        top = poorBoundaryY,
        bottom = barHeight,
        width = barWidth,
    )

    // Dark outer border so the bar remains visible
    // even when the bar color matches the screen background.
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.55f),
        topLeft = Offset(
            x = barLeft - 1.dp.toPx(),
            y = -1.dp.toPx(),
        ),
        size = Size(
            width = barWidth + 2.dp.toPx(),
            height = barHeight + 2.dp.toPx(),
        ),
        cornerRadius = CornerRadius(
            x = barWidth / 2f + 1.dp.toPx(),
            y = barWidth / 2f + 1.dp.toPx(),
        ),
        style = Stroke(
            width = 2.dp.toPx(),
        ),
    )

    // Thin white inner border for extra contrast.
    drawRoundRect(
        color = Color.White.copy(alpha = 0.9f),
        topLeft = Offset(
            x = barLeft,
            y = 0f,
        ),
        size = Size(
            width = barWidth,
            height = barHeight,
        ),
        cornerRadius = CornerRadius(
            x = barWidth / 2f,
            y = barWidth / 2f,
        ),
        style = Stroke(
            width = 1.dp.toPx(),
        ),
    )

    drawVerticalThresholdTick(
        y = moderateBoundaryY,
        left = barLeft - tickWidth / 2f,
        width = tickWidth,
    )

    drawVerticalThresholdTick(
        y = poorBoundaryY,
        left = barLeft - tickWidth / 2f,
        width = tickWidth,
    )

    if (hasValidScore && score != null) {
        val markerY = scoreToY(score)

        val markerPath = Path().apply {
            moveTo(
                barLeft + barWidth + 2.dp.toPx(),
                markerY,
            )
            lineTo(
                barLeft + barWidth + markerSize + 2.dp.toPx(),
                markerY - markerSize,
            )
            lineTo(
                barLeft + barWidth + markerSize + 2.dp.toPx(),
                markerY + markerSize,
            )
            close()
        }

        drawPath(
            path = markerPath,
            color = Color.White,
        )
    }
}

//private fun DrawScope.drawThresholdBar(
//    score: Int?,
//    hasValidScore: Boolean,
//    thresholds: CrmThresholdRanges,
//) {
//    val barWidth = 7.dp.toPx()
//    val tickWidth = 13.dp.toPx()
//    val markerSize = 7.dp.toPx()
//
//    val barLeft = 0f
//    val barHeight = size.height
//
//    /*
//     * Higher values appear toward the top:
//     *
//     * 100  Good
//     *      Moderate
//     *   0  Poor
//     */
//    fun scoreToY(value: Int): Float {
//        return barHeight * (1f - value.coerceIn(0, 100) / 100f)
//    }
//
//    val poorBoundaryY = scoreToY(thresholds.poorMax)
//    val moderateBoundaryY = scoreToY(thresholds.moderateMax)
//
//    drawVerticalRoundedSegment(
//        color = CrmGoodColor,
//        left = barLeft,
//        top = 0f,
//        bottom = moderateBoundaryY,
//        width = barWidth,
//    )
//
//    drawVerticalRoundedSegment(
//        color = CrmModerateColor,
//        left = barLeft,
//        top = moderateBoundaryY,
//        bottom = poorBoundaryY,
//        width = barWidth,
//    )
//
//    drawVerticalRoundedSegment(
//        color = CrmPoorColor,
//        left = barLeft,
//        top = poorBoundaryY,
//        bottom = barHeight,
//        width = barWidth,
//    )
//
//    drawRoundRect(
//        color = Color.White.copy(alpha = 0.9f),
//        topLeft = Offset(barLeft, 0f),
//        size = Size(barWidth, barHeight),
//        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
//        style = Stroke(width = 1.5.dp.toPx()),
//    )
//
//    drawVerticalThresholdTick(
//        y = moderateBoundaryY,
//        left = barLeft - tickWidth / 2f,
//        width = tickWidth,
//    )
//
//    drawVerticalThresholdTick(
//        y = poorBoundaryY,
//        left = barLeft - tickWidth / 2f,
//        width = tickWidth,
//    )
//
//    if (hasValidScore && score != null) {
//        val markerY = scoreToY(score)
//
//        val markerPath = Path().apply {
//            moveTo(barLeft + barWidth + 2.dp.toPx(), markerY)
//            lineTo(
//                barLeft + barWidth + markerSize + 2.dp.toPx(),
//                markerY - markerSize,
//            )
//            lineTo(
//                barLeft + barWidth + markerSize + 2.dp.toPx(),
//                markerY + markerSize,
//            )
//            close()
//        }
//
//        drawPath(
//            path = markerPath,
//            color = Color.White,
//        )
//    }
//}

private fun DrawScope.drawVerticalRoundedSegment(
    color: Color,
    left: Float,
    top: Float,
    bottom: Float,
    width: Float,
) {
    val height = (bottom - top).coerceAtLeast(0f)

    if (height <= 0f) return

    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
}

private fun DrawScope.drawVerticalThresholdTick(
    y: Float,
    left: Float,
    width: Float,
) {
    drawLine(
        color = Color.White.copy(alpha = 0.95f),
        start = Offset(left, y),
        end = Offset(left + width, y),
        strokeWidth = Stroke.DefaultMiter,
    )
}


@Composable
private fun rememberNowMillisTicker(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    return now
}

private fun crmAgeLabel(
    nowMillis: Long,
    lastAcceptedAtMillis: Long,
): String {
    if (lastAcceptedAtMillis <= 0L) return ""

    val ageSeconds = ((nowMillis - lastAcceptedAtMillis) / 1_000L).coerceAtLeast(0L)

    return when {
        ageSeconds < 10L -> "Now"
        ageSeconds < 60L -> "${ageSeconds}s ago"
        ageSeconds < 3600L -> "${ageSeconds / 60L}m ago"
        else -> "${ageSeconds / 3600L}h ago"
    }
}

private fun DrawScope.drawRoundedSegment(
    color: Color,
    startX: Float,
    endX: Float,
    top: Float,
    height: Float,
) {
    val width = (endX - startX).coerceAtLeast(0f)

    if (width <= 0f) return

    drawRoundRect(
        color = color,
        topLeft = Offset(startX, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f, height / 2f),
    )
}

private fun DrawScope.drawThresholdTick(
    x: Float,
    top: Float,
    height: Float,
) {
    drawLine(
        color = Color.White.copy(alpha = 0.95f),
        start = Offset(x, top),
        end = Offset(x, top + height),
        strokeWidth = Stroke.DefaultMiter,
    )
}

private fun crmBackgroundColor(
    displayState: CrmDisplayState?,
    hasValidScore: Boolean,
): Color {
    if (!hasValidScore) return CrmUnknownColor

    return when (displayState) {
        CrmDisplayState.POOR -> CrmPoorColor
        CrmDisplayState.MODERATE -> CrmModerateColor
        CrmDisplayState.GOOD -> CrmGoodColor
        null -> CrmUnknownColor
    }
}

private fun crmForegroundColor(
    displayState: CrmDisplayState?,
    hasValidScore: Boolean,
): Color {
    if (!hasValidScore) return Color.White

    return when (displayState) {
        CrmDisplayState.MODERATE -> Color.Black
        CrmDisplayState.POOR,
        CrmDisplayState.GOOD,
        null -> Color.White
    }
}

private val CrmPoorColor = Color(0xFFB00020)
private val CrmModerateColor = Color(0xFFFFC107)
private val CrmGoodColor = Color(0xFF0B7D3B)
private val CrmUnknownColor = Color(0xFF202124)