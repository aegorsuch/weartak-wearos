package com.aitech.weartak_civ.data.takserver

import java.io.IOException
import okhttp3.Call
import okhttp3.Response
import android.net.Network
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import com.aitech.weartak_civ.service.openvpn.OpenVpnSocketFactory

/** Cancels only HTTP work belonging to one TAK connection generation. */
class TakHttpSession(
    val network: Network? = null,
    private val openVpnSocketFactory: OpenVpnSocketFactory? = null,
    private val allowed: () -> Boolean,
) {
    val usesOpenVpn: Boolean get() = openVpnSocketFactory != null
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val pool = okhttp3.ConnectionPool()
    private val dns = network?.let { route ->
        object : okhttp3.Dns {
            override fun lookup(hostname: String) = route.getAllByName(hostname).toList()
        }
    }

    fun route(client: OkHttpClient): OkHttpClient {
        ensureCurrent()
        val route = network
        if (route == null && openVpnSocketFactory == null) return client
        val builder = client.newBuilder().connectionPool(pool)
        if (openVpnSocketFactory != null) builder.socketFactory(openVpnSocketFactory)
        else if (route != null) builder.socketFactory(route.socketFactory)
        if (route != null) builder.dns(requireNotNull(dns))
        return builder.build()
    }

    fun connectOpenVpnSocket(host: String, port: Int, timeoutMs: Int): Socket {
        ensureCurrent()
        val factory = openVpnSocketFactory ?: throw IOException("OpenVPN route is not selected")
        val addresses = network?.getAllByName(host)?.toList() ?: InetAddress.getAllByName(host).toList()
        val address = addresses.firstOrNull { it is Inet4Address }
            ?: throw IOException("OpenVPN route requires a TAK server with IPv4 address")
        return factory.createSocket().apply { connect(InetSocketAddress(address, port), timeoutMs) }
    }

    fun register(call: Call) {
        val permitted = allowed()
        synchronized(lock) {
            if (closed || !permitted) {
                call.cancel()
                throw IOException("TAK connection session is no longer available")
            }
            calls.add(call)
        }
    }

    fun unregister(call: Call) = synchronized(lock) { calls.remove(call); Unit }

    fun ensureCurrent() {
        val permitted = allowed()
        synchronized(lock) {
            if (closed || !permitted) throw IOException("TAK connection session is no longer available")
        }
    }

    /** Marks the session closed immediately; call cancellation and pool eviction run off-thread. */
    fun cancel() {
        val pending = synchronized(lock) {
            closed = true
            calls.toList()
        }
        TakConnectionCloser.execute {
            pending.forEach { runCatching { it.cancel() } }
            pool.evictAll()
        }
    }
}

inline fun <T> Call.withSessionResponse(session: TakHttpSession?, block: (Response) -> T): T {
    session?.register(this)
    try {
        val result = execute().use(block)
        session?.ensureCurrent()
        return result
    } finally {
        session?.unregister(this)
    }
}
