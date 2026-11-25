package com.aitech.weartak_civ.data

/**
 * When a received contact or marker is stale, and when it is removed, following ATAK's defaults.
 *
 * ATAK's Stale Data Preferences (res/xml/stale_data_prefs.xml in ATAK 5.8), all on by default:
 *
 * - `expireEverything` with `expireStaleItemsTime` = 5: every non-archival item is deleted once it
 *   has been stale for five minutes. Until then it stays on the map, marked stale.
 * - `expireUnknowns`: Unknown-affiliation markers (`a-u-*`) are deleted as soon as they go stale.
 * - Archival items (`<archive/>` in the CoT detail) are never deleted by staleness.
 *
 * Before this, WearTAK checked `stale` only on arrival, so a contact that stopped reporting stayed on
 * the map until the app restarted - for days in the field.
 */
object StaleItemPolicy {
    /** ATAK `expireStaleItemsTime` default: minutes an item stays, marked stale, before removal. */
    const val CLEANUP_AFTER_STALE_MS: Long = 5 * 60_000L

    /** How often stale items are looked for; removal is minutes after stale, so this need not be tight. */
    const val CHECK_INTERVAL_MS: Long = 15_000L

    fun isStale(staleTime: Long, now: Long): Boolean = staleTime <= now

    /** A contact: removed once it has been stale for the clean-up time. */
    fun removeUser(staleTime: Long, now: Long): Boolean =
        staleTime <= now - CLEANUP_AFTER_STALE_MS

    /**
     * A marker: never if archived; an Unknown as soon as it is stale; anything else once it has been
     * stale for the clean-up time.
     */
    fun removeMarker(markerType: String, staleTime: Long, archived: Boolean, now: Long): Boolean = when {
        archived -> false
        isUnknown(markerType) -> isStale(staleTime, now)
        else -> staleTime <= now - CLEANUP_AFTER_STALE_MS
    }

    /** CoT atom type with Unknown affiliation: `a-u` or `a-u-...`. */
    internal fun isUnknown(type: String): Boolean = type == "a-u" || type.startsWith("a-u-")
}
