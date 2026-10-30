package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.data.takserver.TakHttpSession
import com.aitech.weartak_civ.data.takserver.withSessionResponse
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal data class PluginTakServer(val id: String, val name: String)

internal class PluginTakHttpClient(
    val server: PluginTakServer,
    private val address: String,
    private val session: TakHttpSession,
    internal val client: OkHttpClient,
) {
    constructor(
        server: PluginTakServer,
        address: String,
        session: TakHttpSession,
        sslContext: SSLContext,
        trustManager: X509TrustManager,
    ) : this(server, address, session, session.route(strictClient(sslContext, trustManager)))

    // Deliberately use OkHttp's strict hostname verifier, not TAK's SAN fallback.
    private companion object {
        fun strictClient(sslContext: SSLContext, trustManager: X509TrustManager) = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    internal fun request(request: PluginHttpRequest): Request {
        val url = HttpUrl.Builder().scheme("https").host(address).port(C.REST_PORT)
            .encodedPath(request.path).encodedQuery(request.query).build()
        require(url.encodedPath == request.path) { "Non-canonical path" }
        val body = if (request.method == "GET" || (request.method == "DELETE" && request.body == null)) null
            else request.body.orEmpty().toRequestBody("${request.contentType}; charset=utf-8".toMediaType())
        return Request.Builder().url(url).method(request.method, body).build()
    }

    fun execute(request: PluginHttpRequest, execute: (Call, () -> Bundle) -> Bundle): Bundle {
        val call = client.newCall(request(request))
        return execute(call) {
            call.withSessionResponse(session) { response ->
                val body = response.body
                val bytes = body?.source()?.let { source ->
                    source.request(C.MAX_RESPONSE_BYTES.toLong() + 1)
                    if (source.buffer.size > C.MAX_RESPONSE_BYTES) throw PluginResponseTooLarge()
                    source.readByteArray()
                } ?: byteArrayOf()
                C.result(C.OK).apply {
                    putString(C.KEY_SERVER_ID, server.id)
                    putInt(C.KEY_HTTP_STATUS, response.code)
                    putString(C.KEY_BODY, bytes.toString(Charsets.UTF_8))
                }
            }
        }
    }
}

internal class PluginResponseTooLarge : IOException("Plugin REST response exceeds ${C.MAX_RESPONSE_BYTES} bytes")
