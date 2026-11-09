package com.aitech.weartak_civ.presentation

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cot.CotAffiliation
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.elevation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import java.io.IOException
import kotlin.math.roundToInt

internal data class LollipopTarget(
    val key: String,
    val position: Position,
    val hae: Double?,
    val le: Double?,
    val type: String,
    val title: String,
    val team: String? = null,
    val self: Boolean = false,
    val cleared: Boolean = false,
    val pending: Boolean = false,
    val atos: Boolean = false,
    val alertUid: String? = null
)

internal data class LollipopRender(
    val target: LollipopTarget,
    val agl: Double,
    val projection: VerticalProjection,
    val raised: ScreenCoordinate,
    val segments: List<StemSegment>
)

internal data class LollipopState(val markers: List<LollipopRender> = emptyList())
private data class ResolvedHeight(val target: LollipopTarget, val agl: Double?)

internal class RenderedBuildingFootprints {
    var features: List<Feature<Geometry, JsonObject?>> by mutableStateOf(emptyList())
        private set
    fun onFrame(
        enabled: Boolean,
        moving: Boolean,
        query: () -> List<Feature<Geometry, JsonObject?>>
    ) {
        if (!enabled) {
            features = emptyList()
            return
        }
        if (moving) return
        features = query()
    }
}

@Composable
internal fun rememberBuildingFootprintQuery(
    enabled: Boolean,
    camera: CameraState,
    footprints: RenderedBuildingFootprints
): () -> Unit {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    val height = configuration.screenHeightDp.dp
    val callback by rememberUpdatedState {
        footprints.onFrame(enabled, camera.isCameraMoving, {
            camera.projection?.queryRenderedFeatures(DpRect(0.dp, 0.dp, width, height),
                setOf(BUILDINGS_3D_LAYER)).orEmpty()
        })
    }
    val frames = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(frames) {
        for (frame in frames) {
            // Query after rendering, including the final frame; do not cache an early empty style.
            delay(250)
            callback()
        }
    }
    DisposableEffect(frames) { onDispose { frames.close() } }
    return remember { { frames.trySend(Unit); Unit } }
}

@Composable
internal fun rememberLollipops(
    enabled: Boolean,
    targets: List<LollipopTarget>,
    camera: CameraState,
    buildings: List<Feature<Geometry, JsonObject?>>,
    provider: ElevationProvider = AwsTerrainProvider.get(LocalContext.current)
): LollipopState {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.toDouble()
    val height = configuration.screenHeightDp.toDouble()
    val projection = camera.projection
    val position = camera.position
    val visible = remember(targets, position, projection, width, height, enabled) {
        if (!enabled || projection == null) emptyList() else targets.filter {
            if (!validHae(it.hae)) false else {
                val screen = projection.screenLocationFromPosition(it.position)
                screen.x.value.toDouble() in 0.0..width && screen.y.value.toDouble() in 0.0..height
            }
        }
    }
    val geoid by produceState<Egm96Grid?>(null, enabled) {
        if (enabled && value == null) value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("egm96-15.pgm").use(Egm96Grid::read)
            } catch (error: IOException) {
                Log.e("Lollipop", "EGM96 asset unavailable; altitude disabled", error)
                null
            } catch (error: IllegalArgumentException) {
                Log.e("Lollipop", "EGM96 asset invalid; altitude disabled", error)
                null
            }
        }
    }
    val heights by produceState<Map<String, ResolvedHeight>>(emptyMap(),
        enabled, visible, camera.isCameraMoving, provider, geoid) {
        value = value.filterKeys { key -> targets.any { it.key == key } }
        if (!enabled) {
            value = emptyMap()
            return@produceState
        }
        if (camera.isCameraMoving || visible.isEmpty() || geoid == null) return@produceState
        while (isActive) {
            for (target in visible) {
                val ground = provider.groundAt(target.position.latitude, target.position.longitude)
                val agl = heightAboveGround(target.hae, ground,
                    geoid?.undulation(target.position.latitude, target.position.longitude))
                // Null AGL can mean a valid ground-level marker, not a terrain failure.
                value = value + (target.key to ResolvedHeight(target, agl))
            }
            delay(60_000)
        }
    }
    if (!enabled || projection == null) return LollipopState()
    fun screen(point: Position): ScreenCoordinate {
        val result = projection.screenLocationFromPosition(point)
        return ScreenCoordinate(result.x.value.toDouble(), result.y.value.toDouble())
    }
    val center = screen(position.target)
    val rendered = visible.mapNotNull { target ->
        val resolved = heights[target.key]?.takeIf { it.target == target } ?: return@mapNotNull null
        val agl = resolved.agl ?: return@mapNotNull null
        val vertical = VerticalProjection.create(
            screen(target.position),
            screen(mercatorForwardSample(target.position, position.bearing, 50.0)),
            screen(mercatorForwardSample(target.position, position.bearing, -50.0)),
            center, 50.0, position.tilt
        ) ?: return@mapNotNull null
        val raised = vertical.atHeight(agl) ?: return@mapNotNull null
        LollipopRender(target, agl, vertical, raised,
            stemSegments(agl, buildingHeightRanges(target.position, buildings)))
    }
    return LollipopState(rendered)
}

