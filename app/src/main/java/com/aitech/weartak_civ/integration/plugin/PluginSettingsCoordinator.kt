package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
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
    private val crmSettingsState = MutableStateFlow<CrmPluginSettings?>(null)
    internal val crmSettings = crmSettingsState.asStateFlow()
    private val pluginRefs = ConcurrentHashMap<String, IWearTakPlugin>()
    private val transportHosts = ConcurrentHashMap<String, PluginTransportHostV1Impl>()
    private val syncMutex = Mutex()
    private var hostWatcherStarted = false
    internal var onLegacyAudioSettingsRejected: (String, IWearTakPlugin) -> Unit = { _, _ -> }
    private val outboundCotAvailableState = MutableStateFlow(false)
    val outboundCotAvailable = outboundCotAvailableState.asStateFlow()
    private val outboundCotReadyRoutesState = MutableStateFlow<Map<String, Long>>(emptyMap())
    val outboundCotReadyRoutes = outboundCotReadyRoutesState.asStateFlow()
    private var outboundCotGeneration = 0L
    @Volatile private var dittoForwardingFenced = false
    @Volatile private var dittoSettingsReadFailed = false
    private var dittoForwardingRevision = 0L
    data class ConnectivityToggle(val enabled: Boolean, val binding: IWearTakPlugin)
    private val connectivityTogglesState = MutableStateFlow<Map<String, ConnectivityToggle>>(emptyMap())
    val connectivityToggles = connectivityTogglesState.asStateFlow()
    internal val routeStatuses = PluginRouteStatuses()
    internal val connectionStatuses = routeStatuses.statuses
    private val mainUiChangeRevisionsState = MutableStateFlow<Map<String, Long>>(emptyMap())
    val mainUiChangeRevisions = mainUiChangeRevisionsState.asStateFlow()
    internal var trafficAdmissionForPlugin: (String) -> (() -> AutoCloseable?)? = { null }
    internal var networkEnabledForPlugin: (String) -> Boolean = { true }

    private data class ActionTarget(
        val pluginId: String,
        val plugin: IWearTakPlugin,
        val token: Long?,
        val trafficAdmission: (() -> AutoCloseable?)?,
    )

    fun setConnectivityEnabled(pluginId: String, expected: ConnectivityToggle, enabled: Boolean): SettingsApplyResultParcel {
        if (pluginRefs[pluginId] !== expected.binding || connectivityToggles.value[pluginId] != expected) {
            return connectivityRejected("Plugin connection changed")
        }
        return applyPluginSettings(pluginId, Bundle().apply {
            putBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, enabled)
        })
    }

    internal fun setCrmEnabled(enabled: Boolean): SettingsApplyResultParcel = runBlocking {
        syncMutex.withLock {
            if (crmSettingsState.value == null) return@withLock connectivityRejected("CRM settings are unavailable")
            applyPluginSettings(CRM_PLUGIN_PACKAGE, Bundle().apply {
                putBoolean("enable_crm", enabled)
            })
        }
    }

    private fun connectivityRejected(message: String) = SettingsApplyResultParcel(
        success = false,
        errors = listOf(com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
            code = "connectivity.unavailable", message = message
        ))
    )

    fun registerPlugin(pluginId: String, plugin: IWearTakPlugin) = synchronized(pluginRefs) {
        if (pluginId == TrustedCommunityPlugin.PACKAGE) ++dittoForwardingRevision
        pluginRefs[pluginId] = plugin
        updateOutboundCotAvailability()
    }

    fun unregisterPlugin(pluginId: String) = synchronized(pluginRefs) {
        if (pluginId == TrustedCommunityPlugin.PACKAGE) ++dittoForwardingRevision
        pluginRefs.remove(pluginId)
        transportHosts.remove(pluginId)
        cache.remove(pluginId)
        if (pluginId == CRM_PLUGIN_PACKAGE) crmSettingsState.value = null
        mainUiChangeRevisionsState.update { it - pluginId }
        updateOutboundCotAvailability()
    }

    internal fun registerTransportHost(pluginId: String, host: PluginTransportHostV1Impl) {
        transportHosts[pluginId] = host
    }

    internal fun unregisterTransportHost(pluginId: String, host: PluginTransportHostV1Impl) {
        transportHosts.remove(pluginId, host)
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
            val admission = trafficAdmissionForPlugin(pluginId)
            val ticket = admission?.invoke()
            if (admission != null && ticket == null) return HostedPluginMainUiResult.Failed("Plugin network is disabled")
            try {
                plugin.invokeMainUiAction(action.actionId, action.args)
            } finally {
                ticket?.close()
            }
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

    internal fun legacyDittoNetworkEnabled(): Boolean? {
        if (dittoSettingsReadFailed) return null
        val state = cache[TrustedCommunityPlugin.PACKAGE] ?: return null
        if (state.lastValidationErrors.isNotEmpty()) return null
        val field = state.schema.fields.firstOrNull {
            it.fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        } ?: return null
        if (field.fieldType != SettingsFieldTypes.TOGGLE ||
            !state.values.containsKey(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)
        ) return null
        return state.values.getBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, false)
    }

    /** True when any connected plugin declares [fieldId] and currently has it set. */
    fun anyPluginHasSettingEnabled(fieldId: String): Boolean =
        cache.entries.any { (pluginId, state) ->
            !forwardingFenced(pluginId, fieldId) && networkSettingEnabled(pluginId, fieldId) &&
                pluginRefs.containsKey(pluginId) &&
                state.schema.fields.any { it.fieldId == fieldId } && state.values.getBoolean(fieldId, false)
        }

    internal fun pluginHasSettingEnabled(pluginId: String, fieldId: String): Boolean {
        val state = cache[pluginId] ?: return false
        return !forwardingFenced(pluginId, fieldId) && pluginRefs.containsKey(pluginId) &&
            state.schema.fields.any { it.fieldId == fieldId && it.fieldType == SettingsFieldTypes.TOGGLE } &&
            state.values.getBoolean(fieldId, false)
    }

    private fun forwardingFenced(pluginId: String, fieldId: String): Boolean =
        pluginId == TrustedCommunityPlugin.PACKAGE &&
            fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT &&
            (dittoForwardingFenced || dittoSettingsReadFailed)

    private fun networkSettingEnabled(pluginId: String, fieldId: String): Boolean =
        pluginId != TrustedCommunityPlugin.PACKAGE ||
            fieldId != PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT ||
            networkEnabledForPlugin(pluginId)

    internal fun refreshOutboundCotAvailability() = updateOutboundCotAvailability()

    private fun dittoReadRevision(pluginId: String): Long? = synchronized(pluginRefs) {
        dittoForwardingRevision.takeIf { pluginId == TrustedCommunityPlugin.PACKAGE }
    }

    private fun fenceFailedDittoRead(pluginId: String, plugin: IWearTakPlugin, revision: Long?) = synchronized(pluginRefs) {
        if (pluginId == TrustedCommunityPlugin.PACKAGE && pluginRefs[pluginId] === plugin &&
            revision == dittoForwardingRevision) {
            dittoSettingsReadFailed = true
            ++dittoForwardingRevision
            updateOutboundCotAvailability()
        }
    }

    internal fun dittoForwardingToken(): Long? = synchronized(pluginRefs) {
        dittoForwardingRevision.takeIf {
            pluginHasSettingEnabled(TrustedCommunityPlugin.PACKAGE, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)
        }
    }

    internal fun isDittoForwardingTokenCurrent(token: Long): Boolean = synchronized(pluginRefs) {
        token == dittoForwardingRevision &&
            pluginHasSettingEnabled(TrustedCommunityPlugin.PACKAGE, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)
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
            .filter { (pluginId, state) ->
                !forwardingFenced(pluginId, fieldId) &&
                    state.schema.fields.any { it.fieldId == fieldId } && state.values.getBoolean(fieldId, false)
            }
            .mapNotNull { (pluginId, _) ->
                val plugin = pluginRefs[pluginId] ?: return@mapNotNull null
                val token = if (pluginId == TrustedCommunityPlugin.PACKAGE &&
                    fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) {
                    dittoForwardingToken() ?: return@mapNotNull null
                } else null
                ActionTarget(pluginId, plugin, token, trafficAdmissionForPlugin(pluginId))
            }
        if (targets.isEmpty()) return

        scope.launch {
            for ((pluginId, plugin, token, trafficAdmission) in targets) {
                if (pluginRefs[pluginId] !== plugin) continue
                if (forwardingFenced(pluginId, fieldId)) continue
                if (token != null && !isDittoForwardingTokenCurrent(token)) continue
                if (fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT &&
                    (pluginRefs[pluginId] !== plugin ||
                        cache[pluginId]?.schema?.fields?.none {
                            it.fieldId == fieldId && it.fieldType == SettingsFieldTypes.TOGGLE
                        } != false ||
                        cache[pluginId]?.values?.getBoolean(fieldId, false) != true)) continue
                val ticket = trafficAdmission?.invoke()
                if (trafficAdmission != null && ticket == null) continue
                try {
                    runCatching { plugin.invokeMainUiAction(actionId, args) }
                        .onFailure { Log.w(TAG, "action $actionId failed for $pluginId: ${it.message}") }
                } finally {
                    ticket?.close()
                }
            }
        }
    }

    fun invokeCotOnPluginsWithSetting(
        fieldId: String,
        actionId: String,
        args: Bundle,
        sourceEnvelope: TakServerCotEnvelope? = null,
        senderUid: String? = null,
        senderCallsign: String? = null,
        excludedPluginId: String? = null,
    ) {
        val targets = cache.entries
            .filter { (pluginId, state) ->
                pluginId != excludedPluginId && !forwardingFenced(pluginId, fieldId) && state.schema.fields.any {
                    it.fieldId == fieldId && it.fieldType == SettingsFieldTypes.TOGGLE
                } && state.values.getBoolean(fieldId, false)
            }
            .mapNotNull { (pluginId, _) ->
                val plugin = pluginRefs[pluginId] ?: return@mapNotNull null
                val token = if (pluginId == TrustedCommunityPlugin.PACKAGE &&
                    fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) {
                    dittoForwardingToken() ?: return@mapNotNull null
                } else null
                ActionTarget(pluginId, plugin, token, trafficAdmissionForPlugin(pluginId))
            }
        if (targets.isEmpty()) return

        scope.launch {
            for ((pluginId, plugin, token, trafficAdmission) in targets) {
                val state = cache[pluginId]
                if (pluginRefs[pluginId] !== plugin || forwardingFenced(pluginId, fieldId) ||
                    (token != null && !isDittoForwardingTokenCurrent(token)) ||
                    state?.schema?.fields?.none {
                        it.fieldId == fieldId && it.fieldType == SettingsFieldTypes.TOGGLE
                    } != false ||
                    state?.values?.getBoolean(fieldId, false) != true
                ) continue

                val transport = transportHosts[pluginId]
                if (transport?.shouldUseTransport() == true) {
                    if (!transport.isTransportRegistered()) continue
                    val xml = args.getString(PluginCotEgress.KEY_COT_XML) ?: continue
                    if (fieldId == PluginCotIngress.SETTING_RECEIVE_INBOUND_COT) {
                        val envelope = sourceEnvelope ?: continue
                        transport.offerInboundCoT(envelope)
                    } else {
                        val uid = senderUid?.takeIf(String::isNotBlank) ?: continue
                        transport.offerCoT(xml, uid, senderCallsign,
                            eligible = token?.let { captured -> { isDittoForwardingTokenCurrent(captured) } })
                    }
                } else {
                    val ticket = trafficAdmission?.invoke()
                    if (trafficAdmission != null && ticket == null) continue
                    try {
                        runCatching { plugin.invokeMainUiAction(actionId, args) }
                            .onFailure { Log.w(TAG, "action $actionId failed for $pluginId: ${it.message}") }
                    } finally {
                        ticket?.close()
                    }
                }
            }
        }
    }

    /** Deliver a best-effort host action to one currently connected plugin. */
    fun invokeActionOnPlugin(pluginId: String, actionId: String, args: Bundle) {
        val plugin = pluginRefs[pluginId] ?: return
        val trafficAdmission = trafficAdmissionForPlugin(pluginId)
        scope.launch {
            if (pluginRefs[pluginId] !== plugin) return@launch
            val ticket = trafficAdmission?.invoke()
            if (trafficAdmission != null && ticket == null) return@launch
            try {
                runCatching { plugin.invokeMainUiAction(actionId, args) }
                    .onFailure { Log.w(TAG, "action $actionId failed for $pluginId: ${it.message}") }
            } finally {
                ticket?.close()
            }
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
        scope.launch {
            combine(
                repo.getEnableOnnxGunshotService,
                repo.getOnnxGunshotTriggerThresholdPercent,
                repo.getEnableOnnxUasService,
                repo.getOnnxUasTriggerThresholdPercent,
            ) { gunshotEnabled, gunshotThreshold, uasEnabled, uasThreshold ->
                LegacyAudioSettings(
                    gunshotEnabled = gunshotEnabled,
                    gunshotThresholdPercent = gunshotThreshold.coerceIn(0, 100),
                    uasEnabled = uasEnabled,
                    uasThresholdPercent = uasThreshold.coerceIn(0, 100),
                )
            }
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Legacy audio settings watcher failed", error)
                    throw error
                }
                .collect {
                    syncMutex.withLock {
                        pushLegacyAudioSettingsLocked(PluginDetectionEventBridge.DART_PACKAGE, it)
                        pushLegacyAudioSettingsLocked(PluginDetectionEventBridge.FALCON_PACKAGE, it)
                    }
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
        runBlocking {
            // A frozen plugin's handshake must not hold the shared settings sync mutex.
            refreshFromPluginLocked(pluginId, plugin)
            pushHostSettingsToPluginLocked(pluginId, plugin)
            if (pluginId == CRM_PLUGIN_PACKAGE) {
                migrateLegacyCrmSettings(pluginId, plugin)
            }
            if (legacyAudioSettingsFields(pluginId) != null) {
                if (cache[pluginId]?.hostManagedSettingsSupported != true) {
                    val errors = cache[pluginId]?.lastValidationErrors.orEmpty()
                    throw IllegalStateException(
                        "Plugin $pluginId has no valid host-managed audio settings schema: $errors"
                    )
                }
                val settings = readLegacyAudioSettings()
                if (!pushLegacyAudioSettingsLocked(pluginId, settings, force = true)) {
                    val result = cache[pluginId]?.lastApplyResult
                    throw IllegalStateException(
                        "Unable to apply WearTAK audio settings to $pluginId: ${result?.errors.orEmpty()}"
                    )
                }
            }
        }
        startHostSettingsWatcher()
    }

    private suspend fun migrateLegacyCrmSettings(pluginId: String, plugin: IWearTakPlugin) {
        val state = cache[pluginId] ?: error("CRM plugin settings unavailable")
        if (!state.settingsSupported && !state.hostManagedSettingsSupported) {
            error("CRM plugin settings unsupported")
        }
        val requiredFields = mapOf(
            CRM_ENABLE to SettingsFieldTypes.TOGGLE,
            CRM_POOR_MAX to SettingsFieldTypes.NUMBER,
            CRM_MODERATE_MAX to SettingsFieldTypes.NUMBER,
            CRM_MIGRATION_COMPLETE to SettingsFieldTypes.TOGGLE,
        )
        check(requiredFields.all { (id, type) ->
            state.schema.fields.any { it.fieldId == id && it.fieldType == type }
        }) { "CRM plugin settings schema missing or mismatched" }
        val marker = state.values.get(CRM_MIGRATION_COMPLETE) as? Boolean
            ?: error("CRM migration marker unavailable")
        if (marker) return
        val (enabled, thresholds) = app.repo.crmLegacyMigrationSettings.first()
        val patch = Bundle().apply {
            putBoolean(CRM_ENABLE, enabled)
            putDouble(CRM_POOR_MAX, thresholds.poorMax.toDouble())
            putDouble(CRM_MODERATE_MAX, thresholds.moderateMax.toDouble())
            putBoolean(CRM_MIGRATION_COMPLETE, true)
        }
        val validation = SettingsApplyValidator.applySettingsWithResult(
            schema = state.schema,
            previous = state.values,
            incoming = patch,
            hostSettings = state.hostSettings,
            applyMode = SettingsApplyMode.PARTIAL_PATCH,
        )
        check(validation.success) { "CRM migration settings schema rejected" }
        check(pluginRefs[pluginId] === plugin) { "CRM plugin connection changed" }
        val result = checkNotNull(plugin.applySettingsWithResult(patch, SettingsApplyMode.PARTIAL_PATCH))
        check(pluginRefs[pluginId] === plugin) { "CRM plugin connection changed" }
        if (result.success) {
            check(result.values.get(CRM_MIGRATION_COMPLETE) == true &&
                result.values.get(CRM_ENABLE) == enabled &&
                result.values.get(CRM_POOR_MAX) == thresholds.poorMax.toDouble() &&
                result.values.get(CRM_MODERATE_MAX) == thresholds.moderateMax.toDouble()
            ) { "CRM migration was not confirmed" }
        } else {
            val current = plugin.settingsValues
            check(pluginRefs[pluginId] === plugin) { "CRM plugin connection changed" }
            check(current?.get(CRM_MIGRATION_COMPLETE) == true) { "CRM migration rejected" }
        }
        refreshValuesFromPluginLocked(pluginId, plugin)
    }

    fun onPluginSettingsSchemaChanged(pluginId: String) {
        val plugin = pluginRefs[pluginId] ?: return
        scope.launch {
            syncMutex.withLock {
                refreshFromPluginLocked(pluginId, plugin)
                if (legacyAudioSettingsFields(pluginId) != null) {
                    pushLegacyAudioSettingsLocked(pluginId, readLegacyAudioSettings())
                }
            }
        }
    }

    fun onPluginSettingsValuesChanged(pluginId: String) {
        val plugin = pluginRefs[pluginId] ?: return
        scope.launch {
            syncMutex.withLock {
                refreshValuesFromPluginLocked(pluginId, plugin)
                if (legacyAudioSettingsFields(pluginId) != null) {
                    pushLegacyAudioSettingsLocked(pluginId, readLegacyAudioSettings())
                }
            }
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
        if (!state.settingsSupported && !state.hostManagedSettingsSupported) {
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
        val forwardingRevision = synchronized(pluginRefs) {
            if (pluginRefs[pluginId] !== plugin || cache[pluginId] !== state) {
                return connectivityRejected("Plugin settings changed")
            }
            if (pluginId == TrustedCommunityPlugin.PACKAGE &&
                incoming.containsKey(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)) {
                dittoForwardingFenced = true
                (++dittoForwardingRevision).also { updateOutboundCotAvailability() }
            } else null
        }
        val remote = if (forwardingRevision != null) {
            runCatching { checkNotNull(plugin.applySettingsWithResult(incoming, applyMode)) }
                .getOrElse {
                    Log.w(TAG, "Ditto forwarding apply unavailable: ${it.javaClass.simpleName}")
                    connectivityRejected("Plugin forwarding apply unavailable")
                }
        } else invokePluginApply(plugin, incoming, applyMode)
        return synchronized(pluginRefs) {
            if (pluginRefs[pluginId] !== plugin || cache[pluginId] !== state ||
                (forwardingRevision != null && forwardingRevision != dittoForwardingRevision)) {
                return@synchronized connectivityRejected("Plugin connection changed")
            }
            if (remote.success && forwardingRevision != null &&
                (!remote.values.containsKey(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) ||
                    remote.values.getBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, false) !=
                    incoming.getBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, false))) {
                return@synchronized connectivityRejected("Plugin forwarding state not confirmed")
            }
            if (remote.success) {
                if (forwardingRevision != null &&
                    incoming.getBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, false)) {
                    dittoForwardingFenced = false
                    dittoSettingsReadFailed = false
                }
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
        val readRevision = dittoReadRevision(pluginId)
        val caps = runCatching { plugin.capabilities }.getOrDefault(PluginCapabilitiesParcel())
        val settingsSupported = caps.hostedSettingsUi || caps.externalSettingsActivity
        val audioSettingsFields = legacyAudioSettingsFields(pluginId)
        val shouldLoadSchema = settingsSupported || audioSettingsFields != null
        var schema = SettingsSchemaParcel()
        var values = Bundle()
        var readSucceeded = false
        var errors = emptyList<com.weartak.plugin.sdk.model.SettingsValidationErrorParcel>()
        if (shouldLoadSchema) {
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
            values = runCatching { plugin.settingsValues }.onSuccess { readSucceeded = true }.getOrElse {
                fenceFailedDittoRead(pluginId, plugin, readRevision)
                Log.w(TAG, "Settings values unavailable for $pluginId: ${it.javaClass.simpleName}")
                Bundle()
            }
        }
        val hostManagedSettingsSupported = audioSettingsFields?.expectedFieldTypes?.all { (fieldId, fieldType) ->
            schema.fields.any { it.fieldId == fieldId && it.fieldType == fieldType }
        } == true
        if (audioSettingsFields != null && !hostManagedSettingsSupported) {
            Log.e(TAG, "host-managed audio settings schema missing or mismatched pluginId=$pluginId")
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
                hostManagedSettingsSupported = hostManagedSettingsSupported,
                lastSyncedAtMillis = System.currentTimeMillis(),
            ),
            readSucceeded = readSucceeded,
            readRevision = readRevision,
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
        val readRevision = dittoReadRevision(pluginId)
        val prior = cache[pluginId] ?: return
        if (!prior.settingsSupported && !prior.hostManagedSettingsSupported) return
        var readSucceeded = false
        val values = runCatching { plugin.settingsValues }.onSuccess { readSucceeded = true }.getOrElse {
            fenceFailedDittoRead(pluginId, plugin, readRevision)
            Log.w(TAG, "Settings values unavailable for $pluginId: ${it.javaClass.simpleName}")
            Bundle()
        }
        if (pluginRefs[pluginId] !== plugin) return
        updateConnectionCache(
            pluginId,
            plugin,
            prior.copy(
                values = values,
                lastSyncedAtMillis = System.currentTimeMillis(),
            ),
            readSucceeded = readSucceeded,
            readRevision = readRevision,
        )
        PluginDiagnostics.recordSettingsCache(pluginId, cache[pluginId])
    }

    private suspend fun readLegacyAudioSettings(): LegacyAudioSettings {
        val repo = app.repo
        return try {
            LegacyAudioSettings(
                gunshotEnabled = repo.getEnableOnnxGunshotService.first(),
                gunshotThresholdPercent = repo.getOnnxGunshotTriggerThresholdPercent.first().coerceIn(0, 100),
                uasEnabled = repo.getEnableOnnxUasService.first(),
                uasThresholdPercent = repo.getOnnxUasTriggerThresholdPercent.first().coerceIn(0, 100),
            )
        } catch (error: java.io.IOException) {
            Log.e(TAG, "Unable to read legacy audio-detection settings", error)
            throw IllegalStateException("Unable to read legacy audio-detection settings", error)
        }
    }

    private fun pushLegacyAudioSettingsLocked(
        pluginId: String,
        settings: LegacyAudioSettings,
        force: Boolean = false,
    ): Boolean {
        val fields = legacyAudioSettingsFields(pluginId) ?: return true
        val plugin = pluginRefs[pluginId] ?: return true
        val state = cache[pluginId] ?: return true
        if (!state.hostManagedSettingsSupported) {
            Log.e(TAG, "legacy audio settings unsupported by pluginId=$pluginId")
            if (!force) onLegacyAudioSettingsRejected(pluginId, plugin)
            return false
        }
        val (enabled, thresholdPercent) = when (pluginId) {
            PluginDetectionEventBridge.DART_PACKAGE ->
                settings.gunshotEnabled to settings.gunshotThresholdPercent
            PluginDetectionEventBridge.FALCON_PACKAGE ->
                settings.uasEnabled to settings.uasThresholdPercent
            else -> return true
        }
        if (!force &&
            state.values.containsKey(fields.enabledFieldId) &&
            state.values.containsKey(fields.thresholdFieldId) &&
            state.values.getBoolean(fields.enabledFieldId) == enabled &&
            state.values.getDouble(fields.thresholdFieldId) == thresholdPercent.toDouble()
        ) {
            return true
        }
        if (pluginRefs[pluginId] !== plugin) return false
        val values = Bundle().apply {
            putBoolean(fields.enabledFieldId, enabled)
            putDouble(fields.thresholdFieldId, thresholdPercent.toDouble())
        }
        var result = applyPluginSettings(pluginId, values)
        var retries = 0
        while (!result.success && retries < 2 && pluginRefs[pluginId] === plugin) {
            val transientChange = result.errors.any { error ->
                error.code == "connectivity.unavailable" &&
                    (error.message == "Plugin connection changed" || error.message == "Plugin settings changed")
            }
            if (!transientChange) break
            Log.w(TAG, "Retrying audio settings after concurrent cache update pluginId=$pluginId")
            result = applyPluginSettings(pluginId, values)
            retries++
        }
        if (!result.success) {
            Log.e(TAG, "legacy audio settings rejected by pluginId=$pluginId errors=${result.errors}")
            if (!force) onLegacyAudioSettingsRejected(pluginId, plugin)
        }
        return result.success
    }

    private data class LegacyAudioSettings(
        val gunshotEnabled: Boolean,
        val gunshotThresholdPercent: Int,
        val uasEnabled: Boolean,
        val uasThresholdPercent: Int,
    )

    private data class LegacyAudioSettingsFields(
        val enabledFieldId: String,
        val thresholdFieldId: String,
        val expectedFieldTypes: Map<String, String>,
    )

    private fun legacyAudioSettingsFields(pluginId: String): LegacyAudioSettingsFields? =
        when (pluginId) {
            PluginDetectionEventBridge.DART_PACKAGE -> LegacyAudioSettingsFields(
                enabledFieldId = "enable_onnx_gunshot_service",
                thresholdFieldId = "onnx_gunshot_trigger_threshold_percent",
                expectedFieldTypes = mapOf(
                    "enable_onnx_gunshot_service" to SettingsFieldTypes.TOGGLE,
                    "onnx_gunshot_trigger_threshold_percent" to SettingsFieldTypes.NUMBER,
                ),
            )
            PluginDetectionEventBridge.FALCON_PACKAGE -> LegacyAudioSettingsFields(
                enabledFieldId = "enable_onnx_uas_service",
                thresholdFieldId = "onnx_uas_trigger_threshold_percent",
                expectedFieldTypes = mapOf(
                    "enable_onnx_uas_service" to SettingsFieldTypes.TOGGLE,
                    "onnx_uas_trigger_threshold_percent" to SettingsFieldTypes.NUMBER,
                ),
            )
            else -> null
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
        if (pluginId == CRM_PLUGIN_PACKAGE) {
            crmSettingsState.value = if (state.settingsSupported) crmPluginSettings(state.values) else null
        }
        updateOutboundCotAvailability()
    }

    private fun updateConnectionCache(
        pluginId: String,
        plugin: IWearTakPlugin,
        state: PluginSettingsState,
        readSucceeded: Boolean = false,
        readRevision: Long? = null,
    ) =
        synchronized(pluginRefs) {
            if (pluginRefs[pluginId] === plugin &&
                (readRevision == null || readRevision == dittoForwardingRevision)) {
                if (readSucceeded && pluginId == TrustedCommunityPlugin.PACKAGE) dittoSettingsReadFailed = false
                updateCache(pluginId, state)
            }
        }

    private fun updateOutboundCotAvailability() = synchronized(outboundCotReadyRoutesState) {
        connectivityTogglesState.value = cache.entries.mapNotNull { (id, state) ->
            val plugin = pluginRefs[id] ?: return@mapNotNull null
            if (!state.settingsSupported || state.schema.fields.none {
                it.fieldId == PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT &&
                    it.fieldType == SettingsFieldTypes.TOGGLE
            }) return@mapNotNull null
            id to ConnectivityToggle(
                !forwardingFenced(id, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) &&
                    state.values.getBoolean(PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, false), plugin)
        }.toMap()
        val ready = cache.entries.filter { (pluginId, state) ->
            !forwardingFenced(pluginId, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) &&
                networkSettingEnabled(pluginId, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT) &&
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

        private const val CRM_PLUGIN_PACKAGE = "com.weartak.plugin.crm"
        private const val CRM_ENABLE = "enable_crm"
        private const val CRM_POOR_MAX = "crm_poor_max"
        private const val CRM_MODERATE_MAX = "crm_moderate_max"
        private const val CRM_MIGRATION_COMPLETE = "crm_legacy_migration_complete"
        private const val PLUGIN_SOURCE_PREFIX = "plugin:"
        private const val TAG = "CEP-PluginSettings"
    }
}
