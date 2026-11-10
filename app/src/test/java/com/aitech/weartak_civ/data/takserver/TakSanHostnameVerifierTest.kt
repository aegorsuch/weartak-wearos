/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakSanHostnameVerifierTest {
    private val serverTrust = mockk<X509TrustManager> {
        every { checkServerTrusted(any(), any()) } returns Unit
    }
    private val publicTrust = mockk<X509TrustManager> {
        every { checkServerTrusted(any(), any()) } throws CertificateException("Not a public CA")
    }
    private val resolutions = mutableListOf<String>()
    private val rejections = mutableListOf<String>()
    private val strict = HostnameVerifier { name, session ->
        (session.peerCertificates.first() as X509Certificate).subjectAlternativeNames.orEmpty()
            .any { it.firstOrNull() == 2 && (it.getOrNull(1) as? String)?.let(::normalizeTakDnsName) == name }
    }

    @Test
    fun preservesNormalHostnameValidationWithoutDiscovery() {
        val verifier = verifier()

        assertTrue(verifier.verify(ADDRESS, sessionFor(ADDRESS)))
        assertTrue(resolutions.isEmpty())
        verify(exactly = 0) { serverTrust.checkServerTrusted(any(), any()) }
        verify(exactly = 0) { publicTrust.checkServerTrusted(any(), any()) }
    }

    @Test
    fun discoversExactSanWithoutChangingConfiguredAddress() {
        val verifier = verifier()

        assertTrue(verifier.verify(ADDRESS, sessionFor(SAN)))
        assertEquals(listOf(SAN), resolutions)
        verify(exactly = 1) { serverTrust.checkServerTrusted(any(), "RSA") }
        verify(exactly = 1) { publicTrust.checkServerTrusted(any(), "RSA") }
    }

    @Test
    fun discoversSanWithProductionHostnameValidator() {
        val session = sessionFor(SAN)
        val certificate = session.peerCertificates.first() as X509Certificate
        every { certificate.getExtensionValue("2.5.29.17") } returns byteArrayOf(1)

        assertTrue(verifier(TakChannelHostnameVerifier()).verify(ADDRESS, session))
        assertEquals(listOf(SAN), resolutions)
        assertEquals(ADDRESS, takChannelGroupsUrl(ADDRESS, sendLatestSa = true).host)
    }

    @Test
    fun preservesExistingExactCommonNameFallback() {
        val session = sessionFor()
        val certificate = session.peerCertificates.first() as X509Certificate
        every { certificate.getExtensionValue("2.5.29.17") } returns null
        every { certificate.subjectX500Principal } returns X500Principal("CN=$ADDRESS")

        assertTrue(verifier(TakChannelHostnameVerifier()).verify(ADDRESS, session))
        assertTrue(resolutions.isEmpty())
        verify(exactly = 0) { serverTrust.checkServerTrusted(any(), any()) }
    }

    @Test
    fun normalizesAndDeduplicatesDnsSans() {
        assertTrue(verifier().verify(ADDRESS, sessionFor(SAN.uppercase() + ".", SAN)))
        assertEquals(listOf(SAN), resolutions)
    }

    @Test
    fun rejectsMultipleExactDnsSans() {
        assertFalse(verifier().verify(ADDRESS, sessionFor(SAN, "other.nctak.org")))
        assertEquals(listOf("Automatic SAN discovery requires one exact DNS SAN"), rejections)
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun rejectsMissingSanInsteadOfGuessingCommonName() {
        assertFalse(verifier().verify(ADDRESS, sessionFor()))
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun rejectsWildcardOnlySan() {
        assertFalse(verifier().verify(ADDRESS, sessionFor("*.nctak.org")))
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun usesSingleExactDnsSanEvenWithWildcardAndIpEntries() {
        val session = sessionFor(SAN, "*.nctak.org")
        val certificate = session.peerCertificates.first() as X509Certificate
        every { certificate.subjectAlternativeNames } returns
            listOf(listOf(2, SAN), listOf(2, "*.nctak.org"), listOf(7, "192.0.2.10"))

        assertTrue(verifier().verify(ADDRESS, session))
        assertEquals(listOf(SAN), resolutions)
    }

    @Test
    fun rejectsInvalidDnsSan() {
        assertFalse(verifier().verify(ADDRESS, sessionFor("invalid name")))
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun rejectsIpAddressInDnsSan() {
        assertFalse(verifier().verify(ADDRESS, sessionFor("192.0.2.10")))
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun rejectsCertificateNotTrustedByServerCa() {
        every { serverTrust.checkServerTrusted(any(), any()) } throws CertificateException("Untrusted")

        assertFalse(verifier().verify(ADDRESS, sessionFor(SAN)))
        assertTrue(rejections.single().contains("Server CA validation failed"))
        verify(exactly = 0) { publicTrust.checkServerTrusted(any(), any()) }
    }

    @Test
    fun rejectsPublicCaCertificateDespiteServerCaTrust() {
        every { publicTrust.checkServerTrusted(any(), any()) } returns Unit

        assertFalse(verifier().verify(ADDRESS, sessionFor(SAN)))
        assertTrue(rejections.single().contains("public-CA"))
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun retainsDiscoveredIdentityAcrossRequestsAndRejectsReplacement() {
        val verifier = verifier()

        assertTrue(verifier.verify(ADDRESS, sessionFor(SAN)))
        assertTrue(verifier.verify(ADDRESS, sessionFor(SAN)))
        assertFalse(verifier.verify(ADDRESS, sessionFor("replacement.nctak.org")))
        assertEquals(listOf(SAN), resolutions)
        verify(exactly = 1) { serverTrust.checkServerTrusted(any(), any()) }
    }

    @Test
    fun keepsDiscoveredIdentityScopedToConfiguredHost() {
        val verifier = verifier()

        assertTrue(verifier.verify(ADDRESS, sessionFor(SAN)))
        assertFalse(verifier.verify("redirect.example", sessionFor(SAN)))
        assertTrue(verifier.verify("redirect.example", sessionFor("redirect.example")))
        assertEquals(listOf(SAN), resolutions)
    }

    @Test
    fun doesNotDiscoverSanForAnotherHost() {
        assertFalse(verifier().verify("other.example", sessionFor(SAN)))
        assertTrue(resolutions.isEmpty())
        verify(exactly = 0) { serverTrust.checkServerTrusted(any(), any()) }
    }

    @Test
    fun newConnectionCanDiscoverItsOwnIdentity() {
        assertTrue(verifier().verify(ADDRESS, sessionFor(SAN)))
        assertTrue(verifier().verify(ADDRESS, sessionFor("replacement.nctak.org")))
        assertEquals(listOf(SAN, "replacement.nctak.org"), resolutions)
    }

    @Test
    fun rejectsUnavailablePeerCertificate() {
        val session = mockk<SSLSession> {
            every { peerCertificates } throws SSLPeerUnverifiedException("Unavailable")
        }
        val verifier = verifier(HostnameVerifier { _, _ -> false })

        assertFalse(verifier.verify(ADDRESS, session))
        assertEquals(listOf("Server certificate is unavailable"), rejections)
    }

    @Test
    fun rejectsMalformedSanExtension() {
        val session = sessionFor(SAN)
        val certificate = session.peerCertificates.first() as X509Certificate
        every { certificate.subjectAlternativeNames } throws CertificateParsingException("Malformed")
        val verifier = verifier(HostnameVerifier { _, _ -> false })

        assertFalse(verifier.verify(ADDRESS, session))
        assertTrue(rejections.single().contains("Unable to read"))
    }

    @Test
    fun requiresStrictValidationOfDiscoveredName() {
        assertFalse(verifier(HostnameVerifier { _, _ -> false }).verify(ADDRESS, sessionFor(SAN)))
        assertEquals(listOf("Discovered DNS SAN failed hostname validation"), rejections)
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun checksEcCertificatesWithEcdsaAuthenticationType() {
        val session = sessionFor(SAN)
        val certificate = session.peerCertificates.first() as X509Certificate
        every { certificate.publicKey.algorithm } returns "EC"

        assertTrue(verifier().verify(ADDRESS, session))
        verify { serverTrust.checkServerTrusted(any(), "ECDHE_ECDSA") }
        verify { publicTrust.checkServerTrusted(any(), "ECDHE_ECDSA") }
    }

    @Test
    fun rejectsUnsupportedKeyAlgorithm() {
        val session = sessionFor(SAN)
        val certificate = session.peerCertificates.first() as X509Certificate
        every { certificate.publicKey.algorithm } returns "Unsupported"

        assertFalse(verifier().verify(ADDRESS, session))
        assertEquals(listOf("Unsupported server certificate key algorithm"), rejections)
        verify(exactly = 0) { serverTrust.checkServerTrusted(any(), any()) }
    }

    private fun verifier(strictVerifier: HostnameVerifier = strict) = TakSanHostnameVerifier(
        address = ADDRESS,
        serverTrustManager = serverTrust,
        publicTrustManager = publicTrust,
        strictVerifier = strictVerifier,
        onResolved = resolutions::add,
        onRejected = rejections::add,
    )

    private fun sessionFor(vararg names: String): SSLSession {
        val certificate = mockk<X509Certificate> {
            every { publicKey.algorithm } returns "RSA"
            every { subjectAlternativeNames } returns names.map { listOf(2, it) }
        }
        return mockk {
            every { peerCertificates } returns arrayOf<Certificate>(certificate)
        }
    }

    private companion object {
        const val ADDRESS = "takserver.nctak.org"
        const val SAN = "takserver2.nctak.org"
    }
}
