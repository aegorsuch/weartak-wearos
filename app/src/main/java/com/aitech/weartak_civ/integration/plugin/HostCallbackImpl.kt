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
    private val isActive: () -> Boolean = { true },
) : IWearTakHostCallback.Stub() {
    @Volatile private var closed = false
    fun close() { closed = true }
    private fun active(): Boolean = !closed && isActive()

    override fun onSensorEvent(event: SensorEventParcel?) {
        if (!active() || event == null) return
        runCatching { ingress.onSensorEventFromPlugin(pluginId, pluginUid, event) }
            .onFailure { Log.e(TAG, "onSensorEvent", it) }
    }

    override fun onSensorBatchEvent(events: MutableList<SensorEventParcel>?) {
        if (!active() || events.isNullOrEmpty()) return
        runCatching { ingress.onSensorBatchFromPlugin(pluginId, pluginUid, events.toList()) }
            .onFailure { Log.e(TAG, "onSensorBatchEvent", it) }
    }

    override fun onCotReadyEvent(event: CotReadyEventParcel?) {
        if (!active()) return
        PluginDiagnostics.recordCotReady(pluginId, event)
    }

    override fun onPluginSettingsSchemaChanged() {
        if (!active()) return
        PluginDiagnostics.recordSettingsSchemaChanged(pluginId)
        settingsCoordinator.onPluginSettingsSchemaChanged(pluginId)
    }

    override fun onPluginSettingsValuesChanged() {
        if (!active()) return
        PluginDiagnostics.recordSettingsValuesChanged(pluginId)
        settingsCoordinator.onPluginSettingsValuesChanged(pluginId)
    }

    override fun onPluginMainUiSchemaChanged() {
        if (!active()) return
        PluginDiagnostics.recordMainUiSchemaChanged(pluginId)
        settingsCoordinator.notifyMainUiChanged(pluginId)
    }

    override fun onPluginMainUiStateChanged() {
        if (!active()) return
        PluginDiagnostics.recordMainUiStateChanged(pluginId)
        settingsCoordinator.notifyMainUiChanged(pluginId)
    }

    override fun onPluginStatusChanged(status: PluginStatusParcel?) {
        if (!active()) return
        PluginDiagnostics.recordPluginStatus(pluginId, status)
    }

    override fun onPluginError(error: PluginErrorParcel?) {
        if (!active()) return
        PluginDiagnostics.recordPluginError(pluginId, error)
    }

    override fun onSharedBufferAvailable(buffer: SharedBufferParcel?) {
        if (!active()) return
        PluginDiagnostics.recordSharedBuffer(pluginId, buffer)
    }

    companion object {
        private const val TAG = "CEP-HostCallback"
    }
}