@Composable
internal fun LollipopOverlay(state: LollipopState, onSelect: (LollipopTarget) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            state.markers.forEach { marker ->
                val ground = marker.projection.ground
                drawCircle(Color.Black.copy(alpha = 0.7f), 4.dp.toPx(),
                    Offset(ground.x.toFloat(), ground.y.toFloat()) * density)
                drawCircle(Color.White, 2.dp.toPx(),
                    Offset(ground.x.toFloat(), ground.y.toFloat()) * density)
                marker.segments.forEach { segment ->
                    val start = marker.projection.atHeight(segment.from) ?: return@forEach
                    val end = marker.projection.atHeight(segment.to) ?: return@forEach
                    val from = Offset(start.x.toFloat(), start.y.toFloat()) * density
                    val to = Offset(end.x.toFloat(), end.y.toFloat()) * density
                    val dash = if (segment.inside) PathEffect.dashPathEffect(floatArrayOf(
                        3.dp.toPx(), 3.dp.toPx())) else null
                    drawLine(Color.Black, from, to, 3.dp.toPx(), pathEffect = dash)
                    drawLine(Color.White, from, to, 1.dp.toPx(), pathEffect = dash)
                }
            }
        }
        state.markers.forEach { marker ->
            key(marker.target.key) {
                val target = marker.target
                Box(
                    Modifier.offset { IntOffset(
                        (marker.raised.x.dp.toPx() - 20.dp.toPx()).roundToInt(),
                        (marker.raised.y.dp.toPx() - 20.dp.toPx()).roundToInt()) }
                        .size(40.dp).semantics { contentDescription = target.title }
                        .clickable(enabled = !target.atos, role = Role.Button,
                            onClick = { onSelect(target) }),
                    contentAlignment = Alignment.Center
                ) {
                    if (target.self || CotMessageParser.isGenericMapType(target.type)) {
                        Canvas(Modifier.size(16.dp)) {
                            drawCircle(Color.White, size.minDimension / 2)
                            drawCircle(if (target.self) TeamColors(target.team.orEmpty())
                                else Color(CotAffiliation.fromType(target.type).argb),
                                size.minDimension / 2 - 1.dp.toPx())
                        }
                    } else {
                        val painter = if (target.alertUid != null) painterResource(R.drawable.ic_alert_triangle)
                        else if (target.atos) atosMarkerPainter(target.team, target.type, true)
                        else if (target.team != null && CotMessageParser.isUserPositionType(target.type) &&
                            markerVisualSpec(target.type).tintArgb == null) {
                            painterResource(userTeamIcon(target.team))
                        } else markerPainter(markerVisualSpec(target.type, cleared = target.cleared))
                        Image(painter, contentDescription = null, modifier = Modifier.size(24.dp),
                            alpha = if (target.pending) 0.5f else 1f)
                    }
                    val label = target.title.takeUnless { target.self }.orEmpty()
                    if (label.isNotEmpty()) Text(label, color = Color.White, fontSize = 9.sp,
                        style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color.Black, Offset(0f, 1f), 3f)),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).requiredWidth(100.dp).offset(y = (-36).dp))
                }
            }
        }
    }
}

private fun userTeamIcon(team: String): Int = when (team) {
    "White" -> R.drawable.user_white
    "Yellow", "Teal" -> R.drawable.user_yellow
    "Orange" -> R.drawable.user_orange
    "Magenta" -> R.drawable.user_magenta
    "Red" -> R.drawable.user_red
    "Maroon" -> R.drawable.user_maroon
    "Purple" -> R.drawable.user_purple
    "Dark Blue" -> R.drawable.user_dark_blue
    "Blue" -> R.drawable.user_blue
    "Cyan" -> R.drawable.user_cyan
    "Green" -> R.drawable.user_green
    "Dark Green" -> R.drawable.user_dark_green
    "Brown" -> R.drawable.user_brown
    else -> R.drawable.user_orange
}
