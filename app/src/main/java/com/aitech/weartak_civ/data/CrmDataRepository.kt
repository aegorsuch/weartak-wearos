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
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CrmDataRepository {
    private val _samplingState =
        MutableStateFlow(CrmSamplingState.IDLE)

    val samplingState: StateFlow<CrmSamplingState> =
        _samplingState.asStateFlow()

    fun updateSamplingState(state: CrmSamplingState) {
        _samplingState.value = state
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

}

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