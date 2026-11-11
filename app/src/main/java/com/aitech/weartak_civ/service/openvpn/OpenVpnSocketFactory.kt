/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service.openvpn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.SocketFactory

class OpenVpnSocketFactory(private val expectedProfileId: String) : SocketFactory() {
    override fun createSocket(): Socket = OpenVpnSocket(expectedProfileId)
    override fun createSocket(host: String, port: Int): Socket = connect(host, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(host.hostAddress ?: "", port)
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = connect(address.hostAddress ?: "", port)

    private fun connect(host: String, port: Int): Socket =
        OpenVpnSocket(expectedProfileId).apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}

private class OpenVpnSocket(private val expectedProfileId: String) : Socket() {
    private var streamId = 0L
    private var timeoutMs = 0
    private var closed = false
    private var connected = false
    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkRange(buffer.size, offset, length)
            if (length == 0) return 0
            ensureConnected()
            val count = OpenVpnNativeBridge.readStream(
                streamId,
                buffer,
                offset,
                length.coerceAtMost(OpenVpnNativeBridge.MAX_IO_BYTES),
                ioTimeout(),
            )
            return when {
                count >= 0 -> count
                count == OpenVpnNativeBridge.Result.TIMEOUT -> throw SocketTimeoutException("VPN stream read timed out")
                else -> throw IOException("VPN stream read failed")
            }
        }
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkRange(buffer.size, offset, length)
            ensureConnected()
            var written = 0
            val deadline = System.nanoTime() + ioTimeout().coerceAtLeast(1).toLong() * 1_000_000L
            while (written < length) {
                val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
                val count = OpenVpnNativeBridge.writeStream(
                    streamId,
                    buffer,
                    offset + written,
                    (length - written).coerceAtMost(OpenVpnNativeBridge.MAX_IO_BYTES),
                    remainingMs,
                )
                when {
                    count > 0 -> written += count
                    count == OpenVpnNativeBridge.Result.TIMEOUT -> throw SocketTimeoutException("VPN stream write timed out")
                    else -> throw IOException("VPN stream write failed")
                }
            }
        }
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw SocketException("Socket is closed")
        if (connected) throw SocketException("Socket is already connected")
        val target = endpoint as? InetSocketAddress ?: throw SocketException("Unsupported VPN endpoint")
        val address = target.address ?: InetAddress.getByName(target.hostString)
        if (address !is Inet4Address) throw SocketException("VPN transport supports IPv4 TAK endpoints only")
        val runtime = OpenVpnService.runtimeState.value
        if (runtime.status != OpenVpnStatus.CONNECTED || runtime.profileId != expectedProfileId) {
            throw IOException("Selected OpenVPN profile is not connected")
        }
        val result = OpenVpnNativeBridge.openStream(
            address.hostAddress ?: throw SocketException("Invalid VPN endpoint"),
            target.port,
            timeout.coerceIn(1, OpenVpnNativeBridge.MAX_TIMEOUT_MS.toInt()).toLong(),
        )
        if (result <= 0) throw IOException("VPN stream connection failed")
        streamId = result
        connected = true
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
        timeoutMs = timeout
    }

    override fun getSoTimeout(): Int = timeoutMs
    override fun isConnected(): Boolean = connected
    override fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        if (streamId > 0) OpenVpnNativeBridge.closeStream(streamId)
        streamId = 0
    }

    private fun ensureConnected() {
        if (closed) throw SocketException("Socket is closed")
        if (!connected || streamId <= 0) throw SocketException("Socket is not connected")
    }

    private fun ioTimeout(): Long =
        if (timeoutMs == 0) 60_000L else timeoutMs.toLong().coerceAtMost(OpenVpnNativeBridge.MAX_TIMEOUT_MS)

    private fun checkRange(size: Int, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || length > size - offset) throw IndexOutOfBoundsException()
    }
}
