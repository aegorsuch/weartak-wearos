/*
 * Copyright (C) 2024, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Battery has process-wide consumers even with alerts off; no independent FGS is needed. */
class BatteryPowerObserver(context: Context, private val dataRepository: DataRepository) {
    private val context = context.applicationContext
    private var serviceScope: CoroutineScope? = null
    private var registered = false
    private var lastDockType: DockType? = null
    private var lastBatteryPct: Int? = null
    private var lastChargingOrFull: Boolean? = null

    fun start() {
        if (registered) return
        lastDockType = null
        lastBatteryPct = null
        lastChargingOrFull = null
        serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        val sticky = context.registerReceiver(
            batteryPowerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)     // includes EXTRA_PLUGGED + status
                addAction(Intent.ACTION_POWER_CONNECTED)     // edge-trigger signal
                addAction(Intent.ACTION_POWER_DISCONNECTED)  // edge-trigger signal
            }
        )
        registered = true
        isRunning.set(true)
        sticky?.let { batteryPowerReceiver.onReceive(context, it) }
        Log.d(TAG, "Battery power receiver registered")
    }

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(batteryPowerReceiver)
        isRunning.set(false)
        serviceScope?.cancel()
        serviceScope = null
    }

    private val batteryPowerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!registered) return
            if (intent.action != Intent.ACTION_BATTERY_CHANGED &&
                intent.action != Intent.ACTION_POWER_CONNECTED &&
                intent.action != Intent.ACTION_POWER_DISCONNECTED
            ) return

            // ACTION_BATTERY_CHANGED carries the extras we care about.
            // POWER_CONNECTED / DISCONNECTED may not include EXTRA_PLUGGED on all devices,
            // so we fall back to querying the sticky BATTERY_CHANGED intent if needed.
            val batteryIntent = if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                intent
            } else {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }

            if (batteryIntent == null) {
                Log.w(TAG, "batteryPowerReceiver: batteryIntent=null (cannot determine dock state)")
                return
            }

            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) {
                Log.w(TAG, "batteryPowerReceiver: invalid level/scale (level=$level, scale=$scale)")
                return
            }
            val batteryPct = ((level.toFloat() / scale.toFloat()) * 100f).toInt()

            val dockType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> DockType.WIRELESS
                BatteryManager.BATTERY_PLUGGED_USB      -> DockType.USB
                else                                    -> DockType.NONE
            }

            val chargingOrFull =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

            // De-dupe to avoid spamming repo/logs if system sends repeated BATTERY_CHANGED
            val dockChanged = dockType != lastDockType
            val chargingChanged = (chargingOrFull != lastChargingOrFull)
            val pctChanged = batteryPct != lastBatteryPct
            AppDiagnostics.powerBatteryCallback(dockChanged || chargingChanged)
            if (!dockChanged && !pctChanged && !chargingChanged) return

            lastDockType = dockType
            lastBatteryPct = batteryPct
            lastChargingOrFull = chargingOrFull
            AppDiagnostics.record(DiagnosticEvent.BATTERY, mapOf(
                DiagnosticMetric.BATTERY_PERCENT to batteryPct.toLong(),
                DiagnosticMetric.DOCK to dockType.ordinal.toLong(),
                DiagnosticMetric.CHARGING to if (chargingOrFull) 1L else 0L
            ))

            Log.i(TAG, "batteryPowerReceiver: pct=$batteryPct dockType=$dockType (plugged=$plugged, status=$status, chargingOrFull=$chargingOrFull)")

            serviceScope?.launch {
                if (pctChanged) dataRepository.storeLatestBatteryPercentage(batteryPct)
                if (dockChanged) dataRepository.updateDockType(dockType)
                if (chargingChanged) dataRepository.updateChargingState(chargingOrFull)
            }
        }
    }

    enum class DockType {
        NONE,
        USB,
        WIRELESS
    }

    companion object {
        private const val TAG = "WearTAK-BatteryPowerObserver"
        val isRunning : AtomicBoolean = AtomicBoolean(false)
    }
}