package com.aitech.weartak_civ.data.takserver

import java.io.File
import java.math.BigInteger
import java.net.SocketException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import okhttp3.Request
import okhttp3.OkHttpClient
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakServerCaTrustTest {
    @get:Rule val temporary = TemporaryFolder()
    private var androidProviders = emptyList<Pair<Provider, Int>>()

    @Before fun isolateJvmTlsProviders() {
        // Robolectric leaves Android Conscrypt installed in this shared JVM.
        androidProviders = Security.getProviders().mapIndexedNotNull { index, provider ->
            if (provider.javaClass.name.startsWith("org.conscrypt.")) provider to index + 1 else null
        }
        androidProviders.forEach { (provider, _) -> Security.removeProvider(provider.name) }
    }

    @After fun restoreAndroidProviders() {
        androidProviders.forEach { (provider, position) -> Security.insertProviderAt(provider, position) }
    }

    @Test fun pemAndDerRoundTripAndDisplayFullSha256Fingerprint() {
        val ca = certificate(caKey, true)
        assertArrayEquals(ca.encoded, TakServerCaTrust.parse(ca.encoded).encoded)
        assertArrayEquals(ca.encoded, TakServerCaTrust.parse(pem(ca)).encoded)
        assertArrayEquals(ca.encoded, TakServerCaTrust.decode(TakServerCaTrust.encode(ca)).encoded)
        assertTrue(TakServerCaTrust.fingerprint(ca).matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}")))
    }

    @Test fun invalidLeafExpiredFutureBundleAndOversizedInputsFailClosed() {
        val now = System.currentTimeMillis()
        val ca = certificate(caKey, true)
        listOf(
            byteArrayOf(),
            "not a certificate".toByteArray(),
            ByteArray(TakServerCaTrust.MAX_BYTES + 1),
            certificate(serverKey, false).encoded,
            certificate(caKey, true, start = now - 120_000, end = now - 60_000).encoded,
            certificate(caKey, true, start = now + 60_000, end = now + 120_000).encoded,
            pem(ca) + pem(ca),
            pem(ca) + "\n-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----".toByteArray(),
            ca.encoded + byteArrayOf(0),
        ).forEach { input ->
            assertThrows("Accepted invalid CA (${input.size} bytes)", Exception::class.java) {
                TakServerCaTrust.parse(input)
            }
        }
    }

    @Test fun stagingDoesNotActivateTrustAndApprovedCopyIsIndependentOfLaterFileChanges() {
        val ca = certificate(caKey, true)
        val file = File(temporary.root, "trust/ca.crt").apply {
            parentFile.mkdirs()
            writeBytes(pem(ca))
        }
        val approved = TakServerCaTrust.encode(TakServerCaTrust.readCandidate(temporary.root))
        file.writeText("replaced")
        assertArrayEquals(ca.encoded, TakServerCaTrust.decode(approved).encoded)
        assertThrows(Exception::class.java) { TakServerCaTrust.readCandidate(temporary.root) }
        assertThrows(Exception::class.java) { TakServerCaTrust.readCandidate(temporary.newFolder()) }
        assertTrue(TakServerCaTrust.enrollmentClient(null).followRedirects)
        assertFalse(TakServerCaTrust.enrollmentClient(approved).followSslRedirects)
    }

    @Test fun onlyApprovedCaValidatesServerChain() {
        val ca = certificate(caKey, true)
        val leaf = certificate(serverKey, false)
        val manager = TakServerCaTrust.trustManager(TakServerCaTrust.encode(ca))
        manager.checkServerTrusted(arrayOf(leaf, ca), "RSA")
        val otherKey = keys()
        val otherCa = certificate(otherKey, true, issuerKey = otherKey)
        val otherLeaf = certificate(serverKey, false, issuerKey = otherKey)
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(otherLeaf, otherCa), "RSA")
        }
    }

    @Test fun enrollmentTlsAcceptsApprovedCaWithMatchingIpAndRejectsWrongHostnameOrNoApproval() {
        val ca = certificate(caKey, true)
        val approved = TakServerCaTrust.encode(ca)
        serve(ca) { port ->
            TakServerCaTrust.enrollmentClient(approved).newCall(
                Request.Builder().url("https://127.0.0.1:$port/").build()
            ).execute().use { assertEquals(200, it.code) }
        }
        serve(ca, expectTlsRejection = true) { port ->
            val error = assertThrows(SSLException::class.java) {
                TakServerCaTrust.enrollmentClient(approved).newCall(
                    Request.Builder().url("https://localhost:$port/").build()
                ).execute().close()
            }
            assertFalse(TakConnectionFailure.from(TakEnrollmentStage.CONNECTION, error).untrustedServerCa)
        }
        serve(ca, expectTlsRejection = true) { port ->
            val error = assertThrows(SSLException::class.java) {
                TakServerCaTrust.enrollmentClient(null).newCall(
                    Request.Builder().url("https://127.0.0.1:$port/").build()
                ).execute().close()
            }
            assertTrue(TakConnectionFailure.from(TakEnrollmentStage.CONFIG, error).untrustedServerCa)
        }
    }

    @Test fun defaultEnrollmentAcceptsPlatformChainWithoutStagedOrApprovedCa() {
        val publicKey = keys()
        val publicCa = certificate(publicKey, true, issuerKey = publicKey)
        withPlatformCa(publicCa) {
            assertFalse(File(temporary.root, "trust/ca.crt").exists())
            val client = TakServerCaTrust.enrollmentClient(null)
            serve(publicCa, issuerKey = publicKey) { port ->
                client.newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                    .execute().use { assertEquals(200, it.code) }
            }
            serve(publicCa, expectTlsRejection = true, issuerKey = publicKey) { port ->
                assertThrows(SSLException::class.java) {
                    client.newCall(Request.Builder().url("https://localhost:$port/").build())
                        .execute().close()
                }
            }
        }
    }

    @Test fun approvedEnrollmentAcceptsBothPlatformAndApprovedChainsWithoutChangingOtherServers() {
        val publicKey = keys()
        val publicCa = certificate(publicKey, true, issuerKey = publicKey)
        val privateCa = certificate()
        withPlatformCa(publicCa) {
            val approved = TakServerCaTrust.enrollmentClient(TakServerCaTrust.encode(privateCa))
            val otherServer = TakServerCaTrust.enrollmentClient(null)
            val otherApprovedServer = TakServerCaTrust.enrollmentClient(TakServerCaTrust.encode(publicCa))
            serve(publicCa, issuerKey = publicKey) { port ->
                approved.newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                    .execute().use { assertEquals(200, it.code) }
            }
            serve(privateCa) { port ->
                approved.newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                    .execute().use { assertEquals(200, it.code) }
            }
            serve(publicCa, issuerKey = publicKey) { port ->
                otherServer.newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                    .execute().use { assertEquals(200, it.code) }
            }
            serve(privateCa, expectTlsRejection = true) { port ->
                assertThrows(SSLException::class.java) {
                    otherServer.newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                        .execute().close()
                }
            }
            serve(privateCa, expectTlsRejection = true) { port ->
                assertThrows(SSLException::class.java) {
                    otherApprovedServer.newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                        .execute().close()
                }
            }
            serve(publicCa, expectTlsRejection = true, issuerKey = publicKey) { port ->
                assertThrows(SSLException::class.java) {
                    approved.newCall(Request.Builder().url("https://localhost:$port/").build())
                        .execute().close()
                }
            }
        }
    }

    @Test fun stagingPrivateCaDoesNotAddTrustToDefaultEnrollment() {
        val publicKey = keys()
        val publicCa = certificate(publicKey, true, issuerKey = publicKey)
        val privateCa = certificate()
        File(temporary.root, "trust/ca.crt").apply {
            parentFile.mkdirs()
            writeBytes(pem(privateCa))
        }
        withPlatformCa(publicCa) {
            serve(publicCa, issuerKey = publicKey) { port ->
                TakServerCaTrust.enrollmentClient(null)
                    .newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                    .execute().use { assertEquals(200, it.code) }
            }
            serve(privateCa, expectTlsRejection = true) { port ->
                assertThrows(SSLException::class.java) {
                    TakServerCaTrust.enrollmentClient(null)
                        .newCall(Request.Builder().url("https://127.0.0.1:$port/").build())
                        .execute().close()
                }
            }
        }
    }

    private fun withPlatformCa(ca: X509Certificate, action: () -> Unit) {
        val trustStoreFile = temporary.newFile("platform.jks")
        KeyStore.getInstance("JKS").apply {
            load(null, null)
            setCertificateEntry("platform-test-ca", ca)
            trustStoreFile.outputStream().use { store(it, "test".toCharArray()) }
        }
        val properties = mapOf(
            "javax.net.ssl.trustStore" to trustStoreFile.absolutePath,
            "javax.net.ssl.trustStoreType" to "JKS",
            "javax.net.ssl.trustStorePassword" to "test",
        )
        val previous = properties.mapValues { (key, _) -> System.getProperty(key) }
        try {
            properties.forEach { (key, value) -> System.setProperty(key, value) }
            action()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    @Test fun streamingHttpsEndpointIdentificationAcceptsMatchingIpAndRejectsWrongHostname() {
        val ca = certificate(caKey, true)
        val manager = TakServerCaTrust.trustManager(TakServerCaTrust.encode(ca))
        val tls = SSLContext.getInstance("TLS", "SunJSSE").apply { init(null, arrayOf(manager), null) }
        serve(ca) { port ->
            (tls.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                TakServerCaTrust.configureHostnameVerification(socket)
                socket.startHandshake()
            }
        }
        serve(ca, expectTlsRejection = true) { port ->
            (tls.socketFactory.createSocket("localhost", port) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                TakServerCaTrust.configureHostnameVerification(socket)
                assertThrows(SSLException::class.java) { socket.startHandshake() }
            }
        }

    }

    @Test fun approvedTrustUsesStrictHostnameChecksAndNoRedirectsForAllRestClients() {
        val ca = certificate(caKey, true)
        val manager = TakServerCaTrust.trustManager(TakServerCaTrust.encode(ca))
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val strict = OkHttpClient().hostnameVerifier
        val mission = TakMissionApi("127.0.0.1", tls, manager, "test", "Test",
            strictHostname = true)
        val channel = TakChannelApi("127.0.0.1", tls, manager, "test", "Test", strict,
            strictEndpoint = true)
        val packageClient = TakDataPackageClient("127.0.0.1", tls, manager, strict).client
        val clients = listOf(mission, channel).map { api ->
            api.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(api) as OkHttpClient
        } + packageClient
        clients.forEach { client ->
            assertSame(strict, client.hostnameVerifier)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertSame(manager, client.x509TrustManager)
        }
    }

    private fun serve(
        ca: X509Certificate,
        expectTlsRejection: Boolean = false,
        issuerKey: KeyPair = caKey,
        action: (Int) -> Unit,
    ) {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", serverKey.private, "test".toCharArray(),
                arrayOf(certificate(serverKey, false, issuerKey = issuerKey), ca))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test".toCharArray())
        }
        val tls = SSLContext.getInstance("TLS", "SunJSSE").apply { init(keys.keyManagers, null, null) }
        val executor = Executors.newSingleThreadExecutor()
        (tls.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { listener ->
            listener.soTimeout = 5_000
            val serving = executor.submit {
                var handshakeCompleted = false
                try {
                    (listener.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5_000
                        socket.startHandshake()
                        handshakeCompleted = true
                        socket.inputStream.bufferedReader().let { reader ->
                            if (reader.readLine() == null) return@use
                            while (!reader.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                        }
                        socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.outputStream.flush()
                        socket.inputStream.read()
                    }
                } catch (error: SSLException) {
                    // Negative tests intentionally abort TLS before an HTTP request.
                    if (!expectTlsRejection) throw error
                } catch (error: SocketException) {
                    // A client rejecting the peer can close before the server sends its TLS alert.
                    if (!expectTlsRejection || handshakeCompleted) throw error
                }
            }
            try {
                action(listener.localPort)
            } finally {
                try {
                    serving.get(10, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    companion object {
        private fun keys() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val caKey = keys()
        private val serverKey = keys()

        fun certificate(
            key: KeyPair = caKey,
            ca: Boolean = true,
            issuerKey: KeyPair = caKey,
            start: Long = System.currentTimeMillis() - 60_000,
            end: Long = System.currentTimeMillis() + 86_400_000,
        ): X509Certificate {
            val builder = JcaX509v3CertificateBuilder(
                X500Name("CN=Test CA"), BigInteger.valueOf(System.nanoTime()),
                Date(start), Date(end), X500Name(if (ca) "CN=Test CA" else "CN=Test Server"), key.public
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
                .addExtension(Extension.keyUsage, true,
                    KeyUsage(if (ca) KeyUsage.keyCertSign else KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            if (!ca) builder.addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.iPAddress, "127.0.0.1")))
            return JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.private)))
        }

        fun pem(certificate: X509Certificate): ByteArray =
            ("-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(certificate.encoded) +
                "\n-----END CERTIFICATE-----\n").toByteArray()
    }
}
