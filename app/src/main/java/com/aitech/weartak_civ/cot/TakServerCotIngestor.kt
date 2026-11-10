/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.cot

import android.util.Log
import com.aitech.weartak_civ.command.ChatCommandParser
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.parsers.GeofenceCotParser
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.CancellationException
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser
import com.aitech.weartak_civ.data.takserver.TakDataPackageRequest

data class TakServerCotEnvelope(
    val sourceServerId: String,
    val sourceServerAddress: String,
    val receivedAt: Long,
    val rawXml: String,
    val sourceSessionId: String? = null,
    val sourcePeerAddress: String? = null,
    val sourceChannelId: String? = null,
    val sourceWorkspaceId: String? = null,
    val sourceExternalId: String? = null,
    val sourceTransportMessageId: String? = null,
    val sourceSentAtEpochMillis: Long? = null,
    val sourceSenderUid: String? = null,
    val sourceSenderCallsign: String? = null,
)

interface TakServerCotSink {
    suspend fun onChat(event: TakChatEvent)
    suspend fun onGeofence(geofence: GeofenceCot)
    suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope)
    suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope)
    suspend fun onDelete(event: CotDeletion, envelope: TakServerCotEnvelope) = Unit
    suspend fun onEmergency(event: CotEmergencyEvent, envelope: TakServerCotEnvelope) = Unit
    suspend fun onChannelChanged(envelope: TakServerCotEnvelope) = Unit
    suspend fun onInboundCot(envelope: TakServerCotEnvelope): Boolean = false

    // Pass a parsed route to the route handler
    // Includes the route points and the server the route came from
    suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope)
}

enum class TakServerCotIngestResult {
    CHAT,
    COMMAND,
    GEOFENCE,
    USER,
    MARKER,
    DELETE,
    EMERGENCY,
    ROUTE,
    DATA_PACKAGE,
    CHANNEL_CHANGED,
    CONFIGURATION,
    SUPPRESSED,
    UNSUPPORTED,
    MALFORMED
}

