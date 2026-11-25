package com.aitech.weartak_civ.service

internal object HailPliDetailSelector {
    private const val PLUGIN_CUTOVER_VERIFIED = false

    fun select(native: () -> String, plugin: () -> String): String {
        if (!PLUGIN_CUTOVER_VERIFIED) return native()
        return plugin().ifEmpty(native)
    }
}
