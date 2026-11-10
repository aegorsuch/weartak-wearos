/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.takserver

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

private val Context.takServerSecretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = TakServerSecretsKeys.DATASTORE_NAME
)

class TakServerEncryptedDataStore(context: Context) {
    private val appContext = context.applicationContext
    private val ds = appContext.takServerSecretsDataStore

    private val aead = AppKeyManager(appContext).getAead(
        keysetName = TakServerSecretsKeys.KEYSET_NAME,
        keystoreAlias = TakServerSecretsKeys.KEYSTORE_ALIAS
    )

    fun encryptedString(key: Preferences.Key<String>): Flow<String?> =
        encryptedStringResult(key).map { result ->
            (result as? DecryptResult.Success)?.plaintext
        }

    fun encryptedStringResult(key: Preferences.Key<String>): Flow<DecryptResult?> =
        ds.data.map { prefs ->
            val enc = prefs[key] ?: return@map null
            EncryptedSerializer.decryptFromBase64(aead, enc, TakServerSecretsKeys.AAD)
        }

    suspend fun setEncryptedString(key: Preferences.Key<String>, value: String?) {
        ds.edit { prefs ->
            if (value == null) {
                prefs.remove(key)
            } else {
                val enc = EncryptedSerializer.encryptToBase64(aead, value, TakServerSecretsKeys.AAD)
                prefs[key] = enc
                prefs[TakServerSecretsKeys.CRYPTO_VERSION] = CryptoConfig.CURRENT_CRYPTO_VERSION
            }
        }
    }
}
