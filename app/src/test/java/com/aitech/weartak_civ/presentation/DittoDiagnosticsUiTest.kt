package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.service.DittoDiagnosticTransports
import com.aitech.weartak_civ.service.DittoDirectPeers
import com.aitech.weartak_civ.service.DittoLiveStatus
import org.junit.Assert.*
import org.junit.Test

class DittoDiagnosticsUiTest {
    @Test fun compactSummaryKeepsUniqueTotalSeparateFromOverlappingTransports() {
        assertEquals("3 Peers (2 BLE, 3 LAN)",
            dittoDiagnosticSummary(listOf("3 Peers", "2 BLE", "3 LAN")))
        assertEquals("Unavailable Peers (Unavailable BLE, Unavailable LAN)",
            dittoDiagnosticSummary(listOf("Unavailable Peers", "Unavailable BLE", "Unavailable LAN")))
        assertEquals("0 Peers", dittoDiagnosticSummary(listOf("0 Peers")))
        assertEquals("3 Peers (2 BLE, 3 LAN, 0 Wi-Fi Aware, 1 WebSocket)",
            dittoDiagnosticSummary(listOf("3 Peers", "2 BLE", "3 LAN", "0 Wi-Fi Aware", "1 WebSocket")))
    }

    @Test fun unsupportedOrUnconfiguredTransportRowsAreAbsentNotZero() {
        val state = DittoLiveStatus(DittoDirectPeers(3, 2, 1, 0, 0),
            transports = DittoDiagnosticTransports(bluetooth = true, lan = true))
        assertEquals(listOf("total", "ble", "lan"), dittoDiagnosticPeerRows(state).map { it.third })
        assertEquals(listOf(3, 2, 1), dittoDiagnosticPeerRows(state).map { it.second })
        assertEquals(listOf(null, null, null),
            dittoDiagnosticPeerRows(state.copy(peers = null)).map { it.second })
        assertEquals(listOf("total", "ble", "lan", "p2p", "websocket"),
            dittoDiagnosticPeerRows(state.copy(transports = DittoDiagnosticTransports(true, true, true, true)))
                .map { it.third })
    }

    @Test fun unknownCapabilitiesDoNotInventTransportSupport() {
        assertEquals(listOf("total"), dittoDiagnosticPeerRows(DittoLiveStatus()).map { it.third })
        assertEquals(listOf("total", "ble"), dittoDiagnosticPeerRows(DittoLiveStatus(
            transports = DittoDiagnosticTransports(bluetooth = true, lan = false))).map { it.third })
    }
}
