package com.aitech.weartak_civ.data.takgov

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TakGovUserInfoTest {
    @Test
    fun exactMilitaryGroupWinsOverPublicGroup() {
        assertEquals(
            TakGovAccessLevel.MIL,
            TakGovUserInfo.accessLevel("""{"groups":["Website Public","Website Military"]}"""),
        )
    }

    @Test
    fun exactPublicGroupMapsToCivilian() {
        assertEquals(
            TakGovAccessLevel.CIV,
            TakGovUserInfo.accessLevel("""{"groups":["Website Public"]}"""),
        )
    }

    @Test
    fun unknownOrSimilarGroupNamesDoNotMap() {
        assertNull(
            TakGovUserInfo.accessLevel(
                """{"groups":["Not Website Military","Website Military Test","website public"]}"""
            )
        )
    }

    @Test
    fun malformedOrMissingGroupsDoNotMap() {
        assertNull(TakGovUserInfo.accessLevel("""{"groups":"Website Military"}"""))
        assertNull(TakGovUserInfo.accessLevel("""{"groups":["Website Military",3]}"""))
        assertNull(TakGovUserInfo.accessLevel("""{"roles":["Website Military"]}"""))
        assertNull(TakGovUserInfo.accessLevel("not json"))
    }

    @Test
    fun accessLevelChecksAreBoundedToOncePerDay() {
        val firstCheckAt = 1_000_000L
        assertFalse(isTakGovAccessLevelCheckDue(firstCheckAt, firstCheckAt + 1_000L))
        assertTrue(
            isTakGovAccessLevelCheckDue(
                firstCheckAt,
                firstCheckAt + TAKGOV_ACCESS_LEVEL_RECHECK_INTERVAL_MILLIS,
            )
        )
    }
}
