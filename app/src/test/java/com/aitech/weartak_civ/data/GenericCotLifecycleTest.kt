package com.aitech.weartak_civ.data

import android.app.Application
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.aitech.weartak_civ.cot.*
import com.aitech.weartak_civ.integration.plugin.HostPointEndpointV1
import com.aitech.weartak_civ.integration.plugin.DittoTrafficFence
import com.aitech.weartak_civ.presentation.MainActivity
import com.weartak.plugin.sdk.point.v1.IPointEndpointV1
import com.weartak.plugin.sdk.point.v1.PointContractV1
import com.weartak.plugin.sdk.point.v1.PointOperationV1
import com.weartak.plugin.sdk.point.v1.PointResultV1
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
import java.time.Instant

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

    @Test fun pointUpsertProtectingLocalPointReturnsValidRejectedResult() {
        val uid = "owned-${java.util.UUID.randomUUID()}"
        val time = Instant.ofEpochMilli(now)
        val stale = Instant.ofEpochMilli(now + 60_000)
        val xml = """<event version="2.0" uid="$uid" type="a-f-G" time="$time" start="$time" stale="$stale" how="m-g"><point lat="41" lon="-87" hae="0" ce="10" le="10"/><detail/></event>"""
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val operation = PointOperationV1(
            direction = PointContractV1.PLUGIN_TO_HOST, kind = PointContractV1.UPSERT,
            operationId = "protected-upsert", peer = "peer", owner = "owner", pointUid = uid,
            revision = DittoCotDocument.digest(xml), eventTimeMillis = now, deletedAtMillis = 0,
            authorCallsign = null, payloadKind = PointContractV1.PAYLOAD_COT_UTF8, payloadLength = bytes.size,
        )
        val file = java.io.File.createTempFile("point-test-", ".cot", RuntimeEnvironment.getApplication().cacheDir)
        var inputXml = xml
        val host = HostPointEndpointV1(42, { true }, { it == "session" }, repo, bloodhounds, { 42 }) { _, _ -> inputXml }
        val plugin = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        try {
            runBlocking {
                assertTrue(repo.requestMarkerDrop("friendly", "local", "", now, 41.0, -87.0, uid))
            }
            file.writeBytes(bytes)
            assertEquals(PointContractV1.REGISTERED, host.register("session", plugin))
            val result = host.apply("session", operation, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            assertEquals(PointContractV1.REJECTED, result.code)
            assertTrue(PointContractV1.isValidResult(operation, result))
            assertTrue(repo.receivedMarkerList.value.none { it.uid == uid })
            val legacyUid = "legacy-${java.util.UUID.randomUUID()}"
            repo.dittoPointLedger.created(legacyUid, "watch")
            inputXml = xml.replace(uid, legacyUid)
            val legacyBytes = inputXml.toByteArray(Charsets.UTF_8)
            file.writeBytes(legacyBytes)
            val freshOperation = operation.copy(operationId = "fresh-upsert", pointUid = legacyUid,
                revision = DittoCotDocument.digest(inputXml), payloadLength = legacyBytes.size)
            assertEquals(PointContractV1.LOCAL_PROCESSED,
                host.apply("session", freshOperation, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).code)
            assertTrue(host.closeAndDrain(1000))
        } finally {
            file.delete()
        }
    }

    @Test fun pointDeletionRejectsLocalIdentityAndFutureEvents() {
        val host = HostPointEndpointV1(42, { true }, { it == "session" }, repo, bloodhounds, { 42 })
        val plugin = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        val deletion = PointOperationV1(
            direction = PointContractV1.PLUGIN_TO_HOST, kind = PointContractV1.DELETE,
            operationId = "delete", peer = "peer", owner = "owner", pointUid = "point",
            revision = "a".repeat(64), eventTimeMillis = now, deletedAtMillis = now,
            authorCallsign = null, payloadKind = PointContractV1.PAYLOAD_NONE, payloadLength = 0,
        )
        assertEquals(PointContractV1.REGISTERED, host.register("session", plugin))
        val future = System.currentTimeMillis() + DittoCotReceiver.MAX_FUTURE_MS + 60_000
        val rejected = listOf(
            deletion.copy(owner = MainActivity.device_uid),
            deletion.copy(pointUid = MainActivity.device_uid),
            deletion.copy(eventTimeMillis = future, deletedAtMillis = future),
        )
        rejected.forEach {
            val result = host.apply("session", it, null)
            assertEquals(PointContractV1.REJECTED, result.code)
            assertTrue(PointContractV1.isValidResult(it, result))
        }
        verify(exactly = 0) { bloodhounds.removeMarkerCopy(any(), any(), any(), any()) }
        assertEquals(PointContractV1.LOCAL_PROCESSED, host.apply("session", deletion, null).code)
        assertTrue(host.closeAndDrain(1000))
    }

    @Test fun disabledAndStaleTrafficGenerationRejectsPointIngress() {
        val fence = DittoTrafficFence(initiallyEnabled = true)
        val generation = requireNotNull(fence.currentGeneration())
        val host = HostPointEndpointV1(
            42, { true }, { it == "session" }, repo, bloodhounds, { 42 },
            trafficAdmission = { fence.enter(generation) },
        )
        val plugin = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        val deletion = PointOperationV1(
            direction = PointContractV1.PLUGIN_TO_HOST, kind = PointContractV1.DELETE,
            operationId = "network-disabled-delete", peer = "peer", owner = "remote",
            pointUid = "remote-point", revision = "a".repeat(64), eventTimeMillis = now,
            deletedAtMillis = now, authorCallsign = null,
            payloadKind = PointContractV1.PAYLOAD_NONE, payloadLength = 0,
        )
        try {
            assertEquals(PointContractV1.REGISTERED, host.register("session", plugin))
            fence.disable()
            assertEquals(PointContractV1.RETRYABLE, host.apply("session", deletion, null).code)
            assertNotNull(fence.enable())
            assertEquals(PointContractV1.RETRYABLE, host.apply("session", deletion, null).code)
        } finally {
            assertTrue(host.closeAndDrain(1000))
        }
    }

    @Test fun pointRegistrationCanReplaceOnlyAnInactiveSession() {
        val sessions = mutableSetOf("old", "new")
        val host = HostPointEndpointV1(42, { true }, { it in sessions }, repo, bloodhounds, { 42 })
        fun endpoint() = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        val old = endpoint()
        val replacement = endpoint()
        assertEquals(PointContractV1.REGISTERED, host.register("old", old))
        assertEquals(PointContractV1.REGISTER_BUSY, host.register("new", replacement))
        sessions.remove("old")
        assertEquals(PointContractV1.REGISTERED, host.register("new", replacement))
        val deletion = PointOperationV1(
            direction = PointContractV1.PLUGIN_TO_HOST, kind = PointContractV1.DELETE,
            operationId = "restart-delete", peer = "peer", owner = "owner", pointUid = "restart-point",
            revision = "a".repeat(64), eventTimeMillis = now, deletedAtMillis = now,
            authorCallsign = null, payloadKind = PointContractV1.PAYLOAD_NONE, payloadLength = 0,
        )
        assertEquals(PointContractV1.RETRYABLE, host.apply("old", deletion, null).code)
        assertEquals(PointContractV1.LOCAL_PROCESSED, host.apply("new", deletion, null).code)
        assertTrue(host.closeAndDrain(1000))
    }

    @Test fun retiredPointEndpointDeathDoesNotFenceReplacement() {
        val sessions = mutableSetOf("old", "new")
        val host = HostPointEndpointV1(42, { true }, { it in sessions }, repo, bloodhounds, { 42 })
        val binder = mockk<IBinder>(relaxed = true)
        val old = mockk<IPointEndpointV1>()
        var death: IBinder.DeathRecipient? = null
        every { old.asBinder() } returns binder
        every { binder.isBinderAlive } returns true
        every { binder.linkToDeath(any(), any()) } answers { death = firstArg() }
        val replacement = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        assertEquals(PointContractV1.REGISTERED, host.register("old", old))
        sessions.remove("old")
        death!!.binderDied()
        assertEquals(PointContractV1.REGISTERED, host.register("new", replacement))
        death!!.binderDied()
        val deletion = PointOperationV1(
            direction = PointContractV1.PLUGIN_TO_HOST, kind = PointContractV1.DELETE,
            operationId = "death-delete", peer = "peer", owner = "owner", pointUid = "death-point",
            revision = "a".repeat(64), eventTimeMillis = now, deletedAtMillis = now,
            authorCallsign = null, payloadKind = PointContractV1.PAYLOAD_NONE, payloadLength = 0,
        )
        assertEquals(PointContractV1.LOCAL_PROCESSED, host.apply("new", deletion, null).code)
        assertTrue(host.closeAndDrain(1000))
    }

    @Test fun pointUpsertRetryRequiresOriginalPayload() {
        val uid = "remote-${java.util.UUID.randomUUID()}"
        val time = Instant.ofEpochMilli(now)
        val stale = Instant.ofEpochMilli(now + 60_000)
        val xml = """<event version="2.0" uid="$uid" type="a-f-G" time="$time" start="$time" stale="$stale" how="m-g"><point lat="41" lon="-87" hae="0" ce="10" le="10"/><detail/></event>"""
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val operation = PointOperationV1(
            direction = PointContractV1.PLUGIN_TO_HOST, kind = PointContractV1.UPSERT,
            operationId = "upsert-retry", peer = "peer", owner = "owner", pointUid = uid,
            revision = DittoCotDocument.digest(xml), eventTimeMillis = now, deletedAtMillis = 0,
            authorCallsign = null, payloadKind = PointContractV1.PAYLOAD_COT_UTF8, payloadLength = bytes.size,
        )
        val file = java.io.File.createTempFile("point-test-", ".cot", RuntimeEnvironment.getApplication().cacheDir)
        val host = HostPointEndpointV1(42, { true }, { it == "session" }, repo, bloodhounds, { 42 }) { _, _ ->
            file.readText(Charsets.UTF_8)
        }

        val plugin = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        fun apply(content: String): PointResultV1 {
            file.writeText(content, Charsets.UTF_8)
            return host.apply("session", operation, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        }
        try {
            assertEquals(PointContractV1.REGISTERED, host.register("session", plugin))
            assertEquals(PointContractV1.LOCAL_PROCESSED, apply(xml).code)
            assertEquals(PointContractV1.LOCAL_PROCESSED, apply(xml).code)
            assertEquals(PointContractV1.REJECTED, apply(xml.replace("lat=\"41\"", "lat=\"42\"")).code)
            assertTrue(host.closeAndDrain(1000))
        } finally {
            file.delete()
        }
    }

    @Test fun replacementPointBindingCannotOvertakeRetiredInFlightCall() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val uid = "remote-${java.util.UUID.randomUUID()}"
        val time = Instant.ofEpochMilli(now)
        val xml = """<event version="2.0" uid="$uid" type="a-f-G" time="$time"><point lat="41" lon="-87" hae="0" ce="10" le="10"/><detail/></event>"""
        val operation = PointOperationV1(PointContractV1.PLUGIN_TO_HOST, PointContractV1.UPSERT,
            "first", "peer", "owner", uid, DittoCotDocument.digest(xml), now, 0,
            null, PointContractV1.PAYLOAD_COT_UTF8, xml.toByteArray(Charsets.UTF_8).size)
        val plugin = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        val old = HostPointEndpointV1(42, { true }, { it == "old" }, repo, bloodhounds, { 42 }) { _, _ ->
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            xml
        }
        val replacement = HostPointEndpointV1(42, { true }, { it == "new" }, repo, bloodhounds, { 42 })
        val file = java.io.File.createTempFile("point-test-", ".cot", RuntimeEnvironment.getApplication().cacheDir)
        var oldResult: Int? = null
        try {
            file.writeText(xml, Charsets.UTF_8)
            assertEquals(PointContractV1.REGISTERED, old.register("old", plugin))
            val worker = Thread {
                try {
                    oldResult = old.apply("old", operation,
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).code
                } finally {
                    finished.countDown()
                }
            }
            worker.start()
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                old.revoke()
                assertFalse(old.closeAndDrain(50))
                assertEquals(PointContractV1.REGISTERED, replacement.register("new", plugin))
                val deletion = operation.copy(kind = PointContractV1.DELETE, operationId = "second",
                    deletedAtMillis = now, payloadKind = PointContractV1.PAYLOAD_NONE, payloadLength = 0)
                assertEquals(PointContractV1.RETRYABLE, replacement.apply("new", deletion, null).code)
                release.countDown()
                assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(PointContractV1.RETRYABLE, oldResult)
                assertTrue(old.closeAndDrain(1000))
                assertEquals(PointContractV1.LOCAL_PROCESSED, replacement.apply("new", deletion, null).code)
            } finally {
                release.countDown()
                worker.join(5000)
            }
            assertTrue(replacement.closeAndDrain(1000))
        } finally {
            file.delete()
        }
    }

    @Test fun revocationDrainsAdmittedPointMutationBeforeReplacementCanMutate() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completedTimes = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val uid = "remote-${java.util.UUID.randomUUID()}"
        val source = "ditto:${DittoCotDocument.digest("peer")}"
        val old = HostPointEndpointV1(42, { true }, { it == "old" }, repo, bloodhounds, { 42 })
        val replacement = HostPointEndpointV1(42, { true }, { it == "new" }, repo, bloodhounds, { 42 })
        val plugin = object : IPointEndpointV1.Stub() {
            override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?) =
                PointResultV1(PointContractV1.RETRYABLE)
        }
        val deletion = PointOperationV1(
            direction = PointContractV1.PLUGIN_TO_HOST, kind = PointContractV1.DELETE,
            operationId = "old-delete", peer = "peer", owner = "owner", pointUid = uid,
            revision = "a".repeat(64), eventTimeMillis = now, deletedAtMillis = now,
            authorCallsign = null, payloadKind = PointContractV1.PAYLOAD_NONE, payloadLength = 0,
        )
        val next = deletion.copy(operationId = "new-delete", eventTimeMillis = now + 1, deletedAtMillis = now + 1)
        every { bloodhounds.removeMarkerCopy(uid, source, any(), any()) } answers {
            val eventTime = thirdArg<Long>()
            if (eventTime == now) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            completedTimes.add(eventTime)
            true
        }
        try {
            runBlocking { assertTrue(repo.receiveMarkerEvent(marker(uid), bloodhounds, "Ditto", source, now)) }
            assertTrue(repo.receivedMarkerList.value.any { it.uid == uid })
            assertEquals(PointContractV1.REGISTERED, old.register("old", plugin))
            val result = executor.submit<Int> { old.apply("old", deletion, null).code }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            old.revoke()
            assertFalse(old.closeAndDrain(50))
            assertEquals(PointContractV1.REGISTERED, replacement.register("new", plugin))
            assertEquals(PointContractV1.RETRYABLE, replacement.apply("new", next, null).code)
            assertTrue(completedTimes.isEmpty())
            release.countDown()
            assertEquals(PointContractV1.UNKNOWN, result.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(old.closeAndDrain(1000))
            assertEquals(listOf(now), completedTimes.toList())
            assertTrue(repo.receivedMarkerList.value.none { it.uid == uid })
            assertEquals(PointContractV1.RETRYABLE, old.apply("old", deletion, null).code)
            assertEquals(listOf(now), completedTimes.toList())
            assertEquals(PointContractV1.LOCAL_PROCESSED, replacement.apply("new", next, null).code)
            assertEquals(listOf(now, now + 1), completedTimes.toList())
            assertTrue(repo.receivedMarkerList.value.none { it.uid == uid })
            assertTrue(replacement.closeAndDrain(1000))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            old.closeAndDrain(1000)
            replacement.closeAndDrain(1000)
        }
    }

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
