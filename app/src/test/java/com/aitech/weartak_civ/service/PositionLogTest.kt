package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PositionLogTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.deleteDatabase("position-log.db")
        app.getSharedPreferences("position_log", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() {
        app.deleteDatabase("position-log.db")
    }

    private fun fix(time: Long, accuracy: Boolean = true) = Location("gps").apply {
        this.time = time
        elapsedRealtimeNanos = time * 1_000_000
        latitude = 41.5
        longitude = -87.25
        if (accuracy) this.accuracy = 6.5f
    }

    @Test fun csvDeduplicatesFixesAndLeavesUnknownFieldsBlank() {
        val journal = PositionJournal(app)
        try {
            journal.append("watch,\"one\"", fix(1_700_000_000_000))
            journal.append("watch,\"one\"", fix(1_700_000_000_000))
            journal.append("watch,\"one\"", fix(1_700_000_001_000, false))
            val csv = StringWriter().also(journal::export).toString()
            val lines = csv.trimEnd().split("\r\n")
            assertEquals(3, lines.size)
            assertEquals("\"watch,\"\"one\"\"\",2023-11-14T22:13:20Z,41.5,-87.25,6.5,,unknown,,,,,,,,,,", lines[1])
            assertEquals("\"watch,\"\"one\"\"\",2023-11-14T22:13:21Z,41.5,-87.25,,,unknown,,,,,,,,,,", lines[2])
        } finally {
            journal.close()
        }
    }

    @Test fun socketWriteResultUpdatesOnlyMatchingFixAndSuccessSurvivesFailure() {
        val journal = PositionJournal(app)
        try {
            journal.append("watch", fix(1_000))
            journal.append("watch", fix(2_000))
            val key = PositionFixKey("watch", "gps", 1_000, 1_000_000_000, 41.5, -87.25)
            journal.recordRouteResult(key, PositionBearer.TAK_SERVER, false, 3_000)
            journal.recordRouteResult(key, PositionBearer.TAK_SERVER, true, 4_000)
            journal.recordRouteResult(key, PositionBearer.TAK_SERVER, false, 5_000)
            journal.recordRouteResult(key, PositionBearer.MULTICAST, true, 4_500)
            journal.recordRouteResult(key, PositionBearer.SITX, false, 4_600)
            journal.recordRouteResult(key.copy(timeMs = 2_000, elapsedNanos = 2_000_000_000), PositionBearer.SITX, true, 6_000)
            val lines = StringWriter().also(journal::export).toString().trimEnd().split("\r\n")
            assertTrue(lines[1].endsWith(",TAK_SERVER;MULTICAST,unknown,,socket_written,1970-01-01T00:00:04Z,socket_written,1970-01-01T00:00:04.500Z,not_accepted,,,,"))
            assertTrue(lines[2].endsWith(",SITX,unknown,,,,,,api_accepted,1970-01-01T00:00:06Z,,,"))
        } finally {
            journal.close()
        }
    }

    @Test fun dispositionsRequireEvidenceAndTrackFirstHandoff() {
        val journal = PositionJournal(app)
        try {
            for (time in 1_000L..8_000L step 1_000L)
                journal.append("watch", fix(time), generatedDuringCapture = time != 7_000L)
            fun key(time: Long) = PositionFixKey("watch", "gps", time, time * 1_000_000, 41.5, -87.25)
            journal.recordSelection(key(1_000), false)
            journal.recordSelection(key(2_000), false)
            journal.recordSelection(key(2_000), true)
            journal.recordAttempt(key(3_000), "first")
            journal.recordRouteResult(key(3_000), PositionBearer.MULTICAST, true, 4_000, "first", true)
            journal.recordAttempt(key(4_000), "first")
            journal.recordRouteResult(key(4_000), PositionBearer.TAK_SERVER, false, 5_000, "first", true)
            journal.recordAttempt(key(4_000), "retry")
            journal.recordRouteResult(key(4_000), PositionBearer.TAK_SERVER, true, 6_000, "retry", true)
            journal.recordAttempt(key(5_000), "queued")
            journal.recordRouteResult(key(5_000), PositionBearer.SITX, true, 7_000, "queued", false)
            journal.recordAttempt(key(6_000), "mixed")
            journal.recordRouteResult(key(6_000), PositionBearer.MULTICAST, true, 8_000, "mixed", true)
            journal.recordUntracked(key(6_000), PositionUntrackedRoute.PLUGIN)
            journal.recordUntracked(key(6_000), PositionUntrackedRoute.COMPANION)
            journal.recordSelection(key(7_000), false)
            journal.recordAttempt(key(8_000), "late-first", withinInterval = false)
            journal.recordRouteResult(key(8_000), PositionBearer.MULTICAST, true, 9_000, "late-first", true)
            val rows = StringWriter().also(journal::export).toString().trimEnd().split("\r\n").drop(1)
                .map { it.split(",") }
            assertEquals(listOf("never_sent", "unknown", "sent_immediately", "buffered_sent_later",
                "buffered_sent_later", "unknown", "unknown", "buffered_sent_later"), rows.map { it[6] })
            assertEquals("1970-01-01T00:00:04Z", rows[2][14])
            assertEquals("MULTICAST", rows[2][15])
            assertEquals("1970-01-01T00:00:06Z", rows[3][14])
            assertEquals("TAK_SERVER", rows[3][15])
            assertEquals("PLUGIN;COMPANION", rows[5][16])
        } finally {
            journal.close()
        }
    }

    @Test fun retentionRemovesOldestFixes() {
        val journal = PositionJournal(app, maxRows = 2)
        try {
            assertFalse(journal.append("watch", fix(1_000)))
            assertFalse(journal.append("watch", fix(2_000)))
            assertTrue(journal.append("watch", fix(3_000)))
            val csv = StringWriter().also(journal::export).toString()
            assertFalse(csv.contains("1970-01-01T00:00:01Z"))
            assertTrue(csv.contains("1970-01-01T00:00:02Z"))
            assertTrue(csv.contains("1970-01-01T00:00:03Z"))
        } finally {
            journal.close()
        }
    }

    @Test fun upgradePreservesExistingFixes() {
        app.openOrCreateDatabase("position-log.db", Application.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE fixes (
                id INTEGER PRIMARY KEY, uid TEXT NOT NULL, provider TEXT NOT NULL, fix_ms INTEGER NOT NULL,
                elapsed_ns INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy_m REAL,
                UNIQUE(uid, provider, fix_ms, elapsed_ns, latitude, longitude))""")
            db.execSQL("INSERT INTO fixes(uid, provider, fix_ms, elapsed_ns, latitude, longitude) VALUES('watch','gps',1000,1000000000,41.5,-87.25)")
            db.version = 1
        }
        val journal = PositionJournal(app)
        try {
            journal.recordRouteResult(PositionFixKey("watch", "gps", 1_000, 1_000_000_000, 41.5, -87.25), PositionBearer.TAK_SERVER, true, 2_000)
            val csv = StringWriter().also(journal::export).toString()
            assertTrue(csv.contains("watch,1970-01-01T00:00:01Z,41.5,-87.25"))
            assertTrue(csv.contains("socket_written,1970-01-01T00:00:02Z"))
        } finally {
            journal.close()
        }
    }

    @Test fun upgradeFromVersionTwoPreservesTakResults() {
        app.openOrCreateDatabase("position-log.db", Application.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE fixes (
                id INTEGER PRIMARY KEY, uid TEXT NOT NULL, provider TEXT NOT NULL, fix_ms INTEGER NOT NULL,
                elapsed_ns INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy_m REAL,
                tak_status TEXT, tak_written_ms INTEGER,
                UNIQUE(uid, provider, fix_ms, elapsed_ns, latitude, longitude))""")
            db.execSQL("INSERT INTO fixes(uid, provider, fix_ms, elapsed_ns, latitude, longitude, tak_status, tak_written_ms) VALUES('watch','gps',1000,1000000000,41.5,-87.25,'socket_written',2000)")
            db.version = 2
        }
        val journal = PositionJournal(app)
        try {
            journal.recordRouteResult(PositionFixKey("watch", "gps", 1_000, 1_000_000_000, 41.5, -87.25), PositionBearer.MULTICAST, true, 3_000)
            val csv = StringWriter().also(journal::export).toString()
            assertTrue(csv.contains("TAK_SERVER;MULTICAST,unknown,,socket_written,1970-01-01T00:00:02Z,socket_written,1970-01-01T00:00:03Z"))
        } finally {
            journal.close()
        }
    }

    @Test fun upgradeFromVersionThreeDoesNotInventDisposition() {
        app.openOrCreateDatabase("position-log.db", Application.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE fixes (
                id INTEGER PRIMARY KEY, uid TEXT NOT NULL, provider TEXT NOT NULL, fix_ms INTEGER NOT NULL,
                elapsed_ns INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy_m REAL,
                tak_status TEXT, tak_written_ms INTEGER, multicast_status TEXT, multicast_written_ms INTEGER,
                sitx_status TEXT, sitx_written_ms INTEGER,
                UNIQUE(uid, provider, fix_ms, elapsed_ns, latitude, longitude))""")
            db.execSQL("INSERT INTO fixes(uid, provider, fix_ms, elapsed_ns, latitude, longitude, tak_status, tak_written_ms) VALUES('watch','gps',1000,1000000000,41.5,-87.25,'socket_written',2000)")
            db.version = 3
        }
        val journal = PositionJournal(app)
        try {
            val csv = StringWriter().also(journal::export).toString()
            assertTrue(csv.contains("TAK_SERVER,unknown,,socket_written,1970-01-01T00:00:02Z"))
        } finally {
            journal.close()
        }
    }

    @Test fun captureRequiresExplicitOptIn() = runBlocking {
        val log = PositionLog(app)
        log.record("watch", fix(1_000))
        log.setEnabled(true)
        log.record("watch", fix(2_000))
        log.setEnabled(false)
        log.record("watch", fix(3_000))
        val writer = StringWriter()
        log.export(writer)
        val csv = writer.toString()
        assertFalse(csv.contains("1970-01-01T00:00:01Z"))
        assertTrue(csv.contains("1970-01-01T00:00:02Z"))
        assertFalse(csv.contains("1970-01-01T00:00:03Z"))
    }

    @Test fun captureCorrelatesSelectionAndHandoffs() = runBlocking {
        val log = PositionLog(app)
        log.setEnabled(true)
        val location = fix(1_000).apply { elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() }
        val snapshot = DataRepository.LocationSnapshot(location.time, location.latitude, location.longitude,
            null, null, null, null, null, location.provider, location.elapsedRealtimeNanos)
        log.record("watch", location)
        log.recordSelection("watch", location, false)
        assertTrue(StringWriter().also { log.export(it) }.toString().contains(",never_sent,"))
        log.recordSelection("watch", location, true)
        val attempt = log.beginAttempt("watch", snapshot, 60_000)
        log.recordRouteResult("watch", snapshot, PositionBearer.MULTICAST, true, attempt)
        assertTrue(StringWriter().also { log.export(it) }.toString().contains(",sent_immediately,"))
        log.recordUntracked("watch", snapshot, PositionUntrackedRoute.PLUGIN)
        assertTrue(StringWriter().also { log.export(it) }.toString().contains(",unknown,"))
    }
}
