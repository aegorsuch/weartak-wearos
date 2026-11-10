/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.model.GeofenceTrigger
import com.aitech.weartak_civ.data.model.LatLon
import com.aitech.weartak_civ.data.parsers.GeofenceCotParser
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceRepositorySourceTest {
    @Test
    fun clearingNewestSourceRevealsTheRemainingGeofenceCopy() = runTest {
        try {
            GeofenceRepository.sendGeofenceCot(geofence(SERVER_A, "Alpha", eventTime = 100L))
            GeofenceRepository.sendGeofenceCot(geofence(SERVER_B, "Bravo", eventTime = 200L))

            assertEquals("Bravo", GeofenceRepository.geofenceCotFlow.value.single().callsign)

            GeofenceRepository.clearSource(SERVER_B)

            assertEquals("Alpha", GeofenceRepository.geofenceCotFlow.value.single().callsign)
        } finally {
            GeofenceRepository.clearSource(SERVER_A)
            GeofenceRepository.clearSource(SERVER_B)
        }
    }

    @Test
    fun newestCotEventTimeWinsRegardlessOfReceiptOrder() = runTest {
        try {
            GeofenceRepository.sendGeofenceCot(
                geofence(SERVER_A, "Newer event", eventTime = 200L, receivedAt = 100L)
            )
            GeofenceRepository.sendGeofenceCot(
                geofence(SERVER_B, "Later arrival", eventTime = 100L, receivedAt = 200L)
            )

            assertEquals("Newer event", GeofenceRepository.geofenceCotFlow.value.single().callsign)
        } finally {
            GeofenceRepository.clearSource(SERVER_A)
            GeofenceRepository.clearSource(SERVER_B)
        }
    }

    @Test
    fun olderStopEventDoesNotRemoveNewerTrackedGeofence() = runTest {
        try {
            GeofenceRepository.sendGeofenceCot(
                geofence(SERVER_A, "Current", eventTime = 200L, receivedAt = 100L)
            )
            GeofenceRepository.sendGeofenceCot(
                geofence(
                    SERVER_A,
                    "Older stop",
                    eventTime = 100L,
                    receivedAt = 200L,
                    tracking = false
                )
            )

            assertEquals("Current", GeofenceRepository.geofenceCotFlow.value.single().callsign)
        } finally {
            GeofenceRepository.clearSource(SERVER_A)
        }
    }

    @Test
    fun parserRetainsCotEventTime() {
        val parsed = GeofenceCotParser.parse(GEOFENCE_XML)

        assertNotNull(parsed)
        assertEquals(Instant.parse("2026-09-17T12:34:56.000Z").toEpochMilli(), parsed?.eventTime)
    }

    private fun geofence(
        serverId: String,
        callsign: String,
        eventTime: Long,
        receivedAt: Long = eventTime,
        tracking: Boolean = true
    ) = GeofenceCot(
        uid = "same-uid",
        type = "u-d-r",
        callsign = callsign,
        points = listOf(LatLon(41.0, -87.0), LatLon(41.1, -87.0), LatLon(41.1, -87.1)),
        tracking = tracking,
        trigger = GeofenceTrigger.Entry,
        sourceServerId = serverId,
        receivedAt = receivedAt,
        eventTime = eventTime
    )

    private companion object {
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val GEOFENCE_XML = "<event version='2.0' uid='geofence-1' type='u-d-r' time='2026-09-17T12:34:56.000Z' start='2026-09-17T12:34:56.000Z' stale='2026-09-17T12:35:56.000Z' how='h-e'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><contact callsign='SCIF'/><link point='41.0,-87.0'/><link point='41.1,-87.0'/><link point='41.1,-87.1'/><__geofence trigger='Entry' tracking='true'/></detail></event>"
    }
}
