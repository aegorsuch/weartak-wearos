package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.aitech.weartak_civ.cot.CotAffiliation
import com.aitech.weartak_civ.cot.CotMessageParser
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

internal data class TacticalScreenPoint(val position: Position, val color: Color, val radiusDp: Float)
internal data class TacticalScreenLine(val positions: List<Position>, val color: Color, val widthDp: Float)

internal fun tacticalGroundGeometry(
    routes: List<RouteCollection>,
    markers: List<MapMarkerItem>,
    self: Position?,
    selfColor: Color,
    bloodhound: Position?,
    showRoutePoints: Boolean,
    raisedGrounds: Set<Position> = emptySet()
): Pair<List<TacticalScreenPoint>, List<TacticalScreenLine>> {
    val points = mutableListOf<TacticalScreenPoint>()
    val lines = mutableListOf<TacticalScreenLine>()
    routes.forEach { route ->
        val color = TeamColors(route.color)
        route.toLineString()?.let { lines += TacticalScreenLine(it.coordinates, color, 4f) }
        if (showRoutePoints) route.toPointFeatures().forEach {
            points += TacticalScreenPoint(it.geometry.coordinates, color, 5f)
        }
    }
    markers.filter { CotMessageParser.isGenericMapType(it.markerType) }.forEach { marker ->
        validMapPositionOrNull(marker.lat, marker.lon)?.let { center ->
            val color = Color(CotAffiliation.fromType(marker.markerType).argb)
            if (center !in raisedGrounds) points += TacticalScreenPoint(center, color, 4f)
            marker.circleRadiusMeters?.takeIf { it.isFinite() && it > 0 && it <= 100_000 }?.let {
                circleSegments(circleOutline(center.latitude, center.longitude, it)).forEach { segment ->
                    lines += TacticalScreenLine(segment, color, 2f)
                }
            }
        }
    }
    if (self != null) {
        if (bloodhound != null) lines += TacticalScreenLine(listOf(self, bloodhound), Color.Green, 2f)
        if (self !in raisedGrounds) points += TacticalScreenPoint(self, selfColor, 6f)
    }
    return points to lines
}

// Screen-space safety pass has no map depth buffer and no input handler.
@Composable
internal fun TacticalGeometryOverlay(
    camera: CameraState,
    points: List<TacticalScreenPoint>,
    lines: List<TacticalScreenLine>
) {
    val position = camera.position
    val projection = camera.projection ?: return
    fun project(point: Position): Offset {
        val screen = projection.screenLocationFromPosition(point)
        return Offset(screen.x.value, screen.y.value)
    }
    val projectedPoints = remember(position, projection, points) { points.map { it to project(it.position) } }
    val projectedLines = remember(position, projection, lines) {
        lines.map { it to it.positions.map(::project) }
    }
    Canvas(Modifier.fillMaxSize()) {
        projectedLines.forEach { (line, positions) ->
            val path = Path()
            var started = false
            positions.forEach { point ->
                if (!point.x.isFinite() || !point.y.isFinite()) started = false
                else {
                    if (started) path.lineTo(point.x * density, point.y * density)
                    else path.moveTo(point.x * density, point.y * density)
                    started = true
                }
            }
            drawPath(path, line.color, style = Stroke(line.widthDp * density))
        }
        projectedPoints.forEach { (point, screen) ->
            if (screen.x.isFinite() && screen.y.isFinite()) {
                val center = screen * density
                drawCircle(Color.White, (point.radiusDp + 1f) * density, center)
                drawCircle(point.color, point.radiusDp * density, center)
            }
        }
    }
}
