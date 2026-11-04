package com.aitech.weartak_civ.data

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionBluetoothAddressPermissionTest {
    private fun readAddress(context: Context): String? {
        val repository = CompanionAppRepository(context)
        val method = CompanionAppRepository::class.java.getDeclaredMethod(
            "getLocalBluetoothMacOrNull", Context::class.java)
        method.isAccessible = true
        return method.invoke(repository, context) as String?
    }

    @Test fun eitherMissingPermissionPreventsAnyAdapterAccess() {
        for (missing in listOf(Manifest.permission.BLUETOOTH_CONNECT, "android.permission.LOCAL_MAC_ADDRESS")) {
            val context = mockk<Context>(relaxed = true)
            every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
            every { context.checkSelfPermission(missing) } returns PackageManager.PERMISSION_DENIED
            assertNull(readAddress(context))
            verify(exactly = 0) { context.getSystemService(Context.BLUETOOTH_SERVICE) }
        }
    }

    @Test fun grantedPermissionsPreserveAddressAndRevocationReturnsUnavailable() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        val adapter = mockk<BluetoothAdapter>()
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { manager.adapter } returns adapter
        every { adapter.address } returns "02:00:00:00:00:00"
        assertEquals("02:00:00:00:00:00", readAddress(context))
        every { adapter.address } throws SecurityException("synthetic revocation")
        assertNull(readAddress(context))
    }
}
