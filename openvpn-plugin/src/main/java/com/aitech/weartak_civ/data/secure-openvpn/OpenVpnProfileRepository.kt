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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.service.openvpn.OpenVpnImportedProfile
import com.aitech.weartak_civ.service.openvpn.OpenVpnProfileRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.openVpnProfilesStore: DataStore<Preferences> by preferencesDataStore("openvpn_profiles")

class OpenVpnProfileRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.openVpnProfilesStore
    private val gson = Gson()

    val profiles: Flow<List<OpenVpnProfileRecord>> = store.data.map { preferences ->
        preferences[PROFILES_KEY]?.let(::decryptProfiles) ?: emptyList()
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
            preferences[PROFILES_KEY] = encryptProfiles(profiles)
        }
        return record
    }

    suspend fun saveCredentials(id: String, username: String, password: String) {
        store.edit { preferences ->
            val profiles = decodeExisting(preferences[PROFILES_KEY]).map { profile ->
                if (profile.id == id) profile.copy(username = username, password = password) else profile
            }
            require(profiles.any { it.id == id }) { "OpenVPN profile not found" }
            preferences[PROFILES_KEY] = encryptProfiles(profiles)
        }
    }

    suspend fun delete(id: String) {
        store.edit { preferences ->
            val remaining = decodeExisting(preferences[PROFILES_KEY]).filterNot { it.id == id }
            if (remaining.isEmpty()) preferences.remove(PROFILES_KEY)
            else preferences[PROFILES_KEY] = encryptProfiles(remaining)
        }
    }

    private suspend fun readProfiles(): List<OpenVpnProfileRecord> =
        store.data.map { it[PROFILES_KEY] }.first()?.let(::decryptProfiles) ?: emptyList()

    private fun decodeExisting(encoded: String?): List<OpenVpnProfileRecord> =
        encoded?.let(::decryptProfiles) ?: emptyList()

    private fun encryptProfiles(profiles: List<OpenVpnProfileRecord>): String {
        val plaintext = gson.toJson(profiles).toByteArray(StandardCharsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(plaintext)
            val output = ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
                .put(FORMAT_VERSION)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
            try {
                Base64.encodeToString(output, Base64.NO_WRAP)
            } finally {
                output.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decryptProfiles(encoded: String): List<OpenVpnProfileRecord> {
        val payload = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Unable to decode encrypted OpenVPN profiles", error)
        }
        require(payload.size > 1 + GCM_NONCE_BYTES + GCM_TAG_BYTES && payload[0] == FORMAT_VERSION) {
            "Encrypted OpenVPN profiles have an unsupported format"
        }
        val iv = payload.copyOfRange(1, 1 + GCM_NONCE_BYTES)
        val ciphertext = payload.copyOfRange(1 + GCM_NONCE_BYTES, payload.size)
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } catch (error: Exception) {
            throw IllegalStateException("Unable to decrypt OpenVPN profiles", error)
        } finally {
            payload.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
        return try {
            val type = object : TypeToken<List<OpenVpnProfileRecord>>() {}.type
            gson.fromJson<List<OpenVpnProfileRecord>>(
                String(plaintext, StandardCharsets.UTF_8),
                type,
            ) ?: emptyList()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "weartak.openvpn.plugin.profiles.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val FORMAT_VERSION: Byte = 1
        val PROFILES_KEY = stringPreferencesKey("encrypted_profiles")
        val AAD = "weartak:openvpn-plugin:profiles:v1".toByteArray(StandardCharsets.UTF_8)
    }
}
