/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.cot

import android.app.Application
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.takserver.TakDataPackageRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TakServerCotIngestorTest {
    @Test
    fun genericAffiliationMarkersReachTheMapSinkFromEveryDirectSource() = runTest {
        val sink = RecordingSink()
        val ingestor = ingestor(sink)
        val shapeDetails = mapOf(
            "u-d-c-c" to "<shape><ellipse major='100' minor='100'/></shape>",
            "u-d-e" to "<shape><ellipse major='100' minor='50' angle='45'/></shape>",
            "u-d-f" to "<shape><link point='41,-87'/><link point='41.001,-87.001'/></shape>",
            "u-d-r" to "<shape><link point='41,-87'/><link point='41,-86.999'/>" +
                "<link point='41.001,-86.999'/><link point='41.001,-87'/></shape>"
        )
        val types = listOf(
            "a-n-A-C-F", "a-n-A-C-H", "a-f-Z", "a-h-G-U-C",
            "a-s-S", "a-a-G-X", "b-m-p-s-m"
        ) + shapeDetails.keys
        for (source in listOf(SERVER_ID, "multicast:239.2.3.1:6969", "plugin:mesh")) {
            for (type in types) {
                val xml = MARKER_XML.replace("type='a-h-G'", "type='$type'")
                    .replace("<detail>", "<detail>${shapeDetails[type].orEmpty()}")
                val message = envelope(xml, 102L)
                    .copy(sourceServerId = source)
                assertEquals(TakServerCotIngestResult.MARKER, ingestor.ingest(message))
                assertEquals(type, sink.markers.last().first.type)
                assertEquals(CotMessageParser.isMapShapeType(type), sink.markers.last().first.detail.mapShape != null)
                assertEquals(message, sink.markers.last().second)
            }
        }
        assertEquals(33, sink.markers.size)
        assertEquals(TakServerCotIngestResult.USER, ingestor.ingest(envelope(USER_XML, 103L)))
    }

    @Test
    fun pluginTransportChatKeepsTransportAndWorkspaceMetadataSeparateFromEventUid() = runTest {
        val sink = RecordingSink()
        val source = envelope(CHAT_XML, 104L).copy(
            sourceServerId = "plugin:com.example.transport",
            sourceSessionId = "session-1",
            sourceChannelId = "satellite",
            sourceWorkspaceId = "workspace-9",
            sourceExternalId = "18446744073709551615",
            sourceTransportMessageId = "transport-message-1",
            sourceSentAtEpochMillis = 4_105_152_000_000L,
        )

        assertEquals(TakServerCotIngestResult.CHAT, ingestor(sink).ingest(source))

        val message = sink.chats.single().parsedRooms!!.single().messageList.single()
        assertEquals("plugin:com.example.transport", message.sourceServerId)
        assertEquals("session-1", message.sourceSessionId)
        assertEquals("satellite", message.sourceChannelId)
        assertEquals("workspace-9", message.workspaceId)
        assertEquals("18446744073709551615", message.externalId)
        assertEquals("transport-message-1", message.transportMessageId)
        assertEquals(4_105_152_000_000L, message.sentAtEpochMillis)
        assertEquals("chat-1", CotMessageParser.parseDocumentOrNull(source.rawXml)!!
            .documentElement.getAttribute("uid"))
    }

    @Test
    fun pluginTransportChatDoesNotAuthorizeCommandsFromClaimedSenderIdentity() = runTest {
        val sink = RecordingSink()
        var commands = 0
        val ingestor = TakServerCotIngestor(
            sink = sink,
            isChatEnabled = { true },
            isGeofenceEnabled = { false },
            deviceUid = { "watch-1" },
            deviceCallsign = { "EAGLE" },
            chatCommandHandler = { commands++; false },
        )
        val commandChat = CHAT_XML.replace("Hello", "#weartakcommand toggleditto state=on")

        assertEquals(
            TakServerCotIngestResult.CHAT,
            ingestor.ingest(
                envelope(commandChat, 105L).copy(
                    sourceServerId = "plugin:com.example.transport",
                    sourceTransportMessageId = "transport-1",
                ),
            ),
        )
        assertEquals(0, commands)
        assertEquals(1, sink.chats.size)

        assertEquals(TakServerCotIngestResult.CHAT, ingestor.ingest(envelope(commandChat, 106L)))
        assertEquals(1, commands)
    }

    @Test fun deletionKeepsSourceAndDoesNotNeedAPoint() = runTest {
        val sink = RecordingSink()
        val message = envelope(MARKER_XML.replace("type='a-h-G'", "type='t-x-d-d'"), 102L)
        assertEquals(TakServerCotIngestResult.DELETE, ingestor(sink).ingest(message))
        assertEquals("marker-1", sink.deletions.single().first.uid)
        assertEquals(message, sink.deletions.single().second)
        assertTrue(sink.markers.isEmpty())
    }

    @Test
    fun phoneOnlyGateBlocksEveryDirectTypeBeforePluginCommandsOrPackagesAndResumesAfterDisconnect() = runTest {
        var connected = false
        val sink = RecordingSink()
        val logs = mutableListOf<String>()
        var offers = 0
        var commands = 0
        val ingestor = TakServerCotIngestor(
            sink, { true }, { true }, { "watch-1" }, { "EAGLE" },
            chatCommandHandler = { commands++; false },
            dataPackageHandler = { _, _ -> offers++ },
            isInboundAllowed = { !connected },
            logger = { message, _ -> logs += message }
        )
        assertEquals(TakServerCotIngestResult.USER, ingestor.ingest(envelope(USER_XML, 1)))
        repeat(2) {
            connected = true
            sink.acceptConfiguration = true
            for (xml in listOf(USER_XML, MARKER_XML, EMERGENCY_XML, CHAT_XML, GEOFENCE_XML,
                CHANNEL_CHANGE_XML, "<event type='b-f-t-r'><detail/></event>")) {
                for (source in listOf(SERVER_ID, "multicast:239.2.3.1:6969", "plugin:mesh")) {
                    assertEquals(TakServerCotIngestResult.SUPPRESSED,
                        ingestor.ingest(envelope(xml, 2).copy(sourceServerId = source)))
                }
            }
            assertTrue(sink.configurations.isEmpty())
            assertEquals(0, offers)
            assertEquals(0, commands)
            connected = false
            sink.acceptConfiguration = false
            assertEquals(TakServerCotIngestResult.USER, ingestor.ingest(envelope(USER_XML, 3)))
        }
        assertEquals(3, sink.users.size)
        assertTrue(logs.any { it.contains("Suppressing direct CoT") })
    }

    private class RecordingSink : TakServerCotSink {
        val chats = mutableListOf<TakChatEvent>()
        val geofences = mutableListOf<GeofenceCot>()
        val routes = mutableListOf<Pair<TakRoute, TakServerCotEnvelope>>()
        override suspend fun onRoute(
            route: TakRoute,
            envelope: TakServerCotEnvelope
        ) {
            routes += route to envelope
        }
        val users = mutableListOf<Pair<CotUserPositionEvent, TakServerCotEnvelope>>()
        val markers = mutableListOf<Pair<CotMarkerEvent, TakServerCotEnvelope>>()
        val deletions = mutableListOf<Pair<CotDeletion, TakServerCotEnvelope>>()
        override suspend fun onDelete(event: CotDeletion, envelope: TakServerCotEnvelope) {
            deletions += event to envelope
        }
        val emergencies = mutableListOf<Pair<CotEmergencyEvent, TakServerCotEnvelope>>()
        val channelChanges = mutableListOf<TakServerCotEnvelope>()
        val configurations = mutableListOf<TakServerCotEnvelope>()
        var acceptConfiguration = false

        override suspend fun onInboundCot(envelope: TakServerCotEnvelope): Boolean {
            if (!acceptConfiguration) return false
            configurations += envelope
            return true
        }

        override suspend fun onChat(event: TakChatEvent) {
            chats += event
        }

        override suspend fun onGeofence(geofence: GeofenceCot) {
            geofences += geofence
        }

        override suspend fun onUser(
            event: CotUserPositionEvent,
            envelope: TakServerCotEnvelope
        ) {
            users += event to envelope
        }

        override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) {
            markers += event to envelope
        }

        override suspend fun onEmergency(event: CotEmergencyEvent, envelope: TakServerCotEnvelope) {
            emergencies += event to envelope
        }

        override suspend fun onChannelChanged(envelope: TakServerCotEnvelope) {
            channelChanges += envelope
        }
    }

    @Test
    fun deliversServerPackageOffersAndRejectsMalformedOffersWithoutStoppingCot() = runTest {
        val sink = RecordingSink()
        val offers = mutableListOf<Pair<TakDataPackageRequest, TakServerCotEnvelope>>()
        val logs = mutableListOf<String>()
        val ingestor = TakServerCotIngestor(
            sink = sink,
            isChatEnabled = { true },
            isGeofenceEnabled = { true },
            deviceUid = { "watch" },
            deviceCallsign = { "WATCH" },
            dataPackageHandler = { offer, source -> offers += offer to source },
            logger = { message, _ -> logs += message }
        )
        val xml = """<event uid="package" type="b-f-t-r"><detail>
            <fileshare sha256="${"a".repeat(64)}" sizeInBytes="89176"
            senderUrl="https://192.0.2.1:8443/Marti/sync/content?hash=${"a".repeat(64)}"/>
            </detail></event>"""
        val source = envelope(xml, 100L)
        assertEquals(TakServerCotIngestResult.DATA_PACKAGE, ingestor.ingest(source))
        assertEquals(source, offers.single().second)
        assertEquals(89176L, offers.single().first.size)
        assertEquals(TakServerCotIngestResult.MALFORMED,
            ingestor.ingest(source.copy(rawXml = xml.replace("89176", "999999999"))))
        assertEquals(1, offers.size)
        assertTrue(logs.any { it.contains("malformed") })
        assertEquals(TakServerCotIngestResult.USER, ingestor.ingest(envelope(USER_XML, 101L)))
    }

    @Test
    fun deliversRoutesWithTheOriginalSourceAndOrderedPoints() = runTest {
        val sink = RecordingSink()
        val ingestor = ingestor(sink)
        val xml = """
            <event uid="shared-route" type="b-m-r"><detail>
                <contact callsign="Test route"/>
                <link point="34.0,-117.0,100" callsign="Start"/>
                <link point="35.0,-118.0,200" callsign="Finish"/>
            </detail></event>
        """.trimIndent()
        val first = envelope(xml, 100L)
        val second = first.copy(sourceServerId = "other-server", receivedAt = 101L)

        assertEquals(TakServerCotIngestResult.ROUTE, ingestor.ingest(first))
        assertEquals(TakServerCotIngestResult.ROUTE, ingestor.ingest(second))
        assertEquals(listOf(first, second), sink.routes.map { it.second })
        assertEquals(listOf(SERVER_ID, "other-server"), sink.routes.map { it.first.serverId })
        sink.routes.forEach { (route, _) ->
            assertEquals("shared-route", route.uid)
            assertEquals(listOf("Start", "Finish"), route.points.map { it.name })
            assertEquals(listOf(34.0, 35.0), route.points.map { it.latitude })
        }
    }

    @Test
    fun malformedRouteDoesNotPreventTheNextValidRoute() = runTest {
        val sink = RecordingSink()
        val logs = mutableListOf<String>()
        val ingestor = ingestor(sink, logs)
        val empty = "<event uid='route' type='b-m-r'><detail/></event>"
        val valid = "<event uid='route' type='b-m-r'><detail><link point='34,-117'/></detail></event>"

        assertEquals(TakServerCotIngestResult.MALFORMED, ingestor.ingest(envelope(empty, 100L)))
        assertEquals(TakServerCotIngestResult.ROUTE, ingestor.ingest(envelope(valid, 101L)))
        assertEquals(1, sink.routes.size)
        assertTrue(logs.any { it.contains("malformed route") })
    }

    @Test
    fun configurationHookRunsForEverySourceAndUnclaimedEventsKeepRouting() = runTest {
        val sink = RecordingSink()
        val ingestor = ingestor(sink)
        val xml = "<event uid='config' type='t-x-meshcfg'><detail></detail></event>"
        listOf("tak-1", "multicast:239.2.3.1:6969", "plugin:com.example").forEach { source ->
            val message = TakServerCotEnvelope(source, source, 100L, xml)
            sink.acceptConfiguration = true
            assertEquals(TakServerCotIngestResult.CONFIGURATION, ingestor.ingest(message))
            assertEquals(message, sink.configurations.last())
            sink.acceptConfiguration = false
            assertEquals(TakServerCotIngestResult.UNSUPPORTED, ingestor.ingest(message))
        }
        assertEquals(TakServerCotIngestResult.USER, ingestor.ingest(envelope(USER_XML, 101L)))
    }

    @Test
    fun routesSupportedDirectTakMessagesWithServerMetadata() = runTest {
        val sink = RecordingSink()
        val ingestor = ingestor(sink)

        assertEquals(TakServerCotIngestResult.USER, ingestor.ingest(envelope(USER_XML, 101L)))
        assertEquals(TakServerCotIngestResult.MARKER, ingestor.ingest(envelope(MARKER_XML, 102L)))
        assertEquals(TakServerCotIngestResult.EMERGENCY, ingestor.ingest(envelope(EMERGENCY_XML, 102L)))
        assertEquals(TakServerCotIngestResult.CHAT, ingestor.ingest(envelope(CHAT_XML, 103L)))
        assertEquals(TakServerCotIngestResult.GEOFENCE, ingestor.ingest(envelope(GEOFENCE_XML, 104L)))

        assertEquals(SERVER_ID, sink.users.single().second.sourceServerId)
        assertEquals(SERVER_ID, sink.markers.single().second.sourceServerId)
        assertEquals(SERVER_ID, sink.emergencies.single().second.sourceServerId)
        assertEquals(102L, sink.emergencies.single().second.receivedAt)
        assertEquals(SERVER_ID, sink.chats.single().sourceServerId)
        assertEquals(103L, sink.chats.single().receivedAt)
        assertEquals(SERVER_ID, sink.chats.single().parsedRooms?.single()?.messageList?.single()?.sourceServerId)
        assertEquals(SERVER_ID, sink.geofences.single().sourceServerId)
        assertEquals(104L, sink.geofences.single().receivedAt)
    }

    @Test
    fun routesStandardGeofenceEmergencyThroughRemoteAlertIngress() = runTest {
        val sink = RecordingSink()
        val result = ingestor(sink).ingest(envelope(GEOFENCE_EMERGENCY_XML, 105L))

        assertEquals(TakServerCotIngestResult.EMERGENCY, result)
        assertEquals("Geofence Breached", sink.emergencies.single().first.category)
        assertEquals("atak-device", sink.emergencies.single().first.senderUid)
    }

    @Test
    fun malformedMessageDoesNotPreventTheNextValidMessage() = runTest {
        val sink = RecordingSink()
        val logs = mutableListOf<String>()
        val ingestor = ingestor(sink, logs)

        assertEquals(
            TakServerCotIngestResult.MALFORMED,
            ingestor.ingest(envelope(MALFORMED_USER_XML, 100L))
        )
        assertEquals(
            TakServerCotIngestResult.USER,
            ingestor.ingest(envelope(USER_XML, 101L))
        )

        assertEquals(1, sink.users.size)
        assertTrue(logs.any { it.contains("malformed") })
    }

    @Test
    fun suppressesOwnEmergencyAndCancellationButRejectsMalformedEmergency() = runTest {
        val sink = RecordingSink()
        val logs = mutableListOf<String>()
        val ingestor = ingestor(sink, logs)
        val ownAlert = EMERGENCY_XML.replace("device-2", "watch-1")
        val ownCancel = EMERGENCY_XML
            .replace("type='b-a-o'", "type='b-a-o-can'")
            .replace("<link uid='device-2' type='a-f-G-U-C' parent_callsign='SENDER' relation='p-p'/>", "")
            .replace("device-2", "watch-1")
            .replace("<emergency type='TEST'>", "<emergency cancel='true'>")

        assertEquals(TakServerCotIngestResult.EMERGENCY, ingestor.ingest(envelope(ownAlert, 100L)))
        assertEquals(TakServerCotIngestResult.EMERGENCY, ingestor.ingest(envelope(ownCancel, 101L)))
        assertEquals(
            TakServerCotIngestResult.MALFORMED,
            ingestor.ingest(envelope(EMERGENCY_XML.replace(" stale='2099-05-01T12:05:00.000Z'", ""), 102L))
        )

        assertTrue(sink.emergencies.isEmpty())
        assertTrue(sink.markers.isEmpty())
        assertTrue(sink.users.isEmpty())
        assertTrue(logs.any { it.contains("malformed emergency") })
    }

    @Test
    fun routesChannelChangeToTheOwningServer() = runTest {
        val sink = RecordingSink()

        assertEquals(
            TakServerCotIngestResult.CHANNEL_CHANGED,
            ingestor(sink).ingest(envelope(CHANNEL_CHANGE_XML, 105L))
        )

        assertEquals(SERVER_ID, sink.channelChanges.single().sourceServerId)
    }

    private fun ingestor(
        sink: RecordingSink,
        logs: MutableList<String> = mutableListOf()
    ) = TakServerCotIngestor(
        sink = sink,
        isChatEnabled = { true },
        isGeofenceEnabled = { true },
        deviceUid = { "watch-1" },
        deviceCallsign = { "EAGLE" },
        logger = { message, _ -> logs += message }
    )

    private fun envelope(xml: String, receivedAt: Long) = TakServerCotEnvelope(
        sourceServerId = SERVER_ID,
        sourceServerAddress = "tak.example",
        receivedAt = receivedAt,
        rawXml = xml
    )

    private companion object {
        const val SERVER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val MALFORMED_USER_XML = "<event version='2.0' uid='broken' type='a-f-G-U-C' time='not-a-time' start='not-a-time' stale='not-a-time' how='m-g'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><contact callsign='BROKEN'/><__group role='Team Member' name='Cyan'/></detail></event>"
        const val USER_XML = "<event version='2.0' uid='user-1' type='a-f-G-U-C' time='2099-05-01T12:00:00.000Z' start='2099-05-01T12:00:00.000Z' stale='2099-05-01T12:00:30.000Z' how='m-g'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><contact callsign='USER'/><__group role='Team Member' name='Cyan'/></detail></event>"
        const val MARKER_XML = "<event version='2.0' uid='marker-1' type='a-h-G' time='2099-05-01T12:00:00.000Z' start='2099-05-01T12:00:00.000Z' stale='2099-05-01T12:00:30.000Z' how='h-g-i-g-o'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><contact callsign='TARGET'/></detail></event>"
        const val EMERGENCY_XML = "<event version='2.0' uid='alert-1' type='b-a-o' time='2099-05-01T12:00:00.000Z' start='2099-05-01T12:00:00.000Z' stale='2099-05-01T12:05:00.000Z' how='h-e'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><link uid='device-2' type='a-f-G-U-C' parent_callsign='SENDER' relation='p-p'/><biometrics alertCategory='CAT_TEST'><device><uid>device-2</uid></device></biometrics><contact callsign='SENDER'/><emergency type='TEST'>SENDER</emergency></detail></event>"
        const val GEOFENCE_EMERGENCY_XML = "<event version='2.0' uid='geofence-alert' type='b-a-g' time='2099-05-01T12:00:00.000Z' start='2099-05-01T12:00:00.000Z' stale='2099-05-01T12:05:00.000Z' how='m-g'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><link uid='atak-device' type='a-f-G-U-C' parent_callsign='SENDER' relation='p-p'/><emergency type='Geo-fence Breached'>SENDER</emergency></detail></event>"
        const val CHAT_XML = "<event version='2.0' uid='chat-1' type='b-t-f' time='2099-05-01T12:00:00.000Z' start='2099-05-01T12:00:00.000Z' stale='2099-05-01T12:00:30.000Z' how='h-g-i-g-o'><point lat='0' lon='0' hae='0' ce='1' le='1'/><detail><__chat senderCallsign='SENDER'><chatgrp uid0='sender-1' uid1='watch-1' id='watch-1'/></__chat><remarks to='watch-1'>Hello</remarks></detail></event>"
        const val GEOFENCE_XML = "<event version='2.0' uid='geofence-1' type='u-d-r' time='2099-05-01T12:00:00.000Z' start='2099-05-01T12:00:00.000Z' stale='2099-05-01T12:00:30.000Z' how='h-e'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><contact callsign='SCIF'/><link point='41.0,-87.0'/><link point='41.1,-87.0'/><link point='41.1,-87.1'/><__geofence trigger='Entry' tracking='true'/></detail></event>"
        const val CHANNEL_CHANGE_XML = "<event version='2.0' uid='groups-1' type='t-x-g-c' time='2099-05-01T12:00:00.000Z' start='2099-05-01T12:00:00.000Z' stale='2099-05-01T12:00:30.000Z' how='h-g-i-g-o'><point lat='0' lon='0' hae='0' ce='1' le='1'/><detail/></event>"
    }
}
