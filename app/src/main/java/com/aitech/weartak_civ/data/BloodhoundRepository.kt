/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.cot.CotLink
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotMarkerMapItem
import com.aitech.weartak_civ.cot.CotPoint
import com.aitech.weartak_civ.cot.CotEmergencyEvent
import com.aitech.weartak_civ.cot.canCoalesceTrack
import com.aitech.weartak_civ.provider.TargetVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.pow
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bloodhound")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private const val NON_TAK_BLOODHOUND_SOURCE_ID = "non-tak"
private const val COMPANION_BLOODHOUND_SOURCE_ID = "companion-bloodhound"
class BloodhoundRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.dataStore,
    private val projectionScope: CoroutineScope = scope,
) {
    private val TAG = "WearTAK-" + BloodhoundRepository::class.java.simpleName
    private val emergencyMutex = Mutex()
    private var cancellationsLoaded = false
    private val cancellationTimes = mutableMapOf<String, Long>()
    private val dismissalTimes = mutableMapOf<String, Long>()
    private val alertExpiryJobs = mutableMapOf<SourceCotKey, Job>()

    data class BloodhoundItem(
        val bhEvent: BloodhoundEvent,

        // fields you want to update/react to in UI and service:
        val vector: TargetVector = TargetVector(),
        val state: State = State(),
        val target: Target = Target(bhEvent.mapItem),
        val origin: Origin = Origin(bhEvent),
        val compass: Compass = Compass()
    ) {
        val server = bhEvent.server
        val sourceServerId = bhEvent.sourceServerId
        val uid = bhEvent.mapItem.uid
        val originLabel: String?
            get() = bhEvent.issuer?.parentCallsign
                ?: bhEvent.mapItem.creator?.callsign

        val containsHoundmasterRemark: Boolean
            get() = bhEvent.remark.equals("#houndmaster")


        data class Target(
            val mapItem: CotMarkerMapItem
        ) {
            val name: String? = mapItem.cotEvent.detail.markerCallsign
            val point: CotPoint = mapItem.point
            val type: String = mapItem.type
        }

        data class State(
            var acknowledged: Boolean = false,
            var active: Boolean = false,
            var inPos: Boolean = false,
        )

        data class Compass(
            var targetBearingDeg: Double? = null,
        )

        data class Origin(
            val bhEvent: BloodhoundEvent
        ) {
            val server = bhEvent.server
            val issuerUid = bhEvent.issuer?.uid
            val chatRoomUid = issuerUid
            val chatRoom: ChatRoom? = null
        }
    }

    enum class ActivationResult {
        FIRST_ACK,
        SWITCH_ONLY,
        NO_CHANGE
    }

    /*
     *  Backend uses this to map devices back
     */
    data class BloodhoundEvent(
        val mapItem: CotMarkerMapItem,                   // to identify the event, calc trajectory,
        val uid: String = mapItem.uid,                   // unique identifier of the event
        val server: String,                              // so we can reply via chat to the proper socket  //TODO: Instead of passing the entire details, we can pass a uuid for a tak server detail map...
        var issuer: CotLink? = mapItem.link,              // so we send the chat to the right person
        var timeIssued: Long?,                            // tell user on UI when it was sent.
        val timeCompleted: Long?,                         // tell the issuer how long it took.
        val remark: String = "",
        val sourceServerId: String? = null,
        val receivedAt: Long = System.currentTimeMillis(),
        val remoteAlert: RemoteAlert? = null,
        val tracksUserPosition: Boolean = false,
        val isRouteFollow: Boolean = false,
        val tracksLocalMarker: Boolean = false
    )

    data class RemoteAlert(
        val category: String,
        val description: String,
        val senderUid: String?,
        val senderCallsign: String?,
        val staleTime: Long,
        val hasLocation: Boolean,
        val isStale: Boolean = false
    )

    data class BloodhoundUpdate(
        val event: BloodhoundEvent,
        val isNewUid: Boolean,
        val becameHoundmaster: Boolean
    )

    /*
     * The UI needs a List of all the current Bloodhounds Items that are
     *    selectable for navigation. These Bloodhound Items can be active or
     *    inactive, acknowledged or unacknowledged.
     */
    // ---- State ----
    private val bloodhoundStateLock = Any()
    private val bloodhoundStore = SourceAwareCotStore(
        sourceIdOf = { item: BloodhoundItem -> sourceKey(item.bhEvent).sourceId },
        cotUidOf = BloodhoundItem::uid,
        eventTimeOf = { item: BloodhoundItem -> item.bhEvent.mapItem.cotEvent.time },
        receivedAtOf = { item: BloodhoundItem -> item.bhEvent.receivedAt },
        publicationScope = projectionScope,
        equivalent = { a, b ->
            a.bhEvent.copy(receivedAt = b.bhEvent.receivedAt) == b.bhEvent &&
                a.state == b.state && a.vector == b.vector && a.compass == b.compass
        }
    )
    private val _bloodhoundMap = MutableStateFlow<Map<String, BloodhoundItem>>(emptyMap())
    val bloodhoundMap: StateFlow<Map<String, BloodhoundItem>> = _bloodhoundMap
    private val _remoteAlerts = MutableStateFlow<Map<String, BloodhoundItem>>(emptyMap())
    val remoteAlerts: StateFlow<Map<String, BloodhoundItem>> = _remoteAlerts
    val remoteEmergencyMapItems: StateFlow<List<RemoteEmergencyMapItem>> =
        remoteAlerts.map { alerts -> alerts.values.mapNotNull { it.toRemoteEmergencyMapItemOrNull() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun applyEmergencyEvent(
        event: CotEmergencyEvent,
        server: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis(),
        deviceUid: String
    ): Boolean = emergencyMutex.withLock {
        if (event.uid.isBlank() || event.time <= 0 || event.start <= 0 || event.stale <= 0 || event.isFrom(deviceUid)) {
            Log.w(TAG, "Emergency ignored reason=invalid-or-self uid=${event.uid}")
            return@withLock false
        }
        loadEmergencyLedgers()
        if (event.cancelled) {
            val previous = cancellationTimes[event.uid] ?: Long.MIN_VALUE
            if (event.time > previous) {
                val clearsDismissal = dismissalTimes[event.uid]?.let { event.time >= it } == true
                // Persist before publishing removal so restart cannot resurrect a cancelled alert.
                dataStore.edit { prefs ->
                    val json = JSONObject(prefs[ALERT_CANCELLATIONS] ?: "{}")
                    json.put(event.uid, maxOf(json.optLong(event.uid, Long.MIN_VALUE), event.time))
                    prefs[ALERT_CANCELLATIONS] = json.toString()
                    if (clearsDismissal) {
                        val dismissed = JSONObject(prefs[ALERT_DISMISSALS] ?: "{}")
                        dismissed.remove(event.uid)
                        prefs[ALERT_DISMISSALS] = dismissed.toString()
                    }
                }
                cancellationTimes[event.uid] = event.time
                if (clearsDismissal) dismissalTimes.remove(event.uid)
            }
            synchronized(bloodhoundStateLock) {
                bloodhoundStore.snapshot().filter { (key, item) ->
                    key.cotUid == event.uid && item.bhEvent.remoteAlert != null &&
                        item.bhEvent.mapItem.cotEvent.time <= event.time
                }.keys.forEach { key ->
                    bloodhoundStore.remove(key)
                    alertExpiryJobs.remove(key)?.cancel()
                    if (_activeKey.value == key) setActiveKey(null)
                }
                publishVisibleBloodhounds()
            }
            Log.i(TAG, "Emergency cancelled uid=${event.uid} source=$sourceServerId time=${event.time}")
            return@withLock true
        }
        val now = System.currentTimeMillis()
        val cancelledAt = cancellationTimes[event.uid]
        if (cancelledAt != null && event.time <= cancelledAt) {
            Log.i(TAG, "Emergency ignored reason=cancelled uid=${event.uid} time=${event.time} cancelledAt=$cancelledAt")
            return@withLock false
        }
        dismissalTimes[event.uid]?.let { dismissedAt ->
            // Track suppressed refreshes so a delayed cancellation cannot end the wrong session.
            if (event.time > dismissedAt) persistDismissal(event.uid, event.time)
            Log.i(TAG, "Emergency ignored reason=locally-dismissed uid=${event.uid} time=${event.time}")
            return@withLock false
        }
        synchronized(bloodhoundStateLock) {
            val source = sourceServerId ?: "remote:$server"
            val key = SourceCotKey("emergency:$source", event.uid)
            val previous = bloodhoundStore.item(key)
            if (previous != null && event.time < previous.bhEvent.mapItem.cotEvent.time) {
                Log.i(TAG, "Emergency ignored reason=older uid=${event.uid} source=$source time=${event.time}")
                return@synchronized false
            }
            // A location-less refresh can retain only this source's existing valid location.
            val point = if (event.hasLocation) event.point else
                previous?.takeIf { it.bhEvent.remoteAlert?.hasLocation == true }?.target?.point
            val hasLocation = point != null
            val detail = CotMarkerDetail(
                markerCallsign = event.senderCallsign ?: event.uid,
                creator = null,
                link = CotLink(event.senderUid, null, null, event.senderCallsign, null),
                usericon = null,
                remarks = event.description,
                contact = null
            )
            val marker = CotMarkerEvent(
                "2.0", event.uid, event.type, "h-e", event.time, event.start, event.stale, null,
                point ?: CotPoint(0.0, 0.0, 9999999.0, 9999999.0, 9999999.0), detail
            )
            val e = BloodhoundEvent(
                mapItem = CotMarkerMapItem(marker), server = server, timeIssued = event.time,
                timeCompleted = null, sourceServerId = source, receivedAt = receivedAt,
                remoteAlert = RemoteAlert(
                    event.category ?: event.type, event.description.orEmpty(), event.senderUid,
                    event.senderCallsign, event.stale, hasLocation, isStale = event.stale <= now
                )
            )
            val item = previous?.copy(
                bhEvent = e, target = BloodhoundItem.Target(e.mapItem), origin = BloodhoundItem.Origin(e),
                vector = TargetVector(), compass = BloodhoundItem.Compass()
            ) ?: BloodhoundItem(e)
            val isNew = !_remoteAlerts.value.containsKey(event.uid)
            val visibleTime = _remoteAlerts.value[event.uid]?.bhEvent?.mapItem?.cotEvent?.time
            if (!bloodhoundStore.upsertWithResult(item).applied) {
                Log.i(TAG, "Emergency ignored reason=not-newer uid=${event.uid} source=$source time=${event.time}")
                return@synchronized false
            }
            publishVisibleBloodhounds()
            reconcileActiveTrackingSource(event.uid)
            if (visibleTime == null || event.time > visibleTime) {
                _bloodhoundEvent.value = BloodhoundUpdate(e, isNew, false)
            }
            alertExpiryJobs.remove(key)?.cancel()
            alertExpiryJobs[key] = scope.launch {
                delay((event.stale - System.currentTimeMillis()).coerceAtLeast(0))
                synchronized(bloodhoundStateLock) {
                    val current = bloodhoundStore.item(key)
                    if (current?.bhEvent?.remoteAlert?.staleTime?.let { it <= System.currentTimeMillis() } == true) {
                        bloodhoundStore.update(key) { item ->
                            item.copy(bhEvent = item.bhEvent.copy(
                                remoteAlert = item.bhEvent.remoteAlert?.copy(isStale = true)
                            ))
                        }
                        alertExpiryJobs.remove(key)
                        publishVisibleBloodhounds()
                        Log.i(TAG, "Emergency stale retained uid=${event.uid} source=$source")
                    }
                }
            }
            Log.i(TAG, "Emergency active uid=${event.uid} source=$source hasLocation=$hasLocation")
            true
        }
    }

    private inline fun updateBloodhound(
        uid: String,
        crossinline transform: (BloodhoundItem) -> BloodhoundItem
    ) {
        synchronized(bloodhoundStateLock) {
            visibleKey(uid)?.let { key -> bloodhoundStore.update(key) { current -> transform(current) } }
            publishVisibleBloodhounds()
        }
    }

    // So we can filter da list of hounds
    private fun BloodhoundItem.receivedAt(): Long {
        return bhEvent.receivedAt
    }

    // Detail screen activates a uid, downstream updates UI list and active
    private val _activeKey = MutableStateFlow<SourceCotKey?>(null)
    private val _activeUid = MutableStateFlow<String?>(null)
    val activeUid: StateFlow<String?> = _activeUid
    private val presentationConsumers = mutableMapOf<Any, String?>()
    // Null means the visible picker needs every target; a set means only those detail targets.
    private val _presentationTargets = MutableStateFlow<Set<String>?>(emptySet())
    internal val presentationTargets: StateFlow<Set<String>?> = _presentationTargets

    internal fun requestPresentation(consumer: Any, uid: String? = null) = synchronized(bloodhoundStateLock) {
        presentationConsumers[consumer] = uid
        publishPresentationDemand()
    }

    internal fun releasePresentation(consumer: Any) = synchronized(bloodhoundStateLock) {
        presentationConsumers.remove(consumer)
        publishPresentationDemand()
    }

    private fun publishPresentationDemand() {
        _presentationTargets.value = if (presentationConsumers.values.any { it == null }) null
            else presentationConsumers.values.filterNotNull().toSet()
    }

    internal fun guidanceTargets(): Map<String, BloodhoundItem> = synchronized(bloodhoundStateLock) {
        val requested = _presentationTargets.value ?: return@synchronized _bloodhoundMap.value
        val uids = requested + listOfNotNull(_activeUid.value)
        uids.mapNotNull { uid -> _bloodhoundMap.value[uid]?.let { uid to it } }.toMap()
    }

    private val _routeFollow = MutableStateFlow<RouteFollowSession?>(null)
    val routeFollow: StateFlow<RouteFollowSession?> = _routeFollow
    private var routePersistence: RoutePersistence? = null

    private fun setRouteSession(session: RouteFollowSession?) {
        routePersistence?.saveSession(session)
        _routeFollow.value = session
    }

    internal fun restoreRouteSession(routes: List<TakRoute>, persistence: RoutePersistence) =
        synchronized(bloodhoundStateLock) {
            routePersistence = persistence
            val session = persistence.restoreSession(routes, System.currentTimeMillis())
            setRouteSession(session)
            if (session != null) {
                updateRouteTarget(session)
                // Recovery selects only the local synthetic target; no phone command or ACK is sent.
                activate(session.uid)
            }
        }
    private data class PhoneRouteRevision(val sequence: Long, val routeUid: String, val ended: Boolean)
    private val phoneRouteRevisions = object : LinkedHashMap<String, PhoneRouteRevision>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhoneRouteRevision>?) = size > 128
    }

    fun applyPhoneRoute(update: CompanionRouteNavigation, location: TakRoutePoint?): Boolean =
        synchronized(bloodhoundStateLock) {
            val previous = phoneRouteRevisions[update.sessionId]
            if (previous != null && (previous.ended || update.sequence <= previous.sequence ||
                    previous.routeUid != update.routeUid)) return@synchronized false
            if (update.action == "stop") {
                phoneRouteRevisions[update.sessionId] = PhoneRouteRevision(update.sequence, update.routeUid, true)
                val session = _routeFollow.value?.takeIf { it.phoneSessionId == update.sessionId }
                if (session != null) {
                    stopRoute()
                    if (update.completed) {
                        setRouteSession(session.copy(
                            navigation = session.navigation.atIndex(session.navigation.points.lastIndex, completed = true),
                            remainingMeters = 0.0))
                    }
                }
                return@synchronized true
            }
            val route = requireNotNull(update.route)
            val index = requireNotNull(update.targetPointIndex)
            val navigation = RouteFollow.start(route.points, RouteFollow.StartMode.BEGINNING, method = route.method).atIndex(index)
            if (_routeFollow.value?.phoneSessionId != update.sessionId) stopRoute()
            val fix = location?.takeIf(RouteFollow::validLocation)
            val session = RouteFollowSession(route, navigation, fix?.let(navigation::remainingDistanceMeters), update.sessionId)
            setRouteSession(session)
            phoneRouteRevisions[update.sessionId] = PhoneRouteRevision(update.sequence, update.routeUid, false)
            updateRouteTarget(session)
            activate(session.uid)
            true
        }

    fun disconnectPhoneRoute() = synchronized(bloodhoundStateLock) {
        val session = _routeFollow.value?.takeIf { it.phoneSessionId != null } ?: return@synchronized
        // A reconnect may resume, but explicit local stop/delete remains tombstoned.
        setRouteSession(null)
        removeTarget(session.uid)
    }

    private fun dismissPhoneRoute(session: RouteFollowSession) {
        session.phoneSessionId?.let { id ->
            phoneRouteRevisions[id]?.let { phoneRouteRevisions[id] = it.copy(ended = true) }
        }
    }

    fun preparePhoneRoute(sessionId: String) = synchronized(bloodhoundStateLock) {
        val session = _routeFollow.value ?: return@synchronized
        phoneRouteRevisions[sessionId] = PhoneRouteRevision(-1L, session.route.uid, false)
        setRouteSession(session.copy(phoneSessionId = sessionId))
    }

    fun resumeLocalRoute(sessionId: String) = synchronized(bloodhoundStateLock) {
        val session = _routeFollow.value?.takeIf { it.phoneSessionId == sessionId } ?: return@synchronized
        dismissPhoneRoute(session)
        setRouteSession(session.copy(phoneSessionId = null))
    }

    fun startRoute(route: TakRoute, mode: RouteFollow.StartMode, location: TakRoutePoint?): Boolean =
        synchronized(bloodhoundStateLock) {
            if (route.staleTime?.let { it <= System.currentTimeMillis() } == true) return@synchronized false
            val navigation = runCatching { RouteFollow.start(route.points, mode, location, route.method) }.getOrNull()
                ?: return@synchronized false
            stopRoute()
            val session = RouteFollowSession(route, navigation, location?.let(navigation::remainingDistanceMeters))
            setRouteSession(session)
            updateRouteTarget(session)
            // Use the same single-active selection as ordinary and companion targets, without an ACK.
            activate(session.uid)
            _activeUid.value == session.uid
        }

    fun stopRoute() = synchronized(bloodhoundStateLock) {
        val session = _routeFollow.value ?: return@synchronized
        dismissPhoneRoute(session)
        setRouteSession(null)
        removeTarget(session.uid)
    }

    fun reconcileRoutes(routes: List<TakRoute>) = synchronized(bloodhoundStateLock) {
        val session = _routeFollow.value ?: return@synchronized
        // Phone snapshots replace geometry and index together, independently of map-store emissions.
        if (session.phoneSessionId != null) return@synchronized
        val current = routes.firstOrNull(session::matches)
        // A changed geometry invalidates the index; never silently navigate a different route.
        if (current == null || current.points != session.route.points || current.method != session.route.method) stopRoute()
    }

    fun updateRouteLocation(location: TakRoutePoint, accuracyMeters: Double = 0.0) = synchronized(bloodhoundStateLock) {
        val session = _routeFollow.value ?: return@synchronized
        if (session.navigation.completed || _activeUid.value != session.uid || !RouteFollow.validLocation(location)) {
            return@synchronized
        }
        val navigation = if (session.phoneSessionId == null) session.navigation.advance(location, accuracyMeters) else session.navigation
        val updated = session.copy(navigation = navigation, remainingMeters = navigation.remainingDistanceMeters(location))
        if (navigation.completed) {
            // Keep completion visible, but remove the synthetic target and clear active navigation.
            setRouteSession(null)
            removeTarget(session.uid)
            setRouteSession(updated)
        } else {
            setRouteSession(updated)
            if (navigation.currentIndex != session.navigation.currentIndex) updateRouteTarget(updated)
        }
    }

    private fun updateRouteTarget(session: RouteFollowSession) {
        val old = _bloodhoundMap.value[session.uid]?.bhEvent?.mapItem?.cotEvent
        val time = maxOf(System.currentTimeMillis(), (old?.time ?: 0L) + 1L)
        val point = session.navigation.target
        val marker = CotMarkerEvent(
            version = "2.0", uid = session.uid, type = "b-m-p-s-m", how = "h-g-i-g-o",
            time = time, start = time, stale = Long.MAX_VALUE, access = null,
            point = CotPoint(point.latitude, point.longitude, point.altitude ?: 0.0, 999999.0, 999999.0),
            detail = CotMarkerDetail(session.route.name, null, null, null, null, null)
        )
        upsertFromEvent(BloodhoundEvent(
            mapItem = CotMarkerMapItem(marker), server = "local", timeIssued = time,
            timeCompleted = null, isRouteFollow = true
        ))
    }

    // --- UI Adapter function
    //  - backend domain item -> frontend UI model item (Utils.BloodhoundItem)
    private fun BloodhoundItem.toUi(isActive: Boolean): Utils.BloodhoundItem {
        val distKm = if (bhEvent.remoteAlert?.hasLocation == false) null else this.vector.distance
        val needle = this.compass.targetBearingDeg ?: 0.0

//        val eventMs = Utils.toMillis(bhEvent.timeIssued) ?: System.currentTimeMillis()
        val eventMs = bhEvent.remoteAlert?.let { bhEvent.mapItem.cotEvent.time } ?: System.currentTimeMillis()

        return Utils.BloodhoundItem(
            target = this.target.name ?: "unknown",
            uid = this.uid,
            callsign = this.originLabel ?: "Unknown sender",
            timeReceived = eventMs,
            distance = distKm,
            orientation = needle.round(1).toFloat(),
            bloodhounding = isActive,
            acknowledged = this.state.acknowledged,
            alertCategory = bhEvent.remoteAlert?.category,
            isRemoteAlert = bhEvent.remoteAlert != null,
            isStale = bhEvent.remoteAlert?.isStale == true,
            hasLocation = bhEvent.remoteAlert?.hasLocation != false,
            isCamera = bhEvent.mapItem.cotEvent.detail.isCamera || target.type == "b-i-v",
            targetKind = when {
                bhEvent.remoteAlert != null -> Utils.BloodhoundTargetKind.ALERT
                uid.startsWith("route-point-") -> Utils.BloodhoundTargetKind.POINT
                bhEvent.tracksLocalMarker -> Utils.BloodhoundTargetKind.MARKER
                bhEvent.tracksUserPosition || target.type.startsWith("a-") -> Utils.BloodhoundTargetKind.CONTACT
                else -> Utils.BloodhoundTargetKind.MARKER
            }
        )
    }
    fun addMarkerTarget(
        uid: String,
        title: String,
        markerType: String,
        lat: Double,
        lon: Double,
        time: Long,
        isCamera: Boolean = false,
        tracksLocalMarker: Boolean = false
    ) {
        val point = CotPoint(
            lat = lat,
            lon = lon,
            hae = 0.0,
            ce = 999999.0,
            le = 999999.0
        )

        val detail = CotMarkerDetail(
            markerCallsign = title,
            link = null,
            creator = null,
            usericon = null,
            remarks = null,
            contact = null,
            isCamera = isCamera
        )

        val cotEvent = CotMarkerEvent(
            version = "2.0",
            uid = uid,
            type = markerType,
            how = "h-g-i-g-o",
            time = time,
            start = time,
            stale = time + 300_000,
            access = null,
            point = point,
            detail = detail
        )

        val mapItem = CotMarkerMapItem(cotEvent)

        upsertFromEvent(
            BloodhoundEvent(
                mapItem = mapItem,
                server = "local",
                timeIssued = time,
                timeCompleted = null,
                tracksLocalMarker = tracksLocalMarker
            )
        )
    }

    fun addUserTarget(user: DataRepository.ReceivedUser): Boolean =
        synchronized(bloodhoundStateLock) {
            val location = user.location
            if (user.uid.isBlank() || location == null ||
                !location.lat.isFinite() || !location.lon.isFinite() ||
                location.lat !in -90.0..90.0 || location.lon !in -180.0..180.0
            ) {
                Log.w(TAG, "User tracking rejected: missing identity or invalid location")
                return@synchronized false
            }
            val marker = CotMarkerEvent(
                version = "2.0", uid = user.uid, type = user.type, how = "h-g-i-g-o",
                time = user.timeReceived, start = user.timeReceived, stale = user.staleTime,
                access = null,
                point = CotPoint(
                    location.lat, location.lon, location.hae ?: 0.0,
                    location.ce?.toDouble() ?: 999999.0,
                    location.le?.toDouble() ?: 999999.0
                ),
                detail = CotMarkerDetail(user.title, null, null, null, null, null, isCamera = user.isCamera)
            )
            val event = BloodhoundEvent(
                mapItem = CotMarkerMapItem(marker), server = user.source,
                timeIssued = user.timeReceived, timeCompleted = null,
                sourceServerId = user.sourceServerId, receivedAt = user.receivedAt,
                tracksUserPosition = true
            )
            val existing = bloodhoundStore.item(sourceKey(event))
            // Preserve an existing assignment's issuer and acknowledgement semantics.
            if (existing != null) {
                bloodhoundStore.update(sourceKey(event)) {
                    it.copy(bhEvent = it.bhEvent.copy(tracksUserPosition = true))
                }
            }
            upsertFromEvent(
                existing?.bhEvent?.copy(
                    mapItem = CotMarkerMapItem(marker.copy(
                        detail = existing.bhEvent.mapItem.cotEvent.detail.copy(
                            markerCallsign = user.title, isCamera = user.isCamera)
                    )),
                    receivedAt = event.receivedAt,
                    tracksUserPosition = true
                ) ?: event
            )
            isUidValid(user.uid)
        }

    fun refreshUserTargets(users: List<DataRepository.ReceivedUser>) =
        synchronized(bloodhoundStateLock) {
            users.forEach { user ->
                val key = SourceCotKey(user.sourceServerId ?: NON_TAK_BLOODHOUND_SOURCE_ID, user.uid)
                if (bloodhoundStore.item(key)?.bhEvent?.tracksUserPosition == true) addUserTarget(user)
            }
        }

    fun refreshLocalMarkerTargets(markers: List<DataRepository.DroppedMarker>): Boolean =
        synchronized(bloodhoundStateLock) {
            var changed = false
            markers.filter { it.cotType != DataRepository.MarkerCotType.CLEAR }.forEach { marker ->
                val location = marker.location ?: return@forEach
                if (!location.lat.isFinite() || !location.lon.isFinite() ||
                    location.lat !in -90.0..90.0 || location.lon !in -180.0..180.0
                ) return@forEach
                val key = SourceCotKey(NON_TAK_BLOODHOUND_SOURCE_ID, marker.uid)
                val item = bloodhoundStore.item(key) ?: return@forEach
                if (!item.bhEvent.tracksLocalMarker) return@forEach
                val oldEvent = item.bhEvent.mapItem.cotEvent
                val event = oldEvent.copy(
                    point = oldEvent.point.copy(lat = location.lat, lon = location.lon),
                    type = marker.markerType.type,
                    detail = oldEvent.detail.copy(markerCallsign = marker.title)
                )
                if (event == oldEvent) return@forEach
                val mapItem = CotMarkerMapItem(event)
                bloodhoundStore.update(key) {
                    it.copy(bhEvent = it.bhEvent.copy(mapItem = mapItem),
                        target = BloodhoundItem.Target(mapItem))
                }
                changed = true
            }
            if (changed) publishVisibleBloodhounds()
            changed
        }


    // UI adapter, list to populate chips in the bloodhounds screen
    val listBhItemsUi: StateFlow<List<Utils.BloodhoundItem>> =
        combine(bloodhoundMap, _activeKey) { map, activeKey ->
            map.values
                .filterNot { it.bhEvent.isRouteFollow }
                .sortedWith(
                    compareByDescending<BloodhoundItem> { it.bhEvent.remoteAlert != null }
                        .thenByDescending { if (it.bhEvent.remoteAlert != null) it.bhEvent.mapItem.cotEvent.time else Long.MIN_VALUE }
                        .thenByDescending { sourceKey(it.bhEvent) == activeKey }
                        .thenByDescending { it.receivedAt() }
                )
                .map { item ->
                    item.toUi(isActive = sourceKey(item.bhEvent) == activeKey)
                }
        }.stateIn(scope, SharingStarted.WhileSubscribed(0, replayExpirationMillis = 0), emptyList())

    internal fun bloodhoundItemUi(uid: String): Flow<Utils.BloodhoundItem?> =
        combine(bloodhoundMap, _activeKey) { map, activeKey ->
            map[uid]?.let { it.toUi(isActive = sourceKey(it.bhEvent) == activeKey) }
        }.distinctUntilChanged()

    // UI adapter, BloodhoundItem used by Main Screen button and Complication
    val activeBhItemUi: StateFlow<Utils.BloodhoundItem?> =
        combine(_activeKey, bloodhoundStore.allItems) { key, items ->
            key?.let(items::get)?.toUi(isActive = true)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val activeBhItemMap: StateFlow<BloodhoundItem?> =
        combine(_activeKey, bloodhoundStore.allItems) { key, items ->
            key?.let(items::get)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun Double.round(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToInt() / factor
    }

    // ---- API ----
    fun updateBloodhoundItemVector(uid: String, vector: TargetVector) {
        updateBloodhound(uid) { it.copy(vector = vector) }
    }

    fun upsertFromEvent(e: BloodhoundEvent): Boolean = upsertEvent(e, coalesce = false).applied

    private fun upsertEvent(e: BloodhoundEvent, coalesce: Boolean): SourceCotUpsertResult = synchronized(bloodhoundStateLock) {
        val key = sourceKey(e)
        val current = bloodhoundStore.item(key)
        val updated = if (current == null) {
            BloodhoundItem(e)
        } else {
            // Preserve state; refresh event payload for this source-owned copy.
            current.copy(
                bhEvent = e.copy(tracksUserPosition = e.tracksUserPosition || current.bhEvent.tracksUserPosition),
                target = BloodhoundItem.Target(e.mapItem),
                origin = BloodhoundItem.Origin(e)
            )
        }
        val result = bloodhoundStore.upsertWithResult(updated, coalesce = coalesce)
        val projected = !bloodhoundStore.hasPendingProjection(key) && !result.skipped
        if (projected) publishVisibleBloodhounds()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(
            TAG,
            "Map state bloodhound upsert uid=${e.uid} sourceServerId=${e.sourceServerId} " +
                "eventTime=${e.mapItem.cotEvent.time} receivedAt=${e.receivedAt} " +
                "applied=${result.applied} visibleSourceId=${bloodhoundStore.visibleKey(e.uid)?.sourceId} " +
                "channelOwnership=unavailable"
        )

        if (result.applied && projected) reconcileActiveTrackingSource(e.uid)
        result
    }

    suspend fun storeActiveBloodhoundItems(list: List<BloodhoundItem>) {
        // TODO: Convert BloodHoundMapItem to Json string and store json string.
        dataStore.edit { prefs ->
            prefs[ACTIVE_BLOOD_HOUND] = list.toString()
        }
    }
    val getLastActiveBloodhound: Flow<String> = dataStore.data.map { prefs -> prefs[ACTIVE_BLOOD_HOUND] ?: "" }

    private val _requestActivateBloodhound = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val requestActivateBloodhound = _requestActivateBloodhound.asSharedFlow()

    private val _requestAcknowledgement = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val requestAcknowledgement = _requestAcknowledgement.asSharedFlow()

    private val _requestInPosition = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val requestInPosition = _requestInPosition.asSharedFlow()

    fun requestInPosition(uid: String) {
        _requestInPosition.tryEmit(uid)
    }

    fun requestActivateBloodhound(uid: String) {
        if (isUidValid(uid)) _requestActivateBloodhound.tryEmit(uid)
    }

    fun activate(uid: String): Pair<ActivationResult, BloodhoundItem?> =
        synchronized(bloodhoundStateLock) {
            val selectedKey = visibleKey(uid)
                ?: return@synchronized ActivationResult.NO_CHANGE to null
            val currentItem = bloodhoundStore.item(selectedKey)
                ?: return@synchronized ActivationResult.NO_CHANGE to null
            if (currentItem.bhEvent.remoteAlert?.hasLocation == false) {
                return@synchronized ActivationResult.NO_CHANGE to currentItem
            }
            val currentActiveKey = _activeKey.value
            val result = when {
                currentItem.state.acknowledged -> ActivationResult.SWITCH_ONLY
                else -> ActivationResult.FIRST_ACK
            }

            bloodhoundStore.transformAll { key, value ->
                if (key == selectedKey) {
                    value.copy(state = value.state.copy(active = true, acknowledged = true))
                } else {
                    value.copy(state = value.state.copy(active = false))
                }
            }
            publishVisibleBloodhounds()
            setActiveKey(selectedKey)
            org.greenrobot.eventbus.EventBus.getDefault().post(com.aitech.weartak_civ.events.InteractionPliRefresh(
                com.aitech.weartak_civ.events.PliRefreshReason.NAVIGATION_STARTED))

            val updatedItem = bloodhoundStore.item(selectedKey) ?: currentItem
            val normalizedResult = if (
                currentActiveKey == selectedKey && currentItem.state.acknowledged
            ) {
                ActivationResult.NO_CHANGE
            } else {
                result
            }
            normalizedResult to updatedItem
        }

    fun activeTrackingItem(): BloodhoundItem? = synchronized(bloodhoundStateLock) {
        _activeKey.value?.let(bloodhoundStore::item)
    }

    fun activateCompanionTarget(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        hae: Double
    ): Boolean = synchronized(bloodhoundStateLock) {
        if (uid.isBlank() || !lat.isFinite() || !lon.isFinite() || !hae.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0
        ) {
            Log.w(TAG, "Companion tracking rejected: invalid target")
            return@synchronized false
        }
        if (visibleKey(uid) == null) {
            val time = System.currentTimeMillis()
            val marker = CotMarkerEvent(
                version = "2.0", uid = uid, type = "b-m-p-s-m", how = "h-g-i-g-o",
                time = time, start = time, stale = time + 300_000L, access = null,
                point = CotPoint(lat, lon, hae, 999999.0, 999999.0),
                detail = CotMarkerDetail(callsign, null, null, null, null, null)
            )
            upsertFromEvent(
                BloodhoundEvent(
                    mapItem = CotMarkerMapItem(marker), server = "Companion ATAK",
                    timeIssued = time, timeCompleted = null, sourceServerId = COMPANION_BLOODHOUND_SOURCE_ID
                )
            )
        } else {
            val key = visibleKey(uid)
            val item = key?.let(bloodhoundStore::item)
            if (key?.sourceId == COMPANION_BLOODHOUND_SOURCE_ID && item != null) {
                refreshCompanionTarget(item, callsign, lat, lon, hae)
            }
        }
        // Companion tracking is navigation, not an assignment acknowledgement or chat request.
        activate(uid)
        _activeUid.value == uid
    }

    fun stopTracking(uid: String): Boolean = synchronized(bloodhoundStateLock) {
        val key = _activeKey.value?.takeIf { it.cotUid == uid }
            ?: return@synchronized false
        bloodhoundStore.transformAll { candidate, item ->
            if (candidate == key) item.copy(state = item.state.copy(active = false)) else item
        }
        publishVisibleBloodhounds()
        setActiveKey(null)
        true
    }

    fun updateCompanionTarget(uid: String, callsign: String, lat: Double, lon: Double, hae: Double): Boolean =
        synchronized(bloodhoundStateLock) {
            if (_activeUid.value != uid || !lat.isFinite() || !lon.isFinite() || !hae.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0
            ) {
                Log.w(TAG, "Companion tracking update rejected: inactive or invalid target")
                return@synchronized false
            }
            val key = _activeKey.value ?: return@synchronized false
            if (key.sourceId != COMPANION_BLOODHOUND_SOURCE_ID) return@synchronized true
            val item = bloodhoundStore.item(key) ?: return@synchronized false
            refreshCompanionTarget(item, callsign, lat, lon, hae)
            true
        }

    private fun refreshCompanionTarget(item: BloodhoundItem, callsign: String, lat: Double, lon: Double, hae: Double) {
        val old = item.bhEvent.mapItem.cotEvent
        val time = maxOf(System.currentTimeMillis(), old.time + 1L)
        val marker = old.copy(
            time = time, start = time, stale = time + 300_000L,
            point = old.point.copy(lat = lat, lon = lon, hae = hae),
            detail = old.detail.copy(markerCallsign = callsign)
        )
        upsertFromEvent(item.bhEvent.copy(mapItem = CotMarkerMapItem(marker), receivedAt = time))
    }

    private fun reconcileActiveTrackingSource(uid: String) {
        val activeKey = _activeKey.value ?: return
        if (activeKey.cotUid != uid || visibleKey(uid) == activeKey) return
        if (activeKey.sourceId == COMPANION_BLOODHOUND_SOURCE_ID &&
            visibleKey(uid) != null && _bloodhoundMap.value[uid]?.bhEvent?.remoteAlert?.hasLocation != false
        ) {
            // Replace a companion-only snapshot with the real incoming target without ending tracking.
            activate(uid)
        } else {
            setActiveKey(null)
        }
    }

    fun setInPos(uid: String): BloodhoundItem? = synchronized(bloodhoundStateLock) {
        if (_routeFollow.value?.let { it.uid == uid && !it.navigation.completed } == true) {
            return@synchronized null
        }
        val activeKey = _activeKey.value
        val key = activeKey?.takeIf { it.cotUid == uid }
            ?: visibleKey(uid)
            ?: return@synchronized null
        val item = bloodhoundStore.item(key) ?: return@synchronized null
        if (item.bhEvent.remoteAlert != null) {
            if (activeKey == key) setActiveKey(null)
            return@synchronized item
        }
        bloodhoundStore.remove(key)
        publishVisibleBloodhounds()
        if (activeKey == key) setActiveKey(null)
        item
    }

    suspend fun removeFromMenu(uid: String): BloodhoundItem? = emergencyMutex.withLock {
        val selected = synchronized(bloodhoundStateLock) {
            visibleKey(uid)?.let { bloodhoundStore.item(it) }
        } ?: return@withLock null
        if (selected.bhEvent.remoteAlert != null) {
            loadEmergencyLedgers()
            persistDismissal(uid, selected.bhEvent.mapItem.cotEvent.time)
        }
        synchronized(bloodhoundStateLock) {
        val key = visibleKey(uid) ?: return@synchronized null
        val item = bloodhoundStore.item(key) ?: return@synchronized null
        // Local dismissal never emits a sender cancellation or removes ordinary targets.
        val removedKeys = if (item.bhEvent.remoteAlert != null) {
            bloodhoundStore.snapshot().filter { (candidate, candidateItem) ->
                candidate.cotUid == uid && candidateItem.bhEvent.remoteAlert != null
            }.keys
        } else {
            setOf(key)
        }
        removedKeys.forEach { removedKey ->
            bloodhoundStore.remove(removedKey)
            alertExpiryJobs.remove(removedKey)?.cancel()
        }
        publishVisibleBloodhounds()
        if (_activeKey.value in removedKeys) setActiveKey(null)
        item
        }
    }

    private suspend fun loadEmergencyLedgers() {
        if (cancellationsLoaded) return
        val prefs = dataStore.data.first()
        val cancelled = JSONObject(prefs[ALERT_CANCELLATIONS] ?: "{}")
        cancelled.keys().forEach { uid -> cancellationTimes[uid] = cancelled.getLong(uid) }
        val dismissed = JSONObject(prefs[ALERT_DISMISSALS] ?: "{}")
        dismissed.keys().forEach { uid -> dismissalTimes[uid] = dismissed.getLong(uid) }
        cancellationsLoaded = true
    }

    private suspend fun persistDismissal(uid: String, time: Long) {
        persistDismissals(mapOf(uid to time))
    }

    private suspend fun persistDismissals(times: Map<String, Long>) {
        val latest = times.mapValues { (uid, time) ->
            maxOf(dismissalTimes[uid] ?: Long.MIN_VALUE, time)
        }
        dataStore.edit { prefs ->
            val dismissed = JSONObject(prefs[ALERT_DISMISSALS] ?: "{}")
            latest.forEach { (uid, time) -> dismissed.put(uid, time) }
            prefs[ALERT_DISMISSALS] = dismissed.toString()
        }
        dismissalTimes.putAll(latest)
    }

    suspend fun removeAllFromMenu() = emergencyMutex.withLock {
        val selected = synchronized(bloodhoundStateLock) {
            bloodhoundStore.snapshot()
        }
        val alerts = selected.values.filter { it.bhEvent.remoteAlert != null }
            .groupBy { it.uid }
            .mapValues { (_, items) -> items.maxOf { it.bhEvent.mapItem.cotEvent.time } }
        if (alerts.isNotEmpty()) {
            loadEmergencyLedgers()
            persistDismissals(alerts)
        }
        synchronized(bloodhoundStateLock) {
            stopRoute()
            selected.forEach { (key, item) ->
                if (bloodhoundStore.item(key) == item) {
                    bloodhoundStore.remove(key)
                    alertExpiryJobs.remove(key)?.cancel()
                    if (_activeKey.value == key) setActiveKey(null)
                }
            }
            publishVisibleBloodhounds()
        }
        Log.i(TAG, "Bloodhound Remove All removed snapshot entries=${selected.size} dismissedAlerts=${alerts.size}")
    }

    /** Removes every source copy of [uid], e.g. when the matching map item is deleted. */
    fun removeTarget(uid: String): List<BloodhoundItem> = synchronized(bloodhoundStateLock) {
        val removed = bloodhoundStore.removeUid(uid)
        if (removed.isEmpty()) return@synchronized removed
        alertExpiryJobs.keys.filter { it.cotUid == uid }.toList().forEach {
            alertExpiryJobs.remove(it)?.cancel()
        }
        publishVisibleBloodhounds()
        if (_activeKey.value?.cotUid == uid) setActiveKey(null)
        removed
    }

    fun isUidValid(uid: String) = _bloodhoundMap.value.containsKey(uid)

    fun remarkFromHoundmaster(uid: String) =
        _bloodhoundMap.value[uid]?.containsHoundmasterRemark ?: false

    fun updateBloodhoundCompassNeedle(uid: String, needleDeg: Double) {
        updateBloodhoundCompassNeedles(mapOf(uid to needleDeg))
    }

    fun updateBloodhoundCompassNeedles(needles: Map<String, Double>) {
        synchronized(bloodhoundStateLock) {
            val bySource = needles.mapNotNull { (uid, angle) ->
                visibleKey(uid)?.let { it to angle }
            }.toMap()
            if (bySource.isEmpty()) return
            val changed = bloodhoundStore.transformAll { key, item ->
                val angle = bySource[key]
                if (angle == null || item.compass.targetBearingDeg == angle) item
                else item.copy(compass = item.compass.copy(targetBearingDeg = angle))
            }
            if (changed) publishVisibleBloodhounds()
        }
    }

    fun clearSource(serverId: String): List<BloodhoundItem> = synchronized(bloodhoundStateLock) {
        val activeKey = _activeKey.value
        val activeWasRemoved = activeKey?.sourceId in listOf(serverId, "emergency:$serverId")
        val removed = bloodhoundStore.removeSource(serverId) + bloodhoundStore.removeSource("emergency:$serverId")
        alertExpiryJobs.keys.filter { it.sourceId == "emergency:$serverId" }.toList().forEach {
            alertExpiryJobs.remove(it)?.cancel()
        }
        publishVisibleBloodhounds()
        if (_bloodhoundEvent.value?.event?.sourceServerId == serverId) {
            _bloodhoundEvent.value = null
        }
        if (activeWasRemoved) {
            setActiveKey(null)
        }
        removed
    }

    private fun setActiveKey(key: SourceCotKey?) {
        val route = _routeFollow.value
        if (route != null && !route.navigation.completed && key?.cotUid != route.uid) {
            dismissPhoneRoute(route)
            setRouteSession(null)
            bloodhoundStore.removeUid(route.uid)
            publishVisibleBloodhounds()
        }
        _activeKey.value = key
        _activeUid.value = key?.cotUid
    }

    private fun publishVisibleBloodhounds() {
        val alerts = bloodhoundStore.allItems.value.values.filter { it.bhEvent.remoteAlert != null }
            .groupBy(BloodhoundItem::uid).mapValues { (_, items) ->
                items.maxWith(compareBy<BloodhoundItem> { it.bhEvent.mapItem.cotEvent.time }.thenBy { it.bhEvent.receivedAt })
            }
        _remoteAlerts.value = alerts
        _bloodhoundMap.value = bloodhoundStore.visibleItems.value.associateBy(BloodhoundItem::uid) + alerts
    }

    private fun visibleKey(uid: String): SourceCotKey? =
        _remoteAlerts.value[uid]?.let { sourceKey(it.bhEvent) } ?: bloodhoundStore.visibleKey(uid)

    private fun sourceKey(event: BloodhoundEvent): SourceCotKey = SourceCotKey(
        sourceId = if (event.remoteAlert != null) "emergency:${event.sourceServerId}"
            else event.sourceServerId ?: NON_TAK_BLOODHOUND_SOURCE_ID,
        cotUid = event.uid
    )



    val getEnableBloodhoundService: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ENABLE] ?: false
    }

    suspend fun storeEnableBloodhoundService(enable: Boolean) {
        dataStore.edit { prefs ->
            prefs[ENABLE] = enable
        }
    }

    private val _bloodhoundEvent = MutableStateFlow<BloodhoundUpdate?>(null)
    val bloodhoundEvent: StateFlow<BloodhoundUpdate?> = _bloodhoundEvent
    private val pendingEventKeys = mutableSetOf<SourceCotKey>()

    suspend fun updateBloodhoundEvent(event: BloodhoundEvent, coalesce: Boolean = false) {
        synchronized(bloodhoundStateLock) {
            val isNewUid = !isUidValid(event.uid)
            val visibleTime = _bloodhoundMap.value[event.uid]?.bhEvent?.mapItem?.cotEvent?.time
            val previouslyFromHoundmaster = if (isNewUid) {
                false
            } else {
                remarkFromHoundmaster(event.uid)
            }
            val result = upsertEvent(event, coalesce)
            if (!result.applied || result.skipped) return@synchronized
            if (coalesce && bloodhoundStore.hasPendingProjection(result.key)) {
                pendingEventKeys.add(result.key)
                return@synchronized
            }
            pendingEventKeys.remove(result.key)
            if (visibleKey(event.uid) != sourceKey(event)) {
                return@synchronized
            }
            if (visibleTime != null && event.mapItem.cotEvent.time <= visibleTime) return@synchronized
            _bloodhoundEvent.value = BloodhoundUpdate(
                event = event,
                isNewUid = isNewUid,
                becameHoundmaster = event.remark == "#houndmaster" && !previouslyFromHoundmaster
            )
        }

    }

    suspend fun applyReceivedMarkerEvent(
        event: CotMarkerEvent,
        server: String,
        sourceServerId: String?,
        receivedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (event.stale <= now) {
            removeMarkerCopy(event.uid, sourceServerId, event.time, receivedAt)
        } else {
            val mapItem = CotMarkerMapItem(event)
            updateBloodhoundEvent(BloodhoundEvent(
                mapItem = mapItem, server = server, timeIssued = mapItem.link?.productionTime,
                timeCompleted = null, remark = event.detail.remarks.orEmpty(),
                sourceServerId = sourceServerId, receivedAt = receivedAt
            ), coalesce = event.detail.canCoalesceTrack(event.type, event.how, sourceServerId))
        }
    }

    fun removeMarkerCopy(uid: String, sourceId: String?, time: Long, receivedAt: Long): Boolean =
        synchronized(bloodhoundStateLock) {
            val key = SourceCotKey(sourceId ?: NON_TAK_BLOODHOUND_SOURCE_ID, uid)
            val removed = bloodhoundStore.removeIfNotOlder(key, time, receivedAt)
            if (removed != null) {
                alertExpiryJobs.remove(key)?.cancel()
                if (_activeKey.value == key) setActiveKey(null)
                if (_bloodhoundEvent.value?.event?.let { sourceKey(it) == key } == true) {
                    _bloodhoundEvent.value = null
                }
                publishVisibleBloodhounds()
            }
            removed != null
        }

    fun removeLocalMarkerCopy(uid: String): Boolean = synchronized(bloodhoundStateLock) {
        val local = bloodhoundStore.item(SourceCotKey(NON_TAK_BLOODHOUND_SOURCE_ID, uid))
            ?: return@synchronized false
        removeMarkerCopy(uid, null, local.bhEvent.mapItem.cotEvent.time, System.currentTimeMillis())
    }

    init {
        projectionScope.launch {
            bloodhoundStore.publicationVersion.collect {
                synchronized(bloodhoundStateLock) {
                    publishVisibleBloodhounds()
                    val completed = pendingEventKeys.filterNot(bloodhoundStore::hasPendingProjection)
                    pendingEventKeys.removeAll(completed.toSet())
                    completed.map { it.cotUid }.distinct().forEach(::reconcileActiveTrackingSource)
                    completed.mapNotNull { key ->
                        _bloodhoundMap.value[key.cotUid]?.takeIf { sourceKey(it.bhEvent) == key }
                    }.lastOrNull()?.let { item ->
                        _bloodhoundEvent.value = BloodhoundUpdate(item.bhEvent, false, false)
                    }
                }
            }
        }
    }

    companion object {
        private val ENABLE = booleanPreferencesKey("enable_bloodhound_service")
        private val ACTIVE_BLOOD_HOUND = stringPreferencesKey("active_bloodhound")
        private val ALERT_CANCELLATIONS = stringPreferencesKey("remote_emergency_cancellations")
        private val ALERT_DISMISSALS = stringPreferencesKey("remote_emergency_dismissals")
    }
}
