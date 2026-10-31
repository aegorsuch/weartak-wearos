package com.aitech.weartak_civ.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevBadgeInitializerTest {
    @Test fun emulatorPreviewsHideBadgeAndPhysicalDebugBuildsKeepIt() {
        assertFalse(showDevBadge("ranchu"))
        assertFalse(showDevBadge("goldfish"))
        assertTrue(showDevBadge("samsungexynos"))
        assertTrue(showDevBadge("qcom"))
    }
}
