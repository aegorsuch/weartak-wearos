package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class CotMessageParserTest {
    @Test
    fun repeatedConcurrentIngressReusesOnlySchemaPatternsWithoutRetainingPayloads() {
        CotMessageParser.parseUserPositionOrNull(PLI_XML)
        CotMessageParser.parseMarkerOrNull(MARKER_XML)
        val patterns = CotMessageParser::class.java.getDeclaredField("attributePatterns").apply {
            isAccessible = true
        }.get(null) as Map<*, *>
        val compiled = patterns.toMap()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 4).map { worker ->
                workers.submit {
                    repeat(100) { index ->
                        val uid = "transient-$worker-$index"
                        val xml = PLI_XML.replace("uid='watch-1'", "uid='$uid'")
                        assertEquals(uid, CotMessageParser.parseUserPositionOrNull(xml)!!.uid)
                        assertEquals("marker-1", CotMessageParser.parseMarkerOrNull(MARKER_XML)!!.uid)
                    }
                }
            }
            results.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
        assertEquals(compiled.keys, patterns.keys)
        assertTrue(patterns.size <= 29)
        compiled.forEach { (key, pattern) -> org.junit.Assert.assertSame(pattern, patterns[key]) }
        assertFalse(patterns.toString().contains("transient-"))
        assertFalse(patterns.toString().contains("41.0"))
    }

    @Test
    fun parsesMarkerFieldsWithoutChangingTheirMeaning() {
        val event = CotMessageParser.parseMarkerOrNull(MARKER_XML)
        assertNotNull(event)

        assertEquals("marker-1", event!!.uid)
        assertEquals("a-h-G", event.type)
        assertEquals(1_777_636_800_000L, event.time)
        assertEquals(CotPoint(41.0, -87.0, 100.0, 4.0, 5.0), event.point)
        assertEquals("Target", event.detail.markerCallsign)
        assertEquals("watch-1", event.detail.link?.uid)
        assertEquals("Remark", event.detail.remarks)
    }

    @Test
    fun parsesGenericAtomsWithoutDiscardingTheirTypeOrCallsign() {
        listOf("a-f-A-M-F-Q", "a-u-A-M-F-Q", "a-h-A-M-F-Q", "a-n-A-C-F",
            "a-h-G-U-C", "a-a-G-X", "a-s-S", "a-j-G-E-V", "a-k-A-C-H",
            "a-p-G", "a-o-G", "a-f-A-Z-unsupported").forEach { type ->
            val uas = CotMessageParser.parseMarkerOrNull(MARKER_XML.replace("type='a-h-G'", "type='$type'"))
            assertNotNull(uas)
            assertEquals(type, uas!!.type)
            assertEquals("Target", uas.detail.markerCallsign)
            assertTrue(CotMessageParser.isMarkerType(type))
        }
        listOf("a-", "a-n", "a--G", "a-n-", "a-n--A", "a-n-A ", "b-n-A", "a-n-A/garbage")
            .forEach { assertFalse(it, CotMessageParser.isMarkerType(it)) }
    }

    @Test
    fun genericAtomsRetainCoordinateTimeAndStaleValidation() {
        val xml = MARKER_XML.replace("type='a-h-G'", "type='a-n-A-C-F'")
        assertNull(CotMessageParser.parseMarkerOrNull(xml.replace("lat='41.0'", "lat='91'")))
        assertNull(CotMessageParser.parseMarkerOrNull(xml.replace("lon='-87.0'", "lon='181'")))
        assertNull(CotMessageParser.parseMarkerOrNull(xml.replace("hae='100.0'", "hae='NaN'")))
        assertNull(CotMessageParser.parseMarkerOrNull(xml.replace(Regex("stale='[^']*'"), "stale='bad'")))
        assertEquals(CotMessageParser.parseMarkerOrNull(MARKER_XML)?.stale,
            CotMessageParser.parseMarkerOrNull(xml)?.stale)
    }

    @Test
    fun parsesPliAndRejectsItAsAMarker() {
        val event = CotMessageParser.parseUserPositionOrNull(PLI_XML)
        assertNotNull(event)

        assertEquals("watch-1", event!!.uid)
        assertEquals("Cyan", event.team)
        assertEquals("Team Member", event.role)
        assertEquals("EAGLE", event.detail.markerCallsign)
        assertNull(CotMessageParser.parseMarkerOrNull(PLI_XML))
    }

    @Test
    fun acceptsPhysiologyAndLegacyBiometricsPliWithoutChangingPositionFields() {
        val expected = CotMessageParser.parseUserPositionOrNull(PLI_XML)
        for (tag in listOf("physiology", "biometrics")) {
            val xml = PLI_XML.replace("</detail>",
                "<$tag><device><model>WEAROS</model><uid>watch-1</uid>" +
                    "<hr>72</hr><skt>98.6</skt><exert>0.25</exert><bat>80</bat></device></$tag></detail>")
            assertEquals(expected, CotMessageParser.parseUserPositionOrNull(xml))
            assertNull(CotMessageParser.parseCompanionUserOrNull(
                xml.replace("uid='watch-1'", "uid='rewritten'"), "watch-1", "phone-self"))
        }
    }

    @Test
    fun physiologyAndLegacyBiometricsAlertsRetainIdenticalMetadata() {
        for (legacy in listOf(EMERGENCY_XML, ATAK_STANDARD_ALERT_XML, WEARTAK_ALERT_XML, WEARTAK_CANCEL_XML)) {
            val physiology = legacy.replace("<biometrics", "<physiology")
                .replace("</biometrics>", "</physiology>")
            val expected = CotMessageParser.parseEmergencyOrNull(legacy)
            assertNotNull(expected)
            assertEquals(expected, CotMessageParser.parseEmergencyOrNull(physiology))
        }
    }

    @Test
    fun rejectsMarkersWithInvalidCoordinates() {
        assertNull(CotMessageParser.parseMarkerOrNull(MARKER_XML.replace("lat='41.0'", "lat='91.0'")))
        assertNull(CotMessageParser.parseMarkerOrNull(MARKER_XML.replace("lon='-87.0'", "lon='NaN'")))
        listOf("lat='41.0'", "lon='-87.0'", "hae='100.0'", "ce='4.0'", "le='5.0'").forEach { attribute ->
            assertNull(CotMessageParser.parseMarkerOrNull(
                MARKER_XML.replace(attribute, "${attribute.substringBefore('=')}='Infinity'")
            ))
        }
    }

    @Test
    fun rejectsBlankMarkerUidAndInvalidTimesButPreservesMissingTimeDefaults() {
        assertNull(CotMessageParser.parseMarkerOrNull(MARKER_XML.replace("uid='marker-1'", "uid=' '")))
        listOf("time", "start", "stale").forEach { name ->
            val invalid = MARKER_XML.replace(Regex("$name='[^']*'"), "$name='not-a-time'")
            assertNull(CotMessageParser.parseMarkerOrNull(invalid))
            val epoch = MARKER_XML.replace(Regex("\\b$name='[^']*'"), "$name='1970-01-01T00:00:00Z'")
            assertNull(CotMessageParser.parseMarkerOrNull(epoch))
        }
        val withoutDefaults = MARKER_XML.replace(Regex(" (start|stale)='[^']*'"), "")
        val event = CotMessageParser.parseMarkerOrNull(withoutDefaults)!!
        assertEquals(event.time, event.start)
        assertEquals(event.time, event.stale)
    }

    @Test
    fun parsesEmergencyAlertCategorySenderAndLocation() {
        val event = CotMessageParser.parseEmergencyOrNull(EMERGENCY_XML)

        assertNotNull(event)
        assertEquals("alert-1", event!!.uid)
        assertEquals("b-a-o", event.type)
        assertEquals("device-1", event.senderUid)
        assertEquals("SENDER", event.senderCallsign)
        assertEquals("CAT_HR_EMERGENCY", event.category)
        assertEquals("Medical emergency", event.description)
        assertEquals(CotPoint(41.0, -87.0, 100.0, 4.0, 5.0), event.point)
        assertFalse(event.cancelled)
    }

    @Test
    fun parsesEmergencySubtypeAndBothCancellationRepresentations() {
        val subtype = CotMessageParser.parseEmergencyOrNull(
            EMERGENCY_XML.replace("type='b-a-o'", "type='b-a-o-medical'")
                .replace("alertCategory='CAT_HR_EMERGENCY'", "")
        )
        val cancelTrue = CotMessageParser.parseEmergencyOrNull(
            EMERGENCY_XML.replace("type='b-a-o'", "type='b-a-o-medical'")
                .replace("<emergency type='Medical emergency'>", "<emergency cancel='true'>")
        )
        val cancelOne = CotMessageParser.parseEmergencyOrNull(
            EMERGENCY_XML.replace("type='b-a-o'", "type='b-a-o-medical'")
                .replace("<emergency type='Medical emergency'>", "<emergency cancel='1'>")
        )
        val cancelType = CotMessageParser.parseEmergencyOrNull(
            EMERGENCY_XML.replace("type='b-a-o'", "type='b-a-o-can'")
        )

        assertEquals("Medical emergency", subtype?.category)
        assertFalse(subtype?.cancelled ?: true)
        assertEquals(true, cancelTrue?.cancelled)
        assertEquals(true, cancelOne?.cancelled)
        assertEquals(true, cancelType?.cancelled)
    }

    @Test
    fun parsesRepresentativeAtakNineOneOneAlertAndCancellation() {
        val alert = CotMessageParser.parseEmergencyOrNull(ATAK_911_XML)
        val cancel = CotMessageParser.parseEmergencyOrNull(ATAK_911_CANCEL_XML)

        assertNotNull(alert)
        assertEquals("ANDROID-1c9928da1656d2e7-9-1-1", alert!!.uid)
        assertEquals("b-a-o-tbl", alert.type)
        assertEquals("ANDROID-1c9928da1656d2e7", alert.senderUid)
        assertEquals("RA26", alert.senderCallsign)
        assertEquals("911 Alert", alert.category)
        assertEquals("911 Alert", alert.description)
        assertEquals(CotPoint(27.95, -82.51, 5.0, 10.0, 9999999.0), alert.point)
        assertTrue(alert.hasLocation)
        assertFalse(alert.cancelled)
        assertTrue(alert.isFrom("ANDROID-1c9928da1656d2e7"))
        assertFalse(alert.isFrom("WEAROS_056b9ca33004b318"))

        assertNotNull(cancel)
        assertEquals(alert.uid, cancel!!.uid)
        assertEquals("ANDROID-1c9928da1656d2e7", cancel.senderUid)
        assertTrue(cancel.cancelled)
        assertTrue(cancel.time > alert.time)
    }

    @Test
    fun parsesAllStandardAtakEmergencyTypesAndUsesTypeWhenEmergencyTextIsMissing() {
        StandardEmergencyType.entries.forEach { standardType ->
            val xml = ATAK_STANDARD_ALERT_XML
                .replace("type='b-a-o-tbl'", "type='${standardType.cotType}'")
                .replace("type='911 Alert'", "type='${standardType.cotDescription}'")
            val event = CotMessageParser.parseEmergencyOrNull(xml)

            assertNotNull(event)
            assertEquals(standardType.displayName, event!!.category)
            assertEquals(standardType.cotDescription, event.description)
        }

        val typeOnlyXml = ATAK_STANDARD_ALERT_XML
            .replace("type='b-a-o-tbl'", "type='b-a-g'")
            .replace("<emergency type='911 Alert'>RA26</emergency>", "")
            .replace("<biometrics alertCategory='Manual Alert'/>", "")
        val typeOnly = CotMessageParser.parseEmergencyOrNull(typeOnlyXml)
        assertEquals("Geofence Breached", typeOnly?.category)
        assertEquals("Geofence Breached", typeOnly?.description)
        assertTrue(CotMessageParser.isEmergencyType("b-a-g"))
    }

    @Test
    fun ownWearTakAlertShapesAreAttributedToTheSendingDevice() {
        val alert = CotMessageParser.parseEmergencyOrNull(WEARTAK_ALERT_XML)
        val cancel = CotMessageParser.parseEmergencyOrNull(WEARTAK_CANCEL_XML)

        assertEquals("WEAROS_056b9ca33004b318", alert?.senderUid)
        assertEquals("Manual Alert", alert?.category)
        assertFalse(alert?.hasLocation ?: true)
        assertTrue(alert?.isFrom("WEAROS_056b9ca33004b318") ?: false)
        assertEquals("WEAROS_056b9ca33004b318", cancel?.senderUid)
        assertTrue(cancel?.cancelled ?: false)
        assertTrue(cancel?.isFrom("WEAROS_056b9ca33004b318") ?: false)
    }

    @Test
    fun rejectsEmergencyWithoutStaleOrWithInvalidPoint() {
        assertNull(CotMessageParser.parseEmergencyOrNull(ATAK_911_XML.replace(" stale=\"2026-10-08T03:12:32.000Z\"", "")))
        assertNull(CotMessageParser.parseEmergencyOrNull(ATAK_911_XML.replace("lat=\"27.95\"", "lat=\"95.0\"")))
        assertNull(CotMessageParser.parseEmergencyOrNull(MARKER_XML))
    }

    @Test
    fun preservesChatAddressingAndParsedRoomShape() {
        assertTrue(CotMessageParser.isChatMessage(CHAT_XML))
        assertTrue(CotMessageParser.isChatForRecipient(CHAT_XML, "watch-1", "EAGLE"))
        assertFalse(CotMessageParser.isChatForRecipient(CHAT_XML, "another-watch", "OTHER"))

        val room = CotMessageParser.parseChatRooms(
            CHAT_XML,
            "watch-1",
            "EAGLE",
            sourceServerId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            receivedAt = 123L
        ).single()
        assertEquals("sender-1", room.uid)
        assertEquals("SENDER", room.title)
        assertEquals("Hello", room.messageList.single().message)
        assertEquals("chat-1", room.messageList.single().messageId)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", room.messageList.single().sourceServerId)
        assertEquals(123L, room.messageList.single().timeReceived)
    }

    @Test
    fun reportsIngressOwnershipCluesWithoutTreatingTeamAsAChannel() {
        val normal = CotMessageParser.ingressDiagnostics(PLI_XML)

        assertEquals("watch-1", normal.uid)
        assertEquals("a-f-G-U-C", normal.type)
        assertEquals("Cyan", normal.teamName)
        assertEquals("Team Member", normal.teamRole)
        assertTrue(normal.explicitChannelStamps.isEmpty())

        val annotated = CotMessageParser.ingressDiagnostics(DIAGNOSTIC_XML)

        assertEquals(listOf("TAK-Server-example"), annotated.flowTagNames)
        assertEquals(listOf("channelId=A_group"), annotated.explicitChannelStamps)
    }

    private companion object {
        const val MARKER_XML = "<event version='2.0' uid='marker-1' type='a-h-G' time='2026-05-01T12:00:00.000Z' start='2026-05-01T12:00:00.000Z' stale='2027-05-01T12:00:00.000Z' how='h-g-i-g-o' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><link uid='watch-1' production_time='2026-05-01T12:00:00.000Z' type='a-f-G-U-C' parent_callsign='EAGLE' relation='p-p'/><remarks>Remark</remarks><contact callsign='Target'/></detail></event>"
        const val EMERGENCY_XML = "<event version='2.0' uid='alert-1' type='b-a-o' time='2026-05-01T12:00:00.000Z' start='2026-05-01T12:00:00.000Z' stale='2026-05-01T12:05:00.000Z' how='h-e'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><link uid='device-1' type='a-f-G-U-C' parent_callsign='SENDER' relation='p-p'/><biometrics alertCategory='CAT_HR_EMERGENCY'><device><uid>device-1</uid></device></biometrics><contact callsign='SENDER'/><emergency type='Medical emergency'>SENDER</emergency></detail></event>"
        const val ATAK_911_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><event version=\"2.0\" uid=\"ANDROID-1c9928da1656d2e7-9-1-1\" type=\"b-a-o-tbl\" time=\"2026-10-08T03:02:32.000Z\" start=\"2026-10-08T03:02:32.000Z\" stale=\"2026-10-08T03:12:32.000Z\" how=\"m-g\"><point lat=\"27.95\" lon=\"-82.51\" hae=\"5.0\" ce=\"10.0\" le=\"9999999.0\"/><detail><link uid=\"ANDROID-1c9928da1656d2e7\" type=\"a-f-G-U-C\" relation=\"p-p\"/><contact callsign=\"RA26-Alert\"/><emergency type=\"911 Alert\">RA26</emergency></detail></event>"
        const val ATAK_STANDARD_ALERT_XML = "<event version='2.0' uid='atak-alert' type='b-a-o-tbl' time='2026-10-08T03:02:32.000Z' start='2026-10-08T03:02:32.000Z' stale='2026-10-08T03:12:32.000Z' how='m-g'><point lat='27.95' lon='-82.51' hae='5.0' ce='10.0' le='9999999.0'/><detail><link uid='atak-device' type='a-f-G-U-C' relation='p-p'/><contact callsign='RA26-Alert'/><biometrics alertCategory='Manual Alert'/><emergency type='911 Alert'>RA26</emergency></detail></event>"
        const val ATAK_911_CANCEL_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><event version=\"2.0\" uid=\"ANDROID-1c9928da1656d2e7-9-1-1\" type=\"b-a-o-can\" time=\"2026-10-08T03:03:01.000Z\" start=\"2026-10-08T03:03:01.000Z\" stale=\"2026-10-08T03:13:01.000Z\" how=\"m-g\"><point lat=\"27.95\" lon=\"-82.51\" hae=\"5.0\" ce=\"10.0\" le=\"9999999.0\"/><detail><link uid=\"ANDROID-1c9928da1656d2e7\" type=\"a-f-G-U-C\" relation=\"p-p\"/><emergency cancel=\"true\">RA26</emergency></detail></event>"
        const val WEARTAK_ALERT_XML = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='34e71ecf-f5b5-4050-a9ee-d92e0ef1ec0f' type='b-a-o' time='2026-10-08T03:03:05.000Z' start='2026-10-08T03:03:05.000Z' stale='2026-10-08T03:08:05.000Z' how='h-e' access='Undefined'><point lat='0.0' lon='0.0' hae='0.0' ce='9999999.0' le='9999999.0'/><detail><biometrics alertUid='34e71ecf-f5b5-4050-a9ee-d92e0ef1ec0f' alertState='ALERT' alertCategory='Manual Alert' alertPriority='1' alertDescription='Manual Alert Pressed by User'><device><model>WEAROS</model><uid>WEAROS_056b9ca33004b318</uid></device></biometrics><link uid='WEAROS_056b9ca33004b318' type='a-f-G-U-C' relation='p-p'/><contact callsign='WATCH&#10;Manual Alert Pressed by User'/><emergency type='Manual Alert Pressed by User'>WATCH</emergency></detail></event>"
        const val WEARTAK_CANCEL_XML = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='34e71ecf-f5b5-4050-a9ee-d92e0ef1ec0f' type='b-a-o-can' time='2026-10-08T03:03:14.000Z' start='2026-10-08T03:03:14.000Z' stale='2026-10-08T03:08:14.000Z' how='h-e' access='Undefined'><point lat='0.0' lon='0.0' hae='0.0' ce='9999999.0' le='9999999.0'/><detail><biometrics alertUid='34e71ecf-f5b5-4050-a9ee-d92e0ef1ec0f' alertState='CANCEL' alertCategory='Manual Alert' alertPriority='1' alertDescription='Manual Alert Pressed by User'><device><model>WEAROS</model><uid>WEAROS_056b9ca33004b318</uid></device></biometrics><emergency cancel='true'>WATCH</emergency></detail></event>"
        const val PLI_XML = "<event version='2.0' uid='watch-1' type='a-f-G-U-C' time='2026-05-01T12:00:00.000Z' start='2026-05-01T12:00:00.000Z' stale='2026-05-01T12:00:30.000Z' how='m-g'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><contact callsign='EAGLE'/><__group role='Team Member' name='Cyan'/></detail></event>"
        const val CHAT_XML = "<event version='2.0' uid='chat-1' type='b-t-f' time='2026-05-01T12:00:00.000Z' start='2026-05-01T12:00:00.000Z' stale='2026-05-01T12:00:00.000Z' how='h-g-i-g-o'><point lat='0' lon='0' hae='0' ce='1' le='1'/><detail><__chat senderCallsign='SENDER' messageId='chat-1'><chatgrp uid0='sender-1' uid1='watch-1' id='watch-1'/></__chat><remarks to='watch-1'>Hello</remarks></detail></event>"
        const val DIAGNOSTIC_XML = "<event uid='watch-1' type='a-f-G-U-C' time='2026-05-01T12:00:00.000Z'><detail><__group role='Team Member' name='Cyan'/><_flow-tags_ TAK-Server-example='2026-05-01T12:00:00.000Z'/><routing channelId='A_group'/></detail></event>"
    }
}
