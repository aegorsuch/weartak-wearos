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
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakChannelHostnameVerifierTest {
    @Test
    fun acceptsWhenStrictVerifierAccepts() {
        val verifier = TakChannelHostnameVerifier(HostnameVerifier { _, _ -> true })

        assertTrue(verifier.verify("tak.example", mockk()))
    }

    @Test
    fun acceptsExactCommonNameWhenSubjectAlternativeNameIsAbsent() {
        val verifier = fallbackVerifier()
        val session = sessionFor("CN=takserver.aftakcoe.org,OU=XX,O=COE-TAK")

        assertTrue(verifier.verify("TAKSERVER.AFTAKCOE.ORG.", session))
    }

    @Test
    fun rejectsMismatchedCommonName() {
        val verifier = fallbackVerifier()
        val session = sessionFor("CN=other.example")

        assertFalse(verifier.verify("tak.example", session))
    }

    @Test
    fun rejectsCommonNameFallbackWhenSubjectAlternativeNameIsPresent() {
        val verifier = fallbackVerifier()
        val session = sessionFor(
            subject = "CN=tak.example",
            subjectAlternativeNameExtension = byteArrayOf(1),
        )

        assertFalse(verifier.verify("tak.example", session))
    }

    @Test
    fun rejectsIpAddressCommonNameFallback() {
        val verifier = fallbackVerifier()
        val session = sessionFor("CN=192.0.2.10")

        assertFalse(verifier.verify("192.0.2.10", session))
    }

    @Test
    fun rejectsWildcardCommonNameFallback() {
        val verifier = fallbackVerifier()
        val session = sessionFor("CN=*.example")

        assertFalse(verifier.verify("tak.example", session))
    }

    @Test
    fun rejectsUnavailablePeerCertificate() {
        val verifier = fallbackVerifier()
        val session = mockk<SSLSession>()
        every { session.peerCertificates } throws SSLPeerUnverifiedException("unavailable")

        assertFalse(verifier.verify("tak.example", session))
    }

    private fun fallbackVerifier(): TakChannelHostnameVerifier =
        TakChannelHostnameVerifier(HostnameVerifier { _, _ -> false })

    private fun sessionFor(
        subject: String,
        subjectAlternativeNameExtension: ByteArray? = null,
    ): SSLSession {
        val certificate = mockk<X509Certificate>()
        every { certificate.subjectX500Principal } returns X500Principal(subject)
        every { certificate.getExtensionValue(SUBJECT_ALTERNATIVE_NAME_OID) } returns
            subjectAlternativeNameExtension

        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns arrayOf<Certificate>(certificate)
        return session
    }

    private companion object {
        const val SUBJECT_ALTERNATIVE_NAME_OID = "2.5.29.17"
    }
}
