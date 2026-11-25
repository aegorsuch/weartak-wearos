package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.cot.DittoPointCutoverSnapshot
import com.aitech.weartak_civ.cot.DittoPointMutationFence
import com.weartak.plugin.sdk.pointcutover.v1.PointCutoverContractV1 as Contract
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HostPointCutoverV1Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)

    @Test fun binderExportsStablePairAndRefusesChangedSourceOrDeadSession() {
        val local = prefs()
        val remote = prefs()
        val fencePrefs = prefs()
        val key = DittoCotDocument.digest("id")
        val json = """{"uid":"id","owner":"watch","deleted":true}"""
        assertTrue(local.edit().putString(key, json).commit())
        var active = true
        var uid = 42
        val snapshots = DittoPointCutoverSnapshot(local, remote, DittoPointMutationFence(fencePrefs)) { true }
        val endpoint = HostPointCutoverV1(
            "session", 42, { true }, { active }, { true }, snapshots, context.cacheDir, { uid },
            exportAllowed = { true },
        )
        val first = endpoint.read("session", "transfer")
        assertTrue(Contract.isValidReadResult(first, "transfer"))
        val firstFd = first.getParcelable<ParcelFileDescriptor>(Contract.KEY_PAYLOAD)!!
        firstFd.close()
        val again = endpoint.read("session", "transfer")
        assertTrue(Contract.isValidReadResult(again, "transfer"))
        again.getParcelable<ParcelFileDescriptor>(Contract.KEY_PAYLOAD)!!.close()
        val nonce = first.getByteArray(Contract.KEY_NONCE)!!
        assertArrayEquals(nonce, again.getByteArray(Contract.KEY_NONCE))
        assertArrayEquals(first.getByteArray(Contract.KEY_DIGEST), again.getByteArray(Contract.KEY_DIGEST))
        assertNull(endpoint.withAcknowledgedCurrent(42, "session") { "ready" })
        assertEquals(Contract.OK, endpoint.acknowledge("session", "transfer", nonce))
        assertEquals("ready", endpoint.withAcknowledgedCurrent(42, "session") { "ready" })
        assertNull(endpoint.withAcknowledgedCurrent(43, "session") { "ready" })
        assertNull(endpoint.withAcknowledgedCurrent(42, "other") { "ready" })
        uid = 43
        assertEquals(Contract.UNAUTHORIZED, endpoint.acknowledge("session", "transfer", nonce))
        uid = 42
        assertTrue(local.edit().putString(key, json.replace("true", "false")).commit())
        assertNull(endpoint.withAcknowledgedCurrent(42, "session") { "ready" })
        assertEquals(Contract.CHANGED, endpoint.acknowledge("session", "transfer", nonce))
        assertTrue(local.edit().putString(key, json).commit())
        assertEquals(Contract.CHANGED, endpoint.acknowledge("session", "transfer", nonce))
        active = false
        assertEquals(Contract.INACTIVE_SESSION, endpoint.read("session", "transfer").getInt(Contract.KEY_CODE))
        endpoint.close()
    }

    @Test fun failedQuiescenceNeverExportsOrAcknowledges() {
        val snapshots = DittoPointCutoverSnapshot(prefs(), prefs(), DittoPointMutationFence(prefs())) { false }
        val endpoint = HostPointCutoverV1(
            "session", 42, { true }, { true }, { false }, snapshots, context.cacheDir, { 42 },
            exportAllowed = { true },
        )
        val result = endpoint.read("session", "transfer")
        assertEquals(Contract.UNAVAILABLE, result.getInt(Contract.KEY_CODE))
        assertTrue(Contract.isValidReadResult(result, "transfer"))
        assertEquals(Contract.MISSING, endpoint.acknowledge("session", "transfer", ByteArray(16)))
        endpoint.close()
    }
}
