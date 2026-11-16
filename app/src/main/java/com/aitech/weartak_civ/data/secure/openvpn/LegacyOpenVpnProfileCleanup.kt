package com.aitech.weartak_civ.data.secure.openvpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

private val Context.legacyOpenVpnProfiles: DataStore<Preferences> by
    preferencesDataStore("openvpn_profiles")

/** Removes unused host-owned profiles only after explicit confirmation in the plugin UI. */
internal class LegacyOpenVpnProfileCleanup(context: Context) {
    private val store = context.applicationContext.legacyOpenVpnProfiles

    suspend fun clear() {
        store.edit { it.remove(LEGACY_PROFILES_KEY) }
    }

    private companion object {
        val LEGACY_PROFILES_KEY = stringPreferencesKey("encrypted_profiles")
    }
}
