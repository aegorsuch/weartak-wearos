package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MapUserRolePreferencesTest {
    private lateinit var preferences: SharedPreferences

    @Before fun setup() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
            "map-role-test", Context.MODE_PRIVATE
        )
        preferences.edit().clear().commit()
    }

    @Test fun defaultsIncludeBothRoleSections() {
        val filters = MapUserRolePreferences(preferences)
        assertEquals(MapUserRolePreferences.STANDARD_ROLES, filters.standardRoles())
        assertEquals(MapUserRolePreferences.LEO_ROLES, filters.leoRoles())
    }

    @Test fun editingLeoRolesDoesNotHideStandardRolesAfterReopening() {
        MapUserRolePreferences(preferences).saveLeoRoles(setOf("TOC"))
        val reopened = MapUserRolePreferences(preferences)
        assertTrue("K9 must remain visible", "K9" in reopened.standardRoles())
        assertEquals(setOf("TOC"), reopened.leoRoles())
        assertFalse("Command Post" in reopened.leoRoles())
    }

    @Test fun editingStandardRolesDoesNotHideLeoRolesAfterReopening() {
        MapUserRolePreferences(preferences).saveStandardRoles(setOf("Medic"))
        val reopened = MapUserRolePreferences(preferences)
        assertEquals(setOf("Medic"), reopened.standardRoles())
        assertFalse("K9" in reopened.standardRoles())
        assertEquals(MapUserRolePreferences.LEO_ROLES, reopened.leoRoles())
    }

    @Test fun independentSelectionsAndExplicitEmptySelectionsSurviveAlternatingEdits() {
        val filters = MapUserRolePreferences(preferences)
        filters.saveStandardRoles(setOf("K9"))
        filters.saveLeoRoles(setOf("TOC"))
        assertEquals(setOf("K9"), MapUserRolePreferences(preferences).standardRoles())
        filters.saveStandardRoles(emptySet())
        assertEquals(setOf("TOC"), MapUserRolePreferences(preferences).leoRoles())
        filters.saveLeoRoles(emptySet())
        val reopened = MapUserRolePreferences(preferences)
        assertTrue(reopened.standardRoles().isEmpty())
        assertTrue(reopened.leoRoles().isEmpty())
    }

    @Test fun legacyLeoSelectionDoesNotAccidentallyHideEveryStandardRole() {
        preferences.edit().putStringSet("visible_user_roles", setOf("TOC")).commit()
        val filters = MapUserRolePreferences(preferences)
        assertEquals(MapUserRolePreferences.STANDARD_ROLES, filters.standardRoles())
        assertEquals(setOf("TOC"), filters.leoRoles())
    }

    @Test fun legacyStandardSelectionDoesNotAccidentallyHideEveryLeoRole() {
        preferences.edit().putStringSet("visible_user_roles", setOf("Medic")).commit()
        val filters = MapUserRolePreferences(preferences)
        assertEquals(setOf("Medic"), filters.standardRoles())
        assertEquals(MapUserRolePreferences.LEO_ROLES, filters.leoRoles())
    }

    @Test fun ambiguousLegacyEmptyAndUnknownSelectionsArePreservedWithoutWidening() {
        for (selection in listOf(emptySet(), setOf("Custom Role"))) {
            preferences.edit().putStringSet("visible_user_roles", selection).commit()
            val filters = MapUserRolePreferences(preferences)
            assertEquals(selection, filters.standardRoles())
            assertEquals(selection, filters.leoRoles())
        }
    }

    @Test fun mixedLegacySelectionsStayInTheirOwnSectionSoTogglesCannotBeBypassed() {
        preferences.edit().putStringSet("visible_user_roles", setOf("K9", "TOC")).commit()
        val filters = MapUserRolePreferences(preferences)
        assertEquals(setOf("K9"), filters.standardRoles())
        assertEquals(setOf("TOC"), filters.leoRoles())
        filters.saveStandardRoles(emptySet())
        assertTrue(MapUserRolePreferences(preferences).standardRoles().isEmpty())
        assertFalse("K9" in MapUserRolePreferences(preferences).leoRoles())
    }

    @Test fun explicitNewSelectionsOverrideLegacyValuesIncludingEmpty() {
        preferences.edit().putStringSet("visible_user_roles", setOf("TOC")).commit()
        val filters = MapUserRolePreferences(preferences)
        filters.saveStandardRoles(emptySet())
        filters.saveLeoRoles(setOf("Command Post"))
        preferences.edit().putStringSet("visible_user_roles", setOf("Medic")).commit()
        val reopened = MapUserRolePreferences(preferences)
        assertTrue(reopened.standardRoles().isEmpty())
        assertEquals(setOf("Command Post"), reopened.leoRoles())
    }
}
