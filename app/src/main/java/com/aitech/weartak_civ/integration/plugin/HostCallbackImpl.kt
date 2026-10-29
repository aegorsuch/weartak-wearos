package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.model.CotReadyEventParcel
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SensorEventParcel
import com.weartak.plugin.sdk.model.SharedBufferParcel

class HostCallbackImpl(
    private val ingress: PluginIngressPort,
    private val settingsCoordinator: PluginSettingsCoordinator,
    private val pluginId: String,
    private val pluginUid: Int,
) : IWearTakHostCallback.Stub() {

    override fun onSensorEvent(event: SensorEventParcel?) {
        if (event == null) return
        runCatching { ingress.onSensorEventFromPlugin(pluginId, pluginUid, event) }
            .onFailure { Log.e(TAG, "onSensorEvent", it) }
    }

    override fun onSensorBatchEvent(events: MutableList<SensorEventParcel>?) {
        if (events.isNullOrEmpty()) return
        runCatching { ingress.onSensorBatchFromPlugin(pluginId, pluginUid, events.toList()) }
            .onFailure { Log.e(TAG, "onSensorBatchEvent", it) }
    }

    override fun onCotReadyEvent(event: CotReadyEventParcel?) {
        PluginDiagnostics.recordCotReady(pluginId, event)
    }

    override fun onPluginSettingsSchemaChanged() {
        PluginDiagnostics.recordSettingsSchemaChanged(pluginId)
        settingsCoordinator.onPluginSettingsSchemaChanged(pluginId)
    }

    override fun onPluginSettingsValuesChanged() {
        PluginDiagnostics.recordSettingsValuesChanged(pluginId)
        settingsCoordinator.onPluginSettingsValuesChanged(pluginId)
    }

    override fun onPluginMainUiSchemaChanged() {
        PluginDiagnostics.recordMainUiSchemaChanged(pluginId)
        settingsCoordinator.notifyMainUiChanged(pluginId)
    }

    override fun onPluginMainUiStateChanged() {
        PluginDiagnostics.recordMainUiStateChanged(pluginId)
        settingsCoordinator.notifyMainUiChanged(pluginId)
    }

    override fun onPluginStatusChanged(status: PluginStatusParcel?) {
        PluginDiagnostics.recordPluginStatus(pluginId, status)
    }

    override fun onPluginError(error: PluginErrorParcel?) {
        PluginDiagnostics.recordPluginError(pluginId, error)
    }

    override fun onSharedBufferAvailable(buffer: SharedBufferParcel?) {
        PluginDiagnostics.recordSharedBuffer(pluginId, buffer)
    }

    companion object {
        private const val TAG = "CEP-HostCallback"
    }
}
