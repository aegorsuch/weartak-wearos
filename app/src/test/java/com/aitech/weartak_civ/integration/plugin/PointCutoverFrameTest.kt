package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.cot.DittoPointCutoverSnapshot
import com.aitech.weartak_civ.cot.DittoPointIngress
import com.aitech.weartak_civ.cot.DittoPointLedger
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PointCutoverFrameTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)

    @Test fun exactFramePreservesBothStoresAndDigest() {
        val ledgerPrefs = prefs()
        val watermarkPrefs = prefs()
        val localKey = DittoCotDocument.digest("id")
        val watermarkKey = DittoCotDocument.digest("peer\u0000id")
        val local = """{"uid":"id","owner":"watch","deleted":true,"pending":true,"document":{"_r":true}}"""
        val watermark = """{"owner":"watch","time":1000,"revision":"","deleted":true}"""
        assertTrue(ledgerPrefs.edit().putString(localKey, local).commit())
        assertTrue(watermarkPrefs.edit().putString(watermarkKey, watermark).commit())
        val pair = DittoPointCutoverSnapshot.Pair(
            DittoPointLedger(ledgerPrefs).snapshotForMigration(),
            DittoPointIngress(watermarkPrefs).snapshotForMigration(),
        )
        PointCutoverFrame.create(context.cacheDir, pair).use { frame ->
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(frame.openReadOnly()).use { it.readBytes() }
            assertEquals(bytes.size.toLong(), frame.length)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), frame.digest())
            DataInputStream(bytes.inputStream()).use { input ->
                val magic = ByteArray(8).also(input::readFully)
                assertEquals("WTPAIR01", String(magic, Charsets.US_ASCII))
                assertEquals(1, input.readInt())
                assertEquals(1, input.readInt())
                assertEquals(1, input.readInt())
                for ((key, json) in listOf(localKey to local, watermarkKey to watermark)) {
                    val actualKey = ByteArray(64).also(input::readFully)
                    assertEquals(key, String(actualKey, Charsets.US_ASCII))
                    val record = ByteArray(input.readInt()).also(input::readFully)
                    assertEquals(json, String(record, Charsets.UTF_8))
                }
                assertEquals(-1, input.read())
            }
        }
        assertEquals(local, ledgerPrefs.getString(localKey, null))
        assertEquals(watermark, watermarkPrefs.getString(watermarkKey, null))
    }

    @Test fun malformedUtf16AndUnsortedKeysAreRejectedWithoutLeavingFiles() {
        val ledger = DittoPointLedger(prefs())
        val ingress = DittoPointIngress(prefs())
        fun pair(records: List<DittoPointLedger.MigrationRecord>) = DittoPointCutoverSnapshot.Pair(
            DittoPointLedger.MigrationSnapshot(ledger, 0, records),
            DittoPointIngress.MigrationSnapshot(ingress, 0, emptyList()),
        )
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            PointCutoverFrame.create(context.cacheDir, pair(listOf(
                DittoPointLedger.MigrationRecord("a".repeat(64), "\uD800")
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PointCutoverFrame.create(context.cacheDir, pair(listOf(
                DittoPointLedger.MigrationRecord("b".repeat(64), "{}"),
                DittoPointLedger.MigrationRecord("a".repeat(64), "{}"),
            )))
        }
        assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun maximumFrameAndStoreByteLimits() {
        val ledger = DittoPointLedger(prefs())
        val ingress = DittoPointIngress(prefs())
        val local = (0 until 4096).map {
            DittoPointLedger.MigrationRecord(it.toString(16).padStart(64, '0'), "x".repeat(2048))
        }
        val watermarks = (0 until 8192).map {
            DittoPointLedger.MigrationRecord(it.toString(16).padStart(64, '0'), "x".repeat(1024))
        }
        fun pair(remote: List<DittoPointLedger.MigrationRecord>) = DittoPointCutoverSnapshot.Pair(
            DittoPointLedger.MigrationSnapshot(ledger, 0, local),
            DittoPointIngress.MigrationSnapshot(ingress, 0, remote),
        )
        PointCutoverFrame.create(context.cacheDir, pair(watermarks)).use { frame ->
            assertEquals(17_612_820L, frame.length)
            assertEquals(4096, frame.localCount)
            assertEquals(8192, frame.watermarkCount)
        }
        val oversized = watermarks.toMutableList()
        oversized[0] = DittoPointLedger.MigrationRecord(oversized[0].key, "x".repeat(1025))
        assertThrows(IllegalArgumentException::class.java) {
            PointCutoverFrame.create(context.cacheDir, pair(oversized))
        }
    }
}
