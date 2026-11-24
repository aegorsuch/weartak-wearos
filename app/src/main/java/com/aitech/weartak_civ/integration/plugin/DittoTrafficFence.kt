package com.aitech.weartak_civ.integration.plugin

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Revokes a traffic generation immediately and completes its drain after admitted effects finish.
 */
internal class DittoTrafficFence(initiallyEnabled: Boolean) {
    private val lock = Any()
    private var enabled = initiallyEnabled
    private var generation = 1L
    private var exhausted = false
    private var inFlight = 0
    private var drain = CompletableDeferred<Unit>().apply { complete(Unit) }

    fun currentGeneration(): Long? = synchronized(lock) {
        generation.takeIf { enabled }
    }

    fun enter(expectedGeneration: Long): AutoCloseable? = synchronized(lock) {
        if (!enabled || generation != expectedGeneration) return@synchronized null
        inFlight++
        val closed = AtomicBoolean(false)
        AutoCloseable {
            if (closed.compareAndSet(false, true)) leave()
        }
    }

    fun disable(): Deferred<Unit> {
        var completed: CompletableDeferred<Unit>? = null
        val result = synchronized(lock) {
            if (enabled) {
                enabled = false
                if (generation == Long.MAX_VALUE) exhausted = true else generation++
                drain = CompletableDeferred()
                if (inFlight == 0) completed = drain
            }
            drain
        }
        completed?.complete(Unit)
        return result
    }

    fun enable(): Long? = synchronized(lock) {
        if (enabled) return@synchronized generation
        if (exhausted || !drain.isCompleted || inFlight != 0) return@synchronized null
        enabled = true
        generation
    }

    private fun leave() {
        val completed = synchronized(lock) {
            check(inFlight > 0)
            inFlight--
            drain.takeIf { !enabled && inFlight == 0 }
        }
        completed?.complete(Unit)
    }
}
