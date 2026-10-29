/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.cot.canCoalesceTrack
import com.aitech.weartak_civ.cot.isPassiveTrack
import com.aitech.weartak_civ.service.AppDiagnostics
import com.aitech.weartak_civ.service.ProcessingStage

import android.content.Context
import android.annotation.SuppressLint
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.location.Location
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.Utils
import kotlinx.coroutines.flow.first
import com.aitech.weartak_civ.DEFAULT_BIRTH_YEAR
import com.aitech.weartak_civ.DEFAULT_ALLERGY_LIST
import com.aitech.weartak_civ.DEFAULT_BATT_ALERT_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_BATT_WARNING_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_BLOOD_TYPE
import com.aitech.weartak_civ.DEFAULT_MULTICAST_ADDRESS
import com.aitech.weartak_civ.DEFAULT_MULTICAST_PORT
import com.aitech.weartak_civ.DEFAULT_MULTICAST_PROTOCOL
import com.aitech.weartak_civ.DEFAULT_ON_FOOT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_CONSTANT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_ENABLE_ACTIVITY_RECOGNITION_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION
import com.aitech.weartak_civ.DEFAULT_ENABLE_AUDIO_CAP_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_BETA_FEATURES
import com.aitech.weartak_civ.DEFAULT_ENABLE_BATDOK_COT
import com.aitech.weartak_civ.DEFAULT_ENABLE_BATTERY_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLE_BEACON_SERVICE
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
import com.aitech.weartak_civ.DEFAULT_CRM_POOR_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_CRM_MODERATE_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLOODHOUND_COMPASS_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
import com.aitech.weartak_civ.DEFAULT_ENABLE_CHAT_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_CHAT_COMMANDS
import com.aitech.weartak_civ.command.ChatCommandAuthorizer
import com.aitech.weartak_civ.DEFAULT_ENABLE_COMPANION_APP_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_CRM_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_DITTO_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_DYNAMIC_REPORTING
import com.aitech.weartak_civ.DEFAULT_ENABLE_ESRI_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_GEOFENCE_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_HAIL_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_HEART_RATE_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_LOCATION_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_MULTICAST_ON_WIFI
import com.aitech.weartak_civ.DEFAULT_ENABLE_ONNX_GUNSHOT
import com.aitech.weartak_civ.DEFAULT_ENABLE_ONNX_UAS
import com.aitech.weartak_civ.DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.DEFAULT_ENABLE_PHYSIOLOGICAL_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_USER_WARNING
import com.aitech.weartak_civ.DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES
import com.aitech.weartak_civ.DEFAULT_ENABLE_SITX_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_SKIN_TEMPERATURE_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_SMS_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_TABLET_COMMAND_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_TAK_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_WIFI_BATTERY_OPTIMIZATION
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_LENGTH_SECS
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_LENGTH_SECS
import com.aitech.weartak_civ.DEFAULT_HEIGHT_FEET
import com.aitech.weartak_civ.DEFAULT_HEIGHT_INCHES
import com.aitech.weartak_civ.DEFAULT_HIGH_HEART_RATE_ALERT_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_HIGH_HEART_RATE_ALERT_THRESHOLD_NUMBER_SECONDS
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_LOADOUT_WEIGHT
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_RESTING_HR
import com.aitech.weartak_civ.DEFAULT_SELECTED_PHYSIO_METRIC
import com.aitech.weartak_civ.DEFAULT_SEX
import com.aitech.weartak_civ.DEFAULT_STATIONARY_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_STRIDE_LENGTH
import com.aitech.weartak_civ.DEFAULT_UNIFORM_PANTS_LENGTH
import com.aitech.weartak_civ.DEFAULT_UNIFORM_WAIST_SIZE
import com.aitech.weartak_civ.DEFAULT_USER_TYPE
import com.aitech.weartak_civ.DEFAULT_VEHICLE_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_WEIGHT
import com.aitech.weartak_civ.DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
import com.aitech.weartak_civ.service.ChatService
import com.aitech.weartak_civ.service.ReactiveServiceManager
import com.aitech.weartak_civ.service.BatteryPowerObserver.DockType
import com.aitech.weartak_civ.service.CompanionTransportDebounce
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.emitAll
import java.time.Instant
import kotlinx.coroutines.flow.stateIn
import com.aitech.weartak_civ.Utils.SettingsUtils.CategoryRolesEntry
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.HailService
import com.aitech.weartak_civ.service.HailService.HailLicenseStates
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.secure.takserver.TakServerSecretsRepository
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import com.aitech.weartak_civ.data.model.TakServerChannels
import com.aitech.weartak_civ.data.takserver.TakServerEndpointIdentity
import com.aitech.weartak_civ.data.takserver.TakServerId
import com.aitech.weartak_civ.data.takserver.TakServerManagedReconciler
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import com.aitech.weartak_civ.data.model.TakMission
import com.aitech.weartak_civ.data.model.TakMissionState
import com.aitech.weartak_civ.data.model.TakMissionStatus
import com.aitech.weartak_civ.data.model.TakServerMissions
import java.time.Year
import java.time.temporal.ChronoUnit
import java.util.EnumMap
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import com.aitech.weartak_civ.DEFAULT_ENABLE_IMMERSION_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.cot.CotEventTime
import com.aitech.weartak_civ.cot.CotEventTimeFactory
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotTimeOffset
import com.aitech.weartak_civ.cot.CotUserPositionEvent
import com.aitech.weartak_civ.ipc.AtosCotPacket
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.collections.plus
import com.aitech.weartak_civ.service.SitxTakService
import kotlinx.serialization.builtins.ListSerializer
import com.aitech.weartak_civ.DEFAULT_ENABLE_IMMERSION_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.data.model.PressureReading
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.model.payloads.Spo2TierEventPayload
import com.aitech.weartak_civ.manager.NetworkManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.PliRefreshReason


private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "data",
    produceMigrations = {
        listOf(TakMasterPreferenceMigration(), EnvironmentalAlertsPreferenceMigration())
    }
)
private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private const val NON_TAK_COT_SOURCE_ID = "non-tak"
internal const val COMPANION_USER_SOURCE_ID = "companion-phone-users"

// Identifies which PDL the user selected
enum class DittoPdlSource {
    COMMUNITY,
    CUSTOM
}

private const val DEFAULT_COMMUNITY_PDL =
    BuildConfig.DITTO_COMMUNITY_IDENTITY

class DataRepository(
    private val context: Context,
    private val projectionScope: CoroutineScope = repoScope,
) {
    internal val locationMotionNanos = MutableStateFlow(0L)

    internal fun noteLocationMotion() {
        locationMotionNanos.value = android.os.SystemClock.elapsedRealtimeNanos()
    }

    internal val stationaryMotion = com.aitech.weartak_civ.service.StationaryMotionPolicy()
    internal val passiveSaDemand = PassiveSaDemand()
    internal val headingComplications = HeadingComplicationDemand(
        context.getSharedPreferences("heading-complications", Context.MODE_PRIVATE)
    )
    internal val chatCommandHandler by lazy {
        com.aitech.weartak_civ.service.ChatCommandRuntime.build(context, this)
    }
    internal val dittoPointLedger by lazy {
        com.aitech.weartak_civ.cot.DittoPointLedger(
            context.getSharedPreferences("ditto-local-points-v1", Context.MODE_PRIVATE)
        )
    }
    internal val dittoPointIngress by lazy {
        com.aitech.weartak_civ.cot.DittoPointIngress(
            context.getSharedPreferences("ditto-point-watermarks-v1", Context.MODE_PRIVATE)
        )
    }
    data class Spo2DebugState(
        val activeBaselinePct: Double,
        val activeBaselineFamily: String,
        val activeAltitudeBucket: Int,
        val activeBaselineProvisional: Boolean,
        val ringSize: Int,
        val lastInvalidReason: String?,
        val lastStateUpdateTs: Long,
        val lastResetReason: String?,
    )

    private val TAG = "WearTAK-" + DataRepository::class.java.simpleName

    // Saves the Custom PDL entered by the user
    private val DITTO_CUSTOM_PDL =
        stringPreferencesKey("ditto_custom_pdl")

    // Saves whether the user selected Community or Custom
    private val DITTO_PDL_SOURCE =
        stringPreferencesKey("ditto_pdl_source")

    @androidx.annotation.VisibleForTesting
    internal var takServerSecretsOverride: TakServerSecretsRepository? = null

    private val takServerSecretsRepository: TakServerSecretsRepository
        get() = takServerSecretsOverride ?: (context.applicationContext as WearTakApplication).takServerRepo

    val passiveDataEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PASSIVE_DATA_ENABLED] ?: false
    }

    fun legacyDataStore(): DataStore<Preferences> = context.dataStore

    suspend fun setPassiveDataEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PASSIVE_DATA_ENABLED] = enabled
        }
    }

    data class WearTakInfo(
        val versionNumber: String,
        val versionCode: Int,
        val gitBranch: String,
        val gitCommit: String,
        val buildType: String,
    )

    val wearTakInfo = WearTakInfo(
        versionNumber = BuildConfig.VERSION_NAME,
        versionCode   = BuildConfig.VERSION_CODE,
        gitBranch     = BuildConfig.GIT_BRANCH,
        gitCommit     = BuildConfig.GIT_COMMIT_SHORT,
        buildType     = BuildConfig.BUILD_TYPE,
    )

    data class DeviceInfo(
        val wearTakUid: String,
        val callsign: String,
    )

    private val _deviceInfo = MutableStateFlow(
        DeviceInfo(
            wearTakUid = MainActivity.device_uid,
            callsign = "",
            )
    )

    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    private val _takChannels = MutableStateFlow<List<TakServerChannels>>(emptyList())
    val takChannels: StateFlow<List<TakServerChannels>> = _takChannels.asStateFlow()

    // Store the missions for each connected TAK Server
    // Each server has its own mission list and mission states
    private val _takMissions =
        MutableStateFlow<List<TakServerMissions>>(emptyList())

    // Expose mission updates to the rest of the app
    // Keep the underlying list editable only inside the repository
    val takMissions: StateFlow<List<TakServerMissions>> =
        _takMissions.asStateFlow()

    // Keep source ownership for mission bookkeeping and fallback; project versioned CoT by UID.
    internal val routePersistence = RoutePersistence(
        context.getSharedPreferences("received-routes-v1", Context.MODE_PRIVATE)
    )
    private val restoredRoutes = routePersistence.load(System.currentTimeMillis())
    private val _takRoutes = MutableStateFlow(restoredRoutes.routes.associateBy { it.serverId to it.uid })
    private val routeRemovals = object : LinkedHashMap<Pair<String, String>, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Long>?) = size > 2048
    }.apply { putAll(restoredRoutes.removals) }
    private var routeExpiryJob: Job? = null
    internal val takRouteCopies: List<TakRoute> get() = _takRoutes.value.values.toList()
    internal val visibleTakRoutes: List<TakRoute> get() = projectRoutes(takRouteCopies)

    val takRoutes: StateFlow<List<TakRoute>> =
        _takRoutes
            .map { routes -> projectRoutes(routes.values.toList()) }
            .stateIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                started = SharingStarted.Eagerly,
                initialValue = projectRoutes(restoredRoutes.routes)
            )

    private fun projectRoutes(routes: List<TakRoute>): List<TakRoute> {
        val versioned = routes.filter { it.eventTime != null }.groupBy { it.uid }
            .mapValues { (_, copies) -> copies.maxBy { requireNotNull(it.eventTime) } }
        return routes.filter { it.eventTime == null || versioned[it.uid] == it }
    }

    init {
        synchronized(routeRemovals) { scheduleRouteExpiry() }
    }

    private fun publishRoutes(routes: Map<Pair<String, String>, TakRoute>) {
        // Commit first: a process death after publishing must not restore a deleted copy.
        routePersistence.save(routes.values, routeRemovals)
        _takRoutes.value = routes
        scheduleRouteExpiry()
    }

    private fun scheduleRouteExpiry() {
        routeExpiryJob?.cancel()
        val stale = _takRoutes.value.values.mapNotNull { it.staleTime }.minOrNull() ?: return
        routeExpiryJob = repoScope.launch {
            delay((stale - System.currentTimeMillis()).coerceAtLeast(0))
            pruneTakRoutes()
        }
    }

    internal fun pruneTakRoutes(now: Long = System.currentTimeMillis()) = synchronized(routeRemovals) {
        publishRoutes(_takRoutes.value.filterValues { it.staleTime == null || it.staleTime > now })
    }

    // Add a new route or update an existing route
    // A route is identified by its server ID and UID
    fun upsertTakRoute(route: TakRoute) = synchronized(routeRemovals) {
        if (route.staleTime?.let { it <= System.currentTimeMillis() } == true) return@synchronized
        if (route.eventTime == null) {
            Log.d(TAG, "Route has no event version; cannot replace versioned state uid=${route.uid}")
        }

        // Build a unique key for this route on its TAK Server
        val key = route.serverId to route.uid
        if (routeRemovals[key]?.let { (route.eventTime ?: 0) <= it } == true) return@synchronized

        // Update the route map with the latest route data
        // An existing route with the same key will be replaced
        // Other stored routes will remain unchanged
        val previous = _takRoutes.value[key]
        if (previous?.eventTime != null &&
            (route.eventTime == null || route.eventTime < previous.eventTime)) return@synchronized
        publishRoutes(_takRoutes.value + (key to route))

        // Log the stored route information for debugging
        Log.d(
            TAG,
            "Route stored uid=${route.uid} name=${route.name} " +
                    "serverId=${route.serverId} points=${route.points.size}"
        )
    }

    fun removeTakRoute(serverId: String, routeUid: String) = synchronized(routeRemovals) {
        publishRoutes(_takRoutes.value - (serverId to routeUid))
    }

    fun removeRouteFromWatch(route: TakRoute, bloodhoundRepository: BloodhoundRepository) {
        if (bloodhoundRepository.routeFollow.value?.matches(route) == true) bloodhoundRepository.stopRoute()
        synchronized(routeRemovals) {
            publishRoutes(_takRoutes.value.filterValues { candidate ->
                !(candidate.uid == route.uid && (candidate.serverId == route.serverId ||
                    (route.eventTime != null && candidate.eventTime != null)))
            })
        }
        bloodhoundRepository.reconcileRoutes(takRouteCopies)
    }

    fun clearRoutesFromWatch(bloodhoundRepository: BloodhoundRepository) {
        bloodhoundRepository.stopRoute()
        synchronized(routeRemovals) { publishRoutes(emptyMap()) }
    }

    // Remove routes that belong to a specific mission on a specific server
    // Keep routes from other missions and TAK Servers
    fun removeTakRoutesForMission(serverId: String, missionName: String) {

        // Filter the route map to remove matching mission routes
        synchronized(routeRemovals) {
            publishRoutes(_takRoutes.value.filterValues { route ->

                // Remove only routes matching both the server ID and mission name
                !(route.serverId == serverId && route.missionName == missionName)
            })
        }
    }

    class TakServerConfigurationRefresh internal constructor() {
        val result = CompletableDeferred<Result<Unit>>()
    }

    private val takServerConfigurationRefreshChannel =
        Channel<TakServerConfigurationRefresh>(Channel.UNLIMITED)
    val takServerConfigurationRefreshes: Flow<TakServerConfigurationRefresh> =
        takServerConfigurationRefreshChannel.receiveAsFlow()

    fun updateTakServerChannels(server: TakServerChannels) {
        _takChannels.update { current ->
            val existingIndex = current.indexOfFirst { it.serverId == server.serverId }
            if (existingIndex == -1) {
                current + server
            } else {
                current.toMutableList().apply { this[existingIndex] = server }
            }
        }
    }

    fun removeTakServerChannels(serverId: String) {
        _takChannels.update { current ->
            current.filterNot { it.serverId == serverId }
        }
    }

    // Mark a TAK Server's mission list as loading
    // Keep any missions already stored while the new request runs
    fun updateTakMissionLoading(
        serverId: String,
        serverName: String,
        serverAddress: String
    ) {

        // Update the mission data for the requested server
        _takMissions.update { current ->

            // Find any existing mission data for this server
            val previous = current.firstOrNull { it.serverId == serverId }

            // Create an updated server entry with a loading status
            // Keep the previous mission list so it does not disappear during refresh
            val updated = TakServerMissions(
                serverId = serverId,
                serverName = serverName,
                serverAddress = serverAddress,
                missions = previous?.missions.orEmpty(),
                status = TakMissionStatus.LOADING
            )

            // Replace this server's old entry with the loading entry
            // Leave all other servers unchanged
            current.filterNot { it.serverId == serverId } + updated
        }
    }

    // Save the latest missions returned by a TAK Server
    // Keep existing mission states when the same missions are returned again
    fun updateTakMissions(
        serverId: String,
        serverName: String,
        serverAddress: String,
        missions: List<TakMission>
    ) {

        // Update only the mission list belonging to this server
        _takMissions.update { current ->

            // Find previously stored missions for this server
            val previousServer =
                current.firstOrNull { it.serverId == serverId }

            if (previousServer != null &&
                previousServer.missions.map { it.mission } == missions
            ) {
                val updated = previousServer.copy(
                    serverName = serverName,
                    serverAddress = serverAddress,
                    status = if (missions.isEmpty()) TakMissionStatus.EMPTY else TakMissionStatus.READY,
                    errorMessage = null
                )
                return@update if (updated == previousServer) current
                    else current.map { if (it.serverId == serverId) updated else it }
            }

            // Build a state entry for every mission returned by the server
            val missionStates = missions.map { mission ->

                // Check whether this mission was already stored
                // Match missions by their names within this server
                val previousState =
                    previousServer
                        ?.missions
                        ?.firstOrNull { it.mission.name == mission.name }

                if (previousState != null) {

                    // Keep the existing mission state
                    // Replace the mission details with the latest server response
                    // This preserves fields such as subscription and sync progress
                    previousState.copy(
                        mission = mission,

                        )
                } else {

                    // Create a new state for a mission that was not stored before
                    // Mark the new mission as ready
                    TakMissionState(
                        mission = mission,
                        status = TakMissionStatus.READY
                    )
                }
            }

            // Create the updated mission entry for this TAK Server
            val updated = TakServerMissions(
                serverId = serverId,
                serverName = serverName,
                serverAddress = serverAddress,
                missions = missionStates,

                // Mark the server as empty when no missions were returned
                // Otherwise mark its mission list as ready
                status = if (missionStates.isEmpty()) {
                    TakMissionStatus.EMPTY
                } else {
                    TakMissionStatus.READY
                }
            )

            // Replace only this server's mission data
            current.filterNot { it.serverId == serverId } + updated
        }
    }

    // Store an error when the mission list fails to load
    // Keep previously loaded missions available in the repository
    fun updateTakMissionError(
        serverId: String,
        serverName: String,
        serverAddress: String,
        message: String
    ) {

        // Update the error state for the requested TAK Server
        _takMissions.update { current ->

            // Find the existing server entry if one is available
            val previous =
                current.firstOrNull { it.serverId == serverId }

            // Create a server entry with the error status and message
            // Preserve any missions that were already loaded
            val updated = TakServerMissions(
                serverId = serverId,
                serverName = serverName,
                serverAddress = serverAddress,
                missions = previous?.missions.orEmpty(),
                status = TakMissionStatus.ERROR,
                errorMessage = message
            )

            // Replace the matching server entry without affecting other servers
            current.filterNot { it.serverId == serverId } + updated
        }
    }

    // Update whether a mission is subscribed or unsubscribed
    // Only change the mission on the matching TAK Server
    fun setMissionSubscribed(
        serverId: String,
        missionName: String,
        subscribed: Boolean
    ) {

        // Go through the stored TAK Servers
        _takMissions.update { servers ->
            servers.map { server ->

                // Skip servers that do not match the requested server ID
                if (server.serverId != serverId) {
                    server
                } else {

                    // Update the mission list for the matching server
                    server.copy(
                        missions = server.missions.map { state ->

                            // Find the mission being subscribed or unsubscribed
                            if (state.mission.name == missionName) {

                                // Save the new subscription value
                                // Reset the mission status to idle
                                // Clear any previous error message
                                state.copy(
                                    subscribed = subscribed,
                                    status = TakMissionStatus.IDLE,
                                    errorMessage = null
                                )
                            } else {

                                // Leave other missions unchanged
                                state
                            }
                        }
                    )
                }
            }
        }
    }

    // Mark a mission as actively syncing
    // Clear its previous error when a new sync starts
    fun setMissionSyncing(
        serverId: String,
        missionName: String
    ) {

        // Find the matching server and mission
        _takMissions.update { servers ->
            servers.map { server ->

                // Do not change mission states on other servers
                if (server.serverId != serverId) {
                    server
                } else {
                    server.copy(
                        missions = server.missions.map { state ->

                            // Update only the requested mission
                            if (state.mission.name == missionName) {

                                // Mark the mission as syncing
                                // Remove any error from an earlier attempt
                                state.copy(
                                    status = TakMissionStatus.SYNCING,
                                    errorMessage = null
                                )
                            } else {
                                state
                            }
                        }
                    )
                }
            }
        }
    }

    // Mark a mission sync as complete
    // Save when the sync finished and which changes were processed
    fun setMissionSyncComplete(
        serverId: String,
        missionName: String,
        processedChangeKeys: Set<String>,
        syncTime: Long
    ) {

        // Update the matching mission on the requested server
        _takMissions.update { servers ->
            servers.map { server ->

                // Leave other TAK Servers unchanged
                if (server.serverId != serverId) {
                    server
                } else {
                    server.copy(
                        missions = server.missions.map { state ->

                            // Find the mission that finished syncing
                            if (state.mission.name == missionName) {

                                // Return the mission to idle after syncing
                                // Record the latest sync time
                                // Keep track of processed changes for future syncs
                                // Clear the previous error message
                                state.copy(
                                    status = TakMissionStatus.IDLE,
                                    lastSyncTime = syncTime,
                                    processedChangeKeys = processedChangeKeys,
                                    errorMessage = null
                                )
                            } else {
                                state
                            }
                        }
                    )
                }
            }
        }
    }

    // Mark a mission sync as failed
    // Save the error so the app can show what went wrong
    fun setMissionSyncError(
        serverId: String,
        missionName: String,
        message: String
    ) {

        // Find the matching server and mission
        _takMissions.update { servers ->
            servers.map { server ->

                // Skip mission lists belonging to other servers
                if (server.serverId != serverId) {
                    server
                } else {
                    server.copy(
                        missions = server.missions.map { state ->

                            // Update only the mission that failed
                            if (state.mission.name == missionName) {

                                // Set the error status
                                // Store the message from the failed sync
                                state.copy(
                                    status = TakMissionStatus.ERROR,
                                    errorMessage = message
                                )
                            } else {
                                state
                            }
                        }
                    )
                }
            }
        }
    }

    // Get all subscribed missions for one TAK Server
    // Only subscribed missions should be included in this result
    fun getSubscribedMissions(
        serverId: String
    ): List<TakMissionState> {

        // Find the matching server
        // Filter its missions to those marked as subscribed
        // Return an empty list if the server is not found
        return takMissions.value
            .firstOrNull { it.serverId == serverId }
            ?.missions
            ?.filter { it.subscribed }
            .orEmpty()
    }

    // Remove a TAK Server's mission data from the repository
    // Leave mission data from other servers untouched
    fun removeTakServerMissions(
        serverId: String
    ) {

        // Remove the server entry matching the given ID
        _takMissions.update { current ->
            current.filterNot {
                it.serverId == serverId
            }
        }
    }

    // Store the information needed to restore a mission subscription
    // Keep the server ID so missions from different servers stay separate
    // Save the last sync time and processed changes for future syncs
    data class PersistedMissionSubscription(
        val serverId: String,
        val missionName: String,
        val lastSyncTime: Long? = null,
        val processedChangeKeys: Set<String> = emptySet()
    )

    // DataStore key used to save mission subscriptions
    private val TAK_MISSION_SUBSCRIPTIONS =
        stringPreferencesKey("tak_mission_subscriptions")

    // Define the list type used when converting saved JSON back into objects
    private val missionSubscriptionListType =
        object : TypeToken<List<PersistedMissionSubscription>>() {}.type

    // Read saved mission subscriptions from DataStore
    // Convert the saved JSON into subscription objects
    val persistedMissionSubscriptions: Flow<List<PersistedMissionSubscription>> =
        context.dataStore.data.map { prefs ->

            // Get the JSON string saved under the mission subscription key
            val json = prefs[TAK_MISSION_SUBSCRIPTIONS]

            // Return an empty list if nothing has been saved
            if (json.isNullOrBlank()) {
                emptyList()
            } else {

                // Try to convert the JSON into saved mission subscriptions
                // Return an empty list if the saved data cannot be parsed
                runCatching {
                    gson.fromJson<List<PersistedMissionSubscription>>(
                        json,
                        missionSubscriptionListType
                    )
                }.getOrDefault(emptyList())
            }
        }

    // Save the full subscription list to DataStore
    // Convert the subscription objects into JSON before storing them
    private suspend fun storeMissionSubscriptions(
        subscriptions: List<PersistedMissionSubscription>
    ) {

        // Update the saved mission subscription value
        context.dataStore.edit { prefs ->
            prefs[TAK_MISSION_SUBSCRIPTIONS] =
                gson.toJson(subscriptions, missionSubscriptionListType)
        }
    }

    // Save a mission subscription so it can be restored later
    // Avoid adding the same mission twice for the same server
    suspend fun persistMissionSubscribed(
        serverId: String,
        missionName: String
    ) {

        // Read the current saved subscriptions
        val current = persistedMissionSubscriptions.first()

        // Check whether this server and mission combination is already saved
        if (current.none {
                it.serverId == serverId &&
                        it.missionName == missionName
            }
        ) {

            // Add the new mission subscription to the saved list
            storeMissionSubscriptions(
                current + PersistedMissionSubscription(
                    serverId = serverId,
                    missionName = missionName
                )
            )
        }
    }

    // Remove a mission subscription from DataStore
    // Keep all other saved mission subscriptions
    suspend fun persistMissionUnsubscribed(
        serverId: String,
        missionName: String
    ) {

        // Read the current saved subscriptions
        val current = persistedMissionSubscriptions.first()

        // Remove only the subscription matching this server and mission
        storeMissionSubscriptions(
            current.filterNot {
                it.serverId == serverId &&
                        it.missionName == missionName
            }
        )
    }

    // Save the latest sync progress for a subscribed mission
    // Keep the last sync time and processed changes between app sessions
    suspend fun persistMissionSyncProgress(
        serverId: String,
        missionName: String,
        lastSyncTime: Long,
        processedChangeKeys: Set<String>
    ) {

        // Read all currently saved mission subscriptions
        val current = persistedMissionSubscriptions.first()

        // Update the saved progress for the matching mission
        val updated = current.map { subscription ->

            // Match both the server ID and mission name
            // This prevents updating the same mission name on another server
            if (
                subscription.serverId == serverId &&
                subscription.missionName == missionName
            ) {

                // Save the new sync time and processed change keys
                subscription.copy(
                    lastSyncTime = lastSyncTime,
                    processedChangeKeys = processedChangeKeys
                )
            } else {

                // Keep other saved subscriptions unchanged
                subscription
            }
        }

        // Write the updated subscription list back to DataStore
        storeMissionSubscriptions(updated)
    }

    // Seed saved joined missions independently of available-list discovery.
    suspend fun restorePersistedMissionState(
        serverId: String,
        serverName: String,
        serverAddress: String
    ) {

        // Read saved subscriptions for the requested TAK Server
        val persisted = persistedMissionSubscriptions.first()
            .filter { it.serverId == serverId }

        // Stop if there are no saved subscriptions for this server
        if (persisted.isEmpty()) {
            return
        }

        _takMissions.update { servers ->
            val previous = servers.firstOrNull { it.serverId == serverId }
                ?: TakServerMissions(serverId, serverName, serverAddress, status = TakMissionStatus.READY)
            val missing = persisted.filter { saved ->
                previous.missions.none { it.mission.name == saved.missionName }
            }.map { saved ->
                TakMissionState(mission = TakMission(name = saved.missionName))
            }
            val restored = previous.copy(
                missions = (previous.missions + missing).map { state ->
                    val saved = persisted.firstOrNull { it.missionName == state.mission.name }
                    if (saved == null || state.subscribed) state else state.copy(
                        subscribed = true,
                        lastSyncTime = saved.lastSyncTime,
                        processedChangeKeys = saved.processedChangeKeys,
                        status = TakMissionStatus.IDLE,
                        errorMessage = null
                    )
                }
            )
            if (servers.none { it.serverId == serverId }) servers + restored
            else servers.map { if (it.serverId == serverId) restored else it }
        }
    }

    private val _airplaneOnSuccess = MutableStateFlow(false)
    val airplaneOnSuccess: StateFlow<Boolean> = _airplaneOnSuccess.asStateFlow()

    fun setAirplaneOnSuccess(success: Boolean) {
        _airplaneOnSuccess.value = success
    }



    /**
     * Below is a prototype of a resting heart rate average calculator, using aggregate
     * heart rate data to generate an average.
     */
