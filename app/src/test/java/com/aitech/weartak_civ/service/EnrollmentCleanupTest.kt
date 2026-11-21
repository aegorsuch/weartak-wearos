package com.aitech.weartak_civ.service

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.aitech.weartak_civ.command.PurgeSensitiveEnrollmentCopies
import com.aitech.weartak_civ.command.SensitiveEnrollment
import com.aitech.weartak_civ.cot.ChatMessage
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EnrollmentCleanupTest {
    private val command = "#weartakcommand enrollserver Ops,host,8089,u,fake-secret"
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        MainActivity.device_uid = "watch"
        MainActivity.callsign = "WATCH"
    }

    @Test fun inboxGuardAndTargetedPurgeRetainOrdinaryMessages() {
        val repo = DataRepository(app)
        val chat = ChatService()
        ChatService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true
            set(chat, repo)
        }
        val room = ChatRoom("sender", "SENDER", listOf(
            ChatMessage("Hello", "sender", "SENDER", 1L, messageId = "ordinary"),
            ChatMessage(command, "sender", "SENDER", 2L, messageId = "secret")
        ), true, 0L, 0L)
        chat.onTakChat(TakChatEvent("", parsedRooms = listOf(room)))
        assertEquals(listOf("Hello"), chat.getInbox().single().messageList.map { it.message })
        repo.setChatRooms(listOf(room))
        assertEquals(listOf("Hello"), repo.chatRoomsFlow.value.single().messageList.map { it.message })
        @Suppress("UNCHECKED_CAST")
        val map = ChatService::class.java.getDeclaredField("chatRoomMap").apply { isAccessible = true }
            .get(chat) as MutableMap<String, ChatRoom>
        map["sender"] = room
        chat.purgeSensitiveEnrollments()
        assertEquals(listOf("ordinary"), chat.getInbox().single().messageList.map { it.messageId })
        assertEquals(1, chat.getUnreadMsgsCount())
    }

    @Test fun onlyNewRemoteMessagesVibrate() {
        io.mockk.mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) } returns Unit
        try {
            val chat = ChatService()
            ChatService::class.java.getDeclaredField("dataRepository").apply {
                isAccessible = true
                set(chat, DataRepository(app))
            }
            fun room(vararg m: ChatMessage) = ChatRoom("sender", "SENDER", m.toList(), true, 0L, 0L)
            val remote = ChatMessage("Hi", "sender", "SENDER", 1L, messageId = "r1")
            chat.onTakChat(TakChatEvent("", parsedRooms = listOf(room(remote))))
            io.mockk.verify(exactly = 1) { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) }
            // Duplicate remote history plus a new local echo stays silent.
            val echo = ChatMessage("Ack", "watch", "WATCH", 2L, messageId = "l1")
            chat.onTakChat(TakChatEvent("", parsedRooms = listOf(room(remote, echo))))
            io.mockk.verify(exactly = 1) { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) }
            val next = ChatMessage("Again", "sender", "SENDER", 3L, messageId = "r2")
            chat.onTakChat(TakChatEvent("", parsedRooms = listOf(room(remote, echo, next))))
            io.mockk.verify(exactly = 2) { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) }
        } finally {
            io.mockk.unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun notificationCleanupFindsNestedMessagingExtrasWithoutDeletingOtherCards() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("chat-test", "Chat", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(1, Notification.Builder(app, "chat-test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentText("Hello").build())
        manager.notify(2, Notification.Builder(app, "chat-test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setExtras(Bundle().apply { putParcelableArray("android.messages",
                arrayOf(Bundle().apply { putCharSequence("text", command) })) }).build())
        SensitiveEnrollment.purgeNotifications(app)
        assertEquals(listOf(1), manager.activeNotifications.map { it.id })
    }

    @Test fun queueCleanupRemovesCommandsButPreservesCompanionCredentialSettings() {
        val companion = CompanionAppService()
        @Suppress("UNCHECKED_CAST")
        val pending = CompanionAppService::class.java.getDeclaredField("pendingJson").apply { isAccessible = true }
            .get(companion) as ArrayDeque<String>
        val settings = """{"msgType":"settings_changed","payload":{"password":"fake-secret"}}"""
        pending.add(settings)
        pending.add(org.json.JSONObject().put("msgType", "chat")
            .put("payload", org.json.JSONObject().put("msg", command)).toString())
        companion.onPurgeSensitiveEnrollments(PurgeSensitiveEnrollmentCopies())
        assertEquals(listOf(settings), pending.toList())
        val dispatch = CotDispatchService()
        @Suppress("UNCHECKED_CAST")
        val queue = CotDispatchService::class.java.getDeclaredField("pendingChatMsgQueue").apply { isAccessible = true }
            .get(dispatch) as java.util.concurrent.ConcurrentLinkedDeque<DataRepository.SendChatRequest>
        queue.add(DataRepository.SendChatRequest("sender", "SENDER", command, 1L))
        queue.add(DataRepository.SendChatRequest("sender", "SENDER", "Hello", 2L))
        dispatch.onPurgeSensitiveEnrollments(PurgeSensitiveEnrollmentCopies())
        assertEquals(listOf("Hello"), queue.map { it.message })
    }

    @Test fun diagnosticsAndRuntimeStringificationExcludeCredentialPayloads() = runBlocking {
        val diagnostics = AppDiagnostics(app)
        try {
            diagnostics.setEnabled(true)
            diagnostics.onAppEvent(TakChatEvent(command))
            assertFalse(diagnostics.exportJson().contains("fake-secret"))
            val detail = TAKConnectService.TakServerDetail("id", true, "Ops", "host", 8089,
                false, "operator", "fake-secret", null, null)
            assertFalse(detail.toString().contains("fake-secret"))
            assertFalse(detail.toString().contains("operator"))
        } finally {
            diagnostics.setEnabled(false)
            diagnostics.close()
        }
    }

    @Test fun runtimeFinallyPurgesEvenWhenSaveAndAcknowledgementFail() = runBlocking {
        val repo = io.mockk.spyk(DataRepository(app))
        repo.storeEnableChatCommands(true)
        repo.storeChatCommandCallsigns(listOf("ALPHA"))
        io.mockk.coEvery { repo.chatEnrollmentBlocked() } returns false
        io.mockk.coEvery { repo.saveChatEnrollment(any(), any(), any(), any(), any()) } throws
            IllegalStateException("fake-secret must never be reported")
        io.mockk.mockkObject(ChatCommandReplies)
        val replies = mutableListOf<String>()
        try {
            io.mockk.coEvery { ChatCommandReplies.send(any(), any(), any(), any()) } coAnswers {
                replies += arg<String>(3)
                false
            }
            val runtime = ChatCommandRuntime.build(app, repo)
            val now = System.currentTimeMillis()
            val result = runCatching { runtime(com.aitech.weartak_civ.command.ChatCommandRequest(
                "sender", "ALPHA", command, "failed", now, now, "srv")) }
            assertTrue(result.isFailure)
            assertEquals(listOf("Command failed"), replies)
            io.mockk.verify(exactly = 1) { repo.purgeSensitiveChatRooms() }
            assertTrue(org.robolectric.shadows.ShadowLog.getLogs().none { it.msg.contains("fake-secret") })
        } finally { io.mockk.unmockkObject(ChatCommandReplies) }
    }
}
