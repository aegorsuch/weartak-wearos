package com.weartak.plugin.sdk.ditto.v1

/** Unpublished discovery and ownership protocol, independent of host lifecycle versions. */
object DittoContractV1 {
    const val VERSION = 1
    const val PLUGIN_PACKAGE = "com.weartak.plugin.ditto"
    const val TRACKER_PACKAGE = "com.tak.weartak_tracker"
    const val TRACKER_SERVICE = "com.weartak.plugin.ditto.tracker.TrackerDittoServiceV1"
    const val TRACKER_ACTION = "com.weartak.plugin.ditto.action.TRACKER_DITTO_V1"
    const val TRACKER_PERMISSION = "com.weartak.plugin.ditto.permission.BIND_TRACKER_DITTO_V1"

    const val AVAILABILITY_UNAVAILABLE = 0
    const val AVAILABILITY_AVAILABLE = 1

    const val LEASE_UNAVAILABLE = 0
    const val LEASE_ACQUIRED = 1
    const val LEASE_BUSY = 2
    const val LEASE_STOPPED = 3
    const val LEASE_STOPPING = 4
    const val LEASE_STALE = 5
    const val LEASE_UNCERTAIN = 6
}
