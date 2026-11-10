/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import java.net.IDN
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import okhttp3.OkHttpClient
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle

internal class TakChannelHostnameVerifier(
    private val strictVerifier: HostnameVerifier = OkHttpClient().hostnameVerifier,
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (strictVerifier.verify(hostname, session)) return true

        val leafCertificate = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: Exception) {
            null
        } ?: return false

        return verifyCommonNameFallback(hostname, leafCertificate)
    }

    private fun verifyCommonNameFallback(
        hostname: String,
        certificate: X509Certificate,
    ): Boolean {
        if (certificate.getExtensionValue(SUBJECT_ALTERNATIVE_NAME_OID) != null) return false

        val normalizedHostname = normalizeTakDnsName(hostname) ?: return false
        val normalizedCommonName = certificate.commonName()
            ?.let(::normalizeTakDnsName)
            ?: return false

        return normalizedHostname == normalizedCommonName
    }

    private fun X509Certificate.commonName(): String? = runCatching {
        X500Name.getInstance(subjectX500Principal.encoded)
            .getRDNs(BCStyle.CN)
            .flatMap { rdn ->
                rdn.typesAndValues.filter { attribute -> attribute.type == BCStyle.CN }
            }
            .singleOrNull()
            ?.value
            ?.toString()
    }.getOrNull()

    private companion object {
        const val SUBJECT_ALTERNATIVE_NAME_OID = "2.5.29.17"
    }
}

internal fun normalizeTakDnsName(value: String): String? {
    val withoutTrailingDot = value.removeSuffix(".")
    if (withoutTrailingDot.isEmpty() || withoutTrailingDot.contains('*')) return null
    if (withoutTrailingDot.contains(':') || withoutTrailingDot.all { it.isDigit() || it == '.' }) {
        return null
    }

    return try {
        IDN.toASCII(withoutTrailingDot, IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.US)
    } catch (_: IllegalArgumentException) {
        null
    }
}
