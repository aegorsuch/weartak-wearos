/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.weartak.plugin.sdk.v2.model.DetectionEventParcel
import com.weartak.plugin.sdk.v2.model.V2HostRejectionCode
import com.weartak.plugin.sdk.v2.model.V2HostResultParcel

class PluginDetectionEventBridge(
    private val pluginPackage: String,
    private val detectorKey: String,
    private val dartAudioConsent: DartAudioConsent? = null,
    private val acceptEvent: (AudioDetectionEvent) -> Boolean,
) {
    private var lastAccepted: DetectionEventParcel? = null

    @Synchronized
    fun publish(event: DetectionEventParcel): V2HostResultParcel {
        if (pluginPackage == DART_PACKAGE) {
            return dartAudioConsent?.whileEnabled { publishAllowed(event) } ?: run {
                Log.w(TAG, "DART detection rejected: monitoring is disabled")
                rejected()
            }
        }
        return publishAllowed(event)
    }

    private fun publishAllowed(event: DetectionEventParcel): V2HostResultParcel {
        if (event.pluginId != pluginPackage || event.streamKey != detectorKey) {
            Log.w(TAG, "detection event identity mismatch pkg=$pluginPackage stream=${event.streamKey}")
            return rejected()
        }
        if (event.timestampUtcMillis <= 0L || !event.confidence.isFinite() || event.confidence !in 0f..1f) {
            Log.w(TAG, "detection event missing timestamp or confidence pkg=$pluginPackage")
            return rejected()
        }
        val previous = lastAccepted
        if (previous != null && event.sequence == previous.sequence && event == previous) {
            return V2HostResultParcel(accepted = true)
        }
        if (previous != null && event.sequence <= previous.sequence) {
            Log.w(TAG, "detection event sequence replay pkg=$pluginPackage sequence=${event.sequence}")
            return rejected()
        }

        val accepted = acceptEvent(
            AudioDetectionEvent(
                detectorKey = detectorKey,
                timestamp = event.timestampUtcMillis,
                confidence = event.confidence,
                loc = null,
                uid = "$pluginPackage|$detectorKey|${event.sequence}|${event.detectionId}",
                alertLevel = Utils.AlertChipState.Warning,
                chunkRef = null,
                triggerPcmPath = null,
                audioSource = null,
                sourcePluginId = pluginPackage,
                sourceStreamKey = detectorKey,
                classificationLabel = event.classificationLabel,
                attributesJson = event.attributesJson,
            ),
        )
        if (!accepted) return rejected()
        lastAccepted = event
        return V2HostResultParcel(accepted = true)
    }

    private fun rejected(): V2HostResultParcel =
        V2HostResultParcel(accepted = false, rejectionCode = V2HostRejectionCode.UNSPECIFIED)

    companion object {
        private const val TAG = "PluginDetectionBridge"

        fun forPlugin(
            app: WearTakApplication,
            pluginPackage: String,
            dartAudioConsent: DartAudioConsent,
        ): PluginDetectionEventBridge? =
            when (pluginPackage) {
                DART_PACKAGE -> PluginDetectionEventBridge(pluginPackage, "gunshot", dartAudioConsent) {
                    app.gunshotDetectionRepo.acceptPluginEvent(it)
                }
                FALCON_PACKAGE -> PluginDetectionEventBridge(pluginPackage, "uas") {
                    app.uasOnnxRepo.acceptPluginEvent(it)
                }
                else -> null
            }

        const val DART_PACKAGE = "com.weartak.plugin.dart"
        const val FALCON_PACKAGE = "com.weartak.plugin.falcon"
        // True only while the detector has an active PCM stream and successful inference.
        const val DETECTOR_READY_EXTRA = "weartak.audio.detector.ready"
    }
}
