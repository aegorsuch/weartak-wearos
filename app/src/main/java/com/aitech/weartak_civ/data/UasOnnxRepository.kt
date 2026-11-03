/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
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
