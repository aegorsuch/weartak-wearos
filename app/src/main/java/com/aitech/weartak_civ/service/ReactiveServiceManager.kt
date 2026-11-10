/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ReactiveServiceManager is  a Foreground service  orchestrator
 * responsible for starting and  stopping  WearTAK services. The
 * manager reacts to state changes exposed by ServiceConfiguration
 * and  reconciles those against  what is actually running. Running
 * services are kept in the ServiceRegistry, which maps ServiceTypes
 * to Service::java.class level .isRunning states.
 *
 * Each service has three layers of state:
 *   1. UserSetting (intent): the raw toggle/MDM config ( e.g. -> repo.storeEnablePhysio(enable = true) ).
 *   2. PolicyGates (environment): dynamic gates such as license, permissions. Extensible
 *      to future gates like power management, service hierarchies.
 *   3. RuntimeState: whether the Android Service is actually running.
 *
 * Desired = UserSetting
 * Running = UserSetting AND PolicyGates
 *   - If Desired == true and Running == false → start the service (isRunning)
 *   - If Desired == false and Running == true → stop the service. (!isRunning)
 *
 * Manager behavior:
 *   - seeds Desired state once at startup from persistent store settings
 *   - observes getEnableService flows and reconciles them to RuntimeState
 *   - observes License changes and reconciles states based on their policy gates.
 *   - always starts dependencies before dependents
 *   - always stops dependents before dependencies
 *   - runs independent of any Activity lifecycle
 *   - handles processes for non runnable services (hail, physio, custom access).
 *
 * Note:
 *  1. PHYSIO is a virtual group (UI only) and is not directly
 *     orchestrated as a service. Its children (e.g., HeartRate, SkinTemp,
 *     ActivityRecognition) are reconciled & run in accordance but independently.
 *
 *  2. A handful of the functions are for enhanced debug logging. Some context
 *     for understanding the state logging table and where the values come from.
 *     We will use YY Service as an example.
 *
 *                 |------- MgdCfgUpdate() ------|----- ReSrvMgr ----|"
 *         SERVICE | R-KNOX  | CMP-OBJ | P-STORE | DESIRED | RUNNING |
 *
 *
 *          - R-KNOX  --> appRestrictions.getBoolean("YY_service_restriction_key")
 *              - Returns null if 1. Not Configured by Kox (unchcked) 2. No restriction exists for this service in Knox
 *          - CMP-OBJ --> MainActivity.enableYYService
 *              - In memory boolean from main activity, seeded from the users current setting for this Service's state.
 *                Useful to log bc it is the fallback  value on Knox updates. Also the in mem companion objs of main act
 *                can be accessed all over the app. Confirms no unexpected state flips.
 *
 *          - P-STORE --> enableYYServiceConfigUpdate = appRestrictions.getBoolean("YY_service_restriction_key", MainActivity.enableYYService)
 *              - AKA the ConfigUpdate, this is what is written to persistent store using the data repo fun:
 *                             < storeEnableYYService(enableYYServiceConfigUpdate) >
 *              - This value will trigger flow events in ReactiveServiceManager via collecting getServiceEnabledFlow(YYService)
 *
 *          - DESIRED --> desiredServicesStateMap[ServiceType.YY]
 *              - This value is equivalent to the persistent store value in data repo for YY service
 *              - We use the in mem map to check if we should reconcile states. If true, then knox/user wants to run this service
 *              - Using configured values in ServiceType.isKnoxMgdService, we can determine which services are ALWAYS
 *
 *          - RUNNING --> ServiceRegistry.runnableServiceClassMap[ServiceType.YY]  aka  isRunning for YYService::class.java
 *              - The actual running state is determined in reconcileStates() using policy checks for running state, availability (perms, license).
 *              - Services that are not runnable by this Manager (Physio, CustomAccess, Hail) will show False here (X)
 *
 */
class ReactiveServiceManager : Service() {

    companion object {
        private const val TAG = "WearTAK-ReactiveServiceMgr"
        private const val CHANNEL_ID = "ServiceManager_channel"
        private const val CHANNEL_NAME = "Service Manager"
        private const val NOTIF_ID = 77
        val isRunning = AtomicBoolean(false)
    }

