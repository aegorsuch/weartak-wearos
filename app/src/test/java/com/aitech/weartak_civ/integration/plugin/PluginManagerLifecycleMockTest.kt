package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * Bind / unbind / discovery orchestration for [PluginManager] without a real plugin APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginManagerLifecycleMockTest {

    private val pluginPkg = "com.weartak.fake.plugin"
    private val hostPkg = "com.aitech.weartak_civ"

    @Before
    fun setup() {
        PluginDiagnostics.resetForTest()
        PluginIngressLocator.clearTestOverride()
    }

    @After
    fun tearDown() {
        PluginIngressLocator.clearTestOverride()
    }

    private fun noopPluginTrust(): PluginTrust = mockk(relaxed = true)

    private fun stubIngressSenderPackages(ingress: PluginIngressPort, pkgs: Set<String>) {
        every { ingress.packagesForUid(any(), any()) } returns pkgs
    }

    private fun resolveInfoFor(
        pkg: String,
        className: String,
        permission: String? = com.weartak.plugin.sdk.WearTakPluginContract.PERMISSION_BIND_PLUGIN,
    ): ResolveInfo {
        val si = ServiceInfo().apply {
            packageName = pkg
            name = className
            this.permission = permission
        }
        return ResolveInfo().apply { serviceInfo = si }
    }

    private fun mockHostApp(
        resolveList: List<ResolveInfo>,
        onBind: (Intent, ServiceConnection) -> Unit = { _, _ -> },
    ): WearTakApplication {
        val pm = mockk<PackageManager>(relaxed = true)
        @Suppress("DEPRECATION")
        every { pm.queryIntentServices(any<Intent>(), any<Int>()) } returns resolveList
        every { pm.queryIntentServices(any<Intent>(), any<PackageManager.ResolveInfoFlags>()) } returns resolveList
        @Suppress("DEPRECATION")
        every { pm.getApplicationInfo(any<String>(), any<Int>()) } returns ApplicationInfo().apply {
            uid = 10_001
        }

        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.packageName } returns hostPkg
        every { app.packageManager } returns pm
        every {
            app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        } answers {
            val intent = invocation.args[0] as Intent
            val conn = invocation.args[1] as ServiceConnection
            onBind(intent, conn)
            true
        }
        every { app.unbindService(any<ServiceConnection>()) } answers { }
        return app
    }

    @Test
    fun refreshBindings_bindsDiscoveredPluginsOnce() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        val captured = mutableListOf<ServiceConnection>()
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            captured.add(conn)
            val cn = intent.component!!
            conn.onServiceConnected(cn, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        mgr.refreshBindings()
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(1, mgr.activeConnectionCountForTest())
        assertEquals(1, captured.size)
        val snap = PluginDiagnostics.snapshot()
        assertTrue(snap.discovered.contains(pluginPkg))
        assertTrue(snap.bound.contains(pluginPkg))
        assertTrue(snap.connected.contains(pluginPkg))
    }

    @Test
    fun unbindPackage_removesConnectionAndRecordsUnbind() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        mgr.unbindPackage(pluginPkg)
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertTrue(PluginDiagnostics.snapshot().unbindEvents >= 1)
        verify(atLeast = 1) { app.unbindService(any<ServiceConnection>()) }
    }

    @Test
    fun onServiceDisconnected_clearsConnectionFromManager() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        lateinit var sc: ServiceConnection
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            sc = conn
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        sc.onServiceDisconnected(ComponentName(pluginPkg, ".FakeSvc"))
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(
            PluginLoadingStatus.NOT_LOADED,
            PluginStatusMapper.mapLoadingStatus(pluginPkg, PluginDiagnostics.snapshot())
        )
        assertTrue(
            (PluginDiagnostics.snapshot().categoryCounts[PluginDiagnostics.AdmissionCategory.LIFECYCLE_DISCONNECT]
                ?: 0) >= 1
        )
    }

    @Test
    fun lostConnections_rebindOnceForDisconnectDeathAndPackageUpdate() {
        for (event in listOf("disconnect", "death", "update")) {
            val captured = mutableListOf<ServiceConnection>()
            val app = mockHostApp(listOf(resolveInfoFor(pluginPkg, ".FakeSvc"))) { intent, conn ->
                captured.add(conn)
                conn.onServiceConnected(intent.component, FakeWearTakPlugin(pluginPkg).asBinder())
            }
            val ingress = mockk<PluginIngressPort>(relaxed = true)
            stubIngressSenderPackages(ingress, setOf(pluginPkg))
            val mgr = PluginManager(app, ingress, noopPluginTrust())
            mgr.refreshBindings()
            awaitPluginLifecycle(pluginPkg)
            val old = captured.single() as PluginConnection
            when (event) {
                "disconnect" -> old.onServiceDisconnected(null)
                "death" -> old.triggerBinderDeathSyncForTest()
                "update" -> old.onBindingDied(null)
            }
            // Android can report both service disconnect and binder death for one loss.
            old.triggerBinderDeathSyncForTest()
            assertFalse(mgr.hasConnectionForTest(pluginPkg))
            awaitPluginLifecycle(pluginPkg)
            verify(exactly = 1) { app.unbindService(old) }
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1))
            awaitPluginLifecycle(pluginPkg)
            assertTrue(mgr.hasConnectionForTest(pluginPkg))
            assertEquals(2, captured.size)
            // Late callbacks from the old binder cannot evict its replacement.
            old.onServiceDisconnected(null)
            assertTrue(mgr.hasConnectionForTest(pluginPkg))
            mgr.shutdownAll()
            awaitPluginLifecycle(pluginPkg)
        }
    }

    @Test
    fun explicitUnbindAndShutdown_cancelPendingRecovery() {
        for (shutdown in listOf(false, true)) {
            val app = mockHostApp(listOf(resolveInfoFor(pluginPkg, ".FakeSvc"))) { intent, conn ->
                conn.onServiceConnected(intent.component, FakeWearTakPlugin(pluginPkg).asBinder())
            }
            val ingress = mockk<PluginIngressPort>(relaxed = true)
            stubIngressSenderPackages(ingress, setOf(pluginPkg))
            val mgr = PluginManager(app, ingress, noopPluginTrust())
            mgr.refreshBindings()
            awaitPluginLifecycle(pluginPkg)
            mgr.connectionForTest(pluginPkg)!!.onBindingDied(null)
            if (shutdown) mgr.shutdownAll() else mgr.unbindPackage(pluginPkg)
            // Finish remote cleanup before the next iteration reuses the same package.
            awaitPluginLifecycle(pluginPkg)
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMinutes(1))
            assertFalse(mgr.hasConnectionForTest(pluginPkg))
            verify(exactly = 1) { app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
        }
    }

    @Test
    fun failedRecovery_usesBoundedExponentialBackoff() {
        val app = mockHostApp(listOf(resolveInfoFor(pluginPkg, ".FakeSvc"))) { intent, conn ->
            conn.onServiceConnected(intent.component, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        every { app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns false
        mgr.connectionForTest(pluginPkg)!!.onBindingDied(null)
        awaitPluginLifecycle(pluginPkg)
        for (seconds in listOf(1L, 2L, 4L, 8L, 16L)) {
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(seconds))
        }
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMinutes(1))
        verify(exactly = 6) { app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
    }

    @Test
    fun nullBinding_isRejectedWithoutRetry() {
        val app = mockHostApp(listOf(resolveInfoFor(pluginPkg, ".FakeSvc")))
        val mgr = PluginManager(app, mockk(relaxed = true), noopPluginTrust())
        mgr.refreshBindings()
        mgr.connectionForTest(pluginPkg)!!.onNullBinding(null)
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMinutes(1))
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        verify(exactly = 1) { app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
    }

    @Test
    fun onConnectionFailed_fromNullBinder_recordsDiagnosticsAndEvicts() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, null)
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertTrue(PluginDiagnostics.snapshot().bindFailures >= 1)
        assertTrue(PluginDiagnostics.snapshot().deniedAdmission >= 1)
        assertEquals(
            1,
            PluginDiagnostics.snapshot().categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_PROTOCOL] ?: 0
        )
    }

    @Test
    fun shutdownAll_unbindsEveryActiveConnection() {
        val r1 = resolveInfoFor("com.plugin.one", ".A")
        val r2 = resolveInfoFor("com.plugin.two", ".B")
        val app = mockHostApp(listOf(r1, r2)) { intent, conn ->
            val pkg = intent.component!!.packageName
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf("com.plugin.one", "com.plugin.two"))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        awaitPluginLifecycle("com.plugin.one")
        awaitPluginLifecycle("com.plugin.two")
        assertEquals(2, mgr.activeConnectionCountForTest())
        mgr.shutdownAll()
        awaitPluginLifecycle("com.plugin.one")
        awaitPluginLifecycle("com.plugin.two")
        assertEquals(0, mgr.activeConnectionCountForTest())
        verify(atLeast = 2) { app.unbindService(any<ServiceConnection>()) }
    }
}
