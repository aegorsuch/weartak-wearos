package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import android.content.Intent
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MapActivity
import com.aitech.weartak_civ.presentation.requestBloodhoundComplicationUpdate
import com.aitech.weartak_civ.service.BloodhoundService
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import io.mockk.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PluginMapActionsTest {
    private val app = mockk<WearTakApplication>(relaxed = true)
    private val repo = mockk<DataRepository>(relaxed = true)
    private val bloodhound = BloodhoundRepository(mockk<Context>(relaxed = true))
    private val actions = PluginMapActions(app, "com.example.plugin")

    @Before fun setup() {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "self"
        every { app.bhRepo } returns bloodhound
        every { app.repo } returns repo
        every { repo.receivedUserList } returns MutableStateFlow(emptyList())
        every { repo.receivedMarkerList } returns MutableStateFlow(emptyList())
        every { repo.droppedMarkerList } returns MutableStateFlow(emptyList())
        mockkStatic("com.aitech.weartak_civ.presentation.BloodhoundActivityKt")
        every { requestBloodhoundComplicationUpdate(any()) } just Runs
        BloodhoundService.isRunning.set(true)
    }

    @After fun cleanup() {
        BloodhoundService.isRunning.set(false)
        unmockkStatic("com.aitech.weartak_civ.presentation.BloodhoundActivityKt")
    }

    @Test fun targetUsesExistingMapFocusIntentAndDoesNotCreateBloodhound() {
        val result = actions.execute(PluginMapRequest(C.ACTION_TARGET, "incident", "Incident", 38.0, -77.0))
        assertEquals(C.OK, result.getString(C.KEY_STATUS))
        val intent = slot<Intent>()
        verify { app.startActivity(capture(intent)) }
        assertEquals(MapActivity::class.java.name, intent.captured.component!!.className)
        assertEquals(38.0, intent.captured.getDoubleExtra(MapActivity.EXTRA_FOCUS_LAT, 0.0), 0.0)
        assertEquals(-77.0, intent.captured.getDoubleExtra(MapActivity.EXTRA_FOCUS_LON, 0.0), 0.0)
        assertTrue(bloodhound.bloodhoundMap.value.isEmpty())
    }

    @Test fun coordinateBloodhoundIsNamespacedAndUsesExistingActivationFlow() = runBlocking {
        val activation = async(start = CoroutineStart.UNDISPATCHED) { bloodhound.requestActivateBloodhound.first() }
        val result = actions.execute(PluginMapRequest(C.ACTION_BLOODHOUND, "incident", "Incident", 38.0, -77.0))
        assertEquals(C.OK, result.getString(C.KEY_STATUS))
        val uid = result.getString(C.KEY_UID)!!
        assertEquals("plugin:com.example.plugin:incident", uid)
        assertEquals(uid, activation.await())
        assertEquals("Incident", bloodhound.bloodhoundMap.value[uid]!!.target.name)
        assertEquals(38.0, bloodhound.bloodhoundMap.value[uid]!!.target.point.lat, 0.0)
        verify { requestBloodhoundComplicationUpdate(app) }
    }

    @Test fun uidContactKeepsSourceAndMovingContactTracking() = runBlocking {
        val now = System.currentTimeMillis()
        val user = DataRepository.ReceivedUser("contact", "a-f-G", "Blue", "Contact", "", now,
            now + 300_000, DataRepository.LocationSnapshot(now, 38.0, -77.0, 0.0, null, null, null, null),
            "TAK", "server-a")
        every { repo.receivedUserList } returns MutableStateFlow(listOf(user))
        val activation = async(start = CoroutineStart.UNDISPATCHED) { bloodhound.requestActivateBloodhound.first() }
        assertEquals(C.OK, actions.execute(
            PluginMapRequest(C.ACTION_BLOODHOUND, user.uid, null, null, null)).getString(C.KEY_STATUS))
        assertEquals(user.uid, activation.await())
        bloodhound.activate(user.uid)
        assertEquals("server-a", bloodhound.activeTrackingItem()!!.sourceServerId)
        assertTrue(bloodhound.activeTrackingItem()!!.bhEvent.tracksUserPosition)
    }

    @Test fun unknownUidAndStoppedServiceReturnExplicitErrorsWithoutMutation() {
        assertEquals(C.NOT_FOUND, actions.execute(
            PluginMapRequest(C.ACTION_TARGET, "unknown", null, null, null)).getString(C.KEY_STATUS))
        BloodhoundService.isRunning.set(false)
        assertEquals(C.UNAVAILABLE, actions.execute(
            PluginMapRequest(C.ACTION_BLOODHOUND, "incident", null, 38.0, -77.0)).getString(C.KEY_STATUS))
        assertTrue(bloodhound.bloodhoundMap.value.isEmpty())
    }

    @Test fun existingBloodhoundUidResolvesEvenWithoutCurrentSourceLocation() {
        val now = System.currentTimeMillis()
        val user = DataRepository.ReceivedUser("tracked", "a-f-G", "Blue", "Tracked", "", now,
            now + 300_000, null, "TAK", "server-a")
        bloodhound.addMarkerTarget("tracked", "Tracked", "a-f-G", 38.0, -77.0, now)
        for (users in listOf(emptyList(), listOf(user))) {
            every { repo.receivedUserList } returns MutableStateFlow(users)
            assertEquals(C.OK, actions.execute(
                PluginMapRequest(C.ACTION_TARGET, "tracked", null, null, null)).getString(C.KEY_STATUS))
        }
        verify(exactly = 2) { app.startActivity(any()) }
        assertEquals(1, bloodhound.bloodhoundMap.value.size)
    }
}
