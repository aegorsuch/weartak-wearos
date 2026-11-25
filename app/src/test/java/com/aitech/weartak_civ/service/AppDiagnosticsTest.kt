package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private var recorder: AppDiagnostics? = null

    @Before fun reset() {
        app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).edit().clear().commit()
        val root = app.getExternalFilesDir("diagnostics")!!
        listOf("events.jsonl", "events.1.jsonl", "events.2.jsonl").forEach { root.resolve(it).delete() }
    }

    @After fun cleanup() {
        runBlocking {
            recorder?.setEnabled(false)
            recorder?.close()
        }
    }

    @Test fun defaultOffDoesNotSubscribeOrWrite() {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        assertFalse(diagnostics.enabled.value)
        assertFalse(EventBus.getDefault().isRegistered(diagnostics))
        diagnostics.onAppEvent(DittoDocumentEvent("chat", "secret payload"))
        assertFalse(app.getExternalFilesDir("diagnostics")!!.resolve("events.jsonl").exists())
    }

    @Test fun enabledPreferenceAndLogsSurviveRecorderRecreation() = runBlocking {
        val first = AppDiagnostics(app).also { recorder = it }
        first.setEnabled(true)
        first.onAppEvent(DittoDocumentEvent("chat", "not retained"))
        assertTrue(first.exportJson().contains("SYNC"))
        first.close()
        val restored = AppDiagnostics(app).also { recorder = it }
        assertTrue(restored.enabled.value)
        val events = JsonParser.parseString(restored.exportJson()).asJsonObject["events"].asJsonArray
        assertTrue(events.any { it.asJsonObject["event"].asString == "SYNC" })
        assertTrue(events.any { it.asJsonObject["event"].asString == "PROCESS_START" })
        assertTrue(app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).getBoolean("enabled", false))
    }

    @Test fun samsungContinuousListenerStateIsCapturedAtOptInWithoutHealthPayload() = runBlocking {
        AppDiagnostics.updateSamsungHrContinuousRunning(true)
        try {
            val diagnostics = AppDiagnostics(app).also { recorder = it }
            diagnostics.setEnabled(true)
            val events = JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
                .filter { it.asJsonObject["event"].asString == "SAMSUNG_HR_CONTINUOUS" }
            assertEquals(1, events.size)
            assertEquals(1, events.single().asJsonObject["metrics"].asJsonObject["ACTIVE"].asInt)
            assertEquals(if (com.aitech.weartak_civ.BuildConfig.SAMSUNG_HRV_ENABLED) 1 else 0,
                events.single().asJsonObject["metrics"].asJsonObject["ENABLED"].asInt)
        } finally {
            AppDiagnostics.updateSamsungHrContinuousRunning(false)
        }
    }

    @Test fun payloadsAreNeverReadAndRepeatedEventsAreAggregated() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        repeat(100) {
            diagnostics.onAppEvent(DittoDocumentEvent(
                "sensitive-server-address", "<point lat=\"41.123\"/><remarks>token-secret callsign contact</remarks>"))
        }
        val exported = diagnostics.exportJson()
        listOf("41.123", "token-secret", "callsign", "contact", "sensitive-server-address", "remarks").forEach {
            assertFalse(it, exported.contains(it))
        }
        val events = JsonParser.parseString(exported).asJsonObject["events"].asJsonArray
            .filter { it.asJsonObject["event"].asString == "SYNC" }
        assertEquals(2, events.size)
        assertEquals(100, events.sumOf { it.asJsonObject["metrics"].asJsonObject["COUNT"].asInt })
        assertTrue(events.all { it.asJsonObject["elapsedMs"].asLong >= 0 })
    }

    @Test fun policyStateTransitionsAreNotMergedTogetherAndOldPolicyRecordsKeepTheirProvenance() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        val capture = AppDiagnostics::class.java.getDeclaredMethod(
            "capture", DiagnosticEvent::class.java, Map::class.java
        ).apply { isAccessible = true }
        listOf(
            mapOf(DiagnosticMetric.ACTIVITY to 3L, DiagnosticMetric.REPORT_REASON to 4L,
                DiagnosticMetric.OBSERVATION_REASON to 5L),
            mapOf(DiagnosticMetric.ACTIVITY to 3L, DiagnosticMetric.REPORT_REASON to 5L,
                DiagnosticMetric.OBSERVATION_REASON to 5L),
            mapOf(DiagnosticMetric.ACTIVITY to 7L, DiagnosticMetric.REPORT_REASON to 6L,
                DiagnosticMetric.OBSERVATION_REASON to 6L),
        ).forEach { capture.invoke(diagnostics, DiagnosticEvent.REPORT_POLICY, it) }
        val events = JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
            .filter { it.asJsonObject["event"].asString == "REPORT_POLICY" }
        assertEquals(3, events.size)
        assertTrue(events.all { it.asJsonObject["policy"].asString == "hybrid-motion-confirm-v3" })
        val root = temporary.newFolder()
        val journal = DiagnosticJournal({ root })
        journal.append(sample(1).copy(policy = "dynamic-speed-observation-v1",
            version = "old-build", sourceCommit = "old-source"))
        journal.append(sample(2))
        val preserved = JsonParser.parseString(journal.exportJson()).asJsonObject["events"].asJsonArray
        assertEquals("dynamic-speed-observation-v1", preserved[0].asJsonObject["policy"].asString)
        assertEquals("old-build", preserved[0].asJsonObject["version"].asString)
        assertEquals("old-source", preserved[0].asJsonObject["sourceCommit"].asString)
        assertEquals("hybrid-motion-confirm-v3", preserved[1].asJsonObject["policy"].asString)
    }

    @Test fun connectivityTransitionsKeepEveryDistinctStateWithoutFiveSecondCoalescing() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        val capture = AppDiagnostics::class.java.getDeclaredMethod(
            "capture", DiagnosticEvent::class.java, Map::class.java
        ).apply { isAccessible = true }
        listOf(0L, 1L, 0L).forEach { connected ->
            capture.invoke(diagnostics, DiagnosticEvent.CONNECTIVITY_PATH, mapOf(
                DiagnosticMetric.KIND to 0L,
                DiagnosticMetric.ENABLED to 1L,
                DiagnosticMetric.CONFIGURED_COUNT to 5L,
                DiagnosticMetric.CONNECTED_COUNT to connected
            ))
        }
        val events = JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
            .filter { it.asJsonObject["event"].asString == "CONNECTIVITY_PATH" }
        assertEquals(listOf(0, 1, 0),
            events.map { it.asJsonObject["metrics"].asJsonObject["CONNECTED_COUNT"].asInt })
    }

    @Test fun powerAndDockThrottleTransitionsKeepDistinctAppliedPolicyRecords() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        val capture = AppDiagnostics::class.java.getDeclaredMethod(
            "capture", DiagnosticEvent::class.java, Map::class.java
        ).apply { isAccessible = true }
        listOf(0L to 0L, 1L to 0L, 1L to 1L).forEach { (power, dock) ->
            capture.invoke(diagnostics, DiagnosticEvent.POWER_CHANGED, mapOf(
                DiagnosticMetric.POWER_THROTTLED to power,
                DiagnosticMetric.DOCK_THROTTLED to dock,
                DiagnosticMetric.REPORT_SECONDS to 60L
            ))
        }
        val events = JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
            .filter { it.asJsonObject["event"].asString == "POWER_CHANGED" }
        assertEquals(3, events.size)
        assertEquals(listOf(0, 1, 1),
            events.map { it.asJsonObject["metrics"].asJsonObject["POWER_THROTTLED"].asInt })
        assertEquals(listOf(0, 0, 1),
            events.map { it.asJsonObject["metrics"].asJsonObject["DOCK_THROTTLED"].asInt })
    }

    @Test fun displayAndRotationMetadataRemainDistinctNumericAndRateBounded() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        val capture = AppDiagnostics::class.java.getDeclaredMethod(
            "capture", DiagnosticEvent::class.java, Map::class.java
        ).apply { isAccessible = true }
        val categories = setOf(DiagnosticEvent.DISPLAY_INTERACTIVE, DiagnosticEvent.ROTATION_LISTENER)
        categories.forEach { event ->
            repeat(100) { index ->
                val metrics = mapOf(DiagnosticMetric.ACTIVE to (index % 2).toLong()) +
                    if (event == DiagnosticEvent.ROTATION_LISTENER)
                        mapOf(DiagnosticMetric.RESULT to 1L) else emptyMap()
                capture.invoke(diagnostics, event, metrics)
            }
        }
        val events = JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
            .filter { item -> categories.any { it.name == item.asJsonObject["event"].asString } }
        categories.forEach { event ->
            val records = events.filter { it.asJsonObject["event"].asString == event.name }
            assertEquals(4, records.size)
            assertEquals(100, records.sumOf { it.asJsonObject["metrics"].asJsonObject["COUNT"].asInt })
            assertEquals(setOf(0, 1), records.map {
                it.asJsonObject["metrics"].asJsonObject["ACTIVE"].asInt
            }.toSet())
        }
        assertTrue(events.all { item ->
            item.asJsonObject["metrics"].asJsonObject.entrySet().all { (key, value) ->
                key in setOf("ACTIVE", "RESULT", "COUNT", "AGGREGATED") &&
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber
            }
        })
    }

    @Test fun configurationSnapshotDoesNotStartServicesOrAcquireLocation() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "diagnostics-test"
        val repository = com.aitech.weartak_civ.data.DataRepository(app)
        val input = repository.getLocationInput.first()
        val location = repository.latestLocation.value
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.bindRepository(repository)
        diagnostics.setEnabled(true)
        val json = withTimeout(5_000) {
            var result = diagnostics.exportJson()
            while (!result.contains("CONFIGURATION")) {
                delay(20)
                result = diagnostics.exportJson()
            }
            result
        }
        val configuration = JsonParser.parseString(json).asJsonObject["events"].asJsonArray
            .first { it.asJsonObject["event"].asString == "CONFIGURATION" }.asJsonObject["metrics"].asJsonObject
        assertEquals(input?.ordinal ?: -1, configuration["SOURCE"].asInt)
        assertEquals(repository.getOnFootReportingInterval.first(), configuration["FOOT_SECONDS"].asInt)
        assertEquals(input, repository.getLocationInput.first())
        assertSame(location, repository.latestLocation.value)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun explicitOffStopsRecordingWithoutErasingAndClearKeepsPreference() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        diagnostics.onAppEvent(DittoDocumentEvent("track", "private"))
        assertTrue(diagnostics.exportJson().contains("SYNC"))
        diagnostics.setEnabled(false)
        assertFalse(EventBus.getDefault().isRegistered(diagnostics))
        val stopped = diagnostics.exportJson()
        repeat(20) { diagnostics.onAppEvent(DittoDocumentEvent("track", "private")) }
        assertEquals(stopped, diagnostics.exportJson())
        assertTrue(stopped.contains("SYNC"))
        diagnostics.setEnabled(true)
        diagnostics.clear()
        assertTrue(diagnostics.enabled.value)
        assertEquals(0, JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray.size())
    }

    @Test fun rotationRetentionAndClearOnlyTouchOwnedFiles() {
        val root = temporary.newFolder()
        var now = System.currentTimeMillis()
        val journal = DiagnosticJournal({ root }, maxBytes = 700, wallClock = { now })
        val unrelated = root.resolve("keep.txt").apply { writeText("not diagnostics") }
        repeat(30) { index -> journal.append(sample(index.toLong())) }
        val owned = root.listFiles()!!.filter { it.name.startsWith("events") }
        assertTrue(owned.size <= 3)
        assertTrue(owned.all { it.length() <= 700 })
        val events = JsonParser.parseString(journal.exportJson()).asJsonObject["events"].asJsonArray
        assertTrue(events.size() in 1 until 30)
        assertTrue(events.first().asJsonObject["elapsedMs"].asLong > 0)
        assertEquals(29, events.last().asJsonObject["elapsedMs"].asInt)
        now += 4 * 24 * 60 * 60 * 1000L
        assertEquals(0, JsonParser.parseString(journal.exportJson()).asJsonObject["events"].asJsonArray.size())
        journal.append(sample(31))
        journal.clear()
        assertEquals(listOf("keep.txt"), root.listFiles()!!.map { it.name })
        assertEquals("not diagnostics", unrelated.readText())
    }

    @Test fun productionCapacityRetainsTwelveHourSixtySecondTrialTrace() {
        val root = temporary.newFolder()
        val start = System.currentTimeMillis()
        var now = start
        val journal = DiagnosticJournal({ root }, wallClock = { now })
        val events = listOf(
            DiagnosticEvent.LOCATION_REQUEST, DiagnosticEvent.WAKE_LOCK,
            DiagnosticEvent.LOCATION_ACCEPTED, DiagnosticEvent.LOCATION_TIMEOUT,
            DiagnosticEvent.LOCATION_REJECTED, DiagnosticEvent.LOCATION_FAILURE,
            DiagnosticEvent.LOCATION_COALESCED, DiagnosticEvent.LOCATION_STOPPED,
            DiagnosticEvent.WAKE_LOCK, DiagnosticEvent.OBSERVATION_SCHEDULED,
            DiagnosticEvent.REPORT_POLICY, DiagnosticEvent.SPEED_VALIDITY,
            DiagnosticEvent.PLI_ATTEMPT, DiagnosticEvent.PLI_RESULT,
            DiagnosticEvent.BATTERY, DiagnosticEvent.POWER_CHANGED,
            DiagnosticEvent.CONNECTIVITY, DiagnosticEvent.APP_EVENT
        )
        repeat(12 * 60) { minute ->
            now = start + minute * 60_000L
            events.forEachIndexed { index, event ->
                journal.append(DiagnosticRecord(
                    wallTimeMs = now + index, elapsedMs = minute * 60_000L + index,
                    event = event, metrics = mapOf(
                        DiagnosticMetric.SOURCE to 0,
                        DiagnosticMetric.KIND to 1,
                        DiagnosticMetric.DURATION_MS to 6_000,
                        DiagnosticMetric.RESULT to 1,
                        DiagnosticMetric.COUNT to 1
                    )
                ))
            }
        }
        val stored = root.listFiles()!!.filter { it.name.startsWith("events") }
        assertTrue(stored.size in 2..3)
        assertTrue(stored.all { it.length() <= 2 * 1024 * 1024L })
        val retained = JsonParser.parseString(journal.exportJson()).asJsonObject["events"].asJsonArray
        assertEquals(12 * 60 * events.size, retained.size())
        assertEquals(0L, retained.first().asJsonObject["elapsedMs"].asLong)
        assertEquals(719 * 60_000L + events.lastIndex, retained.last().asJsonObject["elapsedMs"].asLong)
    }

    @Test fun storageAndIncompleteExportFailuresAreExplicit() {
        val file = temporary.newFile()
        val unavailable = DiagnosticJournal({ file })
        assertThrows(IOException::class.java) { unavailable.append(sample(1)) }
        val root = temporary.newFolder()
        root.resolve("events.jsonl").writeText("{partial")
        val corrupt = DiagnosticJournal({ root })
        assertThrows(IOException::class.java) { corrupt.exportJson() }
        corrupt.clear()
        assertFalse(root.resolve("events.jsonl").exists())
    }

    @Test fun javaCrashEvidencePreservesTheExistingHandlerWithoutErrorContents() = runBlocking {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded = 0
        val expected = IllegalStateException("sensitive crash message")
        val inherited = Thread.UncaughtExceptionHandler { _, error ->
            assertSame(expected, error)
            forwarded++
        }
        Thread.setDefaultUncaughtExceptionHandler(inherited)
        try {
            val diagnostics = AppDiagnostics(app).also { recorder = it }
            diagnostics.setEnabled(true)
            val firstHandler = Thread.getDefaultUncaughtExceptionHandler()!!
            val wrapper = Thread.UncaughtExceptionHandler { thread, error ->
                firstHandler.uncaughtException(thread, error)
            }
            Thread.setDefaultUncaughtExceptionHandler(wrapper)
            diagnostics.setEnabled(false)
            diagnostics.setEnabled(true)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), expected)
            assertEquals(1, forwarded)
            val exported = diagnostics.exportJson()
            assertTrue(exported.contains("JAVA_CRASH"))
            assertFalse(exported.contains("sensitive crash message"))
            diagnostics.setEnabled(false)
            assertSame(wrapper, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            recorder?.setEnabled(false)
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun sample(elapsed: Long) = DiagnosticRecord(
        wallTimeMs = System.currentTimeMillis(), elapsedMs = elapsed,
        event = DiagnosticEvent.BATTERY, metrics = mapOf(DiagnosticMetric.BATTERY_PERCENT to 75)
    )
}
