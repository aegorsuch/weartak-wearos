/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.sitx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.data.secure.crypto.AppKeyManager
import com.aitech.weartak_civ.data.secure.crypto.CryptoConfig
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sitxSecretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SitxSecretsKeys.DATASTORE_NAME
)

class SitxEncryptedDataStore(context: Context) {
    private val appContext = context.applicationContext
    private val ds = appContext.sitxSecretsDataStore

    private val aead = AppKeyManager(appContext).getAead(
        keysetName = SitxSecretsKeys.KEYSET_NAME,
        keystoreAlias = SitxSecretsKeys.KEYSTORE_ALIAS
    )

    // -------- plaintext --------

    fun plaintextString(key: Preferences.Key<String>, default: String = ""): Flow<String> =
        ds.data.map { it[key] ?: default }

    suspend fun setPlaintextString(key: Preferences.Key<String>, value: String) {
        ds.edit { it[key] = value }
    }

    fun plaintextLong(key: Preferences.Key<Long>, default: Long = 0L): Flow<Long> =
        ds.data.map { it[key] ?: default }

    suspend fun setPlaintextLong(key: Preferences.Key<Long>, value: Long) {
        ds.edit { it[key] = value }
    }

    fun plaintextInt(key: Preferences.Key<Int>, default: Int = 0): Flow<Int> =
        ds.data.map { it[key] ?: default }

    suspend fun setPlaintextInt(key: Preferences.Key<Int>, value: Int) {
        ds.edit { it[key] = value }
    }

    // -------- encrypted --------

    fun encryptedString(key: Preferences.Key<String>, default: String? = null): Flow<String?> =
        ds.data.map { prefs ->
            val enc = prefs[key] ?: return@map default
            when (val res = EncryptedSerializer.decryptFromBase64(aead, enc, SitxSecretsKeys.AAD)) {
                is DecryptResult.Success -> res.plaintext
                is DecryptResult.Failure -> default
            }
        }

    suspend fun setEncryptedString(key: Preferences.Key<String>, value: String?) {
        ds.edit { prefs ->
            if (value == null) {
                prefs.remove(key)
            } else {
                val enc = EncryptedSerializer.encryptToBase64(aead, value, SitxSecretsKeys.AAD)
                prefs[key] = enc
                prefs[SitxSecretsKeys.CRYPTO_VERSION] = CryptoConfig.CURRENT_CRYPTO_VERSION
            }
        }
    }

    suspend fun clearAllSecrets(keepBaseUrl: Boolean = true) {
        ds.edit { prefs ->
            prefs.remove(SitxSecretsKeys.CLIENT_ID)
            prefs.remove(SitxSecretsKeys.GROUP_NAME)

            prefs.remove(SitxSecretsKeys.DEVICE_CODE)
            prefs.remove(SitxSecretsKeys.USER_CODE)
            prefs.remove(SitxSecretsKeys.VERIFICATION_URI_COMPLETE)
            prefs.remove(SitxSecretsKeys.DEVICE_CODE_EXPIRES_AT_EPOCH_SEC)
            prefs.remove(SitxSecretsKeys.DEVICE_POLL_INTERVAL_SEC)

            prefs.remove(SitxSecretsKeys.REFRESH_TOKEN_BASE_URL)
            prefs.remove(SitxSecretsKeys.REFRESH_TOKEN_CLIENT_ID)
            prefs.remove(SitxSecretsKeys.REFRESH_TOKEN)
            prefs.remove(SitxSecretsKeys.REFRESH_TOKEN_EXPIRES_AT_EPOCH_SEC)

            prefs.remove(SitxSecretsKeys.TAK_SESSIONS_JSON)
            prefs.remove(SitxSecretsKeys.SELECTED_TAK_GROUP)
            prefs.remove(SitxSecretsKeys.WS_HEALTH_JSON)

            prefs.remove(SitxSecretsKeys.DEVICE_UID)
            prefs.remove(SitxSecretsKeys.CRYPTO_VERSION)

            if (!keepBaseUrl) {
                prefs.remove(SitxSecretsKeys.BASE_URL)
            }
        }
    }
}