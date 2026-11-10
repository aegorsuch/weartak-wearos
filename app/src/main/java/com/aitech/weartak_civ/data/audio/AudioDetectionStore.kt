/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.audio

import android.util.Log
import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

class AudioDetectionStore(
    private val detectorKey: String,
    private val repo: DataRepository
) {
    private val _feedback =
        MutableSharedFlow<AudioDetectionFeedback>(
            replay = 0,
            extraBufferCapacity = 20,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val feedback: SharedFlow<AudioDetectionFeedback> = _feedback

    private val _events =
        MutableSharedFlow<AudioDetectionEvent>(
            replay = 0,
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val events: SharedFlow<AudioDetectionEvent> = _events

    private val recentEventsMap = ConcurrentHashMap<String, AudioDetectionEvent>()
    private val _recentEventsFlow = MutableStateFlow<List<AudioDetectionEvent>>(emptyList())
    val recentEventsFlow: StateFlow<List<AudioDetectionEvent>> = _recentEventsFlow
    private val _pendingEvent = MutableStateFlow<AudioDetectionEvent?>(null)
    val pendingEvent: StateFlow<AudioDetectionEvent?> = _pendingEvent
    private val reviewedEventUids = HashSet<String>()

    suspend fun createEvent(
        uid: String,
        confidence: Float,
        pcmChunk: AudioCaptureRepository.AudioPcmChunk,
        triggerPcmPath: String?,
        audioSource: String?,
        inferStart: Long? = null,
        inferEnd: Long? = null,
        classification: AudioClassificationResult? = null
    ): AudioDetectionEvent {
        val event = AudioDetectionEvent(
            detectorKey = detectorKey,
            timestamp = System.currentTimeMillis(),
            confidence = (round(confidence * 10000f) / 10000f),
            loc = repo.snapshotLocation(),
            uid = uid,
            alertLevel = AlertChipState.Warning,
            chunkRef = AudioDetectionChunkRef(
                sessionId = pcmChunk.sessionId,
                seq = pcmChunk.seq,
                timestampMs = pcmChunk.timestampMs,
                sampleRateHz = pcmChunk.sampleRateHz,
                channels = pcmChunk.channels
            ),
            triggerPcmPath = triggerPcmPath,
            audioSource = audioSource,
            inferStartElapsedMs = inferStart,
            inferEndElapsedMs = inferEnd,
            classification = classification
        )
        synchronized(this) {
            upsertRecent(event)
            _pendingEvent.value = event
        }
        _events.emit(event)
        return event
    }

    @Synchronized
    fun tryEmitFeedback(feedback: AudioDetectionFeedback): Boolean {
        if (!recentEventsMap.containsKey(feedback.eventUid) || feedback.eventUid in reviewedEventUids) {
            Log.w("AudioDetectionStore", "$detectorKey feedback rejected: missing or reviewed uid=${feedback.eventUid}")
            return false
        }
        if (_feedback.subscriptionCount.value == 0 || !_feedback.tryEmit(feedback)) {
            Log.e("AudioDetectionStore", "$detectorKey feedback unavailable uid=${feedback.eventUid}")
            return false
        }
        if (feedback.label == AudioDetectionUserLabel.YES || feedback.label == AudioDetectionUserLabel.NO) {
            reviewedEventUids.add(feedback.eventUid)
            if (_pendingEvent.value?.uid == feedback.eventUid) _pendingEvent.value = null
        }
        return true
    }

    @Synchronized
    fun upsertRecent(event: AudioDetectionEvent) {
        recentEventsMap[event.uid] = event
        _recentEventsFlow.value = recentEventsMap.values.sortedByDescending { it.timestamp }
    }

    fun getEventFromUid(uid: String): AudioDetectionEvent? = recentEventsMap[uid]

    @Synchronized
    fun setEventAlertLevel(uid: String, level: AlertChipState): Boolean {
        val current = recentEventsMap[uid] ?: return false
        recentEventsMap[uid] = current.copy(alertLevel = level)
        _recentEventsFlow.value = recentEventsMap.values.sortedByDescending { it.timestamp }
        return true
    }

    @Synchronized
    fun remove(uid: String): Boolean {
        val removed = recentEventsMap.remove(uid) != null
        if (removed) {
            reviewedEventUids.remove(uid)
            if (_pendingEvent.value?.uid == uid) _pendingEvent.value = null
            _recentEventsFlow.value = recentEventsMap.values.sortedByDescending { it.timestamp }
        }
        return removed
    }

    @Synchronized
    fun clearAll() {
        recentEventsMap.clear()
        reviewedEventUids.clear()
        _pendingEvent.value = null
        _recentEventsFlow.value = emptyList()
    }
}
