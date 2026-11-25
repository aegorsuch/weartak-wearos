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
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotPoint
import com.aitech.weartak_civ.cot.CotUserPositionEvent
import com.aitech.weartak_civ.data.StaleItemPolicy.CLEANUP_AFTER_STALE_MS
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StaleItemExpiryTest {
    init {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
    }
    private val repo = DataRepository(RuntimeEnvironment.getApplication())
    private val now = System.currentTimeMillis()
    private val stale = now + 60_000

    private fun user(uid: String, source: String?) = runBlocking {
        assertTrue(repo.applyReceivedUserEvent(CotUserPositionEvent(
            "2.0", uid, "Cyan", "a-f-G-U-C", "Team Member", "m-g", now, now, stale, null,
            CotPoint(38.86, -77.05, 0.0, 10.0, 10.0),
            CotMarkerDetail(uid, null, null, null, null, null)
        ), "test", source, now))
    }

    private fun marker(uid: String, type: String, archived: Boolean = false) = runBlocking {
        assertTrue(repo.applyReceivedMarkerEvent(CotMarkerEvent(
            "2.0", uid, type, "h-g-i-g-o", now, now, stale, null, CotPoint(38.86, -77.05, 0.0, 10.0, 10.0),
            CotMarkerDetail(uid, null, null, null, null, null, archived = archived)
        ), "test", "server", now, now))
    }

    private fun users() = repo.receivedUserList.value.map { it.uid }.toSet()
    private fun markers() = repo.receivedMarkerList.value.map { it.uid }.toSet()

    @After fun clear() = runBlocking {
        repo.clearReceivedUsers()
        repo.clearReceivedMarkers()
    }

    @Test fun contactsFromEverySourceStayWhileStaleThenAreRemoved() {
        user("multicast-contact", "multicast:239.2.3.1:6969")
        user("plugin-contact", "plugin:com.meshtastic.weartak")
        user("server-contact", "server")

        repo.pruneStaleItems(stale)
        assertEquals(setOf("multicast-contact", "plugin-contact", "server-contact"), users())
        repo.pruneStaleItems(stale + CLEANUP_AFTER_STALE_MS - 1)
        assertEquals(3, users().size)

        repo.pruneStaleItems(stale + CLEANUP_AFTER_STALE_MS)
        assertEquals(emptySet<String>(), users())
    }

    @Test fun markersFollowAtakStaleDefaults() {
        marker("hostile", "a-h-G")
        marker("unknown", "a-u-G")
        marker("neutral", "a-n-G")
        marker("kept", "a-f-G", archived = true)

        repo.pruneStaleItems(stale)
        assertEquals(setOf("hostile", "neutral", "kept"), markers())

        repo.pruneStaleItems(stale + CLEANUP_AFTER_STALE_MS)
        assertEquals(setOf("kept"), markers())
        repo.pruneStaleItems(stale + 365L * 24 * 3600_000)
        assertEquals(setOf("kept"), markers())
    }

    @Test fun archivedGenericPointOutlivesGenericExpiry() {
        marker("spot", "b-m-p-s-m")
        marker("kept-spot", "b-m-p-s-m", archived = true)
        repo.pruneGenericMarkers(stale)
        assertEquals(setOf("kept-spot"), markers())
    }

    @Test fun archivedGenericPointDoesNotKeepExpiryJobRunning() = runBlocking {
        marker("kept-spot", "b-m-p-s-m", archived = true)
        val field = DataRepository::class.java.getDeclaredField("genericExpiryJob")
        field.isAccessible = true
        val job = field.get(repo) as Job
        withTimeout(10_000) { job.join() }
        assertEquals(setOf("kept-spot"), markers())
    }

    @Test fun refreshedContactIsNotRemoved() = runBlocking {
        user("moving", "server")
        val later = stale + CLEANUP_AFTER_STALE_MS
        assertTrue(repo.applyReceivedUserEvent(CotUserPositionEvent(
            "2.0", "moving", "Cyan", "a-f-G-U-C", "Team Member", "m-g", later - 1_000, later - 1_000,
            later + 60_000, null, CotPoint(38.861, -77.05, 0.0, 10.0, 10.0),
            CotMarkerDetail("moving", null, null, null, null, null)
        ), "test", "server", later - 1_000))
        repo.pruneStaleItems(later)
        assertEquals(setOf("moving"), users())
    }
}
