/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.takgov

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class TakGovAccessLevel {
    CIV,
    MIL,
}

internal const val TAKGOV_ACCESS_LEVEL_RECHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L

internal fun isTakGovAccessLevelCheckDue(lastCheckedAtMillis: Long, nowMillis: Long): Boolean =
    lastCheckedAtMillis <= 0L ||
        nowMillis - lastCheckedAtMillis >= TAKGOV_ACCESS_LEVEL_RECHECK_INTERVAL_MILLIS

internal object TakGovUserInfo {
    fun accessLevel(json: String): TakGovAccessLevel? {
        return try {
            val groups = JSONObject(json).opt("groups") as? JSONArray ?: return null
            val names = mutableListOf<String>()
            for (index in 0 until groups.length()) {
                val group = groups.opt(index) as? String ?: return null
                names.add(group)
            }
            when {
                "Website Military" in names -> TakGovAccessLevel.MIL
                "Website Public" in names -> TakGovAccessLevel.CIV
                else -> null
            }
        } catch (_: JSONException) {
            null
        }
    }
}
