package com.aitech.weartak_civ.presentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import kotlin.math.*

class LollipopGeometryTest {
    @Test fun renderedFootprintsRefreshAfterInitiallyEmptyFrameWithoutCameraMovement() {
        val center = Position(-74.009, 40.706)
        val polygon = Polygon(listOf(listOf(
            Position(-74.01, 40.705), Position(-74.008, 40.705),
            Position(-74.008, 40.707), Position(-74.01, 40.707), Position(-74.01, 40.705))))
        val building = Feature<Geometry, JsonObject?>(polygon,
            JsonObject(mapOf("render_height" to JsonPrimitive(150))))
        val footprints = RenderedBuildingFootprints()
        footprints.onFrame(true, false, { emptyList() })
        assertEquals(listOf(StemSegment(0.0, 60.0, false)),
            stemSegments(60.0, buildingHeightRanges(center, footprints.features)))
        footprints.onFrame(true, false, { listOf(building) })
        assertEquals(listOf(StemSegment(0.0, 60.0, true)),
            stemSegments(60.0, buildingHeightRanges(center, footprints.features)))
        footprints.onFrame(true, true, { error("Do not query during camera motion") })
        assertEquals(listOf(building), footprints.features)
        footprints.onFrame(false, false, { error("Do not query disabled layer") })
        assertTrue(footprints.features.isEmpty())
    }

    @Test fun perspectiveLiftMatchesIndependentCameraModelAcrossPitchDistanceAndHeight() {
        // Pinhole reference camera, right/forward/vertical coordinates in meters.
        for (pitch in listOf(22.5, 45.0, 60.0)) {
            val angle = Math.toRadians(pitch)
            for (right in listOf(-100.0, 0.0, 100.0)) for (forward in listOf(-100.0, 0.0, 200.0)) {
                val distance = 500.0
                val focal = 340.5
                val center = ScreenCoordinate(101.0, 123.0) // padded viewport
                fun reference(north: Double, height: Double): ScreenCoordinate {
                    val depth = distance + north * sin(angle) - height * cos(angle)
                    return ScreenCoordinate(center.x + focal * right / depth,
                        center.y + focal * (-north * cos(angle) - height * sin(angle)) / depth)
                }
                val ground = reference(forward, 0.0)
                val lift = requireNotNull(VerticalProjection.create(ground,
                    reference(forward + 50, 0.0), reference(forward - 50, 0.0), center, 50.0, pitch))
                for (height in listOf(0.0, 3.1, 60.0, 150.0)) {
                    val expected = reference(forward, height)
                    val actual = requireNotNull(lift.atHeight(height))
                    assertEquals(expected.x, actual.x, 1e-8)
                    assertEquals(expected.y, actual.y, 1e-8)
                }
                assertNull(lift.atHeight(-1.0))
                assertNull(lift.atHeight(10_000.0))
            }
        }
    }

    @Test fun unknownAndOverheadProjectionFallsBackToGround() {
        val point = ScreenCoordinate(100.0, 100.0)
        assertNull(VerticalProjection.create(point, point, point, point, 50.0, 45.0))
        assertNull(VerticalProjection.create(point, point, point, point, 50.0, 0.0))
        assertNull(VerticalProjection.create(point, point, point, point, -1.0, 45.0))
    }

    @Test fun stemsInside150MeterBuildingAreDashedAt60AndSolidAboveRoof() {
        assertEquals(listOf(StemSegment(0.0, 60.0, true)),
            stemSegments(60.0, listOf(HeightRange(0.0, 150.0))))
        assertEquals(listOf(StemSegment(0.0, 150.0, true), StemSegment(150.0, 180.0, false)),
            stemSegments(180.0, listOf(HeightRange(0.0, 150.0))))
        assertEquals(listOf(StemSegment(0.0, 60.0, false)), stemSegments(60.0, emptyList()))
        assertEquals(listOf(StemSegment(0.0, 20.0, false), StemSegment(20.0, 60.0, true)),
            stemSegments(60.0, listOf(HeightRange(20.0, 150.0))))
    }

    @Test fun extrusionLookupUsesFootprintNotForegroundRoofAndHonorsHoles() {
        val center = Position(-74.009, 40.706)
        fun ring(radius: Double) = listOf(
            Position(center.longitude - radius, center.latitude - radius),
            Position(center.longitude + radius, center.latitude - radius),
            Position(center.longitude + radius, center.latitude + radius),
            Position(center.longitude - radius, center.latitude + radius),
            Position(center.longitude - radius, center.latitude - radius))
        fun feature(rings: List<List<Position>>, height: Double, hidden: Boolean = false): Feature<Geometry, JsonObject?> =
            Feature(Polygon(rings), JsonObject(mapOf(
                "render_height" to JsonPrimitive(height),
                "render_min_height" to JsonPrimitive(0.0),
                "hide_3d" to JsonPrimitive(hidden))))
        assertEquals(listOf(HeightRange(0.0, 150.0)),
            buildingHeightRanges(center, listOf(feature(listOf(ring(0.001)), 150.0))))
        assertTrue(buildingHeightRanges(center, listOf(feature(
            listOf(ring(0.001), ring(0.0001)), 150.0))).isEmpty())
        assertTrue(buildingHeightRanges(Position(-74.02, 40.7),
            listOf(feature(listOf(ring(0.001)), 150.0))).isEmpty())
        assertTrue(buildingHeightRanges(center,
            listOf(feature(listOf(ring(0.001)), 150.0, true))).isEmpty())
        assertTrue(buildingHeightRanges(center,
            listOf(feature(listOf(ring(0.001)), -1.0))).isEmpty())
    }

    @Test fun forwardSamplesPreserveMercatorScaleAndWrapDateline() {
        val center = Position(179.9999, 40.0)
        val east = mercatorForwardSample(center, 90.0, 50.0)
        assertTrue(east.longitude < -179.99)
        assertEquals(center.latitude, east.latitude, 1e-10)
        val north = mercatorForwardSample(center, 0.0, 50.0)
        assertTrue(north.latitude > center.latitude)
        assertEquals(center.longitude, north.longitude, 1e-10)
    }
}
