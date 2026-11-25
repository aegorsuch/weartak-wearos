/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.takgov

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class TakGovDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

internal sealed interface TakGovDeviceAuthorizationPoll {
    data object Pending : TakGovDeviceAuthorizationPoll
    data object SlowDown : TakGovDeviceAuthorizationPoll
    data object Denied : TakGovDeviceAuthorizationPoll
    data object Expired : TakGovDeviceAuthorizationPoll
    data class Approved(
        val refreshToken: String,
        val accessToken: String?,
    ) : TakGovDeviceAuthorizationPoll
}

internal data class TakGovRefreshedToken(
    val accessToken: String?,
    val refreshToken: String?,
)

internal class TakGovDeviceAuthorizationRepository(
    private val client: OkHttpClient = defaultClient(),
    private val deviceAuthorizationUrl: String = DEVICE_AUTHORIZATION_URL,
    private val tokenUrl: String = TOKEN_URL,
    private val userInfoUrl: String = USER_INFO_URL,
) {
    suspend fun requestDeviceAuthorization(): TakGovDeviceAuthorization =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(deviceAuthorizationUrl)
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("scope", SCOPE)
                        .build()
                )
                .build()
            val payload = executeJson(request, "device authorization")
            TakGovDeviceAuthorization(
                deviceCode = payload.requiredString("device_code"),
                userCode = payload.requiredString("user_code"),
                expiresInSeconds = payload.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
                    .coerceIn(1, MAX_EXPIRES_IN_SECONDS),
                intervalSeconds = payload.optLong("interval", DEFAULT_POLL_INTERVAL_SECONDS)
                    .coerceAtLeast(DEFAULT_POLL_INTERVAL_SECONDS),
            )
        }

    suspend fun pollForAuthorization(
        deviceCode: String,
    ): TakGovDeviceAuthorizationPoll = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(tokenUrl)
            .post(
                FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("grant_type", DEVICE_CODE_GRANT)
                    .add("device_code", deviceCode)
                    .build()
            )
            .build()
        val response = client.newCall(request).execute().use { httpResponse ->
            val body = httpResponse.body?.string()
                ?: throw IOException("TAK.gov returned an empty token response.")
            if (httpResponse.isSuccessful) {
                val payload = parseJson(body)
                TakGovDeviceAuthorizationPoll.Approved(
                    refreshToken = payload.requiredString("refresh_token"),
                    accessToken = payload.optString("access_token").takeIf(String::isNotBlank),
                )
            } else {
                val error = parseJson(body).optString("error")
                when (error) {
                    "authorization_pending" -> TakGovDeviceAuthorizationPoll.Pending
                    "slow_down" -> TakGovDeviceAuthorizationPoll.SlowDown
                    "access_denied" -> TakGovDeviceAuthorizationPoll.Denied
                    "expired_token" -> TakGovDeviceAuthorizationPoll.Expired
                    else -> throw IOException("TAK.gov token request failed (HTTP ${httpResponse.code}).")
                }
            }
        }
        response
    }

    suspend fun refreshAccessToken(refreshToken: String): TakGovRefreshedToken =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(tokenUrl)
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("grant_type", REFRESH_TOKEN_GRANT)
                        .add("refresh_token", refreshToken)
                        .build()
                )
                .build()
            val payload = executeJson(request, "token refresh")
            TakGovRefreshedToken(
                accessToken = payload.optString("access_token").takeIf(String::isNotBlank),
                refreshToken = payload.optString("refresh_token").takeIf(String::isNotBlank),
            )
        }

    suspend fun getUserAccessLevel(accessToken: String): TakGovAccessLevel? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(userInfoUrl)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("TAK.gov UserInfo request failed (HTTP ${response.code}).")
                }
                response.body?.string()
                    ?: throw IOException("TAK.gov returned an empty UserInfo response.")
            }
            TakGovUserInfo.accessLevel(body)
        }

    private fun executeJson(request: Request, requestName: String): JSONObject =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IOException("TAK.gov returned an empty $requestName response.")
            if (!response.isSuccessful) {
                throw IOException("TAK.gov $requestName failed (HTTP ${response.code}).")
            }
            parseJson(body)
        }

    private fun parseJson(body: String): JSONObject = try {
        JSONObject(body)
    } catch (error: JSONException) {
        throw IOException("TAK.gov returned an invalid authorization response.", error)
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf(String::isNotBlank)
            ?: throw IOException("TAK.gov authorization response is missing $name.")

    companion object {
        const val CLIENT_ID = "tak-gov-eud"
        const val SCOPE = "openid offline_access email profile"
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val REFRESH_TOKEN_GRANT = "refresh_token"
        const val DEVICE_AUTHORIZATION_URL =
            "https://auth.tak.gov/auth/realms/TPC/protocol/openid-connect/auth/device"
        const val TOKEN_URL =
            "https://auth.tak.gov/auth/realms/TPC/protocol/openid-connect/token"
        const val USER_INFO_URL =
            "https://auth.tak.gov/auth/realms/TPC/protocol/openid-connect/userinfo"
        const val REGISTER_DEVICE_URL = "https://tak.gov/register-device"
        const val DEFAULT_EXPIRES_IN_SECONDS = 600L
        const val MAX_EXPIRES_IN_SECONDS = 3_600L
        const val DEFAULT_POLL_INTERVAL_SECONDS = 5L

        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
