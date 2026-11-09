/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.takserver

import android.util.Log
import com.aitech.weartak_civ.data.model.TakChannel
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class TakChannelGroupSnapshot(
    val payload: ArrayNode,
    val channels: List<TakChannel>,
)

internal fun takChannelGroupsUrl(address: String, sendLatestSa: Boolean): HttpUrl =
    takChannelEndpoint(address, "Marti/api/groups/all")
        .addQueryParameter("useCache", "true")
        .apply {
            if (sendLatestSa) addQueryParameter("sendLatestSA", "true")
        }
        .build()

internal fun takChannelActiveGroupsUrl(address: String, clientUid: String): HttpUrl =
    takChannelEndpoint(address, "Marti/api/groups/active")
        .addQueryParameter("clientUid", clientUid)
        .build()

private fun takChannelEndpoint(address: String, path: String): HttpUrl.Builder = HttpUrl.Builder()
    .scheme("https")
    .host(address)
    .port(8443)
    .addPathSegments(path)

internal object TakChannelJson {
    private val objectMapper = ObjectMapper()

    fun parseSupportResponse(body: String): Boolean {
        val data = parseRoot(body).path("data")
        if (!data.isBoolean) throw TakChannelParseException("Channel support response has no Boolean data field")
        return data.asBoolean()
    }

    fun parseGroupsResponse(body: String): TakChannelGroupSnapshot {
        val data = parseRoot(body).path("data")
        if (!data.isArray) throw TakChannelParseException("Channel response has no data array")
        val payload = (data as ArrayNode).deepCopy()
        val channels = payload
            .mapNotNull(::toChannelOrNull)
            .distinctBy(TakChannel::bitPosition)
        return TakChannelGroupSnapshot(payload, channels)
    }

    fun updateActive(
        payload: ArrayNode,
        channelBitPosition: Int,
        shouldBeActive: Boolean,
    ): TakChannelGroupSnapshot {
        val updated = payload.deepCopy()
        var found = false
        updated.forEach { group ->
            if (group.path("bitpos").asInt(Int.MIN_VALUE) == channelBitPosition) {
                (group as? ObjectNode)?.put("active", shouldBeActive)
                found = true
            }
        }
        if (!found) {
            throw TakChannelParseException("Channel bit position $channelBitPosition was not returned by TAK Server")
        }
        val channels = updated
            .mapNotNull(::toChannelOrNull)
            .distinctBy(TakChannel::bitPosition)
        return TakChannelGroupSnapshot(updated, channels)
    }

    private fun parseRoot(body: String): JsonNode = try {
        objectMapper.readTree(body)
            ?: throw TakChannelParseException("TAK Server returned an empty JSON document")
    } catch (error: TakChannelParseException) {
        throw error
    } catch (error: Exception) {
        throw TakChannelParseException("Unable to parse TAK channel response", error)
    }

    private fun toChannelOrNull(group: JsonNode): TakChannel? {
        val name = group.path("name").asText().trim()
        val bitPositionNode = group.path("bitpos")
        if (!bitPositionNode.isIntegralNumber) return null
        val bitPosition = bitPositionNode.asInt()
        if (name.isEmpty()) return null
        return TakChannel(
            name = name,
            direction = group.path("direction").asText(),
            active = group.path("active").asBoolean(false),
            bitPosition = bitPosition,
        )
    }
}

