package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import kotlin.math.floor

internal enum class MapTargetKind(val label: Int) {
    CONTACT(R.string.map_target_contact),
    DROPPED(R.string.map_target_dropped),
    RECEIVED(R.string.map_target_received)
}

internal data class MapSelectionTarget(
    val uid: String,
    val title: String,
    val kind: MapTargetKind,
    val lat: Double,
    val lon: Double,
    val sourceServerId: String? = null
) {
    val key: String get() = mapSelectionKey(kind, uid, sourceServerId)
}

internal fun mapSelectionKey(kind: MapTargetKind, uid: String, source: String?): String =
    "${kind.name}:${uid.length}:$uid:${source.orEmpty()}"

internal data class MapTargetScreenPoint(val x: Double, val y: Double)

internal fun groupMapTargets(
    targets: List<MapSelectionTarget>,
    project: (Double, Double) -> MapTargetScreenPoint?,
): List<List<MapSelectionTarget>> {
    val radius = 28.0
    data class Group(val anchor: MapTargetScreenPoint, val targets: MutableList<MapSelectionTarget>)
    val cells = mutableMapOf<Pair<Int, Int>, MutableList<Group>>()
    val groups = mutableListOf<Group>()
    for (target in targets.sortedBy { it.key }) {
        val point = project(target.lat, target.lon)?.takeIf { it.x.isFinite() && it.y.isFinite() } ?: continue
        val x = floor(point.x / radius).toInt()
        val y = floor(point.y / radius).toInt()
        val nearby = (-1..1).asSequence().flatMap { dx ->
            (-1..1).asSequence().flatMap { dy -> cells[x + dx to y + dy].orEmpty().asSequence() }
        }.firstOrNull {
            val dx = point.x - it.anchor.x
            val dy = point.y - it.anchor.y
            dx * dx + dy * dy <= radius * radius
        }
        if (nearby != null) nearby.targets.add(target) else {
            val group = Group(point, mutableListOf(target))
            cells.getOrPut(x to y) { mutableListOf() }.add(group)
            groups.add(group)
        }
    }
    return groups.map { it.targets.toList() }
}

@Composable
internal fun MapTargetPicker(
    targets: List<MapSelectionTarget>,
    onSelect: (MapSelectionTarget) -> Unit,
    onBack: () -> Unit
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
            WearTakScreenDimensions(
                text = stringResource(R.string.map_select_target),
                columnContent = {
                    if (targets.isEmpty()) item { Text(stringResource(R.string.map_no_targets)) }
                    targets.forEach { target ->
                        item(key = target.key) {
                            Chip(
                                onClick = { onSelect(target) },
                                label = { FittedTopMetricText(target.title.ifBlank { target.uid }) },
                                secondaryLabel = { Text(stringResource(target.kind.label)) }
                            )
                        }
                    }

                },
                rowContent = { WearTAKBackButton(onBack) }
            )
        }
    }
}

@Composable
internal fun MapShapeDetailsDialog(
    title: String,
    geometryType: String,
    imported: Boolean,
    onRemove: () -> Unit,
    onBack: () -> Unit
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
            WearTakScreenDimensions(
                text = title,
                columnContent = {
                    item { Text(stringResource(R.string.map_shape_geometry, geometryType)) }
                    if (imported) {
                        item {
                            Chip(
                                onClick = onRemove,
                                label = { Text(stringResource(R.string.delete)) }
                            )
                        }
                    }
                },
                rowContent = { WearTAKBackButton(onBack) }
            )
        }
    }
}
