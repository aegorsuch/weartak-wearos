package com.aitech.weartak_civ.service

import android.app.Application
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import live.ditto.Ditto
import live.ditto.DittoAddress
import live.ditto.DittoConnection
import live.ditto.DittoConnectionType
import live.ditto.DittoError
import live.ditto.DittoLogCallback
import live.ditto.DittoLogLevel
import live.ditto.DittoPeer
import live.ditto.DittoPresenceGraph
import live.ditto.DittoPresenceObserver
import live.ditto.Presence
import live.ditto.transports.DittoTransportConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoDiagnosticsTest {
    @Test fun sdkHookAndPresenceExportOnlyNumericMetadataThroughExistingJournal() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).edit().clear().commit()
        val diagnostics = AppDiagnostics(app)
        diagnostics.clear()
        val captureMethod = AppDiagnostics::class.java.getDeclaredMethod(
            "capture", DiagnosticEvent::class.java, Map::class.java
        ).apply { isAccessible = true }
        val emit: (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Unit = { event, metrics ->
            captureMethod.invoke(diagnostics, event, metrics)
            Unit
        }
        val presence = mockk<Presence>()
        val observer = mockk<DittoPresenceObserver>(relaxed = true)
        val ditto = mockk<Ditto>()
        val callback = slot<(DittoPresenceGraph) -> Unit>()
        val secret = "PDL-shared-key-license-token-address-callsign-chat"
        val config = DittoTransportConfig().apply {
            enableAllPeerToPeer()
            connect.websocketUrls = mutableSetOf("wss://$secret.invalid")
        }
        val ble = connection("ble-secret", "watch-secret", secret, DittoConnectionType.Bluetooth)
        val lan = connection("lan-secret", secret, "watch-secret", DittoConnectionType.AccessPoint)
        val indirect = connection("indirect-secret", secret, "other-secret", DittoConnectionType.WebSocket)
        val graph = graph(listOf(ble, ble, lan, indirect), secret)
        every { ditto.presence } returns presence
        every { ditto.transportConfig } returns config
        every { presence.graph } returns graph
        every { presence.observe(capture(callback)) } returns observer
        var sdkCallback: DittoLogCallback? = null
        var removed = 0
        try {
            diagnostics.setEnabled(true)
            val registration = observeDittoDiagnostics(ditto, emit,
                installLogCallback = { sdkCallback = it }, removeLogCallback = { removed++ })
            try {
                repeat(100) { sdkCallback!!.log(DittoLogLevel.ERROR, secret) }
                DittoLogLevel.values().filter { it != DittoLogLevel.ERROR }.forEach {
                    sdkCallback!!.log(it, secret)
                }
                callback.captured(graph)
                val json = diagnostics.exportJson()
                listOf(secret, "ble-secret", "lan-secret", "watch-secret", "other-secret",
                    "indirect-secret", "wss://").forEach { assertFalse(it, json.contains(it)) }
                val events = JsonParser.parseString(json).asJsonObject["events"].asJsonArray
                    .map { it.asJsonObject }
                val peers = events.filter { it["event"].asString == "DITTO_PEERS" }
                assertEquals(1, peers.size)
                val metrics = peers.single()["metrics"].asJsonObject
                assertEquals(1, metrics["CONNECTED_COUNT"].asInt)
                assertEquals(2, metrics["SOCKET_COUNT"].asInt)
                assertEquals(3, metrics["TRANSPORT"].asInt)
                assertEquals(15, events.single { it["event"].asString == "DITTO_TRANSPORTS" }
                    ["metrics"].asJsonObject["TRANSPORT"].asInt)
                val logs = events.filter { it["event"].asString == "DITTO_SDK_LOG" }
                assertEquals(104, logs.sumOf { it["metrics"].asJsonObject["COUNT"].asInt })
                assertEquals((0..4).toSet(), logs.map {
                    it["metrics"].asJsonObject["KIND"].asInt
                }.toSet())
                callback.captured(graph(emptyList(), secret))
                assertEquals(2, JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"]
                    .asJsonArray.count { it.asJsonObject["event"].asString == "DITTO_PEERS" })
            } finally {
                registration.close()
            }
            assertEquals(1, removed)
            verify(exactly = 1) { observer.close() }
            verify(exactly = 0) { ditto.startSync() }
            verify(exactly = 0) { ditto.stopSync() }
            verify(exactly = 0) { ditto.transportConfig = any() }
            diagnostics.setEnabled(false)
            val retained = diagnostics.exportJson()
            emit(DiagnosticEvent.DITTO_LIFECYCLE, mapOf(DiagnosticMetric.KIND to 42L))
            assertEquals(retained, diagnostics.exportJson())
            diagnostics.clear()
            assertEquals(0, JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"]
                .asJsonArray.size())
        } finally {
            diagnostics.setEnabled(false)
            diagnostics.close()
        }
    }

    @Test fun optInOwnsRegistrationsAndRejectsLateCallbacksAcrossDisableRestartAndShutdown() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val enabled = MutableStateFlow(false)
        val ditto = mockk<Ditto>()
        val callbacks = mutableListOf<(DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Unit>()
        val emitted = mutableListOf<DiagnosticEvent>()
        var closed = 0
        fun startSession() = DittoDiagnostics(scope, enabled, ditto, { event, _ -> emitted += event },
            observe = { _, callback ->
                callbacks += callback
                AutoCloseable { closed++ }
            })
        try {
            startSession()
            runCurrent()
            assertTrue(callbacks.isEmpty())
            enabled.value = true
            runCurrent()
            assertEquals(1, callbacks.size)
            callbacks[0](DiagnosticEvent.DITTO_PEERS, emptyMap())
            assertEquals(listOf(DiagnosticEvent.DITTO_PEERS), emitted)
            enabled.value = false
            callbacks[0](DiagnosticEvent.DITTO_PEERS, emptyMap())
            runCurrent()
            assertEquals(1, closed)
            enabled.value = true
            runCurrent()
            assertEquals(2, callbacks.size)
            callbacks[0](DiagnosticEvent.DITTO_PEERS, emptyMap())
            assertEquals(1, emitted.size)
            callbacks[1](DiagnosticEvent.DITTO_SDK_LOG, emptyMap())
            startSession()
            runCurrent()
            // A second service cannot replace the process-global SDK hook before old cleanup.
            assertEquals(2, callbacks.size)
        } finally {
            scope.cancel()
            runCurrent()
        }
        assertEquals(2, closed)
        callbacks.last()(DiagnosticEvent.DITTO_PEERS, emptyMap())
        assertEquals(listOf(DiagnosticEvent.DITTO_PEERS, DiagnosticEvent.DITTO_SDK_LOG), emitted)
    }

    @Test fun failedPresenceRegistrationRemovesSdkHookIncludingSdkThrowableErrors() {
        val ditto = mockk<Ditto>()
        val presence = mockk<Presence>()
        every { ditto.transportConfig } returns DittoTransportConfig()
        every { ditto.presence } returns presence
        val error = mockk<DittoError>(relaxed = true)
        every { presence.observe(any<(DittoPresenceGraph) -> Unit>()) } throws error
        var removed = 0
        try {
            observeDittoDiagnostics(ditto, { _, _ -> },
                installLogCallback = {}, removeLogCallback = { removed++ })
            fail("Expected registration failure")
        } catch (caught: DittoError) {
            assertSame(error, caught)
            assertEquals(1, removed)
        }
    }

    @Test fun sdkRegistrationFailureIsReportedWithoutExceptionTextOrStoppingSync() = runTest {
        val enabled = MutableStateFlow(true)
        val ditto = mockk<Ditto>()
        val error = mockk<DittoError>(relaxed = true)
        every { error.message } returns "shared-key-license-token-secret"
        val emitted = mutableListOf<Pair<DiagnosticEvent, Map<DiagnosticMetric, Long>>>()
        DittoDiagnostics(backgroundScope, enabled, ditto, { event, metrics -> emitted += event to metrics },
            observe = { _, _ -> throw error })
        runCurrent()
        assertEquals(listOf(DiagnosticEvent.DITTO_LIFECYCLE to mapOf(
            DiagnosticMetric.KIND to DittoDiagnosticPhase.DIAGNOSTICS.code,
            DiagnosticMetric.RESULT to 0L
        )), emitted)
        val log = ShadowLog.getLogsForTag("WearTAK-DittoDiagnostics").single()
        assertFalse(log.msg.contains("secret"))
        assertNull(log.throwable)
        verify(exactly = 0) { ditto.stopSync() }
    }

    private fun connection(id: String, first: String, second: String, type: DittoConnectionType) =
        DittoConnection::class.java.declaredConstructors.single { it.parameterCount == 7 }.newInstance(
            id, byteArrayOf(), byteArrayOf(), first, second, type, null
        ) as DittoConnection

    private fun peer(key: String, connections: List<DittoConnection>) =
        DittoPeer::class.java.declaredConstructors.single { it.parameterCount == 12 }.newInstance(
            mockk<DittoAddress>(), connections, key, key, true, false,
            mapOf("secret" to key), key, byteArrayOf(), key, mapOf("secret" to key), 0
        ) as DittoPeer

    private fun graph(connections: List<DittoConnection>, remote: String) =
        DittoPresenceGraph::class.java.getDeclaredConstructor(DittoPeer::class.java, List::class.java)
            .newInstance(peer("watch-secret", connections), listOf(peer(remote, emptyList())))
}
