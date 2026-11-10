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
import android.media.MediaRecorder
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "data")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class AudioCaptureRepository(private val context: Context) {

    private val TAG = "WearTAK-" + AudioCaptureRepository::class.java.simpleName

    enum class RecordingState {
        RECORDING,
        PAUSED,
        PLAYING,
        AVAILABLE,
        UNAVAILABLE
    }

    // PCM_16BIT
    data class AudioPcmChunk(
        val sessionId: String,
        val seq: Long,
        val timestampMs: Long,
        val timestampElMs: Long,
        val sampleRateHz: Int,     // 44100 in current gunshot config
        val channels: Int,         // 1
        val samples: ShortArray    // PCM16 mono little-endian
    )
    private val _pcmChunks =
        MutableSharedFlow<AudioPcmChunk>(
            replay = 0,
            extraBufferCapacity = 50, // ~2 seconds-ish depending on READ_SIZE
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
    fun tryEmitChunk(chunk: AudioPcmChunk): Boolean = _pcmChunks.tryEmit(chunk)
    val getPcmChunks: SharedFlow<AudioPcmChunk> = _pcmChunks

    private val consumerLock = Any()
    private val _activeConsumers = MutableStateFlow<Set<String>>(emptySet())
    val activeConsumers: StateFlow<Set<String>> = _activeConsumers

    /**
     * Acquires an idempotent lease on the single microphone capture session.
     *
     * Stable owner keys let local detectors and trusted plugins coexist without duplicate starts.
     */
    fun acquireConsumer(consumerId: String): Boolean {
        if (consumerId.isBlank()) return false
        return synchronized(consumerLock) {
            val current = _activeConsumers.value
            if (consumerId in current) {
                false
            } else {
                _activeConsumers.value = current + consumerId
                true
            }
        }
    }

    /** Releases a lease; repeated releases are safe no-ops. */
    fun releaseConsumer(consumerId: String): Boolean = synchronized(consumerLock) {
        val current = _activeConsumers.value
        if (consumerId !in current) {
            false
        } else {
            _activeConsumers.value = current - consumerId
            true
        }
    }

    /*
    MediaRecorder.AudioSource:
    CAMCORDER = 5,
    DEFAULT = 0,
    MIC = 1;
    REMOTE_SUBMIX = 8;
    UNPROCESSED = 9;
    VOICE_CALL = 4;
    VOICE_COMMUNICATION = 7;
    VOICE_DOWNLINK = 3;
    VOICE_PERFORMANCE = 10;
    VOICE_RECOGNITION = 6;
    VOICE_UPLINK = 2;
    */
    val audioSource: Int = MediaRecorder.AudioSource.UNPROCESSED

    // So the UI knows what buttons to show the user (e.g. stop, pause, record)
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.AVAILABLE)
    val getRecordingState: StateFlow<RecordingState> = _recordingState

    // So the UI knows when to start/stop showing the user that a recording session is in session. Like the macro event of a recording sesh.
    private val _recordingSessionState = MutableStateFlow<Boolean>(false)
    val getRecordingSessionState: StateFlow<Boolean> = _recordingSessionState

    suspend fun updateRecordingSessionState(isSessionRunnning: Boolean) {
        _recordingSessionState.emit(isSessionRunnning)
    }

    suspend fun updateRecordingState(state: RecordingState) {
        _recordingState.emit(state)
    }
}
