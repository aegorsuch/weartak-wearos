/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.util.Log
import com.aitech.weartak_civ.DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.UasOnnxRepository
import com.aitech.weartak_civ.data.audio.AudioClassificationResult
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.service.audio.AudioDetectionRuntime
import com.aitech.weartak_civ.service.audio.AudioDetectionSpec
import com.aitech.weartak_civ.service.audio.UasAudioClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

class UasOnnxService : BaseAudioDetectionService() {

    private val uasRepo by lazy {
        try {
            (applicationContext as WearTakApplication).uasOnnxRepo
        } catch (_: Throwable) {
            UasOnnxRepository(applicationContext)
        }
    }
    private val dataRepository by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }
    private val classifier by lazy {
        UasAudioClassifier(
            context = applicationContext,
            logTag = TAG,
            assetName = CLASSIFIER_ASSET_NAME,
            labels = CLASSIFIER_LABELS
        )
    }

    override val logTag: String = TAG
    override val notificationId: Int = NOTIFICATION_ID
    override val runningFlag: AtomicBoolean = isRunning
    override val spec: AudioDetectionSpec = SPEC
    override val feedbackFlow: Flow<AudioDetectionFeedback>
        get() = uasRepo.uasFeedback
    override val thresholdFlow: Flow<Float>
        get() = dataRepository.getOnnxUasTriggerThresholdPercent.map { it.coerceIn(0, 100) / 100f }
    override val runtime: AudioDetectionRuntime by lazy {
        createRuntime { trigger ->
            val classification = classifier.classifyPcm16(
                audioPcm = trigger.windowPcm,
                sampleRateHz = trigger.pcmChunk.sampleRateHz
            )
            val event = uasRepo.createUasEvent(
                uid = trigger.uid,
                confidence = trigger.confidence,
                pcmChunk = trigger.pcmChunk,
                triggerPcmPath = trigger.triggerPcmPath,
                audioSource = trigger.audioSource,
                inferStart = trigger.inferStart,
                inferEnd = trigger.inferEnd,
                classification = classification
            )
            logClassification(event, classification)
            uasRepo.upsertRecentUas(event)
        }
    }

    override fun getEventFromUid(uid: String): AudioDetectionEvent? = uasRepo.getEventFromUid(uid)

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

    override fun onDestroy() {
        classifier.close()
        super.onDestroy()
    }

    private suspend fun logClassification(
        event: AudioDetectionEvent,
        classification: AudioClassificationResult?
    ) {
        if (classification == null) {
            Log.w(
                TAG,
                "UAS_CLASSIFY_FAIL uid=${event.uid} detectorConf=${"%.4f".format(event.confidence)}"
            )
            return
        }

        val scoresText = classification.scores.joinToString(
            prefix = "[",
            postfix = "]"
        ) { "%.4f".format(it) }
        Log.i(
            TAG,
            "UAS_CLASSIFY uid=${event.uid} detectorConf=${"%.4f".format(event.confidence)} " +
                "classIdx=${classification.predictedIndex} " +
                "label=\"${classification.predictedLabel}\" " +
                "classScore=${"%.4f".format(classification.predictedScore)} " +
                "scores=$scoresText"
        )
        Utils.logAudioDetectionClassification(
            context = applicationContext,
            event = event,
            debugDirName = spec.debugDirName
        )
    }

    companion object {
        private const val TAG = "WearTAK-UasOnnxService"
        private const val NOTIFICATION_ID = 920
        private const val CLASSIFIER_ASSET_NAME = "falcon-multi.onnx"
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        private val CLASSIFIER_LABELS = listOf(
            "Autel Evo 2",
            "DJI Avata",
            "DJI Matrice 210",
            "DJI Matrice 30 T",
            "DJI Mavic 2 Pro",
            "DJI Mavic 3 Enterprise",
            "DJI Mavic 3 Pro"
        )

        private val SPEC = AudioDetectionSpec(
            detectorKey = "uas",
            assetName = "falcon-bin.onnx",
            threshold = DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT / 100f,
            debugDirName = "uas_debug",
            logTag = TAG,
            triggerPrefix = "UAS",
            feedbackPrefix = "UAS"
        )
    }
}
