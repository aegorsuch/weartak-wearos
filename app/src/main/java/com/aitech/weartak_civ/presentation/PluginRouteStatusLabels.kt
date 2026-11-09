package com.aitech.weartak_civ.presentation

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aitech.weartak_civ.R
import com.weartak.plugin.sdk.routestatus.model.PluginRouteStatusParcel
import com.weartak.plugin.sdk.routestatus.RouteConnectionState

@StringRes
internal fun pluginRouteStatusLabel(state: Int): Int = when (state) {
    RouteConnectionState.CONNECTED -> R.string.plugin_route_connected
    RouteConnectionState.CONNECTING -> R.string.plugin_route_connecting
    RouteConnectionState.DISCONNECTED -> R.string.plugin_route_disconnected
    RouteConnectionState.ERROR -> R.string.plugin_route_error
    else -> R.string.plugin_route_unknown
}

internal fun pluginRouteIsActive(state: Int): Boolean =
    state == RouteConnectionState.CONNECTED || state == RouteConnectionState.CONNECTING

internal fun pluginRoutePriorityKey(pluginId: String, routeId: String) = "plugin:$pluginId:$routeId"

@Composable
internal fun pluginRouteSummary(routes: Collection<PluginRouteStatusParcel>): String =
    routes.sortedBy { it.routeId }.map { route ->
        val state = stringResource(pluginRouteStatusLabel(route.state))
        val detail = route.statusText?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        "${route.label}: $state$detail"
    }.joinToString("; ")
