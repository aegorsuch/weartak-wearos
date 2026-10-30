package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginConnectionBindTrustTest {

    private val pluginPkg = "com.trust.fail.plugin"
    private val hostPkg = "com.aitech.weartak_civ"
    private val pluginUid = 10_001

    @Before
    fun setup() {
        PluginDiagnostics.resetForTest()
    }

    private fun resolveInfoFor(
        pkg: String,
        className: String = ".Svc",
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
        onBind: (Intent, ServiceConnection) -> Unit,
    ): WearTakApplication {
        val pm = mockk<PackageManager>(relaxed = true)
        @Suppress("DEPRECATION")
        every { pm.queryIntentServices(any<Intent>(), any<Int>()) } returns resolveList
        every { pm.queryIntentServices(any<Intent>(), any<PackageManager.ResolveInfoFlags>()) } returns resolveList
        @Suppress("DEPRECATION")
        every { pm.getApplicationInfo(any<String>(), any<Int>()) } returns ApplicationInfo().apply {
            uid = pluginUid
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
    fun bind_rejectsWhenPluginTrustThrows() {
        val ri = resolveInfoFor(pluginPkg)
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val trust = mockk<PluginTrust>()
        coEvery {
            trust.enforce(pluginPkg, pluginUid, setOf(pluginPkg))
        } throws ParserException(ReasonCodes.PLUGIN_CERT_CHANGED, "cert pin failed")
        val mgr = PluginManager(app, ingress, trust)
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        val c = PluginDiagnostics.snapshot().categoryCounts
        assertTrue((c[PluginDiagnostics.AdmissionCategory.TRUST_FAILURE] ?: 0) >= 1)
    }

    @Test
    fun bind_rejectsWhenSenderPackagesDoNotOwnPluginId() {
        val ri = resolveInfoFor(pluginPkg)
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf("com.other.app")
        val trust = mockk<PluginTrust>(relaxed = true)
        val mgr = PluginManager(app, ingress, trust)
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        val c = PluginDiagnostics.snapshot().categoryCounts
        assertTrue((c[PluginDiagnostics.AdmissionCategory.UID_PACKAGE_MISMATCH] ?: 0) >= 1)
    }

    @Test
    fun bind_rejectsUnsupportedApiVersion() {
        val ri = resolveInfoFor(pluginPkg)
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg, apiVersion = 4).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val trust = mockk<PluginTrust>(relaxed = true)
        val mgr = PluginManager(app, ingress, trust)
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        val c = PluginDiagnostics.snapshot().categoryCounts
        assertTrue((c[PluginDiagnostics.AdmissionCategory.BINDER_PROTOCOL] ?: 0) >= 1)
    }

    @Test
    fun bind_usesInitializePluginV2ForApiVersion2() {
        val ri = resolveInfoFor(pluginPkg)
        lateinit var plugin: FakeWearTakPlugin
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            plugin = FakeWearTakPlugin(pluginPkg, apiVersion = 2)
            conn.onServiceConnected(intent.component!!, plugin.asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val trust = mockk<PluginTrust>(relaxed = true)
        val mgr = PluginManager(app, ingress, trust)
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(1, plugin.initializePluginV2Calls)
        assertEquals(0, plugin.initializePluginCalls)
        assertTrue(plugin.lastInit!!.hostExtras.getBoolean(HostControlImpl.HOST_EXTRA_COT_ROUTING_V1))
        assertTrue(plugin.lastInit!!.hostExtras.getBoolean(HostControlImpl.HOST_EXTRA_CHAT_DISPATCH_V1))
        org.junit.Assert.assertNotNull(
            com.weartak.plugin.sdk.host.v1.HostServicesContract.fromHostExtras(plugin.lastInit!!.hostExtras)
        )
    }

    @Test
    fun bind_usesInitializePluginV3ForApiVersion3() {
        val ri = resolveInfoFor(pluginPkg)
        lateinit var plugin: FakeWearTakPlugin
        val audioRepo = mockk<com.aitech.weartak_civ.data.AudioCaptureRepository>(relaxed = true)
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            plugin = FakeWearTakPlugin(pluginPkg, apiVersion = 3)
            conn.onServiceConnected(intent.component!!, plugin.asBinder())
        }
        every { app.audioRepo } returns audioRepo
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val trust = mockk<PluginTrust>(relaxed = true)
        val mgr = PluginManager(app, ingress, trust)

        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)

        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(1, plugin.initializePluginV3Calls)
        assertEquals(0, plugin.initializePluginV2Calls)
    }

    @Test
    fun unbind_callsStopBeforeShutdown() {
        val ri = resolveInfoFor(pluginPkg)
        lateinit var plugin: FakeWearTakPlugin
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            plugin = FakeWearTakPlugin(pluginPkg)
            conn.onServiceConnected(intent.component!!, plugin.asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val trust = mockk<PluginTrust>(relaxed = true)
        val mgr = PluginManager(app, ingress, trust)
        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        mgr.unbindPackage(pluginPkg)
        awaitPluginLifecycle(pluginPkg)
        assertTrue(plugin.lifecycleCalls.indexOf("stopPlugin") >= 0)
        assertTrue(plugin.lifecycleCalls.indexOf("shutdownPlugin") >= 0)
        assertTrue(plugin.lifecycleCalls.indexOf("stopPlugin") < plugin.lifecycleCalls.indexOf("shutdownPlugin"))
    }
}
