package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import com.aitech.weartak_civ.data.DittoPdlFrozenSelection
import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import com.weartak.plugin.sdk.pdlsnapshot.v1.IPdlSnapshotV1
import com.weartak.plugin.sdk.pdlsnapshot.v1.PdlSnapshotContractV1 as Contract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostPdlSnapshotV1Test {
    private var uid = 42
    private var trusted = true
    private var active = true
    private var quiescent = true
    private var snapshot = snapshot(null)

    private fun snapshot(custom: String?, community: String = "community") =
        DittoPdlFrozenSelection(
            DittoPdlMigrationSelection(DittoPdlSource.COMMUNITY, community),
            "0123456789abcdef0123456789abcdef",
            community,
            custom,
            true,
        )

    private fun endpoint() = HostPdlSnapshotV1(
        sessionId = "session",
        expectedUid = 42,
        authorized = { trusted },
        bindingActive = { active },
        quiescenceStillProven = { quiescent },
        readFrozen = { snapshot },
        withCurrent = { expected, action -> if (expected === snapshot) action() else null },
        callingUid = { uid },
    )

    @Test fun proxyPreservesNullAndEmptyCustomValues() {
        for (custom in listOf(null, "")) {
            snapshot = snapshot(custom, "original\uD800")
            val host = endpoint()
            val remote = object : Binder() {
                override fun queryLocalInterface(descriptor: String): IInterface? = null
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                    host.asBinder().transact(code, data, reply, flags)
            }
            val proxy = IPdlSnapshotV1.Stub.asInterface(remote)
            val result = proxy.read("session", "transfer")
            assertTrue(Contract.isValidReadResult(result, "transfer"))
            assertTrue(result.containsKey(Contract.KEY_CUSTOM_IDENTITY))
            assertEquals(custom, result.getString(Contract.KEY_CUSTOM_IDENTITY))
            assertEquals("original\uD800", result.getString(Contract.KEY_COMMUNITY_IDENTITY))
            assertTrue(result.getBoolean(Contract.KEY_ENABLED))
            val nonce = result.getByteArray(Contract.KEY_NONCE)!!
            assertEquals(Contract.INVALID_ARGUMENT, proxy.acknowledge("session", "transfer", ByteArray(16)))
            assertEquals(Contract.OK, proxy.acknowledge("session", "transfer", nonce))
            assertEquals(Contract.OK, proxy.acknowledge("session", "transfer", nonce))
            assertEquals(true, runBlocking { host.withAcknowledgedSnapshot(42, "session") { true } })
            host.close()
            assertEquals(Contract.INACTIVE_SESSION, proxy.acknowledge("session", "transfer", nonce))
        }
    }

    @Test fun oversizeAndChangedSnapshotFailClosed() {
        snapshot = snapshot(null, "x".repeat(16_385))
        var host = endpoint()
        var result = host.read("session", "transfer")
        assertEquals(Contract.TOO_LARGE, result.getInt(Contract.KEY_CODE))
        assertTrue(Contract.isValidReadResult(result, "transfer"))
        snapshot = snapshot("x".repeat(16_385))
        host = endpoint()
        assertEquals(Contract.TOO_LARGE, host.read("session", "transfer").getInt(Contract.KEY_CODE))
        snapshot = snapshot(null, "x".repeat(16_384))
        host = endpoint()
        result = host.read("session", "transfer")
        assertTrue(Contract.isValidReadResult(result, "transfer"))
        val nonce = result.getByteArray(Contract.KEY_NONCE)!!
        snapshot = snapshot(null, "changed")
        assertEquals(Contract.CHANGED, host.acknowledge("session", "transfer", nonce))
        assertNull(runBlocking { host.withAcknowledgedSnapshot(42, "session") { true } })
        assertEquals(Contract.CHANGED, host.read("session", "transfer").getInt(Contract.KEY_CODE))
    }

    @Test fun uidTrustBindingAndQuiescenceFenceAcknowledgement() {
        val host = endpoint()
        val nonce = host.read("session", "transfer").getByteArray(Contract.KEY_NONCE)!!
        uid = 43
        assertEquals(Contract.UNAUTHORIZED, host.acknowledge("session", "transfer", nonce))
        uid = 42
        trusted = false
        assertEquals(Contract.UNAUTHORIZED, host.acknowledge("session", "transfer", nonce))
        trusted = true
        active = false
        assertEquals(Contract.INACTIVE_SESSION, host.acknowledge("session", "transfer", nonce))
        active = true
        assertEquals(Contract.OK, host.acknowledge("session", "transfer", nonce))
        quiescent = false
        assertNull(runBlocking { host.withAcknowledgedSnapshot(42, "session") { true } })
        quiescent = true
        assertEquals(Contract.CHANGED, host.acknowledge("session", "transfer", nonce))
    }
}
