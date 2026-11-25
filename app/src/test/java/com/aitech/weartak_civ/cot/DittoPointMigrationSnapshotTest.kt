package com.aitech.weartak_civ.cot

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoPointMigrationSnapshotTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences(
        "point-snapshot-${UUID.randomUUID()}", Context.MODE_PRIVATE
    )

    @Test fun snapshotPreservesPendingTombstoneAndUnpublishedOwnership() {
        val prefs = preferences()
        val tombstone = """{"uid":"published","owner":"watch","deleted":true,"pending":true,"document":{"_r":true,"weartakPoint":{"version":1,"deleted":true}}}"""
        val unpublished = """{"uid":"unpublished","owner":"watch","deleted":true}"""
        val publishedKey = DittoCotDocument.digest("published")
        val unpublishedKey = DittoCotDocument.digest("unpublished")
        assertTrue(prefs.edit().putString(publishedKey, tombstone)
            .putString(unpublishedKey, unpublished).commit())
        val ledger = DittoPointLedger(prefs)
        val snapshot = ledger.snapshotForMigration()
        assertEquals(1, snapshot.version)
        assertEquals(mapOf(publishedKey to tombstone, unpublishedKey to unpublished),
            snapshot.records.associate { it.key to it.json })
        assertFalse(JSONObject(snapshot.records.single { it.key == unpublishedKey }.json).has("document"))
        assertTrue(ledger.isMigrationSnapshotCurrent(snapshot))
        ledger.created("new-point", "watch")
        assertFalse(ledger.isMigrationSnapshotCurrent(snapshot))
        assertEquals(2, snapshot.records.size)
        assertEquals(tombstone, prefs.getString(publishedKey, null))
    }

    @Test fun externalChangesAndOtherLedgerInstancesInvalidateSnapshot() {
        val prefs = preferences()
        val first = DittoPointLedger(prefs)
        first.created("point", "watch")
        val snapshot = first.snapshotForMigration()
        val other = DittoPointLedger(prefs)
        assertFalse(other.isMigrationSnapshotCurrent(snapshot))
        other.created("another", "watch")
        assertFalse(first.isMigrationSnapshotCurrent(snapshot))
        assertThrows(IllegalStateException::class.java) { first.snapshotForMigration() }
    }

    @Test fun malformedOwnershipIsNotExportedOrModified() {
        val prefs = preferences()
        val key = DittoCotDocument.digest("point")
        val malformed = """{"uid":"point","owner":"watch","deleted":false,"pending":true}"""
        assertTrue(prefs.edit().putString(key, malformed).commit())
        val ledger = DittoPointLedger(prefs)
        assertThrows(IllegalArgumentException::class.java) { ledger.snapshotForMigration() }
        assertEquals(malformed, prefs.getString(key, null))
    }

    @Test fun incompleteActivePointBlocksPreflightButDurablePendingAndTombstoneDoNot() {
        val prefs = preferences()
        val deleted = """{"uid":"deleted","owner":"watch","deleted":true}"""
        assertTrue(prefs.edit()
            .putString(DittoCotDocument.digest("deleted"), deleted).commit())
        val ledger = DittoPointLedger(prefs)
        ledger.created("pending", "watch")
        val document = mapOf<String, Any?>(
            "_id" to "pending", "_v" to 2, "_r" to false, "a" to "peer",
            "AuthorId" to "watch", "b" to 1000L, "d" to "watch",
            "g" to "2.0", "h" to 1.0, "i" to 50.0, "j" to 38.0, "k" to 1.0,
            "l" to -77.0, "n" to 1000L, "o" to 601000L,
            "p" to "m-g", "q" to "Undefined",
            "r" to "<detail><contact callsign='Point'/></detail>", "w" to "a-f-G",
        )
        assertThrows(IllegalStateException::class.java) {
            ledger.publish(document) { throw IllegalStateException("offline") }
        }
        assertTrue(ledger.hasPending())
        assertFalse(ledger.hasUnexportableActivePoints())
        ledger.created("incomplete", "watch")
        assertTrue(ledger.hasUnexportableActivePoints())
    }

    @Test fun malformedActiveDocumentFailsPreflight() {
        val prefs = preferences()
        val record = """{"uid":"point","owner":"watch","deleted":false,"document":{}}"""
        assertTrue(prefs.edit().putString(DittoCotDocument.digest("point"), record).commit())
        val ledger = DittoPointLedger(prefs)
        assertThrows(IllegalArgumentException::class.java) { ledger.hasUnexportableActivePoints() }
    }

    @Test fun watermarkSnapshotPreservesOpaqueTombstonesAndDetectsExternalChange() {
        val prefs = preferences()
        val key = DittoCotDocument.digest("synthetic-peer\u0000synthetic-point")
        val value = """{"owner":"watch","time":1000,"revision":"${"a".repeat(64)}","deleted":true}"""
        assertTrue(prefs.edit().putString(key, value).commit())
        val ingress = DittoPointIngress(prefs)
        val snapshot = ingress.snapshotForMigration()
        assertEquals(1, snapshot.version)
        assertEquals(key, snapshot.records.single().key)
        assertEquals(value, snapshot.records.single().json)
        assertTrue(ingress.isMigrationSnapshotCurrent(snapshot))
        assertTrue(prefs.edit().putString(key, value.replace("1000", "1001")).commit())
        assertFalse(ingress.isMigrationSnapshotCurrent(snapshot))
        assertEquals(value, snapshot.records.single().json)
    }

    @Test fun malformedWatermarkIsNotExportedOrModified() {
        val prefs = preferences()
        val key = DittoCotDocument.digest("synthetic-peer\u0000synthetic-point")
        val value = """{"owner":"watch","time":1000,"revision":"bad","deleted":true}"""
        assertTrue(prefs.edit().putString(key, value).commit())
        val ingress = DittoPointIngress(prefs)
        assertThrows(IllegalArgumentException::class.java) { ingress.snapshotForMigration() }
        assertEquals(value, prefs.getString(key, null))
    }

    @Test fun watermarkSnapshotPreservesEmptySensitiveFingerprint() {
        val prefs = preferences()
        val key = DittoCotDocument.digest("synthetic-peer\u0000synthetic-point")
        val value = """{"owner":"watch","time":1000,"revision":"","deleted":false}"""
        assertTrue(prefs.edit().putString(key, value).commit())
        assertEquals(value, DittoPointIngress(prefs).snapshotForMigration().records.single().json)
    }

    @Test fun localMutationFencePersistsAndBlocksOtherLedgerInstancesWithoutLosingRecords() {
        val prefs = preferences()
        val fencePrefs = preferences()
        val first = DittoPointLedger(prefs, DittoPointMutationFence(fencePrefs))
        first.created("point", "watch")
        val second = DittoPointLedger(prefs, DittoPointMutationFence(fencePrefs))
        val before = prefs.all
        assertTrue(DittoPointMutationFence(fencePrefs).fence())
        assertThrows(IllegalStateException::class.java) { first.created("new", "watch") }
        assertThrows(IllegalStateException::class.java) { second.delete("point", "watch", 1000) }
        val restarted = DittoPointLedger(prefs, DittoPointMutationFence(fencePrefs))
        assertThrows(IllegalStateException::class.java) { restarted.created("restart", "watch") }
        assertEquals(before, prefs.all)
    }

    @Test fun fenceWaitsForInFlightMutationAndBlocksLaterMutations() {
        val fence = DittoPointMutationFence(preferences())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                fence.mutate {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val fencing = executor.submit<Boolean> { fence.fence() }
            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                fencing.get(100, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertTrue(fencing.get(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { fence.mutate { } }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun mutationDrainTimesOutWithoutWaitingForWriterAndClosesAdmission() {
        val prefs = preferences()
        val first = DittoPointMutationFence(prefs)
        val second = DittoPointMutationFence(prefs)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                first.mutate {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    first.mutate { }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(second.closeAndDrain(50))
            assertThrows(IllegalStateException::class.java) { second.mutate { } }
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertTrue(second.closeAndDrain(1000))
            assertTrue(second.fence())
            assertThrows(IllegalStateException::class.java) { first.mutate { } }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun corruptFenceRefusesBothMutationAndCutover() {
        val prefs = preferences()
        assertTrue(prefs.edit().putString("local-point-writers-fenced", "invalid").commit())
        val fence = DittoPointMutationFence(prefs)
        assertFalse(fence.fence())
        assertThrows(IllegalStateException::class.java) { fence.mutate { } }
    }

    @Test fun failedFenceCommitRemainsUnavailableDespiteCachedMarker() {
        val real = preferences()
        val prefs = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        editor.commit()
                        return false
                    }
                }
            }
        }
        assertFalse(DittoPointMutationFence(prefs).fence())
        val next = DittoPointMutationFence(prefs)
        assertFalse(next.fence())
        assertThrows(IllegalStateException::class.java) { next.mutate { } }
    }

    @Test fun timedOutFenceCommitNeverBecomesConfirmedWhenWorkerFinishes() {
        val real = preferences()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val prefs = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value)
                        return this
                    }
                    override fun commit(): Boolean = try {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        editor.commit()
                    } finally {
                        finished.countDown()
                    }
                }
            }
        }
        val fence = DittoPointMutationFence(prefs)
        try {
            assertFalse(fence.fence(100))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { fence.mutate { } }
            assertFalse(DittoPointMutationFence(prefs).fence(50))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertFalse(fence.fence(1000))
            assertNull(fence.withConfirmedFence { "unsafe" })
        } finally {
            release.countDown()
        }
    }

    @Test fun fencedRetryDoesNotPublishPendingDocument() {
        val prefs = preferences()
        val fence = DittoPointMutationFence(preferences())
        val key = DittoCotDocument.digest("point")
        val record = """{"uid":"point","owner":"watch","deleted":false,"pending":true,"nativePhase":"NEVER_DISPATCHED","document":{}}"""
        assertTrue(prefs.edit().putString(key, record).commit())
        val ledger = DittoPointLedger(prefs, fence)
        assertTrue(fence.fence())
        var writes = 0
        var errors = 0
        ledger.retry(write = { writes++ }, onError = { errors++ })
        assertEquals(0, writes)
        assertEquals(1, errors)
        assertEquals(record, prefs.getString(key, null))
    }

    @Test fun retryDoesNotInvokeFailingErrorHandlerTwice() {
        val prefs = preferences()
        val fence = DittoPointMutationFence(preferences())
        val key = DittoCotDocument.digest("point")
        val record = """{"uid":"point","owner":"watch","deleted":false,"pending":true,"nativePhase":"NEVER_DISPATCHED","document":{}}"""
        assertTrue(prefs.edit().putString(key, record).commit())
        val ledger = DittoPointLedger(prefs, fence)
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            ledger.retry(write = { throw IllegalStateException("write") },
                onError = { calls++; throw IllegalStateException("handler") })
        }
        assertEquals(1, calls)
    }

    @Test fun interruptedNativeWriteIsNotRetriedAfterLedgerRecreation() {
        val prefs = preferences()
        val ledger = DittoPointLedger(prefs)
        ledger.created("point", "watch")
        val document = mapOf<String, Any?>(
            "_id" to "point", "_v" to 2, "_r" to false, "a" to "peer",
            "AuthorId" to "watch", "b" to 1000L, "d" to "watch",
            "g" to "2.0", "h" to 1.0, "i" to 50.0, "j" to 38.0, "k" to 1.0,
            "l" to -77.0, "n" to 1000L, "o" to 601000L,
            "p" to "m-g", "q" to "Undefined",
            "r" to "<detail><contact callsign='Point'/></detail>", "w" to "a-f-G",
        )
        assertThrows(IllegalStateException::class.java) {
            ledger.publish(document) { throw IllegalStateException("unknown native outcome") }
        }
        val restored = DittoPointLedger(prefs)
        assertTrue(restored.hasPending())
        assertTrue(restored.hasUncertainNativeWrites())
        assertFalse(restored.hasRetryablePending())
        var writes = 0
        restored.retry({ writes++ }, { throw it })
        assertEquals(0, writes)
        assertThrows(IllegalStateException::class.java) { restored.delete("point", "watch", 2000L) }
    }

    @Test fun pointMutationDrainWaitsThroughNativeWriteAndLedgerAcknowledgement() {
        val ledgerPrefs = preferences()
        val fencePrefs = preferences()
        val fence = DittoPointMutationFence(fencePrefs)
        val ledger = DittoPointLedger(ledgerPrefs, fence)
        ledger.created("point", "watch")
        val document = mapOf<String, Any?>(
            "_id" to "point", "_v" to 2, "_r" to false, "a" to "peer",
            "AuthorId" to "watch", "b" to 1000L, "d" to "watch",
            "g" to "2.0", "h" to 1.0, "i" to 50.0, "j" to 38.0, "k" to 1.0,
            "l" to -77.0, "n" to 1000L, "o" to 601000L,
            "p" to "m-g", "q" to "Undefined",
            "r" to "<detail><contact callsign='Point'/></detail>", "w" to "a-f-G",
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit<Boolean> {
                ledger.publish(document) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(fence.closeAndDrain(50))
            val other = DittoPointMutationFence(fencePrefs)
            assertThrows(IllegalStateException::class.java) { other.mutate { } }
            release.countDown()
            assertTrue(writer.get(5, TimeUnit.SECONDS))
            assertTrue(fence.closeAndDrain(1000))
            assertFalse(ledger.hasPending())
            assertTrue(fence.fence())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun uncertainStorageCommitBlocksFenceAcrossInstancesWithoutBlockingNormalWrites() {
        val fencePrefs = preferences()
        val ledgerPrefs = preferences()
        val editor = ledgerPrefs.edit()
        val failedEditor = object : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                editor.commit()
                return false
            }
        }
        failedEditor.putString("uncertain", "value")
        assertFalse(DittoPointMutationFence(fencePrefs).commit(failedEditor))
        val second = DittoPointMutationFence(fencePrefs)
        assertFalse(second.fence())
        assertEquals("value", ledgerPrefs.getString("uncertain", null))
        assertEquals("continued", second.mutate { "continued" })
    }

    @Test fun failedLedgerWriteBlocksFenceEvenIfFreshInstanceCanReadCachedRecord() {
        val real = preferences()
        val fencePrefs = preferences()
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        editor.commit()
                        return false
                    }
                }
            }
        }
        val ledger = DittoPointLedger(failing, DittoPointMutationFence(fencePrefs))
        assertThrows(IllegalStateException::class.java) { ledger.created("point", "watch") }
        assertEquals(1, DittoPointLedger(real).snapshotForMigration().records.size)
        assertFalse(DittoPointMutationFence(fencePrefs).fence())
    }

    @Test fun fencedIngressRejectsLateWatermarkWrites() {
        val fencePrefs = preferences()
        val watermarkPrefs = preferences()
        val ingress = DittoPointIngress(watermarkPrefs, DittoPointMutationFence(fencePrefs))
        val raw = mapOf<String, Any?>(
            "_id" to "point", "_v" to 2, "_r" to false, "a" to "remote-peer",
            "AuthorId" to "alice", "b" to 1000L, "d" to "point",
            "g" to "2.0", "h" to 1.0, "i" to 50.0, "j" to 38.0, "k" to 1.0,
            "l" to -77.0, "n" to 1000L, "o" to 601000L,
            "p" to "m-g", "q" to "Undefined",
            "r" to "<detail><contact callsign='Remote point'/></detail>", "w" to "a-h-G",
        )
        val point = DittoPointIngress.Point(DittoCotDocument.decode(raw), false)
        assertTrue(DittoPointMutationFence(fencePrefs).fence())
        assertThrows(IllegalStateException::class.java) { ingress.accept(point) }
        assertTrue(watermarkPrefs.all.isEmpty())
    }

    @Test fun pairedCaptureRequiresQuiescenceAndPreservesBothStoresAcrossRestart() = runTest {
        val ledgerPrefs = preferences()
        val watermarkPrefs = preferences()
        val fencePrefs = preferences()
        val fence = DittoPointMutationFence(fencePrefs)
        val ledgerKey = DittoCotDocument.digest("point")
        val watermarkKey = DittoCotDocument.digest("peer\u0000point")
        val local = """{"uid":"point","owner":"watch","deleted":true}"""
        val remote = """{"owner":"watch","time":1000,"revision":"","deleted":true}"""
        assertTrue(ledgerPrefs.edit().putString(ledgerKey, local).commit())
        assertTrue(watermarkPrefs.edit().putString(watermarkKey, remote).commit())
        val notStopped = DittoPointCutoverSnapshot(ledgerPrefs, watermarkPrefs, fence) { false }
        try {
            notStopped.capture()
            fail("Unconfirmed quiescence must fail")
        } catch (_: IllegalStateException) {
            assertTrue(fencePrefs.all.isEmpty())
        }
        val capture = DittoPointCutoverSnapshot(ledgerPrefs, watermarkPrefs, fence) { true }
        val first = capture.capture()
        assertEquals(local, first.ledger.records.single().json)
        assertEquals(remote, first.watermarks.records.single().json)
        assertThrows(IllegalStateException::class.java) {
            DittoPointLedger(ledgerPrefs, DittoPointMutationFence(fencePrefs)).created("new", "watch")
        }
        val restarted = DittoPointCutoverSnapshot(
            ledgerPrefs, watermarkPrefs, DittoPointMutationFence(fencePrefs)
        ) { true }.capture()
        assertEquals(local, restarted.ledger.records.single().json)
        assertEquals(remote, restarted.watermarks.records.single().json)
        assertEquals(local, ledgerPrefs.getString(ledgerKey, null))
        assertEquals(remote, watermarkPrefs.getString(watermarkKey, null))
    }

    @Test fun pairedCaptureIncludesWriterAlreadyInFlightBeforeFence() {
        val real = preferences()
        val fencePrefs = preferences()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowPrefs = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return editor.commit()
                    }
                }
            }
        }
        val fence = DittoPointMutationFence(fencePrefs)
        val ledger = DittoPointLedger(slowPrefs, fence)
        val capture = DittoPointCutoverSnapshot(real, preferences(), fence) { true }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit { ledger.created("point", "watch") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val snapshot = executor.submit<DittoPointCutoverSnapshot.Pair> {
                runBlocking { capture.capture() }
            }
            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                snapshot.get(100, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertEquals("point", JSONObject(snapshot.get(5, TimeUnit.SECONDS)
                .ledger.records.single().json).getString("uid"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
