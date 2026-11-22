package com.aitech.weartak_civ.cot

object ChatInboxMerge {
    private data class MessageKey(
        val sender: String,
        val id: String?,
        val time: Long?,
        val text: String?
    )

    private fun key(message: ChatMessage): MessageKey {
        val id = message.messageId?.takeIf { it.isNotBlank() }
        return MessageKey(message.uid, id,
            if (id == null) message.timeReceived else null,
            if (id == null) message.message else null)
    }

    fun addedMessages(existing: ChatRoom?, incoming: ChatRoom): List<ChatMessage> {
        val known = existing?.messageList.orEmpty().mapTo(mutableSetOf(), ::key)
        return incoming.messageList.filter { known.add(key(it)) }
    }

    fun merge(existing: ChatRoom?, incoming: ChatRoom): ChatRoom {
        val added = addedMessages(existing, incoming)
        if (existing != null && added.isEmpty()) return existing
        return (existing ?: incoming).copy(
            title = incoming.title,
            messageList = existing?.messageList.orEmpty() + added,
            newMessagesPresent = existing?.newMessagesPresent == true || added.isNotEmpty(),
            isGroup = existing?.isGroup == true || incoming.isGroup,
            participantUids = incoming.participantUids.ifEmpty { existing?.participantUids.orEmpty() }
        )
    }
}
