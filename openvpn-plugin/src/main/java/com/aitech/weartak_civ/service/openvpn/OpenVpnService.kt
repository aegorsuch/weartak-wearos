/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service.openvpn

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.OpenVpnPluginApplication
import com.aitech.weartak_civ.OpenVpnPluginRuntime
import com.aitech.weartak_civ.OpenVpnPluginService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class OpenVpnStatus { STOPPED, CONNECTING, CONNECTED, STOPPING, ERROR }

data class OpenVpnRuntimeState(
    val status: OpenVpnStatus = OpenVpnStatus.STOPPED,
    val profileId: String? = null,
    val error: String? = null,
)

class OpenVpnService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationGeneration = AtomicLong()
    private val destroyed = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private var connectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.openvpn),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.openvpn))
            .setContentText(getString(R.string.openvpn_service_running))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId.isNullOrBlank()) {
                    publish(OpenVpnRuntimeState(OpenVpnStatus.ERROR, error = "No OpenVPN profile selected"))
                    stopForegroundCompat()
                    stopSelf(startId)
                } else {
                    beginConnect(profileId)
                }
            }
            ACTION_DISCONNECT -> requestDisconnect(startId)
            else -> {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun beginConnect(profileId: String) {
        val generation = operationGeneration.incrementAndGet()
        connectJob?.cancel()
        monitorJob?.cancel()
        connectJob = serviceScope.launch {
            publish(OpenVpnRuntimeState(OpenVpnStatus.CONNECTING, profileId))
            try {
                val repository = (application as OpenVpnPluginApplication).openVpnProfiles
                val profile = repository.get(profileId) ?: error("OpenVPN profile not found")
                if (OpenVpnNativeBridge.getState() != OpenVpnNativeBridge.State.STOPPED) {
                    stopNativeUntilStopped()
                }
                if (operationGeneration.get() != generation) return@launch
                val config = OpenVpnProfileSupport.nativeConfig(profile, profile.username, profile.password)
                val result = try {
                    withContext(Dispatchers.IO) { OpenVpnNativeBridge.start(config) }
                } finally {
                    config.fill(0)
                }
                if (result != OpenVpnNativeBridge.Result.OK) {
                    error(OpenVpnNativeBridge.resultName(result))
                }
                val connected = withContext(Dispatchers.IO) {
                    OpenVpnNativeBridge.awaitConnected(CONNECT_TIMEOUT_MS)
                }
                if (operationGeneration.get() != generation) return@launch
                if (connected != OpenVpnNativeBridge.Result.OK) {
                    error(OpenVpnNativeBridge.resultName(connected))
                }
                publish(OpenVpnRuntimeState(OpenVpnStatus.CONNECTED, profileId))
                monitorJob = serviceScope.launch { monitor(profileId, generation) }
            } catch (error: Exception) {
                if (operationGeneration.get() == generation) {
                    val reason = try {
                        stopNativeUntilStopped()
                        safeError(error)
                    } catch (_: Exception) {
                        "OpenVPN cleanup failed"
                    }
                    publish(OpenVpnRuntimeState(OpenVpnStatus.ERROR, profileId, reason))
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private suspend fun monitor(profileId: String, generation: Long) {
        while (operationGeneration.get() == generation) {
            delay(750)
            if (OpenVpnNativeBridge.getState() != OpenVpnNativeBridge.State.CONNECTED) {
                publish(OpenVpnRuntimeState(OpenVpnStatus.ERROR, profileId, "VPN tunnel disconnected"))
                stopForegroundCompat()
                stopSelf()
                return
            }
        }
    }

    private fun requestDisconnect(startId: Int?) {
        if (destroyed.get()) return
        val generation = operationGeneration.incrementAndGet()
        connectJob?.cancel()
        monitorJob?.cancel()
        val profileId = runtimeState.value.profileId
        publish(OpenVpnRuntimeState(OpenVpnStatus.STOPPING, profileId))
        serviceScope.launch {
            try {
                stopNativeUntilStopped()
                if (operationGeneration.get() == generation) {
                    publish(OpenVpnRuntimeState())
                    stopForegroundCompat()
                    if (startId == null) stopSelf() else stopSelf(startId)
                }
            } catch (_: Exception) {
                if (operationGeneration.get() == generation) {
                    publish(OpenVpnRuntimeState(OpenVpnStatus.ERROR, profileId, "OpenVPN cleanup failed"))
                }
            }
        }
    }

    private suspend fun stopNativeUntilStopped() {
        val requested = withContext(Dispatchers.IO) { OpenVpnNativeBridge.stop() }
        if (requested != OpenVpnNativeBridge.Result.OK &&
            requested != OpenVpnNativeBridge.Result.STOPPING) {
            error(OpenVpnNativeBridge.resultName(requested))
        }
        repeat(STOP_POLL_COUNT) {
            if (OpenVpnNativeBridge.getState() == OpenVpnNativeBridge.State.STOPPED) return
            delay(STOP_POLL_MS)
        }
        error("OpenVPN cleanup timed out")
    }

    override fun onDestroy() {
        destroyed.set(true)
        if (activeInstance === this) activeInstance = null
        operationGeneration.incrementAndGet()
        connectJob?.cancel()
        monitorJob?.cancel()
        OpenVpnPluginRuntime.closeStreams()
        if (OpenVpnNativeBridge.getState() != OpenVpnNativeBridge.State.STOPPED) {
            OpenVpnNativeBridge.stop()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publish(state: OpenVpnRuntimeState) {
        mutableRuntimeState.value = state
        OpenVpnPluginRuntime.publishRuntimeState(state.profileId, state.status)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun safeError(error: Exception): String = when {
        error.message == "OpenVPN profile not found" -> error.message!!
        error.message?.startsWith("Enter valid OpenVPN") == true -> error.message!!
        error.message?.startsWith("OpenVPN credentials") == true -> error.message!!
        else -> "OpenVPN connection failed"
    }

    companion object {
        const val ACTION_CONNECT = "com.aitech.weartak_civ.openvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.aitech.weartak_civ.openvpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val NOTIFICATION_ID = 59037
        private const val CHANNEL_ID = "openvpn_service"
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val STOP_POLL_COUNT = 100
        private const val STOP_POLL_MS = 100L
        private val mutableRuntimeState = kotlinx.coroutines.flow.MutableStateFlow(OpenVpnRuntimeState())
        @Volatile private var activeInstance: OpenVpnService? = null
        val runtimeState: kotlinx.coroutines.flow.StateFlow<OpenVpnRuntimeState> = mutableRuntimeState

        fun connect(context: Context, profileId: String) {
            val intent = Intent(context, OpenVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect() {
            OpenVpnPluginRuntime.closeStreams()
            activeInstance?.requestDisconnect(null)
        }

        fun currentState(): OpenVpnRuntimeState = mutableRuntimeState.value
    }
}
