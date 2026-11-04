/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "companion_app")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class CompanionAppRepository(private val context: Context) {
    private val TAG = "WearTAK-CompanAppRepo"
    private val companionSettingsRetryDelayMs = 1_000L
    @Volatile
    private var lastWearTakSettingsSnapshot: WearTakSettingsSnapshot? = null

    private val repo by lazy {
        try {
            (context as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context)
        }
    }

    // --- BLE UUID constants --------------------------------------------------

    val COMPANION_SERVICE_UUID   = java.util.UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB")
    val TX_TO_COMPANION_UUID     = java.util.UUID.fromString("0000A11B-0000-1000-8000-00805F9B34FB")
    val RX_FROM_COMPANION_UUID   = java.util.UUID.fromString("0000A11C-0000-1000-8000-00805F9B34FB")

    val CLIENT_CHARACTERISTIC_CONFIG_UUID =
        java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- BLE connection state -----------------------------------------------

    enum class BleConnectionState {
        NOT_CONNECTED,
        ADVERTISING,
        CONNECTING,
        CONNECTED,
        SERVICES_READY,
        DISCONNECTING,
        DISCONNECTED,
        CONNECTION_FAILED,
        ERROR
    }

    private val _bleConnectionState =
        MutableStateFlow(BleConnectionState.NOT_CONNECTED)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState

    val companionAppReady: StateFlow<Boolean> =
        bleConnectionState
            .map { state ->
                    state == BleConnectionState.SERVICES_READY
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = false
            )

    fun updateBleConnectionState(state: BleConnectionState) {
        if (state != BleConnectionState.SERVICES_READY) gateway.reset()
        _bleConnectionState.value = state
    }

    val gateway = com.aitech.weartak_civ.cot.CompanionGatewaySession()
    val gatewayCotEvents = kotlinx.coroutines.channels.Channel<Pair<String, String>>(64)

    fun offerGatewayCot(xml: String): Boolean {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.containsSensitiveXml(xml)) {
            Log.w(TAG, "Sensitive enrollment command excluded from gateway queue")
            return false
        }
        val token = gateway.session ?: return false
        return gatewayCotEvents.trySend(token to xml).isSuccess
    }

    data class Envelope<T>(
        val vers: Int = 2,      // Companion app version control
        val msgType: String,    // Pli, Chat, Marker, Emergency, ReportInt
        val msgUid: String,     // Inherits the UID of the msgType
        val t: String,          // timestamp millis epoch string
        val src: String,        // source (default to wearos)
        val payload: T
    )

    sealed class OutboundCotEvent {
        data class Marker(val snapshot: DataRepository.MarkerSnapshot) : OutboundCotEvent()
        data class Emergency(val snapshot: DataRepository.EmergencyEventSnapshot) : OutboundCotEvent()
        data class Chat(val snapshot: DataRepository.ChatEventSnapshot) : OutboundCotEvent()
        data class Pli(val snapshot: DataRepository.PliSnapshot) : OutboundCotEvent()
        data class ReportInt(val interval: Int) : OutboundCotEvent()
    }

    private val _outboundBleCotEvents =
        MutableSharedFlow<OutboundCotEvent>(extraBufferCapacity = 64)
    val outboundBleCotEvents: SharedFlow<OutboundCotEvent> = _outboundBleCotEvents

    suspend fun enqueueMarkerSnapshotForBle(snapshot: DataRepository.MarkerSnapshot) {
        _outboundBleCotEvents.emit(OutboundCotEvent.Marker(snapshot))
    }

    suspend fun enqueueEmergencySnapshotForBle(snapshot: DataRepository.EmergencyEventSnapshot) {
        _outboundBleCotEvents.emit(OutboundCotEvent.Emergency(snapshot))
    }

    suspend fun enqueueChatSnapshotForBle(snapshot: DataRepository.ChatEventSnapshot) {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(snapshot.message)) {
            Log.w(TAG, "Sensitive enrollment command excluded from companion chat queue")
            return
        }
        _outboundBleCotEvents.emit(OutboundCotEvent.Chat(snapshot))
    }

    suspend fun enqueuePliSnapshotForBle(snapshot: DataRepository.PliSnapshot) {
        _outboundBleCotEvents.emit(OutboundCotEvent.Pli(snapshot))
    }

    suspend fun enqueueReportIntForBle(interval: Int) {
        _outboundBleCotEvents.emit(OutboundCotEvent.ReportInt(interval))
    }

    fun buildPliEnvelopeFromSnapshot(
        snapshot: DataRepository.PliSnapshot,
        repIntSeconds: Int,
        includeLegacyHeartRate: Boolean = true
    ): Envelope<PliPayload> {

        val loc = requireNotNull(snapshot.loc?.takeIf(
            com.aitech.weartak_civ.cot.EmergencyLocationPolicy::usable
        )) { "Companion position PLI requires a valid location fix" }

        val timeSnap = snapshot.time
        val sys      = snapshot.systemData
        val phys     = snapshot.physioData
        val dm       = phys.data.dailyMetrics

        val payload = PliPayload(
            lat = loc.lat,
            lon = loc.lon,
            hae = loc.hae,
            ce  = loc.ce?.toDouble(),
            le  = loc.le?.toDouble(),

            tStart = timeSnap.startCot,
            tStale = timeSnap.staleCot,

            cs        = sys.callsign,
            hr      = phys.data.heartRateBpm.takeIf { includeLegacyHeartRate },
            skt             = phys.data.skinTempF?.toInt() ?: 0,
            bat = sys.batteryPercent,

            int = repIntSeconds,
            age    = phys.data.ageYears ?: 0,
            exr    = ((phys.data.exertion ?: 0.0) * 100.0).toInt(), // 0..100

            stps = dm?.steps?.toInt() ?: 0,
            flrs = dm?.floors?.roundToInt() ?: 0,
            elev = dm?.elevationM?.roundToInt() ?: 0
        )

        val nowIso = Utils.timeNowIsoUtc()

        return Envelope(
            vers = 1,
            msgType      = "pli",
            msgUid        = sys.deviceId,   // stable per-device id; change if you want per-update unique ids
            t           = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    fun buildChatEnvelopeFromSnapshot(
        snapshot: DataRepository.ChatEventSnapshot
    ): Envelope<ChatPayload> {

        val timeSnap = snapshot.time
        val sys      = snapshot.system
        val phys     = snapshot.physio

        // For now, cap BLE message length to avoid MTU issues until chunking exists.
        val maxMsgLenForBle = 200
        val bleMsg = if (snapshot.message.length > maxMsgLenForBle) {
            snapshot.message.take(maxMsgLenForBle)
        } else {
            snapshot.message
        }

        val payload = ChatPayload(
            uid        = snapshot.uid,
            rmUid    = snapshot.roomUid,
            rmTitle  = snapshot.roomTitle,
            isGroup  = snapshot.isGroup,
            msg        = bleMsg,
            tStart     = timeSnap.startCot,
            tStale     = timeSnap.staleCot,
            cs         = sys.callsign,
            hr         = phys?.data?.heartRateBpm,
            bat        = sys.batteryPercent
        )

        val nowIso = Utils.timeNowIsoUtc()

        return Envelope(
            vers = 1,
            msgType      = "chat",
            msgUid        = snapshot.uid,
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

//    private val _markerSnapshotsForBle =
//        MutableSharedFlow<DataRepository.MarkerSnapshot>(extraBufferCapacity = 64)
//    val markerSnapshotsForBle: SharedFlow<DataRepository.MarkerSnapshot> = _markerSnapshotsForBle

    // In CompanionAppRepository

    // TODO: Make certain fields nullable like exertion, skt, hr if device not worn!!!
    data class PliPayload(
        val lat: Double,        // latitude  (WGS84)
        val lon: Double,        // longitude (WGS84)
        val hae: Double?,       // height above elevation (meters)
        val ce: Double?,        // circular error (horizontal accuracy)
        val le: Double?,        // linear error   (vertical accuracy)
        val tStart: String,     // start time (UTC zulu)
        val tStale: String,     // stale time (UTC zulu)
        val cs: String,         // callsign
        val hr: Int?,           // heart rate (bpm)
        val skt: Int,           // skin temp (degree F)
        val bat: Int?,          // battery (0-100%)
        val int: Int,           // reporting interval (seconds)
        val age: Int,           // user age (years)
        val exr: Int,           // exertion (0-100%)
        val stps: Int,          // steps     (cumulative daily taken)
        val flrs: Int,          // floors    (cumulative daily climbed)
        val elev: Int,          // elevation (cumulative daily climbed meters)
    )

    data class ChatPayload(
        val uid: String,        // chat message id
        val rmUid: String,      // chat room uid
        val rmTitle: String,    // chat room title
        val msg: String,        // truncated for BLE
        val tStart: String,     // start time zulu
        val tStale: String,     // start time zulu
        val cs: String,         // callsign
        val hr: Int?,           // heart rate
        val bat: Int?,          // battery
        val isGroup: Boolean = false,
        val roomUid: String = rmUid,
        val roomTitle: String = rmTitle,
        val recipientUid: String? = if (isGroup) null else roomUid,
        val replyTo: String? = recipientUid
    )

    data class ChatReceivedEnvelope(
        @SerializedName("msgType")
        val msgType: String? = null,
        val payload: ChatReceivedPayload? = null,
    )

    data class ChatReceivedPayload(
        val uid: String? = null,
        val senderUid: String? = null,
        val senderCallsign: String? = null,
        val msg: String? = null,
        val receivedAt: Long? = null,
        val conversationUid: String? = null,
        val conversationTitle: String? = null,
        val isGroup: Boolean? = null,
        val participantUids: List<String>? = null,
        val sentAt: Long? = null,
    )

    data class TakServerList(
        val list: List<DataRepository.TakServerItem>
    )

    data class MarkerPayload(
        val uid: String,        // == msg_id
        val type: String,       // Marker type (a-f-G, a-u-G, etc).
        val icn: String?,       // Icon Path in cot details for itak
        val lat: Double,
        val lon: Double,
        val hae: Double?,
        val ce: Double?,
        val le: Double?,
        val tStart: String,
        val tStale: String,
        val cs: String,
        val hr: String,
        val title: String,
        val bat: Int?
    )

    data class EmergencyAlertPayload(
        val uid: String,      // the unique id for an alert generated on weartak
        val state: String,    // is this an alert or cancellation of an alert
        val catg: String,     // what kind of an alert is it
        val desc: String,     // details on the alert

        // App crashed because payload was > 512 Bytes, Android GATT MTU.
//        val lat: Double,
//        val lon: Double,
//        val alt: Double?,
//        val ce: Double?,
//        val le: Double?,

        val tStart: String,
        val tStale: String,

        val cs: String,
        val hr: Int?,
        val bat: Int?
    )

    fun buildEmergencyEnvelopeFromSnapshot(
        snapshot: DataRepository.EmergencyEventSnapshot
    ): Envelope<EmergencyAlertPayload> {

        val timeSnap = snapshot.time
        val sys      = snapshot.system
        val phys     = snapshot.physio

        val payload = EmergencyAlertPayload(
            uid     = snapshot.uid,
            state   = snapshot.alertState.name,   // "ALERT" or "CANCEL"
            catg    = snapshot.category,
            desc    = snapshot.description,

            tStart  = timeSnap.startCot,
            tStale  = timeSnap.staleCot,

            cs      = sys.callsign,
            hr      = phys?.data?.heartRateBpm,
            bat     = sys.batteryPercent
        )

        val nowIso = Utils.timeNowIsoUtc()

        return Envelope(
            vers = 1,
            msgType      = "emergency",
            msgUid        = snapshot.uid,   // same UID is used for alert + cancel
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    data class WatchInfoPayload(
        val uid: String,    // Android Device UUID
        val cs: String,     // callsign
        val wtVrs: String,  // WearTAK Version
        val mac: String?,   // Mac address Bluetooth
        val bat: Int?,
        val int: Int,       // reporting interval
        val standaloneCallsign: String
    )

    data class ReportingIntPayload(
        val int: Int
    )

    suspend fun buildReportIntPayload(interval: Int): Envelope<ReportingIntPayload> {
        val payload = ReportingIntPayload(
            int = interval
        )
        val nowIso = Utils.timeNowIsoUtc()
        return Envelope(
            vers = 1,
            msgType      = "repInt",
            msgUid        = "",   // same UID is used for alert + cancel
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    suspend fun buildWatchInfoPayload(): WatchInfoPayload {
        val sys = repo.snapshotSystem()
        val mac = getLocalBluetoothMacOrNull(context)

        return WatchInfoPayload(
            uid       = sys.deviceId,
            cs       = sys.callsign,
            wtVrs = sys.wearTakVersion,
            mac     = mac,
            bat = sys.batteryPercent,
            int  = sys.reportIntSecs,
            standaloneCallsign = sys.standaloneCallsign
        )
    }

    data class EnvelopeBase(
        @SerializedName("msg_type")
        val msg_type: String? = null,

        @SerializedName("msgType")
        val msgType: String? = null
    ) {
        // Single accessor used everywhere else
        val type: String
            get() = msg_type ?: msgType ?: ""
    }
    data class SetSettingsMessage(
        @SerializedName("msg_type")
        val msg_type: String? = null,

        @SerializedName("msgType")
        val msgType: String? = null,

        val payload: SetSettingsPayload
    ) {
        val type: String
            get() = msg_type ?: msgType ?: ""
    }

    data class SetSettingsPayload(
        val takServerList: List<DataRepository.TakServerItem> = emptyList(),
        val reportIntSecs: Int? = null,
        val callsign: String? = null,
        val team: String? = null,
        val role: String? = null,
        val phoneManagedSettings: Boolean = false,
        /** ATAK Unit Display Format prefs; only `rab_rng_units_pref` is used. Absent = unchanged. */
        val unitPrefs: Map<String, Any?>? = null,
    )

    data class SettingsAckPayload(
        val ok: Boolean,
        val error: String? = null
    )

    fun buildUnitPrefsAck(ok: Boolean, error: String? = null): Envelope<SettingsAckPayload> =
        buildSettingsAck(ok, error).copy(msgType = "unit_prefs_ack")

    fun buildSettingsAck(ok: Boolean, error: String? = null): Envelope<SettingsAckPayload> {
        val nowIso = Utils.timeNowIsoUtc()
        val msgId = UUID.randomUUID().toString()

        val payload = SettingsAckPayload(ok = ok, error = error)

        return Envelope(
            vers = 1,
            msgType = "settings_set_ack",
            msgUid = msgId,
            t = nowIso,
            src = "wearos",
            payload = payload
        )
    }

    suspend fun buildWatchSettingsEnvelope(): Envelope<WearTakSettingsSnapshot> {
        val nowIso = Utils.timeNowIsoUtc()
        val msgId  = UUID.randomUUID().toString()
        val payload = snapshotWearTakSettings()

        return Envelope(
            vers = 1,
            msgType = "settings_request",
            msgUid        = msgId,
            t = nowIso,
            src = "wearos",
            payload = payload
        )
    }

    // The settings we can change from the companion app.
    data class WearTakSettingsSnapshot(
        val timeMillis: Long,                       // When the snapshot was taken
        val takServerList: List<TakServerItem>,     // Tak Server Connections
        val reportIntSecs: Int,                     // pli reporting rate
        val callsign: String,                       // callsign right now
        val team: String,
        val role: String,
        val phoneManagedSettings: Boolean,
        val standaloneCallsign: String = "",
        val unitPrefs: Map<String, String> = emptyMap(),
    )

    suspend fun snapshotWearTakSettings(): WearTakSettingsSnapshot {
        return WearTakSettingsSnapshot(
            timeMillis    = System.currentTimeMillis(),
            takServerList = repo.awaitTakServerList(),
            reportIntSecs = repo.getConstantReportingInterval.first(),
            callsign      = repo.getCallsign.first(),
            team          = repo.getTeam.first(),
            role          = repo.getRole.first(),
            phoneManagedSettings = repo.phoneManagedSettingsEnabled.first(),
            standaloneCallsign = repo.getStandaloneCallsign.first(),
            unitPrefs = repo.unitPrefs.first().toAtakMap(),
        )
    }

    data class SettingsChangedPayload(
        val callsign: String? = null,
        val reportIntSecs: Int? = null,
        val takServerList: List<TakServerItem>? = null,
        val team: String? = null,
        val role: String? = null,
        val standaloneCallsign: String? = null
    )

    suspend fun buildSettingsChangedEnvelope(delta: SettingsChangedPayload): Envelope<SettingsChangedPayload> {
        val nowIso = Utils.timeNowIsoUtc()
        val msgId  = UUID.randomUUID().toString()

        return Envelope(
            vers = 1,
            msgType = "settings_changed",
            msgUid = msgId,
            t = nowIso,
            src = "wearos",
            payload = delta
        )
    }


    /**
     * Emits when phone-managed identity / reporting / server settings change.
     *
     * Important: timeMillis is NOT included in equality comparison, otherwise it would
     * emit constantly. We generate timeMillis only at emit time.
     */
    fun wearTakSettingsFlow(): Flow<WearTakSettingsSnapshot> {
        val profileAndConnectionSettings = combine(
            repo.getTakServerListReady,
            repo.getConstantReportingInterval,
            repo.getCallsign,
            repo.getTeam,
            repo.getRole
        ) { list, interval, callsign, team, role ->
            WearTakSettingsSnapshot(
                timeMillis = System.currentTimeMillis(),
                takServerList = list,
                reportIntSecs = interval,
                callsign = callsign,
                team = team,
                role = role,
                phoneManagedSettings = false
            )
        }
        return combine(
            profileAndConnectionSettings,
            repo.phoneManagedSettingsEnabled,
            repo.getStandaloneCallsign
        ) { snapshot, managed, standaloneCallsign ->
            snapshot.copy(phoneManagedSettings = managed, standaloneCallsign = standaloneCallsign)
        }
            // Strip timeMillis out of distinct comparison
            .distinctUntilChanged { old, new ->
                old.callsign == new.callsign &&
                        old.standaloneCallsign == new.standaloneCallsign &&
                        old.reportIntSecs == new.reportIntSecs &&
                        old.takServerList == new.takServerList &&
                        old.team == new.team &&
                        old.role == new.role &&
                        old.phoneManagedSettings == new.phoneManagedSettings
            }
            .onEach { snapshot -> lastWearTakSettingsSnapshot = snapshot }
            .retryWhen { cause, _ ->
                if (cause !is IllegalStateException) {
                    return@retryWhen false
                }
                Log.e(TAG, "wearTakSettingsFlow: settings unavailable; retrying", cause)
                delay(companionSettingsRetryDelayMs)
                true
            }
            .catch { cause ->
                Log.e(TAG, "wearTakSettingsFlow: collector stopped", cause)
                lastWearTakSettingsSnapshot?.let { emit(it) }
            }
    }

    fun settingsChangedDeltasFlow(): Flow<SettingsChangedPayload> = flow {
        var prev: WearTakSettingsSnapshot? = null

        wearTakSettingsFlow()
            .catch { cause ->
                Log.e(TAG, "settingsChangedDeltasFlow: settings collector stopped", cause)
            }
            .collect { cur ->
            val p = prev
            prev = cur

            // First emission: you can choose to emit a full "delta" or emit nothing.
            // I recommend emitting full once so the plugin UI gets initialized immediately.
            val callsignChanged = (p == null) || (p.callsign != cur.callsign)
            val standaloneCallsignChanged = (p == null) || (p.standaloneCallsign != cur.standaloneCallsign)
            val intChanged      = (p == null) || (p.reportIntSecs != cur.reportIntSecs)
            val serversChanged  = (p == null) || (p.takServerList != cur.takServerList)
            val teamChanged     = (p == null) || (p.team != cur.team)
            val roleChanged     = (p == null) || (p.role != cur.role)

            // If nothing changed (should be rare because wearTakSettingsFlow is distinct, but safe):
            if (!callsignChanged && !standaloneCallsignChanged && !intChanged && !serversChanged &&
                !teamChanged && !roleChanged) return@collect

            emit(
                SettingsChangedPayload(
                    callsign = if (callsignChanged) cur.callsign else null,
                    standaloneCallsign = if (standaloneCallsignChanged) cur.standaloneCallsign else null,
                    reportIntSecs = if (intChanged) cur.reportIntSecs else null,
                    takServerList = if (serversChanged) cur.takServerList else null,
                    team = if (teamChanged) cur.team else null,
                    role = if (roleChanged) cur.role else null
                )
            )
        }
    }

    suspend fun buildWatchInfoEnvelope(): Envelope<WatchInfoPayload> {
        val nowIso = Utils.timeNowIsoUtc()
        val msgId  = UUID.randomUUID().toString()

        val payload = buildWatchInfoPayload()

        return Envelope(
            vers = 1,
            msgType      = "watch_info",
            msgUid        = msgId,
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    /**
     * Example builder for a marker envelope based on your MarkerSnapshot.
     * Adapt field names to your actual MarkerSnapshot structure.
     */
    suspend fun buildMarkerEnvelopeFromSnapshot(
        snapshot: DataRepository.MarkerSnapshot
    ): Envelope<MarkerPayload> {
        val uid = snapshot.uid                              // WearTAK-generated UID
        val nowIso = System.currentTimeMillis().toString()  // or Utils.timeNowIsoUtc()
        val source = "wearos"                               // eventually can replace with android device uuid

        var hr = (snapshot.physio?.data?.heartRateBpm)
        var hrStr = "\"N/A\""
        if (hr != null) {
            hrStr = hr.toString()
        }

        val payload = MarkerPayload(
            uid       = uid,
            type     = snapshot.markerType.type,
            icn       = snapshot.iconSetPath,
            lat             = snapshot.loc?.lat ?: 0.0,
            lon             = snapshot.loc?.lon ?: 0.0,
            hae             = snapshot.loc?.hae,
            ce              = snapshot.loc?.ce?.toDouble(),
            le              = snapshot.loc?.le?.toDouble(),
            tStart      = snapshot.time.startCot,
            tStale      = snapshot.time.staleCot,
            cs        = snapshot.system.callsign,
            hr      = hrStr,
            title = snapshot.title,
            bat = snapshot.system.batteryPercent
        )

        return Envelope(
            msgType  = "marker",
            msgUid    = uid,
            t = nowIso,
            src    = source,
            payload   = payload
        )
    }

    private val gson = Gson()

    // --- JSON helper ------------------------------------------------------------
    fun <T> envelopeToJson(envelope: Envelope<T>): String {
        return gson.toJson(envelope)
    }

    @SuppressLint("HardwareIds")
    private fun getLocalBluetoothMacOrNull(ctx: Context): String? {
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        // [Unverified] On modern Android/WearOS this will often return a
        // constant like "02:00:00:00:00:00" or null. Treat as best-effort / debug-only.
        return try {
            val manager  = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter  = manager?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.address
        } catch (e: SecurityException) {
            Log.w(TAG, "Local Bluetooth address unavailable after permission check")
            null
        } catch (t: Throwable) {
            null
        }
    }

    companion object {

    }
}
