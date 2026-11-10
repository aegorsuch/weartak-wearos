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
import com.aitech.weartak_civ.cot.CotPoint
import com.aitech.weartak_civ.cot.CotUserPositionEvent
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Replays the p368 LTE trial's inbound passive-SA mix (52 min, map never opened) and counts
 * the user-list projections that downstream consumers would process, with and without deferral.
 * Each projection rebuilds the visible list and wakes the Bloodhound target refresh, vector
 * update and any live map snapshot, each of which walks the whole list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PassiveSaWorkBudgetTest {
    private val now = System.currentTimeMillis()

    private data class Inbound(val atMs: Long, val uid: String, val sourceId: String, val type: String)

    private data class Budget(val publications: Int, val listItemsWalked: Int, val deferred: Long)

    // p368-lte-1026 logcat: 349 user-store upserts from 15:41:43 to 16:34:06.
    private val trialMix: List<Inbound> = buildList {
        fun stream(uid: String, source: String, count: Int, type: String = "a-f-G-U-C", offsetMs: Long = 0) {
            val spacing = TRIAL_MS / count
            repeat(count) { add(Inbound(offsetMs + it * spacing, uid, source, type)) }
        }
        stream("ANDROID-8c3a66b30c4e7e8c", "ditto:phone", 120)
        stream("ANDROID-8c3a66b30c4e7e8c", TAK, 21, offsetMs = 700)
        stream("GRF.sensor.scout-cb04b47f", TAK, 51, "a-u-G", 1_100)
        stream("GRF.sensor.scout-906562e0", TAK, 51, "a-u-G", 10_300)
        stream("ANDROID-707956446d9f2d85", TAK, 43, offsetMs = 3_000)
        stream("ANDROID-2ded908bebdd29f7", TAK, 19, offsetMs = 5_000)
        stream("ANDROID-eeab35450f85ebcf", TAK, 19, offsetMs = 7_000)
        stream("S-1-5-21-3764072333", TAK, 19, offsetMs = 9_000)
        stream("WEAROS_ffd863ef0fd282b4", TAK, 6, offsetMs = 11_000)
    }.sortedBy { it.atMs }

    private suspend fun TestScope.replay(deferral: Boolean): Budget {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        if (deferral) repo.passiveSaDemand.enable()
        repo.clearReceivedUsers()
        var publications = 0
        var walked = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.receivedUserList.collect { users ->
                publications++
                walked += users.size
            }
        }
        runCurrent()
        val start = publications
        val startWalked = walked
        var clock = 0L
        trialMix.forEach { inbound ->
            advanceTimeBy(inbound.atMs - clock)
            clock = inbound.atMs
            val time = now + inbound.atMs
            repo.applyReceivedUserEvent(user(inbound, time), "trial", inbound.sourceId, time)
        }
        advanceTimeBy(5_000)
        runCurrent()
        // The user opens the map once at the end of the trial.
        repo.passiveSaDemand.request(Any())
        runCurrent()
        assertEquals(trialMix.map { it.uid }.toSet(), repo.receivedUserList.value.map { it.uid }.toSet())
        repo.receivedUserList.value.forEach { visible ->
            val newest = trialMix.filter { it.uid == visible.uid }.maxOf { it.atMs }
            assertEquals(now + newest, visible.timeReceived)
        }
        val budget = Budget(publications - start, walked - startWalked, repo.passiveSaDemand.deferredUpdates)
        repo.clearReceivedUsers()
        return budget
    }

    private fun user(inbound: Inbound, time: Long) = CotUserPositionEvent(
        "2.0", inbound.uid, "Blue", inbound.type, "Team Member", "m-g",
        time, time, time + 300_000, null,
        CotPoint(30.0 + inbound.atMs / 1e9, -90.0, 0.0, 10.0, 10.0),
        CotMarkerDetail(inbound.uid, null, null, null, null, null)
    )

    @Test
    fun p368MixProjectsOncePerTrackInsteadOfOncePerUpdate() = runTest {
        val baseline = replay(deferral = false)
        val deferred = replay(deferral = true)
        val firstSightings = trialMix.map { it.sourceId to it.uid }.toSet().size
        val avoided = baseline.publications - deferred.publications
        println(
            "p368 passive SA replay: inbound=${trialMix.size} " +
                "baselinePublications=${baseline.publications} baselineItemsWalked=${baseline.listItemsWalked} " +
                "deferredPublications=${deferred.publications} deferredItemsWalked=${deferred.listItemsWalked} " +
                "deferredUpdates=${deferred.deferred} avoided=$avoided " +
                "avoidedPerInbound=${"%.2f".format(avoided.toDouble() / trialMix.size)}"
        )
        assertTrue(baseline.publications >= (trialMix.size - firstSightings) * 8 / 10)
        // One projection per new source copy, plus the single refresh when the map opens.
        assertTrue(deferred.publications <= firstSightings + 1)
        assertEquals((trialMix.size - firstSightings).toLong(), deferred.deferred)
    }

    private companion object {
        const val TAK = "4890e4de-f011-47c7-9dda-34b63c3e3af4"
        const val TRIAL_MS = 52L * 60_000L
    }
}
