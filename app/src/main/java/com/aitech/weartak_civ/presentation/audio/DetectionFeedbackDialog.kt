/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
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
