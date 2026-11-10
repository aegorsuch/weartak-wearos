/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.DEFAULT_CRM_MODERATE_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_CRM_POOR_MAX_THRESHOLD
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.CrmInferenceStatus
import com.aitech.weartak_civ.data.CrmThresholdRanges
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.CrmDataRepository.CrmSamplingState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.scan
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CrmViewModel(
    private val crmDataRepository: CrmDataRepository,
    private val dataRepository: DataRepository,

) : ViewModel() {

    val samplingState: StateFlow<CrmSamplingState> =
        crmDataRepository.samplingState

    private val _waveformSamples =
        MutableStateFlow<List<Float>>(emptyList())

    val waveformSamples: StateFlow<List<Float>> =
        _waveformSamples.asStateFlow()

    init {
        collectRawPpgWaveform()
    }

    private fun collectRawPpgWaveform() {
        viewModelScope.launch {
            val sampleBuffer = ArrayDeque<Float>(CRM_WAVEFORM_SAMPLE_COUNT)

            var lastPublishedAtMillis = 0L

            crmDataRepository.rawPpgSamples.collect { sample ->
                val rawValue = sample.rawValue

                val sampleIsUsable =
                    sample.sourceAvailable &&
                            sample.deviceWorn &&
                            rawValue != null &&
                            rawValue.isFinite()

                if (!sampleIsUsable) {
                    if (sampleBuffer.isNotEmpty()) {
                        sampleBuffer.clear()
                        _waveformSamples.value = emptyList()
                    }

                    return@collect
                }

                sampleBuffer.addLast(rawValue)

                while (sampleBuffer.size > CRM_WAVEFORM_SAMPLE_COUNT) {
                    sampleBuffer.removeFirst()
                }

                val nowMillis = sample.uptimeMillis

                if (
                    nowMillis - lastPublishedAtMillis >=
                    CRM_WAVEFORM_PUBLISH_INTERVAL_MS
                ) {
                    _waveformSamples.value = sampleBuffer.toList()
                    lastPublishedAtMillis = nowMillis
                }
            }
        }
    }

    fun onSamplingButtonClick() {
        when (samplingState.value) {
            CrmSamplingState.STARTING,
            CrmSamplingState.SAMPLING -> {
                crmDataRepository.requestSamplingStop()
            }

            CrmSamplingState.STOPPING -> {
                // Ignore repeated clicks while stopping.
            }

            CrmSamplingState.IDLE,
            CrmSamplingState.NOT_WORN,
            CrmSamplingState.UNAVAILABLE,
            CrmSamplingState.ERROR -> {
                crmDataRepository.requestSamplingStart()
            }
        }
    }

    val uiState: StateFlow<CrmUiState> =
        combine(
            crmDataRepository.latestCrmInference,
            crmDataRepository.thresholdRanges,
            dataRepository.latestIsDeviceWorn,
        ) { inference, thresholds, isDeviceWorn ->
            Triple(inference, thresholds, isDeviceWorn)
        }.scan(CrmUiState.empty()) { previousState, (inference, thresholds, isDeviceWorn) ->
            val latestScore = inference.displayScore

            val acceptedNow =
                inference.status == CrmInferenceStatus.ACCEPTED && latestScore != null

            val lastAcceptedScore = when {
                acceptedNow -> latestScore
                previousState.lastAcceptedScore != null -> previousState.lastAcceptedScore
                else -> null
            }

            val lastAcceptedAtMillis = when {
                acceptedNow -> inference.updatedAtMillis
                else -> previousState.lastAcceptedAtMillis
            }

            val nowMillis = System.currentTimeMillis()

            val hasRecentAcceptedScore =
                lastAcceptedScore != null &&
                        lastAcceptedAtMillis > 0L &&
                        nowMillis - lastAcceptedAtMillis <= CRM_RECENT_READING_MAX_AGE_MS

            val scoreToDisplay = if (hasRecentAcceptedScore) {
                lastAcceptedScore
            } else {
                null
            }

            val displayState = scoreToDisplay?.let { thresholds.stateFor(it) }

            val sampleProgress =
                (inference.sourceSampleCount.toFloat() / CRM_REQUIRED_SAMPLE_COUNT.toFloat())
                    .coerceIn(0f, 1f)

            CrmUiState(
                score = scoreToDisplay,
                lastAcceptedScore = lastAcceptedScore,
                percentValue = if (acceptedNow) {
                    inference.percentValue
                } else {
                    previousState.percentValue
                },
                displayState = displayState,
                thresholds = thresholds,
                status = inference.status,
                updatedAtMillis = inference.updatedAtMillis,
                lastAcceptedAtMillis = lastAcceptedAtMillis,
                isUsingLastAcceptedScore = !acceptedNow && scoreToDisplay != null,
                isDeviceWorn = isDeviceWorn,
                isRecent = hasRecentAcceptedScore,
                requiredSampleCount = CRM_REQUIRED_SAMPLE_COUNT,
                sampleProgress = sampleProgress,
                quality = inference.quality,
                rejectionReason = inference.rejectionReason,
                sourceSampleCount = inference.sourceSampleCount,
                inputWindowDurationMs = inference.inputWindowDurationMs,
                inferenceLatencyMs = inference.inferenceLatencyMs,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CrmUiState.empty(),
        )
}

private const val CRM_REQUIRED_SAMPLE_COUNT = 500
private const val CRM_RECENT_READING_MAX_AGE_MS = 30_000L
private const val CRM_WAVEFORM_SAMPLE_COUNT = 500
private const val CRM_WAVEFORM_PUBLISH_INTERVAL_MS = 100L

data class CrmUiState(
    val score: Int?,
    val lastAcceptedScore: Int?,
    val percentValue: Float?,
    val displayState: CrmDisplayState?,
    val thresholds: CrmThresholdRanges,
    val status: CrmInferenceStatus,
    val updatedAtMillis: Long,

    val lastAcceptedAtMillis: Long,
    val isUsingLastAcceptedScore: Boolean,

    val isDeviceWorn: Boolean,
    val isRecent: Boolean,
    val requiredSampleCount: Int,
    val sampleProgress: Float,

    val quality: Float?,
    val rejectionReason: String?,
    val sourceSampleCount: Int,
    val inputWindowDurationMs: Long,
    val inferenceLatencyMs: Long?,
) {
    val hasValidScore: Boolean
        get() = score != null

    val displayScoreText: String
        get() = score?.toString() ?: "--"

    val displayLabel: String
        get() = when {
            score == null && status == CrmInferenceStatus.NOT_WORN -> "NOT WORN"
            score == null && status == CrmInferenceStatus.IDLE -> "WAITING"
            score == null && status == CrmInferenceStatus.REJECTED -> "NO SIGNAL"
            score == null && status == CrmInferenceStatus.ERROR -> "ERROR"
            displayState == CrmDisplayState.POOR -> "POOR"
            displayState == CrmDisplayState.MODERATE -> "MODERATE"
            displayState == CrmDisplayState.GOOD -> "GOOD"
            else -> ""
        }

    val signalLabel: String
        get() = when {
            status == CrmInferenceStatus.NOT_WORN -> "NOT WORN"
            status == CrmInferenceStatus.ACCEPTED -> "LIVE"
            status == CrmInferenceStatus.REJECTED && score != null -> "OFF"
            status == CrmInferenceStatus.ERROR && score != null -> "LAST KNOWN"
            status == CrmInferenceStatus.IDLE && score != null -> "LAST KNOWN"
            else -> displayLabel
        }

    val guidanceLabel: String
        get() = when {
            !isDeviceWorn -> "Wear watch snugly"
            status == CrmInferenceStatus.ERROR -> "CRM unavailable"
            sourceSampleCount in 1 until requiredSampleCount -> "Collecting PPG • Hold still"
            status == CrmInferenceStatus.REJECTED -> rejectionReason
                ?.takeIf { it.isNotBlank() }
                ?: "Adjust strap for better signal"
            status == CrmInferenceStatus.IDLE -> "Waiting for PPG"
            lastAcceptedScore != null && !isRecent -> "No recent CRM reading"
            status == CrmInferenceStatus.ACCEPTED && isRecent -> "Recent CRM reading"
            else -> "Waiting for usable signal"
        }

    companion object {
        fun empty() = CrmUiState(
            score = null,
            lastAcceptedScore = null,
            percentValue = null,
            displayState = null,
            thresholds = CrmThresholdRanges(
                poorMax = DEFAULT_CRM_POOR_MAX_THRESHOLD,
                moderateMax = DEFAULT_CRM_MODERATE_MAX_THRESHOLD,
            ),
            status = CrmInferenceStatus.IDLE,
            updatedAtMillis = 0L,
            lastAcceptedAtMillis = 0L,
            isUsingLastAcceptedScore = false,
            isDeviceWorn = false,
            isRecent = false,
            requiredSampleCount = CRM_REQUIRED_SAMPLE_COUNT,
            sampleProgress = 0f,
            quality = null,
            rejectionReason = null,
            sourceSampleCount = 0,
            inputWindowDurationMs = 0L,
            inferenceLatencyMs = null,
        )
    }
}

class CrmViewModelFactory(
    private val crmDataRepository: CrmDataRepository,
    private val dataRepository: DataRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CrmViewModel::class.java)) {
            return CrmViewModel(
                crmDataRepository = crmDataRepository,
                dataRepository = dataRepository,
            ) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}