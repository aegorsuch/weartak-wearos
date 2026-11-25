package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.data.DataRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HailPliPolicyTest {
    @Test fun policyMustMatchBothCurrentRestrictionsAndSnapshot() {
        val restrictions = Bundle()
        val unmanaged = DataRepository.ManagedHailServicePolicy(true, null)
        val enabled = DataRepository.ManagedHailServicePolicy(true, true)
        val disabled = DataRepository.ManagedHailServicePolicy(true, false)
        assertEquals(true, HailPliPolicy.policyMatches(restrictions, unmanaged, 0))
        assertFalse(HailPliPolicy.policyMatches(restrictions, enabled, 1))
        assertFalse(HailPliPolicy.policyMatches(restrictions, unmanaged, 1))
        assertFalse(HailPliPolicy.policyMatches(
            restrictions, DataRepository.ManagedHailServicePolicy(false, null), -1))
        restrictions.putBoolean("hail_service_enabled", false)
        assertEquals(true, HailPliPolicy.policyMatches(restrictions, disabled, 2))
        assertFalse(HailPliPolicy.policyMatches(restrictions, enabled, 1))
        assertFalse(HailPliPolicy.policyMatches(restrictions, unmanaged, 0))
        restrictions.putString("hail_service_enabled", "false")
        assertFalse(HailPliPolicy.policyMatches(restrictions, disabled, 2))
    }
}
