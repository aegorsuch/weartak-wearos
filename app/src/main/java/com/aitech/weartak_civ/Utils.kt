/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.provider.Settings
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.runtime.collectAsState
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import com.aitech.weartak_civ.data.model.LatLon
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.receiver.AlarmReceiver
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleAlt
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeacon
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeaconUpdateTime
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleDistance
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLat
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLon
import com.aitech.weartak_civ.service.CotDispatchService.BleLocationOutput
import com.aitech.weartak_civ.service.CotDispatchService.Companion.GPS_STALE_TIME_INTERVAL_MS
import com.aitech.weartak_civ.service.CotDispatchService.Companion.OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import java.security.SecureRandom
import java.lang.reflect.Method
import dalvik.system.PathClassLoader
import mil.nga.mgrs.MGRS

object Utils {
    private const val TAG = "WearTAK-Utils"
    private var logFileFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private const val DECIMAL_DEGREES_PER_METER = 3.631033E-06 //Approximate decimal degrees per meter at the equator
    const val EARTH_RADIUS = 6378137.0  // in meters
    const val GPS_ALARM_REQUEST_CODE = 234324243
    const val REDUCE_ALARM_TIME_MS = 35
    val secureRandom: SecureRandom = SecureRandom()

    fun buildFallbackLocation(): Location =
        Location("manual").apply {
            latitude = 0.0
            longitude = 0.0
            altitude = 0.0
            accuracy = 999_999.0f
            verticalAccuracyMeters = 999_999.0f
            time = System.currentTimeMillis()
        }