//    init {
//        repoScope.launch {
//            _latestHeartRate
//                .filterNotNull()
//                .combine(_latestActivityRecognized.filterNotNull()) { hr, activity ->
//                    hr to activity
//                }
//                .filter { (_, activity) -> activity == "STILL" }
//                .collect { (hr, _) ->
//                    restingAggregator.pushSample(hr.toDouble())
//
//                    restingAggregator.computeAverage()?.let { avg ->
//                        _restingHeartRate.emit(avg.roundToInt())
//                    }
//                }
//        }
//    }

    private val _missingPermissions = MutableStateFlow<List<String>>(emptyList())
    val missingPermissions: StateFlow<List<String>> = _missingPermissions

    suspend fun updateMissingPermissions(perms: List<String>) {
        _missingPermissions.emit(perms)
    }

    private val _blockedServices = MutableStateFlow<Map<ServiceType, List<String>>>(emptyMap())
    val blockedServices: StateFlow<Map<ServiceType, List<String>>> = _blockedServices

    suspend fun updateBlockedServices(blocked: Map<ServiceType, List<String>> ) {
        _blockedServices.emit(blocked)
    }

    // ======== ======== HAIL SECTION START ======== ========

    /**
     * Hail Service is controlled by the User (or in Knox mgd configs), this gate
     * initiates hail licensing and injection of data into a Hail Cot Detail, given
     * the license is valid.
     */
    suspend fun storeEnableHailService(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_HAIL_SERVICE] = enable
        }
    }

    val getEnableHailService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_HAIL_SERVICE] ?: DEFAULT_ENABLE_HAIL_SERVICE
    }

    suspend fun storeEnableBatdokCot(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BATDOK_COT] = enable
        }
    }

    val getEnableBatdokCot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BATDOK_COT] ?: DEFAULT_ENABLE_BATDOK_COT
    }



    /**
     * Hail License State can be one of the four states below and controls
     * UI screens, license authentication, and activation of building
     * the Hail CotDetail. See HailService.HailLicenseStates{} for more info:
     *
     *     enum class HailLicenseStates {
     *         VALID,
     *         CHECKING,
     *         SOFT_EXPIRED,
     *         EXPIRED,
     *         INVALID
     *     }
     **/
    private fun String.toHailStateOr(default: HailLicenseStates): HailLicenseStates =
        runCatching { enumValueOf<HailLicenseStates>(this) }.getOrElse { default }

    val hailLicenseState: StateFlow<HailLicenseStates> =
        context.dataStore.data
            .map { prefs ->
                prefs[HAIL_LICENSE_STATE]?.toHailStateOr(HailLicenseStates.INVALID)
                    ?: HailLicenseStates.INVALID
            }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.Eagerly,
                initialValue = HailLicenseStates.INVALID
            )

    suspend fun storeHailLicenseState(state: HailLicenseStates?) {
        context.dataStore.edit { prefs ->
            if (state == null) {
                prefs.remove(HAIL_LICENSE_STATE)   // don’t persist “null”
            } else {
                prefs[HAIL_LICENSE_STATE] = state.name
            }
        }
    }

    val getHailLicenseState: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[HAIL_LICENSE_STATE] ?: HailLicenseStates.INVALID.name
    }

    /**
     * Combat ID is unique to each hail USER (aka INDIVIDUAL PERSON), not
     * necessarily a device. This is a hail feature so users can have
     * multiple devices/sensors feeding data into hail. The CombatID is
     * required in the Hail CotDetail and is synonymous with a "card" in
     * the ATAK plugin UI.
     */
    suspend fun storeCombatId(id: Int) {
        context.dataStore.edit { prefs ->
            prefs[HAIL_COMBAT_ID] = id
        }
    }
    val getCombatId: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[HAIL_COMBAT_ID]
    }

    /**
     * When check button is clicked in UI, this value will be
     * flipped to true, and the license will be checked.
     */
    var hailCheckLicenseButtonPressed = MutableStateFlow<Boolean>(false)

    /**
     * Change part of the saved license info without overwriting everything.
     * Reads the current value, gives it to the transform function,
     * then saves whatever that function returns.
     * Return null from the transform to clear the value.
     */
    suspend fun updateHailLicenseInfo(
        transform: (HailService.HailLicenseInfo?) -> HailService.HailLicenseInfo?
    ) {
        context.dataStore.edit { prefs ->
            val beforeJson = prefs[HAIL_LICENSE_INFO_JSON]
            val current = beforeJson?.let {
                runCatching { gson.fromJson(it, HailService.HailLicenseInfo::class.java) }.getOrNull()
            }
            val next = transform(current)
            val afterJson = next?.let { gson.toJson(it)}

            Log.d(TAG, "HailLicenseInfo Before=$beforeJson")
            Log.d(TAG, "HailLicenseInfo Current=$current")
            Log.d(TAG, "HailLicenseInfo After=$afterJson")

            if (next == null) {
                prefs.remove(HAIL_LICENSE_INFO_JSON)
            } else {
                prefs[HAIL_LICENSE_INFO_JSON] = gson.toJson(next)
            }
        }
    }

    /**
     * A live stream of the saved hail license info.
     * Every time the persistently stored value changes, this will emit
     * the new value. Emits null if there is nothing saved.
     */
    val hailLicenseInfoFlow: Flow<HailService.HailLicenseInfo?> =
        context.dataStore.data.map { prefs ->
            val json = prefs[HAIL_LICENSE_INFO_JSON] ?: return@map null
            runCatching {
                gson.fromJson(json, HailService.HailLicenseInfo::class.java)
            }.getOrNull()
        }

    /**
     * A version of hailLicenseInfoFlow that always remembers the latest value.
     * Useful for UI code so it can instantly read the current license info
     * without waiting for a change.
     */
    val hailInfoStateFlow: StateFlow<HailService.HailLicenseInfo?> =
        hailLicenseInfoFlow.stateIn(
            scope = repoScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /**
     * buildHailDetail is the final gate for Hail Service activation.
     * ReactiveServiceManager controls this gate, and the CotDispatchService
     * checks against it. Once true, the CotDispatchService constructs
     * the Hail Detail (via the Hail SDK APIs) and injects it into each
     * PLI CoT. The device will then show up on the Hail Plugin in ATAK.
     */
    val buildHailDetail: StateFlow<Boolean> =
        combine(getEnableHailService, hailLicenseState) { enable, state ->
            enable && (state == HailLicenseStates.VALID ||
                    state == HailLicenseStates.SOFT_EXPIRED)
        }
            .distinctUntilChanged()
            .stateIn(repoScope, SharingStarted.Eagerly, /* initial */ false)

    // ======== ======== HAIL SECTION END ======== ==========

    // ======== ======== Multicast SECTION Start ======== ==========

    private val _multicastPublisherRunning = MutableStateFlow<Boolean> (false)
    val multicastPublisherRunning: StateFlow<Boolean> = _multicastPublisherRunning

    fun setMulticastPublisherRunning(running: Boolean) {
        _multicastPublisherRunning.value = running
    }

    val multicastEnabled: StateFlow<Boolean?> =
        context.dataStore.data
            .map { prefs ->
                prefs[ENABLE_MULTICAST] ?: DEFAULT_ENABLE_MULTICAST_ON_WIFI
            }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    suspend fun storeEnableMulticast(enable: Boolean) {
        storeNetworkToggle(ENABLE_MULTICAST, enable)
    }

    val getEnableMulticast: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_MULTICAST] ?: DEFAULT_ENABLE_MULTICAST_ON_WIFI
    }

    val multicastAddress: StateFlow<String?> =
        context.dataStore.data
            .map { prefs ->
                prefs[MULTICAST_ADDRESS] ?: DEFAULT_MULTICAST_ADDRESS
            }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    val getMulticastAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[MULTICAST_ADDRESS] ?: DEFAULT_MULTICAST_ADDRESS
    }

    suspend fun storeMulticastAddress(multicastAddress: String) {
        context.dataStore.edit { prefs ->
            prefs[MULTICAST_ADDRESS] = multicastAddress
        }
    }

    val getMulticastProtocol: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[MULTICAST_PROTOCOL] ?: DEFAULT_MULTICAST_PROTOCOL
    }

    suspend fun storeMulticastProtocol(multicastProtocol: String) {
        context.dataStore.edit { prefs ->
            prefs[MULTICAST_PROTOCOL] = multicastProtocol
        }
    }

    val multicastPort: StateFlow<Int?> =
        context.dataStore.data
            .map { prefs ->
                prefs[MULTICAST_PORT] ?: DEFAULT_MULTICAST_PORT
            }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )
    val getMulticastPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[MULTICAST_PORT] ?: DEFAULT_MULTICAST_PORT
    }

    suspend fun storeMulticastPort(multicastPort: Int) {
        context.dataStore.edit { prefs ->
            prefs[MULTICAST_PORT] = multicastPort
        }
    }

    /*
     *  tracks the wifi connection state to start or stop multicasting.
     */
    var isWifiConnected = MutableStateFlow<Boolean>(false)

    // ======== ======== Multicast SECTION END ======== ==========

    val geofenceServiceEnabled: StateFlow<Boolean?> =
        context.dataStore.data
            .map { prefs ->
                prefs[ENABLE_GEOFENCE_SERVICE] ?: DEFAULT_ENABLE_GEOFENCE_SERVICE
            }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    suspend fun storeEnableGeofenceService(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_GEOFENCE_SERVICE] = enable
        }
    }

    val getEnableGeofenceService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_GEOFENCE_SERVICE] ?: DEFAULT_ENABLE_GEOFENCE_SERVICE
    }
    private val _latestActivityConfidence = MutableStateFlow<Int>(100)
    val latestActivityConfidence: StateFlow<Int?> = _latestActivityConfidence

    private val _latestActivityRecognized = MutableStateFlow<ActivityRecognized>(ActivityRecognized.UNKNOWN)
    val latestActivityRecognized: StateFlow<ActivityRecognized> = _latestActivityRecognized

    private val _latestHeartRate = MutableStateFlow<Int?>(null)
    val latestHeartRate: StateFlow<Int?> = _latestHeartRate
    private val selfStatusHeartRate = MutableStateFlow<PhysioSample<Int>?>(null)
    private val selfStatusSkinTemperature = MutableStateFlow<PhysioSample<Float>?>(null)

    fun selfStatusInputs(): Flow<SelfStatusInputs> = combine(
        context.dataStore.data, selfStatusHeartRate, selfStatusSkinTemperature, latestIsDeviceWorn
    ) { prefs, hr, skin, worn ->
        val savedMopp = MoppStatus.entries.firstOrNull { it.key == prefs[MOPP_STATUS] }?.let {
            SavedMoppStatus(it, prefs[MOPP_SAVED_AT])
        }
        SelfStatusInputs(
            SelfStatusPolicy(
                prefs[ENABLE_MOPP] ?: false, savedMopp,
                prefs[ENABLE_PHYSIOLOGICAL_SERVICES] ?: DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES,
                prefs[ENABLE_HEART_RATE_SERVICE] ?: DEFAULT_ENABLE_HEART_RATE_SERVICE,
                prefs[ENABLE_SKIN_TEMP_SERVICE] ?: DEFAULT_ENABLE_SKIN_TEMPERATURE_SERVICE,
                worn, prefs[AGE] ?: (Year.now().value - DEFAULT_BIRTH_YEAR).coerceAtLeast(0),
                prefs[RESTING_HEART_RATE] ?: DEFAULT_RESTING_HR
            ), hr, skin
        )
    }

    val getEnableCrmService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_CRM_SERVICE] ?: DEFAULT_ENABLE_CRM_SERVICE
    }

    val getEnableMopp: Flow<Boolean> = context.dataStore.data.map { it[ENABLE_MOPP] ?: false }
    val getMoppStatus: Flow<MoppStatus> = context.dataStore.data.map {
        MoppStatus.fromKey(it[MOPP_STATUS])
    }
    val getEnableDataSync: Flow<Boolean> = context.dataStore.data.map { it[ENABLE_DATA_SYNC] ?: true }

    suspend fun storeEnableMopp(enabled: Boolean) {
        context.dataStore.edit { it[ENABLE_MOPP] = enabled }
    }

    suspend fun storeMoppStatus(status: MoppStatus) {
        context.dataStore.edit {
            it[MOPP_STATUS] = status.key
            it[MOPP_SAVED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun storeEnableDataSync(enabled: Boolean) {
        context.dataStore.edit { it[ENABLE_DATA_SYNC] = enabled }
    }

    suspend fun storeEnableCrmService(enableCrmService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_CRM_SERVICE] = enableCrmService
        }
    }

    private val _latestCrmInference = MutableStateFlow(CrmInferenceState.idle())
    val latestCrmInference: StateFlow<CrmInferenceState> = _latestCrmInference

    private val _crmRawPpgSamples = MutableSharedFlow<CrmRawPpgSample>(extraBufferCapacity = 256)
    val crmRawPpgSamples: SharedFlow<CrmRawPpgSample> = _crmRawPpgSamples

    suspend fun storeCrmInference(state: CrmInferenceState) {
        _latestCrmInference.emit(state)
    }

    val crmThresholdRanges: Flow<CrmThresholdRanges> =
        context.dataStore.data.map { prefs ->
            val poorMax = prefs[CRM_RED_MAX_THRESHOLD]
                ?: DEFAULT_CRM_POOR_MAX_THRESHOLD

            val moderateMax = prefs[CRM_YELLOW_MAX_THRESHOLD]
                ?: DEFAULT_CRM_MODERATE_MAX_THRESHOLD

            sanitizeCrmThresholdRanges(
                poorMax = poorMax,
                moderateMax = moderateMax,
            )
        }

    suspend fun storeCrmThresholdRanges(
        poorMax: Int,
        moderateMax: Int,
    ) {
        val sanitized = sanitizeCrmThresholdRanges(
            poorMax = poorMax,
            moderateMax = moderateMax,
        )

        context.dataStore.edit { prefs ->
            prefs[CRM_RED_MAX_THRESHOLD] = sanitized.poorMax
            prefs[CRM_YELLOW_MAX_THRESHOLD] = sanitized.moderateMax
        }
    }

    fun tryStoreCrmInference(state: CrmInferenceState): Boolean = _latestCrmInference.tryEmit(state)

    fun tryStoreCrmRawPpgSample(sample: CrmRawPpgSample): Boolean = _crmRawPpgSamples.tryEmit(sample)

    private val _restingHeartRate = MutableStateFlow<Int>(DEFAULT_RESTING_HR)
    val restingHeartRate: StateFlow<Int> = _restingHeartRate

    private val _latestSkinTemp = MutableStateFlow<Float?>(null)
    val latestSkinTemp: StateFlow<Float?> = _latestSkinTemp
    private val _latestSpo2 = MutableStateFlow<Double?>(null)
    val latestSpo2: StateFlow<Double?> = _latestSpo2.asStateFlow()
    private val _latestSpo2TierEvent = MutableStateFlow<Spo2TierEventPayload?>(null)
    val latestSpo2TierEvent: StateFlow<Spo2TierEventPayload?> = _latestSpo2TierEvent.asStateFlow()
    private val _latestSpo2DebugState = MutableStateFlow<Spo2DebugState?>(null)
    val latestSpo2DebugState: StateFlow<Spo2DebugState?> = _latestSpo2DebugState.asStateFlow()

    // PRESSURE SENSOR START
    private val _latestPressure = MutableStateFlow<PressureReading?>(null)
    val latestPressure: StateFlow<PressureReading?> = _latestPressure.asStateFlow()

    private val _pressureHistory = MutableStateFlow<List<PressureReading>>(emptyList())
    val pressureHistory: StateFlow<List<PressureReading>> = _pressureHistory.asStateFlow()

    suspend fun storeLatestPressure(reading: PressureReading) {
        _latestPressure.emit(reading)

        // keep last 20 samples
        val updated = (_pressureHistory.value + reading).takeLast(20)
        _pressureHistory.emit(updated)
    }
    suspend fun debugPressureTestOnce() {
        val reading = PressureReading(
            pressure = 1013.25f,
            timestamp = System.currentTimeMillis()
        )
        storeLatestPressure(reading)

        Log.i(TAG, "pressure debug: latest=${latestPressure.value}")
        Log.i(TAG, "pressure debug: historySize=${pressureHistory.value.size}")
        Log.i(TAG, "pressure debug: historyLast=${pressureHistory.value.lastOrNull()}")
    }


    // PRESSURE SENSOR END

    private val _latestLocation = MutableStateFlow<Location?>(null)
    val latestLocation: StateFlow<Location?> = _latestLocation
    val locationInputStatus = MutableStateFlow(LocationInputStatus.OFF)
    val getLocationInput: Flow<LocationInput?> = context.dataStore.data.map {
        LocationInput.fromKey(it[LOCATION_INPUT])
    }
    val hasConfiguredLocationInput: Flow<Boolean> = context.dataStore.data.map { it.contains(LOCATION_INPUT) }
    private val locationInputWriteMutex = Mutex()
    @Volatile private var currentLocationInput: LocationInput? = LocationInput.FUSED
    @Volatile private var locationInputChanging = false
    @Volatile private var locationInputChangedNanos = 0L

    suspend fun storeLocationInput(input: LocationInput) = locationInputWriteMutex.withLock {
        if (phoneManagedSettingsLocked.first()) {
            Log.w(TAG, "Location Input change rejected while Reporting is phone-managed")
            return@withLock false
        }
        synchronized(_latestLocation) {
            locationInputChanging = true
            locationInputChangedNanos = android.os.SystemClock.elapsedRealtimeNanos()
            lastValidLocation = null
            _latestLocation.value = null
            if (locationInputStatus.value != LocationInputStatus.OFF)
                locationInputStatus.value = LocationInputStatus.ACQUIRING
        }
        try {
            context.dataStore.edit { it[LOCATION_INPUT] = input.provider }
            currentLocationInput = input
        } finally {
            locationInputChanging = false
        }
        true
    }

    fun isCurrentLocationInput(location: Location): Boolean =
        !locationInputChanging && location.provider == currentLocationInput?.provider &&
            location.elapsedRealtimeNanos >= locationInputChangedNanos

    fun isCurrentLocationSnapshot(location: LocationSnapshot): Boolean =
        !locationInputChanging && location.provider == currentLocationInput?.provider &&
            (location.elapsedRealtimeNanos ?: 0L) >= locationInputChangedNanos

    private val _useGpsCogAsHeading = MutableStateFlow(false)
    val useGpsCogAsHeading: StateFlow<Boolean> = _useGpsCogAsHeading

    private val _magAzTrueDeg = MutableStateFlow<Double?>(null)
    val magAzTrueDeg: StateFlow<Double?> = _magAzTrueDeg

    fun setUseGpsCogAsHeading(enabled: Boolean) {
        _useGpsCogAsHeading.value = enabled
    }

    fun updateMagAzimuthTrue(angleTrueDeg: Double?) {
        _magAzTrueDeg.value = angleTrueDeg?.let(LocationVectorCalculator::normalize0to360)
    }

    internal val magneticHeadingSubscriptions get() = _magAzTrueDeg.subscriptionCount

    val autoUseGps: StateFlow<Boolean> =
        latestLocation
            .map { loc -> loc != null && loc.hasSpeed() && loc.speed > 1.5f && loc.hasBearing() }
            .stateIn(repoScope, SharingStarted.WhileSubscribed(5_000), false)

    val gpsCogTrueDeg: StateFlow<Double?> =
        latestLocation
            .map { loc ->
                if (loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) {
                    LocationVectorCalculator.normalize0to360(loc.bearing.toDouble())
                } else {
                    null
                }
            }
            .stateIn(repoScope, SharingStarted.WhileSubscribed(5_000), null)

    private val useGpsHeadingFinal: StateFlow<Boolean> =
        combine(useGpsCogAsHeading, autoUseGps) { manual, auto ->
            if (manual) true else auto
        }.stateIn(repoScope, SharingStarted.WhileSubscribed(5_000), false)

    val azimuthTrueDeg: StateFlow<Double?> =
        combine(useGpsHeadingFinal, gpsCogTrueDeg, magAzTrueDeg) { useGps, gps, mag ->
            LocationVectorCalculator.fuseHeading(
                useGpsHeading = useGps,
                gpsCogTrueDeg = gps,
                magAzTrueDeg = mag
            )
        }.stateIn(repoScope, SharingStarted.WhileSubscribed(5_000), null)

    val azimuthTrueDegSmoothed: StateFlow<Double?> =
        azimuthTrueDeg
            .scan<Double?, Double?>(null) { prev, curr ->
                when {
                    prev == null -> curr
                    curr == null -> null
                    else -> LocationVectorCalculator.smoothAngle(prev, curr, alpha = 0.15)
                }
            }
            .stateIn(repoScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _latestBatteryPercentage = MutableStateFlow<Int?>(null)
    val latestBatteryPercentage: StateFlow<Int?> = _latestBatteryPercentage
    // TODO: Later move nonNullBatteryPercentage to ViewModel
    val nonNullBatteryPercentage: StateFlow<Int> = latestBatteryPercentage
        .map { it ?: 100 }
        .stateIn(
            scope = repoScope,
            started = SharingStarted.Eagerly,
            initialValue = 100
        )

    private val _dockType = MutableStateFlow(DockType.NONE)
    val dockType: StateFlow<DockType> = _dockType

    suspend fun updateDockType(type: DockType) {
        _dockType.emit(type)
    }

    val isDocked: StateFlow<Boolean> =
        dockType
            .map { it != DockType.NONE }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.Eagerly,
                initialValue = false
            )

    suspend fun updateChargingState(charging: Boolean) {
        _chargingState.emit(charging)
    }
    private val _chargingState = MutableStateFlow<Boolean>(false)
    val chargingState: StateFlow<Boolean> = _chargingState

    private val _latestIsDeviceWorn = MutableStateFlow<Boolean>(false)
    val latestIsDeviceWorn: StateFlow<Boolean> = _latestIsDeviceWorn

    // Enable Service Flow Toggles
    // --- Desired state (on/off intent) ---
    private val _serviceStateFlow =
        MutableStateFlow(EnumMap<ServiceType, Boolean>(ServiceType::class.java))

    val serviceStateFlow: StateFlow<EnumMap<ServiceType, Boolean>> =
        _serviceStateFlow.asStateFlow()

    suspend fun updateServicesStateFlow(map: EnumMap<ServiceType, Boolean>) {
        _serviceStateFlow.emit(map)
    }

    // --- Service States Debugging Tools ----
    private val _serviceDebugStateFlow =
        MutableStateFlow(EnumMap<ServiceType, ReactiveServiceManager.ServiceDebugState?>(
            ServiceType::class.java
        ))

    val serviceDebugStateFlow: StateFlow<EnumMap<ServiceType, ReactiveServiceManager.ServiceDebugState?>> =
        _serviceDebugStateFlow.asStateFlow()

    // Merge helpers: update only one stage without losing the others
    suspend fun updateDebugStageRemoteKnox(knoxMap: EnumMap<ServiceType, Boolean?>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in knoxMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = v,
                localCompanion = prev?.localCompanion,
                configUpdate = prev?.configUpdate,
                desired = prev?.desired ?: false,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageLocalPersisted(localMap: EnumMap<ServiceType, Boolean?>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in localMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = v,
                configUpdate = prev?.configUpdate,
                desired = prev?.desired ?: false,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageConfigUpdate(configMap: EnumMap<ServiceType, Boolean?>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in configMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = prev?.localCompanion,
                configUpdate = v,
                desired = prev?.desired ?: false,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageDesired(desiredMap: EnumMap<ServiceType, Boolean>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in desiredMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = prev?.localCompanion,
                configUpdate = prev?.configUpdate,
                desired = v,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageRunning(runningMap: EnumMap<ServiceType, Boolean>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in runningMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = prev?.localCompanion,
                configUpdate = prev?.configUpdate,
                desired = prev?.desired ?: false,
                running = v
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    // -------- End of Service State Debugging ----------

    val getEnableBloodhoundCompassService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BLOODHOUND_COMPASS_SERVICE] ?: DEFAULT_ENABLE_BLOODHOUND_COMPASS_SERVICE
    }

    suspend fun storeEnableBloodhoundCompassService(enableBloodhoundService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BLOODHOUND_COMPASS_SERVICE] = enableBloodhoundService
        }
    }

    val getEnableBloodhoundProximityVibration: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BLOODHOUND_PROXIMITY_VIBRATION] ?: DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
    }

    suspend fun storeEnableBloodhoundProximityVibration(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BLOODHOUND_PROXIMITY_VIBRATION] = enabled
        }
    }

    val getBloodhoundProximityRadiusMeters: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BLOODHOUND_PROXIMITY_RADIUS_METERS] ?: DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
    }

    suspend fun storeBloodhoundProximityRadiusMeters(radiusMeters: Int) {
        context.dataStore.edit { prefs ->
            prefs[BLOODHOUND_PROXIMITY_RADIUS_METERS] = radiusMeters
        }
    }

    /** Display units, stored under ATAK's Range Units key (`rab_rng_units_pref`) and values. */
    val unitPrefs: Flow<com.aitech.weartak_civ.ui.formatter.UnitPrefs> = context.dataStore.data.map { prefs ->
        com.aitech.weartak_civ.ui.formatter.UnitPrefs.fromStored(prefs[UNIT_RANGE_UNITS])
    }.distinctUntilChanged()

    /**
     * Applies ATAK unit preference updates. Only `rab_rng_units_pref` is used; other keys are
     * ignored. An invalid range value throws and nothing is stored.
     */
    suspend fun storeUnitPrefs(updates: Map<String, Any?>): com.aitech.weartak_civ.ui.formatter.UnitPrefs {
        lateinit var result: com.aitech.weartak_civ.ui.formatter.UnitPrefs
        context.dataStore.edit { prefs ->
            val current = com.aitech.weartak_civ.ui.formatter.UnitPrefs.fromStored(prefs[UNIT_RANGE_UNITS])
            result = current.withUpdates(updates)
            prefs[UNIT_RANGE_UNITS] = result.rangeUnits.toString()
        }
        com.aitech.weartak_civ.ui.formatter.UnitPrefsState.update(result)
        return result
    }

    val getBloodhoundProximityIntensity: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BLOODHOUND_PROXIMITY_INTENSITY] ?: DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
    }

    suspend fun storeBloodhoundProximityIntensity(intensity: String) {
        context.dataStore.edit { prefs ->
            prefs[BLOODHOUND_PROXIMITY_INTENSITY] = intensity
        }
    }

    private val _getEnableTakConnectService = MutableStateFlow<Boolean>(true)
    var getEnableTakConnectService: MutableStateFlow<Boolean> = _getEnableTakConnectService

    private val _getEnableCotDispatchService = MutableStateFlow<Boolean>(true)
    var getEnableCotDispatchService: MutableStateFlow<Boolean> = _getEnableCotDispatchService

    private val _getEnableMovementDetectionService = MutableStateFlow<Boolean>(true)
    var getEnableMovementDetectionService: MutableStateFlow<Boolean> = _getEnableMovementDetectionService

    private val _getEnableBatteryService = MutableStateFlow<Boolean>(true)
    var getEnableBatteryService: MutableStateFlow<Boolean> = _getEnableBatteryService

    private val _getEnablePowerManagerService = MutableStateFlow<Boolean>(true)
    var getEnablePowerManagerService: MutableStateFlow<Boolean> = _getEnablePowerManagerService

    private val _getEnableOffBodyDetectService = MutableStateFlow<Boolean>(true)
    var getEnableOffBodyDetectService: MutableStateFlow<Boolean> = _getEnableOffBodyDetectService

    @Entity(tableName = "daily_health_metrics")
    data class DailyHealthMetrics(
        @PrimaryKey val timestampHour: Instant,  // truncated to the top of the hour
        val steps: Long,
        val elevationMeters: Double
    )

    val getRestingHeartRate: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[RESTING_HEART_RATE] ?: DEFAULT_RESTING_HR
    }

    suspend fun storeRestingHeartRate(restHr: Int) {
        context.dataStore.edit { prefs ->
            prefs[RESTING_HEART_RATE] = restHr
        }
        _restingHeartRate.emit(restHr)
    }

    suspend fun snapshotLocation(location: Location? = latestLocation.value): LocationSnapshot? {
        val baseLoc = location ?: return null

        var latActual   = baseLoc.latitude
        var lonActual   = baseLoc.longitude
        var haeActual   = baseLoc.altitude
        var ceActual    = if (baseLoc.hasAccuracy()) baseLoc.accuracy else 9_999_999f
        var leActual    = if (baseLoc.hasVerticalAccuracy()) baseLoc.verticalAccuracyMeters else 9_999_999f
        var coordinateSource = baseLoc.provider

        // TODO Senni: Do we need 2 lines below as it is checked in buildPliCot
        if (ceActual.isNaN()) ceActual = 9999999.0f
        if (leActual.isNaN()) leActual = 9999999.0f

        // TODO Senni: Check mapLocationAgainstClosestBleBeacon and if we set leActual too...
        // Apply BLE-beacon override if enabled
        if (getEnableBleBeaconService.first()) {
            val bleOutput = Utils.LocationUtils.mapLocationAgainstClosestBleBeacon(baseLoc)
            if (bleOutput.lat != latActual || bleOutput.lon != lonActual ||
                bleOutput.hae != haeActual || bleOutput.ce != ceActual) coordinateSource = "ble"
            latActual = bleOutput.lat
            lonActual = bleOutput.lon
            haeActual = bleOutput.hae
            ceActual  = bleOutput.ce
        }

        return LocationSnapshot(
            timeMillis = baseLoc.time,
            lat = latActual,
            lon = lonActual,
            hae = haeActual,
            ce = ceActual,
            le = leActual,
            course = baseLoc.bearing.takeIf { !it.isNaN() },
            speed = baseLoc.speed.takeIf { !it.isNaN() },
            provider = baseLoc.provider,
            elapsedRealtimeNanos = baseLoc.elapsedRealtimeNanos,
            coordinateSource = coordinateSource,
        )
    }

    data class LocationSnapshot(
        val timeMillis: Long,
        val lat: Double,
        val lon: Double,
        val hae: Double?,
        val ce: Float?,    // circular error (horizontal)
        val le: Float?,    // linear error (vertical)
        val course: Float?, // optional: bearing
        val speed: Float?,   // optional: m/s
        val provider: String? = null,
        val elapsedRealtimeNanos: Long? = null,
        val coordinateSource: String? = provider,
    )

    data class PhysioData(
        val heartRateBpm: Int?,          // current HR
        val skinTempF: Float?,           // Temp of skin in Deg F
        val ageYears: Int?,              // physiological age used for calculations
        val weightKg: Int?,              // settings
        val restingHrBpm: Int?,          // baseline rest HR
        val exertion: Double?,           // 0..1 or null if unknown
        val exertionClass: String?,      // "LIGHT", "MODERATE", "VIGOROUS", "UNKNOWN"
        val dailyMetrics: DailyMetrics?  // aggregated steps/floors/elevation
    )

    data class PhysioSnapshot(
        val timeMillis: Long,
        val data: PhysioData
    )

    suspend fun snapshotPhysio(): PhysioSnapshot {
        val nowMillis = System.currentTimeMillis()

        val hr          = latestHeartRate.value
        val skinT       = latestSkinTemp.value
        val ageYears    = getPhysiologicalMetricAge.first()
        val weightKg    = getPhysiologicalMetricWeight.first()
        val restingHr   = getRestingHeartRate.first()
        val exertion    = latestExertion.value
        val daily       = latestDailyMetrics.value

        val exertionClass: String? = exertion.let {
            Utils.PhysioUtils.classifyExertion(it)
        }

        return PhysioSnapshot(
            timeMillis = nowMillis,
            data = PhysioData(
                heartRateBpm  = hr,
                skinTempF     = skinT,
                ageYears      = ageYears,
                weightKg      = weightKg,
                restingHrBpm  = restingHr,
                exertion      = exertion,
                exertionClass = exertionClass,
                dailyMetrics  = daily
            )
        )
    }

    data class DailyMetrics(
        val timestampHour: Instant,
        val steps: Long       = 0L,
        val floors: Double    = 0.0,
        val elevationM: Double = 0.0
    )

    private val _latestDailyMetrics = MutableStateFlow<DailyMetrics?>(null)
    val latestDailyMetrics: StateFlow<DailyMetrics?> = _latestDailyMetrics

    fun updateDailyMetrics(
        timestampHour: Instant,
        steps: Long?,
        floors: Double?,
        elevationM: Double?
    ) {
        val prev = _latestDailyMetrics.value
        _latestDailyMetrics.value = DailyMetrics(
            timestampHour = timestampHour,
            steps        = steps       ?: prev?.steps       ?: 0L,
            floors       = floors      ?: prev?.floors      ?: 0.0,
            elevationM   = elevationM  ?: prev?.elevationM  ?: 0.0
        )
    }

    suspend fun logSystemSnapshot(tag: String) {
        val states = snapshotSystemStates()
        Log.d(tag, formatSystemSnapshot(states))
    }

    private fun formatSystemSnapshot(states: SystemStates): String {
        return buildString {
            appendLine("┌─ System Snapshot")
            appendLine("│ timeMillis             : ${states.timeMillis}")
            appendLine("│ dockType               : ${states.dockType}")
            appendLine("│ charging               : ${states.charging}")
            appendLine("│ isWorn                 : ${states.isWorn}")
            appendLine("│ desiredReportInterval  : ${states.desiredReportInterval} s")
            appendLine("│ actualReportInterval   : ${states.actualReportInterval} s")
            appendLine("└─────────────────────────────")
        }
    }

    data class SystemStates(
        val timeMillis: Long,       // timestamp of the states
        val dockType: DockType,     // docked via wireless, usb, or none
        val charging: Boolean,      // is the device currently charging
        val isWorn: Boolean,        // is the user wearing the device
        val desiredReportInterval: Int,    // the desired reporting interval before optimizations are applied
        val actualReportInterval: Int,    // the actual dispatching interval in cot dispatch
    )

    private fun snapshotSystemStates(): SystemStates {
        return SystemStates(
            timeMillis = System.currentTimeMillis(),
            dockType = dockType.value,
            charging = chargingState.value,
            isWorn = _latestIsDeviceWorn.value,
            desiredReportInterval = CotDispatchService.desiredReportingIntervalSecs,
            actualReportInterval = CotDispatchService.actualReportingIntervalSecs
        )
    }

    // TODO Senni: Change name of SystemSnapshot and SystemStates
    data class SystemSnapshot(
        val timeMillis: Long,       // When the snapshot was taken
        val batteryPercent: Int?,   // nullable because sensors can fail or race
        val callsign: String,       // always available from DataStore
        val deviceId: String,       // MainActivity.device_uid
        val wearTakVersion: String, // gradle build vers #
        val reportIntSecs: Int,     // pli reporting rate
        val systemStates: SystemStates,
        val role: String = "Team Member",
        val team: String = "Orange",
        val standaloneCallsign: String = ""
    )

    suspend fun snapshotSystem(): SystemSnapshot {
        val now = System.currentTimeMillis()

        val battery  = latestBatteryPercentage.value
        val prefs = context.dataStore.data.first()
        val callsign = prefs[CALLSIGN] ?: ""
        val teamNames = (prefs[TEAM_NAMES] ?: DEFAULT_TEAM_LIST).split(",")
        val teamColors = (prefs[TEAM_COLORS] ?: DEFAULT_TEAM_LIST).split(",")
        val teamIndex = teamNames.indexOf(prefs[TEAM] ?: "Orange")
        val team = teamColors.getOrNull(teamIndex) ?: teamColors.firstOrNull() ?: "Orange"
        val deviceId = MainActivity.device_uid
        val version  = MainActivity.versionNumber
        val interval = CotDispatchService.actualReportingIntervalSecs

        return SystemSnapshot(
            timeMillis = now,
            batteryPercent = battery,
            callsign = callsign,
            deviceId = deviceId,
            wearTakVersion = version,
            reportIntSecs = interval,
            systemStates = snapshotSystemStates(),
            role = prefs[SELECTED_ROLE] ?: "Team Member",
            team = team,
            standaloneCallsign = prefs.standaloneCallsign()
        )
    }

    data class MarkerSnapshot(
        val cotType: MarkerCotType,
        val uid: String,
        val markerType: MarkerType,
        val title: String,
        val remark: String,
        val iconSetPath: String?,
        val enqueuedToBeSent: Boolean,
        val waitingForUpdatedLocation: Boolean,
        val loc: LocationSnapshot?,
        val time: CotEventTime,
        val physio: PhysioSnapshot?,
        val system: SystemSnapshot
    )

    suspend fun snapshotMarkerLocation(uid: String, lat: Double?, lon: Double?, timeMillis: Long): LocationSnapshot? {
        return if (lat == null || lon == null) {
            _droppedMarkerList.value.firstOrNull { it.uid == uid }?.location
                ?: snapshotLocation()
        } else {
            LocationSnapshot(timeMillis = timeMillis, lat = lat, lon = lon,
                hae = 0.0, ce = 0.0f, le = 0.0f, course = 0.0f, speed = 0.0f
            )
        }
    }

    suspend fun snapshotMarker(item: DroppedMarker): MarkerSnapshot {
        val loc = item.location
        val timeSnapshot = CotEventTimeFactory.now(
            startOffset = CotTimeOffset(),
            staleOffset = CotTimeOffset(years = 1)
        )

        val physioSnapshot = snapshotPhysio()
        val systemSnapshot = snapshotSystem()

        return MarkerSnapshot(
            cotType = item.cotType,
            uid = item.uid.ifEmpty { UUID.randomUUID().toString() },
            markerType = item.markerType,
            title = item.title,
            remark = item.remark,
            iconSetPath = item.markerType.icon,
            enqueuedToBeSent = item.enqueuedToBeSent,
            waitingForUpdatedLocation = item.waitingForUpdatedLocation,
            loc = loc,
            time = timeSnapshot,
            physio = physioSnapshot,
            system = systemSnapshot
        )
    }

    enum class AlertState {
        ALERT,
        CANCEL
    }

    data class EmergencyEventSnapshot(
        val uid: String,                     // stable per alert thread
        val alertState: AlertState,          // ALERT or CANCEL
        val category: String,
        val description: String,
        val priority: Int,
        val sendSms: Boolean,
        val time: CotEventTime,
        val location: LocationSnapshot?,
        val physio: PhysioSnapshot?,
        val system: SystemSnapshot
    )

    suspend fun snapshotEmergencyEvent(alert: CustomEmergencyAlert): EmergencyEventSnapshot {
        // TODO Check if we should use alert.timeMillis
        val timeSnap = CotEventTimeFactory.now(
            startOffset = CotTimeOffset(),
            staleOffset = CotTimeOffset(minutes = 15) // or whatever SOS stale you want
        )

        val locSnap = snapshotLocation(bestKnownLocation())
        val systemSnap = snapshotSystem()
        val physioSnap = snapshotPhysio()

        return EmergencyEventSnapshot(
            uid         = alert.uuid,
            alertState  = alert.alertState,
            category    = alert.category,
            description = alert.description,
            priority    = alert.priority,
            sendSms     = alert.sendSms,
            time        = timeSnap,
            location    = locSnap,
            physio      = physioSnap,
            system      = systemSnap
        )
    }

    data class PliSnapshot(
        val loc: LocationSnapshot?,
        val time: CotEventTime,
        val physioData: PhysioSnapshot,
        val systemData: SystemSnapshot,
        val remarks: String = "",
        val monitoredPhysio: PhysioData? = null,
    )

    suspend fun snapshotPliLocation(): LocationSnapshot? =
        com.aitech.weartak_civ.cot.EmergencyLocationPolicy.forDispatch(
            snapshotLocation(bestKnownLocation()), System.currentTimeMillis()
        )

    suspend fun snapshotPli(): PliSnapshot {
        val timeSnap = CotEventTimeFactory.now(
            startOffset = CotTimeOffset(),
            staleOffset = pliStaleOffset()
        )

        val physio = snapshotPhysio()
        val now = System.currentTimeMillis()
        val input = selfStatusInputs().first()
        val selfStatus = selfStatusSnapshot(input, now)
        val policy = input.policy
        return PliSnapshot(
            loc = snapshotPliLocation(),
            time = timeSnap,
            physioData = physio,
            systemData = snapshotSystem(),
            remarks = selfStatus.remarksLine(now),
            monitoredPhysio = physio.data.takeIf { policy.monitoring && policy.worn }?.let {
                it.copy(
                    heartRateBpm = selfStatus.physio?.hr,
                    skinTempF = selfStatus.physio?.sktF,
                    exertion = selfStatus.physio?.exert,
                    exertionClass = selfStatus.physio?.exert?.let(Utils.PhysioUtils::classifyExertion),
                )
            },
        )
    }

    internal suspend fun snapshotSelfStatusRemarks(): String {
        val input = selfStatusInputs().first()
        val now = System.currentTimeMillis()
        return selfStatusSnapshot(input, now).remarksLine(now)
    }

    suspend fun snapshotInteractionPli(location: Location): PliSnapshot =
        snapshotPli().copy(
            loc = snapshotLocation(location),
            time = CotEventTimeFactory.at(
                location.time,
                staleOffset = pliStaleOffset()
            )
        )

    suspend fun snapshotOutboundAnnouncement(location: Location): PliSnapshot {
        val snapshot = snapshotInteractionPli(location)
        val current = snapshot.monitoredPhysio
        return snapshot.copy(physioData = snapshot.physioData.copy(data = snapshot.physioData.data.copy(
            heartRateBpm = current?.heartRateBpm,
            skinTempF = current?.skinTempF,
            exertion = current?.exertion,
            exertionClass = current?.exertionClass,
            dailyMetrics = null,
        )))
    }

    internal fun hasCompanionEverBeenReady(): Boolean {
        return companionEverReady.value == true
    }

    private fun pliStaleOffset(): CotTimeOffset {
        val standaloneSeconds = com.aitech.weartak_civ.service.StationaryLocationPolicy.staleSeconds(
            almightyReportingIntervalSecs.value, CotDispatchService.stationaryLocation,
            CotDispatchService.stationaryIntervalSeconds)
        val offsetMillis = com.aitech.weartak_civ.cot.SelfPliStalePolicy.offsetMillis(
            companionBonded = companionEverReady.value == true,
            effectiveIntervalSeconds = CotDispatchService.actualReportingIntervalSecs,
            standaloneOffsetMillis = standaloneSeconds * 1_000L,
        )
        return CotTimeOffset(seconds = (offsetMillis / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    // This is the data class for both physio alerts and manual alerts that the backend adds to and clears.
    // This is a custom TAK emergency (911) alert as we change the description of the 911 alert sent to TAK
    data class CustomEmergencyAlert(
        val uuid: String,
        val alertState: AlertState,
        val category: String,
        val description: String,
        val priority : Int,
        val sendSms: Boolean,
        val timeMillis: Long,
        val enqueuedToBeSent: Boolean,
    )

    // UI reads manualAlertList to display manual alerts sent out
    private val _manualAlertList = MutableStateFlow<List<CustomEmergencyAlert>>(emptyList())
    val manualAlertList: StateFlow<List<CustomEmergencyAlert>> = _manualAlertList
    // TODO Senni: should we change isAlerting to account for physioAndAutoGenAlertList
    val isAlerting: StateFlow<Boolean> =
        manualAlertList
            .map { alertList ->
                alertList.isNotEmpty() && alertList.any { it.alertState == AlertState.ALERT }
            }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    // Backend-only use
    suspend fun appendManualAlert(item: CustomEmergencyAlert) {
        _manualAlertList.emit(_manualAlertList.value + item)
    }

    suspend fun changeManualAlertEnqueueStatusUid(uid: String, enqueuedToBeSent: Boolean) {
        val updatedAlerts = _manualAlertList.value.map {
            if (it.uuid == uid) {
                it.copy(enqueuedToBeSent = enqueuedToBeSent)
            } else { it }
        }
        _manualAlertList.emit(updatedAlerts)
    }

    suspend fun changeManualAlertToClearAndEnqueueStatusUid(uid: String, enqueuedToBeSent: Boolean) {
        val updatedAlerts = _manualAlertList.value.map {
            if (it.uuid == uid) {
                it.copy(alertState = AlertState.CANCEL, enqueuedToBeSent = enqueuedToBeSent)
            } else { it }
        }
        _manualAlertList.emit(updatedAlerts)
    }

    suspend fun removeManualAlertByUid(uid: String) {
        _manualAlertList.emit(_manualAlertList.value.filterNot { it.uuid == uid })
    }

    // This is used by UI to request to clear SOS and Markers (UI --> Backend)
    // Also used by backend to automatically clear physio alerts
    // TODO: Should this be what we use to update the gunshot emergency alerts? like should we have a datum for all alerts, manual or automatic, of any type (gunshot, physio, sos, etc)
    //       and use this datum to populate adapters like emergency event objects. We could create a const hashmap given alert type enums returns value strings needed in the cot xml for
    //       category, description, callsign, etc.
    /*
     *                  [ mm = MasterAlertsMap(Alert UUID, Alert Object) ]
     *                              |
     *                              V
     *                  PhysioAlertList = mm.filter(Alert.TYPE == Physio)
     *
     *
    */
    sealed interface ClearRequest {
        data class Specific(val uid: String) : ClearRequest
        data object Last : ClearRequest
        data object All : ClearRequest
    }

    // UI to request addition of manual custom emergency alerts (UI --> backend)
    data class ManualAlertRequest(
        val alertState: AlertState = AlertState.ALERT,
        val category: String,
        val description: String,
        val timeMillis: Long
    )

    private val _addManualAlert = MutableSharedFlow<ManualAlertRequest>(extraBufferCapacity = 64)
    val addManualAlert: SharedFlow<ManualAlertRequest> = _addManualAlert

    private val _clearManualAlert = MutableSharedFlow<ClearRequest>(extraBufferCapacity = 64)
    val clearManualAlert: SharedFlow<ClearRequest> = _clearManualAlert

    suspend fun addManualAlertRequest(alertState: AlertState, category: String, description: String, timeMillis: Long) {
        requestInteractionPliRefresh(if (alertState == AlertState.CANCEL) PliRefreshReason.MANUAL_ALERT_CLEARED else PliRefreshReason.MANUAL_ALERT_ACTIVATED)
        _addManualAlert.emit(ManualAlertRequest(alertState, category, description, timeMillis))
    }

    suspend fun clearManualAlertRequestUid(uid: String) {
        _clearManualAlert.emit(ClearRequest.Specific(uid))
        requestInteractionPliRefresh(PliRefreshReason.MANUAL_ALERT_CLEARED)
    }

    suspend fun clearManualAlertRequestLast() {
        _clearManualAlert.emit(ClearRequest.Last)
        requestInteractionPliRefresh(PliRefreshReason.MANUAL_ALERT_CLEARED)
    }

    suspend fun clearManualAlertRequestAll() {
        _clearManualAlert.emit(ClearRequest.All)
        requestInteractionPliRefresh(PliRefreshReason.MANUAL_ALERT_CLEARED)
    }

    private val _physioAndAutoGenAlertList = MutableStateFlow<List<CustomEmergencyAlert>>(emptyList())
    val physioAndAutoGenAlertList: StateFlow<List<CustomEmergencyAlert>> = _physioAndAutoGenAlertList

    // Backend-only use
    suspend fun appendPhysioAndAutoGenAlert(item: CustomEmergencyAlert) {
        _physioAndAutoGenAlertList.emit(_physioAndAutoGenAlertList.value + item)
    }

    suspend fun changePhysioAndAutoGenAlertEnqueueStatusUid(uid: String, enqueuedToBeSent: Boolean) {
        val updatedAlerts = _physioAndAutoGenAlertList.value.map {
            if (it.uuid == uid) {
                it.copy(enqueuedToBeSent = enqueuedToBeSent)
            } else { it }
        }
        _physioAndAutoGenAlertList.emit(updatedAlerts)
    }

    suspend fun changePhysioAndAutoGenAlertToClearAndEnqueueStatusUid(uid: String, enqueuedToBeSent: Boolean) {
        val updatedAlerts = _physioAndAutoGenAlertList.value.map {
            if (it.uuid == uid) {
                it.copy(alertState = AlertState.CANCEL, enqueuedToBeSent = enqueuedToBeSent)
            } else { it }
        }
        _physioAndAutoGenAlertList.emit(updatedAlerts)
    }

    suspend fun removePhysioAndAutoGenAlertByUid(uid: String) {
        _physioAndAutoGenAlertList.emit(_physioAndAutoGenAlertList.value.filterNot { it.uuid == uid })
    }

    data class PhysioAndAutoGenAlertRequest(
        val uuid: String,
        val alertState: AlertState = AlertState.ALERT,
        val category: String,
        val description: String,
        val priority: Int,
        val sendSms: Boolean,
        val timeMillis: Long
    )

    private val _addPhysioAndAutoGenAlert = MutableSharedFlow<PhysioAndAutoGenAlertRequest>(extraBufferCapacity = 64)
    val addPhysioAndAutoGenAlert: SharedFlow<PhysioAndAutoGenAlertRequest> = _addPhysioAndAutoGenAlert

    private val _clearPhysioAndAutoGenAlert = MutableSharedFlow<ClearRequest>(extraBufferCapacity = 64)
    val clearPhysioAndAutoGenAlert: SharedFlow<ClearRequest> = _clearPhysioAndAutoGenAlert

    suspend fun addPhysioAndAutoGenAlertRequest(uid: String, category: String, description: String, priority: Int, sendSms: Boolean, timeMillis: Long) {
        Log.i(
            TAG,
            "addPhysioAndAutoGenAlertRequest emit start uid=$uid category=$category priority=$priority " +
                "sendSms=$sendSms subscriptionCount=${_addPhysioAndAutoGenAlert.subscriptionCount.value}",
        )
        requestInteractionPliRefresh(PliRefreshReason.PHYSIO_ALERT_ACTIVATED)
        _addPhysioAndAutoGenAlert.emit(PhysioAndAutoGenAlertRequest(uid, AlertState.ALERT, category, description, priority, sendSms, timeMillis))
        Log.i(
            TAG,
            "addPhysioAndAutoGenAlertRequest emit complete uid=$uid category=$category priority=$priority " +
                "sendSms=$sendSms subscriptionCount=${_addPhysioAndAutoGenAlert.subscriptionCount.value}",
        )
    }

    suspend fun clearPhysioAndAutoGenAlertRequestUid(uid: String) {
        _clearPhysioAndAutoGenAlert.emit(ClearRequest.Specific(uid))
        requestInteractionPliRefresh(PliRefreshReason.PHYSIO_ALERT_CLEARED)
    }

    suspend fun clearPhysioAndAutoGenAlertRequestLast() {
        _clearPhysioAndAutoGenAlert.emit(ClearRequest.Last)
        requestInteractionPliRefresh(PliRefreshReason.PHYSIO_ALERT_CLEARED)
    }

    suspend fun clearPhysioAndAutoGenAlertRequestAll() {
        _clearPhysioAndAutoGenAlert.emit(ClearRequest.All)
        requestInteractionPliRefresh(PliRefreshReason.PHYSIO_ALERT_CLEARED)
    }

    data class ReceivedUser(
        val uid: String,
        val type: String,
        val team: String,
        val title: String,
        val role: String,
        val timeReceived: Long,
        val staleTime: Long,
        val location: LocationSnapshot?,
        val source: String,
        val sourceServerId: String? = null,
        val receivedAt: Long = timeReceived,
        val chatEndpoint: String? = null,
        val isCamera: Boolean = false
    )

    private val receivedUserStore = SourceAwareCotStore(
        sourceIdOf = { item: ReceivedUser -> item.sourceServerId ?: NON_TAK_COT_SOURCE_ID },
        cotUidOf = ReceivedUser::uid,
        eventTimeOf = ReceivedUser::timeReceived,
        receivedAtOf = ReceivedUser::receivedAt,
        publicationScope = projectionScope,
        equivalent = { a, b ->
            a.copy(receivedAt = b.receivedAt) == b
        },
        onReduction = { coalesced, skipped ->
            AppDiagnostics.recordIngestReduction(ProcessingStage.USER_STORE, coalesced, skipped)
        },
        // Event time still wins. Equal-time mesh/relay copies must not flip back to
        // direct TAK just because the same PLI arrived over another transport later.
        sourcePriorityOf = { item ->
            when {
                item.sourceServerId == COMPANION_USER_SOURCE_ID -> 2
                item.sourceServerId?.startsWith("ditto:") == true -> 1
                else -> 0
            }
        },
        deferProjection = { key -> passiveSaDemand.shouldDefer(key.cotUid) },
        onDeferred = {
            passiveSaDemand.recordDeferred()
            AppDiagnostics.recordIngestReduction(ProcessingStage.SA_DEFERRED, 1, 0)
        }
    )
    val receivedUserList: StateFlow<List<ReceivedUser>> = receivedUserStore.visibleItems

    init {
        passiveSaDemand.setOnDemand {
            if (receivedUserStore.flushDeferred()) passiveSaDemand.recordFlush()
        }
    }

    fun receiveCompanionUserEvent(event: CotUserPositionEvent, receivedAt: Long): Boolean {
        pruneCompanionUsers(receivedAt)
        val key = SourceCotKey(COMPANION_USER_SOURCE_ID, event.uid)
        if (event.stale <= receivedAt) {
            receivedUserStore.item(key)?.takeIf { it.timeReceived <= event.time }?.let {
                receivedUserStore.remove(key)
            }
            return false
        }
        val copies = receivedUserStore.snapshot().filterKeys { it.sourceId == COMPANION_USER_SOURCE_ID }
        if (key !in copies && copies.size >= 512) {
            copies.minByOrNull { it.value.receivedAt }?.key?.let(receivedUserStore::remove)
        }
        return applyReceivedUserEventNow(event, "Companion ATAK", COMPANION_USER_SOURCE_ID, receivedAt)
    }

    fun removeCompanionUser(uid: String) {
        receivedUserStore.remove(SourceCotKey(COMPANION_USER_SOURCE_ID, uid))
    }

    fun clearCompanionUsers() {
        receivedUserStore.removeSource(COMPANION_USER_SOURCE_ID)
    }

    fun pruneCompanionUsers(now: Long = System.currentTimeMillis()) {
        receivedUserStore.snapshot().forEach { (key, user) ->
            if (key.sourceId == COMPANION_USER_SOURCE_ID && user.staleTime <= now) {
                receivedUserStore.remove(key)
            }
        }
    }

    suspend fun upsertReceivedUser(item: ReceivedUser): SourceCotUpsertResult =
        receivedUserStore.upsertWithResult(item)

    suspend fun removeReceivedUserByUid(uid: String) {
        receivedUserStore.removeUid(uid)
    }

    suspend fun clearReceivedUsers() {
        receivedUserStore.clear()
    }

    suspend fun applyReceivedUserEvent(
        event: CotUserPositionEvent,
        source: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis()
    ): Boolean = applyReceivedUserEventNow(event, source, sourceServerId, receivedAt)

    private fun applyReceivedUserEventNow(
        event: CotUserPositionEvent,
        source: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val sourceKey = sourceServerId ?: NON_TAK_COT_SOURCE_ID
        if (event.stale <= System.currentTimeMillis()) {
            val key = SourceCotKey(sourceKey, event.uid)
            val removed = if (sourceKey == COMPANION_USER_SOURCE_ID) {
                receivedUserStore.item(key)?.takeIf { it.timeReceived <= event.time }
                    ?.let { receivedUserStore.remove(key) }
            } else {
                receivedUserStore.removeIfNotOlder(key, event.time, receivedAt)
            }
            Log.d(
                TAG,
                "Map state user stale uid=${event.uid} sourceServerId=$sourceServerId eventTime=${event.time} " +
                    "receivedAt=$receivedAt removed=${removed != null} " +
                    "visibleSourceId=${receivedUserStore.visibleKey(event.uid)?.sourceId} " +
                    "activeChannelsAtIngest=${activeChannelsForLog(sourceServerId)} channelOwnership=unavailable"
            )
            return false
        }

        val item = ReceivedUser(
            uid = event.uid,
            type = event.type,
            team = event.team,
            role = event.role,
            title = event.detail.markerCallsign.orEmpty(),
            timeReceived = event.time,
            staleTime = event.stale,
            location = LocationSnapshot(
                timeMillis = event.time,
                lat = event.point.lat,
                lon = event.point.lon,
                hae = event.point.hae,
                ce = event.point.ce.toFloat(),
                le = event.point.le.toFloat(),
                course = null,
                speed = null
            ),
            source = source,
            sourceServerId = sourceServerId,
            receivedAt = receivedAt,
            chatEndpoint = event.detail.contact,
            isCamera = event.detail.isCamera
        )
        val result = receivedUserStore.upsertWithResult(item,
            coalesce = event.detail.canCoalesceTrack(event.type, event.how, sourceServerId),
            deferrable = event.detail.isPassiveTrack(event.type, sourceServerId))
        Log.d(
            TAG,
            "Map state user upsert uid=${event.uid} sourceServerId=$sourceServerId sourceAddress=$source " +
                "eventTime=${event.time} stale=${event.stale} receivedAt=$receivedAt applied=${result.applied} " +
                "visibleSourceId=${receivedUserStore.visibleKey(event.uid)?.sourceId} team=${event.team} " +
                "activeChannelsAtIngest=${activeChannelsForLog(sourceServerId)} channelOwnership=unavailable"
        )
        return true
    }

    enum class MarkerCotType {
        DROP, EDIT, CLEAR
    }

    // Backend-only use for markers
    data class DroppedMarker(
        val cotType: MarkerCotType,
        val uid: String,
        val markerType: MarkerType,
        val title: String,
        val remark: String,
        val timeDropped: Long,
        val location: LocationSnapshot?,
        val enqueuedToBeSent: Boolean,
        val waitingForUpdatedLocation: Boolean
    )

    data class ReceivedMarker(
        val uid: String,
        val markerType: String,
        val title: String,
        val timeReceived: Long,
        val staleTime: Long,
        val location: LocationSnapshot?,
        val source: String,
        val sourceServerId: String? = null,
        val receivedAt: Long = timeReceived,
        val circleRadiusMeters: Double? = null,
        val isCamera: Boolean = false
    )

    private val _droppedMarkerList = MutableStateFlow<List<DroppedMarker>>(emptyList())
    val droppedMarkerList: StateFlow<List<DroppedMarker>> = _droppedMarkerList

    // Backend-only mutators (UI never calls these)
    suspend fun appendDroppedMarker(item: DroppedMarker) {
        dittoPointLedger.created(item.uid, MainActivity.device_uid)
        deletedLocalPointUids.remove(item.uid)
        _droppedMarkerList.emit(_droppedMarkerList.value + item)
    }

    suspend fun changeDroppedMarkerCotTypeStatusByUid(uid: String, cotType: MarkerCotType, enqueuedToBeSent: Boolean, waitingForUpdatedLocation: Boolean) {
        val updatedMarkers = _droppedMarkerList.value.map {
            if (it.uid == uid) {
                it.copy(cotType = cotType, enqueuedToBeSent = enqueuedToBeSent, waitingForUpdatedLocation = waitingForUpdatedLocation)
            } else { it }
        }
        _droppedMarkerList.emit(updatedMarkers)
    }

    suspend fun changeDroppedMarkerLocationByUid(uid: String, location: LocationSnapshot?) {
        val updatedMarkers = _droppedMarkerList.value.map {
            if (it.uid == uid) {
                it.copy(location = location)
            } else { it }
        }
        _droppedMarkerList.emit(updatedMarkers)
    }

    suspend fun changeDroppedMarkerCotTypeByUid(uid: String, cotType: MarkerCotType) {
        val updatedMarkers = _droppedMarkerList.value.map {
            if (it.uid == uid) {
                it.copy(cotType = cotType)
            } else { it }
        }
        _droppedMarkerList.emit(updatedMarkers)
    }

    suspend fun removeMarkerByUid(uid: String) {
        _droppedMarkerList.emit(_droppedMarkerList.value.filterNot { it.uid == uid })
    }

    suspend fun storeDroppedMarkerList(droppedMarkerList: List<DroppedMarker>) {
        _droppedMarkerList.emit(droppedMarkerList)
    }

    private val receivedMarkerStore = SourceAwareCotStore(
        sourceIdOf = { item: ReceivedMarker -> item.sourceServerId ?: NON_TAK_COT_SOURCE_ID },
        cotUidOf = ReceivedMarker::uid,
        eventTimeOf = ReceivedMarker::timeReceived,
        receivedAtOf = ReceivedMarker::receivedAt,
        publicationScope = projectionScope,
        equivalent = { a, b ->
            val receiptControlsRetention = com.aitech.weartak_civ.cot.CotMessageParser.isGenericMapType(b.markerType)
            if (receiptControlsRetention) a == b else a.copy(receivedAt = b.receivedAt) == b
        },
        onReduction = { coalesced, skipped ->
            AppDiagnostics.recordIngestReduction(ProcessingStage.MARKER_STORE, coalesced, skipped)
        }
    )
    val receivedMarkerList: StateFlow<List<ReceivedMarker>> = receivedMarkerStore.visibleItems

    suspend fun upsertReceivedMarker(item: ReceivedMarker): SourceCotUpsertResult =
        receivedMarkerStore.upsertWithResult(item)

    suspend fun removeReceivedMarkerByUid(uid: String) {
        receivedMarkerStore.removeUid(uid)
    }

    suspend fun clearReceivedMarkers() {
        receivedMarkerStore.clear()
    }

    private val markerIngressMutex = Mutex()
    private val deletedLocalPointUids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun isDeletedLocalPoint(uid: String): Boolean = uid in deletedLocalPointUids
    val companionPointRevisions = CompanionPointRevisions()
    data class PointDeletionRequest(val uid: String, val revision: CompanionPointRevisions.Revision)
    private val _pointDeletionRequests = MutableSharedFlow<PointDeletionRequest>(extraBufferCapacity = 64)
    val pointDeletionRequests: SharedFlow<PointDeletionRequest> = _pointDeletionRequests
    private val _remotePointDeletions = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val remotePointDeletions: SharedFlow<String> = _remotePointDeletions
    val pointDeletionAcks = PointDeletionAcks()
    private val _pointDeletionResults = MutableSharedFlow<PointDeletionAcks.Result>(extraBufferCapacity = 64)
    /** Whether the paired phone acknowledged and applied a deletion this watch sent. */
    val pointDeletionResults: SharedFlow<PointDeletionAcks.Result> = _pointDeletionResults

    fun reportPointDeletionResult(result: PointDeletionAcks.Result) {
        Log.i(TAG, "Point deletion uid=${result.uid} outcome=${result.outcome}")
        _pointDeletionResults.tryEmit(result)
    }

    fun notifyLocalPointDeletion(uid: String, emitMirrorRequest: Boolean = true) {
        // The receiver of this request checks readiness and its connection generation.
        val revision = companionPointRevisions.current(uid, companionPointGeneration) ?: return
        receivedMarkerStore.removeIfNotOlder(SourceCotKey("companion-phone", uid),
            revision.time, System.currentTimeMillis())
        companionPointRevisions.forget(uid)
        if (!emitMirrorRequest) return
        if (_pointDeletionRequests.subscriptionCount.value == 0) {
            Log.d(TAG, "Point deletion local only: no active companion transport")
        } else if (!_pointDeletionRequests.tryEmit(PointDeletionRequest(uid, revision))) {
            Log.w(TAG, "Point deletion request buffer full")
        }
    }

    @Volatile var companionPointGeneration = -1L

    suspend fun deleteReceivedPointLocally(
        uid: String,
        bloodhounds: BloodhoundRepository,
    ): List<ReceivedMarker> =
        markerIngressMutex.withLock {
            if (receivedMarkerStore.visibleKey(uid)?.sourceId == "companion-phone") {
                companionPointRevisions.current(uid, companionPointGeneration)?.let {
                    bloodhounds.removeMarkerCopy(uid, "companion-phone", it.time, System.currentTimeMillis())
                }
                notifyLocalPointDeletion(uid)
            }
            // Preserve the existing received-point delete UX (all local UID copies).
            bloodhounds.removeTarget(uid)
            receivedMarkerStore.removeUid(uid)
        }

    suspend fun deleteDroppedPointLocally(
        uid: String,
        bloodhounds: BloodhoundRepository,
        emitMirrorRequest: Boolean = true,
    ): Boolean =
        markerIngressMutex.withLock {
            if (_droppedMarkerList.value.none { it.uid == uid }) return@withLock false
            try {
                dittoPointLedger.delete(uid, MainActivity.device_uid, System.currentTimeMillis())
            } catch (error: Exception) {
                Log.e(TAG, "Point clear blocked: could not persist Ditto deletion intent", error)
                return@withLock false
            }
            deletedLocalPointUids.add(uid)
            companionPointRevisions.current(uid, companionPointGeneration)?.let {
                bloodhounds.removeMarkerCopy(uid, "companion-phone", it.time, System.currentTimeMillis())
            }
            notifyLocalPointDeletion(uid, emitMirrorRequest)
            bloodhounds.removeLocalMarkerCopy(uid)
            removeMarkerByUid(uid)
            true
        }

    suspend fun receivePointDeletion(
        uid: String, token: String, generation: Long, bloodhounds: BloodhoundRepository
    ): Boolean = markerIngressMutex.withLock {
        val revision = companionPointRevisions.current(uid, generation)
            ?.takeIf { it.token == token } ?: return@withLock false
        val local = _droppedMarkerList.value.firstOrNull { it.uid == uid }
        if (revision.local && local?.let {
                it.enqueuedToBeSent || it.waitingForUpdatedLocation
            } == true) return@withLock false
        val key = SourceCotKey("companion-phone", uid)
        if (receivedMarkerStore.item(key)?.timeReceived?.let { it > revision.time } == true) {
            return@withLock false
        }

        val receivedAt = System.currentTimeMillis()
        bloodhounds.removeMarkerCopy(uid, "companion-phone", revision.time, receivedAt)
        val removed = receivedMarkerStore.removeIfNotOlder(key, revision.time, receivedAt) != null
        if (revision.local && local != null) {
            deletedLocalPointUids.add(uid)
            removeMarkerByUid(uid)
            bloodhounds.removeMarkerCopy(uid, null, revision.time, receivedAt)
            _remotePointDeletions.emit(uid)
        }
        companionPointRevisions.forget(uid)
        removed || (revision.local && local != null)
    }

    internal suspend fun receiveDittoPointDeletion(
        uid: String, sourceId: String, time: Long, bloodhounds: BloodhoundRepository
    ) = markerIngressMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (_droppedMarkerList.value.any { it.uid == uid } || isDeletedLocalPoint(uid)) return@withLock
        val receivedAt = System.currentTimeMillis()
        receivedMarkerStore.removeIfNotOlder(SourceCotKey(sourceId, uid), time, receivedAt)
        bloodhounds.removeMarkerCopy(uid, sourceId, time, receivedAt)
    }

    /** Serializes map and navigation ingestion across BLE and TAK receive paths. */
    suspend fun receiveMarkerEvent(
        event: CotMarkerEvent,
        bloodhoundRepository: BloodhoundRepository,
        source: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis(),
        companionRevision: CompanionPointRevisions.Revision? = null
    ): Boolean = markerIngressMutex.withLock {
        genericBloodhounds = bloodhoundRepository
        currentCoroutineContext().ensureActive()
        if (event.uid.isBlank() || event.time <= 0 || event.start <= 0 || event.stale <= 0) {
            return@withLock false
        }
        val now = System.currentTimeMillis()
        bloodhoundRepository.applyReceivedMarkerEvent(event, source, sourceServerId, receivedAt, now)
        val applied = applyReceivedMarkerEvent(event, source, sourceServerId, receivedAt, now)
        pruneGenericMarkers(now)
        if (applied && sourceServerId == "companion-phone") {
            companionPointRevisions.forget(event.uid)
            companionRevision?.let { companionPointRevisions.remember(event.uid, it) }
        }
        applied
    }

    suspend fun applyReceivedMarkerEvent(
        event: CotMarkerEvent,
        source: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis(),
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val sourceKey = sourceServerId ?: NON_TAK_COT_SOURCE_ID
        if (event.stale <= now) {
            val removed = receivedMarkerStore.removeIfNotOlder(
                key = SourceCotKey(sourceKey, event.uid),
                eventTime = event.time,
                receivedAt = receivedAt
            )
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(
                TAG,
                "Map state marker stale uid=${event.uid} sourceServerId=$sourceServerId eventTime=${event.time} " +
                    "receivedAt=$receivedAt removed=${removed != null} " +
                    "visibleSourceId=${receivedMarkerStore.visibleKey(event.uid)?.sourceId} " +
                    "activeChannelsAtIngest=${activeChannelsForLog(sourceServerId)} channelOwnership=unavailable"
            )
            return false
        }

        val item = ReceivedMarker(
            uid = event.uid,
            markerType = event.type,
            title = event.detail.markerCallsign?.takeIf(String::isNotBlank)
                ?: if (com.aitech.weartak_civ.cot.CotMessageParser.isGenericMapType(event.type)) event.uid else "",
            timeReceived = event.time,
            staleTime = event.stale,
            location = LocationSnapshot(
                timeMillis = event.time,
                lat = event.point.lat,
                lon = event.point.lon,
                hae = event.point.hae,
                ce = event.point.ce.toFloat(),
                le = event.point.le.toFloat(),
                course = null,
                speed = null
            ),
            source = source,
            sourceServerId = sourceServerId,
            receivedAt = receivedAt,
            circleRadiusMeters = event.detail.circleRadiusMeters,
            isCamera = event.detail.isCamera
        )
        val result = receivedMarkerStore.upsertWithResult(item,
            coalesce = event.detail.canCoalesceTrack(event.type, event.how, sourceServerId))
        if (com.aitech.weartak_civ.cot.CotMessageParser.isGenericMapType(event.type)) {
            pruneGenericMarkers(now)
            startGenericExpiry()
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(
            TAG,
            "Map state marker upsert uid=${event.uid} sourceServerId=$sourceServerId sourceAddress=$source " +
                "eventTime=${event.time} stale=${event.stale} receivedAt=$receivedAt applied=${result.applied} " +
                "visibleSourceId=${receivedMarkerStore.visibleKey(event.uid)?.sourceId} " +
                "activeChannelsAtIngest=${activeChannelsForLog(sourceServerId)} channelOwnership=unavailable"
        )
        return result.applied
    }

    private var genericBloodhounds: BloodhoundRepository? = null
    private var genericExpiryJob: kotlinx.coroutines.Job? = null

    private fun startGenericExpiry() = synchronized(receivedMarkerStore) {
        if (genericExpiryJob?.isActive != true) {
            genericExpiryJob = repoScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    markerIngressMutex.withLock { pruneGenericMarkers() }
                    val keepRunning = synchronized(receivedMarkerStore) {
                        val hasGeneric = receivedMarkerStore.snapshot().values.any {
                            com.aitech.weartak_civ.cot.CotMessageParser.isGenericMapType(it.markerType)
                        }
                        if (!hasGeneric) genericExpiryJob = null
                        hasGeneric
                    }
                    if (!keepRunning) break
                }
            }
        }
    }

    fun pruneGenericMarkers(now: Long = System.currentTimeMillis()) {
        receivedMarkerStore.pruneSelected(
            selected = { com.aitech.weartak_civ.cot.CotMessageParser.isGenericMapType(it.markerType) },
            expired = { it.staleTime <= now || now - it.receivedAt >= 15 * 60_000L },
            maxCount = 512
        ).forEach {
            genericBloodhounds?.removeMarkerCopy(it.uid, it.sourceServerId, it.timeReceived, now)
        }
    }

    suspend fun receiveCotDeletion(
        deletion: com.aitech.weartak_civ.cot.CotDeletion,
        sourceId: String?,
        bloodhounds: BloodhoundRepository,
        receivedAt: Long = System.currentTimeMillis()
    ) = markerIngressMutex.withLock {
        val source = sourceId ?: NON_TAK_COT_SOURCE_ID
        receivedUserStore.removeIfNotOlder(SourceCotKey(source, deletion.uid), deletion.time, receivedAt)
        receivedMarkerStore.removeIfNotOlder(SourceCotKey(source, deletion.uid), deletion.time, receivedAt)
        bloodhounds.removeMarkerCopy(deletion.uid, sourceId, deletion.time, receivedAt)
        synchronized(routeRemovals) {
            val key = source to deletion.uid
            routeRemovals[key] = maxOf(routeRemovals[key] ?: Long.MIN_VALUE, deletion.time)
            takRouteCopies.firstOrNull { it.uid == deletion.uid && it.serverId == source &&
                (it.eventTime ?: 0) <= deletion.time }?.let { removeTakRoute(source, it.uid) }
            routePersistence.save(_takRoutes.value.values, routeRemovals)
        }
    }

    data class SourceClearSummary(
        val serverId: String,
        val userUids: List<String>,
        val markerUids: List<String>,
        val geofenceUids: List<String>,
        val bloodhoundUids: List<String>
    ) {
        fun diagnosticSummary(): String =
            "serverId=$serverId users=${userUids.size}:${userUids.forLog()} " +
                "markers=${markerUids.size}:${markerUids.forLog()} " +
                "geofences=${geofenceUids.size}:${geofenceUids.forLog()} " +
                "bloodhounds=${bloodhoundUids.size}:${bloodhoundUids.forLog()}"

        private fun List<String>.forLog(): String =
            take(MAX_LOG_UIDS).joinToString(prefix = "[", postfix = if (size > MAX_LOG_UIDS) ",...]" else "]")

        private companion object {
            const val MAX_LOG_UIDS = 20
        }
    }

    /** Clears live state owned by one TAK Server while preserving chat and all other sources. */
    suspend fun clearSource(serverId: String): SourceClearSummary {
        val normalizedServerId = TakServerId.normalizeOrNull(serverId)
            ?: throw IllegalArgumentException("Invalid TAK server ID")
        val summary = SourceClearSummary(
            serverId = normalizedServerId,
            userUids = receivedUserStore.removeSource(normalizedServerId).map(ReceivedUser::uid),
            markerUids = receivedMarkerStore.removeSource(normalizedServerId).map(ReceivedMarker::uid),
            geofenceUids = GeofenceRepository.clearSource(normalizedServerId).map { it.uid },
            bloodhoundUids = (context.applicationContext as WearTakApplication).bhRepo
                .clearSource(normalizedServerId)
                .map { it.uid }
        )
        Log.i(
            TAG,
            "Cleared source-owned live state ${summary.diagnosticSummary()} " +
                "remainingVisibleUsers=${receivedUserList.value.size} " +
                "remainingVisibleMarkers=${receivedMarkerList.value.size} " +
                "channelOwnership=unavailable"
        )
        return summary
    }

    private fun activeChannelsForLog(serverId: String?): String {
        if (serverId == null) return "not-direct-tak"
        val server = takChannels.value.firstOrNull { it.serverId == serverId } ?: return "unknown"
        val active = server.channels.filter { it.active }
        return active.joinToString(prefix = "[", postfix = "]") { channel ->
            "${channel.name}{bitpos=${channel.bitPosition},direction=${channel.direction}}"
        }
    }

    enum class MarkerType(
        val displayName: String,
        val type: String,
        val icon: String
    ) {
        FRIENDLY("Friendly", "a-f-G", "COT_MAPPING_2525C/a-f/a-f-G"),
        UNKNOWN("Unknown", "a-u-G", "COT_MAPPING_2525C/a-u/a-u-G"),
        HOSTILE("Hostile", "a-h-G", "COT_MAPPING_2525C/a-h/a-h-G"),
        NEUTRAL("Neutral", "a-n-G", "COT_MAPPING_2525C/a-n/a-n-G");

        companion object {
            fun parse(input: String): MarkerType? {
                val s = input.trim()
                return entries.firstOrNull {
                    it.name.equals(s, ignoreCase = true) ||
                        it.displayName.equals(s, ignoreCase = true) ||
                        it.type.equals(s, ignoreCase = true)
                }
            }
        }
    }

    // --- request flows (UI -> backend) ---
    data class MarkerDropRequest(
        val markerType: MarkerType,
        val title: String,
        val remark: String,
        val timeMillis: Long,
        val uid: String = "",
        val lat: Double? = null,
        val lon: Double? = null
    )

    data class MarkerEditRequest(
        val uid: String,
        val markerType: MarkerType,
        val title: String,
        val remark: String,
        val updateLocation: Boolean = false,
        val timeMillis: Long
    )

    private val _markerDropRequests = MutableSharedFlow<MarkerDropRequest>(extraBufferCapacity = 64)
    val markerDropRequests: SharedFlow<MarkerDropRequest> = _markerDropRequests

    private val _markerEditRequests = MutableSharedFlow<MarkerEditRequest>(extraBufferCapacity = 64)
    val markerEditRequests: SharedFlow<MarkerEditRequest> = _markerEditRequests

    private val _clearMarkerRequests = MutableSharedFlow<ClearRequest>(extraBufferCapacity = 64)
    val clearMarkerRequests: SharedFlow<ClearRequest> = _clearMarkerRequests

    // UI-facing API
    suspend fun requestMarkerDrop(
        markerTypeRaw: String,
        title: String,
        remark: String,
        timeMillis: Long,
        lat: Double? = null,
        lon: Double? = null,
        uid: String = "",
    ): Boolean {
        val parsed = MarkerType.parse(markerTypeRaw) ?: return false // caller can handle false
        _markerDropRequests.emit(MarkerDropRequest(parsed, title.trimEnd { it <= ' ' }, remark, timeMillis, uid = uid, lat = lat, lon = lon))
        return true
    }

    /** Plugin/UI: update an existing dropped marker and enqueue CoT edit. Returns false if type invalid or uid missing. */
    suspend fun requestMarkerUpdate(
        markerTypeRaw: String,
        title: String,
        remark: String,
        timeMillis: Long,
        uid: String,
        lat: Double? = null,
        lon: Double? = null,
    ): Boolean {
        val parsed = MarkerType.parse(markerTypeRaw) ?: return false
        if (uid.isBlank()) return false
        val explicitLoc = if (lat != null && lon != null) {
            LocationSnapshot(
                timeMillis = timeMillis,
                lat = lat,
                lon = lon,
                hae = 0.0,
                ce = 0.0f,
                le = 0.0f,
                course = 0.0f,
                speed = 0.0f,
            )
        } else {
            null
        }
        var updatedMarker: DroppedMarker? = null
        _droppedMarkerList.update { list ->
            list.map { marker ->
                if (marker.uid == uid && marker.cotType != MarkerCotType.CLEAR) {
                    marker.copy(
                        cotType = MarkerCotType.EDIT,
                        markerType = parsed,
                        title = title,
                        remark = remark,
                        location = explicitLoc ?: marker.location,
                        enqueuedToBeSent = true,
                        waitingForUpdatedLocation = false,
                    ).also { updatedMarker = it }
                } else {
                    marker
                }
            }
        }
        if (updatedMarker == null) return false
        emitMarkerEditRequest(updatedMarker)
        return true
    }

    suspend fun requestMarkerClear(uid: String) {
        if (uid.isBlank()) return
        requestClearSpecific(uid)
    }

    suspend fun requestUpdateMarkerLocation(uid: String) {
        val loc = snapshotLocation()
        var updatedMarker: DroppedMarker? = null
        _droppedMarkerList.update { list ->
            list.map { marker ->
                if ((marker.uid == uid) && (marker.cotType != MarkerCotType.CLEAR)) {
                    marker.copy(cotType = MarkerCotType.EDIT, location = loc, enqueuedToBeSent = true, waitingForUpdatedLocation = true).also { updatedMarker = it }
                } else {
                    marker
                }
            }
        }
        if (null != updatedMarker) emitMarkerEditRequest(updatedMarker)
    }

    fun requestChangeMarkerTitle(uid: String, title: String) {
        var updatedMarker: DroppedMarker? = null
        _droppedMarkerList.update { list ->
            list.map { marker ->
                if ((marker.uid == uid) && (marker.cotType != MarkerCotType.CLEAR)) {
                    marker.copy(cotType = MarkerCotType.EDIT, enqueuedToBeSent = true, title = title).also { updatedMarker = it }
                } else {
                    marker
                }
            }
        }
        if (null != updatedMarker) emitMarkerEditRequest(updatedMarker)
    }

    fun requestChangeMarkerRemark(uid: String, remark: String) {
        var updatedMarker: DroppedMarker? = null
        _droppedMarkerList.update { list ->
            list.map { marker ->
                if (marker.uid == uid) {
                    if (marker.cotType != MarkerCotType.CLEAR) {
                        marker.copy(cotType = MarkerCotType.EDIT, enqueuedToBeSent = true, remark = remark).also { updatedMarker = it }
                    } else { marker }
                } else {
                    marker
                }
            }
        }
        if (null != updatedMarker) emitMarkerEditRequest(updatedMarker)
    }

    fun requestChangeMarkerType(uid: String, type: MarkerType) {
        var updatedMarker: DroppedMarker? = null
        _droppedMarkerList.update { list ->
            list.map { marker ->
                if (marker.uid == uid) {
                    if (marker.cotType != MarkerCotType.CLEAR) {
                        marker.copy(cotType = MarkerCotType.EDIT, enqueuedToBeSent = true, markerType = type).also { updatedMarker = it }
                    } else { marker }
                } else {
                    marker
                }
            }
        }
        if (null != updatedMarker) emitMarkerEditRequest(updatedMarker)
    }

    private fun emitMarkerEditRequest(marker: DroppedMarker?) {
        if (marker == null) return
        val markerType = MarkerType.parse(marker.markerType.type)
        if (markerType == null) {
            Log.w(TAG, "Skipping marker edit send request, unknown marker type: ${marker.markerType}")
            return
        }

        _markerEditRequests.tryEmit(
            MarkerEditRequest(
                uid = marker.uid,
                markerType = markerType,
                title = marker.title,
                remark = marker.remark,
                updateLocation = marker.waitingForUpdatedLocation,
                timeMillis = System.currentTimeMillis()
            )
        )
    }


    private val _receivedAtosTagList = MutableStateFlow<List<AtosCotPacket>>(emptyList())
    val receivedAtosTagList: StateFlow<List<AtosCotPacket>> = _receivedAtosTagList

    suspend fun upsertReceivedAtosTag(item: AtosCotPacket) {
        _receivedAtosTagList.update { list ->
            val existingIndex = list.indexOfFirst { it.uid == item.uid }
            if (existingIndex < 0) {
                list + item
            } else {
                list.toMutableList().apply { this[existingIndex] = item }
            }
        }
        Log.i(
            TAG,
            "ATOS tag upserted uid=${item.uid ?: "null"} type=${item.type ?: "null"} tagType=${item.atosDetail?.tagType ?: "null"} stale=${item.stale ?: "null"} listSize=${_receivedAtosTagList.value.size}"
        )
    }

    suspend fun removeReceivedAtosTagByUid(uid: String?) {
        _receivedAtosTagList.update { list -> list.filterNot { it.uid == uid } }
        Log.i(TAG, "ATOS tag removed uid=${uid ?: "null"} listSize=${_receivedAtosTagList.value.size}")
    }

    suspend fun clearReceivedAtosTags() {
        _receivedAtosTagList.emit(emptyList())
        Log.i(TAG, "ATOS tags cleared listSize=0")
    }

    suspend fun applyReceivedAtosTagEvent(
        event: AtosCotPacket,
    ): Boolean {
        val now = System.currentTimeMillis()
        val staleEpochMillis = parseAtosCotTimeToEpochMillis(event.stale)
        val eventTimeEpochMillis = parseAtosCotTimeToEpochMillis(event.time)
        var normalizedEvent = event
        if (staleEpochMillis != null) {
            if (staleEpochMillis <= now) {
                val senderTtlMillis = eventTimeEpochMillis?.let { staleEpochMillis - it }
                val skewAdjustedStale = senderTtlMillis?.takeIf { it > 0 && it <= ChronoUnit.DAYS.duration.toMillis() }
                    ?.let { now + it }

                if (skewAdjustedStale != null) {
                    normalizedEvent = event.copy(stale = Instant.ofEpochMilli(skewAdjustedStale).toString())
                    Log.w(
                        TAG,
                        "ATOS tag stale appears sender-clock-skewed uid=${event.uid ?: "null"} timeRaw=${event.time ?: "null"} staleRaw=${event.stale ?: "null"} staleEpochMillis=$staleEpochMillis now=$now ttlMillis=$senderTtlMillis normalizedStale=${normalizedEvent.stale}"
                    )
                } else {
                    Log.w(
                        TAG,
                        "ATOS tag dropped as stale uid=${event.uid ?: "null"} staleRaw=${event.stale ?: "null"} staleEpochMillis=$staleEpochMillis now=$now"
                    )
                    removeReceivedAtosTagByUid(event.uid)
                    return false
                }
            }
        } else if (!event.stale.isNullOrBlank()) {
            Log.w(
                TAG,
                "ATOS tag stale value could not be parsed uid=${event.uid ?: "null"} staleRaw=${event.stale}"
            )
        }
        upsertReceivedAtosTag(
            AtosCotPacket(
                version = normalizedEvent.version,
                uid = normalizedEvent.uid,
                type = normalizedEvent.type,
                time = normalizedEvent.time,
                start = normalizedEvent.start,
                stale = normalizedEvent.stale,
                how = normalizedEvent.how,
                point = normalizedEvent.point,
                contact = normalizedEvent.contact,
                track = normalizedEvent.track,
                atosDetail = normalizedEvent.atosDetail,
                link = normalizedEvent.link,
                hasArchive = normalizedEvent.hasArchive,
                rawXmlLength = normalizedEvent.rawXmlLength,
                warnings = normalizedEvent.warnings
            )
        )
        Log.i(
            TAG,
            "ATOS tag accepted uid=${normalizedEvent.uid ?: "null"} lat=${normalizedEvent.point?.lat ?: "null"} lon=${normalizedEvent.point?.lon ?: "null"} stale=${normalizedEvent.stale ?: "null"} warnings=${normalizedEvent.warnings.joinToString(separator = " | ")}"
        )
        return true
    }

    private fun parseAtosCotTimeToEpochMillis(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        value.toLongOrNull()?.let { numeric ->
            return if (numeric < 100_000_000_000L) numeric * 1000 else numeric
        }

        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            val withZ = if (value.endsWith("Z", ignoreCase = true)) value else "${value}Z"
            try {
                Instant.parse(withZ).toEpochMilli()
            } catch (_: Exception) {
                val trimmedFraction = withZ.substringBefore('.', withZ)
                try {
                    Instant.parse(trimmedFraction).toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }



    suspend fun requestClearSpecific(uid: String) =
        _clearMarkerRequests.emit(ClearRequest.Specific(uid))
    suspend fun requestClearLast() = _clearMarkerRequests.emit(ClearRequest.Last)
    suspend fun requestClearAll() = _clearMarkerRequests.emit(ClearRequest.All)

    fun recordHeartRateSample(heartRate: Int, measuredAtMillis: Long?) {
        selfStatusHeartRate.value = measuredAtMillis?.takeIf { it > 0 }?.let { PhysioSample(heartRate, it) }
    }

    suspend fun storeLatestHeartRate(heartRate: Int, measuredAtMillis: Long? = null) {
        Log.e("HR_REPO", "storeLatestHeartRate($heartRate)")
        recordHeartRateSample(heartRate, measuredAtMillis)
        _latestHeartRate.emit(heartRate)
    }

    suspend fun storeLatestActivityRecognized(activity: ActivityRecognized) {
        _latestActivityRecognized.emit(activity)
    }

    fun resetActivityRecognition() {
        _latestActivityRecognized.value = ActivityRecognized.UNKNOWN
        _latestActivityConfidence.value = 0
    }

    suspend fun storeLatestActivityConfidence(confidence: Int) {
        _latestActivityConfidence.emit(confidence)
    }

    suspend fun storeLatestSkinTemp(skinTemp: Float, measuredAtMillis: Long? = null) {
        selfStatusSkinTemperature.value = measuredAtMillis?.takeIf { it > 0 }?.let { PhysioSample(skinTemp, it) }
        _latestSkinTemp.emit(skinTemp)
    }

    suspend fun storeLatestSpo2(spo2: Double?) {
        _latestSpo2.emit(spo2)
    }

    suspend fun storeLatestSpo2TierEvent(event: Spo2TierEventPayload?) {
        _latestSpo2TierEvent.emit(event)
    }

    suspend fun storeLatestSpo2DebugState(state: Spo2DebugState?) {
        _latestSpo2DebugState.emit(state)
    }

    suspend fun setUserRiskProfile(profile: String) {
        CepProvider.getProfile(context).setUserRiskProfile(profile)
        CepProvider.getOrCreateEngine(context).updateSpo2Profile()
    }

    @Volatile private var lastValidLocation: Location? = null

    fun bestKnownLocation(): Location? =
        listOfNotNull(latestLocation.value?.takeIf { validLocationFix(it) }, lastValidLocation)
            .filter(::isCurrentLocationInput)
            .maxByOrNull { it.time }?.let { Location(it) }

    private fun validLocationFix(location: Location): Boolean =
        location.time > 0 && location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0

    suspend fun storeLatestLocation(location: Location) = locationInputWriteMutex.withLock {
        val selected = getLocationInput.first()
        synchronized(_latestLocation) {
            if (locationInputChanging || location.provider != selected?.provider ||
                location.elapsedRealtimeNanos < locationInputChangedNanos) {
                Log.i(TAG, "Ignoring location from previous or unselected input: ${location.provider}")
                return@withLock
            }
            currentLocationInput = selected
            if (validLocationFix(location) && location.time >= (lastValidLocation?.time ?: 0)) {
                lastValidLocation = Location(location)
            }
            _latestLocation.value = Location(location)
        }
    }

    suspend fun storeLatestBatteryPercentage(batteryPercentage: Int) {
        _latestBatteryPercentage.emit(batteryPercentage)
    }

    suspend fun storeLatestIsDeviceWorn(isDeviceWorn: Boolean, source: String = "unknown") {
        val previous = _latestIsDeviceWorn.value
        if (previous != isDeviceWorn) {
            Log.i("WearTAK-DeviceWorn", "worn $previous -> $isDeviceWorn (source=$source)")
        }
        _latestIsDeviceWorn.emit(isDeviceWorn)
        _latestDeviceWornObservation.value = isDeviceWorn
    }

    private val _latestDeviceWornObservation = MutableStateFlow<Boolean?>(null)
    val latestDeviceWornObservation: StateFlow<Boolean?> = _latestDeviceWornObservation

    suspend fun invalidateDeviceWornObservation() {
        Log.i("WearTAK-DeviceWorn", "Awaiting an actual off-body sensor observation")
        _latestDeviceWornObservation.value = null
        _latestIsDeviceWorn.emit(false)
    }

    val getCallsign: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CALLSIGN] ?: ""
    }

    val getStandaloneCallsign: Flow<String> = context.dataStore.data.map { prefs ->
        prefs.standaloneCallsign()
    }

    private fun Preferences.standaloneCallsign(): String =
        if (this[PHONE_MANAGED_SNAPSHOT_SAVED] == true) this[PHONE_MANAGED_PREVIOUS_CALLSIGN] ?: ""
        else this[CALLSIGN] ?: ""

    val phoneManagedSettingsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PHONE_MANAGED_SETTINGS_ENABLED] ?: false
    }

    private val phoneManagementSessionActive = MutableStateFlow(false)
    val phoneManagedSettingsLocked: Flow<Boolean> = kotlinx.coroutines.flow.combine(
        phoneManagedSettingsEnabled, phoneManagementSessionActive
    ) { enabled, active -> enabled && active }

    internal fun setPhoneManagementSessionActive(active: Boolean) {
        phoneManagementSessionActive.value = active
    }

    val phoneManagedCompanionAddress: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[PHONE_MANAGED_COMPANION_ADDRESS]
    }

    suspend fun enablePhoneManagedSettings(
        companionAddress: String,
        mayEnable: () -> Boolean = { true }
    ) {
        require(companionAddress.isNotBlank()) { "Companion address cannot be blank" }
        context.dataStore.edit { prefs ->
            check(mayEnable()) { "Companion connection changed before management activation" }
            prefs[PHONE_MANAGED_COMPANION_ADDRESS] = companionAddress
            prefs[PHONE_MANAGED_SETTINGS_ENABLED] = true
        }
    }

    /** Effective values of every setting the phone bond writes, after a snapshot restore. */
    data class PhoneManagedSettingsValues(
        val enableDynamicReporting: Boolean,
        val constantReportingInterval: Int,
        val callsign: String,
        val team: String,
        val teamValue: String,
        val role: String,
        val roleCategory: String
    ) {
        val reporting: Pair<Boolean, Int> get() = enableDynamicReporting to constantReportingInterval

        fun applyToRuntimeConstants() {
            MainActivity.Constants.enableDynamicReportingStrategy = enableDynamicReporting
            MainActivity.Constants.constantReportingInterval = constantReportingInterval
            MainActivity.Constants.callsign = callsign
            MainActivity.Constants.team = team
            MainActivity.Constants.teamValue = teamValue
            MainActivity.Constants.role = role
            MainActivity.Constants.category = roleCategory
        }
    }

    // Snapshots written before WEARTAK-168 only contain the two reporting keys.
    private fun Preferences.phoneManagedSnapshotSaved(): Boolean =
        this[PHONE_MANAGED_SNAPSHOT_SAVED] == true || contains(PHONE_MANAGED_PREVIOUS_DYNAMIC_REPORTING)

    private fun MutablePreferences.savePhoneManagedSnapshot() {
        this[PHONE_MANAGED_PREVIOUS_DYNAMIC_REPORTING] =
            this[ENABLE_DYNAMIC_REPORTING_STRATEGY] ?: DEFAULT_ENABLE_DYNAMIC_REPORTING
        this[PHONE_MANAGED_PREVIOUS_CONSTANT_INTERVAL] =
            this[CONSTANT_REPORTING_INTERVAL] ?: DEFAULT_CONSTANT_REPORTING_INTERVAL
        this[PHONE_MANAGED_PREVIOUS_CALLSIGN] = this[CALLSIGN] ?: ""
        this[PHONE_MANAGED_PREVIOUS_TEAM] = this[TEAM] ?: DEFAULT_TEAM
        this[PHONE_MANAGED_PREVIOUS_ROLE] = this[SELECTED_ROLE] ?: DEFAULT_ROLE
        this[PHONE_MANAGED_PREVIOUS_ROLE_CATEGORY] = this[SELECTED_CATEGORY] ?: DEFAULT_ROLE_CATEGORY
        this[PHONE_MANAGED_SNAPSHOT_SAVED] = true
    }

    private fun MutablePreferences.restoreSnapshotValues() {
        this[PHONE_MANAGED_PREVIOUS_DYNAMIC_REPORTING]?.let { this[ENABLE_DYNAMIC_REPORTING_STRATEGY] = it }
        this[PHONE_MANAGED_PREVIOUS_CONSTANT_INTERVAL]?.let { this[CONSTANT_REPORTING_INTERVAL] = it }
        this[PHONE_MANAGED_PREVIOUS_CALLSIGN]?.let { this[CALLSIGN] = it }
        this[PHONE_MANAGED_PREVIOUS_TEAM]?.let { this[TEAM] = it }
        this[PHONE_MANAGED_PREVIOUS_ROLE]?.let { this[SELECTED_ROLE] = it }
        this[PHONE_MANAGED_PREVIOUS_ROLE_CATEGORY]?.let { this[SELECTED_CATEGORY] = it }
        PHONE_MANAGED_SNAPSHOT_KEYS.forEach { remove(it) }
    }

    private fun Preferences.phoneManagedSettingsValues(): PhoneManagedSettingsValues {
        val team = this[TEAM] ?: DEFAULT_TEAM
        val names = (this[TEAM_NAMES] ?: DEFAULT_TEAM_LIST).split(',')
        val colors = (this[TEAM_COLORS] ?: DEFAULT_TEAM_LIST).split(',')
        return PhoneManagedSettingsValues(
            enableDynamicReporting = this[ENABLE_DYNAMIC_REPORTING_STRATEGY] ?: DEFAULT_ENABLE_DYNAMIC_REPORTING,
            constantReportingInterval = this[CONSTANT_REPORTING_INTERVAL] ?: DEFAULT_CONSTANT_REPORTING_INTERVAL,
            callsign = this[CALLSIGN] ?: "",
            team = team,
            teamValue = colors.getOrNull(names.indexOf(team)) ?: team,
            role = this[SELECTED_ROLE] ?: DEFAULT_ROLE,
            roleCategory = this[SELECTED_CATEGORY] ?: DEFAULT_ROLE_CATEGORY
        )
    }

    /**
     * A watch-side edit made while no live phone session holds the lock becomes the
     * watch's own value, so a later snapshot restore keeps it instead of reverting it.
     */
    private fun <T> MutablePreferences.recordWatchOwnedValue(
        previousKey: Preferences.Key<T>,
        value: T,
        identity: Boolean
    ) {
        if (phoneManagementSessionActive.value) return
        val saved = if (identity) this[PHONE_MANAGED_SNAPSHOT_SAVED] == true else phoneManagedSnapshotSaved()
        if (saved) this[previousKey] = value
    }

    private suspend fun publishPhoneManagedChanges(
        before: PhoneManagedSettingsValues,
        after: PhoneManagedSettingsValues
    ) {
        _enableDynamicReportingStrategy.emit(after.enableDynamicReporting)
        _constantReportingInterval.emit(after.constantReportingInterval)
        if (before.callsign != after.callsign) requestInteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED)
        if (before.team != after.team) requestInteractionPliRefresh(PliRefreshReason.TEAM_CHANGED)
        if (before.role != after.role) requestInteractionPliRefresh(PliRefreshReason.ROLE_CHANGED)
    }

    /**
     * Writes the phone's identity and reporting values. The watch's own values are
     * snapshotted first unless a snapshot already exists for this management period,
     * so repeated phone pushes never overwrite the saved originals.
     */
    suspend fun applyPhoneManagedSettings(
        callsign: String,
        team: String,
        role: String,
        constantReportingInterval: Int,
        mayApply: () -> Boolean = { true }
    ): PhoneManagedSettingsValues {
        require(callsign.isNotBlank() && team.isNotBlank() && role.isNotBlank()) {
            "Phone-managed settings require callsign, team, and role"
        }
        require(constantReportingInterval > 0) { "Reporting interval must be positive" }
        lateinit var before: PhoneManagedSettingsValues
        lateinit var after: PhoneManagedSettingsValues
        context.dataStore.edit { prefs ->
            check(mayApply()) { "Companion connection changed before phone-managed settings were applied" }
            check(prefs[PHONE_MANAGED_SETTINGS_ENABLED] == true) { "Phone management is not enabled" }
            before = prefs.phoneManagedSettingsValues()
            if (!prefs.phoneManagedSnapshotSaved()) prefs.savePhoneManagedSnapshot()
            prefs[ENABLE_DYNAMIC_REPORTING_STRATEGY] = false
            prefs[CONSTANT_REPORTING_INTERVAL] = constantReportingInterval
            prefs[CALLSIGN] = callsign
            prefs[TEAM] = team
            prefs[SELECTED_ROLE] = role
            after = prefs.phoneManagedSettingsValues()
        }
        publishPhoneManagedChanges(before, after)
        return after
    }

    suspend fun applyPhoneManagedIdentity(
        companionAddress: String,
        field: String,
        value: String,
        mayApply: () -> Boolean = { true }
    ): PhoneManagedSettingsValues {
        val change = requireNotNull(com.aitech.weartak_civ.command.IdentityProfileChange.parse(
            field, value, if (field == "team") MainActivity.teamEntriesValuesList.ltEntries else emptyList(),
            if (field == "role") {
                MainActivity.categoryRolesEntryLists.flatMap { c -> c.roles.map { c.category to it } }
            } else emptyList()
        )) { "Invalid $field; nothing changed" }
        lateinit var before: PhoneManagedSettingsValues
        lateinit var after: PhoneManagedSettingsValues
        context.dataStore.edit { prefs ->
            check(mayApply()) { "Companion connection changed before identity was applied" }
            check(prefs[PHONE_MANAGED_SETTINGS_ENABLED] == true &&
                prefs[PHONE_MANAGED_COMPANION_ADDRESS].equals(companionAddress, ignoreCase = true) &&
                prefs[PHONE_MANAGED_SNAPSHOT_SAVED] == true) {
                "Identity requires the active phone-managed owner and profile"
            }
            before = prefs.phoneManagedSettingsValues()
            change.apply(object : com.aitech.weartak_civ.command.IdentityProfileStore {
                override suspend fun setCallsign(value: String) { prefs[CALLSIGN] = value }
                override suspend fun setTeam(value: String) { prefs[TEAM] = value }
                override suspend fun setRole(category: String, role: String) {
                    prefs[SELECTED_CATEGORY] = category
                    prefs[SELECTED_ROLE] = role
                }
            })
            after = prefs.phoneManagedSettingsValues()
        }
        publishPhoneManagedChanges(before, after)
        return after
    }

    /**
     * Restores the watch's own values after the live phone session ends, keeping the
     * management opt-in so a fresh managed handshake can take over again.
     */
    suspend fun restorePhoneManagedSnapshot(
        mayRestore: () -> Boolean = { true }
    ): PhoneManagedSettingsValues? {
        val prefs = context.dataStore.data.first()
        if (!prefs.phoneManagedSnapshotSaved() && prefs[PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED] != true) return null
        var before: PhoneManagedSettingsValues? = null
        var after: PhoneManagedSettingsValues? = null
        context.dataStore.edit { mutable ->
            if (!mayRestore() || !mutable.phoneManagedSnapshotSaved()) return@edit
            before = mutable.phoneManagedSettingsValues()
            mutable.restoreSnapshotValues()
            after = mutable.phoneManagedSettingsValues()
        }
        if (mayRestore()) restorePhoneManagedTakServers()
        val restored = after ?: return null
        publishPhoneManagedChanges(requireNotNull(before), restored)
        return restored
    }

    suspend fun clearPhoneManagedSettingsForPeer(
        companionAddress: String,
        mayClear: () -> Boolean = { true }
    ): PhoneManagedSettingsValues? =
        clearPhoneManagedSettingsForPeerInternal(companionAddress, mayClear, false)

    suspend fun clearPhoneManagedSettingsFromNetworkSettings(
        companionAddress: String
    ): PhoneManagedSettingsValues? =
        clearPhoneManagedSettingsForPeerInternal(companionAddress, { true }, true)

    private suspend fun clearPhoneManagedSettingsForPeerInternal(
        companionAddress: String,
        mayClear: () -> Boolean,
        requireNetworkUnlocked: Boolean
    ): PhoneManagedSettingsValues? {
        var before: PhoneManagedSettingsValues? = null
        var after: PhoneManagedSettingsValues? = null
        context.dataStore.edit { prefs ->
            if (requireNetworkUnlocked && prefs[NETWORK_PREFERENCES_LOCKED] == true) {
                Log.w(TAG, "Companion management release rejected while network settings are locked")
                return@edit
            }
            check(mayClear()) { "Companion connection changed before management release" }
            val managedAddress = prefs[PHONE_MANAGED_COMPANION_ADDRESS]
            if (prefs[PHONE_MANAGED_SETTINGS_ENABLED] == true &&
                managedAddress.equals(companionAddress, ignoreCase = true)
            ) {
                before = prefs.phoneManagedSettingsValues()
                prefs.restoreSnapshotValues()
                prefs[PHONE_MANAGED_SETTINGS_ENABLED] = false
                prefs.remove(PHONE_MANAGED_COMPANION_ADDRESS)
                after = prefs.phoneManagedSettingsValues()
            }
        }
        val restored = after ?: return null
        setPhoneManagementSessionActive(false)
        restorePhoneManagedTakServers()
        publishPhoneManagedChanges(requireNotNull(before), restored)
        return restored
    }

    suspend fun storeCallsign(callsign: String) {
        var changed = false
        context.dataStore.edit { prefs ->
            changed = (prefs[CALLSIGN] ?: "") != callsign
            prefs[CALLSIGN] = callsign
            prefs.recordWatchOwnedValue(PHONE_MANAGED_PREVIOUS_CALLSIGN, callsign, identity = true)
        }
        if (changed) requestInteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED)
    }

    suspend fun storeTeamNames(teamNames: String) {
        context.dataStore.edit { prefs ->
            prefs[TEAM_NAMES] = teamNames
        }
    }

    val getTeamNames: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TEAM_NAMES] ?: "White,Yellow,Orange,Magenta,Red,Maroon,Purple,Dark Blue,Blue,Cyan,Teal,Green,Dark Green,Brown"
    }

    suspend fun storeTeamColors(teamColors: String) {
        context.dataStore.edit { prefs ->
            prefs[TEAM_COLORS] = teamColors
        }
    }

    val getTeamColors: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TEAM_COLORS] ?: "White,Yellow,Orange,Magenta,Red,Maroon,Purple,Dark Blue,Blue,Cyan,Teal,Green,Dark Green,Brown"
    }

    private val listType = object : TypeToken<List<CategoryRolesEntry>>() {}.type

    suspend fun storeCategoryRolesList(entries: List<CategoryRolesEntry>) {
        context.dataStore.edit { prefs ->
            prefs[CATEGORY_ROLES_ENTRIES_JSON] = gson.toJson(entries, listType)
        }
    }

    val getCategoryRolesList: Flow<List<CategoryRolesEntry>> =
        context.dataStore.data.map { prefs ->
            val jsonString = prefs[CATEGORY_ROLES_ENTRIES_JSON]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<CategoryRolesEntry>>() {}
                val type = typeToken.type
                gson.fromJson<List<CategoryRolesEntry>>(jsonString, type)
            }
        }

    suspend fun storeRoleCategory(category: String) =
        context.dataStore.edit { prefs ->
            prefs[SELECTED_CATEGORY] = category
            prefs.recordWatchOwnedValue(PHONE_MANAGED_PREVIOUS_ROLE_CATEGORY, category, identity = true)
        }

    val getRoleCategory: Flow<String> =
        context.dataStore.data.map {prefs ->
            prefs[SELECTED_CATEGORY] ?: DEFAULT_ROLE_CATEGORY
        }

    fun getRolesForCategory(cat: String): Flow<List<String>> =
        getCategoryRolesList.map { list ->
            list.firstOrNull { it.category == cat }?.roles ?: emptyList()
        }

