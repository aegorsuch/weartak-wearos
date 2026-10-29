package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.BatteryPowerObserver.DockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class CotMessageBuilderTest {
    @Test fun pliPreservesWatchUidAndHumanCallsignWithAtakStyleDittoDeviceName() {
        val base = pliSnapshot()
        val uid = "WEAROS_056b9ca33004b318"
        val snapshot = base.copy(systemData = base.systemData.copy(deviceId = uid))
        val xml = CotMessageBuilder.pli(snapshot,
            CotMessageBuilder.PliOptions("ODIN-WEARTAK", "Medic", "Blue", false, "",
                dittoMetadata = CotDetailBuilder.DittoMetadata("watch-sdk-peer", "T004b318", "runtime-sdk")))
        val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml))).documentElement
        val ditto = event.getElementsByTagName("ditto").item(0) as Element
        assertEquals(uid, event.getAttribute("uid"))
        assertEquals("T004b318", ditto.getAttribute("deviceName"))
        assertEquals("watch-sdk-peer", ditto.getAttribute("a"))
        assertEquals("runtime-sdk", ditto.getAttribute("version"))
        assertEquals("0.0.0.0", ditto.getAttribute("ip"))
        assertEquals("ODIN-WEARTAK",
            (event.getElementsByTagName("contact").item(0) as Element).getAttribute("callsign"))
        val group = event.getElementsByTagName("__group").item(0) as Element
        assertEquals("Medic", group.getAttribute("role"))
        assertEquals("Blue", group.getAttribute("name"))
    }

    @Test fun pliIncludesOnlySuppliedLocalDittoMetadataAndEscapesAttributes() {
        val metadata = CotDetailBuilder.DittoMetadata(
            "watch-peer&'<>\"", "Watch & ' \" < >", "runtime-sdk-version&",
        )
        for (local in listOf(metadata, null)) {
            val xml = CotMessageBuilder.pli(pliSnapshot(),
                CotMessageBuilder.PliOptions("EAGLE", "Medic", "Blue", false, "",
                    dittoMetadata = local))
            val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml))).documentElement
            val elements = event.getElementsByTagName("ditto")
            assertEquals(if (local == null) 0 else 1, elements.length)
            assertEquals("watch-1", event.getAttribute("uid"))
            if (local != null) {
                val ditto = elements.item(0) as Element
                assertEquals("detail", ditto.parentNode.nodeName)
                assertEquals(local.peerId, ditto.getAttribute("a"))
                assertEquals(local.deviceName, ditto.getAttribute("deviceName"))
                assertEquals(local.version, ditto.getAttribute("version"))
                assertEquals("0.0.0.0", ditto.getAttribute("ip"))
                assertEquals(4, ditto.attributes.length)
            }
        }
    }

    @Test fun ownedDeletionMatchesAtakLinkedForcedDeleteContractWithoutChangingTargetIdentity() {
        val uid = "original & ' \" < > pin"
        val now = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        for (location in listOf(location(), null)) {
            val xml = CotMessageBuilder.ownedPointDeletion(uid, location, now, "independent-event")
            val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml))).documentElement
            assertEquals("independent-event", event.getAttribute("uid"))
            assertEquals("t-x-d-d", event.getAttribute("type"))
            assertEquals(now, Instant.parse(event.getAttribute("time")).toEpochMilli())
            assertEquals(event.getAttribute("time"), event.getAttribute("start"))
            assertEquals(now + 86_400_000, Instant.parse(event.getAttribute("stale")).toEpochMilli())
            val link = event.getElementsByTagName("link").item(0) as Element
            assertEquals(uid, link.getAttribute("uid"))
            assertEquals("none", link.getAttribute("relation"))
            assertEquals("none", link.getAttribute("type"))
            assertEquals(1, event.getElementsByTagName("__forcedelete").length)
            val point = event.getElementsByTagName("point").item(0) as Element
            assertEquals(location?.lat ?: 0.0, point.getAttribute("lat").toDouble(), 0.0)
            assertEquals(location?.lon ?: 0.0, point.getAttribute("lon").toDouble(), 0.0)
            assertTrue(CompanionGatewaySession.supported(xml))
            assertEquals(uid, CotMessageParser.parseDeletionOrNull(xml)!!.uid)
        }
    }

    @Test
    fun pliUsesActualCoordinateSourceWithoutRelabelingBleRefinementAsItsAcquisitionProvider() {
        for ((provider, source) in listOf("fused" to "fused", "fused" to "ble",
                "gps" to "gps", "network" to "network", "gps" to "ble")) {
            val base = pliSnapshot()
            val snapshot = base.copy(loc = base.loc!!.copy(provider = provider, coordinateSource = source))
            val xml = CotMessageBuilder.pli(snapshot,
                CotMessageBuilder.PliOptions("EAGLE", "Team Member", "Cyan", false, ""))
            val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml))).documentElement
            val precision = event.getElementsByTagName("precisionlocation").item(0) as Element
            assertEquals(source.uppercase(), precision.getAttribute("geopointsrc"))
            assertEquals(base.time.startCot, event.getAttribute("time"))
            val point = event.getElementsByTagName("point").item(0) as Element
            assertEquals(base.loc!!.lat, point.getAttribute("lat").toDouble(), 0.000001)
            assertEquals(base.loc!!.lon, point.getAttribute("lon").toDouble(), 0.000001)
        }
    }

    @Test
    fun pliMatchesLegacyStructureWithStatus() {
        val actual = CotMessageBuilder.pli(pliSnapshot(), CotMessageBuilder.PliOptions("EAGLE", "Team Member", "Cyan", false, "<hail/>"))
        val legacy = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='watch-1' type='a-f-G-U-C' time='$START' start='$START' stale='$STALE' how='m-g'><point lat='41.000000' lon='-87.000000' hae='100.0' ce='4' le='5'/><detail><status readiness='true' battery='80'/><remarks/><contact endpoint='*:-1:stcp' callsign='EAGLE'/><__group role='Team Member' name='Cyan'/><track course='45' speed='3'/><hail/></detail></event>"
        val expected = legacy.replace("<detail>", "<detail>${CotDetailBuilder.takv()}")
            .replace("<remarks/>", "<remarks/>${CotDetailBuilder.physiology("watch-1", null, 80)}")
        assertEquals(project(expected), project(actual))
    }

    @Test fun pliPhysiologyIsIndependentOfBatdokAndGatesVitalsOnMonitoredPhysio() {
        for (monitoring in listOf(false, true)) {
            for (batdok in listOf(false, true)) {
                val snapshot = pliSnapshot().copy(monitoredPhysio = physio().data.takeIf { monitoring })
                val xml = CotMessageBuilder.pli(snapshot,
                    CotMessageBuilder.PliOptions("EAGLE", "Medic", "Blue", batdok, ""))
                assertTrue(xml.contains(CotDetailBuilder.physiology("watch-1", snapshot.monitoredPhysio, 80)))
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(InputSource(StringReader(xml))).documentElement
                assertEquals(0, root.getElementsByTagName("biometrics").length)
                assertEquals(1, root.getElementsByTagName("physiology").length)
                val physiology = root.getElementsByTagName("physiology").item(0) as Element
                assertEquals("detail", physiology.parentNode.nodeName)
                for ((tag, value) in listOf("hr" to "72", "skt" to "98.6", "exert" to "0.25")) {
                    assertEquals(if (monitoring) 1 else 0, physiology.getElementsByTagName(tag).length)
                    if (monitoring) assertEquals(value, physiology.getElementsByTagName(tag).item(0).textContent)
                }
                assertEquals(if (batdok) 1 else 0, root.getElementsByTagName("_atmist_").length)
            }
        }
    }

    @Test
    fun batdokHeartRateOnlyUsesIncidentTimeNotExpiration() {
        assertBatdokVitalTimes(72, -1f)
    }

    @Test
    fun batdokHeartRateAndTemperatureUseIncidentTimeNotExpiration() {
        assertBatdokVitalTimes(72, 98.6f)
    }

    @Test
    fun batdokTemperatureOnlyKeepsIncidentTime() {
        assertBatdokVitalTimes(-1, 98.6f)
    }

    private fun assertBatdokVitalTimes(heartRateBpm: Int, skinTempF: Float) {
        val epoch = Instant.parse("2026-10-15T12:34:56.789Z").toEpochMilli()
        val staleOffsets = listOf(60, 300).flatMap { reportingIntervalSeconds ->
            // Exercise both the reported historical offsets and the current PLI policy.
            listOf(3 * reportingIntervalSeconds, 2 * reportingIntervalSeconds + 15)
        }
        for (staleOffsetSeconds in staleOffsets) {
            val eventTime = CotEventTimeFactory.at(
                nowMillis = epoch,
                staleOffset = CotTimeOffset(seconds = staleOffsetSeconds),
            )
            val physio = physio().copy(data = physio().data.copy(
                heartRateBpm = heartRateBpm,
                skinTempF = skinTempF,
            ))
            val xml = CotMessageBuilder.pli(
                pliSnapshot().copy(time = eventTime, physioData = physio),
                CotMessageBuilder.PliOptions("EAGLE", "Team Member", "Cyan", true, ""),
            )
            val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml))).documentElement
            assertEquals(eventTime.startCot, event.getAttribute("time"))
            assertEquals(eventTime.startCot, event.getAttribute("start"))
            assertEquals(eventTime.staleCot, event.getAttribute("stale"))
            assertEquals(
                epoch + staleOffsetSeconds * 1_000L,
                Instant.parse(event.getAttribute("stale")).toEpochMilli(),
            )

            val atmistNodes = event.getElementsByTagName("_atmist_")
            assertEquals(1, atmistNodes.length)
            val atmist = atmistNodes.item(0) as Element
            assertEquals("30", atmist.getAttribute("age"))
            assertEquals(eventTime.startCot, atmist.getAttribute("timeOfIncident"))
            val expectedTuples = buildList {
                if (heartRateBpm != -1) add("(HR,72,$epoch)")
                if (skinTempF != -1f) add("(Temp,98.6 (F) 37 (C),$epoch)")
            }
            val vitals = atmist.getElementsByTagName("vitalSign")
            assertEquals(expectedTuples.size, vitals.length)
            expectedTuples.forEachIndexed { index, tuple ->
                val vital = vitals.item(index) as Element
                assertEquals(index.toString(), vital.getAttribute("index"))
                assertEquals(tuple, vital.textContent)
                assertEquals(atmist.getAttribute("timeOfIncident"), vital.getAttribute("timestamp"))
                assertEquals(epoch, Instant.parse(vital.getAttribute("timestamp")).toEpochMilli())
            }
        }
    }

    @Test
    fun emergencyAlertAndCancelMatchLegacyStructure() {
        val alert = emergencySnapshot(DataRepository.AlertState.ALERT)
        val cancel = emergencySnapshot(DataRepository.AlertState.CANCEL)
        val alertXml = CotMessageBuilder.emergency(alert)
        val cancelXml = CotMessageBuilder.emergency(cancel)
        for (xml in listOf(alertXml, cancelXml)) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml))).documentElement
            assertEquals(1, root.getElementsByTagName("physiology").length)
            assertEquals(0, root.getElementsByTagName("biometrics").length)
            val parsed = CotMessageParser.parseEmergencyOrNull(xml)!!
            assertEquals("watch-1", parsed.senderUid)
            assertEquals(parsed, CotMessageParser.parseEmergencyOrNull(
                xml.replace("<physiology", "<biometrics").replace("</physiology>", "</biometrics>")))
        }
        assertTrue(alertXml.contains("<physiology"))
        assertTrue(alertXml.contains("uid='alert-1'"))
        assertTrue(alertXml.contains("alertUid='alert-1'"))
        assertTrue(alertXml.contains("<weartak_location timeMillis='0'/>"))
        assertTrue(alertXml.contains("alertState='ALERT'"))
        assertTrue(alertXml.contains("alertCategory='Alert'"))
        assertTrue(alertXml.contains("alertPriority='1'"))
        assertTrue(alertXml.contains("<device><model>WEAROS</model><uid>watch-1</uid><hr>72</hr><skt>98.6</skt><exert>0.25</exert></device>"))
        assertTrue(!alertXml.contains("<device model='WEAROS'"))
        assertTrue(alertXml.contains("type='b-a-o'"))
        assertTrue(cancelXml.contains("<physiology"))
        assertTrue(cancelXml.contains("alertUid='alert-1'"))
        assertTrue(cancelXml.contains("alertState='CANCEL'"))
        assertTrue(cancelXml.contains("<device><model>WEAROS</model><uid>watch-1</uid><hr>72</hr><skt>98.6</skt><exert>0.25</exert></device>"))
        assertTrue(cancelXml.contains("type='b-a-o-can'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emergencyWithoutLocationCannotCreateAPhantomPosition() {
        CotMessageBuilder.emergency(
            emergencySnapshot(DataRepository.AlertState.ALERT).copy(location = null),
        )
    }

    @Test
    fun emergencyNeverSerializesInvalidCoordinatesOrErrors() {
        for (invalid in listOf(
            location().copy(lat = Double.NaN),
            location().copy(lon = Double.POSITIVE_INFINITY),
            location().copy(lat = 91.0),
            location().copy(lon = -181.0),
        )) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CotMessageBuilder.emergency(emergencySnapshot(DataRepository.AlertState.ALERT).copy(location = invalid))
            }
        }
        val xml = CotMessageBuilder.emergency(
            emergencySnapshot(DataRepository.AlertState.CANCEL).copy(
                location = location().copy(hae = Double.NaN, ce = Float.POSITIVE_INFINITY, le = -1f),
            ),
        )
        assertTrue(xml.contains("hae='0.0'"))
        assertTrue(xml.contains("ce='9999999.0'"))
        assertTrue(xml.contains("le='9999999.0'"))
    }

    @Test
    fun standardManualAlertsUseAtakTypesAndRoundTripAsTheirDisplayCategory() {
        StandardEmergencyType.entries.forEach { standardType ->
            val alert = emergencySnapshot(DataRepository.AlertState.ALERT)
                .copy(category = "Manual Alert", description = standardType.displayName)
            val xml = CotMessageBuilder.emergency(alert)
            val parsed = CotMessageParser.parseEmergencyOrNull(xml)

            assertEquals(standardType.cotType, rootAttribute(xml, "type"))
            assertTrue(xml.contains("type='${standardType.cotDescription}'"))
            assertEquals(standardType.displayName, parsed?.category)
            assertEquals(standardType.cotDescription, parsed?.description)

            val cancelXml = CotMessageBuilder.emergency(alert.copy(alertState = DataRepository.AlertState.CANCEL))
            assertEquals("b-a-o-can", rootAttribute(cancelXml, "type"))
            assertEquals(alert.uid, rootAttribute(cancelXml, "uid"))
        }
    }

    @Test
    fun markerAndClearMatchLegacyStructure() {
        val marker = markerSnapshot(time())
        val clear = markerSnapshot(CotEventTime(0, 0, 0, START, "2026-08-19T11:59:56.000Z", "2026-08-19T11:57:00.000Z"))
        val legacy = "<event version='2.0' uid='marker-1' type='a-f-G' time='$START' start='$START' stale='$STALE' how='h-g-i-g-o' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><status readiness='true' battery='80'/><precisionlocation altsrc='SRTM1'/><link uid='watch-1' production_time='$START' type='a-f-G-U-C' parent_callsign='EAGLE' relation='p-p'/><color argb='-1'/><usericon iconsetpath='COT_MAPPING_2525C/a-f/a-f-G'/><remarks>Remark</remarks><contact callsign='Title'/></detail></event>"
        assertEquals(project(legacy), project(CotMessageBuilder.marker(marker, "Title", marker.markerType.icon)))
        assertEquals("2026-08-19T11:57:00.000Z", rootAttribute(CotMessageBuilder.marker(clear, "Title", clear.markerType.icon), "stale"))
    }

    @Test
    fun chatMatchesLegacyStructure() {
        val actual = CotMessageBuilder.chat(chatSnapshot(), "EAGLE", "watch-1", "message-1", 0)
        val legacy = "<event version='2.0' uid='GeoChat.watch-1.room-1.message-1' type='b-t-f' time='1970-01-01T00:00:00.000Z' start='1970-01-01T00:00:00.000Z' stale='1970-01-02T00:00:00.000Z' how='h-g-i-g-o' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><__chat parent='RootContactGroup' groupOwner='false' messageId='message-1' chatroom='Room' id='room-1' senderCallsign='EAGLE'><chatgrp uid0='watch-1' uid1='room-1' id='room-1'/></__chat><link uid='watch-1' type='a-f-G-U-C' relation='p-p'/><remarks source='watch-1' to='room-1' time='1970-01-01T00:00:00.000Z'>Hello</remarks><marti><dest callsign='Room'/></marti></detail></event>"
        assertEquals(project(legacy), project(actual))
    }

            @Test
            fun chatStaysLiveForOneDaySoStaleFilteringServersRelayIt() {
                val now = Instant.parse("2026-10-21T14:18:18.469Z").toEpochMilli()
                val xml = CotMessageBuilder.chat(chatSnapshot(), "EAGLE", "watch-1", "message-1", now)
                assertEquals("2026-10-21T14:18:18.469Z", rootAttribute(xml, "time"))
                assertEquals("2026-10-22T14:18:18.469Z", rootAttribute(xml, "stale"))
            }
    @Test fun pliNeverInventsPositionFromMissingOrInvalidFix() {
        val options = CotMessageBuilder.PliOptions("EAGLE", "Team Member", "Cyan", false, "")
        val valid = pliSnapshot()
        for (location in listOf(null, valid.loc!!.copy(timeMillis = 0),
            valid.loc.copy(lat = Double.NaN), valid.loc.copy(lon = 181.0))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CotMessageBuilder.pli(valid.copy(loc = location), options)
            }
        }
        val zeroFix = valid.copy(loc = valid.loc.copy(lat = 0.0, lon = 0.0))
        assertTrue(CotMessageBuilder.pli(zeroFix, options).contains("lat='0.000000'"))
    }

    private fun pliSnapshot() = DataRepository.PliSnapshot(location().copy(timeMillis = 1), time(), physio(), system())
    private fun markerSnapshot(time: CotEventTime) = DataRepository.MarkerSnapshot(DataRepository.MarkerCotType.DROP, "marker-1", DataRepository.MarkerType.FRIENDLY, "Title", "Remark", DataRepository.MarkerType.FRIENDLY.icon, false, false, location(), time, physio(), system())
    private fun emergencySnapshot(state: DataRepository.AlertState) = DataRepository.EmergencyEventSnapshot("alert-1", state, "Alert", "Help", 1, false, time(), location(), physio(), system())
    private fun chatSnapshot() = DataRepository.ChatEventSnapshot("ignored", "room-1", "Room", "Hello", time(), location(), physio(), system())
    private fun location() = DataRepository.LocationSnapshot(0, 41.0, -87.0, 100.0, 4f, 5f, 45.9f, 3.8f)
    private fun time() = CotEventTime(0, 0, 0, START, START, STALE)
    private fun physio() = DataRepository.PhysioSnapshot(0, DataRepository.PhysioData(72, 98.6f, 30, 70, 60, .25, null, null))
    private fun system() = DataRepository.SystemSnapshot(0, 80, "EAGLE", "watch-1", "1", 10, DataRepository.SystemStates(0, DockType.NONE, false, true, 10, 10))

    private fun project(xml: String): String {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        return project(root)
    }

    private fun project(element: Element): String = buildString {
        append('<').append(element.tagName)
        (0 until element.attributes.length).map { element.attributes.item(it) }.sortedBy { it.nodeName }.forEach {
            append(' ').append(it.nodeName).append('=').append(it.nodeValue)
        }
        append('>')
        for (index in 0 until element.childNodes.length) {
            when (val child = element.childNodes.item(index)) {
                is Element -> append(project(child))
                else -> if (child.nodeType == Node.TEXT_NODE && child.nodeValue.isNotBlank()) append(child.nodeValue.trim())
            }
        }
        append("</").append(element.tagName).append('>')
    }

    private fun rootAttribute(xml: String, name: String): String =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement.getAttribute(name)

    private companion object {
        const val START = "2026-08-19T12:00:00.000Z"
        const val STALE = "2026-08-19T12:00:30.000Z"
    }
}
