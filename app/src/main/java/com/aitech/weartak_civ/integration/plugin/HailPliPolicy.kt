package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.aitech.weartak_civ.data.DataRepository

internal object HailPliPolicy {
    const val PACKAGE = PluginTrust.HAIL_PLUGIN_PACKAGE
    const val POLICY_SNAPSHOT = "com.aitech.weartak.internal.hail.policy"
    private const val MANAGED_ENABLED = "hail_service_enabled"

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
}
