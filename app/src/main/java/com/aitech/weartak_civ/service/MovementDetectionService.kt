/*
 * Copyright (C) 2025, Ascent Integrated Tech.
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MovementDetectionService : Service(), SensorEventListener {

    // --- Sensors / manager ---
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var rotationVector: Sensor? = null

    // --- Data Layers ---
    private val repo by lazy { (applicationContext as WearTakApplication).repo as DataRepository }
    // --- WearTAK Service requirements: Binder / scope ---
    private val binder = MovementDetectionServiceBinder()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val interactive = MutableStateFlow(false)
    private var interactiveInitialized = false
    private var rotationRegistered = false
    private var rotationStartedNanos = 0L
    private var receiverRegistered = false
    private var complicationProjection: Job? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateInteractive()
        }
    }

    // --- Movement detection state ---
    private val gravity = FloatArray(3)
    private var isUserMoving = false
    private val transitionHandler = Handler(Looper.getMainLooper())
    private var highCount = 0
    private var lowCount = 0

    // --- Heading state ---
    private var lastAzimuthTrueDeg: Double? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MovementDetectionService onCreate()")
        isRunning.set(true)

        startForegroundServiceCompat(
            notificationId = MOVEMENT_DETECTION_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        // Init sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Register listeners
//        accelerometer?.let { sensorManager.registerListener(this, it, ACCELEROMETER_DELAY_US) } // not currently using for compass
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        updateInteractive()
        collectHeadingDemand((applicationContext as WearTakApplication).bhRepo.activeUid)

        // Initial state
        isUserMoving = false
        highCount = 0
        lowCount = 0
        // currentInterval = INTERVAL_STILL
        // updateCotInterval(isStill = true, newInterval = INTERVAL_STILL)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "MovementDetectionService onStartCommand()")
        return START_NOT_STICKY
    }

    inner class MovementDetectionServiceBinder : Binder() {
        val service: MovementDetectionService
            get() = this@MovementDetectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (!rotationRegistered || event.timestamp < rotationStartedNanos) return

        when (event.sensor.type) {
//            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event.values)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event.values)
        }
    }

    private fun updateInteractive() {
        val current = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (interactiveInitialized && interactive.value == current) return
        interactiveInitialized = true
        interactive.value = current
        if (current) repo.noteLocationMotion()
        AppDiagnostics.record(DiagnosticEvent.DISPLAY_INTERACTIVE, mapOf(
            DiagnosticMetric.ACTIVE to if (current) 1L else 0L
        ))
    }

    private fun collectHeadingDemand(activeTarget: StateFlow<String?>) {
        scope.launch {
            combine(interactive, repo.headingComplications.activeIds) { screen, ids ->
                screen && ids.isNotEmpty()
            }.distinctUntilChanged().collect { wanted ->
                complicationProjection?.cancel()
                complicationProjection = if (wanted) scope.launch {
                    repo.azimuthTrueDegSmoothed.collect { }
                } else null
            }
        }
        scope.launch {
            combine(interactive, repo.magneticHeadingSubscriptions, activeTarget,
                repo.headingComplications.activeIds) { screen, subscribers, active, slots ->
                active != null || (screen && (subscribers > 0 || slots.isNotEmpty()))
            }.distinctUntilChanged().collect(::setRotationDemand)
        }
    }

    private fun setRotationDemand(wanted: Boolean) {
        if (wanted == rotationRegistered) return
        if (wanted) {
            val sensor = rotationVector
            if (sensor == null) {
                Log.w(TAG, "Rotation sensor unavailable for requested heading")
                return
            }
            lastAzimuthTrueDeg = null
            lastPublishAtMs = 0L
            rotationStartedNanos = android.os.SystemClock.elapsedRealtimeNanos()
            rotationRegistered = DiagnosticResources.acquire(DiagnosticResource.HEADING, this,
                periodUs = 200_000, successful = { it }) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (!rotationRegistered) Log.e(TAG, "Rotation listener registration failed")
        } else {
            rotationRegistered = false
            DiagnosticResources.release(DiagnosticResource.HEADING, this) {
                sensorManager.unregisterListener(this)
            }
            // Preserve the last bearing for cached ambient/complication rendering.
        }
        AppDiagnostics.record(DiagnosticEvent.ROTATION_LISTENER, mapOf(
            DiagnosticMetric.ACTIVE to if (rotationRegistered) 1L else 0L,
            DiagnosticMetric.RESULT to if (wanted && !rotationRegistered) 0L else 1L
        ))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "MovementDetectionService onAccuracyChanged(): sensor(${sensor?.name}) accuracy($accuracy)")
        // If needed, you can gate outputs on low accuracy here.
    }

    // --- Accelerometer-based movement detection ---
    private fun handleAccelerometer(values: FloatArray) {
        // Simple gravity filter
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2]

        // Linear acceleration magnitude
        val ax = values[0] - gravity[0]
        val ay = values[1] - gravity[1]
        val az = values[2] - gravity[2]
        val accelMag = sqrt(ax * ax + ay * ay + az * az)

        if (accelMag > MOVEMENT_THRESHOLD) {
            highCount++
            lowCount = 0
            // Cancel pending still confirmation
            transitionHandler.removeCallbacks(confirmStillRunnable)

            if (!isUserMoving && highCount >= REQUIRED_CONSECUTIVE_HIGHS) {
                isUserMoving = true
                Log.d(TAG, "MovementDetectionService ✅ MOVING ($REQUIRED_CONSECUTIVE_HIGHS consecutive HIGHs)")
                // updateCotInterval(isStill = false, newInterval = INTERVAL_MOVING)
                highCount = 0
            }
        } else {
            highCount = 0
            lowCount++

            if (isUserMoving && lowCount >= REQUIRED_CONSECUTIVE_LOWS) {
                if (lowCount == REQUIRED_CONSECUTIVE_LOWS) {
                    Log.d(TAG, "MovementDetectionService ⏳ $REQUIRED_CONSECUTIVE_LOWS LOWs, confirming STILL in $STILL_CONFIRM_DELAY_MS ms")
                    transitionHandler.removeCallbacks(confirmStillRunnable)
                    transitionHandler.postDelayed(confirmStillRunnable, STILL_CONFIRM_DELAY_MS)
                }
            }
        }
    }

    private val confirmStillRunnable = Runnable {
        if (isUserMoving) {
            isUserMoving = false
            Log.d(TAG, "MovementDetectionService 🛑 Confirmed STILL")
            // updateCotInterval(isStill = true, newInterval = INTERVAL_STILL)
        }
    }
    private var lastPublishAtMs: Long = 0L
    private val PUBLISH_INTERVAL_MS: Long = 100L   // ~3 times/sec
    private var count: Long = 0L   // if you still want to track events
// private var cachedDeclinationDeg: Double? = null // optional cache

    private fun handleRotationVector(values: FloatArray) {
        count += 1

        // Throttle: only proceed ~3 Hz (monotonic clock)
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastPublishAtMs < PUBLISH_INTERVAL_MS) return
        lastPublishAtMs = nowMs

        // Build rotation matrix
        val R = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(R, values)

        // Orientation angles (azimuth/pitch/roll)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(R, orientation)

        // Magnetic azimuth (radians → degrees)
        var azimuthMagDeg = Math.toDegrees(orientation[0].toDouble())
        azimuthMagDeg = LocationVectorCalculator.normalize0to360(azimuthMagDeg)

        // Add declination from latest location -> TRUE north
        val loc = repo.latestLocation.value
        val declinationDeg = if (loc != null) {
            GeomagneticField(
                loc.latitude.toFloat(),
                loc.longitude.toFloat(),
                (if (loc.hasAltitude()) loc.altitude else 0.0).toFloat(),
                System.currentTimeMillis()
            ).declination.toDouble()
        } else 0.0

        val azimuthTrue = LocationVectorCalculator.normalize0to360(azimuthMagDeg + declinationDeg)

        // Smooth and publish at throttled rate
        val smoothed = lastAzimuthTrueDeg?.let {
            LocationVectorCalculator.smoothAngle(it, azimuthTrue, HEADING_SMOOTH_ALPHA)
        } ?: azimuthTrue
        lastAzimuthTrueDeg = smoothed
        repo.updateMagAzimuthTrue(smoothed)
    }

    private var currentInterval = INTERVAL_STILL

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MovementDetectionService onDestroy()")
        isRunning.set(false)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        receiverRegistered = false
        complicationProjection?.cancel()
        if (rotationRegistered) setRotationDemand(false) else sensorManager.unregisterListener(this)
        repo.updateMagAzimuthTrue(null)
        scope.coroutineContext.cancel()
        stopForegroundServiceCompat()
    }

    // --- Required by your manager / project conventions ---
    companion object {
        val isRunning = AtomicBoolean(false)
        private val TAG = "WearTAK-MvmtService"

        private const val MOVEMENT_DETECTION_SERVICE_NOTIFICATION_ID = 112
        private const val ACCELEROMETER_DELAY_US = 100_000 // 100 ms (~10 Hz)

        // Movement thresholds
        private const val MOVEMENT_THRESHOLD = 8.0f
        private const val REQUIRED_CONSECUTIVE_HIGHS = 3
        private const val REQUIRED_CONSECUTIVE_LOWS = 3
        private const val STILL_CONFIRM_DELAY_MS = 5_000L

        // (Optional) CoT intervals
        private const val INTERVAL_MOVING = 5   // seconds
        private const val INTERVAL_STILL = 30   // seconds

        // Heading smoothing
        private const val HEADING_SMOOTH_ALPHA = 0.15
    }
}