//    suspend fun storeRolesList(rolesList: String) {
//        context.dataStore.edit { prefs ->
//            prefs[ROLES_LIST] = rolesList
//        }
//    }
//
//    val getRolesList: Flow<String> = context.dataStore.data.map { prefs ->
//        prefs[ROLES_LIST] ?: "Team Member,Team Lead,HQ,Sniper,Medic,Forward Observer,RTO,K9"
//    }

    val getTeam: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TEAM] ?: DEFAULT_TEAM
    }

    suspend fun storeTeam(team: String) {
        var changed = false
        context.dataStore.edit { prefs ->
            changed = (prefs[TEAM] ?: DEFAULT_TEAM) != team
            prefs[TEAM] = team
            prefs.recordWatchOwnedValue(PHONE_MANAGED_PREVIOUS_TEAM, team, identity = true)
        }
        if (changed) requestInteractionPliRefresh(PliRefreshReason.TEAM_CHANGED)
    }

    val getRole: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_ROLE] ?: DEFAULT_ROLE
    }

    suspend fun storeRole(role: String) {
        var changed = false
        context.dataStore.edit { prefs ->
            changed = (prefs[SELECTED_ROLE] ?: DEFAULT_ROLE) != role
            prefs[SELECTED_ROLE] = role
            prefs.recordWatchOwnedValue(PHONE_MANAGED_PREVIOUS_ROLE, role, identity = true)
        }
        if (changed) requestInteractionPliRefresh(PliRefreshReason.ROLE_CHANGED)
    }

    val getLicense: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LICENSE] ?: "License"
    }

    suspend fun storeLicense(license: String) {
        Log.i(TAG, " ********************* storeLicense Called changing from ${getLicense.first()} → $license")
        context.dataStore.edit { prefs ->
            prefs[LICENSE] = license
        }
    }

    suspend fun storeDittoAppId(appId: String) {
        context.dataStore.edit { prefs ->
            prefs[DITTO_APP_ID] = appId
        }
    }

    val getDittoAppId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DITTO_APP_ID] ?: "d0a0ed19-be6f-43ed-bb70-ff95653c98b7"
    }

    suspend fun storeDittoAuthToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[DITTO_AUTH_TOKEN] = token
        }
    }

    val getDittoAuthToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DITTO_AUTH_TOKEN] ?: "8b7efdee-5a96-409e-842c-1ed48045de24"
    }

    suspend fun storeCustomDeveloperConfigJson(jsonString: String) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_DEVELOPER_CONFIG_JSON] = jsonString
        }
    }

    val getCustomDeveloperConfigJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_DEVELOPER_CONFIG_JSON] ?: "{}"
    }

    val getEnableLocationService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_LOCATION_SERVICE] ?: DEFAULT_ENABLE_LOCATION_SERVICE
    }

    suspend fun storeEnableLocationService(enableLocationService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_LOCATION_SERVICE] = enableLocationService
        }
    }

    suspend fun initializeLocationServiceSetting(managedSetting: Boolean?): Boolean {
        val enabled = managedSetting ?: DEFAULT_ENABLE_LOCATION_SERVICE
        storeEnableLocationService(enabled)
        return enabled
    }

    val getEnablePhysiologicalServices: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_PHYSIOLOGICAL_SERVICES] ?: DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES
    }

    suspend fun storeEnablePhysiologicalServices(enablePhysiologicalServices: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_PHYSIOLOGICAL_SERVICES] = enablePhysiologicalServices
        }
    }

    val getEnableActRecService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ACTIVITY_RECOGNITION_SERVICE] ?: DEFAULT_ENABLE_ACTIVITY_RECOGNITION_SERVICE
    }

    suspend fun storeEnableActivityRecognitionService(enableActivityRecognitionService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ACTIVITY_RECOGNITION_SERVICE] = enableActivityRecognitionService
        }
    }

    val getEnableSkinTempService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_SKIN_TEMP_SERVICE] ?: DEFAULT_ENABLE_SKIN_TEMPERATURE_SERVICE
    }

    suspend fun storeEnableSkinTempService(enableSkinTempService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_SKIN_TEMP_SERVICE] = enableSkinTempService
        }
    }

    val getEnableHeartRateService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_HEART_RATE_SERVICE] ?: DEFAULT_ENABLE_HEART_RATE_SERVICE
    }

    suspend fun storeEnableHeartRateService(enableHeartRateService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_HEART_RATE_SERVICE] = enableHeartRateService
        }
    }

    val getEnableEnvironmentalAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENVIRONMENTAL_ALERTS_ENABLED] ?: false
    }

    suspend fun storeEnableEnvironmentalAlerts(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENVIRONMENTAL_ALERTS_ENABLED] = enable
        }
    }

    val effectiveEnableImmersionAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (prefs[ENVIRONMENTAL_ALERTS_ENABLED] ?: false) &&
            (prefs[ENABLE_IMMERSION_ALERTS] ?: DEFAULT_ENABLE_IMMERSION_ALERTS)
    }

    val effectiveEnableLowAtmPressureAlert: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (prefs[ENVIRONMENTAL_ALERTS_ENABLED] ?: false) &&
            (prefs[ENABLE_LOW_ATM_PRESSURE_ALERT] ?: DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT)
    }

    val effectiveEnableHighAtmPressureAlert: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (prefs[ENVIRONMENTAL_ALERTS_ENABLED] ?: false) &&
            (prefs[ENABLE_HIGH_ATM_PRESSURE_ALERT] ?: DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT)
    }

    val getEnableImmersionAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_IMMERSION_ALERTS] ?: DEFAULT_ENABLE_IMMERSION_ALERTS
    }

    suspend fun storeEnableImmersionAlerts(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_IMMERSION_ALERTS] = enable
        }
    }

    val getEnableLowAtmPressureAlert: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_LOW_ATM_PRESSURE_ALERT] ?: DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT
    }

    suspend fun storeEnableLowAtmPressureAlert(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_LOW_ATM_PRESSURE_ALERT] = enable
        }
    }

    val getLowAtmPressureAlertThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LOW_ATM_PRESSURE_ALERT_THRESHOLD] ?: DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
    }

    suspend fun storeLowAtmPressureAlertThreshold(threshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOW_ATM_PRESSURE_ALERT_THRESHOLD] = threshold
        }
    }

    val getEnableHighAtmPressureAlert: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_HIGH_ATM_PRESSURE_ALERT] ?: DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT
    }

    suspend fun storeEnableHighAtmPressureAlert(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_HIGH_ATM_PRESSURE_ALERT] = enable
        }
    }

    val getHighAtmPressureAlertThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HIGH_ATM_PRESSURE_ALERT_THRESHOLD] ?: DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
    }

    suspend fun storeHighAtmPressureAlertThreshold(threshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[HIGH_ATM_PRESSURE_ALERT_THRESHOLD] = threshold
        }
    }

    suspend fun storeEnablePhysioServices(enablePhysioService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_PHYSIOLOGICAL_SERVICES]    = enablePhysioService
