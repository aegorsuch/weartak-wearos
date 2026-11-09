package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C

internal data class PluginMapRequest(
    val action: String,
    val uid: String?,
    val label: String?,
    val latitude: Double?,
    val longitude: Double?,
    val returnToMain: Boolean = false,
) {
    companion object {
        fun parse(bundle: Bundle): PluginMapRequest {
            require(bundle.keySet().all { it in setOf(C.KEY_ACTION, C.KEY_UID, C.KEY_LABEL,
                C.KEY_LATITUDE, C.KEY_LONGITUDE, C.KEY_RETURN_TO_MAIN) }) { "Unknown map request field" }
            val action = requireNotNull(bundle.string(C.KEY_ACTION)) { "Action required" }
            require(action in setOf(C.ACTION_TARGET, C.ACTION_BLOODHOUND, C.ACTION_STOP_BLOODHOUND)) {
                "Unknown map action"
            }
            val uid = bundle.string(C.KEY_UID)?.also {
                require(it.isNotBlank() && it.length <= 256) { "Invalid UID" }
            }
            val label = bundle.string(C.KEY_LABEL)?.also {
                require(it.length <= 256) { "Label too long" }
            }
            val lat = bundle.double(C.KEY_LATITUDE)
            val lon = bundle.double(C.KEY_LONGITUDE)
            require((lat == null) == (lon == null)) { "Both coordinates required" }
            require(lat == null || (lat.isFinite() && lat in -90.0..90.0 &&
                lon != null && lon.isFinite() && lon in -180.0..180.0)) { "Invalid location" }
            require(uid != null || lat != null) { "UID or location required" }
            if (action == C.ACTION_STOP_BLOODHOUND) {
                require(uid != null && lat == null && label == null) { "Stop requires only an incident UID" }
            }
            val returnToMain = bundle.boolean(C.KEY_RETURN_TO_MAIN) ?: false
            require(!bundle.containsKey(C.KEY_RETURN_TO_MAIN) || action == C.ACTION_BLOODHOUND) {
                "Return to main is only supported for Bloodhound start"
            }
            return PluginMapRequest(action, uid, label, lat, lon, returnToMain)
        }
    }
}

internal data class PluginHttpRequest(
    val serverId: String?,
    val method: String,
    val path: String,
    val query: String?,
    val body: String?,
    val contentType: String,
) {
    companion object {
        fun parse(bundle: Bundle): PluginHttpRequest {
            require(bundle.keySet().all { it in setOf(C.KEY_SERVER_ID, C.KEY_METHOD, C.KEY_PATH,
                C.KEY_QUERY, C.KEY_BODY, C.KEY_CONTENT_TYPE) }) { "Unknown HTTP request field" }
            val serverId = bundle.string(C.KEY_SERVER_ID)?.also {
                require(it.isNotBlank() && it.length <= 256) { "Invalid server ID" }
            }
            val method = bundle.string(C.KEY_METHOD) ?: "GET"
            require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported method" }
            val path = requireNotNull(bundle.string(C.KEY_PATH)) { "Path required" }
            require(path.length <= C.MAX_PATH_LENGTH && path.startsWith(C.REST_PREFIX) &&
                path.length > C.REST_PREFIX.length &&
                path.matches(Regex("[A-Za-z0-9._~/-]+")) &&
                path.split('/').none { it == "." || it == ".." } &&
                !path.contains("//")) { "Only canonical /Marti/api/plugins/ paths allowed" }
            val query = bundle.string(C.KEY_QUERY)?.also {
                require(it.length <= C.MAX_QUERY_LENGTH && !it.startsWith("?") &&
                    it.none { ch -> ch.code < 32 || ch.code == 127 }) {
                    "Invalid query"
                }
            }
            val body = bundle.string(C.KEY_BODY)?.also {
                require(it.toByteArray(Charsets.UTF_8).size <= C.MAX_REQUEST_BYTES) { "Request body too large" }
                require(method != "GET") { "GET body not supported" }
            }
            val contentType = bundle.string(C.KEY_CONTENT_TYPE) ?: "application/json"
            require(contentType in setOf("application/json", "application/x-www-form-urlencoded", "text/plain")) {
                "Unsupported content type"
            }
            return PluginHttpRequest(serverId, method, path, query, body, contentType)
        }
    }
}

@Suppress("DEPRECATION")
private fun Bundle.string(key: String): String? {
    val value = get(key) ?: return null
    require(value is String) { "$key must be a String" }
    return value
}

@Suppress("DEPRECATION")
private fun Bundle.double(key: String): Double? {
    val value = get(key) ?: return null
    require(value is Double) { "$key must be a Double" }
    return value
}

@Suppress("DEPRECATION")
private fun Bundle.boolean(key: String): Boolean? {
    if (!containsKey(key)) return null
    val value = get(key)
    require(value is Boolean) { "$key must be a Boolean" }
    return value
}
