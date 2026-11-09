package com.aitech.weartak_civ.presentation

import android.content.SharedPreferences

internal class OverlayManagerCategoryPreferences(private val preferences: SharedPreferences) {
    fun expanded(categoryId: String, default: Boolean): Boolean =
        preferences.getBoolean("category_expanded_$categoryId", default)

    fun saveExpanded(categoryId: String, expanded: Boolean) {
        preferences.edit().putBoolean("category_expanded_$categoryId", expanded).apply()
    }

    companion object {
        const val FILE_NAME = "map_layer_preferences"
    }
}
