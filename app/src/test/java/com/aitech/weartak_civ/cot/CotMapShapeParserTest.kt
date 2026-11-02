package com.aitech.weartak_civ.cot

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CotMapShapeParserTest {
    private fun event(type: String, detail: String) = """
        <event version="2.0" uid="shape-1" type="$type" how="h-e"
            time="2026-10-31T12:00:00Z" start="2026-10-31T12:00:00Z"
            stale="2099-10-31T12:00:00Z">
          <point lat="41.0" lon="-87.0" hae="180" ce="5" le="5"/>
          <detail><contact callsign="Test shape"/>$detail</detail>
        </event>
    """.trimIndent()

    @Test
    fun parsesFreehandPolylineAndRectangleFromLinkCoordinates() {
        val line = CotMessageParser.parseMarkerOrNull(event(
            "u-d-f",
            """<link point="41.0,-87.0"/><link point="41.1,-87.1"/>"""
        ))
        assertNotNull(line)
        val lineGeometry = JsonParser.parseString(line!!.detail.mapShape!!.geometryJson).asJsonObject
        assertEquals("LineString", lineGeometry["type"].asString)
        assertEquals(-87.0, lineGeometry["coordinates"].asJsonArray[0].asJsonArray[0].asDouble, 0.0)
        assertTrue(CotMessageParser.isGenericMapType(line.type))

        val rectangle = CotMessageParser.parseMarkerOrNull(event(
            "u-d-r",
            """<link point="41.0,-87.0"/><link point="41.0,-86.9"/>
               <link point="41.1,-86.9"/><link point="41.1,-87.0"/>"""
        ))
        assertNotNull(rectangle)
        val polygon = JsonParser.parseString(rectangle!!.detail.mapShape!!.geometryJson).asJsonObject
        assertEquals("Polygon", polygon["type"].asString)
        assertEquals(5, polygon["coordinates"].asJsonArray[0].asJsonArray.size())
    }

    @Test
    fun parsesUnequalEllipseAndFilledCircleWithStyle() {
        val ellipse = CotMessageParser.parseMarkerOrNull(event(
            "u-d-e", """<ellipse major="900" minor="250" angle="30"/>"""
        ))
        assertNotNull(ellipse)
        val polygon = JsonParser.parseString(ellipse!!.detail.mapShape!!.geometryJson).asJsonObject
        assertEquals("Polygon", polygon["type"].asString)
        assertEquals(65, polygon["coordinates"].asJsonArray[0].asJsonArray.size())

        val circle = CotMessageParser.parseMarkerOrNull(event(
            "u-d-c-c", """<ellipse major="500" minor="500" angle="0"/>
                <strokeColor value="-16711936"/><fillColor value="-1778384896"/>
                <strokeWeight value="3.5"/>"""
        ))
        assertNotNull(circle)
        assertEquals(500.0, circle!!.detail.circleRadiusMeters!!, 0.0)
        assertEquals(-16711936, circle.detail.mapShape!!.strokeColor)
        assertEquals(-1778384896, circle.detail.mapShape.fillColor)
        assertEquals(3.5f, circle.detail.mapShape.strokeWidth)
    }

    @Test
    fun rejectsMalformedShapeInsteadOfTreatingItAsPoint() {
        assertNull(CotMessageParser.parseMarkerOrNull(event("u-d-f", """<link point="91,-87"/>""")))
        val nonCircularCircle = CotMessageParser.parseMarkerOrNull(event(
            "u-d-c-c", """<ellipse major="500" minor="400"/>"""
        ))
        assertNotNull(nonCircularCircle)
        assertNull(nonCircularCircle!!.detail.circleRadiusMeters)
        assertNull(nonCircularCircle.detail.mapShape)
        assertNull(CotMessageParser.parseMarkerOrNull(event("u-d-r", "")))
        assertNull(CotMessageParser.parseMarkerOrNull(event(
            "u-d-r",
            """<link point="41.0,-87.0"/><link point="41.0,-86.9"/>
                <link point="41.1,-86.9"/><strokeColor value="not-a-color"/>"""
        )))
    }

    @Test
    fun safetyRectangleGeofencesNeverFallThroughAsMapShapes() {
        val xml = event(
            "u-d-r",
            """<contact callsign="SCIF"/>
                <link point="41.0,-87.0"/><link point="41.0,-86.9"/>
                <link point="41.1,-86.9"/><link point="41.1,-87.0"/>"""
        )
        assertTrue(com.aitech.weartak_civ.data.parsers.GeofenceCotParser
            .isRadioSilenceRectangleGeofence(xml))
        assertNull(CotMessageParser.parseMarkerOrNull(xml))
    }
}
