/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.service.openvpn.OpenVpnSocketFactory
import java.io.IOException
import java.net.InetSocketAddress
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TakHttpSessionOpenVpnTest {
    @Test
    fun selectedOpenVpnRouteFailsClosedWhenProfileIsDisconnected() {
        val session = TakHttpSession(
            openVpnSocketFactory = OpenVpnSocketFactory("test-profile"),
            allowed = { true },
        )
        val socket = session.route(OkHttpClient()).socketFactory.createSocket()

        try {
            val error = try {
                socket.connect(InetSocketAddress("192.0.2.1", 8089), 100)
                null
            } catch (expected: IOException) {
                expected
            }

            assertNotNull(error)
            assertEquals("Selected OpenVPN profile is not connected", error?.message)
        } finally {
            socket.close()
            session.cancel()
        }
    }
}
