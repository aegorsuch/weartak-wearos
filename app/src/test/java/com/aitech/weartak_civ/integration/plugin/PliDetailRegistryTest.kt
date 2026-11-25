package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.pli.v1.PliDetailContractV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PliDetailRegistryTest {
    private val registry = PliDetailRegistry()

    @After fun close() = registry.close()

    private fun response(): Bundle = Bundle().apply {
        putInt(PliDetailContractV1.KEY_STATUS, PliDetailContractV1.OK)
        putParcelableArrayList(PliDetailContractV1.KEY_DETAILS, arrayListOf(Bundle().apply {
            putString(PliDetailContractV1.KEY_TAG, "__hail")
            putBundle(PliDetailContractV1.KEY_ATTRIBUTES, Bundle().apply { putString("version", "1") })
            putString(PliDetailContractV1.KEY_TEXT, "payload")
        }))
    }

    @Test fun onlyRegisteredTagsMayBeEmitted() {
        val owner = Any()
        assertEquals(PliDetailRegistry.Registration.REGISTERED,
            registry.register(owner, listOf("__hail"), { true }) { response() })
        assertEquals("<__hail version='1'>payload</__hail>", registry.build(Bundle()))
        assertEquals(PliDetailRegistry.Registration.BUSY,
            registry.register(Any(), listOf("__hail"), { true }) { response() })
        registry.unregister(owner)
        assertEquals("", registry.build(Bundle()))
    }

    @Test fun providerFailureAndNonOkStatusesLeavePliUnchanged() {
        registry.register(Any(), listOf("first"), { true }) { throw IllegalStateException() }
        listOf(
            PliDetailContractV1.DISABLED,
            PliDetailContractV1.UNLICENSED,
            PliDetailContractV1.ERROR,
        ).forEachIndexed { index, status ->
            registry.register(Any(), listOf("second$index"), { true }) {
                Bundle().apply { putInt(PliDetailContractV1.KEY_STATUS, status) }
            }
        }
        assertEquals("", registry.build(Bundle()))
    }

    @Test fun unregisteredInFlightResponseIsDiscarded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<String>()
        val owner = Any()
        registry.register(owner, listOf("__hail"), { true }) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            response()
        }
        val caller = thread {
            result.set(registry.build(Bundle()))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        registry.unregister(owner)
        release.countDown()
        caller.join(5000)
        assertEquals("", result.get())
    }

    @Test fun policyInvalidationDiscardsInFlightResponse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val result = AtomicReference<String>()
        registry.register(Any(), listOf("__hail"), active::get) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            response()
        }
        val caller = thread { result.set(registry.build(Bundle())) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            active.set(false)
        } finally {
            release.countDown()
            caller.join(5000)
        }
        assertEquals("", result.get())
    }

    @Test fun timedOutProviderDoesNotBlockPli() {
        val release = CountDownLatch(1)
        registry.register(Any(), listOf("__hail"), { true }) {
            try {
                release.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // Simulate a remote Binder call that does not stop on cancellation.
                release.await(5, TimeUnit.SECONDS)
            }
            response()
        }
        try {
            assertEquals("", registry.build(Bundle()))
        } finally {
            release.countDown()
        }
    }

    @Test fun saturatedWorkersRejectInsteadOfQueueing() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        registry.register(Any(), listOf("__hail"), { true }) {
            calls.incrementAndGet()
            entered.countDown()
            try {
                release.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                release.await(5, TimeUnit.SECONDS)
            }
            response()
        }
        val callers = List(2) { thread { registry.build(Bundle()) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("", registry.build(Bundle()))
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            callers.forEach { it.join(5000) }
        }
    }
}
