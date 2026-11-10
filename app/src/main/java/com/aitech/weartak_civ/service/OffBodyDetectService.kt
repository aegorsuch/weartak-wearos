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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
//import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.events.IsDeviceWornEvent.Companion.IS_NOT_WORN
import com.aitech.weartak_civ.events.IsDeviceWornEvent.Companion.IS_WORN
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.atomic.AtomicBoolean

class OffBodyDetectService: Service() {
    private lateinit var sensorManager: SensorManager
    private var offBodySensor: Sensor? = null
    var permission: Boolean = false
    private var active = false
    private var sensorRegistered = false
    private var lastObservationAtNanos = Long.MIN_VALUE

    private val dataRepository : DataRepository
        get() = MainActivity.dataRepository

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OffBodyDetectService onCreate()")
        isRunning.set(true)
        active = true
        startForegroundServiceCompat(
            notificationId = OFFBODY_DETECT_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        runBlocking {
            dataRepository.invalidateDeviceWornObservation()
        }
        startOffBodySensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OffBodyDetectService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        runBlocking { dataRepository.invalidateDeviceWornObservation() }
        super.onDestroy()
        Log.d(TAG, "OffBodyDetectService onDestroy()")
        isRunning.set(false)
        stopOffBodySensor()
        stopForegroundServiceCompat()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    fun startOffBodySensor() {
        Log.d(TAG, "OffBodyDetectService startOffBodySensor()")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (null != offBodySensor) {
            lastObservationAtNanos = Long.MIN_VALUE
            sensorRegistered = DiagnosticResources.acquire(DiagnosticResource.OFF_BODY, sensorEventListener,
                periodUs = 200_000, successful = { it }) {
                sensorManager.registerListener(sensorEventListener, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (sensorRegistered) Log.d(TAG, "OffBody Sensor callback registered")
            else Log.e(TAG, "OffBody listener registration refused; worn state unknown")
        } else {
            Log.e(TAG, "OffBody Sensor is null. CANNOT read off body status; worn state unknown")
        }
    }

    fun stopOffBodySensor() {
        if (sensorRegistered) {
            DiagnosticResources.release(DiagnosticResource.OFF_BODY, sensorEventListener) {
                sensorManager.unregisterListener(sensorEventListener)
            }
            sensorRegistered = false
        }
    }

    val sensorEventListener = object : SensorEventListener {
        var offBodyDataFloat : Float = 0.0F
        var accuracyVal = -1
        //var lastTimeHrSent : Long = 0L
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "OffBodyDetectService onAccuracyChanged(): accuracy= $accuracy")
            accuracyVal = accuracy
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!active || !sensorRegistered) return
            if (event.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                // On-change sensors deliver their current activation's retained value to new listeners.
                if (event.timestamp < 0L || event.timestamp <= lastObservationAtNanos ||
                    event.timestamp > android.os.SystemClock.elapsedRealtimeNanos()) {
                    Log.w(TAG, "Ignoring invalid or out-of-order off-body observation")
                    return
                }
                offBodyDataFloat = event.values[0]
                Log.d(TAG, "OffBodyDetectService onSensorChanged(): accuracy= $accuracyVal, offbody= $offBodyDataFloat")
                if (!offBodyDataFloat.isFinite() ||
                    (Math.abs(offBodyDataFloat.toDouble()) > TOLERANCE &&
                        Math.abs(offBodyDataFloat - 1.0) > TOLERANCE)) {
                    Log.w(TAG, "Ignoring invalid off-body sensor value")
                    return
                }
                lastObservationAtNanos = event.timestamp
                if (Math.abs(offBodyDataFloat - 1.0) > TOLERANCE) {
                    runBlocking {
                        dataRepository.storeLatestIsDeviceWorn(false, source = "offbody-sensor")
                        Log.i(TAG, "OffBodyDetectService Device is not worn")
                        EventBus.getDefault().post(IsDeviceWornEvent(IS_NOT_WORN))
                        CepBus.emit(CepEvent(Topics.OFFBODY, OffBodyPayload(on_wrist = 0)))
                    }
                } else {
                    runBlocking {
                        dataRepository.storeLatestIsDeviceWorn(true, source = "offbody-sensor")
                        Log.i(TAG, "OffBodyDetectService Device is worn")
                        EventBus.getDefault().post(IsDeviceWornEvent(IS_WORN))
                        CepBus.emit(CepEvent(Topics.OFFBODY, OffBodyPayload(on_wrist = 1)))
                    }
                }
            }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + OffBodyDetectService::class.java.simpleName
        private val OFFBODY_DETECT_SERVICE_NOTIFICATION_ID = 103
        private val TOLERANCE = 0.001F
//        val offBodyDetectLiveData = MutableLiveData<Boolean>(true)
        val isRunning = AtomicBoolean(false)
        private lateinit var instance : OffBodyDetectService
    }
}
