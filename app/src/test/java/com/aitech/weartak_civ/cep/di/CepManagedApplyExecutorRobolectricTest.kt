package com.aitech.weartak_civ.cep.di

import android.app.Application
import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.config.CanonicalJson
import com.aitech.weartak_civ.cep.engine.config.CepConfigRegistry
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.engine.config.SourceLayer
import com.aitech.weartak_civ.cep.engine.lifecycle.CepEngineHolder
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CepManagedApplyExecutorRobolectricTest {

    private class RecordingPersistence : CepApplyPersistence {
        val rawWrites = mutableListOf<String>()
        var appliedWrites = 0
        var lastAppliedHash: String? = null
        var mirrorEpoch: Long = 0L
        var sustainClearCount = 0

        var failureState: DataRepository.CepManagedPayloadFailureState? = null
        var failureStateClearCount = 0
        var failureStateStoreCount = 0

        override suspend fun storeCepConfigurationRawJson(raw: String) {
            rawWrites.add(raw)
        }

        override suspend fun getCepSustainOverridesOnce(): DataRepository.CepSustainOverrides =
            DataRepository.CepSustainOverrides(mapOf("rule_x" to 42L), emptyMap())

        override suspend fun clearCepSustainOverridesStorage() {
            sustainClearCount++
        }

        override suspend fun storeCepSustainOverridesDual(
            main: Map<String, Long>,
            warn: Map<String, Long>
        ) = Unit

        override suspend fun storeCepConfigurationApplied(hash: String, atMs: Long) {
            appliedWrites++
            lastAppliedHash = hash
        }

        override suspend fun shouldConsumeCepResetEpoch(epoch: Long): Boolean = epoch > mirrorEpoch

        override suspend fun advanceCepResetEpochMirror(epoch: Long): Boolean {
            if (epoch > mirrorEpoch) {
                mirrorEpoch = epoch
                return true
            }
            return false
        }

        override suspend fun getManagedPayloadFailureStateOnce(): DataRepository.CepManagedPayloadFailureState? =
            failureState

        override suspend fun storeManagedPayloadFailureState(state: DataRepository.CepManagedPayloadFailureState) {
            failureStateStoreCount++
            failureState = state
        }

        override suspend fun clearManagedPayloadFailureState() {
            failureStateClearCount++
            failureState = null
        }
    }

//    private class CapturingDiag : CepApplyDiagnostics {
//        val rejections = mutableListOf<String>()
//        val infos = mutableListOf<String>()
//
//        override fun recordRejection(
//            source: SourceLayer,
//            reasonCode: String,
//            schemaVersion: Int?,
//            payloadHash: String?,
//            effectiveBefore: String?,
//            effectiveAfter: String?,
//            fallbackDecision: String?,
//            tenant: String?, issuer: String?,
//            message: String?,
//            pluginId: String?,
//            pluginVersion: String?
//        ) {
//            rejections.add(reasonCode)
//        }
//
//        override fun recordInfo(
//            source: SourceLayer,
//            reasonCode: String,
//            schemaVersion: Int?,
//            payloadHash: String?,
//            effectiveBefore: String?,
//            effectiveAfter: String?,
//            tenant: String?, issuer: String?,
//            pluginId: String?,
//            pluginVersion: String?
//        ) {
//            infos.add(reasonCode)
//        }
//    }
private class CapturingDiag : CepApplyDiagnostics {
    val rejections = mutableListOf<String>()
    val infos = mutableListOf<String>()

    override fun recordRejection(context: CepDiagnosticContext) {
        rejections.add(context.reasonCode)
    }

    override fun recordInfo(context: CepDiagnosticContext) {
        infos.add(context.reasonCode)
    }
}

//    private object NoopDiag : CepApplyDiagnostics {
//        override fun recordRejection(
//            source: SourceLayer,
//            reasonCode: String,
//            schemaVersion: Int?,
//            payloadHash: String?,
//            effectiveBefore: String?,
//            effectiveAfter: String?,
//            fallbackDecision: String?,
//            tenant: String?, issuer: String?,
//            message: String?,
//            pluginId: String?,
//            pluginVersion: String?
//        ) = Unit
//
//        override fun recordInfo(
//            source: SourceLayer,
//            reasonCode: String,
//            schemaVersion: Int?,
//            payloadHash: String?,
//            effectiveBefore: String?,
//            effectiveAfter: String?,
//            tenant: String?, issuer: String?,
//            pluginId: String?,
//            pluginVersion: String?
//        ) = Unit
//    }
private object NoopDiag : CepApplyDiagnostics {
    override fun recordRejection(context: CepDiagnosticContext) = Unit
    override fun recordInfo(context: CepDiagnosticContext) = Unit
}

    private fun overlayJson(cepResetEpoch: Long): String = """
        {"schemaVersion":1,"cepResetEpoch":$cepResetEpoch,"rulesMode":"MERGE","featuresMode":"MERGE",
        "clearCepRules":false,"clearCepFeatures":false,"metadata":{},
        "disabledRuleIds":[],"disabledFeatureIds":[],
        "features":[],"rules":[]}
    """.trimIndent()

    private fun mockRepo(): DataRepository {
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.getYearOfBirth } returns flowOf(1990)
        every { repo.getRestingHeartRate } returns flowOf(60)
        every { repo.getRestingHrHighAlertBpmThreshold } returns flowOf(120)
        every { repo.getRestingHrLowAlertBpmThreshold } returns flowOf(50)
        every { repo.getExertionThresholdWarning } returns flowOf(50)
        every { repo.getExertionThresholdAlert } returns flowOf(80)
        every { repo.getHighAtmPressureAlertThreshold } returns flowOf(950)
        every { repo.getLowAtmPressureAlertThreshold } returns flowOf(100)
        every { repo.effectiveEnableImmersionAlerts } returns flowOf(true)
        every { repo.effectiveEnableLowAtmPressureAlert } returns flowOf(true)
        every { repo.effectiveEnableHighAtmPressureAlert } returns flowOf(true)
        coEvery { repo.getCepSustainOverridesOnce() } returns DataRepository.CepSustainOverrides(
            mapOf("rule_x" to 42L),
            emptyMap()
        )
        return repo
    }

    private fun newExecutor(
        app: Application,
        persistence: CepApplyPersistence,
        holder: CepEngineHolder,
        diag: CepApplyDiagnostics = NoopDiag,
        onBeforeHygiene: suspend (previous: CepEngine?, newEngine: CepEngine) -> Unit = { _, _ -> },
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): CepManagedApplyExecutor {
        val builtIn = CepConfig(1, features = emptyList(), rules = emptyList(), ruleEvalIntervalMs = 1000)
        val cadence = RuleCompiler.EngineCadence(1, 1000)
        val registry = CepConfigRegistry(builtIn = builtIn, defaultCadence = cadence)
        val repo = mockRepo()
        return CepManagedApplyExecutor(
            registry = registry,
            persistence = persistence,
            holder = holder,
            appCtx = app,
            buildEngine = { ctx, cfg ->
                CepEngine(
                    appContext = ctx.applicationContext,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    config = cfg,
                    dataRepository = repo
                )
            },
            diagnostics = diag,
            clearAlertsForRemovedRules = { _, _ -> },
            onBeforeHygiene = onBeforeHygiene,
            nowMs = nowMs
        )
    }

    /** Schema-mismatch payload — parses past JSON validity, fails the schemaVersion gate. */
    private fun poisonedPayload(marker: Int): String = """
        {"schemaVersion":2,"cepResetEpoch":0,"marker":$marker}
    """.trimIndent()

    @Test
    fun weatherDemandFollowsAppliedConfigurationButNotRejectedCandidate() = runBlocking {
        val holder = CepEngineHolder()
        val ex = newExecutor(RuntimeEnvironment.getApplication(), RecordingPersistence(), holder)
        val empty = overlayJson(0L)
        val withWeather = org.json.JSONObject(empty)
            .put("featuresMode", "REPLACE")
            .put("features", org.json.JSONArray("""[{
                "type":"derived","id":"features.wind_chill.c",
                "expr":{"wind_chill":{"temp_key":"ambient.temp_c","wind_key":"ambient.wind_mps"}}
            }]""")).toString()
        try {
            val initial = ex.execute(empty, "", { false }) as CepProvider.ApplyResult.Applied
            assertFalse(holder.weatherDemand.value)
            val weather = ex.execute(withWeather, initial.effectiveHash, { false }) as CepProvider.ApplyResult.Applied
            assertTrue(holder.weatherDemand.value)
            val current = holder.current()
            val rejected = ex.execute(poisonedPayload(1), weather.effectiveHash, { false })
            assertTrue(rejected is CepProvider.ApplyResult.Rejected)
            assertEquals(current, holder.current())
            assertTrue(holder.weatherDemand.value)
            assertTrue(ex.execute(empty, weather.effectiveHash, { false }) is CepProvider.ApplyResult.Applied)
            assertFalse(holder.weatherDemand.value)
        } finally { holder.current()?.cancel() }
    }

    @Test
    fun resetWithUnchangedDefinitionalHashStillSwapsAdvancesMirrorAndClearsSustain() {
        runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val persistence = RecordingPersistence()
        val holder = CepEngineHolder()
        val ex = newExecutor(app, persistence, holder)

        val r1 = ex.execute(rawPayload = overlayJson(0L), effectiveBefore = "", isSuperseded = { false })
        assertTrue(r1 is CepProvider.ApplyResult.Applied)
        val h1 = (r1 as CepProvider.ApplyResult.Applied).effectiveHash

        val eng1 = holder.current()!!
        eng1.ingestForWindowTesting(
            CepEvent(Topics.HEART_RATE, HeartRatePayload(77.0), ts = 12_000L)
        )
        assertEquals(77.0, eng1.peekNumericRingLast(Topics.HEART_RATE)!!, 0.01)

        val r2 = ex.execute(rawPayload = overlayJson(1L), effectiveBefore = h1, isSuperseded = { false })
        assertTrue(r2 is CepProvider.ApplyResult.Applied)
        assertEquals(h1, (r2 as CepProvider.ApplyResult.Applied).effectiveHash)
        assertNotSame(eng1, holder.current())
        assertEquals(77.0, holder.current()!!.peekNumericRingLast(Topics.HEART_RATE)!!, 0.01)
        assertEquals(1L, persistence.mirrorEpoch)
        assertTrue(persistence.sustainClearCount >= 1)
        assertTrue(persistence.appliedWrites >= 2)

        holder.current()?.cancel()
        }
    }

    @Test
    fun definitionalNoOpWithoutResetSkipsSwapAndDoesNotAdvanceMirror() {
        runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val persistence = RecordingPersistence()
        val holder = CepEngineHolder()
        val ex = newExecutor(app, persistence, holder)

        val r1 = ex.execute(rawPayload = overlayJson(0L), effectiveBefore = "", isSuperseded = { false })
        val h1 = (r1 as CepProvider.ApplyResult.Applied).effectiveHash
        val eng1 = holder.current()!!

        val r2 = ex.execute(rawPayload = overlayJson(0L), effectiveBefore = h1, isSuperseded = { false })
        assertTrue(r2 is CepProvider.ApplyResult.NoOp)
        assertEquals(eng1, holder.current())
        assertEquals(0L, persistence.mirrorEpoch)

        holder.current()?.cancel()
        }
    }

    @Test
    fun parseRejectionLeavesMirrorAndAppliedWritesUnchanged() {
        runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val persistence = RecordingPersistence()
        val holder = CepEngineHolder()
        val ex = newExecutor(app, persistence, holder)

        val r = ex.execute(rawPayload = """{"schemaVersion":2,"cepResetEpoch":5}""", effectiveBefore = "", isSuperseded = { false })
        assertTrue(r is CepProvider.ApplyResult.Rejected)
        assertEquals(0L, persistence.mirrorEpoch)
        assertEquals(0, persistence.appliedWrites)
        assertTrue(holder.current() == null)
        }
    }

    @Test
    fun composeRejectionWithPendingResetDoesNotAdvanceMirrorOrSwap() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val ex = newExecutor(app, persistence, holder)
            val bad = """
                {"schemaVersion":1,"cepResetEpoch":1,"rulesMode":"MERGE","featuresMode":"MERGE",
                "clearCepRules":false,"clearCepFeatures":false,"metadata":{},
                "engine":{"window_tick_ms":1,"rule_eval_interval_ms":1},
                "disabledRuleIds":[],"disabledFeatureIds":[],
                "features":[],"rules":[]}
            """.trimIndent()
            val r = ex.execute(rawPayload = bad, effectiveBefore = "", isSuperseded = { false })
            assertTrue(r is CepProvider.ApplyResult.Rejected)
            assertEquals(0L, persistence.mirrorEpoch)
            assertEquals(0, persistence.appliedWrites)
            assertEquals(0, persistence.sustainClearCount)
            assertTrue(holder.current() == null)
        }
    }

    @Test
    fun previousEngineIsQuiescedBeforeHygieneRuns() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val ex = newExecutor(
                app = app,
                persistence = persistence,
                holder = holder,
                onBeforeHygiene = { previous, newEngine ->
                    if (previous == null) {
                        assertFalse(newEngine.isAuthoritativeForTest())
                        assertTrue(holder.current() === newEngine)
                    } else {
                        assertFalse(previous.isAuthoritativeForTest())
                        assertFalse(previous.isScopeActiveForTest())
                        assertFalse(newEngine.isAuthoritativeForTest())
                        assertTrue(holder.current() === newEngine)
                    }
                }
            )

            val r1 = ex.execute(rawPayload = overlayJson(0L), effectiveBefore = "", isSuperseded = { false })
            assertTrue(r1 is CepProvider.ApplyResult.Applied)

            val r2 = ex.execute(rawPayload = overlayJson(1L), effectiveBefore = (r1 as CepProvider.ApplyResult.Applied).effectiveHash, isSuperseded = { false })
            assertTrue(r2 is CepProvider.ApplyResult.Applied)

            val current = holder.current()!!
            assertTrue(current.isAuthoritativeForTest())
            assertTrue(current.isScopeActiveForTest())

            current.cancel()
        }
    }

    // ---- WEAR-509 poison-pill hold-off (design §847–857) ---------------------------------

    @Test
    fun firstAndSecondRejectionsDoNotHoldOffAndIncrementStreak() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val diag = CapturingDiag()
            var clock = 1_000L
            val ex = newExecutor(app, persistence, holder, diag) { clock }

            val payload = poisonedPayload(1)
            val expectedHash = CanonicalJson.hashRaw(payload)

            val r1 = ex.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            assertTrue(r1 is CepProvider.ApplyResult.Rejected)
            assertNotEquals(
                ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                (r1 as CepProvider.ApplyResult.Rejected).reasonCode
            )
            assertEquals(expectedHash, persistence.failureState?.payloadHash)
            assertEquals(1, persistence.failureState?.failureCount)
            assertEquals(1_000L, persistence.failureState?.firstFailureAtMs)

            clock = 2_000L
            val r2 = ex.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            assertTrue(r2 is CepProvider.ApplyResult.Rejected)
            assertNotEquals(
                ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                (r2 as CepProvider.ApplyResult.Rejected).reasonCode
            )
            assertEquals(2, persistence.failureState?.failureCount)
            // firstFailureAtMs stays anchored at the first failure timestamp.
            assertEquals(1_000L, persistence.failureState?.firstFailureAtMs)
            assertTrue(diag.rejections.none { it == ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF })
        }
    }

    @Test
    fun thirdRejectionWithinWindowShortCircuitsWithHoldoff() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val diag = CapturingDiag()
            var clock = 1_000L
            val ex = newExecutor(app, persistence, holder, diag) { clock }

            val payload = poisonedPayload(1)
            ex.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            clock = 2_000L
            ex.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })

            // Third attempt within the 6-hour window: short-circuit with holdoff reason.
            clock = 3_000L
            val schemaRejectionsBefore = diag.rejections.count { it == "schema_mismatch" }
            val r3 = ex.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            assertTrue(r3 is CepProvider.ApplyResult.Rejected)
            assertEquals(
                ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                (r3 as CepProvider.ApplyResult.Rejected).reasonCode
            )
            assertTrue(diag.rejections.contains(ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF))
            // Parse path should NOT have re-run on the 3rd attempt — no new schema_mismatch.
            assertEquals(schemaRejectionsBefore, diag.rejections.count { it == "schema_mismatch" })
            assertEquals(3, persistence.failureState?.failureCount)

            // A fourth attempt is still held off and does NOT increment past the threshold.
            clock = 4_000L
            val r4 = ex.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            assertEquals(
                ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                (r4 as CepProvider.ApplyResult.Rejected).reasonCode
            )
            assertEquals(3, persistence.failureState?.failureCount)
        }
    }

    @Test
    fun differentPayloadHashResetsTheFailureStreak() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val diag = CapturingDiag()
            var clock = 1_000L
            val ex = newExecutor(app, persistence, holder, diag) { clock }

            val poisonedA = poisonedPayload(1)
            val poisonedB = poisonedPayload(2)
            val hashA = CanonicalJson.hashRaw(poisonedA)
            val hashB = CanonicalJson.hashRaw(poisonedB)
            assertNotEquals(hashA, hashB)

            ex.execute(rawPayload = poisonedA, effectiveBefore = "", isSuperseded = { false })
            clock = 2_000L
            ex.execute(rawPayload = poisonedA, effectiveBefore = "", isSuperseded = { false })
            assertEquals(2, persistence.failureState?.failureCount)
            assertEquals(hashA, persistence.failureState?.payloadHash)

            // A different hash resets the streak: count goes back to 1 keyed by the new hash.
            clock = 3_000L
            val rB = ex.execute(rawPayload = poisonedB, effectiveBefore = "", isSuperseded = { false })
            assertTrue(rB is CepProvider.ApplyResult.Rejected)
            assertNotEquals(
                ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                (rB as CepProvider.ApplyResult.Rejected).reasonCode
            )
            assertEquals(hashB, persistence.failureState?.payloadHash)
            assertEquals(1, persistence.failureState?.failureCount)
            assertEquals(3_000L, persistence.failureState?.firstFailureAtMs)
        }
    }

    @Test
    fun successfulApplyClearsTheFailureStreak() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val ex = newExecutor(app, persistence, holder)

            // Prime a streak so we can observe it being cleared.
            persistence.failureState = DataRepository.CepManagedPayloadFailureState(
                payloadHash = "deadbeef",
                failureCount = 2,
                firstFailureAtMs = 100L
            )

            val good = overlayJson(0L)
            val r = ex.execute(rawPayload = good, effectiveBefore = "", isSuperseded = { false })
            assertTrue(r is CepProvider.ApplyResult.Applied)
            assertNull(persistence.failureState)
            assertTrue(persistence.failureStateClearCount >= 1)

            holder.current()?.cancel()
        }
    }

    @Test
    fun noOpApplyAlsoClearsTheFailureStreak() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val ex = newExecutor(app, persistence, holder)

            // Establish the baseline applied hash.
            val good = overlayJson(0L)
            val r1 = ex.execute(rawPayload = good, effectiveBefore = "", isSuperseded = { false })
            val baseHash = (r1 as CepProvider.ApplyResult.Applied).effectiveHash

            // Prime a stale streak that should be cleared by the NoOp.
            persistence.failureState = DataRepository.CepManagedPayloadFailureState(
                payloadHash = "stale",
                failureCount = 2,
                firstFailureAtMs = 100L
            )
            val r2 = ex.execute(rawPayload = good, effectiveBefore = baseHash, isSuperseded = { false })
            assertTrue(r2 is CepProvider.ApplyResult.NoOp)
            assertNull(persistence.failureState)

            holder.current()?.cancel()
        }
    }

    @Test
    fun holdoffStateSurvivesRestartByReReadingPersistence() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder1 = CepEngineHolder()
            val diag1 = CapturingDiag()
            var clock = 1_000L
            val ex1 = newExecutor(app, persistence, holder1, diag1) { clock }

            val payload = poisonedPayload(7)
            ex1.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            clock = 2_000L
            ex1.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            assertEquals(2, persistence.failureState?.failureCount)

            // Simulate process restart: same persistence (durable on disk), brand-new executor,
            // engine holder, and diag sink. The third attempt must still short-circuit.
            val holder2 = CepEngineHolder()
            val diag2 = CapturingDiag()
            clock = 3_000L
            val ex2 = newExecutor(app, persistence, holder2, diag2) { clock }

            val r = ex2.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            assertTrue(r is CepProvider.ApplyResult.Rejected)
            assertEquals(
                ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                (r as CepProvider.ApplyResult.Rejected).reasonCode
            )
            assertTrue(diag2.rejections.contains(ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF))
            assertNotNull(persistence.failureState)
            assertEquals(3, persistence.failureState?.failureCount)
        }
    }

    @Test
    fun expiredHoldoffWindowAllowsRetryAndResetsStreak() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val diag = CapturingDiag()
            var clock = 1_000L
            val ex = newExecutor(app, persistence, holder, diag) { clock }

            val payload = poisonedPayload(9)
            val hash = CanonicalJson.hashRaw(payload)
            // Pre-existing streak at threshold but firstFailureAtMs > 6h ago.
            persistence.failureState = DataRepository.CepManagedPayloadFailureState(
                payloadHash = hash,
                failureCount = CepManagedApplyExecutor.HOLDOFF_THRESHOLD,
                firstFailureAtMs = 1_000L
            )
            clock = 1_000L + CepManagedApplyExecutor.HOLDOFF_WINDOW_MS + 1L

            val r = ex.execute(rawPayload = payload, effectiveBefore = "", isSuperseded = { false })
            assertTrue(r is CepProvider.ApplyResult.Rejected)
            // Hold-off must NOT fire after window expiry; we re-run validation and get the real reason.
            assertNotEquals(
                ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                (r as CepProvider.ApplyResult.Rejected).reasonCode
            )
            // Streak resets to 1 anchored at the new firstFailureAtMs.
            assertEquals(1, persistence.failureState?.failureCount)
            assertEquals(clock, persistence.failureState?.firstFailureAtMs)
        }
    }
}
