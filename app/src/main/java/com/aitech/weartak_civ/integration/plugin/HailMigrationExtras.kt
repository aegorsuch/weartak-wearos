package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.aitech.weartak_civ.data.DataRepository

internal object HailMigrationExtras {
    const val PACKAGE = PluginTrust.HAIL_PLUGIN_PACKAGE
    const val VERSION = "com.weartak.plugin.hail.migration.version"
    const val ID = "com.weartak.plugin.hail.migration.id"
    const val ENABLED = "com.weartak.plugin.hail.migration.enabled"
    const val COMBAT_ID = "com.weartak.plugin.hail.migration.combatId"
    const val POLICY_SNAPSHOT = "com.aitech.weartak.internal.hail.policy"
    private const val MANAGED_ENABLED = "hail_service_enabled"

    fun eligible(pluginPackage: String, approvedSigner: Boolean): Boolean =
        pluginPackage == PACKAGE && approvedSigner

    fun policyCode(policy: DataRepository.ManagedHailServicePolicy): Int = when {
        !policy.loaded -> -1
        policy.enabled == null -> 0
        policy.enabled -> 1
        else -> 2
    }

    fun policyMatches(restrictions: Bundle, policy: DataRepository.ManagedHailServicePolicy, expected: Int): Boolean {
        if (!policy.loaded || policyCode(policy) != expected) return false
        if (!restrictions.containsKey(MANAGED_ENABLED)) return policy.enabled == null
        val current = restrictions.get(MANAGED_ENABLED)
        return current is Boolean && current == policy.enabled
    }

    fun putInto(extras: Bundle, migration: DataRepository.LegacyHailMigration) {
        extras.putInt(VERSION, 1)
        extras.putString(ID, migration.id)
        extras.putBoolean(ENABLED, migration.enabled)
        migration.combatId?.let { extras.putString(COMBAT_ID, it.toString()) }
    }
}
