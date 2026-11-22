package com.aitech.weartak_civ.integration.plugin

import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.weartak.plugin.sdk.v2.model.DetectionEventParcel
import com.weartak.plugin.sdk.v2.model.V2HostRejectionCode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostControlV2ImplTest {

    @Before
    fun resetDiagnostics() {
        PluginDiagnostics.resetForTest()
    }

    @Test
    fun publishDetectionEvent_returnsNotSupported() {
        val control = v2Control()
        val result = control.publishDetectionEvent(
            DetectionEventParcel(
                pluginId = "com.plugin.pkg",
                streamKey = "detector",
                detectionId = "d1",
                sequence = 1L,
                timestampUtcMillis = 100L,
                classificationLabel = "test",
            ),
        )
        assertFalse(result.accepted)
        assertEquals(V2HostRejectionCode.NOT_SUPPORTED, result.rejectionCode)
        assertEquals(1, PluginDiagnostics.unsupportedV2CallCount("com.plugin.pkg"))
    }

    @Test
    fun publishDetectionEvent_acceptsIdenticalRetryWithoutEmittingAgain() {
        val received = mutableListOf<AudioDetectionEvent>()
        val bridge = PluginDetectionEventBridge("com.weartak.plugin.dart", "gunshot") {
            received += it
            true
        }
        val control = v2Control(
            pluginPackage = "com.weartak.plugin.dart",
            detectionEventBridge = bridge,
        )
        val event = DetectionEventParcel(
            pluginId = "com.weartak.plugin.dart",
            streamKey = "gunshot",
            detectionId = "d1",
            sequence = 1L,
            timestampUtcMillis = 100L,
            classificationLabel = "gunshot",
            confidence = .9f,
        )

        assertTrue(control.hostCapabilities.detectionEvents)
        assertTrue(control.publishDetectionEvent(event).accepted)
        val replay = control.publishDetectionEvent(event)

        assertTrue(replay.accepted)
        assertFalse(control.publishDetectionEvent(event.copy(confidence = .8f)).accepted)
        assertTrue(control.publishDetectionEvent(event.copy(sequence = 2L, detectionId = "d2")).accepted)
        assertFalse(control.publishDetectionEvent(event).accepted)
        assertEquals(2, received.size)
        assertEquals("gunshot", received.first().detectorKey)
        assertEquals("d1", received.first().uid.substringAfterLast('|'))
        assertNull(received.first().chunkRef)
        assertEquals("com.weartak.plugin.dart", received.first().sourcePluginId)
    }

    @Test
    fun publishDetectionEvent_canRetryRejectedDelivery() {
        var attempts = 0
        val bridge = PluginDetectionEventBridge("com.weartak.plugin.dart", "gunshot") { ++attempts > 1 }
        val control = v2Control("com.weartak.plugin.dart", bridge)
        val event = DetectionEventParcel(
            pluginId = "com.weartak.plugin.dart",
            streamKey = "gunshot",
            detectionId = "d1",
            sequence = 1L,
            timestampUtcMillis = 100L,
            classificationLabel = "gunshot",
            confidence = .9f,
        )
        assertFalse(control.publishDetectionEvent(event).accepted)
        assertTrue(control.publishDetectionEvent(event).accepted)
        assertTrue(control.publishDetectionEvent(event).accepted)
        assertEquals(2, attempts)
    }

    @Test
    fun publishDetectionEvent_rejectsEventForDifferentPluginIdentity() {
        val bridge = PluginDetectionEventBridge("com.weartak.plugin.dart", "gunshot") { true }
        val control = v2Control(
            pluginPackage = "com.weartak.plugin.dart",
            detectionEventBridge = bridge,
        )

        val result = control.publishDetectionEvent(
            DetectionEventParcel(
                pluginId = "com.weartak.plugin.falcon",
                streamKey = "gunshot",
                detectionId = "d1",
                sequence = 1L,
                timestampUtcMillis = 100L,
                classificationLabel = "gunshot",
                confidence = .9f,
            ),
        )

        assertFalse(result.accepted)
        assertEquals(V2HostRejectionCode.UNSPECIFIED, result.rejectionCode)
    }

    @Test
    fun getHostCapabilities_detectionAndTaskCommandsFalse() {
        val control = v2Control()
        val caps = control.hostCapabilities
        assertTrue(caps.cepRules)
        assertFalse(caps.detectionEvents)
        assertFalse(caps.taskCommands)
    }

    private fun v2Control(
        pluginPackage: String = "com.plugin.pkg",
        detectionEventBridge: PluginDetectionEventBridge? = null,
    ): HostControlV2Impl {
        val app = mockk<WearTakApplication>(relaxed = true)
        return HostControlV2Impl(
            app = app,
            ingress = mockk(relaxed = true),
            pluginPackage = pluginPackage,
            expectedPluginUid = Process.myUid(),
            detectionEventBridge = detectionEventBridge,
        )
    }
}
