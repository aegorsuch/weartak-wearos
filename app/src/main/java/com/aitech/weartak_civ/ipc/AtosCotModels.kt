/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.ipc

import com.aitech.weartak_civ.cot.CotAffiliation

data class AtosCotPacket(
    val version: String?,
    val uid: String?,
    val type: String?,
    val time: String?,
    val start: String?,
    val stale: String?,
    val how: String?,
    val point: CotPoint?,
    val contact: CotContact?,
    val track: CotTrack?,
    val atosDetail: AtosDetail?,
    val link: CotLink?,
    val hasArchive: Boolean,
    val rawXmlLength: Int,
    val warnings: List<String> = emptyList()
)

data class AtosMapMarker(
    val uid: String,
    val iconCotType: String,
    val title: String?,
    val lat: Double,
    val lon: Double,
    val color: String?,
    val hae: Double?
)

sealed class AtosMapMarkerResult {
    data class Success(val marker: AtosMapMarker) : AtosMapMarkerResult()
    data class Failure(val reason: String) : AtosMapMarkerResult()
}

data class CotPoint(
    val lat: Double?,
    val lon: Double?,
    val hae: Double?,
    val ce: Double?,
    val le: Double?
)

data class CotContact(
    val callsign: String?
)

data class CotTrack(
    val course: Double?,
    val speed: Double?,
    val vspeed: Double?,
    val slope: Double?
)

data class AtosDetail(
    val tagType: String?,
    val color: String?,
    val tagUid: Int?,
    val commandLink: String?,
    val tempC: Int?,
    val voltage: Double?,
    val alarm: Int?,
    val manifest: String?,
    val attributes: Map<String, String>
)

data class CotLink(
    val relation: String?,
    val uid: String?,
    val type: String?,
    val parentCallsign: String?
)

sealed class AtosCotParseResult {
    data class Success(val packet: AtosCotPacket) : AtosCotParseResult()
    data class Failure(val reason: String) : AtosCotParseResult()
}

enum class AtosIpcRoute {
    LOCAL_INGEST_REQUEST,
    BROADCAST_REQUEST
}

object AtosReferenceTables {
    val tagTypeToCotType: Map<String, String> = mapOf(
        "PAX" to "a-f-G",
        "Personnel" to "a-f-G-U-C-I",
        "K-9" to "a-f-G-A",
        "K9" to "a-f-G-A",
        "Vehicle" to "a-f-G-U-C-I-I",
        "Equipment" to "a-g-G-E",
        "Medical" to "a-f-G-U-S-M",
        "Weapon" to "a-f-G-E-W",
        "Custom" to "a-f-G-c",
        "Bundle" to "a-f-A",
        "Boat" to "a-f-S",
        "Fixed Wing" to "a-f-A-C-F",
        "Rotary Wing" to "a-f-A-C-H",
        "UAS" to "a-f-A-M"
    )

    val teamColorToArgb: Map<String, Long> = mapOf(
        "White" to 0xffffffffL,
        "Yellow" to 0xffffff00L,
        "Orange" to 0xffffa500L,
        "Magenta" to 0xffff00ffL,
        "Red" to 0xffff0000L,
        "Maroon" to 0xff800000L,
        "Purple" to 0xff800080L,
        "Blue" to 0xff0000ffL,
        "Cyan" to 0xff00ffffL,
        "Teal" to 0xff00b2b2L,
        "Green" to 0xff00ff00L,
        "Dark Green" to 0xff004c00L
    )

    fun resolveTagTypeToCotType(tagType: String?): String? {
        val normalized = tagType?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return tagTypeToCotType[normalized]
            ?: tagTypeToCotType.entries.firstOrNull { (key, _) ->
                key.equals(normalized, ignoreCase = true)
            }?.value
    }
}

fun AtosCotPacket.toAtosMapMarkerResult(): AtosMapMarkerResult {
    val markerUid = uid ?: return AtosMapMarkerResult.Failure("missing uid")
    val markerPoint = point ?: return AtosMapMarkerResult.Failure("missing point element")
    val markerLat = markerPoint.lat ?: return AtosMapMarkerResult.Failure("missing point.lat")
    val markerLon = markerPoint.lon ?: return AtosMapMarkerResult.Failure("missing point.lon")
    // ATOS tag_type is the most stable source for local icon selection; packet type is fallback only.
    val resolvedIconCotType = AtosReferenceTables.resolveTagTypeToCotType(atosDetail?.tagType)
        ?.let { tagType ->
            val eventType = type?.trim()
            if (eventType != null && CotAffiliation.isAtomType(eventType)) {
                "a-${eventType[2]}-${tagType.substring(4)}"
            } else tagType
        }
        ?: type?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AtosMapMarkerResult.Failure("missing renderable type and no tag_type fallback")

    return AtosMapMarkerResult.Success(
        AtosMapMarker(
            uid = markerUid,
            iconCotType = resolvedIconCotType,
            title = contact?.callsign,
            lat = markerLat,
            lon = markerLon,
            color = atosDetail?.color,
            hae = markerPoint.hae,
        )
    )
}

fun AtosCotPacket.toAtosMapMarkerOrNull(): AtosMapMarker? {
    return when (val result = toAtosMapMarkerResult()) {
        is AtosMapMarkerResult.Success -> result.marker
        is AtosMapMarkerResult.Failure -> null
    }
}
