package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.CompanionAppRepository

object CompanionChatIngestor {
    fun room(payload: CompanionAppRepository.ChatReceivedPayload?, now: Long): ChatRoom {
        requireNotNull(payload) { "Missing chat payload" }
        val id = requireNotNull(payload.uid?.takeIf { it.isNotBlank() }) { "Missing message ID" }
        val sender = requireNotNull(payload.senderUid?.takeIf { it.isNotBlank() }) { "Missing sender UID" }
        val callsign = requireNotNull(payload.senderCallsign?.takeIf { it.isNotBlank() }) { "Missing sender callsign" }
        val text = requireNotNull(payload.msg?.takeIf { it.isNotBlank() }) { "Missing chat text" }
        val conversation = payload.conversationUid?.takeIf { it.isNotBlank() }
        val title = payload.conversationTitle?.takeIf { it.isNotBlank() }
        val isGroup = payload.isGroup == true
        require(!isGroup || (conversation != null && conversation != sender && title != null)) {
            "Missing group identity or title"
        }
        return ChatRoom(
            uid = if (isGroup) requireNotNull(conversation) else sender,
            title = if (isGroup) requireNotNull(title) else callsign,
            messageList = listOf(ChatMessage(
                message = text, uid = sender, callsign = callsign,
                timeReceived = payload.receivedAt?.takeIf { it > 0L } ?: now,
                messageId = id,
                messageTime = payload.sentAt?.takeIf { it > 0L }
                    ?: payload.receivedAt?.takeIf { it > 0L } ?: now
            )),
            newMessagesPresent = true,
            timeLastOpened = 0L,
            timeLastClosed = 0L,
            isGroup = isGroup,
            participantUids = if (isGroup) {
                (payload.participantUids.orEmpty().filter { it.isNotBlank() && it != conversation } + sender).distinct()
            } else emptyList()
        )
    }
}
