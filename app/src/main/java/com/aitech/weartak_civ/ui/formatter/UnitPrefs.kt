/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.ui.formatter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Display units. The stored key and values are ATAK's Range Units preference
 * (`rab_rng_units_pref`, com.atakmap.coremap.conversions.Span) so a Companion-bonded watch can
 * follow the phone. The watch offers Metric and Imperial only; ATAK's Nautical Miles ("2") is
 * shown as Imperial. Every stored or computed value stays metric; this only changes display.
 */
data class UnitPrefs(val rangeUnits: Int = RANGE_METRIC) {
    val isImperial: Boolean get() = rangeUnits == RANGE_IMPERIAL

    fun toAtakMap(): Map<String, String> = mapOf(KEY_RANGE_UNITS to rangeUnits.toString())

    /**
     * Applies ATAK key/value updates. Keys other than [KEY_RANGE_UNITS] (ATAK's thresholds,
     * speed, altitude and bearing prefs) are ignored; an invalid range value throws so the
     * caller can reject the whole update.
     */
    fun withUpdates(updates: Map<String, Any?>): UnitPrefs {
        if (!updates.containsKey(KEY_RANGE_UNITS)) return this
        return copy(rangeUnits = rangeFromAtak(updates[KEY_RANGE_UNITS]))
    }

    companion object {
        const val KEY_RANGE_UNITS = "rab_rng_units_pref"

        // com.atakmap.coremap.conversions.Span.ENGLISH / METRIC / NM
        const val RANGE_IMPERIAL = 0
        const val RANGE_METRIC = 1
        const val RANGE_NAUTICAL = 2

        /** ATAK's defaults for rng_feet_display_pref and rng_meters_display_pref. */
        const val FEET_TO_MILES_THRESHOLD = 5280
        const val METERS_TO_KM_THRESHOLD = 2000

        val DEFAULT = UnitPrefs()

        /** Parses an ATAK Range Units value; Nautical maps to Imperial. */
        fun rangeFromAtak(raw: Any?): Int = when (parseInt(raw)) {
            RANGE_METRIC -> RANGE_METRIC
            RANGE_IMPERIAL, RANGE_NAUTICAL -> RANGE_IMPERIAL
            else -> throw IllegalArgumentException("Invalid $KEY_RANGE_UNITS")
        }

        fun fromStored(value: String?): UnitPrefs =
            value?.let { runCatching { UnitPrefs(rangeFromAtak(it)) }.getOrNull() } ?: DEFAULT

        private fun parseInt(raw: Any?): Int? = when (raw) {
            is Int -> raw
            is Number -> raw.toDouble().takeIf { it.isFinite() && it == floor(it) && abs(it) < 1e9 }?.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }
}

/** Process-wide current display units; DataRepository keeps it in sync with the stored pref. */
object UnitPrefsState {
    private val _prefs = MutableStateFlow(UnitPrefs.DEFAULT)
    val prefs: StateFlow<UnitPrefs> = _prefs.asStateFlow()

    fun update(prefs: UnitPrefs) {
        _prefs.value = prefs
    }
}

object UnitFormatter {
    const val METERS_PER_FOOT = 0.3048
    const val METERS_PER_MILE = 1609.344

    /**
     * Formats a distance given in meters. Metric: m below 2000 m, then km. Imperial: ft below
     * 5280 ft, then mi (ATAK's default thresholds).
     */
    fun distance(meters: Double?, prefs: UnitPrefs, separator: String = " "): String {
        val m = meters?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        return if (prefs.isImperial) {
            val feet = m / METERS_PER_FOOT
            if (feet < UnitPrefs.FEET_TO_MILES_THRESHOLD) "${feet.toInt()}${separator}ft"
            else "${twoDecimals(m / METERS_PER_MILE)}${separator}mi"
        } else {
            if (m < UnitPrefs.METERS_TO_KM_THRESHOLD) "${m.toInt()}${separator}m"
            else "${twoDecimals(m / 1000.0)}${separator}km"
        }
    }

    fun distanceKm(km: Double?, prefs: UnitPrefs, separator: String = " "): String =
        distance(km?.times(1000.0), prefs, separator)

    /** A short radius label in the smallest unit, e.g. "50 m" or "164 ft". */
    fun shortDistance(meters: Double, prefs: UnitPrefs): String =
        if (prefs.isImperial) "${(meters / METERS_PER_FOOT).roundToLong()} ft"
        else "${meters.roundToLong()} m"

    /** Formats a true bearing in degrees, e.g. "123°T". */
    fun trueBearing(trueBearingDeg: Double?): String {
        val b = trueBearingDeg?.takeIf { it.isFinite() } ?: return "--"
        return "${((b % 360.0 + 360.0) % 360.0).roundToInt() % 360}\u00B0T"
    }

    private fun twoDecimals(value: Double): String = String.format(Locale.US, "%.2f", value)
}
