/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.takgov

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object TakGovLinkKeys {
    const val DATASTORE_NAME = "takgov_link_secrets"
    const val KEYSET_NAME = "takgov_link_aead_keyset"
    const val KEYSTORE_ALIAS = "weartak.takgov.link.master.v1"

    val AAD = "weartak:takgov-link:secrets".toByteArray(Charsets.UTF_8)
    val REFRESH_TOKEN = stringPreferencesKey("takgov.refresh_token.enc")
    val DISPLAY_ACCESS_LEVEL = stringPreferencesKey("takgov.display_access_level.enc")
    val ACCESS_LEVEL_CHECKED_AT = longPreferencesKey("takgov.access_level_checked_at")
    val CRYPTO_VERSION = intPreferencesKey("takgov.crypto_version")
}
