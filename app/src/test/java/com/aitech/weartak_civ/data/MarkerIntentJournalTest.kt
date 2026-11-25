package com.aitech.weartak_civ.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MarkerIntentJournalTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences(
        "marker-journal-${UUID.randomUUID()}", Context.MODE_PRIVATE
    )

    private fun marker(location: DataRepository.LocationSnapshot? = null) =
        DataRepository.DroppedMarker(DataRepository.MarkerCotType.DROP, "uid",
            DataRepository.MarkerType.FRIENDLY, "title", "remark", 123L, location, true, true)

    @Test fun recreationPreservesOrderedEditsTombstoneAndLocationPresence() {
        val prefs = preferences()
        val journal = MarkerIntentJournal(prefs)
        val location = DataRepository.LocationSnapshot(
            12L, 3.0, 4.0, null, 0f, null, 42f, null, "gps", 99L, null
        )
        val first = journal.admit("uid", marker(location))
        val edited = journal.admit("uid", marker(location).copy(title = "edited"))
        val deleted = journal.admit("uid", null)
        val recovered = MarkerIntentJournal(prefs).read()
        assertEquals(listOf(first, edited, deleted), recovered.intents)
        assertEquals(location, recovered.intents.first().marker?.location)
        assertTrue(recovered.effective.containsKey("uid"))
        assertNull(recovered.effective["uid"])
        assertEquals(3, recovered.intents.map { it.dispatchId }.toSet().size)
    }

    @Test fun unknownDispatchOutcomeBlocksLaterIntentsAndSurvivesRecreation() {
        val prefs = preferences()
        val journal = MarkerIntentJournal(prefs)
        val first = journal.admit("uid", marker())
        val second = journal.admit("uid", marker().copy(title = "later"))
        journal.begin(first.sequence, "<event/>")
        val restarted = MarkerIntentJournal(prefs)
        assertEquals(MarkerIntentJournal.Phase.UNCERTAIN, restarted.recoverInterrupted().intents[0].phase)
        assertThrows(IllegalStateException::class.java) { restarted.begin(first.sequence, "<event/>") }
        assertThrows(IllegalStateException::class.java) { restarted.begin(second.sequence, "<event/>") }
        assertEquals(MarkerIntentJournal.Phase.NEVER_DISPATCHED, restarted.read().intents[1].phase)
        assertTrue(restarted.dispatchable().isEmpty())
    }

    @Test fun closePersistsAndRejectsAdmissionAndDispatch() {
        val prefs = preferences()
        val journal = MarkerIntentJournal(prefs)
        val pending = journal.admit("uid", marker())
        journal.closeAdmission()
        val restarted = MarkerIntentJournal(prefs)
        assertTrue(restarted.read().closed)
        assertTrue(restarted.dispatchable().isEmpty())
        assertThrows(IllegalStateException::class.java) { restarted.admit("other", null) }
        assertThrows(IllegalStateException::class.java) { restarted.begin(pending.sequence, "<event/>") }
    }

    @Test fun companionDeletionIsDurableAndCannotOvertakePendingLocalWork() {
        val prefs = preferences()
        val journal = MarkerIntentJournal(prefs)
        val first = journal.admit("uid", marker())
        assertNull(journal.recordRemoteDeletion("uid", "remote-revision"))
        journal.begin(first.sequence, "<event/>")
        journal.markCommitted(first.sequence, MarkerIntentJournal.Outcome.LOCAL_ONLY)
        assertNotNull(journal.recordRemoteDeletion("uid", "remote-revision"))
        val restored = MarkerIntentJournal(prefs)
        assertTrue(restored.read().effective.containsKey("uid"))
        assertNull(restored.read().effective["uid"])
        assertTrue(restored.dispatchable().isEmpty())
    }

    @Test fun failedCommitPoisonsFurtherAccessInProcess() {
        val real = preferences()
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean = false
                }
            }
        }
        val journal = MarkerIntentJournal(failing)
        assertThrows(IllegalStateException::class.java) { journal.admit("uid", marker()) }
        assertThrows(IllegalStateException::class.java) { journal.admit("uid", marker()) }
        assertThrows(IllegalStateException::class.java) { MarkerIntentJournal(failing).read() }
    }

    @Test fun modifiedPayloadCannotBeReadAsTheOriginalDispatch() {
        val prefs = preferences()
        MarkerIntentJournal(prefs).admit("uid", marker())
        val key = "marker-intent-journal-v1"
        val root = JSONObject(prefs.getString(key, null)!!)
        root.getJSONArray("intents").getJSONObject(0).put("payload", "null")
        assertTrue(prefs.edit().putString(key, root.toString()).commit())
        assertThrows(IllegalArgumentException::class.java) { MarkerIntentJournal(prefs).read() }
    }

    @Test fun acknowledgedHistoryCompactsWithoutDroppingUnresolvedIntentsOrTombstones() {
        val prefs = preferences()
        val journal = MarkerIntentJournal(prefs)
        val deletionLocation = DataRepository.LocationSnapshot(12, 3.0, 4.0, null, 0f, null, null, null, null, null, null)
        val changes = (0 until 4091).map { "uid" to marker().copy(title = "edit-$it") } +
            listOf(
                "deleted" to marker(deletionLocation).copy(uid = "deleted"),
                "deleted" to null,
                "uncertain" to marker().copy(uid = "uncertain"),
                "started" to marker().copy(uid = "started"),
                "pending" to marker().copy(uid = "pending"),
            )
        val admitted = journal.admitAll(changes)
        val key = "marker-intent-journal-v1"
        val root = JSONObject(prefs.getString(key, null)!!)
        val records = root.getJSONArray("intents")
        val payload = "<event/>"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        for (index in 0 until 4095) {
            val record = records.getJSONObject(index)
            record.put("phase", when (index) {
                4093 -> "UNCERTAIN"
                4094 -> "DISPATCH_STARTED"
                else -> "ACKNOWLEDGED"
            })
            record.put("dispatchPayload", payload).put("dispatchDigest", digest)
                .put("outcome", if (index >= 4093) JSONObject.NULL else "LOCAL_ONLY")
        }
        val laterEvent = """<event version="2.0" uid="uid" type="a-f-G" time="2025-01-01T00:00:00Z"><point lat="1" lon="2" hae="0" ce="1" le="1"/></event>"""
        val laterDigest = MessageDigest.getInstance("SHA-256").digest(laterEvent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        records.getJSONObject(100).put("dispatchPayload", laterEvent).put("dispatchDigest", laterDigest)
        assertTrue(prefs.edit().putString(key, root.toString()).commit())
        val before = MarkerIntentJournal(prefs).read()
        val firstAcknowledged = before.intents.first()
        val highestEvent = before.intents[100]
        val latestAcknowledged = before.intents[4090]
        val deletedMarker = before.intents[4091]
        val tombstone = before.intents[4092]
        val uncertain = before.intents[4093]
        val started = before.intents[4094]
        val pending = before.intents[4095]
        val remote = journal.recordRemoteDeletion("remote", "revision")!!
        assertEquals(4097L, remote.sequence)
        val added = MarkerIntentJournal(prefs).admit("uid", marker().copy(title = "after-cap"))
        assertEquals(4098L, added.sequence)
        val restored = MarkerIntentJournal(prefs).read()
        assertEquals(listOf(firstAcknowledged, highestEvent, latestAcknowledged, deletedMarker, tombstone, uncertain, started, pending, remote, added), restored.intents)
        assertEquals(deletionLocation, restored.intents.single { it.sequence == deletedMarker.sequence }.marker?.location)
        assertNull(restored.effective["deleted"])
        assertEquals("after-cap", restored.effective["uid"]?.title)
        assertEquals(4099L, restored.nextSequence)
        assertEquals(admitted[4093].dispatchId, uncertain.dispatchId)
        journal.begin(added.sequence, payload)
        journal.markCommitted(added.sequence, MarkerIntentJournal.Outcome.LOCAL_ONLY)
        assertEquals(4099L, MarkerIntentJournal(prefs).admit("uid", marker().copy(title = "after-restart")).sequence)
    }

    @Test fun compactsBeforeByteCapacityRejectsAnotherEdit() {
        val prefs = preferences()
        val journal = MarkerIntentJournal(prefs)
        val large = marker().copy(title = "x".repeat(240_000))
        journal.admitAll(List(34) { "uid" to large })
        val key = "marker-intent-journal-v1"
        val root = JSONObject(prefs.getString(key, null)!!)
        val records = root.getJSONArray("intents")
        val dispatch = "<event/>"
        val dispatchDigest = MessageDigest.getInstance("SHA-256").digest(dispatch.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        for (index in 0 until records.length()) {
            records.getJSONObject(index).put("phase", "ACKNOWLEDGED")
                .put("dispatchPayload", dispatch).put("dispatchDigest", dispatchDigest)
                .put("outcome", "LOCAL_ONLY")
        }
        assertTrue(prefs.edit().putString(key, root.toString()).commit())
        val added = journal.admit("uid", large)
        val restored = MarkerIntentJournal(prefs).read()
        assertEquals(35L, added.sequence)
        assertEquals(listOf(1L, 34L, 35L), restored.intents.map { it.sequence })
        assertEquals(large, restored.effective["uid"])
    }
}
