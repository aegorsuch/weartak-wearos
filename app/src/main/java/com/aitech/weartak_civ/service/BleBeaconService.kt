/*
 * Copyright (C) 2024, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.Notification.PRIORITY_MAX
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Observer
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.getValueFromJson
import com.aitech.weartak_civ.Utils.jitteredLatLon
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.LOCALIZATION_BEACONS_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.davidgyoungtech.beaconparsers.IBeaconParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconRegion
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.Settings
import org.altbeacon.beacon.service.RunningAverageRssiFilter
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId


class BleBeaconService: Service() {
    // TODO items:
    // 1. There needs to be hysteresis when switching between BLE beacons
    // 2. Add same logic in buildLocationBio to Marker and SOS
    // 3.
    data class LocalizationBleBeacon(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val origLat: Double,
        val origLon: Double,
        var lat: Double,
        var lon: Double,
        val alt: Double,
        var bluetoothAddress: String = "", // This field is for debugging and not currently used.
        var proximity: Double = 30.0, // In meters
        var bleExitHysteresisCount : Int = 0,
        var overrideGpsWithBle: Boolean = true,
        var overrideGpsWithBleExitHysteresisCount: Int = 20, // Number of times BLE distance can be above proximity
                                                              // before we start considering GPS again. (Must be greater than or equal to 0)
        var overrideGPSWithBLECount: Int = 0,
        var distance: Double = -1.0, // In meters
        var lastUpdateTime: Long = 0 // In milliseconds
    )
    private val binder = BleBeaconServiceBinder()
    lateinit private var beaconManager: BeaconManager
    //private var localizationBleBeaconList: MutableList<LocalizationBleBeacon> = mutableListOf()
    private val mapLocalizationBeacons = HashMap<String, LocalizationBleBeacon>()

    // Should we only look out for a specific UUID. I think we should, so put that UUID as a string Identifier.parse("2F234454-CF6D-4A0F-ADF2-F4911BA9FFA6")
    // For now, id1 is null but later put in a specific UUID via Identifier.parse("2F234454-CF6D-4A0F-ADF2-F4911BA9FFA6")
    private val iBeaconRegion = BeaconRegion("everyIBeaconRegion", IBeaconParser() /* from com.davidgyoungtech:beacon-parsers:1.0 */, null, null, null)

    inner class BleBeaconServiceBinder: Binder() {
        val service: BleBeaconService
            get() = this@BleBeaconService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BleBeaconService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            BLE_BEACON_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        beaconManager = BeaconManager.getInstanceForApplication(mainActivity)

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        CoroutineScope(Dispatchers.Main).launch {
            setDataFromDatastore()
            startMonitoringBleBeacons()
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private fun setDataFromDatastore() {
        runBlocking {
            setValuesFromCustomDeveloperConfig()
            val localizationBleBeaconItemList = dataRepository.getLocalizationBleBeaconItemList.first()
            mapLocalizationBeacons.clear()
            if (localizationBleBeaconItemList.size > 0) {
                for (bleBeacon in localizationBleBeaconItemList) {
                    val uuid = bleBeacon.uuid.lowercase()
                    val major = bleBeacon.major
                    val minor = bleBeacon.minor
                    val id: String = "$uuid|$major|$minor"
                    val (jitteredLat, jitteredLon) = jitteredLatLon(bleBeacon.lat, bleBeacon.lon, JITTER_MAX_M)
                    val logString = "setDataFromDatastore: $id (${bleBeacon.lat}, ${bleBeacon.lon}, (jittered: $jitteredLat, $jitteredLon), ${bleBeacon.alt})"
                    Log.d(TAG, logString)
                    //writeToLogFileLocation(logFile, logString)
                    val beacon = LocalizationBleBeacon(bleBeacon.uuid.lowercase(), bleBeacon.major, bleBeacon.minor, bleBeacon.lat, bleBeacon.lon, jitteredLat, jitteredLon, bleBeacon.alt)
                    mapLocalizationBeacons.put(id, beacon)
                }
            }
            setValuesFromCustomDeveloperConfig()
        }
    }

    private fun startMonitoringBleBeacons() {
        Log.d(TAG, "startMonitoringBleBeacons called")

        // Use RunningAverageRssiFilter which by default collects signal measurements over 20 seconds,
        // throws out the top 10% and bottom 10% of measurements, and averages the rest.
        // Since we force beacons to transmit every 100ms (rather than the default of 1s),
        // we should be good with 2s but still use 5s.
        BeaconManager.setRssiFilterImplClass(RunningAverageRssiFilter::class.java)
        RunningAverageRssiFilter.setSampleExpirationMilliseconds(5000L)

        // Use AutoRegressiveMovingAverage filter. This uses an algorithm that responds more quickly to changes,
        // by making up to a 10 percent change (adjustable) on each RSSI value per measurement based on subsequent
        // measurements. This can sometimes give better performance than the default algorithm.
        // BeaconManager.setRssiFilterImplClass(ArmaRssiFilter::class.java)

        //beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"))
        //beaconManager.beaconParsers.add(IBeaconParser())
        beaconManager.beaconParsers.set(0, IBeaconParser())
        val settings = getSettingsForForegroundServiceScanning()
        beaconManager.replaceSettings(settings)
        //beaconManager.adjustSettings(Settings(debug = true))

        // Set up a Live Data observer so this Activity can get beacon data from the Application class
        val regionViewModel = beaconManager.getRegionViewModel(iBeaconRegion)
        // observer will be called each time the monitored regionState changes (inside vs. outside region)
        //regionViewModel.regionState.observeForever(centralMonitoringObserver)
        //beaconManager.startMonitoring(iBeaconRegion)

        // observer will be called each time a new list of beacons is ranged (typically ~1 second in the foreground)
        regionViewModel.rangedBeacons.observeForever(centralRangingObserver)
        beaconManager.startRangingBeacons(iBeaconRegion)
    }

    suspend private fun setValuesFromCustomDeveloperConfig() {
        val jsonString = dataRepository.getCustomDeveloperConfigJson.first()
        // Check for BLE_EXIT_HYSTERESIS_COUNT and BLE_STALE_TIME_INTERVAL_MS
        // Json could be {"bleExitHysteresisCount" : "20", "bleStaleTimeIntervalMs" : "5000", "overrideGpsWithBleExitHysteresisCount" : "30", "gpsStaleTimeInterval" : "10000" }
        var value: String? = getValueFromJson(jsonString, BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY)
        if (null != value) {
            val intValue = value.toIntOrNull()
            Log.d(TAG, "$BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY: $intValue")
            if (null != intValue) {
                BLE_EXIT_HYSTERESIS_COUNT = intValue
            }
        }
        value = getValueFromJson(jsonString, BLE_STALE_TIME_INTERVAL_MS_JSON_KEY)
        if (null != value) {
            val longValue = value.toLongOrNull()
            Log.d(TAG, "$BLE_STALE_TIME_INTERVAL_MS_JSON_KEY: $longValue")
            if (null != longValue) {
                BLE_STALE_TIME_INTERVAL_MS = longValue
            }
        }
        value = getValueFromJson(jsonString, JITTER_MIN_M_JSON_KEY)
        if (null != value) {
            val doubleValue = value.toDoubleOrNull()
            Log.d(TAG, "$JITTER_MIN_M_JSON_KEY: $doubleValue")
            if (null != doubleValue) {
                JITTER_MIN_M = doubleValue
            }
        }
        value = getValueFromJson(jsonString, JITTER_MAX_M_JSON_KEY)
        if (null != value) {
            val doubleValue = value.toDoubleOrNull()
            Log.d(TAG, "$JITTER_MAX_M_JSON_KEY: $doubleValue")
            if (null != doubleValue) {
                JITTER_MAX_M = doubleValue
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(event: MessageEvent) {
        val msg = event.message
        if (LOCALIZATION_BEACONS_CHANGED == msg) {
            Log.d(TAG, "Received LOCALIZATION_BEACONS_CHANGED message")
            beaconManager.stopRangingBeacons(iBeaconRegion)
            setDataFromDatastore()
            closestBleBeaconId = null; closestBleBeacon = null; closestBleDistance= -1.0; closestBleBeaconUpdateTime = -1
            closestBleLat = 0.0; closestBleLon = 0.0; closestBleAlt = 0.0
            beaconManager.startRangingBeacons(iBeaconRegion)
        } else if (CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED == msg) {
            CoroutineScope(Dispatchers.Main).launch {
                setValuesFromCustomDeveloperConfig()
                for (beacon in mapLocalizationBeacons.values) {
                    val (jitteredLat, jitteredLon) = jitteredLatLon(beacon.origLat, beacon.origLon, JITTER_MAX_M)
                    beacon.lat = jitteredLat
                    beacon.lon = jitteredLon
                }
            }
        }
    }


    fun getSettingsForForegroundServiceScanning(): Settings {
        val builder = Notification.Builder(this, "AltBeacon")
        builder.setSmallIcon(R.drawable.weartak_logo)
        builder.setContentTitle("Scanning for Beacons")
        val intent = Intent(mainActivity, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            mainActivity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent);
        val channel =  NotificationChannel("beacon-ref-notification-id",
            "My Notification Name", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "My Notification Channel Description"
        val notificationManager =  getSystemService(
            Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        builder.setChannelId(channel.id)
        val notification = builder.build()

        return Settings(
            scanStrategy = Settings.ForegroundServiceScanStrategy(
                notification, ALT_BEABON_SERVICE_NOTIFICATION_ID
            ),
            scanPeriods = Settings.ScanPeriods(1100, 0, 1100, 0),
            longScanForcingEnabled = true
        )
    }


    val centralRangingObserver = Observer<Collection<Beacon>> { beacons ->
        val rangeAgeMillis = System.currentTimeMillis() - (beacons.firstOrNull()?.lastCycleDetectionTimestamp ?: 0)
        //Log.d(TAG, "centralRangingObserver: rangeAgeMillis($rangeAgeMillis); beacons(${beacons.count()}): $beacons")
        if (rangeAgeMillis < 10000) {
            val updateTime = System.currentTimeMillis()
            for (beacon: Beacon in beacons) {
                val id = "${beacon.id1}|${beacon.id2}|${beacon.id3}"
                val locBeacon = mapLocalizationBeacons.get(id)
                if (null != locBeacon) {
                    //val logString = "${String.format("%.2f", beacon.distance)}m $beacon"
                    //Log.d(TAG, logString)
                    //writeToLogFileLocation(logFile, logString)
                    locBeacon.distance = beacon.distance
                    //locBeacon.lastUpdateTime = beacon.lastCycleDetectionTimestamp
                    locBeacon.lastUpdateTime = updateTime
                    locBeacon.bluetoothAddress = beacon.bluetoothAddress
                }
            }
            setClosestBleBeacon(updateTime)
        }
        /*
        else {
            val logString = "Ignoring stale ranged beacons from $rangeAgeMillis millis ago"
            Log.d(TAG, logString)
            //writeToLogFileLocation(logFile, logString)
        }

         */
    }

    private fun setClosestBleBeacon(currTimeMs: Long) {
        var closestId : String = ""; var closestDist: Double = -1.0; var closestUpdateTime: Long = -1
        var closestLat : Double = 0.0; var closestLon : Double = 0.0; var closestAlt : Double = 0.0
        for (id in mapLocalizationBeacons.keys) {
            val beacon = mapLocalizationBeacons.get(id)
            if ((null != beacon) && (beacon.lastUpdateTime >= currTimeMs - BLE_STALE_TIME_INTERVAL_MS)) { // Less than STALE_TIME_INTERVAL_MS (5 secs) ago
                if (closestDist < 0) {
                    closestId = id; closestDist = beacon.distance; closestUpdateTime = beacon.lastUpdateTime
                    closestLat = beacon.lat; closestLon = beacon.lon; closestAlt = beacon.alt
                } else {
                    if (beacon.distance < closestDist) {
                        closestId = id; closestDist = beacon.distance; closestUpdateTime = beacon.lastUpdateTime
                        closestLat = beacon.lat; closestLon = beacon.lon; closestAlt = beacon.alt
                    }
                }
            }
        }
        if (closestDist >= 0) {
            if (null == closestBleBeaconId) {
                closestBleBeaconId = closestId; closestBleBeacon = mapLocalizationBeacons[closestId]; closestBleDistance = closestDist
                closestBleBeaconUpdateTime = closestUpdateTime; closestBleLat = closestLat; closestBleLon = closestLon; closestBleAlt = closestAlt
                closestBleBeacon?.bleExitHysteresisCount = 0
            } else {
                // closestBleBeacon != null
                val currClosestBeacon: LocalizationBleBeacon = closestBleBeacon!!
                if (currClosestBeacon.lastUpdateTime < (currTimeMs - 2*BLE_STALE_TIME_INTERVAL_MS)) {
                    closestBleBeaconId = closestId; closestBleBeacon = mapLocalizationBeacons[closestId]; closestBleDistance = closestDist
                    closestBleBeaconUpdateTime = closestUpdateTime; closestBleLat = closestLat; closestBleLon = closestLon; closestBleAlt = closestAlt
                    closestBleBeacon?.bleExitHysteresisCount = 0
                } else {
                    if (closestId != closestBleBeaconId) {
                        if (currClosestBeacon.bleExitHysteresisCount++ >= BLE_EXIT_HYSTERESIS_COUNT) {
                            currClosestBeacon.bleExitHysteresisCount = 0
                            closestBleBeaconId = closestId; closestBleBeacon = mapLocalizationBeacons[closestId]; closestBleDistance = closestDist
                            closestBleBeaconUpdateTime = closestUpdateTime; closestBleLat = closestLat; closestBleLon = closestLon; closestBleAlt = closestAlt
                        } else {
                            //Log.d(TAG, "In BLE hysteresis w/ count: ${currClosestBeacon.bleExitHysteresisCount}")
                        }
                    } else {
                        currClosestBeacon.bleExitHysteresisCount = 0
                        closestBleDistance = closestDist; closestBleBeaconUpdateTime = closestUpdateTime
                        closestBleLat = closestLat; closestBleLon = closestLon; closestBleAlt = closestAlt
                    }
                }
            }
        }
        //val logString = "Closest Beacon($closestBleBeaconId, ${closestBleBeacon?.bluetoothAddress}): updated($closestBleBeaconUpdateTime, ${convertTimestampToLocalDateTime(closestBleBeaconUpdateTime)}) dist($closestBleDistance) loc($closestBleLat, $closestBleLon, $closestBleAlt)"
        //Log.d(TAG, logString)
        //writeToLogFileLocation(logFile, logString)
    }

    fun convertTimestampToLocalDateTime(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDateTime {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zoneId)
    }

    private val centralMonitoringObserver = Observer<Int> { state ->
        if (state == MonitorNotifier.OUTSIDE) {
            Log.d(TAG, "outside beacon region.")
        }
        else {
            Log.d(TAG, "inside beacon region.")
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "startForegroundService called")
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "BleBeacon Service"
        }

        val notificationBuilder = Notification.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.weartak_logo)
            .setContentText("WearTAK-CIV BLE beacon service running")
            .setPriority(PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(BLE_BEACON_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set BLE Beacon ForegroundService: ${e.message}", e)
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "BleBeaconService"
        val channelName = "BLE Beacon Foreground Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_HIGH
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleBeaconService onDestroy()")
        isRunning.set(false)
        stopForegroundServiceCompat()

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        beaconManager.stopRangingBeacons(iBeaconRegion)
    }

    companion object {
        private val TAG = "WearTAK-" + BleBeaconService::class.java.simpleName
        private const val BLE_BEACON_SERVICE_NOTIFICATION_ID = 1166
        private const val ALT_BEABON_SERVICE_NOTIFICATION_ID = 1167
        private var BLE_STALE_TIME_INTERVAL_MS = 5_000L
        private var BLE_EXIT_HYSTERESIS_COUNT = 10
        private const val BLE_STALE_TIME_INTERVAL_MS_JSON_KEY = "bleStaleTimeIntervalMs"
        private const val BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY = "bleExitHysteresisCount"
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        var closestBleBeaconId: String? = null
        var closestBleBeacon: LocalizationBleBeacon? = null
        var closestBleDistance: Double = -1.0 // This gives an estimation also of error and currently we assume that horizontal error is the same as the distance
                                              // TODO: we can explicitly provide error estimates
        var closestBleBeaconUpdateTime: Long = -1
        var closestBleLat: Double = 0.0
        var closestBleLon: Double = 0.0
        var closestBleAlt: Double = 0.0
        private var JITTER_MIN_M : Double = -2.0
        private var JITTER_MAX_M : Double = 2.0
        private const val JITTER_MIN_M_JSON_KEY = "jitterMinM"
        private const val JITTER_MAX_M_JSON_KEY = "jitterMaxM"
    }
}
