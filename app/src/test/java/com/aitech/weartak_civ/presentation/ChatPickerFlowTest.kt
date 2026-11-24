package com.aitech.weartak_civ.presentation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import com.aitech.weartak_civ.cot.ChatDestinations
import com.aitech.weartak_civ.cot.ChatMessage
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.ChatService
import com.aitech.weartak_civ.events.TakChatEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatPickerFlowTest {
    private val opened = mutableListOf<ChatRoom>()
    private val navigations = mutableListOf<String>()
    private val errors = mutableListOf<IllegalArgumentException>()
    private var launches = 0
    private fun flow(open: (ChatRoom) -> ChatRoom = { opened += it; it }) =
        ChatPickerFlow("watch", open, { navigations += it }, { launches++ }, { errors += it })

    @Test fun actionsOpenCanonicalBroadcastOrLaunchMapWithoutCreatingARoom() {
        val picker = flow()
        picker.newChat()
        assertEquals(1, launches)
        assertTrue(opened.isEmpty())
        picker.allChatRooms("Localized broadcast title")
        assertEquals(listOf(ChatDestinations.ALL_CHAT_ROOMS), navigations)
        assertTrue(opened.single().isGroup)
        assertEquals("Localized broadcast title", opened.single().title)
    }

    @Test fun contractTargetsMapAndCancelNeverCreatesAConversation() {
        val contract = ChatContactContract()
        val intent = contract.createIntent(RuntimeEnvironment.getApplication(), Unit)
        assertEquals(ChatContactPickerActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.getBooleanExtra(ChatContactContract.EXTRA_SELECT_CHAT_CONTACT, false))
        val contact = DirectChatContact("alice", "Alice")
        val result = ChatContactContract.result(contact)
        flow().selectedContact(contract.parseResult(Activity.RESULT_CANCELED, result))
        assertTrue(opened.isEmpty())
        flow().selectedContact(contract.parseResult(Activity.RESULT_OK, result))
        assertEquals(listOf("alice"), navigations)
        assertFalse(opened.single().isGroup)
        assertEquals(listOf("alice"), opened.single().participantUids)
    }

    @Test fun manifestAllowsPickerResultsWithoutChangingNormalMapTaskBehavior() {
        val app = RuntimeEnvironment.getApplication()
        fun info(type: Class<*>) = app.packageManager.getActivityInfo(ComponentName(app, type), 0)
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, info(ChatContactPickerActivity::class.java).launchMode)
        assertFalse(info(ChatContactPickerActivity::class.java).exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info(ChatActivity::class.java).launchMode)
        assertEquals("com.aitech.weartak_civ", info(ChatActivity::class.java).taskAffinity)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info(MapActivity::class.java).launchMode)
        assertEquals(info(MapActivity::class.java).taskAffinity, info(ChatActivity::class.java).taskAffinity)
    }

    @Test fun invalidSelfBroadcastAndMalformedSelectionDoNotNavigateOrSend() {
        val picker = flow()
        listOf(DirectChatContact("", "Nobody"), DirectChatContact("watch", "Me"),
            DirectChatContact(ChatDestinations.ALL_CHAT_ROOMS, "All"),
            DirectChatContact("alice", "")).forEach(picker::selectedContact)
        picker.selectedContact(ChatContactContract().parseResult(Activity.RESULT_OK, Intent()))
        assertEquals(5, errors.size)
        assertTrue(opened.isEmpty())
        assertTrue(navigations.isEmpty())
    }

    @Test fun serviceFailureIsReportedForBothActionsWithoutNavigation() {
        val picker = flow { throw IllegalArgumentException("Service unavailable") }
        picker.allChatRooms("All Chat Rooms")
        picker.selectedContact(DirectChatContact("alice", "Alice"))
        assertEquals(2, errors.size)
        assertTrue(navigations.isEmpty())
    }

    private fun user(uid: String, title: String = uid) = DataRepository.ReceivedUser(
        uid, "a-f-G-U-C", "Blue", title, "Team Member", 1, 100, null, "server", chatEndpoint = "*:4242:tcp")

    @Test fun aCallsignAloneDoesNotEnableChat() {
        val point = user("point").copy(chatEndpoint = null)
        assertThrows(IllegalArgumentException::class.java) {
            resolveChatContact(point.uid, listOf(point), "watch")
        }
    }

    @Test fun mapSelectionUsesLiveUidNotListIndexAndRejectsDisappearedPeople() {
        val alice = user("alice", "Alice")
        val bob = user("bob", "Bob")
        assertEquals(DirectChatContact("alice", "Alice"),
            resolveChatContact("alice", listOf(bob, alice), "watch"))
        assertEquals(DirectChatContact("alice", "New callsign"),
            resolveChatContact("alice", listOf(alice.copy(title = "New callsign"), bob), "watch"))
        for (uid in listOf("alice", "marker", null)) {
            assertThrows(IllegalArgumentException::class.java) { resolveChatContact(uid, listOf(bob), "watch") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveChatContact("watch", listOf(user("watch")), "watch")
        }
    }

    @Test fun reopeningServiceConversationPreservesMessagesReadStateAndGroupMembership() {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val service = Robolectric.buildService(ChatService::class.java).get()
        ChatService::class.java.getDeclaredField("dataRepository").apply { isAccessible = true }.set(service, repo)
        val group = ChatDestinations.allRooms("All Chat Rooms").copy(
            messageList = listOf(ChatMessage("Hi", "alice", "Alice", 100)),
            timeLastClosed = 120, participantUids = listOf("alice", "watch"))
        service.openConversation(group)
        assertEquals(group, service.openConversation(ChatDestinations.allRooms("All Chat Rooms")))
        val direct = ChatDestinations.direct("alice", "Alice", "watch").copy(
            messageList = group.messageList, timeLastClosed = 120)
        service.openConversation(direct.copy(participantUids = emptyList()))
        assertEquals(direct, service.openConversation(ChatDestinations.direct("alice", "Alice", "watch")))
        assertEquals(2, service.getInbox().size)
        assertEquals(direct, repo.chatRoomsFlow.value.single { it.uid == "alice" })
        assertThrows(IllegalArgumentException::class.java) {
            service.openConversation(direct.copy(isGroup = true))
        }

    }

    @Test fun incomingDirectAndGroupMessagesRepublishDynamicRoomOrder() {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val service = Robolectric.buildService(ChatService::class.java).get()
        ChatService::class.java.getDeclaredField("dataRepository").apply { isAccessible = true }.set(service, repo)
        fun incoming(uid: String, sender: String, time: Long, group: Boolean = false) {
            val room = ChatRoom(uid, uid, listOf(ChatMessage("Received $time", sender, sender, time)),
                true, 0, 0, isGroup = group)
            service.onTakChat(TakChatEvent("", parsedRooms = listOf(room)))
        }
        incoming("alice", "alice", 100)
        incoming("team", "bob", 200, true)
        assertEquals(listOf("team", "alice"), repo.chatRoomsFlow.value.map { it.uid })
        incoming("alice", "alice", 300)
        assertEquals(listOf("alice", "team"), repo.chatRoomsFlow.value.map { it.uid })
        incoming("alice", "alice", 50)
        assertEquals(listOf("alice", "team"), repo.chatRoomsFlow.value.map { it.uid })
    }

    @Test fun markingRoomReadDoesNotMutatePreviouslyPublishedRoom() {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val service = Robolectric.buildService(ChatService::class.java).get()
        ChatService::class.java.getDeclaredField("dataRepository").apply { isAccessible = true }.set(service, repo)
        val unread = ChatRoom("alice", "Alice", listOf(ChatMessage("Hi", "alice", "Alice", 1)),
            true, 0, 0)
        service.openConversation(unread)
        val published = repo.chatRoomsFlow.value.single()

        service.markRoomRead("alice")

        assertTrue(published.newMessagesPresent)
        assertEquals(0L, published.timeLastClosed)
        assertFalse(service.getInbox().single().newMessagesPresent)
        assertEquals(0, service.getUnreadMsgsCount())
    }
}
