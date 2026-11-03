package com.aitech.weartak_civ.service

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import live.ditto.DittoAddress
import live.ditto.DittoConnection
import live.ditto.DittoConnectionType
import live.ditto.DittoPeer
import live.ditto.DittoPresenceGraph
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DittoLiveStatusTest {
    @Test fun directEndpointsDeduplicateDirectionsSocketsAndGraphCopiesPerTransport() {
        val ble = connection("ble", "watch", "phone", DittoConnectionType.Bluetooth)
        val reverse = connection("reverse", "phone", "watch", DittoConnectionType.Bluetooth)
        val lan = connection("lan", "watch", "phone", DittoConnectionType.AccessPoint)
        val p2p = connection("aware", "tablet", "watch", DittoConnectionType.P2PWiFi)
        val ws = connection("ws", "watch", "server", DittoConnectionType.WebSocket)
        val indirect = connection("indirect", "tablet", "mesh-only", DittoConnectionType.Bluetooth)
        val self = connection("self", "watch", "watch", DittoConnectionType.Bluetooth)
        val graph = graph(listOf(ble, lan, self), listOf(
            peer("phone", listOf(ble, reverse, lan)),
            peer("tablet", listOf(p2p, indirect)),
            peer("server", listOf(ws)),
            peer("mesh-only", listOf(indirect)),
        ))
        assertEquals(DittoDirectPeers(3, 1, 1, 1, 1), dittoDirectPeers(graph))
    }

    @Test fun meshPresenceAloneIsNotDirectAndEmptySnapshotReallyIsZero() {
        val indirect = connection("remote", "phone", "tablet", DittoConnectionType.Bluetooth)
        assertEquals(DittoDirectPeers(0, 0, 0, 0, 0),
            dittoDirectPeers(graph(emptyList(), listOf(peer("phone", listOf(indirect))))))
        assertEquals(DittoDirectPeers(0, 0, 0, 0, 0), dittoDirectPeers(graph(emptyList())))
    }

    @Test fun eachSdkTransportCountsMultipleDistinctPeers() {
        DittoConnectionType.entries.forEach { type ->
            val result = dittoDirectPeers(graph(listOf(
                connection("1", "watch", "one", type),
                connection("2", "two", "watch", type),
                connection("3", "watch", "one", type),
            )))
            assertEquals(2, result.total)
            assertEquals(if (type == DittoConnectionType.Bluetooth) 2 else 0, result.bluetooth)
            assertEquals(if (type == DittoConnectionType.AccessPoint) 2 else 0, result.lan)
            assertEquals(if (type == DittoConnectionType.P2PWiFi) 2 else 0, result.p2pWifi)
            assertEquals(if (type == DittoConnectionType.WebSocket) 2 else 0, result.websocket)
        }
    }

    @Test fun stopGateAndReplacementDiscardStaleCountsAndLateCallbacks() {
        val store = DittoLiveStatusStore()
        assertEquals(DittoLiveStatus(), store.state.value)
        val old = store.begin()
        assertNull(store.state.value.peers)
        val counts = DittoDirectPeers(2, 1, 1, 0, 0)
        old.peers(counts)
        assertNull(store.state.value.peers)
        old.setSyncing(true)
        old.peers(counts)
        old.recovery(DittoRecoveryStatus.ACTIVE)
        assertEquals(DittoLiveStatus(counts, DittoRecoveryStatus.ACTIVE), store.state.value)
        old.setSyncing(false)
        old.peers(counts)
        assertEquals(DittoLiveStatus(recovery = DittoRecoveryStatus.INELIGIBLE), store.state.value)
        old.setSyncing(true)
        assertNull("resume waits for an SDK snapshot", store.state.value.peers)
        val current = store.begin()
        current.setSyncing(true)
        old.peers(counts)
        old.recovery(DittoRecoveryStatus.TIMED_OUT)
        old.close()
        assertEquals(DittoLiveStatus(recovery = DittoRecoveryStatus.INELIGIBLE), store.state.value)
        current.peers(DittoDirectPeers(0, 0, 0, 0, 0))
        assertNotNull("live zero differs from unavailable", store.state.value.peers)
        current.close()
        current.peers(counts)
        current.recovery(DittoRecoveryStatus.ACTIVE)
        assertEquals(DittoLiveStatus(), store.state.value)
    }

    @Test fun recoveryPublishesActualControllerTransitionsEvenWithoutTransportWrite() = runTest {
        val states = mutableListOf<DittoRecoveryStatus>()
        val writes = mutableListOf<Boolean>()
        val discovery = DittoBleDiscovery(this, { writes += it; true }, {},
            elapsedClock = { testScheduler.currentTime }, jitter = { .5 }, statusChanged = { states += it })
        discovery.setAllowed(true)
        assertEquals(DittoRecoveryStatus.ACTIVE, states.last())
        val beforeLink = writes.toList()
        discovery.peersChanged(setOf("link"), 1)
        assertEquals(beforeLink, writes)
        assertEquals(DittoRecoveryStatus.ACTIVE, states.last())
        discovery.peersChanged(emptySet(), 0)
        discovery.pauseRetries()
        assertEquals(DittoRecoveryStatus.USER_PAUSED, states.last())
        discovery.setRadioAvailable(false)
        assertEquals(DittoRecoveryStatus.INELIGIBLE, states.last())
        discovery.setRadioAvailable(true)
        assertEquals(DittoRecoveryStatus.USER_PAUSED, states.last())
        discovery.retryNow()
        assertEquals(DittoRecoveryStatus.ACTIVE, states.last())
        advanceTimeBy(DittoBleDiscovery.NO_LINK_BUDGET_MS)
        runCurrent()
        assertEquals(DittoRecoveryStatus.TIMED_OUT, states.last())
        discovery.setAllowed(false)
        assertEquals(DittoRecoveryStatus.INELIGIBLE, states.last())
        discovery.setAllowed(true)
        assertEquals(DittoRecoveryStatus.TIMED_OUT, states.last())
        discovery.close()
        assertEquals(DittoRecoveryStatus.STOPPED, states.last())
    }

    @Test fun verifiedCapabilitiesSurviveStopButAreNotAssumedForReplacementService() {
        val store = DittoLiveStatusStore()
        val session = store.begin()
        val transports = DittoDiagnosticTransports(true, true, websocket = true)
        session.transports(transports)
        session.setSyncing(true)
        session.peers(DittoDirectPeers(0, 0, 0, 0, 0))
        session.setSyncing(false)
        assertEquals(transports, store.state.value.transports)
        assertNull(store.state.value.peers)
        session.close()
        assertEquals(transports, store.state.value.transports)
        assertEquals(DittoRecoveryStatus.STOPPED, store.state.value.recovery)
        store.begin()
        assertNull(store.state.value.transports)
        session.transports(transports)
        assertNull(store.state.value.transports)
    }

    // SDK snapshot constructors are Kotlin-internal; use real immutable field-backed snapshots.
    private fun connection(id: String, first: String, second: String, type: DittoConnectionType) =
        DittoConnection::class.java.declaredConstructors.single { it.parameterCount == 7 }.newInstance(
            id, byteArrayOf(), byteArrayOf(), first, second, type, null
        ) as DittoConnection

    private fun peer(key: String, connections: List<DittoConnection>) =
        DittoPeer::class.java.declaredConstructors.single { it.parameterCount == 12 }.newInstance(
            mockk<DittoAddress>(), connections, "Test", "4.14.6", true, false,
            emptyMap<String, Any?>(), "Android", byteArrayOf(), key, emptyMap<String, Any?>(), 0
        ) as DittoPeer

    private fun graph(local: List<DittoConnection>, remote: List<DittoPeer> = emptyList()) =
        DittoPresenceGraph::class.java.getDeclaredConstructor(DittoPeer::class.java, List::class.java)
            .newInstance(peer("watch", local), remote)
}
