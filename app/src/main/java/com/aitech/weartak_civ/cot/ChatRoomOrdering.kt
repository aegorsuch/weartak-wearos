package com.aitech.weartak_civ.cot

object ChatRoomOrdering {
    fun newestMessages(messages: List<ChatMessage>): List<ChatMessage> =
        messages.sortedWith(compareByDescending<ChatMessage> { it.messageTime }
            .thenBy { it.uid }.thenBy { it.messageId.orEmpty() }.thenBy { it.message })

    fun latestActivityTime(room: ChatRoom): Long =
        room.messageList.maxOfOrNull { it.messageTime } ?: 0L

    fun byLatestActivity(rooms: Collection<ChatRoom>): List<ChatRoom> =
        rooms.sortedWith(compareByDescending<ChatRoom> { latestActivityTime(it) }.thenBy { it.uid })

    fun pickerRooms(rooms: Collection<ChatRoom>): List<ChatRoom> =
        byLatestActivity(rooms.filter { it.uid != ChatDestinations.ALL_CHAT_ROOMS })
}
