package com.aitech.weartak_civ.service

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.CompanionPointRevisions
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CotDispatchServicePointDeletionTest {
    private class Fixture {
        val app = RuntimeEnvironment.getApplication().also {
            MainActivity.device_uid = "test-watch"
            MainActivity.versionNumber = "test"
            MainActivity.mainActivity = mockk { every { applicationContext } returns it }
        }
        val repo = DataRepository(app)
        val companion = CompanionAppRepository(app)
        val calls = mutableListOf<Pair<String, String>>()
        var pluginAvailable = false
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            private val mutex = Mutex()
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                mutex.withLock { transform(data.value).also { data.value = it } }
        }
        val bloodhounds = BloodhoundRepository(app, preferences)
        val service: CotDispatchService
        val queue: MutableList<DataRepository.DroppedMarker>
        val map: MutableMap<String, DataRepository.DroppedMarker>
        val refreshes: MutableMap<String, Long>
        init {
            MainActivity.Constants.bhRepo = bloodhounds
            val host = mockk<WearTakApplication>(relaxed = true)
            every { host.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } answers { pluginAvailable }
            service = spyk(Robolectric.buildService(CotDispatchService::class.java).get())
            every { service.applicationContext } returns host
            field("dataRepository").set(service, repo)
            field("capRepo\$delegate").set(service, lazyOf(companion))
            val dispatcher = CotEndpointDispatcher(
                sendTak = { xml, _ -> calls += "TAK" to xml },
                sendSitx = { calls += "SITX" to it },
                sendDitto = { _, xml -> calls += "Ditto" to xml },
                sendMulticast = { calls += "multicast" to it },
                sendPliMulticast = { calls += "PLI multicast" to it },
                sendCompanion = { error("Deletion cannot use legacy marker CoT") },
                sendPlugin = { calls += "plugin" to it })
            field("endpointDispatcher\$delegate").set(service, lazyOf(dispatcher))
            @Suppress("UNCHECKED_CAST")
            queue = field("pendingMarkerCotList").get(service) as MutableList<DataRepository.DroppedMarker>
            @Suppress("UNCHECKED_CAST")
            map = field("markerDropMap").get(service) as MutableMap<String, DataRepository.DroppedMarker>
            @Suppress("UNCHECKED_CAST")
            refreshes = field("selfMarkerRefreshes").get(service) as MutableMap<String, Long>
        }
        suspend fun add(uid: String) {
            val now = System.currentTimeMillis()
            val marker = DataRepository.DroppedMarker(DataRepository.MarkerCotType.DROP,
                uid, DataRepository.MarkerType.HOSTILE, "Point", "", now, null, true, true)
            checkNotNull(repo.markerIntentJournal.admitNew(uid, marker, refreshSelf = false))
            repo.appendDroppedMarker(marker)
            map[uid] = marker
            queue.add(marker)
            refreshes[uid] = android.os.SystemClock.elapsedRealtimeNanos()
            bloodhounds.addMarkerTarget(uid, "Edited point", "a-h-G", 38.0, -77.0, now + 1)
            bloodhounds.activate(uid)
        }
        suspend fun close() {
            (field("serviceScope").get(service) as kotlinx.coroutines.CoroutineScope)
                .coroutineContext[Job]?.cancel()
            bloodhounds.removeAllFromMenu()
        }
    }

    @Test fun offlineAndLegacyCompanionRemoveLocallyWithoutExternalFallbackOrReconnectQueue() = runBlocking {
        val f = Fixture()
        val requests = mutableListOf<DataRepository.PointDeletionRequest>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            f.repo.pointDeletionRequests.collect { requests.add(it) }
        }
        try {
            for (shared in listOf(false, true)) {
                val uid = if (shared) "shared" else "unshared"
                f.add(uid)
                if (shared) {
                    f.pluginAvailable = true
                    f.repo.storeEnableCompanionAppService(true)
                    f.repo.setCompanionAppConnected()
                    f.repo.companionPointGeneration = 7
                    f.repo.companionPointRevisions.remember(uid,
                        CompanionPointRevisions.Revision("shared-revision", System.currentTimeMillis(), true, 7))
                }
                call(f.service, "handleClearMarkerUid", uid)
                assertTrue(f.repo.droppedMarkerList.value.isEmpty())
                assertFalse(f.bloodhounds.isUidValid(uid))
                assertNull(f.bloodhounds.activeUid.value)
                assertFalse(f.map.containsKey(uid))
                assertTrue(f.queue.isEmpty())
                assertTrue(f.refreshes.isEmpty())
                assertTrue(f.repo.isDeletedLocalPoint(uid))
                assertTrue(f.calls.isEmpty())
            }
            yield()
            assertEquals(listOf("shared"), requests.map { it.uid })
            f.repo.setCompanionAppDisconnected()
            call(f.service, "handleClearMarkerAll")
            assertTrue("No invented offline replay", f.calls.isEmpty())
        } finally { collector.cancel(); f.close() }
    }

    @Test fun individualAndClearAllSendOnlyOwnedOriginalUidsAndNeverReceivedOrUnprovenRestoredPins() = runBlocking {
        val f = Fixture()
        try {
            f.pluginAvailable = true
            f.add("one")
            f.add("two")
            val restored = DataRepository.DroppedMarker(DataRepository.MarkerCotType.DROP,
                "unproven", DataRepository.MarkerType.FRIENDLY, "Legacy", "", 1L, null, false, false)
            f.repo.storeDroppedMarkerList(f.repo.droppedMarkerList.value + restored)
            f.repo.upsertReceivedMarker(DataRepository.ReceivedMarker(
                uid = "peer", markerType = "a-f-G", title = "Peer", timeReceived = 1L,
                staleTime = Long.MAX_VALUE, location = null, source = "TAK", sourceServerId = "peer-server"))
            call(f.service, "handleClearMarkerUid", "peer")
            assertTrue(f.calls.isEmpty())
            call(f.service, "handleClearMarkerUid", "one")
            call(f.service, "handleClearMarkerAll")
            assertEquals(listOf("one", "two"), f.calls.map { CotMessageParser.parseDeletionOrNull(it.second)!!.uid })
            assertTrue(f.calls.all { it.first == "plugin" })
            assertEquals("peer", f.repo.receivedMarkerList.value.single().uid)
            assertTrue(f.repo.droppedMarkerList.value.isEmpty())
            assertTrue(f.queue.isEmpty())
            assertTrue(f.map.isEmpty())
            assertTrue(f.refreshes.isEmpty())
            assertNull(f.bloodhounds.activeUid.value)
            call(f.service, "handleClearMarkerUid", "one")
            assertEquals(2, f.calls.size)
            f.repo.deleteReceivedPointLocally("peer", f.bloodhounds)
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertEquals("Received Delete must remain local", 2, f.calls.size)
        } finally { f.close() }
    }

    @Test fun gatewayIsExclusiveAndQueuesOneActualDeleteNotLegacyOrDirectCopies() = runBlocking {
        val f = Fixture()
        val mirrors = mutableListOf<DataRepository.PointDeletionRequest>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            f.repo.pointDeletionRequests.collect { mirrors += it }
        }
        try {
            f.pluginAvailable = true
            f.repo.storeEnableCompanionAppService(true)
            f.repo.setCompanionAppConnected()
            f.companion.gateway.negotiate(JSONObject("""{"version":1,"session":"current-session"}"""))
            f.companion.gateway.confirm("current-session")
            f.add("gateway-owned")
            f.repo.companionPointGeneration = 7
            f.repo.companionPointRevisions.remember("gateway-owned",
                CompanionPointRevisions.Revision("known-revision", System.currentTimeMillis(), true, 7))
            call(f.service, "handleClearMarkerUid", "gateway-owned")
            val queued = f.companion.gatewayCotEvents.tryReceive().getOrThrow()
            assertEquals("current-session", queued.first)
            assertEquals("gateway-owned", CotMessageParser.parseDeletionOrNull(queued.second)!!.uid)
            assertTrue(f.companion.gatewayCotEvents.tryReceive().isFailure)
            assertTrue(f.calls.isEmpty())
            assertNull(f.bloodhounds.activeUid.value)
            yield()
            assertTrue("Do not duplicate raw deletion with mirror deletion", mirrors.isEmpty())
        } finally { collector.cancel(); f.close() }
    }

    companion object {
        private fun field(name: String) = CotDispatchService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
        private suspend fun call(service: CotDispatchService, name: String, uid: String? = null): Any? =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                val method = CotDispatchService::class.java.declaredMethods.single { it.name == name }
                    .apply { isAccessible = true }
                if (uid == null) method.invoke(service, continuation) else method.invoke(service, uid, continuation)
            }
    }
}
