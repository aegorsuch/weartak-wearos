/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.model.SensorEventParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrmPluginStatusTest {
    private val now = 100_000L
    private val owner = Any()
    private val store = CrmPluginStatusStore().apply { activate(owner) }

    private fun event(
        sequence: Long = 0,
        timestamp: Long = now,
        status: String = "ACCEPTED",
        value: Double? = 0.25,
        enabled: Boolean = true,
    ) = SensorEventParcel(
        CrmPluginStatusStore.SENSOR_KEY,
        timestamp,
        sequence,
        Bundle().apply {
            putString("status", status)
            putBoolean("enabled", enabled)
            if (value != null) putDouble("value", value)
        },
    )

    @Test fun validatesPayloadAndFreshnessAndClearsInvalidReadings() {
        assertTrue(store.publish(owner, event(), now))
        val accepted = store.status.value
        assertEquals(CrmPluginStatus("ACCEPTED", 0.25, true, now), accepted)
        assertFalse(store.publish(owner, event(sequence = 1, value = Double.NaN), now))
        assertNull(store.status.value)
        assertFalse(store.publish(owner, event(sequence = 2, value = 1.01), now))
        assertFalse(store.publish(owner, event(sequence = 3, value = null), now))
        assertFalse(store.publish(owner, event(sequence = 4, status = "REJECTED"), now))
        assertFalse(store.publish(owner, event(sequence = 5, timestamp = now + 1), now))
        assertFalse(store.publish(owner, event(sequence = 6, timestamp = now - 30_001), now))
        assertTrue(store.publish(owner, event(sequence = 7, timestamp = now - 30_000), now))
    }

    @Test fun sequenceAndConnectionFencePreventStaleEvents() {
        assertTrue(store.publish(owner, event(), now))
        assertFalse(store.publish(owner, event(), now))
        val replacement = Any()
        store.activate(replacement)
        assertNull(store.status.value)
        assertFalse(store.publish(owner, event(sequence = 1), now))
        store.clear(owner)
        assertTrue(store.publish(replacement, event(), now))
        store.clear(replacement)
        assertNull(store.status.value)
        assertFalse(store.publish(replacement, event(sequence = 1), now))
    }

    @Test fun inactiveOrDisabledStatesReplaceTheReading() {
        assertTrue(store.publish(owner, event(), now))
        assertTrue(store.publish(owner, event(sequence = 1, status = "IDLE", value = null, enabled = false), now))
        assertEquals(CrmPluginStatus("IDLE", null, false, now), store.status.value)
    }
}
