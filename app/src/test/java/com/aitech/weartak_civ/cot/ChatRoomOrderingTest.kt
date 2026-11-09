package com.aitech.weartak_civ.cot

import org.junit.Assert.*
import org.junit.Test

class ChatRoomOrderingTest {
    private fun message(sender: String, time: Long, received: Long = time) =
        ChatMessage("Message $time", sender, sender, received, messageTime = time)
    private fun room(uid: String, vararg messages: ChatMessage) =
        ChatRoom(uid, uid, messages.toList(), false, 0, 0, isGroup = uid == "team")

    @Test fun pickerExcludesPinnedBroadcastAndIncludesSentActivityWithDeterministicEmptyTies() {
        val direct = room("alice", message("alice", 200), message("alice", 50))
        val group = room("team", message("bob", 300), message("watch", 1000))
        val broadcast = ChatDestinations.allRooms("All Chat Rooms").copy(
            messageList = listOf(message("bob", 2000)))
        val empty = room("carol")
        val outgoingOnly = room("dave", message("watch", 3000))
        assertEquals(listOf("dave", "team", "alice", "carol"),
            ChatRoomOrdering.pickerRooms(listOf(empty, direct, broadcast, outgoingOnly, group)).map { it.uid })
        assertEquals(1000L, ChatRoomOrdering.latestActivityTime(group))
        assertEquals(listOf("carol", "zed"),
            ChatRoomOrdering.byLatestActivity(listOf(room("zed"), empty)).map { it.uid })
    }

    @Test fun incomingAndLocalSendPromoteRoomButLateOlderReplayDoesNot() {
        val alice = room("alice", message("alice", 100))
        val team = room("team", message("bob", 200))
        fun order(first: ChatRoom) = ChatRoomOrdering.pickerRooms(listOf(first, team)).map { it.uid }
        assertEquals(listOf("team", "alice"), order(alice))
        val replay = ChatInboxMerge.merge(alice, room("alice", message("alice", 50, received = 1000)))
        assertEquals(listOf("team", "alice"), order(replay))
        val updated = ChatInboxMerge.merge(replay, room("alice", message("alice", 300)))
        assertEquals(listOf("alice", "team"), order(updated))
        assertEquals(listOf("alice", "team"), order(alice.copy(
            messageList = alice.messageList + message("watch", 400))))
    }

    @Test fun messagesUseEventTimeDescendingAndDeterministicTiesWithoutMutatingReceiptOrUnread() {
        val oldReplay = message("alice", 50, received = 1000)
        val newest = message("watch", 300)
        val tieA = message("alice", 200).copy(messageId = "a")
        val tieB = message("alice", 200).copy(messageId = "b")
        val source = listOf(oldReplay, tieB, newest, tieA)
        val expected = listOf(newest, tieA, tieB, oldReplay)
        assertEquals(expected, ChatRoomOrdering.newestMessages(source))
        assertEquals(expected, ChatRoomOrdering.newestMessages(source.reversed()))
        assertEquals(1000L, oldReplay.timeReceived)
        val unread = room("alice", *source.toTypedArray()).copy(newMessagesPresent = true, timeLastClosed = 400)
        assertEquals(unread, ChatRoomOrdering.byLatestActivity(listOf(unread)).single())
    }

    @Test fun parsedReplayUsesCotTimeButPreservesArrivalForUnread() {
        fun xml(time: String) = """
            <event time="$time"><detail>
              <__chat id="watch" senderCallsign="Alice" messageId="old">
                <chatgrp id="watch" uid0="alice" uid1="watch"/>
              </__chat><remarks to="watch">Old replay</remarks>
            </detail></event>
        """.trimIndent()
        val received = 9000L
        val parsed = CotMessageParser.parseChatRooms(xml("1970-01-01T00:00:01Z"),
            "watch", "WATCH", receivedAt = received).single().messageList.single()
        assertEquals(1000L, parsed.messageTime)
        assertEquals(received, parsed.timeReceived)
        val fallback = CotMessageParser.parseChatRooms(xml(""), "watch", "WATCH",
            receivedAt = received).single().messageList.single()
        assertEquals(received, fallback.messageTime)
    }
}
