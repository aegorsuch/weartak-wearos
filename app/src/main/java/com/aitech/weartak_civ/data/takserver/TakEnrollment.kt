package com.aitech.weartak_civ.data.takserver

import android.content.Context
import com.aitech.weartak_civ.R
import com.fasterxml.jackson.databind.ObjectMapper
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.cert.CertPathValidatorException
import java.security.cert.PKIXReason
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.Base64
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

enum class TakEnrollmentStage(val label: String) {
    CONFIG("Certificate configuration"), CSR("Certificate request"),
    SIGN("Certificate signing"), CERTIFICATE("Client certificate"), CONNECTION("TAK connection")
}

enum class TakFailureCategory { AUTHENTICATION, HTTP, TLS, CONNECTIVITY, CERTIFICATE }

data class TakConnectionFailure(
    val stage: TakEnrollmentStage,
    val category: TakFailureCategory,
    val httpCode: Int? = null,
    val untrustedServerCa: Boolean = false,
) {
    fun displayMessage(context: Context): String {
        val stageResource = when (stage) {
            TakEnrollmentStage.CONFIG -> R.string.tak_enrollment_stage_config
            TakEnrollmentStage.CSR -> R.string.tak_enrollment_stage_csr
            TakEnrollmentStage.SIGN -> R.string.tak_enrollment_stage_sign
            TakEnrollmentStage.CERTIFICATE -> R.string.tak_enrollment_stage_certificate
            TakEnrollmentStage.CONNECTION -> R.string.tak_enrollment_stage_connection
        }
        val detail = when (category) {
            TakFailureCategory.AUTHENTICATION -> context.getString(R.string.tak_enrollment_authentication_failed)
            TakFailureCategory.HTTP -> context.getString(R.string.tak_enrollment_http_failed, httpCode)
            TakFailureCategory.TLS -> context.getString(
                if (untrustedServerCa) R.string.ca_trust_untrusted else R.string.tak_enrollment_tls_failed)
            TakFailureCategory.CONNECTIVITY -> context.getString(R.string.tak_enrollment_connectivity_failed)
            TakFailureCategory.CERTIFICATE -> context.getString(R.string.tak_enrollment_certificate_failed)
        }
        return context.getString(R.string.tak_enrollment_failure, context.getString(stageResource), detail)
    }

    fun displayMessage(): String = "${stage.label}: " + when (category) {
        TakFailureCategory.AUTHENTICATION -> "authentication rejected (HTTP 401). Check username/password."
        TakFailureCategory.HTTP -> "server rejected request (HTTP $httpCode)."
        TakFailureCategory.TLS -> if (untrustedServerCa) "server certificate issuer is not trusted."
            else "secure connection failed. Check server certificate and hostname."
        TakFailureCategory.CONNECTIVITY -> "server unreachable. Check network and server address."
        TakFailureCategory.CERTIFICATE -> "invalid or missing certificate data."
    }

    companion object {
        fun from(stage: TakEnrollmentStage, error: Exception): TakConnectionFailure {
            if (error is TakEnrollmentException) return error.failure
            val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
            val category = when {
                causes.any { it is SSLException } -> TakFailureCategory.TLS
                error is IOException -> TakFailureCategory.CONNECTIVITY
                else -> TakFailureCategory.CERTIFICATE
            }
            val untrustedCa = category == TakFailureCategory.TLS &&
                causes.none {
                    it is CertificateExpiredException || it is CertificateNotYetValidException ||
                        it is javax.net.ssl.SSLPeerUnverifiedException
                } && causes.any {
                    (it is CertPathValidatorException &&
                        (it.reason == PKIXReason.NO_TRUST_ANCHOR ||
                            it.message == "Trust anchor for certification path not found.")) ||
                        it.javaClass.name == "sun.security.provider.certpath.SunCertPathBuilderException"
                }
            return TakConnectionFailure(stage, category, untrustedServerCa = untrustedCa)
        }
    }
}

class TakEnrollmentException(val failure: TakConnectionFailure) : Exception(failure.displayMessage())

data class TakSignedCertificates(
    val signedCert: X509Certificate,
    val authorities: List<Pair<String, X509Certificate>>
) {
    fun install(keyStore: KeyStore, privateKey: PrivateKey, alias: String, password: CharArray) {
        keyStore.load(null, null)
        authorities.forEach { (name, cert) -> keyStore.setCertificateEntry(name, cert) }
        val chain = (listOf<Certificate>(signedCert) + authorities.map { it.second }).toTypedArray()
        keyStore.setKeyEntry(alias, privateKey, password, chain)
    }
}

/** Enrollment transport retains the platform's HTTPS trust and hostname checks. */
object TakEnrollment {
    private val authorityName = Regex("ca[0-9]*")

