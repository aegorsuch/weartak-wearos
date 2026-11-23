package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.WearTakPluginContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Discovers plugin services, binds with [Context.BIND_AUTO_CREATE], and coordinates
 * [PluginConnection] teardown on death or explicit eviction. Only a verified handshake
 * adds [Context.BIND_ALLOW_ACTIVITY_STARTS] to the same connection.
 * Unexpected disconnects release the dead binding and retry discovery/binding after
 * 1, 2, 4, 8 and 16 seconds. Explicit unbind/shutdown cancels recovery; admission
 * failures are not retried. A successful handshake resets the retry budget.
 */
open class PluginManager(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val pluginTrust: PluginTrust = CepProvider.pluginTrust(),
    val settingsCoordinator: PluginSettingsCoordinator = PluginSettingsCoordinator(app),
    private val pluginStateStore: PluginStateStore? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connections = ConcurrentHashMap<String, PluginConnection>()
    private val audioDetectionClaimOwners = ConcurrentHashMap<String, PluginConnection>()
    private val audioDetectionDelegatesState = MutableStateFlow<Set<String>>(emptySet())
    val audioDetectionDelegates = audioDetectionDelegatesState.asStateFlow()
    private val dittoConnectionState = MutableStateFlow<PluginConnection?>(null)
    internal val dittoReadyConnection = dittoConnectionState.asStateFlow()
    private val topologyListeners = CopyOnWriteArraySet<PluginTopologyListener>()
    private val reconnectTasks = mutableMapOf<String, Runnable>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val preferences = app.getSharedPreferences("plugin_loading", Context.MODE_PRIVATE)
    private val disabled = preferences.getStringSet("disabled_packages", emptySet()).orEmpty().toMutableSet()
    private var stopped = false

    init {
        settingsCoordinator.onLegacyAudioSettingsRejected = { pluginId, plugin ->
            mainHandler.post {
                if (settingsCoordinator.connectionToken(pluginId) === plugin) {
                    Log.e(TAG, "Disconnecting audio plugin after settings rejection pkg=$pluginId")
                    detachPackage(pluginId)
                }
            }
        }
    }

    fun isEnabled(packageName: String): Boolean =
        pluginStateStore?.isEnabled(packageName) ?: (packageName !in disabled)

    private fun setEnabled(packageName: String, enabled: Boolean) {
        pluginStateStore?.setEnabled(packageName, enabled)
        if (enabled) disabled.remove(packageName) else disabled.add(packageName)
        preferences.edit().putStringSet("disabled_packages", disabled.toSet()).apply()
    }

    fun startDeferred() {
        mainHandler.post { refreshBindings() }
    }

    fun refreshBindings() {
        stopped = false
        val targets = PluginServiceQuery.discoverPluginComponents(app.packageManager, app.packageName)
        PluginDiagnostics.markDiscovered(targets.map { it.packageName })
        val targetPkgs = targets.map { it.packageName }.toSet()
        for (existing in connections.keys.toList()) {
            if (existing !in targetPkgs || !isEnabled(existing)) {
                detachPackage(existing)
            }
        }
        for (cn in targets) {
            if (!isEnabled(cn.packageName) || connections.containsKey(cn.packageName)) continue
            bind(cn)
        }
        notifyTopologyChanged()
    }

    internal fun retryPackageDiscovery(packageName: String) {
        if (stopped || !isEnabled(packageName) || connections.containsKey(packageName)) return
        val target = discoverPluginComponents().firstOrNull { it.packageName == packageName } ?: return
        bind(target)
    }

    fun bindPackage(packageName: String): Boolean {
        if (connections.containsKey(packageName)) return true
        stopped = false
        setEnabled(packageName, true)
        cancelReconnect(packageName)
        val cn = discoverPluginComponents().firstOrNull { it.packageName == packageName } ?: return false
        bind(cn)
        return connections.containsKey(packageName) || reconnectTasks.containsKey(packageName)
    }

    fun unbindPackage(packageName: String) {
        setEnabled(packageName, false)
        detachPackage(packageName)
    }

    internal fun detachPackage(packageName: String) {
        cancelReconnect(packageName)
        val c = connections.remove(packageName)
        if (c != null) dittoConnectionState.compareAndSet(c, null)
        if (c != null) releaseAudioDetectionPlugin(c)
        c?.tearDownBinder(reason = "unbindPackage")
        PluginDiagnostics.markUnbound(packageName)
        notifyTopologyChanged()
        if (c != null) {
            runCatching { app.unbindService(c) }
                .onFailure { Log.w(TAG, "Local unbind failed pkg=$packageName", it) }
        }
    }

    internal fun onPackageRemoved(packageName: String) {
        detachPackage(packageName)
        disabled.remove(packageName)
        preferences.edit().putStringSet("disabled_packages", disabled.toSet()).apply()
        pluginStateStore?.remove(packageName)
    }

    fun shutdownAll() {
        stopped = true
        reconnectTasks.keys.toList().forEach(::cancelReconnect)
        for (pkg in connections.keys.toList()) {
            detachPackage(pkg)
        }
    }

    internal open fun onConnectionDied(conn: PluginConnection) {
        onConnectionLost(conn)
    }

    internal open fun onConnectionLost(conn: PluginConnection) {
        val entry = connections.entries.firstOrNull { it.value === conn } ?: return
        if (!connections.remove(entry.key, conn)) return
        dittoConnectionState.compareAndSet(conn, null)
        releaseAudioDetectionPlugin(conn)
        conn.tearDownBinder(reason = "service_disconnected")
        PluginDiagnostics.markDisconnected(entry.key)
        notifyTopologyChanged()
        runCatching { app.unbindService(conn) }
            .onFailure { Log.w(TAG, "unbind disconnected plugin pkg=${entry.key}", it) }
        scheduleReconnect(entry.key)
    }

    internal fun onConnectionReady(conn: PluginConnection): Boolean {
        val packageName = conn.connectionPackageName
        if (
            stopped || !isEnabled(packageName) || connections[packageName] !== conn ||
            !conn.hasVerifiedTrust
        ) return false
        // Android retains both binding records under this connection; one unbind removes both.
        val intent = Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE)
            .setComponent(conn.connectionComponent)
        val ok = runCatching {
            app.bindService(
                intent,
                conn,
                Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS,
            )
        }.getOrElse {
            PluginDiagnostics.recordBindFailure(conn.connectionComponent.flattenToString(), it)
            false
        }
        if (!ok) {
            onConnectionFailed(conn, "activity_starts_bind_failed")
            return false
        }
        if (connections[packageName] !== conn) return false
        cancelReconnect(packageName)
        return true
    }

    internal fun onConnectionInitialized(conn: PluginConnection) {
        if (connections[conn.connectionPackageName] === conn) {
            if (conn.connectionPackageName == TrustedCommunityPlugin.PACKAGE) {
                dittoConnectionState.value = conn
            }
            notifyTopologyChanged()
        }
    }

    internal fun updateAudioDetectionHealth(conn: PluginConnection, healthy: Boolean) {
        val packageName = conn.connectionPackageName
        if (packageName != PluginDetectionEventBridge.DART_PACKAGE &&
            packageName != PluginDetectionEventBridge.FALCON_PACKAGE
        ) {
            return
        }
        if (!healthy) {
            releaseAudioDetectionPlugin(conn)
            return
        }
        if (
            stopped || !isEnabled(packageName) || connections[packageName] !== conn ||
            !conn.hasVerifiedTrust
        ) {
            return
        }
        audioDetectionClaimOwners[packageName] = conn
        audioDetectionDelegatesState.value = audioDetectionClaimOwners.keys.toSet()
    }

    private fun releaseAudioDetectionPlugin(conn: PluginConnection) {
        val packageName = conn.connectionPackageName
        if (audioDetectionClaimOwners.remove(packageName, conn)) {
            audioDetectionDelegatesState.value = audioDetectionClaimOwners.keys.toSet()
        }
    }

    private fun cancelReconnect(packageName: String) {
        reconnectTasks.remove(packageName)?.let(mainHandler::removeCallbacks)
        reconnectAttempts.remove(packageName)
    }

    private fun scheduleReconnect(packageName: String) {
        if (stopped || !isEnabled(packageName)) return
        if (reconnectTasks.containsKey(packageName)) return
        val attempt = reconnectAttempts[packageName] ?: 0
        if (attempt >= 5) {
            Log.e(TAG, "Plugin reconnect exhausted pkg=$packageName; refresh bindings to retry")
            return
        }
        reconnectAttempts[packageName] = attempt + 1
        val delayMillis = 1_000L shl attempt
        val task = Runnable {
            reconnectTasks.remove(packageName)
            if (!stopped && isEnabled(packageName) && !connections.containsKey(packageName)) {
                val target = discoverPluginComponents().firstOrNull { it.packageName == packageName }
                if (target != null) bind(target) else cancelReconnect(packageName)
            }
        }
        reconnectTasks[packageName] = task
        Log.i(TAG, "Scheduling plugin reconnect pkg=$packageName attempt=${attempt + 1} delayMs=$delayMillis")
        mainHandler.postDelayed(task, delayMillis)
    }

    internal open fun onConnectionFailed(conn: PluginConnection, reason: String) {
        Log.w(TAG, "connection failed: $reason")
        dittoConnectionState.compareAndSet(conn, null)
        val entry = connections.entries.firstOrNull { it.value === conn }
        val pkg = entry?.key ?: conn.connectionPackageName
        releaseAudioDetectionPlugin(conn)
        if (entry == null && connections.containsKey(pkg)) return
        if (entry != null) {
            connections.remove(entry.key)
        }
        conn.tearDownBinder(reason = reason)
        runCatching { app.unbindService(conn) }
        PluginDiagnostics.recordAdmissionRejected(
            pkg,
            PluginDiagnostics.admissionCategoryForAidlBindFailure(reason),
            reason,
        )
        notifyTopologyChanged()
        if (reason in setOf("remote_exception", "get_plugin_info", "lifecycle_busy")) {
            scheduleReconnect(pkg)
        } else {
            cancelReconnect(pkg)
        }
    }

    private fun bind(cn: ComponentName) {
        if (stopped || !isEnabled(cn.packageName)) return
        if (PluginLifecycleWork.isBusy(cn.packageName)) {
            scheduleReconnect(cn.packageName)
            return
        }
        val uid = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageUid(
                    cn.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getApplicationInfo(cn.packageName, 0).uid
            }
        }.getOrElse {
            PluginDiagnostics.recordBindFailure(cn.flattenToString(), it)
            return
        }
        val conn = PluginConnection(app, ingress, cn, uid, this, pluginTrust,
            pluginOnlyEnabled = PluginOnlyDittoHost::enabled)
        val intent = Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE).setComponent(cn)
        connections[cn.packageName] = conn
        conn.startBindingTimeout()
        val ok = runCatching {
            app.bindService(
                intent,
                conn,
                Context.BIND_AUTO_CREATE
            )
        }.getOrElse {
            PluginDiagnostics.recordBindFailure(cn.flattenToString(), it)
            false
        }
        if (ok) {
            if (conn.suppressPostBindRegistration) return
            PluginDiagnostics.markBound(cn.packageName)
            notifyTopologyChanged()
        } else {
            connections.remove(cn.packageName, conn)
            conn.tearDownBinder("bind_failed")
            PluginDiagnostics.recordBindFailure(cn.flattenToString(), null)
            scheduleReconnect(cn.packageName)
        }
    }

    internal fun discoverPluginComponents(): List<ComponentName> =
        PluginServiceQuery.discoverPluginComponents(app.packageManager, app.packageName)

    internal fun activeConnectionCountForTest(): Int = connections.size

    internal fun hasConnectionForTest(packageName: String): Boolean =
        connections.containsKey(packageName)

    internal fun connectionForTest(packageName: String): PluginConnection? =
        connections[packageName]

    internal fun connectionFor(packageName: String): PluginConnection? =
        connections[packageName]

    internal fun dittoCotSelected(): Boolean {
        val packageName = TrustedCommunityPlugin.PACKAGE
        if (!isEnabled(packageName) || discoverPluginComponents().none { it.packageName == packageName }) return false
        val connection = connectionFor(packageName)
        if (connection?.hasVerifiedTrust != true || settingsCoordinator.getState(packageName) == null) return true
        return settingsCoordinator.pluginHasSettingEnabled(packageName, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)
    }

    fun addTopologyListener(listener: PluginTopologyListener) {
        topologyListeners.add(listener)
    }

    fun removeTopologyListener(listener: PluginTopologyListener) {
        topologyListeners.remove(listener)
    }

    private fun notifyTopologyChanged() {
        topologyListeners.forEach { it.onPluginTopologyChanged() }
    }

    fun interface PluginTopologyListener {
        fun onPluginTopologyChanged()
    }

    companion object {
        private const val TAG = "CEP-PluginManager"
    }
}
