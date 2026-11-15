package com.aitech.weartak_civ.presentation

import androidx.annotation.StringRes
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.service.TAKConnectService

/** Direct TAK is paused by the active phone transport or its reconnect grace period. */
internal enum class TakCompanionPause(
    @StringRes val label: Int,
    @StringRes val detail: Int,
) {
    ACTIVE(R.string.tak_companion_active, R.string.tak_companion_active_detail),
    WAITING(R.string.companion_status_waiting, R.string.tak_companion_waiting_detail);

    companion object {
        fun resolve(connected: Boolean, graceActive: Boolean): TakCompanionPause? = when {
            connected -> ACTIVE
            graceActive -> WAITING
            else -> null
        }
    }
}

@StringRes
internal fun takServerStatusLabel(
    enabled: Boolean,
    status: String,
    offBodyWaiting: Boolean,
    companionPause: TakCompanionPause? = null,
): Int = when {
    !enabled -> R.string.tak_status_off
    // A socket can still report its previous state while the transport is switching.
    companionPause != null -> companionPause.label
    status == TAKConnectService.TAK_SERVER_CONNECTION_FAILED_STRING -> R.string.connection_failed
    status == TAKConnectService.TAK_SERVER_CONNECTED_STRING -> R.string.tak_status_connected
    status == TAKConnectService.TAK_SERVER_CONNECTING_STRING -> R.string.server_connecting
    offBodyWaiting && status == TAKConnectService.TAK_SERVER_DISCONNECTED_STRING ->
        R.string.physio_not_worn
    else -> R.string.tak_status_not_connected
}

@StringRes
internal fun takNetworkStatusOverride(
    masterEnabled: Boolean,
    enabledServerStatuses: List<String>,
    offBodyWaiting: Boolean,
    companionPause: TakCompanionPause? = null,
): Int? = when {
    !masterEnabled || enabledServerStatuses.isEmpty() -> null
    companionPause != null -> companionPause.label
    TAKConnectService.TAK_SERVER_CONNECTION_FAILED_STRING in enabledServerStatuses ->
        R.string.connection_failed
    offBodyWaiting && enabledServerStatuses.all {
        it == TAKConnectService.TAK_SERVER_DISCONNECTED_STRING
    } -> R.string.physio_not_worn
    else -> null
}
