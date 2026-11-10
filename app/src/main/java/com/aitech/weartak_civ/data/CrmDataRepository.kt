/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.DEFAULT_CRM_MODERATE_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_CRM_POOR_MAX_THRESHOLD
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class CrmDataRepository(private val dataRepository: DataRepository) {

    val latestCrmInference: StateFlow<CrmInferenceState> =
        dataRepository.latestCrmInference

    val rawPpgSamples: SharedFlow<CrmRawPpgSample> =
        dataRepository.crmRawPpgSamples

    val thresholdRanges: Flow<CrmThresholdRanges> =
        dataRepository.crmThresholdRanges

    fun tryPublish(state: CrmInferenceState): Boolean =
        dataRepository.tryStoreCrmInference(state)

    suspend fun publish(state: CrmInferenceState) =
        dataRepository.storeCrmInference(state)

    suspend fun storeThresholdRanges(
        poorMax: Int,
        moderateMax: Int,
    ) = dataRepository.storeCrmThresholdRanges(
        poorMax = poorMax,
        moderateMax = moderateMax,
    )

    private val _samplingCommands = MutableSharedFlow<CrmSamplingCommand>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val samplingCommands: SharedFlow<CrmSamplingCommand> =
        _samplingCommands.asSharedFlow()

    private val _samplingState =
        MutableStateFlow(CrmSamplingState.IDLE)

    val samplingState: StateFlow<CrmSamplingState> =
        _samplingState.asStateFlow()

    fun requestSamplingStart(): Boolean {
        _samplingState.value = CrmSamplingState.STARTING
        return _samplingCommands.tryEmit(CrmSamplingCommand.START)
    }

    fun requestSamplingStop(): Boolean {
        _samplingState.value = CrmSamplingState.STOPPING
        return _samplingCommands.tryEmit(CrmSamplingCommand.STOP)
    }

    fun updateSamplingState(state: CrmSamplingState) {
        _samplingState.value = state
    }

    enum class CrmSamplingCommand {
        START,
        STOP,
    }

    enum class CrmSamplingState {
        IDLE,
        STARTING,
        SAMPLING,
        STOPPING,
        NOT_WORN,
        UNAVAILABLE,
        ERROR,
    }

    fun tryPublishRawPpgSample(
        uptimeMillis: Long,
        rawValue: Float,
        sourceAvailable: Boolean,
        deviceWorn: Boolean,
    ): Boolean = dataRepository.tryStoreCrmRawPpgSample(
        CrmRawPpgSample(
            uptimeMillis = uptimeMillis,
            rawValue = rawValue,
            sourceAvailable = sourceAvailable,
            deviceWorn = deviceWorn,
        )
    )

    fun tryPublishPpgSourceUnavailable(
        uptimeMillis: Long,
        reason: String,
    ): Boolean = dataRepository.tryStoreCrmRawPpgSample(
        CrmRawPpgSample(
            uptimeMillis = uptimeMillis,
            rawValue = null,
            sourceAvailable = false,
            deviceWorn = false,
            reason = reason,
        )
    )
}

data class CrmRawPpgSample(
    val uptimeMillis: Long,
    val rawValue: Float?,
    val sourceAvailable: Boolean,
    val deviceWorn: Boolean,
    val emittedAtMillis: Long = System.currentTimeMillis(),
    val reason: String? = null,
)

enum class CrmInferenceStatus {
    IDLE,
    ACCEPTED,
    REJECTED,
    ERROR,
    NOT_WORN
}

data class CrmInferenceState(
    val status: CrmInferenceStatus = CrmInferenceStatus.IDLE,
    val value: Float? = null,
    val updatedAtMillis: Long = 0L,
    val quality: Float? = null,
    val rejectionReason: String? = null,
    val sourceSampleCount: Int = 0,
    val inputWindowDurationMs: Long = 0L,
    val inferenceLatencyMs: Long? = null,
) {
    /**
     * Raw CRM model output normalized to 0.0f..1.0f.
     *
     * Example:
     * value = 0.6927f
     * normalizedValue = 0.6927f
     */
    val normalizedValue: Float?
        get() = value?.coerceIn(0f, 1f)

    /**
     * CRM converted to 0.0f..100.0f.
     *
     * Example:
     * value = 0.6927f
     * percentValue = 69.27f
     */
    val percentValue: Float?
        get() = normalizedValue?.times(100f)

    /**
     * CRM display score for the watch UI.
     *
     * Example:
     * value = 0.6927f
     * displayScore = 69
     */
    val displayScore: Int?
        get() = percentValue?.roundToInt()?.coerceIn(0, 100)

    companion object {
        fun idle(updatedAtMillis: Long = System.currentTimeMillis()) = CrmInferenceState(
            status = CrmInferenceStatus.IDLE,
            updatedAtMillis = updatedAtMillis,
        )
    }
}

data class CrmThresholdRanges(
    val poorMax: Int = DEFAULT_CRM_POOR_MAX_THRESHOLD,
    val moderateMax: Int = DEFAULT_CRM_MODERATE_MAX_THRESHOLD,
) {
    val poorRange: IntRange
        get() = 0..poorMax

    val moderateRange: IntRange
        get() = (poorMax + 1)..moderateMax

    val goodRange: IntRange
        get() = (moderateMax + 1)..100

    fun stateFor(score: Int): CrmDisplayState {
        val clamped = score.coerceIn(0, 100)

        return when {
            clamped <= poorMax -> CrmDisplayState.POOR
            clamped <= moderateMax -> CrmDisplayState.MODERATE
            else -> CrmDisplayState.GOOD
        }
    }
}

enum class CrmDisplayState {
    POOR,
    MODERATE,
    GOOD,
}

fun sanitizeCrmThresholdRanges(
    poorMax: Int,
    moderateMax: Int,
): CrmThresholdRanges {
    val cleanPoorMax = poorMax.coerceIn(0, 98)
    val cleanModerateMax = moderateMax.coerceIn(cleanPoorMax + 1, 100)

    return CrmThresholdRanges(
        poorMax = cleanPoorMax,
        moderateMax = cleanModerateMax,
    )
}