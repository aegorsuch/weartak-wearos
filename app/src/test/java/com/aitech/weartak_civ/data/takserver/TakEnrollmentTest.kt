package com.aitech.weartak_civ.data.takserver

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLHandshakeException

class TakEnrollmentTest {
    @Test fun onlyConfirmedUntrustedIssuerFailuresOfferCaImport() {
        val androidTrustFailure = java.security.cert.CertPathValidatorException(
            "Trust anchor for certification path not found.")
        val pkixTrustFailure = java.security.cert.CertPathValidatorException(
            "no anchor", null, null, -1, java.security.cert.PKIXReason.NO_TRUST_ANCHOR)
        listOf(androidTrustFailure, pkixTrustFailure).forEach { cause ->
            val failure = TakConnectionFailure.from(TakEnrollmentStage.CONFIG,
                SSLHandshakeException("handshake").apply { initCause(cause) })
            assertEquals(TakFailureCategory.TLS, failure.category)
            assertTrue(failure.untrustedServerCa)
            assertEquals(failure, TakConnectionFailure.from(TakEnrollmentStage.CONNECTION,
                TakEnrollmentException(failure)))
        }
        listOf(
            SSLHandshakeException("generic handshake"),
            javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch"),
            IOException("offline"),
            SSLHandshakeException("expired").apply {
                initCause(java.security.cert.CertificateExpiredException())
            },
            SSLHandshakeException("not yet valid").apply {
                initCause(java.security.cert.CertificateNotYetValidException())
            },
            SSLHandshakeException("invalid signature").apply {
                initCause(java.security.cert.CertPathValidatorException("invalid signature"))
            },
        ).forEach {
            assertFalse(TakConnectionFailure.from(TakEnrollmentStage.CONNECTION, it).untrustedServerCa)
        }
    }

    @Test fun openTakServerAuthorityIsParsedInstalledAndTrusted() {
        val leaf = chain[1]
        val ca = chain[0]
        val pem = "-----BEGIN CERTIFICATE-----\n${b64(ca)}\n-----END CERTIFICATE-----"
        listOf(
            """{"signedCert":"${b64(leaf)}","ca":"${b64(ca)}"}""",
            """{"signedCert":"${b64(leaf)}","ca":"${pem.replace("\n", "\\n")}"}""",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><enrollment>" +
                "<signedCert>${b64(leaf)}</signedCert><ca>$pem</ca></enrollment>",
            "<r:enrollment xmlns:r=\"urn:tak\"><r:signedCert>${b64(leaf)}</r:signedCert>" +
                "<r:ca>$pem</r:ca></r:enrollment>"
        ).forEach {
            val parsed = TakEnrollment.parseSignResponse(it)
            assertEquals(leaf, parsed.signedCert)
            assertEquals(listOf("ca" to ca), parsed.authorities)
            val ks = KeyStore.getInstance("PKCS12")
            parsed.install(ks, keys[1].private, "client", password)
            assertTrue(ks.isCertificateEntry("ca"))
            assertEquals(listOf(leaf, ca), ks.getCertificateChain("client").toList())
            assertEquals(listOf(ca), TakEnrollment.selectTrustAnchors(ks))
        }
    }

    @Test fun unnumberedAuthoritySortsBeforeNumberedAuthorities() {
        val entries = linkedMapOf("ca10" to b64(chain[0]), "ca2" to b64(chain[1]),
            "ca0" to b64(chain[2]), "ca" to b64(chain[0]))
        val json = """{"signedCert":"${b64(chain[3])}",${entries.entries.joinToString { "\"${it.key}\":\"${it.value}\"" }}}"""
        val xml = "<enrollment><signedCert>${b64(chain[3])}</signedCert>" +
            entries.entries.joinToString("") { "<${it.key}>${it.value}</${it.key}>" } + "</enrollment>"
        listOf(json, xml).forEach {
            assertEquals(listOf("ca", "ca0", "ca2", "ca10"),
                TakEnrollment.parseSignResponse(it).authorities.map { ca -> ca.first })
        }
    }

    @Test fun blankOptionalOpenTakServerAuthorityIsAccepted() {
        listOf(
            """{"signedCert":"${b64(chain[1])}","ca":null,"ca0":"${b64(chain[0])}"}""",
            "<enrollment><signedCert>${b64(chain[1])}</signedCert><ca> </ca>" +
                "<ca0>${b64(chain[0])}</ca0></enrollment>"
        ).forEach {
            assertEquals(listOf("ca0"), TakEnrollment.parseSignResponse(it).authorities.map { ca -> ca.first })
        }
    }

