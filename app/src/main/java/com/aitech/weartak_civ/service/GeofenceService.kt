/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.isPointInPolygon
import com.aitech.weartak_civ.Utils.setAirplaneMode
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.data.GeofenceRepository
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.model.GeofenceTrigger
import com.aitech.weartak_civ.data.model.LatLon
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean


class GeofenceService: Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val geofenceMutex = Mutex()
    private val geofencesByUid = LinkedHashMap<String, GeofenceCot>()
    private val insideStateByUid = HashMap<String, Boolean>()
    private var latestLatLon: LatLon? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GeofenceService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            GEOFENCE_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        startLocationCollector()
        startGeofenceCotConsumer(serviceScope) { geofences ->
            handleGeofenceSnapshot(geofences)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "GeofenceService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GeofenceService onDestroy()")
        isRunning.set(false)
        stopForegroundServiceCompat()
        serviceScope.cancel()
    }

    private fun shouldTrackEntry(g: GeofenceCot): Boolean {
        if (!g.tracking) return false
        return g.trigger == GeofenceTrigger.Entry || g.trigger == GeofenceTrigger.Both
    }

    private suspend fun evaluateGeofencesForPoint(point: LatLon) {
        val snapshot: List<GeofenceCot> = geofenceMutex.withLock {
            geofencesByUid.values.toList()
        }
        for (g in snapshot) {
            evaluateSingleGeofence(g, point)
        }
    }

    private suspend fun evaluateSingleGeofence(geofence: GeofenceCot, point: LatLon) {
        // If it’s in the map, it *should* already satisfy this, but keeping it safe.
        if (!shouldTrackEntry(geofence)) return

        if (geofence.points.size < 3) return

        val isInsideNow = isPointInPolygon(point, geofence.points)

        val wasInside = geofenceMutex.withLock {
            val prev = insideStateByUid[geofence.uid] ?: false
            insideStateByUid[geofence.uid] = isInsideNow
            prev
        }

        if (!wasInside && isInsideNow) {
            Log.i(TAG, "ENTERED geofence uid=${geofence.uid} callsign=${geofence.callsign}")
            val success = setAirplaneMode("1")
            if (success) dataRepository.setAirplaneOnSuccess(true)
        }
    }

    private suspend fun handleGeofenceSnapshot(geofences: List<GeofenceCot>) {
        val trackableByUid = geofences
            .filter(::shouldTrackEntry)
            .associateBy(GeofenceCot::uid)
        val changed = mutableListOf<GeofenceCot>()

        geofenceMutex.withLock {
            val removedUids = geofencesByUid.keys - trackableByUid.keys
            removedUids.forEach { uid ->
                geofencesByUid.remove(uid)
                insideStateByUid.remove(uid)
            }

            trackableByUid.forEach { (uid, geofence) ->
                if (geofencesByUid[uid] != geofence) {
                    geofencesByUid[uid] = geofence
                    insideStateByUid.putIfAbsent(uid, false)
                    changed += geofence
                }
            }
        }

        if (changed.isNotEmpty()) {
            Log.i(TAG, "Synchronized ${changed.size} changed geofence(s)")
        }
        val point = latestLatLon ?: return
        changed.forEach { geofence -> evaluateSingleGeofence(geofence, point) }
    }
    fun startGeofenceCotConsumer(
        scope: CoroutineScope,
        onCotReceived: suspend (List<GeofenceCot>) -> Unit
    ) {
        scope.launch {
            GeofenceRepository.geofenceCotFlow.collect { geofences ->
                onCotReceived(geofences)
            }
        }
    }

    private suspend fun onLocationUpdate(point: LatLon) {
        evaluateGeofencesForPoint(point)
    }

    private fun startLocationCollector() {
        serviceScope.launch {
            dataRepository.latestLocation
                .filterNotNull()
                .collect { loc ->
                    val point = LatLon(lat = loc.latitude, lon = loc.longitude)
                    if (point != latestLatLon) {
                        onLocationUpdate(point) // evaluates all stored geofences
                        latestLatLon = point
                    }
                }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + GeofenceService::class.java.simpleName
        const private val GEOFENCE_SERVICE_NOTIFICATION_ID = 178
        val isRunning : AtomicBoolean = AtomicBoolean(false)
    }
}