//            prefs[ENABLE_SKIN_TEMP_SERVICE]  = enablePhysioService
//            prefs[ENABLE_HEART_RATE_SERVICE] = enablePhysioService
//            prefs[ENABLE_ACTIVITY_RECOGNITION_SERVICE] = enablePhysioService
        }
    }

    val getEnableDittoService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_DITTO_SERVICE] ?: DEFAULT_ENABLE_DITTO_SERVICE
    }

    suspend fun storeEnableDittoService(enableDittoService: Boolean) {
        storeNetworkToggle(ENABLE_DITTO_SERVICE, enableDittoService)
    }

    val getEnableChatService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_CHAT_SERVICE] ?: DEFAULT_ENABLE_CHAT_SERVICE
    }

    suspend fun storeEnableChatService(enableChatService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_CHAT_SERVICE] = enableChatService
        }
    }

    val getEnableAudioCaptureService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_AUDIO_CAP_SERVICE] ?: DEFAULT_ENABLE_AUDIO_CAP_SERVICE
    }

    suspend fun storeEnableAudioCaptureService(enableAudioCaptureService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_AUDIO_CAP_SERVICE] = enableAudioCaptureService
        }
    }

    val getEnableBetaFeatures: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BETA_FEATURES] ?: DEFAULT_ENABLE_BETA_FEATURES
    }

    suspend fun storeEnableBetaFeatures(enableBetaFeatures: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BETA_FEATURES] = enableBetaFeatures
        }
    }

    val getEnableOnnxGunshotService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ONNX_GUNSHOT_SERVICE] ?: DEFAULT_ENABLE_ONNX_GUNSHOT
    }

    suspend fun storeEnableOnnxGunshotService(enableOnnxGunshot: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ONNX_GUNSHOT_SERVICE] = enableOnnxGunshot
        }
    }

    val getOnnxGunshotTriggerThresholdPercent: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT] ?: DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
    }

    suspend fun storeOnnxGunshotTriggerThresholdPercent(thresholdPercent: Int) {
        context.dataStore.edit { prefs ->
            prefs[ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT] = thresholdPercent
        }
    }

    val getEnableOnnxUasService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ONNX_UAS_SERVICE] ?: DEFAULT_ENABLE_ONNX_UAS
    }

    suspend fun storeEnableOnnxUasService(enableOnnxUas: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ONNX_UAS_SERVICE] = enableOnnxUas
        }
    }

    val getOnnxUasTriggerThresholdPercent: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ONNX_UAS_TRIGGER_THRESHOLD_PERCENT] ?: DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
    }

    suspend fun storeOnnxUasTriggerThresholdPercent(thresholdPercent: Int) {
        context.dataStore.edit { prefs ->
            prefs[ONNX_UAS_TRIGGER_THRESHOLD_PERCENT] = thresholdPercent
        }
    }

    private val _almightyReportingIntervalSecs =
        MutableStateFlow(DEFAULT_CONSTANT_REPORTING_INTERVAL)

    val almightyReportingIntervalSecs: StateFlow<Int> =
        _almightyReportingIntervalSecs.asStateFlow()

    fun setAlmightyReportingInterval(intervalInSecs: Int) {
        if (_almightyReportingIntervalSecs.value == intervalInSecs) return
        _almightyReportingIntervalSecs.value = intervalInSecs
    }

    private val _reportingUpdateIntervalSecs = MutableStateFlow<Int>(DEFAULT_CONSTANT_REPORTING_INTERVAL)
    val reportingUpdateIntervalSecs: StateFlow<Int> = _reportingUpdateIntervalSecs

    suspend fun storeReportingUpdateInterval(reportingUpdateInterval: Int) {
        _reportingUpdateIntervalSecs.emit(reportingUpdateInterval)
    }

    val getEnableWifiBatteryOptimization: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_WIFI_BATTERY_OPTIMIZATION] ?: DEFAULT_ENABLE_WIFI_BATTERY_OPTIMIZATION
    }

    suspend fun storeEnableWifiBatteryOptimization(enableWifiBatteryOptimization: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_WIFI_BATTERY_OPTIMIZATION] = enableWifiBatteryOptimization
        }
    }

    val getEnableAllWifiBatteryOptimization: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION] ?: DEFAULT_ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION
    }

    suspend fun storeEnableAllWifiBatteryOptimization(enableAllWifiBatteryOptimization: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION] = enableAllWifiBatteryOptimization
        }
    }

    data class WifiSsidItem(val name: String)
    val gson = Gson()
    val getWifiSsidList: Flow<List<WifiSsidItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[WIFI_SSID_LIST_KEY]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson<List<WifiSsidItem>>(jsonString, type)
            }
        }

    suspend fun storeWifiSsidList(newList: List<WifiSsidItem>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[WIFI_SSID_LIST_KEY] = jsonString
        }
    }

    /*
    suspend fun addWifiSsidItem(item: WifiSsidItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[WIFI_SSID_LIST_KEY]
            Log.d(TAG, "addWifiSsidItem: $item, currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<WifiSsidItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            currentList.add(item)

            val newJson = gson.toJson(currentList)
            preferences[WIFI_SSID_LIST_KEY] = newJson
            Log.d(TAG, "addeWifiSsidItem newJson: $newJson")
        }
    }

    suspend fun removeWifiSsidItem(item: WifiSsidItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[WIFI_SSID_LIST_KEY]
            Log.d(TAG, "removeWifiSsidItem: $item, currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<WifiSsidItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            currentList.remove(item)

            preferences[WIFI_SSID_LIST_KEY] = gson.toJson(currentList)
            Log.d(TAG, "removeWifiSsidItem newJson: ${gson.toJson(currentList)}")
        }
    }

    suspend fun updateWifiSsidItem(oldItemIndex: Int, newItem: WifiSsidItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[WIFI_SSID_LIST_KEY]
            Log.d(TAG, "updateWifiSsidItem oldIndex($oldItemIndex), newItem($newItem), currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<WifiSsidItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            if (oldItemIndex != -1) {
                currentList[oldItemIndex] = newItem
            }

            val newJson = gson.toJson(currentList)
            preferences[WIFI_SSID_LIST_KEY] = newJson
            Log.d(TAG, "updateWifiSsidItem newJson: $newJson")
            preferences[WIFI_SSID_LIST_KEY] = gson.toJson(currentList)
        }
    }
     */

    data class ChatEventSnapshot(
        val uid: String,                     // stable per chat message
        val roomUid: String,                // e.g. "All Chat Rooms" or actual room id
        val roomTitle: String,              // human label for the room
        val message: String,                // chat text

        val time: CotEventTime,
        val location: LocationSnapshot?,    // might be null if you want to allow it
        val physio: PhysioSnapshot?,
        val system: SystemSnapshot,
        val isGroup: Boolean = false,
        val participantUids: List<String> = emptyList()
    )

    suspend fun snapshotChatEvent(
        roomUid: String,
        roomTitle: String,
        message: String,
        isGroup: Boolean = false,
        participantUids: List<String> = emptyList()
    ): ChatEventSnapshot {

        val timeSnap = CotEventTimeFactory.now(
            startOffset = CotTimeOffset(),
            staleOffset = CotTimeOffset(minutes = 15) // or shorter if you want
        )
        val locSnap = snapshotLocation()
        val systemSnap = snapshotSystem()
        val physioSnap = snapshotPhysio()
        val msgUid = UUID.randomUUID().toString()
        val room = chatRoomsFlow.value.firstOrNull { it.uid == roomUid }

        return ChatEventSnapshot(
            uid        = msgUid,
            roomUid    = roomUid,
            roomTitle  = roomTitle,
            message    = message,
            time       = timeSnap,
            location   = locSnap,
            physio     = physioSnap,
            system     = systemSnap,
            isGroup    = isGroup || roomUid == "All Chat Rooms" || room?.isGroup == true,
            participantUids = room?.participantUids ?: participantUids
        )
    }

    var newMessagesPresentPublicBoolean = MutableLiveData<Boolean>(false)

    private val _chatRoomsFlow = MutableStateFlow<List<ChatRoom>>(emptyList())
    val chatRoomsFlow: StateFlow<List<ChatRoom>> = _chatRoomsFlow

    /* ─── unread indicator ──────────────────────────────────────────────────── */
    private val _unreadCount      = MutableStateFlow(0)
    private val _hasUnread        = MutableStateFlow(false)
    private val _bloodhoundUnreadCount = MutableStateFlow(0)
    private val _hasUnreadBloodhounds = MutableStateFlow(false)

    val unreadCount : StateFlow<Int>  = _unreadCount        // if you need the exact number
    val hasUnread   : StateFlow<Boolean> = _hasUnread        // true/false for the badge
    val bloodhoundUnreadCount: StateFlow<Int> = _bloodhoundUnreadCount
    val hasUnreadBloodhounds: StateFlow<Boolean> = _hasUnreadBloodhounds

    suspend fun refreshUnreadCount() {
        val n = ChatService.getInstance()?.getUnreadMsgsCount() ?: 0
        _unreadCount.emit(n)
        _hasUnread.emit(n > 0)
    }

    fun setChatRooms(rooms: List<ChatRoom>) {
        val safeRooms = rooms.map { room ->
            val messages = room.messageList.filterNot {
                com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(it.message)
            }
            if (messages.size == room.messageList.size) room else room.copy(messageList = messages,
                newMessagesPresent = messages.any { it.timeReceived > room.timeLastClosed })
        }
        _chatRoomsFlow.value = safeRooms
        val anyUnread = safeRooms.any { it.newMessagesPresent && it.messageList.isNotEmpty() }
        newMessagesPresentPublicBoolean.postValue(anyUnread)
    }

    internal fun purgeSensitiveChatRooms() = setChatRooms(_chatRoomsFlow.value)

    suspend fun markRoomRead(roomId: String) {
        ChatService.markRoomReadStatic(roomId)
        val fresh = ChatService.getInstance()?.getInbox() ?: emptyList()
        _chatRoomsFlow.emit(fresh)

        // update indicator
        _unreadCount.emit(fresh.count { it.newMessagesPresent })
        _hasUnread.emit(fresh.any  { it.newMessagesPresent })
    }

    suspend fun incrementBloodhoundUnreadCount(uid: String) {
        _bloodhoundUnreadCount.update { current ->
            val updated = current + 1
            //Log.i(TAG, "Bloodhound unread incremented for uid=$uid count=$updated")
            updated
        }
        _hasUnreadBloodhounds.emit(_bloodhoundUnreadCount.value > 0)
    }

    suspend fun updateHasUnreadBloodhounds(hasUnread: Boolean) {
        _hasUnreadBloodhounds.emit(hasUnread)
    }


    suspend fun clearBloodhoundUnreadCount(reason: String) {
        val previous = _bloodhoundUnreadCount.value
        _bloodhoundUnreadCount.emit(0)
        _hasUnreadBloodhounds.emit(false)
        Log.i(TAG, "Bloodhound unread cleared reason=$reason previousCount=$previous")
    }

    // --- request flow (UI --> backend ChatService)
    data class SendChatRequest(
        val dstUid: String,
        val dstChatroom: String,
        val message: String,
        val timeMillis: Long,
        val isGroup: Boolean = false,
        val participantUids: List<String> = emptyList()
    ) {
        override fun toString() = "SendChatRequest(group=$isGroup, characters=${message.length})"
    }

    private val _chatRequestFromUI = MutableSharedFlow<SendChatRequest>(extraBufferCapacity = 64)
    val chatRequestFromUI: SharedFlow<SendChatRequest> = _chatRequestFromUI

    private val _automatedChatRequest = MutableSharedFlow<SendChatRequest>(extraBufferCapacity = 64)
    val automatedChatRequest: SharedFlow<SendChatRequest> = _automatedChatRequest

    suspend fun requestChatMessageSendFromUI(dstUid: String, dstChatroom: String, message: String, timeMillis: Long) {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(message)) {
            Log.w(TAG, "Sensitive enrollment commands cannot be sent from watch chat")
            return
        }
        _chatRequestFromUI.emit(SendChatRequest(dstUid, dstChatroom, message, timeMillis))
    }

    suspend fun requestAutomatedChatSend(dstUid: String, dstChatroom: String, message: String, timeMillis: Long) {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(message)) {
            Log.w(TAG, "Sensitive enrollment commands cannot enter automated chat")
            return
        }
        _automatedChatRequest.emit(SendChatRequest(dstUid, dstChatroom, message, timeMillis))
    }

    // backend --> UI
    sealed class BloodhoundUiCommand {
        data class OpenChatRoom(val roomUid: String) : BloodhoundUiCommand()
        data object CloseBloodhoundScreen : BloodhoundUiCommand()
    }

    private val _bloodhoundUiCommands = MutableSharedFlow<BloodhoundUiCommand>(extraBufferCapacity = 16)
    val bloodhoundUiCommands: SharedFlow<BloodhoundUiCommand> = _bloodhoundUiCommands

    suspend fun sendBloodhoundUiCommand(command: BloodhoundUiCommand) {
        _bloodhoundUiCommands.emit(command)
    }

    // ChatService --> CotDispatchService
    private val _chatRequestSend = MutableSharedFlow<SendChatRequest>(extraBufferCapacity = 64)
    val chatRequestSend: SharedFlow<SendChatRequest> = _chatRequestSend

    suspend fun sendChatMessage(dstUid: String, dstChatroom: String, message: String, timeMillis: Long) {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(message)) {
            Log.w(TAG, "Sensitive enrollment command excluded from chat dispatch")
            return
        }
        val room = chatRoomsFlow.value.firstOrNull { it.uid == dstUid }
        _chatRequestSend.emit(SendChatRequest(dstUid, dstChatroom, message, timeMillis,
            isGroup = dstUid == "All Chat Rooms" || room?.isGroup == true,
            participantUids = room?.participantUids.orEmpty()))
        requestInteractionPliRefresh(PliRefreshReason.CHAT_SENT)
    }

    suspend fun sendGroupChatReply(
        dstUid: String, title: String, message: String, timeMillis: Long, participants: List<String>
    ) {
        _chatRequestSend.emit(SendChatRequest(dstUid, title, message, timeMillis, true, participants))
        requestInteractionPliRefresh(PliRefreshReason.CHAT_SENT)
    }

    fun requestInteractionPliRefresh(reason: PliRefreshReason) {
        EventBus.getDefault().post(InteractionPliRefresh(reason))
    }

    enum class Endpoint { TAK_SERVER, DITTO, COMPANION_APP, SITX, MULTICAST }

    private val _endpointConnected = MutableStateFlow<Set<Endpoint>>(emptySet())
    val endpointConnected: StateFlow<Set<Endpoint>> = _endpointConnected
    data class OutboundReadyRoute(val endpoint: Endpoint, val generation: Long)
    private val outboundReadinessLock = Any()
    private var outboundReadyGeneration = 0L
    private val _outboundReadyRoutes = MutableStateFlow<Map<String, OutboundReadyRoute>>(emptyMap())
    val outboundReadyRoutes: StateFlow<Map<String, OutboundReadyRoute>> = _outboundReadyRoutes

    val anyEndpointConnected: StateFlow<Boolean> =
        endpointConnected.map { it.isNotEmpty() }
            .stateIn( scope = repoScope , SharingStarted.Eagerly, false)

    fun setEndpointConnected(endpoint: Endpoint, readyKeys: Set<String> = setOf(endpoint.name)) =
      synchronized(outboundReadinessLock) {
        _endpointConnected.update { set ->
            if (endpoint in set) set else set + endpoint
        }
        val keys = readyKeys.map { "${endpoint.name}:$it" }.toSet()
        _outboundReadyRoutes.update { current ->
            current.filter { (key, value) -> value.endpoint != endpoint || key in keys } +
                keys.associateWith { key ->
                    current[key] ?: OutboundReadyRoute(endpoint, ++outboundReadyGeneration)
                }
        }
    }

    fun setEndpointDisconnected(endpoint: Endpoint) = synchronized(outboundReadinessLock) {
        _endpointConnected.update { set ->
            if (endpoint !in set) set else set - endpoint
        }
        _outboundReadyRoutes.update { current -> current.filterValues { it.endpoint != endpoint } }
    }

    fun setCompanionAppConnected() {
        _companionAppConnected.value = true
        _companionEverReady.value = true
        repoScope.launch {
            try {
                context.dataStore.edit { it[COMPANION_EVER_READY] = true }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Could not persist Companion readiness history", error)
            }
        }
        companionTransportDebounce.connected()
        setEndpointConnected(Endpoint.COMPANION_APP)
    }

    suspend fun clearCompanionAppReadinessHistory() {
        _companionEverReady.value = false
        context.dataStore.edit { it[COMPANION_EVER_READY] = false }
    }

    fun setCompanionAppDisconnected() {
        companionTransportDebounce.disconnected(_companionAppConnected.value)
        _companionAppConnected.value = false
        setEndpointDisconnected(Endpoint.COMPANION_APP)
    }

    fun setCompanionTransportPending() {
        companionTransportDebounce.awaitingInitialConnection()
    }

    private val _companionAppConnected = MutableStateFlow<Boolean>(false)
    val isCompanionConnected: StateFlow<Boolean> = _companionAppConnected.asStateFlow()
    private val _companionEverReady = MutableStateFlow<Boolean?>(null)
    val companionEverReady: StateFlow<Boolean?> = _companionEverReady.asStateFlow()
    private val companionHistoryLoaded = CompletableDeferred<Unit>()
    init {
        repoScope.launch {
            try {
                val persistedValue = context.dataStore.data.first()[COMPANION_EVER_READY] ?: false
                _companionEverReady.compareAndSet(null, persistedValue)
                companionHistoryLoaded.complete(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never block service startup on this history; fall back to standalone behavior.
                Log.e(TAG, "Could not load persisted Companion readiness history", error)
                _companionEverReady.compareAndSet(null, false)
                companionHistoryLoaded.complete(Unit)
            }
        }
    }

    suspend fun awaitCompanionHistoryLoaded() {
        companionHistoryLoaded.await()
    }

    private val companionTransportDebounce = CompanionTransportDebounce(repoScope)
    val companionDisconnectGraceActive: StateFlow<Boolean> = companionTransportDebounce.graceActive
    val watchTransportSuppressed: Flow<Boolean> = combine(
        isCompanionConnected, companionDisconnectGraceActive
    ) { connected, graceActive -> connected || graceActive }.distinctUntilChanged()

    fun shouldSuppressWatchTransportNow(): Boolean =
        isCompanionConnected.value || companionDisconnectGraceActive.value

    fun resumeWatchTransportsImmediately(): Boolean =
        companionTransportDebounce.resumeImmediately()

    enum class SitxServerStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    private val _sitxStatus = MutableStateFlow(SitxServerStatus.DISCONNECTED)

    val sitxStatus: StateFlow<SitxServerStatus> = _sitxStatus.stateIn(
        repoScope,
        SharingStarted.Eagerly,
        SitxServerStatus.DISCONNECTED)

    fun setSitxConnecting() {
        _sitxStatus.value = SitxServerStatus.CONNECTING
    }

    fun setSitxConnected() {
        setEndpointConnected(Endpoint.SITX)
        _sitxStatus.value = SitxServerStatus.CONNECTED
    }

    fun setSitxDisconnected() {
        setEndpointDisconnected(Endpoint.SITX)
        _sitxStatus.value = SitxServerStatus.DISCONNECTED
    }
    private val _sitxGroups = MutableStateFlow<List<SitxTakService.TakServerItem>>(emptyList())
    val sitxGroups: StateFlow<List<SitxTakService.TakServerItem>> = _sitxGroups

    fun setSitxGroups(newGroups: List<SitxTakService.TakServerItem>) {
        _sitxGroups.value = newGroups
    }

    enum class SitxGroupStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED,
        FAILED
    }
    private val _sitxGroupStatus = MutableStateFlow<SitxGroupStatus>(SitxGroupStatus.CONNECTED)
    val sitxGroupStatus: StateFlow<SitxGroupStatus> = _sitxGroupStatus

    fun setSitxGroupStatus(status: SitxGroupStatus) {
        _sitxGroupStatus.value = status
    }

    val getSelectedSitxGroup: Flow<SitxTakService.TakServerItem?> =
        context.dataStore.data.map { preferences ->
            val jsonString = preferences[SELECTED_SITX_GROUP]
            if (jsonString.isNullOrEmpty()) {
                null
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<SitxTakService.TakServerItem>() {}
                val type = typeToken.type
                gson.fromJson<SitxTakService.TakServerItem>(jsonString, type)
            }
        }

    suspend fun setSelectedSitxGroup(item: SitxTakService.TakServerItem?) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(item)
            // Store in DataStore
            preferences[SELECTED_SITX_GROUP] = jsonString
        }
    }

