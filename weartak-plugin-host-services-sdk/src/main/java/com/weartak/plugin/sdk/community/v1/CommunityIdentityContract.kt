package com.weartak.plugin.sdk.community.v1

object CommunityIdentityContract {
    const val VERSION = 1
    const val EXTRA_VERSION = "com.weartak.plugin.ditto.community.version"
    const val EXTRA_CONTROL = "com.weartak.plugin.ditto.community.v1"
    const val KEY_STATUS = "status"
    const val KEY_IDENTITY = "identity"
    const val OK = "ok"
    const val UNAUTHORIZED = "unauthorized"
    const val INACTIVE = "inactive"
    const val UNAVAILABLE = "unavailable"
    const val MAX_IDENTITY_LENGTH = 16_384
}
