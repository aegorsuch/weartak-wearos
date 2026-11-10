/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.crypto

object CryptoConfig {
    // Increment when you change envelope format or algorithms.
    const val CURRENT_CRYPTO_VERSION: Int = 1

    // Tink AEAD is the high-level primitive; actual algorithm is embedded in the key template.
    // (e.g., AES256_GCM or AES256_GCM_SIV depending on your choice).
    const val KEYSET_PREFS_NAME: String = "weartak_secure_keysets"
}