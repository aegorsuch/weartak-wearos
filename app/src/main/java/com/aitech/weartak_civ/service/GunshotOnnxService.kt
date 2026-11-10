/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.GunshotOnnxRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.service.audio.AudioDetectionRuntime
import com.aitech.weartak_civ.service.audio.AudioDetectionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

class GunshotOnnxService : BaseAudioDetectionService() {

    private val gunshotRepo by lazy {
        try {
            (applicationContext as WearTakApplication).gunshotOnnxRepo
        } catch (_: Throwable) {
            GunshotOnnxRepository(applicationContext)
        }
    }
    private val dataRepository by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    override val logTag: String = TAG
    override val notificationId: Int = NOTIFICATION_ID
    override val runningFlag: AtomicBoolean = isRunning
    override val spec: AudioDetectionSpec = SPEC
    override val feedbackFlow: Flow<AudioDetectionFeedback>
        get() = gunshotRepo.gunshotFeedback
    override val thresholdFlow: Flow<Float>
        get() = dataRepository.getOnnxGunshotTriggerThresholdPercent.map { it.coerceIn(0, 100) / 100f }
    override val runtime: AudioDetectionRuntime by lazy {
        createRuntime { trigger ->
            val event = gunshotRepo.createGunshotEvent(
                uid = trigger.uid,
                confidence = trigger.confidence,
                pcmChunk = trigger.pcmChunk,
                triggerPcmPath = trigger.triggerPcmPath,
                audioSource = trigger.audioSource,
                inferStart = trigger.inferStart,
                inferEnd = trigger.inferEnd
            )
            gunshotRepo.upsertRecentGunshot(event)
        }
    }

    override fun getEventFromUid(uid: String): AudioDetectionEvent? = gunshotRepo.getEventFromUid(uid)

    override suspend fun logFeedback(event: AudioDetectionEvent, feedback: AudioDetectionFeedback) {
        Utils.logAudioDetectionFeedback(
            context = applicationContext,
            event = event,
            label = feedback.label,
            respondedAtMs = feedback.respondedAtMs,
            responseLatencyMs = feedback.responseLatencyMs,
            debugDirName = spec.debugDirName,
            extra = feedback.extra
        )
    }

    companion object {
        private const val TAG = "WearTAK-GunshotOnnxService"
        private const val NOTIFICATION_ID = 919
        val isRunning: AtomicBoolean = AtomicBoolean(false)

        private val SPEC = AudioDetectionSpec(
            detectorKey = "gunshot",
            assetName = "dart-bin-2chan_20260327.onnx",
            threshold = DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT / 100f,
            debugDirName = "gunshot_debug",
            logTag = TAG,
            triggerPrefix = "GS",
            feedbackPrefix = "GS"
        )
    }
}
