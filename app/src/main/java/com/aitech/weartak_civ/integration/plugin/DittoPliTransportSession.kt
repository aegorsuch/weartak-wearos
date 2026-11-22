package com.aitech.weartak_civ.integration.plugin

import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportDeliveryStage
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.TransportValidator
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel

/** Host-only accounting for authenticated Ditto channels, not a delivery receipt. */
internal class DittoPliTransportSession(
    private val sessionId: String,
    private val peerUid: Int,
    private val capabilities: TransportCapabilitiesParcel,
    private val clock: () -> Long,
) {
    private data class Pending(
        val deadline: Long,
        var sequence: Long = 0,
        var stage: Int = 0,
        var terminal: Boolean = false,
    )

    private val messages = mutableMapOf<String, Pending>()
    private var active = true

    init {
        require(TransportValidator.id(sessionId) && peerUid >= 0)
        require(TransportValidator.capabilities(capabilities).code == TransportResultCode.ACCEPTED)
        require(capabilities.channelIds == listOf(CHANNEL) || capabilities.channelIds == listOf(GENERAL_CHANNEL))
        require(capabilities.outboundPayloadKinds == listOf(TransportPayloadKind.COT_XML_UTF8))
        require(if (capabilities.channelIds.single() == CHANNEL) capabilities.inboundPayloadKinds.isEmpty()
            else capabilities.inboundPayloadKinds == listOf(TransportPayloadKind.COT_XML_UTF8))
        require(!capabilities.deviceReceipts && !capabilities.recipientReceipts)
    }

    @Synchronized
    fun admitOutbound(request: TransportSendRequestParcel, uid: Int): TransportResultParcel {
        fence(request.sessionId, uid)?.let { return it }
        if (!capabilities.runtimeAvailable) return error(TransportResultCode.UNAVAILABLE, "Transport unavailable")
        if (request.channelId != capabilities.channelIds.single()) return error(TransportResultCode.UNSUPPORTED, "Unknown channel")
        val valid = TransportValidator.message(request.message, capabilities, outbound = true)
        if (valid.code != TransportResultCode.ACCEPTED) return valid
        val now = clock()
        if (request.deadlineEpochMillis <= now) return error(TransportResultCode.EXPIRED, "Deadline expired")
        if (request.deadlineEpochMillis - now > TransportContract.MAX_DEADLINE_MS) {
            return error(TransportResultCode.INVALID_REQUEST, "Deadline too distant")
        }
        if (request.message.messageId in messages) return error(TransportResultCode.DUPLICATE, "Message already observed")
        if (messages.size >= TransportContract.MAX_SESSION_MESSAGES ||
            messages.values.count { !it.terminal } >= capabilities.maxPendingMessages
        ) return error(TransportResultCode.LIMIT_EXCEEDED, "Session capacity reached")
        messages[request.message.messageId] = Pending(request.deadlineEpochMillis)
        return ok()
    }

    @Synchronized
    fun delivery(session: String, update: TransportDeliveryParcel, uid: Int): TransportResultParcel {
        fence(session, uid)?.let { return it }
        val valid = TransportValidator.delivery(update)
        if (valid.code != TransportResultCode.ACCEPTED) return valid
        val record = messages[update.messageId] ?: return error(TransportResultCode.UNKNOWN_MESSAGE, "Unknown outbound message")
        if (update.channelId != capabilities.channelIds.single()) return error(TransportResultCode.INVALID_REQUEST, "Channel mismatch")
        if (record.terminal || update.sequence <= record.sequence || update.stage < record.stage) {
            return error(TransportResultCode.STALE_UPDATE, "Terminal or stale delivery update")
        }
        if (clock() >= record.deadline) return error(TransportResultCode.EXPIRED, "Deadline expired")
        if (update.stage == TransportDeliveryStage.CHANNEL_DELIVERED ||
            update.stage == TransportDeliveryStage.RECIPIENT_DELIVERED
        ) return error(TransportResultCode.UNSUPPORTED, "Receipt scope not negotiated")
        record.sequence = update.sequence
        record.stage = update.stage
        record.terminal = update.stage == TransportDeliveryStage.CHANNEL_SUBMITTED ||
            update.stage == TransportDeliveryStage.FAILED || update.stage == TransportDeliveryStage.CANCELLED
        return ok()
    }

    @Synchronized
    fun expire(): List<String> = messages.filterValues { !it.terminal && clock() >= it.deadline }
        .map { (id, record) ->
            record.terminal = true
            record.stage = TransportDeliveryStage.FAILED
            id
        }

    @Synchronized
    fun close(): List<String> {
        active = false
        return messages.filterValues { !it.terminal }.keys.toList().also { messages.clear() }
    }

    private fun fence(session: String, uid: Int): TransportResultParcel? = when {
        uid != peerUid -> error(TransportResultCode.UNAUTHORIZED, "Caller UID mismatch")
        !active || session != sessionId -> error(TransportResultCode.INACTIVE_SESSION, "Inactive transport session")
        else -> null
    }

    private fun ok() = TransportResultParcel(TransportResultCode.ACCEPTED, sessionId)
    private fun error(code: Int, detail: String) = TransportResultParcel(code, detail = detail)

    companion object {
        const val CHANNEL = "ditto-pli-v1"
        const val GENERAL_CHANNEL = "ditto-v1"
    }
}
