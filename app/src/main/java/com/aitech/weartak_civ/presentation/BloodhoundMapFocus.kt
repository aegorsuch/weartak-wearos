package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.data.RouteFollow
import com.aitech.weartak_civ.data.model.TakRoutePoint
import kotlin.math.*

data class BloodhoundMapFocus(
    val point: TakRoutePoint? = null,
    val serverId: String? = null,
    val routeUid: String? = null,
    val requestId: Long = System.nanoTime(),
    val pointZoom: Double? = null
)

internal data class RouteCameraFrame(val latitude: Double, val longitude: Double, val zoom: Double)

/** Fit Web Mercator geometry inside the safe central rectangle of a round watch. */
internal fun routeCameraFrame(points: List<TakRoutePoint>, widthDp: Double, heightDp: Double): RouteCameraFrame {
    require(points.isNotEmpty() && points.all(RouteFollow::validLocation))
    val x = points.map { (it.longitude + 180.0) / 360.0 }.sorted()
    // Exclude the largest circular gap so a route crossing the dateline stays local.
    val gap = x.indices.maxBy { i -> (if (i == x.lastIndex) x[0] + 1.0 else x[i + 1]) - x[i] }
    val start = x[(gap + 1) % x.size]
    val unwrapped = x.map { if (it < start) it + 1.0 else it }
    val y = points.map {
        val radians = Math.toRadians(it.latitude.coerceIn(-85.05112878, 85.05112878))
        (1.0 - ln(tan(PI / 4.0 + radians / 2.0)) / PI) / 2.0
    }
    val minX = unwrapped.min()
    val maxX = unwrapped.max()
    val minY = y.min()
    val maxY = y.max()
    val zoomX = log2(widthDp.coerceAtLeast(1.0) * 0.6 / (512.0 * (maxX - minX).coerceAtLeast(1e-12)))
    val zoomY = log2(heightDp.coerceAtLeast(1.0) * 0.6 / (512.0 * (maxY - minY).coerceAtLeast(1e-12)))
    return RouteCameraFrame(
        latitude = Math.toDegrees(atan(sinh(PI * (1.0 - minY - maxY)))),
        longitude = (((minX + maxX) / 2.0 * 360.0) % 360.0) - 180.0,
        zoom = minOf(zoomX, zoomY).coerceIn(0.0, 15.5)
    )
}
