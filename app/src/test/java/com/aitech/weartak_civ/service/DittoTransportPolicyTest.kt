package com.aitech.weartak_civ.service

import android.app.Application
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import live.ditto.transports.DittoTransportConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoTransportPolicyTest {
    @Test fun samsungPrivateDirectFeatureDoesNotEnableUnsupportedAwareAndBleIsUntouched() {
        val features = mockk<PackageManager>()
        every { features.hasSystemFeature(any()) } answers { firstArg<String>() == "sem.hardware.wifi.direct" }
        val config = DittoTransportConfig().apply { enableAllPeerToPeer() }
        DittoTransportPolicy.apply(config, features, false, false, emptySet())
        assertFalse(config.peerToPeer.wifiAware.enabled)
        assertFalse(config.peerToPeer.lan.enabled)
        assertTrue(config.peerToPeer.bluetoothLe.enabled)
        assertEquals(setOf("BLE"), DittoTransportPolicy.enabled(config))
    }

    @Test fun wifiInternetAndPublicFeatureChangesGateOnlyTheirOwnTransports() {
        val features = mockk<PackageManager>()
        every { features.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) } returns true
        val config = DittoTransportConfig().apply {
            peerToPeer.bluetoothLe.enabled = false
            connect.retryIntervalMs = 12_345
        }
        val cloud = setOf("wss://example.invalid/sync")
        DittoTransportPolicy.apply(config, features, true, false, cloud)
        assertEquals(setOf("LAN", "Wi-Fi Aware"), DittoTransportPolicy.enabled(config))
        DittoTransportPolicy.apply(config, features, false, true, cloud)
        assertEquals(setOf("Wi-Fi Aware", "WebSocket"), DittoTransportPolicy.enabled(config))
        assertEquals(cloud, config.connect.websocketUrls)
        assertEquals(12_345, config.connect.retryIntervalMs)
        DittoTransportPolicy.apply(config, features, true, true, emptySet())
        assertTrue(config.connect.websocketUrls.isEmpty())
        assertFalse(config.peerToPeer.bluetoothLe.enabled)
    }

    @Test fun diagnosticCapabilitiesRequirePublicAwareAndConfiguredWebsocketNotPrivateDirect() {
        val features = mockk<PackageManager>()
        every { features.hasSystemFeature(any()) } answers {
            firstArg<String>() in setOf(PackageManager.FEATURE_BLUETOOTH_LE,
                PackageManager.FEATURE_WIFI, "sem.hardware.wifi.direct")
        }
        val config = DittoTransportConfig().apply { enableAllPeerToPeer() }
        assertEquals(DittoDiagnosticTransports(true, true), dittoDiagnosticTransports(features, config))
        config.peerToPeer.bluetoothLe.enabled = false
        config.peerToPeer.lan.enabled = false
        assertEquals("temporary BLE window/LAN network gates do not hide usable hardware",
            DittoDiagnosticTransports(true, true), dittoDiagnosticTransports(features, config))
        every { features.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) } returns true
        assertTrue(dittoDiagnosticTransports(features, config).p2pWifi)
        config.peerToPeer.wifiAware.enabled = false
        assertFalse(dittoDiagnosticTransports(features, config).p2pWifi)
        assertFalse("unknown service config cannot claim Aware is usable", dittoDiagnosticTransports(features).p2pWifi)
        config.connect.websocketUrls = mutableSetOf("wss://example.invalid/sync")
        assertTrue(dittoDiagnosticTransports(features, config).websocket)
        config.connect.websocketUrls.clear()
        assertTrue("a configured endpoint survives temporary internet gating",
            dittoDiagnosticTransports(features, config, websocketConfigured = true).websocket)
        assertFalse(dittoDiagnosticTransports(features, config).websocket)
    }
}
