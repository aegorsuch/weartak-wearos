/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakServerP12Validator
import com.aitech.weartak_civ.cot.CompanionChatIngestor
import com.aitech.weartak_civ.events.TakChatEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.DISCONNECTED
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.CONNECTION_FAILED
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.NOT_CONNECTED
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.ERROR
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus

/**
 * CompanionAppService:
 * - Foreground start via Utils.startForegroundServiceCompat
 * - START_NOT_STICKY
 * - Standardized logging TAG
 * - Companion isRunning flag
 *
 * Rename class + adjust NOTIFICATION_ID. Add your work loop (HandlerThread/Coroutine) if needed.
 */

class CompanionAppService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val gson = Gson()

    private val notifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifyMutex = Mutex()
    private val NOTIFY_CHUNK_DELAY_MS = 12L // tune 8–20ms

    @Volatile private var activeAddr: String? = null
    private val linkMtus = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun isActive(device: BluetoothDevice): Boolean =
        activeAddr != null && device.address == activeAddr

    private val capRepo by lazy {
        try {
            (applicationContext as WearTakApplication).capRepo
        } catch (_: Throwable) {
            CompanionAppRepository(applicationContext)
        }
    }

    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    internal fun beginGraceForPreviouslyReadyCompanion() {
        if (repo.companionEverReady.value == true) {
            Log.i(TAG, "Previously ready Companion found; delaying direct transports until Companion is ready")
            repo.setCompanionTransportPending()
        }
    }

    private val bhRepo by lazy { (applicationContext as WearTakApplication).bhRepo }
    private val bloodhoundSyncMutex = Mutex()
    private val bloodhoundConnectionGeneration = java.util.concurrent.atomic.AtomicLong()
    private val companionUserLock = Any()
    private var phoneRoutesReceived = false
    private val routeSync by lazy {
        CompanionRouteSync(
            activeRoute = { bhRepo.routeFollow.value },
            prepare = {
                phoneRoutesReceived = true
                bhRepo.preparePhoneRoute(it)
            },
            resumeLocal = bhRepo::resumeLocalRoute,
            send = ::sendBloodhoundControl,
            reportError = { error ->
                Log.w(TAG, "Companion route: $error")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this,
                        getString(R.string.companion_route_sync_failed), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        )
    }
    private fun advanceCompanionGeneration() = synchronized(companionUserLock) {
        bloodhoundConnectionGeneration.incrementAndGet()
        selfStatus.reset()
        repo.clearCompanionUsers()
        if (phoneRoutesReceived) {
            routeSync.connectionChanged(false)
            bhRepo.disconnectPhoneRoute()
        }
    }
    internal fun beginCompanionSubscription() {
        // Samsung can reopen a GATT client without disconnecting the shared physical link.
        advanceCompanionGeneration()
        rxBuf.clear()
        capRepo.gateway.reset()
        helloSentForConnection = false
        Log.i(TAG, "New companion subscription generation=${bloodhoundConnectionGeneration.get()}")
    }
    private val phoneManagementSettingsMutex = Mutex()
    private val phoneManagementLease = PhoneManagementLease()
    private val phoneManagementReleaseGeneration = java.util.concurrent.atomic.AtomicLong(-1L)
    // A restarted service waits one heartbeat window for the phone to re-handshake.
    @Volatile private var phoneManagedRestoreNotBefore = 0L
    @SuppressLint("MissingPermission")
    private fun currentPeer(address: String, generation: Long): Boolean =
        bloodhoundConnectionGeneration.get() == generation &&
            connectedDevice?.address.equals(address, ignoreCase = true) && bloodhoundLinkReady()

    internal suspend fun releasePhoneManagement(address: String, generation: Long): Boolean {
        check(currentPeer(address, generation)) { "Companion connection is not ready" }
        repo.clearPhoneManagedSettingsForPeer(address) {
            currentPeer(address, generation)
        }?.let(::applyRestoredPhoneManagedSettings)
        val unmanaged = !repo.phoneManagedSettingsEnabled.first()
        if (unmanaged) synchronized(phoneManagementLease) {
            phoneManagementLease.stop()
            repo.setPhoneManagementSessionActive(false)
        }
        check(currentPeer(address, generation)) { "Companion connection changed during management release" }
        return unmanaged
    }

    private var observedBloodhoundGeneration = 0L
    private val selfStatus = com.aitech.weartak_civ.data.CompanionSelfStatus()
    @Volatile private var selfStatusInputs: com.aitech.weartak_civ.data.SelfStatusInputs? = null

    @SuppressLint("MissingPermission")
    private fun startSelfStatus() {
        serviceScope.launch { repo.selfStatusInputs().collect { selfStatusInputs = it } }
        serviceScope.launch {
            while (true) {
                sendSelfStatusIfDue()
                delay(1_000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendSelfStatusIfDue() = synchronized(companionUserLock) {
        if (!bloodhoundLinkReady()) selfStatus.reset()
        else selfStatusInputs?.let { input ->
            val uid = MainActivity.device_uid
            val moppGeneration = com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability.generation()
            if (uid.isBlank()) Log.w(TAG, "Self status cannot send before watch identity is ready")
            else if (selfStatus.sendIfDue(
                bloodhoundConnectionGeneration.get(), uid, input.copy(policy = input.policy.copy(
                    moppEnabled = input.policy.moppEnabled &&
                        com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability.available.value,
                )), System.currentTimeMillis(),
                android.os.SystemClock.elapsedRealtime(), { sendBloodhoundControl(it, moppGeneration) }
            ) == false) Log.w(TAG, "Self status queue unavailable; snapshot will retry")
        }
    }

    private fun gatewayAllowsControl(message: org.json.JSONObject, generation: Long): Boolean {
        if (capRepo.gateway.session == null) return true
        return when (message.optString("msgType")) {
            "gateway_cot", "gateway_ready", "gateway_error" ->
                message.optString("session") == capRepo.gateway.session
            "self_status" -> selfStatus.acceptsSession(
                generation, message.optJSONObject("payload")?.optString("session"))
            else -> false
        }
    }
    private data class BloodhoundFrame(
        val bytes: ByteArray,
        val address: String,
        val generation: Long,
        val chunkSize: Int,
        val diagnostic: Boolean = false,
        val moppGeneration: Long? = null,
    )
    private val bloodhoundFrames = kotlinx.coroutines.channels.Channel<BloodhoundFrame>(64)
    private val locationDiagnosticFrames =
        kotlinx.coroutines.channels.Channel<BloodhoundFrame>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private data class GatewayIncoming(val token: String, val generation: Long, val xml: String)
    private val gatewayIncoming = kotlinx.coroutines.channels.Channel<GatewayIncoming>(64)
    @Volatile private var locationDiagnosticsEnabled = false
    @Volatile private var locationDiagnosticsSession: String? = null
    @Volatile private var locationDiagnosticsGeneration: Long = -1L
    @Volatile private var locationDiagnosticsReportJob: kotlinx.coroutines.Job? = null
    private val locationDiagnostics by lazy {
        (applicationContext as WearTakApplication).locationDiagnostics
    }
    private fun gatewayError(token: String?, message: String) {
        Log.w(TAG, message)
        if (token == null || token != capRepo.gateway.session) return
        val json = org.json.JSONObject().put("msgType", "gateway_error")
            .put("session", token).put("error", message).toString()
        if (!sendBloodhoundControl(json)) Log.w(TAG, "Gateway error notification queue full")
    }
    private val bloodhoundSync by lazy {
        CompanionBloodhoundSync(
            activeTarget = {
                bhRepo.activeTrackingItem()?.takeUnless { it.bhEvent.isRouteFollow }?.let {
                    CompanionBloodhoundSync.Target(
                        it.uid, it.target.name ?: it.uid, it.target.point.lat,
                        it.target.point.lon, it.target.point.hae
                    )
                }
            },
            startTracking = {
                if (!BloodhoundService.isRunning.get()) {
                    Log.w(TAG, "Companion Bloodhound rejected: watch Bloodhound service is not running")
                    false
                } else {
                    bhRepo.activateCompanionTarget(it.uid, it.callsign, it.lat, it.lon, it.hae)
                }
            },
            stopTracking = bhRepo::stopTracking,
            send = ::sendBloodhoundControl,
            reportError = { error ->
                Log.w(TAG, "Companion Bloodhound: $error")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this, getString(R.string.companion_bloodhound_sync_failed), android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            updateTracking = {
                bhRepo.updateCompanionTarget(it.uid, it.callsign, it.lat, it.lon, it.hae)
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun bloodhoundLinkReady(): Boolean =
        bleSendReady() && connectedDevice?.bondState == BluetoothDevice.BOND_BONDED

    @SuppressLint("MissingPermission")
    private fun isCurrentSingleWatch(address: String, generation: Long): Boolean =
        currentPeer(address, generation) && capRepo.gateway.session == null

    @SuppressLint("MissingPermission")
    private fun refreshBloodhoundConnection() {
        val generation = bloodhoundConnectionGeneration.get()
        if (observedBloodhoundGeneration != generation) {
            bloodhoundSync.connectionChanged(false)
            synchronized(companionUserLock) { routeSync.connectionChanged(false) }
            observedBloodhoundGeneration = generation
        }
        bloodhoundSync.connectionChanged(bloodhoundLinkReady() && capRepo.gateway.session == null)
        synchronized(companionUserLock) {
            routeSync.connectionChanged(bloodhoundLinkReady() && capRepo.gateway.session == null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPointDeletionRelay() {
        serviceScope.launch {
            capRepo.bleConnectionState.collect {
                repo.companionPointGeneration = bloodhoundConnectionGeneration.get()
                refreshLocationDiagnosticsSession()
            }
        }
        serviceScope.launch {
            repo.pointDeletionRequests.collect { request ->
                if (request.revision.generation == bloodhoundConnectionGeneration.get()) {
                    val json = com.aitech.weartak_civ.data.CompanionPointRevisions
                        .deletion(request.uid, request.revision.token).toString()
                    if (!sendBloodhoundControl(json)) {
                        Log.w(TAG, "Point deletion write rejected")
                        repo.pointDeletionAcks.timedOut(request.uid, request.revision.token, request.revision.generation)
                            ?.let { repo.reportPointDeletionResult(it) }
                    } else {
                        trackPointDeletion(request.uid, request.revision.token, request.revision.generation)
                    }
                } else Log.w(TAG, "Point deletion belongs to ended connection")
            }
        }
    }

    private fun trackPointDeletion(uid: String, token: String, generation: Long) {
        repo.pointDeletionAcks.sent(uid, token, generation)
        serviceScope.launch {
            delay(POINT_DELETION_ACK_TIMEOUT_MS)
            repo.pointDeletionAcks.timedOut(uid, token, generation)?.let { repo.reportPointDeletionResult(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBloodhoundSync() {
        serviceScope.launch {
            bhRepo.routeFollow.collect {
                synchronized(companionUserLock) {
                    routeSync.connectionChanged(bloodhoundLinkReady() && capRepo.gateway.session == null)
                    routeSync.localRouteChanged()
                }
            }
        }
        serviceScope.launch {
            while (true) {
                val frame = bloodhoundFrames.tryReceive().getOrNull()
                    ?: kotlinx.coroutines.selects.select {
                        bloodhoundFrames.onReceiveCatching { it.getOrNull() }
                        locationDiagnosticFrames.onReceiveCatching { it.getOrNull() }
                    }
                    ?: continue
                if (!com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability
                        .isCurrent(frame.moppGeneration)) continue
                if (capRepo.gateway.session != null) {
                    val message = org.json.JSONObject(frame.bytes.toString(Charsets.UTF_8))
                    if (!gatewayAllowsControl(message, frame.generation)) continue
                }
                if (frame.diagnostic) {
                    val message = org.json.JSONObject(frame.bytes.toString(Charsets.UTF_8))
                    val type = message.optString("msgType")
                    val stopAfterDisable = type ==
                        com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.STOP &&
                        !locationDiagnosticsEnabled && locationDiagnosticsSession == null
                    if (!isCurrentSingleWatch(frame.address, frame.generation) ||
                        (!stopAfterDisable &&
                            locationDiagnosticsSession != message.optString("session"))
                    ) continue
                }
                sendChunkedNotify(
                    frame.bytes, frame.chunkSize, expectedAddress = frame.address,
                    expectedGeneration = frame.generation, diagnostics = frame.diagnostic
                )
            }
        }
        serviceScope.launch {
            capRepo.bleConnectionState.collect {
                bloodhoundSyncMutex.withLock {
                    refreshBloodhoundConnection()
                }
            }
        }
        serviceScope.launch {
            bhRepo.activeUid.collect {
                bloodhoundSyncMutex.withLock {
                    refreshBloodhoundConnection()
                    if (capRepo.gateway.session != null) return@withLock
                    bloodhoundSync.localTargetChanged()
                }
            }
        }
        serviceScope.launch {
            bhRepo.activeBhItemMap.collect {
                bloodhoundSyncMutex.withLock {
                    refreshBloodhoundConnection()
                    if (capRepo.gateway.session != null) return@withLock
                    bloodhoundSync.localTargetChanged()
                }
            }
        }
        serviceScope.launch {
            while (true) {
                delay(1_000L)
                bloodhoundSyncMutex.withLock {
                    refreshBloodhoundConnection()
                    if (capRepo.gateway.session != null) return@withLock
                    bloodhoundSync.checkTimeouts()
                    synchronized(companionUserLock) { routeSync.checkTimeouts() }
                }
            }
        }
    }

    private fun startLocationDiagnostics() {
        serviceScope.launch {
            repo.getBondedLocationDiagnosticsEnabled.collect { enabled ->
                locationDiagnosticsEnabled = enabled
                if (!enabled) stopLocationDiagnosticsSession("disabled")
                else refreshLocationDiagnosticsSession()
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun refreshLocationDiagnosticsSession() {
        if (!locationDiagnosticsEnabled || !bloodhoundLinkReady() ||
            capRepo.gateway.session != null
        ) {
            stopLocationDiagnosticsSession("connection not eligible")
            return
        }
        val generation = bloodhoundConnectionGeneration.get()
        if (locationDiagnosticsSession != null && locationDiagnosticsGeneration == generation) return

        val session = com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.newSession()
        locationDiagnosticsSession = session
        locationDiagnosticsGeneration = generation
        val address = connectedDevice?.address ?: return
        val frame = BloodhoundFrame(
            "${com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.hello(session)}\n"
                .toByteArray(Charsets.UTF_8),
            address,
            generation,
            maxNotifyPayloadBytes(),
            diagnostic = true
        )
        if (!locationDiagnosticFrames.trySend(frame).isSuccess) {
            Log.w(TAG, "Location diagnostics capability queue unavailable")
            stopLocationDiagnosticsSession("capability queue unavailable")
        }
    }

    @Synchronized
    private fun stopLocationDiagnosticsSession(reason: String) {
        val session = locationDiagnosticsSession
        val generation = locationDiagnosticsGeneration
        val address = connectedDevice?.address
        val wasActive = session != null
        locationDiagnosticsReportJob?.cancel()
        locationDiagnosticsReportJob = null
        locationDiagnosticsSession = null
        locationDiagnosticsGeneration = -1L
        locationDiagnostics.stop()
        // Repeated inactive-session cleanup must not drain a stop awaiting the sender.
        if (wasActive) {
            while (locationDiagnosticFrames.tryReceive().isSuccess) Unit
        }
        if (reason == "disabled" && session != null && address != null &&
            generation == bloodhoundConnectionGeneration.get() &&
            bloodhoundLinkReady() && capRepo.gateway.session == null
        ) {
            val stop = com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.stop(session)
            val bytes = "$stop\n".toByteArray(Charsets.UTF_8)
            val frame = BloodhoundFrame(
                bytes, address, generation, maxNotifyPayloadBytes(), diagnostic = true
            )
            if (!locationDiagnosticFrames.trySend(frame).isSuccess) {
                Log.w(TAG, "Location diagnostics stop notification could not be queued")
            }
        }
        if (wasActive) Log.i(TAG, "Bonded location diagnostics stopped: $reason")
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun sendLocationDiagnosticSnapshot() {
        val session = locationDiagnosticsSession ?: return
        val address = connectedDevice?.address ?: return
        val generation = bloodhoundConnectionGeneration.get()
        if (!locationDiagnosticsEnabled || !isCurrentSingleWatch(address, generation) ||
            generation != locationDiagnosticsGeneration
        ) return
        val json = try {
            locationDiagnostics.watchSnapshot(session)
        } catch (error: IllegalStateException) {
            Log.i(TAG, "Skipping diagnostics snapshot after session end", error)
            return
        }
        val bytes = "$json\n".toByteArray(Charsets.UTF_8)
        if (bytes.size > com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.MAX_MESSAGE_BYTES) {
            Log.w(TAG, "Location diagnostics snapshot exceeded protocol size limit")
            return
        }
        val frame = BloodhoundFrame(bytes, address, generation, maxNotifyPayloadBytes(), diagnostic = true)
        if (!locationDiagnosticFrames.trySend(frame).isSuccess) {
            Log.w(TAG, "Location diagnostics snapshot dropped because its bounded queue is full")
        }
    }

    private fun handleLocationDiagnosticsMessage(json: String, type: String): Boolean {
        if (type !in setOf(
                com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.CAPABILITY,
                com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.SNAPSHOT,
                com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.STOP
            )
        ) return false
        val address = connectedDevice?.address ?: return true
        val generation = bloodhoundConnectionGeneration.get()
        val expected = locationDiagnosticsSession ?: return true
        if (!locationDiagnosticsEnabled || !isCurrentSingleWatch(address, generation) ||
            generation != locationDiagnosticsGeneration
        ) return true
        val valid = try {
            com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.parse(
                org.json.JSONObject(json), type, expected
            )
        } catch (error: org.json.JSONException) {
            Log.w(TAG, "Ignoring malformed bonded location diagnostics message", error)
            return true
        } ?: run {
            Log.w(TAG, "Ignoring invalid bonded location diagnostics message")
            return true
        }
        if (type == com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.STOP) {
            stopLocationDiagnosticsSession("peer disabled diagnostics")
        } else if (type == com.aitech.weartak_civ.service.LocationDiagnosticsProtocol.CAPABILITY) {
            if (!valid.optBoolean("supported", false)) {
                stopLocationDiagnosticsSession("peer does not support diagnostics")
                return true
            }
            if (!locationDiagnosticsFramesReady(address, generation)) return true
            locationDiagnostics.start(expected)
            locationDiagnosticsReportJob?.cancel()
            locationDiagnosticsReportJob = serviceScope.launch {
                while (locationDiagnosticsSession == expected &&
                    locationDiagnosticsGeneration == generation &&
                    locationDiagnosticsEnabled
                ) {
                    sendLocationDiagnosticSnapshot()
                    delay(LOCATION_DIAGNOSTICS_REPORT_INTERVAL_MS)
                }
            }
        } else if (!locationDiagnostics.acceptPhoneSnapshot(valid, expected)) {
            Log.w(TAG, "Ignoring malformed or stale phone diagnostics snapshot")
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun locationDiagnosticsFramesReady(address: String, generation: Long): Boolean =
        locationDiagnosticsEnabled && generation == bloodhoundConnectionGeneration.get() &&
            isCurrentSingleWatch(address, generation) && capRepo.gateway.session == null

    @SuppressLint("MissingPermission")
    private fun sendBloodhoundControl(json: String): Boolean = sendBloodhoundControl(json, null)

    @SuppressLint("MissingPermission")
    private fun sendBloodhoundControl(json: String, moppGeneration: Long?): Boolean {
        if (!com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability.isCurrent(moppGeneration)) return false
        if (capRepo.gateway.session != null) {
            val message = org.json.JSONObject(json)
            if (!gatewayAllowsControl(message, bloodhoundConnectionGeneration.get())) return false
        }
        if (!bloodhoundLinkReady()) return false
        val address = connectedDevice?.address ?: return false
        val generation = bloodhoundConnectionGeneration.get()
        val bytes = "$json\n".toByteArray(Charsets.UTF_8)
        val maxPayload = maxNotifyPayloadBytes()
        return bloodhoundFrames.trySend(BloodhoundFrame(bytes, address, generation, maxPayload,
            moppGeneration = moppGeneration)).isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun collectBleState() {
        serviceScope.launch {
            capRepo.bleConnectionState.collect { state ->
                if (state in listOf(DISCONNECTED, ERROR, CONNECTION_FAILED, NOT_CONNECTED)) {
                    if (connectedDevice == null) startAdvertising()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSettingsChangedCollector() {
        serviceScope.launch {
            capRepo.settingsChangedDeltasFlow().collect { delta ->
                val env = capRepo.buildSettingsChangedEnvelope(delta)
                val json = capRepo.envelopeToJson(env)

                if (!capRepo.companionAppReady.value || !notificationsEnabled) {
                    enqueueJson(json)
                    return@collect
                }

                flushPendingIfReady()
                sendJsonToCompanion(json)
            }
        }
    }

    @SuppressLint("MissingPermission")
    internal fun startAdvertisingCallsignCollector() {
        serviceScope.launch {
            repo.getStandaloneCallsign.distinctUntilChanged().collect(::updateAdvertisingCallsign)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneManagementWatchdog() {
        serviceScope.launch {
            while (true) {
                delay(1_000L)
                phoneManagementSettingsMutex.withLock {
                    val now = android.os.SystemClock.elapsedRealtime()
                    synchronized(phoneManagementLease) {
                        val ownsTransport = phoneManagementLease.ownsSession(
                            connectedDevice?.address, bloodhoundConnectionGeneration.get()
                        )
                        if (expirePhoneManagement(now)) {
                            if (ownsTransport) forceBleReset("phone_management_timeout")
                        } else {
                            val address = connectedDevice?.address
                            val generation = bloodhoundConnectionGeneration.get()
                            if (address != null && isCurrentSingleWatch(address, generation)) {
                                phoneManagementLease.probe(address, generation, now)?.let { token ->
                                    val message = org.json.JSONObject()
                                        .put("msgType", "phone_management_ping")
                                        .put("version", 1).put("token", token)
                                    if (!sendBloodhoundControl(message.toString())) {
                                        Log.w(TAG, "Phone management heartbeat queue full")
                                    }
                                }
                            }
                        }
                    }
                    try {
                        restorePhoneManagedSettingsIfUnleased(now)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Failed to restore phone-managed settings", error)
                    }
                }
            }
        }
    }

    /**
     * Restores the watch's own phone-locked values once no live managed session holds
     * the lease. Transient drops inside the heartbeat grace keep the lease and do not
     * restore; a fresh managed handshake snapshots and re-applies the phone's values.
     */
    internal suspend fun restorePhoneManagedSettingsIfUnleased(now: Long): Boolean {
        val unleased = synchronized(phoneManagementLease) {
            !phoneManagementLease.isActive() && now >= phoneManagedRestoreNotBefore
        }
        if (!unleased) return false
        val restored = repo.restorePhoneManagedSnapshot {
            synchronized(phoneManagementLease) { !phoneManagementLease.isActive() }
        } ?: return false
        applyRestoredPhoneManagedSettings(restored)
        Log.i(TAG, "Phone-managed session ended; restored the watch's own locked settings")
        return true
    }

    private fun applyRestoredPhoneManagedSettings(values: DataRepository.PhoneManagedSettingsValues) {
        values.applyToRuntimeConstants()
    }

    internal fun expirePhoneManagement(now: Long): Boolean = synchronized(phoneManagementLease) {
        if (!phoneManagementLease.expire(now)) return@synchronized false
        repo.setPhoneManagementSessionActive(false)
        Log.w(TAG, "Phone management heartbeat timed out; local settings unlocked")
        true
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val pendingJson = ArrayDeque<String>(MAX_PENDING_JSON)
    private val pendingLock = Any()

    @Volatile private var gattServiceAdded: Boolean = false
    @Volatile private var handshakeWatchdogArmed: Boolean = false
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val HANDSHAKE_TIMEOUT_MS = 4_000L
    private val ADVERTISING_START_TIMEOUT_MS = 30_000L
    private val GATT_SERVICE_ADD_RETRY_MS = 1_000L
    private val MAX_GATT_SERVICE_ADD_RETRIES = 3

    @SuppressLint("MissingPermission")
    private fun armHandshakeWatchdog(reason: String) {
        if (handshakeWatchdogArmed) return
        handshakeWatchdogArmed = true
        Log.d(TAG, "Handshake watchdog armed ($reason)")

        watchdogHandler.postDelayed(@RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT]) {
            handshakeWatchdogArmed = false

            // If we never reached SERVICES_READY, force a reset
            val stuck = (connectedDevice != null && !notificationsEnabled)
            if (stuck) {
                Log.w(TAG, "Handshake stuck in CONNECTED (no CCCD). Forcing disconnect/reset.")
                forceBleReset("handshake_timeout")
            }
        }, HANDSHAKE_TIMEOUT_MS)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    private fun forceBleReset(why: String) {
        Log.w(TAG, "forceBleReset: $why")
        repo.setCompanionAppDisconnected()
        advanceCompanionGeneration()
        rxBuf.clear()

        // Drop state
        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.DISCONNECTING)
        activeAddr = null
        connectedDevice = null
        notificationsEnabled = false
        negotiatedMtu = DEFAULT_ATT_MTU
        helloSentForConnection = false
        clearPendingQueue()
        disarmHandshakeWatchdog()

        // Tear down + rebuild server
        closeGattServer()
        initBle()

        // Resume advertising (if available)
        startAdvertising()

        capRepo.updateBleConnectionState(NOT_CONNECTED)
    }

    private fun disarmHandshakeWatchdog() {
        handshakeWatchdogArmed = false
        watchdogHandler.removeCallbacksAndMessages(null)
    }

    private fun enqueueJson(json: String) {
        if (isSensitiveChatJson(json)) return
        synchronized(pendingLock) {
            pendingJson.removeAll(::isSensitiveChatJson)
            while (pendingJson.size >= MAX_PENDING_JSON) {
                pendingJson.removeFirst()
            }
            pendingJson.addLast(json)
        }
    }

    private fun drainPending(): List<String> {
        synchronized(pendingLock) {
            val out = pendingJson.filterNot(::isSensitiveChatJson)
            pendingJson.clear()
            return out
        }
    }

    private fun clearPendingQueue() {
        synchronized(pendingLock) { pendingJson.clear() }
    }

    @org.greenrobot.eventbus.Subscribe
    fun onPurgeSensitiveEnrollments(@Suppress("UNUSED_PARAMETER") event:
        com.aitech.weartak_civ.command.PurgeSensitiveEnrollmentCopies) {
        synchronized(pendingLock) { pendingJson.removeAll(::isSensitiveChatJson) }
    }

    private fun isSensitiveChatJson(json: String): Boolean {
        val envelope = try { org.json.JSONObject(json) } catch (_: org.json.JSONException) {
            Log.w(TAG, "Invalid queued companion envelope discarded")
            return true
        }
        if (envelope.optString("msgType") != "chat") return false
        return com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(
            envelope.optJSONObject("payload")?.optString("msg").orEmpty())
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_KEY_MISSING)
        }
        //registerReceiver(bondStateReceiver, filter)
        ContextCompat.registerReceiver(
            this,
            bondStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterReceiver(bondStateReceiver) failed", t)
        } finally {
            bondReceiverRegistered = false
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice ?: return
            val address = device.address ?: return

            if (action == ACTION_KEY_MISSING) {
                if (!isActive(device)) return
                // A failed encrypted handshake is not an explicit Android unpair.
                Log.w(TAG, "Bond key missing for $address; preserving pairing and management")
                forceBleReset("key_missing:$address")
                return
            }

            if (action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

            Log.d(TAG, "Bond state changed for $address: $prevBondState -> $bondState active=$activeAddr")

            if (bondState != BluetoothDevice.BOND_NONE || prevBondState == BluetoothDevice.BOND_NONE) return
            clearPhoneManagedSettings(address)
            if (!isActive(device)) return
            serviceScope.launch {
                try {
                    repo.clearCompanionAppReadinessHistory()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e(TAG, "Could not clear Companion readiness history after unpair", error)
                }
            }

            Log.w(TAG, "Active companion bond lost for $address; forcing BLE reset")
            forceBleReset("bond_lost:$address")
        }
    }

    private fun bleSendReady(): Boolean {
        return (connectedDevice != null &&
                gattServer != null &&
                txCharacteristic != null &&
                notificationsEnabled &&
                capRepo.companionAppReady.value)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun flushPendingIfReady() {
        if (!bleSendReady()) return
        val batch = drainPending()
        for (j in batch) {
            sendJsonToCompanion(j) // will still enforce MTU payload cap
        }
    }

    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    @Volatile private var bondReceiverRegistered: Boolean = false
    @Volatile private var negotiatedMtu: Int = DEFAULT_ATT_MTU
    @Volatile private var notificationsEnabled: Boolean = false
    @Volatile private var helloSentForConnection: Boolean = false
    @Volatile private var takSocketsClosedForBle = false

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private fun maxNotifyPayloadBytes(): Int {
        // ATT payload max for notifications = MTU - 3
        val mtu = negotiatedMtu.coerceAtLeast(DEFAULT_ATT_MTU)
        val attPayload = (mtu - 3).coerceAtLeast(0)
        return minOf(TARGET_JSON_BYTES, attPayload)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    override fun onCreate() {
        super.onCreate()
        EventBus.getDefault().register(this)
        Log.w(TAG, "Repo identity = ${System.identityHashCode(repo)}")
        Log.d(TAG, "CompanionAppService onCreate()")
        isRunning.set(true)
        repo.setCompanionAppDisconnected()

        // 1) HARD RESET — this instance must never inherit BLE state
        connectedDevice = null
        negotiatedMtu = DEFAULT_ATT_MTU
        notificationsEnabled = false
        helloSentForConnection = false
        clearPendingQueue()

        capRepo.updateBleConnectionState(NOT_CONNECTED)

        // 2) Foreground FIRST (keeps process alive during BLE init)
        startForegroundServiceCompat(
            NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        // 3) Collect state *before* BLE init so transitions are not missed
        collectBleState()
        registerBondReceiver()
        registerBluetoothStateReceiver()

        phoneManagedRestoreNotBefore =
            android.os.SystemClock.elapsedRealtime() + PhoneManagementLease.TIMEOUT_MS

        // Arm the startup grace before the BLE server can delay other service startup.
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        beginGraceForPreviouslyReadyCompanion()

        // 4) Initialize BLE server + GATT service
        initBle()
        serviceScope.launch {
            val managedAddress = repo.phoneManagedCompanionAddress.first()
            val adapter = bluetoothAdapter
            if (managedAddress != null && adapter?.isEnabled == true) {
                val peerStillBonded = adapter.bondedDevices
                    .any { it.address.equals(managedAddress, ignoreCase = true) }
                if (!peerStillBonded) clearPhoneManagedSettings(managedAddress)
            }
        }

        // 5) Start outbound BLE collector & changed settings state
        startBleOutboundCollector()
        startSettingsChangedCollector()
        startAdvertisingCallsignCollector()
        startPhoneManagementWatchdog()
        startPointDeletionRelay()
        startLocationDiagnostics()
        repo.clearCompanionUsers()
        serviceScope.launch {
            while (true) {
                delay(1_000)
                synchronized(companionUserLock) { repo.pruneCompanionUsers() }
            }
        }
        startBloodhoundSync()
        startSelfStatus()

        // 6) Finish setup async
        serviceScope.launch {
            // IMPORTANT:
            // Only advertise if we are NOT already connected.
            // If we ARE already connected, the handshake watchdog
            // will force a clean reconnect if CCCD never arrives.
            if (connectedDevice == null) {
                startAdvertising()
            } else {
                Log.w(TAG, "Service started while already connected; waiting for handshake")
                armHandshakeWatchdog("onCreate_already_connected")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleOutboundCollector() {
        serviceScope.launch {
            for (frame in gatewayIncoming) {
                if (frame.token != capRepo.gateway.session ||
                    frame.generation != bloodhoundConnectionGeneration.get()) continue
                try {
                    receiveGatewayMap(frame.xml, frame.token, frame.generation)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    gatewayError(frame.token, "Gateway import failed: ${error.message}")
                }
            }
        }
        serviceScope.launch {
            for ((token, xml) in capRepo.gatewayCotEvents) {
                if (com.aitech.weartak_civ.command.SensitiveEnrollment.containsSensitiveXml(xml)) continue
                if (token != capRepo.gateway.session) continue
                val deadline = System.currentTimeMillis() + 10_000L
                while (token == capRepo.gateway.session && !capRepo.gateway.ready &&
                    bloodhoundLinkReady() && System.currentTimeMillis() < deadline) {
                    delay(100L)
                }
                if (token != capRepo.gateway.session || !capRepo.gateway.ready || !bloodhoundLinkReady()) {
                    Log.w(TAG, "Gateway CoT not sent because session negotiation did not complete")
                    continue
                }
                try {
                    if (!sendBloodhoundControl(capRepo.gateway.envelope(xml))) {
                        Log.w(TAG, "Gateway notification queue full; CoT dropped")
                    }
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Unsupported gateway outbound CoT", error)
                }
            }
        }
        serviceScope.launch {
            capRepo.outboundBleCotEvents.collect { event ->
                if (event is CompanionAppRepository.OutboundCotEvent.Chat &&
                    com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(event.snapshot.message)) return@collect
                if (!capRepo.companionAppReady.value) {
                    Log.i(TAG, "Companion not ready; queueing BLE event")
                    // build json as normal below and enqueueJson(json)
                    // but we need json first, so don't return here
                }

                val json = when (event) {
                    is CompanionAppRepository.OutboundCotEvent.Pli -> {
                        Log.i(TAG, "PLI - BLE Outbound Collector")
                        val env = capRepo.buildPliEnvelopeFromSnapshot(
                            snapshot = event.snapshot, repIntSeconds = repo.almightyReportingIntervalSecs.value,
                            includeLegacyHeartRate = !selfStatus.active(bloodhoundConnectionGeneration.get())
                        )
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.OutboundCotEvent.Marker -> {
                        Log.i(TAG, "MARKER - BLE Outbound Collector")
                        val env = capRepo.buildMarkerEnvelopeFromSnapshot(event.snapshot)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.OutboundCotEvent.Emergency -> {
                        Log.i(TAG, "EMERGENCY - BLE Outbound Collector")
                        val env = capRepo.buildEmergencyEnvelopeFromSnapshot(event.snapshot)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.OutboundCotEvent.Chat -> {
                        Log.i(TAG, "CHAT - BLE Outbound Collector uid=${event.snapshot.uid}")
                        val env = capRepo.buildChatEnvelopeFromSnapshot(snapshot = event.snapshot)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.OutboundCotEvent.ReportInt -> {
                        Log.i(TAG, "REPORTING INT - BLE Outbound Collector")
                        val env = capRepo.buildReportIntPayload(event.interval)
                        capRepo.envelopeToJson(env)
                    }

                }

                if (!capRepo.companionAppReady.value || !notificationsEnabled) {
                    Log.i(TAG, "Companion not ready; queueing BLE event")
                    enqueueJson(json)
                    return@collect
                }
                flushPendingIfReady()
                sendJsonToCompanion(json)
            }
        }
    }

    private fun collectOutboundSettings() {
        serviceScope.launch {
            capRepo.outboundBleCotEvents
        }
    }

    internal suspend fun receiveGatewayMap(xml: String, token: String, generation: Long) {
        if (!capRepo.gateway.ready || token != capRepo.gateway.session ||
            generation != bloodhoundConnectionGeneration.get()) return
        require(com.aitech.weartak_civ.cot.CompanionGatewaySession.supported(xml)) { "Unsupported gateway CoT" }
        val identity = repo.snapshotSystem()
        if (token != capRepo.gateway.session || generation != bloodhoundConnectionGeneration.get()) return
        val position = CotMessageParser.parseUserPositionOrNull(xml)
        if (position != null) {
            if (position.uid != identity.deviceId &&
                capRepo.gateway.acceptEvent(position.uid, position.time))
                repo.applyReceivedUserEvent(position, "Gateway ATAK", "companion-gateway")
            return
        }
        if (CotMessageParser.isChatMessage(xml)) {
            val rooms = CotMessageParser.parseChatRooms(
                xml, identity.deviceId, identity.callsign, "companion-gateway"
            )
            if (rooms.isNotEmpty()) {
                val uid = CotMessageParser.eventUidOrNull(xml)
                    ?: throw IllegalArgumentException("Gateway chat UID missing")
                val time = CotMessageParser.eventTimeOrNull(xml)
                    ?: throw IllegalArgumentException("Gateway chat time invalid")
                if (capRepo.gateway.acceptEvent(uid, time)) {
                    if (com.aitech.weartak_civ.command.ChatCommandIngress.handle(
                            rooms, time, System.currentTimeMillis(), "companion-gateway",
                            ChatCommandRuntime.create(applicationContext, repo))) return
                    org.greenrobot.eventbus.EventBus.getDefault().post(
                        com.aitech.weartak_civ.events.TakChatEvent(
                            rawXml = xml,
                            sourceServerId = "companion-gateway",
                            receivedAt = System.currentTimeMillis(),
                            parsedRooms = rooms
                        )
                    )
                }
            }
            return
        }
        val emergency = CotMessageParser.parseEmergencyOrNull(xml)
        if (emergency != null) {
            if (!emergency.isFrom(identity.deviceId) &&
                capRepo.gateway.acceptEvent(emergency.uid, emergency.time)) {
                bhRepo.applyEmergencyEvent(
                    event = emergency,
                    server = "Gateway ATAK",
                    sourceServerId = "companion-gateway",
                    receivedAt = System.currentTimeMillis(),
                    deviceUid = identity.deviceId
                )
            }
            return
        }
        val deletion = CotMessageParser.parseDeletionOrNull(xml)
        if (deletion != null) {
            if (deletion.uid != identity.deviceId && capRepo.gateway.acceptEvent(deletion.uid, deletion.time, deleted = true)) {
                repo.receiveCotDeletion(deletion, "companion-gateway", bhRepo)
            }
            return
        }
        val marker = CotMessageParser.parseMarkerOrNull(xml)
            ?: throw IllegalArgumentException("Unsupported gateway map CoT")
        if (capRepo.gateway.acceptEvent(marker.uid, marker.time))
            repo.receiveMarkerEvent(marker, bhRepo, "Gateway ATAK", "companion-gateway")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CompanionAppService onStartCommand()")
        // TODO: handle explicit ACTION_* if you use start/stop intents
        return START_NOT_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        Log.d(TAG, "CompanionAppService onDestroy()")
        // Fence late GATT callbacks and queued starts before tearing down the transport.
        synchronized(advertisingLock) {
            serviceJob.cancel()
            stopAdvertising()
            advertisingRefreshHandler.removeCallbacks(gattServiceRebuild)
        }
        synchronized(phoneManagementLease) {
            advanceCompanionGeneration()
            phoneManagementLease.stop()
            repo.setPhoneManagementSessionActive(false)
        }

        // 1) Update app-level state (avoid stale SERVICES_READY in repo)
        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.DISCONNECTING)
        notificationsEnabled = false
        negotiatedMtu = DEFAULT_ATT_MTU
        clearPendingQueue()

        // 2) Stop outbound work
        bloodhoundFrames.close()
        locationDiagnosticFrames.close()
        stopLocationDiagnosticsSession("service stopped")
        gatewayIncoming.close()

        // 3) Stop bond monitoring + close the server + force disconnect
        unregisterBondReceiver()
        unregisterBluetoothStateReceiver()
        closeGattServer() // should cancel connection + close server

        // 4) Finalize state
        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.NOT_CONNECTED)
        repo.setCompanionAppDisconnected()
        isRunning.set(false)

        // 5) Foreground teardown
        stopForegroundServiceCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun initBle() {
        if (bluetoothManager == null) {
            bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        }
        if (bluetoothAdapter == null) bluetoothAdapter = bluetoothManager?.adapter
        gattServiceAdded = false

        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not available or disabled")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)

        if (gattServer == null) {
            Log.w(TAG, "Failed to open GATT server")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
            return
        }

        // Build GATT service and characteristics using constants from capRepo
        val service = BluetoothGattService(
            capRepo.COMPANION_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        txCharacteristic = BluetoothGattCharacteristic(
            capRepo.TX_TO_COMPANION_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
        ).apply {
            val cccd = BluetoothGattDescriptor(
                capRepo.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM
            )
            addDescriptor(cccd)
        }

        rxCharacteristic = BluetoothGattCharacteristic(
            capRepo.RX_FROM_COMPANION_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
        )

        service.addCharacteristic(txCharacteristic)
        service.addCharacteristic(rxCharacteristic)

        val added = gattServer?.addService(service) ?: false
        if (!added) {
            Log.w(TAG, "Failed to add companion GATT service")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
        } else {
            gattServiceAdded = true
            Log.i(TAG, "Companion GATT service ${capRepo.COMPANION_SERVICE_UUID} added")
        }
    }

    // A Bluetooth stack restart drops every GATT server registration. Advertising A11A without
    // the server makes the phone connect and then fail with "Companion service A11A missing".
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun ensureCompanionGattService(): Boolean {
        if (gattServer != null && gattServiceAdded) return true
        Log.w(TAG, "Companion GATT service missing; reopening GATT server")
        closeGattServer()
        initBle()
        return gattServer != null && gattServiceAdded
    }

    private fun registerBluetoothStateReceiver() {
        if (bluetoothStateReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bluetoothStateReceiverRegistered = true
    }

    private fun unregisterBluetoothStateReceiver() {
        if (!bluetoothStateReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterReceiver(bluetoothStateReceiver) failed", t)
        } finally {
            bluetoothStateReceiverRegistered = false
        }
    }

    @Volatile private var bluetoothStateReceiverRegistered: Boolean = false
    @Volatile private var gattServiceAddFailures: Int = 0

    @SuppressLint("MissingPermission")
    private val gattServiceRebuild = Runnable {
        if (serviceJob.isActive && connectedDevice == null) ensureCompanionGattService()
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            onBluetoothAdapterStateChanged(
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            )
        }
    }

    @SuppressLint("MissingPermission")
    internal fun onBluetoothAdapterStateChanged(state: Int) {
        if (!serviceJob.isActive) return
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                Log.w(TAG, "Bluetooth adapter state=$state; dropping Companion GATT server")
                dropBleTransport()
                capRepo.updateBleConnectionState(NOT_CONNECTED)
            }
            BluetoothAdapter.STATE_ON -> {
                Log.w(TAG, "Bluetooth adapter on; re-exposing Companion GATT service")
                dropBleTransport()
                gattServiceAddFailures = 0
                synchronized(advertisingLock) {
                    advertiser = null
                    advertisingFailures = 0
                    advertisingRetryNotBefore = 0L
                    advertisingFailedPermanently = false
                }
                ensureCompanionGattService()
                startAdvertising()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun dropBleTransport() {
        synchronized(advertisingLock) {
            try {
                stopAdvertising()
            } catch (t: Throwable) {
                Log.w(TAG, "stopAdvertising failed while Bluetooth restarted", t)
            } finally {
                advertising = false
                advertisingStartPending = false
            }
        }
        if (activeAddr != null || connectedDevice != null) {
            repo.setCompanionAppDisconnected()
            advanceCompanionGeneration()
        }
        rxBuf.clear()
        clearPendingQueue()
        disarmHandshakeWatchdog()
        closeGattServer()
    }


    // --- Advertising ---------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising(): Unit = synchronized(advertisingLock) {
        Log.d(TAG, "startAdvertising(): advertising=$advertising pending=$advertisingStartPending connectedDevice=${connectedDevice?.address}")
        if (!serviceJob.isActive || connectedDevice != null) return
        if (advertisingFailedPermanently) return
        if (bluetoothAdapter?.isEnabled == false) {
            // ACTION_STATE_CHANGED(STATE_ON) rebuilds the GATT server and restarts advertising.
            Log.d(TAG, "startAdvertising(): Bluetooth off; waiting for adapter")
            return
        }
        val retryDelay = advertisingRetryNotBefore - android.os.SystemClock.elapsedRealtime()
        if (retryDelay > 0) {
            advertisingRefreshHandler.removeCallbacks(advertisingRetry)
            advertisingRefreshHandler.postDelayed(advertisingRetry, retryDelay)
            return
        }

        if (advertiser == null) {
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        }

        if (advertising || advertisingStartPending) {
            Log.d(TAG, "startAdvertising(): already advertising; skipping")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ADVERTISING)
            return
        }

        val adv = advertiser ?: run {
            Log.w(TAG, "No BLE advertiser available")
            handleAdvertisingFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            return
        }

        advertisingStartPending = true
        advertisingRefreshHandler.postDelayed(advertisingStartTimeout, ADVERTISING_START_TIMEOUT_MS)
        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ADVERTISING)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val uuid = capRepo.COMPANION_SERVICE_UUID
        advertiseCallback = createAdvertiseCallback()
        try {
            adv.startAdvertising(
                settings,
                CompanionAdvertisingData.primary(uuid),
                CompanionAdvertisingData.scanResponse(uuid, advertisingCallsign),
                advertiseCallback
            )
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid companion advertising data", error)
            handleAdvertisingFailure(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Companion advertiser unavailable", error)
            handleAdvertisingFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        } catch (error: SecurityException) {
            Log.e(TAG, "Companion advertising permission denied", error)
            advertisingFailedPermanently = true
            advertising = false
            advertisingStartPending = false
            advertisingRefreshHandler.removeCallbacks(advertisingStartTimeout)
            advertisingRefreshHandler.removeCallbacks(advertisingRetry)
            capRepo.updateBleConnectionState(ERROR)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising(): Unit = synchronized(advertisingLock) {
        advertisingRefreshHandler.removeCallbacks(advertisingStartTimeout)
        advertisingRefreshHandler.removeCallbacks(advertisingRetry)
        if (!advertising && !advertisingStartPending) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } finally {
            advertising = false
            advertisingStartPending = false
        }
    }

    private val advertisingLock = Any()
    @Volatile private var advertising: Boolean = false
    @Volatile private var advertisingStartPending: Boolean = false
    private val advertisingRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val advertisingStartTimeout = Runnable { handleAdvertisingStartTimeout() }
    @SuppressLint("MissingPermission")
    private val advertisingRetry = Runnable { startAdvertising() }
    private var advertisingRetryNotBefore = 0L
    private var advertisingFailures = 0
    private var advertisingFailedPermanently = false
    private var advertisingCallsign = CompanionAdvertisingData.encodeCallsign("")

    @SuppressLint("MissingPermission")
    internal fun updateAdvertisingCallsign(callsign: String): Unit = synchronized(advertisingLock) {
        val encoded = CompanionAdvertisingData.encodeCallsign(callsign)
        if (advertisingCallsign.contentEquals(encoded)) return
        advertisingCallsign = encoded
        if (serviceJob.isActive && connectedDevice == null && (advertising || advertisingStartPending)) {
            stopAdvertising()
            startAdvertising()
        }
    }

    private fun handleAdvertisingFailure(errorCode: Int) {
        val errorName = when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
            else -> "UNKNOWN_ADVERTISE_ERROR"
        }
        Log.w(TAG, "Companion advertising failed: $errorName ($errorCode)")
        advertisingStartPending = false
        advertisingRefreshHandler.removeCallbacks(advertisingStartTimeout)
        advertisingRefreshHandler.removeCallbacks(advertisingRetry)
        if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
            advertising = true
            advertisingFailures = 0
            advertisingRetryNotBefore = 0L
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ADVERTISING)
            return
        }

        advertising = false
        val transient = errorCode == AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ||
            errorCode == AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR
        advertisingFailures++
        if (!advertisingFailedPermanently && transient && advertisingFailures <= 5) {
            val delayMs = 1_000L shl (advertisingFailures - 1)
            advertisingRetryNotBefore = android.os.SystemClock.elapsedRealtime() + delayMs
            advertisingRefreshHandler.postDelayed(advertisingRetry, delayMs)
            Log.w(TAG, "Retrying companion advertising in ${delayMs}ms (retry $advertisingFailures/5)")
        } else {
            advertisingFailedPermanently = true
            Log.e(TAG, "Companion advertising stopped; restart Companion after resolving $errorName")
        }
        // Set the retry/terminal gate before publishing ERROR: the state collector requests a start.
        capRepo.updateBleConnectionState(ERROR)
    }

    // Only an unanswered start expires. Recreating healthy advertising rotates the private
    // address on some watches, invalidating scan results while the phone tries to bond.
    @SuppressLint("MissingPermission")
    private fun handleAdvertisingStartTimeout(): Unit = synchronized(advertisingLock) {
        if (!serviceJob.isActive || connectedDevice != null || !advertisingStartPending) return
        Log.w(TAG, "Companion advertising start callback timed out")
        try {
            stopAdvertising()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Advertiser unavailable while cancelling timed-out start", error)
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied while cancelling timed-out advertising start", error)
            advertisingFailedPermanently = true
        }
        handleAdvertisingFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        // A lost start callback is the signature of a Bluetooth stack restart, which also drops
        // the GATT server registration while our handle stays non-null. Rebuild it before retry.
        if (gattServer != null) {
            Log.w(TAG, "Recycling Companion GATT server after lost advertising callback")
            closeGattServer()
            initBle()
        }
    }

    private var advertiseCallback: AdvertiseCallback = createAdvertiseCallback()

    private fun createAdvertiseCallback() = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?): Unit = synchronized(advertisingLock) {
            if (advertiseCallback !== this || !advertisingStartPending) return
            Log.d(TAG, "Companion BLE advertising started")
            advertisingStartPending = false
            advertisingRefreshHandler.removeCallbacks(advertisingStartTimeout)
            advertising = true
            advertisingFailures = 0
            advertisingRetryNotBefore = 0L
            advertisingRefreshHandler.removeCallbacks(advertisingRetry)
        }

        override fun onStartFailure(errorCode: Int): Unit = synchronized(advertisingLock) {
            if (advertiseCallback !== this || !advertisingStartPending) return
            handleAdvertisingFailure(errorCode)
        }
    }

    private fun resetNegotiatedMtu() {
        negotiatedMtu = DEFAULT_ATT_MTU
        notificationsEnabled = false
        helloSentForConnection = false
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (service?.uuid != capRepo.COMPANION_SERVICE_UUID) return
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                gattServiceAddFailures = 0
                Log.i(TAG, "Companion GATT service registered")
                return
            }
            gattServiceAdded = false
            gattServiceAddFailures++
            Log.w(TAG, "Companion GATT service registration failed status=$status attempt=$gattServiceAddFailures")
            if (gattServiceAddFailures > MAX_GATT_SERVICE_ADD_RETRIES) {
                capRepo.updateBleConnectionState(ERROR)
                return
            }
            advertisingRefreshHandler.removeCallbacks(gattServiceRebuild)
            advertisingRefreshHandler.postDelayed(gattServiceRebuild, GATT_SERVICE_ADD_RETRY_MS)
        }

        @SuppressLint("MissingPermission")
        private fun bondStatus(device: BluetoothDevice): Int = try {
            device.bondState
        } catch (_: Throwable) {
            BluetoothDevice.BOND_NONE
        }

        private fun isBonded(device: BluetoothDevice): Boolean =
            bondStatus(device) == BluetoothDevice.BOND_BONDED

        @SuppressLint("MissingPermission")
        private fun claimCompanion(device: BluetoothDevice): Boolean {
            if (!serviceJob.isActive) return false
            if (isActive(device)) return true
            if (activeAddr != null || !linkMtus.containsKey(device.address)) return false

            activeAddr = device.address
            connectedDevice = device
            repo.setCompanionAppDisconnected()
            advanceCompanionGeneration()
            rxBuf.clear()
            resetNegotiatedMtu()
            negotiatedMtu = linkMtus[device.address] ?: DEFAULT_ATT_MTU
            stopAdvertising()
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.CONNECTED)
            armHandshakeWatchdog("Companion service used by ${device.address}")
            return true
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (!serviceJob.isActive) return

            Log.d(TAG, "onConnectionStateChange: dev=${device.address} status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                linkMtus.remove(device.address)
                if (!isActive(device)) {
                    Log.d(TAG, "Ignoring connection error from non-Companion device=${device.address}")
                    return
                }
                Log.w(TAG, "Connection error status=$status, resetting BLE state")
                repo.setCompanionAppDisconnected()
                advanceCompanionGeneration()
                rxBuf.clear()
                activeAddr = null
                connectedDevice = null
                resetNegotiatedMtu()
                clearPendingQueue()
                disarmHandshakeWatchdog()
                capRepo.updateBleConnectionState(
                    CompanionAppRepository.BleConnectionState.CONNECTION_FAILED
                )
                restartAdvertisingSafely()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Android reports shared LE links, including links owned by Ditto.
                    linkMtus.putIfAbsent(device.address, DEFAULT_ATT_MTU)
                    Log.d(TAG, "LE link available; awaiting Companion service use by ${device.address}")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    linkMtus.remove(device.address)
                    if (!isActive(device)) {
                        Log.w(TAG, "Ignoring DISCONNECTED from stale device=${device.address} active=$activeAddr")
                        return
                    }


                    Log.d(TAG, "Active device disconnected: ${device.address}")
                    repo.setCompanionAppDisconnected()
                    advanceCompanionGeneration()
                    rxBuf.clear()
                    activeAddr = null
                    connectedDevice = null

                    // reset session flags
                    negotiatedMtu = DEFAULT_ATT_MTU
                    notificationsEnabled = false
                    helloSentForConnection = false

                    clearPendingQueue()

                    capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.DISCONNECTED)
                    startAdvertising()
                    disarmHandshakeWatchdog()
                }
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
        private fun restartAdvertisingSafely() {
            try {
                stopAdvertising()
            } catch (t: Throwable) {
                Log.w(TAG, "stopAdvertising failed in restartAdvertisingSafely", t)
            }
            startAdvertising()
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            val status = when {
                characteristic.uuid != capRepo.RX_FROM_COMPANION_UUID -> BluetoothGatt.GATT_FAILURE
                !isBonded(device) -> BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                !claimCompanion(device) -> BluetoothGatt.GATT_FAILURE
                else -> BluetoothGatt.GATT_SUCCESS
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, status, offset, null)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Companion RX rejected: device=${device.address} status=$status active=$activeAddr")
                return
            }

            Log.d(TAG, "RX from companion chunk bytes=${value.size}")
            try {
                for (line in rxBuf.accept(value)) handleCompanionLine(line)
            } catch (error: IllegalStateException) {
                gatewayError(capRepo.gateway.session, error.message ?: "Invalid BLE frame")
                forceBleReset("oversized BLE JSON frame")
            }

        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val server = gattServer

            fun respond(status: Int) {
                if (responseNeeded && server != null) {
                    server.sendResponse(device, requestId, status, offset, null)
                }
            }

            // Only handle CCCD
            if (descriptor.uuid != capRepo.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                respond(BluetoothGatt.GATT_FAILURE)
                return
            }

            if (!isBonded(device)) {
                Log.i(TAG, "CCCD write ignored: device ${device.address} is not bonded")
                respond(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION)
                return
            }

            // Only CCCD on TX characteristic
            if (descriptor.characteristic.uuid != capRepo.TX_TO_COMPANION_UUID) {
                Log.i(TAG, "CCCD write ignored: not TX characteristic (${descriptor.characteristic.uuid})")
                respond(BluetoothGatt.GATT_FAILURE)
                return
            }

            val enableNoti = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disableNoti = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            if ((!enableNoti && !disableNoti) || preparedWrite || offset != 0 ||
                (enableNoti && !claimCompanion(device)) || (disableNoti && !isActive(device))) {
                respond(BluetoothGatt.GATT_FAILURE)
                return
            }
            descriptor.value = value

            Log.d(TAG, "CCCD write from ${device.address}, enable=$enableNoti disable=$disableNoti")

            if (enableNoti) {
                beginCompanionSubscription()
                notificationsEnabled = true
                disarmHandshakeWatchdog()

                serviceScope.launch {
                    try {
                        CotDispatchService.prepareForCompanionHandoffIfRunning()
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to expire direct self PLI before Companion handoff", error)
                    }
                    try {
                        DittoService.prepareForCompanionHandoffIfRunning()
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to expire direct Ditto self track before Companion handoff", error)
                    }
                    repo.setCompanionAppConnected()
                    capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.SERVICES_READY)
                    if (!helloSentForConnection) {
                        helloSentForConnection = true
                        val env = capRepo.buildWatchInfoEnvelope()
                        val json = capRepo.envelopeToJson(env)
                        sendJsonToCompanion(json)
                    }
                    flushPendingIfReady()
                }

                respond(BluetoothGatt.GATT_SUCCESS)
                return
            }

            if (disableNoti) {
                notificationsEnabled = false
                selfStatus.reset()
                repo.setCompanionAppDisconnected()
                capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.CONNECTED)
                respond(BluetoothGatt.GATT_SUCCESS)
                return
            }

            respond(BluetoothGatt.GATT_FAILURE)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            linkMtus.computeIfPresent(device.address) { _, _ -> mtu }
            if (!isActive(device)) {
                Log.d(TAG, "MTU recorded for unclaimed LE link ${device.address}")
                return
            }
            negotiatedMtu = mtu
            Log.d(TAG, "MTU now $mtu for ${device.address}")
            serviceScope.launch { flushPendingIfReady() }
        }
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGattServer() {
        val server = gattServer
        val dev = connectedDevice

        // Drop local flags immediately
        activeAddr = null
        connectedDevice = null
        linkMtus.clear()
        notificationsEnabled = false
        negotiatedMtu = DEFAULT_ATT_MTU
        helloSentForConnection = false

        if (server != null && dev != null) {
            try {
                Log.d(TAG, "Cancelling GATT connection to ${dev.address}")
                server.cancelConnection(dev)
            } catch (t: Throwable) {
                Log.w(TAG, "cancelConnection failed", t)
            }
        }

        try {
            Log.d(TAG, "Closing GATT server")
            server?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "gattServer.close() failed", t)
        } finally {
            gattServer = null
            gattServiceAdded = false
        }
    }

    // --- Public send API: JSON -> Notify ------------------------------------

    /**
     * Public entrypoint to send a JSON envelope over the TX characteristic.
     * You can call this from CotDispatchService (via some reference) once you have
     * a MarkerSnapshot -> Envelope -> JSON.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendJsonToCompanion(json: String) {
        sendJsonForPeer(json, connectedDevice?.address, bloodhoundConnectionGeneration.get())
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendJsonForPeer(json: String, address: String?, generation: Long) {
        if (isSensitiveChatJson(json)) return
        if (address == null || !currentPeer(address, generation)) {
            Log.w(TAG, "Dropping companion JSON for ended/unready connection")
            return
        }
        if (capRepo.gateway.session != null) {
            Log.i(TAG, "Suppressed legacy companion output during Gateway session")
            return
        }
        if (!capRepo.companionAppReady.value) {
            Log.d(TAG, "Companion not ready (state=${capRepo.bleConnectionState.value}); dropping JSON")
            return
        }
        if (!notificationsEnabled) {
            Log.d(TAG, "Notifications not enabled; dropping/queueing JSON")
            return
        }

        val device = connectedDevice ?: run {
            Log.d(TAG, "Not connected companion; dropping JSON bytes=${json.toByteArray().size}")
            return
        }
        val tx = txCharacteristic ?: run {
            Log.w(TAG, "TX characteristic not ready")
            return
        }
        val server = gattServer ?: run {
            Log.w(TAG, "Gatt server not ready")
            return
        }

        var outgoing = json
        val envelope = org.json.JSONObject(json)
        if (envelope.optString("msgType") == "marker") {
            if (!bloodhoundLinkReady()) return
            val payload = envelope.getJSONObject("payload")
            val time = java.time.Instant.parse(payload.getString("tStart")).toEpochMilli()
            val stale = java.time.Instant.parse(payload.getString("tStale")).toEpochMilli()
            // Legacy clear CoT remains on other transports, never import it as a fresh BLE marker.
            if (stale <= System.currentTimeMillis()) return
            val uid = payload.getString("uid")
            if (repo.isDeletedLocalPoint(uid)) {
                Log.d(TAG, "Ignoring update for deleted local point")
                return
            }
            val token = java.util.UUID.randomUUID().toString()
            payload.put("revision", token)
            outgoing = envelope.toString()
            if (sendBloodhoundControl(outgoing)) {
                repo.companionPointRevisions.remember(uid,
                    com.aitech.weartak_civ.data.CompanionPointRevisions.Revision(
                        token, time, true, bloodhoundConnectionGeneration.get()))
            } else Log.w(TAG, "Point update write rejected")
            return
        }
        val framed = if (outgoing.endsWith("\n")) outgoing else (outgoing + "\n")
        val bytes = framed.toByteArray(Charsets.UTF_8)
        val maxPayload = maxNotifyPayloadBytes()
        notifyScope.launch {
            sendChunkedNotify(bytes, maxPayload, address, generation)
        }

        /*
        tx.value = bytes

        try {
            val ok = server.notifyCharacteristicChanged(device, tx, false)
            if (!ok) {
                Log.w(TAG, "notifyCharacteristicChanged returned false, will keep connection but drop this msg")
            } else {
                Log.d(TAG, "Sent JSON to companion bytes=${json.toByteArray().size}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notifyCharacteristicChanged threw", t)
            // [Inference] optional: treat as connection failure and reset:
            // capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
            // restartAdvertisingSafely()
        }

         */
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun sendChunkedNotify(
        bytes: ByteArray,
        maxPayload: Int,
        expectedAddress: String? = null,
        expectedGeneration: Long? = null,
        diagnostics: Boolean = false
    ) {
        if (capRepo.gateway.session != null) {
            val message = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
            if (!gatewayAllowsControl(message, expectedGeneration ?: bloodhoundConnectionGeneration.get())) return
        }
        val device = connectedDevice ?: run {
            Log.d(TAG, "sendChunkedNotify: no connectedDevice")
            return
        }
        val tx = txCharacteristic ?: run {
            Log.w(TAG, "sendChunkedNotify: txCharacteristic null")
            return
        }
        val server = gattServer ?: run {
            Log.w(TAG, "sendChunkedNotify: gattServer null")
            return
        }

        // Diagnostics are best-effort and never wait behind operational output.
        val acquired = if (diagnostics) notifyMutex.tryLock() else {
            notifyMutex.lock()
            true
        }
        if (!acquired) {
            Log.d(TAG, "Dropping diagnostics notification while operational traffic is active")
            return
        }
        try {
            val message = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
            val type = message.optString("msgType")
            val gatewayToken = if (type.startsWith("gateway_")) message.optString("session") else null
            if (capRepo.gateway.session != null &&
                !gatewayAllowsControl(message, expectedGeneration ?: bloodhoundConnectionGeneration.get())) return
            var offset = 0
            while (offset < bytes.size) {
                if (gatewayToken != null && gatewayToken != capRepo.gateway.session) return
                if (expectedAddress != null &&
                    (device.address != expectedAddress || connectedDevice != device || !bloodhoundLinkReady() ||
                        bloodhoundConnectionGeneration.get() != expectedGeneration)
                ) {
                    Log.w(TAG, "Dropping Bloodhound control after companion connection changed")
                    return
                }
                val end = minOf(offset + maxPayload, bytes.size)
                val chunk = bytes.copyOfRange(offset, end)

                tx.value = chunk

                val ok = try {
                    server.notifyCharacteristicChanged(device, tx, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "notifyCharacteristicChanged threw", t)
                    false
                }

                if (!ok) {
                    Log.w(TAG, "notifyCharacteristicChanged returned false at offset=$offset/${
                        bytes.size
                    } (dropping remainder)")
                    return
                }

                offset = end

                // Small pacing delay avoids buffer overruns on some stacks
                if (offset < bytes.size) delay(NOTIFY_CHUNK_DELAY_MS)
            }
        } finally {
            notifyMutex.unlock()
        }

        Log.d(TAG, "sendChunkedNotify: sent ${bytes.size} bytes in chunks (maxPayload=$maxPayload)")
    }
    private val rxBuf = com.aitech.weartak_civ.cot.CompanionJsonFramer()

    suspend fun applySettingsFromCompanion(
        payload: CompanionAppRepository.SetSettingsPayload,
        expectedGeneration: Long = bloodhoundConnectionGeneration.get()
    ) {
        check(capRepo.gateway.session == null) { "Profile writes forbidden in gateway session" }
        check(!payload.phoneManagedSettings || phoneManagementReleaseGeneration.get() != expectedGeneration) {
            "Phone management was released for this companion session"
        }
        TakServerP12Validator.validate(payload.takServerList)
        require(payload.reportIntSecs == null || payload.reportIntSecs > 0) {
            "Reporting interval must be positive"
        }
        payload.unitPrefs?.let { com.aitech.weartak_civ.ui.formatter.UnitPrefs.DEFAULT.withUpdates(it) }
        val sendingPeerAddress = connectedDevice?.address

        if (!payload.phoneManagedSettings) {
            // Configure mode: restore any bond snapshot first so the uploaded settings stick.
            sendingPeerAddress?.let { peerAddress ->
                repo.clearPhoneManagedSettingsForPeer(peerAddress)?.let { restored ->
                    synchronized(phoneManagementLease) { phoneManagementLease.stop() }
                    applyRestoredPhoneManagedSettings(restored)
                }
            }
        }

        val managedPeerAddress = if (payload.phoneManagedSettings) {
            require((payload.reportIntSecs ?: 0) > 0) {
                "Phone-managed settings require a positive reporting interval"
            }
            require(!payload.callsign.isNullOrBlank() && !payload.team.isNullOrBlank() && !payload.role.isNullOrBlank()) {
                "Phone-managed settings require callsign, team, and role"
            }
            sendingPeerAddress
                ?: throw IllegalStateException("Cannot apply phone-managed settings without an active companion")
        } else null

        // 1) Store servers, first saving the watch's own servers when a bond takes over.
        if (managedPeerAddress != null) repo.snapshotTakServersForPhoneManagement()
        Log.d(TAG, "applySettingsFromCompanion: storeTakServerList size=${payload.takServerList.size}")
        val refresh = repo.storeProvisionedTakServerList(payload.takServerList)
        if (TAKConnectService.isRunning.get()) {
            val refreshResult = withTimeoutOrNull(TAK_SERVER_REFRESH_TIMEOUT_MS) {
                refresh.result.await()
            } ?: throw IllegalStateException(
                "Settings saved, but the active TAK service refresh timed out."
            )
            refreshResult.getOrThrow()
        }
        val storedTakServerList = repo.awaitTakServerList()
        // reflect the settings ui.
        takServerList = storedTakServerList
//        takServerListState.postValue(takServerList)

        managedPeerAddress?.let { peerAddress ->
            check(phoneManagementReleaseGeneration.get() != expectedGeneration) {
                "Phone management was released for this companion session"
            }
            check(connectedDevice?.address.equals(peerAddress, ignoreCase = true)) {
                "The companion disconnected before phone-managed settings could be applied"
            }
            repo.enablePhoneManagedSettings(peerAddress) {
                bloodhoundConnectionGeneration.get() == expectedGeneration &&
                    phoneManagementReleaseGeneration.get() != expectedGeneration &&
                    connectedDevice?.address.equals(peerAddress, ignoreCase = true)
            }
        }
        if (managedPeerAddress != null) {
            // 2) Snapshot the watch's own locked values once, then apply the phone's.
            val applied = repo.applyPhoneManagedSettings(
                callsign = requireNotNull(payload.callsign),
                team = requireNotNull(payload.team),
                role = requireNotNull(payload.role),
                constantReportingInterval = requireNotNull(payload.reportIntSecs)
            ) {
                bloodhoundConnectionGeneration.get() == expectedGeneration &&
                    phoneManagementReleaseGeneration.get() != expectedGeneration &&
                    connectedDevice?.address.equals(managedPeerAddress, ignoreCase = true)
            }
            applied.applyToRuntimeConstants()
        } else {
            applyProvisionedSettings(payload)
        }
        // Display-only preference: applied in both configure and phone-managed modes, never restored.
        payload.unitPrefs?.let { repo.storeUnitPrefs(it) }
        synchronized(phoneManagementLease) {
            if (managedPeerAddress != null && currentPeer(managedPeerAddress, expectedGeneration) &&
                phoneManagementReleaseGeneration.get() != expectedGeneration
            ) {
                phoneManagementLease.start(
                    managedPeerAddress, expectedGeneration, android.os.SystemClock.elapsedRealtime()
                )
                repo.setPhoneManagementSessionActive(true)
            }
        }
    }

    private suspend fun applyProvisionedSettings(payload: CompanionAppRepository.SetSettingsPayload) {
        // 2) Store reporting interval
        val int = payload.reportIntSecs
        if (int != null && int != 0) {
            repo.storeConstantReportingInterval(payload.reportIntSecs)
            MainActivity.Constants.constantReportingInterval = payload.reportIntSecs
        }

        payload.callsign?.let { phoneCallsign ->
            repo.storeCallsign(phoneCallsign)
            callsign = phoneCallsign
        }

        payload.team?.let { phoneTeam ->
            repo.storeTeam(phoneTeam)
            MainActivity.Constants.team = phoneTeam
            val names = repo.getTeamNames.first().split(',')
            val colors = repo.getTeamColors.first().split(',')
            val teamIndex = names.indexOf(phoneTeam)
            MainActivity.Constants.teamValue = colors.getOrNull(teamIndex) ?: phoneTeam
        }

        payload.role?.let { phoneRole ->
            repo.storeRole(phoneRole)
            MainActivity.Constants.role = phoneRole
        }
    }

    private fun clearPhoneManagedSettings(address: String) {
        serviceScope.launch {
            val restored = repo.clearPhoneManagedSettingsForPeer(address) ?: return@launch
            synchronized(phoneManagementLease) { phoneManagementLease.stop() }
            applyRestoredPhoneManagedSettings(restored)
            Log.i(TAG, "Phone-managed watch settings released after companion bond removal")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCompanionLine(json: String) {
        Log.d(TAG, "RX from companion bytes=${json.toByteArray().size}")

        try {
            val base = gson.fromJson(json, CompanionAppRepository.EnvelopeBase::class.java)
            if (base.type == "self_status_capability") {
                synchronized(companionUserLock) {
                    require(bloodhoundLinkReady()) { "Self status capability requires bonded ready transport" }
                    require(org.json.JSONObject(json).opt("version") == 1) { "Unsupported self status capability" }
                    selfStatus.negotiate(bloodhoundConnectionGeneration.get())
                }
                return
            }
            if (base.type == "gateway_hello") {
                require(bloodhoundLinkReady()) { "Gateway requires bonded ready transport" }
                val ack = capRepo.gateway.negotiate(org.json.JSONObject(json))
                serviceScope.launch {
                    bloodhoundSyncMutex.withLock { refreshBloodhoundConnection() }
                    val sys = repo.snapshotSystem()
                    if (capRepo.gateway.session != ack.getString("session") || !bloodhoundLinkReady()) return@launch
                    ack.put("uid", sys.deviceId).put("callsign", sys.callsign)
                        .put("team", sys.team).put("role", sys.role)
                    if (!sendBloodhoundControl(ack.toString())) {
                        capRepo.gateway.reset()
                        bloodhoundSyncMutex.withLock { refreshBloodhoundConnection() }
                        Log.w(TAG, "Gateway negotiation acknowledgement queue full")
                    } else if (!capRepo.gateway.confirm(ack.getString("session"))) {
                        Log.w(TAG, "Discarding acknowledgement for ended gateway session")
                    }
                }
                return
            }
            if (base.type == "gateway_cot") {
                val token = capRepo.gateway.session
                val xml = capRepo.gateway.accepts(org.json.JSONObject(json))
                val generation = bloodhoundConnectionGeneration.get()
                if (token == null || !gatewayIncoming.trySend(GatewayIncoming(token, generation, xml)).isSuccess)
                    gatewayError(token, "Gateway import queue full (64)")
                return
            }
            if (capRepo.gateway.session != null) {
                Log.w(TAG, "Gateway rejected non-map/profile command: ${base.type}")
                return
            }
            if (handleLocationDiagnosticsMessage(json, base.type)) return

            when (base.type) {
                "route_navigation_ack" -> {
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    synchronized(companionUserLock) {
                        if (!isCurrentSingleWatch(address, generation)) return
                        routeSync.receiveAck(org.json.JSONObject(json).getJSONObject("payload"))
                    }
                }
                "phone_route_navigation" -> {
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    if (!isCurrentSingleWatch(address, generation)) return
                    val update = com.aitech.weartak_civ.data.CompanionRouteNavigation.parse(
                        org.json.JSONObject(json).getJSONObject("payload"))
                    synchronized(companionUserLock) {
                        if (!isCurrentSingleWatch(address, generation)) return
                        val location = repo.latestLocation.value?.let {
                            com.aitech.weartak_civ.data.model.TakRoutePoint(it.latitude, it.longitude)
                        }
                        if (bhRepo.applyPhoneRoute(update, location)) {
                            phoneRoutesReceived = true
                            routeSync.connectionChanged(true)
                            routeSync.phoneSnapshot(update)
                            update.route?.let(repo::upsertTakRoute)
                            Log.i(TAG, "Phone route ${update.action} uid=${update.routeUid} sequence=${update.sequence}")
                        } else {
                            Log.i(TAG, "Ignoring stale/dismissed phone route session=${update.sessionId}")
                        }
                    }
                }

                "phone_management_pong" -> {
                    val message = org.json.JSONObject(json)
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    require(message.optInt("version") == 1) { "Unsupported management heartbeat version" }
                    val token = message.getString("token")
                    if (!currentPeer(address, generation) ||
                        !phoneManagementLease.acknowledge(
                            address, generation, token, android.os.SystemClock.elapsedRealtime()
                        )
                    ) Log.w(TAG, "Ignoring stale phone management heartbeat")
                }

                "phone_user_cot", "phone_user_remove" -> {
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    if (!isCurrentSingleWatch(address, generation)) return
                    val payload = org.json.JSONObject(json).getJSONObject("payload")
                    val phoneUid = payload.opt("phoneUid") as? String
                    require(!phoneUid.isNullOrBlank() && phoneUid.length <= 512 &&
                        !phoneUid.any(Char::isISOControl)
                    ) { "Invalid phone UID" }
                    if (base.type == "phone_user_remove") {
                        val uid = payload.opt("targetUid") as? String
                        require(!uid.isNullOrBlank() && uid.length <= 512 && !uid.any(Char::isISOControl) &&
                            uid != device_uid && uid != phoneUid
                        ) { "Invalid user removal UID" }
                        synchronized(companionUserLock) {
                            if (isCurrentSingleWatch(address, generation)) repo.removeCompanionUser(uid)
                        }
                    } else {
                        val xml = payload.get("cot") as? String
                            ?: throw IllegalArgumentException("User CoT must be XML text")
                        require(xml.length <= 131_072) { "User CoT too large" }
                        val event = CotMessageParser.parseCompanionUserOrNull(xml, device_uid, phoneUid)
                        if (event == null) {
                            Log.w(TAG, "Ignoring invalid/self companion user PLI")
                            return
                        }
                        synchronized(companionUserLock) {
                            if (isCurrentSingleWatch(address, generation)) {
                                repo.receiveCompanionUserEvent(event, System.currentTimeMillis())
                            }
                        }
                    }
                }

                "release_phone_management" -> {
                    val payload = org.json.JSONObject(json).getJSONObject("payload")
                    val requestId = payload.opt("requestId") as? String
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    if (!currentPeer(address, generation)) return
                    serviceScope.launch {
                        var error: String? = null
                        val validId = requestId != null &&
                            requestId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                        if (validId) phoneManagementReleaseGeneration.set(generation)
                        val ok = if (!validId) {
                            error = "Invalid requestId UUID"
                            false
                        } else try {
                            phoneManagementSettingsMutex.withLock {
                                releasePhoneManagement(address, generation).also {
                                    if (!it) error = "Settings are managed by a different companion"
                                }
                            }
                        } catch (failure: IllegalStateException) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            error = failure.message
                            false
                        } catch (failure: java.io.IOException) {
                            error = failure.message ?: "Unable to persist management release"
                            false
                        }
                        if (!currentPeer(address, generation)) return@launch
                        val ackPayload = org.json.JSONObject()
                            .put("requestId", requestId ?: org.json.JSONObject.NULL).put("ok", ok)
                        error?.let { ackPayload.put("error", it) }
                        val ack = org.json.JSONObject().put("msgType", "phone_management_release_ack")
                            .put("payload", ackPayload)
                        val frame = BloodhoundFrame(
                            "$ack\n".toByteArray(Charsets.UTF_8), address, generation, maxNotifyPayloadBytes()
                        )
                        if (!bloodhoundFrames.trySend(frame).isSuccess) {
                            Log.w(TAG, "Management release acknowledgement queue full")
                        }
                    }
                }

                "bloodhound_control", "bloodhound_control_result" -> {
                    val payload = org.json.JSONObject(json).getJSONObject("payload")
                    val generation = bloodhoundConnectionGeneration.get()
                    serviceScope.launch {
                        bloodhoundSyncMutex.withLock {
                            if (generation != bloodhoundConnectionGeneration.get()) {
                                Log.w(TAG, "Ignoring Bloodhound command from ended BLE connection")
                                return@withLock
                            }
                            refreshBloodhoundConnection()
                            bloodhoundSync.localTargetChanged()
                            if (base.type == "bloodhound_control") bloodhoundSync.receiveControl(payload)
                            else bloodhoundSync.receiveResult(payload)
                        }
                    }
                }

                "request_watch_info" -> {
                    serviceScope.launch {
                        val env = capRepo.buildWatchInfoEnvelope()
                        sendJsonToCompanion(capRepo.envelopeToJson(env))
                    }
                }

                "request_settings" -> {
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    serviceScope.launch {
                        if (!currentPeer(address, generation)) {
                            Log.w(TAG, "Ignoring settings request from ended/unready connection")
                            return@launch
                        }
                        val env = capRepo.buildWatchSettingsEnvelope()
                        if (!currentPeer(address, generation)) {
                            Log.w(TAG, "Discarding settings snapshot for ended connection")
                            return@launch
                        }
                        Log.i(TAG, "Sending settings snapshot generation=$generation")
                        sendJsonForPeer(capRepo.envelopeToJson(env), address, generation)
                    }
                }

                "set_callsign", "set_team", "set_role" -> {
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    serviceScope.launch {
                        val ack = try {
                            val payload = org.json.JSONObject(json).getJSONObject("payload")
                            val field = base.type.removePrefix("set_")
                            require(payload.keys().asSequence().toSet() == setOf(field)) {
                                "${base.type} requires only $field"
                            }
                            val value = payload.get(field)
                            require(value is String && value.isNotBlank()) {
                                "$field must be a nonblank string"
                            }
                            phoneManagementSettingsMutex.withLock {
                                fun mayApply() = currentPeer(address, generation) &&
                                    phoneManagementReleaseGeneration.get() != generation &&
                                    capRepo.gateway.session == null
                                check(mayApply()) { "Identity belongs to an ended or released companion session" }
                                repo.applyPhoneManagedIdentity(
                                    address, field, value,
                                    mayApply = ::mayApply
                                ).applyToRuntimeConstants()
                            }
                            capRepo.buildSettingsAck(ok = true)
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TAG, "${base.type}: failed to parse/apply", error)
                            capRepo.buildSettingsAck(ok = false, error = error.message ?: "parse/apply error")
                        }
                        sendJsonForPeer(capRepo.envelopeToJson(ack), address, generation)
                    }
                }

                "unit_prefs" -> {
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    if (!isCurrentSingleWatch(address, generation)) return
                    serviceScope.launch {
                        val ack = try {
                            val payload = org.json.JSONObject(json).getJSONObject("payload")
                            val updates = payload.keys().asSequence().associateWith { payload.get(it) }
                            phoneManagementSettingsMutex.withLock {
                                check(currentPeer(address, generation)) { "Unit prefs belong to an ended companion connection" }
                                repo.storeUnitPrefs(updates)
                            }
                            capRepo.buildUnitPrefsAck(ok = true)
                        } catch (t: Throwable) {
                            Log.w(TAG, "unit_prefs: failed to apply", t)
                            capRepo.buildUnitPrefsAck(ok = false, error = t.message ?: "parse/apply error")
                        }
                        sendJsonForPeer(capRepo.envelopeToJson(ack), address, generation)
                    }
                }

                "set_settings" -> {
                    val address = connectedDevice?.address ?: return
                    val generation = bloodhoundConnectionGeneration.get()
                    serviceScope.launch {
                        try {
                            val msg = gson.fromJson(json, CompanionAppRepository.SetSettingsMessage::class.java)
                            phoneManagementSettingsMutex.withLock {
                                check(currentPeer(address, generation)) { "Settings belong to an ended companion connection" }
                                applySettingsFromCompanion(msg.payload, generation)
                            }

                            // ACK with the current settings (msgType)
                            val ackEnv = capRepo.buildSettingsAck(ok = true)
                            sendJsonForPeer(capRepo.envelopeToJson(ackEnv), address, generation)

                        } catch (t: Throwable) {
                            Log.w(TAG, "set_settings: failed to parse/apply", t)

                            val ackEnv = capRepo.buildSettingsAck(
                                ok = false,
                                error = t.message ?: "parse/apply error"
                            )
                            sendJsonForPeer(capRepo.envelopeToJson(ackEnv), address, generation)
                        }
                        // whenever we are commanded to write setting values, we always send back over the actual settings to the plugin to ensure true sync.
                        try {
                            val currSettingsEnv = capRepo.buildWatchSettingsEnvelope()
                            sendJsonForPeer(capRepo.envelopeToJson(currSettingsEnv), address, generation)
                        } catch (t: Throwable) {
                            Log.w(TAG, "set_settings: failed to send current settings", t)
                        }
                    }
                }

                "marker_delete" -> {
                    val deletion = com.aitech.weartak_civ.data.CompanionPointRevisions.parseDeletion(
                        org.json.JSONObject(json).optJSONObject("payload"))
                    if (deletion == null) {
                        Log.w(TAG, "Ignoring invalid point deletion")
                    } else {
                        val generation = bloodhoundConnectionGeneration.get()
                        val failureHandler = CoroutineExceptionHandler { _, error ->
                            Log.e(TAG, "Failed to process point deletion", error)
                        }
                        serviceScope.launch(failureHandler) {
                            if (generation != bloodhoundConnectionGeneration.get() || !bloodhoundLinkReady()) {
                                Log.w(TAG, "Ignoring point deletion from ended/unready connection")
                                return@launch
                            }
                            val applied = repo.receivePointDeletion(deletion.first, deletion.second, generation, bhRepo)
                            Log.d(TAG, "Point deletion applied=$applied")
                            if (generation == bloodhoundConnectionGeneration.get()) {
                                val ack = com.aitech.weartak_civ.data.CompanionPointRevisions
                                    .deletionAck(deletion.first, deletion.second, applied).toString()
                                if (!sendBloodhoundControl(ack)) Log.w(TAG, "Point deletion ack write rejected")
                            }
                        }
                    }
                }

                "marker_delete_ack" -> {
                    val ack = com.aitech.weartak_civ.data.CompanionPointRevisions.parseDeletionAck(
                        org.json.JSONObject(json).optJSONObject("payload"))
                    val result = ack?.let {
                        repo.pointDeletionAcks.acknowledged(it.first, it.second,
                            bloodhoundConnectionGeneration.get(), it.third)
                    }
                    if (result == null) Log.w(TAG, "Ignoring unknown point deletion ack")
                    else repo.reportPointDeletionResult(result)
                }

                "phone_marker_cot" -> {
                    val receivedAt = System.currentTimeMillis()
                    val generation = bloodhoundConnectionGeneration.get()
                    val payload = org.json.JSONObject(json).optJSONObject("payload")
                    val xml = payload?.optString("cot", "")?.takeIf(String::isNotBlank)
                    val event = xml?.let(CotMessageParser::parseMarkerOrNull)
                    if (event == null) {
                        Log.w(TAG, "Ignoring invalid phone marker CoT")
                    } else {
                        val failureHandler = CoroutineExceptionHandler { _, error ->
                            Log.e(TAG, "Failed to process phone marker uid=${event.uid}", error)
                        }
                        serviceScope.launch(failureHandler) {
                            if (generation != bloodhoundConnectionGeneration.get()) {
                                Log.w(TAG, "Ignoring marker from ended BLE connection")
                                return@launch
                            }
                            val applied = repo.receiveMarkerEvent(
                                event = event,
                                bloodhoundRepository = bhRepo,
                                source = "Companion ATAK",
                                sourceServerId = "companion-phone",
                                receivedAt = receivedAt,
                                companionRevision = (payload?.opt("revision") as? String)?.let { token ->
                                    com.aitech.weartak_civ.data.CompanionPointRevisions.Revision(
                                        token, event.time,
                                        repo.droppedMarkerList.value.any { it.uid == event.uid },
                                        generation)
                                }
                            )
                            Log.d(TAG, "Phone marker processed uid=${event.uid} applied=$applied")
                        }
                    }
                }

                "chat_received" -> {
                    val payload = gson.fromJson(
                        json,
                        CompanionAppRepository.ChatReceivedEnvelope::class.java
                    )?.payload
                    val room = try {
                        CompanionChatIngestor.room(payload, System.currentTimeMillis())
                    } catch (error: IllegalArgumentException) {
                        Log.w(TAG, "chat_received: ${error.message}")
                        return
                    }
                    val receivedAt = room.messageList.single().timeReceived
                    val generation = bloodhoundConnectionGeneration.get()
                    val failureHandler = CoroutineExceptionHandler { _, _ ->
                        Log.e(TAG, "Phone chat command processing or acknowledgement failed")
                    }
                    serviceScope.launch(failureHandler) {
                        if (generation != bloodhoundConnectionGeneration.get() || !bloodhoundLinkReady()) return@launch
                        if (room.messageList.single().uid == device_uid) return@launch
                        if (room.isGroup && room.uid != "All Chat Rooms" &&
                            device_uid !in room.participantUids) {
                            Log.w(TAG, "Ignoring phone chat without local group membership")
                            return@launch
                        }
                        val handled = com.aitech.weartak_civ.command.ChatCommandIngress.handle(
                            listOf(room), payload?.sentAt ?: 0L, receivedAt, "companion-phone",
                            ChatCommandRuntime.create(applicationContext, repo))
                        if (!handled) EventBus.getDefault().post(
                            TakChatEvent(rawXml = "", sourceServerId = "companion-phone",
                                receivedAt = receivedAt, parsedRooms = listOf(room))
                        )
                    }
                }

                "phone_emergency_cot" -> {
                    val receivedAt = System.currentTimeMillis()
                    val payload = org.json.JSONObject(json).optJSONObject("payload")
                    val xml = payload?.optString("cot", "")?.takeIf(String::isNotBlank)
                    val event = xml?.let(CotMessageParser::parseEmergencyOrNull)
                    if (event == null) {
                        Log.w(TAG, "Ignoring invalid phone emergency CoT")
                    } else {
                        val failureHandler = CoroutineExceptionHandler { _, error ->
                            Log.e(TAG, "Failed to process phone emergency uid=${event.uid}", error)
                        }
                        serviceScope.launch(failureHandler) {
                            val applied = bhRepo.applyEmergencyEvent(
                                event = event,
                                server = "Companion ATAK",
                                sourceServerId = "companion-phone",
                                receivedAt = receivedAt,
                                deviceUid = device_uid
                            )
                            Log.d(TAG, "Phone emergency processed uid=${event.uid} applied=$applied")
                        }
                    }
                }

                else -> {
                    Log.d(TAG, "RX: unhandled type=${base.type}")
                }
            }

        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "Failed to parse RX JSON bytes=${json.toByteArray().size}")
            gatewayError(capRepo.gateway.session, "Gateway receive error")
        }
    }

    companion object {
        private val TAG = "WearTAK-" + CompanionAppService::class.java.simpleName
        private const val LOCATION_DIAGNOSTICS_REPORT_INTERVAL_MS = 30_000L
        private const val POINT_DELETION_ACK_TIMEOUT_MS = 10_000L
        private const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"
        private const val NOTIFICATION_ID = 999  // change per service
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        private const val DEFAULT_ATT_MTU = 23
        private const val TARGET_JSON_BYTES = 512
        private const val MAX_PENDING_JSON = 64
        private const val TAK_SERVER_REFRESH_TIMEOUT_MS = 5_000L
    }
}
