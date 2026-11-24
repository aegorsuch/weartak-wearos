package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CotCompanionPayload {
    data class Pli(val snapshot: DataRepository.PliSnapshot) : CotCompanionPayload
    data class Chat(val snapshot: DataRepository.ChatEventSnapshot) : CotCompanionPayload
    data class Marker(val snapshot: DataRepository.MarkerSnapshot) : CotCompanionPayload
    data class OwnedPointDeletion(val uid: String) : CotCompanionPayload
    data class Emergency(val snapshot: DataRepository.EmergencyEventSnapshot) : CotCompanionPayload
}

data class CotOutboundMessage(
    val xml: String,
    val kind: CotMessageKind,
    val companionPayload: CotCompanionPayload,
    /** Associates the no-fix GeoChat notification with its emergency delivery receipt. */
    val emergencyDelivery: DataRepository.EmergencyEventSnapshot? = null,
    val dittoCompletion: ((Boolean) -> Unit)? = null,
    val onDittoOffered: (() -> Unit)? = null,
    val excludeDittoPlugin: Boolean = false,
    val alertActivationId: String? = null,
    val positionAttemptId: String? = null,
)

class CotEndpointDispatcher(
    private val sendTak: (xml: String, addToQueue: Boolean) -> Unit,
    private val sendSitx: (xml: String) -> Unit,
    private val sendDitto: (collection: String, xml: String) -> Unit,
    private val sendMulticast: (xml: String) -> Unit,
    private val sendPliMulticast: (xml: String) -> Unit,
    private val sendCompanion: suspend (payload: CotCompanionPayload) -> Unit,
    private val sendPlugin: (xml: String) -> Unit = {},
    private val sendPluginWithoutDitto: (xml: String) -> Unit = sendPlugin,
    private val sendTrackedDitto: ((collection: String, xml: String, completion: (Boolean) -> Unit) -> Unit)? = null,
    private val sendTrackedTakPli: ((message: CotOutboundMessage) -> Unit)? = null,
    private val sendTrackedMulticastPli: ((message: CotOutboundMessage) -> Unit)? = null,
    private val sendTrackedSitxPli: ((message: CotOutboundMessage) -> Unit)? = null,
    private val sendTrackedPluginPli: ((message: CotOutboundMessage) -> Unit)? = null,
) {
    private val emergencyMutex = Mutex()
    /**
     * An emergency is delivered once per uid, state and route. Newer fixes do not resend it:
     * receivers (TAK Server bridges, ATAK) treat every copy as a new alert notification.
     * The no-fix GeoChat notice and the positional alert are tracked separately so the
     * positional alert still goes out once GPS becomes available.
     */
    private data class DeliveryKey(
        val uid: String, val state: DataRepository.AlertState, val route: CotRoute, val positional: Boolean,
        val activationId: String?,
    )
    private val emergencyDeliveries = object : LinkedHashMap<DeliveryKey, Unit>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DeliveryKey, Unit>?) = size > 1024
    }

    private suspend fun deliver(message: CotOutboundMessage, route: CotRoute, send: suspend () -> Boolean): Boolean {
        val snapshot = message.emergencyDelivery
            ?: (message.companionPayload as? CotCompanionPayload.Emergency)?.snapshot
            ?: return send()
        return emergencyMutex.withLock {
            val positional = message.kind == CotMessageKind.EMERGENCY && snapshot.location != null
            val key = DeliveryKey(snapshot.uid, snapshot.alertState, route, positional, message.alertActivationId)
            if (key in emergencyDeliveries) return@withLock true
            send().also { accepted -> if (accepted) emergencyDeliveries[key] = Unit }
        }
    }

    suspend fun dispatchGateway(message: CotOutboundMessage, send: suspend () -> Boolean): Boolean =
        deliver(message, CotRoute.COMPANION, send)

    suspend fun dispatchFinalSelfPliExpiry(
        message: CotOutboundMessage,
        availability: CotEndpointAvailability,
        sendTakExpiry: (String) -> Boolean,
        sendMulticastExpiry: (String) -> Boolean,
    ): Boolean {
        require(message.kind == CotMessageKind.PLI) { "Final self-expiry must be a PLI" }
        var accepted = false
        if (availability.tak) {
            accepted = deliver(message, CotRoute.TAK) { sendTakExpiry(message.xml) } || accepted
        }
        if (availability.multicast) {
            accepted = deliver(message, CotRoute.MULTICAST) {
                sendMulticastExpiry(message.xml)
            } || accepted
        }
        return accepted
    }

    suspend fun dispatch(
        message: CotOutboundMessage,
        availability: CotEndpointAvailability,
        readyPliOnly: Boolean = false
    ): Boolean {
        val routes = CotDispatchPolicy.routesFor(message.kind, availability).filter { route ->
            if (!readyPliOnly || message.kind != CotMessageKind.PLI) true else when (route) {
                CotRoute.TAK -> availability.tak
                CotRoute.SITX -> availability.sitx
                CotRoute.MULTICAST -> availability.multicast
                CotRoute.COMPANION -> availability.companion
                CotRoute.PLUGIN -> availability.plugin
                CotRoute.DITTO -> false
            }
        }
        routes.forEach { route ->
            deliver(message, route) {
                when (route) {
                    CotRoute.TAK -> if (message.kind == CotMessageKind.PLI && sendTrackedTakPli != null)
                        sendTrackedTakPli.invoke(message) else sendTak(message.xml, message.kind != CotMessageKind.PLI)
                    CotRoute.SITX -> if (message.kind == CotMessageKind.PLI && sendTrackedSitxPli != null)
                        sendTrackedSitxPli.invoke(message) else sendSitx(message.xml)
                    CotRoute.DITTO -> {
                        message.onDittoOffered?.invoke()
                        val collection = if (message.kind == CotMessageKind.CHAT) "chat" else "mapitem"
                        val completion = message.dittoCompletion
                        if (completion != null && sendTrackedDitto != null) {
                            sendTrackedDitto.invoke(collection, message.xml, completion)
                        } else {
                            sendDitto(collection, message.xml)
                        }
                    }
                    CotRoute.MULTICAST -> if (message.kind == CotMessageKind.PLI) {
                        if (sendTrackedMulticastPli != null) sendTrackedMulticastPli.invoke(message)
                        else sendPliMulticast(message.xml)
                    } else {
                        sendMulticast(message.xml)
                    }
                    CotRoute.COMPANION -> sendCompanion(message.companionPayload)
                    CotRoute.PLUGIN -> if (message.kind == CotMessageKind.PLI && sendTrackedPluginPli != null)
                        sendTrackedPluginPli.invoke(message)
                    else if (message.excludeDittoPlugin) sendPluginWithoutDitto(message.xml)
                    else sendPlugin(message.xml)
                }
                true
            }
        }
        return routes.isNotEmpty()
    }
}
