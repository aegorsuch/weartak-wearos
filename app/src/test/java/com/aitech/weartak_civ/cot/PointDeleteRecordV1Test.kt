package com.aitech.weartak_civ.cot

import android.app.Application
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PointDeleteRecordV1Test {
    private fun fixture(): String = requireNotNull(javaClass.getResourceAsStream("/point-delete-v1.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd('\r', '\n') }

    @Test fun goldenTombstoneAndLedgerSerializationAgree() {
        val payload = fixture()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        assertEquals(580, bytes.size)
        assertEquals("31d04b2bd8abfbc3c473aa8ae539e174af9595de72208f73e86e208091dc813c",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        val expected = PointDeleteRecordV1.Record("point", "watch", "peer",
            "4531a117db21a45005bd032c6b764fa7231c876f99435fbc69954936961018a6",
            1700000000000L, 1700000001234L)
        assertEquals(expected, PointDeleteRecordV1.parse(payload, "peer"))
        val document = JSONObject(payload).getJSONObject("document")
        val ledger = JSONObject().put("uid", "point").put("owner", "watch").put("deleted", true)
            .put("pending", true).put("nativePhase", "NEVER_DISPATCHED").put("document", document)
        val encoded = PointDeleteRecordV1.serializeLedgerRecord(ledger.toString(), "peer").toString(Charsets.UTF_8)
        assertEquals(expected, PointDeleteRecordV1.parse(encoded, "peer"))
        assertEquals(document.toString(), JSONObject(encoded).getJSONObject("document").toString())
    }

    @Test fun rejectsIncompleteConflictingOrAmbiguousRecords() {
        val payload = fixture()
        assertThrows(IllegalArgumentException::class.java) { PointDeleteRecordV1.parse(payload, "other-peer") }
        assertThrows(IllegalArgumentException::class.java) {
            PointDeleteRecordV1.parse(payload.replace("\"deletedAt\":1700000001234", "\"deletedAt\":1699999999999"), "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointDeleteRecordV1.parse(payload.replace("\"owner\":\"watch\"", "\"owner\":\"other\""), "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointDeleteRecordV1.parse(payload.replace("\"_r\":true", "\"_r\":true,\"_r\":true"), "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointDeleteRecordV1.serializeLedgerRecord("""{"uid":"point","owner":"watch","deleted":true}""", "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointDeleteRecordV1.parse(payload.replace("\"visible\":false", "\"visible\":true"), "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointDeleteRecordV1.parse(payload.replace("\"deletedAt\":1700000001234", "\"deletedAt\":1700000001234.5"), "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointDeleteRecordV1.parse(payload.replace("\"revision\":\"4531", "\"revision\":\"5531"), "peer")
        }
        assertThrows(CharacterCodingException::class.java) {
            PointDeleteRecordV1.parse(payload.replace("\"authorCs\":\"Point\"", "\"authorCs\":\"\\ud800\""), "peer")
        }
    }
}
