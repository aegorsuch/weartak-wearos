package com.aitech.weartak_civ.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import com.aitech.weartak_civ.presentation.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MarkerCreationTitleTest {
    @Test fun newTitlesTrimOnlyTrailingAsciiWhitespaceAndKeepRemarks() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        MainActivity.Constants.callsign = "watch"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        for ((index, pair) in listOf(
            "Test " to "Test", "Bad \t\r\n" to "Bad", " Keep leading " to " Keep leading",
            " \t" to "", "Nonbreaking\u00a0" to "Nonbreaking\u00a0"
        ).withIndex()) {
            val (input, expected) = pair
            val uid = "original-$index"
            assertTrue(repo.requestMarkerDrop("friendly", input, "Remark  ", 123L, uid = uid))
            val marker = repo.droppedMarkerList.value.first { it.uid == uid }
            assertEquals(if (expected.isEmpty()) "watch_000000Z" else expected, marker.title)
            assertEquals("Remark  ", marker.remark)
        }
        val legacy = DataRepository.DroppedMarker(DataRepository.MarkerCotType.DROP,
            "legacy", DataRepository.MarkerType.FRIENDLY, "Legacy ", "Old  ", 1L, null, false, false)
        repo.storeDroppedMarkerList(listOf(legacy))
        assertEquals("Legacy ", repo.droppedMarkerList.value.single().title)
        assertEquals("Old  ", repo.droppedMarkerList.value.single().remark)
    }
}
