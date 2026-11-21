package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.ditto.v1.DittoContractV1
import com.weartak.plugin.sdk.ditto.v1.DittoLeaseResultV1
import com.weartak.plugin.sdk.ditto.v1.IDittoHostControlV1
import com.weartak.plugin.sdk.dittoruntime.aidl.IDittoRuntimeV1
import com.weartak.plugin.sdk.dittoruntime.DittoRuntimeContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
class DittoRuntimeHandoffTest {
    private val context = mockk<Context>(relaxed = true)
    private val component = ComponentName("test.plugin", "test.plugin.Runtime")

    @Test
    fun missingFirstStartProofAndUntrustedEndpointNeverBindOrFence() {
        var fenced = false
        val noProof = DittoRuntimeHandoff(context, component, "test.action", 42,
            { true }, { true }, { false }, { fenced = true; true })
        assertFalse(noProof.acquire { _, _ -> true })
        val untrusted = DittoRuntimeHandoff(context, component, "test.action", 42,
            { true }, { false }, { true }, { fenced = true; true })
        assertFalse(untrusted.acquire { _, _ -> true })
        assertFalse(fenced)
        verify(exactly = 0) { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
    }

    @Test
    fun missingCallbackOrRejectedProfileNeverGrantsOwnershipAndReleasesLease() {
        val runtime = mockk<IDittoRuntimeV1>()
        val binder = Binder().apply {
            attachInterface(runtime, "com.weartak.plugin.sdk.dittoruntime.aidl.IDittoRuntimeV1")
        }
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } answers {
            secondArg<ServiceConnection>().onServiceConnected(component, binder)
            true
        }
        every { runtime.release(any(), any()) } answers {
            DittoLeaseResultV1().apply { status = DittoContractV1.LEASE_STOPPED }
        }
        val result = { DittoLeaseResultV1().apply {
            status = DittoContractV1.LEASE_ACQUIRED
            generation = 7
        } }
        every { runtime.acquire(any<IBinder>(), any()) } answers { result() }
        ShadowBinder.setCallingUid(42)
        val noCallback = DittoRuntimeHandoff(context, component, "test.action", 42,
            { true }, { true }, { true }, { true })
        assertFalse(noCallback.acquire { _, _ -> true })
        assertFalse(noCallback.ownershipConfirmed())
        verify(exactly = 1) { runtime.release(any(), 7) }

        every { runtime.acquire(any<IBinder>(), any()) } answers {
            assertTrue(secondArg<IDittoHostControlV1>().quiesceBundledEngine())
            result()
        }
        val badProfile = DittoRuntimeHandoff(context, component, "test.action", 42,
            { true }, { true }, { true }, { true })
        assertFalse(badProfile.acquire { _, _ -> false })
        assertFalse(badProfile.ownershipConfirmed())
        verify(exactly = 2) { runtime.release(any(), 7) }
    }

    @Test
    fun lostTrustPermanentlyRevokesConfirmedLease() {
        val runtime = mockk<IDittoRuntimeV1>()
        val binder = Binder().apply {
            attachInterface(runtime, "com.weartak.plugin.sdk.dittoruntime.aidl.IDittoRuntimeV1")
        }
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } answers {
            secondArg<ServiceConnection>().onServiceConnected(component, binder)
            true
        }
        every { runtime.acquire(any<IBinder>(), any()) } answers {
            assertTrue(secondArg<IDittoHostControlV1>().quiesceBundledEngine())
            DittoLeaseResultV1().apply { status = DittoContractV1.LEASE_ACQUIRED; generation = 7 }
        }
        every { runtime.release(any(), any()) } returns DittoLeaseResultV1().apply {
            status = DittoContractV1.LEASE_STOPPED
        }
        var trusted = true
        ShadowBinder.setCallingUid(42)
        val handoff = DittoRuntimeHandoff(context, component, "test.action", 42,
            { true }, { trusted }, { true }, { true })
        assertTrue(handoff.acquire { _, _ -> true })
        assertTrue(handoff.ownershipConfirmed())
        trusted = false
        assertFalse(handoff.ownershipConfirmed())
        trusted = true
        assertFalse(handoff.ownershipConfirmed())
        handoff.release()
    }

    @Test
    fun runtimeDisconnectAndReconnectCannotRestoreOldLease() {
        val runtime = mockk<IDittoRuntimeV1>()
        val binder = Binder().apply {
            attachInterface(runtime, "com.weartak.plugin.sdk.dittoruntime.aidl.IDittoRuntimeV1")
        }
        lateinit var connection: ServiceConnection
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } answers {
            connection = secondArg()
            connection.onServiceConnected(component, binder)
            true
        }
        every { runtime.acquire(any<IBinder>(), any()) } answers {
            assertTrue(secondArg<IDittoHostControlV1>().quiesceBundledEngine())
            DittoLeaseResultV1().apply { status = DittoContractV1.LEASE_ACQUIRED; generation = 7 }
        }
        every { runtime.release(any(), any()) } returns DittoLeaseResultV1().apply {
            status = DittoContractV1.LEASE_STOPPED
        }
        ShadowBinder.setCallingUid(42)
        val handoff = DittoRuntimeHandoff(context, component, "test.action", 42,
            { true }, { true }, { true }, { true })
        assertTrue(handoff.acquire { _, _ -> true })
        connection.onServiceDisconnected(component)
        assertFalse(handoff.ownershipConfirmed())
        connection.onServiceConnected(component, binder)
        assertFalse(handoff.ownershipConfirmed())
        handoff.release()
    }

    @Test
    fun runtimeEndpointRequiresExactOwnerServiceAndGrantedSignaturePermission() {
        val app = mockk<WearTakApplication>()
        val pm = mockk<PackageManager>()
        val packageName = TrustedCommunityPlugin.PACKAGE
        val service = ServiceInfo().apply {
            this.packageName = packageName
            name = DittoRuntimeContract.RUNTIME_SERVICE
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply { enabled = true; uid = 42 }
            permission = DittoRuntimeContract.BIND_PERMISSION
        }
        val permission = PermissionInfo().apply {
            this.packageName = packageName
            protectionLevel = PermissionInfo.PROTECTION_SIGNATURE
        }
        every { app.packageManager } returns pm
        every { app.packageName } returns "test.host"
        every { pm.getServiceInfo(any<ComponentName>(), any<PackageManager.ComponentInfoFlags>()) } returns service
        @Suppress("DEPRECATION")
        every { pm.getPermissionInfo(DittoRuntimeContract.BIND_PERMISSION, 0) } returns permission
        every { pm.checkPermission(DittoRuntimeContract.BIND_PERMISSION, "test.host") } returns PackageManager.PERMISSION_GRANTED
        mockkObject(TrustedCommunityPlugin)
        every { TrustedCommunityPlugin.verify(app, 42) } returns true
        try {
            val connection = PluginConnection(app, mockk(relaxed = true),
                ComponentName(packageName, "test.Lifecycle"), 42,
                mockk(relaxed = true), mockk<PluginTrust>(relaxed = true))
            assertTrue(connection.trustedDittoRuntimeEndpoint())
            permission.packageName = "untrusted.owner"
            assertFalse(connection.trustedDittoRuntimeEndpoint())
            permission.packageName = packageName
            service.exported = false
            assertFalse(connection.trustedDittoRuntimeEndpoint())
            service.exported = true
            every { pm.checkPermission(DittoRuntimeContract.BIND_PERMISSION, "test.host") } returns PackageManager.PERMISSION_DENIED
            assertFalse(connection.trustedDittoRuntimeEndpoint())
        } finally {
            unmockkObject(TrustedCommunityPlugin)
        }
    }
}
