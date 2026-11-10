/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.audio.AudioClassificationResult
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.data.audio.AudioDetectionStore
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel

class UasOnnxRepository(private val context: Context) {

    private val repo by lazy {
        try {
            (context as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context)
        }
    }

    private val store by lazy { AudioDetectionStore(DETECTOR_KEY, repo) }

    suspend fun createUasEvent(
        uid: String,
        confidence: Float,
        pcmChunk: AudioCaptureRepository.AudioPcmChunk,
        triggerPcmPath: String?,
        audioSource: String?,
        inferStart: Long? = null,
        inferEnd: Long? = null,
        classification: AudioClassificationResult? = null
    ): AudioDetectionEvent = store.createEvent(
        uid = uid,
        confidence = confidence,
        pcmChunk = pcmChunk,
        triggerPcmPath = triggerPcmPath,
        audioSource = audioSource,
        inferStart = inferStart,
        inferEnd = inferEnd,
        classification = classification
    )

    val uasFeedback = store.feedback
    fun tryEmitUasFeedback(feedback: AudioDetectionFeedback): Boolean = store.tryEmitFeedback(feedback)

    val uasEvents = store.events
    val recentUasFlow = store.recentEventsFlow
    val pendingUas = store.pendingEvent

    fun upsertRecentUas(event: AudioDetectionEvent) = store.upsertRecent(event)
    fun getEventFromUid(uid: String): AudioDetectionEvent? = store.getEventFromUid(uid)
    fun setUasEventAlertLevel(uid: String, level: Utils.AlertChipState): Boolean = store.setEventAlertLevel(uid, level)
    fun removeUas(uid: String): Boolean = store.remove(uid)
    fun clearAllUas() = store.clearAll()

    companion object {
        const val DETECTOR_KEY = "uas"
    }
}

typealias UasOnnxEvent = AudioDetectionEvent
typealias UasUserLabel = AudioDetectionUserLabel
typealias UasFeedback = AudioDetectionFeedback
