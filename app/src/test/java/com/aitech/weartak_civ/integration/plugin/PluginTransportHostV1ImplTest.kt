package com.aitech.weartak_civ.integration.plugin

import android.os.Process
import android.os.IBinder
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.cot.TakServerCotIngestResult
import com.aitech.weartak_civ.cot.TakServerCotIngestor
import com.aitech.weartak_civ.cot.TakServerCotSink
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotUserPositionEvent
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import com.weartak.plugin.sdk.pdltransfer.v1.PdlTransferContractV1 as PdlContract
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.service.DittoTrackSnapshot
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportDeliveryStage
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginTransportHostV1ImplTest {
    private val hosts = mutableListOf<PluginTransportHostV1Impl>()
    private lateinit var app: WearTakApplication
    private lateinit var ingress: PluginIngressPort

    @Before
    fun setUp() {
        app = mockk(relaxed = true)
        ingress = mockk()
        every { ingress.packagesForUid(app, any()) } returns setOf(PLUGIN_ID)
    }

    @After
    fun tearDown() {
        hosts.forEach { it.closeBinding() }
    }

    @Test
    fun pdlMigrationUsesLiveInactiveTransportRegistration() {
        val host = preactivationHost()
        val registration = host.registerTransport(preactivationCapabilities(), RecordingCallback())
        assertEquals(TransportResultCode.ACCEPTED, registration.code)
        val sessionId = requireNotNull(registration.sessionId)
        val selection = DittoPdlMigrationSelection(DittoPdlSource.CUSTOM, "")
        val broker = HostPdlTransferBrokerV1(
            expectedUid = Process.myUid(),
            authorized = { true },
            isPreactivationSession = host::isPreactivationSession,
            readSelection = { selection },
            selectionCurrent = { it === selection },
            acknowledgeIfCurrent = { expected, action -> if (expected === selection) action() else null },
        )
        val result = broker.read(sessionId, "transfer")
        assertTrue(PdlContract.isValidReadResult(result, "transfer"))
        assertEquals(
            TransportResultCode.INACTIVE_SESSION,
            host.receiveMessage(sessionId, DittoPliTransportSession.GENERAL_CHANNEL, inboundMessage("before-ready")).code,
        )
        assertEquals(PdlContract.INACTIVE_SESSION, broker.read("other", "transfer").getInt(PdlContract.KEY_CODE))
        val nonce = requireNotNull(result.getByteArray(PdlContract.KEY_NONCE))
        assertEquals(PdlContract.OK, broker.acknowledge(sessionId, "transfer", nonce))
        host.activateBinding()
        assertEquals(PdlContract.OK, broker.acknowledge(sessionId, "transfer", nonce))
        host.closeBinding()
        assertEquals(PdlContract.INACTIVE_SESSION, broker.acknowledge(sessionId, "transfer", nonce))
        broker.close()
    }

    @Test
    fun pdlMigrationCannotUseUnregisteredOrReplacedTransportSession() {
        val host = preactivationHost()
        val caps = preactivationCapabilities()
        val first = requireNotNull(host.registerTransport(caps, RecordingCallback()).sessionId)
        val selection = DittoPdlMigrationSelection(DittoPdlSource.CUSTOM, "value")
        val broker = HostPdlTransferBrokerV1(
            expectedUid = Process.myUid(),
            authorized = { true },
            isPreactivationSession = host::isPreactivationSession,
            readSelection = { selection },
            selectionCurrent = { it === selection },
            acknowledgeIfCurrent = { expected, action -> if (expected === selection) action() else null },
        )
        val nonce = requireNotNull(broker.read(first, "transfer").getByteArray(PdlContract.KEY_NONCE))
        assertEquals(TransportResultCode.UNAVAILABLE, host.registerTransport(caps, RecordingCallback()).code)
        assertEquals(PdlContract.INACTIVE_SESSION, broker.acknowledge(first, "transfer", nonce))
        assertEquals(PdlContract.INACTIVE_SESSION, broker.read(first, "new-transfer").getInt(PdlContract.KEY_CODE))
        broker.close()
    }

    @Test
    fun preactivationRefusesEarlyReadyAndAlreadyActiveBinding() {
        val host = preactivationHost()
        val caps = preactivationCapabilities()
        assertEquals(TransportResultCode.UNSUPPORTED,
            host.registerTransport(caps.copy(runtimeAvailable = true), RecordingCallback()).code)
        host.activateBinding()
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.registerTransport(caps, RecordingCallback()).code)
    }

    @Test
    fun preactivationUnregisterRequiresCurrentTrustAndTimeoutRetiresSession() {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        var trusted = true
        var retireCalls = 0
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            pluginOnlyPli = true, preactivationEnabled = true, preactivationTrusted = { trusted },
            retirePreactivation = { retireCalls++; false }, onPliUnavailable = {},
        ).also(hosts::add)
        val id = requireNotNull(host.registerTransport(preactivationCapabilities(), RecordingCallback()).sessionId)
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.unregisterTransport("stale").code)
        trusted = false
        assertEquals(TransportResultCode.UNAUTHORIZED, host.unregisterTransport(id).code)
        assertTrue(host.isPreactivationSession(id))
        assertEquals(0, retireCalls)
        trusted = true
        assertEquals(TransportResultCode.UNAVAILABLE, host.unregisterTransport(id).code)
        assertFalse(host.isPreactivationSession(id))
        assertEquals(1, retireCalls)
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.unregisterTransport(id).code)
        assertEquals(1, retireCalls)
    }

    private fun preactivationHost(): PluginTransportHostV1Impl {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        return PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            pluginOnlyPli = true, trustedDittoPlugin = { true }, onPliUnavailable = {},
            preactivationEnabled = true, preactivationTrusted = { true },
        ).also(hosts::add)
    }

    private fun preactivationCapabilities() = capabilities().copy(
        runtimeAvailable = false,
        outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
        inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
        channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
        deviceReceipts = false,
        recipientReceipts = false,
    )

    @Test
    fun inboundTypedChatUsesHostParserAndPreservesTransportMetadata() {
        val sink = RecordingSink()
        val ingestor = TakServerCotIngestor(
            sink = sink,
            isChatEnabled = { true },
            isGeofenceEnabled = { false },
            deviceUid = { DEVICE_UID },
            deviceCallsign = { "WATCH" },
        )
        val host = createHost { envelope -> ingestor.ingest(envelope) }
        val callback = RecordingCallback()
        val sessionId = register(host, callback, capabilities().copy(maxPayloadBytes = 500))
        val message = TransportMessageParcel(
            messageId = "transport-message-9",
            payloadKind = TransportPayloadKind.CHAT_UTF8,
            payload = "Hello from transport".toByteArray(Charsets.UTF_8),
            senderUid = "remote-1",
            recipientUid = DEVICE_UID,
            senderCallsign = "REMOTE",
            workspaceId = "workspace-9",
            externalId = "18446744073709551615",
            sentAtEpochMillis = 4_107_542_400_000L,
        )

        assertTrue(PluginTransportMessageMapper.chatToCot(message)!!.toByteArray(Charsets.UTF_8).size > 500)
        val result = host.receiveMessage(sessionId, "satellite", message)

        assertEquals(TransportResultCode.ACCEPTED, result.code)
        val chat = sink.chats.single()
        val received = chat.parsedRooms!!.single().messageList.single()
        assertEquals("plugin:$PLUGIN_ID", chat.sourceServerId)
        assertEquals("remote-1", received.uid)
        assertEquals("remote-1", received.transportSenderUid)
        assertEquals("REMOTE", received.transportSenderCallsign)
        assertEquals("REMOTE", received.callsign)
        assertEquals("Hello from transport", received.message)
        assertEquals(sessionId, received.sourceSessionId)
        assertEquals("satellite", received.sourceChannelId)
        assertEquals("workspace-9", received.workspaceId)
        assertEquals("18446744073709551615", received.externalId)
        assertEquals("transport-message-9", received.transportMessageId)
        assertEquals(4_107_542_400_000L, received.sentAtEpochMillis)
        assertNotEquals(received.messageId, received.transportMessageId)
        assertEquals(
            TransportResultCode.DUPLICATE,
            host.receiveMessage(sessionId, "satellite", message).code,
        )
        assertEquals(1, sink.chats.size)

        val missingRecipient = message.copy(
            messageId = "transport-message-no-recipient",
            recipientUid = null,
        )
        assertEquals(
            TransportResultCode.INVALID_REQUEST,
            host.receiveMessage(sessionId, "satellite", missingRecipient).code,
        )
        assertEquals(1, sink.chats.size)

        val unknownSourceTime = message.copy(
            messageId = "transport-message-unknown-time",
            sentAtEpochMillis = null,
        )
        assertEquals(
            TransportResultCode.ACCEPTED,
            host.receiveMessage(sessionId, "satellite", unknownSourceTime).code,
        )
        assertNull(sink.chats.last().parsedRooms!!.single().messageList.single().sentAtEpochMillis)

        val oversized = message.copy(
            messageId = "transport-message-too-large",
            payload = ByteArray(501),
        )
        assertEquals(
            TransportResultCode.LIMIT_EXCEEDED,
            host.receiveMessage(sessionId, "satellite", oversized).code,
        )
        assertEquals(2, sink.chats.size)
    }

    @Test
    fun failedInboundIngestionCanRetrySameMessageId() {
        val attempts = AtomicInteger()
        val host = createHost {
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("temporary ingress failure")
            TakServerCotIngestResult.CHAT
        }
        val sessionId = register(host, RecordingCallback())
        val message = inboundMessage("retry-after-ingress-failure")

        assertEquals(
            TransportResultCode.UNAVAILABLE,
            host.receiveMessage(sessionId, "satellite", message).code,
        )
        assertEquals(
            TransportResultCode.ACCEPTED,
            host.receiveMessage(sessionId, "satellite", message).code,
        )
        assertEquals(
            TransportResultCode.DUPLICATE,
            host.receiveMessage(sessionId, "satellite", message).code,
        )
        assertEquals(2, attempts.get())
    }

    @Test
    fun failedInboundNormalizationCanRetrySameMessageId() {
        val host = createHost { TakServerCotIngestResult.CHAT }
        val sessionId = register(host, RecordingCallback())
        val invalidChat = TransportMessageParcel(
            messageId = "retry-after-normalization-failure",
            payloadKind = TransportPayloadKind.CHAT_UTF8,
            payload = "Hello".toByteArray(Charsets.UTF_8),
            senderUid = "remote-1",
            senderCallsign = "REMOTE",
        )

        assertEquals(
            TransportResultCode.INVALID_REQUEST,
            host.receiveMessage(sessionId, "satellite", invalidChat).code,
        )
        val validChat = invalidChat.copy(recipientUid = DEVICE_UID)
        assertEquals(
            TransportResultCode.ACCEPTED,
            host.receiveMessage(sessionId, "satellite", validChat).code,
        )
        assertEquals(
            TransportResultCode.DUPLICATE,
            host.receiveMessage(sessionId, "satellite", validChat).code,
        )
    }

    @Test
    fun cancelledInboundIngestionReleasesMessageReservation() {
        val attempts = AtomicInteger()
        val host = createHost {
            if (attempts.incrementAndGet() == 1) throw CancellationException("ingress cancelled")
            TakServerCotIngestResult.CHAT
        }
        val sessionId = register(host, RecordingCallback())
        val message = inboundMessage("retry-after-ingress-cancellation")
        var cancelled = false

        try {
            host.receiveMessage(sessionId, "satellite", message)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(
            TransportResultCode.ACCEPTED,
            host.receiveMessage(sessionId, "satellite", message).code,
        )
        assertEquals(2, attempts.get())
    }

    @Test
    fun concurrentInboundDuplicateIsRejectedUntilOriginalCompletes() {
        val enteredIngest = CountDownLatch(1)
        val releaseIngest = CountDownLatch(1)
        val host = createHost {
            enteredIngest.countDown()
            check(releaseIngest.await(3, TimeUnit.SECONDS))
            TakServerCotIngestResult.CHAT
        }

        val sessionId = register(host, RecordingCallback())
        val message = inboundMessage("concurrent-inbound-message")
        val executor = Executors.newSingleThreadExecutor()

        try {
            val first = executor.submit<TransportResultParcel> {
                host.receiveMessage(sessionId, "satellite", message)
            }
            assertTrue(enteredIngest.await(3, TimeUnit.SECONDS))
            assertEquals(
                TransportResultCode.DUPLICATE,
                host.receiveMessage(sessionId, "satellite", message).code,
            )
            releaseIngest.countDown()
            assertEquals(TransportResultCode.ACCEPTED, first.get(3, TimeUnit.SECONDS).code)
        } finally {
            releaseIngest.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun disablingTrafficDrainsInboundEffectsAndRejectsOldBindingAfterReenable() {
        val fence = DittoTrafficFence(initiallyEnabled = true)
        val generation = requireNotNull(fence.currentGeneration())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val effects = AtomicInteger()
        val host = PluginTransportHostV1Impl(
            app, ingress, PLUGIN_ID, Process.myUid(),
            trafficAdmission = { fence.enter(generation) },
            cotIngest = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                effects.incrementAndGet()
                TakServerCotIngestResult.CHAT
            },
        ).also(hosts::add)
        val session = register(host, RecordingCallback())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val inFlight = executor.submit<TransportResultParcel> {
                host.receiveMessage(session, "satellite", inboundMessage("before-disable"))
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val drain = fence.disable()
            assertFalse(drain.isCompleted)
            assertEquals(TransportResultCode.INACTIVE_SESSION,
                host.receiveMessage(session, "satellite", inboundMessage("while-disabled")).code)
            release.countDown()
            assertEquals(TransportResultCode.ACCEPTED, inFlight.get(3, TimeUnit.SECONDS).code)
            assertTrue(drain.isCompleted)
            assertEquals(1, effects.get())
            assertNotNull(fence.enable())
            assertEquals(TransportResultCode.INACTIVE_SESSION,
                host.receiveMessage(session, "satellite", inboundMessage("stale-binding")).code)
            assertEquals(1, effects.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun disablingTrafficRejectsOutboundOffersFromOldBindingAfterReenable() {
        val fence = DittoTrafficFence(initiallyEnabled = true)
        val generation = requireNotNull(fence.currentGeneration())
        val callback = RecordingCallback()
        val host = PluginTransportHostV1Impl(
            app, ingress, PLUGIN_ID, Process.myUid(),
            trafficAdmission = { fence.enter(generation) },
            cotIngest = { TakServerCotIngestResult.CHAT },
        ).also(hosts::add)
        register(host, callback)
        fence.disable()
        host.offerCoT(chatXml(), DEVICE_UID, "WATCH")
        assertNull(callback.request)
        assertNotNull(fence.enable())
        host.offerCoT(chatXml(), DEVICE_UID, "WATCH")
        assertNull(callback.request)
    }

    @Test
    fun unregisterDuringInboundIngestionFencesLateAdmission() {
        val enteredIngest = CountDownLatch(1)
        val releaseIngest = CountDownLatch(1)
        val host = createHost {
            enteredIngest.countDown()
            check(releaseIngest.await(3, TimeUnit.SECONDS))
            TakServerCotIngestResult.CHAT
        }
        val sessionId = register(host, RecordingCallback())
        val message = inboundMessage("inbound-during-unregister")
        val executor = Executors.newSingleThreadExecutor()

        try {
            val inFlight = executor.submit<TransportResultParcel> {
                host.receiveMessage(sessionId, "satellite", message)
            }
            assertTrue(enteredIngest.await(3, TimeUnit.SECONDS))
            assertEquals(TransportResultCode.ACCEPTED, host.unregisterTransport(sessionId).code)
            releaseIngest.countDown()
            assertEquals(TransportResultCode.INACTIVE_SESSION, inFlight.get(3, TimeUnit.SECONDS).code)
            assertEquals(
                TransportResultCode.INACTIVE_SESSION,
                host.receiveMessage(sessionId, "satellite", message).code,
            )
        } finally {
            releaseIngest.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun rawCotPayloadUsesHostParserAndPluginOriginDoesNotEcho() {
        val sink = RecordingSink()
        val ingestor = TakServerCotIngestor(
            sink = sink,
            isChatEnabled = { true },
            isGeofenceEnabled = { false },
            deviceUid = { DEVICE_UID },
            deviceCallsign = { "WATCH" },
        )
        val host = createHost { envelope -> ingestor.ingest(envelope) }
        val callback = RecordingCallback()
        val sessionId = register(host, callback)
        val rawChat = PluginTransportMessageMapper.fromCot(
            xml = chatXml(),
            senderUid = "remote-1",
            senderCallsign = "REMOTE",
            supportedKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
        )!!.copy(senderUid = "transport-peer-not-event-author", senderCallsign = "BEARER")
        assertEquals(TransportPayloadKind.COT_XML_UTF8, rawChat.payloadKind)

        assertEquals(
            TransportResultCode.ACCEPTED,
            host.receiveMessage(sessionId, "radio", rawChat).code,
        )
        assertEquals(1, sink.chats.size)
        val received = sink.chats.single().parsedRooms!!.single().messageList.single()
        assertEquals("transport-peer-not-event-author", received.transportSenderUid)
        assertEquals("BEARER", received.transportSenderCallsign)
        assertNotEquals(received.uid, received.transportSenderUid)
        assertEquals(
            "Hello raw CoT",
            sink.chats.single().parsedRooms!!.single().messageList.single().message,
        )

        host.offerInboundCoT(
            TakServerCotEnvelope(
                sourceServerId = "plugin:already-here",
                sourceServerAddress = "plugin:already-here",
                receivedAt = 1L,
                rawXml = chatXml(),
            ),
        )
        assertNull(callback.request)
    }

    @Test
    fun outboundRawCotUsesOnlyNegotiatedKindAndFirstChannel() = runTest {
        val host = PluginTransportHostV1Impl(
            app, ingress, PLUGIN_ID, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler),
            cotIngest = { TakServerCotIngestResult.UNSUPPORTED },
        ).also(hosts::add)
        val callback = RecordingCallback()
        val sessionId = register(
            host, callback,
            capabilities().copy(
                outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
                inboundPayloadKinds = emptyList(),
                channelIds = listOf("radio", "satellite"),
            ),
        )
        val xml = "<event uid='position-1' type='a-f-G'><point lat='1' lon='2'/></event>"
        host.offerCoT(xml, DEVICE_UID, "WATCH")
        runCurrent()
        val request = callback.request!!
        assertEquals(sessionId, request.sessionId)
        assertEquals("radio", request.channelId)
        assertEquals(TransportPayloadKind.COT_XML_UTF8, request.message.payloadKind)
        assertEquals(xml, request.message.payload.toString(Charsets.UTF_8))
        assertNull(host.latestDeliveryForTest(request.message.messageId))
        assertEquals(
            TransportResultCode.INVALID_REQUEST,
            host.reportDelivery(
                sessionId,
                TransportDeliveryParcel(
                    request.message.messageId, "satellite", 1L, TransportDeliveryStage.QUEUED,
                ),
            ).code,
        )
        assertEquals(
            TransportResultCode.INVALID_REQUEST,
            host.reportDelivery(
                sessionId,
                TransportDeliveryParcel(
                    request.message.messageId, "radio", Long.MAX_VALUE, TransportDeliveryStage.QUEUED,
                ),
            ).code,
        )
        host.closeBinding()
        runCurrent()
    }

    @Test
    fun pluginOnlyPliSubmissionIsLocalTerminalAndGenericCotCannotEnterChannel() = runTest {
        var now = 100L
        var trusted = true
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            clock = { now }, dispatcher = StandardTestDispatcher(testScheduler),
            pluginOnlyPli = true, trustedDittoPlugin = { trusted }, onPliUnavailable = {},
        ).also(hosts::add)
        val callback = RecordingCallback()
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = emptyList(),
            channelIds = listOf(DittoPliTransportSession.CHANNEL),
            maxPendingMessages = 1,
            deviceReceipts = false,
            recipientReceipts = false,
        )
        val sessionId = register(host, callback, caps)
        val xml = "<event uid='watch-1' type='a-f-G'><point lat='1' lon='2'/></event>"
        host.offerCoT(xml, DEVICE_UID, "WATCH")
        runCurrent()
        assertNull(callback.request)
        host.offerPluginPli(pliSnapshot()) { true }
        runCurrent()
        val first = requireNotNull(callback.request)
        assertEquals(DittoPliTransportSession.CHANNEL, first.channelId)
        val submitted = TransportDeliveryParcel(first.message.messageId, first.channelId, 1,
            TransportDeliveryStage.CHANNEL_SUBMITTED)
        assertEquals(TransportResultCode.ACCEPTED, host.reportDelivery(sessionId, submitted).code)
        assertEquals(TransportResultCode.STALE_UPDATE, host.reportDelivery(sessionId, submitted).code)
        assertEquals(TransportDeliveryStage.CHANNEL_SUBMITTED,
            host.latestDeliveryForTest(first.message.messageId)?.stage)
        var eligible = true
        host.offerPluginPli(pliSnapshot()) { eligible }
        eligible = false
        runCurrent()
        assertEquals(first.message.messageId, callback.request?.message?.messageId)
        host.offerPluginPli(pliSnapshot()) { true }
        runCurrent()
        val second = requireNotNull(callback.request)
        assertNotEquals(first.message.messageId, second.message.messageId)
        trusted = false
        assertEquals(TransportResultCode.UNAUTHORIZED,
            host.reportDelivery(sessionId, submitted.copy(messageId = second.message.messageId)).code)
        trusted = true
        now += 60_000
        host.expirePendingMessages()
        assertEquals(TransportDeliveryStage.CHANNEL_SUBMITTED,
            host.latestDeliveryForTest(first.message.messageId)?.stage)
        assertEquals(TransportDeliveryStage.FAILED, host.latestDeliveryForTest(second.message.messageId)?.stage)
        host.closeBinding()
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.reportDelivery(sessionId, submitted).code)
        runCurrent()
    }

    @Test
    fun pluginOnlyProfileRequiresExactPackageSignerAndNoReceipts() {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        var trusted = false
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            pluginOnlyPli = true, trustedDittoPlugin = { trusted }, onPliUnavailable = {},
        ).also(hosts::add)
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = emptyList(),
            channelIds = listOf(DittoPliTransportSession.CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        assertEquals(TransportResultCode.UNSUPPORTED, host.registerTransport(caps, RecordingCallback()).code)
        trusted = true
        assertEquals(TransportResultCode.UNSUPPORTED,
            host.registerTransport(caps.copy(deviceReceipts = true), RecordingCallback()).code)
        assertEquals(TransportResultCode.UNSUPPORTED,
            host.registerTransport(caps.copy(channelIds = listOf(DittoPliTransportSession.CHANNEL, "other")),
                RecordingCallback()).code)
    }

    @Test
    fun trackedDittoSubmissionWaitsForLocalSubmissionAndFencesDisconnect() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = {},
        ).also(hosts::add)
        val callback = RecordingCallback()
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        val session = register(host, callback, caps)
        val first = async { host.submitDittoCoT(chatXml(), DEVICE_UID, "WATCH", "stable-id") }
        runCurrent()
        assertFalse(first.isCompleted)
        val request = requireNotNull(callback.request)
        assertEquals("stable-id", request.message.messageId)
        assertEquals(TransportResultCode.ACCEPTED, host.reportDelivery(session,
            TransportDeliveryParcel("stable-id", request.channelId, 1, TransportDeliveryStage.CHANNEL_SUBMITTED)).code)
        assertEquals(PluginTransportHostV1Impl.Submission.SUBMITTED, first.await())
        val second = async { host.submitDittoCoT(chatXml(), DEVICE_UID, "WATCH", "next-id") }
        runCurrent()
        host.closeBinding()
        assertEquals(PluginTransportHostV1Impl.Submission.UNCERTAIN, second.await())
        assertEquals(PluginTransportHostV1Impl.Submission.NOT_ADMITTED,
            host.submitDittoCoT(chatXml(), DEVICE_UID, "WATCH", "third-id"))
    }

    @Test
    fun queuedTrackedAlertIsNotSentAfterEligibilityIsRevoked() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = {},
        ).also(hosts::add)
        val callback = RecordingCallback()
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        register(host, callback, caps)
        var eligible = true
        val awaitResult = host.offerTrackedDittoCoT(chatXml(), DEVICE_UID, "WATCH", "cancelled-id") { eligible }
        eligible = false
        runCurrent()
        assertNull(callback.request)
        assertEquals(PluginTransportHostV1Impl.Submission.CANCELLED_BEFORE_EFFECT, awaitResult())
    }

    @Test
    fun trackedAdmissionRejectsDifferentTransportSession() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = {},
        ).also(hosts::add)
        val callback = RecordingCallback()
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        val session = register(host, callback, caps)
        assertEquals(session, host.activeGeneralDittoSession())
        val receipt = host.offerTrackedDittoCoT(chatXml(), DEVICE_UID, "WATCH", "stale-id", "old-session")
        assertEquals(PluginTransportHostV1Impl.Submission.NOT_ADMITTED, receipt())
        runCurrent()
        assertNull(callback.request)
    }

    @Test
    fun gatedGeneralDittoChannelCarriesCotAndLocalSubmissionIsTerminal() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        var trusted = true
        val received = mutableListOf<TakServerCotEnvelope>()
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { trusted }, onPliUnavailable = {},
            cotIngest = { received.add(it); TakServerCotIngestResult.CHAT },
        ).also(hosts::add)
        val callback = RecordingCallback()
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            maxPendingMessages = 1, deviceReceipts = false, recipientReceipts = false,
        )
        val session = register(host, callback, caps)
        val outbound = chatXml()
        host.offerCoT(outbound, DEVICE_UID, "WATCH")
        runCurrent()
        val first = requireNotNull(callback.request)
        assertEquals(outbound, first.message.payload.toString(Charsets.UTF_8))
        assertEquals(DEVICE_UID, first.message.senderUid)
        assertEquals("WATCH", first.message.senderCallsign)
        assertEquals(TransportResultCode.ACCEPTED, host.reportDelivery(session,
            TransportDeliveryParcel(first.message.messageId, first.channelId, 1,
                TransportDeliveryStage.CHANNEL_SUBMITTED)).code)
        host.offerCoT(outbound, DEVICE_UID, "WATCH")
        runCurrent()
        assertNotEquals(first.message.messageId, callback.request?.message?.messageId)

        val inbound = TransportMessageParcel(
            messageId = "synthetic-inbound", payloadKind = TransportPayloadKind.COT_XML_UTF8,
            payload = outbound.toByteArray(Charsets.UTF_8), senderUid = "peer-1",
        )
        trusted = false
        assertEquals(TransportResultCode.UNAUTHORIZED,
            host.receiveMessage(session, DittoPliTransportSession.GENERAL_CHANNEL, inbound).code)
        assertTrue(received.isEmpty())
        trusted = true
        assertEquals(TransportResultCode.ACCEPTED,
            host.receiveMessage(session, DittoPliTransportSession.GENERAL_CHANNEL, inbound).code)
        assertEquals(outbound, received.single().rawXml)
        assertEquals(TransportResultCode.DUPLICATE,
            host.receiveMessage(session, DittoPliTransportSession.GENERAL_CHANNEL, inbound).code)
        host.closeBinding()
        runCurrent()
    }

    @Test
    fun gatedGeneralDittoChannelUsesOnlyEligibleSnapshotForPli() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = {},
        ).also(hosts::add)
        val callback = RecordingCallback()
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        register(host, callback, caps)
        val snapshot = pliSnapshot()
        host.offerCoT(snapshot.toPluginPli().xml, DEVICE_UID, "WATCH")
        runCurrent()
        assertNull(callback.request)
        host.offerInboundCoT(
            TakServerCotEnvelope(
                sourceServerId = "tak-server",
                sourceServerAddress = "tak-server",
                receivedAt = 1L,
                rawXml = snapshot.toPluginPli().xml,
            ),
        )
        runCurrent()
        assertNotNull(callback.request)
        callback.request = null
        assertTrue(host.offerPluginPli(snapshot) { true })
        runCurrent()
        assertEquals(DittoPliTransportSession.GENERAL_CHANNEL, callback.request?.channelId)
    }

    @Test
    fun gatedGeneralDittoChannelRequiresExactProfile() {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            pluginOnlyPli = true, trustedDittoPlugin = { true }, onPliUnavailable = {},
        ).also(hosts::add)
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        assertEquals(TransportResultCode.UNSUPPORTED,
            host.registerTransport(caps.copy(inboundPayloadKinds = emptyList()), RecordingCallback()).code)
        assertEquals(TransportResultCode.UNSUPPORTED,
            host.registerTransport(caps.copy(recipientReceipts = true), RecordingCallback()).code)
    }

    @Test
    fun gatedDittoTransportReplacementFencesInsteadOfRotating() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val reasons = mutableListOf<String>()
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = { reasons.add(it) },
        ).also(hosts::add)
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        register(host, RecordingCallback(), caps)
        assertEquals(TransportResultCode.UNAVAILABLE, host.registerTransport(caps, RecordingCallback()).code)
        assertEquals(listOf(PluginTransportHostV1Impl.PLI_UNAVAILABLE), reasons)
        assertFalse(host.isTransportRegistered())
        runCurrent()
    }

    @Test
    fun gatedDittoUnregisterFencesBindingInsteadOfAllowingRotation() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val reasons = mutableListOf<String>()
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = { reasons.add(it) },
        ).also(hosts::add)
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        val session = register(host, RecordingCallback(), caps)
        assertEquals(TransportResultCode.ACCEPTED, host.unregisterTransport(session).code)
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.registerTransport(caps, RecordingCallback()).code)
        assertEquals(listOf(PluginTransportHostV1Impl.PLI_UNAVAILABLE), reasons)
        runCurrent()
    }

    @Test
    fun pluginOnlyUnregisterRetiresSessionWithoutClosingBinding() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val reasons = mutableListOf<String>()
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            restartablePluginOnly = true, trustedDittoPlugin = { true },
            onPliUnavailable = { reasons.add(it) },
        ).also(hosts::add)
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        val callback = RecordingCallback()
        val old = register(host, callback, caps)
        val pending = async { host.submitDittoCoT(chatXml(), DEVICE_UID, "WATCH", "pending-id") }
        runCurrent()
        assertNotNull(callback.request)
        assertEquals(TransportResultCode.ACCEPTED, host.unregisterTransport(old).code)
        assertEquals(PluginTransportHostV1Impl.Submission.UNCERTAIN, pending.await())
        assertFalse(host.isActiveDittoSession(old))
        val next = register(host, RecordingCallback(), caps)
        assertNotEquals(old, next)
        assertTrue(host.isActiveDittoSession(next))
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.unregisterTransport(old).code)
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.reportDelivery(old,
            TransportDeliveryParcel("pending-id", DittoPliTransportSession.GENERAL_CHANNEL, 1,
                TransportDeliveryStage.CHANNEL_SUBMITTED)).code)
        assertTrue(host.isActiveDittoSession(next))
        assertTrue(reasons.isEmpty())
        runCurrent()
    }

    @Test
    fun gatedDittoCallbackDeathFencesBindingInsteadOfAllowingRotation() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val reasons = mutableListOf<String>()
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = { reasons.add(it) },
        ).also(hosts::add)
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        var deathRecipient: IBinder.DeathRecipient? = null
        val binder = mockk<IBinder>(relaxed = true)
        every { binder.isBinderAlive } returns true
        every { binder.linkToDeath(any(), any()) } answers { deathRecipient = firstArg() }
        val callback = object : RecordingCallback() {
            override fun asBinder(): IBinder = binder
        }
        register(host, callback, caps)
        requireNotNull(deathRecipient).binderDied()
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.registerTransport(caps, RecordingCallback()).code)
        assertEquals(listOf(PluginTransportHostV1Impl.PLI_UNAVAILABLE), reasons)
        assertFalse(host.isTransportRegistered())
        runCurrent()
    }

    @Test
    fun gatedGeneralDittoInboundExhaustionClosesInsteadOfRotating() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val reasons = mutableListOf<String>()
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = { reasons.add(it) },
            cotIngest = { TakServerCotIngestResult.CHAT },
        ).also(hosts::add)
        val caps = capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            channelIds = listOf(DittoPliTransportSession.GENERAL_CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        )
        val session = register(host, RecordingCallback(), caps)
        repeat(TransportContract.MAX_SESSION_MESSAGES) { index ->
            val message = TransportMessageParcel(
                messageId = "inbound-$index", payloadKind = TransportPayloadKind.COT_XML_UTF8,
                payload = chatXml().toByteArray(Charsets.UTF_8), senderUid = "peer-1",
            )
            assertEquals(TransportResultCode.ACCEPTED,
                host.receiveMessage(session, DittoPliTransportSession.GENERAL_CHANNEL, message).code)
        }
        val overflow = TransportMessageParcel(
            messageId = "inbound-overflow", payloadKind = TransportPayloadKind.COT_XML_UTF8,
            payload = chatXml().toByteArray(Charsets.UTF_8), senderUid = "peer-1",
        )
        assertEquals(TransportResultCode.LIMIT_EXCEEDED,
            host.receiveMessage(session, DittoPliTransportSession.GENERAL_CHANNEL, overflow).code)
        assertEquals(listOf(PluginTransportHostV1Impl.PLI_EXHAUSTED), reasons)
        assertFalse(host.isTransportRegistered())
        runCurrent()
    }

    @Test
    fun withoutPluginOnlyGateNoReceiptSubmissionKeepsLegacyExpiry() = runTest {
        var now = 100L
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            clock = { now }, dispatcher = StandardTestDispatcher(testScheduler),
        ).also(hosts::add)
        val callback = RecordingCallback()
        val session = register(host, callback, capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = emptyList(),
            channelIds = listOf(DittoPliTransportSession.CHANNEL),
            deviceReceipts = false, recipientReceipts = false,
        ))
        host.offerPluginPli(pliSnapshot()) { true }
        runCurrent()
        assertNull(callback.request)
        host.offerCoT("<event uid='watch-1' type='a-f-G'/>", DEVICE_UID, "WATCH")
        runCurrent()
        val request = requireNotNull(callback.request)
        assertEquals(TransportResultCode.ACCEPTED, host.reportDelivery(session,
            TransportDeliveryParcel(request.message.messageId, request.channelId, 1,
                TransportDeliveryStage.CHANNEL_SUBMITTED)).code)
        now += 60_000
        host.expirePendingMessages()
        assertEquals(TransportDeliveryStage.FAILED, host.latestDeliveryForTest(request.message.messageId)?.stage)
        host.closeBinding()
        runCurrent()
    }

    @Test
    fun pluginOnlySessionExhaustionClosesBindingAndReportsExhaustionOnce() = runTest {
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        val unavailable = mutableListOf<String>()
        val host = PluginTransportHostV1Impl(
            app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler), pluginOnlyPli = true,
            trustedDittoPlugin = { true }, onPliUnavailable = { unavailable.add(it) },
        ).also(hosts::add)
        val callback = RecordingCallback()
        val session = register(host, callback, capabilities().copy(
            outboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            inboundPayloadKinds = emptyList(),
            channelIds = listOf(DittoPliTransportSession.CHANNEL),
            maxPendingMessages = 1, deviceReceipts = false, recipientReceipts = false,
        ))
        repeat(TransportContract.MAX_SESSION_MESSAGES) {
            host.offerPluginPli(pliSnapshot()) { true }
            runCurrent()
            val request = requireNotNull(callback.request)
            assertEquals(TransportResultCode.ACCEPTED, host.reportDelivery(session,
                TransportDeliveryParcel(request.message.messageId, request.channelId, 1,
                    TransportDeliveryStage.CHANNEL_SUBMITTED)).code)
        }
        host.offerPluginPli(pliSnapshot()) { true }
        host.offerPluginPli(pliSnapshot()) { true }
        runCurrent()
        assertEquals(listOf(PluginTransportHostV1Impl.PLI_EXHAUSTED), unavailable)
        assertFalse(host.isTransportRegistered())
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.reportDelivery(session,
            TransportDeliveryParcel("old", DittoPliTransportSession.CHANNEL, 1,
                TransportDeliveryStage.CHANNEL_SUBMITTED)).code)
        assertEquals(TransportResultCode.INACTIVE_SESSION, host.registerTransport(capabilities(), callback).code)
        assertTrue(callback.sessionClosedLatch.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun callbackAcceptanceIsNotDeliveryAndReceiptsAreCorrelatedAndMonotonic() {
        val host = createHost { TakServerCotIngestResult.UNSUPPORTED }
        val callback = RecordingCallback()
        val sessionId = register(host, callback)

        host.offerCoT(chatXml(), senderUid = DEVICE_UID, senderCallsign = "WATCH")

        assertTrue(callback.sendLatch.await(3, TimeUnit.SECONDS))
        val request = callback.request!!
        assertEquals("satellite", request.channelId)
        assertEquals(TransportPayloadKind.CHAT_UTF8, request.message.payloadKind)
        assertNull(host.latestDeliveryForTest(request.message.messageId))

        val accepted = host.reportDelivery(
            sessionId,
            TransportDeliveryParcel(
                messageId = request.message.messageId,
                channelId = request.channelId,
                sequence = 1L,
                stage = TransportDeliveryStage.QUEUED,
            ),
        )
        assertEquals(TransportResultCode.ACCEPTED, accepted.code)
        assertEquals(
            TransportDeliveryStage.QUEUED,
            host.latestDeliveryForTest(request.message.messageId)?.stage,
        )
        assertEquals(
            TransportResultCode.ACCEPTED,
            host.reportDelivery(
                sessionId,
                TransportDeliveryParcel(
                    messageId = request.message.messageId,
                    channelId = request.channelId,
                    sequence = 2L,
                    stage = TransportDeliveryStage.CHANNEL_SUBMITTED,
                ),
            ).code,
        )
        assertEquals(
            TransportResultCode.ACCEPTED,
            host.reportDelivery(
                sessionId,
                TransportDeliveryParcel(
                    messageId = request.message.messageId,
                    channelId = request.channelId,
                    sequence = 3L,
                    stage = TransportDeliveryStage.CHANNEL_DELIVERED,
                ),
            ).code,
        )
        assertEquals(
            TransportDeliveryStage.CHANNEL_DELIVERED,
            host.latestDeliveryForTest(request.message.messageId)?.stage,
        )
        assertEquals(TransportResultCode.ACCEPTED, host.unregisterTransport(sessionId).code)
        assertEquals(
            TransportResultCode.INACTIVE_SESSION,
            host.receiveMessage(
                sessionId,
                "satellite",
                TransportMessageParcel(
                    messageId = "stale-session-message",
                    payloadKind = TransportPayloadKind.COT_XML_UTF8,
                    payload = chatXml().toByteArray(Charsets.UTF_8),
                    senderUid = "remote-1",
                ),
            ).code,
        )
    }

    @Test
    fun registrationRejectsUidAndPackageIdentityMismatch() {
        every { ingress.packagesForUid(app, any()) } returns emptySet()
        val host = createHost { TakServerCotIngestResult.UNSUPPORTED }

        assertEquals(
            TransportResultCode.UNAUTHORIZED,
            host.registerTransport(capabilities(), RecordingCallback()).code,
        )

        every { ingress.packagesForUid(app, any()) } returns setOf(PLUGIN_ID)
        val wrongUidHost = PluginTransportHostV1Impl(
            app = app,
            ingress = ingress,
            pluginId = PLUGIN_ID,
            expectedPluginUid = Process.myUid() + 1,
            cotIngest = { TakServerCotIngestResult.UNSUPPORTED },
        ).also(hosts::add)
        assertEquals(
            TransportResultCode.UNAUTHORIZED,
            wrongUidHost.registerTransport(capabilities(), RecordingCallback()).code,
        )
    }

    @Test
    fun unavailableTransportCannotSendOrReceive() {
        val host = createHost { TakServerCotIngestResult.UNSUPPORTED }
        val callback = RecordingCallback()
        val result = host.registerTransport(
            capabilities().copy(runtimeAvailable = false),
            callback,
        )
        assertEquals(TransportResultCode.ACCEPTED, result.code)
        host.activateBinding()

        assertEquals(
            TransportResultCode.UNAVAILABLE,
            host.receiveMessage(
                result.sessionId,
                "satellite",
                TransportMessageParcel(
                    messageId = "unavailable-message",
                    payloadKind = TransportPayloadKind.COT_XML_UTF8,
                    payload = chatXml().toByteArray(Charsets.UTF_8),
                    senderUid = "remote-1",
                ),
            ).code,
        )
        host.offerCoT(chatXml(), senderUid = DEVICE_UID, senderCallsign = "WATCH")
        assertNull(callback.request)
    }

    @Test
    fun unregisterCancelsPendingWorkAndFencesStaleSession() {
        val host = createHost { TakServerCotIngestResult.UNSUPPORTED }
        val callback = RecordingCallback()
        val sessionId = register(host, callback)
        host.offerCoT(chatXml(), senderUid = DEVICE_UID, senderCallsign = "WATCH")
        assertTrue(callback.sendLatch.await(3, TimeUnit.SECONDS))
        val messageId = callback.request!!.message.messageId

        assertEquals(TransportResultCode.ACCEPTED, host.unregisterTransport(sessionId).code)
        assertTrue(callback.cancelLatch.await(3, TimeUnit.SECONDS))
        assertTrue(callback.sessionClosedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(messageId, callback.cancelledMessageId)
        assertEquals(
            TransportResultCode.INACTIVE_SESSION,
            host.reportDelivery(
                sessionId,
                TransportDeliveryParcel(
                    messageId = messageId,
                    channelId = "satellite",
                    sequence = 1L,
                    stage = TransportDeliveryStage.QUEUED,
                ),
            ).code,
        )
    }

    @Test
    fun callbackRejectionAfterQueuedReceiptRecordsTerminalFailure() = runBlocking {
        val host = createHost { TakServerCotIngestResult.UNSUPPORTED }
        lateinit var sessionId: String
        val callback = object : RecordingCallback() {
            override fun sendMessage(request: TransportSendRequestParcel?): TransportResultParcel {
                val value = requireNotNull(request)
                assertEquals(
                    TransportResultCode.ACCEPTED,
                    host.reportDelivery(
                        sessionId,
                        TransportDeliveryParcel(
                            value.message.messageId, value.channelId, 4L, TransportDeliveryStage.QUEUED,
                        ),
                    ).code,
                )
                super.sendMessage(value)
                return TransportResultParcel(TransportResultCode.UNAVAILABLE)
            }
        }
        sessionId = register(host, callback)
        host.offerCoT(chatXml(), DEVICE_UID, "WATCH")
        withTimeout(3_000L) {
            while (callback.request?.message?.messageId?.let(host::latestDeliveryForTest)?.stage !=
                TransportDeliveryStage.FAILED
            ) delay(10L)
        }
        val failed = host.latestDeliveryForTest(callback.request!!.message.messageId)!!
        assertEquals(5L, failed.sequence)
        assertEquals("callback_rejected", failed.errorCode)
    }

    @Test
    fun nullCallbackResultFailsImmediatelyWithoutFabricatingDelivery() = runTest {
        val host = PluginTransportHostV1Impl(
            app, ingress, PLUGIN_ID, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler),
            cotIngest = { TakServerCotIngestResult.UNSUPPORTED },
        ).also(hosts::add)
        val callback = object : RecordingCallback() {
            override fun sendMessage(request: TransportSendRequestParcel?): TransportResultParcel? {
                super.sendMessage(request)
                return null
            }
        }
        register(host, callback)
        host.offerCoT(chatXml(), DEVICE_UID, "WATCH")
        runCurrent()
        val delivery = host.latestDeliveryForTest(callback.request!!.message.messageId)!!
        assertEquals(TransportDeliveryStage.FAILED, delivery.stage)
        assertEquals("callback_invalid_response", delivery.errorCode)
        host.closeBinding()
        runCurrent()
    }

    @Test
    fun callbackCrashAfterSubmissionFailsButCannotReplaceRecipientDelivery() = runTest {
        for (stage in listOf(TransportDeliveryStage.CHANNEL_SUBMITTED, TransportDeliveryStage.RECIPIENT_DELIVERED)) {
            val host = PluginTransportHostV1Impl(
                app, ingress, PLUGIN_ID, Process.myUid(),
                dispatcher = StandardTestDispatcher(testScheduler),
                cotIngest = { TakServerCotIngestResult.UNSUPPORTED },
            ).also(hosts::add)
            lateinit var sessionId: String
            val callback = object : RecordingCallback() {
                override fun sendMessage(request: TransportSendRequestParcel?): TransportResultParcel {
                    val value = requireNotNull(request)
                    assertEquals(
                        TransportResultCode.ACCEPTED,
                        host.reportDelivery(
                            sessionId,
                            TransportDeliveryParcel(value.message.messageId, value.channelId, 7L, stage),
                        ).code,
                    )
                    super.sendMessage(value)
                    throw IllegalStateException("plugin failure must not become receipt detail")
                }
            }
            sessionId = register(host, callback)
            host.offerCoT(chatXml(), DEVICE_UID, "WATCH")
            runCurrent()
            val messageId = callback.request!!.message.messageId
            if (stage == TransportDeliveryStage.CHANNEL_SUBMITTED) {
                assertEquals(TransportDeliveryStage.FAILED, host.latestDeliveryForTest(messageId)?.stage)
                assertEquals(8L, host.latestDeliveryForTest(messageId)?.sequence)
                assertEquals("callback_error", host.latestDeliveryForTest(messageId)?.errorCode)
            } else {
                assertEquals(TransportDeliveryStage.RECIPIENT_DELIVERED, host.latestDeliveryForTest(messageId)?.stage)
                assertEquals(7L, host.latestDeliveryForTest(messageId)?.sequence)
            }
            host.closeBinding()
            runCurrent()
        }
    }

    @Test
    fun expirationFailsAndCancelsPendingSubmissionAndRejectsLateDelivery() = runBlocking {
        var now = System.currentTimeMillis()
        val host = PluginTransportHostV1Impl(
            app, ingress, PLUGIN_ID, Process.myUid(),
            clock = { now },
            cotIngest = { TakServerCotIngestResult.UNSUPPORTED },
        ).also(hosts::add)
        val callback = RecordingCallback()
        val sessionId = register(host, callback)
        host.offerCoT(chatXml(), DEVICE_UID, "WATCH")
        assertTrue(callback.sendLatch.await(3, TimeUnit.SECONDS))
        val request = callback.request!!
        assertEquals(
            TransportResultCode.ACCEPTED,
            host.reportDelivery(
                sessionId,
                TransportDeliveryParcel(
                    request.message.messageId, request.channelId, 2L,
                    TransportDeliveryStage.CHANNEL_SUBMITTED,
                ),
            ).code,
        )
        now = request.deadlineEpochMillis
        host.expirePendingMessages()
        assertTrue(callback.cancelLatch.await(3, TimeUnit.SECONDS))
        assertEquals(request.message.messageId, callback.cancelledMessageId)
        assertEquals("deadline_expired", host.latestDeliveryForTest(request.message.messageId)?.errorCode)
        assertEquals(
            TransportResultCode.STALE_UPDATE,
            host.reportDelivery(
                sessionId,
                TransportDeliveryParcel(
                    request.message.messageId, request.channelId, 4L,
                    TransportDeliveryStage.RECIPIENT_DELIVERED,
                ),
            ).code,
        )
    }

    @Test
    fun callbackDeathRevokesImmediatelyAndReplacementRejectsOldCorrelation() {
        val host = createHost { TakServerCotIngestResult.UNSUPPORTED }
        var deathRecipient: IBinder.DeathRecipient? = null
        val binder = mockk<IBinder>(relaxed = true)
        every { binder.isBinderAlive } returns true
        every { binder.linkToDeath(any(), any()) } answers {
            deathRecipient = firstArg()
        }
        val callback = mockk<IPluginTransportCallbackV1>()
        every { callback.asBinder() } returns binder
        val first = host.registerTransport(capabilities(), callback)
        assertEquals(TransportResultCode.ACCEPTED, first.code)
        host.activateBinding()
        requireNotNull(deathRecipient).binderDied()
        assertFalse(host.isTransportRegistered())
        assertTrue(host.shouldUseTransport())

        val replacement = host.registerTransport(capabilities(), RecordingCallback())
        assertEquals(TransportResultCode.ACCEPTED, replacement.code)
        assertNotEquals(first.sessionId, replacement.sessionId)
        assertEquals(
            TransportResultCode.INACTIVE_SESSION,
            host.reportDelivery(
                first.sessionId,
                TransportDeliveryParcel("old-message", "satellite", 1L, TransportDeliveryStage.QUEUED),
            ).code,
        )
    }

    @Test
    fun supersededRegistrationLeavesCleanupToItsRemoverExactlyOnce() = runTest {
        val host = PluginTransportHostV1Impl(
            app, ingress, PLUGIN_ID, Process.myUid(),
            dispatcher = StandardTestDispatcher(testScheduler),
            cotIngest = { TakServerCotIngestResult.UNSUPPORTED },
        ).also(hosts::add)
        val originalBinder = mockk<IBinder>(relaxed = true)
        val supersededBinder = mockk<IBinder>(relaxed = true)
        every { originalBinder.isBinderAlive } returns true
        every { supersededBinder.isBinderAlive } returns true
        val original = object : RecordingCallback() {
            override fun asBinder(): IBinder = originalBinder
        }
        val superseded = object : RecordingCallback() {
            override fun asBinder(): IBinder = supersededBinder
        }
        register(host, original)
        var unlinkCount = 0
        every { supersededBinder.unlinkToDeath(any(), any()) } answers {
            unlinkCount++
            true
        }
        var replacement: TransportResultParcel? = null
        every { originalBinder.unlinkToDeath(any(), any()) } answers {
            host.offerCoT(chatXml(), DEVICE_UID, "WATCH")
            runCurrent()
            replacement = host.registerTransport(capabilities(), RecordingCallback())
            true
        }

        assertEquals(
            TransportResultCode.INACTIVE_SESSION,
            host.registerTransport(capabilities(), superseded).code,
        )
        runCurrent()
        assertEquals(TransportResultCode.ACCEPTED, replacement?.code)
        assertEquals(1, unlinkCount)
        assertEquals(superseded.request!!.message.messageId, superseded.cancelledMessageId)
        assertEquals(0L, superseded.sessionClosedLatch.count)
        assertTrue(host.isTransportRegistered())
        host.closeBinding()
        runCurrent()
    }

    private fun createHost(
        cotIngest: suspend (TakServerCotEnvelope) -> TakServerCotIngestResult,
    ): PluginTransportHostV1Impl = PluginTransportHostV1Impl(
        app = app,
        ingress = ingress,
        pluginId = PLUGIN_ID,
        expectedPluginUid = Process.myUid(),
        cotIngest = cotIngest,
    ).also(hosts::add)

    private fun register(
        host: PluginTransportHostV1Impl,
        callback: RecordingCallback,
        caps: TransportCapabilitiesParcel = capabilities(),
    ): String {
        val result = host.registerTransport(caps, callback)
        assertEquals(TransportResultCode.ACCEPTED, result.code)
        assertNotNull(result.sessionId)
        host.activateBinding()
        return result.sessionId!!
    }

    private fun capabilities() = TransportCapabilitiesParcel(
        version = TransportContract.VERSION,
        outboundPayloadKinds = listOf(
            TransportPayloadKind.CHAT_UTF8,
            TransportPayloadKind.COT_XML_UTF8,
        ),
        inboundPayloadKinds = listOf(
            TransportPayloadKind.CHAT_UTF8,
            TransportPayloadKind.COT_XML_UTF8,
        ),
        channelIds = listOf("satellite", "radio"),
        maxPayloadBytes = TransportContract.MAX_PAYLOAD_BYTES,
        maxPendingMessages = TransportContract.MAX_PENDING_MESSAGES,
        runtimeAvailable = true,
        deviceReceipts = true,
        recipientReceipts = true,
    )

    private fun inboundMessage(messageId: String) = TransportMessageParcel(
        messageId = messageId,
        payloadKind = TransportPayloadKind.COT_XML_UTF8,
        payload = chatXml().toByteArray(Charsets.UTF_8),
        senderUid = "remote-1",
    )

    private fun pliSnapshot() = DittoTrackSnapshot(
        eventTime = 20_000L, staleTime = 65_000L,
        location = DataRepository.LocationSnapshot(timeMillis = 20_000L, lat = 1.0, lon = 2.0,
            hae = null, ce = null, le = null, course = null, speed = null),
        uid = DEVICE_UID, callsign = "WATCH", role = "Medic", team = "Blue", battery = 80,
        remarks = "", monitoredPhysio = null, moppGeneration = null, intervalSeconds = 15,
    )

    private open class RecordingCallback : IPluginTransportCallbackV1.Stub() {
        @Volatile
        var request: TransportSendRequestParcel? = null
        val sendLatch = CountDownLatch(1)
        val cancelLatch = CountDownLatch(1)
        val sessionClosedLatch = CountDownLatch(1)
        @Volatile
        var cancelledMessageId: String? = null

        override fun sendMessage(request: TransportSendRequestParcel?): TransportResultParcel? {
            this.request = request
            sendLatch.countDown()
            return TransportResultParcel(TransportResultCode.ACCEPTED, request?.sessionId)
        }

        override fun cancelMessage(sessionId: String?, messageId: String?): TransportResultParcel {
            cancelledMessageId = messageId
            cancelLatch.countDown()
            return TransportResultParcel(TransportResultCode.ACCEPTED, sessionId)
        }

        override fun onSessionClosed(sessionId: String?, reason: Int) {
            sessionClosedLatch.countDown()
        }
    }

    private class RecordingSink : TakServerCotSink {
        val chats = mutableListOf<TakChatEvent>()

        override suspend fun onChat(event: TakChatEvent) {
            chats += event
        }

        override suspend fun onGeofence(geofence: GeofenceCot) = Unit
        override suspend fun onUser(
            event: CotUserPositionEvent,
            envelope: TakServerCotEnvelope,
        ) = Unit

        override suspend fun onMarker(
            event: CotMarkerEvent,
            envelope: TakServerCotEnvelope,
        ) = Unit

        override suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope) = Unit
    }

    private fun chatXml() = """
        <event version="2.0" uid="raw-event-1" type="b-t-f"
            time="2100-03-01T00:00:00.000Z" start="2100-03-01T00:00:00.000Z"
            stale="2100-03-02T00:00:00.000Z" how="h-g-i-g-o">
            <point lat="0" lon="0" hae="0" ce="1" le="1"/>
            <detail>
                <__chat id="$DEVICE_UID" senderCallsign="REMOTE" messageId="raw-external-id">
                    <chatgrp uid0="remote-1" uid1="$DEVICE_UID" id="$DEVICE_UID"/>
                </__chat>
                <remarks to="$DEVICE_UID">Hello raw CoT</remarks>
            </detail>
        </event>
    """.trimIndent()

    private companion object {
        const val PLUGIN_ID = "com.plugin.transport"
        const val DEVICE_UID = "watch-1"
    }
}
