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
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.WearTakPluginContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers plugin services, binds with [Context.BIND_AUTO_CREATE], and coordinates
 * [PluginConnection] teardown on death or explicit eviction. Only a verified handshake
 * adds [Context.BIND_ALLOW_ACTIVITY_STARTS] to the same connection.
 * Unexpected disconnects release the dead binding and retry discovery/binding after
 * 1, 2, 4, 8 and 16 seconds. Explicit unbind/shutdown cancels recovery; admission
 * failures are not retried. A successful handshake resets the retry budget.
 * The Android binding is released only after remote stop/unregister cleanup has run,
 * because plugins may close their binder in onUnbind.
 */
open class PluginManager(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val pluginTrust: PluginTrust = CepProvider.pluginTrust(),
    val settingsCoordinator: PluginSettingsCoordinator = PluginSettingsCoordinator(app),
    private val pluginStateStore: PluginStateStore? = null,
    private val dittoNetworkPolicyRepository: DataRepository? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connections = ConcurrentHashMap<String, PluginConnection>()
    private val audioDetectionClaimOwners = ConcurrentHashMap<String, PluginConnection>()
    private val audioDetectionDelegatesState = MutableStateFlow<Set<String>>(emptySet())
    val audioDetectionDelegates = audioDetectionDelegatesState.asStateFlow()
    internal val crmPluginStatus = CrmPluginStatusStore()
    private val dittoConnectionState = MutableStateFlow<PluginConnection?>(null)
    internal val dittoReadyConnection = dittoConnectionState.asStateFlow()
    private val topologyListeners = CopyOnWriteArraySet<PluginTopologyListener>()
    private val reconnectTasks = mutableMapOf<String, Runnable>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val preferences = app.getSharedPreferences("plugin_loading", Context.MODE_PRIVATE)
    internal val dittoTrafficFence = DittoTrafficFence(initiallyEnabled = dittoNetworkPolicyRepository == null)
    private val dittoNetworkEnabledState = MutableStateFlow(dittoNetworkPolicyRepository == null)
    internal val dittoNetworkEnabled = dittoNetworkEnabledState.asStateFlow()
    private val dittoPolicyLoadedState = MutableStateFlow(dittoNetworkPolicyRepository == null)
    internal val dittoNetworkPolicyLoaded = dittoPolicyLoadedState.asStateFlow()
    private val dittoPolicyMigrationFailedState = MutableStateFlow(false)
    internal val dittoNetworkPolicyMigrationFailed = dittoPolicyMigrationFailedState.asStateFlow()
    private val dittoNetworkChange = Mutex()
    private val dittoPolicyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dittoPolicyRetryScheduled = AtomicBoolean(false)
    @Volatile private var dittoPolicyLoaded = dittoNetworkPolicyRepository == null
    @Volatile private var dittoLegacyMigrationPending = false
    private val disabled = preferences.getStringSet("disabled_packages", emptySet()).orEmpty().toMutableSet()
    private var stopped = false

    init {
        settingsCoordinator.trafficAdmissionForPlugin = ::captureDittoTrafficAdmission
        settingsCoordinator.networkEnabledForPlugin = { pluginId ->
            pluginId != TrustedCommunityPlugin.PACKAGE || dittoTrafficAllowed()
        }
        dittoNetworkPolicyRepository?.let { repository ->
            dittoPolicyScope.launch {
                repository.getDittoPluginNetworkPolicy
                    .catch { error ->
                        Log.e(TAG, "Ditto network policy unreadable; keeping traffic blocked", error)
                        failDittoNetworkPolicyMigration()
                    }
                    .collect { policy ->
                        dittoNetworkChange.withLock {
                            if (policy == null) prepareLegacyDittoNetworkMigration()
                            else applyDittoNetworkPolicy(policy)
                        }
                    }
            }
        }
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
            val networkPolicyOff = existing == TrustedCommunityPlugin.PACKAGE &&
                isEnabled(existing) && !networkBindingAllowed(existing)
            if (existing !in targetPkgs || !isEnabled(existing) || !networkBindingAllowed(existing)) {
                detachPackage(existing, shutdownRemotePlugin = !networkPolicyOff)
            }
        }
        for (cn in targets) {
            if (!isEnabled(cn.packageName) || !networkBindingAllowed(cn.packageName) ||
                connections.containsKey(cn.packageName)) continue
            bind(cn)
        }
        notifyTopologyChanged()
    }

    internal fun retryPackageDiscovery(packageName: String) {
        if (stopped || !isEnabled(packageName) || !networkBindingAllowed(packageName) ||
            connections.containsKey(packageName)) return
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

    internal fun detachPackage(packageName: String, shutdownRemotePlugin: Boolean = true) {
        cancelReconnect(packageName)
        val c = connections.remove(packageName)
        if (c != null) dittoConnectionState.compareAndSet(c, null)
        if (c != null) releaseAudioDetectionPlugin(c)
        if (c != null) {
            c.tearDownBinder(reason = "unbindPackage", shutdownRemotePlugin = shutdownRemotePlugin,
                afterRemoteCleanup = { releaseBinding(c, packageName) })
        }
        PluginDiagnostics.markUnbound(packageName)
        notifyTopologyChanged()
    }

    /**
     * Unbinding lets the plugin close its binder, so it must follow remote stop/unregister.
     * Called from [PluginConnection.tearDownBinder] once that cleanup has run.
     */
    private fun releaseBinding(conn: PluginConnection, packageName: String) {
        if (!conn.claimLocalUnbind()) return
        runCatching { app.unbindService(conn) }
            .onFailure { Log.w(TAG, "Local unbind failed pkg=$packageName", it) }
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
        conn.tearDownBinder(reason = "service_disconnected",
            shutdownRemotePlugin = shouldShutdownRemotePlugin(entry.key),
            afterRemoteCleanup = { releaseBinding(conn, entry.key) })
        PluginDiagnostics.markDisconnected(entry.key)
        notifyTopologyChanged()
        scheduleReconnect(entry.key)
    }

    internal fun onConnectionReady(conn: PluginConnection): Boolean {
        val packageName = conn.connectionPackageName
        if (
            stopped || !isEnabled(packageName) || !networkBindingAllowed(packageName) ||
            (packageName == TrustedCommunityPlugin.PACKAGE &&
                !dittoPolicyLoaded && !dittoLegacyMigrationPending) ||
            connections[packageName] !== conn ||
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
        if (stopped || !isEnabled(packageName) || !networkBindingAllowed(packageName)) return
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
            if (!stopped && isEnabled(packageName) && networkBindingAllowed(packageName) &&
                !connections.containsKey(packageName)) {
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
        conn.tearDownBinder(reason = reason, shutdownRemotePlugin = shouldShutdownRemotePlugin(pkg),
            afterRemoteCleanup = { releaseBinding(conn, pkg) })
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
        if (stopped || !isEnabled(cn.packageName) || !networkBindingAllowed(cn.packageName)) return
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
            conn.tearDownBinder("bind_failed", shutdownRemotePlugin = shouldShutdownRemotePlugin(cn.packageName))
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

    /** Inventory is local/cached: never blocks the BLE handshake on remote Binder calls. */
    fun companionPluginInventory(): List<org.json.JSONObject> = discoverPluginComponents()
        .sortedBy { it.packageName }.take(64).map { component ->
            val connection = connections[component.packageName]
            val info = connection?.pluginInfoSnapshot()
            val installed = runCatching { app.packageManager.getPackageInfo(component.packageName, 0) }.getOrNull()
            val ready = isEnabled(component.packageName) && connection?.companionReady() == true
            val caps = if (ready) settingsCoordinator.getState(component.packageName)?.capabilities else null
            org.json.JSONObject().put("packageName", component.packageName)
                .put("name", info?.displayName?.take(256) ?: component.packageName)
                .put("versionName", (info?.versionName ?: installed?.versionName ?: "unknown").take(64))
                .put("versionCode", installed?.longVersionCode ?: 0)
                .put("installed", true).put("enabled", isEnabled(component.packageName))
                .put("loaded", ready).put("apiVersion", connection?.apiVersionSnapshot() ?: 0)
                .put("sdkCapabilities", org.json.JSONObject().put("hostedSettingsUi", caps?.hostedSettingsUi ?: false)
                    .put("externalSettingsActivity", caps?.externalSettingsActivity ?: false)
                    .put("hostedMainUi", caps?.hostedMainUi ?: false).put("externalMainActivity", caps?.externalMainActivity ?: false)
                    .put("cotReadyEvents", caps?.cotReadyEvents ?: false).put("mapLayers", caps?.mapLayers ?: false)
                    .put("deviceIntegration", caps?.deviceIntegration ?: false).put("sharedMemory", caps?.sharedMemory ?: false))
                .put("features", org.json.JSONObject(if (ready) connection!!.companionFeaturesSnapshot() else emptyMap<String, Int>()))
        }

    internal fun companionVoiceTarget(): String? = companionPluginInventory()
        .filter { it.getBoolean("loaded") && it.getJSONObject("features").optInt("voice_channels_v1") == 1 }
        .map { it.getString("packageName") }.singleOrNull()

    internal fun dittoCotSelected(): Boolean {
        val packageName = TrustedCommunityPlugin.PACKAGE
        if (!dittoTrafficAllowed()) return false
        if (!isEnabled(packageName) || discoverPluginComponents().none { it.packageName == packageName }) return false
        val connection = connectionFor(packageName)
        if (connection?.hasVerifiedTrust != true || settingsCoordinator.getState(packageName) == null) return true
        return settingsCoordinator.pluginHasSettingEnabled(packageName, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)
    }

    private fun networkBindingAllowed(packageName: String): Boolean =
        packageName != TrustedCommunityPlugin.PACKAGE ||
            dittoTrafficAllowed() || dittoLegacyMigrationPending

    private fun shouldShutdownRemotePlugin(packageName: String): Boolean =
        packageName != TrustedCommunityPlugin.PACKAGE || networkBindingAllowed(packageName)

    internal fun dittoTrafficAllowed(): Boolean =
        dittoPolicyLoaded && dittoNetworkEnabledState.value

    private fun captureDittoTrafficAdmission(pluginId: String): (() -> AutoCloseable?)? {
        if (pluginId != TrustedCommunityPlugin.PACKAGE) return null
        val generation = dittoTrafficFence.currentGeneration()
        return {
            if (dittoTrafficAllowed()) generation?.let(dittoTrafficFence::enter) else null
        }
    }

    internal fun isDittoNetworkPolicyMigrationPending(): Boolean = dittoLegacyMigrationPending

    internal suspend fun migrateLegacyDittoNetworkPolicy(legacyEnabled: Boolean) {
        val repository = dittoNetworkPolicyRepository ?: return
        dittoNetworkChange.withLock {
            if (dittoPolicyLoaded || !dittoLegacyMigrationPending) return@withLock
            val enabled = repository.initializeDittoPluginNetworkPolicy(legacyEnabled)
            applyDittoNetworkPolicy(enabled)
        }
    }

    internal suspend fun failDittoNetworkPolicyMigration() {
        dittoNetworkChange.withLock {
            dittoLegacyMigrationPending = false
            dittoPolicyLoadedState.value = false
            dittoPolicyMigrationFailedState.value = true
            dittoPolicyLoaded = false
            dittoNetworkEnabledState.value = false
            settingsCoordinator.refreshOutboundCotAvailability()
            disableDittoNetwork()
        }
    }

    internal suspend fun setDittoNetworkEnabled(enabled: Boolean): Boolean {
        val repository = dittoNetworkPolicyRepository ?: return false
        return dittoNetworkChange.withLock {
            if (!dittoPolicyLoaded && !dittoPolicyMigrationFailedState.value) return@withLock false
            if (!enabled) {
                val drain = disableDittoNetwork()
                val rejection = try {
                    repository.storeDittoPluginNetworkEnabled(false)
                } catch (error: IOException) {
                    Log.e(TAG, "Failed to persist Ditto network disable; traffic remains blocked", error)
                    return@withLock false
                }
                if (rejection != null) {
                    Log.w(TAG, "Ditto network disable rejected: $rejection")
                    readDittoNetworkPolicy()?.let { applyDittoNetworkPolicy(it) }
                    return@withLock false
                }
                dittoPolicyLoaded = true
                dittoPolicyLoadedState.value = true
                dittoLegacyMigrationPending = false
                dittoPolicyMigrationFailedState.value = false
                settingsCoordinator.refreshOutboundCotAvailability()
                val drainedInTime = withTimeoutOrNull(5_000) { drain.await(); true } ?: false
                if (!drainedInTime) Log.e(TAG, "Ditto host traffic drain timed out")
                return@withLock drainedInTime
            }

            if (dittoPolicyLoaded && dittoNetworkEnabledState.value) return@withLock true
            val drain = dittoTrafficFence.disable()
            val drainedInTime = withTimeoutOrNull(5_000) { drain.await(); true } ?: false
            if (!drainedInTime) {
                Log.w(TAG, "Ditto network enable deferred until host traffic drains")
                return@withLock false
            }
            val rejection = try {
                repository.storeDittoPluginNetworkEnabled(true)
            } catch (error: IOException) {
                Log.e(TAG, "Failed to persist Ditto network enable", error)
                return@withLock false
            }
            if (rejection != null) {
                Log.w(TAG, "Ditto network enable rejected: $rejection")
                return@withLock false
            }
            applyDittoNetworkPolicy(true)
            dittoNetworkEnabledState.value
        }
    }

    private suspend fun applyLatestDittoNetworkPolicy() = dittoNetworkChange.withLock {
        val policy = readDittoNetworkPolicy()
        if (policy == null) prepareLegacyDittoNetworkMigration()
        else applyDittoNetworkPolicy(policy)
    }

    private suspend fun readDittoNetworkPolicy(): Boolean? {
        val repository = dittoNetworkPolicyRepository ?: return null
        return try {
            repository.getDittoPluginNetworkPolicy.first()
        } catch (error: IOException) {
            Log.e(TAG, "Ditto network policy read failed", error)
            null
        }
    }

    private suspend fun prepareLegacyDittoNetworkMigration() {
        if (dittoPolicyLoaded || dittoLegacyMigrationPending) return
        dittoLegacyMigrationPending = true
        dittoPolicyMigrationFailedState.value = false
        withContext(Dispatchers.Main.immediate) { if (!stopped) refreshBindings() }
    }

    private suspend fun applyDittoNetworkPolicy(enabled: Boolean) {
        dittoPolicyLoaded = true
        dittoLegacyMigrationPending = false
        dittoPolicyLoadedState.value = true
        dittoPolicyMigrationFailedState.value = false
        if (!enabled) {
            dittoNetworkEnabledState.value = false
            settingsCoordinator.refreshOutboundCotAvailability()
            disableDittoNetwork()
            return
        }

        if (dittoTrafficFence.enable() == null) {
            val drain = dittoTrafficFence.disable()
            scheduleDittoPolicyRetry(drain)
            return
        }
        dittoNetworkEnabledState.value = true
        settingsCoordinator.refreshOutboundCotAvailability()
        withContext(Dispatchers.Main.immediate) {
            if (stopped) return@withContext
            val bootstrap = connectionFor(TrustedCommunityPlugin.PACKAGE)
            if (bootstrap != null && !bootstrap.hasCurrentDittoTrafficGeneration()) {
                detachPackage(TrustedCommunityPlugin.PACKAGE, shutdownRemotePlugin = false)
            }
            refreshBindings()
        }
    }

    private suspend fun disableDittoNetwork(): kotlinx.coroutines.Deferred<Unit> {
        val drain = dittoTrafficFence.disable()
        dittoNetworkEnabledState.value = false
        settingsCoordinator.refreshOutboundCotAvailability()
        withContext(Dispatchers.Main.immediate) {
            cancelReconnect(TrustedCommunityPlugin.PACKAGE)
            detachPackage(TrustedCommunityPlugin.PACKAGE, shutdownRemotePlugin = false)
        }
        return drain
    }

    private fun scheduleDittoPolicyRetry(drain: kotlinx.coroutines.Deferred<Unit>) {
        if (!dittoPolicyRetryScheduled.compareAndSet(false, true)) return
        dittoPolicyScope.launch {
            try {
                drain.await()
                applyLatestDittoNetworkPolicy()
            } finally {
                dittoPolicyRetryScheduled.set(false)
            }
        }
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
