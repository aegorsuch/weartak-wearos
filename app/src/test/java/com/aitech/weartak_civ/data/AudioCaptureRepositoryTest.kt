/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioCaptureRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun consumerLeases_areIdempotentAndReleaseOnlyTheNamedOwner() {
        val repository = AudioCaptureRepository(context)

        assertTrue(repository.acquireConsumer("core:gunshot"))
        assertFalse(repository.acquireConsumer("core:gunshot"))
        assertTrue(repository.acquireConsumer("plugin:uas"))
        assertEquals(setOf("core:gunshot", "plugin:uas"), repository.activeConsumers.value)

        assertTrue(repository.releaseConsumer("core:gunshot"))
        assertFalse(repository.releaseConsumer("core:gunshot"))
        assertEquals(setOf("plugin:uas"), repository.activeConsumers.value)

        assertTrue(repository.releaseConsumer("plugin:uas"))
        assertTrue(repository.activeConsumers.value.isEmpty())
    }
}
