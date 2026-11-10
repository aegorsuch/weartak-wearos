/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.events

/**
 * Posted when an alert is cleared (e.g. exit condition met or user cancelled).
 * UI (e.g. UserWarnActivity) can finish when it was showing this alert.
 */
data class AlertClearedEvent(val uid: String)