internal class TakChannelApi(
    private val address: String,
    sslContext: SSLContext,
    trustManager: X509TrustManager,
    private val serverId: String,
    private val serverName: String,
    hostnameVerifier: HostnameVerifier,
    client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val session: TakHttpSession? = null,
    strictEndpoint: Boolean = false,
) {
    private val client = if (strictEndpoint) client.newBuilder()
        .followRedirects(false).followSslRedirects(false).build() else client

    fun loadGroups(
        sendLatestSa: Boolean,
        checkSupport: Boolean,
        traceId: String,
    ): TakChannelGroupSnapshot {
        if (checkSupport && !isSupported(traceId)) throw TakChannelsUnsupportedException()
        val snapshot = TakChannelJson.parseGroupsResponse(
            execute(
                request = groupsRequest(sendLatestSa),
                traceId = traceId,
                operation = if (sendLatestSa) "load-groups-and-send-latest-sa" else "load-groups"
            )
        )
        Log.i(
            TAG,
            "Channel API groups parsed trace=$traceId serverId=$serverId serverName=$serverName " +
                "sendLatestSa=$sendLatestSa channels=${snapshot.channels.diagnosticSummary()}"
        )
        return snapshot
    }

    fun updateChannel(
        channelBitPosition: Int,
        shouldBeActive: Boolean,
        clientUid: String,
        traceId: String,
    ): TakChannelGroupSnapshot {
        val current = loadGroups(
            sendLatestSa = false,
            checkSupport = false,
            traceId = traceId,
        )
        val updated = TakChannelJson.updateActive(
            payload = current.payload,
            channelBitPosition = channelBitPosition,
            shouldBeActive = shouldBeActive,
        )
        Log.i(
            TAG,
            "Channel API toggle payload prepared trace=$traceId serverId=$serverId serverName=$serverName " +
                "bitpos=$channelBitPosition desiredActive=$shouldBeActive " +
                "before=${current.channels.diagnosticSummary()} after=${updated.channels.diagnosticSummary()}"
        )
        val request = Request.Builder()
            .url(takChannelActiveGroupsUrl(address, clientUid))
            .put(
                updated.payload.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("Accept", "*/*")
            .build()
        execute(
            request = request,
            traceId = traceId,
            operation = "update-active-groups",
            requestBodyForLog = updated.payload.toString(),
        )
        return updated
    }

    private fun isSupported(traceId: String): Boolean {
        val request = Request.Builder()
            .url(takChannelEndpoint(address, "Marti/api/groups/groupCacheEnabled").build())
            .get()
            .addHeader("Accept", "*/*")
            .build()
        val supported = TakChannelJson.parseSupportResponse(
            execute(request, traceId, "check-group-cache-support")
        )
        Log.i(
            TAG,
            "Channel API support parsed trace=$traceId serverId=$serverId serverName=$serverName supported=$supported"
        )
        return supported
    }

    private fun groupsRequest(sendLatestSa: Boolean): Request {
        return Request.Builder()
            .url(takChannelGroupsUrl(address, sendLatestSa))
            .get()
            .addHeader("Accept", "*/*")
            .build()
    }

    private fun execute(
        request: Request,
        traceId: String,
        operation: String,
        requestBodyForLog: String? = null,
    ): String {
        val safeUrl = request.url.newBuilder()
            .apply {
                if (request.url.queryParameter("clientUid") != null) {
                    setQueryParameter("clientUid", "REDACTED")
                }
            }
            .build()
        Log.i(
            TAG,
            "Channel API request trace=$traceId operation=$operation serverId=$serverId " +
                "serverName=$serverName method=${request.method} url=$safeUrl"
        )
        requestBodyForLog?.let { body ->
            Log.d(
                TAG,
                "Channel API request body trace=$traceId operation=$operation " +
                    "bytes=${body.toByteArray().size} body=${body.boundedForLog()}"
            )
        }
        val startedAtNanos = System.nanoTime()
        try {
            return (session?.route(client) ?: client).newCall(request).withSessionResponse(session) { response ->
                val body = response.body?.string().orEmpty()
                val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
                Log.i(
                    TAG,
                    "Channel API response trace=$traceId operation=$operation serverId=$serverId " +
                        "code=${response.code} successful=${response.isSuccessful} durationMs=$durationMs " +
                        "bodyBytes=${body.toByteArray().size}"
                )
                Log.d(
                    TAG,
                    "Channel API response body trace=$traceId operation=$operation body=${body.boundedForLog()}"
                )
                if (!response.isSuccessful) {
                    throw TakChannelHttpException(
                        "TAK channel request failed: ${response.code} ${request.method} ${request.url.encodedPath}"
                    )
                }
                body
            }
        } catch (error: Exception) {
            val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            Log.e(
                TAG,
                "Channel API failure trace=$traceId operation=$operation serverId=$serverId " +
                    "durationMs=$durationMs error=${error.javaClass.simpleName}: ${error.message}",
                error
            )
            throw error
        }
    }

    private fun List<TakChannel>.diagnosticSummary(): String = joinToString(
        prefix = "[",
        postfix = "]",
    ) { channel ->
        "${channel.name}{bitpos=${channel.bitPosition},direction=${channel.direction},active=${channel.active}}"
    }

    private fun String.boundedForLog(): String =
        if (length <= MAX_LOG_BODY_CHARS) this else take(MAX_LOG_BODY_CHARS) + "...<truncated>"

    private companion object {
        const val TAG = "WearTAK-TakChannelApi"
        const val MAX_LOG_BODY_CHARS = 12_000
    }
}

internal open class TakChannelApiException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal class TakChannelHttpException(message: String) : TakChannelApiException(message)

internal class TakChannelParseException(message: String, cause: Throwable? = null) :
    TakChannelApiException(message, cause)

internal class TakChannelsUnsupportedException :
    TakChannelApiException("TAK Server does not support channels")
