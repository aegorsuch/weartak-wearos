package com.aitech.weartak_civ.service

internal object HailPliDetailSelector {
    private const val PLUGIN_CUTOVER_VERIFIED = false

    fun select(
        native: () -> String,
        plugin: () -> String,
        usePlugin: Boolean = PLUGIN_CUTOVER_VERIFIED,
    ): String {
        if (!usePlugin) return native()
        return plugin()
    }
}
