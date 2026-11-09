package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aitech.weartak_civ.WearTakApplication
import androidx.lifecycle.viewModelScope
import android.widget.Toast
import com.aitech.weartak_civ.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PluginViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: PluginRepository =
        (application as WearTakApplication).pluginRepository

    val plugins = repository.plugins
    val selectedPlugin = repository.selectedPlugin
    val operationOutcome = repository.operationOutcome
    private val coordinator = (application as WearTakApplication).pluginManager.settingsCoordinator
    val connectivityToggles = coordinator.connectivityToggles
    internal val connectionStatuses = coordinator.connectionStatuses
    internal val mainUiChangeRevisions = coordinator.mainUiChangeRevisions
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
