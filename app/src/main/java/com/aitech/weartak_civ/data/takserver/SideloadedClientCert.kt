/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Detects a client certificate side-loaded into the app's `certs/` directory for a server
 * address, using the same file names and password fallback as TAKConnectService:
 * `<address>.p12` (or `<address>.p12.b64`) plus optional `<address>.pwd`.
 */
object SideloadedClientCert {
    private const val DEFAULT_P12_PASSWORD = "atakatak"

    sealed class Status {
        object None : Status()
        data class Found(val commonName: String) : Status()
        object Unreadable : Status()
    }

    fun detect(certDir: File?, address: String): Status {
        val name = address.trim()
        if (certDir == null || name.isEmpty() || name.contains('/') || name.contains('\\') ||
            name == "." || name == ".."
        ) return Status.None
        val binary = File(certDir, "$name.p12")
        val encoded = File(certDir, "$name.p12.b64")
        if (!binary.isFile && !encoded.isFile) return Status.None

        val password = runCatching { File(certDir, "$name.pwd").readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_P12_PASSWORD
        return try {
            val bytes = if (binary.isFile) {
                binary.readBytes()
            } else {
                Base64.getDecoder().decode(encoded.readText().replace("\\s+".toRegex(), ""))
            }
            val keyStore = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(bytes).use { keyStore.load(it, password.toCharArray()) }
            commonName(keyStore, password)?.let { Status.Found(it) } ?: Status.Unreadable
        } catch (_: Exception) {
            Status.Unreadable
        }
    }

    private fun commonName(keyStore: KeyStore, password: String): String? {
        for (alias in keyStore.aliases().toList()) {
            if (!keyStore.isKeyEntry(alias)) continue
            val hasKey = runCatching { keyStore.getKey(alias, password.toCharArray()) is PrivateKey }
                .getOrDefault(false)
            val certificate = keyStore.getCertificateChain(alias)?.firstOrNull() as? X509Certificate
            if (!hasKey || certificate == null) continue
            val cn = runCatching {
                X500Name(certificate.subjectX500Principal.name).getRDNs(BCStyle.CN)
                    .firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) }
            }.getOrNull()
            return cn?.takeIf { it.isNotBlank() } ?: alias
        }
        return null
    }
}
