/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.events

class IsDeviceWornEvent(val isDeviceWorn: Boolean) {

    companion object {
        val IS_WORN = true
        val IS_NOT_WORN = false
    }
}
