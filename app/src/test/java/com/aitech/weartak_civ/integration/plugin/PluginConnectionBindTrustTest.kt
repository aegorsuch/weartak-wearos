package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportHostV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    private val bindFlags = mutableListOf<Int>()

    @Before
    fun setup() {
        PluginDiagnostics.resetForTest()
        bindFlags.clear()
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
        onAllowActivityStarts: () -> Unit = {},
        resolvedUid: Int = pluginUid,
        onBind: (Intent, ServiceConnection) -> Unit,
    ): WearTakApplication {
        val pm = mockk<PackageManager>(relaxed = true)
        @Suppress("DEPRECATION")
        every { pm.queryIntentServices(any<Intent>(), any<Int>()) } returns resolveList
        every { pm.queryIntentServices(any<Intent>(), any<PackageManager.ResolveInfoFlags>()) } returns resolveList
        every { pm.getPackageUid(any<String>(), any<PackageManager.PackageInfoFlags>()) } returns resolvedUid
        @Suppress("DEPRECATION")
        every { pm.getApplicationInfo(any<String>(), any<Int>()) } returns ApplicationInfo().apply {
            uid = resolvedUid
        }
        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.packageName } returns hostPkg
        every { app.packageManager } returns pm
        every {
            app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        } answers {
            val intent = invocation.args[0] as Intent
            val conn = invocation.args[1] as ServiceConnection
            bindFlags += invocation.args[2] as Int
            if (bindFlags.last() and Context.BIND_ALLOW_ACTIVITY_STARTS != 0) {
                onAllowActivityStarts()
            } else {
                onBind(intent, conn)
            }
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
        assertEquals(listOf(Context.BIND_AUTO_CREATE), bindFlags)
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
        assertEquals(listOf(Context.BIND_AUTO_CREATE), bindFlags)
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
        assertEquals(listOf(Context.BIND_AUTO_CREATE), bindFlags)
    }

    @Test
    fun bind_allowsActivityStartsOnlyAfterVerifiedInitialization() {
        var verified = false
        lateinit var plugin: FakeWearTakPlugin
        val app = mockHostApp(
            listOf(resolveInfoFor(pluginPkg)),
            onAllowActivityStarts = {
                assertTrue(verified)
                assertEquals(1, plugin.lifecycleCalls.count { it == "startPlugin" })
            },
        ) { intent, conn ->
            assertFalse(verified)
            plugin = FakeWearTakPlugin(pluginPkg)
            conn.onServiceConnected(intent.component!!, plugin.asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val trust = mockk<PluginTrust>()
        coEvery { trust.enforce(pluginPkg, pluginUid, setOf(pluginPkg)) } answers {
            verified = true
        }
        val mgr = PluginManager(app, ingress, trust)

        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)
        mgr.refreshBindings()

        assertEquals(
            listOf(Context.BIND_AUTO_CREATE, Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS),
            bindFlags,
        )
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(1, plugin.lifecycleCalls.count { it == "startPlugin" })
        mgr.unbindPackage(pluginPkg)
        awaitPluginLifecycle(pluginPkg)
        verify(exactly = 1) { app.unbindService(any<ServiceConnection>()) }

        verified = false
        coEvery { trust.enforce(pluginPkg, pluginUid, setOf(pluginPkg)) } throws
            ParserException(ReasonCodes.PLUGIN_CERT_CHANGED, "replacement cert rejected")
        mgr.bindPackage(pluginPkg)
        awaitPluginLifecycle(pluginPkg)

        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(3, bindFlags.size)
        assertEquals(Context.BIND_AUTO_CREATE, bindFlags.last())
    }

    @Test
    fun bind_pendingHandshakeNeverAllowsActivityStarts() {
        val app = mockHostApp(listOf(resolveInfoFor(pluginPkg))) { _, _ -> }
        val mgr = PluginManager(app, mockk(relaxed = true), mockk(relaxed = true))

        mgr.refreshBindings()

        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        assertFalse(mgr.onConnectionReady(mgr.connectionForTest(pluginPkg)!!))
        assertEquals(listOf(Context.BIND_AUTO_CREATE), bindFlags)
        mgr.shutdownAll()
    }

    @Test
    fun bind_failedActivityStartsBindingUnloadsVerifiedPlugin() {
        val app = mockHostApp(listOf(resolveInfoFor(pluginPkg))) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        every {
            app.bindService(
                any<Intent>(),
                any<ServiceConnection>(),
                Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS,
            )
        } returns false
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val mgr = PluginManager(app, ingress, mockk(relaxed = true))

        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)

        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertFalse(pluginPkg in PluginDiagnostics.snapshot().connected)
        assertEquals("activity_starts_bind_failed",
            PluginDiagnostics.snapshot().lastAdmissionDetailByPackage[pluginPkg])
        verify(exactly = 1) { app.unbindService(any<ServiceConnection>()) }
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
        assertEquals(com.weartak.plugin.sdk.MainUiActionContract.PRESENTATION_VERSION,
            plugin.lastInit!!.hostExtras.getInt(
                com.weartak.plugin.sdk.MainUiActionContract.HOST_PRESENTATION_VERSION_KEY))
        org.junit.Assert.assertNotNull(
            com.weartak.plugin.sdk.host.v1.HostServicesContract.fromHostExtras(plugin.lastInit!!.hostExtras)
        )
        assertTrue(plugin.lastInit!!.hostExtras.getBoolean(
            com.weartak.plugin.sdk.routestatus.RouteStatusContract.EXTRA_CAPABILITY))
        val routeControl = com.weartak.plugin.sdk.routestatus.aidl.IPluginRouteStatusControlV1.Stub.asInterface(
            plugin.lastInit!!.hostExtras.getBinder(
                com.weartak.plugin.sdk.routestatus.RouteStatusContract.EXTRA_CONTROL))
        assertEquals(1, routeControl.contractVersion)
        assertTrue(mgr.settingsCoordinator.connectionStatuses.value.isEmpty())
        assertEquals(
            com.weartak.plugin.sdk.transport.TransportContract.VERSION,
            plugin.lastInit!!.hostExtras.getInt(
                com.weartak.plugin.sdk.transport.TransportContract.EXTRA_VERSION,
            ),
        )
        org.junit.Assert.assertNotNull(
            plugin.lastInit!!.hostExtras.getBinder(
                com.weartak.plugin.sdk.transport.TransportContract.EXTRA_CONTROL,
            ),
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
        assertTrue(plugin.lastInit!!.hostExtras.getBoolean(
            com.weartak.plugin.sdk.routestatus.RouteStatusContract.EXTRA_CAPABILITY))
    }

    @Test
    fun verifiedBindingRegistersUnavailableTransportAndFencesRestartedSession() {
        val provider1 = FakeUnavailableTransportPlugin(pluginPkg)
        val provider2 = FakeUnavailableTransportPlugin(pluginPkg)
        val providers = ArrayDeque(listOf(provider1, provider2))
        val app = mockHostApp(
            listOf(resolveInfoFor(pluginPkg)),
            resolvedUid = Process.myUid(),
        ) { intent, conn ->
            conn.onServiceConnected(intent.component!!, providers.removeFirst().asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), Process.myUid()) } returns setOf(pluginPkg)
        val mgr = PluginManager(app, ingress, mockk(relaxed = true))

        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)

        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(TransportResultCode.ACCEPTED, provider1.registrationResult?.code)
        assertNotNull(provider1.registrationResult?.sessionId)
        assertEquals(TransportResultCode.UNAVAILABLE, provider1.sendUnavailableMessage().code)
        provider1.offerHostMessage()
        assertEquals(0, provider1.callback.sentMessages.get())

        val oldSessionId = provider1.registrationResult!!.sessionId!!
        mgr.unbindPackage(pluginPkg)
        awaitPluginLifecycle(pluginPkg)
        assertTrue(provider1.callback.sessionClosed)
        assertEquals(
            TransportResultCode.INACTIVE_SESSION,
            provider1.sendUnavailableMessage(oldSessionId).code,
        )

        assertTrue(mgr.bindPackage(pluginPkg))
        awaitPluginLifecycle(pluginPkg)
        assertEquals(TransportResultCode.ACCEPTED, provider2.registrationResult?.code)
        assertNotEquals(oldSessionId, provider2.registrationResult?.sessionId)
        assertEquals(
            TransportResultCode.INACTIVE_SESSION,
            provider1.sendUnavailableMessage(oldSessionId).code,
        )
        assertEquals(TransportResultCode.UNAVAILABLE, provider2.sendUnavailableMessage().code)

        mgr.shutdownAll()
        awaitPluginLifecycle(pluginPkg)
    }

    @Test
    fun transportRegistrationFencesUnexpectedBinderPeerUid() {
        val provider = FakeUnavailableTransportPlugin(pluginPkg)
        val peerUid = Process.myUid() + 1
        val app = mockHostApp(
            listOf(resolveInfoFor(pluginPkg)),
            resolvedUid = peerUid,
        ) { intent, conn ->
            conn.onServiceConnected(intent.component!!, provider.asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), peerUid) } returns setOf(pluginPkg)
        val mgr = PluginManager(app, ingress, mockk(relaxed = true))

        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)

        assertEquals(TransportResultCode.UNAUTHORIZED, provider.registrationResult?.code)
        assertFalse(provider.hostCapabilitiesAvailable)
        mgr.shutdownAll()
        awaitPluginLifecycle(pluginPkg)
    }

    @Test
    fun transportRegistrationRejectsEmptyPayloadKindsEvenWhenUnavailable() {
        val provider = FakeUnavailableTransportPlugin(pluginPkg, payloadKinds = emptyList())
        val app = mockHostApp(
            listOf(resolveInfoFor(pluginPkg)),
            resolvedUid = Process.myUid(),
        ) { intent, conn ->
            conn.onServiceConnected(intent.component!!, provider.asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), Process.myUid()) } returns setOf(pluginPkg)
        val mgr = PluginManager(app, ingress, mockk(relaxed = true))

        mgr.refreshBindings()
        awaitPluginLifecycle(pluginPkg)

        assertEquals(TransportResultCode.UNSUPPORTED, provider.registrationResult?.code)
        mgr.shutdownAll()
        awaitPluginLifecycle(pluginPkg)
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

    private class FakeUnavailableTransportPlugin(
        packageName: String,
        private val payloadKinds: List<Int> = listOf(TransportPayloadKind.CHAT_UTF8),
    ) : FakeWearTakPlugin(packageName, apiVersion = 2) {
        private lateinit var hostControl: IPluginTransportHostV1
        private var hostImplementation: PluginTransportHostV1Impl? = null
        var hostCapabilitiesAvailable = false
            private set
        var registrationResult: TransportResultParcel? = null
            private set
        val callback = FakeTransportCallback()

        override fun initializePluginV2(
            init: com.weartak.plugin.sdk.model.PluginInitParcel?,
            control: com.weartak.plugin.sdk.aidl.IWearTakPluginControl?,
            controlV2: com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2?,
        ) {
            super.initializePluginV2(init, control, controlV2)
            val extras = requireNotNull(init).hostExtras
            hostControl = requireNotNull(
                IPluginTransportHostV1.Stub.asInterface(
                    requireNotNull(extras.getBinder(TransportContract.EXTRA_CONTROL)),
                ),
            )
            hostImplementation = hostControl as? PluginTransportHostV1Impl
            hostCapabilitiesAvailable = hostControl.getHostCapabilities().runtimeAvailable
            registrationResult = hostControl.registerTransport(unavailableCapabilities(), callback)
        }

        fun offerHostMessage() {
            requireNotNull(hostImplementation).offerCoT(
                "<event/>",
                senderUid = "watch-1",
                senderCallsign = "WATCH",
            )
        }

        fun sendUnavailableMessage(
            sessionId: String? = registrationResult?.sessionId,
        ): TransportResultParcel = hostControl.receiveMessage(
            sessionId,
            "somewear-satellite",
            TransportMessageParcel(
                messageId = "fake-provider-message",
                payloadKind = TransportPayloadKind.CHAT_UTF8,
                payload = "offline".toByteArray(),
                senderUid = "remote-user",
                recipientUid = "watch-1",
                senderCallsign = "REMOTE",
            ),
        )

        private fun unavailableCapabilities() = TransportCapabilitiesParcel(
            version = TransportContract.VERSION,
            outboundPayloadKinds = payloadKinds,
            inboundPayloadKinds = payloadKinds,
            channelIds = listOf("somewear-satellite"),
            maxPayloadBytes = 500,
            maxPendingMessages = 1,
            runtimeAvailable = false,
            deviceReceipts = false,
            recipientReceipts = false,
        )
    }

    private class FakeTransportCallback : IPluginTransportCallbackV1.Stub() {
        val sentMessages = AtomicInteger()
        @Volatile
        var sessionClosed = false
            private set

        override fun sendMessage(request: TransportSendRequestParcel?): TransportResultParcel {
            sentMessages.incrementAndGet()
            return TransportResultParcel(TransportResultCode.UNAVAILABLE, request?.sessionId)
        }

        override fun cancelMessage(sessionId: String?, messageId: String?) =
            TransportResultParcel(TransportResultCode.ACCEPTED, sessionId)

        override fun onSessionClosed(sessionId: String?, reason: Int) {
            sessionClosed = true
        }
    }
}
