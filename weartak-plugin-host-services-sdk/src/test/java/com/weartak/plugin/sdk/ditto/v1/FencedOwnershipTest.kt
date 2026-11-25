package com.weartak.plugin.sdk.ditto.v1

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FencedOwnershipTest {
    @Test
    fun acquireRequiresBothEnginesQuiescentAndExclusiveSession() {
        val gate = FencedOwnership()
        val first = Any()
        assertNull(gate.acquire(first, false, true))
        assertNull(gate.acquire(first, true, false))
        val generation = gate.acquire(first, true, true)!!
        assertTrue(gate.isCurrent(first, generation))
        assertFalse(gate.isCurrent(Any(), generation))
        assertNull(gate.acquire(Any(), true, true))
    }

    @Test
    fun stopFencesOldSessionAndRequiresPositiveConfirmation() {
        val gate = FencedOwnership()
        val first = Any()
        val generation = gate.acquire(first, true, true)!!
        assertFalse(gate.confirmPluginStopped(generation))
        assertFalse(gate.beginStop(Any(), generation))
        assertTrue(gate.beginStop(first, generation))
        assertFalse(gate.isCurrent(first, generation))
        assertNull(gate.acquire(Any(), true, true))
        assertFalse(gate.confirmPluginStopped(generation + 1))
        assertTrue(gate.confirmPluginStopped(generation))
        val second = Any()
        val next = gate.acquire(second, true, true)!!
        assertTrue(next > generation)
        assertFalse(gate.beginStop(first, generation))
        assertFalse(gate.markUncertain(first, generation))
        assertFalse(gate.confirmPluginStopped(generation))
        assertTrue(gate.isCurrent(second, next))
    }

    @Test
    fun disconnectOrTimeoutNeverGrantsFallback() {
        val gate = FencedOwnership()
        val session = Any()
        val generation = gate.acquire(session, true, true)!!
        assertTrue(gate.markUncertain(session, generation))
        assertEquals(FencedOwnership.State.UNCERTAIN, gate.snapshot().state)
        assertFalse(gate.isCurrent(session, generation))
        assertNull(gate.acquire(Any(), true, true))
        assertTrue(gate.confirmPluginStopped(generation))
        assertNotNull(gate.acquire(Any(), true, true))
    }

    @Test
    fun concurrentAcquiresGrantOnlyOneLease() {
        val gate = FencedOwnership()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                pool.submit<Long?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.acquire(Any(), true, true)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) != null })
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}
