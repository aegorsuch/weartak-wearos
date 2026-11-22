package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotOutboundMessage
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SelfMarkerLocationRefreshTest {
    @Test fun pluginOnlyMarkersUseJournalWithoutNativeLedger() = runTest {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "plugin-only-test"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        val repo = DataRepository(app)
        val host = mockk<WearTakApplication>(relaxed = true)
        every { host.pluginManager.settingsCoordinator.outboundCotAvailable } returns MutableStateFlow(true)
        every { host.pluginManager.settingsCoordinator.outboundCotReadyRoutes } returns MutableStateFlow(mapOf("other.plugin" to 1L))
        every { host.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } returns true
        every { host.pluginManager.dittoCotSelected() } returns true
        val plugin = mockk<com.aitech.weartak_civ.integration.plugin.PluginConnection>()
        every { host.pluginManager.connectionFor(any()) } returns plugin
        every { plugin.dittoCotAvailable() } returns true
        val submissions = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        coEvery { plugin.submitDittoCoT(any(), any(), any(), any()) } coAnswers {
            submissions += firstArg<String>() to arg<String>(3)
            com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission.SUBMITTED
        }
        val companion = mockk<CompanionAppRepository>(relaxed = true)
        every { companion.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        val messages = java.util.concurrent.CopyOnWriteArrayList<CotOutboundMessage>()
        val dispatcher = mockk<CotEndpointDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } coAnswers {
            messages += firstArg<CotOutboundMessage>()
            true
        }
        val service = spyk(Robolectric.buildService(CotDispatchService::class.java).get())
        val scope = CoroutineScope(Dispatchers.IO + Job())
        MainActivity.Constants.bhRepo = BloodhoundRepository(app)
        every { service.applicationContext } returns host
        fun inject(name: String, value: Any) {
            CotDispatchService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        inject("dataRepository", repo)
        inject("markerIntentConsumer\$delegate", lazyOf(com.aitech.weartak_civ.data.MarkerIntentConsumer(repo.markerIntentJournal)))
        inject("serviceScope", scope)
        inject("capRepo\$delegate", lazyOf(companion))
        inject("endpointDispatcher\$delegate", lazyOf(dispatcher))
        mockkObject(com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost)
        every { com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost.enabled() } returns true
        try {
            CotDispatchService::class.java.getDeclaredMethod("startMarkerJournalCollector")
                .apply { isAccessible = true }.invoke(service)
            assertTrue(repo.requestMarkerDrop("friendly", "New", "", System.currentTimeMillis(), 41.0, -87.0, "plugin-point"))
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (messages.none { (it.companionPayload as? CotCompanionPayload.Marker)?.snapshot?.uid == "plugin-point" }) delay(10)
                    true
                } == true)
            }
            assertFalse(repo.dittoPointLedger.owns("plugin-point"))
            assertTrue(repo.requestMarkerClear("plugin-point"))
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (messages.none { (it.companionPayload as? CotCompanionPayload.OwnedPointDeletion)?.uid == "plugin-point" } ||
                        repo.markerIntentJournal.read().intents.last().outcome !=
                        com.aitech.weartak_civ.data.MarkerIntentJournal.Outcome.PLUGIN_SUBMITTED) delay(10)
                    true
                } == true)
            }
            val clear = messages.last { it.companionPayload is CotCompanionPayload.OwnedPointDeletion }
            assertTrue(clear.xml.contains("t-x-d-d"))
            assertFalse(repo.dittoPointLedger.owns("plugin-point"))
            assertEquals(2, submissions.size)
            assertTrue(messages.all { it.excludeDittoPlugin })
            assertTrue(repo.markerIntentJournal.read().intents.all {
                it.outcome == com.aitech.weartak_civ.data.MarkerIntentJournal.Outcome.PLUGIN_SUBMITTED
            })
        } finally {
            scope.coroutineContext[Job]?.cancel()
            unmockkObject(com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost)
        }
    }

    @Test fun selectedDittoReconnectRetainsMarkerAndClearUntilSubmitted() = runTest {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "plugin-reconnect-test"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        val repo = DataRepository(app)
        val host = mockk<WearTakApplication>(relaxed = true)
        every { host.pluginManager.settingsCoordinator.outboundCotAvailable } returns MutableStateFlow(true)
        every { host.pluginManager.settingsCoordinator.outboundCotReadyRoutes } returns MutableStateFlow(mapOf("other.plugin" to 1L))
        every { host.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } returns true
        every { host.pluginManager.dittoCotSelected() } returns true
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        val plugin = mockk<com.aitech.weartak_civ.integration.plugin.PluginConnection>()
        every { host.pluginManager.connectionFor(any()) } answers { if (connected.get()) plugin else null }
        val submissions = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        coEvery { plugin.submitDittoCoT(any(), any(), any(), any()) } coAnswers {
            submissions += firstArg<String>() to arg<String>(3)
            com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission.SUBMITTED
        }
        val companion = mockk<CompanionAppRepository>(relaxed = true)
        every { companion.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        val messages = java.util.concurrent.CopyOnWriteArrayList<CotOutboundMessage>()
        val dispatcher = mockk<CotEndpointDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } coAnswers {
            messages += firstArg<CotOutboundMessage>()
            true
        }
        val service = spyk(Robolectric.buildService(CotDispatchService::class.java).get())
        val scope = CoroutineScope(Dispatchers.IO + Job())
        MainActivity.Constants.bhRepo = BloodhoundRepository(app)
        every { service.applicationContext } returns host
        fun inject(name: String, value: Any) {
            CotDispatchService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        inject("dataRepository", repo)
        inject("markerIntentConsumer\$delegate", lazyOf(com.aitech.weartak_civ.data.MarkerIntentConsumer(repo.markerIntentJournal)))
        inject("serviceScope", scope)
        inject("capRepo\$delegate", lazyOf(companion))
        inject("endpointDispatcher\$delegate", lazyOf(dispatcher))
        mockkObject(com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost)
        every { com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost.enabled() } returns true
        try {
            CotDispatchService::class.java.getDeclaredMethod("startMarkerJournalCollector")
                .apply { isAccessible = true }.invoke(service)
            suspend fun awaitDeferred(): com.aitech.weartak_civ.data.MarkerIntentJournal.Intent =
                withContext(Dispatchers.IO) { withTimeoutOrNull(3000) {
                    while (true) {
                        val intent = repo.markerIntentJournal.read().intents.lastOrNull()
                        if (intent?.dispatchPayload != null &&
                            intent.phase == com.aitech.weartak_civ.data.MarkerIntentJournal.Phase.NEVER_DISPATCHED &&
                            intent.otherRoutes == com.aitech.weartak_civ.data.MarkerIntentJournal.OtherRoutes.OFFERED) return@withTimeoutOrNull intent
                        delay(10)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                } } ?: error("Marker was not deferred")
            suspend fun awaitSubmission(count: Int) {
                assertTrue(withContext(Dispatchers.IO) { withTimeoutOrNull(8000) {
                    while (submissions.size < count || messages.size < count ||
                        repo.markerIntentJournal.read().intents.count {
                            it.outcome == com.aitech.weartak_civ.data.MarkerIntentJournal.Outcome.PLUGIN_SUBMITTED
                        } < count) delay(10)
                    true
                } } == true)
            }
            assertTrue(repo.requestMarkerDrop("friendly", "New", "", System.currentTimeMillis(), 41.0, -87.0, "reconnect-point"))
            val drop = awaitDeferred()
            assertEquals(1, messages.size)
            assertTrue(repo.requestMarkerClear("reconnect-point"))
            val clear = withContext(Dispatchers.IO) { withTimeoutOrNull(8000) {
                while (messages.size < 2) delay(10)
                awaitDeferred()
            } } ?: error("Clear was not independently offered")
            assertTrue(submissions.isEmpty())
            connected.set(true)
            awaitSubmission(2)
            assertEquals(drop.dispatchPayload to drop.dispatchId, submissions[0])
            assertEquals(clear.dispatchPayload to clear.dispatchId, submissions[1])
            assertTrue(messages.all { it.excludeDittoPlugin })
            assertTrue(repo.markerIntentJournal.read().intents.all {
                it.outcome == com.aitech.weartak_civ.data.MarkerIntentJournal.Outcome.PLUGIN_SUBMITTED
            })
        } finally {
            scope.coroutineContext[Job]?.cancel()
            unmockkObject(com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost)
        }
    }

    @Test fun selfDropPublishesBestFixImmediatelyThenUpdatesSameUidButExplicitDropNeverMoves() = runTest {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "self-test"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        val repo = DataRepository(app)
        repo.storeLocationInput(com.aitech.weartak_civ.data.LocationInput.INTERNAL_GNSS)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(2))
        repo.setEndpointConnected(DataRepository.Endpoint.TAK_SERVER)
        val old = Location("gps").apply {
            latitude = 41.0; longitude = -87.0; accuracy = 5f; time = System.currentTimeMillis() - 60_000
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 60_000_000_000L
        }
        repo.storeLatestLocation(old)
        val companion = mockk<CompanionAppRepository>(relaxed = true)
        every { companion.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        val host = mockk<WearTakApplication>(relaxed = true)
        every { host.pluginManager.settingsCoordinator.outboundCotAvailable } returns MutableStateFlow(true)
        every { host.pluginManager.settingsCoordinator.outboundCotReadyRoutes } returns MutableStateFlow(mapOf("other.plugin" to 1L))
        every { host.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } returns true
        every { host.pluginManager.dittoCotSelected() } returns true
        val plugin = mockk<com.aitech.weartak_civ.integration.plugin.PluginConnection>()
        every { host.pluginManager.connectionFor(any()) } returns plugin
        every { plugin.dittoCotAvailable() } returns true
        val pendingAdmission = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pendingResult = kotlinx.coroutines.CompletableDeferred<com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission>()
        coEvery { plugin.submitDittoCoT(any(), any(), any(), any()) } coAnswers {
            if (firstArg<String>().contains("tracked-pending")) {
                pendingAdmission.complete(Unit)
                pendingResult.await()
            } else com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission.SUBMITTED
        }
        val service = spyk(Robolectric.buildService(CotDispatchService::class.java).get())
        MainActivity.Constants.bhRepo = BloodhoundRepository(app)
        every { service.applicationContext } returns host
        val messages = java.util.concurrent.CopyOnWriteArrayList<CotOutboundMessage>()
        val dispatcher = mockk<CotEndpointDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } coAnswers {
            val message = firstArg<CotOutboundMessage>()
            messages += message
            true
        }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        fun inject(name: String, value: Any) {
            CotDispatchService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        inject("dataRepository", repo)
        inject("markerIntentConsumer\$delegate", lazyOf(com.aitech.weartak_civ.data.MarkerIntentConsumer(repo.markerIntentJournal)))
        inject("serviceScope", scope)
        inject("capRepo\$delegate", lazyOf(companion))
        inject("endpointDispatcher\$delegate", lazyOf(dispatcher))
        mockkObject(com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost)
        every { com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost.enabled() } returns true
        CotDispatchService::class.java.getDeclaredMethod("startMarkerJournalCollector").apply { isAccessible = true }.invoke(service)
        runCurrent()
        try {
            repo.requestMarkerDrop("friendly", "Self", "", System.currentTimeMillis(), uid = "self")
            repo.requestMarkerDrop("friendly", "Explicit", "", System.currentTimeMillis(), 42.0, -88.0, "explicit")
            runCurrent()
            withContext(Dispatchers.IO) {
                val ready = withTimeoutOrNull(3000) {
                    while (messages.count { it.companionPayload is CotCompanionPayload.Marker } < 2) delay(10)
                    true
                }
                assertTrue("Initial self and explicit markers not dispatched: journal=${repo.markerIntentJournal.read()} logs=${org.robolectric.shadows.ShadowLog.getLogs()}", ready == true)
            }
            val initial = messages.mapNotNull { (it.companionPayload as? CotCompanionPayload.Marker)?.snapshot }
            assertEquals(2, initial.size)
            assertEquals(41.0, initial.first().loc!!.lat, 0.0)
            assertEquals(old.time, initial.first().loc!!.timeMillis)
            assertFalse(repo.droppedMarkerList.value.first().waitingForUpdatedLocation)
            val fresh = Location(old).apply {
                latitude = 41.5
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            repo.storeLatestLocation(fresh)
            service.onInteractionLocationRcvd(InteractionLocationRcvd(fresh, setOf(PliRefreshReason.SELF_MARKER)))
            runCurrent()
            withContext(Dispatchers.IO) {
                val ready = withTimeoutOrNull(3000) {
                    while (messages.count { it.companionPayload is CotCompanionPayload.Marker } < 3) delay(10)
                    true
                }
                assertTrue("Fresh self-marker update not dispatched", ready == true)
            }
            val markers = messages.mapNotNull { (it.companionPayload as? CotCompanionPayload.Marker)?.snapshot }
            assertEquals(3, markers.size)
            assertEquals("self", markers.last().uid)
            assertEquals(41.5, markers.last().loc!!.lat, 0.0)
            assertEquals(42.0, repo.droppedMarkerList.value.single { it.uid == "explicit" }.location!!.lat, 0.0)
            service.onInteractionLocationRcvd(InteractionLocationRcvd(fresh, setOf(PliRefreshReason.SELF_MARKER)))
            runCurrent()
            assertEquals(3, messages.count { it.companionPayload is CotCompanionPayload.Marker })

            repo.requestMarkerDrop("friendly", "Delete before fix", "", System.currentTimeMillis(), uid = "deleted")
            runCurrent()
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (messages.count { it.companionPayload is CotCompanionPayload.Marker } < 4) delay(10)
                    true
                } == true)
            }
            assertTrue(repo.requestMarkerClear("deleted"))
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (messages.none { (it.companionPayload as? CotCompanionPayload.OwnedPointDeletion)?.uid == "deleted" }) delay(10)
                    true
                } == true)
            }
            fresh.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            fresh.time = System.currentTimeMillis()
            service.onInteractionLocationRcvd(InteractionLocationRcvd(fresh, setOf(PliRefreshReason.SELF_MARKER)))
            runCurrent()
            assertEquals(4, messages.count { it.companionPayload is CotCompanionPayload.Marker })
            assertTrue(repo.droppedMarkerList.value.none { it.uid == "deleted" })
            assertTrue(repo.requestMarkerClear("explicit"))
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (messages.none { (it.companionPayload as? CotCompanionPayload.OwnedPointDeletion)?.uid == "explicit" }) delay(10)
                    true
                } == true)
            }
            val deletion = messages.last { it.companionPayload is CotCompanionPayload.OwnedPointDeletion }
            assertEquals("explicit", (deletion.companionPayload as CotCompanionPayload.OwnedPointDeletion).uid)
            assertTrue(repo.requestMarkerDrop("friendly", "Tracked", "", System.currentTimeMillis(), 42.0, -88.0, "tracked-pending"))
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    pendingAdmission.await()
                    true
                } == true)
            }
            assertEquals(com.aitech.weartak_civ.data.MarkerIntentJournal.Phase.DISPATCH_STARTED,
                repo.markerIntentJournal.read().intents.last().phase)
            pendingResult.complete(com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission.UNCERTAIN)
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (repo.markerIntentJournal.read().intents.last().phase != com.aitech.weartak_civ.data.MarkerIntentJournal.Phase.UNCERTAIN) delay(10)
                    true
                } == true)
            }
            assertTrue(repo.requestMarkerDrop("friendly", "Committed", "", System.currentTimeMillis(), 42.0, -88.0, "tracked-success"))
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (repo.markerIntentJournal.read().intents.last().outcome != com.aitech.weartak_civ.data.MarkerIntentJournal.Outcome.PLUGIN_SUBMITTED) delay(10)
                    true
                } == true)
            }
            assertEquals(1, messages.count { (it.companionPayload as? CotCompanionPayload.Marker)?.snapshot?.uid == "tracked-pending" })
        } finally {
            scope.coroutineContext[Job]?.cancel()
            unmockkObject(com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost)
        }
    }
}
