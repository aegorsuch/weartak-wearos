package com.aitech.weartak_civ.data.takserver

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier

/** Explicit, local trust for one configured endpoint. Never changes platform-wide trust. */
object TakServerCaTrust {
    const val MAX_BYTES = 64 * 1024

    /** Review was requested without a staged file; distinct from an invalid staged file. */
    class NoStagedCaException : IllegalStateException("No CA file staged at trust/ca.crt")

    fun readCandidate(directory: File): X509Certificate {
        val file = File(directory, "trust/ca.crt")
        if (!file.exists()) throw NoStagedCaException()
        require(file.isFile) { "Stage trust/ca.crt first" }
        val bytes = file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): X509Certificate {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "CA file must be 1-65536 bytes" }
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
        require(certificates.size == 1) { "Import exactly one PEM or DER CA certificate" }
        val certificate = certificates.single() as X509Certificate
        val text = bytes.toString(Charsets.US_ASCII).trim()
        if (text.startsWith("-----BEGIN")) {
            require(Regex("-----BEGIN CERTIFICATE-----\\s+[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----")
                .matches(text)) { "Import only a public CA certificate, without keys or extra data" }
        } else {
            require(bytes.contentEquals(certificate.encoded)) { "DER contains extra data" }
        }
        certificate.checkValidity()
        require(certificate.basicConstraints >= 0) { "Certificate is not a CA" }
        require(certificate.keyUsage?.getOrNull(5) != false) { "CA cannot sign certificates" }
        return certificate
    }

    fun encode(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(certificate.encoded)

    fun decode(encoded: String): X509Certificate {
        require(encoded.length <= MAX_BYTES * 2) { "CA certificate is too large" }
        return parse(Base64.getDecoder().decode(encoded))
    }

    fun encodeAll(certificates: List<X509Certificate>): String {
        require(certificates.size in 1..4)
        return certificates.distinctBy(::fingerprint).joinToString(".") { encode(it) }
    }

    fun decodeAll(encoded: String): List<X509Certificate> {
        require(encoded.length <= MAX_BYTES * 2)
        val parts = encoded.split('.')
        require(parts.size in 1..4)
        return parts.map(::decode).also { require(it.distinctBy(::fingerprint).size == it.size) }
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    fun trustManager(encoded: String): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            decodeAll(encoded).forEachIndexed { index, ca -> setCertificateEntry("approved-ca-$index", ca) }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    fun platformTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()

    /**
     * Streaming/REST trust for one server: enrolled TAK root, platform (public) roots and an
     * optional approved CA are additive; nothing is trust-all. Chains validated by the enrolled
     * root without an approved CA keep legacy behavior (TAK certificates often lack IP SANs);
     * every other accepted chain must also match [host]. Expiry is enforced by each delegate.
     */
    fun streamingTrustManager(
        host: String,
        enrolled: X509TrustManager?,
        approvedCa: String?,
        platform: X509TrustManager = platformTrustManager(),
        allowEnrolledTrust: Boolean = true,
    ): X509TrustManager {
        val approved = approvedCa?.let(::trustManager)
        val legacyEnrolled = enrolled.takeIf { allowEnrolledTrust }
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                listOfNotNull(legacyEnrolled, approved, platform).flatMap { it.acceptedIssuers.asList() }
                    .distinct().toTypedArray()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                (enrolled ?: platform).checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Server certificate chain is empty")
                var failure: CertificateException? = null
                fun attempt(manager: X509TrustManager?, verifyHost: Boolean): Boolean {
                    if (manager == null) return false
                    try {
                        manager.checkServerTrusted(chain, authType)
                    } catch (error: CertificateException) {
                        failure?.addSuppressed(error) ?: run { failure = error }
                        return false
                    }
                    if (verifyHost && !OkHostnameVerifier.verify(host, chain[0])) {
                        throw CertificateException("Server certificate does not match $host")
                    }
                    return true
                }
                if (attempt(legacyEnrolled, verifyHost = approved != null)) return
                if (attempt(approved, verifyHost = true)) return
                if (attempt(platform, verifyHost = true)) return
                throw failure ?: CertificateException("Server certificate is not trusted")
            }
        }
    }

    fun configureHostnameVerification(socket: SSLSocket) {
        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }

    fun enrollmentClient(encoded: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (encoded != null) {
            val platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val approved = trustManager(encoded)
            val trustManager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    (platform.acceptedIssuers + approved.acceptedIssuers).distinct().toTypedArray()

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    platform.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        platform.checkServerTrusted(chain, authType)
                    } catch (platformFailure: CertificateException) {
                        try {
                            approved.checkServerTrusted(chain, authType)
                        } catch (approvedFailure: CertificateException) {
                            platformFailure.addSuppressed(approvedFailure)
                            throw platformFailure
                        }
                    }
                }
            }
            val tls = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            builder.sslSocketFactory(tls.socketFactory, trustManager)
                .followRedirects(false)
                .followSslRedirects(false)
        }
        return builder.build()
    }
}
