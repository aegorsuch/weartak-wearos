/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.ui.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UnitPrefsTest {
    private val metric = UnitPrefs.DEFAULT
    private val imperial = UnitPrefs(UnitPrefs.RANGE_IMPERIAL)

    @Test
    fun defaultIsMetricAndAtakValuesMap() {
        assertEquals(UnitPrefs.RANGE_METRIC, metric.rangeUnits)
        assertEquals(mapOf("rab_rng_units_pref" to "1"), metric.toAtakMap())
        assertEquals(imperial, metric.withUpdates(mapOf("rab_rng_units_pref" to "0")))
        assertEquals(metric, imperial.withUpdates(mapOf("rab_rng_units_pref" to 1.0)))
        // ATAK Nautical Miles is shown as Imperial on the watch.
        assertEquals(imperial, metric.withUpdates(mapOf("rab_rng_units_pref" to "2")))
    }

    @Test
    fun otherAtakKeysAreIgnoredAndBadRangeRejected() {
        val other = mapOf("rng_feet_display_pref" to "junk", "speed_unit_pref" to "3", "rab_north_ref_pref" to "1")
        assertEquals(metric, metric.withUpdates(other))
        assertThrows(IllegalArgumentException::class.java) { metric.withUpdates(mapOf("rab_rng_units_pref" to "3")) }
        assertThrows(IllegalArgumentException::class.java) { metric.withUpdates(mapOf("rab_rng_units_pref" to "x")) }
        assertThrows(IllegalArgumentException::class.java) { metric.withUpdates(mapOf("rab_rng_units_pref" to null)) }
        assertEquals(metric, UnitPrefs.fromStored("garbage"))
        assertEquals(metric, UnitPrefs.fromStored(null))
    }

    @Test
    fun metricSwitchesToKilometersAtAtakThreshold() {
        assertEquals("850 m", UnitFormatter.distance(850.4, metric))
        assertEquals("1999 m", UnitFormatter.distance(1999.9, metric))
        assertEquals("2.00 km", UnitFormatter.distance(2000.0, metric))
        assertEquals("12.35km", UnitFormatter.distanceKm(12.345, metric, separator = ""))
        assertEquals("0 m", UnitFormatter.distance(Double.NaN, metric))
    }

    @Test
    fun imperialSwitchesToMilesAt5280Feet() {
        assertEquals("164 ft", UnitFormatter.distance(50.0, imperial))
        assertEquals("5279 ft", UnitFormatter.distance(5279.5 * 0.3048, imperial))
        assertEquals("1.00 mi", UnitFormatter.distance(1609.344, imperial))
        assertEquals("164 ft", UnitFormatter.shortDistance(50.0, imperial))
        assertEquals("50 m", UnitFormatter.shortDistance(50.0, metric))
    }

    @Test
    fun bearingsAreLabelledTrue() {
        assertEquals("123°T", UnitFormatter.trueBearing(123.4))
        assertEquals("0°T", UnitFormatter.trueBearing(359.7))
        assertEquals("270°T", UnitFormatter.trueBearing(-90.0))
        assertEquals("--", UnitFormatter.trueBearing(null))
    }
}
