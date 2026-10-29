package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.cep.bus.CepBus
import kotlinx.coroutines.*
import android.util.Log
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_RESTING_HR
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import com.aitech.weartak_civ.cep.model.NumericSample
import com.aitech.weartak_civ.cep.model.payloads.Spo2EdgeState
import com.aitech.weartak_civ.cep.model.payloads.Spo2Tier
import com.aitech.weartak_civ.cep.model.payloads.Spo2TierEventPayload
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class CepEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val dataRepository: DataRepository
) {
    private val featureStore = FeatureStore()
    private val windows = WindowStore(RingConfig.default())
    private val profileStore = CepProvider.getProfile(appContext)
    private val seenTierEventLru = ArrayDeque<String>(32)
    private val seenTierEventSet = mutableSetOf<String>()
    private val warmUpCompleted = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val authoritative = AtomicBoolean(false)

    /**
     * Retains sensor ring buffers across a holder-mediated swap. Invoke on the replacement
     * engine after [buildEngine], before [warmUp]; does not copy [FeatureStore].
     */
    internal fun adoptWindowIngestionFrom(previous: CepEngine) {
        windows.adoptIngestionStateFrom(previous.windows)
        if (!requiresWeather || !previous.requiresWeather) windows.clearWeatherState()
    }

    /**
     * Test hook: feed a bus-shaped sample into [WindowStore] without starting coroutines.
     * Production path uses [CepBus] from [start].
     */
    internal fun ingestForWindowTesting(evt: CepEvent<out CepPayload>, tsMs: Long = evt.ts) {
        windows.ingest(evt, featureStore, tsMs)
    }

    internal fun peekNumericRingLast(topic: String): Double? = windows.peekNumericRingLast(topic)

    /** Snapshot of the rule IDs this engine evaluates. Used by the holder/provider to
     *  compute removed-rule sets across a swap so [AlertRegistry] entries can be cleared. */
    val ruleIds: Set<String> get() = config.rules.map { it.id }.toSet()
    val featureIds: Set<String> get() = config.features.map { it.id }.toSet()
    val requiresWeather: Boolean = WeatherDependencies.requiresWeather(config)

    @Volatile
    private var sustainOverridesMain: Map<String, Long> = emptyMap()

    @Volatile
    private var sustainOverridesWarn: Map<String, Long> = emptyMap()

    private val ruleEngine = RuleEngine(
        scope,
        config,
        featureStore,
        sustainOverrideMainProvider = { sustainOverridesMain },
        sustainOverrideWarnProvider = { sustainOverridesWarn }
    )

    private companion object {
        const val PRESSURE_IMMERSION_DISABLED_REASON = "settings.pressure.immersion_alerts_enabled=0"
        const val PHYSIO_ALERT_DISABLED_REASON = "settings.physio.alerts_enabled=0"
    }

    fun start() {
        if (!warmUpCompleted.get()) {
            Log.w("CEP", "start() ignored until warmUp() has completed")
            return
        }
        if (!started.compareAndSet(false, true)) {
            Log.w("CEP", "start() ignored: engine already started")
            return
        }
        authoritative.set(true)
        Log.d("CEP", "CEP Engine started")

        launchBusCollector()
        launchFeatureLoops()
        launchRuleLoop()
    }

    private fun launchBusCollector() {
        // Subscribe to the bus (no emitters yet; safe)
        // Pass evt.ts so WindowStore timestamps reflect sensor measurement time, not processing time.
        scope.launch { CepBus.events.collect { evt -> windows.ingest(evt, featureStore, evt.ts) } }
    }

    private fun launchFeatureLoops() {
        // Each feature runs in its own coroutine at its own updateIntervalMs cadence.
        // Failures stay isolated to the feature that threw.
        for (def in config.features) {
            scope.launch {
                while (isActive) {
                    try {
                        when (def) {
                            is FeatureDef.Window -> windows.computeWindow(def, featureStore)
                            is FeatureDef.MultiWindow -> windows.computeMultiWindow(def, featureStore)
                            is FeatureDef.SlopeMulti -> windows.computeSlopeMulti(def, featureStore)
                            is FeatureDef.Delta -> windows.computeDelta(def, featureStore)
                            is FeatureDef.Derived -> DerivedEval.eval(def, featureStore)
                            is FeatureDef.SampleWindow -> windows.computeSampleWindow(def, featureStore)
                            is FeatureDef.SampleDelta -> windows.computeSampleDelta(def, featureStore)
                            is FeatureDef.SampleSlope -> windows.computeSampleSlope(def, featureStore)
                        }
                    } catch (ex: Throwable) {
                        Log.e("CEP", "Feature compute failed for ${def.id} — isolated, engine continues", ex)
                    }
                    delay(def.updateIntervalMs)
                }
            }
        }
    }

    private fun launchRuleLoop() {
        scope.launch {
            val tickMs = config.ruleEvalIntervalMs.toLong()
            while (isActive) {
                featureStore.beginTick()
                featureStore.logDerivedTick("CEP")
                ruleEngine.processTick()
                checkAndClearAlerts()
                delay(tickMs)
            }
        }
    }

    fun updateHrProfile() {
        scope.launch { loadHrProfile() }
    }

    /**
     * Synchronous form of [updateHrProfile]. Used by the holder/provider warm-up path so
     * the new engine's `profile.*` and `thresholds.hr.*` keys are populated BEFORE authority
     * transfers (per [Update v1.1 — U9]).
     */
    private suspend fun loadHrProfile() {
        try {
            val newYearOfBirth = dataRepository.getYearOfBirth.first()
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val age = (currentYear - newYearOfBirth).coerceIn(0, 200)

            val hrMax = ZoneHelper.tanakaHrMax(age)

            val hrRest = dataRepository.getRestingHeartRate.first() ?: DEFAULT_RESTING_HR
            val hrRestingHigh = dataRepository.getRestingHrHighAlertBpmThreshold.first() ?: DEFAULT_HIGH_RESTING_HR_THRESHOLD
            val hrRestingLow  = dataRepository.getRestingHrLowAlertBpmThreshold.first() ?: DEFAULT_LOW_RESTING_HR_THRESHOLD

            featureStore.put("profile.hr_rest_bpm", hrRest)
            featureStore.put("profile.hr_max_bpm", hrMax)

            featureStore.put("thresholds.hr.rest_high_bpm", hrRestingHigh)
            featureStore.put("thresholds.hr.rest_low_bpm",  hrRestingLow)

            Log.d("CEP", "Updated HR profile: YOB=$newYearOfBirth age=$age " +
                    "hrRest=$hrRest hrMax=$hrMax hrHighResting=$hrRestingHigh hrLowResting=$hrRestingLow"
            )
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to update HR profile from DataRepository", t)
        }
    }

    fun updatePhysioAlertProfile() {
        scope.launch {
            loadPhysioAlertProfile()
            if (authoritative.get()) {
                try {
                    applyPhysioAlertHygiene()
                } catch (t: Throwable) {
                    Log.e("CEP", "Failed to clear disabled physio alerts after profile update", t)
                }
            }
        }
    }

    fun updateHrrProfile() {
        scope.launch { loadHrrProfile() }
    }

    /** Reload physiological settings after the settings write has completed. */
    suspend fun refreshPhysiologicalRuntime(reloadSustainOverrides: Boolean) {
        loadPhysioAlertProfile()
        loadHrProfile()
        loadHrrProfile()
        if (reloadSustainOverrides) {
            updateSustainOverrides()
        }
        if (authoritative.get()) {
            applyPhysioAlertHygiene()
        }
        Log.d("CEP", "Physiological runtime refresh complete sustainReloaded=$reloadSustainOverrides")
    }

    /** Synchronous form of [updateHrrProfile] used during warm-up. */
    private suspend fun loadHrrProfile() {
        try {
            val exertWarn = dataRepository.getExertionThresholdWarning.first() ?: DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
            val exertAlert = dataRepository.getExertionThresholdAlert.first() ?: DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE

            featureStore.put("thresholds.hrr.exert_warn_percent", exertWarn.toDouble())
            featureStore.put("thresholds.hrr.exert_alert_percent", exertAlert.toDouble())

            Log.d("CEP", "Loaded exertion thresholds: warn=$exertWarn% alert=$exertAlert%")
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to update HRR profile from DataRepository", t)
        }
    }

    /**
     * Load atmospheric-pressure alert thresholds from DataRepository into FeatureStore
     * so derived features (features.pressure.above_high_threshold, below_low_threshold)
     * can compare against them without hard-coding numeric values in the rules JSON.
     *
     * Call from start() and whenever the user changes pressure alert thresholds in settings.
     */
    fun updatePressureProfile() {
        scope.launch {
            loadPressureProfile()
            if (authoritative.get()) {
                try {
                    clearDisabledPressureAlertsFromRepository()
                } catch (t: Throwable) {
                    Log.e("CEP", "Failed to clear disabled pressure alerts after profile update", t)
                }
            }
        }
    }

    fun updateSpo2Profile() {
        val family = profileStore.spo2BaselineFamily()
        val bucket = profileStore.spo2BaselineAltitudeBucket()
        val baseline = profileStore.spo2Baseline(family, bucket)
        val provisional = profileStore.spo2BaselineProvisional(family, bucket)
        featureStore.put("profile.spo2_active_baseline_pct", baseline)
        featureStore.put("profile.spo2_active_baseline_provisional", if (provisional) 1.0 else 0.0)
        featureStore.put("profile.spo2_active_baseline_altitude_bucket", bucket.toDouble())
        featureStore.put("profile.spo2_active_baseline_family", if (family == "walk") 1.0 else 0.0)
        featureStore.put("profile.spo2_baseline_pct", baseline)
        featureStore.put("profile.spo2_baseline_altitude_bucket", bucket.toDouble())
        featureStore.put("profile.spo2_baseline_family", if (family == "walk") 1.0 else 0.0)
        featureStore.put("profile.spo2_baseline_provisional", if (provisional) 1.0 else 0.0)
        featureStore.put("thresholds.spo2.warn_floor", baseline - 4.0)
        featureStore.put("thresholds.spo2.alert_pct", 90.0)
        featureStore.put("thresholds.spo2.emergency_pct", 88.0)
        featureStore.put("settings.spo2.consecutive_required", 4.0)
        featureStore.put("settings.spo2.emergency_consecutive_required", 2.0)
    }

    /** Synchronous form of [updatePressureProfile] used during warm-up. */
    private suspend fun loadPressureProfile() {
        try {
            val highHpa = dataRepository.getHighAtmPressureAlertThreshold.first()
            val lowHpa  = dataRepository.getLowAtmPressureAlertThreshold.first()
            featureStore.put("thresholds.pressure.high", highHpa.toDouble())
            featureStore.put("thresholds.pressure.low",  lowHpa.toDouble())

            val immersionOn = dataRepository.effectiveEnableImmersionAlerts.first()
            val lowAtmOn    = dataRepository.effectiveEnableLowAtmPressureAlert.first()
            val highAtmOn   = dataRepository.effectiveEnableHighAtmPressureAlert.first()
            featureStore.put("settings.pressure.immersion_alerts_enabled", if (immersionOn) 1.0 else 0.0)
            featureStore.put("settings.pressure.low_atm_alert_enabled",    if (lowAtmOn)    1.0 else 0.0)
            featureStore.put("settings.pressure.high_atm_alert_enabled",   if (highAtmOn)   1.0 else 0.0)

            Log.d("CEP", "Updated pressure profile: high=$highHpa low=$lowHpa hPa " +
                    "immersionOn=$immersionOn lowAtmOn=$lowAtmOn highAtmOn=$highAtmOn")
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to update pressure profile from DataRepository", t)
        }
    }

    /**
     * Synchronously seed all runtime state needed before the engine can produce correct ticks.
     * Called by `CepProvider` during the warm-up phase BEFORE installing this engine into
     * `CepEngineHolder` (per [Update v1.1 — U9]). Idempotent and safe to call before [start].
     *
     * After this returns, the engine has:
     *  - HR / HRR / pressure profile + thresholds populated
     *  - sustain overrides loaded for any rule with `allow_sustain_override == true`
     */
    suspend fun warmUp() {
        loadHrProfile()
        loadHrrProfile()
        loadPhysioAlertProfile()
        loadPressureProfile()
        updateSpo2Profile()
        updateSustainOverrides()
        warmUpCompleted.set(true)
    }

    /**
     * Post-swap pressure hygiene pass. This keeps warm-up pure while still allowing the
     * authoritative engine to clear alerts whose enable flags are currently off.
     */
    suspend fun applyPressureAlertHygiene() {
        try {
            clearDisabledPressureAlertsFromRepository()
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to apply pressure alert hygiene from DataRepository", t)
        }
    }

    /**
     * Post-swap physio hygiene pass. This mirrors the pressure hygiene path so disabled
     * HR / HRR / HRV alerts are cleared on startup and after managed-config swaps.
     */
    suspend fun applyPhysioAlertHygiene() {
        try {
            clearDisabledPhysioAlertsFromRepository()
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to apply physio alert hygiene from DataRepository", t)
        }
    }

    /**
     * Cancel this engine's coroutine scope. Safe to call only after the engine has been
     * removed from `CepEngineHolder`. Per [Update v1.1 — U1], the apply lane invokes this
     * AFTER the swap completes so that no old feature/rule coroutines remain scheduling work
     * once the new engine is authoritative.
     */
    fun cancel() {
        try {
            authoritative.set(false)
            (scope.coroutineContext[kotlinx.coroutines.Job])?.cancel()
        } catch (t: Throwable) {
            Log.w("CEP", "CepEngine.cancel(): ${t.message}")
        }
    }

    /**
     * Cancel and wait for this engine's scope to quiesce.
     *
     * The apply lane uses this before running global hygiene so the previous engine cannot
     * keep reading or mutating shared state while the replacement is being prepared.
     */
    suspend fun cancelAndJoin() {
        try {
            authoritative.set(false)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        } catch (t: Throwable) {
            Log.w("CEP", "CepEngine.cancelAndJoin(): ${t.message}")
        }
    }

    internal fun isAuthoritativeForTest(): Boolean = authoritative.get()
    internal fun isScopeCancelledForTest(): Boolean = scope.coroutineContext[kotlinx.coroutines.Job]?.isCancelled == true
    internal fun isScopeActiveForTest(): Boolean = scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    /**
     * Test-only: write known threshold values directly into FeatureStore, bypassing the async
     * DataRepository load.  Call before P6-T3 / P6-T4 / P6-T5 to make threshold-dependent
     * derived features deterministic regardless of device DataStore state.
     */
    fun injectPressureThresholdsForTest(lowHpa: Double, highHpa: Double) {
        featureStore.put("thresholds.pressure.high", highHpa)
        featureStore.put("thresholds.pressure.low",  lowHpa)
        Log.d("CEP", "[TEST] Injected pressure thresholds: low=$lowHpa high=$highHpa hPa")
    }

    /**
     * Test-only: write the three pressure alert enable flags synchronously into FeatureStore,
     * bypassing the async DataRepository load.  All P6 scenarios call this with all flags = true
     * so the enable-flag gate in each pressure rule does not suppress test assertions.
     */
    fun injectPressureAlertFlagsForTest(immersion: Boolean = true, lowAtm: Boolean = true, highAtm: Boolean = true) {
        featureStore.put("settings.pressure.immersion_alerts_enabled", if (immersion) 1.0 else 0.0)
        featureStore.put("settings.pressure.low_atm_alert_enabled",    if (lowAtm)    1.0 else 0.0)
        featureStore.put("settings.pressure.high_atm_alert_enabled",   if (highAtm)   1.0 else 0.0)
        Log.d("CEP", "[TEST] Injected pressure alert flags: immersion=$immersion lowAtm=$lowAtm highAtm=$highAtm")
    }

    /**
     * Test-only: drop the pressure numeric ring and clear all pressure-derived keys from
     * FeatureStore so each Phase 6 scenario starts from a completely clean state.
     */

    /** Test-only: snapshot all pressure-related FeatureStore keys for FAIL diagnostics. */
    fun pressureFeatureSnapshot(): Map<String, Any?> = featureStore.snapshot(
        listOf(
            "pressure", "pressure.accuracy",
            "thresholds.pressure.low", "thresholds.pressure.high",
            "features.pressure.mean_n5", "features.pressure.delta_n5",
            "features.pressure.delta_rapid_rise", "features.pressure.delta_shallow_rise",
            "features.pressure.below_low_threshold", "features.pressure.above_high_threshold"
        )
    )

    /** Test-only: snapshot SpO2 profile and threshold keys for warm-up assertions. */
    internal fun spo2FeatureSnapshot(): Map<String, Any?> = featureStore.snapshot(
        listOf(
            "profile.spo2_active_baseline_pct",
            "profile.spo2_active_baseline_provisional",
            "profile.spo2_active_baseline_altitude_bucket",
            "profile.spo2_active_baseline_family",
            "profile.spo2_baseline_pct",
            "profile.spo2_baseline_altitude_bucket",
            "profile.spo2_baseline_family",
            "profile.spo2_baseline_provisional",
            "thresholds.spo2.warn_floor",
            "thresholds.spo2.alert_pct",
            "thresholds.spo2.emergency_pct",
            "settings.spo2.consecutive_required",
            "settings.spo2.emergency_consecutive_required"
        )
    )

    fun clearPressureStateForTest() {
        windows.clearRing("pressure")
        listOf(
            "pressure", "pressure.accuracy",
            "features.pressure.mean_n5",  "features.pressure.mean_n20",
            "features.pressure.delta_n5", "features.pressure.slope_n20",
            "features.pressure.submerge_rise",
            "features.pressure.delta_rapid_rise", "features.pressure.delta_shallow_rise",
            "features.pressure.above_high_threshold", "features.pressure.below_low_threshold"
        ).forEach { featureStore.clear(it) }
        // Note: settings.pressure.* flags are NOT cleared — they are injected by
        // injectPressureAlertFlagsForTest() and must survive across the clear so the
        // enable-gate conditions stay satisfied for the next scenario.
        Log.d("CEP", "[TEST] Pressure ring and derived features cleared")
    }

    fun resetSpo2SimulationStateForTest() {
        AlertRegistry.clearAll()
        ruleEngine.resetForTest()
        synchronized(seenTierEventLru) {
            seenTierEventLru.clear()
            seenTierEventSet.clear()
        }
        listOf(
            "spo2.pct",
            "spo2.status_ok",
            "spo2.ppg_red_sustained_ms",
            "spo2.tier_event_warn_edge",
            "spo2.tier_event_warn_clear",
            "spo2.tier_event_alert_edge",
            "spo2.tier_event_alert_clear",
            "spo2.tier_event_emergency_edge",
            "spo2.tier_event_emergency_clear"
        ).forEach { featureStore.clear(it) }
        Log.d("CEP", "[TEST] SpO2 rule timers, dispatch throttle, tier-event features, and dedupe state cleared")
    }

    /**
     * Load sustain overrides from DataRepository and apply only for rules with allowSustainOverride.
     * Call after engine start and whenever UI/settings save overrides (mirror updateHrProfile pattern).
     */
    suspend fun updateSustainOverrides() {
        Log.d("CEP", "updateSustainOverrides: loading threshold length values from DataRepository")
        try {
            val loaded = dataRepository.getCepSustainOverridesOnce()
            val overrideableIds: Set<String> = config.rules.filter { it.allowSustainOverride }.map { it.id }.toSet()
            sustainOverridesMain = loaded.main
                .filterKeys { it in overrideableIds }
                .mapValues { (_: String, v: Long) -> v.coerceAtLeast(0L) }
            sustainOverridesWarn = loaded.warn
                .filterKeys { it in overrideableIds }
                .mapValues { (_: String, v: Long) -> v.coerceAtLeast(0L) }
            if (sustainOverridesMain.isNotEmpty() || sustainOverridesWarn.isNotEmpty()) {
                Log.d("CEP", "updateSustainOverrides: main=${sustainOverridesMain.entries.joinToString { "${it.key}=${it.value}s" }} warn=${sustainOverridesWarn.entries.joinToString { "${it.key}=${it.value}s" }}")
            } else if (overrideableIds.isNotEmpty()) {
                Log.d("CEP", "updateSustainOverrides: no overrides in storage; overrideable rule ids=$overrideableIds")
            }
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to load CEP sustain overrides from DataRepository", t)
            sustainOverridesMain = emptyMap()
            sustainOverridesWarn = emptyMap()
        }
    }

    // Note: computeFeaturesSafely() was removed in Phase 3.
    // Feature compute is now done by per-feature coroutines launched in start().

    /**
     * Clear an active alert owned by [ruleId]. Public so that the holder/provider apply
     * lane can invoke this for rules removed by recompose, disable lists, or reset
     * (per Active-alert hygiene in WEAR-508 design).
     *
     * Idempotent: returns false if no alert is currently active for the rule.
     */
    suspend fun clearActiveAlertByRuleId(ruleId: String, reason: String? = null): Boolean {
        val alert = AlertRegistry.get(ruleId) ?: return false

        if (alert.priority == 1) {
            dataRepository.clearPhysioAndAutoGenAlertRequestUid(alert.uid)
        } else {
            dataRepository.removePhysioAndAutoGenAlertByUid(alert.uid)
        }
        AlertRegistry.clear(ruleId)
        dataRepository.addAlertClearedEventCep(alert.uid)
        Log.i("CEP", "Cleared active alert for rule=$ruleId reason=${reason ?: "unspecified"} uid=${alert.uid}")
        return true
    }

    private suspend fun clearDisabledPressureAlerts(lowAtmOn: Boolean, highAtmOn: Boolean, immersionOn: Boolean) {
        if (!lowAtmOn) {
            clearActiveAlertByRuleId(
                ruleId = "pressure_low_atm",
                reason = "settings.pressure.low_atm_alert_enabled=0"
            )
        }
        if (!highAtmOn) {
            clearActiveAlertByRuleId(
                ruleId = "pressure_high_atm",
                reason = "settings.pressure.high_atm_alert_enabled=0"
            )
        }
        if (!immersionOn) {
            clearActiveAlertByRuleId(
                ruleId = "pressure_dunk_detected",
                reason = PRESSURE_IMMERSION_DISABLED_REASON
            )
            clearActiveAlertByRuleId(
                ruleId = "pressure_shallow_dunk",
                reason = PRESSURE_IMMERSION_DISABLED_REASON
            )
            clearActiveAlertByRuleId(
                ruleId = "pressure_submerge_detected",
                reason = PRESSURE_IMMERSION_DISABLED_REASON
            )
        }
    }

    //Fix for cep feature key alert cancellation
    private suspend fun checkAndClearAlerts() {
        val now = System.currentTimeMillis()

        for (alert in AlertRegistry.allActive()) {
            val conditions = alert.exitConditions
            if (conditions.isEmpty()) continue

            val satisfiedFlags = conditions.map { cond ->
                val value = featureStore.latestAsDouble(cond.feature) ?: return@map false

                // Resolve thresholds: prefer literal, else feature-key
                val below = cond.exitBelow ?: cond.exitBelowKey?.let { key -> featureStore.latestAsDouble(key) }
                val above = cond.exitAbove ?: cond.exitAboveKey?.let { key -> featureStore.latestAsDouble(key) }

                val belowOk = below?.let { value < it - cond.hysteresis } ?: false
                val aboveOk = above?.let { value > it + cond.hysteresis } ?: false

                belowOk || aboveOk
            }

            val allOrAnySatisfied = when (alert.exitLogic) {
                "OR" -> satisfiedFlags.any { it }
                else -> satisfiedFlags.all { it }  // default AND
            }

            if (allOrAnySatisfied) {
                val since = alert.exitSatisfiedSinceMs
                if (since == null) {
                    alert.exitSatisfiedSinceMs = now
                } else {
                    val elapsedSec = (now - since) / 1000L
                    if (elapsedSec >= alert.exitSustainSec) {
                        if (alert.priority == 1) {
                            dataRepository.clearPhysioAndAutoGenAlertRequestUid(alert.uid)
                            AlertRegistry.clear(alert.ruleId)
                            dataRepository.addAlertClearedEventCep(alert.uid)
                        } else {
                            dataRepository.removePhysioAndAutoGenAlertByUid(alert.uid)
                            AlertRegistry.clear(alert.ruleId)
                            dataRepository.addAlertClearedEventCep(alert.uid)
                        }
                    }
                }
            } else {
                alert.exitSatisfiedSinceMs = null
            }
        }
    }

    fun ingest(path: String, value: Double, tsMs: Long = System.currentTimeMillis()) {
        if (!authoritative.get()) {
            return
        }
        try {
            val parts = path.split(".")
            if (parts.size < 2) return

            val ns  = parts[0]
            val key = parts.drop(1).joinToString(".")

            // write to store using the full dotted id
            featureStore.put("$ns.$key", value, tsMs)

            // publish to bus
            val evt = CepEvent(
                topic = "$ns.$key",
                payload = NumericSample(ns = ns, key = key, value = value),
                ts = tsMs
            )
            if (!CepBus.tryEmit(evt)) {
                scope.launch { CepBus.emit(evt) }
            }

            Log.i("CEP", "INGEST $ns.$key=$value @ $tsMs")
        } catch (ex: Exception) {
            Log.e("CEP", "Failed to ingest $path", ex)
        }
    }

    fun handleTierEvent(p: Spo2TierEventPayload) {
        val dedupeKey = "${p.tier.name}|${p.state.name}|${p.ts}"
        synchronized(seenTierEventLru) {
            if (!seenTierEventSet.add(dedupeKey)) {
                Log.d("CEP", "SPO2_TIER_EVENT_DEDUP key=$dedupeKey")
                return
            }
            seenTierEventLru.addLast(dedupeKey)
            while (seenTierEventLru.size > 32) {
                val evicted = seenTierEventLru.removeFirst()
                seenTierEventSet.remove(evicted)
            }
        }
        val ts = p.ts
        val keys = listOf("warn", "alert", "emergency")
        keys.forEach {
            featureStore.put("spo2.tier_event_${it}_edge", 0.0, ts)
            featureStore.put("spo2.tier_event_${it}_clear", 0.0, ts)
        }
        val tierKey = when (p.tier) {
            Spo2Tier.WARN -> "warn"
            Spo2Tier.ALERT -> "alert"
            Spo2Tier.EMERGENCY -> "emergency"
            Spo2Tier.NONE -> null
        }
        when (p.state) {
            Spo2EdgeState.ENTER -> {
                if (tierKey != null) {
                    featureStore.put("spo2.tier_event_${tierKey}_edge", 1.0, ts)
                    scope.launch {
                        delay(config.ruleEvalIntervalMs + 250L)
                        featureStore.put("spo2.tier_event_${tierKey}_edge", 0.0, System.currentTimeMillis())
                    }
                }
                if (p.pct.isFinite()) featureStore.put("spo2.pct", p.pct, ts)
            }
            Spo2EdgeState.CLEAR -> {
                if (tierKey != null) {
                    featureStore.put("spo2.tier_event_${tierKey}_clear", 1.0, ts)
                    featureStore.put("spo2.tier_event_${tierKey}_edge", 0.0, ts)
                    AlertRegistry.clear(
                        when (p.tier) {
                            Spo2Tier.WARN -> "spo2_warn_baseline_drop"
                            Spo2Tier.ALERT -> "spo2_alert_sustained_hypoxemia"
                            Spo2Tier.EMERGENCY -> "spo2_emergency_severe_hypoxemia"
                            Spo2Tier.NONE -> ""
                        }
                    )
                    scope.launch {
                        delay(config.ruleEvalIntervalMs + 250L)
                        featureStore.put("spo2.tier_event_${tierKey}_clear", 0.0, System.currentTimeMillis())
                    }
                }
            }
            Spo2EdgeState.HELD -> if (p.pct.isFinite()) featureStore.put("spo2.pct", p.pct, ts)
        }
    }

    private suspend fun clearDisabledPressureAlertsFromRepository() {
        val immersionOn = dataRepository.effectiveEnableImmersionAlerts.first()
        val lowAtmOn = dataRepository.effectiveEnableLowAtmPressureAlert.first()
        val highAtmOn = dataRepository.effectiveEnableHighAtmPressureAlert.first()
        clearDisabledPressureAlerts(lowAtmOn = lowAtmOn, highAtmOn = highAtmOn, immersionOn = immersionOn)
    }

    private suspend fun loadPhysioAlertProfile() {
        try {
            val physioAlertsOn = dataRepository.getEnablePhysiologicalAlerts.first()
            featureStore.put(
                "settings.physio.alerts_enabled",
                if (physioAlertsOn) 1.0 else 0.0
            )
            Log.d("CEP", "Updated physio alert flag: alertsOn=$physioAlertsOn")
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to update physio alert flag from DataRepository", t)
        }
    }

    private suspend fun clearDisabledPhysioAlertsFromRepository() {
        val physioAlertsOn = dataRepository.getEnablePhysiologicalAlerts.first()
        if (physioAlertsOn) return

        AlertRegistry.allActive()
            .map { it.ruleId }
            .filter { ruleId ->
                ruleId.startsWith("hr_") ||
                    ruleId.startsWith("hrv_") ||
                    ruleId.startsWith("exertion_")
            }
            .forEach { ruleId ->
                clearActiveAlertByRuleId(
                    ruleId = ruleId,
                    reason = PHYSIO_ALERT_DISABLED_REASON
                )
            }
    }

}
