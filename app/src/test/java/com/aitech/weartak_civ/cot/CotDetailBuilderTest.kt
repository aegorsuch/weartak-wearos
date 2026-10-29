package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CotDetailBuilderTest {
    @Test
    fun dittoTrackDetailIncludesFullPhysiologyAndPreservesRemarks() {
        val actual = CotDetailBuilder.dittoTrack(
            monitoredPhysio = physio,
            battery = 80,
            callsign = "EAGLE",
            role = "Team Member",
            team = "Cyan",
            uid = "watch-1",
            course = 45f,
            speed = 3f,
            dittoA = "peer-key",
            dittoIp = "0.0.0.0",
            dittoVersion = "4.10.2",
            dittoDeviceName = "EAGLE Watch",
            remarks = "MOPP0; HR: 72 bpm @ 12:00:00Z",
        )

        assertEquals(
            "<detail>${CotDetailBuilder.takv()}<contact endpoint='*:-1:stcp' callsign='EAGLE'/><uid Droid='watch-1'/><precisionlocation altsrc='GPS' geopointsrc='GPS'/><__group role='Team Member' name='Cyan'/><status battery='80'/><remarks>MOPP0; HR: 72 bpm @ 12:00:00Z</remarks><physiology><device><model>WEAROS</model><uid>watch-1</uid><hr>72</hr><skt>98.6</skt><exert>0.25</exert><bat>80</bat></device></physiology><track course='45.0' speed='3.0'/><ditto a='peer-key' ip='0.0.0.0' version='4.10.2' deviceName='EAGLE Watch'/></detail>",
            actual,
        )
    }

    @Test fun noMonitoredPhysioRetainsOnlyDeviceIdentity() {
        assertEquals("<physiology><device><model>WEAROS</model><uid>watch&amp;1</uid></device></physiology>",
            CotDetailBuilder.physiology("watch&1", null, null))
        val detail = CotDetailBuilder.dittoTrack(null, 80, "EAGLE", "Medic", "Blue",
            "watch-1", 0f, 0f, dittoA = "peer", dittoVersion = "test", dittoDeviceName = "watch")
        for (tag in listOf("hr", "skt", "exert")) assertFalse(detail.contains("<$tag>"))
        assertTrue(detail.contains("<status battery='80'/>"))
        assertTrue(detail.contains("<bat>80</bat>"))
    }

    @Test fun physiologyRejectsInvalidValuesAndFormatsValidZeroesInAnyLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for (invalid in listOf(
                physio.copy(heartRateBpm = 19, skinTempF = Float.NaN, exertion = Double.POSITIVE_INFINITY),
                physio.copy(heartRateBpm = 251, skinTempF = 58f, exertion = -0.1),
                physio.copy(heartRateBpm = null, skinTempF = 114f, exertion = 1.1),
            )) assertEquals(CotDetailBuilder.physiology("watch", null, null),
                CotDetailBuilder.physiology("watch", invalid, null))
            assertTrue(CotDetailBuilder.physiology("watch", physio, null)
                .contains("<hr>72</hr><skt>98.6</skt><exert>0.25</exert>"))
            assertTrue(CotDetailBuilder.physiology("watch", physio.copy(skinTempF = 100f, exertion = 0.0), null)
                .contains("<skt>100</skt><exert>0</exert>"))
        } finally {
            Locale.setDefault(original)
        }
    }

    private val physio = DataRepository.PhysioData(72, 98.6f, 30, 70, 60, .25, null, null)
}
