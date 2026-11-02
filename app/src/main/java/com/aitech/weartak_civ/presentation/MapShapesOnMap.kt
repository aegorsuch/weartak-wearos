package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.cot.CotMapShape
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

@Composable
internal fun MapShapeCanvasOverlay(camera: CameraState, markers: List<MapMarkerItem>) {
    val shapes = remember(markers) { markers.filter { it.mapShape != null } }
    val projection = camera.projection ?: return
    val projected = remember(camera.position, projection, shapes) {
        shapes.map { marker ->
            val shape = requireNotNull(marker.mapShape)
            val paths = mapShapePolygonPaths(shape)
            val lines = mapShapeLineSegments(shape)
            Triple(
                shape,
                paths.map { polygon -> polygon.map { ring -> ring.map(projection::screenLocationFromPosition) } },
                lines.map { line -> line.map(projection::screenLocationFromPosition) }
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        projected.forEach { (shape, polygons, lines) ->
            val stroke = Color(shape.strokeColor ?: CotShapeDefaultColor)
            val fill = shape.fillColor?.let { Color(it) } ?: Color.Transparent
            polygons.forEach { rings ->
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    rings.forEach { ring ->
                        ring.forEachIndexed { index, screen ->
                            val point = Offset(screen.x.value * density, screen.y.value * density)
                            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                        close()
                    }
                }
                if (shape.fillColor != null) drawPath(path, fill)
            }
            lines.forEach { points ->
                val path = Path()
                points.forEachIndexed { index, screen ->
                    val x = screen.x.value * density
                    val y = screen.y.value * density
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, stroke, style = Stroke((shape.strokeWidth ?: 2f).coerceIn(0.5f, 20f) * density))
            }
        }
    }
}

internal fun mapShapeLineSegments(shape: CotMapShape): List<List<Position>> {
    val geometry = JsonParser.parseString(shape.geometryJson).asJsonObject
    val type = geometry.get("type").asString
    val coordinates = geometry.getAsJsonArray("coordinates")
    fun positions(value: JsonArray): List<Position> = value.mapNotNull { coordinate ->
        if (!coordinate.isJsonArray || coordinate.asJsonArray.size() < 2) return@mapNotNull null
        validMapPositionOrNull(coordinate.asJsonArray[1].asDouble, coordinate.asJsonArray[0].asDouble)
    }
    val lineArrays = when (type) {
        "LineString" -> listOf(coordinates)
        "MultiLineString", "Polygon" -> coordinates.map { it.asJsonArray }
        "MultiPolygon" -> coordinates.flatMap { polygon ->
            polygon.asJsonArray.map { it.asJsonArray }
        }
        else -> emptyList()
    }
    return lineArrays.mapNotNull { segment ->
        val points = positions(segment)
        points.takeIf { it.size >= 2 }
    }
}

internal fun mapShapePolygonPaths(shape: CotMapShape): List<List<List<Position>>> {
    val geometry = JsonParser.parseString(shape.geometryJson).asJsonObject
    val coordinates = geometry.getAsJsonArray("coordinates")
    fun ring(value: JsonArray): List<Position> = value.mapNotNull { item ->
        if (!item.isJsonArray || item.asJsonArray.size() < 2) return@mapNotNull null
        validMapPositionOrNull(item.asJsonArray[1].asDouble, item.asJsonArray[0].asDouble)
    }
    return when (geometry.get("type").asString) {
        "Polygon" -> listOf(coordinates.mapNotNull { if (it.isJsonArray) ring(it.asJsonArray) else null })
        "MultiPolygon" -> coordinates.mapNotNull { polygon ->
            if (polygon.isJsonArray) polygon.asJsonArray.mapNotNull {
                if (it.isJsonArray) ring(it.asJsonArray) else null
            } else null
        }
        else -> emptyList()
    }
}

private const val CotShapeDefaultColor = 0xffffcd57.toInt()
