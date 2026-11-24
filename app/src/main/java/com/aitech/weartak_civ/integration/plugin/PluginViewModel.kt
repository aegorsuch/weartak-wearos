package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aitech.weartak_civ.WearTakApplication
import androidx.lifecycle.viewModelScope
import android.widget.Toast
import com.aitech.weartak_civ.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PluginViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: PluginRepository =
        (application as WearTakApplication).pluginRepository

    val plugins = repository.plugins
    val selectedPlugin = repository.selectedPlugin
    val operationOutcome = repository.operationOutcome
    private val coordinator = (application as WearTakApplication).pluginManager.settingsCoordinator
    private val pluginManager = (application as WearTakApplication).pluginManager
    val connectivityToggles = coordinator.connectivityToggles
    internal val dittoNetworkEnabled = pluginManager.dittoNetworkEnabled
    internal val dittoNetworkPolicyLoaded = pluginManager.dittoNetworkPolicyLoaded
    internal val dittoNetworkPolicyMigrationFailed = pluginManager.dittoNetworkPolicyMigrationFailed
    internal val connectionStatuses = coordinator.connectionStatuses
    internal val mainUiChangeRevisions = coordinator.mainUiChangeRevisions
    internal val crmSettings = coordinator.crmSettings
    private val crmOperationPendingState = MutableStateFlow(false)
    internal val crmOperationPending = crmOperationPendingState.asStateFlow()
    private val hostedMainUiState = MutableStateFlow<Map<String, HostedPluginMainUiResult>>(emptyMap())
    internal val hostedMainUi = hostedMainUiState.asStateFlow()

    fun setConnectivityEnabled(id: String, expected: PluginSettingsCoordinator.ConnectivityToggle, enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                coordinator.setConnectivityEnabled(id, expected, enabled)
            }
            if (!result.success) Toast.makeText(getApplication(), R.string.network_toggle_failed, Toast.LENGTH_LONG).show()
        }
    }

    internal fun setDittoNetworkEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val succeeded = withContext(Dispatchers.IO) {
                pluginManager.setDittoNetworkEnabled(enabled)
            }

            internal fun setCrmEnabled(pluginId: String, loadedByHost: Boolean, enabled: Boolean) {
                if (crmOperationPendingState.value) return
                crmOperationPendingState.value = true
                viewModelScope.launch {
                    try {
                        if (!loadedByHost) repository.loadPlugin(pluginId)
                        val settings = withTimeoutOrNull(10_000) { crmSettings.filterNotNull().first() }
                        val result = if (settings != null) withContext(Dispatchers.IO) {
                            coordinator.setCrmEnabled(enabled)
                        } else null
                        if (result == null || !result.success ||
                            !result.values.containsKey("enable_crm") ||
                            result.values.getBoolean("enable_crm") != enabled
                        ) {
                            Toast.makeText(getApplication(), R.string.crm_settings_change_failed, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        crmOperationPendingState.value = false
                    }
                }
            }

            internal fun openCrmThresholds() {
                if (crmOperationPendingState.value) return
                crmOperationPendingState.value = true
                viewModelScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            coordinator.invokeHostedMainUiAction(CRM_PLUGIN_PACKAGE, "crm-thresholds")
                        }
                        if (result !is HostedPluginMainUiResult.Ready) {
                            Toast.makeText(getApplication(), R.string.crm_settings_open_failed, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        crmOperationPendingState.value = false
                    }
                }
            }
            if (!succeeded) {
                Toast.makeText(getApplication(), R.string.network_toggle_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun refresh() = repository.refresh()
    fun refreshBindings() = repository.refreshBindings()
    internal suspend fun supportsHostedMainUi(pluginId: String): Boolean =
        withContext(Dispatchers.IO) { coordinator.supportsHostedMainUi(pluginId) }

    internal fun refreshHostedMainUi(pluginId: String) {
        val connection = coordinator.connectionToken(pluginId)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { coordinator.getHostedMainUi(pluginId) }
            if (coordinator.connectionToken(pluginId) === connection) {
                hostedMainUiState.value = hostedMainUiState.value + (pluginId to result)
            }
        }
    }

    internal fun invokeHostedMainUiAction(pluginId: String, cardId: String) {
        val connection = coordinator.connectionToken(pluginId)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                coordinator.invokeHostedMainUiAction(pluginId, cardId)
            }
            if (coordinator.connectionToken(pluginId) === connection) {
                hostedMainUiState.value = hostedMainUiState.value + (pluginId to result)
            }
        }
    }

    fun addPlugin(pluginId: String) = repository.addPlugin(pluginId)
    fun select(pluginId: String?) = repository.select(pluginId)
    fun loadPlugin(pluginId: String) {
        hostedMainUiState.value = hostedMainUiState.value - pluginId
        repository.loadPlugin(pluginId)
    }
    fun unloadPlugin(pluginId: String) {
        hostedMainUiState.value = hostedMainUiState.value - pluginId
        repository.unloadPlugin(pluginId)
    }
    fun removePlugin(pluginId: String) {
        hostedMainUiState.value = hostedMainUiState.value - pluginId
        repository.removePlugin(pluginId)
    }
    fun uninstallPlugin(pluginId: String): PluginUninstallRequest = repository.uninstallPlugin(pluginId)
}
