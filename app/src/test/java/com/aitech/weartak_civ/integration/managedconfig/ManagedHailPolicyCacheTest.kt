package com.aitech.weartak_civ.integration.managedconfig

import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManagedHailPolicyCacheTest {
    @Test fun refreshInvalidatesImmediatelyAndFailsClosedOnErrors() = runTest {
        var restrictions = Bundle()
        var fail = false
        val cache = ManagedHailPolicyCache(this) {
            if (fail) throw IllegalStateException("unavailable")
            restrictions
        }
        assertFalse(cache.policy.value.loaded)
        cache.refresh()
        runCurrent()
        assertEquals(true, cache.policy.value.loaded)
        assertNull(cache.policy.value.enabled)

        restrictions = Bundle().apply { putBoolean("hail_service_enabled", false) }
        cache.refresh()
        assertFalse(cache.policy.value.loaded)
        runCurrent()
        assertEquals(false, cache.policy.value.enabled)

        fail = true
        cache.refresh()
        runCurrent()
        assertFalse(cache.policy.value.loaded)
        fail = false
        restrictions = Bundle().apply { putString("hail_service_enabled", "false") }
        cache.refresh()
        runCurrent()
        assertFalse(cache.policy.value.loaded)
    }

    @Test fun olderRefreshCannotOverwriteNewerPolicy() = runTest {
        val first = CompletableDeferred<Bundle>()
        val second = CompletableDeferred<Bundle>()
        var reads = 0
        val cache = ManagedHailPolicyCache(this) {
            if (reads++ == 0) first.await() else second.await()
        }
        cache.refresh()
        runCurrent()
        cache.refresh()
        runCurrent()
        second.complete(Bundle().apply { putBoolean("hail_service_enabled", false) })
        runCurrent()
        assertEquals(false, cache.policy.value.enabled)
        first.complete(Bundle().apply { putBoolean("hail_service_enabled", true) })
        runCurrent()
        assertEquals(false, cache.policy.value.enabled)
    }
}
