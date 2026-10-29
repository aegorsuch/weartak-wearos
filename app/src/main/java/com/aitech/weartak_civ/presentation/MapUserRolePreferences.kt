package com.aitech.weartak_civ.presentation

import android.content.SharedPreferences

internal class MapUserRolePreferences(private val preferences: SharedPreferences) {
    fun standardRoles(): Set<String> = load(STANDARD_KEY, STANDARD_ROLES, LEO_ROLES)

    fun leoRoles(): Set<String> = load(LEO_KEY, LEO_ROLES, STANDARD_ROLES)

    fun saveStandardRoles(roles: Set<String>) {
        preferences.edit().putStringSet(STANDARD_KEY, roles).apply()
    }

    fun saveLeoRoles(roles: Set<String>) {
        preferences.edit().putStringSet(LEO_KEY, roles).apply()
    }

    private fun load(key: String, defaults: Set<String>, otherRoles: Set<String>): Set<String> {
        preferences.getStringSet(key, null)?.let { return it.toSet() }
        val legacy = preferences.getStringSet("visible_user_roles", null) ?: return defaults
        // The legacy key held only the last edited section. Recover that section's
        // selection, not an accidental exclusion of every role in the other section.
        if (legacy.isNotEmpty() && legacy.all { it in otherRoles }) return defaults
        return legacy - otherRoles
    }

    companion object {
        private const val STANDARD_KEY = "visible_user_roles_standard"
        private const val LEO_KEY = "visible_user_roles_leo"
        val STANDARD_ROLES = setOf(
            "Forward Observer", "HQ", "K9", "Medic", "RTO", "Sniper", "Team Lead", "Team Member"
        )
        val LEO_ROLES = setOf(
            "Armed Surveillance", "Assistant Team Leader", "Aviation", "Bomb Tech",
            "Command Post", "Critical Response", "Hazards", "Negotiator",
            "Surveillance", "Tactical Communicator", "TOC"
        )
    }
}
