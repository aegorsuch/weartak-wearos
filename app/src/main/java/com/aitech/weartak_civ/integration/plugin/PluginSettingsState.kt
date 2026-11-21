package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsValidationErrorParcel

/**
 * Per-plugin cached settings contract state (backend only; no UI).
 */
data class PluginSettingsState(
    val pluginId: String,
    val capabilities: PluginCapabilitiesParcel = PluginCapabilitiesParcel(),
    val schema: SettingsSchemaParcel = SettingsSchemaParcel(),
    val values: Bundle = Bundle(),
    val hostSettings: HostSettingsParcel? = null,
    val hostSettingsRevision: Long = 0L,
    val lastApplyResult: SettingsApplyResultParcel? = null,
    val lastValidationErrors: List<SettingsValidationErrorParcel> = emptyList(),
    val settingsSupported: Boolean = false,
    val hostManagedSettingsSupported: Boolean = false,
    val lastSyncedAtMillis: Long = 0L,
) {
    val hostedSettingsUi: Boolean get() = capabilities.hostedSettingsUi
    val externalSettingsActivity: Boolean get() = capabilities.externalSettingsActivity
}
