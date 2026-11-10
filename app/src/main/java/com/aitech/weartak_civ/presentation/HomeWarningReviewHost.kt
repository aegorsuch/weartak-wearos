/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.presentation

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.GunshotOnnxRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.presentation.audio.DetectionFeedbackDialog
import com.aitech.weartak_civ.service.BleLinkWarning

@Composable
internal fun HomeWarningReviewHost(
    gunshotPending: AudioDetectionEvent?,
    uasPending: AudioDetectionEvent?,
    findEvent: (detectorKey: String, uid: String) -> AudioDetectionEvent?,
    emitFeedback: (AudioDetectionEvent, AudioDetectionFeedback) -> Boolean,
    crmWarning: CrmHomeWarning? = null,
    onCrmDetails: () -> Unit = {},
    physioWarning: HomeStatusWarning? = null,
    dittoWarning: BleLinkWarning = BleLinkWarning.NONE,
    onDittoRetry: () -> Unit = {},
    content: @Composable (warnings: List<HomeStatusWarning>, onReviewWarnings: () -> Unit) -> Unit
) {
    val pending = listOfNotNull(gunshotPending, uasPending)
        .sortedWith(compareByDescending<AudioDetectionEvent> { it.timestamp }
            .thenBy { it.detectorKey }.thenBy { it.uid })
    var selectedDetector by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var shownAtMs by rememberSaveable { mutableStateOf(0L) }
    var feedbackFailed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(gunshotPending?.uid) {
        if (gunshotPending != null) {
            Utils.vibrateWatch(vibDurationMs = 150, numberOfVibes = 2, timeBtwnVibesMs = 120)
        }
    }
    LaunchedEffect(uasPending?.uid) {
        if (uasPending != null) {
            Utils.vibrateWatch(vibDurationMs = 150, numberOfVibes = 2, timeBtwnVibesMs = 120)
        }
    }

    fun dismissReview() {
        selectedDetector = null
        selectedUid = null
        feedbackFailed = false
    }

    val warnings = pending.map { event ->
        HomeStatusWarning(event.detectorKey, TopMetricAlertSource.DETECTION,
            detectionPrompt(event), Utils.AlertChipState.Warning) {
                selectedDetector = event.detectorKey
                selectedUid = event.uid
                shownAtMs = SystemClock.elapsedRealtime()
                feedbackFailed = false
                Log.i("AudioDetectionUI", "DIALOG_SHOW detector=${event.detectorKey} uid=${event.uid}")
        }
    }.toMutableList()
    if (crmWarning != null) {
        val label = stringResource(R.string.crm_warning_status_format, crmWarning.score, crmStatusLabel(crmWarning))
        val warning = HomeStatusWarning("crm", TopMetricAlertSource.CRM, label, crmWarning.chipState) {
            selectedDetector = "crm"
            selectedUid = null
        }
        if (crmWarning.status == CrmDisplayState.POOR) warnings.add(0, warning) else warnings.add(warning)
    }
    if (physioWarning != null) warnings.add(0, physioWarning)
    dittoBleWarningLabel(dittoWarning)?.let { label ->
        warnings.add(HomeStatusWarning("ditto", TopMetricAlertSource.DITTO_BLE,
            stringResource(label), Utils.AlertChipState.Warning) {
            selectedDetector = "ditto"
            selectedUid = null
        })
    }
    content(warnings) {
        if (warnings.size == 1) warnings.first().onReview() else if (warnings.isNotEmpty()) selectedDetector = "picker"
    }

    LaunchedEffect(crmWarning, dittoWarning, warnings.isEmpty(), selectedDetector) {
        if ((selectedDetector == "crm" && crmWarning == null) ||
            (selectedDetector == "ditto" && dittoWarning == BleLinkWarning.NONE) ||
            (selectedDetector == "picker" && warnings.isEmpty())) dismissReview()
    }

    if (selectedDetector == "picker" && warnings.isNotEmpty()) {
        WarningReviewDialog(
            title = stringResource(R.string.home_warning_review),
            chipState = if (warnings.any { it.chipState == Utils.AlertChipState.Emergency })
                Utils.AlertChipState.Emergency else Utils.AlertChipState.Warning,
            tag = "home-warning-picker",
            backTag = "home-warning-picker-back",
            onDismiss = ::dismissReview,
            body = {
                warnings.forEach { warning ->
                    WarningReviewAction(warning.label, "home-warning-${warning.key}",
                        { dismissReview(); warning.onReview() }, Modifier.fillMaxWidth())
                }
            }
        )
    }
    if (selectedDetector == "crm" && crmWarning != null) {
        WarningReviewDialog(
            title = stringResource(R.string.crm_warning_title),
            detail = stringResource(R.string.crm_warning_summary, crmWarning.score, crmStatusLabel(crmWarning)),
            chipState = crmWarning.chipState,
            tag = "crm-warning-dialog",
            backTag = "crm-warning-back",
            onDismiss = ::dismissReview
        ) {
            WarningReviewAction(stringResource(R.string.crm_warning_details), "crm-warning-details",
                { onCrmDetails(); dismissReview() }, Modifier.weight(1f))
        }
    }
    if (selectedDetector == "ditto" && dittoWarning != BleLinkWarning.NONE) {
        DittoBleRecoveryDialog(
            warning = dittoWarning,
            onRetryNow = { dismissReview(); onDittoRetry() },
            onDismiss = ::dismissReview
        )
    }

    // Resolve the saved identity from history, never from the replaceable pending selector.
    val event = selectedUid?.let { uid -> selectedDetector?.let { findEvent(it, uid) } }
    if (event != null) {
        fun answer(label: AudioDetectionUserLabel) {
            if (selectedUid != event.uid || selectedDetector != event.detectorKey) return
            val feedback = AudioDetectionFeedback(
                eventUid = event.uid,
                label = label,
                respondedAtMs = System.currentTimeMillis(),
                responseLatencyMs = SystemClock.elapsedRealtime() - shownAtMs
            )
            if (emitFeedback(event, feedback)) {
                dismissReview()
            } else {
                feedbackFailed = true
                Log.e("AudioDetectionUI", "FEEDBACK_FAILED detector=${event.detectorKey} uid=${event.uid}")
            }
        }
        DetectionFeedbackDialog(
            promptText = detectionPrompt(event),
            shouldShow = true,
            confidence = event.confidence,
            feedbackFailed = feedbackFailed,
            onDismiss = {
                Log.i("AudioDetectionUI", "DIALOG_DISMISS detector=${event.detectorKey} uid=${event.uid} unanswered")
                dismissReview()
            },
            onConfirm = { answer(AudioDetectionUserLabel.YES) },
            onReject = { answer(AudioDetectionUserLabel.NO) }
        )
    }
}

@Composable
private fun detectionPrompt(event: AudioDetectionEvent): String =
    stringResource(
        if (event.detectorKey == GunshotOnnxRepository.DETECTOR_KEY)
            R.string.audio_detection_gunshot_question else R.string.audio_detection_uas_question
    )

@Composable
private fun crmStatusLabel(warning: CrmHomeWarning): String =
    stringResource(if (warning.status == CrmDisplayState.POOR) R.string.crm_warning_poor else R.string.crm_warning_moderate)
