package com.aitech.weartak_civ.presentation

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

internal class MapCameraPreferences(private val preferences: SharedPreferences) {
    fun load(): CameraPosition? {
        val saved = preferences.getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(saved)
            val position = CameraPosition(
                target = Position(json.getDouble("longitude"), json.getDouble("latitude")),
                zoom = json.getDouble("zoom"), bearing = json.getDouble("bearing"))
            require(valid(position)) { "Invalid saved map camera" }
            position
        } catch (error: JSONException) {
            Log.w("MapCamera", "Cannot read saved camera; waiting for location", error)
            null
        } catch (error: IllegalArgumentException) {
            Log.w("MapCamera", "Invalid saved camera; waiting for location", error)
            null
        }
    }

    fun save(position: CameraPosition) {
        if (!valid(position)) {
            Log.w("MapCamera", "Refusing invalid map camera")
            return
        }
        val saved = JSONObject().put("longitude", position.target.longitude)
            .put("latitude", position.target.latitude).put("zoom", position.zoom)
            .put("bearing", position.bearing).toString()
        if (preferences.getString(KEY, null) != saved)
            preferences.edit().putString(KEY, saved).apply()
    }

    private fun valid(position: CameraPosition) =
        position.target.longitude.isFinite() && position.target.longitude in -180.0..180.0 &&
            position.target.latitude.isFinite() && position.target.latitude in -85.05112878..85.05112878 &&
            position.zoom.isFinite() && position.zoom in 0.0..22.0 && position.bearing.isFinite()

    private companion object { const val KEY = "map_camera" }
}

internal class MapCameraBootstrap(
    private val initial: CameraPosition,
    waitingForFirstFix: Boolean,
    canPersistInitial: Boolean
) {
    private var waitingForFix = waitingForFirstFix
    var canPersist = canPersistInitial
        private set

    fun userPanned() { waitingForFix = false }

    fun observe(position: CameraPosition) {
        if (kotlin.math.abs(position.target.longitude - initial.target.longitude) > 1e-7 ||
            kotlin.math.abs(position.target.latitude - initial.target.latitude) > 1e-7 ||
            kotlin.math.abs(position.zoom - initial.zoom) > 1e-5 ||
            kotlin.math.abs(position.bearing - initial.bearing) > 1e-5) {
            waitingForFix = false
            canPersist = true
        }

    }

    fun firstFix(position: Position?, hasMapFocus: Boolean): Position? {
        if (hasMapFocus) waitingForFix = false
        if (!waitingForFix || position == null) return null
        waitingForFix = false
        canPersist = true
        return position
    }
}

internal class MapOpenSession {
    private var opened = false
    fun claimFirstOpen(): Boolean = (!opened).also { opened = true }
}

internal val processMapOpenSession = MapOpenSession()

internal fun initialMapCamera(
    saved: CameraPosition?,
    self: Position?,
    firstOpen: Boolean,
    hasMapFocus: Boolean
): CameraPosition {
    val fallback = saved ?: CameraPosition(target = Position(0.0, 0.0), zoom = 15.5)
    return if (firstOpen && self != null && !hasMapFocus) fallback.copy(target = self) else fallback
}
