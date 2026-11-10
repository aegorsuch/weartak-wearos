/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.events

class TakServerConnStatusEvent(val connEvent: Int) {

    companion object {
        val CONNECTING = 0
        val CONNECTION_FAILURE = 1
        val CONNECTION_SUCCESS = 2
        val DISCONNECTED = 3
    }
}