class TakServerCotIngestor(
    private val sink: TakServerCotSink,
    private val isChatEnabled: suspend () -> Boolean,
    private val isGeofenceEnabled: () -> Boolean,
    private val deviceUid: () -> String,
    private val deviceCallsign: () -> String,
    private val chatCommandHandler: (suspend (ChatCommandRequest) -> Boolean)? = null,
    private val dataPackageHandler: (suspend (TakDataPackageRequest, TakServerCotEnvelope) -> Unit)? = null,

    private val isInboundAllowed: () -> Boolean = { true },
    // Logs problems found while processing incoming CoT messages
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }
) {
    suspend fun ingest(envelope: TakServerCotEnvelope): TakServerCotIngestResult {
        if (!isInboundAllowed()) {
            logger("Suppressing direct CoT while bonded companion is connected source=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.SUPPRESSED
        }
        return try {
            require(envelope.sourceServerId.isNotBlank()) { "TAK server ID cannot be blank" }
            ingestMessage(envelope)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger(
                "Skipping malformed CoT from serverId=${envelope.sourceServerId}",
                error
            )
            TakServerCotIngestResult.MALFORMED
        }
    }

    private suspend fun ingestMessage(envelope: TakServerCotEnvelope): TakServerCotIngestResult {
        val rawXml = envelope.rawXml.trim()
        if (!rawXml.contains("<event") || !rawXml.contains("</event>")) {
            logger("Skipping malformed CoT from serverId=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.MALFORMED
        }

        val isChat = CotMessageParser.isChatMessage(rawXml)
        if (!isChat && com.aitech.weartak_civ.command.SensitiveEnrollment.containsSensitiveXml(rawXml)) {
            return TakServerCotIngestResult.COMMAND
        }
        if (!isChat && sink.onInboundCot(envelope)) {
            return TakServerCotIngestResult.CONFIGURATION
        }
        val eventType = CotMessageParser.eventTypeOrNull(rawXml)
        if (eventType == "t-x-d-d") {
            val deletion = CotMessageParser.parseDeletionOrNull(rawXml) ?: return TakServerCotIngestResult.MALFORMED
            if (deletion.uid != deviceUid()) sink.onDelete(deletion, envelope)
            return TakServerCotIngestResult.DELETE
        }

        if (dataPackageHandler != null && eventType == "b-f-t-r") {
            dataPackageHandler.invoke(
                TakDataPackageRequest.parse(rawXml, envelope.sourcePeerAddress),
                envelope
            )
            return TakServerCotIngestResult.DATA_PACKAGE
        }

        if (chatCommandHandler != null && isChat && envelope.sourceTransportMessageId == null) {
            val candidate = CotMessageParser.parseChatRooms(rawXml, deviceUid(), deviceCallsign(), envelope.sourceServerId, envelope.receivedAt)
                .asSequence()
                .flatMap { room -> room.messageList.asSequence().map { room to it } }
                .firstOrNull { (_, message) ->
                    message.message.trimStart().startsWith(ChatCommandParser.PREFIX, ignoreCase = true)
                }
            if (candidate != null) {
                val (room, message) = candidate
                val request = ChatCommandRequest(
                    senderUid = message.uid,
                    senderCallsign = message.callsign,
                    text = message.message,
                    messageId = message.messageId,
                    sentAtMs = CotMessageParser.eventTimeOrNull(rawXml) ?: 0L,
                    receivedAtMs = envelope.receivedAt,
                    sourceServerId = envelope.sourceServerId,
                    replyRoomUid = room.uid,
                    replyRoomTitle = room.title,
                    replyIsGroup = room.isGroup,
                    replyParticipantUids = room.participantUids
                )
                if (chatCommandHandler(request)) return TakServerCotIngestResult.COMMAND
            }
        }

        if (com.aitech.weartak_civ.command.SensitiveEnrollment.containsSensitiveXml(rawXml)) {
            return TakServerCotIngestResult.COMMAND
        }
        if (isChat && sink.onInboundCot(envelope)) {
            return TakServerCotIngestResult.CONFIGURATION
        }

        // Check whether the incoming message is a chat
        // Only process chats when chat is enabled and the message is for this device
        if (isChatEnabled() &&
            isChat &&
            CotMessageParser.isChatForRecipient(rawXml, deviceUid(), deviceCallsign())) {

            // Parse the chat message and find the rooms or conversations it belongs to
            // Include the source server and time the message was received
            val parsedRooms = CotMessageParser.parseChatRooms(
                rawXml = rawXml,
                myUid = deviceUid(),
                myCallsign = deviceCallsign(),
                sourceServerId = envelope.sourceServerId,
                receivedAt = envelope.receivedAt
            ).map { room ->
                room.copy(messageList = room.messageList.map { message ->
                    message.copy(
                        sourceSessionId = envelope.sourceSessionId,
                        sourceChannelId = envelope.sourceChannelId,
                        workspaceId = envelope.sourceWorkspaceId,
                        externalId = envelope.sourceExternalId,
                        transportMessageId = envelope.sourceTransportMessageId,
                        sentAtEpochMillis = envelope.sourceSentAtEpochMillis,
                        transportSenderUid = envelope.sourceSenderUid,
                        transportSenderCallsign = envelope.sourceSenderCallsign,
                    )
                })
            }

            // Skip the chat if no valid room information was found
            if (parsedRooms.isEmpty()) {
                logger("Skipping malformed TAK chat from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }
            sink.onChat(
                TakChatEvent(
                    rawXml = rawXml,
                    sourceServerId = envelope.sourceServerId,
                    receivedAt = envelope.receivedAt,
                    parsedRooms = parsedRooms
                )
            )

            // Mark the message as a successfully handled chat
            return TakServerCotIngestResult.CHAT
        }


        // Check whether the message contains a supported geofence
        // Only process this type of geofence when geofences are enabled
        if (isGeofenceEnabled() && GeofenceCotParser.isRadioSilenceRectangleGeofence(rawXml)) {

            // Convert the geofence XML into a geofence object
            val geofence = GeofenceCotParser.parse(rawXml)

            // Skip the message if the geofence could not be parsed
            if (geofence == null) {
                logger("Skipping malformed TAK geofence from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Add the source server and receive time to the geofence
            // Send the completed geofence to its handler
            sink.onGeofence(
                geofence.copy(
                    sourceServerId = envelope.sourceServerId,
                    receivedAt = envelope.receivedAt
                )
            )

            return TakServerCotIngestResult.GEOFENCE
        }


        // Check whether the message is a TAK channel change event
        // Channel change messages use the event type t-x-g-c
        if (eventType == CHANNEL_CHANGE_EVENT_TYPE) {

            // Notify the channel handler that a change was received
            sink.onChannelChanged(envelope)

            return TakServerCotIngestResult.CHANNEL_CHANGED
        }


        // Check whether the message contains a TAK route
        // Route CoT messages use the event type b-m-r
        // These messages contain the points needed to draw a route on the map
        if (eventType == ROUTE_EVENT_TYPE) {

            // Parse the route XML into a TakRoute object
            // Extract the route details and its ordered list of points
            // Include the source server ID to keep routes separated by server
            val route = TakRouteCotParser.parse(
                rawXml,
                envelope.sourceServerId
            )

            // Check whether the route was parsed successfully
            // A null result means the route data was missing or invalid
            if (route == null) {
                logger(
                    "Skipping malformed route CoT from serverId=${envelope.sourceServerId}",
                    null
                )

                // Skip the invalid route instead of passing it to the app
                return TakServerCotIngestResult.MALFORMED
            }

            // Send the parsed route to the route handler
            // The route can then be stored and made available to the map
            sink.onRoute(route, envelope)

            // Mark the message as a successfully handled route
            return TakServerCotIngestResult.ROUTE
        }


        // Check whether the message contains an emergency event
        // Emergency CoT uses specific event types recognized by the parser
        if (eventType?.let(CotMessageParser::isEmergencyType) == true) {

            // Parse the emergency details from the XML
            val event = CotMessageParser.parseEmergencyOrNull(rawXml)

            // Skip the message if the emergency information is invalid
            if (event == null) {
                logger("Skipping malformed emergency CoT source=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Log the emergency details for debugging
            // Includes the event UID, status, location, and timing information
            logger(
                "Emergency ingress uid=${event.uid} source=${envelope.sourceServerId} " +
                        "cancelled=${event.cancelled} hasLocation=${event.hasLocation} time=${event.time} stale=${event.stale}",
                null
            )

            // Only forward emergencies that did not come from the current device
            // Prevents the app from processing its own emergency as a new incoming event
            if (!event.isFrom(deviceUid())) sink.onEmergency(event, envelope)

            return TakServerCotIngestResult.EMERGENCY
        }


        // Try to parse the message as a user location update
        // A valid user position contains a UID, timestamps, and coordinates
        CotMessageParser.parseUserPositionOrNull(rawXml)?.let { event ->

            // Make sure the user position has all required fields
            if (!event.hasValidRequiredFields()) {
                logger("Skipping malformed TAK user CoT from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Only forward location updates from other devices
            // Skip the current watch to avoid processing its own position
            if (event.uid != deviceUid()) {
                sink.onUser(event, envelope)
            }

            return TakServerCotIngestResult.USER
        }


        // Try to parse the message as a map marker
        // Markers represent points or objects displayed on the TAK map
        CotMessageParser.parseMarkerOrNull(rawXml)?.let { event ->

            // Check that the marker has the required fields and valid coordinates
            if (!event.hasValidRequiredFields()) {
                logger("Skipping malformed TAK marker CoT from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Send the marker to the map marker handler
            sink.onMarker(event, envelope)

            return TakServerCotIngestResult.MARKER
        }


        // Check whether the message claims to be a supported user or marker type
        // Reaching this point means the earlier parsers could not process it
        // Treat the message as malformed rather than unsupported
        if (eventType != null &&
            (CotMessageParser.isUserPositionType(eventType) || CotMessageParser.isMarkerType(eventType))) {

            logger("Skipping malformed supported CoT from serverId=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.MALFORMED
        }

        // Skip messages that do not match any currently supported CoT type
        logger("Skipping unsupported CoT from serverId=${envelope.sourceServerId}", null)
        return TakServerCotIngestResult.UNSUPPORTED
    }


    // Checks whether a user location update has the required information
    // Requires a UID, valid timestamps, and usable coordinates
    private fun CotUserPositionEvent.hasValidRequiredFields(): Boolean =
        uid.isNotBlank() && time > 0L && start > 0L && stale > 0L && point.isFinite()


    // Checks whether a marker has the required information
    // Requires a UID, valid timestamps, and usable coordinates
    private fun CotMarkerEvent.hasValidRequiredFields(): Boolean =
        uid.isNotBlank() && time > 0L && start > 0L && stale > 0L && point.isFinite()


    // Checks that all coordinate and accuracy values are finite numbers
    // Rejects values such as NaN or infinity that cannot be used as valid numbers
    private fun CotPoint.isFinite(): Boolean =
        lat.isFinite() && lon.isFinite() && hae.isFinite() && ce.isFinite() && le.isFinite()


    // Constants used to identify the logger and specific CoT event types
    private companion object {

        // Tag used to identify messages from this class in Android logs
        const val TAG = "WearTAK-TakServerCotIngestor"

        // CoT event type used for channel change notifications
        const val CHANNEL_CHANGE_EVENT_TYPE = "t-x-g-c"

        // CoT event type used for TAK routes
        const val ROUTE_EVENT_TYPE = "b-m-r"
    }
}
