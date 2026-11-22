package com.aitech.weartak_civ.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDetectionInferenceResultTest {
    @Test
    fun invalidConfidenceIsFailedInferenceNotDetection() {
        for (score in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 1.1f)) {
            val result = detectorResultForScore(score, 0.9f)
            assertFalse(result.inferenceSucceeded)
            assertFalse(result.detected)
            assertNull(result.confidence)
            assertTrue(result.errorMessage!!.contains("invalid confidence"))
        }
    }

    @Test
    fun finiteConfidencePreservesThresholdBehavior() {
        val below = detectorResultForScore(0.89f, 0.9f)
        val at = detectorResultForScore(0.9f, 0.9f)
        assertTrue(below.inferenceSucceeded)
        assertFalse(below.detected)
        assertTrue(at.inferenceSucceeded)
        assertTrue(at.detected)
        assertEquals(0.9f, at.confidence!!, 0f)
    }
}
