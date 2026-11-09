package com.aitech.weartak_civ.presentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import kotlin.math.*

internal data class HeightRange(val base: Double, val top: Double)
internal data class StemSegment(val from: Double, val to: Double, val inside: Boolean)

internal fun stemSegments(agl: Double, buildings: List<HeightRange>): List<StemSegment> {
    val ranges = buildings.filter { it.base.isFinite() && it.top.isFinite() && it.top > it.base }
    val cuts = (listOf(0.0, agl) + ranges.flatMap {
        listOf(it.base.coerceIn(0.0, agl), it.top.coerceIn(0.0, agl))
    }).distinct().sorted()
    return cuts.zipWithNext().filter { it.second > it.first }.map { (from, to) ->
        StemSegment(from, to, ranges.any { (from + to) / 2 in it.base..it.top })
    }
}

internal fun buildingHeightRanges(
    ground: Position,
    features: List<Feature<Geometry, JsonObject?>>
): List<HeightRange> = features.mapNotNull { feature ->
    val properties = feature.properties ?: return@mapNotNull null
    if ((properties["hide_3d"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
    val height = (properties["render_height"] as? JsonPrimitive)?.doubleOrNull
        ?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
    val base = (properties["render_min_height"] as? JsonPrimitive)?.doubleOrNull
        ?.takeIf { it.isFinite() }?.coerceIn(0.0, height) ?: 0.0
    val polygons = when (val geometry = feature.geometry) {
        is Polygon -> listOf(geometry.coordinates)
        is MultiPolygon -> geometry.coordinates
        else -> return@mapNotNull null
    }
    if (polygons.none { polygon ->
        polygon.isNotEmpty() && ringContains(ground, polygon.first()) &&
            polygon.drop(1).none { ringContains(ground, it) }
    }) return@mapNotNull null
    HeightRange(base, height)
}

private fun ringContains(point: Position, ring: List<Position>): Boolean {
    fun longitude(position: Position) =
        ((position.longitude - point.longitude + 540) % 360) - 180
    var inside = false
    if (ring.size < 3) return false
    var previous = ring.last()
    for (current in ring) {
        if ((current.latitude > point.latitude) != (previous.latitude > point.latitude)) {
            val crossing = longitude(current) + (longitude(previous) - longitude(current)) *
                (point.latitude - current.latitude) / (previous.latitude - current.latitude)
            if (crossing > 0) inside = !inside
        }
        previous = current
    }
    return inside
}

internal data class ScreenCoordinate(val x: Double, val y: Double)

/** Lift through the camera's ground-plane homography, not a linear meters-to-pixels guess. */
internal class VerticalProjection private constructor(
    val ground: ScreenCoordinate,
    private val center: ScreenCoordinate,
    private val depthPerMeter: Double,
    private val numeratorYPerMeter: Double
) {
    fun atHeight(meters: Double): ScreenCoordinate? {
        val denominator = 1 - depthPerMeter * meters
        if (!meters.isFinite() || meters < 0 || denominator < 0.05) return null
        return ScreenCoordinate(
            (ground.x - center.x * depthPerMeter * meters) / denominator,
            (ground.y + numeratorYPerMeter * meters) / denominator
        ).takeIf { it.x.isFinite() && it.y.isFinite() }
    }

    companion object {
        fun create(
            ground: ScreenCoordinate,
            forward: ScreenCoordinate,
            backward: ScreenCoordinate,
            center: ScreenCoordinate,
            sampleMeters: Double,
            pitchDegrees: Double
        ): VerticalProjection? {
            if (pitchDegrees !in 22.5..60.0 || sampleMeters <= 0 ||
                listOf(ground, forward, backward, center).any { !it.x.isFinite() || !it.y.isFinite() })
                return null
            val span = forward.y - backward.y
            if (abs(span) < 0.01) return null
            val c = (2 * ground.y - forward.y - backward.y) / (sampleMeters * span)
            val a = (forward.y * (1 + c * sampleMeters) - ground.y) / sampleMeters
            val pitch = Math.toRadians(pitchDegrees)
            val depth = c / tan(pitch)
            if (!depth.isFinite() || depth <= 0) return null
            return VerticalProjection(ground, center, depth,
                (a - center.y * c) * tan(pitch) - center.y * depth)
        }
    }
}

internal fun mercatorForwardSample(position: Position, bearing: Double, meters: Double): Position {
    val latitude = Math.toRadians(position.latitude)
    val angle = Math.toRadians(bearing)
    val distance = meters / cos(latitude)
    val x = Math.toRadians(position.longitude) + distance * sin(angle) / 6_378_137
    val y = asinh(tan(latitude)) + distance * cos(angle) / 6_378_137
    return Position(((Math.toDegrees(x) + 540) % 360) - 180, Math.toDegrees(atan(sinh(y))))
}
