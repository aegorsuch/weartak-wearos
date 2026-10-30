package com.aitech.weartak_civ.data.takserver

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** Explicit, local trust for one configured endpoint. Never changes platform-wide trust. */
object TakServerCaTrust {
    const val MAX_BYTES = 64 * 1024

    fun readCandidate(directory: File): X509Certificate {
        val file = File(directory, "trust/ca.crt")
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

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    fun trustManager(encoded: String): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("approved-ca", decode(encoded))
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    fun configureHostnameVerification(socket: SSLSocket) {
        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }

    fun enrollmentClient(encoded: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (encoded != null) {
            val trustManager = trustManager(encoded)
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
