package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.*
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.Endpoint
import com.aitech.weartak_civ.events.*
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OutboundPliAnnouncementServiceTest {
    private val locationEnabled = MutableStateFlow(true)
    private val dittoEnabled = MutableStateFlow(false)
    private val pluginRoutes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val companionEnabled = MutableStateFlow(false)
    private val companionConnected = MutableStateFlow(false)
    private val routes = MutableStateFlow<Map<String, DataRepository.OutboundReadyRoute>>(emptyMap())
    private val endpoints = MutableStateFlow<Set<Endpoint>>(emptySet())
    private val requests = LinkedBlockingQueue<PliRefreshReason>()
    private val sent = LinkedBlockingQueue<Pair<CotOutboundMessage, CotEndpointAvailability>>()
    private val repo = mockk<DataRepository>(relaxed = true)
    private lateinit var service: CotDispatchService
    private lateinit var dispatcher: CotEndpointDispatcher
    private val observer = TrackObserver()

    class TrackObserver {
        val tracks = LinkedBlockingQueue<OutboundDittoTrackRefresh>()
        @Subscribe fun track(event: OutboundDittoTrackRefresh) { tracks.add(event) }
    }

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        MainActivity.device_uid = "readiness-watch"
        MainActivity.versionNumber = "test"
        val activity = mockk<MainActivity>()
        every { activity.applicationContext } returns app
        MainActivity.mainActivity = activity
        val realRepo = DataRepository(app)
        every { repo.isCurrentLocationInput(any()) } answers { realRepo.isCurrentLocationInput(firstArg()) }
        every { repo.isCurrentLocationSnapshot(any()) } answers { realRepo.isCurrentLocationSnapshot(firstArg()) }
        every { repo.getEnableLocationService } returns locationEnabled
        every { repo.getEnableDittoService } returns dittoEnabled
        every { repo.getEnableSitxService } returns MutableStateFlow(false)
        every { repo.getEnableTakEndpoint } returns MutableStateFlow(false)
        every { repo.getEnableMulticast } returns MutableStateFlow(true)
        every { repo.multicastPublisherRunning } returns MutableStateFlow(true)
        every { repo.getEnableCompanionAppService } returns companionEnabled
        every { repo.isCompanionConnected } returns companionConnected
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        every { repo.outboundReadyRoutes } returns routes
        every { repo.endpointConnected } returns endpoints
        every { repo.buildHailDetail } returns MutableStateFlow(false)
        every { repo.requestInteractionPliRefresh(any()) } answers { requests.add(firstArg()); Unit }
        coEvery { repo.snapshotOutboundAnnouncement(any()) } coAnswers { realRepo.snapshotOutboundAnnouncement(firstArg()) }
        val hostApp = mockk<WearTakApplication>(relaxed = true)
        every { hostApp.pluginManager.settingsCoordinator.outboundCotReadyRoutes } returns pluginRoutes
        every { hostApp.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } answers { pluginRoutes.value.isNotEmpty() }
        val companion = mockk<CompanionAppRepository>(relaxed = true)
        every { companion.gateway } returns CompanionGatewaySession()
        dispatcher = mockk<CotEndpointDispatcher>()
        coEvery { dispatcher.dispatch(any(), any(), true) } coAnswers {
            sent.add(firstArg<CotOutboundMessage>() to secondArg<CotEndpointAvailability>())
            true
        }
        service = spyk(CotDispatchService())
        every { service.applicationContext } returns hostApp
        fun inject(name: String, value: Any) {
            CotDispatchService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        inject("dataRepository", repo)
        inject("endpointDispatcher\$delegate", lazyOf(dispatcher))
        inject("capRepo\$delegate", lazyOf(companion))
        mockkObject(LicenseManager)
        every { LicenseManager.isLicenseValid() } returns false
        EventBus.getDefault().register(observer)
    }

    @After fun cleanup() {
        (CotDispatchService::class.java.getDeclaredField("serviceScope").apply { isAccessible = true }
            .get(service) as CoroutineScope).cancel()
        EventBus.getDefault().unregister(observer)
        unmockkObject(LicenseManager)
    }

    private fun freshFix() = Location("fused").apply {
        latitude = 41.0; longitude = -87.0; altitude = 100.0; accuracy = 5f
        time = System.currentTimeMillis() - 1000
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun ready(endpoint: Endpoint, generation: Long = 1) {
        endpoints.value = endpoints.value + endpoint
        routes.value = routes.value + ("${endpoint.name}:test" to DataRepository.OutboundReadyRoute(endpoint, generation))
    }

    @Test fun enablingBeforePluginReadyWaitsThenAnnouncesOneCurrentTrackWithoutFlushingOtherWork() {
        service.collectOutboundReadyRoutes(service.outboundReadyRouteFlow())
        dittoEnabled.value = true
        assertNull(requests.poll(250, TimeUnit.MILLISECONDS))
        pluginRoutes.value = mapOf("ditto.plugin" to 1L)
        assertEquals(PliRefreshReason.OUTBOUND_ROUTE_READY, requests.poll(3, TimeUnit.SECONDS))
        val fix = freshFix()
        service.onInteractionLocationRcvd(InteractionLocationRcvd(fix, setOf(PliRefreshReason.OUTBOUND_ROUTE_READY)))
        val message = sent.poll(3, TimeUnit.SECONDS)
        assertNotNull(message)
        val snapshot = (message!!.first.companionPayload as CotCompanionPayload.Pli).snapshot
        assertEquals(fix.time, snapshot.time.time)
        assertEquals(fix.latitude, snapshot.loc!!.lat, 0.0)
        assertTrue(message.second.plugin)
        service.onInteractionLocationRcvd(InteractionLocationRcvd(fix, setOf(PliRefreshReason.OUTBOUND_ROUTE_READY)))
        assertNull(sent.poll(200, TimeUnit.MILLISECONDS))
        assertNull(observer.tracks.poll(100, TimeUnit.MILLISECONDS))
        coVerify(exactly = 0) { repo.snapshotEmergencyEvent(any()) }
        coVerify(exactly = 0) { repo.snapshotMarker(any()) }
        coVerify(exactly = 0) { repo.storeLatestLocation(any()) }
    }

    @Test fun currentPrivacyAndPermissionSuppressPendingAnnouncementUntilAllowed() {
        service.collectOutboundReadyRoutes(service.outboundReadyRouteFlow())
        ready(Endpoint.MULTICAST)
        assertEquals(PliRefreshReason.OUTBOUND_ROUTE_READY, requests.poll(3, TimeUnit.SECONDS))
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        service.onInteractionLocationRcvd(InteractionLocationRcvd(freshFix(), setOf(PliRefreshReason.OUTBOUND_ROUTE_READY)))
        assertNull(sent.poll(200, TimeUnit.MILLISECONDS))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        locationEnabled.value = false
        assertTrue(runBlocking { service.outboundReadyRouteFlow().first() }.isEmpty())
        service.onInteractionLocationRcvd(InteractionLocationRcvd(freshFix(), setOf(PliRefreshReason.OUTBOUND_ROUTE_READY)))
        assertNull(sent.poll(200, TimeUnit.MILLISECONDS))
        locationEnabled.value = true
        assertEquals(PliRefreshReason.OUTBOUND_ROUTE_READY, requests.poll(3, TimeUnit.SECONDS))
        service.onInteractionLocationRcvd(InteractionLocationRcvd(freshFix(), setOf(PliRefreshReason.OUTBOUND_ROUTE_READY)))
        assertNotNull(sent.poll(3, TimeUnit.SECONDS))
    }

    @Test fun connectedCompanionSuppressesDirectKeysAndPluginPublication() {
        dittoEnabled.value = true
        companionEnabled.value = true
        companionConnected.value = true
        pluginRoutes.value = mapOf("ditto.plugin" to 1L)
        ready(Endpoint.MULTICAST)
        ready(Endpoint.COMPANION_APP)
        val eligible = runBlocking { service.outboundReadyRouteFlow().first() }
        assertEquals(setOf("COMPANION_APP:test:1"), eligible)
        service.collectOutboundReadyRoutes(service.outboundReadyRouteFlow())
        assertEquals(PliRefreshReason.OUTBOUND_ROUTE_READY, requests.poll(3, TimeUnit.SECONDS))
        service.onInteractionLocationRcvd(InteractionLocationRcvd(freshFix(), setOf(PliRefreshReason.OUTBOUND_ROUTE_READY)))
        val availability = sent.poll(3, TimeUnit.SECONDS)!!.second
        assertTrue(availability.companion)
        assertEquals(listOf(CotRoute.COMPANION), CotDispatchPolicy.routesFor(CotMessageKind.PLI, availability))
        assertNull(observer.tracks.poll(150, TimeUnit.MILLISECONDS))
    }

    @Test fun dynamicObservationsShareAnchoredDueGateWithoutWritingCoordinatesOrBypassingOptOut() {
        val dynamic = MutableStateFlow(true)
        every { repo.getEnableDynamicReportingStrategy } returns dynamic
        val realRepo = DataRepository(RuntimeEnvironment.getApplication())
        val snapshot = runBlocking {
            realRepo.storeLocationInput(com.aitech.weartak_civ.data.LocationInput.FUSED)
            realRepo.storeLatestLocation(freshFix())
            realRepo.snapshotPli()
        }
        assertNotNull(snapshot.loc)
        every { repo.isCurrentLocationSnapshot(any()) } answers { realRepo.isCurrentLocationSnapshot(firstArg()) }
        coEvery { repo.snapshotPli() } returns snapshot
        coEvery { dispatcher.dispatch(any(), any(), false) } coAnswers {
            sent.add(firstArg<CotOutboundMessage>() to secondArg<CotEndpointAvailability>())
            true
        }
        val previous = CotDispatchService.actualReportingIntervalSecs
        try {
            CotDispatchService.actualReportingIntervalSecs = 3600
            service.buildAndSendPhysioLocationCotMsg(periodic = true)
            assertNotNull(sent.poll(1, TimeUnit.SECONDS))
            repeat(10) { service.buildAndSendPhysioLocationCotMsg(periodic = true) }
            assertTrue(sent.isEmpty())
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30))
            service.buildAndSendPhysioLocationCotMsg(periodic = true)
            assertTrue(sent.isEmpty())
            CotDispatchService.actualReportingIntervalSecs = 30
            service.buildAndSendPhysioLocationCotMsg(periodic = true)
            assertNotNull(sent.poll(1, TimeUnit.SECONDS))
            locationEnabled.value = false
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30))
            service.buildAndSendPhysioLocationCotMsg(periodic = true)
            assertTrue(sent.isEmpty())
            coVerify(exactly = 0) { repo.storeLatestLocation(any()) }
            coVerify(exactly = 0) { repo.requestInteractionPliRefresh(any()) }
        } finally { CotDispatchService.actualReportingIntervalSecs = previous }
    }
}
