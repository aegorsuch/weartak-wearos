package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.cot.CotEventTimeFactory
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.CotTimeOffset
import com.aitech.weartak_civ.cot.CotXml
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import org.w3c.dom.Element
import java.util.UUID

internal object PluginTransportMessageMapper {
    fun fromCot(
        xml: String,
        senderUid: String,
        senderCallsign: String?,
        supportedKinds: List<Int> = listOf(
            TransportPayloadKind.CHAT_UTF8,
            TransportPayloadKind.COT_XML_UTF8,
        ),
        workspaceId: String? = null,
        externalId: String? = null,
        sentAtEpochMillis: Long? = null,
    ): TransportMessageParcel? {
        val document = CotMessageParser.parseDocumentOrNull(xml) ?: return null
        val event = document.documentElement.takeIf { it.tagName == "event" } ?: return null
        val eventTime = sentAtEpochMillis ?: CotMessageParser.eventTimeOrNull(xml)
        if (CotMessageParser.isChatMessage(xml)) {
            val chat = event.getElementsByTagName("__chat").item(0) as? Element
            val group = chat?.getElementsByTagName("chatgrp")?.item(0) as? Element
            val remarks = event.getElementsByTagName("remarks").item(0) as? Element
            val chatSenderUid = group?.getAttribute("uid0")?.takeIf(String::isNotBlank)
            val chatSenderCallsign = chat?.getAttribute("senderCallsign")?.takeIf(String::isNotBlank)
            val recipientUid = chat?.getAttribute("id")?.takeIf(String::isNotBlank)
                ?: group?.getAttribute("id")?.takeIf(String::isNotBlank)
                ?: remarks?.getAttribute("to")?.takeIf(String::isNotBlank)
            val text = remarks?.textContent?.takeIf(String::isNotBlank)
            if (TransportPayloadKind.CHAT_UTF8 in supportedKinds &&
                chatSenderUid != null && chatSenderCallsign != null && recipientUid != null && text != null
            ) {
                return TransportMessageParcel(
                    messageId = UUID.randomUUID().toString(),
                    payloadKind = TransportPayloadKind.CHAT_UTF8,
                    payload = text.toByteArray(Charsets.UTF_8),
                    senderUid = chatSenderUid,
                    recipientUid = recipientUid,
                    senderCallsign = chatSenderCallsign,
                    workspaceId = workspaceId,
                    externalId = externalId ?: chat.getAttribute("messageId").takeIf(String::isNotBlank),
                    sentAtEpochMillis = eventTime,
                )
            }
        }

        if (TransportPayloadKind.COT_XML_UTF8 !in supportedKinds) return null
        return TransportMessageParcel(
            messageId = UUID.randomUUID().toString(),
            payloadKind = TransportPayloadKind.COT_XML_UTF8,
            payload = xml.toByteArray(Charsets.UTF_8),
            senderUid = senderUid,
            senderCallsign = senderCallsign,
            workspaceId = workspaceId,
            externalId = externalId,
            sentAtEpochMillis = eventTime,
        )
    }

    fun chatToCot(message: TransportMessageParcel): String? {
        if (message.payloadKind != TransportPayloadKind.CHAT_UTF8) return null
        val senderCallsign = message.senderCallsign?.takeIf(String::isNotBlank) ?: return null
        val recipientUid = message.recipientUid?.takeIf(String::isNotBlank) ?: return null
        val text = decodeUtf8(message.payload)?.takeIf(String::isNotBlank) ?: return null
        val sentAt = message.sentAtEpochMillis ?: System.currentTimeMillis()
        val time = CotEventTimeFactory.at(sentAt, staleOffset = CotTimeOffset(days = 1))
        val eventUid = UUID.randomUUID().toString()
        val chatMessageId = message.externalId?.takeIf(String::isNotBlank) ?: message.messageId
        val chatGroup = CotXml.element(
            "chatgrp",
            listOf(
                "uid0" to CotXml.escape(message.senderUid),
                "uid1" to CotXml.escape(recipientUid),
                "id" to CotXml.escape(recipientUid),
            ),
        )
        val chat = CotXml.element(
            "__chat",
            listOf(
                "parent" to "RootContactGroup",
                "groupOwner" to "false",
                "messageId" to CotXml.escape(chatMessageId),
                "chatroom" to CotXml.escape(senderCallsign),
                "id" to CotXml.escape(recipientUid),
                "senderCallsign" to CotXml.escape(senderCallsign),
            ),
            chatGroup,
        )
        val detail = chat +
            CotXml.element(
                "link",
                listOf(
                    "uid" to CotXml.escape(message.senderUid),
                    "type" to "a-f-G-U-C",
                    "relation" to "p-p",
                ),
            ) +
            CotXml.element(
                "remarks",
                listOf(
                    "source" to CotXml.escape(message.senderUid),
                    "to" to CotXml.escape(recipientUid),
                    "time" to time.timeCot,
                ),
                CotXml.escape(text),
            )
        return CotXml.event(
            listOf(
                "version" to "2.0",
                "uid" to CotXml.escape("GeoChat.${message.senderUid}.$eventUid"),
                "type" to "b-t-f",
                "time" to time.timeCot,
                "start" to time.startCot,
                "stale" to time.staleCot,
                "how" to "h-g-i-g-o",
                "access" to "Undefined",
            ),
            listOf("lat" to "0", "lon" to "0", "hae" to "0", "ce" to "999999", "le" to "999999"),
            detail,
        )
    }

    private fun decodeUtf8(value: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .decode(java.nio.ByteBuffer.wrap(value))
            .toString()
    }.getOrNull()
}
