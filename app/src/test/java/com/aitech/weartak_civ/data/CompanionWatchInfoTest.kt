package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.WearTakApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionWatchInfoTest {
    @Test fun watchInfoPreservesSnapshotWithoutReadingBluetoothAddress() = runBlocking {
        val context = mockk<WearTakApplication>(relaxed = true)
        val data = mockk<DataRepository>()
        every { context.repo } returns data
        coEvery { data.snapshotSystem() } returns DataRepository.SystemSnapshot(
            timeMillis = 1L,
            batteryPercent = 72,
            callsign = "PHONE",
            deviceId = "test-watch",
            wearTakVersion = "test-version",
            reportIntSecs = 60,
            systemStates = mockk(),
            standaloneCallsign = "WATCH"
        )
        val repository = CompanionAppRepository(context)
        val payload = repository.buildWatchInfoPayload()

        assertNull(payload.mac)
        assertEquals("test-watch", payload.uid)
        assertEquals("PHONE", payload.cs)
        assertEquals("WATCH", payload.standaloneCallsign)
        assertEquals("test-version", payload.wtVrs)
        assertEquals(72, payload.bat)
        assertEquals(60, payload.int)
        verify(exactly = 0) { context.getSystemService(Context.BLUETOOTH_SERVICE) }
        verify(exactly = 0) { context.checkPermission(any(), any(), any()) }

        val envelope = CompanionAppRepository.Envelope(
            vers = 1, msgType = "watch_info", msgUid = payload.uid,
            t = "1", src = "wearos", payload = payload
        )
        val json = JSONObject(repository.envelopeToJson(envelope)).getJSONObject("payload")
        assertFalse(json.has("mac"))
        assertEquals("PHONE", json.getString("cs"))
        assertEquals("WATCH", json.getString("standaloneCallsign"))
    }
}
