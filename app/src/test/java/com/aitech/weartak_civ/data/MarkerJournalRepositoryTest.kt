package com.aitech.weartak_civ.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.aitech.weartak_civ.presentation.MainActivity
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MarkerJournalRepositoryTest {
    @Test fun interruptedOtherRouteIsNotRepeatedAfterRecreation() = runBlocking {
        val prefs = preferences()
        val repo = repository(prefs)
        assertTrue(repo.requestMarkerDrop("friendly", "point", "", 1, 1.0, 2.0, "uid"))
        val intent = repo.markerIntentJournal.read().intents.single()
        repo.markerIntentJournal.beginOtherRoutes(intent.sequence, "<event/>")
        val restored = repository(prefs)
        var offers = 0
        MarkerIntentConsumer(restored.markerIntentJournal).drain(
            prepare = { item, _ -> item.dispatchPayload ?: "<event/>" },
            payload = { it },
            execute = { _, _ -> MarkerIntentJournal.Outcome.RETRY_LATER },
            executeOtherRoutes = { _, _ -> offers++; true },
            onError = { throw it },
        )
        assertEquals(0, offers)
        assertEquals(MarkerIntentJournal.OtherRoutes.STARTED,
            restored.markerIntentJournal.read().intents.single().otherRoutes)
    }

    @Test fun rejectedBeforeAdmissionKeepsExactPayloadForRetry() = runBlocking {
        val repo = repository(preferences())
        assertTrue(repo.requestMarkerDrop("friendly", "point", "", 1, 1.0, 2.0, "uid"))
        val consumer = MarkerIntentConsumer(repo.markerIntentJournal)
        val seen = mutableListOf<String>()
        consumer.drain(
            prepare = { intent, _ -> intent.dispatchPayload ?: "<event time='1'/>" },
            payload = { it },
            execute = { _, value -> seen += value; MarkerIntentJournal.Outcome.RETRY_LATER },
            onError = { throw it },
        )
        val pending = repo.markerIntentJournal.read().intents.single()
        assertEquals(MarkerIntentJournal.Phase.NEVER_DISPATCHED, pending.phase)
        assertEquals("<event time='1'/>", pending.dispatchPayload)
        consumer.drain(
            prepare = { intent, _ -> requireNotNull(intent.dispatchPayload) },
            payload = { it },
            execute = { _, value -> seen += value; MarkerIntentJournal.Outcome.PLUGIN_SUBMITTED },
            onError = { throw it },
        )
        assertEquals(listOf("<event time='1'/>", "<event time='1'/>"), seen)
        assertEquals(MarkerIntentJournal.Outcome.PLUGIN_SUBMITTED, repo.markerIntentJournal.read().intents.single().outcome)
    }

    init {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
    }
    private val context = RuntimeEnvironment.getApplication()
    private fun preferences() = context.getSharedPreferences("markers-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun repository(prefs: SharedPreferences) = DataRepository(context, markerJournalPreferences = prefs)

    @Test fun noCollectorAdmissionSurvivesRecreationAndOrderedEditsAndDelete() = runBlocking {
        val prefs = preferences()
        val repo = repository(prefs)
        assertTrue(repo.requestMarkerDrop("friendly", "Test ", "remark", 100, uid = "point"))
        assertNull(repo.droppedMarkerList.value.single().location)
        assertTrue(repo.requestMarkerUpdate("hostile", "Changed", "new", 200, "point", 12.0, 34.0))
        repo.requestChangeMarkerRemark("point", "last")
        val restored = repository(prefs)
        val marker = restored.droppedMarkerList.value.single()
        assertEquals("Changed", marker.title)
        assertEquals("last", marker.remark)
        assertEquals(DataRepository.MarkerType.HOSTILE, marker.markerType)
        assertNull(marker.location!!.hae)
        assertEquals(listOf("Test", "Changed", "Changed"),
            restored.markerIntentJournal.read().intents.map { it.marker!!.title })
        assertTrue(restored.requestClearSpecific("point"))
        val afterDelete = repository(prefs)
        assertTrue(afterDelete.droppedMarkerList.value.isEmpty())
        assertTrue(afterDelete.isDeletedLocalPoint("point"))
        assertNull(afterDelete.markerIntentJournal.read().intents.last().marker)
        assertFalse(afterDelete.requestMarkerUpdate("friendly", "resurrect", "", 300, "point"))
    }

    @Test fun failedPersistenceDoesNotUpdateVisibleStateOrAllowRetry() = runBlocking {
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
        val repo = repository(failing)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.requestMarkerDrop("friendly", "Test", "", 1, 1.0, 2.0, "uid") }
        }
        assertTrue(repo.droppedMarkerList.value.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.requestMarkerDrop("friendly", "Test", "", 1, 1.0, 2.0, "uid") }
        }
        Unit
    }

    @Test fun startedDispatchBecomesUncertainAndCannotBeReplayedOnRepositoryRecreation() = runBlocking {
        val prefs = preferences()
        val repo = repository(prefs)
        assertTrue(repo.requestMarkerDrop("friendly", "Test", "", 1, 1.0, 2.0, "uid"))
        val intent = repo.markerIntentJournal.read().intents.single()
        repo.markerIntentJournal.begin(intent.sequence, "<event/>")
        val restored = repository(prefs)
        assertEquals(MarkerIntentJournal.Phase.UNCERTAIN, restored.markerIntentJournal.read().intents.single().phase)
        assertThrows(IllegalStateException::class.java) { restored.markerIntentJournal.begin(intent.sequence, "<event/>") }
        Unit
    }

    @Test fun closedAdmissionRetainsOfflineWorkAndRejectsNewRequests() = runBlocking {
        val prefs = preferences()
        val repo = repository(prefs)
        assertTrue(repo.requestMarkerDrop("friendly", "offline", "", 1, uid = "uid"))
        repo.markerIntentJournal.closeAdmission()
        val restored = repository(prefs)
        assertEquals("offline", restored.droppedMarkerList.value.single().title)
        assertTrue(restored.markerIntentJournal.dispatchable().isEmpty())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { restored.requestMarkerUpdate("friendly", "new", "", 2, "uid") }
        }
        Unit
    }

    @Test fun consumerDispatchesEachEditAndDeletionInOrderWithPersistedPayload() = runBlocking {
        val prefs = preferences()
        val repo = repository(prefs)
        assertTrue(repo.requestMarkerDrop("friendly", "first", "", 1, 1.0, 2.0, "uid"))
        assertTrue(repo.requestMarkerUpdate("hostile", "second", "", 2, "uid"))
        assertTrue(repo.requestClearSpecific("uid"))
        val observed = mutableListOf<String>()
        MarkerIntentConsumer(repo.markerIntentJournal).drain(
            prepare = { intent, _ -> intent.marker?.title ?: "delete" },
            payload = { "<event>$it</event>" },
            execute = { intent, value ->
                val persisted = repo.markerIntentJournal.read().intents.single { it.sequence == intent.sequence }
                assertEquals(MarkerIntentJournal.Phase.DISPATCH_STARTED, persisted.phase)
                assertEquals("<event>$value</event>", persisted.dispatchPayload)
                observed += value
                if (intent.marker == null) MarkerIntentJournal.Outcome.DELETION_STAGED
                else MarkerIntentJournal.Outcome.NATIVE_COMMITTED
            },
            onError = { throw it },
        )
        assertEquals(listOf("first", "second", "delete"), observed)
        val restored = repository(prefs)
        assertEquals(listOf(MarkerIntentJournal.Outcome.NATIVE_COMMITTED,
            MarkerIntentJournal.Outcome.NATIVE_COMMITTED, MarkerIntentJournal.Outcome.DELETION_STAGED),
            restored.markerIntentJournal.read().intents.map { it.outcome })
        assertTrue(restored.droppedMarkerList.value.isEmpty())
    }

    @Test fun uncertainEffectDoesNotReplayOrOvertakeSameUid() = runBlocking {
        val prefs = preferences()
        val repo = repository(prefs)
        assertTrue(repo.requestMarkerDrop("friendly", "first", "", 1, 1.0, 2.0, "uid"))
        assertTrue(repo.requestMarkerUpdate("friendly", "second", "", 2, "uid"))
        val consumer = MarkerIntentConsumer(repo.markerIntentJournal)
        var attempts = 0
        suspend fun drain() = consumer.drain(
            prepare = { intent, _ -> intent.marker!!.title },
            payload = { it },
            execute = { _, _ -> attempts++; null },
            onError = { throw it },
        )
        drain()
        drain()
        assertEquals(1, attempts)
        assertEquals(listOf(MarkerIntentJournal.Phase.UNCERTAIN, MarkerIntentJournal.Phase.NEVER_DISPATCHED),
            repository(prefs).markerIntentJournal.read().intents.map { it.phase })
    }

    @Test fun consumerClosureWaitsForInFlightEffectAndRejectsNewAdmission() = runBlocking {
        val repo = repository(preferences())
        assertTrue(repo.requestMarkerDrop("friendly", "first", "", 1, 1.0, 2.0, "uid"))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val consumer = MarkerIntentConsumer(repo.markerIntentJournal)
        val active = async(Dispatchers.Default) {
            consumer.drain(
                prepare = { intent, _ -> intent.payload },
                payload = { it },
                execute = { _, _ ->
                    started.complete(Unit)
                    finish.await()
                    MarkerIntentJournal.Outcome.NATIVE_COMMITTED
                },
                onError = { throw it },
            )
        }
        started.await()
        val otherConsumer = MarkerIntentConsumer(repo.markerIntentJournal)
        assertFalse(otherConsumer.closeAndDrain(50))
        assertTrue(repo.markerIntentJournal.read().closed)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.requestMarkerDrop("friendly", "new", "", 2, 1.0, 2.0, "new") }
        }
        finish.complete(Unit)
        active.await()
        assertTrue(otherConsumer.closeAndDrain(1000))
        assertEquals(MarkerIntentJournal.Outcome.NATIVE_COMMITTED,
            repo.markerIntentJournal.read().intents.single().outcome)
    }

    @Test fun consumerClosureNeverClaimsUncertainEffectDrained() = runBlocking {
        val repo = repository(preferences())
        assertTrue(repo.requestMarkerDrop("friendly", "first", "", 1, 1.0, 2.0, "uid"))
        val consumer = MarkerIntentConsumer(repo.markerIntentJournal)
        consumer.drain(prepare = { intent, _ -> intent.payload }, payload = { it },
            execute = { _, _ -> null }, onError = { throw it })
        assertFalse(consumer.closeAndDrain(1000))
        assertEquals(MarkerIntentJournal.Phase.UNCERTAIN, repo.markerIntentJournal.read().intents.single().phase)
    }
}
