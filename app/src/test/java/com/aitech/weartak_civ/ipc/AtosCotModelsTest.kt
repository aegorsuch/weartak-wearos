/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtosCotModelsTest {
    @Test fun tagTypeSelectsSymbolWithoutOverwritingPacketAffiliation() {
        for (affiliation in listOf("f", "a", "h", "s", "j", "k", "n", "u", "p", "o")) {
            val parsed = AtosCotParser.parse("""
                <event uid="aircraft" type="a-$affiliation-G-c">
                    <point lat="35" lon="-78" hae="9999999"/>
                    <detail><contact callsign="AIRCRAFT"/><__atos tag_type="Fixed Wing" color="Blue"></__atos></detail>
                </event>
            """.trimIndent())
            val packet = (parsed as AtosCotParseResult.Success).packet
            assertEquals("a-$affiliation-A-C-F", packet.toAtosMapMarkerOrNull()?.iconCotType)
            assertEquals(9999999.0, packet.toAtosMapMarkerOrNull()?.hae)
        }
    }

    @Test
    fun toAtosMapMarkerPrefersAtosTagTypeWhenPacketCotTypeDisagrees() {
        val packet = AtosCotPacket(
            version = "2.0",
            uid = "atos-1",
            type = "a-f-G-c",
            time = null,
            start = null,
            stale = null,
            how = null,
            point = CotPoint(lat = 35.0, lon = -78.0, hae = 10.0, ce = null, le = null),
            contact = CotContact(callsign = "ATOS-1"),
            track = null,
            atosDetail = AtosDetail(
                tagType = "K9",
                color = "Maroon",
                tagUid = 1,
                commandLink = null,
                tempC = null,
                voltage = null,
                alarm = null,
                manifest = null,
                attributes = emptyMap(),
            ),
            link = null,
            hasArchive = false,
            rawXmlLength = 0,
        )

        val marker = packet.toAtosMapMarkerOrNull()

        requireNotNull(marker)
        assertEquals("a-f-G-A", marker.iconCotType)
        assertEquals("ATOS-1", marker.title)
        assertEquals("Maroon", marker.color)
    }

    @Test
    fun toAtosMapMarkerFallsBackFromTagTypeWhenCotTypeMissing() {
        val packet = AtosCotPacket(
            version = "2.0",
            uid = "atos-2",
            type = null,
            time = null,
            start = null,
            stale = null,
            how = null,
            point = CotPoint(lat = 35.0, lon = -78.0, hae = 9999999.0, ce = null, le = null),
            contact = CotContact(callsign = "ATOS-2"),
            track = null,
            atosDetail = AtosDetail(
                tagType = "Personnel",
                color = "Blue",
                tagUid = 2,
                commandLink = null,
                tempC = null,
                voltage = null,
                alarm = null,
                manifest = null,
                attributes = emptyMap(),
            ),
            link = null,
            hasArchive = false,
            rawXmlLength = 0,
        )

        val marker = packet.toAtosMapMarkerOrNull()

        requireNotNull(marker)
        assertEquals("a-f-G-U-C-I", marker.iconCotType)
    }

    @Test
    fun toAtosMapMarkerFallsBackToPacketCotTypeWhenTagTypeUnknown() {
        val packet = AtosCotPacket(
            version = "2.0",
            uid = "atos-unknown",
            type = "a-f-A-C-H",
            time = null,
            start = null,
            stale = null,
            how = null,
            point = CotPoint(lat = 35.0, lon = -78.0, hae = 9999999.0, ce = null, le = null),
            contact = CotContact(callsign = "ATOS-UNKNOWN"),
            track = null,
            atosDetail = AtosDetail(
                tagType = "Something Else",
                color = "Blue",
                tagUid = 2,
                commandLink = null,
                tempC = null,
                voltage = null,
                alarm = null,
                manifest = null,
                attributes = emptyMap(),
            ),
            link = null,
            hasArchive = false,
            rawXmlLength = 0,
        )

        val marker = packet.toAtosMapMarkerOrNull()

        requireNotNull(marker)
        assertEquals("a-f-A-C-H", marker.iconCotType)
    }

    @Test
    fun toAtosMapMarkerRejectsPacketsWithoutCoordinates() {
        val packet = AtosCotPacket(
            version = "2.0",
            uid = "atos-3",
            type = "a-f-G",
            time = null,
            start = null,
            stale = null,
            how = null,
            point = CotPoint(lat = null, lon = -78.0, hae = null, ce = null, le = null),
            contact = CotContact(callsign = "ATOS-3"),
            track = null,
            atosDetail = null,
            link = null,
            hasArchive = false,
            rawXmlLength = 0,
        )

        assertNull(packet.toAtosMapMarkerOrNull())
    }
}
