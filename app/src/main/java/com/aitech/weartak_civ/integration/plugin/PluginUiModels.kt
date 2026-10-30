package com.aitech.weartak_civ.integration.plugin

import android.graphics.drawable.Drawable

enum class PluginLoadingStatus {
    NOT_LOADED,
    LOADED,
    NOT_INSTALLED,
    ERROR,
}

enum class PluginCompatibilityStatus {
    COMPATIBLE,
    INCOMPATIBLE,
    UNKNOWN,
}

data class PluginUI(
    val icon: Drawable?,
    val title: String,
    val desc: String,
    val loadingStatus: PluginLoadingStatus,
    val errorMessage: String = "None",
    val pluginId: String,
    val versionName: String,
    val packageName: String,
    val compatibility: PluginCompatibilityStatus = PluginCompatibilityStatus.UNKNOWN,
    val isLoaded: Boolean = false,
    val menuSubtitle: String? = null,
) {
    val menuLabel: String?
        get() = when (pluginId) {
            "com.weartak.plugin.gotenna", "com.weartak.plugin.takcad" -> null
            else -> menuSubtitle?.takeIf { it.isNotBlank() } ?: loadingStatus.toString()
        }
}
