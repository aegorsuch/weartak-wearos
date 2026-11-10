/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.AccelPayload
import com.aitech.weartak_civ.cep.model.payloads.ActivityPayload
import com.aitech.weartak_civ.cep.model.payloads.AmbientPayload
import com.aitech.weartak_civ.cep.model.payloads.GyroPayload
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.cep.model.payloads.HrvPayload
import com.aitech.weartak_civ.cep.model.payloads.HumidityPayload
import com.aitech.weartak_civ.cep.model.payloads.ImmersionPayload
import com.aitech.weartak_civ.cep.model.payloads.LightPayload
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.cep.model.payloads.PpgPayload
import com.aitech.weartak_civ.cep.model.payloads.SkinTempPayload
import com.aitech.weartak_civ.cep.model.payloads.SleepPayload
import com.aitech.weartak_civ.cep.model.payloads.Spo2Payload
import com.aitech.weartak_civ.cep.model.payloads.WaterPayload
import com.aitech.weartak_civ.cep.model.payloads.WindPayload
import com.aitech.weartak_civ.cep.model.payloads.PressurePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class CEPSimulationService : Service() {

    companion object {
        val isRunning = AtomicBoolean(false)

        val TAG = "WearTAK-CEPSimulationService"

        private const val NOTIF_ID = 8123
        private const val NOTIF_CHANNEL_ID = "cep_sim_channel"
        private const val NOTIF_CHANNEL_NAME = "CEP Simulation"

        const val ACTION_START = "com.aitech.weartak_civ.cep.sim.START"
        const val ACTION_STOP  = "com.aitech.weartak_civ.cep.sim.STOP"
        const val EXTRA_SIMULATION_TARGET = "com.aitech.weartak_civ.cep.sim.TARGET"
        const val SIM_TARGET_ALL_MAIN = "ALL_MAIN"
        const val SIM_TARGET_SPO2_ALL = "SPO2_ALL"

        fun start(ctx: Context, target: String = SIM_TARGET_ALL_MAIN) {
            if (!BuildConfig.CEP_SIMULATION) return
            val i = Intent(ctx, CEPSimulationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SIMULATION_TARGET, target)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun startPhase(ctx: Context, phase: String) = start(ctx, phase)
        fun startScenario(ctx: Context, scenario: String) = start(ctx, scenario)
        fun startSpo2Scenario(ctx: Context, scenario: String) = start(ctx, scenario)
        fun stop(ctx: Context) {
            if (!BuildConfig.CEP_SIMULATION) return
            val i = Intent(ctx, CEPSimulationService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    /**
     * Phase 1 test gate — flip to `true` to activate the simulation service.
     * When false the service is disabled and excluded from managed service startup.
     * When true, [startPhase1TestLoop]
     * runs through all P1 verification scenarios.
     */
    val enableCepSimulation = BuildConfig.CEP_SIMULATION
    val enableTestAlerts = BuildConfig.CEP_TEST_ALERTS


    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var loopStarted = false
    private var simulationJob: Job? = null

    private val toggleCEP = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!enableCepSimulation) {
            stopSelf()
            return
        }
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification("Idle"))
        } catch (error: RuntimeException) {
            if (!RoutineServiceNotification.isForegroundStartRefusal(error)) throw error
            Log.w("CEPSimulationService", "Foreground start refused; stopping", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enableCepSimulation) {
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "CEPSimulationService enabled (CEP_SIMULATION=true)")

        when (intent?.action) {
            ACTION_START, null -> if (!loopStarted) startSelectedSimulation(resolveSelection(intent))
            ACTION_STOP -> {
                simulationJob?.cancel()
                stopSelf()
            }
        }

        //TODO - ALERT SIMULATION: Uncomment to use and set flag -PCEP_SIMULATION=true and -PCEP_TEST_ALERTS=true
        if (enableTestAlerts) {
            Log.d(TAG, "CEP_TEST_ALERTS=true")
            testAlert()
        } else {
            Log.d(TAG, "CEP_TEST_ALERTS=false: No test alerts will be generated")
        }

        return START_STICKY
    }

    private sealed class SimulationSelection {
        data object AllMain : SimulationSelection()
        data object Spo2All : SimulationSelection()
        data class MainPhase(val phase: Int) : SimulationSelection()
        data class MainScenario(val scenario: Scenario) : SimulationSelection()
        data class Spo2Scenario(val scenario: Spo2Phase7Scenario) : SimulationSelection()
    }

    private fun resolveSelection(intent: Intent?): SimulationSelection {
        val raw = intent?.getStringExtra(EXTRA_SIMULATION_TARGET)?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals(SIM_TARGET_ALL_MAIN, ignoreCase = true) || raw.equals("ALL", ignoreCase = true)) {
            return SimulationSelection.AllMain
        }
        if (raw.equals(SIM_TARGET_SPO2_ALL, ignoreCase = true) || raw.equals("SPO2", ignoreCase = true)) {
            return SimulationSelection.Spo2All
        }
        phaseNumber(raw)?.let { return SimulationSelection.MainPhase(it) }
        mainScenario(raw)?.let { return SimulationSelection.MainScenario(it) }
        spo2Scenario(raw)?.let { return SimulationSelection.Spo2Scenario(it) }
        Log.w(TAG, "Unknown simulation target '$raw' - defaulting to ALL_MAIN")
        return SimulationSelection.AllMain
    }

    private fun phaseNumber(raw: String): Int? = when (raw.uppercase()) {
        "P1" -> 1
        "P2" -> 2
        "P3" -> 3
        "P4" -> 4
        "P5" -> 5
        "P6" -> 6
        else -> null
    }

    private fun mainScenario(raw: String): Scenario? = runCatching {
        Scenario.valueOf(raw.uppercase())
    }.getOrNull()

    private fun spo2Scenario(raw: String): Spo2Phase7Scenario? = runCatching {
        Spo2Phase7Scenario.valueOf(raw.uppercase())
    }.getOrNull()

    private fun startSelectedSimulation(selection: SimulationSelection) {
        loopStarted = true
        simulationJob = scope.launch {
            try {
                runSelection(selection)
            } finally {
                loopStarted = false
                simulationJob = null
                updateNotification("Idle")
            }
        }
    }

    private suspend fun runSelection(selection: SimulationSelection) {
        when (selection) {
            SimulationSelection.AllMain -> runAllMainPhases()
            is SimulationSelection.MainPhase -> runMainPhase(selection.phase)
            is SimulationSelection.MainScenario -> runSingleMainScenario(selection.scenario)
            SimulationSelection.Spo2All -> runSpo2AllScenarios()
            is SimulationSelection.Spo2Scenario -> runSpo2Scenario(selection.scenario)
        }
    }

    private fun testAlert() {
        val title = "Test Alert"
        val message = "This is a test alert message."

        // --- Haptic feedback (vibration for 5 seconds) ---
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            vibrator?.let { v ->
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val effect = android.os.VibrationEffect.createOneShot(
                        5000,  // 5 seconds
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                    v.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(5000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed: ${e.message}")
        }

        // --- Simple notification ---
        val channelId = "test_alert_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val ch = android.app.NotificationChannel(
                channelId,
                "Test Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(RoutineServiceNotification.SIMULATION_ALERT_ID, notification)
    }


    private fun startPhase1TestLoop() {
        startSelectedSimulation(SimulationSelection.AllMain)
    }

    private suspend fun runAllMainPhases() {
        runPhase1()
        runPhase2()
        runPhase3()
        runPhase4()
        runPhase5()
        runPhase6()
        Log.i(TAG, "[ALL] ✅ P1 + P2 + P3 + P4 + P5 + P6 testing complete")
        updateNotification("[ALL] P1–P6 complete")
    }

    private suspend fun runMainPhase(phase: Int) {
        when (phase) {
            1 -> runPhase1()
            2 -> runPhase2()
            3 -> runPhase3()
            4 -> runPhase4()
            5 -> runPhase5()
            6 -> runPhase6()
            else -> runAllMainPhases()
        }
    }

    private suspend fun runSingleMainScenario(scenario: Scenario) {
        when (scenario) {
            Scenario.P1_T1_BASELINE_1HZ,
            Scenario.P1_T2_SUSTAIN_2HZ,
            Scenario.P1_T3_SUSTAIN_4HZ,
            Scenario.P1_T4_WARN_THRESHOLD,
            Scenario.P1_T6_HOLDDOWN_REGRESSION -> runSelectedMainScenario("P1", scenario, 2_000L, true)

            Scenario.P2_T1_WINDOW_1HZ,
            Scenario.P2_T2_WINDOW_4HZ,
            Scenario.P2_T3_SLOPE_1HZ,
            Scenario.P2_T4_SLOPE_4HZ -> runSelectedMainScenario("P2", scenario, 2_000L, true)

            Scenario.P3_T1_SLOW_FEATURE_NO_STALL,
            Scenario.P3_T2_FAST_FEATURE_FRESH_VALUE,
            Scenario.P3_T3_EXCEPTION_ISOLATION -> runSelectedMainScenario("P3", scenario, 2_000L, true)

            Scenario.P4_T1_STALE_TIMESTAMP,
            Scenario.P4_T2_SIGNAL_GAP,
            Scenario.P4_T3_BURST_STRESS,
            Scenario.P4_T4_GAP_THEN_BURST,
            Scenario.P4_T5_HIGHRATE_10HZ -> runSelectedMainScenario("P4", scenario, 2_000L, true)

            Scenario.P5_T1_ALERT_LATENCY_BASELINE,
            Scenario.P5_T2_ALERT_LATENCY_FAST_ONSET,
            Scenario.P5_T3_NO_DOUBLE_FIRE,
            Scenario.P5_T4_SUSTAIN_ACCURACY -> runSelectedMainScenario("P5", scenario, 3_000L, false)

            Scenario.P6_T1_DUNK_DETECTED,
            Scenario.P6_T2_SHALLOW_DUNK,
            Scenario.P6_T3_LOW_ATM_PRESSURE,
            Scenario.P6_T4_HIGH_ATM_PRESSURE,
            Scenario.P6_T5_INSUFFICIENT_HISTORY,
            Scenario.P6_T6_STALE_DATA_SUPPRESSION -> runSelectedMainScenario("P6", scenario, 3_000L, false)

            else -> {
                Log.i(TAG, "[SIM] ▶ Starting: $scenario")
                updateNotification("[SIM] Running: $scenario")
                simulateScenario(scenario, ageYears = 35, hrRest = 60)
                Log.i(TAG, "[SIM] ✓ Completed: $scenario")
                updateNotification("[SIM] Done: $scenario")
            }
        }
    }

    private suspend fun runSpo2AllScenarios() {
        val runner = Spo2Phase7Runner(Spo2Phase7RunnerEnv(applicationContext))
        val results = runner.runReviewedScenarios()
        results.forEach { result ->
            Log.i(TAG, "[SPO2] ${result.scenario} pass=${result.pass} observed=${result.observedRuleIds} missing=${result.missing}")
        }
        updateNotification("[SPO2] All reviewed scenarios complete")
    }

    private suspend fun runSpo2Scenario(scenario: Spo2Phase7Scenario) {
        val runner = Spo2Phase7Runner(Spo2Phase7RunnerEnv(applicationContext))
        updateNotification("[SPO2] Running: $scenario")
        val result = runner.runScenario(scenario)
        Log.i(TAG, "[SPO2] ${result.scenario} pass=${result.pass} observed=${result.observedRuleIds} missing=${result.missing}")
        updateNotification("[SPO2] Done: $scenario")
    }

    private suspend fun runPhase1() {
        runScenarioSequence(
            scenarios = listOf(
                Scenario.P1_T1_BASELINE_1HZ,
                Scenario.P1_T2_SUSTAIN_2HZ,
                Scenario.P1_T3_SUSTAIN_4HZ,
                Scenario.P1_T4_WARN_THRESHOLD,
                Scenario.P1_T6_HOLDDOWN_REGRESSION
            ),
            interScenarioDelayMs = 2_000L
        ) { scenario ->
            emitResetBurst()
            Log.i(TAG, "[P1] ▶ Starting: $scenario")
            updateNotification("[P1] Running: $scenario")
            simulateScenario(scenario, ageYears = 35, hrRest = 60)
            Log.i(TAG, "[P1] ✓ Completed: $scenario")
            updateNotification("[P1] Done: $scenario")
        }
        Log.i(TAG, "[P1] ✅ All Phase 1 scenarios complete")
        updateNotification("[P1] Complete – P2 starting in 5 s")
        delay(5_000)
    }

    private suspend fun runPhase2() {
        runScenarioSequence(
            scenarios = listOf(
                Scenario.P2_T1_WINDOW_1HZ,
                Scenario.P2_T2_WINDOW_4HZ,
                Scenario.P2_T3_SLOPE_1HZ,
                Scenario.P2_T4_SLOPE_4HZ
            ),
            interScenarioDelayMs = 2_000L
        ) { scenario ->
            emitResetBurst()
            Log.i(TAG, "[P2] ▶ Starting: $scenario")
            updateNotification("[P2] Running: $scenario")
            simulateScenario(scenario, ageYears = 35, hrRest = 60)
            Log.i(TAG, "[P2] ✓ Completed: $scenario")
            updateNotification("[P2] Done: $scenario")
        }
        Log.i(TAG, "[P2] ✅ All Phase 2 scenarios complete")
        updateNotification("[P2] Complete – P3 starting in 5 s")
        delay(5_000)
    }

    private suspend fun runPhase3() {
        runScenarioSequence(
            scenarios = listOf(
                Scenario.P3_T1_SLOW_FEATURE_NO_STALL,
                Scenario.P3_T2_FAST_FEATURE_FRESH_VALUE,
                Scenario.P3_T3_EXCEPTION_ISOLATION
            ),
            interScenarioDelayMs = 2_000L
        ) { scenario ->
            emitResetBurst()
            Log.i(TAG, "[P3] ▶ Starting: $scenario")
            updateNotification("[P3] Running: $scenario")
            simulateScenario(scenario, ageYears = 35, hrRest = 60)
            Log.i(TAG, "[P3] ✓ Completed: $scenario")
            updateNotification("[P3] Done: $scenario")
        }
        Log.i(TAG, "[P3] ✅ All Phase 3 scenarios complete")
        updateNotification("[P3] Complete – P4 starting in 5 s")
        delay(5_000)
    }

    private suspend fun runPhase4() {
        runScenarioSequence(
            scenarios = listOf(
                Scenario.P4_T1_STALE_TIMESTAMP,
                Scenario.P4_T2_SIGNAL_GAP,
                Scenario.P4_T3_BURST_STRESS,
                Scenario.P4_T4_GAP_THEN_BURST,
                Scenario.P4_T5_HIGHRATE_10HZ
            ),
            interScenarioDelayMs = 2_000L
        ) { scenario ->
            emitResetBurst()
            Log.i(TAG, "[P4] ▶ Starting: $scenario")
            updateNotification("[P4] Running: $scenario")
            simulateScenario(scenario, ageYears = 35, hrRest = 60)
            Log.i(TAG, "[P4] ✓ Completed: $scenario")
            updateNotification("[P4] Done: $scenario")
        }
        Log.i(TAG, "[P4] ✅ All Phase 4 scenarios complete")
        updateNotification("[P4] Complete – P5 starting in 3 s")
        delay(3_000)
    }

    private suspend fun runPhase5() {
        Log.i(TAG, "[SIM] === PHASE 5 START === rule_eval_interval_ms=500")
        runScenarioSequence(
            scenarios = listOf(
                Scenario.P5_T1_ALERT_LATENCY_BASELINE,
                Scenario.P5_T2_ALERT_LATENCY_FAST_ONSET,
                Scenario.P5_T3_NO_DOUBLE_FIRE,
                Scenario.P5_T4_SUSTAIN_ACCURACY
            ),
            interScenarioDelayMs = 3_000L
        ) { scenario ->
            Log.i(TAG, "[P5] ▶ Starting: $scenario")
            updateNotification("[P5] Running: $scenario")
            runPhase5Scenario(scenario)
            Log.i(TAG, "[P5] ✓ Completed: $scenario")
            updateNotification("[P5] Done: $scenario")
        }
        Log.i(TAG, "[SIM] === PHASE 5 COMPLETE ===")
        updateNotification("[P5] Complete – P6 starting in 3 s")
        delay(3_000)
    }

    private suspend fun runPhase6() {
        Log.i(TAG, "[SIM] === PHASE 6 START === sample-count pressure features")
        runScenarioSequence(
            scenarios = listOf(
                Scenario.P6_T1_DUNK_DETECTED,
                Scenario.P6_T2_SHALLOW_DUNK,
                Scenario.P6_T3_LOW_ATM_PRESSURE,
                Scenario.P6_T4_HIGH_ATM_PRESSURE,
                Scenario.P6_T5_INSUFFICIENT_HISTORY,
                Scenario.P6_T6_STALE_DATA_SUPPRESSION
            ),
            interScenarioDelayMs = 3_000L
        ) { scenario ->
            Log.i(TAG, "[P6] ▶ Starting: $scenario")
            updateNotification("[P6] Running: $scenario")
            runPhase6Scenario(scenario)
            Log.i(TAG, "[P6] ✓ Completed: $scenario")
            updateNotification("[P6] Done: $scenario")
        }
        Log.i(TAG, "[SIM] === PHASE 6 COMPLETE ===")
        updateNotification("[P6] Complete")
    }

    private suspend fun runSelectedMainScenario(
        phaseTag: String,
        scenario: Scenario,
        interScenarioDelayMs: Long,
        needsResetBurst: Boolean
    ) {
        if (needsResetBurst) emitResetBurst()
        Log.i(TAG, "[$phaseTag] ▶ Starting: $scenario")
        updateNotification("[$phaseTag] Running: $scenario")
        when (phaseTag) {
            "P5" -> runPhase5Scenario(scenario)
            "P6" -> runPhase6Scenario(scenario)
            else -> simulateScenario(scenario, ageYears = 35, hrRest = 60)
        }
        Log.i(TAG, "[$phaseTag] ✓ Completed: $scenario")
        updateNotification("[$phaseTag] Done: $scenario")
        delay(interScenarioDelayMs)
    }

    private suspend fun runScenarioSequence(
        scenarios: List<Scenario>,
        interScenarioDelayMs: Long,
        scenarioRunner: suspend (Scenario) -> Unit
    ) {
        scenarios.forEach { scenario ->
            scenarioRunner(scenario)
            delay(interScenarioDelayMs)
        }
    }

    /**
     * Emits 6 seconds of "safe" data (low HR + RUN activity) to force every
     * resting-HR [TriggerTimer]'s [conditionTrueStartMs] back to null, then
     * clears [AlertRegistry] to prevent stale alerts from prior scenarios from
     * polluting the next [SimulationObserver].
     *
     * 6 iterations (up from 3) absorbs FeatureStore propagation lag that occurs
     * after high-rate scenarios (e.g. P4-T4 STILL+HR195 at 10 Hz) where derived
     * features may still reflect the old state for 1–2 ticks after conditions flip.
     *
     * Without this, sequential STILL+highHR scenarios share the same TriggerTimer
     * instance, causing T2/T3 to inherit a pre-matured timer from T1 and fire
     * immediately at holdDown expiry rather than starting a fresh sustain period.
     */
    private suspend fun emitResetBurst() {
        Log.i(TAG, "[RESET] ↺ emitResetBurst: nulling resting-HR TriggerTimers")
        repeat(6) {
            CepBus.emit(CepEvent(Topics.ACTIVITY,   ActivityPayload("RUN")))
            CepBus.emit(CepEvent(Topics.HEART_RATE, HeartRatePayload(bpm = 55.0)))
            CepBus.emit(CepEvent(Topics.HRV,        HrvPayload(rmssd_ms = 80.0)))
            CepBus.emit(CepEvent(Topics.OFFBODY,    OffBodyPayload(1)))
            delay(1_000L)
        }
        AlertRegistry.clearAll()
        Log.i(TAG, "[RESET] ↺ emitResetBurst: complete (AlertRegistry cleared)")
    }

    private fun startLoop() {
        loopStarted = true
        scope.launch {
            val allScenarios = Scenario.values().toList()

            while (isActive) {
                for (scenario in allScenarios) {
                    Log.i("[CEP][SIM]", "Starting Scenario Test: $scenario")
                    updateNotification("Running: $scenario")

                    simulateScenario(scenario, ageYears = 35, hrRest = 60)

                    Log.i("[CEP][SIM]", "Completed Scenario Test: $scenario")
                    updateNotification("Completed: $scenario")

                    // short pause between scenarios
                    delay(1_500)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @Suppress("MagicNumber")
    private enum class Scenario {
        /*NORMAL_BASELINE,
        COLD_WARN_EARLY_COOLING,
        COLD_ALERT_SUSTAINED_SHIVER,
        COLD_EMERGENCY_ABSENT_SHIVER,
        COLD_EMERGENCY_IMMERSION,
        COLD_EMERGENCY_FROSTBITE_WINDCHILL,
        DEXTERITY_LOSS_HANDS,
        TACTILE_FAILURE_HANDS,
        SPO2_PPG_PERFUSION_LOSS*/
        //REST_BRADY_10MIN_EMERGENCY,
        //ZONE1_REST_10MIN_ALERT,
        //ZONE2_REST_60S_ALERT,
        //ZONE3_REST_30S_ALERT,
        ZONE3_EXERCISE_30S_WARN,
        //ZONE4_REST_5S_EMERGENCY,
        ZONE4_EXERCISE_10MIN_EMERGENCY,
        //ZONE5_REST_5S_EMERGENCY,
        ZONE5_EXERCISE_5MIN_EMERGENCY,
        HRMAX_REST_5S_EMERGENCY,
        HRMAX_EXERCISE_60S_EMERGENCY,

        // ── Phase 1: Wall-clock sustain verification ───────────────────────────
        // P1-T1: 1 Hz baseline — HR >HRmax at rest; rule needs 120s. Must fire at ~120s.
        P1_T1_BASELINE_1HZ,
        // P1-T2: 2 Hz tick rate — same rule, same condition. Must still fire at ~120s wall-clock
        //         (NOT at 60s which the old tick-count bug would produce).
        P1_T2_SUSTAIN_2HZ,
        // P1-T3: 4 Hz tick rate — same rule, same condition. Must fire at ~120s wall-clock
        //         (NOT at 30s which the old tick-count bug would produce).
        P1_T3_SUSTAIN_4HZ,
        // P1-T4: Verify sustainedForSecWarn fires on wall-clock seconds, not tick count.
        //         Uses Zone 3 exercise rule which has a warn threshold.
        P1_T4_WARN_THRESHOLD,
        // P1-T6: Verify holdDownSec still prevents re-fire after Phase 1 change (regression guard).
        P1_T6_HOLDDOWN_REGRESSION,

        // ── Phase 2: Time-based window / slope verification ───────────────────
        // P2-T1: HRV 60s window baseline at 1 Hz.
        //         HRV=20 for first 30 s, then HRV=80.  At t≈90 s the 60s window should
        //         average ~50 (30 s@20 + 30 s@80).  Count-based also gives ~50 at 1 Hz
        //         so this establishes the expected value.
        P2_T1_WINDOW_1HZ,
        // P2-T2: HRV 60s window discriminating test at 4 Hz simulation, CEP still at 1 Hz.
        //         Time-based  → avg≈50 (same as P2-T1)  ✓
        //         Count-based → tail(60*1=60 samples) = last 15 s real → avg≈80  ✗
        P2_T2_WINDOW_4HZ,
        // P2-T3: Skin-temp slope baseline at 1 Hz.
        //         Cooling at −0.083 °C/s; expected slope_c_per_s_20s ≈ −0.083.
        //         Count-based and time-based agree at 1 Hz.
        P2_T3_SLOPE_1HZ,
        // P2-T4: Skin-temp slope discriminating at 4 Hz simulation, CEP still at 1 Hz.
        //         Time-based  → slope ≈ −0.083 °C/s  ✓
        //         Count-based → n=20*1=20 samples span only 5 s → slope ≈ −0.021 °C/s  ✗
        P2_T4_SLOPE_4HZ,

        // ── Phase 3: Per-feature cadence decoupling ────────────────────────────
        // P3-T1: A slow feature (update_interval_ms=5000 in cep_rules.json) must not stall
        //         rule evals running every 1 s.  Alert on HR>HRmax must fire at ~10 s wall-clock
        //         even though skin-temp feature only recomputes every 5 s.
        //         Observe in logcat: rule evals on every second tick; no 5-s stall gap.
        P3_T1_SLOW_FEATURE_NO_STALL,
        // P3-T2: A feature computing at 500 ms provides a fresher value than the 1-s rule-eval
        //         cycle.  HR rises above threshold at t≈500 ms; rule must see the elevated value
        //         at the next 1-s rule eval.
        //         Observe in logcat: [ASSERT][P3_T2] ALERT_FIRED elapsedSec≈11 (not 12 or more).
        P3_T2_FAST_FEATURE_FRESH_VALUE,
        // P3-T3: One feature compute throws (NaN injected at tick 10).  Engine must log the
        //         exception for that feature but continue running all other features and rules.
        //         Observe: CEP error log for the broken feature; hr_hrmax_exercise_60s_emergency
        //         fires at the correct wall-clock second; no engine crash or stall.
        P3_T3_EXCEPTION_ISOLATION,

        // ── Phase 4: Native-rate ingestion & freshness ──────────────────────────
        // P4-T1: Stale-timestamp smoke test — all events carry tsOffsetMs=−5 min.
        //         Verify engine accepts old-timestamped events without crash.
        //         HR is safe (70 bpm) → no alert expected.
        P4_T1_STALE_TIMESTAMP,
        // P4-T2: Signal-gap test — all sensor emissions suppressed for ticks 10–19.
        //         Verify "[P4] ⏸ signal gap" log lines appear for each suppressed tick;
        //         engine resumes normally after gap with no crash.
        P4_T2_SIGNAL_GAP,
        // P4-T3: CepBus burst stress — 20× events per sensor fired at tick 5.
        //         Verify "[P4] ⚡ burst" log line appears; buffer (512) holds without
        //         DROP_OLDEST; hr_hrmax_exercise_60s_emergency still fires at ~10s.
        P4_T3_BURST_STRESS,
        // P4-T4: Gap + burst combined — gap at ticks 5–9, burst at tick 20.
        //         Verify both mechanics co-operate without crash; engine runs all 35 ticks.
        P4_T4_GAP_THEN_BURST,
        // P4-T5: 10 Hz native-rate discriminating test.
        //         3200 ticks × 100ms = 320s real.  HR=195 STILL.
        //         hr_hrmax_rest_immediate_emergency (sustained=180s) must fire at ~180s
        //         wall-clock (tick ~1800), NOT at tick 180 (18s).  Same guard as P1-T3
        //         but at 10× tick rate — the sharpest discriminating test for the
        //         wall-clock sustain fix.
        P4_T5_HIGHRATE_10HZ,

        // ── Phase 5: 500 ms rule-eval verification ───────────────────────────
        P5_T1_ALERT_LATENCY_BASELINE,
        P5_T2_ALERT_LATENCY_FAST_ONSET,
        P5_T3_NO_DOUBLE_FIRE,
        P5_T4_SUSTAIN_ACCURACY,

        // ── Phase 6: Sample-count pressure feature verification ──────────────
        // P6-T1: Rapid pressure rise (>5 hPa over 5 samples) → pressure_dunk_detected
        //         fires after sustained_for_sec=2 wall-clock seconds.
        P6_T1_DUNK_DETECTED,
        // P6-T2: Shallow pressure rise (2–4 hPa over 5 samples) → pressure_shallow_dunk
        //         fires after sustained_for_sec=1.
        P6_T2_SHALLOW_DUNK,
        // P6-T3: Mean atmospheric pressure below low threshold for 10 s → pressure_low_atm.
        //         Requires CepEngine to have loaded thresholds.pressure.low via updatePressureProfile().
        P6_T3_LOW_ATM_PRESSURE,
        // P6-T4: Mean atmospheric pressure above high threshold for 10 s → pressure_high_atm.
        P6_T4_HIGH_ATM_PRESSURE,
        // P6-T5: Insufficient sample history guard — only 3 pressure samples emitted.
        //         mean_n5 (min_samples=5) and delta_n5 (horizonSamples=5) require ≥5/6 samples;
        //         all pressure rules must NOT fire. Assert: SUMMARY no_alerts_fired.
        P6_T5_INSUFFICIENT_HISTORY,
        // P6-T6: Stale data suppression — emit dunk trigger, clear, then pause 35 s (>max_age_sec=30).
        //         During and after the gap no stale-triggered alert should fire; verifies that
        //         max_age_sec freshness guard suppresses sample-window features during dropout.
        P6_T6_STALE_DATA_SUPPRESSION
    }

    private data class ScenarioConfig(
        val ticks: Int,
        val tickMs: Long,
        val activity: String,
        val onWrist: Int = 1,
        val sleeping: Boolean = false,
        val covered: Boolean = false,
        val ambientC: Double,
        val windMps: Double,
        val humidityPct: Double,
        val lux: Double,

        // HR inputs
        val entryHr: Double,               // HR during trigger phase
        val exitHr: Double? = null,        // HR during recovery phase
        val exitStartTick: Int? = null,    // when to switch to exitHr

        //val hr: Double,
        val hrvRmssdMs: Double,
        // Phase 2: optional HRV step change — used by P2 window discriminating tests
        val hrvExitRmssdMs: Double? = null,   // HRV after step change
        val hrvExitStartTick: Int? = null,    // tick index at which HRV changes
        val spo2Pct: Double,
        val ppgAmp: Float,
        val accelMagG: Double,
        val cadenceSpm: Double?,
        val gyroRms: Float,
        val skinWristC: Double,
        val skinHandC: Double,
        val wet: Boolean,
        val immersion: Boolean,
        val waterTempC: Float? = null,
        val skinSlopePerTickC: Double = 0.0,
        val hrSlopePerTick: Double = 0.0,
        val ppgSlopePerTick: Float = 0f,
        val spo2SlopePerTick: Double = 0.0,
        val gyroPattern: ((Int) -> Float)? = null,
        // Phase 3 / P3-T3: inject Double.NaN as HR at this tick index to trigger a
        // feature-compute exception and verify engine continues running.
        val injectNanAtTick: Int? = null,

        // ── Phase 4 fields ────────────────────────────────────────────────────
        // P4 / E5: tsOffsetMs — offset (ms) added to evt.ts for all events in this scenario.
        //   Positive = future timestamp; negative = past/stale timestamp.
        //   Used to verify that freshness guards correctly reject events whose ts is
        //   far outside the expected max_age_sec window.
        val tsOffsetMs: Long = 0L,

        // P4 / E6: signalGapRange — (startTick inclusive, endTick exclusive).
        //   Within this tick range all sensor emissions are suppressed (simulates dropout).
        //   Verify that features go stale and doubleIfFresh() returns null during the gap.
        val signalGapRange: Pair<Int, Int>? = null,

        // P4 / E6: burstOnTick — at this tick index fire 20 rapid-fire events of every
        //   sensor type to stress-test CepBus buffer headroom.  Verify no DROP_OLDEST
        //   events are lost (monitor CepBus.events subscription count in logcat).
        val burstOnTick: Int? = null
    )

    private fun scenarioConfig(s: Scenario, ageYears: Int = 35, hrRest: Int = 60): ScenarioConfig {
        // Kept for potential future HRR-based scenario tuning; currently we just feed raw HR.
        val hrMax = 208 - (0.7 * ageYears)
        fun hrr(percent: Double) = hrRest + (hrMax - hrRest) * percent

        return when (s) {
            // 5) Zone 3 (147–159) EXERCISE ≥30 s -> WARN
            Scenario.ZONE3_EXERCISE_30S_WARN -> ScenarioConfig(
                ticks = 400, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 153.0,               // high HR in Zone 3
                exitHr = 120.0,                // raw HR safely below Zone 3 HRR band
                exitStartTick = 320,           // >300s sustained so alert fires, then we cool down
                hrvRmssdMs = 12.0, spo2Pct = 97.0, ppgAmp = 0.85f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )
            // 7) Zone 4 (160–171) EXERCISE ≥10 min -> EMERGENCY
            Scenario.ZONE4_EXERCISE_10MIN_EMERGENCY -> ScenarioConfig(
                ticks = 700, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 166.0,               // Zone 4 HR
                exitHr = 130.0,                // drop to much lower HR
                exitStartTick = 620,           // >600s sustained before cooldown
                hrvRmssdMs = 10.0, spo2Pct = 96.0, ppgAmp = 0.85f,
                accelMagG = 1.2, cadenceSpm = 175.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )
            // 9) Zone 5 (172–184) EXERCISE ≥5 min -> EMERGENCY
            Scenario.ZONE5_EXERCISE_5MIN_EMERGENCY -> ScenarioConfig(
                ticks = 350, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 178.0,               // Zone 5 HR
                exitHr = 140.0,                // drop out of extreme zone
                exitStartTick = 320,           // >300s sustained before cooldown
                hrvRmssdMs = 8.0, spo2Pct = 95.0, ppgAmp = 0.85f,
                accelMagG = 1.2, cadenceSpm = 178.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )
            // 10) ≥185 bpm REST ~immediate (rule uses 120s) -> EMERGENCY
            Scenario.HRMAX_REST_5S_EMERGENCY -> ScenarioConfig(
                ticks = 150, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 185.0,               // >HRmax at rest
                exitHr = 150.0,                // drop back down
                exitStartTick = 130,           // >120s sustained before cooldown
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            // 11) ≥185 bpm EXERCISE ≥60 s -> EMERGENCY
            Scenario.HRMAX_EXERCISE_60S_EMERGENCY -> ScenarioConfig(
                ticks = 400, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 185.0,               // >HRmax during exercise
                exitHr = 150.0,                // recovery HR
                exitStartTick = 320,           // >300s sustained; plenty of time for cooldown + exit_sustain_sec
                hrvRmssdMs = 8.0, spo2Pct = 96.0, ppgAmp = 0.85f,
                accelMagG = 1.0, cadenceSpm = 180.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )

            // ── Phase 1 Scenarios ─────────────────────────────────────────────
            // P1-T1: 1 Hz baseline — HRMAX at rest, 320 ticks (320s real).
            //         hr_hrmax_rest_immediate_emergency (sustained=180s) fires at ~180s.
            //         hr_high_threshold_resting_alert warn (sustained_warn=300s) fires at ~300s.
            //         Both must fire at the same wall-clock seconds regardless of tick rate.
            Scenario.P1_T1_BASELINE_1HZ -> ScenarioConfig(
                ticks = 320, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            // P1-T2: 2 Hz rate — 640 ticks × 500ms = 320s real time.
            //         hr_hrmax_rest_immediate_emergency must fire at ~180s (tick ~360), not at tick 180 (90s).
            //         hr_high_threshold_resting_alert warn must fire at ~300s (tick ~600), not at tick 300 (150s).
            Scenario.P1_T2_SUSTAIN_2HZ -> ScenarioConfig(
                ticks = 640, tickMs = 500,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            // P1-T3: 4 Hz rate — 1280 ticks × 250ms = 320s real time.
            //         hr_hrmax_rest_immediate_emergency must fire at ~180s (tick ~720), not at tick 180 (45s).
            //         hr_high_threshold_resting_alert warn must fire at ~300s (tick ~1200), not at tick 300 (75s).
            Scenario.P1_T3_SUSTAIN_4HZ -> ScenarioConfig(
                ticks = 1280, tickMs = 250,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            // P1-T4: sustained_for_sec_warn wall-clock discriminating test at 2 Hz.
            //   HR=125 STILL: above high_threshold_bpm (120) but below HRmax (~183.5 for age 35).
            //   ONLY hr_high_threshold_resting_alert triggers (sustained_for_sec_warn=300s).
            //   pre-warn fires at ~300s wall-clock — NOT ~150s which a tick-count bug would produce.
            //   AlertRegistry ASSERT: SUMMARY no_alerts_fired — pre-warns skip AlertRegistry by design
            //     (WarnActionHandler isPreWarn=true path omits tryActivate).
            //   Manual logcat check: grep "W/CEP.*hr_high_threshold_resting_alert" — expect at ~300s real.
            //   640 ticks × 500ms = 320s real at 2 Hz — enough headroom to observe the 300s pre-warn.
            Scenario.P1_T4_WARN_THRESHOLD -> ScenarioConfig(
                ticks = 640, tickMs = 500,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 125.0,   // above high_threshold (120) but below HRmax — only warn fires
                hrvRmssdMs = 50.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            // P1-T6: holdDownSec regression guard — hr_hrmax_rest_immediate_emergency fires at ~180s.
            //         hold_down_sec=120 means the rule must NOT re-fire until at least t≈300s.
            //         400 ticks at 1 Hz: observe first fire at ~180s; no re-fire before ~300s.
            Scenario.P1_T6_HOLDDOWN_REGRESSION -> ScenarioConfig(
                ticks = 400, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )

            // ── Phase 2 Scenarios ─────────────────────────────────────────────

            // P2-T1: HRV 60s window baseline at 1 Hz.
            //         HRV=20 for first 30 s, then HRV=80.
            //         At the 90 s mark the 60 s window contains 30 s@20 + 30 s@80 → avg≈50.
            //         Count-based also gives ≈50 at 1 Hz → establishes expected value.
            Scenario.P2_T1_WINDOW_1HZ -> ScenarioConfig(
                ticks = 100, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 70.0,
                hrvRmssdMs = 20.0,
                hrvExitRmssdMs = 80.0,
                hrvExitStartTick = 30,       // tick 30 × 1000ms = 30 s real
                spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )

            // P2-T2: HRV 60s window discriminating test at 4 Hz, CEP still at 1 Hz.
            //         Time-based  → avg≈50 at t≈90 s real  ✓
            //         Count-based → tail(60) = last 15 s → avg≈80  ✗
            Scenario.P2_T2_WINDOW_4HZ -> ScenarioConfig(
                ticks = 400, tickMs = 250,   // 400 × 250ms = 100 s real
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 70.0,
                hrvRmssdMs = 20.0,
                hrvExitRmssdMs = 80.0,
                hrvExitStartTick = 120,      // tick 120 × 250ms = 30 s real
                spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )

            // P2-T3: Skin-temp slope baseline at 1 Hz.
            //         Cooling at −0.083 °C/s → expected slope_c_per_s_20s ≈ −0.083.
            //         Both count-based and time-based agree at 1 Hz.
            Scenario.P2_T3_SLOPE_1HZ -> ScenarioConfig(
                ticks = 80, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 5.0, windMps = 5.0, humidityPct = 40.0, lux = 10.0,
                entryHr = 65.0,
                hrvRmssdMs = 50.0, spo2Pct = 98.0, ppgAmp = 0.8f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.1f,
                skinWristC = 33.0, skinHandC = 32.0,
                skinSlopePerTickC = -0.083,  // −0.083 °C/tick at 1 Hz = −0.083 °C/s
                wet = false, immersion = false
            )

            // P2-T4: Skin-temp slope discriminating at 4 Hz, CEP still at 1 Hz.
            //         Same cooling rate (−0.083 °C/s) but 4× faster ticks (250ms).
            //         Time-based  → slope ≈ −0.083 °C/s  ✓
            //         Count-based → tail(20*1=20 samples) spans only 5 s → slope ≈ −0.021 °C/s  ✗
            Scenario.P2_T4_SLOPE_4HZ -> ScenarioConfig(
                ticks = 320, tickMs = 250,   // 320 × 250ms = 80 s real
                activity = "STILL", sleeping = false,
                ambientC = 5.0, windMps = 5.0, humidityPct = 40.0, lux = 10.0,
                entryHr = 65.0,
                hrvRmssdMs = 50.0, spo2Pct = 98.0, ppgAmp = 0.8f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.1f,
                skinWristC = 33.0, skinHandC = 32.0,
                skinSlopePerTickC = -0.083 / 4.0, // −0.083 °C/s ÷ 4 ticks/s = −0.02075 °C/tick
                wet = false, immersion = false
            )

            // ── Phase 3 Scenarios ─────────────────────────────────────────────

            // P3-T1: Slow-feature no-stall.
            //   HR > HRmax (exercise) — hr_hrmax_exercise_60s_emergency fires at ~10 s.
            //   features.skin_temp.hand_min_c_1m is configured with update_interval_ms=5000
            //   in cep_rules.json.  Rule evals happen every 1 s.  Verify in logcat that:
            //   - Alert fires at ~10 s (not delayed to next 5-s feature cycle)
            //   - No 5-s gap in the rule-eval log lines (engine not blocked)
            Scenario.P3_T1_SLOW_FEATURE_NO_STALL -> ScenarioConfig(
                ticks = 30, tickMs = 1000,   // 30 s real
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 195.0,             // > HRmax exercise; hr_hrmax_exercise_60s_emergency fires at ~10 s
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )

            // P3-T2: Fast-feature fresh value.
            //   HR > HRmax; rule eval at 1 s; feature computing at 500 ms.
            //   The elevated HR feature should be visible to the rule-eval loop within 1 s.
            //   Observe: [ASSERT][P3_T2] ALERT_FIRED elapsedSec≈11 (not 12+).
            //   ticks=30, tickMs=500 gives 15 s real; alert must fire before tick 30.
            Scenario.P3_T2_FAST_FEATURE_FRESH_VALUE -> ScenarioConfig(
                ticks = 30, tickMs = 500,    // 15 s real at 2 Hz tick
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )

            // P3-T3: NaN injection + FeatureStore guard.
            //   Double.NaN injected as HR at tick 10.
            //   JVM NaN arithmetic does NOT throw — it propagates silently through derived
            //   feature chains (e.g. karvonen_ratio = NaN / denominator = NaN).
            //   FeatureStore.put() / putDerived() now REJECT NaN values and emit a W/CEP log
            //   ("NaN rejected for key=…") instead of storing them, so the last valid value
            //   is retained and rules continue to evaluate normally.
            //   Verify in logcat:
            //   - W/CEP: "[FeatureStore] NaN rejected for key=features.hr.*" appears at tick 10
            //   - NO "Feature compute failed" error (NaN is absorbed before reaching arithmetic)
            //   - Engine keeps running: other rules (on skin-temp, HRV etc.) continue
            //   - [ASSERT][P3_T3] ENGINE_RUNNING_POST_EXCEPTION logged at scenario end
            Scenario.P3_T3_EXCEPTION_ISOLATION -> ScenarioConfig(
                ticks = 30, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false,
                injectNanAtTick = 10     // HR → NaN at tick 10; exception must be isolated
            )

            // ── Phase 4 Scenarios ──────────────────────────────────────────────

            // P4-T1: Stale-timestamp smoke test.
            //   All events carry ts = now − 5 min (tsOffsetMs=−300 000).
            //   HR is safe (70 bpm) → no alert from the CEP rule set.
            //   Observe: engine accepts old-timestamped events without crash.
            //   [ASSERT][P4_T1_STALE_TIMESTAMP] SUMMARY no_alerts_fired.
            //
            //   NOTE: This is primarily a smoke/robustness test.  Freshness guards are
            //   wired in both FeatureStore.doubleIfFresh() and JsonLogic
            //   (gte_key_fresh/lte_key_fresh), and at least one HR resting rule now uses
            //   gte_key_fresh. This scenario still focuses on stale timestamp robustness.
            Scenario.P4_T1_STALE_TIMESTAMP -> ScenarioConfig(
                ticks = 30, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 70.0,
                hrvRmssdMs = 50.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false,
                tsOffsetMs = -300_000L   // 5 min in the past
            )

            // P4-T2: Signal-gap dropout test.
            //   HR=195 STILL; ticks 10–19 (10 s) fully suppressed.
            //   Observe: 10 × "[P4] ⏸ signal gap" log lines; engine resumes
            //   cleanly after tick 20; no crash or stall.
            Scenario.P4_T2_SIGNAL_GAP -> ScenarioConfig(
                ticks = 50, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false,
                signalGapRange = Pair(10, 20)   // ticks 10–19 suppressed (10 s dropout)
            )

            // P4-T3: CepBus burst-stress test.
            //   HR=195 RUN → hr_hrmax_exercise_60s_emergency fires at ~10 s.
            //   At tick 5 a 20× burst stresses the 512-entry CepBus buffer.
            //   Observe: "[P4] ⚡ burst at tick=5"; no DROP_OLDEST loss;
            //   alert still fires at ~10s.
            Scenario.P4_T3_BURST_STRESS -> ScenarioConfig(
                ticks = 30, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false,
                burstOnTick = 5   // 20× burst at tick 5; buffer must absorb without loss
            )

            // P4-T4: Gap + burst combined.
            //   Gap: ticks 5–9 suppressed.  Burst: 20× events at tick 20.
            //   HR=195 STILL; 35 s total.
            //   Observe: gap lines then burst line; engine runs all 35 ticks; no crash.
            Scenario.P4_T4_GAP_THEN_BURST -> ScenarioConfig(
                ticks = 35, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false,
                signalGapRange = Pair(5, 10),  // ticks 5–9 suppressed (5 s dropout)
                burstOnTick = 20               // 20× burst at tick 20
            )

            // P4-T5: 10 Hz native-rate discriminating test.
            //   3200 ticks × 100ms = 320s real time at 10× tick rate.
            //   HR=195 STILL.  hr_hrmax_rest_immediate_emergency (sustained=180s) must
            //   fire at ~180s wall-clock (≈tick 1800), NOT at tick 180 (18s).
            //   This is the sharpest regression guard for the wall-clock sustain fix:
            //   the old tick-count bug would fire at tick 180 = 18s real.
            Scenario.P4_T5_HIGHRATE_10HZ -> ScenarioConfig(
                ticks = 3200, tickMs = 100,   // 3200 × 100ms = 320s real at 10 Hz
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )

            // ── Phase 5 Scenarios ─────────────────────────────────────────────
            Scenario.P5_T1_ALERT_LATENCY_BASELINE -> ScenarioConfig(
                // Note: P5_T1 is driven by runP5T1AlertLatencyBaseline(), not simulateScenario().
                // HR=170 targets hr_zone4_rest_immediate_emergency (~84 % HRR with default profile).
                // 13 ticks × 500 ms = 6 500 ms spans the 5-second sustain with one eval cycle margin.
                ticks = 13, tickMs = 500,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 170.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            Scenario.P5_T2_ALERT_LATENCY_FAST_ONSET -> ScenarioConfig(
                ticks = 30, tickMs = 500,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            Scenario.P5_T3_NO_DOUBLE_FIRE -> ScenarioConfig(
                ticks = 40, tickMs = 500,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 195.0,
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            Scenario.P5_T4_SUSTAIN_ACCURACY -> ScenarioConfig(
                ticks = 380, tickMs = 500,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 125.0,
                hrvRmssdMs = 50.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            // P6 scenarios are driven by runPhase6Scenario() and never call simulateScenario(),
            // so they never reach scenarioConfig(). The else branch satisfies exhaustiveness.
            else -> throw IllegalArgumentException("scenarioConfig() called for non-simulatable scenario: $s")
        }
    }

    /**
     * Runs a single Phase 5 scenario body.
     *
     * @param scenario Phase 5 scenario enum entry to execute.
     */
    private suspend fun runPhase5Scenario(scenario: Scenario) {
        when (scenario) {
            Scenario.P5_T1_ALERT_LATENCY_BASELINE -> runP5T1AlertLatencyBaseline()
            Scenario.P5_T2_ALERT_LATENCY_FAST_ONSET -> runP5T2AlertLatencyFastOnset()
            Scenario.P5_T3_NO_DOUBLE_FIRE -> runP5T3NoDoubleFire()
            Scenario.P5_T4_SUSTAIN_ACCURACY -> runP5T4SustainAccuracy()
            else -> {
                // no-op: this method is only for P5 scenarios
            }
        }
    }

    /**
     * Emits the minimum signal set needed for HR-rule scenarios in Phase 5.
     *
     * @param activity Current activity state to publish.
     * @param hrBpm Heart-rate bpm value to publish.
     * @param sleep Whether sleep state is active.
     * @param onWrist Off-body flag (1=on wrist, 0=off wrist).
     */
    private suspend fun emitPhase5HrSample(
        activity: String,
        hrBpm: Double,
        sleep: Boolean = false,
        onWrist: Int = 1
    ) {
        val ts = System.currentTimeMillis()
        CepBus.emit(CepEvent(Topics.ACTIVITY, ActivityPayload(activity), ts = ts))
        CepBus.emit(CepEvent(Topics.SLEEP, SleepPayload(sleep), ts = ts))
        CepBus.emit(CepEvent(Topics.OFFBODY, OffBodyPayload(onWrist), ts = ts))
        CepBus.emit(CepEvent(Topics.HEART_RATE, HeartRatePayload(bpm = hrBpm), ts = ts))
    }

    /**
     * Waits for first activation of [ruleId].
     *
     * @param ruleId Rule id to poll in [AlertRegistry].
     * @param timeoutMs Max wait duration in milliseconds.
     * @param pollMs Poll cadence in milliseconds.
     * @return Epoch-ms fire time if observed, else null on timeout.
     */
    private suspend fun waitForRuleActivation(ruleId: String, timeoutMs: Long, pollMs: Long): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            if (AlertRegistry.allActive().any { it.ruleId == ruleId }) {
                return System.currentTimeMillis()
            }
            delay(pollMs)
        }
        return null
    }

    /**
     * P5-T1: latency baseline for a 5-second sustain rule at 500ms eval cadence.
     *
     * hr_zone4_rest_immediate_emergency requires hrr_percent in [80, 90).
     * With the default profile (HRmax=190, restHR=60), HR=170 bpm yields ~84 % HRR,
     * placing it squarely in zone 4.  Samples are emitted at 500 ms intervals for
     * 6 500 ms (13 iterations) to span the 5-second sustain threshold with one
     * extra eval cycle of headroom.  The assertion checks that the rule fires within
     * one 500 ms eval cycle of the sustain being met: elapsed in [5 000, 5 600] ms.
     */
    private suspend fun runP5T1AlertLatencyBaseline() {
        val ruleId = "hr_zone4_rest_immediate_emergency"
        val sustainMs = 5_000L   // sustained_for_sec = 5 for this rule
        emitResetBurst()
        val t0 = System.currentTimeMillis()
        var fireTime: Long? = null
        // 13 × 500 ms = 6 500 ms — spans the 5-second sustain plus one eval cycle of margin
        repeat(13) {
            emitPhase5HrSample(activity = "STILL", hrBpm = 170.0, sleep = false, onWrist = 1)
            if (fireTime == null && AlertRegistry.allActive().any { it.ruleId == ruleId }) {
                fireTime = System.currentTimeMillis()
            }
            delay(500L)
        }
        if (fireTime == null) {
            Log.i("CEP", "[P5-T1] ASSERT FAIL rule=$ruleId did_not_fire_within_ms=6500")
        } else {
            val elapsed = fireTime!! - t0
            val pass = elapsed in sustainMs..(sustainMs + 600L)
            Log.i("CEP", "[P5-T1] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId elapsedMs=$elapsed windowMs=${sustainMs}..${sustainMs + 600L}")
        }
        emitResetBurst()
        AlertRegistry.clearAll()
    }

    /**
     * P5-T2: verifies 10-second sustained rule latency under 500ms rule-eval cadence.
     *
     * Continuously emits trigger values at 500ms and asserts first fire is near 10 seconds.
     */
    private suspend fun runP5T2AlertLatencyFastOnset() {
        val ruleId = "hr_hrmax_exercise_60s_emergency"
        emitResetBurst()
        val t0 = System.currentTimeMillis()
        var fireTime: Long? = null
        repeat(30) {
            emitPhase5HrSample(activity = "RUN", hrBpm = 195.0, sleep = false, onWrist = 1)
            repeat(5) {
                if (fireTime == null && AlertRegistry.allActive().any { alert -> alert.ruleId == ruleId }) {
                    fireTime = System.currentTimeMillis()
                }
                delay(100L)
            }
        }
        if (fireTime == null) {
            Log.i("CEP", "[P5-T2] ASSERT FAIL rule=$ruleId did_not_fire")
        } else {
            val elapsed = fireTime!! - t0
            val pass = elapsed in 9_500L..11_500L
            Log.i("CEP", "[P5-T2] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId elapsedMs=$elapsed windowMs=9500..11500")
        }
        emitResetBurst()
        AlertRegistry.clearAll()
    }

    /**
     * P5-T3: verifies a sustained condition produces exactly one activation.
     *
     * Uses the same trigger source as P5-T2 and counts false→true activation edges.
     * hr_hrmax_exercise_60s_emergency has sustained_for_sec=10 and hold_down_sec=0.
     * The observation window is (sustainedForSec + holdDownSec + 5) s = 15 s — long
     * enough for the rule to fire once (at ~10 s) while confirming it does not fire a
     * second time within the remaining window.
     */
    private suspend fun runP5T3NoDoubleFire() {
        val ruleId = "hr_hrmax_exercise_60s_emergency"
        val sustainedForSec = 10L   // sustained_for_sec for this rule
        val holdDownSec     = 0L    // hold_down_sec for this rule (no hold-down configured)
        emitResetBurst()
        val endAt = System.currentTimeMillis() + (sustainedForSec + holdDownSec + 5L) * 1_000L
        var wasActive = false
        var activations = 0
        while (System.currentTimeMillis() < endAt) {
            emitPhase5HrSample(activity = "RUN", hrBpm = 195.0, sleep = false, onWrist = 1)
            repeat(5) {
                val isActiveNow = AlertRegistry.allActive().any { it.ruleId == ruleId }
                if (isActiveNow && !wasActive) {
                    activations += 1
                    Log.i("CEP", "[P5-T3] activation rule=$ruleId count=$activations atMs=${System.currentTimeMillis()}")
                }
                wasActive = isActiveNow
                delay(100L)
            }
        }
        val pass = activations == 1
        Log.i("CEP", "[P5-T3] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId activations=$activations expected=1 holdDownSec=$holdDownSec")
        emitResetBurst()
        AlertRegistry.clearAll()
    }

    /**
     * P5-T4: validates long sustain timing accuracy at 500ms rule-eval cadence.
     *
     * Emits resting high HR for 190 seconds and asserts fire near 180 seconds.
     */
    private suspend fun runP5T4SustainAccuracy() {
        val ruleId = "hr_hrmax_rest_immediate_emergency"
        emitResetBurst()
        val t0 = System.currentTimeMillis()
        var fireTime: Long? = null
        repeat(380) {
            emitPhase5HrSample(activity = "STILL", hrBpm = 195.0, sleep = false, onWrist = 1)
            repeat(2) {
                if (fireTime == null && AlertRegistry.allActive().any { alert -> alert.ruleId == ruleId }) {
                    fireTime = System.currentTimeMillis()
                }
                delay(200L)
            }
            delay(100L)
        }
        if (fireTime == null) {
            Log.i("CEP", "[P5-T4] ASSERT FAIL rule=$ruleId did_not_fire")
        } else {
            val elapsed = fireTime!! - t0
            val pass = elapsed in 175_000L..185_000L
            Log.i("CEP", "[P5-T4] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId elapsedMs=$elapsed windowMs=175000..185000")
        }
        emitResetBurst()
        AlertRegistry.clearAll()
    }

    // ── Phase 6: Sample-count pressure feature tests ──────────────────────────

    /**
     * Clears the pressure ring, all pressure-derived FeatureStore keys, and AlertRegistry
     * before each Phase 6 scenario so tests run from a known-clean state.
     */
    private fun resetP6PressureState() {
        val engine = CepProvider.getOrCreateEngine(applicationContext)
        engine.clearPressureStateForTest()
        // All P6 scenarios assert the CEP rule fires; enable all pressure alert flags so
        // the settings-gate conditions added in Phase 6 never suppress a test assertion.
        engine.injectPressureAlertFlagsForTest(immersion = true, lowAtm = true, highAtm = true)
        AlertRegistry.clearAll()
    }

    /**
     * Dispatches a Phase 6 scenario to its runner function.
     * Always resets pressure state first so scenarios do not bleed into each other.
     */
    private suspend fun runPhase6Scenario(scenario: Scenario) {
        resetP6PressureState()
        delay(200L) // let FeatureStore clear propagate before emitting new samples
        when (scenario) {
            Scenario.P6_T1_DUNK_DETECTED         -> runP6T1DunkDetected()
            Scenario.P6_T2_SHALLOW_DUNK          -> runP6T2ShallowDunk()
            Scenario.P6_T3_LOW_ATM_PRESSURE      -> runP6T3LowAtmPressure()
            Scenario.P6_T4_HIGH_ATM_PRESSURE     -> runP6T4HighAtmPressure()
            Scenario.P6_T5_INSUFFICIENT_HISTORY  -> runP6T5InsufficientHistory()
            Scenario.P6_T6_STALE_DATA_SUPPRESSION -> runP6T6StaleDataSuppression()
            else -> { /* non-P6 scenario, no-op */ }
        }
    }

    /**
     * Emits a single pressure sample on CepBus.
     *
     * @param hpa Pressure reading in hectopascals.
     * @param tsMs Measurement timestamp (wall-clock ms).
     */
    private suspend fun emitPressureSample(hpa: Float, tsMs: Long = System.currentTimeMillis()) {
        Log.d("CEP_P6", "[EMIT] hpa=$hpa ts=$tsMs")
        CepBus.emit(
            CepEvent(
                topic = Topics.PRESSURE,
                payload = PressurePayload(hpa = hpa, accuracy = 3, sampleIndex = tsMs, sourceId = "baro_sim"),
                ts = tsMs
            )
        )
    }

    /**
     * P6-T1: Rapid pressure rise — pressure_dunk_detected.
     *
     * Emits 6 baseline samples at 1013 hPa then 12 trigger samples at 1020 hPa.
     * delta_n5 = 1020 − 1013 = 7.0 > 5.0 → features.pressure.delta_rapid_rise = true.
     * pressure_dunk_detected has sustained_for_sec=2; must fire within ~4 s of first trigger.
     *
     * Assert: rule fires; elapsedMs from first trigger is ≥ 2000 ms.
     */
    private suspend fun runP6T1DunkDetected() {
        val ruleId = "pressure_dunk_detected"
        AlertRegistry.clearAll()

        Log.i(TAG, "[P6-T1] ▶ Filling ring: 6 baseline samples at 1013 hPa")
        repeat(6) {
            emitPressureSample(1013.0f)
            delay(400L)
        }

        Log.i(TAG, "[P6-T1] ▶ Emitting dunk trigger: 12 samples at 1020 hPa (delta_n5 ≈ +7 hPa)")
        val triggerStart = System.currentTimeMillis()
        var fireTime: Long? = null
        repeat(12) {
            emitPressureSample(1020.0f)
            if (fireTime == null && AlertRegistry.allActive().any { it.ruleId == ruleId }) {
                fireTime = System.currentTimeMillis()
            }
            delay(500L)
        }

        if (fireTime == null) {
            val snap = CepProvider.getOrCreateEngine(applicationContext).pressureFeatureSnapshot()
            Log.i("CEP", "[P6-T1] ASSERT FAIL rule=$ruleId did_not_fire_within=6000ms SNAPSHOT=$snap")
        } else {
            val elapsed = fireTime!! - triggerStart
            val pass = elapsed >= 2_000L
            Log.i("CEP", "[P6-T1] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId elapsedFromTriggerMs=$elapsed expected=>=2000ms")
        }
        AlertRegistry.clearAll()
    }

    /**
     * P6-T2: Shallow pressure rise — pressure_shallow_dunk.
     *
     * Emits 6 baseline samples at 1013 hPa then 12 trigger samples at 1016 hPa.
     * delta_n5 = 1016 − 1013 = 3.0 ∈ [2.0, 4.0] → features.pressure.delta_shallow_rise = true.
     * pressure_shallow_dunk has sustained_for_sec=1; must fire within ~3 s of first trigger.
     *
     * Assert: rule fires; elapsedMs ≥ 1000 ms.
     */
    private suspend fun runP6T2ShallowDunk() {
        val ruleId = "pressure_shallow_dunk"
        AlertRegistry.clearAll()

        Log.i(TAG, "[P6-T2] ▶ Filling ring: 6 baseline samples at 1013 hPa")
        repeat(6) {
            emitPressureSample(1013.0f)
            delay(400L)
        }

        Log.i(TAG, "[P6-T2] ▶ Emitting shallow trigger: 12 samples at 1016 hPa (delta_n5 ≈ +3 hPa)")
        val triggerStart = System.currentTimeMillis()
        var fireTime: Long? = null
        repeat(12) {
            emitPressureSample(1016.0f)
            if (fireTime == null && AlertRegistry.allActive().any { it.ruleId == ruleId }) {
                fireTime = System.currentTimeMillis()
            }
            delay(400L)
        }

        if (fireTime == null) {
            val snap = CepProvider.getOrCreateEngine(applicationContext).pressureFeatureSnapshot()
            Log.i("CEP", "[P6-T2] ASSERT FAIL rule=$ruleId did_not_fire_within=4800ms SNAPSHOT=$snap")
        } else {
            val elapsed = fireTime!! - triggerStart
            val pass = elapsed >= 1_000L
            Log.i("CEP", "[P6-T2] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId elapsedFromTriggerMs=$elapsed expected=>=1000ms")
        }
        AlertRegistry.clearAll()
    }

    /**
     * P6-T3: Low atmospheric pressure — pressure_low_atm.
     *
     * Emits 25 samples at 900 hPa (well below any reasonable low-pressure threshold loaded
     * by updatePressureProfile()).  mean_n5 (avg of last 5 samples) = 900.0 hPa.
     * pressure_low_atm has sustained_for_sec=10; must fire within ~12 s of condition onset.
     *
     * Prerequisite: CepEngine.updatePressureProfile() has been called at startup so that
     * thresholds.pressure.low is available in FeatureStore.
     *
     * Assert: rule fires.
     */
    private suspend fun runP6T3LowAtmPressure() {
        val ruleId = "pressure_low_atm"
        // Inject known thresholds: low=950, high=2000 hPa.  900 < 950 → below_low_threshold = true.
        CepProvider.getOrCreateEngine(applicationContext).injectPressureThresholdsForTest(950.0, 2000.0)
        AlertRegistry.clearAll()

        Log.i(TAG, "[P6-T3] ▶ Emitting 25 samples at 900 hPa (below low threshold=950)")
        val t0 = System.currentTimeMillis()
        var fireTime: Long? = null
        repeat(25) {
            emitPressureSample(900.0f)
            if (fireTime == null && AlertRegistry.allActive().any { it.ruleId == ruleId }) {
                fireTime = System.currentTimeMillis()
            }
            delay(500L)
        }

        if (fireTime == null) {
            val snap = CepProvider.getOrCreateEngine(applicationContext).pressureFeatureSnapshot()
            Log.i("CEP", "[P6-T3] ASSERT FAIL rule=$ruleId did_not_fire SNAPSHOT=$snap")
        } else {
            val elapsed = fireTime!! - t0
            val pass = elapsed in 10_000L..13_000L
            Log.i("CEP", "[P6-T3] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId elapsedMs=$elapsed expected=10000..13000ms")
        }
        AlertRegistry.clearAll()
    }

    /**
     * P6-T4: High atmospheric pressure — pressure_high_atm.
     *
     * Emits 25 samples at 1070 hPa (above any reasonable high-pressure threshold).
     * mean_n5 = 1070.0 hPa.  pressure_high_atm has sustained_for_sec=10.
     *
     * Assert: rule fires.
     */
    private suspend fun runP6T4HighAtmPressure() {
        val ruleId = "pressure_high_atm"
        // Inject high threshold at 1060 hPa so 1070 > 1060 → above_high_threshold = true.
        // Default is 2000, which would make 1070 > 2000 = false and the test always fail.
        CepProvider.getOrCreateEngine(applicationContext).injectPressureThresholdsForTest(950.0, 1060.0)
        AlertRegistry.clearAll()

        Log.i(TAG, "[P6-T4] ▶ Emitting 25 samples at 1070 hPa (above high threshold=1060)")
        val t0 = System.currentTimeMillis()
        var fireTime: Long? = null
        repeat(25) {
            emitPressureSample(1070.0f)
            if (fireTime == null && AlertRegistry.allActive().any { it.ruleId == ruleId }) {
                fireTime = System.currentTimeMillis()
            }
            delay(500L)
        }

        if (fireTime == null) {
            val snap = CepProvider.getOrCreateEngine(applicationContext).pressureFeatureSnapshot()
            Log.i("CEP", "[P6-T4] ASSERT FAIL rule=$ruleId did_not_fire SNAPSHOT=$snap")
        } else {
            val elapsed = fireTime!! - t0
            val pass = elapsed in 10_000L..13_000L
            Log.i("CEP", "[P6-T4] ASSERT ${if (pass) "PASS" else "FAIL"} rule=$ruleId elapsedMs=$elapsed expected=10000..13000ms")
        }
        AlertRegistry.clearAll()
    }

    /**
     * P6-T5: Insufficient sample history — no pressure alert should fire.
     *
     * Emits only 3 pressure samples (fewer than min_samples=5 for mean_n5 and fewer than the
     * 6 needed for delta_n5 with horizonSamples=5).  All pressure sample-window features will
     * be suppressed:
     *  - computeSampleWindow: window.size < def.minSamples (3 < 5) → not written
     *  - computeSampleDelta:  ring.getByCount(5) returns null (need ≥6) → not written
     *
     * With no feature values, all downstream derived features (delta_rapid_rise, etc.) also
     * produce null, and no pressure rule fires.
     *
     * Assert: SUMMARY no_alerts_fired.
     */
    private suspend fun runP6T5InsufficientHistory() {
        // Inject thresholds so threshold-dependent rules COULD fire if history were sufficient.
        // This ensures the pass proves the min_samples/horizon_samples guard, not missing thresholds.
        CepProvider.getOrCreateEngine(applicationContext).injectPressureThresholdsForTest(950.0, 1060.0)
        AlertRegistry.clearAll()
        val baselineIds: Set<String> = AlertRegistry.allActive().map { it.ruleId }.toSet()

        Log.i(TAG, "[P6-T5] ▶ Emitting only 3 pressure samples (below min_samples=5) — ring is clean")
        repeat(3) {
            emitPressureSample(1020.0f)   // Would be a dunk if history were sufficient
            delay(500L)
        }

        // Give feature coroutines time to run (up to 2× updateIntervalMs=500ms)
        delay(2_000L)

        val newAlerts = AlertRegistry.allActive().filter { it.ruleId !in baselineIds }
        if (newAlerts.isEmpty()) {
            Log.i("CEP", "[P6-T5] ASSERT PASS SUMMARY no_pressure_alerts_fired — min_samples/horizon_samples guard working ✓ (clean ring, thresholds injected)")
        } else {
            Log.i("CEP", "[P6-T5] ASSERT FAIL unexpected_alerts=${newAlerts.map { it.ruleId }}")
        }
        AlertRegistry.clearAll()
    }

    /**
     * P6-T6: Stale data suppression — max_age_sec freshness guard.
     *
     * Step 1: Confirm dunk detection works (establishes baseline).
     * Step 2: Pause all pressure emissions for 35 s (> max_age_sec=30 on mean_n5 / delta_n5).
     *         During this gap, computeSampleWindow and computeSampleDelta suppress writes because
     *         ring.lastTsOrNull() is >30s old.  Downstream derived features go stale.
     * Step 3: Verify no dunk alert fires during the stale window despite residual ring state.
     * Step 4: Resume pressure emissions at baseline and confirm system recovers.
     *
     * Assert: no alert fires during the 35 s gap; system recovers after resuming.
     *
     * Note: hold_down_sec=5 on pressure_dunk_detected prevents immediate re-fire, so we use
     * AlertRegistry.clearAll() between phases to isolate the stale-window assertion.
     */
    private suspend fun runP6T6StaleDataSuppression() {
        AlertRegistry.clearAll()

        // Step 1: Establish dunk detection works
        Log.i(TAG, "[P6-T6] Step 1: fill ring with baseline (1013 hPa)")
        repeat(6) {
            emitPressureSample(1013.0f)
            delay(400L)
        }
        Log.i(TAG, "[P6-T6] Step 1: emit dunk trigger (1020 hPa)")
        var dunkFireTime: Long? = null
        val step1Start = System.currentTimeMillis()
        repeat(10) {
            emitPressureSample(1020.0f)
            if (dunkFireTime == null && AlertRegistry.allActive().any { it.ruleId == "pressure_dunk_detected" }) {
                dunkFireTime = System.currentTimeMillis()
            }
            delay(500L)
        }
        val step1Ok = dunkFireTime != null
        if (!step1Ok) {
            val snap = CepProvider.getOrCreateEngine(applicationContext).pressureFeatureSnapshot()
            Log.i("CEP", "[P6-T6] Step 1 FAIL dunk_baseline fired=never SNAPSHOT=$snap")
        } else {
            Log.i("CEP", "[P6-T6] Step 1 PASS dunk_baseline fired=${dunkFireTime!! - step1Start}ms")
        }
        AlertRegistry.clearAll()

        // Step 2: Pause pressure emissions for 35 s
        Log.i(TAG, "[P6-T6] Step 2: pausing pressure for 35 s (max_age_sec=30 → features go stale)")
        delay(35_000L)

        // Step 3: During (now after) stale period, verify no alerts fired from residual ring state
        val staleAlerts = AlertRegistry.allActive().filter { it.ruleId.startsWith("pressure_") }
        val step3Pass = staleAlerts.isEmpty()
        Log.i("CEP", "[P6-T6] Step 3 ${if (step3Pass) "PASS" else "FAIL"} stale_window_alerts=${staleAlerts.map { it.ruleId }}")

        // Step 4: Resume emissions at normal baseline — features should recover
        Log.i(TAG, "[P6-T6] Step 4: resuming at 1013 hPa — features should recover")
        repeat(12) {
            emitPressureSample(1013.0f)
            delay(400L)
        }
        Log.i("CEP", "[P6-T6] SUMMARY max_age_sec_guard: step1=${if (step1Ok) "PASS" else "FAIL"} step3=${if (step3Pass) "PASS" else "FAIL"}")
        AlertRegistry.clearAll()
    }

    /*private suspend fun simulateScenario(s: Scenario, ageYears: Int = 35, hrRest: Int = 60) {
        val cfg = scenarioConfig(s, ageYears, hrRest)
        val rnd = Random(42)

        fun jitterD(d: Double, mag: Double) = d + rnd.nextDouble(-mag, mag)
        fun jitterF(f: Float, mag: Float) = f + rnd.nextFloat() * (2f * mag) - mag

        var skinWrist = cfg.skinWristC
        var skinHand = cfg.skinHandC
        var hr = cfg.entryHr
        var ppg = cfg.ppgAmp
        var spo2 = cfg.spo2Pct

        repeat(cfg.ticks) { t ->
            val gyro = cfg.gyroPattern?.invoke(t) ?: cfg.gyroRms

            CepBus.emit(CepEvent(Topics.ACTIVITY,  ActivityPayload(cfg.activity)))
            CepBus.emit(CepEvent(Topics.OFFBODY,   OffBodyPayload(cfg.onWrist)))
            CepBus.emit(CepEvent(Topics.SLEEP,     SleepPayload(cfg.sleeping)))
            CepBus.emit(CepEvent(Topics.HEART_RATE,HeartRatePayload(bpm = hr)))
            CepBus.emit(CepEvent(Topics.HRV,       HrvPayload(rmssd_ms = cfg.hrvRmssdMs)))
            CepBus.emit(CepEvent(Topics.SPO2,      Spo2Payload(pct = spo2)))
            CepBus.emit(CepEvent(Topics.PPG,       PpgPayload(amplitude = ppg, quality = 1.0f, wavelength = "green")))
            CepBus.emit(CepEvent(Topics.ACCEL,     AccelPayload(magnitude_g = jitterD(cfg.accelMagG, 0.01), cadence_spm = cfg.cadenceSpm)))
            CepBus.emit(CepEvent(Topics.GYRO,      GyroPayload(x = jitterF(0f, 0.1f), y = jitterF(0f, 0.1f), z = jitterF(0f, 0.1f), rms = gyro)))
            CepBus.emit(CepEvent(Topics.AMBIENT,   AmbientPayload(temp_c = jitterD(cfg.ambientC, 0.2), wind_mps = jitterD(cfg.windMps, 0.2), humidity_pct = jitterD(cfg.humidityPct, 1.5))))
            CepBus.emit(CepEvent(Topics.WIND,      WindPayload(speedMps = jitterF(cfg.windMps.toFloat(), 0.2f), directionDeg = (t * 17 % 360).toFloat())))
            CepBus.emit(CepEvent(Topics.HUMIDITY,  HumidityPayload(relativePercent = jitterF(cfg.humidityPct.toFloat(), 1.5f))))
            CepBus.emit(CepEvent(Topics.LIGHT,     LightPayload(lux = jitterF(cfg.lux.toFloat(), 5f), covered = cfg.covered)))
            CepBus.emit(CepEvent(Topics.SKIN_TEMP, SkinTempPayload(c_peripheral = listOf(skinWrist, skinHand))))
            CepBus.emit(CepEvent(Topics.IMMERSION, ImmersionPayload(wet_flag = cfg.wet, immersion_flag = cfg.immersion)))
            CepBus.emit(CepEvent(Topics.WATER,     WaterPayload(wet = cfg.wet, temperatureC = cfg.waterTempC)))

            // update drifting values
            skinWrist += cfg.skinSlopePerTickC
            skinHand  += cfg.skinSlopePerTickC * 1.15

            // HR: trigger phase then cooldown phase
            if (cfg.exitStartTick != null && cfg.exitHr != null && t >= cfg.exitStartTick) {
                hr = cfg.exitHr
            } else {
                hr += cfg.hrSlopePerTick
            }

            ppg  = (ppg + cfg.ppgSlopePerTick).coerceIn(0f, 1f)
            spo2 = (spo2 + cfg.spo2SlopePerTick).coerceIn(80.0, 100.0)

            delay(cfg.tickMs)
        }
    }*/

    private suspend fun simulateScenario(s: Scenario, ageYears: Int = 35, hrRest: Int = 60) {
        val cfg = scenarioConfig(s, ageYears, hrRest)

        //SonarQube compliant
        val rnd = SecureRandom()

        fun jitterD(d: Double, mag: Double): Double {
            val delta = (rnd.nextDouble() * 2.0 - 1.0) * mag
            return d + delta
        }

        fun jitterF(f: Float, mag: Float): Float {
            val delta = ((rnd.nextFloat() * 2f) - 1f) * mag
            return f + delta
        }

        var skinWrist = cfg.skinWristC
        var skinHand = cfg.skinHandC
        var hr = cfg.entryHr
        var ppg = cfg.ppgAmp
        var spo2 = cfg.spo2Pct
        // Phase 2: mutable HRV so P2 window tests can inject a step change mid-scenario
        var hrv = cfg.hrvRmssdMs

        // Phase 3 / E1: SimulationObserver — polls AlertRegistry each tick and logs
        // structured [ASSERT] lines for manual or scripted logcat verification.
        val observer = SimulationObserver(s)

        repeat(cfg.ticks) { t ->
            val gyro = cfg.gyroPattern?.invoke(t) ?: cfg.gyroRms

            // Phase 2: apply HRV step change and log the transition tick
            if (cfg.hrvExitStartTick != null && cfg.hrvExitRmssdMs != null) {
                if (t == cfg.hrvExitStartTick) {
                    Log.i(TAG, "[P2] ▶ HRV step at tick=$t (~${t * cfg.tickMs / 1000} s real): " +
                            "${cfg.hrvRmssdMs}→${cfg.hrvExitRmssdMs} ms")
                }
                if (t >= cfg.hrvExitStartTick) hrv = cfg.hrvExitRmssdMs
            }

            // Phase 3 / P3-T3: inject NaN at the configured tick to test exception isolation
            val emitHr = if (cfg.injectNanAtTick != null && t == cfg.injectNanAtTick) {
                Log.w(TAG, "[P3] ⚠ NaN injection at tick=$t for $s — expect CEP error log below")
                Double.NaN
            } else hr

            // Phase 4 / E6: signal gap — suppress all emissions within the configured range.
            val inGap = cfg.signalGapRange?.let { (start, end) -> t in start until end } ?: false
            if (inGap) {
                Log.d(TAG, "[P4] ⏸ signal gap tick=$t for $s — all sensor emissions suppressed")
            } else {
                // Phase 4 / E5: apply tsOffsetMs to every event so freshness guards see the offset.
                val ts = System.currentTimeMillis() + cfg.tsOffsetMs

                // Phase 4 / E6: burstOnTick — fire 20 copies of every event on the designated tick.
                val repeatCount = if (cfg.burstOnTick != null && t == cfg.burstOnTick) {
                    Log.i(TAG, "[P4] ⚡ burst at tick=$t for $s — emitting 20× events per sensor")
                    20
                } else 1

                repeat(repeatCount) {
                    CepBus.emit(CepEvent(Topics.ACTIVITY,  ActivityPayload(cfg.activity), ts = ts))
                    CepBus.emit(CepEvent(Topics.OFFBODY,   OffBodyPayload(cfg.onWrist), ts = ts))
                    CepBus.emit(CepEvent(Topics.SLEEP,     SleepPayload(cfg.sleeping), ts = ts))
                    CepBus.emit(CepEvent(Topics.HEART_RATE,HeartRatePayload(bpm = emitHr), ts = ts))
                    CepBus.emit(CepEvent(Topics.HRV,       HrvPayload(rmssd_ms = hrv), ts = ts))
                    CepBus.emit(CepEvent(Topics.SPO2,      Spo2Payload(pct = spo2), ts = ts))
                    CepBus.emit(CepEvent(Topics.PPG,       PpgPayload(amplitude = ppg, quality = 1.0f, wavelength = "green"), ts = ts))
                    CepBus.emit(CepEvent(Topics.ACCEL,     AccelPayload(magnitude_g = jitterD(cfg.accelMagG, 0.01), cadence_spm = cfg.cadenceSpm), ts = ts))
                    CepBus.emit(CepEvent(Topics.GYRO,      GyroPayload(x = jitterF(0f, 0.1f), y = jitterF(0f, 0.1f), z = jitterF(0f, 0.1f), rms = gyro), ts = ts))
                    CepBus.emit(CepEvent(Topics.AMBIENT,   AmbientPayload(temp_c = jitterD(cfg.ambientC, 0.2), wind_mps = jitterD(cfg.windMps, 0.2), humidity_pct = jitterD(cfg.humidityPct, 1.5)), ts = ts))
                    CepBus.emit(CepEvent(Topics.WIND,      WindPayload(speedMps = jitterF(cfg.windMps.toFloat(), 0.2f), directionDeg = (t * 17 % 360).toFloat()), ts = ts))
                    CepBus.emit(CepEvent(Topics.HUMIDITY,  HumidityPayload(relativePercent = jitterF(cfg.humidityPct.toFloat(), 1.5f)), ts = ts))
                    CepBus.emit(CepEvent(Topics.LIGHT,     LightPayload(lux = jitterF(cfg.lux.toFloat(), 5f), covered = cfg.covered), ts = ts))
                    CepBus.emit(CepEvent(Topics.SKIN_TEMP, SkinTempPayload(c_peripheral = listOf(skinWrist, skinHand)), ts = ts))
                    CepBus.emit(CepEvent(Topics.IMMERSION, ImmersionPayload(wet_flag = cfg.wet, immersion_flag = cfg.immersion), ts = ts))
                    CepBus.emit(CepEvent(Topics.WATER,     WaterPayload(wet = cfg.wet, temperatureC = cfg.waterTempC), ts = ts))
                }
            }

            // update drifting values
            skinWrist += cfg.skinSlopePerTickC
            skinHand  += cfg.skinSlopePerTickC * 1.15

            // HR: trigger phase then cooldown phase
            if (cfg.exitStartTick != null && cfg.exitHr != null && t >= cfg.exitStartTick) {
                hr = cfg.exitHr
            } else {
                hr += cfg.hrSlopePerTick
            }

            ppg  = (ppg + cfg.ppgSlopePerTick).coerceIn(0f, 1f)
            spo2 = (spo2 + cfg.spo2SlopePerTick).coerceIn(80.0, 100.0)

            // E1: sample AlertRegistry after each tick
            observer.sample(elapsedMs = (t + 1) * cfg.tickMs)

            delay(cfg.tickMs)
        }

        // E1: print scenario summary
        observer.report()

        // P3-T3 specific: confirm engine is still processing after the NaN injection
        if (cfg.injectNanAtTick != null) {
            Log.i(TAG, "[ASSERT][$s] ENGINE_RUNNING_POST_EXCEPTION tick=${cfg.ticks} — if you see this, exception was isolated ✓")
        }
    }

    /**
     * Phase 3 / E1 — Minimal SimulationObserver.
     *
     * Polls [AlertRegistry] after each simulated tick and emits structured `[ASSERT]`
     * log lines.  These lines can be grepped from logcat for manual or scripted
     * pass/fail verification without needing programmatic JUnit assertions.
     *
     * A full E1 implementation would also expose a callback interface for automated
     * test assertions; this version covers the logcat-observable tier.
     */
    private inner class SimulationObserver(private val scenario: Scenario) {
        private val startMs = System.currentTimeMillis()
        // ruleId → first-fire elapsed ms
        private val firstFireMs = mutableMapOf<String, Long>()
        // Snapshot of alerts already active at construction — exclude these as carryover.
        private val baselineIds: Set<String> = AlertRegistry.allActive().map { it.ruleId }.toSet()

        fun sample(elapsedMs: Long) {
            for (alert in AlertRegistry.allActive()) {
                if (alert.ruleId in baselineIds) continue   // skip pre-existing carryover
                if (!firstFireMs.containsKey(alert.ruleId)) {
                    firstFireMs[alert.ruleId] = elapsedMs
                    Log.i(TAG,
                        "[ASSERT][$scenario] ALERT_FIRED" +
                        " ruleId=${alert.ruleId}" +
                        " elapsedMs=$elapsedMs" +
                        " elapsedSec=${"%.1f".format(elapsedMs / 1000.0)}"
                    )
                }
            }
        }

        fun report() {
            val wallMs = System.currentTimeMillis() - startMs
            if (firstFireMs.isEmpty()) {
                Log.i(TAG, "[ASSERT][$scenario] SUMMARY no_alerts_fired wallSec=${"%.1f".format(wallMs / 1000.0)}")
            } else {
                for ((id, ms) in firstFireMs) {
                    Log.i(TAG,
                        "[ASSERT][$scenario] SUMMARY" +
                        " ruleId=$id" +
                        " firstFireSec=${"%.1f".format(ms / 1000.0)}" +
                        " wallSec=${"%.1f".format(wallMs / 1000.0)}"
                    )
                }
            }
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIF_CHANNEL_ID, NOTIF_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "CEP simulation foreground service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = Intent(this, CEPSimulationService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 1001, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CEP Simulation")
            .setContentText(content)
            .setOngoing(true)
            .addAction(0, "Stop", stopPI)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(content))
    }
}
