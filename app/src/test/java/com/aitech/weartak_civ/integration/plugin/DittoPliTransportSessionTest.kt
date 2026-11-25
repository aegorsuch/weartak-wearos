package com.aitech.weartak_civ.integration.plugin

import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportDeliveryStage
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DittoPliTransportSessionTest {
    private val caps = TransportCapabilitiesParcel(
        version = TransportContract.VERSION,
        outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
        inboundPayloadKinds = emptyList(),
        channelIds = listOf(DittoPliTransportSession.CHANNEL),
        maxPayloadBytes = TransportContract.MAX_PAYLOAD_BYTES,
        maxPendingMessages = 1,
        runtimeAvailable = true,
        deviceReceipts = false,
        recipientReceipts = false,
    )
    private var now = 100L
    private fun session() = DittoPliTransportSession("session", 42, caps) { now }
    private fun request(id: String) = TransportSendRequestParcel(
        sessionId = "session",
        channelId = DittoPliTransportSession.CHANNEL,
        message = TransportMessageParcel(id, TransportPayloadKind.COT_XML_UTF8, "<event/>".toByteArray(), "watch"),
        deadlineEpochMillis = 200L,
    )
    private fun submitted(id: String) = TransportDeliveryParcel(
        id, DittoPliTransportSession.CHANNEL, 1, TransportDeliveryStage.CHANNEL_SUBMITTED,
    )

    @Test fun submissionFreesPendingButRetainsEveryId() {
        val session = session()
        repeat(TransportContract.MAX_SESSION_MESSAGES) { index ->
            val id = "message-$index"
            assertEquals(TransportResultCode.ACCEPTED, session.admitOutbound(request(id), 42).code)
            assertEquals(TransportResultCode.ACCEPTED, session.delivery("session", submitted(id), 42).code)
            assertEquals(TransportResultCode.STALE_UPDATE, session.delivery("session", submitted(id).copy(sequence = 2), 42).code)
            assertEquals(TransportResultCode.DUPLICATE, session.admitOutbound(request(id), 42).code)
        }
        assertEquals(TransportResultCode.LIMIT_EXCEEDED, session.admitOutbound(request("overflow"), 42).code)
        now = 200
        assertTrue(session.expire().isEmpty())
        assertTrue(session.close().isEmpty())
    }

    @Test fun pendingDeadlineAndFencingRemainFailClosed() {
        val session = session()
        assertEquals(TransportResultCode.UNAUTHORIZED, session.admitOutbound(request("one"), 43).code)
        assertEquals(TransportResultCode.ACCEPTED, session.admitOutbound(request("one"), 42).code)
        assertEquals(TransportResultCode.LIMIT_EXCEEDED, session.admitOutbound(request("two"), 42).code)
        assertEquals(TransportResultCode.INACTIVE_SESSION, session.delivery("old", submitted("one"), 42).code)
        assertEquals(TransportResultCode.INVALID_REQUEST, session.delivery("session", submitted("one").copy(channelId = "other"), 42).code)
        assertEquals(TransportResultCode.UNSUPPORTED, session.delivery("session",
            submitted("one").copy(stage = TransportDeliveryStage.CHANNEL_DELIVERED), 42).code)
        now = 200
        assertEquals(TransportResultCode.EXPIRED, session.delivery("session", submitted("one"), 42).code)
        assertEquals(listOf("one"), session.expire())
        assertTrue(session.close().isEmpty())
        assertEquals(TransportResultCode.INACTIVE_SESSION, session.admitOutbound(request("two"), 42).code)
    }
}
