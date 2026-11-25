package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import android.util.Log
import com.aitech.weartak_civ.command.SensitiveEnrollment
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.greenrobot.eventbus.EventBus

/** Data-only ingress. No plugin, configuration, command runtime, channel or package callbacks. */
internal class DittoCotReceiver(
    private val repository: DataRepository,
    private val bloodhounds: BloodhoundRepository,
    private val journal: SharedPreferences,
    private val deviceUid: () -> String,
    private val callsign: () -> String,
    private val localPeer: () -> String,
    private val chatCommandHandler: (suspend (com.aitech.weartak_civ.command.ChatCommandRequest) -> Boolean)? = null,
    private val postChat: (TakChatEvent) -> Unit = { EventBus.getDefault().post(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) }
) {
    private val mutex = Mutex()
    private val seen = mutableMapOf<String, Boolean>()
    private val watermarks = journal.all.toMutableMap()
    private var processingSensitiveDocument = false
    private val ingestor = TakServerCotIngestor(
        sink = object : TakServerCotSink {
            override suspend fun onChat(event: TakChatEvent) {
                currentCoroutineContext().ensureActive()
                if (SensitiveEnrollment.containsSensitiveXml(event.rawXml)) return
                postChat(event)
            }
            override suspend fun onGeofence(geofence: GeofenceCot) = Unit
            override suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                if (event.detail.creator?.uid == deviceUid()) return
                repository.applyReceivedUserEvent(event, envelope.sourceServerAddress,
                    envelope.sourceServerId, envelope.receivedAt)
            }
            override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                if (event.detail.creator?.uid == deviceUid() ||
                    repository.dittoPointLedger.owns(event.uid) ||
                    repository.droppedMarkerList.value.any { it.uid == event.uid } ||
                    repository.isDeletedLocalPoint(event.uid)) return
                repository.receiveMarkerEvent(event, bloodhounds, envelope.sourceServerAddress,
                    envelope.sourceServerId, envelope.receivedAt)
            }
            override suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                repository.upsertTakRoute(route)
            }
            override suspend fun onDelete(event: CotDeletion, envelope: TakServerCotEnvelope) {
                repository.receiveCotDeletion(event, envelope.sourceServerId, bloodhounds, envelope.receivedAt)
            }
            override suspend fun onEmergency(event: CotEmergencyEvent, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                // Expired snapshots may advance replay state, never create stale alerts.
                // Cancellations must still clear an older live alert.
                if (!event.cancelled && event.stale <= envelope.receivedAt) {
                    val source = envelope.sourceServerId ?: "remote:${envelope.sourceServerAddress}"
                    bloodhounds.removeMarkerCopy(
                        event.uid, "emergency:$source", event.time, envelope.receivedAt
                    )
                    log("Ignoring expired Ditto emergency snapshot", null)
                    return
                }
                bloodhounds.applyEmergencyEvent(event, envelope.sourceServerAddress,
                    envelope.sourceServerId, envelope.receivedAt, deviceUid())
            }
        },
        isChatEnabled = { repository.getEnableChatService.first() },
        isGeofenceEnabled = { false },
        deviceUid = deviceUid,
        deviceCallsign = callsign,
        chatCommandHandler = chatCommandHandler,
        logger = { message, error ->
            if (processingSensitiveDocument) log("Sensitive Ditto ingestion diagnostic", null)
            else log(message, error)
        }
    )

    suspend fun receive(
        collection: String,
        documents: List<Map<String, Any?>>,
        cleanup: (String, Map<String, Any?>) -> Unit = { _, _ -> }
    ) {
        try {
            receiveDocuments(collection, documents)
        } finally {
            // The entire snapshot must be cleaned, including unvisited documents on cancellation
            // or companion suppression. Native eviction is synchronous and needs no active Job.
            documents.forEach { raw ->
                if (SensitiveEnrollment.containsSensitiveDocument(raw)) {
                    try {
                        cleanup(collection, raw)
                    } catch (_: Exception) {
                        log("Sensitive Ditto cleanup failed; retrying on the next snapshot", null)
                    }
                }
            }
        }
    }

    private suspend fun receiveDocuments(
        collection: String, documents: List<Map<String, Any?>>
    ) = mutex.withLock {
        require(collection in DittoCotDocument.COLLECTIONS)
        for (raw in documents) {
            currentCoroutineContext().ensureActive()
            if (repository.isCompanionConnected.value) {
                log("Suppressing direct Ditto CoT while bonded companion is connected", null)
                return@withLock
            }
            // A hidden/removed document is not a signed deletion instruction.
            val sensitive = SensitiveEnrollment.containsSensitiveDocument(raw)
            try {
                processingSensitiveDocument = sensitive
                if (DittoPointLedger.METADATA in raw) {
                    require(collection == "mapitem") { "WearTAK point contract outside mapitem" }
                    val point = repository.dittoPointIngress.decode(raw)
                    val doc = point.document
                    if (doc.peer == localPeer() || doc.author == deviceUid() ||
                        doc.uid == deviceUid() || repository.dittoPointLedger.owns(doc.uid) ||
                        repository.isDeletedLocalPoint(doc.uid) ||
                        repository.droppedMarkerList.value.any { it.uid == doc.uid }) continue
                    require(doc.time <= now() + MAX_FUTURE_MS) { "Future-dated Ditto point" }
                    if (!repository.dittoPointIngress.accept(point)) continue
                    if (point.deleted) {
                        repository.receiveSourcePointDeletion(
                            doc.uid, doc.sourceId, doc.time, bloodhounds, repository.dittoPointLedger::owns
                        )
                        continue
                    }
                }
                if (raw["_r"] == true || raw["visible"] == false || raw["f"] == false) continue
                val doc = if (collection == "chat") {
                    val members = repository.chatRoomsFlow.value.firstOrNull {
                        it.uid == raw["roomId"] && it.isGroup
                    }?.participantUids.orEmpty()
                    DittoChatDocument.decode(raw, members)
                } else DittoCotDocument.decode(raw)
                if (repository.dittoPointIngress.deleted(doc)) continue
                require(collection != "track" || CotMessageParser.isUserPositionType(doc.type)) {
                    "Non-PLI document in Ditto track"
                }
                require(collection != "alert" || CotMessageParser.isEmergencyType(doc.type)) {
                    "Non-emergency document in Ditto alert"
                }
                require(collection != "chat" ||
                    (doc.type == "b-t-f" && CotMessageParser.isValidGatewayChat(doc.xml))) {
                    "Invalid GeoChat document in Ditto chat"
                }
                if (doc.peer == localPeer() || doc.author == deviceUid() || doc.uid == deviceUid()) continue
                val currentTime = now()
                require(doc.time <= currentTime + MAX_FUTURE_MS) { "Future-dated Ditto event" }
                if (CotMessageParser.isMarkerType(doc.type) &&
                    repository.dittoPointLedger.owns(doc.uid)) continue
                if (collection == "mapitem" && CotMessageParser.isMarkerType(doc.type) &&
                    DittoPointLedger.METADATA !in raw &&
                    !repository.dittoPointIngress.accept(DittoPointIngress.Point(doc, false))) continue
                val expired = doc.stale <= currentTime
                if (expired && doc.type == "b-t-f") continue
                val key = doc.key
                val fingerprint = if (sensitive) "" else doc.fingerprint
                val previous = (watermarks[key] as? String)?.split(':', limit = 3)
                if (previous != null) {
                    val time = previous[0].toLong()
                    if (doc.time < time ||
                        (doc.time == time && (sensitive || previous[2] != fingerprint ||
                            seen[key] == expired || doc.type == "b-t-f"))) continue
                }
                if (key !in watermarks && watermarks.size >= MAX_JOURNAL_ENTRIES) {
                    pruneJournal(currentTime)
                    require(watermarks.size < MAX_JOURNAL_ENTRIES) { "Ditto replay journal full" }
                }
                val result = if (expired && doc.type == "b-m-r") {
                    if (TakRouteCotParser.parse(doc.xml, doc.sourceId) == null) {
                        log("Rejected malformed expired Ditto route", null)
                        continue
                    }
                    repository.removeTakRoute(doc.sourceId, doc.uid)
                    TakServerCotIngestResult.ROUTE
                } else {
                    ingestor.ingest(TakServerCotEnvelope(
                        sourceServerId = doc.sourceId, sourceServerAddress = "Ditto",
                        receivedAt = currentTime, rawXml = doc.xml
                    ))
                }
                if (result == TakServerCotIngestResult.MALFORMED ||
                    result == TakServerCotIngestResult.UNSUPPORTED) continue
                currentCoroutineContext().ensureActive()
                // Persist chat replay suppression; live state is rehydrated once on a new receiver.
                val retainUntil = maxOf(doc.stale, previous?.get(1)?.toLong() ?: doc.stale)
                val watermark = "${doc.time}:$retainUntil:$fingerprint"
                check(journal.edit().putString(key, watermark).commit()) {
                    "Could not persist Ditto replay watermark"
                }
                watermarks[key] = watermark
                seen[key] = expired
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sensitive) log("Rejected sensitive Ditto document", null)
                else log("Rejected Ditto $collection document: ${error.message}", error)
            } finally {
                processingSensitiveDocument = false
            }
        }
    }

    private fun pruneJournal(time: Long) {
        val editor = journal.edit()
        val expired = watermarks.filter { (_, value) ->
            val stale = (value as? String)?.split(':', limit = 3)?.getOrNull(1)?.toLongOrNull()
            stale != null && stale <= time
        }
        expired.keys.forEach(editor::remove)
        check(editor.commit()) { "Could not prune Ditto replay journal" }
        expired.keys.forEach { watermarks.remove(it); seen.remove(it) }
    }

    companion object {
        private const val TAG = "WearTAK-DittoReceive"
        const val MAX_JOURNAL_ENTRIES = 8192
        const val MAX_FUTURE_MS = 300_000L
    }
}
