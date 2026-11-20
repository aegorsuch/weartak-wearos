package com.aitech.weartak_civ.data.takgov

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TakGovDeviceAuthorizationRepositoryTest {
    @Rule
    @JvmField
    val server = MockWebServer()

    @Test
    fun requestsDeviceCodeAndPollsForRefreshToken() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"device-code","user_code":"ABCD-EFGH","verification_uri":"https://tak.gov/register-device","expires_in":600,"interval":5}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"ephemeral-access-token","refresh_token":"saved-refresh-token"}"""
            )
        )
        val repository = repository()

        val authorization = repository.requestDeviceAuthorization()
        assertEquals("device-code", authorization.deviceCode)
        assertEquals("ABCD-EFGH", authorization.userCode)
        assertEquals(600L, authorization.expiresInSeconds)
        assertEquals(5L, authorization.intervalSeconds)
        val deviceRequest = server.takeRequest()
        assertEquals("/device", deviceRequest.path)
        assertEquals("POST", deviceRequest.method)
        val deviceRequestBody = deviceRequest.body.readUtf8()
        assertTrue(deviceRequestBody.contains("client_id=tak-gov-eud"))
        assertTrue(deviceRequestBody.contains("scope=openid%20offline_access%20email%20profile"))

        assertEquals(
            TakGovDeviceAuthorizationPoll.Pending,
            repository.pollForAuthorization("device-code"),
        )
        assertEquals(
            TakGovDeviceAuthorizationPoll.Approved(
                refreshToken = "saved-refresh-token",
                accessToken = "ephemeral-access-token",
            ),
            repository.pollForAuthorization("device-code"),
        )
        val tokenRequest = server.takeRequest()
        assertEquals("/token", tokenRequest.path)
        val tokenRequestBody = tokenRequest.body.readUtf8()
        assertTrue(tokenRequestBody.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
    }

    @Test
    fun userInfoLookupUsesBearerTokenAndParsesOnlyVerifiedGroups() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"groups":["Website Public","Website Military"]}"""))
        val repository = repository()

        assertEquals(
            TakGovAccessLevel.MIL,
            repository.getUserAccessLevel("ephemeral-access-token"),
        )
        val request = server.takeRequest()
        assertEquals("/userinfo", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer ephemeral-access-token", request.getHeader("Authorization"))
    }

    @Test
    fun refreshReturnsRotatedTokenAndFailedUserInfoIsReported() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"new-access-token","refresh_token":"rotated-refresh-token"}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(503))
        val repository = repository()

        assertEquals(
            TakGovRefreshedToken("new-access-token", "rotated-refresh-token"),
            repository.refreshAccessToken("old-refresh-token"),
        )
        val refreshRequest = server.takeRequest()
        assertEquals("/token", refreshRequest.path)
        assertTrue(refreshRequest.body.readUtf8().contains("grant_type=refresh_token"))
        assertThrows(java.io.IOException::class.java) {
            runBlocking { repository.getUserAccessLevel("new-access-token") }
        }
    }

    @Test
    fun handlesDeniedExpiredAndSlowDownResponses() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"access_denied"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"expired_token"}"""))
        val repository = repository()

        assertEquals(
            TakGovDeviceAuthorizationPoll.SlowDown,
            repository.pollForAuthorization("device-code"),
        )
        assertEquals(
            TakGovDeviceAuthorizationPoll.Denied,
            repository.pollForAuthorization("device-code"),
        )
        assertEquals(
            TakGovDeviceAuthorizationPoll.Expired,
            repository.pollForAuthorization("device-code"),
        )
    }

    private fun repository() = TakGovDeviceAuthorizationRepository(
        deviceAuthorizationUrl = server.url("/device").toString(),
        tokenUrl = server.url("/token").toString(),
        userInfoUrl = server.url("/userinfo").toString(),
    )
}
