package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.CompanionAppRepository
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CompanionChatIngestorTest {
    private fun payload() = CompanionAppRepository.ChatReceivedPayload(
        uid = "message-1", senderUid = "sender", senderCallsign = "Alice",
        msg = "Hello", receivedAt = 100L
    )

    @Test fun bleEnvelopeReachesInboxAndReplayIsDeduplicated() {
        val json = """{"msgType":"chat_received","payload":{"uid":"m1","senderUid":"alice",
            "senderCallsign":"Alice","msg":"Hello","receivedAt":100}}"""
        val envelope = Gson().fromJson(json, CompanionAppRepository.ChatReceivedEnvelope::class.java)
        val incoming = CompanionChatIngestor.room(envelope.payload, 200)
        val room = ChatInboxMerge.merge(null, incoming)
        assertEquals("alice", room.uid)
        assertEquals("Alice", room.title)
        assertEquals("Hello", room.messageList.single().message)
        assertEquals(room, ChatInboxMerge.merge(room, incoming))
    }

    @Test fun invalidRequiredFieldsAreRejected() {
        listOf(null, payload().copy(uid = ""), payload().copy(senderUid = " "),
            payload().copy(senderCallsign = null), payload().copy(msg = "")
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { CompanionChatIngestor.room(value, 200) }
        }
    }

    @Test fun invalidTimestampUsesReceiptTime() {
        assertEquals(200L, CompanionChatIngestor.room(payload().copy(receivedAt = 0), 200)
            .messageList.single().timeReceived)
    }

    @Test fun originalSentTimeOrdersLateBridgeMessagesWithoutChangingReceiptTime() {
        val message = CompanionChatIngestor.room(payload().copy(sentAt = 50), 200)
            .messageList.single()
        assertEquals(50L, message.messageTime)
        assertEquals(100L, message.timeReceived)
        assertEquals(100L, CompanionChatIngestor.room(payload().copy(sentAt = 0), 200)
            .messageList.single().messageTime)
    }

    @Test fun malformedCanonicalSenderNeverFallsBackToMessageIdAsDirectDestination() {
        for (sender in listOf(null, "", " ")) {
            val canonical = payload().copy(
                uid = "GeoChat.phone.alice.message-123",
                senderUid = sender,
                conversationUid = "alice",
                conversationTitle = "Alice",
                isGroup = false
            )
            assertThrows(IllegalArgumentException::class.java) {
                CompanionChatIngestor.room(canonical, 200)
            }
        }
        val valid = payload().copy(uid = "event-message-id", senderUid = "alice",
            conversationUid = "phone", conversationTitle = "Phone", isGroup = false)
        val room = CompanionChatIngestor.room(valid, 200)
        assertEquals("alice", room.uid)
        assertEquals("event-message-id", room.messageList.single().messageId)
    }

    @Test fun bridgeCommandNeedsOriginalSentAtAndSharesReplayIdentityWithOtherRoutes() = kotlinx.coroutines.runBlocking {
        val now = 1_000_000L
        var runs = 0
        val processor = com.aitech.weartak_civ.command.ChatCommandProcessor(
            { true }, { listOf("Alice") },
            mapOf("setcallsign" to com.aitech.weartak_civ.command.ChatCommandHandler { _, _ ->
                runs++
                com.aitech.weartak_civ.command.ChatCommandResult(true, "Changed")
            }), reply = { _, _ -> }, audit = {}, now = { now })
        suspend fun receive(value: CompanionAppRepository.ChatReceivedPayload, source: String) =
            com.aitech.weartak_civ.command.ChatCommandIngress.handle(
                listOf(CompanionChatIngestor.room(value, now)), value.sentAt ?: 0L, now, source,
                processor::handle)
        val command = payload().copy(msg = "#weartakcommandsetcallsign callsign=UPDATED")
        assertTrue(receive(command, "companion-phone"))
        assertEquals(0, runs)
        val fresh = command.copy(sentAt = now)
        assertTrue(receive(fresh, "companion-phone"))
        assertTrue(receive(fresh, "companion-gateway"))
        assertTrue(receive(fresh, "sitx:flow"))
        assertEquals(1, runs)
        assertFalse(receive(fresh.copy(msg = "ordinary #weartakcommandsetcallsign callsign=WRONG"), "companion-phone"))
        assertEquals(1, runs)
    }
}
