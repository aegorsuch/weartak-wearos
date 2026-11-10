/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service.crm

import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.CrmInferenceState
import com.aitech.weartak_civ.data.CrmInferenceStatus

class CrmInferenceCoordinator(
    private val crmRepo: CrmDataRepository,
    private val csvLogger: CrmSessionCsvLogger,
    private val runnerFactory: () -> CrmModelRunner,
    private val preprocessor: CrmPpgPreprocessor = CrmPpgPreprocessor(),
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val uptimeMillis: () -> Long = { SystemClock.uptimeMillis() }
) : AutoCloseable {
    private val lock = Any()
    private var runner: CrmModelRunner? = null
    private var lastInferenceUptimeMs = 0L
    private var lastRejectedLogUptimeMs = 0L
    private var lastRejectedReason: String? = null
    private var latestSampleUptimeMillis: Long = 0L

    fun warmUpModel() {
        try {
            getRunner()
            Log.i(TAG, "CRM_MODEL_READY")
        } catch (t: Throwable) {
            Log.e(TAG, "CRM_MODEL_LOAD_FAIL", t)
            crmRepo.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.ERROR,
                    updatedAtMillis = wallClockMillis(),
                    rejectionReason = t.message ?: t.javaClass.simpleName
                )
            )
        }
    }

    private var bufferedSampleCount = 0

    fun onRawPpgSample(
        sampleUptimeMillis: Long,
        rawValue: Float,
        sourceAvailable: Boolean,
    ) {
        latestSampleUptimeMillis = sampleUptimeMillis
        if (!sourceAvailable) {
            reset("ppg_source_unavailable")
            return
        }

        val result = synchronized(lock) {
            preprocessor.addSample(
                uptimeMillis = sampleUptimeMillis,
                value = rawValue,
            )

            val now = uptimeMillis()

            if (now - lastInferenceUptimeMs < CrmPpgPreprocessor.MODEL_PARAMETERS.inferenceAttemptPeriodMs) {
                return
            }

            lastInferenceUptimeMs = now
            preprocessor.buildWindow()
        }

        // "Insufficient samples" means keep collecting, not publish/reset.
        if (
            result is CrmWindowResult.Rejected &&
            result.reason == "insufficient_samples"
        ) {
            Log.d(
                TAG,
                "CRM_WINDOW_FILL samples=${result.sourceSampleCount} " +
                        "durationMs=${result.windowDurationMs}"
            )
            return
        }

        publish(result)
    }

    fun reset(reason: String) {
        synchronized(lock) {
            preprocessor.clear()
            bufferedSampleCount = 0
            lastInferenceUptimeMs = 0L
        }

        crmRepo.tryPublish(
            CrmInferenceState(
                status = CrmInferenceStatus.REJECTED,
                updatedAtMillis = wallClockMillis(),
                rejectionReason = reason
            )
        )
    }

    private fun publish(result: CrmWindowResult) {
        when (result) {
            is CrmWindowResult.Accepted -> runInference(result)
            is CrmWindowResult.Rejected -> {
                maybeLogRejectedWindow(result)

                csvLogger.logInference(
                    inferenceUptimeMillis = uptimeMillis(),
                    windowStartUptimeMillis = (latestSampleUptimeMillis - result.windowDurationMs).coerceAtLeast(0L),
                    windowEndUptimeMillis = latestSampleUptimeMillis,
                    sourceSampleCount = result.sourceSampleCount,
                    windowDurationMs = result.windowDurationMs,
                    maxSampleGapMs = null,
                    resampledMin = null,
                    resampledMax = null,
                    resampledAmplitude = null,
                    modelInputSampleCount = 0,
                    quality = null,
                    qualityMethod = CrmPpgPreprocessor.MODEL_PARAMETERS.qualityMethod,
                    minCoverageEnforced = false,
                    maxGapEnforced = false,
                    rawModelOutput = null,
                    displayScore = null,
                    inferenceLatencyMs = null,
                    status = "REJECTED",
                    rejectionReason = result.reason,
                )

                crmRepo.tryPublish(
                    CrmInferenceState(
                        status = CrmInferenceStatus.REJECTED,
                        updatedAtMillis = wallClockMillis(),
                        rejectionReason = result.reason,
                        sourceSampleCount = result.sourceSampleCount,
                        inputWindowDurationMs = result.windowDurationMs,
                    )
                )
            }
        }
    }

    private fun runInference(window: CrmWindowResult.Accepted) {
        val start = SystemClock.elapsedRealtime()

        try {
            Log.d(
                TAG,
                "CRM_INFERENCE_ATTEMPT " +
                        "sourceSamples=${window.sourceSampleCount} " +
                        "modelInputSamples=${window.scaledSamples.size} " +
                        "durationMs=${window.windowDurationMs} " +
                        "quality=${"%.3f".format(window.quality)}"
            )

            val value = getRunner().run(window.scaledSamples)

            val end = SystemClock.elapsedRealtime()
            val latencyMs = end - start
            val displayScore =
                (value.coerceIn(0f, 1f) * 100f).toInt()

            val inferenceIndex = csvLogger.logInference(
                inferenceUptimeMillis = end,
                windowStartUptimeMillis = window.windowStartUptimeMs,
                windowEndUptimeMillis = window.windowEndUptimeMs,
                sourceSampleCount = window.sourceSampleCount,
                windowDurationMs = window.windowDurationMs,
                maxSampleGapMs = window.maxSampleGapMs,
                resampledMin = window.resampledMin,
                resampledMax = window.resampledMax,
                resampledAmplitude = window.resampledAmplitude,
                modelInputSampleCount = window.scaledSamples.size,
                quality = window.quality,
                qualityMethod = CrmPpgPreprocessor.MODEL_PARAMETERS.qualityMethod,
                minCoverageEnforced = false,
                maxGapEnforced = false,
                rawModelOutput = value,
                displayScore = displayScore,
                inferenceLatencyMs = latencyMs,
                status = "ACCEPTED",
                rejectionReason = null,
            )

            if (inferenceIndex >= 0L) {
                csvLogger.logModelInput(
                    inferenceIndex = inferenceIndex,
                    windowStartUptimeMs = window.windowStartUptimeMs,
                    windowEndUptimeMs = window.windowEndUptimeMs,
                    resampledSamples = window.resampledSamples,
                    scaledSamples = window.scaledSamples,
                )
            }

            crmRepo.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.ACCEPTED,
                    value = value,
                    updatedAtMillis = wallClockMillis(),
                    quality = window.quality,
                    sourceSampleCount = window.sourceSampleCount,
                    inputWindowDurationMs = window.windowDurationMs,
                    inferenceLatencyMs = latencyMs,
                )
            )

            Log.d(
                TAG,
                "CRM_INFERENCE value=${"%.4f".format(value)} " +
                        "sourceSamples=${window.sourceSampleCount} " +
                        "modelInputSamples=${window.scaledSamples.size} " +
                        "latencyMs=$latencyMs " +
                        "csvInferenceIndex=$inferenceIndex"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "CRM inference failed", t)

            crmRepo.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.ERROR,
                    updatedAtMillis = wallClockMillis(),
                    rejectionReason = t.message ?: t.javaClass.simpleName,
                    sourceSampleCount = window.sourceSampleCount,
                    inputWindowDurationMs = window.windowDurationMs,
                )
            )
        }
    }

    private fun maybeLogRejectedWindow(result: CrmWindowResult.Rejected) {
        val now = uptimeMillis()
        if (result.reason == lastRejectedReason && now - lastRejectedLogUptimeMs < REJECTION_LOG_PERIOD_MS) return
        lastRejectedReason = result.reason
        lastRejectedLogUptimeMs = now
        Log.d(
            TAG,
            "CRM_WINDOW_REJECT reason=${result.reason} samples=${result.sourceSampleCount} durationMs=${result.windowDurationMs}"
        )
    }

    private fun getRunner(): CrmModelRunner = synchronized(lock) {
        runner ?: runnerFactory().also { runner = it }
    }

    override fun close() {
        synchronized(lock) {
            runner?.close()
            runner = null
            preprocessor.clear()
            bufferedSampleCount = 0
        }
    }

    companion object {
        private const val TAG = "WearTAK-CrmInference"
        private const val REJECTION_LOG_PERIOD_MS = 5_000L
    }
}
