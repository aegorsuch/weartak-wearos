/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service.audio

import android.content.Context
import android.os.Build
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

class AudioDetectionMetricsLogger(
    context: Context,
    private val spec: AudioDetectionSpec
) {
    private val appContext = context.applicationContext
    private val debugRootDir by lazy { File(appContext.filesDir, spec.debugDirName).apply { mkdirs() } }
    private val sessionId by lazy { buildSessionId() }
    private val sessionDir by lazy { File(debugRootDir, "session_$sessionId").apply { mkdirs() } }

    private val repo by lazy {
        try {
            (appContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(appContext)
        }
    }

    private var sessionMetadataWritten = false
    private var currentMetricsSegmentIndex = 0
    private var currentMetricsFile: File? = null
    private var currentMetricsWriter: BufferedWriter? = null
    private var currentMetricsBytes = 0L
    private var lastMetricsFlushAtMs = 0L
    private var cachedSystemSnapshot: DataRepository.SystemSnapshot? = null
    private var cachedSystemSnapshotAtMs = 0L

    suspend fun logInferenceWindow(
        chunk: AudioCaptureRepository.AudioPcmChunk,
        streamSegmentIndex: Int,
        windowIndex: Long,
        windowStartSample: Long,
        windowEndSample: Long,
        thresholdUsed: Float,
        inferStartElapsedMs: Long,
        inferEndElapsedMs: Long,
        inference: AudioDetectionInferenceResult,
        audioSource: String,
        triggerUid: String?,
        triggerPcmPath: String?,
        triggered: Boolean,
        cooldownSuppressed: Boolean,
        cpuStartMs: Long,
        cpuEndMs: Long
    ) {
        val system = snapshotSystemCached()
        ensureSessionDirectory()
        ensureSessionMetadata(system, thresholdUsed)

        val inferDurationMs = (inferEndElapsedMs - inferStartElapsedMs).coerceAtLeast(0L)
        val chunkToInferLatencyMs = (inferStartElapsedMs - chunk.timestampElMs).coerceAtLeast(0L)
        val cpuDeltaMs = (cpuEndMs - cpuStartMs).coerceAtLeast(0L)
        val cpuUtilizationPct =
            if (inferDurationMs > 0L) {
                round((cpuDeltaMs.toDouble() * 10000.0) / inferDurationMs) / 100.0
            } else {
                null
            }

        val obj = JSONObject().apply {
            put("metricsSessionId", sessionId)
            put("recordedAtMs", System.currentTimeMillis())
            put("streamSegmentIndex", streamSegmentIndex)
            put("windowIndex", windowIndex)
            put("windowStartSample", windowStartSample)
            put("windowEndSample", windowEndSample)
            put("thresholdUsed", round(thresholdUsed * 10000f) / 10000f)

            put("audioSessionId", chunk.sessionId)
            put("audioSeq", chunk.seq)
            put("audioChunkTimestampMs", chunk.timestampMs)
            put("audioChunkElapsedMs", chunk.timestampElMs)
            put("sampleRateHz", chunk.sampleRateHz)
            put("channels", chunk.channels)
            put("chunkSampleCount", chunk.samples.size)
            put("audioSource", audioSource)

            put("inferStartElapsedMs", inferStartElapsedMs)
            put("inferEndElapsedMs", inferEndElapsedMs)
            put("inferDurationMs", inferDurationMs)
            put("chunkToInferLatencyMs", chunkToInferLatencyMs)

            put("inferenceSucceeded", inference.inferenceSucceeded)
            putNullable("errorMessage", inference.errorMessage)
            putNullable("confidence", inference.confidence?.let { round(it * 10000f) / 10000f })
            put("detected", inference.detected)
            put("triggered", triggered)
            put("cooldownSuppressed", cooldownSuppressed)
            putNullable("triggerUid", triggerUid)
            putNullable("triggerPcmPath", triggerPcmPath)

            put("processCpuStartMs", cpuStartMs)
            put("processCpuEndMs", cpuEndMs)
            put("processCpuDeltaMs", cpuDeltaMs)
            putNullable("processCpuUtilizationPct", cpuUtilizationPct)

            putNullable("batteryPercent", system.batteryPercent)
            put("charging", system.systemStates.charging)
            put("dockType", system.systemStates.dockType.name)
            put("isWorn", system.systemStates.isWorn)
            put("desiredReportIntervalSecs", system.systemStates.desiredReportInterval)
            put("actualReportIntervalSecs", system.systemStates.actualReportInterval)
        }

        appendMetricsLine(
            line = obj.toString() + "\n",
            forceFlush = triggered
        )
    }

    @Synchronized
    fun triggerPcmFile(uid: String): File {
        ensureSessionDirectory()
        return File(sessionDir, "$uid.pcm")
    }

    @Synchronized
    fun onTriggerPcmSaved() {
        enforceBudget(
            root = debugRootDir,
            maxBytes = spec.triggerPcmDirMaxBytes,
            protected = emptySet()
        ) { it.extension.equals("pcm", ignoreCase = true) }
    }

    @Synchronized
    fun close() {
        currentMetricsWriter?.flush()
        currentMetricsWriter?.close()
        currentMetricsWriter = null
        currentMetricsFile = null
        currentMetricsBytes = 0L
        lastMetricsFlushAtMs = 0L
    }

    private suspend fun snapshotSystemCached(): DataRepository.SystemSnapshot {
        val now = System.currentTimeMillis()
        val cached = cachedSystemSnapshot
        if (cached != null && now - cachedSystemSnapshotAtMs < SYSTEM_SNAPSHOT_CACHE_MS) {
            return cached
        }

        return repo.snapshotSystem().also {
            cachedSystemSnapshot = it
            cachedSystemSnapshotAtMs = now
        }
    }

    @Synchronized
    private fun ensureSessionDirectory() {
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }
    }

    private fun ensureSessionMetadata(
        system: DataRepository.SystemSnapshot,
        initialThresholdUsed: Float
    ) {
        if (sessionMetadataWritten) return

        val metadataFile = File(sessionDir, "session.json")
        val overlapPct = round((1.0 - spec.hopSamples.toDouble() / spec.windowSamples.toDouble()) * 1000.0) / 10.0
        val obj = JSONObject().apply {
            put("metricsSessionId", sessionId)
            put("createdAtMs", System.currentTimeMillis())
            put("detectorKey", spec.detectorKey)
            put("modelAssetName", spec.assetName)
            put("defaultThreshold", spec.threshold.toDouble())
            put("initialThresholdUsed", round(initialThresholdUsed * 10000f) / 10000f)
            put("thresholdConfiguredDynamically", true)
            put("targetSampleRateHz", spec.targetSr)
            put("targetSamples", spec.targetSamples)
            put("windowSamples", spec.windowSamples)
            put("hopSamples", spec.hopSamples)
            put("hopMs", (spec.hopSamples.toLong() * 1000L) / spec.targetSr)
            put("overlapPercent", overlapPct)
            put("triggerCooldownMs", spec.triggerCooldownMs)
            put("metricsSegmentMaxBytes", spec.metricsSegmentMaxBytes)
            put("metricsDirMaxBytes", spec.metricsDirMaxBytes)
            put("triggerPcmDirMaxBytes", spec.triggerPcmDirMaxBytes)

            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("wearTakVersion", system.wearTakVersion)
            put("callsign", system.callsign)
            put("deviceId", system.deviceId)
        }

        metadataFile.writeText(obj.toString(2), StandardCharsets.UTF_8)
        sessionMetadataWritten = true
    }

    @Synchronized
    private fun appendMetricsLine(line: String, forceFlush: Boolean) {
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size.toLong()
        ensureMetricsWriter(lineBytes)
        currentMetricsWriter?.write(line)
        currentMetricsBytes += lineBytes

        val now = System.currentTimeMillis()
        if (forceFlush || now - lastMetricsFlushAtMs >= METRICS_FLUSH_INTERVAL_MS) {
            currentMetricsWriter?.flush()
            lastMetricsFlushAtMs = now
        }

        enforceBudget(
            root = debugRootDir,
            maxBytes = spec.metricsDirMaxBytes,
            protected = setOfNotNull(currentMetricsFile)
        ) {
            it.name.startsWith("${spec.detectorKey}_inference_metrics-") &&
                it.extension.equals("jsonl", ignoreCase = true)
        }
    }

    @Synchronized
    private fun ensureMetricsWriter(nextLineBytes: Long) {
        if (currentMetricsWriter == null) {
            rotateMetricsSegment()
            return
        }

        if (currentMetricsBytes + nextLineBytes > spec.metricsSegmentMaxBytes) {
            rotateMetricsSegment()
        }
    }

    @Synchronized
    private fun rotateMetricsSegment() {
        currentMetricsWriter?.flush()
        currentMetricsWriter?.close()

        val file = File(
            sessionDir,
            "${spec.detectorKey}_inference_metrics-${currentMetricsSegmentIndex.toString().padStart(4, '0')}.jsonl"
        )
        currentMetricsSegmentIndex += 1
        currentMetricsFile = file
        currentMetricsWriter = BufferedWriter(FileWriter(file, true))
        currentMetricsBytes = file.length()
        lastMetricsFlushAtMs = 0L
    }

    private fun enforceBudget(
        root: File,
        maxBytes: Long,
        protected: Set<File>,
        predicate: (File) -> Boolean
    ) {
        if (maxBytes <= 0L) return

        val files = root.walkTopDown()
            .filter { it.isFile && predicate(it) }
            .sortedBy { it.lastModified() }
            .toList()

        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= maxBytes) return

        for (file in files) {
            if (file in protected) continue
            if (totalBytes <= maxBytes) break

            val fileBytes = file.length()
            if (file.delete()) {
                totalBytes -= fileBytes
            }
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) {
            put(key, JSONObject.NULL)
        } else {
            put(key, value)
        }
    }

    private fun buildSessionId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    companion object {
        private const val SYSTEM_SNAPSHOT_CACHE_MS = 1000L
        private const val METRICS_FLUSH_INTERVAL_MS = 1000L

        fun currentProcessCpuTimeMs(): Long = Process.getElapsedCpuTime()
    }
}