    @Test fun malformedOpenTakServerAuthorityIsRejected() {
        listOf(
            """{"signedCert":"${b64(chain[1])}","ca":"bad!"}""",
            """{"signedCert":"${b64(chain[1])}","ca":123}""",
            """{"signedCert":"${b64(chain[1])}","ca":{}}""",
            "<enrollment><signedCert>${b64(chain[1])}</signedCert><ca>bad!</ca></enrollment>",
            "<enrollment><signedCert>${b64(chain[1])}</signedCert>" +
                "<ca>${b64(chain[0])}</ca><ca>${b64(chain[0])}</ca></enrollment>"
        ).forEach {
            assertThrows(Exception::class.java) { TakEnrollment.parseSignResponse(it) }
        }
    }

    @Test fun jsonAndXmlAcceptLeafWithoutAuthorities() {
        val leaf = chain.last()
        listOf("""{"signedCert":"${b64(leaf)}","extra":"ignored"}""",
            "<response><signedCert>${b64(leaf)}</signedCert></response>").forEach {
            val parsed = TakEnrollment.parseSignResponse(it)
            assertEquals(leaf, parsed.signedCert)
            assertTrue(parsed.authorities.isEmpty())
            val ks = KeyStore.getInstance("PKCS12")
            parsed.install(ks, keys.last().private, "client", password)
            assertEquals(1, ks.getCertificateChain("client").size)
            assertEquals(1, ks.size())
        }
    }

    @Test fun absentCa1AndBlankOptionalAuthoritiesAreAccepted() {
        listOf("""{"signedCert":"${b64(chain[1])}","ca0":"${b64(chain[0])}","ca2":"","ca3":null}""",
            "<response><signedCert>${b64(chain[1])}</signedCert><ca0>${b64(chain[0])}</ca0><ca2> </ca2></response>").forEach {
            val parsed = TakEnrollment.parseSignResponse(it)
            assertEquals(listOf("ca0"), parsed.authorities.map { ca -> ca.first })
            val ks = KeyStore.getInstance("PKCS12")
            parsed.install(ks, keys[1].private, "client", password)
            assertEquals(2, ks.getCertificateChain("client").size)
            assertTrue(ks.isCertificateEntry("ca0"))
            assertFalse(ks.containsAlias("ca1"))
        }
    }

    @Test fun multipleAuthoritiesSortNumericallyAndInstallVariableChain() {
        val leaf = b64(chain[3])
        val entries = linkedMapOf("ca10" to b64(chain[0]), "ca2" to b64(chain[1]), "ca0" to b64(chain[2]))
        val json = """{"signedCert":"$leaf",${entries.entries.joinToString { "\"${it.key}\":\"${it.value}\"" }}}"""
        val xml = "<r:response xmlns:r=\"urn:tak\"><r:signedCert>$leaf</r:signedCert>" +
            entries.entries.joinToString("") { "<r:${it.key}>${it.value}</r:${it.key}>" } + "</r:response>"
        listOf(json, xml).forEach {
            val parsed = TakEnrollment.parseSignResponse(it)
            assertEquals(listOf("ca0", "ca2", "ca10"), parsed.authorities.map { ca -> ca.first })
            val ks = KeyStore.getInstance("PKCS12")
            parsed.install(ks, keys[3].private, "client", password)
            assertEquals(chain.reversed(), ks.getCertificateChain("client").toList())
            entries.keys.forEach { name -> assertTrue(ks.isCertificateEntry(name)) }
            assertEquals(4, ks.size())
        }
    }

    @Test fun trustAnchorsExcludeClientLeafAndPreserveSuppliedCaChain() {
        val leafOnly = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", keys[3].private, password, arrayOf(chain[3]))
        }
        assertTrue(TakEnrollment.selectTrustAnchors(leafOnly).isEmpty())

