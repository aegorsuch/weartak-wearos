package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.data.takserver.TakHttpSession
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.net.ssl.SSLContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PluginTakHttpClientTest {
    private val request = PluginHttpRequest(null, "GET",
        "${C.REST_PREFIX}tak.server.plugins.TakCadServerPlugin/submit",
        "fn=getIncidents", null, "application/json")
    private fun endpoint(client: OkHttpClient, session: TakHttpSession = TakHttpSession { true }) =
        PluginTakHttpClient(PluginTakServer("server", "Server"), "localhost", session, client)
    private fun execute(endpoint: PluginTakHttpClient) = endpoint.execute(request) { _, block -> block() }

    @Test fun constructsOnlyConfiguredHttpsAuthorityAndPreservesCanonicalQuery() {
        val client = endpoint(OkHttpClient())
        val url = client.request(request.copy(query = "fn=getIncidents&next=https%3A%2F%2Fevil.invalid")).url
        assertEquals("https", url.scheme)
        assertEquals("localhost", url.host)
        assertEquals(8443, url.port)
        assertEquals(request.path, url.encodedPath)
        assertEquals("getIncidents", url.queryParameter("fn"))
    }

    @Test fun nonSuccessHttpStatusAndBoundedBodyReturnedWithoutRedirectFollow() {
        val tls = HandshakeCertificates.Builder().build()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tls.trustManager), null) }
        val endpoint = PluginTakHttpClient(PluginTakServer("server", "Server"), "localhost",
            TakHttpSession { true }, ssl, tls.trustManager)
        assertFalse(endpoint.client.followRedirects)
        assertFalse(endpoint.client.followSslRedirects)
        assertFalse(endpoint.client.retryOnConnectionFailure)
        assertSame(OkHttpClient().hostnameVerifier, endpoint.client.hostnameVerifier)
        val body = "x".repeat(C.MAX_RESPONSE_BYTES)
        val client = endpoint(endpoint.client.newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(302).message("Found").header("Location", "https://evil.invalid")
                .body(body.toResponseBody()).build()
        }.build())
        val result = execute(client)
        assertEquals(C.OK, result.getString(C.KEY_STATUS))
        assertEquals(302, result.getInt(C.KEY_HTTP_STATUS))
        assertEquals(body, result.getString(C.KEY_BODY))
    }

    @Test fun oversizedResponseFailsRatherThanReturningTruncatedSuccess() {
        val client = endpoint(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("x".repeat(C.MAX_RESPONSE_BYTES + 1).toResponseBody()).build()
        }.build())
        assertThrows(PluginResponseTooLarge::class.java) { execute(client) }
    }

    @Test fun disconnectedSessionNeverExecutesAndDisconnectDuringRequestDiscardsResult() {
        var allowed = false
        var hits = 0
        val session = TakHttpSession { allowed }
        val client = endpoint(OkHttpClient.Builder().addInterceptor { chain ->
            hits++
            allowed = false
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("old".toResponseBody()).build()
        }.build(), session)
        assertThrows(IOException::class.java) { execute(client) }
        assertEquals(0, hits)
        allowed = true
        assertThrows(IOException::class.java) { execute(client) }
        assertEquals(1, hits)
    }

    @Test fun realMutualTlsUsesHostKeyAndRejectsWrongHostnameAndUntrustedServer() {
        val ca = HeldCertificate.Builder().certificateAuthority(1).commonName("Test CA").build()
        val serverCertificate = HeldCertificate.Builder().commonName("Server")
            .addSubjectAlternativeName("localhost").signedBy(ca).build()
        val clientCertificate = HeldCertificate.Builder().commonName("Watch").signedBy(ca).build()
        val serverTls = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate, ca.certificate).addTrustedCertificate(ca.certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .heldCertificate(clientCertificate, ca.certificate).addTrustedCertificate(ca.certificate).build()
        val ssl = SSLContext.getInstance("TLS").apply {
            init(arrayOf(clientTls.keyManager), arrayOf(clientTls.trustManager), null)
        }
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.requireClientAuth()
            server.enqueue(MockResponse().setBody("incidents"))
            server.start()
            val hostClient = PluginTakHttpClient(PluginTakServer("server", "Server"),
                "localhost", TakHttpSession { true }, ssl, clientTls.trustManager).client
            // Test TLS against an ephemeral port; production URL builder remains fixed to 8443.
            val url = server.url("/").newBuilder().host("localhost").build()
            hostClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
                assertEquals("incidents", it.body!!.string())
            }
            assertEquals(clientCertificate.certificate, server.takeRequest().handshake!!.peerCertificates.first())
            val wrongName = server.url("/").newBuilder().host("127.0.0.1").build()
            assertThrows(IOException::class.java) {
                hostClient.newCall(okhttp3.Request.Builder().url(wrongName).build()).execute().close()
            }
            val unknownCa = HandshakeCertificates.Builder().build()
            val unknownSsl = SSLContext.getInstance("TLS").apply {
                init(arrayOf(clientTls.keyManager), arrayOf(unknownCa.trustManager), null)
            }
            val untrusted = PluginTakHttpClient(PluginTakServer("server", "Server"),
                "localhost", TakHttpSession { true }, unknownSsl, unknownCa.trustManager)
            assertThrows(IOException::class.java) {
                untrusted.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
            }
        }
    }
}
