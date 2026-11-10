/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.aitech.weartak_civ.cot.CotDeletion
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotPoint
import com.aitech.weartak_civ.cot.CotUserPositionEvent
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PassiveSaDeferralTest {
    private val now = System.currentTimeMillis()

    private data class Item(val sourceId: String, val uid: String, val time: Long, val value: String)

    private fun store(defer: () -> Boolean, deferred: MutableList<Unit> = mutableListOf()) =
        SourceAwareCotStore(
            sourceIdOf = Item::sourceId,
            cotUidOf = Item::uid,
            eventTimeOf = Item::time,
            receivedAtOf = Item::time,
            deferProjection = { defer() },
            onDeferred = { deferred.add(Unit) }
        )

    private fun user(
        uid: String = "remote",
        time: Long = now,
        lat: Double = 1.0,
        type: String = "a-f-G-U-C",
        stale: Long = now + 600_000,
        urgent: Boolean = false
    ) = CotUserPositionEvent(
        "2.0", uid, "Blue", type, "Team Member", "m-g",
        time, time, stale, null, CotPoint(lat, 2.0, 0.0, 1.0, 1.0),
        CotMarkerDetail("Remote", null, null, null, null, null, hasUrgentDetail = urgent)
    )

    private fun marker(uid: String, time: Long, lat: Double) = CotMarkerEvent(
        "2.0", uid, "b-m-p-s-m", "h-g-i-g-o", time, time, now + 600_000, null,
        CotPoint(lat, 2.0, 0.0, 1.0, 1.0),
        CotMarkerDetail("Point", null, null, null, null, null)
    )

    private fun repo(scope: kotlinx.coroutines.CoroutineScope): DataRepository {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        return DataRepository(RuntimeEnvironment.getApplication(), projectionScope = scope)
    }

    @Test
    fun storeKeepsNewestWhileDeferredAndPublishesOnceOnFlush() {
        var deferring = true
        val deferred = mutableListOf<Unit>()
        val store = store({ deferring }, deferred)
        store.upsertWithResult(Item("tak", "u", 1, "first"), deferrable = true)
        val firstVersion = store.publicationVersion.value
        assertEquals(listOf("first"), store.visibleItems.value.map(Item::value))

        store.upsertWithResult(Item("tak", "u", 2, "second"), deferrable = true)
        store.upsertWithResult(Item("tak", "u", 3, "third"), deferrable = true)
        store.upsertWithResult(Item("tak", "u", 2, "late-older"), deferrable = true)

        assertEquals(firstVersion, store.publicationVersion.value)
        assertEquals(listOf("first"), store.visibleItems.value.map(Item::value))
        assertEquals("third", store.visibleItem("u")!!.value)
        assertEquals("third", store.snapshot().values.single().value)
        assertEquals(2, deferred.size)

        deferring = false
        assertTrue(store.flushDeferred())
        assertEquals(firstVersion + 1, store.publicationVersion.value)
        assertEquals(listOf("third"), store.visibleItems.value.map(Item::value))
        assertFalse(store.flushDeferred())
    }

    @Test
    fun firstSightingsImmediateUpdatesAndRemovalsStayImmediate() {
        val store = store({ true })
        store.upsertWithResult(Item("tak", "a", 1, "a1"), deferrable = true)
        store.upsertWithResult(Item("ditto:p", "a", 1, "a-mesh"), deferrable = true)
        assertEquals(1, store.visibleItems.value.size)
        store.upsertWithResult(Item("tak", "b", 1, "b1"), deferrable = true)
        assertEquals(listOf("a-mesh", "b1"), store.visibleItems.value.map(Item::value))

        store.upsertWithResult(Item("tak", "a", 2, "a2"), deferrable = true)
        assertEquals("a-mesh", store.visibleItems.value.first().value)
        // A non-passive update publishes now and carries every pending projection with it.
        store.upsertWithResult(Item("tak", "b", 2, "b2-urgent"), deferrable = false)
        assertEquals(listOf("a2", "b2-urgent"), store.visibleItems.value.map(Item::value))

        store.upsertWithResult(Item("tak", "b", 3, "b3"), deferrable = true)
        store.removeIfNotOlder(SourceCotKey("tak", "b"), 3, 3)
        assertEquals(listOf("a2"), store.visibleItems.value.map(Item::value))
        assertFalse(store.flushDeferred())
        assertNull(store.visibleItem("b"))
    }

    @Test
    fun demandFlushesOnFirstViewerAndExemptsBloodhoundTarget() {
        val demand = PassiveSaDemand()
        var flushes = 0
        demand.setOnDemand { flushes++ }
        assertFalse("disabled demand never defers", demand.shouldDefer("u"))
        demand.enable()
        assertTrue(demand.shouldDefer("u"))

        val map = Any()
        val bloodhound = Any()
        demand.request(map)
        demand.request(bloodhound)
        assertEquals(1, flushes)
        assertFalse(demand.shouldDefer("u"))
        demand.release(map)
        assertFalse(demand.shouldDefer("u"))
        demand.release(bloodhound)
        assertTrue(demand.shouldDefer("u"))

        demand.setExemptUid("target")
        assertEquals(2, flushes)
        assertFalse(demand.shouldDefer("target"))
        assertTrue(demand.shouldDefer("other"))
        demand.setExemptUid("target")
        demand.setExemptUid(null)
        assertEquals(2, flushes)
        assertTrue(demand.shouldDefer("target"))
    }

    @Test
    fun lifecycleRequestsOnlyWhileResumed() {
        val demand = PassiveSaDemand().apply { enable() }
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        observePassiveSaDemand(owner.lifecycle, demand)
        assertTrue(demand.shouldDefer("u"))
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertFalse(demand.shouldDefer("u"))
        // Screen off pauses and stops the map activity.
        owner.registry.currentState = Lifecycle.State.STARTED
        assertTrue(demand.shouldDefer("u"))
        owner.registry.currentState = Lifecycle.State.RESUMED
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertTrue(demand.shouldDefer("u"))
    }

    @Test
    fun repositoryDefersPassiveSaButNotUrgentStaleDeletesOrPoints() = runTest {
        val repo = repo(backgroundScope)
        val navigation = BloodhoundRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        repo.passiveSaDemand.enable()
        val viewer = Any()

        repo.applyReceivedUserEvent(user(), "TCP", "tak", now)
        repo.applyReceivedUserEvent(user("GRF.sensor.scout-1", now, 7.0, type = "a-u-G"), "TCP", "tak", now)
        assertEquals(2, repo.receivedUserList.value.size)
        repo.applyReceivedUserEvent(user(time = now + 1, lat = 3.0), "TCP", "tak", now + 1)
        repo.applyReceivedUserEvent(user("GRF.sensor.scout-1", now + 1, 8.0, type = "a-u-G"), "TCP", "tak", now + 1)
        // The first mesh copy of a known UID is a new source copy, so it projects now.
        repo.applyReceivedUserEvent(user(time = now + 2, lat = 4.0), "Ditto", "ditto:phone", now + 2)
        repo.applyReceivedUserEvent(user(time = now + 3, lat = 5.0), "Ditto", "ditto:phone", now + 3)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4.0, repo.receivedUserList.value.first { it.uid == "remote" }.location!!.lat, 0.0)
        assertEquals(8.0, repo.receivedUserList.value.first { it.uid == "GRF.sensor.scout-1" }.location!!.lat, 0.0)
        assertEquals(3L, repo.passiveSaDemand.deferredUpdates)

        // Points keep their existing path regardless of SA demand.
        repo.receiveMarkerEvent(marker("point-1", now, 9.0), navigation, "TCP", "tak", now)
        assertEquals(listOf("point-1"), repo.receivedMarkerList.value.map { it.uid })

        // Urgent tracks publish now, including any pending passive projection.
        repo.applyReceivedUserEvent(user("GRF.sensor.scout-1", now + 2, 10.0, type = "a-u-G", urgent = true),
            "TCP", "tak", now + 2)
        assertEquals(10.0, repo.receivedUserList.value.first { it.uid == "GRF.sensor.scout-1" }.location!!.lat, 0.0)
        assertEquals(5.0, repo.receivedUserList.value.first { it.uid == "remote" }.location!!.lat, 0.0)

        repo.applyReceivedUserEvent(user("GRF.sensor.scout-1", now + 3, 11.0, type = "a-u-G"), "TCP", "tak", now + 3)
        repo.applyReceivedUserEvent(user("GRF.sensor.scout-1", now + 4, stale = now - 1, type = "a-u-G"),
            "TCP", "tak", now + 4)
        assertTrue(repo.receivedUserList.value.none { it.uid == "GRF.sensor.scout-1" })

        repo.applyReceivedUserEvent(user(time = now + 5, lat = 6.0), "Ditto", "ditto:phone", now + 5)
        repo.receiveCotDeletion(CotDeletion("remote", now + 6), "ditto:phone", navigation, now + 6)
        repo.receiveCotDeletion(CotDeletion("remote", now + 6), "tak", navigation, now + 6)
        assertTrue(repo.receivedUserList.value.isEmpty())
        repo.passiveSaDemand.request(viewer)
        assertTrue(repo.receivedUserList.value.isEmpty())
        repo.passiveSaDemand.release(viewer)

        repo.applyReceivedUserEvent(user("late", now + 7, 1.0), "TCP", "tak", now + 7)
        repo.applyReceivedUserEvent(user("late", now + 8, 2.0), "TCP", "tak", now + 8)
        val before = repo.receivedUserList.value
        repo.passiveSaDemand.request(viewer)
        assertEquals(2.0, repo.receivedUserList.value.single().location!!.lat, 0.0)
        assertTrue(before !== repo.receivedUserList.value)
        assertEquals(1L, repo.passiveSaDemand.flushes)
        repo.passiveSaDemand.release(viewer)
        repo.clearReceivedUsers()
        repo.clearReceivedMarkers()
    }
}
