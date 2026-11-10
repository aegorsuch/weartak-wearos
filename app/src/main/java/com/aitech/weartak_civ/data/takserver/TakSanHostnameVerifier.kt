/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl

internal class TakSanHostnameVerifier(
    address: String,
    private val serverTrustManager: X509TrustManager,
    private val publicTrustManager: X509TrustManager = systemTrustManager(),
    private val strictVerifier: HostnameVerifier = TakChannelHostnameVerifier(),
    private val onResolved: (String) -> Unit = {},
    private val onRejected: (String) -> Unit = {},
) : HostnameVerifier {
    private val configuredHost = HttpUrl.Builder().scheme("https").host(address).build().host
    private var resolvedName: String? = null

    @Synchronized
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (!hostname.equals(configuredHost, ignoreCase = true)) {
            return strictVerifier.verify(hostname, session)
        }
        // Keep the discovered identity for this connection; never silently replace it.
        resolvedName?.let {
            return if (strictVerifier.verify(it, session)) true
            else reject("Previously discovered TLS name failed hostname validation")
        }
        if (strictVerifier.verify(hostname, session)) return true

        val chain = try {
            session.peerCertificates.map {
                it as? X509Certificate ?: return reject("Server supplied a non-X.509 certificate")
            }.toTypedArray()
        } catch (_: SSLPeerUnverifiedException) {
            return reject("Server certificate is unavailable")
        }
        val leaf = chain.firstOrNull() ?: return reject("Server certificate chain is empty")
        val authType = when (leaf.publicKey.algorithm) {
            "RSA" -> "RSA"
            "EC" -> "ECDHE_ECDSA"
            "DSA" -> "DHE_DSS"
            else -> return reject("Unsupported server certificate key algorithm")
        }
        try {
            serverTrustManager.checkServerTrusted(chain, authType)
        } catch (error: CertificateException) {
            return reject("Server CA validation failed: ${error.message}")
        }
        try {
            publicTrustManager.checkServerTrusted(chain, authType)
            return reject("Automatic SAN discovery is unavailable for public-CA certificates")
        } catch (_: CertificateException) {
            // Discovery is restricted to the imported/enrollment CA, not public roots.
        }

        val names = try {
            leaf.subjectAlternativeNames.orEmpty()
                .filter { it.firstOrNull() == DNS_SAN_TYPE }
                .mapNotNull { entry ->
                    val name = entry.getOrNull(1) as? String
                        ?: return reject("Invalid certificate DNS SAN")
                    if ('*' in name) null else {
                        normalizeTakDnsName(name) ?: return reject("Unsupported certificate DNS SAN")
                    }
                }
                .distinct()
        } catch (error: CertificateException) {
            return reject("Unable to read certificate DNS SANs: ${error.message}")
        }
        val name = names.singleOrNull()
            ?: return reject("Automatic SAN discovery requires one exact DNS SAN")
        if (!strictVerifier.verify(name, session)) {
            return reject("Discovered DNS SAN failed hostname validation")
        }
        resolvedName = name
        onResolved(name)
        return true
    }

    private fun reject(reason: String): Boolean {
        onRejected(reason)
        return false
    }

    private companion object {
        const val DNS_SAN_TYPE = 2

        fun systemTrustManager(): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
