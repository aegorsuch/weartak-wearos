package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.presentation.MainActivity

/**
 * Offers CoT this watch is sending to plugins that asked for it.
 *
 * A plugin is another endpoint alongside TAK servers, SITX, companion BLE and multicast — with one
 * difference that shapes where this is called from: a plugin's transport may be the only one
 * available. A Meshtastic plugin on LoRa still works with no wifi, no cell, and no TAK server, which
 * is exactly the situation where the watch most needs to be heard.
 *
 * So this is invoked from [com.aitech.weartak_civ.service.CotDispatchService.dispatchOutgoingCot],
 * the common fan-out point, rather than from the transports beneath it. Hooking `sendDataTAKServers`
 * or `MulticastPublisher.send` would look equivalent but is not: both are conditional on their own
 * transport being up, so forwarding would silently stop precisely when the mesh matters most.
 *
 * Delivery is opt-in and always asynchronous, so a wedged plugin cannot stall the host's dispatch.
 */
object PluginCotEgress {

    /**
     * Settings field a plugin declares to receive outbound CoT. Declaring it advertises the
     * capability; its boolean value is the switch the wearer controls.
     */
    const val SETTING_FORWARD_OUTBOUND_COT = "forwardOutboundCot"

    /** Action id delivered via `invokeMainUiAction`. */
    const val ACTION_COT_OUTBOUND = "cot.outbound"

    /** Bundle key carrying the raw CoT XML. */
    const val KEY_COT_XML = "xml"

    /**
     * Whether any bound plugin has opted in, so [com.aitech.weartak_civ.cot.CotDispatchPolicy] can
     * include `CotRoute.PLUGIN` only when there is somewhere for it to go.
     */
    fun hasSubscribers(app: WearTakApplication): Boolean =
        app.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(SETTING_FORWARD_OUTBOUND_COT)

    /**
     * Offer [cotXml] to interested plugins. Safe to call from any thread, cheap when nobody has
     * opted in, and never throws into the caller's dispatch path.
     *
     * There is deliberately no loop guard. CoT a plugin *injects* is ingested into received-state,
     * which is separate from the local state driving outbound dispatch, so an injected event cannot
     * come back around. If ingest is ever made to feed egress, this needs an origin tag to break the
     * cycle.
     */
    fun offer(app: WearTakApplication, cotXml: String?) {
        if (cotXml.isNullOrBlank()) return
        runCatching {
            val args = Bundle().apply { putString(KEY_COT_XML, cotXml) }
            app.pluginManager.settingsCoordinator.invokeCotOnPluginsWithSetting(
                fieldId = SETTING_FORWARD_OUTBOUND_COT,
                actionId = ACTION_COT_OUTBOUND,
                args = args,
                senderUid = MainActivity.device_uid,
                senderCallsign = MainActivity.callsign,
            )
        }.onFailure { Log.w(TAG, "offer failed", it) }
    }

    private const val TAG = "CEP-PluginCotEgress"
}
