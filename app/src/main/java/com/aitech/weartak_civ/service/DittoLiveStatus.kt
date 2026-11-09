package com.aitech.weartak_civ.service

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import live.ditto.DittoConnectionType
import live.ditto.DittoPresenceGraph
import live.ditto.transports.DittoTransportConfig

internal data class DittoDiagnosticTransports(
    val bluetooth: Boolean,
    val lan: Boolean,
    val p2pWifi: Boolean = false,
    val websocket: Boolean = false,
)

internal fun dittoDiagnosticTransports(
    features: PackageManager,
    config: DittoTransportConfig? = null,
    websocketConfigured: Boolean = config?.connect?.websocketUrls?.isNotEmpty() == true,
) = DittoDiagnosticTransports(
    bluetooth = features.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
    lan = features.hasSystemFeature(PackageManager.FEATURE_WIFI),
    p2pWifi = features.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
        config?.peerToPeer?.wifiAware?.enabled == true,
    websocket = websocketConfigured,
)

internal data class DittoDirectPeers(
    val total: Int,
    val bluetooth: Int,
    val lan: Int,
    val p2pWifi: Int,
    val websocket: Int,
)

/** Counts distinct opposite endpoints, not sockets, mesh presence or requested transports. */
internal fun dittoDirectPeers(graph: DittoPresenceGraph): DittoDirectPeers {
    val local = graph.localPeer.peerKeyString
    val peers = DittoConnectionType.entries.associateWith { mutableSetOf<String>() }
    val connections = graph.localPeer.connections.asSequence() +
        graph.remotePeers.asSequence().flatMap { it.connections.asSequence() }
    connections.forEach { connection ->
        val remote = when (local) {
            connection.peerKeyString1 -> connection.peerKeyString2
            connection.peerKeyString2 -> connection.peerKeyString1
            else -> null
        }
        if (remote != null && remote != local) peers.getValue(connection.connectionType).add(remote)
    }
    return DittoDirectPeers(
        peers.values.flatten().toSet().size,
        peers.getValue(DittoConnectionType.Bluetooth).size,
        peers.getValue(DittoConnectionType.AccessPoint).size,
        peers.getValue(DittoConnectionType.P2PWiFi).size,
        peers.getValue(DittoConnectionType.WebSocket).size,
    )
}

internal enum class DittoRecoveryStatus { ACTIVE, TIMED_OUT, USER_PAUSED, INELIGIBLE, STOPPED, UNAVAILABLE }

internal data class DittoLiveStatus(
    val peers: DittoDirectPeers? = null,
    val recovery: DittoRecoveryStatus = DittoRecoveryStatus.STOPPED,
    val transports: DittoDiagnosticTransports? = null,
)

/** A replaced or stopped service cannot publish a late SDK callback into a newer session. */
internal class DittoLiveStatusStore {
    private val mutable = MutableStateFlow(DittoLiveStatus())
    val state = mutable.asStateFlow()
    private var generation = 0L

    @Synchronized
    fun begin(): Session {
        generation++
        mutable.value = DittoLiveStatus(recovery = DittoRecoveryStatus.UNAVAILABLE)
        return Session(generation)
    }

    internal inner class Session(private val owner: Long) : AutoCloseable {
        private var closed = false
        private var syncing = false

        fun setSyncing(value: Boolean) = synchronized(this@DittoLiveStatusStore) {
            if (closed || owner != generation || syncing == value) return@synchronized
            syncing = value
            mutable.value = mutable.value.copy(peers = null, recovery = DittoRecoveryStatus.INELIGIBLE)
        }

        fun transports(value: DittoDiagnosticTransports) = synchronized(this@DittoLiveStatusStore) {
            if (!closed && owner == generation) mutable.value = mutable.value.copy(transports = value)
        }

        fun peers(value: DittoDirectPeers) = synchronized(this@DittoLiveStatusStore) {
            if (!closed && owner == generation && syncing) mutable.value = mutable.value.copy(peers = value)
        }

        fun recovery(value: DittoRecoveryStatus) = synchronized(this@DittoLiveStatusStore) {
            if (!closed && owner == generation) mutable.value = mutable.value.copy(
                recovery = if (syncing) value else DittoRecoveryStatus.INELIGIBLE
            )
        }

        override fun close() = synchronized(this@DittoLiveStatusStore) {
            if (closed) return@synchronized
            closed = true
            if (owner == generation) mutable.value = mutable.value.copy(
                peers = null, recovery = DittoRecoveryStatus.STOPPED)
        }
    }
}