    fun isPointInPolygon(point: LatLon, polygon: List<LatLon>): Boolean {
        if (polygon.size < 3) return false

        val x = point.lon
        val y = point.lat

        var inside = false
        var j = polygon.lastIndex

        for (i in polygon.indices) {
            val xi = polygon[i].lon
            val yi = polygon[i].lat
            val xj = polygon[j].lon
            val yj = polygon[j].lat

            val intersects =
                ((yi > y) != (yj > y)) &&
                        (x < (xj - xi) * (y - yi) / (yj - yi + 0.0) + xi)

            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    // airplaneModeStatus value should be "1" (airplaneMode ON) / "0" (airplaneMode OFF)
    fun setAirplaneMode(airplaneModeStatus: String): Boolean {
        var success = true
        try {
            val classLoader = mainActivity.classLoader
            val pathClassLoader = PathClassLoader(
                "/system/framework/com.samsung.android.knox.wearable.common.jar",
                "/system/framework/com.samsung.android.knox.wearable.common.jar",
                classLoader)
            //DpmWrapper API: setSystemSettings(ComponentName who, String callerPackage, String settings, String value)
            var dpmWrapper: Class<*> =
                pathClassLoader.loadClass("com.samsung.android.knox.wearable.common.DpmWrapper")
            val method: Method = dpmWrapper.getDeclaredMethod(
                "setGlobalSettings",
                ComponentName::class.java, Context::class.java, String::class.java,
                String::class.java)
            method.invoke(dpmWrapper, null, mainActivity, Settings.Global.AIRPLANE_MODE_ON, airplaneModeStatus)
        } catch (e : Exception) {
            e.printStackTrace()
            success = false
        }
        Log.i(TAG, "setAirplaneMode($airplaneModeStatus) returning $success")
        return success
    }


    fun runTimerJob(timeMs: Long) {
        startGlobalTimer(timeMs)
    }

    fun startGlobalTimer(timeMs: Long) {
        val context = com.aitech.weartak_civ.service.LocationService.getInstance() ?: mainActivity
        val alarmIntent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, GPS_ALARM_REQUEST_CODE, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
        val deadline = SystemClock.elapsedRealtime() + (timeMs - REDUCE_ALARM_TIME_MS).coerceAtLeast(1L)
        // Routine GPS is not a user alarm: allow deep doze to defer it.
        if (com.aitech.weartak_civ.service.CotDispatchService.stationaryLocation) {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent)
        } else if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent)
        } else {
            Log.w(TAG, "Exact routine location alarm unavailable; using an inexact observation")
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent)
        }
    }

    fun cancelGlobalTimer(context: Context) {
        val pendingIntent = PendingIntent.getBroadcast(context, GPS_ALARM_REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        (context.getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
    }

    fun <T> listEqualsIgnoreOrder(list1: List<T>, list2: List<T>): Boolean {
        return list1.size == list2.size && list1.toSet() == list2.toSet()
	}

    // Managed config uses this to determine if an app restriction is configured or not.
    fun Bundle.getNullableBoolean(key: String): Boolean? =
        if (this.containsKey(key)) this.getBoolean(key) else null

    private fun Bundle.getNullableString(key: String): String? =
        if (this.containsKey(key)) this.getString(key) else null

    /**
     * Starts this Service in the foreground. Routine services share the coordinated
     * WearTAK active card; dedicated services keep their own channel and notification.
     * If Android refuses promotion (API 31+ background-start restriction), the service stops
     * itself instead of crashing; ReactiveServiceManager restarts it on a later reconcile.
     *
     * @return false when foreground promotion was refused and the service is stopping.
     *
     * @param notificationId  the ID for startForeground(...)
     * @param iconRes         small icon resource (default = R.drawable.weartak_logo)
     * @param contentText     text shown under the icon (default = "<ServiceName> running")
     * @param channelId       ID for the channel (default = "<ServiceName>_channel")
     * @param channelName     human-readable channel name (default = "<ServiceName> Background Channel")
     * @param importance      channel importance (default = IMPORTANCE_LOW)
     */
    @SuppressLint("ObsoleteSdkInt", "ServiceCast")
    fun Service.startForegroundServiceCompat(
        notificationId: Int,
        @DrawableRes iconRes: Int = R.drawable.weartak_logo,
        contentText: String = "${this.javaClass.simpleName} running",
        channelId: String = "${this.javaClass.simpleName}_channel",
        channelName: String = "${this.javaClass.simpleName} Background Channel",
        importance: Int
    ): Boolean {
        val tag = this.javaClass.simpleName
        com.aitech.weartak_civ.service.RoutineServiceNotification.startIfRoutine(this)?.let { started ->
            if (!started) refuseForeground(tag)
            return started
        }
        Log.d(tag, "Starting $tag as ForegroundService")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, importance).apply {
                lightColor = Color.GRAY
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }

        // Build the notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(iconRes)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // Finally, start as foreground
        try {
            startForeground(notificationId, notification)
        } catch (e: NullPointerException) {
            Log.e(tag, "Failed to set $tag as ForegroundService", e)
        } catch (e: RuntimeException) {
            if (!com.aitech.weartak_civ.service.RoutineServiceNotification.isForegroundStartRefusal(e)) throw e
            Log.w(tag, "Foreground start refused for $tag", e)
            refuseForeground(tag)
            return false
        }
        return true
    }

    private fun Service.refuseForeground(tag: String) {
        Log.w(tag, "Stopping $tag: foreground start not allowed now; manager will retry")
        stopSelf()
    }

    fun Service.stopForegroundServiceCompat() {
        if (!com.aitech.weartak_civ.service.RoutineServiceNotification.stopIfRoutine(this)) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
    }

    fun isValidJson(jsonString: String) : Boolean {
        if (jsonString.isNullOrEmpty()) return false
        return try {
            Gson().fromJson(jsonString, Any::class.java)
            true
        } catch (e: JsonSyntaxException) {
            false
        }
    }

    fun getValueFromJson(jsonString: String, key: String): String? {
        return try {
            val gson = Gson()
            val jsonObject: JsonObject = gson.fromJson(jsonString, JsonObject::class.java)
            jsonObject.get(key)?.asString
        } catch (e: Exception) {
            null
        }
    }

    fun isStringEmptyJson(jsonString: String): Boolean {
        if (jsonString.isNullOrEmpty()) {
            return true
        }

        return try {
            Gson().fromJson(jsonString, Any::class.java)
            jsonString.trim() == "{}" || jsonString.trim() == "[]"
        } catch (e: JsonParseException) {
            false
        }
    }

    fun vibrateWatch(
        vibDurationMs: Long = 200L,
        amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE,
        numberOfVibes: Int = 1,
        timeBtwnVibesMs: Long = 300L
    ) {
        val ctx = WearTakApplication.AppCtx.app.applicationContext

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }

        vibrator?.let { vib ->
            if (!vib.hasVibrator()) return

            if (numberOfVibes <= 1) {
                // Single vibration
                val effect = VibrationEffect.createOneShot(
                    vibDurationMs,
                    amplitude
                )
                vib.vibrate(effect)
            } else {
                // Build waveform pattern:
                // [delay, vibrate, pause, vibrate, pause, ...]
                // First value must be initial delay (0 for immediate start)

                val patternSize = numberOfVibes * 2
                val timings = LongArray(patternSize)

                // amplitudes array must match timings length
                val amplitudes = IntArray(patternSize)

                for (i in 0 until numberOfVibes) {
                    val vibIndex = i * 2
                    val pauseIndex = vibIndex + 1

                    timings[vibIndex] = vibDurationMs
                    amplitudes[vibIndex] = amplitude

                    if (pauseIndex < patternSize) {
                        timings[pauseIndex] = timeBtwnVibesMs
                        amplitudes[pauseIndex] = 0
                    }
                }

                val effect = VibrationEffect.createWaveform(
                    timings,
                    amplitudes,
                    -1 // no repeat
                )

                vib.vibrate(effect)
            }
        }
    }

    fun cancelWatchVibration() {
        val ctx = WearTakApplication.AppCtx.app.applicationContext

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }

        vibrator?.cancel()
    }

    fun latLonToMgrs(lat: Double, lon: Double): String {
        val mgrs = MGRS.from(lon, lat)
        return mgrs.coordinate() // 1-meter precision
    }

    fun writeToLogFileLocation(logFile: File?, data: String) {
        if (null == logFile) {
            return
        }
        try {
            val writer = FileWriter(logFile, true) // Append to existing file
            writer.write("${LocalDateTime.now().format(logFileFormatter)}: $data\n")
            writer.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write log to file: ${e.message}", e)
        }
    }

    fun appendJsonLine(logFile: File?, json: String) {
        if (logFile == null) {
            return
        }
        try {
            FileWriter(logFile, true).use { writer ->
                writer.write(json)
                writer.write("\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to append json line: ${e.message}", e)
        }
    }

    private val repo by lazy {
        (WearTakApplication.AppCtx.app as WearTakApplication).repo
    }

    suspend fun logAudioDetectionFeedback(
        context: Context,
        event: AudioDetectionEvent,
        label: AudioDetectionUserLabel,
        respondedAtMs: Long,
        responseLatencyMs: Long,
        debugDirName: String,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val dir = File(context.filesDir, debugDirName).apply { mkdirs() }
        val logFile = File(dir, "${event.detectorKey}_feedback.jsonl")
        val sl = repo.snapshotLocation()
        val ss = repo.snapshotSystem()

        val obj = buildAudioDetectionEventJson(event, sl, ss).apply {
            put("label", label.name)
            put("respondedAtMs", respondedAtMs)
            put("responseLatencyMs", responseLatencyMs)
            put("triggerToFeedbackMs", respondedAtMs - event.timestamp)

            val extrasObj = org.json.JSONObject()
            for ((k, v) in extra) extrasObj.put(k, v)
            put("extra", extrasObj)
        }

        appendJsonLine(logFile, obj.toString())
    }

    suspend fun logAudioDetectionClassification(
        context: Context,
        event: AudioDetectionEvent,
        debugDirName: String
    ) {
        val classification = event.classification ?: return
        val dir = File(context.filesDir, debugDirName).apply { mkdirs() }
        val logFile = File(dir, "${event.detectorKey}_classification.jsonl")
        val sl = repo.snapshotLocation()
        val ss = repo.snapshotSystem()

        val obj = buildAudioDetectionEventJson(event, sl, ss).apply {
            put("predictedLabel", classification.predictedLabel)
            put("predictedClassIndex", classification.predictedIndex)
            put("predictedScore", classification.predictedScore)
        }

        appendJsonLine(logFile, obj.toString())
    }

    private fun buildAudioDetectionEventJson(
        event: AudioDetectionEvent,
        location: DataRepository.LocationSnapshot?,
        system: DataRepository.SystemSnapshot
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("uid", event.uid)
        put("inferenceWindowUid", event.uid)
        put("eventTimestamp", event.timestamp)
        put("detectorKey", event.detectorKey)
        put("confidence", event.confidence)
        put("triggerPcmPath", event.triggerPcmPath)
        put("audioSessionId", event.chunkRef.sessionId)
        put("audioSeq", event.chunkRef.seq)
        put("audioChunkTimestampMs", event.chunkRef.timestampMs)
        put("sampleRateHz", event.chunkRef.sampleRateHz)
        put("channels", event.chunkRef.channels)
        put("micSource", event.audioSource)
        put("inferStartElapsedMs", event.inferStartElapsedMs)
        put("inferEndElapsedMs", event.inferEndElapsedMs)
        putClassificationFields(event)
        putDeviceFields(system)
        putLocationFields(location)
        put("isWorn", system.systemStates.isWorn)
        put("charging", system.systemStates.charging)
        put("bat", system.batteryPercent)
        put("latestActRec", repo.latestActivityRecognized.value)
    }

    private fun org.json.JSONObject.putClassificationFields(event: AudioDetectionEvent) {
        event.classification?.let { classification ->
            put("classifierLabel", classification.predictedLabel)
            put("classifierClassIndex", classification.predictedIndex)
            put("classifierScore", classification.predictedScore)
            put("classifierScores", org.json.JSONArray(classification.scores))
            put("classifierLabels", org.json.JSONArray(classification.labels))
        }
    }

    private fun org.json.JSONObject.putDeviceFields(system: DataRepository.SystemSnapshot) {
        put("manufacturer", android.os.Build.MANUFACTURER)
        put("model", android.os.Build.MODEL)
        put("sdkInt", android.os.Build.VERSION.SDK_INT)
        put("wearTakVersion", system.wearTakVersion)
        put("callsign", system.callsign)
        put("deviceId", system.deviceId)
    }

    private fun org.json.JSONObject.putLocationFields(location: DataRepository.LocationSnapshot?) {
        location?.let {
            put("lat", it.lat)
            put("lon", it.lon)
            put("hae", it.hae)
            put("ce", it.ce)
        }
    }

    fun jitteredLatLon(lat: Double, lon: Double, maxJitterM: Double): Pair<Double, Double> {
        val maxJitterUnsignedM = abs(maxJitterM)
        // Generate random offsets in [-maxJitterUnsignedM, maxJitterUnsignedM] meters
        val dx = (secureRandom.nextDouble() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // East-West
        val dy = (secureRandom.nextDouble() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // North-South
        //Log.d(TAG, "jitteredLatLon: dlatM($dy), dLonM($dx): dist(${(sqrt(dx*dx + dy*dy))})")

        // Convert to angular distance in radians
        val dLat = dy / EARTH_RADIUS
        val dLon = dx / (EARTH_RADIUS * cos(Math.toRadians(lat)))

        // Apply to original coordinates
        val newLat = lat + Math.toDegrees(dLat)
        val newLon = lon + Math.toDegrees(dLon)

        return Pair(newLat, newLon)
    }

    /** Minimal data models for bloodhound. */
    enum class BloodhoundTargetKind { MARKER, CONTACT, POINT, ALERT }

    data class BloodhoundItem(
        val target: String,
        val uid: String,
        val callsign: String,
        val timeReceived: Long,
        val distance: Double?, // May not come directly from COT, changes consistently
        val orientation: Float, // May not come directly from COT, changes consistently
        val bloodhounding: Boolean,
        val acknowledged: Boolean = false,
        val alertCategory: String? = null,
        val isRemoteAlert: Boolean = false,
        val isStale: Boolean = false,
        val hasLocation: Boolean = true,
        val targetKind: BloodhoundTargetKind = BloodhoundTargetKind.MARKER,
        val isCamera: Boolean = false
    )

    fun normalize0to360(deg: Double): Double =
        ((deg % 360.0) + 360.0) % 360.0

    /** Minimal data models for physio alerts. */
    enum class AlertChipState { None, Warning, Emergency }

    data class PhysioAlert(
        val alert: String,
        val level: AlertChipState,
        val time: Long,
        val trigger: String
	)
    data class SelectedPhysioMetric(
        val key: String,
        val icon: Painter?,
        val metric: String,
        val value: String
    )
    data class TextSelectionButton (
        val shortenedText: String,
        val longText: String
    )

    object LocationUtils {
        fun mapLocationAgainstClosestBleBeacon(loc: Location): BleLocationOutput {

            var latActual = loc.latitude; var lonActual = loc.longitude; var haeActual = loc.altitude; var ceActual = loc.accuracy; val startTimeInMs = System.currentTimeMillis();

            //Log.d(TAG, "closestBleBeaconUpdateTime($closestBleBeaconUpdateTime); currTime($startTimeInMs); diff (${abs(startTimeInMs - closestBleBeaconUpdateTime)})")
            if ((closestBleBeaconUpdateTime > 0) && (abs(startTimeInMs - closestBleBeaconUpdateTime) <= GPS_STALE_TIME_INTERVAL_MS)) {
                if (ceActual > closestBleDistance) {
                    //val logString = "Using BLE($closestBleLat, $closestBleLon, $closestBleAlt): gpsHorizError($ceActual) > bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                    //Log.d(TAG, logString)
                    //writeToLogFileLocation(logFile, logString)
                    ceActual = closestBleDistance.toFloat()
                    latActual = closestBleLat
                    lonActual = closestBleLon
                    haeActual = closestBleAlt
                    closestBleBeacon?.overrideGPSWithBLECount = 0
                } else {
                    val currClosestBleBeacon = closestBleBeacon!!
                    if (currClosestBleBeacon.overrideGPSWithBLECount++ < OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT) {
                        //val logString = "GPS Hysteresis(${currClosestBleBeacon.overrideGPSWithBLECount}) Using BLE($closestBleLat, $closestBleLon, $closestBleAlt): gpsHorizError($ceActual) <= bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                        //Log.d(TAG, logString)
                        //writeToLogFileLocation(logFile, logString)
                        ceActual = closestBleDistance.toFloat()
                        latActual = closestBleLat
                        lonActual = closestBleLon
                        haeActual = closestBleAlt
                    } else {
                        currClosestBleBeacon.overrideGPSWithBLECount = 0
                        //val logString = "Using GPS: gpsHorizAcc($ceActual) <= bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                        //Log.d(TAG, logString)
                        //writeToLogFileLocation(logFile, logString)
                    }
                }
            } else {
                //val logString = "Using GPS: gpsHorizAcc($ceActual) w/ closestBleBeaconUpdateTime($closestBleBeaconUpdateTime); currTime($startTimeInMs); diff (${abs(startTimeInMs - closestBleBeaconUpdateTime)})"
                //Log.d(TAG, logString)
                //writeToLogFileLocation(logFile, logString)
            }
            return BleLocationOutput(latActual, lonActual, haeActual, ceActual)
        }
    }

    object PhysioUtils {
        private fun calculateMaxHeartRate(ageYears: Int): Double {
            return (208 - 0.7 * ageYears)
        }

        fun calculateHeartRateReserve(maxHr: Int, restingHr: Int): Int {
            return (maxHr - restingHr)
        }

        fun calculateExertion(currentHr: Int, ageYears: Int, restingHr: Int): Double {
            //Log.i(TAG, "calculateExertion(hr=$currentHr, age=$ageYears, restHr=$restingHr)")
            if (currentHr < 0.0) return -1.0
            val mhr = calculateMaxHeartRate(ageYears)
            val hrr = mhr - restingHr
            if (hrr <= 0) return 1.0 // mhr >= resting heart rate, hence 100% exertion
            //Log.i(TAG, "exertion ($currentHr, $ageYears, $restingHr, $mhr): ($currentHr-$restingHr)/($mhr-$restingHr)")
            return ((currentHr - restingHr).coerceAtLeast(0)) / hrr // If currentHr <= restingHr, then the user is not at all exerting them selfs, hence exertion = 0
        }

        fun classifyExertion(exertion: Double): String = when {
            exertion >= 0.7 -> "VIGOROUS"
            exertion >= 0.5 -> "MODERATE"
            exertion < 0.0 -> "UNKNOWN"
            else            -> "LIGHT"
        }

        fun convertFarenheitToCelsius(f: Float?): Float {
            return (f?.minus(32))?.times(5)?.div(9) ?: 0F
        }

        sealed class PhysioEvent {
            data class Hr(val bpm: Int, val t: Long) : PhysioEvent()
            data class Motion(val accelRms: Double, val t: Long) : PhysioEvent()
        }

        class RestingHeartRateAggregator(
            private val maxSamples: Int = 15 // 15 * 2 hr samples/sec --> 30 second buffer window
        ) {
            private val buffer = ArrayDeque<Double>(maxSamples)
            private val lock = Mutex()

            suspend fun pushSample(bpm: Double) {
                lock.withLock {
                    buffer.addLast(bpm)
                    if (buffer.size > maxSamples) buffer.removeFirst()
                }
            }

            suspend fun computeAverage(minSamples: Int = maxSamples / 2): Double? {
                return lock.withLock {
                    if (buffer.size >= minSamples) {
                        buffer.average()
                    } else null
                }
            }
        }
    }

    object SettingsUtils {
        private const val TAG = "WearTAK-SettingsUtils"

        data class CategoryRolesEntry(
            val category: String, val roles: List<String>
        )

        fun selectableRoleCategories(entries: List<CategoryRolesEntry>): List<CategoryRolesEntry> =
            entries.filterNot { it.category == "FES" }

        /**
         * Reads the default roles/categories from res/raw/roles_json_config.json.
         * On any error, logs it and returns an empty list.
         */
        fun Context.defaultRoleCategoryEntriesFromRaw(): List<CategoryRolesEntry> {
            return try {
                Log.i(TAG, "Loading default categories from raw JSON")
                val jsonStr = resources.openRawResource(R.raw.roles_json_config)
                    .bufferedReader()
                    .use { it.readText() }

                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<CategoryRolesEntry>>() {}
                val type = typeToken.type
                Gson().fromJson<List<CategoryRolesEntry>>(jsonStr, type).also {
                    Log.i(TAG, "Parsed ${it.size} default categories")
                }
            } catch (io: IOException) {
                Log.e(TAG, "I/O error reading roles_json_config.json", io)
                emptyList()
            } catch (js: JsonSyntaxException) {
                Log.e(TAG, "Malformed JSON in roles_json_config.json", js)
                emptyList()
            } catch (jp: JsonParseException) {
                Log.e(TAG, "Gson failed to parse JSON", jp)
                emptyList()
            } catch (ex: Exception) {
                Log.e(TAG, "Unexpected error loading default categories", ex)
                emptyList()
            }
        }
    }

    fun timeNowIsoUtc(): String = java.time.Instant.now().toString()

}
