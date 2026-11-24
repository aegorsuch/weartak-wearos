/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgSampleDeliveryTest {
    @Test
    fun exclusiveConsumerReceivesEachSampleAndOnlyItsOwnerCanRelease() {
        val received = mutableListOf<RawPpgSample>()
        val first = PpgSampleDelivery.Consumer { received.add(it) }
        val other = PpgSampleDelivery.Consumer { error("unexpected consumer") }
        val sample = RawPpgSample(42f, 100L, 200L, sourceAvailable = true, deviceWorn = true)

        assertTrue(PpgSampleDelivery.acquire(first))
        try {
            assertFalse(PpgSampleDelivery.acquire(other))
            assertFalse(PpgSampleDelivery.release(other))
            PpgSampleDelivery.publish(sample)
            PpgSampleDelivery.publish(sample)
            assertEquals(listOf(sample, sample), received)
        } finally {
            assertTrue(PpgSampleDelivery.release(first))
        }
        PpgSampleDelivery.publish(sample)
        assertEquals(2, received.size)
    }
}
