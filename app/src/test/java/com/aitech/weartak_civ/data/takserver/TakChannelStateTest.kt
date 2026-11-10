/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.model.TakChannel
import com.aitech.weartak_civ.data.model.TakChannelStatus
import com.aitech.weartak_civ.data.model.TakServerChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakChannelStateTest {
    @Test
    fun beginUpdateChangesOnlyTheSelectedChannel() {
        val updating = TakChannelState.beginUpdate(serverState(), 1, false)

        assertFalse(updating.channels.first { it.bitPosition == 1 }.active)
        assertTrue(updating.channels.first { it.bitPosition == 1 }.updating)
        assertTrue(updating.channels.first { it.bitPosition == 2 }.active)
        assertFalse(updating.channels.first { it.bitPosition == 2 }.updating)
    }

    @Test
    fun failedUpdateRestoresThePreviousToggle() {
        val previous = serverState()
        val failed = TakChannelState.updateFailed(previous, "Channel update failed")

        assertTrue(failed.channels.first { it.bitPosition == 1 }.active)
        assertFalse(failed.channels.any { it.updating })
        assertEquals(TakChannelStatus.ERROR, failed.status)
    }

    @Test
    fun failedRefreshKeepsTheConfirmedToggle() {
        val confirmed = serverState().copy(
            channels = serverState().channels.map { channel ->
                if (channel.bitPosition == 1) channel.copy(active = false) else channel
            }
        )

        val failed = TakChannelState.refreshFailed(confirmed, "Refresh failed")

        assertFalse(failed.channels.first { it.bitPosition == 1 }.active)
        assertEquals(TakChannelStatus.ERROR, failed.status)
    }

    private fun serverState() = TakServerChannels(
        serverId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        serverName = "TAK One",
        status = TakChannelStatus.READY,
        channels = listOf(
            TakChannel("Alpha", "IN", active = true, bitPosition = 1),
            TakChannel("Bravo", "IN", active = true, bitPosition = 2),
        ),
    )
}
