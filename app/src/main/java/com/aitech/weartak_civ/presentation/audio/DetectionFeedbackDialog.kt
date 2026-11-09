/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material.ChipDefaults
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.presentation.WarningReviewAction
import com.aitech.weartak_civ.presentation.WarningReviewDialog
import kotlin.math.roundToInt

@Composable
fun DetectionFeedbackDialog(
    promptText: String,
    shouldShow: Boolean,
    confidence: Float,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    feedbackFailed: Boolean = false
) {
    if (!shouldShow) return
    val score = stringResource(R.string.audio_detection_score, (confidence * 100f).roundToInt())
    WarningReviewDialog(
        title = promptText,
        detail = score + if (feedbackFailed) "\n${stringResource(R.string.audio_detection_feedback_failed)}" else "",
        chipState = AlertChipState.Warning,
        tag = "audio-detection-dialog",
        backTag = "audio-detection-back",
        onDismiss = onDismiss
    ) {
        WarningReviewAction(stringResource(R.string.audio_detection_yes), "audio-detection-yes",
            onConfirm, Modifier.weight(1f),
            ChipDefaults.chipColors(backgroundColor = Color.Green, contentColor = Color.Black))
        WarningReviewAction(stringResource(R.string.audio_detection_no), "audio-detection-no",
            onReject, Modifier.weight(1f), ChipDefaults.secondaryChipColors())
    }
}
