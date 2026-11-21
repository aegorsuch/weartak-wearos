package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.ComponentName
import android.os.Process
import android.util.Base64
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.community.v1.CommunityIdentityContract as Contract
import com.weartak.plugin.sdk.community.v1.ICommunityIdentityControlV1
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.transport.TransportContract
import kotlinx.coroutines.flow.flowOf
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

    @Test fun boundDittoPluginReceivesControlButCannotReadWithoutOwnership() {
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
            assertEquals(Contract.VERSION, extras.getInt(Contract.EXTRA_VERSION))
            assertFalse(extras.containsKey(PluginConnection.DITTO_PLUGIN_ONLY_KEY))
            val control = ICommunityIdentityControlV1.Stub.asInterface(extras.getBinder(Contract.EXTRA_CONTROL))
            assertEquals(Contract.UNAVAILABLE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
            verify(exactly = 0) { app.repo.communityDittoPdl() }
            connection.binderDeathRecipientForTest.binderDied()
            assertEquals(Contract.INACTIVE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
        } finally {
            connection.tearDownBinder("test")
            awaitPluginLifecycle(packageName)
            unmockkObject(TrustedCommunityPlugin)
        }
    }

    @Test fun syntheticPluginOnlyStartupReadsSelectedIdentityBeforeStartReturns() {
        val packageName = TrustedCommunityPlugin.PACKAGE
        val app = mockk<WearTakApplication>(relaxed = true)
        val selected = identity()
        val repo = app.repo
        every { repo.getEffectiveDittoPdl } returns flowOf(selected)
        every { repo.androidDeviceUid } returns "synthetic-watch"
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(app, Process.myUid()) } returns setOf(packageName)
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.onConnectionReady(any()) } returns true
        val component = ComponentName(packageName, ".PluginService")
        var proof = true
        val connection = PluginConnection(app, ingress, component, Process.myUid(), manager,
            mockk<PluginTrust>(relaxed = true), pluginOnlyEnabled = { true },
            pluginOnlyOwnershipProven = { proof })
        every { manager.connectionFor(packageName) } returns connection
        val plugin = object : FakeWearTakPlugin(packageName, apiVersion = 2) {
            private lateinit var control: IWearTakPluginControl
            var identityAtStart: android.os.Bundle? = null
            var uidAtStart: String? = null
            override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?,
                controlV2: IWearTakPluginHostControlV2?) {
                super.initializePluginV2(init, control, controlV2)
                this.control = requireNotNull(control)
            }
            override fun startPlugin() {
                val extras = requireNotNull(lastInit).hostExtras
                val identityControl = ICommunityIdentityControlV1.Stub.asInterface(extras.getBinder(Contract.EXTRA_CONTROL))
                identityAtStart = identityControl.readCommunityIdentity()
                uidAtStart = control.deviceStatus.telemetry.getString(DeviceStatusContract.KEY_TAK_UID)
                super.startPlugin()
            }
        }
        mockkObject(TrustedCommunityPlugin)
        every { TrustedCommunityPlugin.verify(app, Process.myUid()) } returns true
        mockkConstructor(PluginOnlyPliProducer::class)
        every { anyConstructed<PluginOnlyPliProducer>().start() } returns Unit
        every { anyConstructed<PluginOnlyPliProducer>().close() } returns Unit
        try {
            connection.onServiceConnected(component, plugin.asBinder())
            awaitPluginLifecycle(packageName)
            val extras = requireNotNull(plugin.lastInit).hostExtras
            assertEquals(1, extras.getInt(PluginConnection.DITTO_PLUGIN_ONLY_KEY))
            assertEquals(TransportContract.VERSION, extras.getInt(TransportContract.EXTRA_VERSION))
            assertEquals(Contract.OK, plugin.identityAtStart?.getString(Contract.KEY_STATUS))
            assertEquals(selected, plugin.identityAtStart?.getString(Contract.KEY_IDENTITY))
            assertEquals("synthetic-watch", plugin.uidAtStart)
            verify(exactly = 0) { repo.communityDittoPdl() }
            proof = false
            val control = ICommunityIdentityControlV1.Stub.asInterface(extras.getBinder(Contract.EXTRA_CONTROL))
            assertEquals(Contract.UNAVAILABLE, control.readCommunityIdentity().getString(Contract.KEY_STATUS))
        } finally {
            connection.tearDownBinder("test")
            awaitPluginLifecycle(packageName)
            unmockkObject(TrustedCommunityPlugin)
            unmockkConstructor(PluginOnlyPliProducer::class)
        }
    }
}
