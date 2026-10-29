package com.aitech.weartak_civ.service

import android.app.Application
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.cot.CotDetailBuilder
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import live.ditto.Ditto
import live.ditto.DittoSyncSubscription
import live.ditto.DittoStore
import live.ditto.DittoSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DittoServicePowerTest {
    private val repo = mockk<DataRepository>(relaxed = true)
    private val native = mockk<Ditto>(relaxed = true)
    private val store = mockk<DittoStore>(relaxed = true)
    private val sync = mockk<DittoSync>(relaxed = true)
    private lateinit var service: DittoService
    private val healthy = DittoPowerState(worn = true, battery = 80)

    private fun field(name: String) = DittoService::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun setup() {
        MainActivity.device_uid = "WEAROS_056b9ca33004b318"
        every { repo.shouldSuppressWatchTransportNow() } returns false
        coEvery { repo.snapshotPliLocation() } returns null
        Ditto::class.java.getDeclaredField("sync").apply { isAccessible = true }.set(native, sync)
        Ditto::class.java.getDeclaredField("store").apply { isAccessible = true }.set(native, store)
        service = Robolectric.buildService(DittoService::class.java).get()
        field("dataRepository").set(service, repo)
        field("ownsDitto").set(service, true)
        field("dittoInstance").set(null, native)
    }

    @After fun cleanup() {
        if (::service.isInitialized) {
            service.onDestroy()
            (field("ioScope").get(service) as CoroutineScope).cancel()
        }
        field("dittoInstance").set(null, null)
        unmockkAll()
    }

    @Test fun offWristStopsOnlySyncRetainsSubscriptionsAndResumeDoesNotDuplicateNativeStarts() {
        val subscription = mockk<DittoSyncSubscription>(relaxed = true)
        field("subscriptionTrack").set(service, subscription)
        service.onPowerStateChanged(healthy)
        assertTrue(DittoService.syncActive.value)
        service.onPowerStateChanged(healthy.copy(worn = false))
        assertTrue(DittoService.powerSuppressed.value)
        assertEquals(DittoPowerSuppression.OFF_WRIST, DittoService.powerSuppressionReason.value)
        assertFalse(DittoService.syncActive.value)
        verify(exactly = 1) { native.stopSync() }
        verify(exactly = 0) { subscription.close() }
        service.onPowerStateChanged(healthy.copy(worn = false))
        verify(exactly = 1) { native.stopSync() }
        service.onPowerStateChanged(healthy)
        service.onPowerStateChanged(healthy)
        verify(exactly = 2) { native.startSync() }
        assertFalse(DittoService.powerSuppressed.value)
        assertNull(DittoService.powerSuppressionReason.value)
        verify(exactly = 0) { repo.setEndpointDisconnected(any()) }
    }

    @Test fun reasonChangesWhileSuppressedWithoutRestartingSyncAndClearsOnStop() {
        service.onPowerStateChanged(healthy.copy(sleeping = true))
        assertEquals(DittoPowerSuppression.POWER, DittoService.powerSuppressionReason.value)
        service.onPowerStateChanged(healthy.copy(worn = false))
        assertEquals(DittoPowerSuppression.OFF_WRIST, DittoService.powerSuppressionReason.value)
        service.onPowerStateChanged(healthy.copy(worn = false, battery = 10))
        assertEquals(DittoPowerSuppression.POWER, DittoService.powerSuppressionReason.value)
        verify(exactly = 1) { native.stopSync() }
        verify(exactly = 0) { native.startSync() }
        service.onDestroy()
        assertFalse(DittoService.powerSuppressed.value)
        assertNull(DittoService.powerSuppressionReason.value)
    }

    @Test fun cotMetadataUsesConfiguredWatchIdentityOnlyWhileLocalSyncEligible() = runBlocking {
        val enabled = MutableStateFlow(true)
        every { repo.getEnableDittoService } returns enabled
        @Suppress("UNCHECKED_CAST")
        val active = field("activeService").get(null) as MutableStateFlow<DittoService?>
        @Suppress("UNCHECKED_CAST")
        val changing = field("reconfiguring").get(null) as MutableStateFlow<Boolean>
        assertNull(DittoService.currentCotMetadata())
        active.value = service
        assertNull(service.ownCotMetadata())
        service.onPowerStateChanged(healthy)
        assertNull(service.ownCotMetadata())
        every { native.sdkVersion } returns "runtime-sdk-version"
        val configureIdentity = DittoService::class.java.getDeclaredMethod("configureCotIdentity").apply {
            isAccessible = true
        }
        val originalCallsign = MainActivity.callsign
        try {
            for (callsign in listOf("ODIN-WEARTAK", "RENAMED-WATCH")) {
                MainActivity.callsign = callsign
                configureIdentity.invoke(service)
                assertEquals("T004b318", native.deviceName)
                assertEquals(callsign, MainActivity.callsign)
            }
        } finally {
            MainActivity.callsign = originalCallsign
        }
        DittoService.localPeerKeyString = "watch-sdk-peer"
        val expected = CotDetailBuilder.DittoMetadata(
            "watch-sdk-peer", "T004b318", "runtime-sdk-version")
        assertEquals(expected, service.ownCotMetadata())
        assertTrue(CotDetailBuilder.ditto(service.ownCotMetadata()!!).contains("deviceName='${native.deviceName}'"))
        assertEquals(expected, DittoService.currentCotMetadata())
        enabled.value = false
        assertNull(DittoService.currentCotMetadata())
        enabled.value = true
        changing.value = true
        assertNull(DittoService.currentCotMetadata())
        changing.value = false
        every { repo.shouldSuppressWatchTransportNow() } returns true
        assertNull(DittoService.currentCotMetadata())
        every { repo.shouldSuppressWatchTransportNow() } returns false
        for (state in listOf(healthy.copy(worn = false), healthy.copy(battery = 1),
                healthy.copy(sleeping = true), healthy.copy(powerSave = true))) {
            service.onPowerStateChanged(state)
            assertNull(DittoService.currentCotMetadata())
            service.onPowerStateChanged(healthy)
            assertEquals(expected, DittoService.currentCotMetadata())
        }
        service.prepareForCompanionHandoff()
        assertTrue(DittoService.syncActive.value)
        assertNull(DittoService.currentCotMetadata())
        service.onDestroy()
        assertNull(service.ownCotMetadata())
        assertNull(DittoService.currentCotMetadata())
    }

    @Test fun chargingRecoveryOverridesAllPowerReasonsAndUnpluggingReappliesThem() {
        val off = healthy.copy(worn = false, battery = 1, sleeping = true, powerSave = true)
        service.onPowerStateChanged(off)
        assertTrue(DittoService.powerSuppressed.value)
        service.onPowerStateChanged(off.copy(charging = true))
        assertFalse(DittoService.powerSuppressed.value)
        assertNull(DittoService.powerSuppressionReason.value)
        assertTrue(DittoService.syncActive.value)
        service.onPowerStateChanged(off)
        assertTrue(DittoService.powerSuppressed.value)
    }

    @Test fun alertResumesImmediatelyBeforeWritingThenCanSuppressAgainAfterGrace() {
        val off = healthy.copy(worn = false, battery = 1, sleeping = true)
        service.onPowerStateChanged(off)
        assertTrue(service.publishDocument("alert", mapOf("_id" to "sos")))
        assertFalse(DittoService.powerSuppressed.value)
        assertNull(DittoService.powerSuppressionReason.value)
        verifyOrder {
            native.startSync()
            store.write(any<(live.ditto.DittoWriteTransaction) -> Unit>())
        }
        SystemClock.sleep(DittoPowerPolicy.ALERT_GRACE_MS)
        service.onPowerStateChanged(off)
        assertTrue(DittoService.powerSuppressed.value)
    }

    @Test fun alertCannotOverrideCompanionOwnershipOrReviveAStoppedPeer() {
        service.onPowerStateChanged(healthy.copy(worn = false))
        every { repo.shouldSuppressWatchTransportNow() } returns true
        assertFalse(service.publishDocument("alert", mapOf("_id" to "sos")))
        service.resumeForAlert()
        verify(exactly = 0) { native.startSync() }
        every { repo.shouldSuppressWatchTransportNow() } returns false
        service.onDestroy()
        service.resumeForAlert()
        verify(exactly = 0) { native.startSync() }
    }

    @Test fun interoperableMapitemEmergencyAlsoResumesSync() {
        service.onPowerStateChanged(healthy.copy(worn = false))
        assertTrue(service.publishDocument("mapitem", mapOf("_id" to "sos", "w" to "b-a-o-tbl")))
        assertTrue(DittoService.syncActive.value)
        assertFalse(DittoService.powerSuppressed.value)
    }

    @Test fun hourlySubscriptionsCloseEachOldHandleBeforeReplacingIt() {
        val handles = listOf("subscriptionTrack", "subscriptionMapItem", "subscriptionAlert").associateWith {
            mockk<DittoSyncSubscription>(relaxed = true)
        }
        handles.forEach { (name, handle) -> field(name).set(service, handle) }
        val queries = mutableListOf<String>()
        every { native.sync.registerSubscription(capture(queries)) } returns mockk(relaxed = true)
        DittoService::class.java.getDeclaredMethod("refreshPointSubscriptions").apply {
            isAccessible = true
        }.invoke(service)
        verifyOrder {
            handles.getValue("subscriptionTrack").close()
            native.sync.registerSubscription(match { it.startsWith("SELECT * FROM track WHERE") })
            handles.getValue("subscriptionMapItem").close()
            native.sync.registerSubscription(match { it.startsWith("SELECT * FROM mapitem WHERE") })
            handles.getValue("subscriptionAlert").close()
            native.sync.registerSubscription(match { it.startsWith("SELECT * FROM alert WHERE") })
        }
        assertEquals(3, queries.size)
    }
}
