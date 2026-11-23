package com.aitech.weartak_civ.integration.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DittoTrafficFenceTest {
    @Test
    fun disableRevokesOldGenerationAndWaitsForAdmittedEffects() {
        val fence = DittoTrafficFence(initiallyEnabled = true)
        val old = requireNotNull(fence.currentGeneration())
        val admitted = requireNotNull(fence.enter(old))

        val drain = fence.disable()
        assertNull(fence.currentGeneration())
        assertNull(fence.enter(old))
        assertNull(fence.enable())
        assertFalse(drain.isCompleted)

        admitted.close()
        admitted.close()
        assertTrue(drain.isCompleted)
        val next = requireNotNull(fence.enable())
        assertNotEquals(old, next)
        assertNull(fence.enter(old))
        requireNotNull(fence.enter(next)).close()
    }

    @Test
    fun initiallyDisabledFenceRejectsAdmissionsUntilEnabled() {
        val fence = DittoTrafficFence(initiallyEnabled = false)
        assertNull(fence.currentGeneration())
        assertTrue(fence.disable().isCompleted)
        requireNotNull(fence.enter(requireNotNull(fence.enable()))).close()
    }
}
