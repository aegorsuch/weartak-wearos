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
import com.aitech.weartak_civ.events.TakChatEvent
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
import java.util.concurrent.TimeUnit
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
