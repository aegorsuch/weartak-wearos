package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.cot.CotMapShape
import com.google.gson.JsonArray
import com.google.gson.JsonParser
internal fun mapShapeHitTest(
    shape: CotMapShape,
    screenPoint: MapTargetScreenPoint,
    project: (longitude: Double, latitude: Double) -> MapTargetScreenPoint?
): Boolean {
    val geometry = JsonParser.parseString(shape.geometryJson).asJsonObject
    val type = geometry.get("type")?.asString ?: return false
    val coordinates = geometry.get("coordinates")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false

    fun ring(value: JsonArray): List<MapTargetScreenPoint>? {
        val points = value.mapNotNull { item ->
            if (!item.isJsonArray || item.asJsonArray.size() < 2) return@mapNotNull null
            val lon = item.asJsonArray[0].asDouble
            val lat = item.asJsonArray[1].asDouble
            if (!lon.isFinite() || !lat.isFinite()) return@mapNotNull null
            project(lon, lat)?.takeIf { it.x.isFinite() && it.y.isFinite() }
        }
        return points.takeIf { it.size >= 2 && it.size == value.size() }
    }

    fun asRings(value: JsonArray): List<List<MapTargetScreenPoint>> =
        value.mapNotNull { if (it.isJsonArray) ring(it.asJsonArray) else null }

    val lines = mutableListOf<List<MapTargetScreenPoint>>()
    val polygons = mutableListOf<List<List<MapTargetScreenPoint>>>()
    when (type) {
        "LineString" -> ring(coordinates)?.let(lines::add)
        "MultiLineString" -> coordinates.forEach { if (it.isJsonArray) ring(it.asJsonArray)?.let(lines::add) }
        "Polygon" -> asRings(coordinates).also { rings ->
            lines.addAll(rings)
            if (rings.isNotEmpty()) polygons.add(rings)
        }
        "MultiPolygon" -> coordinates.forEach { polygon ->
            if (polygon.isJsonArray) asRings(polygon.asJsonArray).also { rings ->
                lines.addAll(rings)
                if (rings.isNotEmpty()) polygons.add(rings)
            }
        }
        else -> return false
    }

    val toleranceSquared = 14.0 * 14.0
    if (lines.any { points ->
            points.zipWithNext().any { (a, b) -> segmentDistanceSquared(screenPoint, a, b) <= toleranceSquared }
        }) return true

    return polygons.any { rings ->
        pointInRing(screenPoint, rings.first()) && rings.drop(1).none { pointInRing(screenPoint, it) }
    }
}

private fun pointInRing(point: MapTargetScreenPoint, ring: List<MapTargetScreenPoint>): Boolean {
    var inside = false
    var previous = ring.last()
    for (current in ring) {
        if ((current.y > point.y) != (previous.y > point.y) &&
            point.x < (previous.x - current.x) * (point.y - current.y) /
            (previous.y - current.y) + current.x
        ) inside = !inside
        previous = current
    }
    return inside
}

private fun segmentDistanceSquared(
    point: MapTargetScreenPoint,
    start: MapTargetScreenPoint,
    end: MapTargetScreenPoint
): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    val ratio = if (lengthSquared == 0.0) 0.0 else
        (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    val closestX = start.x + ratio * dx
    val closestY = start.y + ratio * dy
    val distanceX = point.x - closestX
    val distanceY = point.y - closestY
    return distanceX * distanceX + distanceY * distanceY
}
