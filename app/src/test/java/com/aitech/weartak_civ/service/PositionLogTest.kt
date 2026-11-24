package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
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
            assertEquals("\"watch,\"\"one\"\"\",2023-11-14T22:13:20Z,41.5,-87.25,6.5,,unknown,", lines[1])
            assertEquals("\"watch,\"\"one\"\"\",2023-11-14T22:13:21Z,41.5,-87.25,,,unknown,", lines[2])
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
}
