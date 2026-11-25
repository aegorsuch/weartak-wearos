package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.weartak.plugin.sdk.enablepolicy.v1.EnablePolicyContractV1 as Contract
import com.weartak.plugin.sdk.enablepolicy.v1.IEnablePolicyHostV1
import com.weartak.plugin.sdk.enablepolicy.v1.IEnablePolicyListenerV1
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HostEnablePolicyV1Test {
    private lateinit var repo: DataRepository
    private var uid = 42
    private var active = true
    private var trusted = true
    private val listener = object : IEnablePolicyListenerV1.Stub() {
        override fun onPolicyChanged(sessionId: String?, revision: Long) = Unit
    }

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "policy-test"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { it.clear() }
        Unit
    }

    private fun endpoint(session: String = "session") =
        HostEnablePolicyV1(session, 42, { trusted }, { active }, repo, { uid })

    @Test fun proxyRequestsAndExactRevisionApplication() {
        val host = endpoint()
        val remote = object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                host.asBinder().transact(code, data, reply, flags)
        }
        val proxy = IEnablePolicyHostV1.Stub.asInterface(remote)
        assertEquals(Contract.OK, proxy.register("session", listener))
        val initial = proxy.read("session")
        assertTrue(Contract.isValidReadResult(initial))
        assertEquals(Contract.PENDING, initial.getInt(Contract.KEY_APPLICATION))
        val revision = initial.getLong(Contract.KEY_REVISION)
        assertEquals(Contract.REQUEST_ACCEPTED_PENDING, proxy.requestEnabled("session", revision, true))
        assertEquals(Contract.REQUEST_STALE, proxy.requestEnabled("session", revision, false))
        assertEquals(Contract.STALE, proxy.reportApplied("session", revision, Contract.APPLIED))
        val next = proxy.read("session")
        assertTrue(next.getBoolean(Contract.KEY_DESIRED_ENABLED))
        val current = next.getLong(Contract.KEY_REVISION)
        assertTrue(current > revision)
        assertEquals(Contract.INVALID_ARGUMENT, proxy.reportApplied("session", current, Contract.PENDING))
        assertEquals(Contract.OK, proxy.reportApplied("session", current, Contract.APPLIED))
        assertEquals(Contract.APPLIED, proxy.read("session").getInt(Contract.KEY_APPLICATION))
        host.close()
        assertEquals(Contract.INACTIVE_SESSION, proxy.read("session").getInt(Contract.KEY_CODE))
    }

    @Test fun locksAndManagedBatchPreserveExistingSemantics() = runBlocking {
        val host = endpoint()
        assertEquals(Contract.OK, host.register("session", listener))
        repo.storeNetworkPreferencesLocked(true)
        val locked = host.read("session")
        assertTrue(locked.getBoolean(Contract.KEY_LOCAL_ENABLE_BLOCKED))
        assertFalse(locked.getBoolean(Contract.KEY_MENU_NAVIGATION_BLOCKED))
        assertEquals(Contract.REQUEST_LOCKED,
            host.requestEnabled("session", locked.getLong(Contract.KEY_REVISION), true))
        repo.storeEnableDittoService(true)
        assertFalse(repo.getEnableDittoService.first())
        repo.storeManagedNetworkToggles(tak = false, multicast = true, ditto = true)
        assertTrue(repo.getEnableDittoService.first())
        assertFalse(repo.getEnableTakEndpoint.first())
        assertTrue(repo.getEnableMulticast.first())
        assertEquals(Contract.PENDING, host.read("session").getInt(Contract.KEY_APPLICATION))
        host.close()
    }

    @Test fun phoneTransitionsAndRestartAdvanceDurableRevision() = runBlocking {
        val host = endpoint()
        assertEquals(Contract.OK, host.register("session", listener))
        repo.enablePhoneManagedSettings("AA:BB")
        val before = host.read("session").getLong(Contract.KEY_REVISION)
        repo.setPhoneManagementSessionActive(true)
        repo.setPhoneManagementSessionActive(false)
        val afterRoundTrip = host.read("session").getLong(Contract.KEY_REVISION)
        assertTrue(afterRoundTrip >= before + 2)
        repo.setPhoneManagementSessionActive(true)
        val managed = host.read("session")
        assertTrue(managed.getBoolean(Contract.KEY_LOCAL_ENABLE_BLOCKED))
        assertTrue(managed.getBoolean(Contract.KEY_MENU_NAVIGATION_BLOCKED))
        assertTrue(managed.getLong(Contract.KEY_REVISION) > afterRoundTrip)
        repo.setPhoneManagementSessionActive(false)
        val unlocked = host.read("session")
        assertFalse(unlocked.getBoolean(Contract.KEY_MENU_NAVIGATION_BLOCKED))
        assertTrue(unlocked.getLong(Contract.KEY_REVISION) > managed.getLong(Contract.KEY_REVISION))
        host.close()
        repo = DataRepository(RuntimeEnvironment.getApplication())
        val rebound = endpoint("new-session")
        assertEquals(Contract.OK, rebound.register("new-session", listener))
        val restarted = rebound.read("new-session")
        assertTrue(restarted.getLong(Contract.KEY_REVISION) > unlocked.getLong(Contract.KEY_REVISION))
        assertEquals(Contract.PENDING, restarted.getInt(Contract.KEY_APPLICATION))
        rebound.close()
    }

    @Test fun authorizationAndBindingDeathFailClosed() {
        val host = endpoint()
        uid = 43
        assertEquals(Contract.UNAUTHORIZED, host.register("session", listener))
        uid = 42
        assertEquals(Contract.OK, host.register("session", listener))
        trusted = false
        assertEquals(Contract.UNAUTHORIZED, host.read("session").getInt(Contract.KEY_CODE))
        trusted = true
        active = false
        assertEquals(Contract.REQUEST_INACTIVE, host.requestEnabled("session", 1, true))
        host.close()
    }

    @Test fun observerDrainWaitsForInFlightNotification() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingListener = object : IEnablePolicyListenerV1.Stub() {
            override fun onPolicyChanged(sessionId: String?, revision: Long) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val host = endpoint()
        try {
            assertEquals(Contract.OK, host.register("session", blockingListener))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            host.close()
            assertFalse(host.awaitClosed(20))
            release.countDown()
            assertTrue(host.awaitClosed(5_000))
        } finally {
            release.countDown()
            host.close()
        }
    }
}
