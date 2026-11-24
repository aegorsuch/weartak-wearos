/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.integration.plugin

import com.weartak.plugin.sdk.model.SensorEventParcel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CrmPluginStatus(
    val status: String,
    val value: Double?,
    val enabled: Boolean,
    val updatedAtMillis: Long,
)

internal class CrmPluginStatusStore {
    private val lock = Any()
    private val mutableStatus = MutableStateFlow<CrmPluginStatus?>(null)
    val status = mutableStatus.asStateFlow()
    private var owner: Any? = null
    private var lastSequence = -1L

    fun activate(connection: Any) = synchronized(lock) {
        owner = connection
        lastSequence = -1L
        mutableStatus.value = null
    }

    fun clear(connection: Any) = synchronized(lock) {
        if (owner === connection) {
            owner = null
            lastSequence = -1L
            mutableStatus.value = null
        }
    }

    fun invalidate(connection: Any) = synchronized(lock) {
        if (owner === connection) mutableStatus.value = null
    }

    fun publish(connection: Any, event: SensorEventParcel, nowMillis: Long): Boolean =
        synchronized(lock) {
            if (owner !== connection || event.sensorKey != SENSOR_KEY ||
                event.sequence < 0 || event.sequence <= lastSequence
            ) return@synchronized false

            lastSequence = event.sequence
            mutableStatus.value = null
            if (event.timestampUtcMillis <= 0 || event.timestampUtcMillis > nowMillis ||
                nowMillis - event.timestampUtcMillis > MAX_AGE_MILLIS
            ) return@synchronized false
            val values = event.values
            val state = values.get("status") as? String ?: return@synchronized false
            val enabled = values.get("enabled") as? Boolean ?: return@synchronized false
            if (state !in VALID_STATES) return@synchronized false
            val value = if (state == "ACCEPTED") {
                (values.get("value") as? Double)?.takeIf { it.isFinite() && it in 0.0..1.0 }
                    ?: return@synchronized false
            } else {
                if (values.containsKey("value")) return@synchronized false
                null
            }

            mutableStatus.value = CrmPluginStatus(state, value, enabled, event.timestampUtcMillis)
            true
        }

    companion object {
        const val SENSOR_KEY = "crm_status_v1"
        private const val MAX_AGE_MILLIS = 30_000L
        private val VALID_STATES = setOf("IDLE", "ACCEPTED", "REJECTED", "ERROR", "NOT_WORN")
    }
}
