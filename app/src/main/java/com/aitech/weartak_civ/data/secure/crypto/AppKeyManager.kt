/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager

class AppKeyManager(private val context: Context) {

    init {
        AeadConfig.register()
    }

    /**
     * Returns an AEAD bound to a particular keyset name and Keystore alias.
     * keysetName: unique per "domain" (sitx, takserver, etc.)
     * keystoreAlias: unique per "domain" and versioned when you rotate.
     */
    fun getAead(keysetName: String, keystoreAlias: String): Aead {
        val masterKeyUri = "android-keystore://$keystoreAlias"

        val handle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, CryptoConfig.KEYSET_PREFS_NAME)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(masterKeyUri)
            .build()
            .keysetHandle

        return handle.getPrimitive(Aead::class.java)
    }
}