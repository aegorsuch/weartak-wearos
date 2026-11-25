/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service.audio

import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

fun startAudioDetectionFeedback(
    scope: CoroutineScope,
    logTag: String,
    feedbackPrefix: String,
    feedbackFlow: Flow<AudioDetectionFeedback>,
    getEventFromUid: (String) -> AudioDetectionEvent?,
    logFeedback: suspend (AudioDetectionEvent, AudioDetectionFeedback) -> Unit
): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    collectAudioDetectionFeedback(logTag, feedbackPrefix, feedbackFlow, getEventFromUid, logFeedback)
}

suspend fun collectAudioDetectionFeedback(
    logTag: String,
    feedbackPrefix: String,
    feedbackFlow: Flow<AudioDetectionFeedback>,
    getEventFromUid: (String) -> AudioDetectionEvent?,
    logFeedback: suspend (AudioDetectionEvent, AudioDetectionFeedback) -> Unit
) {
    feedbackFlow.collect { feedback ->
        val event = getEventFromUid(feedback.eventUid)
        val nowEl = SystemClock.elapsedRealtime()
        Log.i(
            logTag,
            "${feedbackPrefix}_FEEDBACK uid=${feedback.eventUid} label=${feedback.label} " +
                "respLatencyMs=${feedback.responseLatencyMs} respondedAtMs=${feedback.respondedAtMs} " +
                "serviceSeenEl=$nowEl"
        )
        if (event != null) {
            logFeedback(event, feedback)
        } else {
            Log.w(logTag, "orphan feedback -- no parent event found for this feedback")
        }
    }
}
