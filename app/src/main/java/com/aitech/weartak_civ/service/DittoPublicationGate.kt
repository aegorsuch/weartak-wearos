package com.aitech.weartak_civ.service

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Counts admitted work until its synchronous native call and completion callback return. */
internal class DittoPublicationGate {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var closed = false
    private var uncertain = false
    private var active = 0

    inner class Admission internal constructor() : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (!released.compareAndSet(false, true)) return
            lock.withLock {
                active--
                if (active == 0) drained.signalAll()
            }
        }
    }

    fun admit(): Admission? = lock.withLock {
        if (closed) return null
        active++
        Admission()
    }

    fun markUncertain() = lock.withLock { uncertain = true }

    fun closeAndDrain(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0)
        lock.withLock {
            closed = true
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            try {
                while (active != 0 && remaining > 0) remaining = drained.awaitNanos(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            return active == 0 && !uncertain
        }
    }
}
