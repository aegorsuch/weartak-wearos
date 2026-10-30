package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.aitech.weartak_civ.cot.CotAffiliation
import com.aitech.weartak_civ.cot.CotMessageParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.expressions.dsl.Feature.get
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.TextPitchAlignment
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Feature.Companion.getStringProperty
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal fun circleOutline(lat: Double, lon: Double, radiusMeters: Double): List<Position> {
    require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
    require(radiusMeters.isFinite() && radiusMeters > 0 && radiusMeters <= 100_000)
    val latitude = Math.toRadians(lat)
    val longitude = Math.toRadians(lon)
    val angle = radiusMeters / 6_371_000.0
    val points = (0 until 36).map { index ->
        val bearing = Math.toRadians(index * 10.0)
        val y = asin((sin(latitude) * cos(angle) + cos(latitude) * sin(angle) * cos(bearing)).coerceIn(-1.0, 1.0))
        val x = longitude + atan2(sin(bearing) * sin(angle) * cos(latitude), cos(angle) - sin(latitude) * sin(y))
        Position(((Math.toDegrees(x) + 540) % 360) - 180, Math.toDegrees(y))
    }
    return points + listOf(points.first())
}

internal fun circleSegments(outline: List<Position>): List<List<Position>> {
    val segments = mutableListOf<List<Position>>()
    var segment = mutableListOf(outline.first())
    outline.zipWithNext().forEach { (previous, next) ->
        if (abs(previous.longitude - next.longitude) > 180) {
            val boundary = if (previous.longitude > 0) 180.0 else -180.0
            val unwrapped = next.longitude + if (previous.longitude > 0) 360 else -360
            val fraction = (boundary - previous.longitude) / (unwrapped - previous.longitude)
            val latitude = previous.latitude + fraction * (next.latitude - previous.latitude)
            segment.add(Position(boundary, latitude))
            segments.add(segment)
            segment = mutableListOf(Position(-boundary, latitude))
        }
        segment.add(next)
    }
    segments.add(segment)
    return segments
}

@Composable
internal fun GenericPointsOnMap(
    markers: List<MapMarkerItem>,
    collection: MarkerLayerCollection,
    camera: CameraState,
    onClick: (String, String) -> Unit,
    onSelectionKey: ((String) -> Unit)? = null
) {
    val groups = remember(markers) {
        markers.filter { CotMessageParser.isGenericMapType(it.markerType) }
            .groupBy { CotAffiliation.fromType(it.markerType) }
    }
    groups.forEach { (affiliation, points) ->
        androidx.compose.runtime.key(collection, affiliation) {
            val features = remember(points) {
                FeatureCollection(points.mapNotNull {
                    val position = validMapPositionOrNull(it.lat, it.lon) ?: return@mapNotNull null
                    Feature(Point(position), JsonObject(mapOf(
                        "id" to JsonPrimitive(it.uid), "title" to JsonPrimitive(it.mapLabel ?: it.title),
                        "selectionKey" to JsonPrimitive(it.selectionKey.orEmpty())
                    )))
                })
            }
            val source = rememberGeoJsonSource(data = features.toSafeGeoJsonData())
            val id = "generic-${collection.name}-${affiliation.name}"
            val color = Color(affiliation.argb)
            CircleLayer(
                id = "$id-dot", source = source, radius = const(4.dp), color = const(color),
                onClick = { hits ->
                    val hit = hits.firstOrNull() ?: return@CircleLayer ClickResult.Pass
                    if (onSelectionKey != null) onSelectionKey(hit.getStringProperty("selectionKey").orEmpty())
                    else onClick(hit.getStringProperty("id").orEmpty(), hit.getStringProperty("title").orEmpty())
                    ClickResult.Consume
                }
            )
            SymbolLayer(
                id = "$id-label", source = source,
                textPitchAlignment = const(TextPitchAlignment.Viewport),
                onClick = { hits ->
                    val hit = hits.firstOrNull() ?: return@SymbolLayer ClickResult.Pass
                    if (onSelectionKey != null) onSelectionKey(hit.getStringProperty("selectionKey").orEmpty())
                    else onClick(hit.getStringProperty("id").orEmpty(), hit.getStringProperty("title").orEmpty())
                    ClickResult.Consume
                },
                textField = if (camera.position.zoom > 10) get("title").asString() else const(""),
                textSize = const(0.35.em), textColor = const(Color.White),
                textHaloColor = const(Color.Black), textHaloWidth = const(2.dp),
                textAnchor = const(SymbolAnchor.Bottom), textOffset = offset(0.em, (-0.5).em),
                textAllowOverlap = const(false)
            )
            val circles = remember(points) {
                FeatureCollection(points.mapNotNull { point ->
                    val center = validMapPositionOrNull(point.lat, point.lon) ?: return@mapNotNull null
                    point.circleRadiusMeters?.takeIf { it.isFinite() && it > 0 && it <= 100_000 }?.let { radius ->
                        Feature(
                            MultiLineString(circleSegments(circleOutline(
                                center.latitude, center.longitude, radius
                            ))),
                            JsonObject(emptyMap())
                        )
                    }
                })
            }
            if (circles.features.isNotEmpty()) {
                val outlineSource = rememberGeoJsonSource(data = circles.toSafeGeoJsonData())
                LineLayer(id = "$id-circles", source = outlineSource, color = const(color), width = const(2.dp))
            }
        }
    }
}
