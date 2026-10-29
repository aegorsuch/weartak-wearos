package com.aitech.weartak_civ.integration.plugin

import android.content.Context

/** Durable host-side lifecycle and visibility state for installed plugins. */
class PluginStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(packageName: String): Boolean = preferences.getBoolean(
        key(packageName, ENABLED_SUFFIX),
        true,
    )

    fun setEnabled(packageName: String, enabled: Boolean) {
        preferences.edit().putBoolean(key(packageName, ENABLED_SUFFIX), enabled).apply()
    }

    fun isHidden(packageName: String): Boolean = preferences.getBoolean(
        key(packageName, HIDDEN_SUFFIX),
        false,
    )

    fun setHidden(packageName: String, hidden: Boolean) {
        preferences.edit().putBoolean(key(packageName, HIDDEN_SUFFIX), hidden).apply()
    }

    fun remove(packageName: String) {
        preferences.edit()
            .remove(key(packageName, ENABLED_SUFFIX))
            .remove(key(packageName, HIDDEN_SUFFIX))
            .apply()
    }

    private fun key(packageName: String, suffix: String): String = "$KEY_PREFIX$packageName.$suffix"

    private companion object {
        const val PREFERENCES_NAME = "weartak_plugin_state"
        const val KEY_PREFIX = "plugin."
        const val ENABLED_SUFFIX = "enabled"
        const val HIDDEN_SUFFIX = "hidden"
    }
}