//    val isCompanionConnected: StateFlow<Boolean> =
//        endpointConnected
//            .map { set -> set.contains(Endpoint.COMPANION_APP)}
//            .distinctUntilChanged()
//            .stateIn(repoScope, SharingStarted.Eagerly, false)

    fun isEndpointConnected(endpoint: Endpoint): Boolean =
        _endpointConnected.value.contains(endpoint)

    /**
     *  Returns the current unread-message total, 0 if the service isn’t running.
     */
    suspend fun getUnreadMsgsCount(): Int =
        ChatService.getInstance()?.getUnreadMsgsCount() ?: 0

    val getEnableSitxService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_SITX_SERVICE] ?: DEFAULT_ENABLE_SITX_SERVICE
    }

    suspend fun storeEnableSitxService(enableSitxService: Boolean) {
        storeNetworkToggle(ENABLE_SITX_SERVICE, enableSitxService)
    }

    val getEnableTakEndpoint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_TAK_ENDPOINT] ?: DEFAULT_ENABLE_TAK_ENDPOINT
    }

    suspend fun storeEnableTakEndpoint(enableTakEndpoint: Boolean) {
        storeNetworkToggle(ENABLE_TAK_ENDPOINT, enableTakEndpoint)
    }

    val getEnableEsriEndpoint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ESRI_ENDPOINT] ?: DEFAULT_ENABLE_ESRI_ENDPOINT
    }

    suspend fun storeEnableEsriEndpoint(enableEsriEndpoint: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ESRI_ENDPOINT] = enableEsriEndpoint
        }
    }

    val getEsriEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ESRI_ENDPOINT] ?: ""
    }

    suspend fun storeEsriEndpoint(esriEndpoint: String) {
        context.dataStore.edit { prefs ->
            prefs[ESRI_ENDPOINT] = esriEndpoint
        }
    }

    val getEsriReportingUpdateInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ESRI_REPORTING_UPDATE_INTERVAL] ?: DEFAULT_CONSTANT_REPORTING_INTERVAL
    }

    suspend fun storeEsriReportingUpdateInterval(esriReportingUpdateInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[ESRI_REPORTING_UPDATE_INTERVAL] = esriReportingUpdateInterval
        }
    }

    val getEnableTabletCommandEndpoint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_TABLET_COMMAND_ENDPOINT] ?: DEFAULT_ENABLE_TABLET_COMMAND_ENDPOINT
    }

    suspend fun storeEnableTabletCommandEndpoint(enableTabletCommandEndpoint: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_TABLET_COMMAND_ENDPOINT] = enableTabletCommandEndpoint
        }
    }

    val getTabletCommandEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TABLET_COMMAND_ENDPOINT] ?: ""
    }

    suspend fun storeTabletCommandEndpoint(tabletCommandEndpoint: String) {
        context.dataStore.edit { prefs ->
            prefs[TABLET_COMMAND_ENDPOINT] = tabletCommandEndpoint
        }
    }

    val getTabletCommandApikey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TABLET_COMMAND_APIKEY] ?: ""
    }

    suspend fun storeTabletCommandApikey(tabletCommandApikey: String) {
        context.dataStore.edit { prefs ->
            prefs[TABLET_COMMAND_APIKEY] = tabletCommandApikey
        }
    }


    val getTabletCommandReportingUpdateInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TABLET_COMMAND_REPORTING_UPDATE_INTERVAL] ?: DEFAULT_CONSTANT_REPORTING_INTERVAL
    }

    suspend fun storeTabletCommandReportingUpdateInterval(tabletCommandReportingUpdateInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[TABLET_COMMAND_REPORTING_UPDATE_INTERVAL] = tabletCommandReportingUpdateInterval
        }
    }

    data class TakServerItem(
        var isEnabled: Boolean,
        val name: String,
        val address: String,
        val port: Int,
        val isP12Cert: Boolean,
        val username: String,
        val password: String,
        val p12Cert: String,
        val p12CertPassword: String,
        val serverId: String? = null
    ) {
        override fun toString() = "TakServerItem(serverId=$serverId, enabled=$isEnabled, p12=$isP12Cert)"
    }

    internal enum class ChatEnrollmentStatus { ADDED, UPDATED, LOCKED, MANAGED, CONFLICT }
    internal data class ChatEnrollmentSave(val status: ChatEnrollmentStatus, val serverId: String? = null)

    internal fun mdmOwnsTakServers(): Boolean =
        context.getSystemService(android.content.RestrictionsManager::class.java)
            ?.applicationRestrictions?.containsKey("tak_server_list") == true

    internal suspend fun chatEnrollmentBlocked(): Boolean =
        phoneManagedSettingsLocked.first() || getNetworkPreferencesLocked.first() || mdmOwnsTakServers()

    internal suspend fun saveChatEnrollment(
        name: String, address: String, port: Int, username: String, password: String
    ): ChatEnrollmentSave {
        awaitTakServerMigration()
        return TakServerEndpointIdentity.mutationMutex.withLock {
            if (phoneManagedSettingsLocked.first() || getNetworkPreferencesLocked.first()) {
                return@withLock ChatEnrollmentSave(ChatEnrollmentStatus.LOCKED)
            }
            if (mdmOwnsTakServers()) return@withLock ChatEnrollmentSave(ChatEnrollmentStatus.MANAGED)
            val servers = readTakServerList()
            val endpoint = TakServerEndpointIdentity.key(address, port)
            val matches = servers.indices.filter { index ->
                servers[index].name.trim().equals(name.trim(), ignoreCase = true) ||
                    TakServerEndpointIdentity.key(servers[index]) == endpoint
            }
            if (matches.size > 1) return@withLock ChatEnrollmentSave(ChatEnrollmentStatus.CONFLICT)
            val index = matches.singleOrNull()
            val id = index?.let { servers[it].serverId } ?: TakServerId.ensureUnique(
                null, servers.mapNotNullTo(mutableSetOf(), TakServerItem::serverId))
            val existing = index?.let { servers[it] }
            val item = if (existing == null) {
                TakServerItem(true, name, address, port, false, username, password, "", "", id)
            } else {
                existing.copy(name = name, address = address, port = port, username = username,
                    password = password, p12Cert = if (existing.isP12Cert) existing.p12Cert else "",
                    p12CertPassword = if (existing.isP12Cert) existing.p12CertPassword else "")
            }
            if (index == null) servers.add(item) else servers[index] = item
            // Policy can change while migration/decryption suspends.
            if (phoneManagedSettingsLocked.first() || getNetworkPreferencesLocked.first()) {
                return@withLock ChatEnrollmentSave(ChatEnrollmentStatus.LOCKED)
            }
            if (mdmOwnsTakServers()) return@withLock ChatEnrollmentSave(ChatEnrollmentStatus.MANAGED)
            writeTakServerList(servers, "saveChatEnrollment")
            ChatEnrollmentSave(if (index == null) ChatEnrollmentStatus.ADDED else ChatEnrollmentStatus.UPDATED, id)
        }
    }

    internal suspend fun installChatP12(
        serverId: String,
        p12: com.aitech.weartak_civ.command.ValidatedChatP12,
        apply: suspend () -> Unit
    ): Boolean {
        awaitTakServerMigration()
        return TakServerEndpointIdentity.mutationMutex.withLock {
            if (phoneManagedSettingsLocked.first() || getNetworkPreferencesLocked.first()) return@withLock false
            val before = readTakServerList()
            val index = before.indexOfFirst { it.serverId == serverId }
            if (index < 0) return@withLock false
            val after = before.toMutableList().apply {
                this[index] = this[index].copy(isP12Cert = true, p12Cert = p12.base64, p12CertPassword = p12.password)
            }
            try {
                writeTakServerList(after, "installChatP12")
                apply()
                true
            } catch (error: Exception) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        writeTakServerList(before, "rollbackChatP12")
                        apply()
                    }
                } catch (_: Exception) {
                    Log.e(TAG, "Chat P12 rollback failed; configuration requires attention")
                    throw IllegalStateException("P12 recovery failed; configuration requires attention")
                }
                if (error is CancellationException) throw error
                Log.e(TAG, "Chat P12 apply failed; previous credentials restored")
                false
            }
        }
    }

    enum class TakServerAddResult {
        ADDED,
        DUPLICATE
    }

    enum class TakServerUpdateResult {
        UPDATED,
        DUPLICATE,
        INVALID_INDEX
    }

    private fun normalizeP12Password(password: String): String {
        return if (password.isEmpty()) "atakatak" else password
    }

    private fun normalizeP12Cert(cert: String): String =
        cert
            .replace("\\/", "/")
            .replace("\r", "")
            .replace("\n", "")
            .trim()

    private fun normalizeTakServerItem(
        item: TakServerItem,
        preferredServerId: String? = null
    ): TakServerItem {
        val normalizedCert = normalizeP12Cert(item.p12Cert)
        val normalizedServerId = TakServerId.ensure(preferredServerId ?: item.serverId)
        if (!item.isP12Cert) {
            return item.copy(
                p12Cert = normalizedCert,
                serverId = normalizedServerId
            )
        }
        val normalizedPassword = normalizeP12Password(item.p12CertPassword)
        return item.copy(
            p12Cert = normalizedCert,
            p12CertPassword = normalizedPassword,
            serverId = normalizedServerId
        )
    }

    private fun normalizeTakServerList(list: List<TakServerItem>): List<TakServerItem> {
        val usedIds = mutableSetOf<String>()
        return list.map { item ->
            val uniqueId = TakServerId.ensureUnique(item.serverId, usedIds)
            normalizeTakServerItem(item, preferredServerId = uniqueId)
        }
    }

    val getTakServerList: Flow<List<TakServerItem>> = flow {
        awaitTakServerMigration()
        emitAll(
            takServerSecretsRepository.takServerListJson.map { jsonString ->
                if (jsonString.isNullOrEmpty()) {
                    Log.i(TAG, "getTakServerList: empty secure store")
                    emptyList()
                } else {
                    Log.i(TAG, "getTakServerList: decrypt OK (len=${jsonString.length})")
                    // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                    val typeToken = object : TypeToken<List<TakServerItem>>() {}
                    val type = typeToken.type
                    normalizeTakServerList(gson.fromJson<List<TakServerItem>>(jsonString, type))
                }
            }
        )
    }

    suspend fun awaitTakServerList(): List<TakServerItem> {
        awaitTakServerMigration()
        return parseTakServerRead(takServerSecretsRepository.takServerListRead.first())
    }

    suspend fun tryAwaitTakServerList(): List<TakServerItem>? {
        return try {
            awaitTakServerList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            Log.e(TAG, "tryAwaitTakServerList: TAK list unavailable", error)
            null
        }
    }

    val getTakServerListReady: Flow<List<TakServerItem>> = flow {
        awaitTakServerMigration()
        emitAll(takServerSecretsRepository.takServerListRead.map(::parseTakServerRead))
    }

    private suspend fun awaitTakServerMigration() {
        (context.applicationContext as? WearTakApplication)?.awaitTakServerMigration()
    }

    suspend fun storeTakServerList(newList: List<TakServerItem>) {
        storeTakServerList(newList, watchOwned = true)
    }

    private suspend fun storeTakServerList(newList: List<TakServerItem>, watchOwned: Boolean) {
        awaitTakServerMigration()
        TakServerEndpointIdentity.mutationMutex.withLock {
            val currentList = readTakServerList()
            val deduplicatedList = newList.distinctBy(TakServerEndpointIdentity::key)
            val matches = TakServerManagedReconciler.matchExistingIndexes(
                deduplicatedList,
                currentList
            )
            val usedIds = mutableSetOf<String>()
            val uniqueList = deduplicatedList.mapIndexed { index, item ->
                val existingId = matches[index]?.let { currentList[it].serverId }
                val uniqueId = TakServerId.ensureUnique(existingId ?: item.serverId, usedIds)
                normalizeTakServerItem(item, preferredServerId = uniqueId)
            }
            val jsonString = gson.toJson(uniqueList)
            Log.i(TAG, "storeTakServerList: writing list size=${uniqueList.size}, len=${jsonString.length}")
            takServerSecretsRepository.setTakServerListJson(jsonString)
            if (watchOwned) recordWatchOwnedTakServers(jsonString)
        }

//        context.dataStore.edit { preferences ->
//            // Convert list to JSON
//            val jsonString = gson.toJson(newList)
//            // Store in DataStore
//            preferences[TAK_SERVER_LIST_KEY] = jsonString
//        }
    }

    suspend fun storeProvisionedTakServerList(
        newList: List<TakServerItem>
    ): TakServerConfigurationRefresh {
        storeTakServerList(newList, watchOwned = false)
        return requestTakServerConfigurationRefresh()
    }

    private suspend fun requestTakServerConfigurationRefresh(): TakServerConfigurationRefresh =
        TakServerConfigurationRefresh().also { refresh ->
            takServerConfigurationRefreshChannel.send(refresh)
        }

    /**
     * Saves the watch's own TAK server list, including certificates, in encrypted storage
     * before a phone bond first replaces it. Existing snapshots are never overwritten, and
     * management periods that began before server snapshots existed keep their servers.
     */
    suspend fun snapshotTakServersForPhoneManagement() {
        awaitTakServerMigration()
        TakServerEndpointIdentity.mutationMutex.withLock {
            val prefs = context.dataStore.data.first()
            if (prefs[PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED] == true || prefs.phoneManagedSnapshotSaved()) {
                return@withLock
            }
            val ownServers = gson.toJson(readTakServerList())
            takServerSecretsRepository.setPhoneManagedSnapshotJson(ownServers)
            context.dataStore.edit { it[PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED] = true }
            Log.i(TAG, "Saved watch TAK servers before phone management")
        }
    }

    /**
     * Restores the TAK server list saved before phone management and asks the TAK
     * service to reconnect. Returns null when no server snapshot exists.
     */
    private suspend fun restorePhoneManagedTakServers(): TakServerConfigurationRefresh? {
        if (context.dataStore.data.first()[PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED] != true) return null
        awaitTakServerMigration()
        val restored = TakServerEndpointIdentity.mutationMutex.withLock {
            if (context.dataStore.data.first()[PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED] != true) {
                return@withLock false
            }
            val snapshot = when (val read = takServerSecretsRepository.phoneManagedSnapshotRead.first()) {
                null -> null
                is DecryptResult.Success -> read.plaintext
                is DecryptResult.Failure -> {
                    Log.e(TAG, "Saved watch TAK servers are unreadable; keeping current servers", read.error)
                    null
                }
            }
            if (snapshot != null) {
                takServerSecretsRepository.setTakServerListJson(snapshot)
                MainActivity.Constants.takServerList =
                    parseTakServerRead(DecryptResult.Success(0, snapshot))
            }
            takServerSecretsRepository.setPhoneManagedSnapshotJson(null)
            context.dataStore.edit { it.remove(PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED) }
            snapshot != null
        }
        if (!restored) return null
        Log.i(TAG, "Restored watch TAK servers after phone management")
        return requestTakServerConfigurationRefresh()
    }

    suspend fun addTakServerItem(item: TakServerItem): TakServerAddResult {
        awaitTakServerMigration()
        return TakServerEndpointIdentity.mutationMutex.withLock {
            val currentList = readTakServerList()
            val usedIds = currentList.mapNotNullTo(mutableSetOf(), TakServerItem::serverId)
            val normalizedItem = normalizeTakServerItem(
                item,
                preferredServerId = TakServerId.ensureUnique(item.serverId, usedIds)
            )
            val duplicate = currentList.any {
                TakServerEndpointIdentity.key(it) == TakServerEndpointIdentity.key(normalizedItem)
            }
            if (duplicate) {
                Log.w(TAG, "addTakServerItem: duplicate endpoint=${TakServerEndpointIdentity.key(normalizedItem)}")
                return@withLock TakServerAddResult.DUPLICATE
            }
            currentList.add(normalizedItem)
            writeTakServerList(currentList, "addTakServerItem")
            TakServerAddResult.ADDED
        }
    }

