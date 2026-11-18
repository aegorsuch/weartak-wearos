package com.aitech.weartak_civ.market

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarketCatalogRepositoryTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun downloadsOnlyWhenHttpsAndMatchesCatalogDigest() = runBlocking {
        withTlsServer { server, client ->
            val bytes = "signed-release-bytes".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val plugin = plugin(server.url("/plugin.apk").toString(), sha256(bytes))
            val file = MarketCatalogRepository(MarketConfiguration("", ""), client)
                .downloadVerified(plugin, temp.root)

            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1, server.requestCount)
            file.delete()
            assertThrows(MarketCatalogException::class.java) {
                runBlocking {
                    MarketCatalogRepository(MarketConfiguration("", ""), client)
                        .downloadVerified(plugin("http://example.com/plugin.apk", sha256(bytes)), temp.root)
                }
            }
        }
    }

    @Test
    fun rejectsWrongDigestAndOversizedResponse() = runBlocking {
        withTlsServer { server, client ->
            server.enqueue(MockResponse().setBody("tampered"))
            val badDigest = plugin(server.url("/wrong.apk").toString(), "0".repeat(64))
            assertThrows(MarketCatalogException::class.java) {
                runBlocking {
                    MarketCatalogRepository(MarketConfiguration("", ""), client)
                        .downloadVerified(badDigest, temp.root)
                }
            }
            assertEquals(0, temp.root.listFiles()?.size ?: 0)

            server.enqueue(
                MockResponse().setHeader(
                    "Content-Length",
                    MarketCatalogRepository.MAX_APK_BYTES + 1,
                ),
            )
            val large = plugin(server.url("/large.apk").toString(), "0".repeat(64))
            assertThrows(MarketCatalogException::class.java) {
                runBlocking {
                    MarketCatalogRepository(MarketConfiguration("", ""), client)
                        .downloadVerified(large, temp.root)
                }
            }
            assertEquals(0, temp.root.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun followsHttpsReleaseRedirectsButNeverDowngradesToHttp() = runBlocking {
        withTlsServer { server, client ->
            val bytes = "redirected-release".toByteArray()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.apk"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val plugin = plugin(server.url("/release.apk").toString(), sha256(bytes))
            val file = MarketCatalogRepository(MarketConfiguration("", ""), client)
                .downloadVerified(plugin, temp.root)
            assertEquals(2, server.requestCount)
            file.delete()

            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://example.com/unsafe.apk"),
            )
            val downgrade = plugin(server.url("/downgrade.apk").toString(), "0".repeat(64))
            assertThrows(MarketCatalogException::class.java) {
                runBlocking {
                    MarketCatalogRepository(MarketConfiguration("", ""), client)
                        .downloadVerified(downgrade, temp.root)
                }
            }
            assertEquals(3, server.requestCount)
        }
    }

    private suspend fun withTlsServer(block: suspend (MockWebServer, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("kubernetes.docker.internal")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        try {
            block(server, client)
        } finally {
            server.shutdown()
        }
    }

    private fun plugin(url: String, digest: String) = MarketPlugin(
        id = "release",
        name = "Release",
        description = "Test release",
        publisher = "Example",
        publisherType = MarketPublisherType.COMMUNITY,
        packageName = "org.example.release",
        versionName = "1.0",
        versionCode = 1,
        minHostVersionCode = 1,
        maxHostVersionCode = null,
        apkUrl = url,
        apkSha256 = digest,
        signerSha256 = "a".repeat(64),
        sourceUrl = null,
        hostUpdate = false,
    )
}
