package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatCommandRepliesTest {
    @After fun cleanup() = unmockkAll()

    @Test fun acknowledgementsUseOnlyOriginalTransportAndVerifiedConversation() = runBlocking {
        val repo = mockk<DataRepository>()
        val app = RuntimeEnvironment.getApplication()
        com.aitech.weartak_civ.presentation.MainActivity.mainActivity = mockk {
            every { applicationContext } returns app
        }
        com.aitech.weartak_civ.presentation.MainActivity.dataRepository = repo
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "watch"
        val snapshot = mockk<DataRepository.ChatEventSnapshot>()
        every { repo.isCompanionConnected } returns MutableStateFlow(false)
        coEvery { repo.snapshotChatEvent(any(), any(), any(), any(), any()) } returns snapshot
        mockkObject(CotMessageBuilder, TAKConnectService.Companion,
            NetworkUtils.MulticastPublisher, SitxServiceCommandBus)
        every { CotMessageBuilder.chat(snapshot, any(), any(), any(), any()) } returns "<safe-ack/>"
        every { TAKConnectService.sendCommandReply("server-one", "<safe-ack/>") } returns true
        every { NetworkUtils.MulticastPublisher.sendCommandReply("<safe-ack/>", "224.10.10.1", 17012) } returns true
        every { SitxServiceCommandBus.trySend(any()) } returns true
        val request = ChatCommandRequest("sender", "ALPHA", "", "id", 1, 1, "",
            "team", "Team", true, listOf("watch", "sender"))
        listOf("server-one", "multicast:224.10.10.1:17012", "sitx:flow").forEach {
            assertTrue(ChatCommandReplies.send(app, repo, request.copy(sourceServerId = it), "status"))
        }
        assertFalse(ChatCommandReplies.send(app, repo, request.copy(sourceServerId = "ditto:peer"), "status"))
        coVerify(exactly = 4) { repo.snapshotChatEvent("team", "Team", "status", true, listOf("watch", "sender")) }
        verify(exactly = 1) { TAKConnectService.sendCommandReply(any(), any()) }
        verify(exactly = 1) { NetworkUtils.MulticastPublisher.sendCommandReply(any(), any(), any()) }
        verify(exactly = 1) { SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot("<safe-ack/>", "flow")) }
        assertFalse(ChatCommandReplies.send(app, repo, request.copy(sourceServerId = "companion-phone"), "status"))
    }

    @Test fun strictWarningUsesCompletedWriteNotLegacyQueueAndRefusesQueueOnlyRoutes() = runBlocking {
        val repo = mockk<DataRepository>()
        val app = RuntimeEnvironment.getApplication()
        val snapshot = mockk<DataRepository.ChatEventSnapshot>()
        coEvery { repo.snapshotChatEvent(any(), any(), any(), any(), any()) } returns snapshot
        mockkObject(CotMessageBuilder, TAKConnectService.Companion, NetworkUtils.MulticastPublisher,
            SitxServiceCommandBus)
        every { CotMessageBuilder.chat(snapshot, any(), any(), any(), any()) } returns "<warning/>"
        coEvery { TAKConnectService.sendCommandReplyWritten("server", "<warning/>") } returns true
        every { NetworkUtils.MulticastPublisher.sendCommandReply("<warning/>", "239.2.3.1", 6969) } returns true
        val request = ChatCommandRequest("sender", "ALPHA", "", "id", 1, 1, "server")
        for (source in listOf("server", "multicast:239.2.3.1:6969")) {
            assertTrue(ChatCommandReplies.sendConfirmedWrite(app, repo, request.copy(sourceServerId = source), "warning"))
        }
        for (source in listOf("sitx:flow", "companion-gateway", "companion-bluetooth", "ditto:peer")) {
            assertFalse(ChatCommandReplies.sendConfirmedWrite(app, repo, request.copy(sourceServerId = source), "warning"))
        }
        coVerify(exactly = 1) { TAKConnectService.sendCommandReplyWritten("server", "<warning/>") }
        verify(exactly = 0) { TAKConnectService.sendCommandReply(any(), any()) }
        verify(exactly = 0) { SitxServiceCommandBus.trySend(any()) }
        coEvery { TAKConnectService.sendCommandReplyWritten(any(), any()) } returns false
        every { NetworkUtils.MulticastPublisher.sendCommandReply(any(), any(), any()) } returns false
        for (source in listOf("server", "multicast:239.2.3.1:6969", "ditto:peer")) {
            assertFalse(ChatCommandReplies.sendConfirmedWrite(app, repo, request.copy(sourceServerId = source), "warning"))
        }
    }
}
