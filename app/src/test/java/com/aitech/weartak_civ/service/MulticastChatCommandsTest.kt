package com.aitech.weartak_civ.service

import android.app.Application
import android.net.Network
import com.aitech.weartak_civ.TakMulticastEndpoints
import com.aitech.weartak_civ.WifiMulticastReceiver
import com.aitech.weartak_civ.command.PendingServerEnrollment
import com.aitech.weartak_civ.cot.TakServerCotIngestor
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlSource
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** Exercises the actual service collector, datagram decoder, shared ingestor and command runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MulticastChatCommandsTest {
    private lateinit var repo: DataRepository
    private lateinit var chat: ChatService
    private lateinit var delivery: ChatDelivery
    private val replies = mutableListOf<String>()
    private val groupReplyUids = mutableListOf<String>()
    private val now = System.currentTimeMillis() - 1000
    private var caseId = 0

    class ChatDelivery(private val service: ChatService) {
        val events = mutableListOf<TakChatEvent>()
        @Subscribe fun receive(event: TakChatEvent) {
            events += event
            service.onTakChat(event)
        }
    }

    @Before fun setUp() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        MainActivity.device_uid = "test-watch"
        MainActivity.callsign = "TEST-WATCH"
        repo = spyk(DataRepository(app))
        MainActivity.dataRepository = repo
        val commandHandler = ChatCommandRuntime.build(app, repo)
        every { repo.chatCommandHandler } returns commandHandler
        mockkObject(ChatCommandReplies)
        coEvery { ChatCommandReplies.send(any(), any(), any(), any()) } coAnswers {
            val request = thirdArg<com.aitech.weartak_civ.command.ChatCommandRequest>()
            if (request.replyIsGroup) groupReplyUids += requireNotNull(request.replyRoomUid)
            replies += arg<String>(3)
            true
        }
        coEvery { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) } returns true
        // Re-run the production lazy initializer against this repository, not another fixture's
        // retained process singleton. Do not duplicate its runtime/handler wiring in the test.
        mockkObject(TAKConnectService.Companion, recordPrivateCalls = true)
        val factory = TAKConnectService::class.java.declaredMethods.single {
            it.returnType == TakServerCotIngestor::class.java && it.parameterCount == 0
        }.apply { isAccessible = true }
        val ingestor = factory.invoke(null) as TakServerCotIngestor
        every { TAKConnectService.Companion getProperty "cotIngestor" } returns ingestor
        resetCase()
    }

    private suspend fun resetCase() {
        caseId++
        if (::delivery.isInitialized) EventBus.getDefault().unregister(delivery)
        repo.storeEnableChatCommands(true)
        repo.storeNetworkPreferencesLocked(false)
        repo.storeChatCommandCallsigns(listOf("ALPHA-TEST"))
        every { repo.phoneManagedSettingsLocked } answers { callOriginal() }
        MainActivity.callsign = "TEST-WATCH"
        replies.clear()
        groupReplyUids.clear()
        clearMocks(repo, answers = false)
        clearMocks(ChatCommandReplies, answers = false)
        chat = Robolectric.buildService(ChatService::class.java).get()
        ChatService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true
            set(chat, repo)
        }
        delivery = ChatDelivery(chat)
        EventBus.getDefault().register(delivery)
        PendingServerEnrollment.clear()
    }

    @After fun tearDown() {
        EventBus.getDefault().unregister(delivery)
        PendingServerEnrollment.clear()
        unmockkObject(TAKConnectService.Companion)
        unmockkObject(ChatCommandReplies)
    }

    @Test fun productionMulticastCommandPolicyAndCrossTransportMatrix() = runBlocking {
        // Both transports intentionally retain one command runtime for the process lifetime.
        // Keep that production runtime and repository together across the policy cases.
        val cases = listOf(
            ::allowlistedDirectMulticastUsesExistingRuntimeAndDoesNotRenderOrRelayCommand,
            ::disabledCommandsStayOrdinaryChatAndNeverExecute,
            ::emptyAllowlistAndNonAllowedSenderAreConsumedWithoutReplyOrExecution,
            ::allCommandsAcceptAllowedGroupAndBroadcastAndRejectUnlistedSenders,
            ::anotherRecipientAndSelfEchoNeverExecuteOrRender,
            ::watchUidMembershipSupportsDirectChatWithoutRemarksToButNotMissingRecipient,
            ::staleAndInvalidCommandsCannotMutateProfile,
            ::managedIdentityLockStillRejectsAllowedMulticastCommand,
            ::existingHeightAndEnrollmentHandlersRetainValidationAndCredentialPrompt,
            ::duplicateServerAndMulticastCommandExecutesAndRepliesOnceInEitherOrder,
            ::networkMasterCommandsExecuteOverMulticastAndCanReenableTak,
            ::ordinaryChatAcrossTransportsRetainsSingleInboxMessageAndUnreadCount
        )
        for (case in cases) {
            resetCase()
            case()
        }
    }

    private fun networkMasterCommandsExecuteOverMulticastAndCanReenableTak() = runBlocking {
        multicast(xml(id = "tak-off", text = "#weartakcommand disabletakservers"))
        assertFalse(repo.getEnableTakEndpoint.first())
        multicast(xml(id = "tak-on", text = "#weartakcommand enabletakservers"))
        assertTrue(repo.getEnableTakEndpoint.first())
        multicast(xml(id = "multicast-off", text = "#weartakcommand disablemulticast"))
        assertFalse(repo.getEnableMulticast.first())
        assertTrue(delivery.events.isEmpty())
        coVerify(exactly = 2) { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) }
    }

    private fun allowlistedDirectMulticastUsesExistingRuntimeAndDoesNotRenderOrRelayCommand() = runBlocking {
        multicast(xml())
        assertEquals(
            org.robolectric.shadows.ShadowLog.getLogsForTag("WearTAK-ChatCommands").joinToString { it.msg },
            listOf("Callsign set to TEST-CHANGED"), replies
        )
        coVerify(exactly = 1) { repo.storeCallsign("TEST-CHANGED") }
        assertTrue(delivery.events.isEmpty())
        assertTrue(chat.getInbox().isEmpty())
    }

    private fun disabledCommandsStayOrdinaryChatAndNeverExecute() = runBlocking {
        repo.storeEnableChatCommands(false)
        multicast(xml(), xml(id = "disabled-group", room = "synthetic-group"),
            xml(id = "disabled-all", room = "All Chat Rooms"))
        coVerify(exactly = 0) { repo.storeCallsign(any()) }
        assertTrue(replies.isEmpty())
        assertEquals(3, chat.getInbox().sumOf { it.messageList.size })
    }

    private fun emptyAllowlistAndNonAllowedSenderAreConsumedWithoutReplyOrExecution() = runBlocking {
        repo.storeChatCommandCallsigns(emptyList())
        multicast(xml())
        repo.storeChatCommandCallsigns(listOf("ALPHA-TEST"))
        multicast(xml(id = "other", sender = "OTHER-TEST"))
        coVerify(exactly = 0) { repo.storeCallsign(any()) }
        assertTrue(replies.isEmpty())
        assertTrue(delivery.events.isEmpty())
    }

    private fun allCommandsAcceptAllowedGroupAndBroadcastAndRejectUnlistedSenders() = runBlocking {
        multicast(
            xml(room = "synthetic-group"),
            xml(id = "broadcast", room = "All Chat Rooms"),
            xml(id = "denied-group", room = "synthetic-group", sender = "OTHER-TEST"),
            xml(id = "denied-broadcast", room = "All Chat Rooms", sender = "OTHER-TEST")
        )
        coVerify(exactly = 2) { repo.storeCallsign("TEST-CHANGED") }
        assertEquals(listOf("Callsign set to TEST-CHANGED", "Callsign set to TEST-CHANGED"), replies)
        assertEquals(listOf("synthetic-group", "All Chat Rooms"), groupReplyUids)
        assertTrue(delivery.events.isEmpty())
    }

    private fun anotherRecipientAndSelfEchoNeverExecuteOrRender() = runBlocking {
        multicast(
            xml(room = "another-watch", participant = "another-watch", to = "another-watch"),
            xml(id = "self", senderUid = "test-watch")
        )
        coVerify(exactly = 0) { repo.storeCallsign(any()) }
        assertTrue(replies.isEmpty())
        assertTrue(delivery.events.isEmpty())
    }

    private fun watchUidMembershipSupportsDirectChatWithoutRemarksToButNotMissingRecipient() = runBlocking {
        multicast(xml(to = ""), xml(id = "missing", room = "", participant = "", to = ""))
        coVerify(exactly = 1) { repo.storeCallsign("TEST-CHANGED") }
        assertEquals(1, replies.size)
        assertTrue(delivery.events.isEmpty())
    }

    private fun staleAndInvalidCommandsCannotMutateProfile() = runBlocking {
        multicast(
            xml(at = now - 6 * 60_000),
            xml(id = "invalid", text = "#weartakcommandsetcallsign callsign=bad;value")
        )
        coVerify(exactly = 0) { repo.storeCallsign(any()) }
        assertEquals(1, replies.size)
        assertTrue(replies.single().startsWith("Command rejected") ||
            replies.single().startsWith("setcallsign needs"))
        assertTrue(delivery.events.isEmpty())
    }

    private fun managedIdentityLockStillRejectsAllowedMulticastCommand() = runBlocking {
        every { repo.phoneManagedSettingsLocked } returns flowOf(true)
        multicast(xml())
        coVerify(exactly = 0) { repo.storeCallsign(any()) }
        assertEquals(listOf("Settings are managed; callsign not changed"), replies)
    }

    private fun existingHeightAndEnrollmentHandlersRetainValidationAndCredentialPrompt() = runBlocking {
        multicast(
            xml(text = "#weartakcommandsetheight height=5-8"),
            xml(id = "enroll", text = "#weartakcommandenrollserver name=Test url=tak.example.invalid"),
            xml(id = "credentials", text = "#weartakcommandenrollserver name=Test url=tak.example.invalid password=synthetic-only")
        )
        coVerify(exactly = 1) { repo.storePhysiologicalMetricHeightFeet(5) }
        coVerify(exactly = 1) { repo.storePhysiologicalMetricHeightInches(8) }
        assertEquals(3, replies.size)
        assertTrue(replies[1].contains("enter credentials"))
        assertTrue(replies[2].contains("both username and password"))
        assertFalse(replies.any { it.contains("synthetic-only") })
    }

    private fun duplicateServerAndMulticastCommandExecutesAndRepliesOnceInEitherOrder() = runBlocking {
        val first = xml()
        server(first)
        multicast(first, first)
        val second = xml(id = "second")
        multicast(second)
        server(second)
        coVerify(exactly = 2) { repo.storeCallsign("TEST-CHANGED") }
        assertEquals(2, replies.size)
        assertTrue(delivery.events.isEmpty())
    }

    private fun ordinaryChatAcrossTransportsRetainsSingleInboxMessageAndUnreadCount() = runBlocking {
        val ordinary = xml(text = "Synthetic chat")
        multicast(ordinary)
        server(ordinary)
        assertEquals(1, chat.getInbox().single().messageList.size)
        assertEquals(1, chat.getUnreadMsgsCount())
        assertTrue(replies.isEmpty())
        coVerify(exactly = 0) { repo.storeCallsign(any()) }
    }

    private suspend fun server(xml: String) {
        val ready = CountDownLatch(1)
        val socket = mockk<SSLSocket>(relaxed = true) {
            every { inputStream } answers {
                check(ready.await(10, TimeUnit.SECONDS))
                ByteArrayInputStream("$xml\n".toByteArray(Charsets.UTF_8))
            }
        }
        val detail = TAKConnectService.TakServerDetail(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", false, "Synthetic", "tak.example.invalid",
            isP12Cert = false, username = "", password = "", p12Cert = null, p12CertPassword = null
        )
        val attempt = TAKConnectService.ConnectionAttempt(0)
        detail.runtime.currentAttempt = attempt
        TAKConnectService.Companion.javaClass.getDeclaredMethod(
            "startCotReader", TAKConnectService.TakServerDetail::class.java,
            SSLSocket::class.java, TAKConnectService.ConnectionAttempt::class.java
        ).apply {
            isAccessible = true
            invoke(TAKConnectService.Companion, detail, socket, attempt)
        }
        val reader = requireNotNull(detail.runtime.readerJob)
        ready.countDown()
        withContext(Dispatchers.Default) { withTimeout(10_000) { reader.join() } }
    }

    private suspend fun multicast(vararg packets: String) {
        val input = packets.iterator()
        val socket = mockk<MulticastSocket>(relaxed = true)
        every { socket.receive(any()) } answers {
            if (!input.hasNext()) throw CancellationException("Synthetic datagrams consumed")
            firstArg<DatagramPacket>().apply {
                val bytes = input.next().toByteArray(Charsets.UTF_8)
                bytes.copyInto(data)
                length = bytes.size
                address = InetAddress.getByName("192.0.2.40")
                port = TakMulticastEndpoints.chat.port
            }
        }
        val receiver = WifiMulticastReceiver(
            listOf(WifiMulticastReceiver.Subscription(TakMulticastEndpoints.chat, socket))
        ) {}
        val service = Robolectric.buildService(TAKConnectService::class.java).get()
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    service.collectMulticastConfigurations(
                        flowOf(TAKConnectService.MulticastConfig(mockk<Network>(), true, "239.2.3.1", 6969)),
                        updatePublisher = { _, _ -> }, openReceiver = { receiver }
                    )
                }
            }
        } finally {
            TAKConnectService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
                (get(service) as CoroutineScope).cancel()
            }
        }
        verify(exactly = 1) { socket.close() }
    }

    private fun xml(
        id: String = "synthetic-message",
        text: String = "#weartakcommandsetcallsign callsign=TEST-CHANGED",
        sender: String = "ALPHA-TEST",
        senderUid: String = "synthetic-sender",
        room: String = "test-watch",
        participant: String = "test-watch",
        to: String = "test-watch",
        at: Long = now
    ) = """<event uid="chat-$caseId-$id" type="b-t-f" time="${Instant.ofEpochMilli(at)}">
        <detail><__chat id="$room" chatroom="Synthetic chat" senderCallsign="$sender" messageId="$caseId-$id">
        <chatgrp id="$room" uid0="$senderUid" uid1="$participant"/></__chat>
        <remarks to="$to">$text</remarks></detail></event>"""
}
