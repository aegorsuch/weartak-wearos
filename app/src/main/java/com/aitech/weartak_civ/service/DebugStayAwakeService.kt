package com.aitech.weartak_civ.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class StayAwakeStopReason(val code: Long, val message: Int) {
    WIFI_OFF(1, R.string.debug_stay_awake_requires_wifi),
    UNPLUGGED(2, R.string.debug_stay_awake_requires_power),
    WIFI_UNAVAILABLE(3, R.string.debug_stay_awake_requires_wifi),
    POWER_UNKNOWN(4, R.string.debug_stay_awake_requires_power),
    WIFI_ENABLE_FAILED(5, R.string.debug_stay_awake_wifi_enable_failed),
    WIFI_ENABLE_TIMEOUT(6, R.string.debug_stay_awake_wifi_enable_failed)
}

class DebugStayAwakeService : Service() {
    private var screenLock: PowerManager.WakeLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiRequestCallback: ConnectivityManager.NetworkCallback? = null
    private var running = false
    private var monitoring = false
    private val handler = Handler(Looper.getMainLooper())
    private var waitingForWifi = false
    private val wifiTimeout = Runnable {
        if (waitingForWifi) failSession(StayAwakeStopReason.WIFI_ENABLE_TIMEOUT)
    }
    private val conditionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!monitoring) return
            val manual = DebugStayAwakePreferences(this@DebugStayAwakeService).enabled
            if (waitingForWifi && intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN) ==
                WifiManager.WIFI_STATE_ENABLED) {
                onStartCommand(Intent().setAction(ACTION_RESTORE), 0, 0)
                return
            }
            val reason = when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> when (
                    intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                ) {
                    WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_DISABLED ->
                        if (waitingForWifi) StayAwakeStopReason.WIFI_ENABLE_FAILED
                        else StayAwakeStopReason.WIFI_OFF
                    else -> null
                }
                Intent.ACTION_POWER_DISCONNECTED -> if (!manual) StayAwakeStopReason.UNPLUGGED else null
                Intent.ACTION_BATTERY_CHANGED -> if (
                    !manual && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) <= 0
                ) StayAwakeStopReason.UNPLUGGED else null
                else -> null
            }
            if (reason != null) {
                failSession(reason)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preferences = DebugStayAwakePreferences(this)
        if (intent?.action == ACTION_STOP) {
            preferences.recordSession(false)
            ChargingStayAwake.setEnabled(this, false)
            finishSession()
            return START_NOT_STICKY
        }
        if (!preferences.enabled && !chargingRequested(this)) {
            finishSession()
            return START_NOT_STICKY
        }
        try {
            if (!monitoring) {
                ContextCompat.registerReceiver(this, conditionReceiver, IntentFilter().apply {
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                }, ContextCompat.RECEIVER_EXPORTED)
                monitoring = true
            }
            startRefusal(this, preferences.enabled)?.let { reason ->
                releaseLocks()
                refuseStart(this, reason)
                finishSession()
                return START_NOT_STICKY
            }
            showNotification()
            if (preferences.enabled &&
                applicationContext.getSystemService(WifiManager::class.java).wifiState !=
                WifiManager.WIFI_STATE_ENABLED) {
                if (!waitingForWifi) {
                    waitingForWifi = true
                    handler.postDelayed(wifiTimeout, WIFI_ENABLE_TIMEOUT_MS)
                }
                return START_STICKY
            }
            waitingForWifi = false
            handler.removeCallbacks(wifiTimeout)
            if (running) {
                if (activeManualMode != preferences.enabled) acquireScreenLock()
                return START_STICKY
            }
            acquireLocks()
            requestWifi()
            // Do not re-enable a radio switched off during resource acquisition.
            wifiRefusal(this)?.let { reason ->
                releaseLocks()
                refuseStart(this, reason)
                finishSession()
                return START_NOT_STICKY
            }
            if (!monitoring || (!preferences.enabled && !chargingRequested(this))) {
                finishSession()
                return START_NOT_STICKY
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot start debug stay awake", error)
            Toast.makeText(this, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
            finishSession()
            return START_NOT_STICKY
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Android prevented debug stay awake", error)
            Toast.makeText(this, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
            finishSession()
            return START_NOT_STICKY
        }
        running = true
        AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK,
            mapOf(DiagnosticMetric.KIND to 1L, DiagnosticMetric.ACTIVE to 1L))
        mutableActive.value = true
        Log.i(TAG, "Debug stay awake running (${if (preferences.enabled) "manual" else "charging"})")
        return START_STICKY
    }

    private fun failSession(reason: StayAwakeStopReason) {
        val pending = waitingForWifi
        val manual = DebugStayAwakePreferences(this).enabled
        releaseLocks()
        DebugStayAwakePreferences(this).recordSession(false)
        recordReason(
            if (pending) DiagnosticEvent.STAY_AWAKE_START_REFUSED
            else DiagnosticEvent.STAY_AWAKE_AUTO_DISABLED, reason
        )
        if (manual) Toast.makeText(this, reason.message, Toast.LENGTH_SHORT).show()
        finishSession()
    }

    private fun requestWifi() {
        // Wi-Fi locks alone do not give WearWifiMediator an active network request.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() {
                DiagnosticResources.note(DiagnosticResource.DEBUG_NETWORK, this, ResourceAction.FAILED)
                handler.post {
                    if (wifiRequestCallback === this) {
                        if (DebugStayAwakePreferences(this@DebugStayAwakeService).enabled) {
                            failSession(StayAwakeStopReason.WIFI_UNAVAILABLE)
                        } else {
                            DiagnosticResources.end(DiagnosticResource.DEBUG_NETWORK, this)
                        }
                    }
                }
            }
        }
        DiagnosticResources.acquire(DiagnosticResource.DEBUG_NETWORK, callback) {
            getSystemService(ConnectivityManager::class.java).requestNetwork(request, callback)
        }
        wifiRequestCallback = callback
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenLock() {
        val power = getSystemService(PowerManager::class.java)
        screenLock?.let {
            if (it.isHeld) DiagnosticResources.release(DiagnosticResource.DEBUG_SCREEN, it) { it.release() }
        }
        activeManualMode = DebugStayAwakePreferences(this).enabled
        // A window flag cannot keep the display awake after leaving the app UI.
        val screenLevel = if (activeManualMode)
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK else PowerManager.SCREEN_DIM_WAKE_LOCK
        screenLock = power.newWakeLock(screenLevel, "$TAG:screen").apply {
            setReferenceCounted(false)
            DiagnosticResources.acquire(DiagnosticResource.DEBUG_SCREEN, this) { acquire() }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        acquireScreenLock()
        val power = getSystemService(PowerManager::class.java)
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
            setReferenceCounted(false)
            DiagnosticResources.acquire(DiagnosticResource.DEBUG_CPU, this) { acquire() }
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
            setReferenceCounted(false)
            DiagnosticResources.acquire(DiagnosticResource.DEBUG_WIFI, this) { acquire() }
        }
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.debug_stay_awake_channel), NotificationManager.IMPORTANCE_LOW
        ))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DebugStayAwakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WearTAKSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = getString(if (
            applicationContext.getSystemService(WifiManager::class.java).wifiState ==
            WifiManager.WIFI_STATE_ENABLED
        ) R.string.debug_stay_awake_active else R.string.debug_stay_awake_waiting)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle(getString(R.string.debug_stay_awake))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                status + "\n" +
                    getString(R.string.debug_stay_awake_warning)
            ))
            .setContentIntent(open)
            .addAction(0, getString(R.string.debug_stay_awake_stop), stop)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun releaseLocks() {
        waitingForWifi = false
        handler.removeCallbacks(wifiTimeout)
        if (monitoring) {
            monitoring = false
            unregisterReceiver(conditionReceiver)
        }
        val hadLocks = wifiLock != null || cpuLock != null || screenLock != null
        wifiRequestCallback?.let { callback ->
            wifiRequestCallback = null
            try {
                DiagnosticResources.release(DiagnosticResource.DEBUG_NETWORK, callback) {
                    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
                }
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Debug stay awake Wi-Fi request was already released", error)
            }
        }
        wifiLock?.let {
            if (it.isHeld) DiagnosticResources.release(DiagnosticResource.DEBUG_WIFI, it) { it.release() }
            else DiagnosticResources.end(DiagnosticResource.DEBUG_WIFI, it, expired = true)
        }
        cpuLock?.let {
            if (it.isHeld) DiagnosticResources.release(DiagnosticResource.DEBUG_CPU, it) { it.release() }
            else DiagnosticResources.end(DiagnosticResource.DEBUG_CPU, it, expired = true)
        }
        screenLock?.let {
            if (it.isHeld) DiagnosticResources.release(DiagnosticResource.DEBUG_SCREEN, it) { it.release() }
            else DiagnosticResources.end(DiagnosticResource.DEBUG_SCREEN, it, expired = true)
        }
        wifiLock = null
        cpuLock = null
        screenLock = null
        if (hadLocks) AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK,
            mapOf(DiagnosticMetric.KIND to 1L, DiagnosticMetric.ACTIVE to 0L))
        running = false
        activeManualMode = false
        mutableActive.value = false
    }

    private fun finishSession() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        Log.i(TAG, "Debug stay awake stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearTAK-DebugStayAwake"
        internal const val ACTION_STOP = "com.aitech.weartak_civ.DEBUG_STAY_AWAKE_STOP"
        internal const val ACTION_RESTORE = "com.aitech.weartak_civ.DEBUG_STAY_AWAKE_RESTORE"
        private const val CHANNEL_ID = "debug_stay_awake"
        private const val NOTIFICATION_ID = 64_001
        internal const val WIFI_ENABLE_TIMEOUT_MS = 20_000L
        private val mutableActive = MutableStateFlow(false)
        private var activeManualMode = false
        val active = mutableActive.asStateFlow()

        fun setEnabled(context: Context, enabled: Boolean) {
            if (enabled) {
                startRefusal(context, manual = true)?.let { reason ->
                    refuseStart(context, reason)
                    context.stopService(Intent(context, DebugStayAwakeService::class.java))
                    return
                }
            }
            DebugStayAwakePreferences(context).recordSession(enabled)
            if (enabled || chargingRequested(context)) restoreIfEnabled(context)
            else context.stopService(Intent(context, DebugStayAwakeService::class.java))
        }

        fun restoreIfEnabled(context: Context) {
            val manual = DebugStayAwakePreferences(context).enabled
            if (!manual && !chargingRequested(context)) return
            if (active.value && activeManualMode == manual) return
            startRefusal(context, manual)?.let { reason ->
                refuseStart(context, reason)
                context.stopService(Intent(context, DebugStayAwakeService::class.java))
                return
            }
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, DebugStayAwakeService::class.java).setAction(ACTION_RESTORE)
                )
            } catch (error: SecurityException) {
                reportRestoreFailure(context, error)
            } catch (error: IllegalStateException) {
                reportRestoreFailure(context, error)
            }
        }

        private fun reportRestoreFailure(context: Context, error: RuntimeException) {
            Log.e(TAG, "Cannot restore Stay awake; saved intent remains enabled", error)
            Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
        }

        private fun wifiRefusal(context: Context): StayAwakeStopReason? {
            when (context.applicationContext.getSystemService(WifiManager::class.java).wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> Unit
                WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_DISABLED ->
                    return StayAwakeStopReason.WIFI_OFF
                else -> return StayAwakeStopReason.WIFI_UNAVAILABLE
            }
            return null
        }

        internal fun chargingRequested(context: Context): Boolean =
            DebugStayAwakePreferences(context).chargingEnabled &&
                ReactiveServiceManager.isRunning.get() && ChargingStayAwake.powerConnected &&
                ChargingStayAwake.isPlugged(context)

        @Suppress("DEPRECATION")
        private fun startRefusal(context: Context, manual: Boolean): StayAwakeStopReason? {
            if (manual) {
                val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
                return when (wifi.wifiState) {
                    WifiManager.WIFI_STATE_ENABLED, WifiManager.WIFI_STATE_ENABLING -> null
                    WifiManager.WIFI_STATE_DISABLED -> try {
                        // Android 10+ permits this only for exempt DO/PO/system callers.
                        // WRITE_SECURE_SETTINGS does not confer that exemption.
                        if (wifi.setWifiEnabled(true)) null else StayAwakeStopReason.WIFI_ENABLE_FAILED
                    } catch (error: SecurityException) {
                        Log.e(TAG, "Wi-Fi activation permission denied", error)
                        StayAwakeStopReason.WIFI_ENABLE_FAILED
                    }
                    else -> StayAwakeStopReason.WIFI_UNAVAILABLE
                }
            }
            wifiRefusal(context)?.let { return it }
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                null, -1 -> StayAwakeStopReason.POWER_UNKNOWN
                0 -> StayAwakeStopReason.UNPLUGGED
                else -> null
            }
        }

        private fun refuseStart(context: Context, reason: StayAwakeStopReason) {
            DebugStayAwakePreferences(context).recordSession(false)
            recordReason(DiagnosticEvent.STAY_AWAKE_START_REFUSED, reason)
            Toast.makeText(context, reason.message, Toast.LENGTH_SHORT).show()
        }

        private fun recordReason(event: DiagnosticEvent, reason: StayAwakeStopReason) {
            Log.i(TAG, "$event: ${reason.name}")
            AppDiagnostics.record(event, mapOf(DiagnosticMetric.REASON to reason.code))
        }

        fun stop(context: Context) {
            DebugStayAwakePreferences(context).recordSession(false)
            ChargingStayAwake.setEnabled(context, false)
            context.stopService(Intent(context, DebugStayAwakeService::class.java))
        }
    }
}