    /*
     * Ordering and race-free states matter more than parallelism, so we use
     * our own single thread dispatcher for management (start/stop) of services
    */
    private val serviceLoop = Executors
        .newSingleThreadExecutor { r -> Thread(r, "svc--service-loop").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope  = CoroutineScope(SupervisorJob() + serviceLoop)

    private lateinit var registry: ServiceRegistry
    private lateinit var config: ServiceConfiguration

    // Snapshot of all the current service states (is this service supposed to be enabled or disabled)
    private val desiredServicesStateMap = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)

    // PHYSIO is a virtual service, representing a group of services (UI only). We want to only manage real services (YYY::class.java) that are present in the registry.
    private lateinit var runnableServicesList: List<ServiceType>

    // Support legacy data repository by lazily resolving repo from main App; fallback if needed.
    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    private val audioRepo by lazy {
        try {
            (applicationContext as WearTakApplication).audioRepo
        } catch (_: Throwable) {
            AudioCaptureRepository(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ReactiveServiceManager onCreate() Start...")
        isRunning.set(true)
        registry         = ServiceRegistry()
        config           = ServiceConfiguration(applicationContext, repo, audioRepo)
        runnableServicesList = registry.getAllServiceTypes()

        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW,
            channelId = CHANNEL_ID,
            channelName = CHANNEL_NAME
        )
        (application as WearTakApplication).chargingStayAwake.start()

        scope.launch {
            seedOnce()
            observeStateChanges()
            startManagedConfigUpdateCollector(repo, registry)
            pollPermissions()
            collectLicenseKey()
        }
        Log.i(TAG, "ReactiveServiceManager onCreate() End...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        (application as WearTakApplication).chargingStayAwake.stop()
        super.onDestroy()
        stopAll()
        scope.cancel()
        serviceLoop.close()
        stopForegroundServiceCompat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Seeding & Observing ----------
    private suspend fun seedOnce() {
        repo.awaitCompanionHistoryLoaded()
        // Initialize service state snapshot for real services
        runnableServicesList.forEach { type ->
            desiredServicesStateMap[type] = config.getServiceEnabledFlow(type).first()
        }
        // One initial reconciliation after seeding on bring up
        reconcileStates(RecType.INIT)
    }

    private val reconcileTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(FlowPreview::class)
    private fun observeStateChanges() {
        // set up watchers
        ServiceType.entries.forEach { type ->
            config.getServiceEnabledFlow(type)
                .distinctUntilChanged()
                .onEach { shouldRun ->
                    // avoid duplicate writes
                    if (desiredServicesStateMap[type] == shouldRun) return@onEach
                    desiredServicesStateMap[type] = shouldRun

                    // signal that "some change happened"
                    reconcileTrigger.tryEmit(Unit)
                }
                .launchIn(scope)
        }

        // coalesce all changes into a single reconcile
        reconcileTrigger
            .debounce(300)
            .onEach {
                reconcileStates(reason = RecType.STATE_CHANGE)
            }
            .launchIn(scope)
    }

    // Simple + effective: lightweight polling uses negligible battery, while subscribing to every permission type is far more complex since Android provides no single universal receiver.
    private fun pollPermissions() {
        scope.launch {
            while (isActive) {
                delay(5_000) // every 5s
                reconcileStates(reason = RecType.PERMISSIONS_POLL)
                val blocked = computeBlockedServices()
                repo.updateBlockedServices(blocked)

                val macro = computeMacroMissingPermissions()
                if (macro.distinct().sorted() != repo.missingPermissions.value) {
                    repo.updateMissingPermissions(macro.distinct().sorted())
                }
            }
        }
    }

    private fun computeMacroMissingPermissions(): List<String> {
        val desiredActive = desiredServicesStateMap
            .filter { it.value == true }
            .keys

        return config.missingPerms(desiredActive)
    }

    private fun computeBlockedServices(): Map<ServiceType, List<String>> {
        val desiredActive = desiredServicesStateMap
            .filter { it.value == true }
            .keys

        val blocked = EnumMap<ServiceType, List<String>>(ServiceType::class.java)
        desiredActive.forEach { t ->
            val missing = config.missingPerms(t) // you already have this private overload in ServiceConfiguration
            if (missing.isNotEmpty()) blocked[t] = missing
        }
        return blocked
    }

    enum class RecType {
        KNOX,
        PERMISSIONS_POLL,
        STATE_CHANGE,
        LICENSE_CHANGE,
        INIT,
        OTHER,
    }

    // Determine whether to start or stop each services. Checks service's permissions, running status, dependencies status
    /*
    private fun reconcileStates(reason: RecType) {
        val order = topoOrder()

        var change = false
        val changedTypes = mutableListOf<ServiceType>() // only want to log states of services that have changed.

        // START phase: dependencies before dependents (bottom up)
        order.forEach { t ->
            if (!runnableServicesList.contains(t)) {
                if (desiredServicesStateMap[t] == true && t == ServiceType.HAIL && !HailService.initialized.get()) {
                    HailService.initialize(appId = "com.rain.hail", context = applicationContext, deviceUid = MainActivity.device_uid)
                    changedTypes += t; change = true
                }
                return@forEach
            }
            val desired = desiredServicesStateMap[t] == true
            val running = registry.isServiceRunning(t)
            val available = config.isServiceAvailable(t)
            val deps = depsSatisfied(t)

            if (desired && !running && available && deps) {
                startOne(t)
                changedTypes += t; change = true
            }
        }

        // STOP phase: dependents before dependencies (top down)
        order.asReversed().forEach { t ->
            if (!runnableServicesList.contains(t)) {
                if (desiredServicesStateMap[t] == false && t == ServiceType.HAIL && HailService.initialized.get()) {
                    changedTypes += t; change = true
                }
                return@forEach
            }
            val desired = desiredServicesStateMap[t] == true
            val running = registry.isServiceRunning(t)
            val available = config.isServiceAvailable(t)

            if (running && (!desired || !available)) {
                Log.w(TAG, "Stopping $t (desired=$desired, available=${config.isServiceAvailable(t)})")
                stopOne(t)
                changedTypes += t; change = true
            }
        }

        if (change) {
            scope.launch {
                repo.updateServicesStateFlow(snapshotRunningServices())
                snapshotAllStages(repo, registry)

                when (reason) {
                    RecType.KNOX -> logKnoxMgdServicesWithDiff(repo, registry, reason, changedTypes)
                    RecType.PERMISSIONS_POLL -> logChangedRowsWithDiffs(repo, registry, reason, changedTypes)
                    RecType.STATE_CHANGE -> logChangedRowsWithDiffs(repo, registry, reason, changedTypes)
                    RecType.LICENSE_CHANGE -> logFullTableWithDiffs(repo, registry, reason)
                    RecType.INIT -> logFullTableWithDiffs(repo, registry, reason)
                    RecType.OTHER -> logFullTableWithDiffs(repo, registry, reason)
                }
            }
        }
    }
    */

    private fun reconcileStates(reason: RecType) {
        val order = topoOrder()

        var anyChange = false
        val changedTypes = mutableListOf<ServiceType>()

        // START phase (deps before dependents). Companion has no dependencies or dependents; start it
        // first so its BLE advertising is not queued behind slower main-thread service startups.
        anyChange = reconcileStartPhase(order.sortedBy { it != ServiceType.COMPANION_APP }, changedTypes) || anyChange

        // STOP phase (dependents before deps)
        anyChange = reconcileStopPhase(order.asReversed(), changedTypes) || anyChange

        if (anyChange) launchAndLog(reason, changedTypes)
    }

    /* -------------------- Phases -------------------- */

    private fun reconcileStartPhase(
        order: List<ServiceType>,
        changedTypes: MutableList<ServiceType>
    ): Boolean {
        var changed = false
        for (t in order) {
            // Non-runnable: only HAIL has side-effects here
            if (!isRunnable(t)) {
                if (shouldInitHail(t)) {
                    HailService.initialize(
                        appId = "com.rain.hail",
                        context = applicationContext,
                        deviceUid = MainActivity.device_uid
                    )
                    changedTypes += t
                    changed = true
                }
                continue
            }

            val ctx = ctx(t)
            if (shouldStart(ctx)) {
                startOne(t)
                changedTypes += t
                changed = true
            }
        }
        return changed
    }

    private fun reconcileStopPhase(
        orderReversed: List<ServiceType>,
        changedTypes: MutableList<ServiceType>
    ): Boolean {
        var changed = false
        for (t in orderReversed) {
            // Non-runnable: only HAIL has side-effects here
            if (!isRunnable(t)) {
                if (shouldMarkHailStop(t)) {
                    // Original code only marked change; it did not call a stop.
                    changedTypes += t
                    changed = true
                }
                continue
            }

            val c = ctx(t) // uses desired, running, available, depsSatisfied

            val shouldStop = c.running && (!c.desired || !c.available || !c.depsSatisfied)

            val desired = desiredServicesStateMap[t] == true
            val running = registry.isServiceRunning(t)
            val available = config.isServiceAvailable(t)

            if (shouldStop) {
                Log.w(TAG, "Stopping $t (desired=$desired, available=${config.isServiceAvailable(t)})")
                stopOne(c.type)
                changedTypes += c.type
                changed = true
            }
        }
        return changed
    }

    /* -------------------- Context & Predicates -------------------- */

    private data class ServiceCtx(
        val type: ServiceType,
        val desired: Boolean,
        val running: Boolean,
        val available: Boolean,
        val depsSatisfied: Boolean
    )

    private fun ctx(t: ServiceType) = ServiceCtx(
        type = t,
        desired = desiredServicesStateMap[t] == true,
        running = registry.isServiceRunning(t),
        available = config.isServiceAvailable(t),
        depsSatisfied = depsSatisfied(t)
    )

    private fun shouldStart(c: ServiceCtx): Boolean =
        c.desired &&
                !c.running &&
                c.available &&
                c.depsSatisfied

    private fun isRunnable(t: ServiceType): Boolean =
        runnableServicesList.contains(t)

    /* -------------------- HAIL special-cases (unchanged semantics) -------------------- */

    private fun shouldInitHail(t: ServiceType): Boolean =
        (desiredServicesStateMap[t] == true) &&
                (t == ServiceType.HAIL) &&
                !HailService.initialized.get()

    private fun shouldMarkHailStop(t: ServiceType): Boolean =
        (desiredServicesStateMap[t] == false) &&
                (t == ServiceType.HAIL) &&
                HailService.initialized.get()

    /* -------------------- Logging -------------------- */

    private fun launchAndLog(
        reason: RecType,
        changedTypes: List<ServiceType>
    ) {
        scope.launch {
            repo.updateServicesStateFlow(snapshotRunningServices())
            snapshotAllStages(repo, registry)

            when (reason) {
                RecType.KNOX,
                RecType.PERMISSIONS_POLL,
                RecType.STATE_CHANGE -> logChangedRowsWithDiffs(repo, registry, reason, changedTypes)

                RecType.LICENSE_CHANGE,
                RecType.INIT,
                RecType.OTHER -> logFullTableWithDiffs(repo, registry, reason)
            }
        }
    }


    private fun collectLicenseKey() {
        scope.launch {
            repo.getLicense
                .distinctUntilChanged()
                .collect { license ->
                    validateLicense(license)
                    if (!LicenseManager.isLicenseValid()) {
                        Log.w(TAG, "WearTAK License: [ $license ] is INVALID. Stopping services if not already stopped.")
                    } else {
                        Log.i(TAG, "WearTAK License: [ $license ] is VALID. Starting services if not already running.")
                    }
                    reconcileStates(RecType.LICENSE_CHANGE)
                }
        }
    }

    private fun depsSatisfied(t: ServiceType): Boolean =
        t.dependencies.all { d ->
            // Only consider real services as dependencies; virtual ones (like PHYSIO) are not in registry
            if (!runnableServicesList.contains(d)) return@all true
            desiredServicesStateMap[d] == true && registry.isServiceRunning(d)
        }

    private fun startOne(t: ServiceType) {
        val cls = registry.getServiceClass(t) ?: return
        Log.i(TAG, "ReactiveServiceManager Starting: ${t.name}")
        val intent = Intent(this, cls)
        try {
            if (t.isForegroundService) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: RuntimeException) {
            if (!RoutineServiceNotification.isForegroundStartRefusal(error)) throw error
            Log.w(TAG, "Start of ${t.name} refused while in background; retrying on next reconcile", error)
            return
        }
        registry.setServiceRunning(t, true)
        AppDiagnostics.record(DiagnosticEvent.SERVICE_REQUEST, mapOf(
            DiagnosticMetric.KIND to t.ordinal.toLong(), DiagnosticMetric.ACTIVE to 1L))
    }

    private fun stopOne(t: ServiceType) {
        val cls = registry.getServiceClass(t) ?: return
        Log.i(TAG, "ReactiveServiceManager Stopping: ${t.name}")
        stopService(Intent(this, cls))
        registry.setServiceRunning(t, false)
        AppDiagnostics.record(DiagnosticEvent.SERVICE_REQUEST, mapOf(
            DiagnosticMetric.KIND to t.ordinal.toLong(), DiagnosticMetric.ACTIVE to 0L))
    }

    private fun stopAll() {
        Log.i(TAG, "ReactiveServiceManager stopAll()")
        // Stop dependents before dependencies.
        topoOrder().asReversed().forEach { t ->
            if (!runnableServicesList.contains(t)) return@forEach
            if (registry.isServiceRunning(t)) {
                stopOne(t)
            }
        }
        scope.launch {
            logFullServiceStatesDebug(repo, registry, "Stopping all Services()")
        }
    }
    // ---------- Topological order (deps-first), cached in-memory ----------

    private var cachedTopo: List<ServiceType>? = null

    private fun topoOrder(): List<ServiceType> {
        cachedTopo?.let { return it }
        val result = mutableListOf<ServiceType>()
        val visited = mutableSetOf<ServiceType>()

        fun dfs(n: ServiceType) {
            if (!visited.add(n)) return
            n.dependencies.forEach(::dfs)
            result += n
        }

        // Build over the full enum graph, but only real nodes are acted upon later
        ServiceType.entries.forEach(::dfs)

        val ordered = result.distinct()
        cachedTopo = ordered
        return ordered
    }

    private val lastSnapshot: EnumMap<ServiceType, ServiceDebugState?> =
        EnumMap(ServiceType::class.java)

    private fun sym(b: Boolean?, emoji: Boolean = false): String = when (b) {
        null -> "-"
        true ->  if (emoji) "✅" else "T"
        false -> if (emoji) "❌" else "F"
    }

    private fun cell(prev: Boolean?, curr: Boolean?, emoji: Boolean = false): String =
        if (prev == curr) sym(curr, emoji) else "${sym(prev, emoji)} → ${sym(curr, emoji)}"

    private fun symDesired(curr: Boolean?, isManagedByKnox: Boolean?): String =
        if (isManagedByKnox == true) sym(curr) else "ALWAYS"   // or ✅?

    // If managed by Knox, show normal prev→curr; otherwise, force constant
    private fun cellDesired(prev: Boolean?, curr: Boolean?, isManagedByKnox: Boolean?): String =
        if (isManagedByKnox == true) cell(prev, curr) else symDesired(curr, isManagedByKnox)

    private fun normalizedRow(
        snapRow: ServiceDebugState?,        // may be null if no record
        desiredNow: Boolean,                // live
        runningNow: Boolean                 // live
    ): ServiceDebugState {
        return ServiceDebugState(
            type          = snapRow?.type ?: ServiceType.TAK_CONNECT, // type is only for identity; not printed
            remoteKnox    = snapRow?.remoteKnox,       // nullable
            localCompanion= snapRow?.localCompanion,   // nullable
            configUpdate  = snapRow?.configUpdate,     // nullable
            desired       = desiredNow,                // always shown (live)
            running       = runningNow,                // always shown (live)
            dependencies  = emptyList()
        )
    }

    private fun printHeader(reason: RecType, changed: List<ServiceType>?) {
        val header0  = "        |------- MgdCfgUpdate() ------|----- ReSrvMgr ----|"
        val header1  = "SERVICE | R-KNOX  | CMP-OBJ | P-STORE | DESIRED | RUNNING |"
        val sep    = "-".repeat(header1.length)
        val ctx    = buildString {
            append("Reason for Reconcile: ").append(reason)
            if (!changed.isNullOrEmpty()) append(" | Changed: ").append(changed.joinToString { it.name })
        }
        Log.v(TAG, sep)
        Log.i(TAG, ctx)
        Log.v(TAG, header0)
        Log.v(TAG, header1)
        Log.v(TAG, sep)
    }

    private fun printRow(t: ServiceType, prev: ServiceDebugState?, curr: ServiceDebugState) {
        Log.v(
            TAG,
            String.format(
                "%-7s | %-7s | %-7s | %-7s | %-7s | %-7s |",
                t.name.take(7),
                cell(prev?.remoteKnox,    curr.remoteKnox),
                cell(prev?.localCompanion,curr.localCompanion),
                cell(prev?.configUpdate,  curr.configUpdate),
                cellDesired(prev?.desired,       curr.desired, t.isKnoxMgdService),
                cell(prev?.running,       curr.running, emoji = true)
            )
        )
    }

    fun logKnoxMgdServicesWithDiff(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: RecType
    ) {
        if (!BuildConfig.DEBUG) return
        val snap = repo.serviceDebugStateFlow.value
        printHeader(reason, changed = null)

        val newSnapshot: EnumMap<ServiceType, ServiceDebugState?> = EnumMap(ServiceType::class.java)

        ServiceType.entries.forEach { t ->
            val desiredNow = (desiredServicesStateMap[t] == true)
            val runningNow = registry.isServiceRunning(t)
            val curr = normalizedRow(snap[t], desiredNow, runningNow)
            val prev = lastSnapshot[t]
            if (t.isKnoxMgdService) {
                printRow(t, prev, curr)
            }
            // store a fresh copy for future diffs
            newSnapshot[t] = curr.copy()
        }

        lastSnapshot.clear()
        lastSnapshot.putAll(newSnapshot)
    }

    fun logFullTableWithDiffs(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: RecType
    ) {
        if (!BuildConfig.DEBUG) return
        val snap = repo.serviceDebugStateFlow.value
        printHeader(reason, changed = null)

        val newSnapshot: EnumMap<ServiceType, ServiceDebugState?> = EnumMap(ServiceType::class.java)

        ServiceType.entries.forEach { t ->
            val desiredNow = (desiredServicesStateMap[t] == true)
            val runningNow = registry.isServiceRunning(t)
            val curr = normalizedRow(snap[t], desiredNow, runningNow)
            val prev = lastSnapshot[t]
            printRow(t, prev, curr)
            // store a fresh copy for future diffs
            newSnapshot[t] = curr.copy()
        }

        lastSnapshot.clear()
        lastSnapshot.putAll(newSnapshot)
    }

    private fun rowChanged(prev: ServiceDebugState?, curr: ServiceDebugState): Boolean {
        return prev?.remoteKnox     != curr.remoteKnox     ||
                prev?.localCompanion != curr.localCompanion ||
                prev?.configUpdate   != curr.configUpdate   ||
                prev?.desired        != curr.desired        ||
                prev?.running        != curr.running
    }

    fun logChangedRowsWithDiffs(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: RecType,
        changed: List<ServiceType> // from reconcileStates()
    ) {
        if (!BuildConfig.DEBUG) return
        val snap = repo.serviceDebugStateFlow.value
        val newSnapshot: EnumMap<ServiceType, ServiceDebugState?> = EnumMap(ServiceType::class.java)

        // Build current rows first
        val currentRows: Map<ServiceType, ServiceDebugState> =
            ServiceType.entries.associateWith { t ->
                normalizedRow(
                    snapRow    = snap[t],
                    desiredNow = (desiredServicesStateMap[t] == true),
                    runningNow = registry.isServiceRunning(t)
                )
            }

        // Determine actual changed rows (use hint to keep it tight, but verify)
        val actualChanged = changed.filter { t ->
            val prev = lastSnapshot[t]
            val curr = currentRows.getValue(t)
            rowChanged(prev, curr)
        }

        if (actualChanged.isEmpty()) return

        printHeader(reason, changed = actualChanged)

        actualChanged.forEach { t ->
            val prev = lastSnapshot[t]
            val curr = currentRows.getValue(t)
            printRow(t, prev, curr)
        }

        // Update full lastSnapshot with fresh copies
        ServiceType.entries.forEach { t -> newSnapshot[t] = currentRows.getValue(t).copy() }
        lastSnapshot.clear()
        lastSnapshot.putAll(newSnapshot)
    }

    data class ServiceDebugState(
        val type: ServiceType,
        val remoteKnox: Boolean?,       // nullable => null means “no restriction configured remotely”
        val localCompanion: Boolean?,   // MainActivity companion object states
        val configUpdate: Boolean?,     // remoteKnox ?: localPersisted → in MainActivity handleMgdConfigUpdate(), what we store if it has changed.
        val desired: Boolean?,          //  desiredServicesStateMap (does someone want this to run?)
        val running: Boolean,           // ServiceRegistry.isServiceRunning(type)  (is it actually running → permissions checked, license checked, dependent services are up).
        val dependencies: List<ServiceType> = emptyList()   // list of parent services (e.g. Chat depends on TakConnect).
    )

    data class ServiceState(
        val type: ServiceType,    // service identifier
        val running: Boolean,     // set by individual service oncreate() after starting
        val desired: Boolean,     // Request from UI, ManagedConfigs. Available
        val dependencies: List<ServiceType> = emptyList()  // services that must be started
    )

    private fun snapshotRunningServices(): EnumMap<ServiceType, Boolean> =
        EnumMap<ServiceType, Boolean>(ServiceType::class.java).apply {
            put(ServiceType.WEATHER,               WeatherService.isRunning.get())
            put(ServiceType.TAK_CONNECT,           TAKConnectService.isRunning.get())
            put(ServiceType.COT_DISPATCH,          CotDispatchService.isRunning.get())
            put(ServiceType.LOCATION,              LocationService.isRunning.get())
            put(ServiceType.REPORTING_INTERVAL,    ReportingIntervalStrategyService.isRunning.get())
            put(ServiceType.HEART_RATE,            HeartrateService.isRunning.get())
            put(ServiceType.SKIN_TEMP,             SkinTempService.isRunning.get())
            put(ServiceType.PRESSURE, PressureService.isRunning.get())
            put(ServiceType.PHYSIO,                desiredServicesStateMap.get(ServiceType.PHYSIO))
            put(ServiceType.DITTO,                 DittoService.isRunning.get())
            put(ServiceType.CHAT,                  ChatService.isRunning.get())
            put(ServiceType.AUDIO_CAP,             AudioCaptureService.isRunning.get())
            put(ServiceType.ONNX_GUNSHOT,          GunshotOnnxService.isRunning.get())
            put(ServiceType.ONNX_UAS,              UasOnnxService.isRunning.get())
            put(ServiceType.CRM_INFERENCE,         CrmInferenceService.isRunning.get())
            put(ServiceType.BLE_BEACON,            BleBeaconService.isRunning.get())
            put(ServiceType.MOVEMENT_DETECTION,    MovementDetectionService.isRunning.get())
            put(ServiceType.ACTIVITY_RECOGNITION,  ActivityRecognitionService.isRunning.get())
            put(ServiceType.OFF_BODY_DETECT,       OffBodyDetectService.isRunning.get())
            put(ServiceType.BATTERY,               BatteryPowerObserver.isRunning.get())
            put(ServiceType.POWER_MGR,             PowerManagerService.isRunning.get())
            put(ServiceType.GEOFENCE,              GeofenceService.isRunning.get())
        }

    private suspend fun publishDesiredSnapshot(repo: DataRepository) {
        val desired = EnumMap<ServiceType, Boolean>(ServiceType::class.java)
        ServiceType.entries.forEach { t ->
            desired[t] = (desiredServicesStateMap[t] == true)
        }
        repo.updateDebugStageDesired(desired)
    }

    private suspend fun publishRunningSnapshot(repo: DataRepository, registry: ServiceRegistry) {
        val running = EnumMap<ServiceType, Boolean>(ServiceType::class.java)
        ServiceType.entries.forEach { t ->
            running[t] = registry.isServiceRunning(t)
        }
        repo.updateDebugStageRunning(running)
    }

    suspend fun snapshotAllStages(repo: DataRepository, registry: ServiceRegistry) {
        publishDesiredSnapshot(repo)
        publishRunningSnapshot(repo, registry)
    }

    /**
     * Logs a single table of all services.
     * - remoteKnox/localPersisted/configUpdate: show "—" when no record exists.
     * - desired: computed live from desiredServicesStateMap (true/false).
     * - running: queried live from ServiceRegistry (true/false).
     *
     * @param reason  A short message describing *why* we are logging (e.g., "ManagedConfigUpdate applied")
     * @param changed Optionally list the services that triggered this log (purely informational)
     */
    fun logFullServiceStatesDebug(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: String,
        changed: List<ServiceType>? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val snap = repo.serviceDebugStateFlow.value   // EnumMap<ServiceType, ServiceDebugState>
        val header = "SERVICE | R-KNOX | LOCAL | CONFIG | DESIRED | RUNNING"
        val context = buildString {
            append("Reason: ").append(reason)
            if (!changed.isNullOrEmpty()) {
                append(" | Changed: ").append(changed.joinToString { it.name })
            }
        }
        val separator = "-".repeat(header.length)

        Log.v(TAG, context)
        Log.v(TAG, header)
        Log.v(TAG, separator)

        ServiceType.entries.forEach { t ->
            val row = snap[t]  // may be null if no record was ever written

            // show "—" when row is missing; desired & running are always shown
            val remoteKnox   = row?.remoteKnox                // Boolean?
            val localPersist = row?.localCompanion            // Boolean?
            val configUpdate = row?.configUpdate              // Boolean?
            val desiredNow   = (desiredServicesStateMap[t] == true) // Boolean
            val runningNow   = registry.isServiceRunning(t)          // Boolean

            Log.v(TAG, String.format(
                "%-7s | %-6s | %-5s | %-6s | %-7s | %-7s",
                t.name.take(7),
                sym(remoteKnox),
                sym(localPersist),
                sym(configUpdate),
                sym(desiredNow),
                sym(runningNow, emoji = true)
            ))
        }
    }

    fun startManagedConfigUpdateCollector(repo: DataRepository, registry: ServiceRegistry) {
        if (!BuildConfig.DEBUG) return
        scope.launch {
            repo.serviceDebugStateFlow
                // filter to only collect config stage as it is only changed when handleManagedConfig is run.
                .map { snap -> snap.mapValues { it.value?.configUpdate } }
                .distinctUntilChanged() // avoid repeats of same map
                .collect {
                    logKnoxMgdServicesWithDiff(
                        repo = repo,
                        registry = registry,
                        reason = RecType.KNOX
                    )
                }
        }
    }



    fun has(requiredPerms: List<String>): Boolean {
        val missingPerms = requiredPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty()) {
            Log.w(TAG, "We are missing permissions to run this Service!: $missingPerms")
            return false
        } else {
            Log.i(TAG, "All permissions for Service are granted!")
            return true
        }
    }

    // ---------- Enum (PHYSIO is virtual; never scheduled to run directly, it is a UI toggle that triggers sub services) ----------

    enum class ServiceType(
        val isForegroundService: Boolean = false,
        val dependencies: List<ServiceType> = emptyList(),
        val isKnoxMgdService: Boolean = false,
        val isNotRunnable: Boolean = false,  // PHYSIO is virtual, we can't start custom access, hail isn't a runnable a service
    ) {
        TAK_CONNECT(true, isKnoxMgdService = false),
        CHAT(true, dependencies = listOf(TAK_CONNECT), isKnoxMgdService = true),
        COT_DISPATCH(true, isKnoxMgdService = false),
        REPORTING_INTERVAL(true, isKnoxMgdService = false),
        SITX_SERVICE(true, isKnoxMgdService = true),
        LOCATION(true, isKnoxMgdService = true),
        PHYSIO(isKnoxMgdService = true, isNotRunnable = true), // virtual group: not in registry
        HEART_RATE(true, dependencies = listOf(PHYSIO), isKnoxMgdService = true),
        SKIN_TEMP(true, dependencies = listOf(PHYSIO), isKnoxMgdService = true),
        PRESSURE(true, isKnoxMgdService = true),
        ACTIVITY_RECOGNITION(true, isKnoxMgdService = true),
        HAIL(isKnoxMgdService = true, isNotRunnable = true),
        DITTO(true, isKnoxMgdService = true),
        BLE_BEACON(true, isKnoxMgdService = true),
        OFF_BODY_DETECT(true, isKnoxMgdService = false),
        BATTERY(isNotRunnable = true),
        GEOFENCE(true, isKnoxMgdService = true),
        POWER_MGR(true, isKnoxMgdService = false),
        BLOOD_HOUND(true, isKnoxMgdService = false),
//        BATTERY_ALERTS(true, isKnoxMgdService = false),
        MOVEMENT_DETECTION(true, isKnoxMgdService = false, dependencies = listOf(BLOOD_HOUND)),
        WEATHER(true, dependencies = listOf(LOCATION), isKnoxMgdService = false),
        AUDIO_CAP(true, isKnoxMgdService = false),
        ONNX_GUNSHOT(true, isKnoxMgdService = false, dependencies = listOf(AUDIO_CAP)),
        ONNX_UAS(true, isKnoxMgdService = false, dependencies = listOf(AUDIO_CAP)),
        CRM_INFERENCE(true, isKnoxMgdService = false, dependencies = listOf(SKIN_TEMP)),
        COMPANION_APP(isForegroundService = true, isKnoxMgdService = false),
        CEP_SIMULATION(isForegroundService = true, isKnoxMgdService = false)
    }
}
