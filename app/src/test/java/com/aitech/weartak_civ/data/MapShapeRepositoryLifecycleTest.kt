package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.cot.CotDeletion
import com.aitech.weartak_civ.cot.CotMapShape
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotPoint
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.mockk
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
class MapShapeRepositoryLifecycleTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()

    @Before
    fun setup() {
        MainActivity.device_uid = "test-watch"
        app.getSharedPreferences("imported-map-shapes-v1", 0).edit().clear().commit()
    }

    @Test
    fun cotShapeUpdatesAndDeletionStaySourceScopedAndDoNotDeleteImportedShapes() = runBlocking {
        val repo = DataRepository(app)
        val imported = ImportedMapShapeRecord(
            uid = "local",
            title = "Local",
            markerType = "u-d-f",
            timeMillis = now,
            latitude = 41.0,
            longitude = -87.0,
            sourceId = "import:${"b".repeat(64)}",
            shape = line("0,0")
        )
        repo.replaceImportedMapShapes(imported.sourceId, listOf(imported))
        repo.applyReceivedMarkerEvent(shapeEvent("remote", now, "1,1"), "TAK", "server-a", now, now)
        repo.applyReceivedMarkerEvent(shapeEvent("remote", now + 1, "2,2"), "TAK", "server-a", now + 1, now)
        assertEquals(2.0, coordinateLongitude(repo.receivedMarkerList.value.single { it.uid == "remote" }), 0.0)

        repo.receiveCotDeletion(CotDeletion("remote", now + 2), "server-a", mockk(relaxed = true), now + 2)
        assertFalse(repo.receivedMarkerList.value.any { it.uid == "remote" })
        assertTrue(repo.receivedMarkerList.value.any { it.uid == "local" })
        assertTrue(DataRepository(app).receivedMarkerList.value.any { it.uid == "local" })
    }

    @Test
    fun importedShapesOutliveGenericExpiryWhileCotShapeStaleTimeStillApplies() = runBlocking {
        val repo = DataRepository(app)
        val imported = ImportedMapShapeRecord(
            "local", "Local", "u-d-f", now, 41.0, -87.0,
            "import:${"c".repeat(64)}", line("0,0")
        )
        repo.replaceImportedMapShapes(imported.sourceId, listOf(imported))
        repo.applyReceivedMarkerEvent(shapeEvent("remote", now, "1,1", stale = now + 10), "TAK", "server", now, now)
        repo.pruneGenericMarkers(now + 11)
        assertEquals(listOf("local"), repo.receivedMarkerList.value.map { it.uid })
        assertEquals(listOf("local"), DataRepository(app).receivedMarkerList.value.map { it.uid })
    }

    private fun shapeEvent(uid: String, time: Long, coords: String, stale: Long = now + 60_000) = CotMarkerEvent(
        version = "2.0",
        uid = uid,
        type = "u-d-f",
        how = "h-e",
        time = time,
        start = time,
        stale = stale,
        access = null,
        point = CotPoint(41.0, -87.0, 0.0, 5.0, 5.0),
        detail = CotMarkerDetail(
            markerCallsign = uid,
            link = null,
            creator = null,
            usericon = null,
            remarks = null,
            contact = null,
            mapShape = line(coords)
        )
    )

    private fun line(value: String) = CotMapShape(
        """{"type":"LineString","coordinates":[[${value.replace(",", ",")}],[3,3]]}"""
    )

    private fun coordinateLongitude(marker: DataRepository.ReceivedMarker): Double =
        com.google.gson.JsonParser.parseString(requireNotNull(marker.mapShape).geometryJson)
            .asJsonObject.getAsJsonArray("coordinates")[0].asJsonArray[0].asDouble
}
