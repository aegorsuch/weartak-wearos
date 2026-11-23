package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.StaleItemPolicy.CLEANUP_AFTER_STALE_MS
import org.junit.Assert.*
import org.junit.Test

class StaleItemPolicyTest {
    private val stale = 1_000_000L

    @Test fun contactIsKeptStaleForTheCleanUpTimeThenRemoved() {
        assertFalse(StaleItemPolicy.isStale(stale, stale - 1))
        assertTrue(StaleItemPolicy.isStale(stale, stale))
        assertFalse(StaleItemPolicy.removeUser(stale, stale))
        assertFalse(StaleItemPolicy.removeUser(stale, stale + CLEANUP_AFTER_STALE_MS - 1))
        assertTrue(StaleItemPolicy.removeUser(stale, stale + CLEANUP_AFTER_STALE_MS))
    }

    @Test fun markerFollowsAtakDefaults() {
        val later = stale + CLEANUP_AFTER_STALE_MS
        assertFalse(StaleItemPolicy.removeMarker("a-h-G", stale, archived = false, now = stale))
        assertTrue(StaleItemPolicy.removeMarker("a-h-G", stale, archived = false, now = later))
        // Unknowns go as soon as they are stale
        assertTrue(StaleItemPolicy.removeMarker("a-u-G", stale, archived = false, now = stale))
        assertTrue(StaleItemPolicy.removeMarker("a-u", stale, archived = false, now = stale))
        assertFalse(StaleItemPolicy.removeMarker("a-u-G", stale, archived = false, now = stale - 1))
        // Archived items are never removed for being stale
        assertFalse(StaleItemPolicy.removeMarker("a-h-G", stale, archived = true, now = Long.MAX_VALUE))
        assertFalse(StaleItemPolicy.removeMarker("a-u-G", stale, archived = true, now = Long.MAX_VALUE))
    }

    @Test fun unknownAffiliationIsTheAtomTypeOnly() {
        assertTrue(StaleItemPolicy.isUnknown("a-u-A-M-F"))
        assertFalse(StaleItemPolicy.isUnknown("a-uX"))
        assertFalse(StaleItemPolicy.isUnknown("a-n-G"))
        assertFalse(StaleItemPolicy.isUnknown("b-m-p-s-m"))
    }
}