        val chainOnly = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", keys[3].private, password, chain.reversed().toTypedArray())
        }
        assertEquals(listOf(chain[0]), TakEnrollment.selectTrustAnchors(chainOnly))

        val withAuthorities = KeyStore.getInstance("PKCS12")
        TakSignedCertificates(
            chain[3],
            listOf("ca0" to chain[2], "ca1" to chain[1], "ca2" to chain[0])
        ).install(withAuthorities, keys[3].private, "client", password)
        val trustAnchors = TakEnrollment.selectTrustAnchors(withAuthorities)
        assertFalse(trustAnchors.contains(chain[3]))
        assertTrue(trustAnchors.containsAll(chain.take(3)))
    }

    @Test fun pemAndWhitespaceAreAccepted() {
        val pem = "-----BEGIN CERTIFICATE-----\n${b64(chain[0])}\n-----END CERTIFICATE-----"
        assertEquals(chain[0], TakEnrollment.parseSignResponse("<response><signedCert>$pem</signedCert></response>").signedCert)
    }

    @Test fun rejectsMalformedMissingLeafAndMalformedAuthority() {
        listOf("{}", """{"ca0":"${b64(chain[0])}"}""", """{"signedCert":null}""",
            """{"signedCert":42}""", """{"signedCert":" "}""", """{"signedCert":"bad!"}""",
            "<response/>", "<response><signedCert>bad!</signedCert></response>", "<broken",
            """{"signedCert":"${b64(chain[0])}","ca0":"bad!"}""",
            "<response><signedCert>${b64(chain[0])}</signedCert><ca0>bad!</ca0></response>").forEach {
            assertThrows(Exception::class.java) { TakEnrollment.parseSignResponse(it) }
        }
    }

    @Test fun configValidatesXmlAndAllowsEmptySubjectEntries() {
        assertEquals(listOf("O" to "Test & Team"), TakEnrollment.parseConfig(
            """<certificateConfig><nameEntries><nameEntry name="O" value="Test &amp; Team"/></nameEntries></certificateConfig>"""))
        assertTrue(TakEnrollment.parseConfig("<certificateConfig/>").isEmpty())
        listOf("", "<error/>", "<certificateConfig>", "<certificateConfig><nameEntry/></certificateConfig>").forEach {
            assertThrows(Exception::class.java) { TakEnrollment.parseConfig(it) }
        }
    }

    @Test fun xmlDeclarationsCannotResolveExternalOrInternalEntities() {
        val external = """<!DOCTYPE response [<!ENTITY leak SYSTEM "file:///nonexistent">]><response><signedCert>&leak;</signedCert></response>"""
        val internal = """<!DOCTYPE certificateConfig [<!ENTITY x "expanded">]><certificateConfig>&x;</certificateConfig>"""
        assertThrows(Exception::class.java) { TakEnrollment.parseSignResponse(external) }
        assertThrows(Exception::class.java) { TakEnrollment.parseConfig(internal) }
    }

    @Test fun configHttpFailureCannotGenerateCsrOrSign() {
        var csrCalls = 0
        var signCalls = 0
        listOf(401, 403, 500).forEach { code ->
            val error = assertThrows(TakEnrollmentException::class.java) {
                TakEnrollment.enroll(
                    config = { TakEnrollment.requireHttpSuccess(code, TakEnrollmentStage.CONFIG) },
                    csr = { csrCalls++; "csr" }, sign = { signCalls++; "signed" })
            }
            assertEquals(TakEnrollmentStage.CONFIG, error.failure.stage)
            assertEquals(code, error.failure.httpCode)
        }
        assertEquals(0, csrCalls)
        assertEquals(0, signCalls)
    }

    @Test fun missingBodyCannotGenerateCsrOrSign() {
        listOf(null, "", " ").forEach { body ->
            assertThrows(TakEnrollmentException::class.java) {
                TakEnrollment.enroll(
                    config = { TakEnrollment.requireBody(body, TakEnrollmentStage.CONFIG) },
                    csr = { fail("CSR must not run"); "csr" },
                    sign = { fail("Signing must not run"); "signed" })
            }
        }
    }

    @Test fun safeCategoriesPreserveStageAndNeverExposeExceptionText() {
        val auth = assertThrows(TakEnrollmentException::class.java) {
            TakEnrollment.requireHttpSuccess(401, TakEnrollmentStage.CONFIG)
        }.failure
        assertEquals(TakFailureCategory.AUTHENTICATION, auth.category)
        assertTrue(auth.displayMessage().contains("configuration"))
        val forbidden = assertThrows(TakEnrollmentException::class.java) {
            TakEnrollment.requireHttpSuccess(403, TakEnrollmentStage.SIGN)
        }.failure
        assertEquals(TakFailureCategory.HTTP, forbidden.category)
        assertFalse(forbidden.displayMessage().contains("username"))
        assertTrue(forbidden.displayMessage().contains("403"))
        val tls = TakConnectionFailure.from(TakEnrollmentStage.SIGN, SSLHandshakeException("secret"))
        assertEquals(TakFailureCategory.TLS, tls.category)
        val network = TakConnectionFailure.from(TakEnrollmentStage.CONNECTION, IOException("secret"))
        assertEquals(TakFailureCategory.CONNECTIVITY, network.category)
        val cert = TakConnectionFailure.from(TakEnrollmentStage.CERTIFICATE, IllegalArgumentException("secret"))
        assertEquals(TakFailureCategory.CERTIFICATE, cert.category)
        listOf(tls, network, cert).forEach { assertFalse(it.displayMessage().contains("secret")) }
        assertEquals(auth, TakConnectionFailure.from(TakEnrollmentStage.SIGN, TakEnrollmentException(auth)))
        assertTrue(network.displayMessage().startsWith("TAK connection:"))
    }

    companion object {
        private val password = "test-password".toCharArray()
        private val keys: List<KeyPair> = (0..3).map {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
        private val chain: List<X509Certificate> = keys.indices.map { i ->
            val name = X500Name("CN=Enrollment Test $i")
            val issuer = X500Name("CN=Enrollment Test ${maxOf(0, i - 1)}")
            val now = System.currentTimeMillis()
            JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(i + 1L), Date(now - 60_000),
                Date(now + 86_400_000), name, keys[i].public
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(i != 3))
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys[maxOf(0, i - 1)].private)))
        }
        private fun b64(cert: X509Certificate) = Base64.getEncoder().encodeToString(cert.encoded)
    }
}
