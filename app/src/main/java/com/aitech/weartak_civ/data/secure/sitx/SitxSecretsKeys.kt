/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.sitx

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SitxSecretsKeys {
    // DataStore file name
    const val DATASTORE_NAME: String = "sitx_secrets"

    // Tink keyset identity + Keystore alias for wrapping key
    const val KEYSET_NAME: String = "sitx_aead_keyset"
    const val KEYSTORE_ALIAS: String = "weartak.sitx.master.v1"

    // AAD binds ciphertext to this domain
    val AAD: ByteArray = "weartak:sitx:secrets".toByteArray(Charsets.UTF_8)

    // plaintext config
    val BASE_URL = stringPreferencesKey("sitx.base_url")

    // encrypted secrets
    val CLIENT_ID = stringPreferencesKey("sitx.client_id.enc")

    val GROUP_NAME = stringPreferencesKey("sitx.group_name.enc")

    // Step 1 cache (encrypted)
    val DEVICE_CODE = stringPreferencesKey("sitx.device_code.enc")
    val USER_CODE = stringPreferencesKey("sitx.user_code.enc")
    val VERIFICATION_URI_COMPLETE = stringPreferencesKey("sitx.verification_uri_complete.enc")

    // Step 1 timers (plaintext)
    val DEVICE_CODE_EXPIRES_AT_EPOCH_SEC = longPreferencesKey("sitx.device_code_expires_at_epoch_sec")
    val DEVICE_POLL_INTERVAL_SEC = longPreferencesKey("sitx.device_poll_interval_sec")

    // Refresh token (encrypted) + associated URL (plaintext) + associated Client Id (encrypted) + expiry epoch seconds (plaintext)
    val REFRESH_TOKEN_BASE_URL = stringPreferencesKey("sitx.refresh_token_base_url")
    val REFRESH_TOKEN_CLIENT_ID = stringPreferencesKey("sitx.refresh_token_client_id.enc")
    val REFRESH_TOKEN = stringPreferencesKey("sitx.refresh_token.enc")
    val REFRESH_TOKEN_EXPIRES_AT_EPOCH_SEC = longPreferencesKey("sitx.refresh_token_expires_at_epoch_sec")

    // TAK sessions list (encrypted JSON array)
    val TAK_SESSIONS_JSON = stringPreferencesKey("sitx.tak_sessions_json.enc")

    val SELECTED_TAK_GROUP =
        stringPreferencesKey("sitx.selected_tak_group.enc")

    // WS health timestamps per flowTag (plaintext JSON map: flowTag -> lastConnectedEpochSec)
    val WS_HEALTH_JSON = stringPreferencesKey("sitx.ws_health_json")

    // Optional (kept)
    val DEVICE_UID = stringPreferencesKey("sitx.device_uid.enc")

    // crypto metadata
    val CRYPTO_VERSION = intPreferencesKey("sitx.crypto_version")
}