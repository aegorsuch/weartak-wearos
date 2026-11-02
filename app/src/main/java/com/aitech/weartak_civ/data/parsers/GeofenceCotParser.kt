/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.parsers

import android.util.Log
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.model.GeofenceTrigger
import com.aitech.weartak_civ.data.model.LatLon
import org.w3c.dom.Element
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses CoT XML geofence events into a GeofenceCot domain object.
 * E.g. XML:
 * <?xml version="1.0" encoding="UTF-8"?>
 * <event version="2.0" uid="ed8df986-7a80-4e9c-939e-a5acec182cfd" type="u-d-r" how="h-e" time="2026-01-26T13:50:58Z" start="2026-01-26T13:50:46Z" stale="2026-01-27T13:50:46Z" access="Undefined">
 *     <point lat="41.886862" lon="-87.669135" hae="149.708" ce="9999999.0" le="9999999.0"/>
 *     <detail>
 *         <contact callsign="SCIF"/>
 *         <link point="41.88666515011527,-87.66937465901017"/>
 *         <link point="41.88669511977846,-87.66886020847735"/>
 *         <link point="41.887057643538775,-87.66889521959065"/>
 *         <link point="41.88703008627496,-87.6694099129149"/>
 *         <__shapeExtras cpvis="true" editable="true"/>
 *         <archive/>
 *         <strokeColor value="-16744704"/>
 *         <strokeWeight value="3.0"/>
 *         <strokeStyle value="solid"/>
 *         <fillColor value="-1778352384"/>
 *         <tog enabled="0"/>
 *         <labels_on value="false"/>
 *         <__geofence elevationMonitored="false" minElevation="NaN" monitor="All" trigger="Entry" tracking="true" maxElevation="NaN" boundingSphere="75000.0"/>
 *         <precisionlocation altsrc="SRTM1"/>
 *         <remarks/>
 *         <_flow-tags_ TAK-Server-fcfaf5ba02ae4fd98f918f01849dc46d="2026-01-26T13:50:58Z"/>
 *     </detail>
 * </event>
 *
 * Extracted outptus (examples):
 *  - <event ... uid="..." type="..." ...>
 *  - <link point="lat,lon"/>
 *  - <__geofence trigger="Entry|Exit|Both" tracking="true|false" .../>
 *  - <contact callsign="SCIF"/>
 *
 * NOTE:
 *  - "monitor" and "boundingSphere" are intentionally ignored for now.
 *  - Polygon points are normalized to distinct vertices:
 *      If the last point equals the first point, the last point is dropped.
 */
object GeofenceCotParser {

    private const val TAG = "WearTAK-GeofenceCotParser"

    // Fast attribute extraction (works for typical CoT event formatting)
    private fun extractAttr(xmlStr: String, attr: String): String? {
        val r = Regex("""\b$attr\s*=\s*['"]([^'"]+)['"]""")
        return r.find(xmlStr)?.groupValues?.getOrNull(1)
    }

    private fun parseTrigger(value: String?): GeofenceTrigger =
        when (value) {
            "Entry" -> GeofenceTrigger.Entry
            "Exit" -> GeofenceTrigger.Exit
            "Both" -> GeofenceTrigger.Both
            else -> GeofenceTrigger.Both
        }

    private fun parseTracking(xmlStr: String): Boolean? {
        val r = Regex("""<__geofence\b[^>]*\btracking\s*=\s*['"](true|false)['"]""")
        return r.find(xmlStr)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
    }

    private fun parseTriggerFromXml(xmlStr: String): GeofenceTrigger {
        val r = Regex("""<__geofence\b[^>]*\btrigger\s*=\s*['"]([^'"]+)['"]""")
        val raw = r.find(xmlStr)?.groupValues?.getOrNull(1)
        return parseTrigger(raw)
    }

    fun parse(xmlStr: String): GeofenceCot? {
        val uid = extractAttr(xmlStr, "uid") ?: run {
            Log.w(TAG, "Missing uid attribute")
            return null
        }

        val type = extractAttr(xmlStr, "type") ?: run {
            Log.w(TAG, "Missing type attribute")
            return null
        }

        val doc = parseXmlToDoc(xmlStr) ?: return null

        val callsign = extractRequiredCallsign(doc) ?: run {
            Log.w(TAG, "Missing/blank contact callsign")
            return null
        }

        val points = normalizePolygon(extractLinkLatLon(doc))
        val tracking = parseTracking(xmlStr) ?: false
        val trigger = parseTriggerFromXml(xmlStr)
        val eventTime = CotMessageParser.eventTimeOrNull(xmlStr) ?: System.currentTimeMillis()

        return GeofenceCot(
            uid = uid,
            type = type,
            callsign = callsign,
            points = points,
            tracking = tracking,
            trigger = trigger,
            eventTime = eventTime
        )
    }

    private fun parseXmlToDoc(xmlStr: String): Document? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
        }

        return try {
            factory.newDocumentBuilder().parse(xmlStr.byteInputStream())
        } catch (e: Exception) {
            Log.w(TAG, "XML parse failed: ${e.message}")
            null
        }
    }

    private fun extractLinkLatLon(doc: Document): List<LatLon> {
        val nodes = doc.getElementsByTagName("link")
        val points = mutableListOf<LatLon>()

        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val pointAttr = el.getAttribute("point")
            if (pointAttr.isBlank()) continue

            val parts = pointAttr.split(',')
            if (parts.size < 2) continue

            val lat = parts[0].trim().toDoubleOrNull() ?: continue
            val lon = parts[1].trim().toDoubleOrNull() ?: continue

            points.add(LatLon(lat, lon))
        }
        return points
    }

    private fun extractRequiredCallsign(doc: Document): String? {
        val nodes = doc.getElementsByTagName("contact")
        if (nodes.length <= 0) return null
        val el = nodes.item(0) as? Element ?: return null
        val callsign = el.getAttribute("callsign")
        return callsign.takeIf { it.isNotBlank() }
    }

    fun normalizePolygon(points: List<LatLon>): List<LatLon> {
        if (points.size >= 2 && points.first() == points.last()) {
            return points.dropLast(1)
        }
        return points
    }

    // Fast parsing
    val hasTypeUdfRegex = Regex("""\btype\s*=\s*['"]u-d-f['"]""")

    fun isRadioSilenceRectangleGeofence(xmlStr : String): Boolean {
        if (hasTypeUdfRegex.containsMatchIn(xmlStr)) {
            Log.i(TAG, "hasTypeUdfRegex is true")
        }
        return CotMessageParser.isRadioSilenceRectangleGeofence(xmlStr)
    }
}
