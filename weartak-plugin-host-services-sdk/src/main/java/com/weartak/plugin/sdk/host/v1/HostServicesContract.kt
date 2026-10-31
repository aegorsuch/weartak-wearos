package com.weartak.plugin.sdk.host.v1

import android.os.Bundle

/** Additive extension; does not change IWearTakPlugin.getApiVersion() or its lifecycle. */
object HostServicesContract {
    const val SDK_VERSION = "1.0.0"
    const val VERSION = 1
    const val EXTRA_VERSION = "com.weartak.plugin.host.services.version"
    const val EXTRA_BINDER = "com.weartak.plugin.host.services.v1"

    const val KEY_VERSION = "version"
    const val KEY_MAP_ACTIONS = "mapActions"
    const val KEY_ACTIVE_BLOODHOUND_UID = "activeBloodhoundUid"
    const val KEY_RETURN_TO_MAIN = "returnToMain"
    const val KEY_TAK_HTTPS = "takHttps"
    const val KEY_STATUS = "status"
    const val KEY_MESSAGE = "message"
    const val KEY_ACTION = "action"
    const val KEY_UID = "uid"
    const val KEY_LABEL = "label"
    const val KEY_LATITUDE = "latitude"
    const val KEY_LONGITUDE = "longitude"
    const val KEY_SERVER_ID = "serverId"
    const val KEY_SERVERS = "servers"
    const val KEY_SERVER_NAME = "name"
    const val KEY_METHOD = "method"
    const val KEY_PATH = "path"
    const val KEY_QUERY = "query"
    const val KEY_BODY = "body"
    const val KEY_CONTENT_TYPE = "contentType"
    const val KEY_HTTP_STATUS = "httpStatus"

    const val ACTION_BLOODHOUND = "bloodhound"
    const val ACTION_STOP_BLOODHOUND = "bloodhound.stop"
    const val ACTION_TARGET = "target"
    const val OK = "ok"
    const val INVALID_REQUEST = "invalid_request"
    const val UNAUTHORIZED = "unauthorized"
    const val CLOSED = "closed"
    const val BUSY = "busy"
    const val UNAVAILABLE = "unavailable"
    const val NOT_FOUND = "not_found"
    const val NETWORK_ERROR = "network_error"
    const val RESPONSE_TOO_LARGE = "response_too_large"
    const val INTERNAL_ERROR = "internal_error"

    const val MAX_REQUEST_BYTES = 32 * 1024
    const val MAX_RESPONSE_BYTES = 64 * 1024
    const val MAX_PATH_LENGTH = 2048
    const val MAX_QUERY_LENGTH = 8192
    const val REST_PORT = 8443
    const val REST_PREFIX = "/Marti/api/plugins/"

    @JvmStatic
    fun fromHostExtras(extras: Bundle): IWearTakHostServices? =
        if (extras.getInt(EXTRA_VERSION) == VERSION) {
            extras.getBinder(EXTRA_BINDER)?.let(IWearTakHostServices.Stub::asInterface)
        } else null

    @JvmStatic
    fun result(status: String, message: String = "") = Bundle().apply {
        putString(KEY_STATUS, status)
        putString(KEY_MESSAGE, message)
    }
}
