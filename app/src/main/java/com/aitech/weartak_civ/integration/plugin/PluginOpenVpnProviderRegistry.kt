package com.aitech.weartak_civ.integration.plugin

import android.net.Network
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as C
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

internal class PluginOpenVpnProviderRegistry(
    private val onChanged: () -> Unit = {},
) {
    internal class Registration(
        val pluginPackage: String,
        val expectedUid: Int,
        val provider: IOpenVpnRawTcpProviderV1,
        val binder: IBinder,
        val deathRecipient: IBinder.DeathRecipient,
    ) {
        val sockets = ConcurrentHashMap.newKeySet<PluginOpenVpnSocket>()
    }

    private val lock = Any()
    private var registration: Registration? = null

    fun register(
        pluginPackage: String,
        expectedUid: Int,
        provider: IOpenVpnRawTcpProviderV1,
    ): Boolean {
        if (pluginPackage != C.PLUGIN_PACKAGE || expectedUid < 0) return false
        val binder = provider.asBinder()
        if (!binder.isBinderAlive) return false
        val apiVersion = try {
            provider.apiVersion
        } catch (_: RemoteException) {
            return false
        }
        if (apiVersion != C.PROVIDER_API_VERSION) return false
        lateinit var next: Registration
        val death = IBinder.DeathRecipient { revoke(next) }
        next = Registration(pluginPackage, expectedUid, provider, binder, death)
        try {
            binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            return false
        }
        val previous = synchronized(lock) {
            registration.also { registration = next }
        }
        previous?.let(::revoke)
        onChanged()
        return binder.isBinderAlive && synchronized(lock) { registration === next }
    }

    fun unregister(pluginPackage: String, expectedUid: Int) {
        val current = synchronized(lock) {
            registration?.takeIf {
                it.pluginPackage == pluginPackage && it.expectedUid == expectedUid
            }?.also { registration = null }
        } ?: return
        revoke(current, removeCurrent = false)
        onChanged()
    }

    fun isConnected(pluginPackage: String, profileId: String): Boolean {
        val current = current(pluginPackage) ?: return false
        return try {
            isCurrent(current) && current.provider.getRuntimeState(profileId) == C.RUNTIME_CONNECTED
        } catch (error: RemoteException) {
            Log.w(TAG, "OpenVPN provider status query failed", error)
            revoke(current)
            false
        }
    }

    fun socketFactory(
        pluginPackage: String,
        serverId: String,
        profileId: String,
        serverAddress: String,
        serverPort: Int,
        allowedPorts: Set<Int>,
        network: Network?,
        connectionGeneration: Long,
        allowed: () -> Boolean,
    ): SocketFactory? {
        if (serverPort !in 1..65535 || serverPort !in allowedPorts ||
            allowedPorts.isEmpty() || allowedPorts.any { it !in 1..65535 }
        ) return null
        val current = current(pluginPackage) ?: return null
        return PluginOpenVpnSocketFactory(
            registry = this,
            registration = current,
            serverId = serverId,
            profileId = profileId,
            serverAddress = serverAddress,
            serverPort = serverPort,
            allowedPorts = allowedPorts,
            network = network,
            connectionGeneration = connectionGeneration,
            allowed = allowed,
        )
    }

    internal fun isCurrent(current: Registration): Boolean =
        current.binder.isBinderAlive && synchronized(lock) { registration === current }

    internal fun adoptSocket(
        current: Registration,
        socket: PluginOpenVpnSocket,
    ): Boolean = synchronized(lock) {
        if (registration !== current || !current.binder.isBinderAlive) {
            false
        } else {
            current.sockets.add(socket)
        }
    }

    internal fun openStream(
        current: Registration,
        serverId: String,
        profileId: String,
        endpointAddress: String,
        port: Int,
        generation: Long,
        timeoutMs: Long,
    ): Long = try {
        current.provider.openStream(serverId, profileId, endpointAddress, port, generation, timeoutMs)
    } catch (error: RemoteException) {
        revoke(current)
        throw IOException("OpenVPN plugin stream is unavailable", error)
    }

    internal fun readStream(
        current: Registration,
        profileId: String,
        streamId: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Long,
    ): Int {
        validateBufferRange(destination.size, offset, length)
        val requested = length.coerceAtMost(C.MAX_IO_BYTES)
        val scratch = ByteArray(requested)
        return try {
            val result = current.provider.readStream(
                profileId, streamId, scratch, 0, requested, timeoutMs,
            )
            validateIoResult(current, result, requested, "read").also {
                if (it > 0) scratch.copyInto(destination, offset, 0, it)
            }
        } catch (error: RemoteException) {
            revoke(current)
            throw IOException("OpenVPN plugin stream is unavailable", error)
        } finally {
            scratch.fill(0)
        }
    }

    internal fun writeStream(
        current: Registration,
        profileId: String,
        streamId: Long,
        source: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Long,
    ): Int {
        validateBufferRange(source.size, offset, length)
        val requested = length.coerceAtMost(C.MAX_IO_BYTES)
        val scratch = source.copyOfRange(offset, offset + requested)
        return try {
            val result = current.provider.writeStream(
                profileId, streamId, scratch, 0, requested, timeoutMs,
            )
            validateIoResult(current, result, requested, "write")
        } catch (error: RemoteException) {
            revoke(current)
            throw IOException("OpenVPN plugin stream is unavailable", error)
        } finally {
            scratch.fill(0)
        }
    }

    private fun validateIoResult(
        current: Registration,
        result: Int,
        requested: Int,
        operation: String,
    ): Int {
        if (result > requested) {
            revoke(current)
            throw IOException("OpenVPN plugin returned an invalid $operation length")
        }
        return result
    }

    private fun validateBufferRange(size: Int, offset: Int, length: Int) {
        if (offset < 0 || length <= 0 || offset > size || length > size - offset) {
            throw IndexOutOfBoundsException("Invalid OpenVPN stream buffer range")
        }
    }

    internal fun closeStream(current: Registration, profileId: String, streamId: Long): Int =
        try {
            current.provider.closeStream(profileId, streamId)
        } catch (error: RemoteException) {
            revoke(current)
            throw IOException("OpenVPN plugin stream is unavailable", error)
        }

    internal fun remove(socket: PluginOpenVpnSocket) {
        socket.registration.sockets.remove(socket)
    }

    private fun current(pluginPackage: String): Registration? =
        synchronized(lock) {
            registration?.takeIf { it.pluginPackage == pluginPackage && it.binder.isBinderAlive }
        }

    private fun revoke(current: Registration, removeCurrent: Boolean = true) {
        val shouldClose = synchronized(lock) {
            if (removeCurrent && registration === current) registration = null
            registration !== current
        }
        if (!shouldClose) return
        runCatching { current.binder.unlinkToDeath(current.deathRecipient, 0) }
        current.sockets.toList().forEach { socket ->
            runCatching { socket.closeFromProviderLoss() }
                .onFailure { Log.w(TAG, "OpenVPN stream cleanup failed", it) }
        }
        if (removeCurrent) onChanged()
    }

    companion object {
        private const val TAG = "PluginOpenVpnRegistry"
    }
}

