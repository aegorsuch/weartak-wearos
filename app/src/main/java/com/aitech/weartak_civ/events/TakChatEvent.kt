/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.events

import com.aitech.weartak_civ.cot.ChatRoom

/**
 * Fired by TAKConnectService whenever a COT XML chat message
 * for this device (or “All Chat Rooms”) is received.
 * [parsedRooms] avoids reparsing direct TAK traffic; legacy sources may leave it null.
 */

data class TakChatEvent(
    val rawXml: String,
    val sourceServerId: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val parsedRooms: List<ChatRoom>? = null
) {
    override fun toString() = "TakChatEvent(source=$sourceServerId)"
}
