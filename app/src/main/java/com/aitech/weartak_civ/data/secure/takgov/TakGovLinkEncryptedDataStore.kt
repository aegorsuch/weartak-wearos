/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.takgov

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.data.secure.crypto.AppKeyManager
import com.aitech.weartak_civ.data.secure.crypto.CryptoConfig
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.takGovLinkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = TakGovLinkKeys.DATASTORE_NAME
)

class TakGovLinkEncryptedDataStore(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.takGovLinkDataStore
    private val aead = AppKeyManager(appContext).getAead(
        keysetName = TakGovLinkKeys.KEYSET_NAME,
        keystoreAlias = TakGovLinkKeys.KEYSTORE_ALIAS
    )

    fun refreshTokenResult(): Flow<DecryptResult?> = dataStore.data.map { preferences ->
        decrypt(preferences[TakGovLinkKeys.REFRESH_TOKEN])
    }

    fun displayAccessLevelResult(): Flow<DecryptResult?> =
        dataStore.data.map { preferences -> decrypt(preferences[TakGovLinkKeys.DISPLAY_ACCESS_LEVEL]) }

    fun accessLevelCheckedAtMillis(): Flow<Long> =
        dataStore.data.map { it[TakGovLinkKeys.ACCESS_LEVEL_CHECKED_AT] ?: 0L }

    suspend fun setRefreshToken(token: String) {
        require(token.isNotBlank()) { "Refresh token must not be blank." }
        dataStore.edit { preferences ->
            preferences[TakGovLinkKeys.REFRESH_TOKEN] = encrypt(token)
            preferences[TakGovLinkKeys.CRYPTO_VERSION] = CryptoConfig.CURRENT_CRYPTO_VERSION
        }
    }

    suspend fun saveNewLink(token: String, checkedAtMillis: Long) {
        require(token.isNotBlank()) { "Refresh token must not be blank." }
        dataStore.edit { preferences ->
            preferences[TakGovLinkKeys.REFRESH_TOKEN] = encrypt(token)
            preferences.remove(TakGovLinkKeys.DISPLAY_ACCESS_LEVEL)
            preferences[TakGovLinkKeys.ACCESS_LEVEL_CHECKED_AT] = checkedAtMillis
            preferences[TakGovLinkKeys.CRYPTO_VERSION] = CryptoConfig.CURRENT_CRYPTO_VERSION
        }
    }

    suspend fun setDisplayAccessLevel(accessLevel: String) {
        require(accessLevel in setOf("CIV", "MIL")) { "Unsupported TAK.gov access level." }
        dataStore.edit { preferences ->
            preferences[TakGovLinkKeys.DISPLAY_ACCESS_LEVEL] = encrypt(accessLevel)
            preferences[TakGovLinkKeys.CRYPTO_VERSION] = CryptoConfig.CURRENT_CRYPTO_VERSION
        }
    }

    suspend fun startAccessLevelCheck(checkedAtMillis: Long) {
        dataStore.edit { preferences ->
            preferences.remove(TakGovLinkKeys.DISPLAY_ACCESS_LEVEL)
            preferences[TakGovLinkKeys.ACCESS_LEVEL_CHECKED_AT] = checkedAtMillis
        }
    }

    suspend fun clearLink() {
        dataStore.edit { preferences ->
            preferences.remove(TakGovLinkKeys.REFRESH_TOKEN)
            preferences.remove(TakGovLinkKeys.DISPLAY_ACCESS_LEVEL)
            preferences.remove(TakGovLinkKeys.ACCESS_LEVEL_CHECKED_AT)
            preferences.remove(TakGovLinkKeys.CRYPTO_VERSION)
        }
    }

    private fun decrypt(encrypted: String?): DecryptResult? =
        encrypted?.let { EncryptedSerializer.decryptFromBase64(aead, it, TakGovLinkKeys.AAD) }

    private fun encrypt(value: String): String =
        EncryptedSerializer.encryptToBase64(aead, value, TakGovLinkKeys.AAD)
}
