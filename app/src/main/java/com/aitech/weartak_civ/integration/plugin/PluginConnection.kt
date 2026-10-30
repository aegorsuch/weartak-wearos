package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.host.v1.HostServicesContract
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.mopp.MoppPpeContract
import com.weartak.plugin.sdk.v2.WearTakPluginV2Contract
import com.weartak.plugin.sdk.v3.WearTakPluginV3Contract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Owns one bound plugin service: binder, death recipient, host stubs, and teardown.
 */
class PluginConnection(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val component: ComponentName,
    private val pluginUid: Int,
    private val manager: PluginManager,
    private val pluginTrust: PluginTrust,
) : ServiceConnection {

    private val pluginPackage: String = component.packageName

    /** Package key for diagnostics when the connection is not yet in [PluginManager]'s map. */
    internal val connectionPackageName: String get() = pluginPackage

    private var pluginBinder: IBinder? = null
    private var plugin: IWearTakPlugin? = null
    @Volatile
    private var pluginInfoSnapshot: PluginInfoParcel? = null
    @Volatile
    private var apiVersionSnapshot: Int = -1
    private var hostControl: HostControlImpl? = null
    private var hostCallback: HostCallbackImpl? = null
    private var hostPcmControlV3: HostPcmControlV3Impl? = null
    @Volatile private var hostMoppControl: HostMoppPpeControl? = null
    private var hostServicesV1: HostServicesV1Impl? = null
    private val stateLock = Any()
    @Volatile private var closed = false
    private var initializing = false
    private var pendingRemoteCleanup: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handshakeTimeout = Runnable {
        if (!closed) failBind("lifecycle_timeout")
    }

    /**
     * Set when [onServiceConnected] fails before [PluginManager.bind] registers this connection.
     * Prevents a synchronous callback from evicting and then [bind] re-adding the same dead connection.
     */
    internal var suppressPostBindRegistration: Boolean = false
        private set

    private fun failBind(reason: String) {
        if (closed) return
        suppressPostBindRegistration = true
        manager.onConnectionFailed(this@PluginConnection, reason)
    }

    private val deathRecipient = IBinder.DeathRecipient {
        hostMoppControl?.close()
        appScope.launch {
            onBinderDiedInternal()
        }
    }

    private fun onBinderDiedInternal() {
        hostMoppControl?.close()
        PluginDiagnostics.recordBinderDeath(pluginPackage)
        manager.onConnectionDied(this)
    }

    /** Test seam: synchronous binder-death path (production uses [binderDeathRecipientForTest]). */
    internal fun triggerBinderDeathSyncForTest() {
        onBinderDiedInternal()
    }

    internal val binderDeathRecipientForTest: IBinder.DeathRecipient get() = deathRecipient

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    internal fun startBindingTimeout() {
        mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (closed) return
        if (service == null) {
            PluginDiagnostics.recordBindFailure(component.flattenToString(), null, includeBinderProtocolCategory = false)
            failBind("null_binder")
            return
        }
        synchronized(stateLock) {
            if (closed || initializing || pluginBinder != null) return
            initializing = true
        }
        mainHandler.removeCallbacks(handshakeTimeout)
        startBindingTimeout()
        if (!PluginLifecycleWork.execute(pluginPackage) {
            try {
                initialize(service)
            } catch (error: RemoteException) {
                Log.e(TAG, "plugin lifecycle failed pkg=$pluginPackage", error)
                postFailure("remote_exception")
            } catch (error: RuntimeException) {
                Log.e(TAG, "plugin initialization failed pkg=$pluginPackage", error)
                postFailure("initialization_failed")
            } finally {
                val cleanup = synchronized(stateLock) {
                    initializing = false
                    pendingRemoteCleanup.also { pendingRemoteCleanup = null }
                }
                cleanup?.invoke()
            }
        }) {
            synchronized(stateLock) { initializing = false }
            failBind("lifecycle_busy")
        }
    }

    private fun postFailure(reason: String) {
        mainHandler.post { if (!closed) failBind(reason) }
    }

    private fun initialize(service: IBinder) {
        if (closed) return
        val iface = runCatching { IWearTakPlugin.Stub.asInterface(service) }.getOrElse { t ->
            PluginDiagnostics.recordBindFailure(component.flattenToString(), t, includeBinderProtocolCategory = false)
            postFailure("asInterface")
            return
        }
        synchronized(stateLock) {
            if (closed) return
            pluginBinder = service
            plugin = iface
        }
        runCatching { service.linkToDeath(deathRecipient, 0) }
            .onFailure { Log.w(TAG, "linkToDeath failed pkg=$pluginPackage", it) }

        val info = runCatching { iface.pluginInfo }.getOrElse { t ->
            Log.e(TAG, "getPluginInfo failed", t)
            postFailure("get_plugin_info")
            return
        }
        if (closed) return
        synchronized(stateLock) {
            if (closed) return
            pluginInfoSnapshot = info
        }
        if (info.packageName != pluginPackage || info.pluginId != pluginPackage) {
            Log.w(
                TAG,
                "plugin info mismatch pkg=$pluginPackage info.pkg=${info.packageName} info.id=${info.pluginId}"
            )
            postFailure("plugin_info_mismatch")
            return
        }

        val senderPackages = ingress.packagesForUid(app, pluginUid)
        if (pluginPackage !in senderPackages) {
            postFailure("uid_package_mismatch")
            return
        }

        runCatching {
            runBlocking {
                pluginTrust.enforce(pluginPackage, pluginUid, senderPackages)
            }
        }.onFailure { e ->
            val code = (e as? ParserException)?.reasonCode ?: e.javaClass.simpleName
            postFailure("trust_failure:$code")
            return
        }

        val api = runCatching { iface.apiVersion }.getOrDefault(-1)
        if (closed) return
        synchronized(stateLock) {
            if (closed) return
            apiVersionSnapshot = api
        }
        if (
            api != WearTakPluginContract.CONTRACT_API_VERSION &&
            api != WearTakPluginV2Contract.CONTRACT_API_VERSION &&
            api != WearTakPluginV3Contract.CONTRACT_API_VERSION
        ) {
            Log.w(TAG, "plugin apiVersion=$api unsupported pkg=$pluginPackage")
            postFailure("api_version_mismatch:$api")
            return
        }

        val control = HostControlImpl(app, ingress, pluginPackage, pluginUid, isActive = { !closed })
        val controlV2 = if (api >= WearTakPluginV2Contract.CONTRACT_API_VERSION) {
            HostControlV2Impl(app, ingress, pluginPackage, pluginUid, isActive = { !closed })
        } else {
            null
        }
        val controlV3 = if (api == WearTakPluginV3Contract.CONTRACT_API_VERSION) {
            HostPcmControlV3Impl(app.audioRepo, pluginPackage, pluginUid)
        } else {
            null
        }
        val callback = HostCallbackImpl(
            ingress = ingress,
            settingsCoordinator = manager.settingsCoordinator,
            pluginId = pluginPackage,
            pluginUid = pluginUid,
            isActive = { !closed },
        )
        val moppControl = if (pluginPackage == MoppPluginAvailability.PACKAGE) {
            HostMoppPpeControl(app.repo.moppPpeStore, pluginUid, authorize = {
                if (closed || pluginBinder?.isBinderAlive != true) {
                    false
                } else {
                    try {
                        runBlocking {
                            pluginTrust.enforce(pluginPackage, pluginUid, ingress.packagesForUid(app, pluginUid))
                        }
                        true
                    } catch (error: ParserException) {
                        Log.w(TAG, "MOPP trust rejected: ${error.reasonCode}")
                        false
                    } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.w(TAG, "MOPP package unavailable", error)
                        false
                    } catch (error: SecurityException) {
                        Log.w(TAG, "MOPP package access denied", error)
                        false
                    }
                }
            })
        } else null
        val services = HostServicesV1Impl(app, pluginPackage, pluginUid)
        synchronized(stateLock) {
            if (closed) {
                moppControl?.close()
                controlV3?.close()
                services.close()
                return
            }
            hostControl = control
            hostCallback = callback
            hostPcmControlV3 = controlV3
            hostServicesV1 = services
            hostMoppControl = moppControl
        }

        val init = PluginInitParcel(
            hostPackageName = app.packageName,
            hostUid = android.os.Process.myUid(),
            pluginIdExpected = pluginPackage,
            hostExtras = android.os.Bundle().apply {
                putInt(HostServicesContract.EXTRA_VERSION, HostServicesContract.VERSION)
                putBinder(HostServicesContract.EXTRA_BINDER, services.asBinder())
                putBoolean(HostControlImpl.HOST_EXTRA_COT_ROUTING_V1, true)
                putBoolean(HostControlImpl.HOST_EXTRA_CHAT_DISPATCH_V1, true)
                if (moppControl != null) {
                    putBoolean(MoppPpeContract.EXTRA_CAPABILITY, true)
                    putBinder(MoppPpeContract.EXTRA_CONTROL, moppControl)
                }
            },
        )
        try {
            if (controlV3 != null) {
                iface.initializePluginV3(init, control, controlV2, controlV3)
            } else {
                iface.initializePluginV2(init, control, controlV2)
            }
            if (closed) return
            iface.registerHostCallback(callback)
            if (closed) return
            iface.startPlugin()
            if (closed) return
            manager.settingsCoordinator.onPluginConnected(pluginPackage, iface) { !closed }
        } catch (e: RemoteException) {
            Log.e(TAG, "plugin lifecycle failed pkg=$pluginPackage", e)
            postFailure("remote_exception")
            return
        }
        mainHandler.post {
            if (!closed) {
                mainHandler.removeCallbacks(handshakeTimeout)
                PluginDiagnostics.markConnected(pluginPackage)
                moppControl?.ready()
                manager.onConnectionReady(pluginPackage)
                Log.i(TAG, "initialized plugin pkg=$pluginPackage api=$api")
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        hostMoppControl?.close()
        Log.w(TAG, "onServiceDisconnected pkg=$pluginPackage")
        manager.onConnectionLost(this@PluginConnection)
    }

    override fun onBindingDied(name: ComponentName?) {
        hostMoppControl?.close()
        Log.w(TAG, "onBindingDied pkg=$pluginPackage")
        manager.onConnectionLost(this)
    }

    override fun onNullBinding(name: ComponentName?) {
        Log.w(TAG, "onNullBinding pkg=$pluginPackage")
        failBind("null_binder")
    }

    fun tearDownBinder(reason: String) {
        Log.i(TAG, "tearDownBinder pkg=$pluginPackage reason=$reason")
        val cleanup = synchronized(stateLock) {
            if (closed) return
            closed = true
            hostMoppControl?.close()
            hostMoppControl = null
            mainHandler.removeCallbacks(handshakeTimeout)
            hostServicesV1?.close()
            hostPcmControlV3?.close()
            val iface = plugin
            val cb = hostCallback
            val binder = pluginBinder
            // Local revocation must not wait for a remote lifecycle transaction.
            hostCallback?.close()
            manager.settingsCoordinator.unregisterPlugin(pluginPackage)
            plugin = null
            pluginInfoSnapshot = null
            apiVersionSnapshot = -1
            hostControl = null
            hostCallback = null
            hostPcmControlV3 = null
            hostServicesV1 = null
            pluginBinder = null
            val remoteCleanup: (() -> Unit)? = if (iface == null && binder == null) null else ({
                fun attempt(method: String, block: () -> Unit) {
                    runCatching(block).onFailure { Log.w(TAG, "$method failed pkg=$pluginPackage", it) }
                }
                attempt("unregisterHostCallback") { if (iface != null && cb != null) iface.unregisterHostCallback(cb) }
                attempt("stopPlugin") { iface?.stopPlugin() }
                attempt("shutdownPlugin") { iface?.shutdownPlugin() }
                attempt("unlinkToDeath") { binder?.unlinkToDeath(deathRecipient, 0) }
            })
            if (initializing) {
                pendingRemoteCleanup = remoteCleanup
                null
            } else remoteCleanup
        }
        if (cleanup != null && !PluginLifecycleWork.execute(pluginPackage, cleanup)) {
            Log.w(TAG, "Best-effort remote cleanup skipped: lifecycle busy pkg=$pluginPackage")
        }
    }

    internal fun pluginInfoSnapshot(): PluginInfoParcel? = pluginInfoSnapshot

    internal fun apiVersionSnapshot(): Int = apiVersionSnapshot

    companion object {
        private const val TAG = "CEP-PluginConnection"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
    }
}
