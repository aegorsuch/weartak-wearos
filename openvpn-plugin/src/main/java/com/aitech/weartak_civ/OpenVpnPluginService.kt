package com.aitech.weartak_civ

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.aitech.weartak_civ.service.openvpn.OpenVpnService
import com.aitech.weartak_civ.service.openvpn.OpenVpnStatus
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.WearTakPluginV3Contract
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract
import java.util.concurrent.atomic.AtomicBoolean

class OpenVpnPluginService : Service() {
    private val plugin = OpenVpnPluginBinder()

    override fun onBind(intent: Intent?): IBinder = plugin

    override fun onUnbind(intent: Intent?): Boolean {
        plugin.onServiceUnbound()
        return false
    }

    override fun onDestroy() {
        plugin.onServiceDestroyed()
        super.onDestroy()
    }
}

internal class OpenVpnPluginBinder : IWearTakPlugin.Stub() {
    private data class ProviderRegistrationAttempt(
        val generation: Long,
        val services: com.weartak.plugin.sdk.openvpn.v1.IWearTakOpenVpnHostServicesV1,
        val provider: OpenVpnRawTcpProviderV1,
    )

    private data class StopOperation(
        val services: com.weartak.plugin.sdk.openvpn.v1.IWearTakOpenVpnHostServicesV1?,
        val provider: OpenVpnRawTcpProviderV1?,
        val cleanupToken: Any?,
    )

    private val lock = Any()
    private val initializeLock = Any()
    private val started = AtomicBoolean(false)
    private var lifecycleGeneration = 0L
    private var providerOperation: Any? = null
    @Volatile private var hostUid = -1
    @Volatile private var hostPackage: String? = null
    @Volatile private var hostServices: com.weartak.plugin.sdk.openvpn.v1.IWearTakOpenVpnHostServicesV1? = null
    @Volatile private var hostServicesDeathRecipient: IBinder.DeathRecipient? = null
    @Volatile private var provider: OpenVpnRawTcpProviderV1? = null
    @Volatile private var lifecycleState = PluginLifecycleState.BOUND

    override fun getPluginInfo() = PluginInfoParcel(
        PLUGIN_PACKAGE,
        "WearTAK OpenVPN",
        BuildConfig.VERSION_NAME,
        PLUGIN_PACKAGE,
        "Optional OpenVPN tunnel and per-server TCP routing for WearTAK",
    )

    override fun getCapabilities() = PluginCapabilitiesParcel(
        hostedSettingsUi = false,
        externalSettingsActivity = true,
        hostedMainUi = false,
        externalMainActivity = true,
        cotReadyEvents = false,
        mapLayers = false,
        deviceIntegration = false,
        sharedMemory = false,
    )

    override fun getApiVersion() = WearTakPluginV3Contract.CONTRACT_API_VERSION

    override fun getStatus() = PluginStatusParcel(lifecycleState, "", 0, Bundle())

