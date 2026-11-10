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
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotMarkerMapItem
import com.aitech.weartak_civ.cot.CotPoint
import com.aitech.weartak_civ.data.BloodhoundRepository.BloodhoundItem
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BloodhoundRepositorySourceTest {
    @Test
    fun presentationDemandBoundsGuidanceWorkWithoutGatingIncomingTargetsOrActiveTracking() = runBlocking {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repeat(80) { index ->
            repository.upsertFromEvent(event(SERVER_A, "Target $index", 100L, uid = "target-$index"))
        }
        assertEquals(80, repository.bloodhoundMap.value.size)
        assertTrue(repository.guidanceTargets().isEmpty())
        assertTrue(repository.listBhItemsUi.value.isEmpty())
        repository.activate("target-0")
        assertEquals(setOf("target-0"), repository.guidanceTargets().keys)
        val detail = Any()
        repository.requestPresentation(detail, "target-1")
        assertEquals(setOf("target-0", "target-1"), repository.guidanceTargets().keys)
        val picker = Any()
        repository.requestPresentation(picker)
        assertEquals(80, repository.guidanceTargets().size)
        repository.releasePresentation(picker)
        assertEquals(setOf("target-0", "target-1"), repository.guidanceTargets().keys)
        repository.releasePresentation(detail)
        repository.upsertFromEvent(event(SERVER_A, "Fresh detail", 200L, uid = "target-1"))
        val detailUi = kotlinx.coroutines.withTimeout(5_000) {
            repository.bloodhoundItemUi("target-1").first { it?.target == "Fresh detail" }
        }
        assertEquals("Fresh detail", detailUi?.target)
        assertTrue(repository.listBhItemsUi.value.isEmpty())
        repository.upsertFromEvent(event(SERVER_A, "Current active position", 200L, uid = "target-0"))
        assertEquals("Current active position", repository.guidanceTargets()["target-0"]?.target?.name)
        assertEquals(SERVER_A, repository.activeTrackingItem()?.sourceServerId)
        repository.activate("target-2")
        assertEquals(setOf("target-2"), repository.guidanceTargets().keys)
        assertEquals("target-2", repository.activeTrackingItem()?.uid)
        repository.stopTracking("target-2")
        assertTrue(repository.guidanceTargets().isEmpty())
        assertEquals(80, repository.bloodhoundMap.value.size)
        assertEquals(80, kotlinx.coroutines.withTimeout(5_000) {
            repository.listBhItemsUi.first { it.size == 80 }
        }.size)
    }

    @Test
    fun compassBatchMatchesSequentialFinalStateWithoutIntermediateTargetMaps() = runBlocking {
        val sequential = BloodhoundRepository(mockk<Context>(relaxed = true))
        val batched = BloodhoundRepository(mockk<Context>(relaxed = true))
        for (repository in listOf(sequential, batched)) {
            repository.upsertFromEvent(event(SERVER_A, "Hidden", 100L))
            repository.upsertFromEvent(event(SERVER_B, "Visible", 200L))
            repository.upsertFromEvent(event(SERVER_A, "Other", 100L, uid = "other"))
            repository.activate(UID)
        }
        val snapshots = mutableListOf<Map<String, BloodhoundItem>>()
        val collector = launch(Dispatchers.Unconfined) {
            batched.bloodhoundMap.collect { snapshots.add(it) }
        }
        try {
            val needles = mapOf(UID to 42.0, "other" to 180.0, "absent" to 10.0)
            needles.forEach { (uid, angle) -> sequential.updateBloodhoundCompassNeedle(uid, angle) }
            batched.updateBloodhoundCompassNeedles(needles)

            assertEquals(sequential.bloodhoundMap.value, batched.bloodhoundMap.value)
            assertEquals(2, snapshots.size)
            assertEquals(42.0, snapshots.last()[UID]?.compass?.targetBearingDeg)
            assertEquals(180.0, snapshots.last()["other"]?.compass?.targetBearingDeg)
            assertEquals(UID, batched.activeUid.value)
            assertEquals(SERVER_B, batched.activeTrackingItem()?.sourceServerId)
            assertTrue(batched.activeTrackingItem()?.state?.active == true)
            val unchanged = batched.bloodhoundMap.value
            batched.updateBloodhoundCompassNeedles(needles)
            batched.updateBloodhoundCompassNeedles(emptyMap())
            assertSame(unchanged, batched.bloodhoundMap.value)
            assertEquals(2, snapshots.size)

            sequential.clearSource(SERVER_B)
            batched.clearSource(SERVER_B)
            assertEquals(sequential.bloodhoundMap.value, batched.bloodhoundMap.value)
            assertNull(batched.activeUid.value)
            assertEquals("Hidden", batched.bloodhoundMap.value[UID]?.target?.name)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun companionStopPreservesTargetAndDoesNotStopAnotherUid() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.activate(UID)
        assertFalse(repository.stopTracking("unrelated"))
        assertEquals(UID, repository.activeUid.value)
        assertTrue(repository.stopTracking(UID))
        assertNull(repository.activeUid.value)
        val target = repository.bloodhoundMap.value[UID]
        assertEquals("Alpha", target?.target?.name)
        assertEquals(SERVER_A, target?.sourceServerId)
        assertFalse(target?.state?.active ?: true)
        assertTrue(repository.activate(UID).second?.state?.active == true)
    }

    @Test
    fun companionStartUsesExistingSourceRatherThanOverwritingIt() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        assertTrue(repository.activateCompanionTarget(UID, "Phone", 38.0, -77.0, 0.0))
        assertEquals("Alpha", repository.activeTrackingItem()?.target?.name)
        assertEquals(SERVER_A, repository.activeTrackingItem()?.sourceServerId)
        assertEquals(41.0, repository.activeTrackingItem()?.target?.point?.lat)
        assertTrue(repository.updateCompanionTarget(UID, "Phone update", 39.0, -77.0, 2.0))
        assertEquals(41.0, repository.activeTrackingItem()?.target?.point?.lat)
        assertEquals("Alpha", repository.activeTrackingItem()?.target?.name)
    }

    @Test
    fun companionStartCreatesMissingTargetAndTrackingOnlyStopKeepsIt() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        assertTrue(repository.activateCompanionTarget(UID, "Phone", 38.0, -77.0, 12.0))
        assertEquals(UID, repository.activeUid.value)
        assertEquals(12.0, repository.activeTrackingItem()?.target?.point?.hae)
        assertEquals("companion-bloodhound", repository.activeTrackingItem()?.sourceServerId)
        assertTrue(repository.updateCompanionTarget(UID, "Moving target", 39.0, -78.0, 13.0))
        assertEquals(39.0, repository.activeTrackingItem()?.target?.point?.lat)
        assertEquals(UID, repository.activeUid.value)
        assertTrue(repository.stopTracking(UID))
        assertEquals("Moving target", repository.bloodhoundMap.value[UID]?.target?.name)
        assertFalse(repository.updateCompanionTarget(UID, "Ended target", 40.0, -78.0, 13.0))
        assertNull(repository.bloodhoundMap.value[UID]?.bhEvent?.remoteAlert)
        assertTrue(repository.activateCompanionTarget(UID, "Restarted target", 40.0, -79.0, 14.0))
        assertEquals(40.0, repository.activeTrackingItem()?.target?.point?.lat)
        assertEquals("Restarted target", repository.activeTrackingItem()?.target?.name)
    }

    @Test
    fun invalidCompanionTargetDoesNotReplaceLocalTracking() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.activate(UID)
        assertFalse(repository.activateCompanionTarget("bad-target", "Bad", 91.0, -77.0, 0.0))
        assertEquals(UID, repository.activeUid.value)
        assertNull(repository.bloodhoundMap.value["bad-target"])
    }

    @Test
    fun realTargetCanReplaceCompanionSnapshotWithoutEndingTracking() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        assertTrue(repository.activateCompanionTarget(UID, "Phone", 38.0, -77.0, 12.0))
        repository.upsertFromEvent(event(SERVER_A, "Live target", System.currentTimeMillis() + 1_000L))
        assertEquals(UID, repository.activeUid.value)
        assertEquals(SERVER_A, repository.activeTrackingItem()?.sourceServerId)
        assertEquals("Live target", repository.activeTrackingItem()?.target?.name)
        assertTrue(repository.activeTrackingItem()?.state?.active == true)
    }

    @Test
    fun clearingActiveSourceStopsSelectionAndRevealsRemainingUidCopy() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.upsertFromEvent(event(SERVER_B, "Bravo", receivedAt = 200L))

        assertEquals("Bravo", repository.bloodhoundMap.value[UID]?.target?.name)
        repository.activate(UID)
        assertEquals(UID, repository.activeUid.value)

        repository.clearSource(SERVER_B)

        assertNull(repository.activeUid.value)
        assertEquals("Alpha", repository.bloodhoundMap.value[UID]?.target?.name)
        assertEquals(SERVER_A, repository.bloodhoundMap.value[UID]?.sourceServerId)
    }

    @Test
    fun removingBloodhoundFromMenuRemovesVisibleItemAndClearsActiveSelection() = kotlinx.coroutines.runBlocking {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.activate(UID)

        val removed = repository.removeFromMenu(UID)

        assertEquals("Alpha", removed?.target?.name)
        assertNull(repository.bloodhoundMap.value[UID])
        assertNull(repository.activeUid.value)
    }

    @Test
    fun removingTargetDropsEveryUidCopyAndClearsActiveSelection() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.upsertFromEvent(event(SERVER_B, "Bravo", receivedAt = 200L))
        repository.activate(UID)

        val removed = repository.removeTarget(UID)

        assertEquals(2, removed.size)
        assertNull(repository.bloodhoundMap.value[UID])
        assertNull(repository.activeUid.value)
    }

    private fun event(
        serverId: String,
        title: String,
        receivedAt: Long,
        uid: String = UID
    ): BloodhoundRepository.BloodhoundEvent {
        val marker = CotMarkerEvent(
            version = "2.0",
            uid = uid,
            type = "a-h-G",
            how = "h-g-i-g-o",
            time = receivedAt,
            start = receivedAt,
            stale = Long.MAX_VALUE,
            access = null,
            point = CotPoint(41.0, -87.0, 100.0, 4.0, 5.0),
            detail = CotMarkerDetail(
                markerCallsign = title,
                link = null,
                creator = null,
                usericon = null,
                remarks = null,
                contact = null
            )
        )
        return BloodhoundRepository.BloodhoundEvent(
            mapItem = CotMarkerMapItem(marker),
            server = "$title.example",
            timeIssued = receivedAt,
            timeCompleted = null,
            sourceServerId = serverId,
            receivedAt = receivedAt
        )
    }

    private companion object {
        const val UID = "same-uid"
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
