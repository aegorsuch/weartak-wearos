package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import com.weartak.plugin.sdk.pdltransfer.v1.IPdlTransferV1
import com.weartak.plugin.sdk.pdltransfer.v1.PdlTransferContractV1 as Contract
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostPdlTransferV1Test {
    private var uid = 42
    private var trusted = true
    private var active = true
    private var selection = DittoPdlMigrationSelection(DittoPdlSource.CUSTOM, "")
    private var failRead = false

    private fun endpoint() = HostPdlTransferV1(
        sessionId = "session",
        expectedUid = 42,
        bindingActive = { active },
        authorize = { trusted },
        readSelection = {
            if (failRead) throw IOException()
            selection
        },
        selectionCurrent = { it === selection },
        acknowledgeIfCurrent = { expected, action -> if (expected === selection) action() else null },
        callingUid = { uid },
    )

    @Test fun customEmptyPayloadAndAcknowledgement() {
        val endpoint = endpoint()
        val result = endpoint.read("session", "transfer")
        assertTrue(Contract.isValidReadResult(result, "transfer"))
        assertEquals(Contract.CUSTOM, result.getInt(Contract.KEY_SOURCE))
        assertEquals("", result.getString(Contract.KEY_IDENTITY))
        val nonce = result.getByteArray(Contract.KEY_NONCE)!!
        assertArrayEquals(nonce, endpoint.read("session", "transfer").getByteArray(Contract.KEY_NONCE))
        assertEquals(Contract.OK, endpoint.acknowledge("session", "transfer", nonce))
        assertEquals(Contract.OK, endpoint.acknowledge("session", "transfer", nonce))
    }

    @Test fun authorizationAndLifecycleRejectWithoutPayload() {
        val endpoint = endpoint()
        uid = 43
        var result = endpoint.read("session", "transfer")
        assertEquals(Contract.UNAUTHORIZED, result.getInt(Contract.KEY_CODE))
        assertTrue(Contract.isValidReadResult(result, "transfer"))
        uid = 42
        result = endpoint.read("session", "transfer")
        val nonce = result.getByteArray(Contract.KEY_NONCE)!!
        trusted = false
        assertEquals(Contract.UNAUTHORIZED, endpoint.acknowledge("session", "transfer", nonce))
        trusted = true
        active = false
        assertEquals(Contract.INACTIVE_SESSION, endpoint.acknowledge("session", "transfer", nonce))
        active = true
        endpoint.close()
        assertEquals(Contract.INACTIVE_SESSION, endpoint.read("session", "transfer").getInt(Contract.KEY_CODE))
    }

    @Test fun mutationAndInvalidInputsFailClosed() {
        val endpoint = endpoint()
        val nonce = endpoint.read("session", "transfer").getByteArray(Contract.KEY_NONCE)!!
        selection = DittoPdlMigrationSelection(DittoPdlSource.COMMUNITY, "new")
        assertEquals(Contract.CHANGED, endpoint.acknowledge("session", "transfer", nonce))
        assertEquals(Contract.INVALID_ARGUMENT, endpoint.read(null, "transfer").getInt(Contract.KEY_CODE))
        assertEquals(Contract.INVALID_ARGUMENT, endpoint.read("session", "\n").getInt(Contract.KEY_CODE))
    }

    @Test fun storageFailureReturnsOnlyErrorCode() {
        failRead = true
        val result = endpoint().read("session", "transfer")
        assertEquals(Contract.INTERNAL_ERROR, result.getInt(Contract.KEY_CODE))
        assertTrue(Contract.isValidReadResult(result, "transfer"))
    }

    @Test fun proxyRoundTripsThroughBinderTransactions() {
        val endpoint = endpoint()
        val remote = object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                endpoint.asBinder().transact(code, data, reply, flags)
        }
        val proxy = IPdlTransferV1.Stub.asInterface(remote)
        val result = proxy.read("session", "transfer")
        assertTrue(Contract.isValidReadResult(result, "transfer"))
        assertEquals(Contract.OK, proxy.acknowledge("session", "transfer", result.getByteArray(Contract.KEY_NONCE)))
        endpoint.close()
        assertEquals(Contract.INACTIVE_SESSION, proxy.read("session", "transfer").getInt(Contract.KEY_CODE))
    }
}
