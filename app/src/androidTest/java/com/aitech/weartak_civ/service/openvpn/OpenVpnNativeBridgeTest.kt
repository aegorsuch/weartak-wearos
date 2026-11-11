package com.aitech.weartak_civ.service.openvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenVpnNativeBridgeTest {
    @Test
    fun invalidProfilesReturnSafeErrors() {
        assertEquals(
            OpenVpnNativeBridge.Result.INVALID_PROFILE,
            OpenVpnNativeBridge.validateProfile(byteArrayOf()),
        )
        assertEquals(
            OpenVpnNativeBridge.Result.INVALID_PROFILE,
            OpenVpnNativeBridge.start(byteArrayOf()),
        )
        assertEquals(
            OpenVpnNativeBridge.Result.INVALID_PROFILE,
            OpenVpnNativeBridge.validateProfile(byteArrayOf(0xC3.toByte(), 0x28)),
        )
        assertEquals(
            OpenVpnNativeBridge.Result.INVALID_PROFILE,
            OpenVpnNativeBridge.validateProfile("cli\u0000ent".encodeToByteArray()),
        )
    }

    @Test
    fun usernamePasswordProfileValidatesWithoutStartingTunnel() {
        val imported = OpenVpnProfileSupport.parse(
            "synthetic.ovpn",
            """
                client
                dev tun
                proto udp
                remote 192.0.2.1 1194
                nobind
                persist-key
                persist-tun
                client-cert-not-required
                auth-user-pass
                verb 3
            """.trimIndent().encodeToByteArray(),
        )
        val record = OpenVpnProfileRecord(
            id = "synthetic",
            name = imported.name,
            config = imported.config,
            requiresCredentials = imported.requiresCredentials,
        )
        val config = OpenVpnProfileSupport.nativeConfig(record, "test-user", "test-password")

        try {
            assertEquals(OpenVpnNativeBridge.Result.OK, OpenVpnNativeBridge.validateProfile(config))
            assertEquals(OpenVpnNativeBridge.State.STOPPED, OpenVpnNativeBridge.getState())
        } finally {
            config.fill(0)
        }
    }

    @Test
    fun unknownStreamHandlesCloseIdempotentlyAndFailReadsAndWrites() {
        val streamId = 42L
        assertEquals(OpenVpnNativeBridge.Result.OK, OpenVpnNativeBridge.closeStream(streamId))
        assertEquals(OpenVpnNativeBridge.Result.OK, OpenVpnNativeBridge.closeStream(streamId))
        assertEquals(
            OpenVpnNativeBridge.Result.STREAM_NOT_FOUND,
            OpenVpnNativeBridge.readStream(streamId, ByteArray(1), 0, 1, 100),
        )
        assertEquals(
            OpenVpnNativeBridge.Result.STREAM_NOT_FOUND,
            OpenVpnNativeBridge.writeStream(streamId, byteArrayOf(1), 0, 1, 100),
        )
    }

    @Test
    fun stoppedLifecycleIsIdempotent() {
        assertEquals(OpenVpnNativeBridge.State.STOPPED, OpenVpnNativeBridge.getState())
        assertEquals(
            OpenVpnNativeBridge.Result.NOT_CONNECTED,
            OpenVpnNativeBridge.awaitConnected(100),
        )
        assertEquals(OpenVpnNativeBridge.Result.OK, OpenVpnNativeBridge.stop())
        assertEquals(OpenVpnNativeBridge.Result.OK, OpenVpnNativeBridge.stop())
        assertEquals(OpenVpnNativeBridge.State.STOPPED, OpenVpnNativeBridge.getState())
    }

    @Test
    fun streamDestinationIsSuppliedAtCallTime() {
        assertEquals(
            OpenVpnNativeBridge.Result.INVALID_ARGUMENT.toLong(),
            OpenVpnNativeBridge.openStream("invalid", 443, 100),
        )
        assertEquals(
            OpenVpnNativeBridge.Result.NOT_CONNECTED.toLong(),
            OpenVpnNativeBridge.openStream("192.0.2.1", 443, 100),
        )
    }
}
