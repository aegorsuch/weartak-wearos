package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.ComponentName
import android.os.Process
import android.util.Base64
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.TAKConnectService
import com.weartak.plugin.sdk.community.v1.CommunityIdentityContract as Contract
import com.weartak.plugin.sdk.community.v1.ICommunityIdentityControlV1
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportHostV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import com.weartak.plugin.sdk.point.v1.IPointEndpointV1
import com.weartak.plugin.sdk.point.v1.IPointHostV1
import com.weartak.plugin.sdk.point.v1.PointContractV1
import com.weartak.plugin.sdk.point.v1.PointOperationV1
import com.weartak.plugin.sdk.point.v1.PointResultV1
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HostCommunityIdentityControlTest {
    private fun identity(expiry: Long = System.currentTimeMillis() + 60_000): String {
        val json = JSONObject().put("v", 2).put("it", "SK").put("a", "synthetic-app")
            .put("s", "synthetic-key").put("t", "synthetic-token").put("e", expiry)
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(output.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    @Test fun requiresCallerBindingReadinessTrustAndOwnership() {
        var caller = 42
        var binding = true
        var trusted = true
        var owner = false
        var reads = 0
        val expected = identity()
        val control = HostCommunityIdentityControl(42, { binding }, { trusted }, { owner },
            { reads++; expected }, { caller })
        assertEquals(Contract.INACTIVE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
        control.ready()
        assertEquals(Contract.UNAVAILABLE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
        assertEquals(0, reads)
        caller = 43
        assertEquals(Contract.UNAUTHORIZED, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
        caller = 42
        owner = true
        val result = control.readCommunityIdentity()
        assertEquals(Contract.OK, result.getString(Contract.KEY_STATUS))
        assertEquals(expected, result.getString(Contract.KEY_IDENTITY))
        binding = false
        assertEquals(Contract.INACTIVE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
        binding = true
        trusted = false
        assertEquals(Contract.UNAUTHORIZED, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
        trusted = true
        assertEquals(Contract.INACTIVE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
    }

    @Test fun rejectsInvalidExpiredAndOversizedIdentityWithoutReturningIt() {
        for (invalid in listOf("", "synthetic-invalid", "x".repeat(Contract.MAX_IDENTITY_LENGTH + 1),
            identity(System.currentTimeMillis() - 1))) {
            val control = HostCommunityIdentityControl(42, { true }, { true }, { true }, { invalid }, { 42 })
            control.ready()
            val result = control.readCommunityIdentity()
            assertEquals(Contract.UNAVAILABLE, result.getString(Contract.KEY_STATUS))
            assertFalse(result.containsKey(Contract.KEY_IDENTITY))
        }
    }

    @Test fun bindingLossDuringReadDiscardsIdentity() {
        var binding = true
        val control = HostCommunityIdentityControl(42, { binding }, { true }, { true },
            { binding = false; identity() }, { 42 })
        control.ready()
        val result = control.readCommunityIdentity()
        assertEquals(Contract.INACTIVE, result.getString(Contract.KEY_STATUS))
        assertFalse(result.containsKey(Contract.KEY_IDENTITY))
    }

    @Test fun closingRevokesEvenAfterReady() {
        val control = HostCommunityIdentityControl(42, { true }, { true }, { true }, { identity() }, { 42 })
        control.ready()
        control.close()
        control.ready()
        assertEquals(Contract.INACTIVE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
    }

    @Test fun requiresExactPackageUidAndSingleApprovedSigner() {
        val digest = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val packages = setOf(TrustedCommunityPlugin.PACKAGE)
        assertTrue(TrustedCommunityPlugin.matches(42, packages, 42, listOf(digest)))
        assertFalse(TrustedCommunityPlugin.matches(42, packages + "another.package", 42, listOf(digest)))
        assertFalse(TrustedCommunityPlugin.matches(42, packages, 43, listOf(digest)))
        assertFalse(TrustedCommunityPlugin.matches(42, packages, 42, listOf(ByteArray(32))))
        assertFalse(TrustedCommunityPlugin.matches(42, packages, 42, listOf(digest, digest)))
    }

    @Test fun quiescentReplacementRequiresVerifiedGateBuild() {
        val digest = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val packages = setOf(TrustedCommunityPlugin.PACKAGE)
        fun matches(code: Long, name: String?, signers: List<ByteArray> = listOf(digest)) =
            TrustedCommunityPlugin.matchesQuiescentReplacement(42, packages, 42, signers, code, name)
        assertTrue(matches(5840059, "5.8.4-p59+9471a6d8"))
        assertFalse(matches(5840035, "5.8.4-p35+d981f7a5"))
        assertFalse(matches(5840051, "5.8.4-p51+fb070252"))
        assertFalse(matches(5840056, "5.8.4-p56+e966b948"))
        assertFalse(matches(5840058, "5.8.4-p58+9471a6d8"))
        assertFalse(matches(5840060, "5.8.4-p60+9471a6d8"))
        assertFalse(matches(5840059, "5.8.4-p59+different"))
        assertFalse(matches(5840059, "5.8.4-p59+9471a6d8", listOf(ByteArray(32))))
        assertFalse(matches(5840059, null))
        assertFalse(matches(5840059, "5.8.4-p59+9471a6d8", listOf(digest, digest)))
        assertFalse(TrustedCommunityPlugin.matchesQuiescentReplacement(
            42, packages + "other", 42, listOf(digest), 5840059, "5.8.4-p59+9471a6d8"))
        assertFalse(TrustedCommunityPlugin.matchesQuiescentReplacement(
            42, packages, 43, listOf(digest), 5840059, "5.8.4-p59+9471a6d8"))
    }

    @Test fun boundDittoPluginDoesNotReceiveLegacyIdentityControl() {
        val packageName = TrustedCommunityPlugin.PACKAGE
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(app, Process.myUid()) } returns setOf(packageName)
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.onConnectionReady(any()) } returns true
        val component = ComponentName(packageName, ".PluginService")
        val connection = PluginConnection(app, ingress, component, Process.myUid(), manager,
            mockk<PluginTrust>(relaxed = true))
        every { manager.connectionFor(packageName) } returns connection
        val plugin = FakeWearTakPlugin(packageName)
        mockkObject(TrustedCommunityPlugin)
        every { TrustedCommunityPlugin.verify(app, Process.myUid()) } returns true
        try {
            connection.onServiceConnected(component, plugin.asBinder())
            awaitPluginLifecycle(packageName)
            val extras = requireNotNull(plugin.lastInit).hostExtras
            assertFalse(extras.containsKey(Contract.EXTRA_CONTROL))
            assertFalse(extras.containsKey(PluginConnection.DITTO_PLUGIN_ONLY_KEY))
            assertFalse(extras.containsKey(PointContractV1.EXTRA_CONTROL))
            verify(exactly = 0) { app.repo.communityDittoPdl() }
            connection.binderDeathRecipientForTest.binderDied()
        } finally {
            connection.tearDownBinder("test")
            awaitPluginLifecycle(packageName)
            unmockkObject(TrustedCommunityPlugin)
        }
    }

    @Test fun pluginOnlyStartupRejectsOlderRuntimeBeforeInitialization() {
        val packageName = TrustedCommunityPlugin.PACKAGE
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(app, Process.myUid()) } returns setOf(packageName)
        val manager = mockk<PluginManager>(relaxed = true)
        val component = ComponentName(packageName, ".PluginService")
        val connection = PluginConnection(app, ingress, component, Process.myUid(), manager,
            mockk<PluginTrust>(relaxed = true), pluginOnlyEnabled = { true })
        val oldPlugin = FakeWearTakPlugin(packageName, apiVersion = 2)
        try {
            connection.onServiceConnected(component, oldPlugin.asBinder())
            awaitPluginLifecycle(packageName)
            assertEquals(0, oldPlugin.initializePluginV2Calls)
            assertFalse(oldPlugin.lifecycleCalls.contains("startPlugin"))
            verify { manager.onConnectionFailed(connection, "ditto_runtime_version_unverified") }
        } finally {
            connection.tearDownBinder("test")
            awaitPluginLifecycle(packageName)
        }
    }

    @Test fun pluginOnlyStartupProvidesDeviceStatusWithoutHostIdentity() {
        val packageName = TrustedCommunityPlugin.PACKAGE
        val app = mockk<WearTakApplication>(relaxed = true)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        every { app.getSystemService(android.os.PowerManager::class.java) } returns
            context.getSystemService(android.os.PowerManager::class.java)
        every { app.contentResolver } returns context.contentResolver
        val repo = app.repo
        val companion = MutableStateFlow(true)
        every { repo.androidDeviceUid } returns "synthetic-watch"
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        every { repo.latestBatteryPercentage } returns MutableStateFlow(80)
        every { repo.getBatteryWarnPercentage } returns flowOf(20)
        every { repo.latestDeviceWornObservation } returns MutableStateFlow(true)
        every { repo.chargingState } returns MutableStateFlow(false)
        every { repo.isAlerting } returns MutableStateFlow(false)
        every { repo.stayConnectedOffWrist } returns flowOf(false)
        every { repo.watchTransportSuppressed } returns companion
        every { repo.getEnableLocationService } returns MutableStateFlow(false)
        every { repo.selfStatusInputs() } returns flowOf(mockk(relaxed = true))
        every { repo.latestHeartRate } returns MutableStateFlow(null)
        every { repo.latestLocation } returns MutableStateFlow(null)
        every { repo.getCallsign } returns flowOf("ALPHA")
        coEvery { repo.snapshotLocation(null) } returns null
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(app, Process.myUid()) } returns setOf(packageName)
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.onConnectionReady(any()) } returns true
        every { manager.settingsCoordinator.pluginHasSettingEnabled(
            packageName, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) } returns true
        val component = ComponentName(packageName, ".PluginService")
        var pluginOnlyEnabled = true
        val connection = PluginConnection(app, ingress, component, Process.myUid(), manager,
            mockk<PluginTrust>(relaxed = true), pluginOnlyEnabled = { pluginOnlyEnabled })
        every { manager.connectionFor(packageName) } returns connection
        val callback = object : IPluginTransportCallbackV1.Stub() {
            override fun sendMessage(request: TransportSendRequestParcel?) =
                TransportResultParcel(TransportResultCode.ACCEPTED, request?.sessionId)
            override fun cancelMessage(sessionId: String?, messageId: String?) =
                TransportResultParcel(TransportResultCode.ACCEPTED, sessionId)
            override fun onSessionClosed(sessionId: String?, reason: Int) = Unit
        }
        val pointPlugin = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?,
                payload: android.os.ParcelFileDescriptor?) = PointResultV1(PointContractV1.RETRYABLE)
        }
        val plugin = object : FakeWearTakPlugin(packageName, apiVersion = 2) {
            override fun getPluginInfo() = com.weartak.plugin.sdk.model.PluginInfoParcel(
                pluginId = packageName, displayName = "Fake",
                versionName = TrustedCommunityPlugin.GATE_VERSION_NAME, packageName = packageName,
            )
            private lateinit var control: IWearTakPluginControl
            private lateinit var transport: IPluginTransportHostV1
            val transitions = java.util.concurrent.LinkedBlockingQueue<String>()
            @Volatile
            var uidAtStart: String? = null
            @Volatile
            var sessionId: String? = null
            @Volatile
            var pointRegistration: Int? = null
            override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?,
                controlV2: IWearTakPluginHostControlV2?) {
                super.initializePluginV2(init, control, controlV2)
                this.control = requireNotNull(control)
            }
            override fun startPlugin() {
                uidAtStart = control.deviceStatus.telemetry.getString(DeviceStatusContract.KEY_TAK_UID)
                val extras = requireNotNull(lastInit).hostExtras
                transport = IPluginTransportHostV1.Stub.asInterface(extras.getBinder(TransportContract.EXTRA_CONTROL))
                val capabilities = TransportCapabilitiesParcel(
                    channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
                    outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
                    inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
                    runtimeAvailable = true,
                )
                val registration = transport.registerTransport(capabilities, callback)
                check(registration.code == TransportResultCode.ACCEPTED)
                sessionId = requireNotNull(registration.sessionId)
                val points = IPointHostV1.Stub.asInterface(extras.getBinder(PointContractV1.EXTRA_CONTROL))
                pointRegistration = points.register(sessionId, pointPlugin)
                super.startPlugin()
                transitions.offer("start")
            }
            override fun stopPlugin() {
                sessionId?.let { check(transport.unregisterTransport(it).code == TransportResultCode.ACCEPTED) }
                sessionId = null
                super.stopPlugin()
                transitions.offer("stop")
            }
        }
        mockkObject(TrustedCommunityPlugin)
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.isAnyTakConnected } returns false
        every { TrustedCommunityPlugin.verify(app, Process.myUid()) } returns true
        every { TrustedCommunityPlugin.supportsQuiescentReplacement(app, Process.myUid()) } returns true
        try {
            fun nextTransition(): String? {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (plugin.transitions.isEmpty() && System.nanoTime() < deadline) {
                    org.robolectric.shadows.ShadowLooper.shadowMainLooper()
                        .idleFor(java.time.Duration.ofMillis(100))
                    Thread.sleep(5)
                }
                return plugin.transitions.poll()
            }
            connection.onServiceConnected(component, plugin.asBinder())
            awaitPluginLifecycle(packageName)
            assertNull(plugin.transitions.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            companion.value = false
            assertEquals("start", nextTransition())
            val extras = requireNotNull(plugin.lastInit).hostExtras
            assertEquals(1, extras.getInt(PluginConnection.DITTO_PLUGIN_ONLY_KEY))
            assertEquals(TransportContract.VERSION, extras.getInt(TransportContract.EXTRA_VERSION))
            assertEquals(PointContractV1.VERSION, extras.getInt(PointContractV1.EXTRA_VERSION))
            val pointHost = IPointHostV1.Stub.asInterface(extras.getBinder(PointContractV1.EXTRA_CONTROL))
            assertEquals(PointContractV1.REGISTERED, plugin.pointRegistration)
            assertNotNull(plugin.sessionId)
            assertEquals(plugin.sessionId, connection.quiescentReplacementSession())
            assertEquals(PointContractV1.REGISTER_INACTIVE, pointHost.register("stale", pointPlugin))
            assertFalse(extras.containsKey(Contract.EXTRA_CONTROL))
            assertEquals("synthetic-watch", plugin.uidAtStart)
            verify(exactly = 0) { repo.communityDittoPdl() }
            verify(exactly = 0) { repo.getEffectiveDittoPdl }
            val oldSession = requireNotNull(plugin.sessionId)
            companion.value = true
            assertEquals("stop", nextTransition())
            companion.value = false
            assertEquals("start", nextTransition())
            assertNotEquals(oldSession, plugin.sessionId)
            assertEquals(plugin.sessionId, connection.quiescentReplacementSession())
            every { TrustedCommunityPlugin.supportsQuiescentReplacement(app, Process.myUid()) } returns false
            assertNull(connection.quiescentReplacementSession())
            pluginOnlyEnabled = false
            assertEquals(PointContractV1.REGISTER_UNAUTHORIZED, pointHost.register("stale", pointPlugin))
        } finally {
            connection.tearDownBinder("test")
            awaitPluginLifecycle(packageName)
            unmockkObject(TrustedCommunityPlugin)
            unmockkObject(TAKConnectService.Companion)
        }
    }
}
