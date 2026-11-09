package com.aitech.weartak_civ.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoConnectionType
import live.ditto.DittoLogCallback
import live.ditto.DittoLogger
import live.ditto.DittoLogLevel
import live.ditto.DittoPresenceGraph
import live.ditto.transports.DittoTransportConfig

internal enum class DittoDiagnosticPhase(val code: Long) {
    START(0), IDENTITY(1), INITIALIZE(2), SYNC(3), POWER(4), TRANSPORT(5),
    SUBSCRIPTIONS(6), COMPANION(7), STOP(8), DIAGNOSTICS(9)
}

internal fun recordDittoLifecycle(phase: DittoDiagnosticPhase, success: Boolean) {
    if (AppDiagnostics.recordingEnabled?.value != true) return
    AppDiagnostics.record(DiagnosticEvent.DITTO_LIFECYCLE, mapOf(
        DiagnosticMetric.KIND to phase.code,
        DiagnosticMetric.RESULT to if (success) 1L else 0L
    ))
}

internal fun dittoTransportMetrics(config: DittoTransportConfig) = mapOf(
    // Bits describe SDK configuration, not established connections or hardware activity.
    DiagnosticMetric.TRANSPORT to (
        (if (config.peerToPeer.bluetoothLe.enabled) 1L else 0L) or
            (if (config.peerToPeer.lan.enabled) 2L else 0L) or
            (if (config.peerToPeer.wifiAware.enabled) 4L else 0L) or
            (if (config.connect.websocketUrls.isNotEmpty()) 8L else 0L)
        )
)

internal fun dittoPeerMetrics(graph: DittoPresenceGraph): Map<DiagnosticMetric, Long> {
    val local = graph.localPeer
    val connections = local.connections.distinctBy { it.id }.filter {
        it.peerKeyString1 == local.peerKeyString || it.peerKeyString2 == local.peerKeyString
    }
    return mapOf(
        DiagnosticMetric.CONNECTED_COUNT to graph.remotePeers.size.toLong(),
        DiagnosticMetric.SOCKET_COUNT to connections.size.toLong(),
        DiagnosticMetric.TRANSPORT to connections.fold(0L) { mask, connection ->
            mask or when (connection.connectionType) {
                DittoConnectionType.Bluetooth -> 1L
                DittoConnectionType.AccessPoint -> 2L
                DittoConnectionType.P2PWiFi -> 4L
                DittoConnectionType.WebSocket -> 8L
            }
        }
    )
}

/** No SDK text, peer identifiers, addresses, metadata or document contents enter the journal. */
internal class DittoDiagnostics(
    scope: CoroutineScope,
    enabled: StateFlow<Boolean>,
    ditto: Ditto,
    private val emit: (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Unit,
    private val observe: (Ditto, (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Unit) -> AutoCloseable =
        ::observeDittoDiagnostics
) {
    init {
        scope.launch {
            enabled.collectLatest { recording ->
                if (!recording) return@collectLatest
                registrationMutex.withLock {
                    val lock = Any()
                    var accepting = true
                    try {
                        val registration = observe(ditto) { event, metrics ->
                            synchronized(lock) { if (accepting && enabled.value) emit(event, metrics) }
                        }
                        try {
                            awaitCancellation()
                        } finally {
                            synchronized(lock) { accepting = false }
                            registration.close()
                        }
                    } catch (_: DittoError) {
                        synchronized(lock) { accepting = false }
                        emit(DiagnosticEvent.DITTO_LIFECYCLE, mapOf(
                            DiagnosticMetric.KIND to DittoDiagnosticPhase.DIAGNOSTICS.code,
                            DiagnosticMetric.RESULT to 0L
                        ))
                        Log.e("WearTAK-DittoDiagnostics", "Could not attach or close Ditto diagnostics")
                    }
                }
            }
        }
    }

    companion object {
        // The SDK callback is process-global. A replacement service must await old cleanup.
        private val registrationMutex = Mutex()
    }
}

internal fun observeDittoDiagnostics(
    ditto: Ditto,
    emit: (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Unit,
    installLogCallback: (DittoLogCallback) -> Unit = { DittoLogger.setCustomLogCallback(it) },
    removeLogCallback: () -> Unit = { DittoLogger.unsetCustomLogCallback() }
): AutoCloseable {
    // Do not change global SDK verbosity or enable raw SDK file logging.
    installLogCallback(object : DittoLogCallback {
        override fun log(level: DittoLogLevel, message: String) {
            emit(DiagnosticEvent.DITTO_SDK_LOG, mapOf(DiagnosticMetric.KIND to when (level) {
                DittoLogLevel.ERROR -> 0L
                DittoLogLevel.WARNING -> 1L
                DittoLogLevel.INFO -> 2L
                DittoLogLevel.DEBUG -> 3L
                DittoLogLevel.VERBOSE -> 4L
            }))
        }
    })
    try {
        emit(DiagnosticEvent.DITTO_TRANSPORTS, dittoTransportMetrics(ditto.transportConfig))
        var previous: Map<DiagnosticMetric, Long>? = null
        val lock = Any()
        fun snapshot(graph: DittoPresenceGraph) {
            synchronized(lock) {
                val metrics = dittoPeerMetrics(graph)
                if (metrics != previous) {
                    previous = metrics
                    emit(DiagnosticEvent.DITTO_PEERS, metrics)
                }
            }
        }
        val observer = ditto.presence.observe { graph: DittoPresenceGraph -> snapshot(graph) }
        try {
            snapshot(ditto.presence.graph)
        } catch (error: Throwable) {
            // DittoError extends Throwable, not Exception; cleanup must cover both.
            observer.close()
            throw error
        }
        return AutoCloseable {
            try {
                observer.close()
            } finally {
                removeLogCallback()
            }
        }
    } catch (error: Throwable) {
        removeLogCallback()
        throw error
    }
}
