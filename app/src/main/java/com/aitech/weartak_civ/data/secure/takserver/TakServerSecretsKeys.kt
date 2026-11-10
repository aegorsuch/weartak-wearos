/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.takserver

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object TakServerSecretsKeys {
    // DataStore file name
    const val DATASTORE_NAME: String = "takserver_secrets"

    // Tink keyset identity + Keystore alias for wrapping key
    const val KEYSET_NAME: String = "takserver_aead_keyset"
    const val KEYSTORE_ALIAS: String = "weartak.takserver.master.v1"

    // AAD binds ciphertext to this domain
    val AAD: ByteArray = "weartak:takserver:list:v1".toByteArray(Charsets.UTF_8)

    // encrypted TAK server list JSON
    val TAK_SERVER_LIST_JSON = stringPreferencesKey("tak_server_list_json")

    // encrypted copy of the watch's own TAK server list saved before a phone bond replaced it
    val PHONE_MANAGED_TAK_SERVER_SNAPSHOT_JSON = stringPreferencesKey("phone_managed_tak_server_snapshot_json")

    // crypto metadata
    val CRYPTO_VERSION = intPreferencesKey("takserver.crypto_version")
}