private class PluginOpenVpnSocketFactory(
    private val registry: PluginOpenVpnProviderRegistry,
    private val registration: PluginOpenVpnProviderRegistry.Registration,
    private val serverId: String,
    private val profileId: String,
    private val serverAddress: String,
    private val serverPort: Int,
    private val allowedPorts: Set<Int>,
    private val network: Network?,
    private val connectionGeneration: Long,
    private val allowed: () -> Boolean,
) : SocketFactory() {
    override fun createSocket(): Socket = PluginOpenVpnSocket(
        registry, registration, serverId, profileId, serverAddress, serverPort, allowedPorts,
        network, connectionGeneration, allowed,
    )

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket(address, port)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}

internal class PluginOpenVpnSocket(
    private val registry: PluginOpenVpnProviderRegistry,
    internal val registration: PluginOpenVpnProviderRegistry.Registration,
    private val serverId: String,
    private val profileId: String,
    private val serverAddress: String,
    private val serverPort: Int,
    private val allowedPorts: Set<Int>,
    private val network: Network?,
    private val connectionGeneration: Long,
    private val allowed: () -> Boolean,
) : Socket() {
    private var streamId = 0L
    private var timeoutMs = 0
    private val stateLock = Any()
    private var closed = false
    private var connected = false
    private var streamPort = 0
    private var connecting = false
    private var remoteAddress: InetAddress? = null
    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkRange(buffer.size, offset, length)
            if (length == 0) return 0
            val socketTimeoutMs = getSoTimeout()
            val timeoutNanos = socketTimeoutMs.toLong() * 1_000_000L
            val startNanos = System.nanoTime()
            while (true) {
                ensureConnected()
                val remainingNanos = if (socketTimeoutMs == 0) {
                    Long.MAX_VALUE
                } else {
                    timeoutNanos - (System.nanoTime() - startNanos)
                }
                if (remainingNanos <= 0) {
                    throw SocketTimeoutException("TAK tunnel read timed out")
                }
                val pollTimeoutMs = if (socketTimeoutMs == 0) {
                    C.MAX_TIMEOUT_MS
                } else {
                    (remainingNanos / 1_000_000L).coerceAtLeast(1)
                        .coerceAtMost(C.MAX_TIMEOUT_MS)
                }
                val result = registry.readStream(
                    registration, profileId, streamId, buffer, offset,
                    length.coerceAtMost(C.MAX_IO_BYTES), pollTimeoutMs,
                )
                when {
                    result > 0 -> return result
                    result == 0 -> return -1
                    result == C.IO_TIMEOUT_RESULT -> ensureUsable()
                    else -> throw IOException("TAK tunnel read failed")
                }
            }
        }
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkRange(buffer.size, offset, length)
            ensureConnected()
            var written = 0
            val deadline = System.nanoTime() + ioTimeout() * 1_000_000L
            while (written < length) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) throw SocketTimeoutException("TAK tunnel write timed out")
                val remainingMs = (remainingNanos / 1_000_000L).coerceAtLeast(1)
                val result = registry.writeStream(
                    registration, profileId, streamId, buffer, offset + written,
                    (length - written).coerceAtMost(C.MAX_IO_BYTES),
                    remainingMs.coerceAtMost(C.MAX_TIMEOUT_MS),
                )
                when {
                    result > 0 -> written += result
                    result == C.IO_TIMEOUT_RESULT -> throw SocketTimeoutException("TAK tunnel write timed out")
                    else -> throw IOException("TAK tunnel write failed")
                }
            }
        }
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        synchronized(stateLock) {
            if (closed) throw SocketException("Socket is closed")
            if (connected || connecting) throw SocketException("Socket is already connecting or connected")
            connecting = true
        }
        try {
            ensureUsable()
            val target = endpoint as? InetSocketAddress ?: throw SocketException("Unsupported TAK endpoint")
            val port = target.port
            if (port !in allowedPorts) {
                throw SocketException("TAK endpoint port is not authorized")
            }
            val addresses = resolveConfiguredAddress()
            val resolved = target.address ?: resolve(target.hostString)
            if (resolved !in addresses) {
                throw SocketException("TAK endpoint is not authorized for this server")
            }
            val id = registry.openStream(
                registration,
                serverId,
                profileId,
                resolved.hostAddress ?: throw SocketException("Invalid TAK endpoint"),
                port,
                connectionGeneration,
                timeout.coerceIn(1, C.MAX_TIMEOUT_MS.toInt()).toLong(),
            )
            if (id <= 0) throw IOException("OpenVPN plugin could not open TAK stream")
            val adopted = synchronized(stateLock) {
                connecting = false
                if (!closed && allowed() && registry.adoptSocket(registration, this)) {
                    streamId = id
                    remoteAddress = resolved
                    streamPort = port
                    connected = true
                    true
                } else {
                    false
                }
            }
            if (!adopted) {
                close()
                registry.closeStream(registration, profileId, id)
                throw SocketException("TAK connection session is no longer available")
            }
        } catch (error: Exception) {
            synchronized(stateLock) { connecting = false }
            throw error
        }
    }

    override fun getInputStream(): InputStream {
        ensureConnected()
        return input
    }

    override fun getOutputStream(): OutputStream {
        ensureConnected()
        return output
    }

    override fun setSoTimeout(timeout: Int) {
        if (timeout < 0) throw IllegalArgumentException("timeout < 0")
        synchronized(stateLock) { timeoutMs = timeout }
    }

    override fun getSoTimeout(): Int = synchronized(stateLock) { timeoutMs }
    override fun isConnected(): Boolean = synchronized(stateLock) { connected }
    override fun isClosed(): Boolean = synchronized(stateLock) { closed }
    override fun isBound(): Boolean = synchronized(stateLock) { connected }
    override fun getInetAddress(): InetAddress? = synchronized(stateLock) { remoteAddress }
    override fun getPort(): Int = synchronized(stateLock) { if (connected) streamPort else 0 }
    override fun getRemoteSocketAddress(): SocketAddress? =
        synchronized(stateLock) {
            remoteAddress?.let { InetSocketAddress(it, streamPort) }
        }
    override fun setTcpNoDelay(on: Boolean) = ensureUsable()
    override fun getTcpNoDelay(): Boolean = true
    override fun setKeepAlive(on: Boolean) = ensureUsable()
    override fun getKeepAlive(): Boolean = false
    override fun setReuseAddress(on: Boolean) = ensureUsable()
    override fun getReuseAddress(): Boolean = false
    override fun setSendBufferSize(size: Int) {
        require(size > 0)
    }
    override fun getSendBufferSize(): Int = C.MAX_IO_BYTES
    override fun setReceiveBufferSize(size: Int) {
        require(size > 0)
    }
    override fun getReceiveBufferSize(): Int = C.MAX_IO_BYTES

    override fun close() {
        val closingStreamId = synchronized(stateLock) {
            if (closed) return
            closed = true
            connecting = false
            registration.sockets.remove(this)
            streamId.also { streamId = 0 }
        }
        if (closingStreamId > 0) registry.closeStream(registration, profileId, closingStreamId)
    }

    fun closeFromProviderLoss() {
        val closingStreamId = synchronized(stateLock) {
            val id = streamId
            streamId = 0
            closed = true
            connecting = false
            registration.sockets.remove(this)
            id
        }
        if (closingStreamId > 0 && registration.binder.isBinderAlive) {
            runCatching { registry.closeStream(registration, profileId, closingStreamId) }
                .onFailure { Log.w(TAG, "OpenVPN raw stream close failed", it) }
        }
    }

    private fun ensureUsable() {
        val isClosed = synchronized(stateLock) { closed }
        if (isClosed) throw SocketException("Socket is closed")
        if (!allowed() || !registry.isCurrent(registration)) {
            close()
            throw IOException("TAK connection session is no longer available")
        }
    }

    private fun ensureConnected() {
        ensureUsable()
        if (!synchronized(stateLock) { connected }) throw SocketException("Socket is not connected")
    }

    private fun ioTimeout(): Long =
        if (timeoutMs == 0) C.MAX_TIMEOUT_MS else timeoutMs.toLong().coerceAtMost(C.MAX_TIMEOUT_MS)

    private fun resolveConfiguredAddress(): Set<InetAddress> =
        (network?.getAllByName(serverAddress) ?: InetAddress.getAllByName(serverAddress))
            .toSet()
            .takeIf { it.isNotEmpty() }
            ?: throw IOException("OpenVPN route could not resolve the TAK endpoint")

    private fun resolve(host: String): InetAddress =
        (network?.getAllByName(host) ?: InetAddress.getAllByName(host))
            .firstOrNull()
            ?: throw IOException("OpenVPN route could not resolve the TAK endpoint")

    private fun checkRange(size: Int, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || length > size - offset) throw IndexOutOfBoundsException()
    }

    private companion object {
        const val TAG = "PluginOpenVpnSocket"
    }
}
