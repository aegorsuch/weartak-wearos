package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.util.Log
import com.weartak.plugin.sdk.routestatus.RouteStatusContract
import com.weartak.plugin.sdk.routestatus.RouteStatusValidator
import com.weartak.plugin.sdk.routestatus.aidl.IPluginRouteStatusControlV1
import com.weartak.plugin.sdk.routestatus.model.PluginRouteStatusParcel
import com.weartak.plugin.sdk.routestatus.RouteStatusResultCode
import com.weartak.plugin.sdk.validation.ContractValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class PluginRouteStatuses {
    private val lock = Any()
    private val owners = mutableMapOf<String, HostRouteStatusControl>()
    private val state = MutableStateFlow<Map<String, Map<String, PluginRouteStatusParcel>>>(emptyMap())
    val statuses = state.asStateFlow()

    fun acquire(pluginId: String, owner: HostRouteStatusControl) = synchronized(lock) {
        owners[pluginId] = owner
        state.update { it - pluginId }
    }

    fun owns(pluginId: String, owner: HostRouteStatusControl) =
        synchronized(lock) { owners[pluginId] === owner }

    fun publish(pluginId: String, owner: HostRouteStatusControl, routes: Map<String, PluginRouteStatusParcel>) =
        synchronized(lock) {
            if (owners[pluginId] === owner) state.update { current ->
                if (routes.isEmpty()) current - pluginId else current + (pluginId to routes)
            }
        }

    fun release(pluginId: String, owner: HostRouteStatusControl) = synchronized(lock) {
        if (owners[pluginId] === owner) {
            owners.remove(pluginId)
            state.update { it - pluginId }
        }
    }
}

internal class HostRouteStatusControl(
    private val registry: PluginRouteStatuses,
    private val pluginId: String,
    private val expectedUid: Int,
    private val isBindingActive: () -> Boolean,
    private val callingUid: () -> Int = Binder::getCallingUid
) : IPluginRouteStatusControlV1.Stub() {
    private val lock = Any()
    private var active = true
    private var ready = false
    private val routes = linkedMapOf<String, PluginRouteStatusParcel>()

    init { registry.acquire(pluginId, this) }

    fun ready() = synchronized(lock) {
        if (active && isBindingActive()) {
            ready = true
            registry.publish(pluginId, this, routes.toMap())
        }
    }

    fun close() = synchronized(lock) {
        active = false
        routes.clear()
        registry.release(pluginId, this)
    }

    override fun getContractVersion(): Int = RouteStatusContract.CONTRACT_VERSION

    override fun reportRouteStatus(status: PluginRouteStatusParcel?): Int = call {
        val result = RouteStatusValidator.validate(status)
        if (result !is ContractValidationResult.Ok) return@call RouteStatusResultCode.INVALID_REQUEST
        val sanitized = result.value
        val previous = routes[sanitized.routeId]
        if (previous != null && sanitized.sequence <= previous.sequence) return@call RouteStatusResultCode.STALE_SEQUENCE
        if (previous == null && routes.size >= RouteStatusContract.MAX_ROUTES_PER_PLUGIN)
            return@call RouteStatusResultCode.LIMIT_EXCEEDED
        routes[sanitized.routeId] = sanitized
        publish()
        RouteStatusResultCode.OK
    }

    override fun removeRoute(routeId: String?): Int = call {
        if (RouteStatusValidator.validateRouteId(routeId) !is ContractValidationResult.Ok)
            return@call RouteStatusResultCode.INVALID_REQUEST
        if (routes.remove(routeId) == null) return@call RouteStatusResultCode.UNKNOWN_ROUTE
        publish()
        RouteStatusResultCode.OK
    }

    override fun clearRoutes(): Int = call {
        routes.clear()
        publish()
        RouteStatusResultCode.OK
    }

    private fun publish() {
        if (ready) registry.publish(pluginId, this, routes.toMap())
    }

    private fun call(block: () -> Int): Int = synchronized(lock) {
        val code = when {
            callingUid() != expectedUid -> RouteStatusResultCode.UNAUTHORIZED
            !active || !registry.owns(pluginId, this) || !isBindingActive() -> {
                close()
                RouteStatusResultCode.INACTIVE_SESSION
            }
            else -> block()
        }
        if (code != RouteStatusResultCode.OK) Log.w("WearTAK-RouteStatus", "Route status rejected code=$code")
        code
    }
}
