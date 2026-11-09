package com.aitech.weartak_civ.data.takserver

/** Network starts cannot observe provisional settings before the review commits or rolls back. */
internal object ReviewedServerRuntimeGate {
    private val holds = mutableMapOf<String, Int>()
    @Synchronized fun hold(address: String, port: Int) {
        val key = TakServerEndpointIdentity.key(address, port)
        holds[key] = (holds[key] ?: 0) + 1
    }
    @Synchronized fun release(address: String, port: Int) {
        val key = TakServerEndpointIdentity.key(address, port)
        val remaining = requireNotNull(holds[key]) - 1
        if (remaining == 0) holds.remove(key) else holds[key] = remaining
    }
    @Synchronized fun blocked(address: String, port: Int) =
        holds.containsKey(TakServerEndpointIdentity.key(address, port))
}
