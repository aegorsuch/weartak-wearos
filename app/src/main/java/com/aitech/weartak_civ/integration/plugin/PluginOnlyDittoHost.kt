package com.aitech.weartak_civ.integration.plugin

/** Inactive until bundled-engine shutdown and recovery across upgrades are proven. */
internal object PluginOnlyDittoHost {
    fun enabled(): Boolean = false
    fun ownershipProven(): Boolean = false
}
