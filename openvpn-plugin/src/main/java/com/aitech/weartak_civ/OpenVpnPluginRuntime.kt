package com.aitech.weartak_civ

import android.os.Bundle
import androidx.core.os.BundleCompat
import com.aitech.weartak_civ.service.openvpn.OpenVpnStatus
import com.weartak.plugin.sdk.openvpn.v1.IWearTakOpenVpnHostServicesV1
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OpenVpnServerRoute(
    val serverId: String,
    val name: String,
    val selectedProfileId: String?,
    val selectedPluginPackage: String?,
)

object OpenVpnPluginRuntime {
    @Volatile
    var hostServices: IWearTakOpenVpnHostServicesV1? = null
        private set
    private val mutableHostServicesAvailable = MutableStateFlow(false)
    val hostServicesAvailable: StateFlow<Boolean> = mutableHostServicesAvailable
    @Volatile private var provider: OpenVpnRawTcpProviderV1? = null
    @Volatile private var runtimeStatePublisher: ((String?, OpenVpnStatus) -> Unit)? = null

    internal fun attach(
        services: IWearTakOpenVpnHostServicesV1?,
        rawTcpProvider: OpenVpnRawTcpProviderV1? = null,
        publishState: ((String?, OpenVpnStatus) -> Unit)? = null,
    ) {
        hostServices = services
        mutableHostServicesAvailable.value = services != null
        provider = rawTcpProvider
        runtimeStatePublisher = publishState
    }

    fun closeStreams() {
        provider?.closeAll()
    }

    fun publishRuntimeState(profileId: String?, status: OpenVpnStatus) {
        runtimeStatePublisher?.invoke(profileId, status)
    }

    fun configuredServers(): List<OpenVpnServerRoute> {
        val response = requireNotNull(hostServices) {
            "WearTAK host transport unavailable"
        }.configuredServers
        if (response.getString(C.KEY_STATUS) != C.OK) {
            throw IllegalStateException(
                response.getString(C.KEY_MESSAGE) ?: "WearTAK host could not return configured servers",
            )
        }
        return BundleCompat.getParcelableArrayList(response, C.KEY_SERVERS, Bundle::class.java)
            .orEmpty().mapNotNull { item ->
            val id = item.getString(C.KEY_SERVER_ID) ?: return@mapNotNull null
            OpenVpnServerRoute(
                serverId = id,
                name = item.getString(C.KEY_SERVER_NAME).orEmpty(),
                selectedProfileId = item.getString(C.KEY_SELECTED_PROFILE_ID),
                selectedPluginPackage = item.getString(C.KEY_SELECTED_PLUGIN_PACKAGE),
            )
        }
    }
}
