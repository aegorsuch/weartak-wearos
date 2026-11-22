package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.DeviceStatusContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
class HostControlVerifyCallerTest {

    @Test
    fun wrongBinderUid_returnsDeniedDeviceStatusAndEmptyCapabilities() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val ctrl = HostControlImpl(app, ingress, "com.plugin.x", expectedPluginUid = 55)
        ShadowBinder.setCallingUid(9999)
        val st = ctrl.deviceStatus
        assertFalse(st.online)
        assertEquals("denied", st.summary)
        assertFalse(st.telemetry.containsKey(DeviceStatusContract.KEY_TAK_UID))
        assertFalse(st.telemetry.containsKey(DeviceStatusContract.KEY_CALLSIGN))
        verify(exactly = 0) { app.repo }
        assertFalse(ctrl.isCotConnected)
        val caps = ctrl.hostCapabilities
        assertFalse(caps.cotDispatch)
        ShadowBinder.setCallingUid(55)
        // Avoid getDeviceStatus() success path — it touches TAKConnectService static init.
        val capsOk = ctrl.hostCapabilities
        assertTrue(capsOk.cotDispatch)
    }

    @Test
    fun closedBindingDoesNotExposeIdentityToPreviouslyAuthorizedUid() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ctrl = HostControlImpl(app, mockk(relaxed = true), "com.plugin.x",
            expectedPluginUid = 55, isActive = { false })
        ShadowBinder.setCallingUid(55)
        val status = ctrl.deviceStatus
        assertEquals("denied", status.summary)
        assertFalse(status.telemetry.containsKey(DeviceStatusContract.KEY_TAK_UID))
        assertFalse(status.telemetry.containsKey(DeviceStatusContract.KEY_CALLSIGN))
        verify(exactly = 0) { app.repo }
    }

    @Test
    fun dittoStatusIsDeniedWithoutLeaseForCorrectWrongAndRevokedCaller() {
        val app = mockk<WearTakApplication>(relaxed = true)
        var active = true
        val ctrl = HostControlImpl(app, mockk(relaxed = true), TrustedCommunityPlugin.PACKAGE,
            expectedPluginUid = 55, isActive = { active }, requireDittoLeaseForStatus = true)
        for ((caller, bindingActive) in listOf(55 to true, 9999 to true, 55 to false)) {
            active = bindingActive
            ShadowBinder.setCallingUid(caller)
            val status = ctrl.deviceStatus
            assertEquals("denied", status.summary)
            assertTrue(status.telemetry.isEmpty)
        }
        verify(exactly = 0) { app.repo }
    }

    @Test
    fun leasedDittoStatusContainsOnlyUidAndRechecksLeaseAfterRead() {
        val repo = mockk<DataRepository>()
        val app = mockk<WearTakApplication>()
        every { app.repo } returns repo
        var owned = true
        every { repo.androidDeviceUid } returns "WEAROS_test"
        val ctrl = HostControlImpl(app, mockk(relaxed = true), TrustedCommunityPlugin.PACKAGE,
            expectedPluginUid = 55, requireDittoLeaseForStatus = true,
            dittoLeaseConfirmed = { owned })
        ShadowBinder.setCallingUid(55)

        val status = ctrl.deviceStatus
        assertEquals(setOf(DeviceStatusContract.KEY_TAK_UID), status.telemetry.keySet())
        assertEquals("WEAROS_test", status.telemetry.getString(DeviceStatusContract.KEY_TAK_UID))

        every { repo.androidDeviceUid } answers { owned = false; "WEAROS_test" }
        val revoked = ctrl.deviceStatus
        assertEquals("denied", revoked.summary)
        assertTrue(revoked.telemetry.isEmpty)
    }

    @Test
    fun leasedDittoStatusOmitsInvalidUid() {
        val repo = mockk<DataRepository>()
        val app = mockk<WearTakApplication>()
        every { app.repo } returns repo
        val ctrl = HostControlImpl(app, mockk(relaxed = true), TrustedCommunityPlugin.PACKAGE,
            expectedPluginUid = 55, requireDittoLeaseForStatus = true,
            dittoLeaseConfirmed = { true })
        ShadowBinder.setCallingUid(55)
        for (uid in listOf("", "WEAROS_", "WEAROS_null")) {
            every { repo.androidDeviceUid } returns uid
            assertTrue(ctrl.deviceStatus.telemetry.isEmpty)
        }
    }
}
