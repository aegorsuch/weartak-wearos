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
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DiagnosticsPackageTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.deleteDatabase("position-log.db")
        app.getSharedPreferences("position_log", Application.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() {
        app.deleteDatabase("position-log.db")
    }

    @Test fun packageContainsManifestAndBothLogs() = runBlocking {
        val positions = PositionLog(app)
        positions.setEnabled(true)
        positions.record("watch", Location("gps").apply {
            time = 1_700_000_000_000
            elapsedRealtimeNanos = 123_000_000
            latitude = 41.5
            longitude = -87.25
        })
        val diagnostics = AppDiagnostics(app)
        try {
            val output = ByteArrayOutputStream()
            DiagnosticsPackage.write(output, positions, diagnostics)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
            assertEquals(setOf("MANIFEST/manifest.xml", "FILES/positions.csv", "FILES/diagnostics.json",
                "FILES/README.txt"), entries.keys)
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(entries.getValue("MANIFEST/manifest.xml")))
            parser.nextTag()
            assertEquals("MissionPackageManifest", parser.name)
            assertEquals("2", parser.getAttributeValue(null, "version"))
            assertTrue(entries.getValue("FILES/positions.csv").contains("watch,2023-11-14T22:13:20Z,41.5,-87.25"))
            assertTrue(entries.getValue("FILES/diagnostics.json").contains("\"events\""))
            assertTrue(entries.getValue("FILES/README.txt").contains("68%"))
        } finally {
            diagnostics.close()
        }
    }
}
