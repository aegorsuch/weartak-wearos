package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import com.weartak.plugin.sdk.routestatus.RouteConnectionState
import com.weartak.plugin.sdk.routestatus.RouteStatusContract
import com.weartak.plugin.sdk.routestatus.RouteStatusResultCode
import com.weartak.plugin.sdk.routestatus.model.PluginRouteStatusParcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HostRouteStatusControlTest {
    private val registry = PluginRouteStatuses()
    private var uid = 42
    private var bound = true
    private fun control(plugin: String = "atom") =
        HostRouteStatusControl(registry, plugin, 42, { bound }, { uid })
    private fun status(id: String = "mesh", state: Int = RouteConnectionState.CONNECTED, sequence: Long = 1) =
        PluginRouteStatusParcel(id, "Meshtastic", state, "3 nodes", sequence, 1000)

    @Test fun noStatusUntilReadyAndEveryReportedRouteBelongsToBindingIdentity() {
        val control = control()
        assertEquals(1, control.contractVersion)
        assertTrue(registry.statuses.value.isEmpty())
        assertEquals(RouteStatusResultCode.OK, control.reportRouteStatus(status()))
        assertTrue(registry.statuses.value.isEmpty())
        control.ready()
        assertEquals(status(), registry.statuses.value.getValue("atom").getValue("mesh"))
        assertEquals(RouteStatusResultCode.OK, control.reportRouteStatus(status("radio")))
        assertEquals(setOf("mesh", "radio"), registry.statuses.value.getValue("atom").keys)
    }

    @Test fun wrongUidCannotReportRemoveOrClearAnotherPluginsRoutes() {
        val control = control()
        control.ready()
        control.reportRouteStatus(status())
        uid = 77
        assertEquals(RouteStatusResultCode.UNAUTHORIZED, control.reportRouteStatus(status(sequence = 2)))
        assertEquals(RouteStatusResultCode.UNAUTHORIZED, control.removeRoute("mesh"))
        assertEquals(RouteStatusResultCode.UNAUTHORIZED, control.clearRoutes())
        assertEquals(1L, registry.statuses.value.getValue("atom").getValue("mesh").sequence)
    }

    @Test fun closedOrLostBindingCannotRetainOrRestoreConnectedState() {
        val control = control()
        control.ready()
        control.reportRouteStatus(status())
        bound = false
        assertEquals(RouteStatusResultCode.INACTIVE_SESSION, control.reportRouteStatus(status(sequence = 2)))
        assertTrue(registry.statuses.value.isEmpty())
        bound = true
        control.ready()
        assertEquals(RouteStatusResultCode.INACTIVE_SESSION, control.reportRouteStatus(status(sequence = 3)))
        assertTrue(registry.statuses.value.isEmpty())
    }

    @Test fun supersededBindingCannotRemoveOrPublishNewSessionsRoutes() {
        val old = control()
        old.ready()
        old.reportRouteStatus(status())
        val replacement = control()
        assertTrue(registry.statuses.value.isEmpty())
        replacement.ready()
        replacement.reportRouteStatus(status(state = RouteConnectionState.CONNECTING))
        old.close()
        assertEquals(RouteStatusResultCode.INACTIVE_SESSION, old.clearRoutes())
        assertEquals(RouteConnectionState.CONNECTING,
            registry.statuses.value.getValue("atom").getValue("mesh").state)
        replacement.close()
        assertTrue(registry.statuses.value.isEmpty())
    }

    @Test fun rejectsInvalidAndOutOfOrderReportsAndSanitizesUnknownFutureState() {
        val control = control()
        control.ready()
        assertEquals(RouteStatusResultCode.INVALID_REQUEST, control.reportRouteStatus(null))
        assertEquals(RouteStatusResultCode.INVALID_REQUEST, control.reportRouteStatus(status("../invalid")))
        assertEquals(RouteStatusResultCode.INVALID_REQUEST, control.reportRouteStatus(status().copy(label = "")))
        assertEquals(RouteStatusResultCode.INVALID_REQUEST, control.reportRouteStatus(status(sequence = -1)))
        assertEquals(RouteStatusResultCode.OK, control.reportRouteStatus(status(sequence = 5)))
        assertEquals(RouteStatusResultCode.STALE_SEQUENCE, control.reportRouteStatus(status(sequence = 4)))
        assertEquals(RouteStatusResultCode.STALE_SEQUENCE, control.reportRouteStatus(status(sequence = 5)))
        assertEquals(RouteStatusResultCode.OK, control.reportRouteStatus(status(state = 999, sequence = 6)))
        assertEquals(RouteConnectionState.UNKNOWN, registry.statuses.value.getValue("atom").getValue("mesh").state)
    }

    @Test fun capsNewRoutesButAllowsUpdatesRemovalAndClearingOnlyOwnersRoutes() {
        val first = control()
        first.ready()
        repeat(RouteStatusContract.MAX_ROUTES_PER_PLUGIN) {
            assertEquals(RouteStatusResultCode.OK, first.reportRouteStatus(status("route$it")))
        }
        assertEquals(RouteStatusResultCode.LIMIT_EXCEEDED, first.reportRouteStatus(status("overflow")))
        assertEquals(RouteStatusResultCode.OK, first.reportRouteStatus(status("route0", sequence = 2)))
        assertEquals(RouteStatusResultCode.INVALID_REQUEST, first.removeRoute("../invalid"))
        assertEquals(RouteStatusResultCode.UNKNOWN_ROUTE, first.removeRoute("missing"))
        assertEquals(RouteStatusResultCode.OK, first.removeRoute("route0"))
        assertEquals(RouteStatusResultCode.OK, first.reportRouteStatus(status("replacement")))
        val second = control("other")
        second.ready()
        second.reportRouteStatus(status())
        assertEquals(RouteStatusResultCode.OK, first.clearRoutes())
        assertEquals(setOf("other"), registry.statuses.value.keys)
    }
}
