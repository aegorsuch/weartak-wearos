/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.migration

import com.aitech.weartak_civ.data.secure.crypto.CryptoConfig
import com.aitech.weartak_civ.data.secure.sitx.SitxEncryptedDataStore
import com.aitech.weartak_civ.data.secure.sitx.SitxSecretsKeys
import kotlinx.coroutines.flow.first

class SitxMigration(private val store: SitxEncryptedDataStore) {

    suspend fun runIfNeeded() {
        val storedVersion = store.plaintextInt(SitxSecretsKeys.CRYPTO_VERSION, default = 0).first()
        if (storedVersion > CryptoConfig.CURRENT_CRYPTO_VERSION) {
            store.clearAllSecrets(keepBaseUrl = true)
            return
        }

        // Conservative probe: if crypto_version is set but decryptable secrets are all null, clear.
        val anyEncrypted =
            store.encryptedString(SitxSecretsKeys.CLIENT_ID).first()
                ?: store.encryptedString(SitxSecretsKeys.REFRESH_TOKEN).first()
                ?: store.encryptedString(SitxSecretsKeys.TAK_SESSIONS_JSON).first()

        if (storedVersion != 0 && anyEncrypted == null) {
            store.clearAllSecrets(keepBaseUrl = true)
        }
    }
}
