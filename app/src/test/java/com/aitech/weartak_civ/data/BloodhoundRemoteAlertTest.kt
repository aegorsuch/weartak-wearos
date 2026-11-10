/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aitech.weartak_civ.cot.CotEmergencyEvent
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotMarkerMapItem
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.CotPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class BloodhoundRemoteAlertTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    // Repositories share persisted preferences without Android's rename semantics on Windows.
    private val dataStore = object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(data.value).also { data.value = it }
            }
    }

    private fun newRepository() = BloodhoundRepository(context, dataStore)

    @Test
    fun companionTrackingStopDoesNotCancelOrDismissEmergency() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        repository.applyEmergencyEvent(alert("remote-alert", now), "TAK", deviceUid = SELF_UID)
        assertTrue(repository.activateCompanionTarget("remote-alert", "Phone", 38.0, -77.0, 0.0))
        assertTrue(repository.stopTracking("remote-alert"))
        assertNull(repository.activeUid.value)
        assertNotNull(repository.remoteAlerts.value["remote-alert"])
        assertNotNull(repository.bloodhoundMap.value["remote-alert"])
        assertTrue(repository.activate("remote-alert").second?.state?.active == true)
        assertTrue(repository.applyEmergencyEvent(alert("remote-alert", now + 1L), "TAK", deviceUid = SELF_UID))
    }

    @Test
    @org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [34])
    fun removeAllClearsAllSourcesAndPersistsLocalAlertDismissals() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        repository.upsertFromEvent(pointEvent("shared", now).copy(sourceServerId = SERVER_A))
        repository.upsertFromEvent(pointEvent("shared", now + 1).copy(sourceServerId = SERVER_B))
        val emergency = alert("shared", now + 2)
        repository.applyEmergencyEvent(emergency, "TAK-A", sourceServerId = SERVER_A, deviceUid = SELF_UID)
        repository.applyEmergencyEvent(emergency, "TAK-B", sourceServerId = SERVER_B, deviceUid = SELF_UID)
        repository.activate("shared")

        repository.removeAllFromMenu()

        assertTrue(repository.bloodhoundMap.value.isEmpty())
        assertTrue(repository.remoteAlerts.value.isEmpty())
        assertNull(repository.activeUid.value)
        assertFalse(repository.applyEmergencyEvent(emergency, "TAK", deviceUid = SELF_UID))
        val restarted = newRepository()
        assertFalse(restarted.applyEmergencyEvent(emergency.copy(time = now + 3), "TAK", deviceUid = SELF_UID))
        repository.upsertFromEvent(pointEvent("new-point", now + 4))
        assertEquals(setOf("new-point"), repository.bloodhoundMap.value.keys)
        repository.removeAllFromMenu()
        repository.removeAllFromMenu()
        assertTrue(repository.bloodhoundMap.value.isEmpty())
    }

    @Test
    fun listsNewestRemoteAlertsBeforeOrdinaryPoints() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        repository.upsertFromEvent(pointEvent("point-1", now))
        repository.applyEmergencyEvent(alert("alert-old", now + 1L), "TAK", deviceUid = SELF_UID)
        repository.applyEmergencyEvent(alert("alert-new", now + 2L), "TAK", deviceUid = SELF_UID)

        val items = withTimeout(3_000L) {
            repository.listBhItemsUi.first { it.size == 3 }
        }

        assertEquals(listOf("alert-new", "alert-old", "point-1"), items.map { it.uid })
        assertTrue(items.first().isRemoteAlert)
        assertEquals("CAT_TEST", items.first().alertCategory)
        assertFalse(items.last().isRemoteAlert)
    }

    @Test
    fun deduplicatesRemoteAlertsAcrossReceiveSourcesForPickerAndMap() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val remoteAlert = alert("duplicate-alert", now, stale = now + 60_000L)

        repository.applyEmergencyEvent(
            remoteAlert, "TAK-A", sourceServerId = SERVER_A, receivedAt = now, deviceUid = SELF_UID
        )
        repository.applyEmergencyEvent(
            remoteAlert, "TAK-B", sourceServerId = SERVER_B, receivedAt = now + 1L, deviceUid = SELF_UID
        )

        assertEquals(listOf(remoteAlert.uid), repository.bloodhoundMap.value.keys.toList())
        assertEquals("TAK-B", repository.bloodhoundMap.value[remoteAlert.uid]?.server)
        val mapItems = withTimeout(3_000L) {
            repository.remoteEmergencyMapItems.first { it.isNotEmpty() }
        }
        assertEquals(listOf(remoteAlert.uid), mapItems.map { it.uid })
        assertEquals("SENDER", mapItems.single().callsign)
        assertEquals("CAT_TEST", mapItems.single().category)
    }

    @Test
    fun cancellationRemovesActiveAlertFromPickerAndMapAndRejectsReplayedAlert() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val active = alert("cancel-alert", now, stale = now + 60_000L)

        assertTrue(repository.applyEmergencyEvent(active, "TAK", deviceUid = SELF_UID))
        repository.activate(active.uid)
        assertEquals(active.uid, repository.activeUid.value)
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.isNotEmpty() } }

        assertTrue(
            repository.applyEmergencyEvent(
                active.copy(time = now + 1L, start = now + 1L, cancelled = true),
                "TAK",
                deviceUid = SELF_UID
            )
        )

        assertNull(repository.bloodhoundMap.value[active.uid])
        assertNull(repository.activeUid.value)
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.isEmpty() } }
        assertFalse(repository.applyEmergencyEvent(active, "TAK", deviceUid = SELF_UID))

        val reactivated = active.copy(time = now + 10L, start = now + 10L)
        assertTrue(repository.applyEmergencyEvent(reactivated, "TAK", deviceUid = SELF_UID))
        repository.applyEmergencyEvent(
            active.copy(time = now, start = now, cancelled = true),
            "TAK",
            deviceUid = SELF_UID
        )
        assertEquals(now + 10L, repository.bloodhoundMap.value[active.uid]?.bhEvent?.mapItem?.cotEvent?.time)

        val restoredRepository = newRepository()
        assertFalse(restoredRepository.applyEmergencyEvent(active, "TAK", deviceUid = SELF_UID))
        assertNull(restoredRepository.bloodhoundMap.value[active.uid])
    }

    @Test
    fun cancellationDoesNotRemoveAnOrdinaryTargetWithTheSameUid() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val uid = "shared-uid"
        repository.upsertFromEvent(pointEvent(uid, now - 1_000L))
        repository.applyEmergencyEvent(alert(uid, now, stale = now + 60_000L), "TAK", deviceUid = SELF_UID)
        assertNotNull(repository.bloodhoundMap.value[uid]?.bhEvent?.remoteAlert)

        repository.applyEmergencyEvent(
            alert(uid, now + 1L).copy(cancelled = true), "TAK", deviceUid = SELF_UID
        )

        val ordinary = repository.bloodhoundMap.value[uid]
        assertNotNull(ordinary)
        assertNull(ordinary?.bhEvent?.remoteAlert)
        assertTrue(repository.remoteEmergencyMapItems.value.none { it.uid == uid })
    }

    @Test
    fun representativeAtakAlertAndCancellationDriveSharedState(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val alert = CotMessageParser.parseEmergencyOrNull(atakXml("b-a-o-tbl", now, "<emergency type=\"911 Alert\">RA26</emergency>"))
        val cancel = CotMessageParser.parseEmergencyOrNull(atakXml("b-a-o-can", now + 1_000L, "<emergency cancel=\"true\">RA26</emergency>"))

        assertTrue(repository.applyEmergencyEvent(alert!!, "239.2.3.1", sourceServerId = "multicast-test", deviceUid = SELF_UID))
        val mapItem = withTimeout(3_000L) {
            repository.remoteEmergencyMapItems.first { it.isNotEmpty() }
        }.single()
        assertEquals("ANDROID-atak-9-1-1", mapItem.uid)
        assertEquals("RA26", mapItem.callsign)
        assertEquals("911 Alert", mapItem.category)

        assertTrue(repository.applyEmergencyEvent(cancel!!, "239.2.3.1", sourceServerId = "multicast-test", deviceUid = SELF_UID))
        assertNull(repository.bloodhoundMap.value[alert.uid])
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.isEmpty() } }
    }

    @Test
    fun excludesOwnAlertsByEventUidLinkedDeviceUidAndAtakEmergencyUid() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()

        assertFalse(repository.applyEmergencyEvent(alert(SELF_UID, now), "TAK", deviceUid = SELF_UID))
        assertFalse(repository.applyEmergencyEvent(alert("other-alert", now, senderUid = SELF_UID), "TAK", deviceUid = SELF_UID))
        assertFalse(repository.applyEmergencyEvent(alert("$SELF_UID-9-1-1", now, senderUid = "relay"), "TAK", deviceUid = SELF_UID))

        assertTrue(repository.bloodhoundMap.value.isEmpty())
    }

    @Test
    fun alertWithoutLocationIsListedButNotNavigableOrRenderedOnMap() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val remoteAlert = alert("no-location-alert", now, stale = now + 60_000L, senderUid = "")
            .copy(point = CotPoint(0.0, 0.0, 0.0, 9999999.0, 9999999.0), senderCallsign = null)

        assertTrue(repository.applyEmergencyEvent(remoteAlert, "TAK", deviceUid = SELF_UID))
        val item = withTimeout(3_000L) {
            repository.listBhItemsUi.first { it.isNotEmpty() }.single()
        }

        assertNull(item.distance)
        assertFalse(item.hasLocation)
        assertTrue(item.isRemoteAlert)
        assertEquals(BloodhoundRepository.ActivationResult.NO_CHANGE, repository.activate(remoteAlert.uid).first)
        assertNull(repository.activeUid.value)
        assertTrue(repository.remoteEmergencyMapItems.value.isEmpty())
    }

    @Test
    fun acceptsAlreadyStaleAlertsInPickerAndMap(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val stale = alert("already-stale", now - 10_000L, stale = now - 1L)

        assertTrue(repository.applyEmergencyEvent(stale, "TAK", deviceUid = SELF_UID))
        val picker = withTimeout(3_000L) {
            repository.listBhItemsUi.first { it.any { item -> item.uid == stale.uid && item.isStale } }
        }.single()
        val map = withTimeout(3_000L) {
            repository.remoteEmergencyMapItems.first { it.any { item -> item.uid == stale.uid && item.isStale } }
        }.single()
        assertTrue(picker.isRemoteAlert)
        assertTrue(picker.hasLocation)
        assertEquals(stale.point, map.point)
        assertTrue(repository.bloodhoundMap.value[stale.uid]?.bhEvent?.remoteAlert?.isStale == true)
    }

    @Test
    fun staleDeadlineRetainsPickerMapAndNavigationUntilCancellation(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val shortLived = alert("becomes-stale", now, stale = now + 1_200L)
        repository.upsertFromEvent(pointEvent(shortLived.uid, now - 1_000L))

        assertTrue(repository.applyEmergencyEvent(shortLived, "TAK", deviceUid = SELF_UID))
        repository.activate(shortLived.uid)
        assertFalse(repository.bloodhoundMap.value[shortLived.uid]!!.bhEvent.remoteAlert!!.isStale)
        withTimeout(3_000L) { repository.listBhItemsUi.first { it.singleOrNull()?.isStale == true } }
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.single().isStale } }
        assertEquals(shortLived.uid, repository.activeUid.value)
        assertNotNull(repository.bloodhoundMap.value[shortLived.uid]?.bhEvent?.remoteAlert)

        assertTrue(repository.applyEmergencyEvent(
            shortLived.copy(time = now + 1L, cancelled = true), "TAK", deviceUid = SELF_UID
        ))
        assertNull(repository.activeUid.value)
        assertNull(repository.bloodhoundMap.value[shortLived.uid]?.bhEvent?.remoteAlert)
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.isEmpty() } }
    }

    @Test
    fun newerRefreshClearsStaleAndOlderUpdateCannotReplaceIt(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val stale = alert("refresh-stale", now - 10_000L, stale = now - 1L)
        assertTrue(repository.applyEmergencyEvent(stale, "TAK", sourceServerId = SERVER_A, deviceUid = SELF_UID))
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.single().isStale } }
        val fresh = stale.copy(time = now, start = now, stale = now + 60_000L)
        assertTrue(repository.applyEmergencyEvent(fresh, "TAK", sourceServerId = SERVER_A, deviceUid = SELF_UID))
        assertFalse(repository.applyEmergencyEvent(stale, "TAK", sourceServerId = SERVER_A, deviceUid = SELF_UID))
        withTimeout(3_000L) { repository.listBhItemsUi.first { it.singleOrNull()?.isStale == false } }
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { !it.single().isStale } }
        assertEquals(now, repository.bloodhoundMap.value[fresh.uid]?.bhEvent?.mapItem?.cotEvent?.time)
    }

    @Test
    fun cancellationRemovesStaleCopiesAcrossSourcesAndBlocksReplay(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val stale = alert("cancel-stale", now - 10_000L, stale = now - 1L)
        assertTrue(repository.applyEmergencyEvent(stale, "TAK-A", sourceServerId = SERVER_A, deviceUid = SELF_UID))
        assertTrue(repository.applyEmergencyEvent(stale, "TAK-B", sourceServerId = SERVER_B, deviceUid = SELF_UID))
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.single().isStale } }

        assertTrue(repository.applyEmergencyEvent(
            stale.copy(time = now, cancelled = true), "TAK-A", sourceServerId = SERVER_A, deviceUid = SELF_UID
        ))
        assertTrue(repository.remoteAlerts.value.isEmpty())
        withTimeout(3_000L) { repository.listBhItemsUi.first { it.isEmpty() } }
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.isEmpty() } }
        repository.clearSource(SERVER_A)
        assertTrue(repository.bloodhoundMap.value.isEmpty())
        assertFalse(repository.applyEmergencyEvent(stale, "TAK-B", sourceServerId = SERVER_B, deviceUid = SELF_UID))
        assertFalse(newRepository().applyEmergencyEvent(stale, "TAK-B", sourceServerId = SERVER_B, deviceUid = SELF_UID))
    }

    @Test
    fun staleNoLocationAlertRemainsNonNavigableAndInvalidFieldsAreRejected(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val stale = alert("stale-no-location", now - 10_000L, stale = now - 1L)
            .copy(point = null)
        assertFalse(repository.applyEmergencyEvent(stale.copy(uid = ""), "TAK", deviceUid = SELF_UID))
        assertFalse(repository.applyEmergencyEvent(stale.copy(time = 0L), "TAK", deviceUid = SELF_UID))
        assertFalse(repository.applyEmergencyEvent(stale.copy(start = 0L), "TAK", deviceUid = SELF_UID))
        assertFalse(repository.applyEmergencyEvent(stale.copy(stale = 0L), "TAK", deviceUid = SELF_UID))
        assertTrue(repository.applyEmergencyEvent(stale, "TAK", deviceUid = SELF_UID))
        val picker = withTimeout(3_000L) { repository.listBhItemsUi.first { it.isNotEmpty() } }.single()
        assertTrue(picker.isStale)
        assertFalse(picker.hasLocation)
        assertNull(picker.distance)
        assertTrue(repository.remoteEmergencyMapItems.value.isEmpty())
        assertEquals(BloodhoundRepository.ActivationResult.NO_CHANGE, repository.activate(stale.uid).first)
    }

    @Test
    fun nPosKeepsAnAlertButPreservesOrdinaryPointRemoval() = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val alert = alert("npos-alert", now, stale = now + 60_000L)
        repository.applyEmergencyEvent(alert, "TAK", deviceUid = SELF_UID)
        repository.activate(alert.uid)

        assertNotNull(repository.setInPos(alert.uid))
        assertNull(repository.activeUid.value)
        assertNotNull(repository.bloodhoundMap.value[alert.uid])

        repository.upsertFromEvent(pointEvent("npos-point", now))
        repository.activate("npos-point")
        assertNotNull(repository.setInPos("npos-point"))
        assertNull(repository.bloodhoundMap.value["npos-point"])
    }

    @Test
    fun localDismissalHidesBothProjectionsAndSuppressesOngoingUpdatesAcrossRestart(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val active = alert("locally-removed-alert", now, stale = now + 60_000L)
        repository.applyEmergencyEvent(active, "TAK-A", sourceServerId = SERVER_A, deviceUid = SELF_UID)
        repository.applyEmergencyEvent(active, "TAK-B", sourceServerId = SERVER_B, deviceUid = SELF_UID)

        assertNotNull(repository.removeFromMenu(active.uid))
        assertNull(repository.bloodhoundMap.value[active.uid])
        withTimeout(3_000L) { repository.listBhItemsUi.first { it.isEmpty() } }
        withTimeout(3_000L) { repository.remoteEmergencyMapItems.first { it.isEmpty() } }
        assertNull(repository.activeUid.value)

        assertFalse(repository.applyEmergencyEvent(active, "TAK-A", sourceServerId = SERVER_A, deviceUid = SELF_UID))
        assertFalse(repository.applyEmergencyEvent(
            active.copy(time = now + 1L), "TAK-B", sourceServerId = SERVER_B, deviceUid = SELF_UID
        ))
        assertTrue(repository.remoteAlerts.value.isEmpty())
        val restored = newRepository()
        assertFalse(restored.applyEmergencyEvent(
            active.copy(time = now + 2L), "TAK-A", sourceServerId = SERVER_A, deviceUid = SELF_UID
        ))
        assertTrue(restored.bloodhoundMap.value.isEmpty())
    }

    @Test
    fun dismissalEndsOnlyAfterOrderedSenderCancellationThenNewActivation(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val active = alert("reused-uid-9-1-1", now)
        assertTrue(repository.applyEmergencyEvent(active, "TAK", deviceUid = SELF_UID))
        repository.removeFromMenu(active.uid)
        assertFalse(repository.applyEmergencyEvent(active.copy(time = now + 10L), "TAK", deviceUid = SELF_UID))

        // Older cancellation cannot clear dismissal after a suppressed newer refresh.
        assertTrue(repository.applyEmergencyEvent(
            active.copy(time = now + 5L, cancelled = true), "TAK", deviceUid = SELF_UID
        ))
        assertFalse(repository.applyEmergencyEvent(active.copy(time = now + 11L), "TAK", deviceUid = SELF_UID))
        val restored = newRepository()
        assertFalse(restored.applyEmergencyEvent(active.copy(time = now + 12L), "TAK", deviceUid = SELF_UID))
        assertTrue(restored.applyEmergencyEvent(
            active.copy(time = now + 12L, cancelled = true), "TAK", deviceUid = SELF_UID
        ))
        assertFalse(restored.applyEmergencyEvent(active.copy(time = now + 12L), "TAK", deviceUid = SELF_UID))
        val reactivated = newRepository()
        assertTrue(reactivated.applyEmergencyEvent(active.copy(time = now + 13L), "TAK", deviceUid = SELF_UID))
        withTimeout(3_000L) { reactivated.listBhItemsUi.first { it.singleOrNull()?.uid == active.uid } }
        withTimeout(3_000L) { reactivated.remoteEmergencyMapItems.first { it.single().uid == active.uid } }
    }

    @Test
    fun localDismissalDoesNotEmitOutgoingRequestsOrRemoveOrdinarySameUid(): Unit = runBlocking {
        val repository = newRepository()
        val now = System.currentTimeMillis()
        val active = alert("local-only-dismissal", now)
        repository.upsertFromEvent(pointEvent(active.uid, now - 1L))
        repository.applyEmergencyEvent(active, "TAK", deviceUid = SELF_UID)
        val requests = mutableListOf<String>()
        val collectors = listOf(
            repository.requestAcknowledgement,
            repository.requestActivateBloodhound,
            repository.requestInPosition
        ).map { flow ->
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                flow.collect { requests.add(it) }
            }
        }
        val ingressUpdate = repository.bloodhoundEvent.value
        repository.removeFromMenu(active.uid)
        kotlinx.coroutines.yield()
        assertTrue(requests.isEmpty())
        assertEquals(ingressUpdate, repository.bloodhoundEvent.value)
        assertNull(repository.bloodhoundMap.value[active.uid]?.bhEvent?.remoteAlert)
        assertNotNull(repository.bloodhoundMap.value[active.uid])
        collectors.forEach { it.cancel() }
    }

    private fun atakXml(type: String, time: Long, emergency: String): String {
        val start = Instant.ofEpochMilli(time).toString()
        val stale = Instant.ofEpochMilli(time + 600_000L).toString()
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><event version=\"2.0\" " +
            "uid=\"ANDROID-atak-9-1-1\" type=\"$type\" time=\"$start\" start=\"$start\" stale=\"$stale\" how=\"m-g\">" +
            "<point lat=\"27.95\" lon=\"-82.51\" hae=\"5.0\" ce=\"10.0\" le=\"9999999.0\"/><detail>" +
            "<link uid=\"ANDROID-atak\" type=\"a-f-G-U-C\" relation=\"p-p\"/><contact callsign=\"RA26-Alert\"/>" +
            "$emergency</detail></event>"
    }

    private fun alert(
        uid: String,
        time: Long,
        stale: Long = time + 60_000L,
        senderUid: String = "sender-$uid"
    ) = CotEmergencyEvent(
        uid = uid,
        type = "b-a-o",
        time = time,
        start = time,
        stale = stale,
        point = CotPoint(41.0, -87.0, 100.0, 4.0, 5.0),
        senderUid = senderUid,
        senderCallsign = "SENDER",
        category = "CAT_TEST",
        description = "Test emergency",
        cancelled = false
    )

    private fun pointEvent(uid: String, time: Long): BloodhoundRepository.BloodhoundEvent {
        val cotEvent = CotMarkerEvent(
            version = "2.0",
            uid = uid,
            type = "a-h-G",
            how = "h-g-i-g-o",
            time = time,
            start = time,
            stale = time + 60_000L,
            access = null,
            point = CotPoint(41.0, -87.0, 100.0, 4.0, 5.0),
            detail = CotMarkerDetail(
                markerCallsign = uid,
                link = null,
                creator = null,
                usericon = null,
                remarks = null,
                contact = null
            )
        )
        return BloodhoundRepository.BloodhoundEvent(
            mapItem = CotMarkerMapItem(cotEvent),
            server = "TAK",
            timeIssued = time,
            timeCompleted = null
        )
    }

    private companion object {
        const val SELF_UID = "this-watch"
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
