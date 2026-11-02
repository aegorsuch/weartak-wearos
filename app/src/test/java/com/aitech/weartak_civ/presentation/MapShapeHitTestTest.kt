package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.cot.CotMapShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapShapeHitTestTest {
    @Test
    fun outlineAndFilledPolygonSelectButHoleInteriorDoesNot() {
        val shape = CotMapShape(
            """{"type":"Polygon","coordinates":[[[0,0],[0,10],[10,10],[10,0],[0,0]],[[4,4],[6,4],[6,6],[4,6],[4,4]]]}"""
        )
        val project: (Double, Double) -> MapTargetScreenPoint = { lon, lat ->
            MapTargetScreenPoint(lon * 100, lat * 100)
        }
        assertTrue(mapShapeHitTest(shape, MapTargetScreenPoint(500.0, 200.0), project))
        assertTrue(mapShapeHitTest(shape, MapTargetScreenPoint(0.0, 500.0), project))
        assertFalse(mapShapeHitTest(shape, MapTargetScreenPoint(500.0, 500.0), project))
    }

    @Test
    fun linesAndMultipartPolygonUseFlatAndTiltedCameraProjection() {
        val line = CotMapShape("""{"type":"MultiLineString","coordinates":[[[0,0],[10,0]],[[20,0],[30,0]]]}""")
        val polygon = CotMapShape(
            """{"type":"MultiPolygon","coordinates":[
                [[[0,0],[0,4],[4,4],[4,0],[0,0]]],
                [[[10,0],[10,4],[14,4],[14,0],[10,0]]]
            ]}"""
        )
        val projections = listOf<(Double, Double) -> MapTargetScreenPoint>(
            { lon, lat -> MapTargetScreenPoint(lon * 10, lat * 10) },
            { lon, lat -> MapTargetScreenPoint(lon * 10 + lat, lat * 4 - lon) }
        )
        projections.forEach { project ->
            val linePoint = project(5.0, 0.0)
            assertTrue(mapShapeHitTest(line, linePoint, project))
            val awayFromLine = MapTargetScreenPoint(linePoint.x, linePoint.y + 30.0)
            assertFalse(mapShapeHitTest(line, awayFromLine, project))
            val polygonPoint = project(2.0, 2.0)
            assertTrue("Polygon miss at $polygonPoint", mapShapeHitTest(polygon, polygonPoint, project))
        }
    }
}
