/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.openvpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.data.secure.crypto.AppKeyManager
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import com.aitech.weartak_civ.service.openvpn.OpenVpnImportedProfile
import com.aitech.weartak_civ.service.openvpn.OpenVpnProfileRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.openVpnProfilesStore: DataStore<Preferences> by preferencesDataStore("openvpn_profiles")

class OpenVpnProfileRepository(context: Context) {
    private val store = context.applicationContext.openVpnProfilesStore
    private val aead = AppKeyManager(context.applicationContext)
        .getAead("openvpn_profiles_aead_v1", "weartak.openvpn.profiles.master.v1")
    private val gson = Gson()

    val profiles: Flow<List<OpenVpnProfileRecord>> = store.data.map { preferences ->
        val encoded = preferences[PROFILES_KEY] ?: return@map emptyList()
        when (val result = EncryptedSerializer.decryptFromBase64(aead, encoded, AAD)) {
            is DecryptResult.Success -> decodeProfiles(result.plaintext)
            is DecryptResult.Failure -> throw IllegalStateException("Unable to decrypt OpenVPN profiles", result.error)
        }
    }

    suspend fun get(id: String): OpenVpnProfileRecord? = readProfiles().firstOrNull { it.id == id }

    suspend fun import(profile: OpenVpnImportedProfile): OpenVpnProfileRecord {
        val record = OpenVpnProfileRecord(
            id = UUID.randomUUID().toString(),
            name = profile.name,
            config = profile.config,
            requiresCredentials = profile.requiresCredentials,
        )
        store.edit { preferences ->
            val profiles = decodeExisting(preferences[PROFILES_KEY]).toMutableList()
            profiles += record
            preferences[PROFILES_KEY] = EncryptedSerializer.encryptToBase64(aead, gson.toJson(profiles), AAD)
        }
        return record
    }

    suspend fun saveCredentials(id: String, username: String, password: String) {
        store.edit { preferences ->
            val profiles = decodeExisting(preferences[PROFILES_KEY]).map { profile ->
                if (profile.id == id) profile.copy(username = username, password = password) else profile
            }
            require(profiles.any { it.id == id }) { "OpenVPN profile not found" }
            preferences[PROFILES_KEY] = EncryptedSerializer.encryptToBase64(aead, gson.toJson(profiles), AAD)
        }
    }

    suspend fun delete(id: String) {
        store.edit { preferences ->
            val remaining = decodeExisting(preferences[PROFILES_KEY]).filterNot { it.id == id }
            if (remaining.isEmpty()) preferences.remove(PROFILES_KEY)
            else preferences[PROFILES_KEY] = EncryptedSerializer.encryptToBase64(aead, gson.toJson(remaining), AAD)
        }
    }

    private suspend fun readProfiles(): List<OpenVpnProfileRecord> {
        val encoded = store.data.map { it[PROFILES_KEY] }.first() ?: return emptyList()
        return decryptProfiles(encoded)
    }

    private fun decodeExisting(encoded: String?): List<OpenVpnProfileRecord> =
        if (encoded == null) emptyList() else decryptProfiles(encoded)

    private fun decryptProfiles(encoded: String): List<OpenVpnProfileRecord> =
        when (val result = EncryptedSerializer.decryptFromBase64(aead, encoded, AAD)) {
            is DecryptResult.Success -> decodeProfiles(result.plaintext)
            is DecryptResult.Failure -> throw IllegalStateException("Unable to decrypt OpenVPN profiles", result.error)
        }

    private fun decodeProfiles(json: String): List<OpenVpnProfileRecord> {
        val type = object : TypeToken<List<OpenVpnProfileRecord>>() {}.type
        return gson.fromJson<List<OpenVpnProfileRecord>>(json, type).orEmpty()
    }

    private companion object {
        val PROFILES_KEY = stringPreferencesKey("encrypted_profiles")
        val AAD = "weartak:openvpn:profiles:v1".toByteArray(Charsets.UTF_8)
    }
}
