/*
 * Copyright (C) 2024, Ascent Integrated Tech.
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
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.CELLULAR
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.events.PowerManagerEvent.Companion.CHANGED_UPDATE_INTERVAL
import com.aitech.weartak_civ.events.WifiSsidRcvd
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.DEFAULT_DOCKED_REPORTING_INTERVAL_SECS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

// This service acts as the orchestrator for all power optimizations
class PowerManagerService : Service() {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dockedAndShouldThrottleDispatch: Boolean = false // gate for triggering a throttle dispatch. Determined by dock state and enableDispatchThrottleOnDevDock flag.
    private var currentDockType: BatteryPowerObserver.DockType = BatteryPowerObserver.DockType.NONE  // Are docked via wireless, usb, or not at all
    private var enableDispatchThrottleOnDevDock = false // Default to false. when true, PLI dispatch interval is 15 minutes whenever charging, even on dev dock charger. Only make true when testing.


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PowerManagerService onCreate()")
        isRunning.set(true)
        initialStartDelayDone.set(false)
        observationPowerThrottled = false
        observationDockThrottled = false
        startForegroundServiceCompat(
            POWERMANAGER_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        scope.launch {
            delay(INITIAL_START_DELAY)
            Log.d(TAG, "PowerManagerService Starting PowerManager Functionality")
            dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi),
                NetworkManager.connectedWifiSsid, dataRepository.getEnableWifiBatteryOptimization.first(),
                dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
            initialStartDelayDone.set(true)
        }
        collectDockState()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "PowerManagerService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PowerManagerService onDestroy()")
        isRunning.set(false)
        stopForegroundServiceCompat()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        scope.cancel()
        initialStartDelayDone.set(false)
        Log.d(TAG, "PowerManager Service destroyed")
    }

    private fun collectDockState() {
        scope.launch {
            dataRepository.dockType.collect { dockType ->
//                Log.i(
//                    TAG,
//                    "collectDockState: dockType=$dockType " +
//                            "(initialStartDelayDone=${initialStartDelayDone.get()}, currentDockType=$currentDockType, usbThrottle=$enableDispatchThrottleOnDevDock)"
//                )
                dataRepository.logSystemSnapshot(TAG)

                // De-dupe first (prevents spam)
                if (currentDockType == dockType) {
                    Log.d(TAG, "collectDockState: dockType unchanged ($dockType), skipping")
                    return@collect
                }

                currentDockType = dockType

                dockedAndShouldThrottleDispatch =
                    dockType == BatteryPowerObserver.DockType.WIRELESS ||
                            (dockType == BatteryPowerObserver.DockType.USB && enableDispatchThrottleOnDevDock)
                observationDockThrottled = dockedAndShouldThrottleDispatch

                // ✅ Key fix:
                // If we should throttle, do it immediately, even during startup delay.
                if (dockedAndShouldThrottleDispatch) {
                    val before = CotDispatchService.actualReportingIntervalSecs
                    if (before != DEFAULT_DOCKED_REPORTING_INTERVAL_SECS) {
                        CotDispatchService.actualReportingIntervalSecs = DEFAULT_DOCKED_REPORTING_INTERVAL_SECS

                        Log.d(
                            TAG,
                            "PowerManagerService: Dock throttle ON " +
                                    "(dockType=$dockType, usbThrottle=$enableDispatchThrottleOnDevDock, interval $before -> ${CotDispatchService.actualReportingIntervalSecs})"
                        )

                        EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                    } else {
                        Log.d(
                            TAG,
                            "collectDockState: throttle already applied " +
                                    "(dockType=$dockType, interval=${CotDispatchService.actualReportingIntervalSecs})"
                        )
                    }
                    return@collect
                }

                // For non-throttle states (NONE), keep startup-delay gate.
                if (!initialStartDelayDone.get()) {
                    Log.d(TAG, "collectDockState: startup delay active; skipping restore path for dockType=$dockType")
                    return@collect
                }

                // Restore normal behavior using existing logic
                runBlocking {
                    dynamicUpdates(
                        NetworkManager.isNetworkConnected,
                        isWifiToInt(NetworkManager.isWifi),
                        NetworkManager.connectedWifiSsid,
                        dataRepository.getEnableWifiBatteryOptimization.first(),
                        dataRepository.getEnableAllWifiBatteryOptimization.first(),
                        dataRepository.getWifiSsidList.first()
                    )
                }

                dataRepository.logSystemSnapshot(TAG)

            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(messageEvent: MessageEvent) {
        val msg = messageEvent.message
        if (UPDATE_INTERVAL_CHANGED == msg) {
            val updateInterval = messageEvent.addlIntInfo
            Log.d(TAG, "PowerManagerService onMessageEvent: UPDATE_INTERVAL_CHANGED -> update interval=($updateInterval)")
            if (initialStartDelayDone.get()) {
                runBlocking {
                    dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi), NetworkManager.connectedWifiSsid,
                        dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
                }
            }
        } else if (OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED == msg) {
            // TODO: Note we can get this message even if no details have changed (from handleManagedConfigUpdate). Check that all is good
            Log.d(TAG, "PowerManagerService onMessageEvent: OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED")
            if (initialStartDelayDone.get()) {
                runBlocking {
                    dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi), NetworkManager.connectedWifiSsid,
                        dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onWifiSsidRcvd(wifiSsidRcvd: WifiSsidRcvd) {
        val ssid = wifiSsidRcvd.ssid
        Log.d(TAG, "PowerManagerService onWifiSsidRcvd: ssid= $ssid")
        if (initialStartDelayDone.get()) {
            runBlocking {
                dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi), ssid,
                    dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
            }
        }
    }

    // This method will be called when a NwConnEvent is posted from NetworkManager
    // The NetworkManager guarantees that a NwConnEvent will be sent only on some change in
    // network (either nw becomes disconnected or again connected OR if connectivity changes from Cellular to Wifi).
    // Hence there is no need to keep nw connected state.
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onNwConnEvent(nwConnEvent: NwConnEvent) {
        Log.d(TAG, "PowerManagerService onNwConnEvent: (${nwConnEvent.isConn}, ${nwConnEvent.transport})")
        if (initialStartDelayDone.get()) {
            runBlocking {
                dynamicUpdates(nwConnEvent.isConn, nwConnEvent.transport, NetworkManager.connectedWifiSsid,
                    dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
            }
        }
    }

    // onStart: Location and Heartrate are started with reportingUpdateIntervalSecs, i.e., at fastest rate,
    // and this corresponds to cellular. So if onStart == true and on cellular, DO NOTHING.
    private fun dynamicUpdates(nwConnected: Boolean, nwTransport: Int, connWifiSsid : String?,
                               enableWifiPowerOptim: Boolean, enablePowerOptimOnAllWifiSsids: Boolean,
                               powerOptimWifiSsidList: List<DataRepository.WifiSsidItem>) {
        Log.d(TAG, "PowerManagerService dynamicUpdates(): nwConnected($nwConnected), nwTransport($nwTransport), connWifiSsid($connWifiSsid), enableWifiPowerOptim($enableWifiPowerOptim), enablePowerOptimOnAllWifiSsids($enablePowerOptimOnAllWifiSsids), powerOptimWifiSsidList(${powerOptimWifiSsidList.toString()})")
        observationPowerThrottled = !nwConnected || (nwTransport == WIFI && enableWifiPowerOptim &&
            (enablePowerOptimOnAllWifiSsids || powerOptimWifiSsidList.any { it.name == connWifiSsid }))
        if (dockedAndShouldThrottleDispatch) {
            Log.w(TAG, "Device is docked and should be throttled. Exiting dynamicUpdates() early....")
            return
        }
        if (nwConnected) {
            if (nwTransport == WIFI) {
                if (enableWifiPowerOptim) {
                    if (enablePowerOptimOnAllWifiSsids) {
                        // Slow down the GPS update as we assume that the device is NOT in critical area
                        // TODO: This will have to change this below when we add custom geofence
                        CotDispatchService.actualReportingIntervalSecs = slowDownReportingInterval(CotDispatchService.desiredReportingIntervalSecs)
                        Log.d(TAG, "PowerManagerService: WIFI w/ enableWifiPowerOptim true, enablePowerOptimOnAllWifiSsids true: Setting currReportingIntervalInSecs to ${CotDispatchService.actualReportingIntervalSecs}")
                        // Send eventbus message so that LocationService can soft restart
                        EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                    } else {
                        // Check SSID and slow down if connWifiSsid is in powerOptimWifiSsidList
                        if (powerOptimWifiSsidList.any { it.name == connWifiSsid }) {
                            // connWifiSsid is in powerOptimWifiSsidList
                            CotDispatchService.actualReportingIntervalSecs = slowDownReportingInterval(CotDispatchService.desiredReportingIntervalSecs)
                            Log.d(TAG, "PowerManagerService: WIFI w/ enableWifiPowerOptim true, enablePowerOptimOnAllWifiSsids false (connWifiSsid($connWifiSsid) in $powerOptimWifiSsidList): Setting currReportingIntervalInSecs to ${CotDispatchService.actualReportingIntervalSecs}")
                            // Send eventbus message so that LocationService can soft restart
                            EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                        } else {
                            // Fast updates
                            if (CotDispatchService.actualReportingIntervalSecs != CotDispatchService.desiredReportingIntervalSecs) {
                                CotDispatchService.actualReportingIntervalSecs = CotDispatchService.desiredReportingIntervalSecs
                                Log.d(TAG, "PowerManagerService: WIFI w/ enableWifiPowerOptim true, enablePowerOptimOnAllWifiSsids false (connWifiSsid($connWifiSsid) NOT in $powerOptimWifiSsidList): Setting currReportingIntervalInSecs to ${CotDispatchService.actualReportingIntervalSecs}")
                                // Send eventbus message so that LocationService can soft restart
                                EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                            }
                        }
                    }
                } else {
                    // Fast updates
                    if (CotDispatchService.actualReportingIntervalSecs != CotDispatchService.desiredReportingIntervalSecs) {
                        CotDispatchService.actualReportingIntervalSecs = CotDispatchService.desiredReportingIntervalSecs
                        Log.d(TAG, "PowerManagerService: WIFI w/ enableWifiPowerOptim false: Setting currReportingIntervalInSecs to ${CotDispatchService.actualReportingIntervalSecs}")
                        // Send eventbus message so that LocationService can soft restart
                        EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                    }
                }
            } else { // It will be CELLULAR
                // Fast GPS update
                if (CotDispatchService.actualReportingIntervalSecs != CotDispatchService.desiredReportingIntervalSecs) {
                    CotDispatchService.actualReportingIntervalSecs = CotDispatchService.desiredReportingIntervalSecs
                    Log.d(TAG, "PowerManagerService CELLULAR: Setting currReportingIntervalInSecs to ${CotDispatchService.actualReportingIntervalSecs}")
                    // Send eventbus message so that LocationService can soft restart
                    EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                }
            }
        } else {
            // Not connected. Slow down the GPS update
            CotDispatchService.actualReportingIntervalSecs = slowDownReportingInterval(CotDispatchService.desiredReportingIntervalSecs)
            Log.d(TAG, "PowerManagerService Network Disconnected: Setting currReportingIntervalInSecs to ${CotDispatchService.actualReportingIntervalSecs}")
            // Send eventbus message so that LocationService can soft restart
            EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
        }
    }

    private fun isWifiToInt(isWifi: Boolean) : Int {
        return if (isWifi) { WIFI } else { CELLULAR }
    }

    companion object {
        @Volatile
        var observationPowerThrottled = false
            private set
        @Volatile
        var observationDockThrottled = false
            private set

        fun locationObservationIntervalSeconds(dynamic: Boolean): Int =
            StationaryLocationPolicy.intervalSeconds(
                SpeedReportingPolicy.observationIntervalSeconds(
                    dynamic, CotDispatchService.desiredLocationObservationIntervalSecs,
                    CotDispatchService.actualReportingIntervalSecs,
                    observationPowerThrottled, observationDockThrottled,
                ), CotDispatchService.stationaryLocation,
                CotDispatchService.stationaryIntervalSeconds,
            )

        private val TAG = "WearTAK-" + PowerManagerService::class.java.simpleName
        private val POWERMANAGER_SERVICE_NOTIFICATION_ID = 555
        private const val INITIAL_START_DELAY = 5_000L // 5 seconds
        val isRunning = AtomicBoolean(false)
        // We provide a initial delay of INITIAL_START_DELAY so that other services can come up
        // and we are not concerned with the order of service start. So till this flag is set to
        // true, we will ignore NwConnEvents in the function onNwConnEvent
        var initialStartDelayDone = AtomicBoolean(false)
        var isWifiOpt : Boolean = true
        var isAllWifiOpt : Boolean = true

        fun slowDownReportingInterval(reportingUpdateIntervalSecs: Int): Int {
            var newReportingUpdateInterval: Int = 10
            // TODO: Can add better logic below
            if (reportingUpdateIntervalSecs <= 10) {
                newReportingUpdateInterval = reportingUpdateIntervalSecs * 6
            } else if (reportingUpdateIntervalSecs > 10 && reportingUpdateIntervalSecs <= 60) {
                // The 60 below is the max time from previous if statement. So 10 * 6.
                // The 10 below is the max reportingUpdateIntervalSecs from previous if statement
                // This guarantees that reportingUpdateIntervalSecs for 11s is greater than that for 10s but now the increase is slower
                newReportingUpdateInterval = 60 + (reportingUpdateIntervalSecs - 10) * 4
            } else {
                // The 260 below is the max time from previous else if statement. So 60 + (60 - 10) * 4 = 260
                // The 60 below is the max reportingUpdateIntervalSecs from previous else if statement
                // This guarantees that reportingUpdateIntervalSecs for 61s is greater than that for 60s but now the increase is a constant of 200s more
                newReportingUpdateInterval = 260 + (reportingUpdateIntervalSecs - 60)
            }
            return newReportingUpdateInterval
        }
    }
}