    override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) {
        initialize(init)
    }

    override fun initializePluginV2(
        init: PluginInitParcel?,
        control: IWearTakPluginControl?,
        hostControl: IWearTakPluginHostControlV2?,
    ) {
        initialize(init)
    }

    override fun initializePluginV3(
        init: PluginInitParcel?,
        control: IWearTakPluginControl?,
        hostControl: IWearTakPluginHostControlV2?,
        hostControlV3: IWearTakPluginHostControlV3?,
    ) {
        initialize(init)
    }

    private fun initialize(init: PluginInitParcel?) = synchronized(initializeLock) {
        val value = requireNotNull(init) { "Plugin initialization data is required" }
        require(value.pluginIdExpected == PLUGIN_PACKAGE) { "Unexpected plugin identity" }
        val callerUid = Binder.getCallingUid()
        val callerPackages = OpenVpnPluginApplication.appContext.packageManager
            .getPackagesForUid(callerUid).orEmpty().toSet()
        if (value.hostUid != callerUid || value.hostPackageName !in TRUSTED_HOST_PACKAGES ||
            value.hostPackageName !in callerPackages
        ) {
            throw SecurityException("OpenVPN plugin initialization requires the WearTAK host")
        }
        val services = OpenVpnHostContract.fromHostExtras(value.hostExtras)
        val servicesBinder = services?.asBinder()
        val currentUid = synchronized(lock) { hostUid }
        if (currentUid != -1 && currentUid != callerUid) {
            throw SecurityException("OpenVPN plugin is already initialized by another host")
        }
        if (currentUid == callerUid && synchronized(lock) {
                hostServices?.asBinder() == servicesBinder
            }
        ) {
            return@synchronized
        }
        if (currentUid == callerUid) shutdownInternal()

        val deathRecipient = servicesBinder?.let { binder ->
            IBinder.DeathRecipient { handleHostServicesDeath(callerUid, binder) }
        }
        try {
            if (servicesBinder != null && deathRecipient != null) {
                servicesBinder.linkToDeath(deathRecipient, 0)
            }
        } catch (error: RemoteException) {
            throw IllegalStateException("WearTAK host services are unavailable", error)
        }
        var installed = false
        try {
            synchronized(lock) {
                if (hostUid != -1 && hostUid != callerUid) {
                    throw SecurityException("OpenVPN plugin is already initialized by another host")
                }
                hostUid = callerUid
                hostPackage = value.hostPackageName
                hostServices = services
                hostServicesDeathRecipient = deathRecipient
                provider = OpenVpnRawTcpProviderV1(this, callerUid)
                OpenVpnPluginRuntime.attach(hostServices, provider, ::publishRuntimeState)
                lifecycleState = PluginLifecycleState.READY
                installed = true
            }
        } finally {
            if (!installed && servicesBinder != null && deathRecipient != null) {
                servicesBinder.unlinkToDeath(deathRecipient, 0)
            }
        }
        if (servicesBinder != null && !servicesBinder.isBinderAlive) {
            handleHostServicesDeath(callerUid, servicesBinder)
            throw IllegalStateException("WearTAK host services are unavailable")
        }
    }

    override fun startPlugin() {
        requireHostCaller()
        val attempt = synchronized(lock) {
            if (providerOperation != null || started.get()) return
            val api = hostServices ?: return
            val service = provider ?: return
            ProviderRegistrationAttempt(lifecycleGeneration, api, service).also {
                providerOperation = it
                started.set(true)
            }
        }
        val response = try {
            attempt.services.registerProvider(attempt.provider)
        } catch (error: Exception) {
            try {
                completeRegistration(attempt, accepted = false, uncertainFailure = true)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
        if (response.getString(OpenVpnHostContract.KEY_STATUS) != OpenVpnHostContract.OK) {
            Log.w(TAG, "OpenVPN raw TCP provider registration refused")
        }
        completeRegistration(
            attempt,
            accepted = response.getString(OpenVpnHostContract.KEY_STATUS) == OpenVpnHostContract.OK,
            uncertainFailure = false,
        )
    }

    private fun completeRegistration(
        attempt: ProviderRegistrationAttempt,
        accepted: Boolean,
        uncertainFailure: Boolean,
    ) {
        val needsUnregister = synchronized(lock) {
            if (providerOperation !== attempt) return@synchronized false
            val current = lifecycleGeneration == attempt.generation &&
                hostServices === attempt.services && provider === attempt.provider && started.get()
            when {
                current && accepted && !uncertainFailure -> {
                    lifecycleState = PluginLifecycleState.RUNNING
                    providerOperation = null
                    false
                }
                current && !accepted && !uncertainFailure -> {
                    started.set(false)
                    lifecycleState = PluginLifecycleState.READY
                    providerOperation = null
                    false
                }
                !current && !accepted && !uncertainFailure -> {
                    providerOperation = null
                    false
                }
                else -> true
            }
        }
        if (!needsUnregister) return

        try {
            try {
                attempt.services.unregisterProvider()
            } catch (error: RemoteException) {
                Log.w(TAG, "WearTAK host disappeared while revoking stale OpenVPN provider", error)
            }
        } finally {
            synchronized(lock) {
                if (providerOperation === attempt) {
                    val current = lifecycleGeneration == attempt.generation &&
                        hostServices === attempt.services && provider === attempt.provider && started.get()
                    if (current) {
                        started.set(false)
                        lifecycleState = PluginLifecycleState.READY
                    }
                    providerOperation = null
                }
            }
        }
    }

    override fun stopPlugin() {
        requireHostCaller()
        stopInternal()
    }

    private fun stopInternal() {
        val operation = synchronized(lock) {
            lifecycleGeneration++
            started.set(false)
            lifecycleState = PluginLifecycleState.STOPPED
            val token = if (providerOperation == null && hostServices != null) Any() else null
            if (token != null) providerOperation = token
            StopOperation(hostServices, provider, token)
        }
        try {
            if (operation.cleanupToken != null) {
                try {
                    operation.services?.unregisterProvider()
                } catch (error: RemoteException) {
                    Log.w(TAG, "WearTAK host disappeared while unregistering OpenVPN provider", error)
                }
            }
        } finally {
            if (operation.cleanupToken != null) {
                synchronized(lock) {
                    if (providerOperation === operation.cleanupToken) providerOperation = null
                }
            }
            try {
                operation.provider?.closeAll()
            } finally {
                try {
                    OpenVpnPluginRuntime.closeStreams()
                } finally {
                    stopTunnelIfActive()
                }
            }
        }
    }

    override fun shutdownPlugin() {
        requireHostCaller()
        shutdownInternal()
    }

    fun onServiceUnbound() = shutdownInternal()

    fun onServiceDestroyed() = shutdownInternal()

    private fun shutdownInternal() {
        val services = hostServices
        val deathRecipient = hostServicesDeathRecipient
        try {
            stopInternal()
        } finally {
            if (services != null && deathRecipient != null) {
                runCatching { services.asBinder().unlinkToDeath(deathRecipient, 0) }
            }
            synchronized(lock) {
                hostServices = null
                hostServicesDeathRecipient = null
                provider = null
                hostPackage = null
                hostUid = -1
                OpenVpnPluginRuntime.attach(null)
                lifecycleState = PluginLifecycleState.SHUTDOWN
            }
        }
    }

    private fun handleHostServicesDeath(expectedUid: Int, binder: IBinder) {
        val lost = synchronized(lock) {
            if (hostUid != expectedUid || hostServices?.asBinder() !== binder) {
                false
            } else {
                lifecycleGeneration++
                started.set(false)
                provider?.closeAll()
                hostServices = null
                hostServicesDeathRecipient = null
                provider = null
                hostPackage = null
                hostUid = -1
                OpenVpnPluginRuntime.attach(null)
                lifecycleState = PluginLifecycleState.SHUTDOWN
                true
            }
        }
        if (lost) {
            stopTunnelIfActive()
        }
    }

    private fun stopTunnelIfActive() {
        if (OpenVpnService.currentState().status == OpenVpnStatus.STOPPED) return
        val context = OpenVpnPluginApplication.appContext
        try {
            OpenVpnService.disconnect()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "OpenVPN disconnect request was restricted; stopping service", error)
            context.stopService(Intent(context, OpenVpnService::class.java))
        }
    }

    private fun requireHostCaller() {
        val callerUid = Binder.getCallingUid()
        val packageName = hostPackage
        if (callerUid != hostUid || packageName == null ||
            packageName !in OpenVpnPluginApplication.appContext.packageManager
                .getPackagesForUid(callerUid).orEmpty()
        ) {
            throw SecurityException("OpenVPN plugin lifecycle requires its initialized WearTAK host")
        }
    }

    override fun registerHostCallback(callback: IWearTakHostCallback?) = Unit
    override fun unregisterHostCallback(callback: IWearTakHostCallback?) = Unit
    override fun getSettingsSchema() = SettingsSchemaParcel()
    override fun getSettingsValues() = Bundle()
    override fun applySettings(values: Bundle?) = Unit
    override fun applySettingsWithResult(values: Bundle?, mode: Int) =
        SettingsApplyResultParcel(true, mode, values ?: Bundle(), false, false, emptyList(), emptyList())
    override fun onHostSettingsChanged(settings: HostSettingsParcel?) = Unit
    override fun getMainUiSchema() = PluginMainUiSchemaParcel()
    override fun getMainUiState() = Bundle()
    override fun invokeMainUiAction(action: String?, payload: Bundle?) = Unit

    internal fun hostUid(): Int = hostUid
    internal fun isProviderActive(): Boolean = started.get()
    internal fun publishRuntimeState(profileId: String?, status: OpenVpnStatus) {
        val code = when (status) {
            OpenVpnStatus.STOPPED -> OpenVpnHostContract.RUNTIME_STOPPED
            OpenVpnStatus.CONNECTING -> OpenVpnHostContract.RUNTIME_CONNECTING
            OpenVpnStatus.CONNECTED -> OpenVpnHostContract.RUNTIME_CONNECTED
            OpenVpnStatus.STOPPING -> OpenVpnHostContract.RUNTIME_STOPPING
            OpenVpnStatus.ERROR -> OpenVpnHostContract.RUNTIME_ERROR
        }
        if (started.get() && profileId != null) {
            runCatching { hostServices?.publishRuntimeState(profileId, code) }
                .onFailure { Log.w(TAG, "OpenVPN runtime state notification failed", it) }
        }
    }

    companion object {
        const val PLUGIN_PACKAGE = OpenVpnHostContract.PLUGIN_PACKAGE
        private const val TAG = "WearTAKOpenVPNPlugin"
        private val TRUSTED_HOST_PACKAGES = setOf(
            "com.aitech.weartak_civ",
            "com.aitech.weartak_civ.dev",
            "com.aitech.weartak_civ.nearbyfixture",
            "com.aitech.weartak_civ.ditto.diagnostics.fixture",
        )
    }
}
