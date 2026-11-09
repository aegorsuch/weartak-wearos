package com.aitech.weartak_civ.service

/** Unknown sensor state deliberately retains normal, continuous connectivity. */
internal class OffBodyTakPolicy {
    @Volatile var batching = false
        private set
    @Volatile private var deadlineMs: Long? = null

    fun observe(worn: Boolean?, chargingOrDocked: Boolean = false,
        stayConnectedOffWrist: Boolean = false): Boolean {
        val next = worn == false && !chargingOrDocked && !stayConnectedOffWrist
        if (next == batching) return false
        batching = next
        deadlineMs = null
        return true
    }

    fun open(nowMs: Long) {
        if (batching) deadlineMs = nowMs + WINDOW_MS
    }

    fun close() { deadlineMs = null }

    fun allows(nowMs: Long): Boolean =
        !batching || deadlineMs?.let { nowMs < it } == true

    companion object {
        const val INTERVAL_MS = 180_000L
        const val WINDOW_MS = 30_000L
    }
}
