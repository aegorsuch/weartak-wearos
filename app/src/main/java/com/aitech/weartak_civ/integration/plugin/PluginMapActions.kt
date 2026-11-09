package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.os.Bundle
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MapActivity
import com.aitech.weartak_civ.service.BloodhoundService
import com.aitech.weartak_civ.presentation.requestBloodhoundComplicationUpdate
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import java.util.UUID

internal class PluginMapActions(private val app: WearTakApplication, private val pluginPackage: String) {
    private val targetPrefix = "plugin:$pluginPackage:"

    fun activeIncidentUid(): String? = app.bhRepo.activeUid.value
        ?.takeIf { it.startsWith(targetPrefix) }
        ?.removePrefix(targetPrefix)

    private data class Target(
        val uid: String,
        val label: String,
        val lat: Double,
        val lon: Double,
        val type: String = "b-m-p-s-m",
        val user: DataRepository.ReceivedUser? = null,
        val localMarker: Boolean = false,
        val existingBloodhound: Boolean = false,
    )

    fun execute(request: PluginMapRequest): Bundle {
        if (request.action == C.ACTION_STOP_BLOODHOUND) {
            val uid = request.uid ?: return C.result(C.INVALID_REQUEST, "Incident UID required")
            if (!app.bhRepo.stopTracking("$targetPrefix$uid")) {
                return C.result(C.NOT_FOUND, "Incident is not actively tracked")
            }
            requestBloodhoundComplicationUpdate(app)
            return C.result(C.OK).apply { putString(C.KEY_UID, uid) }
        }
        val target = resolve(request) ?: return C.result(C.NOT_FOUND, "UID has no known location")
        if (!target.lat.isFinite() || !target.lon.isFinite() ||
            target.lat !in -90.0..90.0 || target.lon !in -180.0..180.0) {
            return C.result(C.INVALID_REQUEST, "Target has invalid location")
        }
        if (request.action == C.ACTION_TARGET) {
            app.startActivity(Intent(app, MapActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MapActivity.EXTRA_FOCUS_LAT, target.lat)
                putExtra(MapActivity.EXTRA_FOCUS_LON, target.lon)
            })
        } else {
            if (!BloodhoundService.isRunning.get()) {
                return C.result(C.UNAVAILABLE, "Bloodhound service is not running")
            }
            if (target.user != null) {
                if (!app.bhRepo.addUserTarget(target.user)) {
                    return C.result(C.INVALID_REQUEST, "Contact cannot be tracked")
                }
            } else if (!target.existingBloodhound) {
                app.bhRepo.addMarkerTarget(target.uid, target.label, target.type, target.lat, target.lon,
                    System.currentTimeMillis(), tracksLocalMarker = target.localMarker)
            }
            app.bhRepo.requestActivateBloodhound(target.uid)
            requestBloodhoundComplicationUpdate(app)
            if (request.returnToMain) {
                app.startActivity(Intent(app, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        }
        return C.result(C.OK).apply { putString(C.KEY_UID, target.uid) }
    }

    private fun resolve(request: PluginMapRequest): Target? {
        if (request.latitude != null && request.longitude != null) {
            return Target("$targetPrefix${request.uid ?: UUID.randomUUID()}",
                request.label ?: request.uid ?: pluginPackage, request.latitude, request.longitude)
        }
        val uid = request.uid ?: return null
        if (uid == MainActivity.device_uid) return null
        app.repo.receivedUserList.value.firstOrNull { it.uid == uid }?.let { user ->
            user.location?.let {
                return Target(uid, user.title, it.lat, it.lon, user.type, user = user)
            }
        }
        app.bhRepo.bloodhoundMap.value[uid]?.let {
            return Target(uid, it.target.name ?: uid, it.target.point.lat, it.target.point.lon,
                existingBloodhound = true)
        }
        app.repo.droppedMarkerList.value.firstOrNull { it.uid == uid }?.let { marker ->
            return marker.location?.let {
                Target(uid, marker.title, it.lat, it.lon, marker.markerType.type, localMarker = true)
            }
        }
        app.repo.receivedMarkerList.value.firstOrNull { it.uid == uid }?.let { marker ->
            return marker.location?.let {
                Target(uid, marker.title, it.lat, it.lon, marker.markerType)
            }
        }
        return null
    }
}
