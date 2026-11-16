package com.weartak.plugin.sdk.openvpn.v1

import android.os.Bundle

/** Optional raw TCP extension. It is separate from the CoT/chat transport API. */
object OpenVpnHostContract {
    const val API_VERSION = 1
    const val EXTRA_VERSION = "com.weartak.plugin.openvpn.host.version"
    const val EXTRA_BINDER = "com.weartak.plugin.openvpn.host.v1"
    const val PLUGIN_PACKAGE = "com.aitech.weartak.openvpn"

    const val KEY_STATUS = "status"
    const val KEY_MESSAGE = "message"
    const val KEY_SERVERS = "servers"
    const val KEY_SERVER_ID = "serverId"
    const val KEY_SERVER_NAME = "name"
    const val KEY_SERVER_ADDRESS = "address"
    const val KEY_SERVER_PORT = "port"
    const val KEY_SELECTED_PROFILE_ID = "selectedProfileId"
    const val KEY_SELECTED_PLUGIN_PACKAGE = "selectedPluginPackage"
    const val KEY_PROFILE_ID = "profileId"

    const val OK = "ok"
    const val INVALID_REQUEST = "invalid_request"
    const val UNAUTHORIZED = "unauthorized"
    const val CLOSED = "closed"
    const val NOT_FOUND = "not_found"
    const val LOCKED = "locked"
    const val UNAVAILABLE = "unavailable"
    const val UNSUPPORTED = "unsupported"

    const val PROVIDER_API_VERSION = 1
    const val MAX_ACTIVE_STREAMS = 4
    const val MAX_IO_BYTES = 16 * 1024
    const val MAX_TIMEOUT_MS = 60_000L
    const val IO_TIMEOUT_RESULT = -7
    const val MAX_PROFILE_ID_LENGTH = 128
    const val MAX_SERVER_ID_LENGTH = 128

    const val RUNTIME_STOPPED = 0
    const val RUNTIME_CONNECTING = 1
    const val RUNTIME_CONNECTED = 2
    const val RUNTIME_STOPPING = 3
    const val RUNTIME_ERROR = 4

    @JvmStatic
    fun fromHostExtras(extras: Bundle): IWearTakOpenVpnHostServicesV1? =
        if (extras.getInt(EXTRA_VERSION) == API_VERSION) {
            extras.getBinder(EXTRA_BINDER)?.let(IWearTakOpenVpnHostServicesV1.Stub::asInterface)
        } else null

    @JvmStatic
    fun result(status: String, message: String = "") = Bundle().apply {
        putString(KEY_STATUS, status)
        putString(KEY_MESSAGE, message)
    }
}
