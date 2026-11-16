/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.takserver

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TakHttpSessionOpenVpnTest {
    @Test
    fun selectedOpenVpnRouteFailsClosedWhenProfileIsDisconnected() {
        val session = TakHttpSession(
            openVpnSocketFactory = DisconnectedOpenVpnSocketFactory(),
            allowed = { true },
        )

        try {
            val error = try {
                session.route(OkHttpClient()).socketFactory.createSocket()
                null
            } catch (expected: IOException) {
                expected
            }

            assertNotNull(error)
            assertEquals("Selected OpenVPN profile is not connected", error?.message)
        } finally {
            session.cancel()
        }
    }

    private class DisconnectedOpenVpnSocketFactory : SocketFactory() {
        override fun createSocket(): Socket =
            throw IOException("Selected OpenVPN profile is not connected")

        override fun createSocket(host: String, port: Int): Socket = createSocket()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket()
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            createSocket()
    }
}
