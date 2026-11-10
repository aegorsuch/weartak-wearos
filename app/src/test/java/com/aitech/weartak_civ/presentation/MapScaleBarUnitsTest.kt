/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.ui.formatter.UnitPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapScaleBarUnitsTest {
    private val metric = UnitPrefs.DEFAULT
    private val imperial = UnitPrefs(UnitPrefs.RANGE_IMPERIAL)

    @Test
    fun defaultsMatchSelectedUnits() {
        assertEquals("50 m", scaleBarFor(1234.0, true, metric).first)
        val (label, meters) = scaleBarFor(1234.0, true, imperial)
        assertEquals("200 ft", label)
        assertEquals(60.96, meters, 0.001)
    }

    @Test
    fun scaleLabelsUseUnitsAndNeverExceedAvailableLength() {
        for (max in listOf(30.0, 400.0, 1500.0, 9000.0, 120_000.0)) {
            val (m, mMeters) = scaleBarFor(max, false, metric)
            val (i, iMeters) = scaleBarFor(max, false, imperial)
            assertTrue(m, m.endsWith(" m") || m.endsWith(" km"))
            assertTrue(i, i.endsWith(" ft") || i.endsWith(" mi"))
            assertTrue("$m > $max", mMeters <= max + 1e-6)
            assertTrue("$i > $max", iMeters <= max + 1e-6)
        }
        assertTrue(scaleBarFor(9000.0, false, imperial).first.endsWith(" mi"))
        assertTrue(scaleBarFor(400.0, false, imperial).first.endsWith(" ft"))
        assertTrue(scaleBarFor(9000.0, false, metric).first.endsWith(" km"))
    }
}
