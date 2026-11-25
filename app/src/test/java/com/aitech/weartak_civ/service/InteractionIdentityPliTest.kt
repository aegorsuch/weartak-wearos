package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotOutboundMessage
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InteractionIdentityPliTest {
    @Test fun identityBurstPublishesLatestIdentityWithoutWaitingForNewFixAndPreservesFixTime() = runBlocking {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = mockk<MainActivity>()
        every { activity.applicationContext } returns app
        MainActivity.mainActivity = activity
        val realRepo = DataRepository(app)
        realRepo.storeCallsign("Current")
        realRepo.storeTeam("Blue")
        realRepo.storeRole("Medic")
        val fix = Location("fused").apply {
            latitude = 41.0
            longitude = -87.0
            altitude = 100.0
            accuracy = 5f
            time = System.currentTimeMillis() - 1000
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        val repo = mockk<DataRepository>()
        val currentLocation = MutableStateFlow<Location?>(fix)
        every { repo.latestLocation } returns currentLocation
        every { repo.isCurrentLocationInput(any()) } answers { realRepo.isCurrentLocationInput(firstArg()) }
        every { repo.isCurrentLocationSnapshot(any()) } answers { realRepo.isCurrentLocationSnapshot(firstArg()) }
        every { repo.buildHailDetail } returns MutableStateFlow(false)
        every { repo.getEnableCompanionAppService } returns flowOf(false)
        val companionConnected = MutableStateFlow(false)
        every { repo.isCompanionConnected } returns companionConnected
        every { repo.bestKnownLocation() } answers { currentLocation.value }
        every { repo.getEnableSitxService } returns flowOf(false)
        every { repo.getEnableTakEndpoint } returns flowOf(false)
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        every { repo.multicastPublisherRunning } returns MutableStateFlow(false)
        every { repo.anyEndpointConnected } returns MutableStateFlow(true)
        every { repo.shouldSuppressWatchTransportNow() } returns false
        coEvery { repo.snapshotInteractionPli(any()) } coAnswers {
            realRepo.snapshotInteractionPli(firstArg())
        }
        val companion = mockk<CompanionAppRepository>()
        every { companion.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        every { companion.companionAppReady } returns MutableStateFlow(false)
        val sent = LinkedBlockingQueue<CotOutboundMessage>()
        val dispatcher = mockk<CotEndpointDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } coAnswers {
            sent.add(firstArg())
            true
        }
        coEvery { dispatcher.dispatchGateway(any(), any()) } coAnswers {
            secondArg<suspend () -> Boolean>().invoke()
        }
        val hostApp = mockk<WearTakApplication>(relaxed = true)
        every { hostApp.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } returns false
        val service = spyk(CotDispatchService())
        every { service.applicationContext } returns hostApp
        fun inject(name: String, value: Any) {
            CotDispatchService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(service, value)
            }
        }
        inject("dataRepository", repo)
        inject("endpointDispatcher\$delegate", lazyOf(dispatcher))
        inject("capRepo\$delegate", lazyOf(companion))
        fix.time = System.currentTimeMillis()
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED))
        realRepo.storeCallsign("Latest")
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
        val message = sent.poll(3, TimeUnit.SECONDS)
        assertNotNull(message)
        val snapshot = (message!!.companionPayload as CotCompanionPayload.Pli).snapshot
        assertEquals("Latest", snapshot.systemData.callsign)
        assertEquals("Medic", snapshot.systemData.role)
        assertEquals("Blue", snapshot.systemData.team)
        assertEquals(fix.time, snapshot.time.time)
        assertTrue(message.xml.contains("callsign='Latest'"))
        val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(message.xml))).documentElement
        assertEquals("test-watch", event.getAttribute("uid"))
        val group = event.getElementsByTagName("__group").item(0) as Element
        assertEquals("Medic", group.getAttribute("role"))
        assertEquals("Blue", group.getAttribute("name"))
        val takv = event.getElementsByTagName("takv").item(0) as Element
        assertEquals("WearTAK", takv.getAttribute("platform"))
        assertEquals(BuildConfig.VERSION_NAME, takv.getAttribute("version"))
        assertNull(sent.poll(250, TimeUnit.MILLISECONDS))
        coEvery { repo.snapshotPli() } returns snapshot.copy(loc = null)
        service.buildAndSendPhysioLocationCotMsg()
        assertNull(sent.poll(150, TimeUnit.MILLISECONDS))
        coEvery { repo.snapshotPli() } returns snapshot.copy(loc = snapshot.loc!!.copy(timeMillis = 0))
        service.buildAndSendPhysioLocationCotMsg()
        assertNull(sent.poll(150, TimeUnit.MILLISECONDS))
        coEvery { repo.snapshotPli() } returns snapshot
        val gateway = companion.gateway
        gateway.negotiate(org.json.JSONObject().put("version", 1).put("session", "metadata-service-test"))
        gateway.confirm("metadata-service-test")
        every { repo.getEnableCompanionAppService } returns flowOf(true)
        every { companion.companionAppReady } returns MutableStateFlow(true)
        companionConnected.value = true
        val gatewaySent = mutableListOf<String>()
        every { companion.offerGatewayCot(any()) } answers { gatewaySent += firstArg<String>(); true }
        coEvery { repo.snapshotPli() } returns snapshot.copy(loc = null)
        service.buildAndSendPhysioLocationCotMsg()
        assertTrue(gatewaySent.isEmpty())
        coEvery { repo.snapshotPli() } returns snapshot
        service.buildAndSendPhysioLocationCotMsg()
        assertEquals(listOf(message.xml), gatewaySent)
        assertNull(sent.poll(150, TimeUnit.MILLISECONDS))
        gateway.reset()
        companionConnected.value = false
        every { repo.getEnableCompanionAppService } returns flowOf(false)
        every { companion.companionAppReady } returns MutableStateFlow(false)
        currentLocation.value = Location(fix).apply {
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 4_000_000_000L
            time = fix.time - 60_000L
        }
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.TEAM_CHANGED))
        val fallback = sent.poll(350, TimeUnit.MILLISECONDS)
        assertNotNull(fallback)
        val fallbackSnapshot = (fallback!!.companionPayload as CotCompanionPayload.Pli).snapshot
        assertEquals(fix.time - 60_000L, fallbackSnapshot.loc!!.timeMillis)
        assertTrue(fallbackSnapshot.time.time > fallbackSnapshot.loc.timeMillis)
        assertTrue(fallbackSnapshot.loc.ce!! > fix.accuracy)
        assertTrue(fallback.xml.contains("<weartak_location timeMillis='${fix.time - 60_000L}'/>"))
        currentLocation.value = null
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED))
        assertNull(sent.poll(350, TimeUnit.MILLISECONDS))
        val reportingClock = CotDispatchService::class.java.getDeclaredField("lastLocationUpdateRcvd").apply {
            isAccessible = true
            setLong(service, 123L)
        }
        service.onInteractionLocationRcvd(InteractionLocationRcvd(fix, setOf(PliRefreshReason.MAP_OPENED)))
        assertNotNull(sent.poll(3, TimeUnit.SECONDS))
        assertEquals(123L, reportingClock.getLong(service))
        service.onMessageEvent(MessageEvent(MessageEvent.CALLSIGN_TEAM_ROLE_CHANGED, 0))
        assertNull(sent.poll(150, TimeUnit.MILLISECONDS))
        currentLocation.value = fix
        shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED))
        assertNull(sent.poll(350, TimeUnit.MILLISECONDS))
    }
}
