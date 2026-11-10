/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakChannelJsonTest {
    @Test
    fun parsesSupportAndAssignedChannels() {
        assertTrue(TakChannelJson.parseSupportResponse("""{"data":true}"""))

        val snapshot = TakChannelJson.parseGroupsResponse(GROUPS_RESPONSE)

        assertEquals(2, snapshot.channels.size)
        assertEquals("Alpha", snapshot.channels.first().name)
        assertTrue(snapshot.channels.first().active)
    }

    @Test
    fun updatesEveryPayloadRecordForTheSelectedBitPosition() {
        val current = TakChannelJson.parseGroupsResponse(GROUPS_RESPONSE)

        val updated = TakChannelJson.updateActive(
            payload = current.payload,
            channelBitPosition = 1,
            shouldBeActive = false,
        )

        val matching = updated.payload.filter { it.path("bitpos").asInt() == 1 }
        assertEquals(2, matching.size)
        assertTrue(matching.all { !it.path("active").asBoolean() })
        assertFalse(updated.channels.first { it.bitPosition == 1 }.active)
    }

    @Test
    fun preservesUnselectedGroupsInThePutPayload() {
        val current = TakChannelJson.parseGroupsResponse(GROUPS_RESPONSE)

        val updated = TakChannelJson.updateActive(current.payload, 1, false)

        assertEquals(3, updated.payload.size())
        assertTrue(updated.payload.single { it.path("bitpos").asInt() == 2 }.path("active").asBoolean())
    }

    @Test
    fun updateAndRefreshUrlsContainTheRequiredQueryParameters() {
        val updateUrl = takChannelActiveGroupsUrl("tak.example", "watch uid")
        val refreshUrl = takChannelGroupsUrl("tak.example", sendLatestSa = true)

        assertEquals(8443, updateUrl.port)
        assertEquals("/Marti/api/groups/active", updateUrl.encodedPath)
        assertEquals("watch uid", updateUrl.queryParameter("clientUid"))
        assertEquals("/Marti/api/groups/all", refreshUrl.encodedPath)
        assertEquals("true", refreshUrl.queryParameter("useCache"))
        assertEquals("true", refreshUrl.queryParameter("sendLatestSA"))
    }

    private companion object {
        val GROUPS_RESPONSE = """
            {
              "data": [
                {"name":"Alpha","direction":"IN","active":true,"bitpos":1},
                {"name":"Alpha","direction":"OUT","active":true,"bitpos":1},
                {"name":"Bravo","direction":"IN","active":true,"bitpos":2}
              ]
            }
        """.trimIndent()
    }
}
