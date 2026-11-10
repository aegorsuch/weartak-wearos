/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.ipc

object AtosCotParser {
    private val tagRegex = Regex(
        pattern = "<(/?)([A-Za-z_][A-Za-z0-9._:-]*)([^>]*)>",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val attrRegex = Regex(
        pattern = """([A-Za-z_][A-Za-z0-9._:-]*)\s*=\s*(['"])(.*?)\2""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(cotXml: String): AtosCotParseResult {
        val normalized = cotXml.trim()
        if (normalized.isBlank()) {
            return AtosCotParseResult.Failure("XML was blank")
        }

        val rawBytes = normalized.toByteArray(Charsets.UTF_8)
        if (rawBytes.size > AtosIpcConstants.MAX_COT_XML_BYTES) {
            return AtosCotParseResult.Failure("Payload exceeded ${AtosIpcConstants.MAX_COT_XML_BYTES} bytes")
        }

        val eventStart = normalized.indexOf("<event")
        if (eventStart < 0) {
            return AtosCotParseResult.Failure("Root element was not event")
        }

        val xml = normalized.substring(eventStart)
        val rootMatch = tagRegex.find(xml) ?: return AtosCotParseResult.Failure("Malformed XML: Missing event start tag")
        val rootToken = parseToken(rootMatch)
        if (rootToken.isClosing || !rootToken.name.equals("event", ignoreCase = true)) {
            return AtosCotParseResult.Failure("Root element was not event")
        }

        val warnings = mutableListOf<String>()
        val detailParts = DetailParts()
        val rootAttrs = rootToken.attributes
        val tokens = tagRegex.findAll(xml).map { parseToken(it) }.toList()

        var index = 0
        var sawRoot = false
        var depth = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.isClosing -> {
                    if (depth > 0) depth--
                    if (sawRoot && depth == 0 && token.name.equals("event", ignoreCase = true)) {
                        break
                    }
                }
                token.isSelfClosing -> {
                    when {
                        depth == 1 && token.name.equals("point", ignoreCase = true) -> {
                            detailParts.point = parsePoint(token.attributes, warnings)
                        }
                        depth == 2 && token.name.equals("contact", ignoreCase = true) -> {
                            detailParts.contact = CotContact(callsign = token.attributes["callsign"])
                        }
                        depth == 2 && token.name.equals("track", ignoreCase = true) -> {
                            detailParts.track = parseTrack(token.attributes, warnings)
                        }
                        depth == 2 && token.name.equals("link", ignoreCase = true) -> {
                            detailParts.link = CotLink(
                                relation = token.attributes["relation"],
                                uid = token.attributes["uid"],
                                type = token.attributes["type"],
                                parentCallsign = token.attributes["parent_callsign"]
                            )
                        }
                        depth == 2 && token.name.equals("archive", ignoreCase = true) -> {
                            detailParts.hasArchive = true
                        }
                        depth == 3 && token.name.equals("attributes", ignoreCase = true) -> {
                            detailParts.atosAttributes = token.attributes
                        }
                    }
                }
                else -> {
                    when {
                        !sawRoot && token.name.equals("event", ignoreCase = true) -> {
                            sawRoot = true
                            depth = 1
                        }
                        sawRoot && depth == 1 && token.name.equals("detail", ignoreCase = true) -> {
                            depth = 2
                        }
                        sawRoot && depth == 2 && token.name.equals("__atos", ignoreCase = true) -> {
                            detailParts.atosDetail = parseAtosDetail(token.attributes, warnings)
                            depth = 3
                        }
                        else -> {
                            depth++
                        }
                    }
                }
            }

            index++
        }

        val packet = AtosCotPacket(
            version = rootAttrs["version"],
            uid = rootAttrs["uid"],
            type = rootAttrs["type"],
            time = rootAttrs["time"],
            start = rootAttrs["start"],
            stale = rootAttrs["stale"],
            how = rootAttrs["how"],
            point = detailParts.point,
            contact = detailParts.contact,
            track = detailParts.track,
            atosDetail = detailParts.atosDetail,
            link = detailParts.link,
            hasArchive = detailParts.hasArchive,
            rawXmlLength = rawBytes.size,
            warnings = warnings.toList(),
        ).let { parsedPacket ->
            val atosDetail = parsedPacket.atosDetail
            if (atosDetail == null) {
                parsedPacket
            } else {
                parsedPacket.copy(
                    atosDetail = atosDetail.copy(attributes = detailParts.atosAttributes),
                )
            }
        }

        if (packet.uid.isNullOrBlank()) {
            return AtosCotParseResult.Failure("Missing uid attribute")
        }
        if (packet.type.isNullOrBlank()) {
            return AtosCotParseResult.Failure("Missing type attribute")
        }
        if (packet.atosDetail != null && packet.point == null) {
            warnings += "Point element missing for ATOS detail block"
        }

        packet.atosDetail?.tagType?.let { tagType ->
            val expectedCotType = AtosReferenceTables.resolveTagTypeToCotType(tagType)
            if (expectedCotType != null && packet.type != expectedCotType) {
                warnings += "CoT type '${packet.type}' does not match expected type '$expectedCotType' for ATOS tag_type '$tagType'"
            }
        }

        return AtosCotParseResult.Success(
            packet.copy(warnings = warnings.toList())
        )
    }

    private fun parsePoint(attrs: Map<String, String>, warnings: MutableList<String>): CotPoint {
        return CotPoint(
            lat = parseDouble(attrs["lat"], "point.lat", warnings),
            lon = parseDouble(attrs["lon"], "point.lon", warnings),
            hae = parseDouble(attrs["hae"], "point.hae", warnings),
            ce = parseDouble(attrs["ce"], "point.ce", warnings),
            le = parseDouble(attrs["le"], "point.le", warnings)
        )
    }

    private fun parseTrack(attrs: Map<String, String>, warnings: MutableList<String>): CotTrack {
        return CotTrack(
            course = parseDouble(attrs["course"], "track.course", warnings),
            speed = parseDouble(attrs["speed"], "track.speed", warnings),
            vspeed = parseDouble(attrs["vspeed"], "track.vspeed", warnings),
            slope = parseDouble(attrs["slope"], "track.slope", warnings)
        )
    }

    private fun parseAtosDetail(attrs: Map<String, String>, warnings: MutableList<String>): AtosDetail {
        return AtosDetail(
            tagType = attrs["tag_type"],
            color = attrs["color"],
            tagUid = parseInt(attrs["tag_uid"], "detail.__atos.tag_uid", warnings),
            commandLink = attrs["commandlink"],
            tempC = parseInt(attrs["temp_c"], "detail.__atos.temp_c", warnings),
            voltage = parseDouble(attrs["voltage"], "detail.__atos.voltage", warnings),
            alarm = parseInt(attrs["alarm"], "detail.__atos.alarm", warnings),
            manifest = attrs["manifest"],
            attributes = emptyMap(),
        )
    }

    private fun parseDouble(value: String?, field: String, warnings: MutableList<String>): Double? {
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull() ?: run {
            warnings += "Unable to parse $field='$value' as double"
            null
        }
    }

    private fun parseInt(value: String?, field: String, warnings: MutableList<String>): Int? {
        if (value.isNullOrBlank()) return null
        return value.toIntOrNull() ?: run {
            warnings += "Unable to parse $field='$value' as int"
            null
        }
    }

    private fun parseToken(match: MatchResult): XmlToken {
        val isClosing = match.groupValues[1] == "/"
        val name = match.groupValues[2]
        val rawBody = match.groupValues[3]
        val isSelfClosing = !isClosing && rawBody.trimEnd().endsWith("/")
        val attrBody = rawBody.trim().removeSuffix("/").trim()
        val attrs = linkedMapOf<String, String>()
        attrRegex.findAll(attrBody).forEach { attrMatch ->
            val attrName = attrMatch.groupValues[1]
            val attrValue = attrMatch.groupValues[3]
            attrs[attrName] = attrValue
        }
        return XmlToken(
            name = name,
            isClosing = isClosing,
            isSelfClosing = isSelfClosing,
            attributes = attrs,
        )
    }

    private data class XmlToken(
        val name: String,
        val isClosing: Boolean,
        val isSelfClosing: Boolean,
        val attributes: Map<String, String>,
    )

    private data class DetailParts(
        var point: CotPoint? = null,
        var contact: CotContact? = null,
        var track: CotTrack? = null,
        var atosDetail: AtosDetail? = null,
        var link: CotLink? = null,
        var hasArchive: Boolean = false,
        var atosAttributes: Map<String, String> = emptyMap(),
    )
}
