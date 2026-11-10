/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.model

data class TakChannel(
    val name: String,
    val direction: String,
    val active: Boolean,
    val bitPosition: Int,
    val updating: Boolean = false,
)

data class TakServerChannels(
    val serverId: String,
    val serverName: String,
    val status: TakChannelStatus,
    val channels: List<TakChannel>,
    val errorMessage: String? = null,
)

enum class TakChannelStatus {
    LOADING,
    READY,
    EMPTY,
    UNSUPPORTED,
    ERROR,
}
