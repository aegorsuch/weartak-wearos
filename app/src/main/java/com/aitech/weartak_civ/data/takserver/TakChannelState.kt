/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.model.TakChannelStatus
import com.aitech.weartak_civ.data.model.TakServerChannels

internal object TakChannelState {
    fun beginUpdate(
        previous: TakServerChannels,
        channelBitPosition: Int,
        shouldBeActive: Boolean,
    ): TakServerChannels = previous.copy(
        status = TakChannelStatus.READY,
        channels = previous.channels.map { channel ->
            if (channel.bitPosition == channelBitPosition) {
                channel.copy(active = shouldBeActive, updating = true)
            } else {
                channel
            }
        },
        errorMessage = null,
    )

    fun updateFailed(previous: TakServerChannels, message: String): TakServerChannels =
        previous.copy(
            status = TakChannelStatus.ERROR,
            channels = previous.channels.map { it.copy(updating = false) },
            errorMessage = message,
        )

    fun refreshFailed(confirmed: TakServerChannels, message: String): TakServerChannels =
        confirmed.copy(
            status = TakChannelStatus.ERROR,
            channels = confirmed.channels.map { it.copy(updating = false) },
            errorMessage = message,
        )
}