    fun selectTrustAnchors(keyStore: KeyStore): List<X509Certificate> {
        val aliases = keyStore.aliases().toList()
        val keyAlias = aliases.firstOrNull { keyStore.isKeyEntry(it) }
        val leaf = keyAlias?.let { keyStore.getCertificate(it) as? X509Certificate }
        val chain = keyAlias?.let { keyStore.getCertificateChain(it) }
            ?.filterIsInstance<X509Certificate>()
            .orEmpty()
        val chainAuthorities = chain.filter { it != leaf && it.basicConstraints >= 0 }
        val suppliedAuthorities = aliases.asSequence()
            .filter { keyStore.isCertificateEntry(it) }
            .mapNotNull { keyStore.getCertificate(it) as? X509Certificate }
            .filter { it != leaf && it.basicConstraints >= 0 }
            .toList()

        val chainRoot = chainAuthorities.firstOrNull(::isSelfSigned)
            ?: chainAuthorities.firstOrNull { certificate ->
                chainAuthorities.none {
                    it !== certificate &&
                        it.subjectX500Principal == certificate.issuerX500Principal
                }
            }
        return (suppliedAuthorities + listOfNotNull(chainRoot)).distinct()
    }

    private fun isSelfSigned(certificate: X509Certificate): Boolean {
        if (certificate.subjectX500Principal != certificate.issuerX500Principal) return false
        return try {
            certificate.verify(certificate.publicKey)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    fun requireHttpSuccess(code: Int, stage: TakEnrollmentStage) {
        if (code !in 200..299) throw TakEnrollmentException(
            TakConnectionFailure(stage,
                if (code == 401) TakFailureCategory.AUTHENTICATION else TakFailureCategory.HTTP, code)
        )
    }

    fun requireBody(body: String?, stage: TakEnrollmentStage): String =
        body?.takeIf { it.isNotBlank() } ?: throw TakEnrollmentException(
            TakConnectionFailure(stage, TakFailureCategory.CERTIFICATE)
        )

    fun parseXml(xml: String): Document {
        // Reject declarations even on Android parsers lacking optional JAXP features.
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false
            ).forEach { (name, value) ->
                try { setFeature(name, value) } catch (_: javax.xml.parsers.ParserConfigurationException) {
                    // Android implementations differ; declaration rejection and resolver remain mandatory.
                }
            }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities prohibited") }
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
        }.parse(InputSource(StringReader(xml)))
    }

    fun parseConfig(body: String): List<Pair<String, String>> {
        val document = parseXml(body)
        require(document.documentElement.localName == "certificateConfig")
        val entries = document.getElementsByTagNameNS("*", "nameEntry")
        return (0 until entries.length).map {
            val entry = entries.item(it) as Element
            require(entry.hasAttribute("name") && entry.getAttribute("name").isNotBlank() &&
                entry.hasAttribute("value"))
            entry.getAttribute("name") to entry.getAttribute("value")
        }
    }

    fun parseSignResponse(body: String): TakSignedCertificates {
        val trimmed = body.trimStart { it.isWhitespace() || it == '\uFEFF' }
        val values = if (trimmed.startsWith("{")) {
            val node = ObjectMapper().readTree(trimmed)
            require(node.isObject)
            node.fields().asSequence().associate { (name, value) ->
                if (name == "signedCert" || name.matches(authorityName)) {
                    require(value.isTextual || value.isNull)
                }
                name to if (value.isTextual) value.asText() else ""
            }
        } else {
            val document = parseXml(trimmed)
            val elements = document.getElementsByTagName("*")
            val result = mutableMapOf<String, String>()
            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                val name = element.localName
                if (name == "signedCert" || name.matches(authorityName)) {
                    require(name !in result)
                    result[name] = element.textContent
                }
            }
            result
        }
        val leaf = values["signedCert"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing signed certificate")
        val authorities = values.keys.filter { it.matches(authorityName) }
            .sortedWith(compareBy<String> { it != "ca" }
                .thenBy { BigInteger(it.substring(2).ifEmpty { "0" }) }.thenBy { it })
            .mapNotNull { name -> values[name]?.takeIf { it.isNotBlank() }?.let { name to parseCert(it) } }
        return TakSignedCertificates(parseCert(leaf), authorities)
    }

    private fun parseCert(text: String): X509Certificate {
        val normalized = text.replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "").filterNot { it.isWhitespace() }
        val bytes = Base64.getDecoder().decode(normalized)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    fun <C, R, S> enroll(config: () -> C, csr: (C) -> R, sign: (R) -> S): S =
        sign(csr(config()))
}
