package com.aitech.weartak_civ.service

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import live.ditto.Ditto
import live.ditto.DittoSyncSubscription
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DittoCompanionLifecycleTest {
    private val connected = MutableStateFlow(false)
    private val graceActive = MutableStateFlow(false)
    private val repo = mockk<DataRepository>(relaxed = true) {
        every { isCompanionConnected } returns connected
        every { companionDisconnectGraceActive } returns graceActive
        every { watchTransportSuppressed } returns combine(connected, graceActive) { ready, grace ->
            ready || grace
        }
        every { shouldSuppressWatchTransportNow() } answers { connected.value || graceActive.value }
    }
    private val native = mockk<Ditto>(relaxed = true)
    private val services = mutableListOf<DittoService>()
    private val oldRunning = DittoService.isRunning.get()

    @Before fun setup() {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
    }

    private fun field(name: String) = DittoService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun service(spy: Boolean = false): DittoService =
        Robolectric.buildService(DittoService::class.java).get()
            .let { if (spy) spyk(it) else it }.also {
            field("dataRepository").set(it, repo)
            services += it
        }

    @After fun cleanup() {
        services.forEach { it.onDestroy() }
        field("dittoInstance").set(null, null)
        DittoService.isRunning.set(oldRunning)
        unmockkAll()
    }

    @Test fun bondedReadyAtCreationCannotStartAnyNativePeer() {
        connected.value = true
        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.repo } returns repo
        val service = service(spy = true)
        every { service.applicationContext } returns app
        field("dittoInstance").set(null, native)
        service.onCreate()
        verify(exactly = 0) { native.startSync(); native.sync; native.presence }
    }

    @Test fun bondedCompanionPendingAtColdStartCannotStartAnyNativePeer() {
        graceActive.value = true
        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.repo } returns repo
        val service = service(spy = true)
        every { service.applicationContext } returns app
        field("dittoInstance").set(null, native)

        service.onCreate()

        verify(exactly = 0) { native.startSync(); native.sync; native.presence }
        assertFalse(DittoService.syncActive.value)
    }

    @Test fun readinessStopsNativeSyncSubscriptionsAndPendingCyclesWithoutManagerOrDestroy() {
        val service = service()
        field("dittoInstance").set(null, native)
        field("ownsDitto").set(service, true)
        assertTrue(service.startPeerSync())
        assertTrue(DittoService.syncActive.value)
        val observers = mockk<DittoStoreObservers>(relaxed = true)
        field("storeObservers").set(service, observers)
        val discovery = mockk<DittoBleDiscoverySession>(relaxed = true)
        field("bleDiscovery").set(service, discovery)
        val subscriptions = List(4) { mockk<DittoSyncSubscription>(relaxed = true) }
        listOf("subscriptionTrack", "subscriptionMapItem", "subscriptionAlert")
            .zip(subscriptions).forEach { (name, subscription) -> field(name).set(service, subscription) }
        val retention = DittoChatRetention(
            service.getSharedPreferences("companion-retention-test", android.content.Context.MODE_PRIVATE),
            subscribe = { subscriptions.last() },
            evict = {},
            onError = { throw it }
        )
        retention.start()
        field("chatRetention").set(service, retention)
        val handler = Handler(Looper.getMainLooper())
        field("dittoUpdateHandler").set(service, handler)
        val runnable = field("dittoUpdateRunnable").get(service) as Runnable
        handler.postDelayed(runnable, 3_000)
        val scope = field("ioScope").get(service) as CoroutineScope
        service.observeCompanionConnection()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { native.stopSync() }

        connected.value = true
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { native.stopSync() }
        subscriptions.forEach { subscription -> verify(exactly = 1) { subscription.close() } }
        verify(exactly = 1) { observers.close() }
        verify(exactly = 1) { discovery.close() }
        assertFalse(scope.isActive)
        assertFalse(handler.hasCallbacks(runnable))
        assertNull(field("dittoUpdateHandler").get(service))
        assertFalse(DittoService.syncActive.value)
        verify { repo.setEndpointDisconnected(DataRepository.Endpoint.DITTO) }
        coVerify(exactly = 0) { repo.storeEnableDittoService(any()) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertFalse(service.publishDocument("track", mapOf("_id" to "watch")))
        assertThrows(IllegalStateException::class.java) {
            service.writeDocument("track", mapOf("_id" to "watch"))
        }
        service.onDestroy()
        verify(exactly = 1) { native.stopSync() }
    }

    @Test fun disconnectDoesNotReviveAnOldWorkerButAllowsFreshServiceLifecycle() {
        val old = service()
        field("dittoInstance").set(null, native)
        field("ownsDitto").set(old, true)
        old.observeCompanionConnection()
        connected.value = true
        shadowOf(Looper.getMainLooper()).idle()
        connected.value = false
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse((field("ioScope").get(old) as CoroutineScope).isActive)
        assertFalse(old.startPeerSync())
        verify(exactly = 1) { native.stopSync() }
        verify(exactly = 0) { native.startSync() }

        val replacement = service()
        replacement.observeCompanionConnection()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue((field("ioScope").get(replacement) as CoroutineScope).isActive)
        assertFalse(field("peerStopped").getBoolean(replacement))
        field("ownsDitto").set(replacement, true)
        assertTrue(replacement.startPeerSync())
        verify(exactly = 1) { native.startSync() }
    }

    @Test fun publicationGateIsSynchronousEvenBeforeCollectorProcessesReadiness() {
        val service = service()
        field("dittoInstance").set(null, native)
        connected.value = true
        assertFalse(service.startPeerSync())
        assertFalse(service.publishDocument("track", mapOf("_id" to "watch")))
        assertFalse(service.publishDocument("chat", mapOf("_id" to "chat")))
        assertThrows(IllegalStateException::class.java) {
            service.writeDocument("track", mapOf("_id" to "watch"))
        }
        verify { native wasNot Called }
    }
}
