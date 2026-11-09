package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.R
import com.weartak.plugin.sdk.routestatus.RouteConnectionState
import org.junit.Assert.*
import org.junit.Test

class HomeConnectionStatusTest {
    private fun candidate(key: String) = HomeConnectionStatus(key, R.drawable.tak_server_connected,
        R.string.network_preferences, R.string.plugin_route_connected)

    @Test fun defaultsPreserveCompanionTakSitxConnectingMulticastOrder() {
        val order = listOf("companion", "tak", "sitx", "tak_connecting", "sitx_connecting", "multicast", "ditto")
        order.indices.forEach { index ->
            assertEquals(order[index], resolveHomeConnectionStatus(emptyList(),
                order.drop(index).reversed().map(::candidate)).key)
        }
    }

    @Test fun pluginAndDittoCanOverrideCompanionAndUnavailableEntriesAreSkipped() {
        val candidates = listOf(candidate("companion"), candidate("ditto"), candidate("plugin:atom:mesh"))
        assertEquals("plugin:atom:mesh", resolveHomeConnectionStatus(
            listOf("plugin:missing:mesh", "plugin:atom:mesh"), candidates).key)
        assertEquals("ditto", resolveHomeConnectionStatus(listOf("ditto"), candidates).key)
        assertEquals("companion", resolveHomeConnectionStatus(emptyList(), candidates).key)
    }

    @Test fun noActiveRouteAlwaysUsesDisconnectedRegardlessOfSavedOrder() {
        val result = resolveHomeConnectionStatus(listOf("ditto", "plugin:atom:mesh"), emptyList())
        assertEquals("disconnected", result.key)
        assertEquals(R.drawable.tak_server_disconnected, result.icon)
        assertEquals(R.string.server_disconnected, result.status)
    }

    @Test fun onlyConnectedOrConnectingPluginStatesCanSupplyHomeIcon() {
        assertTrue(pluginRouteIsActive(RouteConnectionState.CONNECTED))
        assertTrue(pluginRouteIsActive(RouteConnectionState.CONNECTING))
        listOf(RouteConnectionState.DISCONNECTED, RouteConnectionState.UNKNOWN,
            RouteConnectionState.ERROR, 999).forEach { assertFalse(pluginRouteIsActive(it)) }
        assertEquals(R.string.plugin_route_unknown, pluginRouteStatusLabel(999))
    }
}
