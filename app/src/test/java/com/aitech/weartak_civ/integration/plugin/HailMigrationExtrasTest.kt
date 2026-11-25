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
class HailMigrationExtrasTest {
    @Test fun eligibilityRequiresExactHailPackageAndApprovedSigner() {
        assertEquals(true, HailMigrationExtras.eligible(HailMigrationExtras.PACKAGE, approvedSigner = true))
        assertEquals(false, HailMigrationExtras.eligible(HailMigrationExtras.PACKAGE, approvedSigner = false))
        assertEquals(false, HailMigrationExtras.eligible("com.example.plugin", approvedSigner = true))
    }

    @Test fun exportsOnlyApprovedConfiguration() {
        val extras = Bundle()
        HailMigrationExtras.putInto(extras, DataRepository.LegacyHailMigration("id", true, 1234))
        assertEquals(
            setOf(HailMigrationExtras.VERSION, HailMigrationExtras.ID, HailMigrationExtras.ENABLED,
                HailMigrationExtras.COMBAT_ID),
            extras.keySet(),
        )
        assertEquals(1, extras.getInt(HailMigrationExtras.VERSION))
        assertEquals("id", extras.getString(HailMigrationExtras.ID))
        assertEquals(true, extras.getBoolean(HailMigrationExtras.ENABLED))
        assertEquals("1234", extras.getString(HailMigrationExtras.COMBAT_ID))
    }

    @Test fun absentCombatIdIsNotExported() {
        val extras = Bundle()
        HailMigrationExtras.putInto(extras, DataRepository.LegacyHailMigration("id", false, null))
        assertFalse(extras.containsKey(HailMigrationExtras.COMBAT_ID))
    }

    @Test fun policyMustMatchBothCurrentRestrictionsAndSnapshot() {
        val restrictions = Bundle()
        val unmanaged = DataRepository.ManagedHailServicePolicy(true, null)
        val enabled = DataRepository.ManagedHailServicePolicy(true, true)
        val disabled = DataRepository.ManagedHailServicePolicy(true, false)
        assertEquals(true, HailMigrationExtras.policyMatches(restrictions, unmanaged, 0))
        assertFalse(HailMigrationExtras.policyMatches(restrictions, enabled, 1))
        assertFalse(HailMigrationExtras.policyMatches(restrictions, unmanaged, 1))
        assertFalse(HailMigrationExtras.policyMatches(
            restrictions, DataRepository.ManagedHailServicePolicy(false, null), -1))
        restrictions.putBoolean("hail_service_enabled", false)
        assertEquals(true, HailMigrationExtras.policyMatches(restrictions, disabled, 2))
        assertFalse(HailMigrationExtras.policyMatches(restrictions, enabled, 1))
        assertFalse(HailMigrationExtras.policyMatches(restrictions, unmanaged, 0))
        restrictions.putString("hail_service_enabled", "false")
        assertFalse(HailMigrationExtras.policyMatches(restrictions, disabled, 2))
    }
}
