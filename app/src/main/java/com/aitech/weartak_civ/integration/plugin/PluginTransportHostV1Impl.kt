package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.cot.TakServerCotIngestResult
import com.aitech.weartak_civ.service.TAKConnectService
import com.aitech.weartak_civ.service.DittoTrackSnapshot
import com.weartak.plugin.sdk.brokerretirement.v1.BrokerRetirementContractV1 as Retirement
import com.weartak.plugin.sdk.transport.TransportCloseReason
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportDeliveryStage
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.TransportSession
import com.weartak.plugin.sdk.transport.TransportValidator
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportHostV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.UUID

internal class PluginTransportHostV1Impl(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val pluginId: String,
    private val expectedPluginUid: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pluginOnlyPli: Boolean = false,
    private val restartablePluginOnly: Boolean = false,
    private val trustedDittoPlugin: () -> Boolean = { TrustedCommunityPlugin.verify(app, expectedPluginUid) },
    private val preactivationEnabled: Boolean = false,
    private val preactivationTrusted: () -> Boolean = { false },
    private val retirePreactivation: (String) -> Boolean = { false },
    private val onPliUnavailable: ((String) -> Unit)? = null,
    private val trafficAdmission: (() -> AutoCloseable?)? = null,
    private val cotIngest: suspend (TakServerCotEnvelope) -> TakServerCotIngestResult = { envelope ->
        TAKConnectService.handleInboundCotWithResult(
            rawXml = envelope.rawXml,
            source = envelope.sourceServerId,
            sourceSessionId = envelope.sourceSessionId,
            sourceChannelId = envelope.sourceChannelId,
            sourceWorkspaceId = envelope.sourceWorkspaceId,
            sourceExternalId = envelope.sourceExternalId,
            sourceTransportMessageId = envelope.sourceTransportMessageId,
            sourceSentAtEpochMillis = envelope.sourceSentAtEpochMillis,
            sourceSenderUid = envelope.sourceSenderUid,
            sourceSenderCallsign = envelope.sourceSenderCallsign,
        )
    },
) : IPluginTransportHostV1.Stub() {
    private data class Registration(
        val sessionId: String,
        val capabilities: TransportCapabilitiesParcel,
        var session: TransportSession?,
        var pliSession: DittoPliTransportSession?,
        val pliProfile: Boolean,
        val preactivation: Boolean,
        val callback: IPluginTransportCallbackV1,
        val callbackBinder: IBinder,
        val deathRecipient: IBinder.DeathRecipient,
        // Mirror bounded SDK message-ID accounting while ingress IDs are only reserved.
        val inboundMessageIds: MutableSet<String> = LinkedHashSet(),
        val inboundInFlight: MutableSet<String> = LinkedHashSet(),
        val outboundMessageIds: MutableSet<String> = LinkedHashSet(),
        val tracked: MutableMap<String, CompletableDeferred<Submission>> = mutableMapOf(),
    ) {
        fun admitOutbound(request: TransportSendRequestParcel, uid: Int) =
            pliSession?.admitOutbound(request, uid) ?: requireNotNull(session).admitOutbound(request, uid)

        fun delivery(id: String, update: TransportDeliveryParcel, uid: Int) =
            pliSession?.delivery(id, update, uid) ?: requireNotNull(session).delivery(id, update, uid)

        fun expire() = pliSession?.expire() ?: session?.expire().orEmpty()
        fun close(): List<String> {
            val pending = pliSession?.close()
            val legacyPending = session?.close().orEmpty()
            return pending ?: legacyPending
        }
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val deliveryUpdates = LinkedHashMap<String, TransportDeliveryParcel>()
    private var registration: Registration? = null
    private var bindingActive = false
    private var bindingClosed = false
    private var transportNegotiated = false

    override fun getHostCapabilities(): TransportCapabilitiesParcel {
        if (!verifyCaller() || synchronized(lock) { bindingClosed }) {
            return hostCapabilities(runtimeAvailable = false)
        }
        return hostCapabilities(runtimeAvailable = true)
    }

    override fun registerTransport(
        capabilities: TransportCapabilitiesParcel?,
        callback: IPluginTransportCallbackV1?,
    ): TransportResultParcel {
        if (!verifyCaller()) return result(TransportResultCode.UNAUTHORIZED, detail = "Caller UID mismatch")
        if (capabilities == null || callback == null) {
            return result(TransportResultCode.INVALID_REQUEST, detail = "Capabilities and callback are required")
        }
        if (synchronized(lock) { bindingClosed }) {
            return result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin binding is closed")
        }

        val validation = TransportValidator.capabilities(capabilities)
        if (validation.code != TransportResultCode.ACCEPTED) return validation
        val negotiated = negotiate(capabilities)
            ?: return result(TransportResultCode.UNSUPPORTED, detail = "No compatible transport direction or channel")
        val pliProfile = pluginOnlyPli && negotiated.channelIds.any {
            it == DittoPliTransportSession.CHANNEL || it == DittoPliTransportSession.GENERAL_CHANNEL
        }
        if (pliProfile && preactivationEnabled && negotiated.runtimeAvailable) {
            return result(TransportResultCode.UNSUPPORTED, detail = "Ditto preactivation must start unavailable")
        }
        val preactivation = pliProfile && preactivationEnabled && !negotiated.runtimeAvailable
        if (pliProfile && (onPliUnavailable == null || pluginId != TrustedCommunityPlugin.PACKAGE ||
                !(if (preactivation) preactivationTrusted() else trustedDittoPlugin()) ||
                negotiated.channelIds.size != 1 ||
                negotiated.outboundPayloadKinds != listOf(TransportPayloadKind.COT_XML_UTF8) ||
                (if (negotiated.channelIds.single() == DittoPliTransportSession.CHANNEL)
                    negotiated.inboundPayloadKinds.isNotEmpty()
                 else negotiated.inboundPayloadKinds != listOf(TransportPayloadKind.COT_XML_UTF8)) ||
                negotiated.deviceReceipts ||
                negotiated.recipientReceipts)
        ) return result(TransportResultCode.UNSUPPORTED, detail = "Invalid plugin-only PLI profile")
        val sessionId = UUID.randomUUID().toString()
        val session = runCatching {
            if (preactivation) null else TransportSession(sessionId, expectedPluginUid, negotiated, clock)
        }
            .getOrElse {
                Log.w(TAG, "Invalid transport registration pkg=$pluginId", it)
                return result(TransportResultCode.INVALID_REQUEST, detail = "Invalid transport registration")
            }
        val binder = callback.asBinder()
        if (!binder.isBinderAlive) {
            return result(TransportResultCode.UNAVAILABLE, detail = "Transport callback is not alive")
        }
        val recipient = IBinder.DeathRecipient { removeIfCurrent(sessionId, notifyCallback = false) }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (error: RemoteException) {
            Log.w(TAG, "Transport callback death-link failed pkg=$pluginId", error)
            return result(TransportResultCode.UNAVAILABLE, detail = "Transport callback death-link failed")
        }

        val pliSession = if (pliProfile && !preactivation) DittoPliTransportSession(sessionId, expectedPluginUid, negotiated, clock) else null
        val next = Registration(sessionId, negotiated, session, pliSession, pliProfile, preactivation, callback, binder, recipient)
        val (installed, previous, blocked) = synchronized(lock) {
            if (bindingClosed || (preactivation && bindingActive)) {
                Triple(false, null, null)
            } else {
                val old = registration
                if (old != null && (old.pliProfile || pliProfile)) {
                    Triple(false, null, old)
                } else {
                    registration = next
                    transportNegotiated = true
                    deliveryUpdates.clear()
                    Triple(true, old, null)
                }
            }
        }
        if (!installed) {
            unlinkCallback(next)
            next.close()
            if (blocked != null) {
                failPliConnection(blocked)
                return result(TransportResultCode.UNAVAILABLE, detail = "Ditto transport replacement requires safe restart")
            }
            return result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin binding is closed")
        }
        previous?.let { closeRegistration(it, TransportCloseReason.REPLACED, notifyCallback = true) }
        if (!isCurrent(next)) {
            return result(TransportResultCode.INACTIVE_SESSION, detail = "Transport registration was superseded")
        }
        if (!binder.isBinderAlive) {
            removeIfCurrent(sessionId, notifyCallback = false)
            return result(TransportResultCode.UNAVAILABLE, detail = "Transport callback died during registration")
        }
        Log.i(TAG, "Transport registered pkg=$pluginId session=$sessionId channels=${negotiated.channelIds.size}")
        return result(TransportResultCode.ACCEPTED, sessionId = sessionId)
    }

    override fun unregisterTransport(sessionId: String?): TransportResultParcel {
        if (!verifyCaller()) return result(TransportResultCode.UNAUTHORIZED, detail = "Caller UID mismatch")
        val candidate = synchronized(lock) { registration?.takeIf { it.sessionId == sessionId } }
            ?: return result(TransportResultCode.INACTIVE_SESSION, detail = "Transport session is inactive")
        if (candidate.preactivation && !preactivationTrusted()) {
            return result(TransportResultCode.UNAUTHORIZED, detail = "Ditto plugin trust unavailable")
        }
        val current = synchronized(lock) {
            registration?.takeIf { it === candidate && (!it.preactivation || it.callbackBinder.isBinderAlive) }?.also {
                if (it.preactivation) {
                    bindingClosed = true
                    bindingActive = false
                    deliveryUpdates.clear()
                    registration = null
                } else if (!it.pliProfile || restartablePluginOnly) registration = null
            }
        } ?: return result(TransportResultCode.INACTIVE_SESSION, detail = "Transport session is inactive")
        if (current.preactivation) {
            val drained = try {
                retirePreactivation(current.sessionId)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Ditto handler retirement failed", error)
                false
            }
            val confirmed = drained && current.callbackBinder.isBinderAlive && preactivationTrusted()
            finishCloseBinding(current, TransportCloseReason.STOPPED)
            checkNotNull(onPliUnavailable).invoke(PLI_UNAVAILABLE)
            if (!confirmed) {
                return result(TransportResultCode.UNAVAILABLE, detail = "Ditto handler retirement uncertain")
            }
        } else if (current.pliProfile && !restartablePluginOnly) {
            failPliConnection(current)
        } else {
            closeRegistration(current, TransportCloseReason.STOPPED, notifyCallback = true)
        }
        return result(TransportResultCode.ACCEPTED, sessionId = current.sessionId)
    }

    internal fun retireMigrationBrokers(sessionId: String?): Int {
        if (sessionId.isNullOrBlank()) return Retirement.INVALID_ARGUMENT
        if (!verifyCaller() || !preactivationTrusted()) return Retirement.UNAUTHORIZED
        val current = synchronized(lock) {
            registration?.takeIf {
                !bindingClosed && !bindingActive && it.preactivation &&
                    it.sessionId == sessionId && it.callbackBinder.isBinderAlive
            }?.also {
                bindingClosed = true
                bindingActive = false
                deliveryUpdates.clear()
                registration = null
            }
        } ?: return Retirement.INACTIVE_SESSION
        val drained = try {
            retirePreactivation(current.sessionId)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Ditto handler retirement failed", error)
            false
        }
        val confirmed = drained && current.callbackBinder.isBinderAlive && preactivationTrusted()
        closeRegistration(current, TransportCloseReason.STOPPED, notifyCallback = false)
        scope.cancel()
        return if (confirmed) Retirement.RETIRED else Retirement.UNCERTAIN
    }

    override fun receiveMessage(
        sessionId: String?,
        channelId: String?,
        message: TransportMessageParcel?,
    ): TransportResultParcel {
        val admission = trafficAdmission?.invoke()
        if (trafficAdmission != null && admission == null) {
            return result(TransportResultCode.INACTIVE_SESSION, detail = "Ditto network disabled or stale binding")
        }
        return try {
            receiveMessageAdmitted(sessionId, channelId, message)
        } finally {
            admission?.close()
        }
    }

    private fun receiveMessageAdmitted(
        sessionId: String?,
        channelId: String?,
        message: TransportMessageParcel?,
    ): TransportResultParcel {
        if (!verifyCaller()) return result(TransportResultCode.UNAUTHORIZED, detail = "Caller UID mismatch")
        val current = currentSession(sessionId)
            ?: return result(TransportResultCode.INACTIVE_SESSION, detail = "Transport session is inactive")
        if (current.pliProfile && !trustedDittoPlugin()) {
            return result(TransportResultCode.UNAUTHORIZED, detail = "Ditto plugin trust unavailable")
        }
        if (!isBindingActive()) return result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin is not active")
        if (message == null || channelId.isNullOrBlank()) {
            return result(TransportResultCode.INVALID_REQUEST, detail = "Channel and message are required")
        }

        val callingUid = Binder.getCallingUid()
        val sourceMessage = requireNotNull(message)
        var inboundMessage: TransportMessageParcel = sourceMessage
        val reservation = synchronized(lock) {
            if (!isCurrent(current) || !bindingActive) {
                return@synchronized result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin is not active")
            }
            if (!current.capabilities.runtimeAvailable && !(current.preactivation && current.session != null)) {
                return@synchronized result(TransportResultCode.UNAVAILABLE, detail = "Transport unavailable")
            }
            if (channelId !in current.capabilities.channelIds) {
                return@synchronized result(TransportResultCode.UNSUPPORTED, detail = "Unknown channel")
            }
            val validation = TransportValidator.message(sourceMessage, current.capabilities, outbound = false)
            if (validation.code != TransportResultCode.ACCEPTED) return@synchronized validation
            inboundMessage = sourceMessage.copy(payload = sourceMessage.payload.copyOf())
            val snapshotValidation =
                TransportValidator.message(inboundMessage, current.capabilities, outbound = false)
            if (snapshotValidation.code != TransportResultCode.ACCEPTED) {
                return@synchronized snapshotValidation
            }
            val messageId = inboundMessage.messageId
            if (messageId in current.inboundMessageIds ||
                messageId in current.inboundInFlight ||
                messageId in current.outboundMessageIds
            ) {
                return@synchronized result(TransportResultCode.DUPLICATE, detail = "Message already observed")
            }
            if (current.inboundMessageIds.size + current.inboundInFlight.size +
                current.outboundMessageIds.size >= TransportContract.MAX_SESSION_MESSAGES
            ) {
                return@synchronized result(TransportResultCode.LIMIT_EXCEEDED, detail = "Session capacity reached")
            }
            current.inboundInFlight.add(messageId)
            null
        }
        if (reservation != null) {
            if (current.pliSession != null && reservation.code == TransportResultCode.LIMIT_EXCEEDED &&
                synchronized(lock) {
                    current.inboundMessageIds.size + current.inboundInFlight.size +
                        current.outboundMessageIds.size >= TransportContract.MAX_SESSION_MESSAGES
                }
            ) failPliConnection(current, PLI_EXHAUSTED)
            return reservation
        }

        try {
            val xml = when (inboundMessage.payloadKind) {
                TransportPayloadKind.COT_XML_UTF8 -> decodeUtf8(inboundMessage.payload)
                TransportPayloadKind.CHAT_UTF8 -> PluginTransportMessageMapper.chatToCot(inboundMessage)
                else -> null
            } ?: return result(TransportResultCode.INVALID_REQUEST, detail = "Invalid transport message payload")
            if (xml.toByteArray(Charsets.UTF_8).size > HOST_MAX_PAYLOAD_BYTES) {
                return result(TransportResultCode.LIMIT_EXCEEDED, detail = "Normalized CoT exceeds host payload limit")
            }
            val envelope = TakServerCotEnvelope(
                sourceServerId = "plugin:$pluginId",
                sourceServerAddress = "plugin:$pluginId",
                receivedAt = clock(),
                rawXml = xml,
                sourceSessionId = current.sessionId,
                sourceChannelId = channelId,
                sourceWorkspaceId = inboundMessage.workspaceId,
                sourceExternalId = inboundMessage.externalId,
                sourceTransportMessageId = inboundMessage.messageId,
                sourceSentAtEpochMillis = inboundMessage.sentAtEpochMillis,
                sourceSenderUid = inboundMessage.senderUid,
                sourceSenderCallsign = inboundMessage.senderCallsign,
            )
            if (!synchronized(lock) { isCurrent(current) && bindingActive }) {
                return result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin is not active")
            }
            val ingestResult = try {
                runBlocking { cotIngest(envelope) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Transport ingress failed pkg=$pluginId channel=$channelId type=${error.javaClass.simpleName}")
                return result(TransportResultCode.UNAVAILABLE, detail = "Host CoT ingestion failed")
            }
            return when (ingestResult) {
                TakServerCotIngestResult.MALFORMED ->
                    result(TransportResultCode.INVALID_REQUEST, detail = "Malformed CoT payload")
                TakServerCotIngestResult.SUPPRESSED ->
                    result(TransportResultCode.UNAVAILABLE, detail = "Host currently suppresses direct CoT")
                TakServerCotIngestResult.UNSUPPORTED ->
                    result(TransportResultCode.UNSUPPORTED, detail = "Unsupported CoT payload")
                else -> synchronized(lock) {
                    if (!isCurrent(current) || !bindingActive) {
                        result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin is not active")
                    } else {
                        val admitted = requireNotNull(current.session).admitInbound(
                            sessionId = sessionId.orEmpty(),
                            channelId = channelId,
                            message = inboundMessage,
                            callingUid = callingUid,
                        )
                        if (admitted.code == TransportResultCode.ACCEPTED) {
                            current.inboundMessageIds.add(inboundMessage.messageId)
                            result(TransportResultCode.ACCEPTED, sessionId = current.sessionId)
                        } else {
                            admitted
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) {
                current.inboundInFlight.remove(inboundMessage.messageId)
            }
        }
    }

    override fun reportDelivery(
        sessionId: String?,
        delivery: TransportDeliveryParcel?,
    ): TransportResultParcel {
        val admission = trafficAdmission?.invoke()
        if (trafficAdmission != null && admission == null) {
            return result(TransportResultCode.INACTIVE_SESSION, detail = "Ditto network disabled or stale binding")
        }
        return try {
            reportDeliveryAdmitted(sessionId, delivery)
        } finally {
            admission?.close()
        }
    }

    private fun reportDeliveryAdmitted(
        sessionId: String?,
        delivery: TransportDeliveryParcel?,
    ): TransportResultParcel {
        if (!verifyCaller()) return result(TransportResultCode.UNAUTHORIZED, detail = "Caller UID mismatch")
        val current = currentSession(sessionId)
            ?: return result(TransportResultCode.INACTIVE_SESSION, detail = "Transport session is inactive")
        if (current.pliSession != null && !trustedDittoPlugin()) {
            return result(TransportResultCode.UNAUTHORIZED, detail = "Ditto plugin trust unavailable")
        }
        if (!isBindingActive()) return result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin is not active")
        if (delivery == null) return result(TransportResultCode.INVALID_REQUEST, detail = "Delivery update is required")
        val response = synchronized(lock) {
            if (!isCurrent(current) || !bindingActive) {
                return result(TransportResultCode.INACTIVE_SESSION, detail = "Plugin is not active")
            }
            val terminal = delivery.stage == TransportDeliveryStage.FAILED ||
                delivery.stage == TransportDeliveryStage.CANCELLED ||
                delivery.stage == TransportDeliveryStage.RECIPIENT_DELIVERED ||
                (delivery.stage == TransportDeliveryStage.CHANNEL_DELIVERED && !current.capabilities.recipientReceipts) ||
                (current.pliSession != null && delivery.stage == TransportDeliveryStage.CHANNEL_SUBMITTED)
            if (!terminal && delivery.sequence == Long.MAX_VALUE) {
                return result(TransportResultCode.INVALID_REQUEST, detail = "Reserve sequence for terminal delivery")
            }
            current.delivery(sessionId.orEmpty(), delivery, Binder.getCallingUid()).also {
                if (it.code == TransportResultCode.ACCEPTED) recordDelivery(current, delivery)
            }
        }
        if (response.code == TransportResultCode.ACCEPTED) {
            if (delivery.stage == TransportDeliveryStage.FAILED) {
                Log.w(TAG, "Transport delivery failed pkg=$pluginId")
            } else {
                Log.d(TAG, "Transport delivery stage=${delivery.stage} pkg=$pluginId channel=${delivery.channelId}")
            }
        }
        return response
    }

    fun asBinderForInit(): IBinder = asBinder()

    fun isTransportRegistered(): Boolean = synchronized(lock) { registration != null }

    fun shouldUseTransport(): Boolean = synchronized(lock) { transportNegotiated }

    fun activateBinding() {
        synchronized(lock) {
            if (!bindingClosed && registration?.preactivation != true) bindingActive = true
        }
    }

    fun offerCoT(
        xml: String,
        senderUid: String,
        senderCallsign: String?,
        workspaceId: String? = null,
        externalId: String? = null,
        sentAtEpochMillis: Long? = null,
        eligible: (() -> Boolean)? = null,
    ) {
        offerCoTInternal(xml, senderUid, senderCallsign, workspaceId, externalId, sentAtEpochMillis,
            null, false, outboundEligible = eligible)
    }

    internal enum class Submission { NOT_ADMITTED, CANCELLED_BEFORE_EFFECT, SUBMITTED, UNCERTAIN }

    internal suspend fun submitDittoCoT(
        xml: String, senderUid: String, senderCallsign: String?, messageId: String,
    ): Submission = offerTrackedDittoCoT(xml, senderUid, senderCallsign, messageId)()

    internal fun offerTrackedDittoCoT(
        xml: String, senderUid: String, senderCallsign: String?, messageId: String,
        expectedSessionId: String? = null,
        eligible: () -> Boolean = { true },
    ): suspend () -> Submission {
        val completion = CompletableDeferred<Submission>()
        if (!offerCoTInternal(xml, senderUid, senderCallsign, null, null, null, null, false,
                messageId, completion, eligible, expectedSessionId)) return { Submission.NOT_ADMITTED }
        return {
            withTimeoutOrNull(OUTBOUND_DEADLINE_MILLIS + 1_000L) { completion.await() }
                ?: Submission.UNCERTAIN
        }
    }

    internal fun offerPluginPli(snapshot: DittoTrackSnapshot, eligible: () -> Boolean): Boolean {
        if (!eligible()) return false
        return offerCoTInternal(snapshot.toPluginPli().xml, snapshot.uid, snapshot.callsign,
            null, null, snapshot.eventTime, eligible, false)
    }

    private fun offerCoTInternal(
        xml: String,
        senderUid: String,
        senderCallsign: String?,
        workspaceId: String?,
        externalId: String?,
        sentAtEpochMillis: Long?,
        pliEligible: (() -> Boolean)?,
        fromTakServer: Boolean,
        trackedMessageId: String? = null,
        trackedCompletion: CompletableDeferred<Submission>? = null,
        outboundEligible: (() -> Boolean)? = null,
        expectedSessionId: String? = null,
    ): Boolean {
        val admission = trafficAdmission?.invoke()
        if (trafficAdmission != null && admission == null) return false
        return try {
            offerCoTAdmitted(xml, senderUid, senderCallsign, workspaceId, externalId, sentAtEpochMillis,
                pliEligible, fromTakServer, trackedMessageId, trackedCompletion, outboundEligible, expectedSessionId)
        } finally {
            admission?.close()
        }
    }

    private fun offerCoTAdmitted(
        xml: String,
        senderUid: String,
        senderCallsign: String?,
        workspaceId: String?,
        externalId: String?,
        sentAtEpochMillis: Long?,
        pliEligible: (() -> Boolean)?,
        fromTakServer: Boolean,
        trackedMessageId: String?,
        trackedCompletion: CompletableDeferred<Submission>?,
        outboundEligible: (() -> Boolean)?,
        expectedSessionId: String?,
    ): Boolean {
        val current = synchronized(lock) {
            registration?.takeIf { bindingActive && !bindingClosed }
        } ?: return false
        val channel = current.capabilities.channelIds.singleOrNull()
        if (trackedCompletion != null &&
            (current.pliSession == null || channel != DittoPliTransportSession.GENERAL_CHANNEL)) return false
        if (current.pliSession == null && pliEligible != null) return false
        if (current.pliSession != null && channel == DittoPliTransportSession.CHANNEL && pliEligible == null) return false
        if (current.pliSession != null && channel == DittoPliTransportSession.GENERAL_CHANNEL &&
            pliEligible == null && !fromTakServer &&
            CotMessageParser.eventTypeOrNull(xml)?.let(CotMessageParser::isUserPositionType) == true
        ) {
            Log.d(TAG, "Ditto PLI is supplied by the eligible snapshot producer")
            return false
        }
        if (current.pliSession != null && !trustedDittoPlugin()) {
            Log.w(TAG, "Ditto plugin trust unavailable; skip PLI")
            return false
        }
        if (!current.capabilities.runtimeAvailable && !(current.preactivation && current.session != null)) {
            Log.w(TAG, "Transport unavailable pkg=$pluginId; skip outbound CoT")
            return false
        }
        val channelId = current.capabilities.channelIds.firstOrNull()
        if (channelId == null) {
            Log.w(TAG, "No negotiated transport channel pkg=$pluginId")
            return false
        }
        val message = PluginTransportMessageMapper.fromCot(
            xml = xml,
            senderUid = senderUid,
            senderCallsign = senderCallsign,
            supportedKinds = current.capabilities.outboundPayloadKinds,
            workspaceId = workspaceId,
            externalId = externalId,
            sentAtEpochMillis = sentAtEpochMillis,
            messageId = trackedMessageId ?: UUID.randomUUID().toString(),
        ) ?: run {
            Log.w(TAG, "No compatible outbound payload kind pkg=$pluginId")
            return false
        }
        val request = TransportSendRequestParcel(
            sessionId = current.sessionId,
            channelId = channelId,
            message = message,
            deadlineEpochMillis = clock() + OUTBOUND_DEADLINE_MILLIS,
        )
        val admitted = synchronized(lock) {
            if (!isCurrent(current) || !bindingActive ||
                (expectedSessionId != null && current.sessionId != expectedSessionId)) return false
            val messageId = request.message.messageId
            if (messageId in current.inboundMessageIds ||
                messageId in current.inboundInFlight ||
                messageId in current.outboundMessageIds ||
                current.inboundMessageIds.size + current.inboundInFlight.size +
                current.outboundMessageIds.size >= TransportContract.MAX_SESSION_MESSAGES
            ) {
                Log.w(TAG, "Transport outbound rejected pkg=$pluginId due to session capacity or duplicate")
                result(TransportResultCode.LIMIT_EXCEEDED, detail = "Session capacity reached")
            } else {
                current.admitOutbound(request, expectedPluginUid)
                    .also {
                        if (it.code == TransportResultCode.ACCEPTED) {
                            current.outboundMessageIds.add(messageId)
                            if (trackedCompletion != null) current.tracked[messageId] = trackedCompletion
                        }
                    }
            }
        }
        if (admitted.code != TransportResultCode.ACCEPTED) {
            Log.w(TAG, "Transport outbound rejected pkg=$pluginId code=${admitted.code}")
            if (current.pliSession != null && admitted.code == TransportResultCode.LIMIT_EXCEEDED &&
                synchronized(lock) {
                    current.inboundMessageIds.size + current.inboundInFlight.size +
                        current.outboundMessageIds.size >= TransportContract.MAX_SESSION_MESSAGES
                }
            ) {
                failPliConnection(current, PLI_EXHAUSTED)
            }
            return false
        }
        scope.launch {
            delay((request.deadlineEpochMillis - clock()).coerceAtLeast(1L))
            expirePendingMessages()
        }
        scope.launch { dispatchOutbound(current, request, pliEligible, outboundEligible) }
        return true
    }

    fun offerInboundCoT(envelope: TakServerCotEnvelope) {
        if (envelope.sourceServerId.startsWith("plugin:")) return
        offerCoTInternal(
            xml = envelope.rawXml,
            senderUid = envelope.sourceServerId,
            senderCallsign = null,
            workspaceId = envelope.sourceWorkspaceId,
            externalId = envelope.sourceExternalId,
            sentAtEpochMillis = envelope.sourceSentAtEpochMillis,
            pliEligible = null,
            fromTakServer = true,
        )
    }

    fun latestDeliveryForTest(messageId: String): TransportDeliveryParcel? =
        synchronized(lock) { deliveryUpdates[messageId] }

    fun closeBinding(reason: Int = TransportCloseReason.DISCONNECTED) {
        val current = synchronized(lock) {
            if (bindingClosed) return
            bindingClosed = true
            bindingActive = false
            deliveryUpdates.clear()
            registration.also { registration = null }
        }
        finishCloseBinding(current, reason)
    }

    private fun finishCloseBinding(current: Registration?, reason: Int) {
        if (current == null) {
            scope.cancel()
            return
        }
        val pending = current.close()
        synchronized(lock) {
            current.tracked.values.forEach { it.complete(Submission.UNCERTAIN) }
            current.tracked.clear()
        }
        unlinkCallback(current)
        scope.launch {
            pending.forEach { messageId ->
                cancelAdapterMessage(current, messageId)
            }
            runCatching { current.callback.onSessionClosed(current.sessionId, reason) }
                .onFailure { Log.w(TAG, "Transport close callback failed pkg=$pluginId type=${it.javaClass.simpleName}") }
            scope.cancel()
        }
    }

    private fun dispatchOutbound(
        current: Registration,
        request: TransportSendRequestParcel,
        pliEligible: (() -> Boolean)?,
        outboundEligible: (() -> Boolean)?,
    ) {
        val admission = trafficAdmission?.invoke()
        if (trafficAdmission != null && admission == null) {
            synchronized(lock) {
                current.tracked.remove(request.message.messageId)?.complete(Submission.CANCELLED_BEFORE_EFFECT)
            }
            markLocalFailure(current, request, "network_disabled")
            return
        }
        try {
            dispatchOutboundAdmitted(current, request, pliEligible, outboundEligible)
        } finally {
            admission?.close()
        }
    }

    private fun dispatchOutboundAdmitted(
        current: Registration,
        request: TransportSendRequestParcel,
        pliEligible: (() -> Boolean)?,
        outboundEligible: (() -> Boolean)?,
    ) {
        if (!isCurrent(current) || !isBindingActive()) return
        if (pliEligible != null && !pliEligible()) {
            markLocalFailure(current, request, "pli_ineligible")
            return
        }
        if (outboundEligible != null && !outboundEligible()) {
            synchronized(lock) {
                current.tracked.remove(request.message.messageId)?.complete(Submission.CANCELLED_BEFORE_EFFECT)
            }
            markLocalFailure(current, request, "outbound_ineligible")
            return
        }
        if (current.pliSession != null && !trustedDittoPlugin()) {
            markLocalFailure(current, request, "plugin_untrusted")
            return
        }
        if (!isCurrent(current) || !isBindingActive()) return
        val response: TransportResultParcel? = try {
            current.callback.sendMessage(request)
        } catch (error: RemoteException) {
            Log.w(TAG, "Transport callback send failed pkg=$pluginId")
            if (current.pliSession != null) failPliConnection(current)
            else markLocalFailure(current, request, "callback_unavailable")
            return
        } catch (error: RuntimeException) {
            Log.e(TAG, "Transport callback send crashed pkg=$pluginId type=${error.javaClass.simpleName}")
            if (current.pliSession != null) failPliConnection(current)
            else markLocalFailure(current, request, "callback_error")
            return
        }
        if (response == null) {
            Log.w(TAG, "Transport callback returned no result pkg=$pluginId")
            if (current.pliSession != null) failPliConnection(current)
            else markLocalFailure(current, request, "callback_invalid_response")
        } else if (response.code != TransportResultCode.ACCEPTED) {
            Log.w(TAG, "Transport callback rejected pkg=$pluginId code=${response.code}")
            markLocalFailure(current, request, "callback_rejected")
        }
    }

    private fun failPliConnection(current: Registration, reason: String = PLI_UNAVAILABLE) {
        val closed = synchronized(lock) {
            if (registration !== current || bindingClosed) false
            else {
                bindingClosed = true
                bindingActive = false
                deliveryUpdates.clear()
                registration = null
                true
            }
        }
        if (closed) {
            finishCloseBinding(current, TransportCloseReason.DISCONNECTED)
            Log.e(TAG, "Ditto PLI connection unavailable: $reason")
            checkNotNull(onPliUnavailable).invoke(reason)
        }
    }

    internal fun invalidatePluginPli() {
        val (closed, current) = synchronized(lock) {
            if (!pluginOnlyPli || bindingClosed) false to null
            else {
                bindingClosed = true
                bindingActive = false
                deliveryUpdates.clear()
                true to registration.also { registration = null }
            }
        }
        if (closed) {
            finishCloseBinding(current, TransportCloseReason.DISCONNECTED)
            Log.e(TAG, "Ditto PLI connection unavailable: $PLI_UNAVAILABLE")
            checkNotNull(onPliUnavailable).invoke(PLI_UNAVAILABLE)
        }
    }

    private fun markLocalFailure(
        current: Registration,
        request: TransportSendRequestParcel,
        errorCode: String,
    ) {
        synchronized(lock) {
            if (!isCurrent(current)) return
            val previous = deliveryUpdates[request.message.messageId]
            if (previous?.sequence == Long.MAX_VALUE) return
            val failure = TransportDeliveryParcel(
                messageId = request.message.messageId,
                channelId = request.channelId,
                sequence = (previous?.sequence ?: 0L) + 1L,
                stage = TransportDeliveryStage.FAILED,
                errorCode = errorCode,
            )
            val response = current.delivery(current.sessionId, failure, expectedPluginUid)
            if (response.code == TransportResultCode.ACCEPTED) recordDelivery(current, failure)
        }
    }

    internal fun expirePendingMessages() {
        val (current, expired) = synchronized(lock) {
            val current = registration?.takeIf { bindingActive && !bindingClosed } ?: return
            val expired = current.expire()
            expired.forEach { messageId ->
                val last = deliveryUpdates[messageId]
                recordDelivery(
                    current,
                    TransportDeliveryParcel(
                        messageId = messageId,
                        channelId = current.capabilities.channelIds.firstOrNull().orEmpty(),
                        sequence = (last?.sequence ?: 0L) + 1L,
                        stage = TransportDeliveryStage.FAILED,
                        errorCode = "deadline_expired",
                    ),
                )
            }
            current to expired
        }
        expired.forEach { messageId ->
            scope.launch {
                cancelAdapterMessage(current, messageId)
            }
        }
    }

    private fun currentSession(sessionId: String?): Registration? =
        synchronized(lock) { registration?.takeIf { it.sessionId == sessionId } }

    internal fun isPreactivationSession(sessionId: String): Boolean = synchronized(lock) {
        val current = registration
        !bindingClosed && !bindingActive && current?.sessionId == sessionId &&
            current.preactivation && current.session == null &&
            current.callbackBinder.isBinderAlive
    }

    internal fun isActiveDittoSession(sessionId: String): Boolean = synchronized(lock) {
        val current = registration
        !bindingClosed && bindingActive && current?.sessionId == sessionId &&
            current.pliSession != null && current.callbackBinder.isBinderAlive
    }

    internal fun isActiveGeneralDittoTransport(): Boolean = synchronized(lock) {
        val current = registration
        !bindingClosed && bindingActive && current?.pliSession != null &&
            current.capabilities.runtimeAvailable &&
            current.capabilities.channelIds == listOf(DittoPliTransportSession.GENERAL_CHANNEL) &&
            current.callbackBinder.isBinderAlive && trustedDittoPlugin()
    }

    internal fun activeGeneralDittoSession(): String? = synchronized(lock) {
        if (isActiveGeneralDittoTransport()) registration?.sessionId else null
    }

    private fun recordDelivery(current: Registration, delivery: TransportDeliveryParcel) {
        synchronized(lock) {
            if (registration !== current || bindingClosed) return
            val previous = deliveryUpdates[delivery.messageId]
            if (previous != null && previous.sequence >= delivery.sequence) return
            deliveryUpdates[delivery.messageId] = delivery
            if (delivery.stage == TransportDeliveryStage.CHANNEL_SUBMITTED ||
                delivery.stage == TransportDeliveryStage.FAILED ||
                delivery.stage == TransportDeliveryStage.CANCELLED) {
                current.tracked.remove(delivery.messageId)?.complete(
                    if (delivery.stage == TransportDeliveryStage.CHANNEL_SUBMITTED) Submission.SUBMITTED
                    else Submission.UNCERTAIN)
            }
            while (deliveryUpdates.size > MAX_DELIVERY_HISTORY) {
                deliveryUpdates.remove(deliveryUpdates.keys.first())
            }
        }
    }

    private fun isCurrent(current: Registration): Boolean =
        synchronized(lock) { registration === current && !bindingClosed }

    private fun isBindingActive(): Boolean =
        synchronized(lock) { bindingActive && !bindingClosed }

    private fun verifyCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid != expectedPluginUid) {
            Log.w(TAG, "Transport caller UID mismatch pkg=$pluginId expected=$expectedPluginUid actual=$uid")
            return false
        }
        return pluginId in ingress.packagesForUid(app, uid)
    }

    private fun negotiate(plugin: TransportCapabilitiesParcel): TransportCapabilitiesParcel? {
        val host = hostCapabilities(runtimeAvailable = true)
        if (TransportValidator.capabilities(host, host = true).code != TransportResultCode.ACCEPTED) {
            return null
        }
        val outbound = plugin.outboundPayloadKinds.filter { it in host.outboundPayloadKinds }
        val inbound = plugin.inboundPayloadKinds.filter { it in host.inboundPayloadKinds }
        val channels = plugin.channelIds.filter { host.channelIds.isEmpty() || it in host.channelIds }
        if ((outbound.isEmpty() && inbound.isEmpty()) || channels.isEmpty()) return null
        return plugin.copy(
            outboundPayloadKinds = outbound,
            inboundPayloadKinds = inbound,
            channelIds = channels,
            maxPayloadBytes = minOf(plugin.maxPayloadBytes, host.maxPayloadBytes),
            maxPendingMessages = minOf(plugin.maxPendingMessages, host.maxPendingMessages),
            runtimeAvailable = plugin.runtimeAvailable && host.runtimeAvailable,
            deviceReceipts = plugin.deviceReceipts && host.deviceReceipts,
            recipientReceipts = plugin.recipientReceipts && host.recipientReceipts,
        )
    }

    private fun hostCapabilities(runtimeAvailable: Boolean) = TransportCapabilitiesParcel(
        version = TransportContract.VERSION,
        outboundPayloadKinds = listOf(TransportPayloadKind.CHAT_UTF8, TransportPayloadKind.COT_XML_UTF8),
        inboundPayloadKinds = listOf(TransportPayloadKind.CHAT_UTF8, TransportPayloadKind.COT_XML_UTF8),
        channelIds = emptyList(),
        maxPayloadBytes = HOST_MAX_PAYLOAD_BYTES,
        maxPendingMessages = TransportContract.MAX_PENDING_MESSAGES,
        runtimeAvailable = runtimeAvailable,
        deviceReceipts = true,
        recipientReceipts = true,
    )

    private fun closeRegistration(current: Registration, reason: Int, notifyCallback: Boolean) {
        val pending = current.close()
        synchronized(lock) {
            current.tracked.values.forEach { it.complete(Submission.UNCERTAIN) }
            current.tracked.clear()
        }
        unlinkCallback(current)
        if (!notifyCallback) return
        scope.launch {
            pending.forEach { messageId ->
                cancelAdapterMessage(current, messageId)
            }
            runCatching { current.callback.onSessionClosed(current.sessionId, reason) }
                .onFailure { Log.w(TAG, "Transport close callback failed pkg=$pluginId type=${it.javaClass.simpleName}") }
        }
    }

    private fun removeIfCurrent(sessionId: String, notifyCallback: Boolean) {
        val current = synchronized(lock) {
            registration?.takeIf { it.sessionId == sessionId }?.also {
                if (!it.pliProfile) registration = null
            }
        } ?: return
        Log.w(TAG, "Transport callback died pkg=$pluginId")
        if (current.pliProfile) {
            failPliConnection(current)
        } else {
            closeRegistration(current, TransportCloseReason.DISCONNECTED, notifyCallback)
        }
    }

    private fun unlinkCallback(current: Registration) {
        try {
            current.callbackBinder.unlinkToDeath(current.deathRecipient, 0)
        } catch (_: NoSuchElementException) {
            Log.d(TAG, "Transport callback already unlinked pkg=$pluginId")
        }
    }

    private fun cancelAdapterMessage(current: Registration, messageId: String) {
        try {
            val response: TransportResultParcel? = current.callback.cancelMessage(current.sessionId, messageId)
            if (response?.code != TransportResultCode.ACCEPTED) {
                Log.w(TAG, "Transport cancellation rejected pkg=$pluginId code=${response?.code}")
            }
        } catch (_: RemoteException) {
            Log.w(TAG, "Transport cancellation callback unavailable pkg=$pluginId")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Transport cancellation callback failed pkg=$pluginId type=${error.javaClass.simpleName}")
        }
    }

    private fun decodeUtf8(value: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString()
    }.getOrNull()

    private fun result(
        code: Int,
        sessionId: String? = null,
        detail: String? = null,
    ) = TransportResultParcel(code, sessionId, detail)

    companion object {
        internal const val PLI_EXHAUSTED = "ditto_pli_exhausted"
        internal const val PLI_UNAVAILABLE = "ditto_pli_unavailable"
        private const val TAG = "CEP-PluginTransport"
        private const val HOST_MAX_PAYLOAD_BYTES = TransportContract.MAX_PAYLOAD_BYTES
        private const val MAX_DELIVERY_HISTORY = TransportContract.MAX_SESSION_MESSAGES
        private const val OUTBOUND_DEADLINE_MILLIS = 60_000L
    }
}
