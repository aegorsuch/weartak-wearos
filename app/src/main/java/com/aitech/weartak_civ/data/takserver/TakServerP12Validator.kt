/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Base64

class TakServerP12ValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object TakServerP12Validator {
    private const val DEFAULT_P12_PASSWORD = "atakatak"

    fun validate(items: List<TakServerItem>) {
        items.forEachIndexed { index, item ->
            if (item.isP12Cert) validate(index, item)
        }
    }

    private fun validate(index: Int, item: TakServerItem) {
        val label = item.name.trim().ifEmpty { "Server ${index + 1}" }
        val encoded = item.p12Cert
            .replace("\\/", "/")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
        if (encoded.isEmpty()) {
            throw TakServerP12ValidationException("$label has P12 authentication enabled but no certificate.")
        }

        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw TakServerP12ValidationException("$label contains malformed P12 certificate data.", error)
        }
        val password = item.p12CertPassword.ifEmpty { DEFAULT_P12_PASSWORD }.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        try {
            ByteArrayInputStream(bytes).use { keyStore.load(it, password) }
        } catch (error: Exception) {
            throw TakServerP12ValidationException(
                "$label P12 certificate or password could not be opened.",
                error
            )
        }

        var hasPrivateKey = false
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements() && !hasPrivateKey) {
            val alias = aliases.nextElement()
            hasPrivateKey = keyStore.isKeyEntry(alias) && runCatching {
                keyStore.getKey(alias, password) is PrivateKey &&
                    !keyStore.getCertificateChain(alias).isNullOrEmpty()
            }.getOrDefault(false)
        }
        if (!hasPrivateKey) {
            throw TakServerP12ValidationException("$label P12 certificate has no client private key.")
        }
    }
}
