/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.complication

import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.presentation.overallComplicationString

class StatusIconsComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + StatusIconsComplicationService::class.java.simpleName
        var cellServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var cellServerConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellServerConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var cellServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var cellDittoConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellDittoConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var cellSomeServersConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellSomeServersConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectedMulticastLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectedMulticastLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiDittoConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiDittoConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiSomeServersConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiSomeServersConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var bluetoothServerConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var bluetoothServerConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var bluetoothServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var bluetoothServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var bluetoothServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var bluetoothServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var airplaneModeServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var airplaneModeServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var airplaneModeServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var airplaneModeServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var airplaneModeSomeServersConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var airplaneModeSomeServersConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var noConnectionServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var noConnectServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var noConnectServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var noConnectServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(
            Icon.createWithResource(
                applicationContext,
                R.drawable.no_connect_server_disconnected_location_disabled
            ), getString(R.string.no_connect_server_disconnected_location_disabled)
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        //Log.d(TAG, "requestStatusIconComplicationUpdate onComplicationRequest called")
        if (null == mainActivityIsInit) {
            return null
        }
        return when (overallComplicationString(applicationContext)) {
            getString(R.string.cell_server_connecting_location_enabled) -> when (null != cellServerConnectingLocationEnabledComplicationData) {
                true -> cellServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectingLocationEnabledComplicationData")
                    cellServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connecting_location_enabled), getString(R.string.cell_server_connecting_location_enabled))
                    return cellServerConnectingLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_server_connecting_location_disabled) -> when (null != cellServerConnectingLocationDisabledComplicationData) {
                true -> cellServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectingLocationDisabledComplicationData")
                    cellServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connecting_location_disabled), getString(R.string.cell_server_connecting_location_disabled))
                    return cellServerConnectingLocationDisabledComplicationData
                }
            }
            getString(R.string.cell_server_connected_location_enabled) -> when (null != cellServerConnectedLocationEnabledComplicationData) {
                true -> cellServerConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectedLocationEnabledComplicationData")
                    cellServerConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connected_location_enabled), getString(R.string.cell_server_connected_location_enabled))
                    return cellServerConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_server_connected_location_disabled) -> when (null != cellServerConnectedLocationDisabledComplicationData) {
                true -> cellServerConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectedLocationDisabledComplicationData")
                    cellServerConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connected_location_disabled), getString(R.string.cell_server_connected_location_disabled))
                    return cellServerConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.cell_server_disconnected_location_enabled) -> when (null != cellServerDisconnectedLocationEnabledComplicationData) {
                true -> cellServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerDisconnectedLocationEnabledComplicationData")
                    cellServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_disconnected_location_enabled), getString(R.string.cell_server_disconnected_location_enabled))
                    return cellServerDisconnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_server_disconnected_location_disabled) -> when (null != cellServerDisconnectedLocationDisabledComplicationData) {
                true -> cellServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerDisconnectedLocationDisabledComplicationData")
                    cellServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_disconnected_location_disabled), getString(R.string.cell_server_disconnected_location_disabled))
                    return cellServerDisconnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.cell_ditto_connected_location_enabled) -> when (null != cellDittoConnectedLocationEnabledComplicationData) {
                true -> cellDittoConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellDittoConnectedLocationEnabledComplicationData")
                    cellDittoConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_ditto_connected_location_enabled), getString(R.string.cell_ditto_connected_location_enabled))
                    return cellDittoConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_ditto_connected_location_disabled) -> when (null != cellDittoConnectedLocationDisabledComplicationData) {
                true -> cellDittoConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellDittoConnectedLocationDisabledComplicationData")
                    cellDittoConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_ditto_connected_location_disabled), getString(R.string.cell_ditto_connected_location_disabled) )
                    return cellDittoConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.cell_some_servers_connected_location_enabled) -> when (null != cellSomeServersConnectedLocationEnabledComplicationData) {
                true -> cellSomeServersConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellSomeServersConnectedLocationEnabledComplicationData")
                    cellSomeServersConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_some_server_connected_location_enabled), getString(R.string.cell_some_servers_connected_location_enabled))
                    return cellSomeServersConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_some_servers_connected_location_disabled) -> when (null != cellSomeServersConnectedLocationDisabledComplicationData) {
                true -> cellSomeServersConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellSomeServersConnectedLocationDisabledComplicationData")
                    cellSomeServersConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_some_server_connected_location_disabled), getString(R.string.cell_some_servers_connected_location_disabled))
                    return cellSomeServersConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_server_connecting_location_enabled) -> when (null != wifiServerConnectingLocationEnabledComplicationData) {
                true -> wifiServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectingLocationEnabledComplicationData")
                    wifiServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connecting_location_enabled), getString(R.string.wifi_server_connecting_location_enabled))
                    return wifiServerConnectingLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_server_connecting_location_disabled) -> when (null != wifiServerConnectingLocationDisabledComplicationData) {
                true -> wifiServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectingLocationDisabledComplicationData")
                    wifiServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connecting_location_disabled), getString(R.string.wifi_server_connecting_location_disabled))
                    return wifiServerConnectingLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_server_connected_location_enabled) -> when (null != wifiServerConnectedLocationEnabledComplicationData) {
                true -> wifiServerConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectedLocationEnabledComplicationData")
                    wifiServerConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connected_location_enabled), getString(R.string.wifi_server_connected_location_enabled))
                    return wifiServerConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_server_connected_location_disabled) -> when (null != wifiServerConnectedLocationDisabledComplicationData) {
                true -> wifiServerConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectedLocationDisabledComplicationData")
                    wifiServerConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connected_location_disabled), getString(R.string.wifi_server_connected_location_disabled))
                    return wifiServerConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_server_connected_multicast_location_enabled) -> when (null != wifiServerConnectedMulticastLocationEnabledComplicationData) {
                true -> wifiServerConnectedMulticastLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectedMulticastLocationEnabledComplicationData")
                    wifiServerConnectedMulticastLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connected_multicast_location_enabled), getString(R.string.wifi_server_connected_multicast_location_enabled))
                    return wifiServerConnectedMulticastLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_server_connected_multicast_location_disabled) -> when (null != wifiServerConnectedMulticastLocationDisabledComplicationData) {
                true -> wifiServerConnectedMulticastLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectedMulticastLocationDisabledComplicationData")
                    wifiServerConnectedMulticastLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connected_multicast_location_disabled), getString(R.string.wifi_server_connected_multicast_location_disabled))
                    return wifiServerConnectedMulticastLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_server_disconnected_location_enabled) -> when (null != wifiServerDisconnectedLocationEnabledComplicationData) {
                true -> wifiServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerDisconnectedLocationEnabledComplicationData")
                    wifiServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_disconnected_location_enabled), getString(R.string.wifi_server_disconnected_location_enabled))
                    return wifiServerDisconnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_server_disconnected_location_disabled) -> when (null != wifiServerDisconnectedLocationDisabledComplicationData) {
                true -> wifiServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerDisconnectedLocationDisabledComplicationData")
                    wifiServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_disconnected_location_disabled), getString(R.string.wifi_server_disconnected_location_disabled))
                    return wifiServerDisconnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_ditto_connected_location_enabled) -> when (null != wifiDittoConnectedLocationEnabledComplicationData) {
                true -> wifiDittoConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiDittoConnectedLocationEnabledComplicationData")
                    wifiDittoConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_ditto_connected_location_enabled), getString(R.string.wifi_ditto_connected_location_enabled))
                    return wifiDittoConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_ditto_connected_location_disabled) -> when (null != wifiDittoConnectedLocationDisabledComplicationData) {
                true -> wifiDittoConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiDittoConnectedLocationDisabledComplicationData")
                    wifiDittoConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_ditto_connected_location_disabled), getString(R.string.wifi_ditto_connected_location_disabled))
                    return wifiDittoConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_some_servers_connected_location_enabled) -> when (null != wifiSomeServersConnectedLocationEnabledComplicationData) {
                true -> wifiSomeServersConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiSomeServersConnectedLocationEnabledComplicationData")
                    wifiSomeServersConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_some_servers_connected_location_enabled), getString(R.string.wifi_some_servers_connected_location_enabled))
                    return wifiSomeServersConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_some_servers_connected_location_disabled) -> when (null != wifiSomeServersConnectedLocationDisabledComplicationData) {
                true -> wifiSomeServersConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiSomeServersConnectedLocationDisabledComplicationData")
                    wifiSomeServersConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_some_servers_connected_location_disabled), getString(R.string.wifi_some_servers_connected_location_disabled))
                    return wifiSomeServersConnectedLocationDisabledComplicationData
                }
            }
            "Bluetooth, Server Connected, Location Enabled" -> when (null != bluetoothServerConnectedLocationEnabledComplicationData) {
                true -> bluetoothServerConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new bluetoothServerConnectedLocationEnabledComplicationData")
                    bluetoothServerConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.bluetooth_server_connected_enabled), "Bluetooth, Server Connected, Location Enabled")
                    return bluetoothServerConnectedLocationEnabledComplicationData
                }
            }
            "Bluetooth, Server Connected, Location Disabled" -> when (null != bluetoothServerConnectedLocationDisabledComplicationData) {
                true -> bluetoothServerConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new bluetoothServerConnectedLocationDisabledComplicationData")
                    bluetoothServerConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.bluetooth_server_connected_disabled), "Bluetooth, Server Connected, Location Disabled")
                    return bluetoothServerConnectedLocationDisabledComplicationData
                }
            }
            "Bluetooth, Server Connecting, Location Enabled" -> when (null != bluetoothServerConnectingLocationEnabledComplicationData) {
                true -> bluetoothServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new bluetoothServerConnectingLocationEnabledComplicationData")
                    bluetoothServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.bluetooth_server_connecting_enabled), "Bluetooth, Server Connecting, Location Enabled")
                    return bluetoothServerConnectingLocationEnabledComplicationData
                }
            }
            "Bluetooth, Server Connecting, Location Disabled" -> when (null != bluetoothServerConnectingLocationDisabledComplicationData) {
                true -> bluetoothServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new bluetoothServerConnectingLocationDisabledComplicationData")
                    bluetoothServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.bluetooth_server_connecting_disabled), "Bluetooth, Server Connecting, Location Disabled")
                    return bluetoothServerConnectingLocationDisabledComplicationData
                }
            }
            "Bluetooth, Server Disconnected, Location Enabled" -> when (null != bluetoothServerDisconnectedLocationEnabledComplicationData) {
                true -> bluetoothServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new bluetoothServerDisconnectedLocationEnabledComplicationData")
                    bluetoothServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.bluetooth_server_disconnected_enabled), "Bluetooth, Server Disconnected, Location Enabled")
                    return bluetoothServerDisconnectedLocationEnabledComplicationData
                }
            }
            "Bluetooth, Server Disconnected, Location Disabled" -> when (null != bluetoothServerDisconnectedLocationDisabledComplicationData) {
                true -> bluetoothServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new bluetoothServerDisconnectedLocationDisabledComplicationData")
                    bluetoothServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.bluetooth_server_disconnected_disabled), "Bluetooth, Server Disconnected, Location Disabled")
                    return bluetoothServerDisconnectedLocationDisabledComplicationData
                }
            }
            "Airplane Mode, Server Connecting, Location Enabled" -> when (null != airplaneModeServerConnectingLocationEnabledComplicationData) {
                true -> airplaneModeServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new airplaneModeServerConnectingLocationEnabledComplicationData")
                    airplaneModeServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.airplanemode_server_connecting_location_enabled), "Airplane Mode, Server Connecting, Location Enabled")
                    return airplaneModeServerConnectingLocationEnabledComplicationData
                }
            }
            "Airplane Mode, Server Connecting, Location Disabled" -> when (null != airplaneModeServerConnectingLocationDisabledComplicationData) {
                true -> airplaneModeServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new airplaneModeServerConnectingLocationDisabledComplicationData")
                    airplaneModeServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.airplanemode_server_connecting_location_disabled), "Airplane Mode, Server Connecting, Location Disabled")
                    return airplaneModeServerConnectingLocationDisabledComplicationData
                }
            }
            "Airplane Mode, Server Disconnected, Location Enabled" -> when (null != airplaneModeServerDisconnectedLocationEnabledComplicationData) {
                true -> airplaneModeServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new airplaneModeServerDisconnectedLocationEnabledComplicationData")
                    airplaneModeServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.airplanemode_server_disconnected_location_enabled), "Airplane Mode, Server Disconnected, Location Enabled")
                    return airplaneModeServerDisconnectedLocationEnabledComplicationData
                }
            }
            "Airplane Mode, Server Disconnected, Location Disabled" -> when (null != airplaneModeServerDisconnectedLocationDisabledComplicationData) {
                true -> airplaneModeServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new airplaneModeServerDisconnectedLocationDisabledComplicationData")
                    airplaneModeServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.airplanemode_server_disconnected_location_disabled), "Airplane Mode, Server Disconnected, Location Disabled")
                    return airplaneModeServerDisconnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.airplane_mode_some_servers_connected_location_enabled) -> when (null != airplaneModeSomeServersConnectedLocationEnabledComplicationData) {
                true -> airplaneModeSomeServersConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new airplaneModeSomeServersConnectedLocationEnabledComplicationData")
                    airplaneModeSomeServersConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.airplane_mode_some_servers_connected_location_enabled), getString(R.string.airplane_mode_some_servers_connected_location_enabled))
                    return airplaneModeSomeServersConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.airplane_mode_some_servers_connected_location_disabled) -> when (null != airplaneModeSomeServersConnectedLocationDisabledComplicationData) {
                true -> airplaneModeSomeServersConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new airplaneModeSomeServersConnectedLocationDisabledComplicationData")
                    airplaneModeSomeServersConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.airplane_mode_some_servers_connected_location_disabled), getString(R.string.airplane_mode_some_servers_connected_location_disabled))
                    return airplaneModeSomeServersConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.no_connect_server_connecting_location_enabled) -> when (null != noConnectionServerConnectingLocationEnabledComplicationData) {
                true -> noConnectionServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectionServerConnectingLocationEnabledComplicationData")
                    noConnectionServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_connecting_location_enabled), getString(R.string.no_connect_server_connecting_location_enabled))
                    return noConnectionServerConnectingLocationEnabledComplicationData
                }
            }
            getString(R.string.no_connect_server_connecting_location_disabled) -> when (null != noConnectServerConnectingLocationDisabledComplicationData) {
                true -> noConnectServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectServerConnectingLocationDisabledComplicationData")
                    noConnectServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_connecting_location_disabled), getString(R.string.no_connect_server_connecting_location_disabled))
                    return noConnectServerConnectingLocationDisabledComplicationData
                }
            }
            getString(R.string.no_connect_server_disconnected_location_enabled) -> when (null != noConnectServerDisconnectedLocationEnabledComplicationData) {
                true -> noConnectServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectServerDisconnectedLocationEnabledComplicationData")
                    noConnectServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_disconnected_location_enabled), getString(R.string.no_connect_server_disconnected_location_enabled))
                    return noConnectServerDisconnectedLocationEnabledComplicationData
                }
            }
            else -> when (null != noConnectServerDisconnectedLocationDisabledComplicationData) {
                true -> noConnectServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectServerDisconnectedLocationDisabledComplicationData")
                    noConnectServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_disconnected_location_disabled), getString(R.string.no_connect_server_disconnected_location_disabled))
                    return noConnectServerDisconnectedLocationDisabledComplicationData
                }
            }
        }
    }

    private fun createComplicationData(icon: Icon, contentDescription: String) =
        SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(icon, SmallImageType.ICON).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()

}