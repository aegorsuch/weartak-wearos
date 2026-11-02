package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.cot.CotMapShape
import org.junit.Assert.assertEquals
import org.junit.Test

class MapShapeRenderGeometryTest {
    @Test
    fun polygonHolesAndMultipartRingsReachCanvasRendererWithoutLoss() {
        val polygon = CotMapShape(
            """{"type":"Polygon","coordinates":[[[0,0],[0,10],[10,10],[10,0],[0,0]],[[2,2],[8,2],[8,8],[2,8],[2,2]]]}"""
        )
        val polygonPaths = mapShapePolygonPaths(polygon)
        assertEquals(1, polygonPaths.size)
        assertEquals(2, polygonPaths.single().size)
        assertEquals(2, mapShapeLineSegments(polygon).size)

        val multi = CotMapShape(
            """{"type":"MultiPolygon","coordinates":[
                [[[0,0],[0,4],[4,4],[4,0],[0,0]]],
                [[[10,0],[10,4],[14,4],[14,0],[10,0]]]
            ]}"""
        )
        assertEquals(2, mapShapePolygonPaths(multi).size)
    }

    @Test
    fun lineAndCircleGeometriesRemainStrokePaths() {
        val line = CotMapShape("""{"type":"MultiLineString","coordinates":[[[0,0],[1,1]],[[2,2],[3,3]]]}""")
        assertEquals(2, mapShapeLineSegments(line).size)
        assertEquals(0, mapShapePolygonPaths(line).size)
    }
}
