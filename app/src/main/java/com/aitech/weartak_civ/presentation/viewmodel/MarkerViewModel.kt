/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bhRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MarkerViewModel(application: Application) : AndroidViewModel(application) {
    data class MarkerVectorUiState(
        val distance: Double?,
        val orientation: Float,
        val callsign: String,
        val timeReceived: String
    )

    private val utcTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC)

    val droppedMarkerListSetting: StateFlow<List<DataRepository.DroppedMarker>> =
        dataRepository.droppedMarkerList

    val receivedMarkerListSetting: StateFlow<List<DataRepository.ReceivedMarker>> =
        dataRepository.receivedMarkerList

    val licenseKey: StateFlow<String> =
        dataRepository.getLicense.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "License"
        )

    val anyEndpointConnected: StateFlow<Boolean> = dataRepository.anyEndpointConnected.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val callsign: StateFlow<String> = dataRepository.getCallsign.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val location = dataRepository.latestLocation.value
    private fun <T, L> markerVectorUiInternal(
        index: Int,
        markerFlow: StateFlow<List<T>>,
        markerLocationProvider: (T) -> L?,
        markerLatProvider: (L) -> Double,
        markerLonProvider: (L) -> Double,
        timestampProvider: (T) -> Long
    ): StateFlow<MarkerVectorUiState?> =
        combine(
            markerFlow,
            dataRepository.latestLocation,
            dataRepository.azimuthTrueDegSmoothed,
            callsign
        ) { markers, location, azimuth, callsign ->
            val marker = markers.getOrNull(index) ?: return@combine null
            val markerLocation = markerLocationProvider(marker) ?: return@combine null
            val currentLocation = location ?: return@combine null

            val vector = LocationVectorCalculator.calculate(
                currentLocation.latitude,
                currentLocation.longitude,
                markerLatProvider(markerLocation),
                markerLonProvider(markerLocation)
            )

            val orientation = LocationVectorCalculator.needleAngle(vector.bearing, azimuth)
                ?: vector.bearing
                ?: 0.0

            MarkerVectorUiState(
                distance = vector.distance,
                orientation = orientation.toFloat(),
                callsign = callsign,
                timeReceived = utcTimeFormatter.format(Instant.ofEpochMilli(timestampProvider(marker)))
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun markerVectorUiForReceived(index: Int) =
        markerVectorUiInternal(
            index,
            receivedMarkerListSetting,
            { it.location },
            { it.lat },
            { it.lon },
            { it.timeReceived }
        ).also { dataRepository.requestInteractionPliRefresh(com.aitech.weartak_civ.events.PliRefreshReason.POSITION_VIEWED) }

    fun markerVectorUi(index: Int) =
        markerVectorUiInternal(
            index,
            droppedMarkerListSetting,
            { it.location },
            { it.lat },
            { it.lon },
            { it.timeDropped }
        ).also { dataRepository.requestInteractionPliRefresh(com.aitech.weartak_civ.events.PliRefreshReason.POSITION_VIEWED) }

    fun deleteReceivedMarker(uid: String) {
        val removedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val removed = dataRepository.deleteReceivedPointLocally(uid, bhRepo)
            val coordinator = getApplication<WearTakApplication>().pluginManager.settingsCoordinator
            removed.forEach { coordinator.notifyLocalMarkerRemoved(it, removedAt) }
        }
    }

    suspend fun requestUpdateMarkerLocation(uid: String) {
        dataRepository.requestUpdateMarkerLocation(uid)
    }

    fun requestChangeMarkerTitle(uid: String, title: String) {
        dataRepository.requestChangeMarkerTitle(uid, title)
    }

    fun requestChangeMarkerRemark(uid: String, remark: String) {
        dataRepository.requestChangeMarkerRemark(uid, remark)
    }

    fun requestChangeMarkerType(uid: String, type: DataRepository.MarkerType){
        dataRepository.requestChangeMarkerType(uid, type)
    }

    suspend fun requestClearAllMarkers() {
        dataRepository.requestClearAll()
    }

    suspend fun requestClearSpecificMarker(uid: String) {
        dataRepository.requestClearSpecific(uid)
    }

    suspend fun requestClearLast() {
        dataRepository.requestClearLast()
    }

    suspend fun requestMarkerDrop(markerTypeRaw: String, title: String, remark: String, timeMillis: Long) {
        dataRepository.requestMarkerDrop(markerTypeRaw, title, remark, timeMillis)
    }
}
