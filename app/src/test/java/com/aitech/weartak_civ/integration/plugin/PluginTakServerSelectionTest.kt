package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakHttpSession
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.TAKConnectService as Tak
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.net.ssl.SSLContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginTakServerSelectionTest {
    @Before fun setup() {
        MainActivity.dataRepository = mockk<DataRepository>(relaxed = true)
        every { MainActivity.dataRepository.shouldSuppressWatchTransportNow() } returns false
        Tak.takEndpointEnabled = true
        Tak.serviceStopping = false
        Tak.takServerDetails = mutableListOf()
    }

    @After fun cleanup() { Tak.takServerDetails = mutableListOf() }

    private fun server(id: String, connected: Boolean = true) = Tak.TakServerDetail(
        serverId = id, isEnabled = true, name = "Server $id", address = "$id.example.invalid",
        isP12Cert = true, username = "", password = "", p12Cert = null, p12CertPassword = null,
    ).apply {
        connectionStatus.set(if (connected) Tak.TAK_SERVER_CONNECTED_COT_PORT else Tak.TAK_SERVER_DISCONNECTED)
        channelSslContext = SSLContext.getDefault()
        channelTrustManager = OkHttpClient().x509TrustManager
        runtime.currentAttempt = Tak.ConnectionAttempt(1).apply { httpSession = TakHttpSession { true } }
    }

    @Test fun noServerAndMultipleServersFailClosedWithoutInferringOne() {
        assertTrue(Tak.pluginRestServers().isEmpty())
        assertThrows(IllegalStateException::class.java) { Tak.pluginRestClient(null) }
        val one = server("one")
        val two = server("two")
        Tak.takServerDetails = mutableListOf(one, two, server("offline", false))
        assertEquals(listOf("one", "two"), Tak.pluginRestServers().map { it.id })
        assertThrows(IllegalStateException::class.java) { Tak.pluginRestClient(null) }
        assertThrows(IllegalStateException::class.java) { Tak.pluginRestClient("offline") }
        assertThrows(IllegalStateException::class.java) { Tak.pluginRestClient("evil.invalid") }
        val selected = Tak.pluginRestClient("two")
        assertEquals("two", selected.server.id)
        assertEquals("two.example.invalid", selected.request(PluginHttpRequest(null, "GET",
            "/Marti/api/plugins/test", null, null, "application/json")).url.host)
        one.isEnabled = false
        assertEquals("two", Tak.pluginRestClient(null).server.id)
    }

    @Test fun missingTlsAndRevokedSessionAreNeverReplacedWithUnauthenticatedClient() {
        val server = server("one")
        Tak.takServerDetails = mutableListOf(server)
        server.channelSslContext = null
        assertThrows(IllegalStateException::class.java) { Tak.pluginRestClient(null) }
        server.channelSslContext = SSLContext.getDefault()
        server.channelTrustManager = null
        assertThrows(IllegalStateException::class.java) { Tak.pluginRestClient(null) }
        server.channelTrustManager = OkHttpClient().x509TrustManager
        server.runtime.currentAttempt!!.httpSession!!.cancel()
        assertThrows(java.io.IOException::class.java) { Tak.pluginRestClient(null) }
    }
}
