package com.aitech.weartak_civ.data

import android.app.Application
import android.util.Log
import com.aitech.weartak_civ.cot.*
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.mockk
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GenericCotLifecycleTest {
    init {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
    }
    private val repo = DataRepository(RuntimeEnvironment.getApplication())
    private val bloodhounds = mockk<BloodhoundRepository>(relaxed = true)
    private val now = System.currentTimeMillis()
    private fun marker(uid: String, time: Long = now, stale: Long = now + 60_000, type: String = "a-n-A-C-F") =
        CotMarkerEvent("2.0", uid, type, "m-g", time, time, stale, null, CotPoint(41.0, -87.0, 0.0, 10.0, 10.0),
            CotMarkerDetail(null, null, null, null, null, null))

    @Test fun disabledMarkerDebugSkipsChannelProjectionWithoutSkippingLiveOrStaleUpdates() = runBlocking {
        ShadowLog.setLoggable("WearTAK-DataRepository", Log.INFO)
        ShadowLog.setLoggable("WearTAK-BloodhoundRepository", Log.INFO)
        val watched = spyk(repo, recordPrivateCalls = true)
        every { watched["activeChannelsForLog"]("server") } throws AssertionError("Disabled debug projected channels")
        val navigation = BloodhoundRepository(RuntimeEnvironment.getApplication())
        assertTrue(watched.receiveMarkerEvent(marker("drop", type = "a-f-G"), navigation, "TAK", "server", now))
        assertEquals("drop", watched.receivedMarkerList.value.single().uid)
        assertEquals("drop", navigation.bloodhoundMap.value.values.single().uid)
        assertFalse(watched.receiveMarkerEvent(
            marker("drop", time = now + 1, stale = now - 1, type = "a-f-G"),
            navigation, "TAK", "server", now + 1))
        assertTrue(watched.receivedMarkerList.value.isEmpty())
        assertTrue(navigation.bloodhoundMap.value.isEmpty())
        verify(exactly = 0) { watched["activeChannelsForLog"]("server") }
        assertTrue(ShadowLog.getLogsForTag("WearTAK-DataRepository").none { it.msg.startsWith("Map state marker") })
        assertTrue(ShadowLog.getLogsForTag("WearTAK-BloodhoundRepository").none { it.msg.startsWith("Map state bloodhound upsert") })
    }

    @Test fun enabledMarkerDebugRetainsSourceAndChannelDiagnosticsAfterStateUpdate() = runBlocking {
        ShadowLog.setLoggable("WearTAK-DataRepository", Log.DEBUG)
        val watched = spyk(repo, recordPrivateCalls = true)
        every { watched["activeChannelsForLog"]("server") } returns "test-channel-projection"
        assertTrue(watched.applyReceivedMarkerEvent(marker("drop", type = "a-f-G"), "TAK", "server", now, now))
        val log = ShadowLog.getLogsForTag("WearTAK-DataRepository").single { it.msg.startsWith("Map state marker upsert") }
        assertTrue(log.msg.contains("applied=true visibleSourceId=server activeChannelsAtIngest=test-channel-projection"))
        verify(exactly = 1) { watched["activeChannelsForLog"]("server") }
        watched.clearReceivedMarkers()
    }

    @Test fun aircraftChurnIsBoundedWithoutEvictingExistingDropMarkers() = runBlocking {
        repo.applyReceivedMarkerEvent(marker("drop", type = "a-f-G"), "TAK", "server", now, now)
        repeat(850) {
            repo.applyReceivedMarkerEvent(marker("plane-$it"), "TAK", "server", now + it, now)
        }
        val points = repo.receivedMarkerList.value
        assertEquals(513, points.size)
        assertTrue(points.any { it.uid == "drop" })
        assertFalse(points.any { it.uid == "plane-0" })
        assertEquals("plane-849", points.last().title)
        repo.clearReceivedMarkers()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aircraftRefreshesReplaceSourceUidInsteadOfGrowingAndOlderPacketsAreRejected() = runTest {
        val repo = DataRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        val navigation = BloodhoundRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        repeat(500) { index ->
            val event = marker("ICAO-${index % 5}", time = now + index)
            assertTrue(repo.receiveMarkerEvent(event, navigation, "skyoverLA", "aircraft",
                receivedAt = now + index))
        }
        assertEquals(5, repo.receivedMarkerList.value.size)
        assertEquals(5, navigation.bloodhoundMap.value.size)
        assertEquals(5, repo.receivedMarkerList.value.map { it.uid }.toSet().size)
        assertFalse(repo.receiveMarkerEvent(marker("ICAO-0", time = now - 1),
            navigation, "skyoverLA", "aircraft", receivedAt = now + 1_000))
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(now + 495, repo.receivedMarkerList.value.single { it.uid == "ICAO-0" }.timeReceived)
        assertEquals(now + 495, navigation.bloodhoundMap.value["ICAO-0"]!!.bhEvent.mapItem.cotEvent.time)
        repo.pruneGenericMarkers(now + 60_001)
        assertTrue(repo.receivedMarkerList.value.isEmpty())
        assertTrue(navigation.bloodhoundMap.value.isEmpty())
    }

    @Test fun staleAndUnrefreshedGenericCopiesLeaveMapAndNavigation() = runBlocking {
        repo.receiveMarkerEvent(marker("short", stale = now + 1_000), bloodhounds, "TAK", "server", now)
        repo.receiveMarkerEvent(marker("long", stale = now + 3_600_000), bloodhounds, "TAK", "server", now)
        repo.pruneGenericMarkers(now + 1_001)
        assertEquals(listOf("long"), repo.receivedMarkerList.value.map { it.uid })
        verify { bloodhounds.removeMarkerCopy("short", "server", now, now + 1_001) }
        repo.pruneGenericMarkers(now + 15 * 60_000)
        assertTrue(repo.receivedMarkerList.value.isEmpty())
        verify { bloodhounds.removeMarkerCopy("long", "server", now, now + 15 * 60_000) }
    }

    @Test fun deleteIsSourceScopedVersionedAndDoesNotResurrectFromDelayedPackets() = runBlocking {
        val event = marker("shared")
        repo.receiveMarkerEvent(event, bloodhounds, "TAK", "a", now)
        repo.receiveMarkerEvent(event.copy(time = now + 1), bloodhounds, "TAK", "b", now)
        repo.receiveCotDeletion(CotDeletion("shared", now - 1), "b", bloodhounds, now)
        assertEquals("b", repo.receivedMarkerList.value.single().sourceServerId)
        repo.receiveCotDeletion(CotDeletion("shared", now + 2), "b", bloodhounds, now)
        assertEquals("a", repo.receivedMarkerList.value.single().sourceServerId)
        assertFalse(repo.receiveMarkerEvent(event, bloodhounds, "TAK", "b", now))
        assertTrue(repo.receiveMarkerEvent(event.copy(time = now + 3), bloodhounds, "TAK", "b", now))
        repo.clearReceivedMarkers()
    }

    @Test fun pointDeletionIsSourceScopedAndRejectsOlderRevisions() = runBlocking {
        val event = marker("shared-point")
        repo.receiveMarkerEvent(event, bloodhounds, "peer", "a", now)
        repo.receiveMarkerEvent(event.copy(time = now + 1), bloodhounds, "peer", "b", now)
        val processed = DataRepository.SourcePointDeletionResult.PROCESSED
        assertEquals(processed, repo.receiveSourcePointDeletion(
            event.uid, "b", now, bloodhounds) { false })
        assertEquals("b", repo.receivedMarkerList.value.single().sourceServerId)
        assertEquals(processed, repo.receiveSourcePointDeletion(
            event.uid, "b", now + 1, bloodhounds) { false })
        assertEquals("a", repo.receivedMarkerList.value.single().sourceServerId)
        assertFalse(repo.receiveMarkerEvent(event, bloodhounds, "peer", "b", now))
    }

    @Test fun pointDeletionProtectsPersistentlyLocalOwnership() = runBlocking {
        val event = marker("owned-point")
        repo.receiveMarkerEvent(event, bloodhounds, "peer", "a", now)
        assertEquals(DataRepository.SourcePointDeletionResult.LOCAL_POINT_PROTECTED,
            repo.receiveSourcePointDeletion(event.uid, "a", now, bloodhounds) { it == event.uid })
        assertEquals(event.uid, repo.receivedMarkerList.value.single().uid)
        verify(exactly = 0) { bloodhounds.removeMarkerCopy(event.uid, "a", any(), any()) }
    }

    @Test fun pointDeletionCannotDeleteDroppedLocalPoint() = runBlocking {
        val uid = "local-${java.util.UUID.randomUUID()}"
        val local = DataRepository.DroppedMarker(
            cotType = DataRepository.MarkerCotType.DROP,
            uid = uid,
            markerType = DataRepository.MarkerType.FRIENDLY,
            title = "Local",
            remark = "",
            timeDropped = now,
            location = null,
            enqueuedToBeSent = false,
            waitingForUpdatedLocation = false,
        )
        repo.appendDroppedMarker(local)
        assertEquals(DataRepository.SourcePointDeletionResult.LOCAL_POINT_PROTECTED,
            repo.receiveSourcePointDeletion(uid, "peer", now, bloodhounds) { false })
        assertEquals(listOf(local), repo.droppedMarkerList.value)
        verify(exactly = 0) { bloodhounds.removeMarkerCopy(uid, "peer", any(), any()) }
    }

    @Test fun deletionBeforeCreationAlsoRejectsOldUsersAndMarkers() = runBlocking {
        repo.receiveCotDeletion(CotDeletion("missing", now + 2), "server", bloodhounds, now)
        assertFalse(repo.receiveMarkerEvent(marker("missing"), bloodhounds, "TAK", "server", now))
        val event = marker("missing")
        repo.applyReceivedUserEvent(CotUserPositionEvent(event.version, event.uid, "Blue", "a-f-G-U-C",
            "Team Member", event.how, event.time, event.start, event.stale, null, event.point, event.detail),
            "TAK", "server", now)
        assertTrue(repo.receivedUserList.value.isEmpty())
        assertTrue(repo.receivedMarkerList.value.isEmpty())
    }

    @Test fun deletionRejectsDelayedRoutesButPreservesOtherSourcesAndNewerRoutes() = runBlocking {
        val route = com.aitech.weartak_civ.data.model.TakRoute("route", "Route", emptyList(), "a", eventTime = now)
        repo.upsertTakRoute(route)
        repo.upsertTakRoute(route.copy(serverId = "b"))
        repo.receiveCotDeletion(CotDeletion("route", now), "a", bloodhounds, now)
        repo.upsertTakRoute(route)
        assertEquals(listOf("b"), repo.takRouteCopies.map { it.serverId })
        repo.upsertTakRoute(route.copy(eventTime = now + 1))
        assertEquals(2, repo.takRouteCopies.size)
    }

    @Test fun idleGenericExpiryRunsWithoutAnotherIncomingPacket() = runBlocking {
        repo.receiveMarkerEvent(marker("short", stale = now + 500), bloodhounds, "TAK", "server", now)
        val deadline = System.currentTimeMillis() + 4_000
        while (repo.receivedMarkerList.value.isNotEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertTrue(repo.receivedMarkerList.value.isEmpty())
        verify { bloodhounds.removeMarkerCopy("short", "server", now, any()) }
    }
}
