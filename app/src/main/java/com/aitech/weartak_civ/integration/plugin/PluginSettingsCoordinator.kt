package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side settings cache, validation, and bidirectional sync with bound plugins.
 */
class PluginSettingsCoordinator(
    private val app: WearTakApplication,
    private val snapshotBuilder: HostSettingsSnapshotBuilder = HostSettingsSnapshotBuilder(app),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val cache = ConcurrentHashMap<String, PluginSettingsState>()
    private val pluginRefs = ConcurrentHashMap<String, IWearTakPlugin>()
    private val syncMutex = Mutex()
    private var hostWatcherStarted = false
    private val outboundCotAvailableState = MutableStateFlow(false)
    val outboundCotAvailable = outboundCotAvailableState.asStateFlow()
    private val outboundCotReadyRoutesState = MutableStateFlow<Map<String, Long>>(emptyMap())
    val outboundCotReadyRoutes = outboundCotReadyRoutesState.asStateFlow()
    private var outboundCotGeneration = 0L
    data class ConnectivityToggle(val enabled: Boolean, val binding: IWearTakPlugin)
    private val connectivityTogglesState = MutableStateFlow<Map<String, ConnectivityToggle>>(emptyMap())
    val connectivityToggles = connectivityTogglesState.asStateFlow()
    private val mainUiChangeRevisionsState = MutableStateFlow<Map<String, Long>>(emptyMap())
    val mainUiChangeRevisions = mainUiChangeRevisionsState.asStateFlow()

    fun setConnectivityEnabled(pluginId: String, expected: ConnectivityToggle, enabled: Boolean): SettingsApplyResultParcel {
        if (pluginRefs[pluginId] !== expected.binding || connectivityToggles.value[pluginId] != expected) {
            return connectivityRejected("Plugin connection changed")
        }
        return applyPluginSettings(pluginId, Bundle().apply {
            putBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, enabled)
        })
    }

    private fun connectivityRejected(message: String) = SettingsApplyResultParcel(
        success = false,
        errors = listOf(com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
            code = "connectivity.unavailable", message = message
        ))
    )

    fun registerPlugin(pluginId: String, plugin: IWearTakPlugin) = synchronized(pluginRefs) {
        pluginRefs[pluginId] = plugin
        updateOutboundCotAvailability()
    }

    fun unregisterPlugin(pluginId: String) = synchronized(pluginRefs) {
        pluginRefs.remove(pluginId)
        cache.remove(pluginId)
        mainUiChangeRevisionsState.update { it - pluginId }
        updateOutboundCotAvailability()
    }

    fun notifyMainUiChanged(pluginId: String) {
        mainUiChangeRevisionsState.update { revisions ->
            revisions + (pluginId to ((revisions[pluginId] ?: 0L) + 1L))
        }
    }

    internal fun connectionToken(pluginId: String): IWearTakPlugin? = pluginRefs[pluginId]

    internal fun supportsHostedMainUi(pluginId: String): Boolean {
        val plugin = pluginRefs[pluginId] ?: return false
        return try {
            plugin.capabilities.hostedMainUi && pluginRefs[pluginId] === plugin
        } catch (e: RemoteException) {
            Log.w(TAG, "main UI capability query failed for $pluginId", e)
            false
        }
    }

    internal fun getHostedMainUi(pluginId: String): HostedPluginMainUiResult {
        val plugin = pluginRefs[pluginId] ?: return HostedPluginMainUiResult.NotConnected
        return try {
            if (!plugin.capabilities.hostedMainUi) return HostedPluginMainUiResult.Unsupported
            val cards = HostedPluginMainUiContract.parse(plugin.mainUiSchema)
            if (pluginRefs[pluginId] === plugin) HostedPluginMainUiResult.Ready(cards)
            else HostedPluginMainUiResult.NotConnected
        } catch (e: RemoteException) {
            Log.w(TAG, "main UI query failed for $pluginId", e)
            HostedPluginMainUiResult.Failed("Unable to load plugin page")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "main UI contract rejected for $pluginId: ${e.message}")
            HostedPluginMainUiResult.Failed("Plugin page contract is invalid")
        }
    }

    internal fun invokeHostedMainUiAction(pluginId: String, cardId: String): HostedPluginMainUiResult {
        val plugin = pluginRefs[pluginId] ?: return HostedPluginMainUiResult.NotConnected
        try {
            if (!plugin.capabilities.hostedMainUi) return HostedPluginMainUiResult.Unsupported
            val cards = HostedPluginMainUiContract.parse(plugin.mainUiSchema)
            val action = cards.firstOrNull { it.cardId == cardId }?.action
                ?: return HostedPluginMainUiResult.Failed("Plugin action is unavailable")
            if (!action.enabled) return HostedPluginMainUiResult.Failed("Plugin action is disabled")
            if (pluginRefs[pluginId] !== plugin) return HostedPluginMainUiResult.NotConnected
            plugin.invokeMainUiAction(action.actionId, action.args)
            if (pluginRefs[pluginId] !== plugin) return HostedPluginMainUiResult.NotConnected
        } catch (e: RemoteException) {
            Log.w(TAG, "main UI action failed for $pluginId", e)
            return HostedPluginMainUiResult.Failed("Plugin action failed")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "main UI action contract rejected for $pluginId: ${e.message}")
            return HostedPluginMainUiResult.Failed("Plugin page contract is invalid")
        }
        return getHostedMainUi(pluginId)
    }

    fun getState(pluginId: String): PluginSettingsState? = cache[pluginId]

    /** True when any connected plugin declares [fieldId] and currently has it set. */
    fun anyPluginHasSettingEnabled(fieldId: String): Boolean =
        cache.entries.any { (pluginId, state) ->
            pluginRefs.containsKey(pluginId) &&
                state.schema.fields.any { it.fieldId == fieldId } && state.values.getBoolean(fieldId, false)
        }

    /**
     * Fire [actionId] at every connected plugin that declares [fieldId] and currently has it set.
     *
     * The opt-in is the plugin's own settings schema — declaring the field advertises the interest,
     * and its value is the on/off switch the wearer already controls through the normal settings UI.
     * No new contract surface, and a plugin that never declares the field is never contacted.
     *
     * **Always asynchronous.** Callers are on WearTAK's own CoT dispatch paths; a plugin that is
     * slow, wedged, or dying must never delay the host's own sending, so this dispatches onto the
     * coordinator's scope and returns immediately. Delivery is therefore best-effort and unordered
     * with respect to the host's other endpoints.
     */
    fun invokeActionOnPluginsWithSetting(fieldId: String, actionId: String, args: Bundle) {
        val targets = cache.entries
            .filter { (_, state) ->
                state.schema.fields.any { it.fieldId == fieldId } && state.values.getBoolean(fieldId, false)
            }
            .mapNotNull { (pluginId, _) -> pluginRefs[pluginId]?.let { pluginId to it } }
        if (targets.isEmpty()) return

        scope.launch {
            for ((pluginId, plugin) in targets) {
                if (pluginRefs[pluginId] !== plugin) continue
                if (fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT &&
                    (pluginRefs[pluginId] !== plugin ||
                        cache[pluginId]?.schema?.fields?.none {
                            it.fieldId == fieldId && it.fieldType == SettingsFieldTypes.TOGGLE
                        } != false ||
                        cache[pluginId]?.values?.getBoolean(fieldId, false) != true)) continue
                runCatching { plugin.invokeMainUiAction(actionId, args) }
                    .onFailure { Log.w(TAG, "action $actionId failed for $pluginId: ${it.message}") }
            }
        }
    }

    /** Deliver a best-effort host action to one currently connected plugin. */
    fun invokeActionOnPlugin(pluginId: String, actionId: String, args: Bundle) {
        val plugin = pluginRefs[pluginId] ?: return
        scope.launch {
            if (pluginRefs[pluginId] !== plugin) return@launch
            runCatching { plugin.invokeMainUiAction(actionId, args) }
                .onFailure { Log.w(TAG, "action $actionId failed for $pluginId: ${it.message}") }
        }
    }

    /** Notify the plugin that originated a received marker when the wearer removes it locally. */
    fun notifyLocalMarkerRemoved(marker: DataRepository.ReceivedMarker, removedAt: Long) {
        val sourceId = marker.sourceServerId ?: return
        if (!sourceId.startsWith(PLUGIN_SOURCE_PREFIX)) return
        val pluginId = sourceId.removePrefix(PLUGIN_SOURCE_PREFIX).takeIf(String::isNotBlank) ?: return
        invokeActionOnPlugin(
            pluginId = pluginId,
            actionId = ACTION_LOCAL_MARKER_REMOVED,
            args = Bundle().apply {
                putString(KEY_MARKER_UID, marker.uid)
                putLong(KEY_EVENT_TIME, marker.timeReceived)
                putLong(KEY_RECEIVED_AT, marker.receivedAt)
                putLong(KEY_REMOVED_AT, removedAt)
            },
        )
    }

    /**
     * Deliver a host-originated value to every connected plugin that declares [fieldId] in its
     * settings schema, and return the ids that accepted it.
     *
     * This is the bridge for host-side data that has no dedicated contract method — notably inbound
     * CoT payloads. [IWearTakHostCallback] is plugin-to-host in every direction, so a plugin cannot
     * observe CoT arriving from a TAK server; routing it through the settings channel reuses the
     * one host-to-plugin path that carries an arbitrary value and validates it against the plugin's
     * own declared schema. A plugin opts in simply by declaring the field.
     */
    fun pushValueToPluginsDeclaring(fieldId: String, value: String): List<String> {
        val targets = cache.entries
            .filter { (_, state) -> state.schema.fields.any { it.fieldId == fieldId } }
            .map { it.key }
        if (targets.isEmpty()) return emptyList()

        val accepted = mutableListOf<String>()
        for (pluginId in targets) {
            val bundle = Bundle().apply { putString(fieldId, value) }
            val result = runCatching { applyPluginSettings(pluginId, bundle) }.getOrNull()
            if (result?.success == true) {
                accepted += pluginId
            } else {
                Log.w(TAG, "pushValue $fieldId rejected by $pluginId: ${result?.errors}")
            }
        }
        return accepted
    }

    @Synchronized
    fun startHostSettingsWatcher() {
        if (hostWatcherStarted) return
        hostWatcherStarted = true
        val repo = app.repo
        scope.launch {
            val baseWatch = combine(
                repo.getCallsign,
                repo.getTeam,
                repo.getRole,
                repo.reportingUpdateIntervalSecs,
                repo.sitxStatus,
            ) { callsign, team, role, interval, sitx ->
                HostWatchKey(callsign, team, role, interval, sitx, false)
            }
            combine(
                baseWatch,
                repo.getEnableTakEndpoint,
            ) { base, takEnabled ->
                base.copy(takConnected = takEnabled)
            }
                .distinctUntilChanged()
                .collect {
                    pushHostSettingsToAllPlugins()
                }
        }
    }

    /**
     * Bind-time initialization: capabilities, schema/values, host snapshot push.
     */
    fun onPluginConnected(pluginId: String, plugin: IWearTakPlugin, isCurrent: () -> Boolean = { true }) {
        synchronized(pluginRefs) {
            if (!isCurrent()) return
            registerPlugin(pluginId, plugin)
        }
        startHostSettingsWatcher()
        runBlocking {
            // A frozen plugin's handshake must not hold the shared settings sync mutex.
            refreshFromPluginLocked(pluginId, plugin)
            pushHostSettingsToPluginLocked(pluginId, plugin)
        }
    }

    fun onPluginSettingsSchemaChanged(pluginId: String) {
        val plugin = pluginRefs[pluginId] ?: return
        scope.launch {
            syncMutex.withLock { refreshFromPluginLocked(pluginId, plugin) }
        }
    }

    fun onPluginSettingsValuesChanged(pluginId: String) {
        val plugin = pluginRefs[pluginId] ?: return
        scope.launch {
            syncMutex.withLock { refreshValuesFromPluginLocked(pluginId, plugin) }
        }
    }

    fun pushHostSettingsToAllPlugins() {
        scope.launch {
            syncMutex.withLock {
                val snapshot = snapshotBuilder.build()
                for ((pluginId, plugin) in pluginRefs) {
                    pushHostSettingsSnapshotLocked(pluginId, plugin, snapshot)
                }
            }
        }
    }

    /**
     * Host-initiated apply (partial patch by default). Validates locally then calls plugin binder.
     */
    fun applyPluginSettings(
        pluginId: String,
        incoming: Bundle,
        applyMode: Int = SettingsApplyMode.PARTIAL_PATCH,
    ): SettingsApplyResultParcel {
        if (incoming.containsKey(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) &&
            runBlocking { app.repo.networkSettingsLocked.first() }) {
            Log.w(TAG, "Connectivity settings rejected while network settings are locked")
            return connectivityRejected("Network settings are locked")
        }
        val plugin = pluginRefs[pluginId]
            ?: return SettingsApplyResultParcel(
                success = false,
                applyMode = applyMode,
                errors = listOf(
                    com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                        code = "plugin.not_bound",
                        message = "Plugin not connected",
                    ),
                ),
            )
        val state = cache[pluginId]
            ?: return SettingsApplyResultParcel(
                success = false,
                applyMode = applyMode,
                errors = listOf(
                    com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                        code = "settings.not_loaded",
                        message = "Settings cache empty",
                    ),
                ),
            )
        if (!state.settingsSupported) {
            return SettingsApplyResultParcel(
                success = false,
                applyMode = applyMode,
                errors = listOf(
                    com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                        code = "settings.unsupported",
                        message = "Plugin does not expose settings",
                    ),
                ),
            )
        }
        val local = SettingsApplyValidator.applySettingsWithResult(
            schema = state.schema,
            previous = state.values,
            incoming = incoming,
            hostSettings = state.hostSettings,
            applyMode = applyMode,
        )
        if (!local.success) {
            return synchronized(pluginRefs) {
                if (pluginRefs[pluginId] !== plugin || cache[pluginId] !== state) {
                    return@synchronized connectivityRejected("Plugin settings changed")
                }
                updateCache(pluginId, state.copy(lastApplyResult = local, lastValidationErrors = local.errors))
                PluginDiagnostics.recordSettingsApply(pluginId, local)
                local
            }
        }
        if (pluginRefs[pluginId] !== plugin || cache[pluginId] !== state) {
            return connectivityRejected("Plugin settings changed")
        }
        if (incoming.containsKey(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) &&
            runBlocking { app.repo.networkSettingsLocked.first() }) {
            return connectivityRejected("Network settings are locked")
        }
        val remote = invokePluginApply(plugin, incoming, applyMode)
        return synchronized(pluginRefs) {
            if (pluginRefs[pluginId] !== plugin || cache[pluginId] !== state) {
                return@synchronized connectivityRejected("Plugin connection changed")
            }
            if (remote.success) {
                updateCache(
                    pluginId,
                    state.copy(
                        values = Bundle(remote.values),
                        lastApplyResult = remote,
                        lastValidationErrors = emptyList(),
                        lastSyncedAtMillis = System.currentTimeMillis(),
                    ),
                )
            } else {
                updateCache(pluginId, state.copy(lastApplyResult = remote, lastValidationErrors = remote.errors))
            }
            PluginDiagnostics.recordSettingsApply(pluginId, remote)
            remote
        }
    }

    private suspend fun refreshFromPluginLocked(pluginId: String, plugin: IWearTakPlugin) {
        if (pluginRefs[pluginId] !== plugin) return
        val caps = runCatching { plugin.capabilities }.getOrDefault(PluginCapabilitiesParcel())
        val settingsSupported = caps.hostedSettingsUi || caps.externalSettingsActivity
        var schema = SettingsSchemaParcel()
        var values = Bundle()
        var errors = emptyList<com.weartak.plugin.sdk.model.SettingsValidationErrorParcel>()
        if (settingsSupported) {
            val rawSchema = runCatching { plugin.settingsSchema }.getOrDefault(SettingsSchemaParcel())
            when (val v = SettingsSchemaContractValidator.validate(rawSchema)) {
                is ContractValidationResult.Ok -> schema = v.value
                is ContractValidationResult.Err -> {
                    errors = v.issues.map {
                        com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                            code = it.code,
                            message = it.message,
                        )
                    }
                    Log.w(TAG, "settings schema validation failed pluginId=$pluginId errors=$errors")
                }
            }
            values = runCatching { plugin.settingsValues }.getOrDefault(Bundle())
        }
        val prior = cache[pluginId]
        val host = prior?.hostSettings ?: snapshotBuilder.build()
        if (pluginRefs[pluginId] !== plugin) return
        updateConnectionCache(
            pluginId,
            plugin,
            PluginSettingsState(
                pluginId = pluginId,
                capabilities = caps,
                schema = schema,
                values = values,
                hostSettings = host,
                hostSettingsRevision = host.revision,
                lastValidationErrors = errors,
                settingsSupported = settingsSupported,
                lastSyncedAtMillis = System.currentTimeMillis(),
            ),
        )
        PluginDiagnostics.recordSettingsCache(pluginId, cache[pluginId])
        Log.i(
            TAG,
            "settings cache refreshed pluginId=$pluginId supported=$settingsSupported " +
                "hostedUi=${caps.hostedSettingsUi} external=${caps.externalSettingsActivity} fields=${schema.fields.size}",
        )
    }

    private suspend fun refreshValuesFromPluginLocked(pluginId: String, plugin: IWearTakPlugin) {
        if (pluginRefs[pluginId] !== plugin) return
        val prior = cache[pluginId] ?: return
        if (!prior.settingsSupported) return
        val values = runCatching { plugin.settingsValues }.getOrDefault(Bundle())
        if (pluginRefs[pluginId] !== plugin) return
        updateConnectionCache(
            pluginId,
            plugin,
            prior.copy(
                values = values,
                lastSyncedAtMillis = System.currentTimeMillis(),
            ),
        )
        PluginDiagnostics.recordSettingsCache(pluginId, cache[pluginId])
    }

    private suspend fun pushHostSettingsToPluginLocked(pluginId: String, plugin: IWearTakPlugin) {
        val snapshot = snapshotBuilder.build()
        pushHostSettingsSnapshotLocked(pluginId, plugin, snapshot)
    }

    private fun pushHostSettingsSnapshotLocked(
        pluginId: String,
        plugin: IWearTakPlugin,
        snapshot: com.weartak.plugin.sdk.model.HostSettingsParcel,
    ) {
        if (pluginRefs[pluginId] !== plugin) return
        val prior = cache[pluginId]
        if (prior != null) {
            updateConnectionCache(
                pluginId,
                plugin,
                prior.copy(
                    hostSettings = snapshot,
                    hostSettingsRevision = snapshot.revision,
                ),
            )
        }
        runCatching { plugin.onHostSettingsChanged(snapshot) }
            .onFailure { Log.d(TAG, "onHostSettingsChanged not available pluginId=$pluginId", it) }
        if (pluginRefs[pluginId] !== plugin) return
        PluginDiagnostics.recordHostSettingsPush(pluginId, snapshot)
        if (prior?.settingsSupported == true && prior.schema.fields.any { it.hostSettingKey != null }) {
            val recompute = SettingsApplyValidator.applySettingsWithResult(
                schema = prior.schema,
                previous = prior.values,
                incoming = Bundle(),
                hostSettings = snapshot,
                applyMode = SettingsApplyMode.HOST_DRIVEN_RECOMPUTE,
            )
            if (recompute.success && recompute.anyValueChanged) {
                invokePluginApply(plugin, Bundle(), SettingsApplyMode.HOST_DRIVEN_RECOMPUTE)
                if (pluginRefs[pluginId] !== plugin) return
                updateConnectionCache(
                    pluginId,
                    plugin,
                    prior.copy(
                        values = Bundle(recompute.values),
                        hostSettings = snapshot,
                        hostSettingsRevision = snapshot.revision,
                        lastApplyResult = recompute,
                        lastSyncedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun invokePluginApply(
        plugin: IWearTakPlugin,
        values: Bundle,
        applyMode: Int,
    ): SettingsApplyResultParcel {
        return runCatching { plugin.applySettingsWithResult(values, applyMode) }
            .getOrElse { remoteFailure ->
                Log.d(TAG, "applySettingsWithResult unavailable, using legacy apply", remoteFailure)
                return runCatching {
                    plugin.applySettings(values)
                    SettingsApplyResultParcel(
                        success = true,
                        applyMode = applyMode,
                        values = Bundle(values),
                        anyValueChanged = true,
                    )
                }.getOrElse {
                    SettingsApplyResultParcel(
                        success = false,
                        applyMode = applyMode,
                        errors = listOf(
                            com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                                code = "binder.remote",
                                message = it.message ?: "apply failed",
                            ),
                        ),
                    )
                }
            }
    }

    private fun updateCache(pluginId: String, state: PluginSettingsState) {
        cache[pluginId] = state
        updateOutboundCotAvailability()
    }

    private fun updateConnectionCache(pluginId: String, plugin: IWearTakPlugin, state: PluginSettingsState) =
        synchronized(pluginRefs) {
            if (pluginRefs[pluginId] === plugin) updateCache(pluginId, state)
        }

    private fun updateOutboundCotAvailability() = synchronized(outboundCotReadyRoutesState) {
        connectivityTogglesState.value = cache.entries.mapNotNull { (id, state) ->
            val plugin = pluginRefs[id] ?: return@mapNotNull null
            if (!state.settingsSupported || state.schema.fields.none {
                it.fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT &&
                    it.fieldType == SettingsFieldTypes.TOGGLE
            }) return@mapNotNull null
            id to ConnectivityToggle(state.values.getBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, false), plugin)
        }.toMap()
        val ready = cache.entries.filter { (pluginId, state) ->
            pluginRefs.containsKey(pluginId) &&
                state.schema.fields.any { it.fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT } &&
                state.values.getBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, false)
        }.map { it.key }
        val previous = outboundCotReadyRoutesState.value
        outboundCotReadyRoutesState.value = ready.associateWith { previous[it] ?: ++outboundCotGeneration }
        outboundCotAvailableState.value = ready.isNotEmpty()
    }

    private data class HostWatchKey(
        val callsign: String,
        val team: String,
        val role: String,
        val reportingInterval: Int,
        val sitx: DataRepository.SitxServerStatus,
        val takConnected: Boolean,
    )

    companion object {
        const val ACTION_LOCAL_MARKER_REMOVED = "marker.local_removed"
        const val KEY_MARKER_UID = "markerUid"
        const val KEY_EVENT_TIME = "eventTime"
        const val KEY_RECEIVED_AT = "receivedAt"
        const val KEY_REMOVED_AT = "removedAt"

        private const val PLUGIN_SOURCE_PREFIX = "plugin:"
        private const val TAG = "CEP-PluginSettings"
    }
}
