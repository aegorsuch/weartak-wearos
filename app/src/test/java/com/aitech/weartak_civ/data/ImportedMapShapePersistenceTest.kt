package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.cot.CotMapShape
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ImportedMapShapePersistenceTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val source = "import:${"a".repeat(64)}"

    @Before
    fun setup() {
        MainActivity.device_uid = "watch"
        app.getSharedPreferences("imported-map-shapes-v1", 0).edit().clear().commit()
    }

    @Test
    fun importedGeometryAndStyleRestoreReplaceAndRemoveWithoutExpiry() = runBlocking {
        val repo = DataRepository(app)
        val first = record("area", polygon, 0xffff0000.toInt(), 0x7f00ff00)
        repo.replaceImportedMapShapes(source, listOf(first))

        val restarted = DataRepository(app)
        val restored = restarted.receivedMarkerList.value.single()
        assertEquals(first.uid, restored.uid)
        assertEquals(first.shape, restored.mapShape)
        restarted.pruneGenericMarkers(Long.MAX_VALUE)
        assertEquals(first.uid, restarted.receivedMarkerList.value.single().uid)

        val replacement = record("line", """{"type":"LineString","coordinates":[[1,2],[3,4]]}""", null, null)
        restarted.replaceImportedMapShapes(source, listOf(replacement))
        val afterReplacement = DataRepository(app).receivedMarkerList.value.single()
        assertEquals(replacement.uid, afterReplacement.uid)
        assertEquals(replacement.shape, afterReplacement.mapShape)

        assertTrue(restarted.removeImportedMapShape(replacement.uid, source))
        assertFalse(DataRepository(app).receivedMarkerList.value.any { it.uid == replacement.uid })
    }

    @Test
    fun malformedPersistedCatalogFailsClosedAndDoesNotRestorePartialShape() {
        app.getSharedPreferences("imported-map-shapes-v1", 0).edit()
            .putString("catalog", """{"shapes":[{"uid":"bad","geometry":"{"}]}""")
            .commit()
        assertTrue(DataRepository(app).receivedMarkerList.value.isEmpty())
    }

    @Test
    fun replacingShapesDoesNotSilentlyOverwriteACorruptCatalog() {
        val preferences = app.getSharedPreferences("imported-map-shapes-v1", 0)
        val corrupt = """{"shapes":[{"uid":"bad","geometry":"{"}]}"""
        preferences.edit().putString("catalog", corrupt).commit()
        val repo = DataRepository(app)
        val replacement = record("new", """{"type":"LineString","coordinates":[[1,2],[3,4]]}""", null, null)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.replaceImportedMapShapes(source, listOf(replacement)) }
        }
        assertEquals(corrupt, preferences.getString("catalog", null))
    }

    @Test
    fun catalogRejectsUnsupportedGeometryAndExcessVertices() {
        val persistence = ImportedMapShapePersistence(
            app.getSharedPreferences("imported-map-shapes-v1", 0)
        )
        val unsupported = record("point", """{"type":"Point","coordinates":[1,2]}""", null, null)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            persistence.save(listOf(unsupported))
        }
        val tooMany = record(
            "large",
            """{"type":"LineString","coordinates":[${(0..10_000).joinToString(",") { "[1,0]" }}]}""",
            null,
            null
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            persistence.save(listOf(tooMany))
        }
        val deeplyNested = record(
            "nested",
            """{"type":"LineString","coordinates":${"[".repeat(40)}[1,2],[3,4]${"]".repeat(40)}}""",
            null,
            null
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            persistence.save(listOf(deeplyNested))
        }
    }

    private fun record(
        uid: String,
        geometry: String,
        stroke: Int?,
        fill: Int?
    ) = ImportedMapShapeRecord(
        uid = uid,
        title = uid,
        markerType = "u-d-f",
        timeMillis = 1_800_000_000_000L,
        latitude = 2.0,
        longitude = 1.0,
        sourceId = source,
        shape = CotMapShape(geometry, stroke, fill, 3f)
    )

    private companion object {
        const val polygon =
            """{"type":"Polygon","coordinates":[[[0,0],[0,2],[2,2],[2,0],[0,0]],[[0.5,0.5],[1.5,0.5],[1.5,1.5],[0.5,1.5],[0.5,0.5]]]}"""
    }
}
