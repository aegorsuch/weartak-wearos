/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CotStreamFramerTest {
    @Test
    fun framesEventsAcrossChunksAndReturnsEveryCompleteEvent() {
        val framer = CotStreamFramer()

        assertTrue(framer.append("noise${EVENT_A.take(18)}").isEmpty())
        assertEquals(
            listOf(EVENT_A, EVENT_B),
            framer.append(EVENT_A.drop(18) + EVENT_B)
        )
    }

    @Test
    fun malformedPrefixDoesNotBlockTheFollowingValidEvent() {
        val discarded = mutableListOf<CotStreamDiscardReason>()
        val framer = CotStreamFramer(onDiscard = { discarded += it })

        val events = framer.append("<event uid='broken'>$EVENT_A")

        assertEquals(listOf(EVENT_A), events)
        assertEquals(listOf(CotStreamDiscardReason.MALFORMED_PREFIX), discarded)
    }

    @Test
    fun boundsIncompleteEventBuffers() {
        val discarded = mutableListOf<CotStreamDiscardReason>()
        val framer = CotStreamFramer(maxBufferChars = 20, onDiscard = { discarded += it })

        assertTrue(framer.append("<event uid='too-large'").isEmpty())

        assertEquals(listOf(CotStreamDiscardReason.OVERSIZED_EVENT), discarded)
    }

    private companion object {
        const val EVENT_A = "<event uid='a'></event>"
        const val EVENT_B = "<event uid='b'></event>"
    }
}
