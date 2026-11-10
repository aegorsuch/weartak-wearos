/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.ipc

object AtosCotSerializer {
    fun toCotXml(packet: AtosCotPacket): String {
        val point = packet.point ?: return ""
        val atos = packet.atosDetail
        val track = packet.track
        val contact = packet.contact
        val link = packet.link

        val rootType = packet.type ?: return ""
        val rootUid = packet.uid ?: return ""
        val rootTime = packet.time ?: return ""
        val rootStart = packet.start ?: rootTime
        val rootStale = packet.stale ?: rootTime
        val rootHow = packet.how ?: "m-g"

        val pointLat = point.lat ?: return ""
        val pointLon = point.lon ?: return ""
        val pointHae = point.hae ?: 0.0
        val pointCe = point.ce ?: 9999999.0
        val pointLe = point.le ?: 9999999.0

        return buildString {
            append("<event version=\"")
            append(escapeXml(packet.version ?: "2.0"))
            append("\" uid=\"")
            append(escapeXml(rootUid))
            append("\" type=\"")
            append(escapeXml(rootType))
            append("\" time=\"")
            append(escapeXml(rootTime))
            append("\" start=\"")
            append(escapeXml(rootStart))
            append("\" stale=\"")
            append(escapeXml(rootStale))
            append("\" how=\"")
            append(escapeXml(rootHow))
            append("\">")

            append("<point lat=\"").append(pointLat)
            append("\" lon=\"").append(pointLon)
            append("\" hae=\"").append(pointHae)
            append("\" ce=\"").append(pointCe)
            append("\" le=\"").append(pointLe)
            append("\"/>")

            append("<detail>")

            val callsign = contact?.callsign
            if (callsign != null) {
                append("<contact callsign=\"").append(escapeXml(callsign)).append("\"/>")
            }

            if (track != null) {
                append("<track")
                track.course?.let { append(" course=\"").append(it).append("\"") }
                track.speed?.let { append(" speed=\"").append(it).append("\"") }
                track.vspeed?.let { append(" vspeed=\"").append(it).append("\"") }
                track.slope?.let { append(" slope=\"").append(it).append("\"") }
                append("/>")
            }

            if (atos != null) {
                append("<__atos")
                atos.tagType?.let { append(" tag_type=\"").append(escapeXml(it)).append("\"") }
                atos.color?.let { append(" color=\"").append(escapeXml(it)).append("\"") }
                atos.tagUid?.let { append(" tag_uid=\"").append(it).append("\"") }
                atos.commandLink?.let { append(" commandlink=\"").append(escapeXml(it)).append("\"") }
                atos.tempC?.let { append(" temp_c=\"").append(it).append("\"") }
                atos.voltage?.let { append(" voltage=\"").append(it).append("\"") }
                atos.alarm?.let { append(" alarm=\"").append(it).append("\"") }
                atos.manifest?.let { append(" manifest=\"").append(escapeXml(it)).append("\"") }

                if (atos.attributes.isEmpty()) {
                    append("/>")
                } else {
                    append(">")
                    append("<attributes")
                    atos.attributes.forEach { (key, value) ->
                        append(' ')
                        append(escapeXml(key))
                        append("=\"")
                        append(escapeXml(value))
                        append("\"")
                    }
                    append("/>")
                    append("</__atos>")
                }
            }

            if (link != null) {
                append("<link")
                link.relation?.let { append(" relation=\"").append(escapeXml(it)).append("\"") }
                link.uid?.let { append(" uid=\"").append(escapeXml(it)).append("\"") }
                link.type?.let { append(" type=\"").append(escapeXml(it)).append("\"") }
                link.parentCallsign?.let { append(" parent_callsign=\"").append(escapeXml(it)).append("\"") }
                append("/>")
            }

            if (packet.hasArchive) {
                append("<archive/>")
            }

            append("</detail>")
            append("</event>")
        }
    }

    private fun escapeXml(value: String): String {
        val out = StringBuilder(value.length + 16)
        value.forEach { ch ->
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
