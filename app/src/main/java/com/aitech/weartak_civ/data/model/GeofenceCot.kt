/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.model

data class GeofenceCot(
    val uid: String,
    val type: String,
    val callsign: String,
    val points: List<LatLon>,
    val tracking: Boolean,
    val trigger: GeofenceTrigger,
    val sourceServerId: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val eventTime: Long = receivedAt
)

enum class GeofenceTrigger {
    Entry, Exit, Both
}
