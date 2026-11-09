package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.cot.TakServerCotEnvelope

internal object PluginCotIngress {
    const val SETTING_RECEIVE_INBOUND_COT = "receiveInboundCot"
    const val ACTION_COT_INBOUND = "cot.inbound"

    fun offer(envelope: TakServerCotEnvelope, coordinator: PluginSettingsCoordinator): Boolean {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.containsSensitiveXml(envelope.rawXml)) return true
        val args = Bundle().apply {
            putString("xml", envelope.rawXml)
            putString("source", envelope.sourceServerId)
            putLong("receivedAt", envelope.receivedAt)
        }
        // Plugin-injected messages are already received by that plugin. Do not feed them back
        // into plugin bearers, where reinjection could create a forwarding loop.
        if (!envelope.sourceServerId.startsWith("plugin:")) {
            coordinator.invokeCotOnPluginsWithSetting(
                fieldId = SETTING_RECEIVE_INBOUND_COT,
                actionId = ACTION_COT_INBOUND,
                args = args,
                sourceEnvelope = envelope,
            )
        }
        return PluginConfigCotBridge.isConfigCot(envelope.rawXml) &&
            PluginConfigCotBridge.handle(envelope.rawXml, coordinator)
    }
}
