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
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.data.audio.AudioDetectionStore
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import com.aitech.weartak_civ.WearTakApplication

class GunshotOnnxRepository(private val context: Context) {

    private val repo by lazy {
        try {
            (context as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context)
        }
    }

    private val store by lazy { AudioDetectionStore(DETECTOR_KEY, repo) }

    suspend fun createGunshotEvent(
        uid: String,
        confidence: Float,
        pcmChunk: AudioCaptureRepository.AudioPcmChunk,
        triggerPcmPath: String?,
        audioSource: String?,
        inferStart: Long? = null,
        inferEnd: Long? = null
    ): AudioDetectionEvent = store.createEvent(
        uid = uid,
        confidence = confidence,
        pcmChunk = pcmChunk,
        triggerPcmPath = triggerPcmPath,
        audioSource = audioSource,
        inferStart = inferStart,
        inferEnd = inferEnd
    )

    val gunshotFeedback = store.feedback
    fun tryEmitGunshotFeedback(feedback: AudioDetectionFeedback): Boolean = store.tryEmitFeedback(feedback)

    val gunshotEvents = store.events
    val recentGunshotsFlow = store.recentEventsFlow
    val pendingGunshot = store.pendingEvent

    fun upsertRecentGunshot(event: AudioDetectionEvent) = store.upsertRecent(event)
    fun getEventFromUid(uid: String): AudioDetectionEvent? = store.getEventFromUid(uid)
    fun setGunshotEventAlertLevel(uid: String, level: Utils.AlertChipState): Boolean = store.setEventAlertLevel(uid, level)
    fun removeGunshot(uid: String): Boolean = store.remove(uid)
    fun clearAllGunshots() = store.clearAll()

    companion object {
        const val DETECTOR_KEY = "gunshot"
    }
}

typealias GunshotOnnxEvent = AudioDetectionEvent
typealias GunshotUserLabel = AudioDetectionUserLabel
typealias GunshotFeedback = AudioDetectionFeedback