//        context.dataStore.edit { preferences ->
//            val currentJson = preferences[TAK_SERVER_LIST_KEY]
//            Log.d(TAG, "addTakServerItem: $item, currJsonList: ${currentJson.toString()}")
//            val currentList: MutableList<TakServerItem> = if (!currentJson.isNullOrEmpty()) {
//                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
//                val typeToken = object : TypeToken<List<TakServerItem>>() {}
//                val type = typeToken.type
//                gson.fromJson(currentJson, type)
//            } else {
//                mutableListOf()
//            }
//            currentList.add(item)
//
//            val newJson = gson.toJson(currentList)
//            preferences[TAK_SERVER_LIST_KEY] = newJson
//            Log.d(TAG, "addTakServerItem newJson: $newJson")
//        }
    suspend fun removeTakServerItem(item: TakServerItem) {
        awaitTakServerMigration()
        TakServerEndpointIdentity.mutationMutex.withLock {
            val currentList = readTakServerList()
            val endpointKey = TakServerEndpointIdentity.key(item)
            currentList.removeAll { TakServerEndpointIdentity.key(it) == endpointKey }
            writeTakServerList(currentList, "removeTakServerItem")
        }

//        context.dataStore.edit { preferences ->
//            val currentJson = preferences[TAK_SERVER_LIST_KEY]
//            Log.d(TAG, "removeTakServerItem: $item, currJsonList: ${currentJson.toString()}")
//            val currentList: MutableList<TakServerItem> = if (!currentJson.isNullOrEmpty()) {
//                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
//                val typeToken = object : TypeToken<List<TakServerItem>>() {}
//                val type = typeToken.type
//                gson.fromJson(currentJson, type)
//            } else {
//                mutableListOf()
//            }
//
//            currentList.remove(item)
//
//            preferences[TAK_SERVER_LIST_KEY] = gson.toJson(currentList)
//            Log.d(TAG, "removeTakServerItem newJson: ${gson.toJson(currentList)}")
//        }
    }

    // updateTakServerItem will act as addTakServerItem if oldItemIndex is greater than or equal to size of already stored list
    suspend fun updateTakServerItem(
        oldItemIndex: Int,
        newItem: TakServerItem
    ): TakServerUpdateResult {
        awaitTakServerMigration()
        return TakServerEndpointIdentity.mutationMutex.withLock {
            val currentList = readTakServerList()
            if (oldItemIndex !in currentList.indices) {
                return@withLock TakServerUpdateResult.INVALID_INDEX
            }
            val normalizedItem = normalizeTakServerItem(
                newItem,
                preferredServerId = currentList[oldItemIndex].serverId
            )
            val duplicateIndex = currentList.indexOfFirst {
                TakServerEndpointIdentity.key(it) == TakServerEndpointIdentity.key(normalizedItem) &&
                    it !== currentList[oldItemIndex]
            }
            if (duplicateIndex >= 0) {
                Log.w(TAG, "updateTakServerItem: duplicate endpoint=${TakServerEndpointIdentity.key(normalizedItem)}")
                return@withLock TakServerUpdateResult.DUPLICATE
            }
            currentList[oldItemIndex] = normalizedItem
            writeTakServerList(currentList, "updateTakServerItem")
            TakServerUpdateResult.UPDATED
        }
    }

    private suspend fun writeTakServerList(list: List<TakServerItem>, operation: String) {
        val json = gson.toJson(normalizeTakServerList(list))
        Log.i(TAG, "$operation: writing list size=${list.size}, len=${json.length}")
        takServerSecretsRepository.setTakServerListJson(json)
        recordWatchOwnedTakServers(json)
    }

    /** Watch-side server edits made while no live phone session holds the lock survive a restore. */
    private suspend fun recordWatchOwnedTakServers(json: String) {
        if (phoneManagementSessionActive.value) return
        if (context.dataStore.data.first()[PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED] != true) return
        takServerSecretsRepository.setPhoneManagedSnapshotJson(json)
    }

    private suspend fun readTakServerList(): MutableList<TakServerItem> =
        parseTakServerRead(takServerSecretsRepository.takServerListRead.first()).toMutableList()

    private fun parseTakServerRead(result: DecryptResult?): List<TakServerItem> {
        val json = when (result) {
            null -> null
            is DecryptResult.Success -> result.plaintext
            is DecryptResult.Failure -> {
                Log.e(TAG, "parseTakServerRead: encrypted store unavailable", result.error)
                throw IllegalStateException("Unable to decrypt TAK server list", result.error)
            }
        }
        if (json.isNullOrEmpty()) return emptyList()
        val type = object : TypeToken<List<TakServerItem>>() {}.type
        return normalizeTakServerList(gson.fromJson<List<TakServerItem>>(json, type))
//        context.dataStore.edit { preferences ->
//            val currentJson = preferences[TAK_SERVER_LIST_KEY]
//            Log.d(TAG, "updateTakServerItem oldIndex($oldItemIndex), newItem($newItem), currJsonList: ${currentJson.toString()}")
//            val currentList: MutableList<TakServerItem> = if (!currentJson.isNullOrEmpty()) {
//                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
//                val typeToken = object : TypeToken<List<TakServerItem>>() {}
//                val type = typeToken.type
//                gson.fromJson(currentJson, type)
//            } else {
//                mutableListOf()
//            }
//
//            val currentListSize = currentList.size
//            if (oldItemIndex != -1) {
//                if (oldItemIndex < currentListSize) {
//                    currentList[oldItemIndex] = newItem
//                } else {
//                    currentList.add(newItem)
//                    if (oldItemIndex != currentListSize) {
//                        Log.w(TAG, "updateTakServerItem: WARNING index($oldItemIndex) passed is greater than stored list size($currentListSize)")
//                    }
//                }
//            }
//
//            val newJson = gson.toJson(currentList)
//            preferences[TAK_SERVER_LIST_KEY] = newJson
//            Log.d(TAG, "updateTakServerItem newJson: $newJson")
//            preferences[TAK_SERVER_LIST_KEY] = gson.toJson(currentList)
//        }
    }
    // Saves the selected PDL option
    suspend fun storeDittoPdlSource(source: DittoPdlSource) {
        context.dataStore.edit { preferences ->
            preferences[DITTO_PDL_SOURCE] = source.name
        }
    }

    // Saves the Custom PDL entered by the user
    suspend fun storeDittoCustomPdl(pdl: String) {
        context.dataStore.edit { preferences ->
            preferences[DITTO_CUSTOM_PDL] = pdl.trim()
        }
    }

    fun communityDittoPdl(): String = DEFAULT_COMMUNITY_PDL

    suspend fun storeDittoCommandSettings(
        source: DittoPdlSource,
        customPdl: String?
    ) {
        context.dataStore.edit { preferences ->
            preferences[DITTO_PDL_SOURCE] = source.name
            if (customPdl != null) preferences[DITTO_CUSTOM_PDL] = customPdl
        }
    }

    // Loads the selected PDL option
    val getDittoPdlSource: Flow<DittoPdlSource> =
        context.dataStore.data.map { preferences ->
            runCatching {
                DittoPdlSource.valueOf(
                    preferences[DITTO_PDL_SOURCE]
                        ?: DittoPdlSource.COMMUNITY.name
                )
            }.getOrDefault(DittoPdlSource.COMMUNITY)
        }

    // Loads the saved Custom PDL
    val getDittoCustomPdl: Flow<String> =
        context.dataStore.data.map { preferences ->
            preferences[DITTO_CUSTOM_PDL] ?: ""
        }

    // Returns the PDL Ditto should use
    val getEffectiveDittoPdl: Flow<String> =
        context.dataStore.data.map { preferences ->
            val source = preferences[DITTO_PDL_SOURCE]
            when (source) {
                DittoPdlSource.CUSTOM.name -> preferences[DITTO_CUSTOM_PDL] ?: ""
                else -> DEFAULT_COMMUNITY_PDL
            }
        }
    val getEnableBleBeaconService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BLE_BEACON_SERVICE] ?: DEFAULT_ENABLE_BLE_BEACON_SERVICE
    }

    suspend fun storeEnableBleBeaconService(enableBleBeaconService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BLE_BEACON_SERVICE] = enableBleBeaconService
        }
    }

    val getEnableCompanionAppService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_COMPANION_APP_SERVICE] ?: DEFAULT_ENABLE_COMPANION_APP_SERVICE
    }

    suspend fun storeEnableCompanionAppService(enableCompanionAppService: Boolean) {
        storeNetworkToggle(ENABLE_COMPANION_APP_SERVICE, enableCompanionAppService)
    }

    val getEnableChatCommands: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_CHAT_COMMANDS] ?: DEFAULT_ENABLE_CHAT_COMMANDS
    }

    val getEnableSensorManager: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_SENSOR_MANAGER] ?: false
    }

    suspend fun storeEnableSensorManager(enable: Boolean) {
        context.dataStore.edit { prefs -> prefs[ENABLE_SENSOR_MANAGER] = enable }
    }

    suspend fun storeEnableChatCommands(enable: Boolean) {
        context.dataStore.edit { prefs -> prefs[ENABLE_CHAT_COMMANDS] = enable }
    }

    val getChatCommandCallsigns: Flow<List<String>> = context.dataStore.data.map { prefs ->
        ChatCommandAuthorizer.parseAllowList(prefs[CHAT_COMMAND_CALLSIGNS].orEmpty())
    }

    suspend fun storeChatCommandCallsigns(callsigns: List<String>) {
        context.dataStore.edit { prefs -> prefs[CHAT_COMMAND_CALLSIGNS] = callsigns.joinToString(",") }
    }

    val getEnableWearTakDevMode: Flow<Boolean> =
        context.dataStore.data
            .map { prefs -> prefs[ENABLE_WEARTAK_DEV_MODE] ?: false }
            .distinctUntilChanged()

    val networkPreferencesChecklistEnabled: Flow<Boolean> =
        context.dataStore.data
            .map { prefs -> prefs[NETWORK_PREFERENCES_CHECKLIST_ENABLED] ?: false }
            .distinctUntilChanged()

    suspend fun storeNetworkPreferencesChecklistEnabled(enabled: Boolean) {
        context.dataStore.edit { it[NETWORK_PREFERENCES_CHECKLIST_ENABLED] = enabled }
    }

    val getBondedLocationDiagnosticsEnabled: Flow<Boolean> =
        context.dataStore.data
            .map { prefs -> prefs[BONDED_LOCATION_DIAGNOSTICS_ENABLED] ?: false }
            .distinctUntilChanged()

    suspend fun storeBondedLocationDiagnosticsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[BONDED_LOCATION_DIAGNOSTICS_ENABLED] = enabled
        }
    }

    suspend fun storeEnableWearTakDevMode(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_WEARTAK_DEV_MODE] = enable
        }
    }

    val getNetworkPreferencesLocked: Flow<Boolean> =
        context.dataStore.data
            .map { prefs -> prefs[NETWORK_PREFERENCES_LOCKED] ?: false }
            .distinctUntilChanged()

    val networkSettingsLocked: Flow<Boolean> = kotlinx.coroutines.flow.combine(
        phoneManagedSettingsLocked,
        getNetworkPreferencesLocked
    ) { phoneManaged, networkLocked -> phoneManaged || networkLocked }
        .distinctUntilChanged()

    suspend fun storeCommandNetworkToggle(
        tak: Boolean,
        enabled: Boolean,
        policyRejection: () -> String?
    ): String? = storeNetworkToggle(
        if (tak) ENABLE_TAK_ENDPOINT else ENABLE_MULTICAST, enabled, policyRejection
    )

    private suspend fun storeNetworkToggle(
        key: Preferences.Key<Boolean>,
        enabled: Boolean,
        policyRejection: (() -> String?)? = null
    ): String? {
        var rejection: String? = null
        context.dataStore.edit { prefs ->
            rejection = if (prefs.networkTogglesLocked()) "network settings locked or phone-managed"
                else policyRejection?.invoke()
            if (rejection != null) {
                Log.w(TAG, "Network toggle rejected: ${key.name}: $rejection")
                return@edit
            }
            prefs[key] = enabled
            if (key == ENABLE_TAK_ENDPOINT) {
                prefs[TAK_ENDPOINT_USER_CHOICE] = enabled
            } else if (key == ENABLE_MULTICAST) {
                prefs[MULTICAST_USER_CHOICE] = enabled
            }
        }
        return rejection
    }

    private fun Preferences.networkTogglesLocked(): Boolean =
        this[NETWORK_PREFERENCES_LOCKED] == true ||
            (this[PHONE_MANAGED_SETTINGS_ENABLED] == true && phoneManagementSessionActive.value)

    /** Trusted Android managed configuration remains authoritative over local UI locks. */
    suspend fun storeManagedNetworkToggles(
        tak: Boolean? = null,
        multicast: Boolean? = null,
        sitx: Boolean? = null,
        ditto: Boolean? = null,
        companion: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            tak?.let { prefs[ENABLE_TAK_ENDPOINT] = it }
            multicast?.let { prefs[ENABLE_MULTICAST] = it }
            sitx?.let { prefs[ENABLE_SITX_SERVICE] = it }
            ditto?.let { prefs[ENABLE_DITTO_SERVICE] = it }
            companion?.let { prefs[ENABLE_COMPANION_APP_SERVICE] = it }
        }
    }

    suspend fun storeNetworkPreferencesLocked(locked: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[NETWORK_PREFERENCES_LOCKED] = locked
        }
    }

    data class LocalizationBleBeaconItem(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val lat: Double,
        val lon: Double,
        val alt: Double
    )
    val getLocalizationBleBeaconItemList: Flow<List<LocalizationBleBeaconItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[LOCALIZATION_BLE_BEACON_LIST_KEY]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<LocalizationBleBeaconItem>>() {}.type
                gson.fromJson<List<LocalizationBleBeaconItem>>(jsonString, type)
            }
        }

    suspend fun storeLocalizationBleBeaconList(newList: List<LocalizationBleBeaconItem>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[LOCALIZATION_BLE_BEACON_LIST_KEY] = jsonString
        }
    }

    private val _enableDynamicReportingStrategy = MutableStateFlow<Boolean?>(null) // TODO: Do we initialize with null OR from getEnableDynamicReportingStrategy
    val enableDynamicReportingStrategy: StateFlow<Boolean?> = _enableDynamicReportingStrategy

    val getEnableDynamicReportingStrategy: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_DYNAMIC_REPORTING_STRATEGY] ?: DEFAULT_ENABLE_DYNAMIC_REPORTING
    }

    suspend fun storeEnableDynamicReportingStrategy(enableDynamicRS: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_DYNAMIC_REPORTING_STRATEGY] = enableDynamicRS
            prefs.recordWatchOwnedValue(PHONE_MANAGED_PREVIOUS_DYNAMIC_REPORTING, enableDynamicRS, identity = false)
        }
        _enableDynamicReportingStrategy.emit(enableDynamicRS)
    }

    private val _constantReportingInterval = MutableStateFlow<Int?>(null)
    val constantReportingInterval: StateFlow<Int?> = _constantReportingInterval

    val androidDeviceUid: String = MainActivity.device_uid

    val getConstantReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[CONSTANT_REPORTING_INTERVAL] ?: DEFAULT_CONSTANT_REPORTING_INTERVAL
    }

    suspend fun storeConstantReportingInterval(constantReportingInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[CONSTANT_REPORTING_INTERVAL] = constantReportingInterval
            prefs.recordWatchOwnedValue(
                PHONE_MANAGED_PREVIOUS_CONSTANT_INTERVAL, constantReportingInterval, identity = false
            )
        }
        _constantReportingInterval.emit(constantReportingInterval)
    }

    private val _dynamicReportingInterval = MutableStateFlow<Int?>(null)
    val dynamicReportingInterval: StateFlow<Int?> = _dynamicReportingInterval

    suspend fun storeDynamicReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[DYNAMIC_REPORTING_INTERVAL] = interval
        }
        _dynamicReportingInterval.emit(interval)
    }

    private val _stationaryReportingInterval = MutableStateFlow<Int?>(null)
    val stationaryReportingInterval: StateFlow<Int?> = _stationaryReportingInterval

    val getStationaryReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[STATIONARY_REPORTING_INTERVAL] ?: DEFAULT_STATIONARY_REPORTING_INTERVAL
    }

    suspend fun storeStationaryReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[STATIONARY_REPORTING_INTERVAL] = interval
        }
        Log.d(TAG, "storeStationaryReportingInterval: $interval")
        _stationaryReportingInterval.emit(interval)
    }

    private val _onFootReportingInterval = MutableStateFlow<Int?>(null)
    val onFootReportingInterval: StateFlow<Int?> = _onFootReportingInterval

    val getOnFootReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ON_FOOT_REPORTING_INTERVAL] ?: DEFAULT_ON_FOOT_REPORTING_INTERVAL
    }

    suspend fun storeOnFootReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[ON_FOOT_REPORTING_INTERVAL] = interval
        }
        _onFootReportingInterval.emit(interval)
    }

    private val _vehicleReportingInterval = MutableStateFlow<Int?>(null)
    val vehicleReportingInterval: StateFlow<Int?> = _vehicleReportingInterval

    val getVehicleReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[VEHICLE_REPORTING_INTERVAL] ?: DEFAULT_VEHICLE_REPORTING_INTERVAL
    }

    suspend fun storeVehicleReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[VEHICLE_REPORTING_INTERVAL] = interval
        }
        _vehicleReportingInterval.emit(interval)
    }

    private val _whileAlertingReportingInterval = MutableStateFlow<Int?>(null)
    val whileAlertingReportingInterval: StateFlow<Int?> = _whileAlertingReportingInterval

    val getWhileAlertingReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[WHILE_ALERTING_REPORTING_INTERVAL] ?: DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
    }

    suspend fun storeWhileAlertingReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[WHILE_ALERTING_REPORTING_INTERVAL] = interval
        }
        _whileAlertingReportingInterval.emit(interval)
    }

    val getEnableBatteryAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BATTERY_ALERTS] ?: DEFAULT_ENABLE_BATTERY_ALERTS
    }

    suspend fun storeEnableBatteryAlerts(enableBatteryAlerts: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BATTERY_ALERTS] = enableBatteryAlerts
        }
    }

    val getEnableSmsForAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_SMS_FOR_ALERTS] ?: DEFAULT_ENABLE_SMS_ALERTS
    }

    suspend fun storeEnableSmsForAlerts(enableSmsForAlerts: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_SMS_FOR_ALERTS] = enableSmsForAlerts
        }
    }

    data class SmsNumber(val telephoneNumber: String)
    val getSmsNumbersList: Flow<List<SmsNumber>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[SMS_NUMBERS_LIST]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<SmsNumber>>() {}
                val type = typeToken.type
                gson.fromJson<List<SmsNumber>>(jsonString, type)
            }
        }

    suspend fun storeSmsNumbersList(newList: List<SmsNumber>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[SMS_NUMBERS_LIST] = jsonString
        }
    }

    private val _bloodhoundItemsFlow = MutableStateFlow<List<Utils.BloodhoundItem>>(emptyList())
    val bloodhoundItemsFlow: StateFlow<List<Utils.BloodhoundItem>> = _bloodhoundItemsFlow

    fun addBloodhoundItemToFront(bloodhound: Utils.BloodhoundItem) {
        _bloodhoundItemsFlow.value = listOf(bloodhound) + _bloodhoundItemsFlow.value
    }

    //TODO WEAR-161: Is this the best metric to use here?
    fun removeBloodhound(bloodhoundStr: String) {
        _bloodhoundItemsFlow.value = _bloodhoundItemsFlow.value
            .filterNot { it.target == bloodhoundStr }
    }

    val getSelectedPhysioMetric: Flow<String> = context.dataStore.data.map { prefs ->
        TopDisplayMetric.fromKey(prefs[SELECTED_PHYSIO_METRIC]).key
    }

    suspend fun storeSelectedPhysioMetric(metric: String) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_PHYSIO_METRIC] = TopDisplayMetric.fromKey(metric).key
        }
    }


    // private val _physioAlertsFlow = MutableStateFlow<List<Utils.PhysioAlert>>(emptyList())
    private val _physioAlertsFlow = MutableStateFlow<List<Utils.PhysioAlert>>(emptyList())
    val physioAlertsFlow: StateFlow<List<Utils.PhysioAlert>> = _physioAlertsFlow

    fun addPhysioAlertToFront(alert: Utils.PhysioAlert) {
        _physioAlertsFlow.value = listOf(alert) + _physioAlertsFlow.value
    }

    fun removePhysioAlerts(alertStr: String) {
        _physioAlertsFlow.value = _physioAlertsFlow.value
            .filterNot { it.alert == alertStr }
    }

    val getEnablePhysiologicalAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_PHYSIOLOGICAL_ALERTS] ?: DEFAULT_ENABLE_PHYSIOLOGICAL_ALERTS
    }

    suspend fun storeEnablePhysiologicalAlerts(enablePhysiologicalAlerts: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_PHYSIOLOGICAL_ALERTS] = enablePhysiologicalAlerts
        }
    }

    val getEnableUserWarning: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_USER_WARNING] ?: DEFAULT_ENABLE_USER_WARNING
    }

    suspend fun storeEnableUserWarning(enableUserWarning: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_USER_WARNING] = enableUserWarning
        }
    }

    val getRestingHrHighAlertBpmThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[RESTING_HR_HIGH_ALERT_BPM_THRESHOLD] ?: DEFAULT_HIGH_RESTING_HR_THRESHOLD
    }

    suspend fun storeRestingHrHighAlertBpmThreshold(restingHrHighAlertBpmThreshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[RESTING_HR_HIGH_ALERT_BPM_THRESHOLD] = restingHrHighAlertBpmThreshold
        }
    }
    val getRestingHrLowAlertBpmThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[RESTING_HR_LOW_ALERT_BPM_THRESHOLD] ?: DEFAULT_LOW_RESTING_HR_THRESHOLD
    }

    suspend fun storeRestingHrLowAlertBpmThreshold(restingHrLowAlertBpmThreshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[RESTING_HR_LOW_ALERT_BPM_THRESHOLD] = restingHrLowAlertBpmThreshold
        }
    }
    val getHighRestingHrWarningThresholdLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH] ?: DEFAULT_HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
    }

    suspend fun storeHighRestingHrWarningThresholdLength(highRestingHrWarningThresholdLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH] = highRestingHrWarningThresholdLength
        }
    }
    val getLowRestingHrWarningThresholdLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH] ?: DEFAULT_LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
    }

    suspend fun storeLowRestingHrWarningThresholdLength(lowRestingHrWarningThresholdLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH] = lowRestingHrWarningThresholdLength
        }
    }
    val getHighRestingHrAlertThresholdLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH] ?: DEFAULT_HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
    }

    suspend fun storeHighRestingHrAlertThresholdLength(highRestingHrAlertThresholdLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH] = highRestingHrAlertThresholdLength
        }
    }
    val getLowRestingHrAlertThresholdLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH] ?: DEFAULT_LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
    }

    suspend fun storeLowRestingHrAlertThresholdLength(lowRestingHrAlertThresholdLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH] = lowRestingHrAlertThresholdLength
        }
    }

    val getExertionThresholdWarning: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[EXERTION_THRESHOLD_WARNING] ?: DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
    }

    suspend fun storeExertionThresholdWarning(exertionThresholdWarning: Int) {
        context.dataStore.edit { prefs ->
            prefs[EXERTION_THRESHOLD_WARNING] = exertionThresholdWarning
        }
    }
    val getExertionThresholdAlert: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[EXERTION_THRESHOLD_ALERT] ?: DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
    }

    suspend fun storeExertionThresholdAlert(exertionThresholdAlert: Int) {
        context.dataStore.edit { prefs ->
            prefs[EXERTION_THRESHOLD_ALERT] = exertionThresholdAlert
        }
    }

    val getExertionWarningThresholdLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[EXERTION_WARNING_THRESHOLD_LENGTH] ?: DEFAULT_EXERTION_WARNING_THRESHOLD_LENGTH_SECS
    }

    suspend fun storeExertionWarningThresholdLength(exertionWarningThresholdLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[EXERTION_WARNING_THRESHOLD_LENGTH] = exertionWarningThresholdLength
        }
    }
    val getExertionAlertThresholdLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[EXERTION_ALERT_THRESHOLD_LENGTH] ?: DEFAULT_EXERTION_ALERT_THRESHOLD_LENGTH_SECS
    }

    suspend fun storeExertionAlertThresholdLength(exertionAlertThresholdLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[EXERTION_ALERT_THRESHOLD_LENGTH] = exertionAlertThresholdLength
        }
    }
    //TODO WEAR-313: Comment out these two
    val getHrHighAlertBpmThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HR_HIGH_ALERT_BPM_THRESHOLD] ?: DEFAULT_HIGH_HEART_RATE_ALERT_THRESHOLD
    }

    suspend fun storeHrHighAlertBpmThreshold(hrHighAlertBpmThreshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[HR_HIGH_ALERT_BPM_THRESHOLD] = hrHighAlertBpmThreshold
        }
    }

    val getHrHighAlertNumSecsThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HR_HIGH_ALERT_NUM_SECS_THRESHOLD] ?: DEFAULT_HIGH_HEART_RATE_ALERT_THRESHOLD_NUMBER_SECONDS
    }

    suspend fun storeHrHighAlertNumSecsThreshold(hrHighAlertNumSecsThreshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[HR_HIGH_ALERT_NUM_SECS_THRESHOLD] = hrHighAlertNumSecsThreshold
        }
    }

    val getBatteryWarnPercentage : Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BATT_WARN_PERCENT] ?: DEFAULT_BATT_WARNING_PERCENTAGE
    }

    suspend fun storeBatteryWarnPercentage(percent: Int) {
        context.dataStore.edit { prefs ->
            prefs[BATT_WARN_PERCENT] = percent
        }
    }

    val getBatteryAlertPercentage : Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BATT_ALERT_PERCENT] ?: DEFAULT_BATT_ALERT_PERCENTAGE
    }

    suspend fun storeBatteryAlertPercentage(percent: Int) {
        context.dataStore.edit { prefs ->
            prefs[BATT_ALERT_PERCENT] = percent
        }
    }

    val getPhysiologicalMetricAge: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[AGE] ?: (Year.now().value - DEFAULT_BIRTH_YEAR).coerceAtLeast(0)
    }

    suspend fun storePhysiologicalMetricAge(age: Int) {
        context.dataStore.edit { prefs ->
            prefs[AGE] = age
        }
    }

    val physiologicalMetricAge: StateFlow<Int> = getPhysiologicalMetricAge.stateIn(
        scope = repoScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = (Year.now().value - DEFAULT_BIRTH_YEAR).coerceAtLeast(0)
    )

    val latestExertion: StateFlow<Double> = combine(
        latestHeartRate.filterNotNull(),
        physiologicalMetricAge.filterNotNull(),
        restingHeartRate.filterNotNull()
    ) { hr, age, rhr ->
        Utils.PhysioUtils.calculateExertion(hr, age, rhr)
    }
        .stateIn(
            scope = repoScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = -1.0
        )


    val getPhysiologicalMetricHeightFeet: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HEIGHT_FEET] ?: DEFAULT_HEIGHT_FEET
    }

    suspend fun storePhysiologicalMetricHeightFeet(heightfeet: Int) {
        context.dataStore.edit { prefs ->
            prefs[HEIGHT_FEET] = heightfeet
        }
    }

    val getPhysiologicalMetricHeightInches: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HEIGHT_INCHES] ?: DEFAULT_HEIGHT_INCHES
    }

    suspend fun storePhysiologicalMetricHeightInches(heightinches: Int) {
        context.dataStore.edit { prefs ->
            prefs[HEIGHT_INCHES] = heightinches
        }
    }

    val getPhysiologicalMetricWeight: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[WEIGHT] ?: DEFAULT_WEIGHT
    }

    suspend fun storePhysiologicalMetricWeight(weight: Int) {
        context.dataStore.edit { prefs ->
            prefs[WEIGHT] = weight
        }
    }

    val getPhysiologicalMetricSex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SEX] ?: DEFAULT_SEX
    }

    suspend fun storePhysiologicalMetricSex(sex: String) {
        context.dataStore.edit { prefs ->
            prefs[SEX] = sex
        }
    }

    val getPhysiologicalMetricBloodType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BLOOD_TYPE] ?: DEFAULT_BLOOD_TYPE
    }

    suspend fun storePhysiologicalMetricBloodType(bloodtype: String) {
        context.dataStore.edit { prefs ->
            prefs[BLOOD_TYPE] = bloodtype
        }
    }

    data class AllergyItem(val name: String)
    val getPhysiologicalMetricAllergyList: Flow<List<AllergyItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[ALLERGIES_KEY]
            if (jsonString.isNullOrEmpty()) {
                listOf(AllergyItem(name=DEFAULT_ALLERGY_LIST))
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<AllergyItem>>() {}
                val type = typeToken.type
                gson.fromJson<List<AllergyItem>>(jsonString, type)
            }
        }

        suspend fun storePhysiologicalMetricAllergyList(newList: List<AllergyItem>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[ALLERGIES_KEY] = jsonString
        }
    }

    val getPhysiologicalMetricAllergyListString: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[ALLERGIES_KEY_STRING]
            if (jsonString.isNullOrEmpty()) {
                listOf((DEFAULT_ALLERGY_LIST))
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<String>>() {}
                val type = typeToken.type
                Log.d("DataStoreDebug", "Allergies raw JSON = $jsonString")
                gson.fromJson<List<String>>(jsonString, type)
            }
        }

    suspend fun storePhysiologicalMetricAllergyListString(newList: List<String>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[ALLERGIES_KEY_STRING] = jsonString
        }
    }

    val getPhysiologicalMetricUserType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[USER_TYPE] ?: DEFAULT_USER_TYPE
    }

    suspend fun storePhysiologicalMetricUserType(usertype: String) {
        context.dataStore.edit { prefs ->
            prefs[USER_TYPE] = usertype
        }
    }

    val getYearOfBirth: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[YEAR_OF_BIRTH] ?: DEFAULT_BIRTH_YEAR
    }

    suspend fun storeYearOfBirth(year: Int) {
        context.dataStore.edit { prefs ->
            prefs[YEAR_OF_BIRTH] = year
        }
        storePhysiologicalMetricAge((Year.now().value - year).coerceAtLeast(0))
    }

    val getPhysiologicalMetricUniformWaistSize: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[UNIFORM_WAIST_SIZE] ?: DEFAULT_UNIFORM_WAIST_SIZE
    }

    suspend fun storePhysiologicalMetricUniformWaistSize(uniformWaistSize: Int) {
        context.dataStore.edit { prefs ->
            prefs[UNIFORM_WAIST_SIZE] = uniformWaistSize
        }
    }
    val getPhysiologicalMetricStrideLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[STRIDE_LENGTH] ?: DEFAULT_STRIDE_LENGTH
    }

    suspend fun storePhysiologicalMetricStrideLength(strideLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[STRIDE_LENGTH] = strideLength
        }
    }
    val getPhysiologicalMetricUniformPantsLength: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[UNIFORM_PANTS_LENGTH] ?: DEFAULT_UNIFORM_PANTS_LENGTH
    }

    suspend fun storePhysiologicalMetricUniformPantsLength(uniformPantsLength: Int) {
        context.dataStore.edit { prefs ->
            prefs[UNIFORM_PANTS_LENGTH] = uniformPantsLength
        }
    }
    val getPhysiologicalMetricLoadoutWeight: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LOADOUT_WEIGHT] ?: DEFAULT_LOADOUT_WEIGHT
    }

    suspend fun storePhysiologicalMetricLoadoutWeight(loadoutWeight: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOADOUT_WEIGHT] = loadoutWeight
        }
    }

    /**
     * Posted when an alert is cleared (e.g. exit condition met or user cancelled).
     * UI (e.g. UserWarnActivity) can finish when it was showing this alert.
     */
    data class AlertClearedEvent(val uid: String)

    private val _alertClearedEventCep = MutableSharedFlow<AlertClearedEvent>(extraBufferCapacity = 64)
    val alertClearedEventCep: SharedFlow<AlertClearedEvent> = _alertClearedEventCep

    suspend fun addAlertClearedEventCep(uid: String) {
        _alertClearedEventCep.emit(AlertClearedEvent(uid))
    }

    /**
     * Dual sustain overrides: main (sustained_for_sec) and warn (sustained_for_sec_warn) per rule id.
     * Legacy stored JSON that is a flat Map<String, Long> is treated as main-only with empty warn.
     */
    data class CepSustainOverrides(
        val main: Map<String, Long>,
        val warn: Map<String, Long>
    )

    private val cepSustainOverridesType = object : TypeToken<Map<String, Long>>() {}.type

    private fun parseStringMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseLongMap(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val m = gson.fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type) ?: emptyMap()
            m.mapValues { (_, v) -> v.coerceAtLeast(0L) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseCepSustainOverrides(json: String?): CepSustainOverrides {
        if (json.isNullOrBlank()) return CepSustainOverrides(emptyMap(), emptyMap())
        return try {
            val el = gson.fromJson(json, JsonElement::class.java) ?: return CepSustainOverrides(emptyMap(), emptyMap())
            if (el.isJsonObject && el.asJsonObject.has("main")) {
                val obj = el.asJsonObject
                val main = gson.fromJson<Map<String, Long>>(obj.getAsJsonObject("main"), cepSustainOverridesType)?.mapValues { (_, v) -> v.coerceAtLeast(0L) } ?: emptyMap()
                val warn = if (obj.has("warn")) gson.fromJson<Map<String, Long>>(obj.getAsJsonObject("warn"), cepSustainOverridesType)?.mapValues { (_, v) -> v.coerceAtLeast(0L) } ?: emptyMap() else emptyMap()
                CepSustainOverrides(main, warn)
            } else {
                val flat = gson.fromJson<Map<String, Long>>(json, cepSustainOverridesType) ?: emptyMap()
                CepSustainOverrides(flat.mapValues { (_, v) -> v.coerceAtLeast(0L) }, emptyMap())
            }
        } catch (e: Exception) {
            Log.w("DataRepository", "Invalid CEP sustain overrides JSON", e)
            CepSustainOverrides(emptyMap(), emptyMap())
        }
    }

    val getCepSustainOverrides: Flow<CepSustainOverrides> = context.dataStore.data.map { prefs ->
        parseCepSustainOverrides(prefs[CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON])
    }

    /** One-shot read of current sustain overrides; use when a single snapshot is needed (e.g. CEP engine load). */
    suspend fun getCepSustainOverridesOnce(): CepSustainOverrides = getCepSustainOverrides.first()

    /** Legacy: store a single map as main-only overrides (writes flat JSON). */
    suspend fun storeCepSustainOverrides(map: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            val sanitized = map.mapValues { (_, v) -> v.coerceAtLeast(0L) }
            prefs[CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON] = gson.toJson(sanitized)
        }
    }

    /** Store separate main and warn sustain overrides. Writes warn-aware JSON for dual override support. */
    suspend fun storeCepSustainOverridesDual(main: Map<String, Long>, warn: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            val mainSanitized = main.mapValues { (_, v) -> v.coerceAtLeast(0L) }
            val warnSanitized = warn.mapValues { (_, v) -> v.coerceAtLeast(0L) }
            val root = JsonObject().apply {
                add("main", gson.toJsonTree(mainSanitized))
                add("warn", gson.toJsonTree(warnSanitized))
            }
            prefs[CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON] = gson.toJson(root)
        }
    }

    /** Rebuild the CEP sustain override blob from physiological settings. */
    suspend fun rebuildCepSustainOverridesFromSettings() {
        val mainOverrides = mapOf(
            "hr_high_threshold_resting_alert" to getHighRestingHrAlertThresholdLength.first() * 60L,
            "hr_low_threshold_resting_alert" to getLowRestingHrAlertThresholdLength.first() * 60L,
            "exertion_alert_hr_zone5_exercise" to getExertionAlertThresholdLength.first().toLong()
        )
        val warningOverrides = mapOf(
            "hr_high_threshold_resting_warning" to getHighRestingHrWarningThresholdLength.first() * 60L,
            "hr_low_threshold_resting_warning" to getLowRestingHrWarningThresholdLength.first() * 60L,
            "exertion_warning_hr_zone4_exercise" to getExertionWarningThresholdLength.first().toLong()
        )
        storeCepSustainOverridesDual(mainOverrides, warningOverrides)
    }

    // ====================================================================================
    // WEAR-508 ManagedConfiguration CEP overlay persistence
    //
    // Persistence boundary (per [Update v1.1 — U1 cleanup]):
    //   pre-swap   : cep_configuration_raw_json
    //   post-swap  : cep_configuration_applied_hash, cep_configuration_applied_at_ms,
    //                cep_last_consumed_reset_epoch (when reset commits with apply)
    // Applied-state keys must NEVER be written for a candidate that did not become
    // authoritative through CepEngineHolder.swap(...).
    // ====================================================================================

    /** Last validated raw managed-overlay payload (or empty string when overlay is cleared). */
    val getCepConfigurationRawJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CEP_CONFIGURATION_RAW_JSON] ?: ""
    }

    suspend fun getCepConfigurationRawJsonOnce(): String = getCepConfigurationRawJson.first()

    /** Pre-swap write. Used after parse + validate + canonicalize, before engine swap. */
    suspend fun storeCepConfigurationRawJson(raw: String) {
        context.dataStore.edit { prefs ->
            if (raw.isBlank()) prefs.remove(CEP_CONFIGURATION_RAW_JSON)
            else prefs[CEP_CONFIGURATION_RAW_JSON] = raw
        }
    }

    /** Canonical hash of the most recent authoritative effective config. Empty when never applied. */
    val getCepConfigurationAppliedHash: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CEP_CONFIGURATION_APPLIED_HASH] ?: ""
    }

    suspend fun getCepConfigurationAppliedHashOnce(): String = getCepConfigurationAppliedHash.first()

    /** Timestamp of the most recent authoritative apply (ms epoch); 0 when never applied. */
    val getCepConfigurationAppliedAtMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[CEP_CONFIGURATION_APPLIED_AT_MS] ?: 0L
    }

    /** Post-swap-only write. Caller MUST gate this on a successful CepEngineHolder.swap(...). */
    suspend fun storeCepConfigurationApplied(hash: String, atMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[CEP_CONFIGURATION_APPLIED_HASH] = hash
            prefs[CEP_CONFIGURATION_APPLIED_AT_MS] = atMs
        }
    }

    /** Local mirror of the most recently consumed `cepResetEpoch`. */
    val getCepLastConsumedResetEpoch: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[CEP_LAST_CONSUMED_RESET_EPOCH] ?: 0L
    }

    suspend fun getCepLastConsumedResetEpochOnce(): Long = getCepLastConsumedResetEpoch.first()

    /** Pre-swap write as part of reset consumption ordering. */
    suspend fun storeCepLastConsumedResetEpoch(epoch: Long) {
        context.dataStore.edit { prefs ->
            prefs[CEP_LAST_CONSUMED_RESET_EPOCH] = epoch.coerceAtLeast(0L)
        }
    }

    /**
     * Returns true when [epoch] should be consumed on the next **successful** authoritative
     * apply (`epoch > 0` and newer than [CEP_LAST_CONSUMED_RESET_EPOCH]). Does not mutate
     * storage — failed validation/warm-up/swap leaves mirror and sustain overrides intact.
     */
    suspend fun shouldConsumeCepResetEpoch(epoch: Long): Boolean {
        if (epoch <= 0L) return false
        val current = getCepLastConsumedResetEpochOnce()
        return epoch > current
    }

    /**
     * Advances [CEP_LAST_CONSUMED_RESET_EPOCH] after `CepEngineHolder.swap` + applied-state
     * persistence commit.
     */
    suspend fun advanceCepResetEpochMirror(epoch: Long): Boolean {
        if (epoch <= 0L) return false
        var advanced = false
        context.dataStore.edit { prefs ->
            val cur = prefs[CEP_LAST_CONSUMED_RESET_EPOCH] ?: 0L
            if (epoch > cur) {
                prefs[CEP_LAST_CONSUMED_RESET_EPOCH] = epoch
                advanced = true
            }
        }
        return advanced
    }

    /** Clears persisted sustain overrides (reset consumption after validation, before warm-up). */
    suspend fun clearCepSustainOverridesStorage() {
        context.dataStore.edit { prefs ->
            prefs.remove(CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON)
        }
    }

    // ---- WEAR-509 poison-pill hold-off state -----------------------------------------
    //
    // Tracks the most recent managed-config rejection streak keyed by payload hash so
    // a broken-but-unchanged ManagedConfiguration payload does not cause repeated churn
    // (design §847–857). State persists across process restart. Only one entry is kept
    // (latest hash); a different hash supersedes the prior entry.

    /**
     * Persisted poison-pill streak for a single payload hash. `firstFailureAtMs` is the
     * ms epoch of the first rejection in the current streak; `failureCount` increments
     * on each rejection of the same hash and resets when the hash changes or on success.
     */
    data class CepManagedPayloadFailureState(
        val payloadHash: String,
        val failureCount: Int,
        val firstFailureAtMs: Long
    )

    val getCepManagedPayloadFailureState: Flow<CepManagedPayloadFailureState?> =
        context.dataStore.data.map { prefs ->
            parseCepManagedPayloadFailureState(prefs[CEP_MANAGED_PAYLOAD_FAILURE_STATE_JSON])
        }

    suspend fun getCepManagedPayloadFailureStateOnce(): CepManagedPayloadFailureState? =
        getCepManagedPayloadFailureState.first()

    suspend fun storeCepManagedPayloadFailureState(state: CepManagedPayloadFailureState) {
        if (state.payloadHash.isBlank()) return
        context.dataStore.edit { prefs ->
            val obj = com.google.gson.JsonObject().apply {
                addProperty("payloadHash", state.payloadHash)
                addProperty("failureCount", state.failureCount.coerceAtLeast(0))
                addProperty("firstFailureAtMs", state.firstFailureAtMs.coerceAtLeast(0L))
            }
            prefs[CEP_MANAGED_PAYLOAD_FAILURE_STATE_JSON] = gson.toJson(obj)
        }
    }

    suspend fun clearCepManagedPayloadFailureState() {
        context.dataStore.edit { prefs ->
            prefs.remove(CEP_MANAGED_PAYLOAD_FAILURE_STATE_JSON)
        }
    }

    private fun parseCepManagedPayloadFailureState(json: String?): CepManagedPayloadFailureState? {
        if (json.isNullOrBlank()) return null
        return try {
            val el = gson.fromJson(json, com.google.gson.JsonElement::class.java) ?: return null
            if (!el.isJsonObject) return null
            val obj = el.asJsonObject
            val hash = obj.get("payloadHash")?.asString.orEmpty()
            if (hash.isBlank()) return null
            val count = obj.get("failureCount")?.asInt ?: 0
            val firstAt = obj.get("firstFailureAtMs")?.asLong ?: 0L
            CepManagedPayloadFailureState(hash, count.coerceAtLeast(0), firstAt.coerceAtLeast(0L))
        } catch (e: Exception) {
            Log.w("DataRepository", "Invalid CEP managed payload failure state JSON", e)
            null
        }
    }

    // WEAR-509 plugin overlay persistence ---------------------------------------------

    val getCepPluginOverlays: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        parseStringMap(prefs[CEP_PLUGIN_OVERLAYS_JSON])
    }

    suspend fun getCepPluginOverlaysOnce(): Map<String, String> = getCepPluginOverlays.first()

    suspend fun storeCepPluginOverlays(overlays: Map<String, String>) {
        context.dataStore.edit { prefs ->
            if (overlays.isEmpty()) prefs.remove(CEP_PLUGIN_OVERLAYS_JSON)
            else prefs[CEP_PLUGIN_OVERLAYS_JSON] = gson.toJson(overlays)
        }
    }

    val getCepPluginLeaseUntilMs: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        parseLongMap(prefs[CEP_PLUGIN_LEASE_UNTIL_MS_JSON])
    }

    suspend fun getCepPluginLeaseUntilMsOnce(): Map<String, Long> = getCepPluginLeaseUntilMs.first()

    suspend fun storeCepPluginLeaseUntilMs(leases: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            if (leases.isEmpty()) prefs.remove(CEP_PLUGIN_LEASE_UNTIL_MS_JSON)
            else prefs[CEP_PLUGIN_LEASE_UNTIL_MS_JSON] = gson.toJson(leases.mapValues { (_, v) -> v.coerceAtLeast(0L) })
        }
    }

    val getCepPluginCertLog: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        parseStringMap(prefs[CEP_PLUGIN_CERT_LOG_JSON])
    }

    suspend fun getCepPluginCertLogOnce(): Map<String, String> = getCepPluginCertLog.first()

    suspend fun storeCepPluginCertLog(certMap: Map<String, String>) {
        context.dataStore.edit { prefs ->
            if (certMap.isEmpty()) prefs.remove(CEP_PLUGIN_CERT_LOG_JSON)
            else prefs[CEP_PLUGIN_CERT_LOG_JSON] = gson.toJson(certMap)
        }
    }

    /** Persisted CEP diagnostic ring. Newest-last; bounded by the encoder/decoder. */
    val getCepDiagnostics: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CEP_DIAGNOSTICS_JSON] ?: ""
    }

    suspend fun getCepDiagnosticsOnce(): String = getCepDiagnostics.first()

    /** Replace the persisted diagnostic ring atomically. */
    suspend fun storeCepDiagnostics(json: String) {
        context.dataStore.edit { prefs ->
            if (json.isBlank()) prefs.remove(CEP_DIAGNOSTICS_JSON)
            else prefs[CEP_DIAGNOSTICS_JSON] = json
        }
    }


    companion object {
        private val COMPANION_EVER_READY = booleanPreferencesKey("companion_ever_ready")
        private val LOCATION_INPUT = stringPreferencesKey("location_input")
        private val ENABLE_MOPP = booleanPreferencesKey("enable_mopp")
        private val MOPP_STATUS = stringPreferencesKey("mopp_status")
        private val MOPP_SAVED_AT = longPreferencesKey("mopp_saved_at")
        private val ENABLE_DATA_SYNC = booleanPreferencesKey("enable_data_sync")
        private val PASSIVE_DATA_ENABLED = booleanPreferencesKey("passive_data_enabled")
        // private val LATEST_HEART_RATE = intPreferencesKey("latest_heart_rate")
        private val CALLSIGN = stringPreferencesKey("callsign")
        private val TEAM = stringPreferencesKey("team")
        private val TEAM_NAMES = stringPreferencesKey("team_names")
        private val TEAM_COLORS = stringPreferencesKey("team_colors")
        private val CATEGORY_ROLES_ENTRIES_JSON = stringPreferencesKey("category_roles_entries_json")
        private val SELECTED_CATEGORY     = stringPreferencesKey("selected_category")
        private val SELECTED_ROLE         = stringPreferencesKey("role")
        private val LICENSE = stringPreferencesKey("license")
        private val DITTO_APP_ID = stringPreferencesKey("ditto_app_id")
        private val DITTO_AUTH_TOKEN = stringPreferencesKey("ditto_auth_token")

        private val SELECTED_SITX_GROUP = stringPreferencesKey("selected_sitx_group")
        private val ENABLE_LOCATION_SERVICE = booleanPreferencesKey("enable_location_service")
        private val ENABLE_PHYSIOLOGICAL_SERVICES = booleanPreferencesKey("enable_physiological_services")
        private val ENABLE_SKIN_TEMP_SERVICE = booleanPreferencesKey("enable_skin_temp_service")
        private val ENABLE_HEART_RATE_SERVICE = booleanPreferencesKey("enable_heart_rate_service")
        private val ENABLE_IMMERSION_ALERTS = booleanPreferencesKey("enable_immersion_alerts")
        private val ENABLE_LOW_ATM_PRESSURE_ALERT = booleanPreferencesKey("enable_low_atm_pressure_alert")
        private val LOW_ATM_PRESSURE_ALERT_THRESHOLD = intPreferencesKey("low_atm_pressure_alert_threshold")
        private val ENABLE_HIGH_ATM_PRESSURE_ALERT = booleanPreferencesKey("enable_high_atm_pressure_alert")
        private val HIGH_ATM_PRESSURE_ALERT_THRESHOLD = intPreferencesKey("high_atm_pressure_alert_threshold")
        private val ENABLE_ACTIVITY_RECOGNITION_SERVICE = booleanPreferencesKey("enable_activity_recognition_service")
        private val ENABLE_DITTO_SERVICE = booleanPreferencesKey("enable_ditto_service")
        private val ENABLE_CHAT_SERVICE = booleanPreferencesKey("enable_chat_service")
        private val ENABLE_AUDIO_CAP_SERVICE = booleanPreferencesKey("enable_audio_capture_service")
        private val ENABLE_BETA_FEATURES = booleanPreferencesKey("enable_beta_features")
        private val ENABLE_ONNX_GUNSHOT_SERVICE = booleanPreferencesKey("enable_onnx_gunshot_service")
        private val ENABLE_ONNX_UAS_SERVICE = booleanPreferencesKey("enable_onnx_uas_service")
        private val ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT = intPreferencesKey("onnx_gunshot_trigger_threshold_percent")
        private val ONNX_UAS_TRIGGER_THRESHOLD_PERCENT = intPreferencesKey("onnx_uas_trigger_threshold_percent")

        private val ENABLE_GEOFENCE_SERVICE = booleanPreferencesKey("enable_geofence_service")
        private val ENABLE_BATTERY_ALERTS = booleanPreferencesKey("enable_battery_alerts")
        private val ENABLE_BLOODHOUND_COMPASS_SERVICE = booleanPreferencesKey("enable_bloodhound_compass_service")
        private val ENABLE_BLOODHOUND_PROXIMITY_VIBRATION = booleanPreferencesKey("enable_bloodhound_proximity_vibration")
        private val BLOODHOUND_PROXIMITY_RADIUS_METERS = intPreferencesKey("bloodhound_proximity_radius_meters")
        private val UNIT_RANGE_UNITS = stringPreferencesKey(com.aitech.weartak_civ.ui.formatter.UnitPrefs.KEY_RANGE_UNITS)
        private val BLOODHOUND_PROXIMITY_INTENSITY = stringPreferencesKey("bloodhound_proximity_intensity")
        private val LOCATION_UPDATE_INTERVAL = intPreferencesKey("location_update_interval")
        private val BIOMETRICS_SEND_INTERVAL = intPreferencesKey("biometrics_send_interval")
        private val ENABLE_WIFI_BATTERY_OPTIMIZATION = booleanPreferencesKey("enable_wifi_battery_optimization")
        private val ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION = booleanPreferencesKey("enable_all_wifi_battery_optimization")
        private val ENABLE_HAIL_SERVICE = booleanPreferencesKey("enable_hail_service")
        private val ENABLE_BATDOK_COT = booleanPreferencesKey("enable_batdok_cot")
        private val HAIL_LICENSE_INFO_JSON = stringPreferencesKey("hail_license_info_json_v1") // include suffix for forward-compat
        private val HAIL_LICENSE_STATE = stringPreferencesKey("hail_license_state")
        private val HAIL_COMBAT_ID = intPreferencesKey("hail_combat_id")
        private val WIFI_SSID_LIST_KEY = stringPreferencesKey("wifi_ssid_list")
        private val TAK_SERVER_LIST_KEY = stringPreferencesKey("tak_server_list")
        private val ENABLE_BLE_BEACON_SERVICE = booleanPreferencesKey("enable_ble_beacon_service")
        private val LOCALIZATION_BLE_BEACON_LIST_KEY = stringPreferencesKey("localization_ble_beacon_list")
        private val CUSTOM_DEVELOPER_CONFIG_JSON = stringPreferencesKey("custom_developer_config_json")
        private val ENABLE_DYNAMIC_REPORTING_STRATEGY = booleanPreferencesKey("enable_dynamic_reporting_strategy")
        private val CONSTANT_REPORTING_INTERVAL = intPreferencesKey("constant_reporting_interval")
        private val PHONE_MANAGED_SETTINGS_ENABLED = booleanPreferencesKey("phone_managed_settings_enabled")
        private val PHONE_MANAGED_COMPANION_ADDRESS = stringPreferencesKey("phone_managed_companion_address")
        private val PHONE_MANAGED_PREVIOUS_DYNAMIC_REPORTING = booleanPreferencesKey("phone_managed_previous_dynamic_reporting")
        private val PHONE_MANAGED_PREVIOUS_CONSTANT_INTERVAL = intPreferencesKey("phone_managed_previous_constant_interval")
        private val PHONE_MANAGED_SNAPSHOT_SAVED = booleanPreferencesKey("phone_managed_snapshot_saved")
        // Marker only; the server list itself is stored in the encrypted TAK server store.
        private val PHONE_MANAGED_TAK_SERVER_SNAPSHOT_SAVED =
            booleanPreferencesKey("phone_managed_tak_server_snapshot_saved")
        private val PHONE_MANAGED_PREVIOUS_CALLSIGN = stringPreferencesKey("phone_managed_previous_callsign")
        private val PHONE_MANAGED_PREVIOUS_TEAM = stringPreferencesKey("phone_managed_previous_team")
        private val PHONE_MANAGED_PREVIOUS_ROLE = stringPreferencesKey("phone_managed_previous_role")
        private val PHONE_MANAGED_PREVIOUS_ROLE_CATEGORY = stringPreferencesKey("phone_managed_previous_role_category")
        private val PHONE_MANAGED_SNAPSHOT_KEYS = listOf(
            PHONE_MANAGED_SNAPSHOT_SAVED,
            PHONE_MANAGED_PREVIOUS_DYNAMIC_REPORTING,
            PHONE_MANAGED_PREVIOUS_CONSTANT_INTERVAL,
            PHONE_MANAGED_PREVIOUS_CALLSIGN,
            PHONE_MANAGED_PREVIOUS_TEAM,
            PHONE_MANAGED_PREVIOUS_ROLE,
            PHONE_MANAGED_PREVIOUS_ROLE_CATEGORY
        )
        private const val DEFAULT_TEAM = "Orange"
        private const val DEFAULT_ROLE = "Team Member"
        private const val DEFAULT_ROLE_CATEGORY = "MIL"
        private const val DEFAULT_TEAM_LIST =
            "White,Yellow,Orange,Magenta,Red,Maroon,Purple,Dark Blue,Blue,Cyan,Teal,Green,Dark Green,Brown"
        private val DYNAMIC_REPORTING_INTERVAL = intPreferencesKey("dynamic_reporting_interval")
        private val STATIONARY_REPORTING_INTERVAL = intPreferencesKey("stationary_reporting_interval")
        private val ON_FOOT_REPORTING_INTERVAL = intPreferencesKey("on_foot_reporting_interval")
        private val VEHICLE_REPORTING_INTERVAL = intPreferencesKey("vehicle_reporting_interval")
        private val WHILE_ALERTING_REPORTING_INTERVAL = intPreferencesKey("while_alerting_reporting_interval")
        private val RESTING_HEART_RATE = intPreferencesKey("resting_heart_rate")
        private val ENABLE_SMS_FOR_ALERTS = booleanPreferencesKey("enable_sms_for_alerts")
        private val ENABLE_USER_WARNING = booleanPreferencesKey("enable_user_warning")
        private val SELECTED_PHYSIO_METRIC = stringPreferencesKey("selected_physio_metric")
        private val ENABLE_PHYSIOLOGICAL_ALERTS = booleanPreferencesKey("enable_physiological_alerts")
        private val ENABLE_COMPANION_APP_SERVICE = booleanPreferencesKey("enable_companion_app_service")
        private val ENABLE_CHAT_COMMANDS = booleanPreferencesKey("enable_chat_commands")
        private val ENABLE_SENSOR_MANAGER = booleanPreferencesKey("enable_sensor_manager")
        private val CHAT_COMMAND_CALLSIGNS = stringPreferencesKey("chat_command_callsigns")
        private val ENABLE_WEARTAK_DEV_MODE = booleanPreferencesKey("enable_weartak_dev_mode")
        private val BONDED_LOCATION_DIAGNOSTICS_ENABLED =
            booleanPreferencesKey("bonded_location_diagnostics_enabled")
        private val NETWORK_PREFERENCES_CHECKLIST_ENABLED = booleanPreferencesKey("network_preferences_checklist_enabled")
        private val NETWORK_PREFERENCES_LOCKED = booleanPreferencesKey("network_preferences_locked")
        private val SMS_NUMBERS_LIST = stringPreferencesKey("sms_numbers_list")
        private val RESTING_HR_HIGH_ALERT_BPM_THRESHOLD = intPreferencesKey("resting_hr_high_alert_bpm_threshold")
        private val RESTING_HR_LOW_ALERT_BPM_THRESHOLD = intPreferencesKey("resting_hr_low_alert_bpm_threshold")
        private val HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH = intPreferencesKey("high_resting_hr_warning_threshold_length")
        private val LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH = intPreferencesKey("low_resting_hr_warning_threshold_length")
        private val HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH = intPreferencesKey("high_resting_hr_alert_threshold_length")
        private val LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH = intPreferencesKey("low_resting_hr_alert_threshold_length")
        private val RESTING_HR_ALERT_BPM_LENGTH_THRESHOLD = intPreferencesKey("resting_hr_alert_bpm_length_threshold")
        private val CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON = stringPreferencesKey("cep_sustain_overrides_json")
        // WEAR-508 ManagedConfiguration CEP overlay persistence
        private val CEP_CONFIGURATION_RAW_JSON = stringPreferencesKey("cep_configuration_raw_json")
        private val CEP_CONFIGURATION_APPLIED_HASH = stringPreferencesKey("cep_configuration_applied_hash")
        private val CEP_CONFIGURATION_APPLIED_AT_MS = androidx.datastore.preferences.core.longPreferencesKey("cep_configuration_applied_at_ms")
        private val CEP_LAST_CONSUMED_RESET_EPOCH = androidx.datastore.preferences.core.longPreferencesKey("cep_last_consumed_reset_epoch")
        private val CEP_MANAGED_PAYLOAD_FAILURE_STATE_JSON = stringPreferencesKey("cep_managed_payload_failure_state_json")
        private val CEP_PLUGIN_OVERLAYS_JSON = stringPreferencesKey("cep_plugin_overlays_json")
        private val CEP_PLUGIN_LEASE_UNTIL_MS_JSON = stringPreferencesKey("cep_plugin_lease_until_ms_json")
        private val CEP_PLUGIN_CERT_LOG_JSON = stringPreferencesKey("cep_plugin_cert_log_json")
        private val CEP_DIAGNOSTICS_JSON = stringPreferencesKey("cep_diagnostics_json")
        private val EXERTION_THRESHOLD_WARNING = intPreferencesKey("exertion_threshold_warning")
        private val EXERTION_THRESHOLD_ALERT = intPreferencesKey("exertion_threshold_alert")
        private val EXERTION_WARNING_THRESHOLD_LENGTH = intPreferencesKey("exertion_warning_threshold_length")
        private val EXERTION_ALERT_THRESHOLD_LENGTH = intPreferencesKey("exertion_alert_threshold_length")
        private val HR_HIGH_ALERT_BPM_THRESHOLD = intPreferencesKey("hr_high_alert_bpm_threshold")
        private val HR_HIGH_ALERT_NUM_SECS_THRESHOLD = intPreferencesKey("hr_high_alert_num_secs_threshold")
        private val ENABLE_CRM_SERVICE = booleanPreferencesKey("enable_crm_service")
        private val CRM_RED_MAX_THRESHOLD = intPreferencesKey("crm_red_max_threshold")

        private val CRM_YELLOW_MAX_THRESHOLD = intPreferencesKey("crm_yellow_max_threshold")

        private val BATT_WARN_PERCENT = intPreferencesKey("batt_warn_percent")
        private val BATT_ALERT_PERCENT = intPreferencesKey("batt_alert_percent")
        private val AGE = intPreferencesKey("age")
        private val HEIGHT_FEET = intPreferencesKey("height_feet")
        private val HEIGHT_INCHES = intPreferencesKey("height_inches")
        private val WEIGHT = intPreferencesKey("weight")
        private val SEX = stringPreferencesKey("sex")
        private val BLOOD_TYPE = stringPreferencesKey("blood_type")
        private val ALLERGIES_KEY = stringPreferencesKey("allergies")
        private val ALLERGIES_KEY_STRING = stringPreferencesKey("allergies")

        private val USER_TYPE = stringPreferencesKey("user_type")
        private val UNIFORM_WAIST_SIZE = intPreferencesKey("uniform_waist_size")
        private val STRIDE_LENGTH = intPreferencesKey("stride_length")
        private val UNIFORM_PANTS_LENGTH = intPreferencesKey("uniform_pants_length")
        private val LOADOUT_WEIGHT = intPreferencesKey("loadout_weight")
        private val ENABLE_MULTICAST = booleanPreferencesKey("enable_multicast")
        private val MULTICAST_USER_CHOICE = booleanPreferencesKey("multicast_user_choice")
        private val MULTICAST_ADDRESS = stringPreferencesKey("multicast_address")
        private val MULTICAST_PROTOCOL = stringPreferencesKey("multicast_protocol")
        private val MULTICAST_PORT =intPreferencesKey("multicast_port")
        private val ENABLE_SITX_SERVICE = booleanPreferencesKey("enable_sitx_service")
        private val ENABLE_TAK_ENDPOINT = TAK_ENDPOINT_ENABLED
        private val ENABLE_ESRI_ENDPOINT = booleanPreferencesKey("enable_esri_endpoint")
        private val ESRI_ENDPOINT = stringPreferencesKey("esri_endpoint")
        private val ESRI_REPORTING_UPDATE_INTERVAL = intPreferencesKey("esri_reporting_update_interval")
        private val ENABLE_TABLET_COMMAND_ENDPOINT = booleanPreferencesKey("enable_tablet_command_endpoint")
        private val TABLET_COMMAND_ENDPOINT = stringPreferencesKey("tablet_command_endpoint")
        private val TABLET_COMMAND_APIKEY = stringPreferencesKey("tablet_command_apikey")
        private val TABLET_COMMAND_REPORTING_UPDATE_INTERVAL = intPreferencesKey("tablet_command_reporting_update_interval")
        private val YEAR_OF_BIRTH = intPreferencesKey("year_of_birth")
    }
}
