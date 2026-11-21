package com.aitech.weartak_civ.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.integration.plugin.DittoRuntimeHandoff
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.dittoruntime.DittoRuntimeResultCode
import com.weartak.plugin.sdk.dittoruntime.aidl.IDittoRuntimeV1
import com.weartak.plugin.sdk.model.PluginInitParcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Separate debug fixture only; this does not exercise production trust or native Ditto. */
@RunWith(AndroidJUnit4::class)
class InertDittoLeaseIpcTest {
    /** Run separately after coldFencedSessionSubmitsOnlyNegotiatedSyntheticPli and host process stop. */
    @Test
    fun persistedFenceRejectsAfterProcessRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPersistedFence") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == HOST_PACKAGE)
        assertTrue(DittoService.isOwnershipFenced(context))
        assertFalse(DittoService.fenceColdStartForPlugin(context))
    }

    @Test
    fun coldFencedSessionSubmitsOnlyNegotiatedSyntheticPli() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == HOST_PACKAGE)
        val pm = context.packageManager
        val fixture = pm.getApplicationInfo(FIXTURE_PACKAGE, 0)
        assertNotEquals(context.applicationInfo.uid, fixture.uid)
        assertEquals(PackageManager.SIGNATURE_MATCH,
            pm.checkSignatures(context.applicationInfo.uid, fixture.uid))
        assertEquals(setOf(FIXTURE_PACKAGE), pm.getPackagesForUid(fixture.uid)?.toSet())

        val lifecycleConnection = FixtureConnection()
        val runtimeConnection = FixtureConnection()
        val lifecycleIntent = Intent(LIFECYCLE_ACTION)
            .setClassName(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.LifecycleService")
        val runtimeIntent = Intent(RUNTIME_ACTION)
            .setClassName(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.RuntimeService")
        assertTrue(context.bindService(lifecycleIntent, lifecycleConnection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(context.bindService(runtimeIntent, runtimeConnection, Context.BIND_AUTO_CREATE))
            try {
                val lifecycleBinder = lifecycleConnection.await()
                val runtimeBinder = runtimeConnection.await()
                val lifecycle = requireNotNull(IWearTakPlugin.Stub.asInterface(lifecycleBinder))
                val runtime = requireNotNull(IDittoRuntimeV1.Stub.asInterface(runtimeBinder))
                var session: IBinder? = null
                var generation = 0L
                val handoff = DittoRuntimeHandoff(
                    context, ComponentName(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.RuntimeService"),
                    RUNTIME_ACTION, fixture.uid,
                    lifecycleActive = { lifecycleConnection.active.get() && lifecycleBinder.isBinderAlive },
                    endpointTrusted = {
                        pm.checkSignatures(context.applicationInfo.uid, fixture.uid) == PackageManager.SIGNATURE_MATCH &&
                            pm.getPackagesForUid(fixture.uid)?.toSet() == setOf(FIXTURE_PACKAGE)
                    },
                    firstStartProven = { true },
                    fenceAndQuiesce = { runBlocking { DittoService.fenceColdStartForPlugin(context) } },
                )
                try {
                    assertTrue(handoff.acquire { leaseSession, leaseGeneration ->
                        session = leaseSession
                        generation = leaseGeneration
                        val extras = Bundle().apply {
                            putInt(PROFILE_KEY, 1)
                            putBinder(SESSION_KEY, leaseSession)
                            putLong(GENERATION_KEY, leaseGeneration)
                        }
                        lifecycle.initializePluginV2(
                            PluginInitParcel(context.packageName, context.applicationInfo.uid,
                                FIXTURE_PACKAGE, extras), null, null)
                        val profile = lifecycle.status.extras
                        profile.getInt(PROFILE_KEY) == 1 &&
                            profile.getBinder(SESSION_KEY) == leaseSession &&
                            profile.getLong(GENERATION_KEY) == leaseGeneration
                    })
                    assertTrue(handoff.ownershipConfirmed())
                    val leaseSession = requireNotNull(session)
                    assertTrue(generation > 0)
                    assertEquals(DittoRuntimeResultCode.OK, runtime.start(leaseSession, generation).code)

                    val snapshot = DittoTrackSnapshot(
                    eventTime = 1_700_000_000_000L,
                    staleTime = 1_700_000_045_000L,
                    location = DataRepository.LocationSnapshot(
                        timeMillis = 1_699_999_999_000L, lat = 0.0, lon = 0.0,
                        hae = 0.0, ce = 5f, le = 8f, course = null, speed = null),
                    uid = "synthetic-watch", callsign = "SYNTHETIC", role = "Team Member",
                    team = "Blue", battery = 80, remarks = "", monitoredPhysio = null,
                    moppGeneration = null, intervalSeconds = 15,
                )
                    val pli = snapshot.toPluginPli()
                    assertEquals(DittoRuntimeResultCode.INACTIVE_LEASE,
                        runtime.publishCot(Binder(), generation, pli).code)
                    assertEquals(DittoRuntimeResultCode.INACTIVE_LEASE,
                        runtime.publishCot(leaseSession, generation + 1, pli).code)
                    assertEquals(DittoRuntimeResultCode.OK, runtime.publishCot(leaseSession, generation, pli).code)
                    handoff.release()
                    assertFalse(handoff.ownershipConfirmed())
                    assertEquals(DittoRuntimeResultCode.INACTIVE_LEASE,
                        runtime.publishCot(leaseSession, generation, pli).code)
                    assertFalse(lifecycle.status.extras.containsKey(PROFILE_KEY))
                    assertFalse(DittoService.fenceColdStartForPlugin(context))
                } finally {
                    handoff.release()
                }
            } finally {
                runtimeConnection.active.set(false)
                context.unbindService(runtimeConnection)
            }
        } finally {
            lifecycleConnection.active.set(false)
            context.unbindService(lifecycleConnection)
        }
    }

    private class FixtureConnection : ServiceConnection {
        val active = AtomicBoolean(false)
        private val connected = CountDownLatch(1)
        private val binder = AtomicReference<IBinder>()

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder.set(service)
            active.set(true)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) { active.set(false) }
        override fun onBindingDied(name: ComponentName) { active.set(false) }
        override fun onNullBinding(name: ComponentName) { connected.countDown() }

        fun await(): IBinder {
            check(connected.await(10, TimeUnit.SECONDS)) { "Fixture connection timed out" }
            return checkNotNull(binder.get()) { "Fixture returned a null binder" }
        }
    }

    private companion object {
        const val HOST_PACKAGE = "com.aitech.weartak_civ.ditto.diagnostics.fixture"
        const val FIXTURE_PACKAGE = "com.weartak.plugin.ditto.syntheticfixture"
        const val LIFECYCLE_ACTION = "$FIXTURE_PACKAGE.LIFECYCLE"
        const val RUNTIME_ACTION = "$FIXTURE_PACKAGE.RUNTIME"
        const val PROFILE_KEY = "com.weartak.plugin.ditto.pli.profile"
        const val SESSION_KEY = "com.weartak.plugin.ditto.pli.session"
        const val GENERATION_KEY = "com.weartak.plugin.ditto.pli.generation"
    }
}
