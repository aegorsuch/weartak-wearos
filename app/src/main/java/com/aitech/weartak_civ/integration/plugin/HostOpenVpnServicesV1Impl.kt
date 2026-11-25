package com.aitech.weartak_civ.integration.plugin

import android.os.BadParcelableException
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.secure.openvpn.LegacyOpenVpnProfileCleanup
import com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1
import com.weartak.plugin.sdk.openvpn.v1.IWearTakOpenVpnHostServicesV1
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class HostOpenVpnServicesV1Impl(
    app: WearTakApplication,
    private val pluginPackage: String,
    private val expectedUid: Int,
    private val providerRegistry: PluginOpenVpnProviderRegistry,
    private val onRuntimeStateChanged: () -> Unit,
    private val onRouteSelectionChanged: (String) -> Unit,
) : IWearTakOpenVpnHostServicesV1.Stub() {
    private val lock = Any()
    private val app = app
    private val legacyProfiles = LegacyOpenVpnProfileCleanup(app)
    private val runtimeStates = mutableMapOf<String, Int>()
    private var closed = false

    override fun getCapabilities(): Bundle = guarded {
        C.result(C.OK).apply {
            putInt(C.EXTRA_VERSION, C.API_VERSION)
            putInt("maxStreams", C.MAX_ACTIVE_STREAMS)
            putInt("maxIoBytes", C.MAX_IO_BYTES)
        }
    }

    override fun getConfiguredServers(): Bundle = guarded {
        val configured = runBlocking(Dispatchers.IO) { app.repo.awaitTakServerList() }
        C.result(C.OK).apply {
            putParcelableArrayList(C.KEY_SERVERS, ArrayList(configured.mapNotNull { server ->
                val id = server.serverId ?: return@mapNotNull null
                Bundle().apply {
                    putString(C.KEY_SERVER_ID, id)
                    putString(C.KEY_SERVER_NAME, server.name)
                    putString(C.KEY_SELECTED_PROFILE_ID, server.openVpnProfileId)
                    putString(C.KEY_SELECTED_PLUGIN_PACKAGE, server.openVpnPluginPackage)
                }
            }))
        }
    }

    override fun selectServerProfile(serverId: String?, profileId: String?): Bundle = guarded {
        if (serverId.isNullOrBlank() || serverId.length > C.MAX_SERVER_ID_LENGTH ||
            (profileId != null && !validProfileId(profileId))
        ) return@guarded C.result(C.INVALID_REQUEST, "Invalid server or profile selection")
        val result = runBlocking(Dispatchers.IO) {
            app.repo.updateOpenVpnRoute(
                serverId = serverId,
                pluginPackage = profileId?.let { pluginPackage },
                profileId = profileId,
            )
        }
        when (result) {
            DataRepository.OpenVpnRouteUpdateResult.UPDATED -> {
                onRouteSelectionChanged(serverId)
                C.result(C.OK)
            }
            DataRepository.OpenVpnRouteUpdateResult.NOT_FOUND -> C.result(C.NOT_FOUND, "TAK server not found")
            DataRepository.OpenVpnRouteUpdateResult.LOCKED -> C.result(C.LOCKED, "Server settings are managed or locked")
            DataRepository.OpenVpnRouteUpdateResult.INVALID -> C.result(C.INVALID_REQUEST, "Invalid route selection")
        }
    }

    override fun registerProvider(provider: IOpenVpnRawTcpProviderV1?): Bundle = guarded {
        val candidate = requireNotNull(provider) { "OpenVPN provider is required" }
        if (pluginPackage != C.PLUGIN_PACKAGE ||
            !providerRegistry.register(pluginPackage, expectedUid, candidate)
        ) {
            return@guarded C.result(C.UNSUPPORTED, "OpenVPN provider contract is unavailable")
        }
        C.result(C.OK)
    }

    override fun unregisterProvider(): Bundle = guarded {
        providerRegistry.unregister(pluginPackage, expectedUid)
        C.result(C.OK)
    }

    override fun publishRuntimeState(profileId: String?, state: Int): Bundle = guarded {
        if (!validProfileId(profileId) || state !in C.RUNTIME_STOPPED..C.RUNTIME_ERROR) {
            return@guarded C.result(C.INVALID_REQUEST, "Invalid OpenVPN runtime state")
        }
        val changed = synchronized(lock) {
            val previous = runtimeStates.put(profileId!!, state)
            previous != state
        }
        if (changed) onRuntimeStateChanged()
        C.result(C.OK)
    }

    override fun clearLegacyProfiles(): Bundle = guarded {
        runBlocking(Dispatchers.IO) { legacyProfiles.clear() }
        C.result(C.OK)
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runtimeStates.clear()
        }
        providerRegistry.unregister(pluginPackage, expectedUid)
        onRuntimeStateChanged()
    }

    private inline fun guarded(block: () -> Bundle): Bundle {
        if (Binder.getCallingUid() != expectedUid) {
            Log.w(TAG, "OpenVPN host services rejected UID mismatch")
            return C.result(C.UNAUTHORIZED)
        }
        synchronized(lock) { if (closed) return C.result(C.CLOSED) }
        val identity = Binder.clearCallingIdentity()
        try {
            val result = block()
            return synchronized(lock) { if (closed) C.result(C.CLOSED) else result }
        } catch (error: BadParcelableException) {
            Log.w(TAG, "Invalid OpenVPN host-service parcel", error)
            return C.result(C.INVALID_REQUEST, "Invalid request parcel")
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Invalid OpenVPN host-service request", error)
            return C.result(C.INVALID_REQUEST, error.message.orEmpty())
        } catch (error: SecurityException) {
            Log.w(TAG, "OpenVPN host-service action denied", error)
            return C.result(C.LOCKED, "Host policy refused OpenVPN change")
        } catch (error: Exception) {
            Log.e(TAG, "OpenVPN host service unavailable", error)
            return C.result(C.UNAVAILABLE, "WearTAK host service unavailable")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun validProfileId(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= C.MAX_PROFILE_ID_LENGTH &&
            value.all { it.isLetterOrDigit() || it in "._-" }

    companion object {
        private const val TAG = "OpenVpnHostServices"
    }
}
