package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.cot.CotMessageParser
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTransportMessageMapperTest {
    @Test
    fun outboundCoTChatBecomesTypedChatWithCorrelationSeparateFromEventUid() {
        val xml = chatXml()

        val message = PluginTransportMessageMapper.fromCot(
            xml = xml,
            senderUid = "watch-1",
            senderCallsign = "WATCH",
            workspaceId = "workspace-9",
        )!!

        assertEquals(TransportPayloadKind.CHAT_UTF8, message.payloadKind)
        assertEquals("sender-1", message.senderUid)
        assertEquals("watch-1", message.recipientUid)
        assertEquals("SENDER", message.senderCallsign)
        assertEquals("workspace-9", message.workspaceId)
        assertEquals("external-123", message.externalId)
        assertEquals(4_107_542_400_000L, message.sentAtEpochMillis)
        assertEquals("Hello from satellite", String(message.payload, Charsets.UTF_8))
        assertNotEquals(CotMessageParser.eventUidOrNull(xml), message.externalId)
    }

    @Test
    fun inboundTypedChatUsesExplicitRecipientAndRetainsOpaqueMetadata() {
        val message = TransportMessageParcel(
            messageId = "transport-42",
            payloadKind = TransportPayloadKind.CHAT_UTF8,
            payload = "Hello <watch>".toByteArray(Charsets.UTF_8),
            senderUid = "remote-1",
            recipientUid = "watch-1",
            senderCallsign = "REMOTE",
            workspaceId = "workspace-9",
            externalId = "18446744073709551615",
            sentAtEpochMillis = 4_105_152_000_000L,
        )

        val xml = PluginTransportMessageMapper.chatToCot(message)!!
        val rooms = CotMessageParser.parseChatRooms(xml, "watch-1", "WATCH", "plugin:test", 50L)
        val received = rooms.single().messageList.single()

        assertEquals("remote-1", received.uid)
        assertEquals("REMOTE", received.callsign)
        assertEquals("Hello <watch>", received.message)
        assertEquals("18446744073709551615", received.messageId)
        assertEquals("plugin:test", received.sourceServerId)
        assertEquals(50L, received.timeReceived)
        assertNotEquals(CotMessageParser.eventUidOrNull(xml), received.messageId)
        assertTrue(CotMessageParser.eventTimeOrNull(xml)!! >= 4_105_152_000_000L)
    }

    @Test
    fun typedChatRejectsUnspecifiedRecipientAndMalformedUtf8() {
        val noRecipient = TransportMessageParcel(
            messageId = "message-1",
            payloadKind = TransportPayloadKind.CHAT_UTF8,
            payload = "hello".toByteArray(),
            senderUid = "remote",
            senderCallsign = "REMOTE",
        )
        val malformedUtf8 = noRecipient.copy(
            recipientUid = "watch",
            payload = byteArrayOf(0xC3.toByte(), 0x28),
        )

        assertNull(PluginTransportMessageMapper.chatToCot(noRecipient))
        assertNull(PluginTransportMessageMapper.chatToCot(malformedUtf8))
    }

    @Test
    fun rawCotFallsBackToOpaqueXmlPayload() {
        val malformedChat = "<event uid='x' type='b-t-f'><detail><__chat/></detail></event>"
        val message = PluginTransportMessageMapper.fromCot(malformedChat, "watch-1", "WATCH")!!

        assertEquals(TransportPayloadKind.COT_XML_UTF8, message.payloadKind)
        assertEquals(malformedChat, String(message.payload, Charsets.UTF_8))
        assertFalse(message.externalId != null)
    }

    private fun chatXml() = """
        <event version="2.0" uid="host-event-1" type="b-t-f"
            time="2100-03-01T00:00:00.000Z" start="2100-03-01T00:00:00.000Z"
            stale="2100-03-02T00:00:00.000Z" how="h-g-i-g-o">
            <point lat="0" lon="0" hae="0" ce="1" le="1"/>
            <detail>
                <__chat id="watch-1" senderCallsign="SENDER" messageId="external-123">
                    <chatgrp uid0="sender-1" uid1="watch-1" id="watch-1"/>
                </__chat>
                <remarks to="watch-1">Hello from satellite</remarks>
            </detail>
        </event>
    """.trimIndent()
}
