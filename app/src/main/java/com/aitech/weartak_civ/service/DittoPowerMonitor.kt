package com.aitech.weartak_civ.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class DittoPowerMonitor(
    private val context: Context,
    private val repo: DataRepository,
    private val scope: CoroutineScope,
    private val changed: (DittoPowerState) -> Unit,
) : AutoCloseable {
    private val power = context.getSystemService(PowerManager::class.java)
    private val system = MutableStateFlow(systemState())
    private var job: Job? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    private fun systemState(): Pair<Boolean, Boolean> =
        (power.isDeviceIdleMode || BEDTIME_KEYS.any {
            Settings.Global.getInt(context.contentResolver, it, 0) != 0
        }) to power.isPowerSaveMode

    private fun refresh() { system.value = systemState() }

    suspend fun initial(threshold: Int) = DittoPowerState(
        worn = repo.latestDeviceWornObservation.value,
        charging = repo.chargingState.value,
        battery = repo.latestBatteryPercentage.value,
        lowBatteryThreshold = threshold,
        sleeping = system.value.first,
        powerSave = system.value.second,
        alerting = repo.isAlerting.value,
        stayConnectedOffWrist = repo.stayConnectedOffWrist.first(),
    )

    fun start() {
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        registered = true
        BEDTIME_KEYS.forEach {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
        }
        refresh()
        job = scope.launch {
            combine(
                combine(repo.latestDeviceWornObservation, repo.chargingState, repo.latestBatteryPercentage,
                    repo.getBatteryWarnPercentage, repo.isAlerting) { worn, charging, battery, threshold, alerting ->
                    DittoPowerState(worn, charging, battery, threshold, alerting = alerting)
                }, system, repo.stayConnectedOffWrist
            ) { state, os, stay -> state.copy(sleeping = os.first, powerSave = os.second,
                stayConnectedOffWrist = stay) }
                .distinctUntilChanged().collect(changed)
        }
    }

    override fun close() {
        job?.cancel()
        if (registered) context.unregisterReceiver(receiver)
        registered = false
        context.contentResolver.unregisterContentObserver(observer)
    }

    companion object {
        // Wear OS/OEM settings are read-only and optional; absent keys are not sleep evidence.
        internal val BEDTIME_KEYS = listOf("bedtime_mode", "wearable_bedtime_mode")
    }
}
