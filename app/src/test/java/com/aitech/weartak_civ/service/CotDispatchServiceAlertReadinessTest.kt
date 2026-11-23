package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.integration.plugin.PluginSettingsCoordinator
import com.aitech.weartak_civ.integration.plugin.PluginConnection
import com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost
import com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission
import com.aitech.weartak_civ.integration.plugin.TrustedCommunityPlugin
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotEventTime
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.just
import io.mockk.Runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CotDispatchServiceAlertReadinessTest {
    @Test
    fun chatRetriesRegistrationAndAdmissionWithoutGenericDittoFanout() = runTest {
        mockkObject(PluginOnlyDittoHost, CotMessageBuilder)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            every { CotMessageBuilder.chat(any(), any(), any(), any(), any()) } returns "<event/>"
            MainActivity.device_uid = "watch"
            val fixture = Fixture(this)
            fixture.pluginAvailable.value = true
            fixture.pluginRoutes.value = mapOf(TrustedCommunityPlugin.PACKAGE to 1L, "com.plugin.other" to 2L)
            fixture.verifiedSession.value = null
            val attempts = mutableListOf<String>()
            fixture.enableTrackedPlugin { _, id ->
                attempts += id
                if (attempts.size == 1) Submission.NOT_ADMITTED else Submission.SUBMITTED
            }
            fixture.sendChat()
            runCurrent()
            assertTrue(attempts.isEmpty())
            assertEquals(1, fixture.pluginWithoutDittoDispatches.size)
            assertTrue(fixture.pluginGenericDispatches.isEmpty())
            fixture.verifiedSession.value = "test-session"
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, attempts.size)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(2, attempts.size)
            assertEquals(attempts.first(), attempts.last())
            assertEquals(1, fixture.pluginWithoutDittoDispatches.size)
        } finally {
            unmockkObject(PluginOnlyDittoHost, CotMessageBuilder)
        }
    }

    @Test
    fun repeatedActivationWithSameUidSendsNewAlertAfterClear() = runTest {
        val fixture = Fixture(this)
        fixture.multicast.value = true
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        val first = fixture.alert("reused-uid")
        fixture.activeAlerts(false)[first.uuid] = first
        fixture.send(false, first)
        fixture.clear(false, first.uuid)
        val second = fixture.alert("reused-uid")
        fixture.activeAlerts(false)[second.uuid] = second
        fixture.send(false, second)
        assertEquals(3, fixture.sent.size)
        assertEquals(2, fixture.sent.count { !it.contains("type='b-a-o-can'") })
    }

    @Test
    fun deselectedPluginClearSurvivesOtherRouteAndRetriesOnReconnect() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            fixture.multicast.value = true
            val submissions = mutableListOf<String>()
            fixture.enableTrackedPlugin { xml, _ -> submissions += xml; Submission.SUBMITTED }
            fixture.startClearRecovery()
            val alert = fixture.alert("pending-clear")
            fixture.activeAlerts(false)[alert.uuid] = alert
            fixture.send(false, alert)
            runCurrent()
            assertEquals(1, submissions.size)
            fixture.pluginSelected.value = false
            fixture.clear(false, alert.uuid)
            runCurrent()
            assertEquals(2, fixture.sent.size)
            assertEquals(1, submissions.size)
            fixture.pluginSelected.value = true
            fixture.pluginRoutes.value = mapOf(TrustedCommunityPlugin.PACKAGE to 1L)
            runCurrent()
            assertEquals(2, submissions.size)
            assertTrue(submissions.last().contains("type='b-a-o-can'"))
            assertEquals(2, fixture.sent.size)
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun clearIsPersistedWhenNoRouteIsAvailable() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            val submissions = mutableListOf<String>()
            fixture.enableTrackedPlugin { xml, _ -> submissions += xml; Submission.SUBMITTED }
            fixture.startClearRecovery()
            val alert = fixture.alert("offline-clear")
            fixture.activeAlerts(false)[alert.uuid] = alert
            fixture.pluginSelected.value = false
            fixture.clear(false, alert.uuid)
            runCurrent()
            assertEquals(1, fixture.persistedClears().size)
            assertTrue(submissions.isEmpty())
            fixture.pluginSelected.value = true
            fixture.pluginRoutes.value = mapOf(TrustedCommunityPlugin.PACKAGE to 1L)
            runCurrent()
            assertEquals(1, submissions.size)
            assertTrue(submissions.single().contains("type='b-a-o-can'"))
            assertTrue(fixture.persistedClears().isEmpty())
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun gatewayClearIsCapturedForPluginWithoutRepeatingGatewaySend() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            val submissions = mutableListOf<String>()
            fixture.enableTrackedPlugin { xml, _ -> submissions += xml; Submission.SUBMITTED }
            fixture.companionEnabled.value = true
            fixture.companionReady.value = true
            fixture.gateway.negotiate(org.json.JSONObject().put("version", 1).put("session", "phone"))
            assertTrue(fixture.gateway.confirm("phone"))
            fixture.startClearRecovery()
            val alert = fixture.alert("gateway-clear")
            fixture.activeAlerts(false)[alert.uuid] = alert
            fixture.clear(false, alert.uuid)
            runCurrent()
            assertEquals(1, fixture.gatewaySent.size)
            assertEquals(1, fixture.persistedClears().size)
            assertTrue(submissions.isEmpty())

            fixture.companionReady.value = false
            fixture.gateway.reset()
            fixture.pluginRoutes.value = mapOf(TrustedCommunityPlugin.PACKAGE to 1L)
            runCurrent()
            assertEquals(1, submissions.size)
            assertEquals(fixture.gatewaySent.single(), submissions.single())
            assertEquals(1, fixture.gatewaySent.size)
            assertTrue(fixture.persistedClears().isEmpty())
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun uncertainAlertClearsAfterVerifiedReplacementWithoutAlertReplay() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            fixture.verifiedSession.value = "old-session"
            val submissions = mutableListOf<String>()
            fixture.enableTrackedPlugin { xml, _ ->
                submissions += xml
                if (submissions.size == 1) Submission.UNCERTAIN else Submission.SUBMITTED
            }
            fixture.startClearRecovery()
            val alert = fixture.alert("uncertain-clear")
            fixture.activeAlerts(false)[alert.uuid] = alert
            fixture.send(false, alert)
            runCurrent()
            fixture.clear(false, alert.uuid)
            runCurrent()
            assertEquals(1, submissions.size)
            assertEquals(1, fixture.persistedClears().size)
            fixture.verifiedSession.value = "new-session"
            fixture.pluginRoutes.value = mapOf(TrustedCommunityPlugin.PACKAGE to 1L)
            runCurrent()
            assertEquals(2, submissions.size)
            assertTrue(submissions.last().contains("type='b-a-o-can'"))
            assertTrue(fixture.persistedClears().isEmpty())
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun recreatedServiceRetriesPersistedClearFromInitialReadyState() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val first = Fixture(this)
            first.snapshotLocation = first.location(System.currentTimeMillis())
            val priorAttempts = mutableListOf<Pair<String, String>>()
            first.enableTrackedPlugin { xml, id ->
                priorAttempts += xml to id
                Submission.NOT_ADMITTED
            }
            val alert = first.alert("recreated-clear")
            first.activeAlerts(false)[alert.uuid] = alert
            first.clear(false, alert.uuid)
            runCurrent()
            assertTrue(priorAttempts.isNotEmpty())
            val captured = first.persistedClears().single()
            first.stopRecoveryCollector()

            val recreated = Fixture(this)
            val submissions = mutableListOf<Pair<String, String>>()
            recreated.enableTrackedPlugin(preservePreferences = true) { xml, id ->
                submissions += xml to id
                Submission.SUBMITTED
            }
            recreated.pluginRoutes.value = mapOf(TrustedCommunityPlugin.PACKAGE to 1L)
            recreated.startClearRecovery()
            runCurrent()
            assertEquals(1, submissions.size)
            assertEquals(captured.xml, submissions.single().first)
            assertEquals(priorAttempts.first(), submissions.single())
            assertTrue(recreated.persistedClears().isEmpty())
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun newSameUidActivationRetiresOfflineClearBeforeReconnect() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            fixture.multicast.value = true
            val submissions = mutableListOf<String>()
            fixture.enableTrackedPlugin(waitForReadiness = true) { xml, _ ->
                submissions += xml
                Submission.SUBMITTED
            }
            fixture.pluginReady.value = false
            fixture.startClearRecovery()
            val first = fixture.alert("reused-offline")
            fixture.activeAlerts(true)[first.uuid] = first
            fixture.clear(true, first.uuid)
            runCurrent()
            assertEquals(1, fixture.persistedClears().size)

            fixture.startAutomaticAlertCollector()
            runCurrent()
            fixture.automaticAlerts.emit(DataRepository.PhysioAndAutoGenAlertRequest(
                first.uuid, DataRepository.AlertState.ALERT, "Alert", "Help", 1, false, 0,
            ))
            runCurrent()
            assertTrue(fixture.persistedClears().isEmpty())
            fixture.pluginReady.value = true
            fixture.pluginRoutes.value = mapOf(TrustedCommunityPlugin.PACKAGE to 1L)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(1, submissions.size)
            assertFalse(submissions.single().contains("type='b-a-o-can'"))
            assertEquals(2, fixture.sent.size)
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun absentDittoPluginDoesNotBlockMulticastAlerts() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.setDittoPluginAbsent()
            fixture.multicast.value = true
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            fixture.send(false, fixture.alert("no-ditto-plugin"))
            runCurrent()
            assertEquals(1, fixture.sent.size)
            assertTrue(fixture.queue(false).isEmpty())
            assertTrue(fixture.pluginDispatches.isEmpty())
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun delayedPluginDoesNotBlockIndependentAlerts() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            fixture.multicast.value = true
            val release = CompletableDeferred<Unit>()
            fixture.enableTrackedPlugin { _, _ ->
                release.await()
                Submission.SUBMITTED
            }
            fixture.send(false, fixture.alert("first-tracked"))
            fixture.send(false, fixture.alert("second-tracked"))
            assertEquals(2, fixture.sent.size)
            release.complete(Unit)
            runCurrent()
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun coldPluginOnlyAlertWaitsForAdmissionAndNeverReplaysUncertainSubmission() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            var attempts = 0
            fixture.enableTrackedPlugin { _, _ ->
                attempts++
                if (attempts == 1) Submission.NOT_ADMITTED else Submission.UNCERTAIN
            }
            val alert = fixture.alert("cold-uncertain")
            fixture.send(false, alert)
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(1, fixture.queue(false).size)
            fixture.drain(false)
            assertEquals(2, attempts)
            assertEquals(1, fixture.queue(false).size)
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun otherRouteReceivesAlertBeforeSuppressedPluginIsReady() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            fixture.multicast.value = true
            val submissions = mutableListOf<Pair<String, String>>()
            fixture.enableTrackedPlugin { xml, id ->
                assertEquals(1, fixture.sent.size)
                submissions += xml to id
                if (submissions.size == 1) Submission.NOT_ADMITTED else Submission.SUBMITTED
            }
            fixture.send(false, fixture.alert("tracked-alert"))
            runCurrent()
            assertEquals(1, fixture.sent.size)
            assertEquals(2, submissions.size)
            assertEquals(1, submissions.toSet().size)
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun pluginOnlyAvailabilityDrainsQueuedEmergencyWithoutEnablingNetworkRoutes() = runTest {
        val fixture = Fixture(this)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.startRecoveryCollector()
        runCurrent()
        val alert = fixture.alert("plugin-only")
        fixture.activeAlerts(false)[alert.uuid] = alert
        fixture.send(false, alert)
        assertTrue(fixture.sent.isEmpty())
        fixture.pluginRoutes.value = mapOf("other.plugin" to 1L)
        fixture.pluginAvailable.value = true
        fixture.awaitFirstSend()
        assertEquals(1, fixture.sent.size)
        assertTrue(fixture.endpoints.value.isEmpty())
        assertFalse(fixture.multicast.value)
        assertFalse(fixture.companionEnabled.value)
        fixture.stopRecoveryCollector()
    }

    @Test
    fun usableRouteSendsManualAndAutomaticAlertsAndCancellationsWithAnOldValidFix() = runTest {
        for (automatic in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.multicast.value = true
            for (location in listOf(fixture.location(System.currentTimeMillis() - 60_000), fixture.location(System.currentTimeMillis()))) {
                fixture.snapshotLocation = location
                val alert = fixture.alert("alert-${fixture.sent.size}")
                fixture.activeAlerts(automatic)[alert.uuid] = alert
                fixture.send(automatic, alert)
                fixture.clear(automatic, alert.uuid)
            }
            assertEquals(4, fixture.sent.size)
            fixture.sent.forEach {
                assertFalse(it.contains("ce='9999999.0'"))
                assertTrue(it.contains("lat='41.0'"))
                assertTrue(it.contains("<weartak_location"))
            }
            assertEquals(2, fixture.sent.count { it.contains("type='b-a-o-can'") })
        }
    }

    @Test
    fun routeRecoveryDrainsQueuedAlertsAndCancellationsWithoutGpsAndWithoutDuplicateDrains() = runTest {
        for (automatic in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis() - 60_000)
            fixture.startRecoveryCollector()
            runCurrent()
            val alert = fixture.alert("queued")
            fixture.activeAlerts(automatic)[alert.uuid] = alert
            fixture.send(automatic, alert)
            fixture.clear(automatic, alert.uuid)
            assertEquals(0, fixture.sent.size)
            assertEquals(1, fixture.queue(automatic).size)

            // The endpoint set already contains another endpoint; recovery is not a
            // false-to-true transition of anyEndpointConnected.
            fixture.endpoints.value = setOf(DataRepository.Endpoint.TAK_SERVER)
            runCurrent()
            fixture.multicast.value = true
            val firstDrain = launch { fixture.drain(automatic) }
            val secondDrain = launch { fixture.drain(automatic) }
            firstDrain.join()
            secondDrain.join()

            assertEquals(1, fixture.sent.size)
            assertTrue(fixture.sent[0].contains("type='b-a-o-can'"))
            assertTrue(fixture.queue(automatic).isEmpty())
            assertTrue(fixture.activeAlerts(automatic).isEmpty())
            fixture.stopRecoveryCollector()
        }
    }

    @Test fun cancelDuringPluginReadinessWaitFencesOldAlertAndDoesNotBlockOtherRoute() = runTest {
        mockkObject(PluginOnlyDittoHost)
        try {
            every { PluginOnlyDittoHost.enabled() } returns true
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
            fixture.pluginReady.value = false
            val submissions = mutableListOf<String>()
            fixture.enableTrackedPlugin(waitForReadiness = true) { xml, _ ->
                submissions += xml
                Submission.SUBMITTED
            }
            val first = fixture.alert("same-uid")
            fixture.activeAlerts(false)[first.uuid] = first
            fixture.send(false, first)
            runCurrent()
            assertTrue(submissions.isEmpty())
            fixture.pluginSelected.value = false
            fixture.multicast.value = true
            fixture.clear(false, first.uuid)
            assertEquals(1, fixture.sent.size)
            assertTrue(fixture.sent.single().contains("type='b-a-o-can'"))
            fixture.pluginSelected.value = true
            fixture.pluginReady.value = true
            advanceTimeBy(251)
            runCurrent()
            assertTrue(submissions.isEmpty())
            val second = fixture.alert("same-uid")
            fixture.activeAlerts(false)[second.uuid] = second
            fixture.send(false, second)
            runCurrent()
            assertEquals(1, submissions.size)
            assertEquals(2, fixture.sent.size)
        } finally {
            unmockkObject(PluginOnlyDittoHost)
        }
    }

    @Test
    fun freshLocationIsRetainedButInvalidLocationIsOmittedFromEveryPayload() = runTest {
        val fixture = Fixture(this)
        fixture.companionReady.value = true
        fixture.companionEnabled.value = true
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.send(false, fixture.alert("fresh"))
        assertEquals(41.0, fixture.payloads.single().snapshot.location?.lat)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis()).copy(lat = Double.NaN)
        fixture.send(false, fixture.alert("invalid"))
        assertEquals(1, fixture.payloads.size)
        assertTrue(fixture.chats.single().snapshot.message.contains("location unavailable, acquiring GPS"))
    }

    @Test fun noFixSendsChatImmediatelyThenFirstRealFixSendsPositionalAlertOnce() = runTest {
        val fixture = Fixture(this)
        fixture.multicast.value = true
        val alert = fixture.alert("no-fix")
        fixture.activeAlerts(false)[alert.uuid] = alert
        fixture.send(false, alert)
        assertEquals(1, fixture.sent.size)
        assertTrue(fixture.sent.single().contains("type='b-t-f'"))
        assertTrue(fixture.sent.single().contains("location unavailable, acquiring GPS"))
        repeat(18) { fixture.drain(false) }
        assertEquals(1, fixture.sent.size)
        assertEquals(1, fixture.queue(false).size)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.drain(false)
        assertEquals(2, fixture.sent.size)
        assertTrue(fixture.sent.last().contains("lat='41.0'"))
        assertFalse(fixture.sent.last().contains("type='b-t-f'"))
        repeat(18) { fixture.drain(false) }
        assertEquals(2, fixture.sent.size)
        fixture.snapshotLocation = fixture.snapshotLocation!!.copy(timeMillis = System.currentTimeMillis() + 1, lat = 42.0)
        fixture.drain(false)
        assertEquals(2, fixture.sent.size)
        fixture.clear(false, alert.uuid)
        fixture.snapshotLocation = fixture.snapshotLocation!!.copy(lat = 43.0)
        fixture.drain(false)
        assertEquals(3, fixture.sent.size)
        assertTrue(fixture.sent.last().contains("type='b-a-o-can'"))
    }

    @Test fun clearingBeforeFirstFixNeverReplaysTheCancelledPositionalAlert() = runTest {
        val fixture = Fixture(this)
        fixture.multicast.value = true
        val alert = fixture.alert("cancel-before-fix")
        fixture.activeAlerts(false)[alert.uuid] = alert
        fixture.send(false, alert)
        fixture.clear(false, alert.uuid)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.drain(false)
        assertEquals(2, fixture.sent.size)
        assertTrue(fixture.sent.all { it.contains("type='b-t-f'") })
        assertTrue(fixture.queue(false).isEmpty())
    }

    @Test fun queuedNoFixAlertDoesNotBlockAnotherImmediateEmergencyNotification() = runTest {
        val fixture = Fixture(this)
        fixture.multicast.value = true
        fixture.send(false, fixture.alert("first"))
        fixture.send(false, fixture.alert("second"))
        assertEquals(2, fixture.sent.size)
        assertTrue(fixture.sent.all { it.contains("type='b-t-f'") })
        assertEquals(2, fixture.queue(false).size)
        fixture.drain(false)
        assertEquals(2, fixture.sent.size)
    }

    @Test fun disabledTakMasterDoesNotUseStaleSocketReadinessOrBlockMulticast() = runTest {
        val fixture = Fixture(this)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.takEnabled.value = false
        TAKConnectService.isAnyTakConnected = true
        try {
            val alert = fixture.alert("master-off")
            fixture.activeAlerts(false)[alert.uuid] = alert
            fixture.send(false, alert)
            assertTrue(fixture.sent.isEmpty())
            assertEquals(1, fixture.queue(false).size)
            fixture.multicast.value = true
            fixture.drain(false)
            assertEquals(1, fixture.sent.size)
            assertTrue(fixture.queue(false).isEmpty())
        } finally {
            TAKConnectService.isAnyTakConnected = false
        }
    }

    private class Fixture(private val scope: TestScope) {
        val endpoints = MutableStateFlow<Set<DataRepository.Endpoint>>(emptySet())
        val multicast = MutableStateFlow(false)
        val companionReady = MutableStateFlow(false)
        val companionEnabled = MutableStateFlow(false)
        val pluginAvailable = MutableStateFlow(false)
        val pluginRoutes = MutableStateFlow<Map<String, Long>>(emptyMap())
        val pluginSelected = MutableStateFlow(true)
        val pluginReady = MutableStateFlow(true)
        val verifiedSession = MutableStateFlow<String?>("test-session")
        val automaticAlerts = MutableSharedFlow<DataRepository.PhysioAndAutoGenAlertRequest>()
        val takEnabled = MutableStateFlow(true)
        val sent = mutableListOf<String>()
        val pluginDispatches = mutableListOf<String>()
        val pluginGenericDispatches = mutableListOf<String>()
        val pluginWithoutDittoDispatches = mutableListOf<String>()
        val gateway = com.aitech.weartak_civ.cot.CompanionGatewaySession()
        val gatewaySent = mutableListOf<String>()
        private val firstSend = CompletableDeferred<Unit>()
        val payloads = mutableListOf<CotCompanionPayload.Emergency>()
        val chats = mutableListOf<CotCompanionPayload.Chat>()
        var snapshotLocation: DataRepository.LocationSnapshot? = null
        private val repo = mockk<DataRepository>(relaxed = true)
        private val companion = mockk<CompanionAppRepository>(relaxed = true)
        private val service: CotDispatchService
        private val app: WearTakApplication
        private val collectorScope = CoroutineScope(
            scope.backgroundScope.coroutineContext + Job(scope.backgroundScope.coroutineContext[Job]),
        )

        init {
            every { companion.gateway } returns gateway
            every { companion.offerGatewayCot(any()) } answers { gatewaySent += firstArg<String>(); true }
            MainActivity.mainActivity = mockk {
                every { applicationContext } returns RuntimeEnvironment.getApplication()
            }
            MainActivity.enableSmsForAlerts = false
            TAKConnectService.isAnyTakConnected = false
            every { repo.endpointConnected } returns endpoints
            every { repo.anyEndpointConnected } returns MutableStateFlow(false)
            every { repo.latestLocation } returns MutableStateFlow<android.location.Location?>(null)
            every { repo.multicastPublisherRunning } returns multicast
            every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
            every { repo.getEnableSitxService } returns MutableStateFlow(false)
            every { repo.getEnableTakEndpoint } returns takEnabled
            every { repo.getEnableCompanionAppService } returns companionEnabled
            every { companion.companionAppReady } returns companionReady
            every { repo.isCompanionConnected } returns companionReady
            every { repo.addPhysioAndAutoGenAlert } returns automaticAlerts
            coEvery { repo.snapshotEmergencyEvent(any()) } coAnswers {
                yield()
                val alert = firstArg<DataRepository.CustomEmergencyAlert>()
                DataRepository.EmergencyEventSnapshot(
                    alert.uuid, alert.alertState, alert.category, alert.description, alert.priority, false,
                    CotEventTime(0, 0, 0, "2026-10-08T00:00:00Z", "2026-10-08T00:00:00Z", "2026-10-08T00:15:00Z"),
                    snapshotLocation, null, mockk(relaxed = true),
                )
            }
            coEvery { repo.snapshotChatEvent(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
            val coordinator = mockk<PluginSettingsCoordinator>(relaxed = true)
            every { coordinator.outboundCotAvailable } returns pluginAvailable
            every { coordinator.outboundCotReadyRoutes } returns pluginRoutes
            every { coordinator.connectivityToggles } returns MutableStateFlow(emptyMap())
            every { coordinator.anyPluginHasSettingEnabled(any()) } answers { pluginAvailable.value }
            app = mockk<WearTakApplication>(relaxed = true)
            every { app.pluginManager.settingsCoordinator } returns coordinator
            service = spyk(CotDispatchService())
            every { service.applicationContext } returns app
            val preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
                "alert-readiness-${java.util.UUID.randomUUID()}", android.content.Context.MODE_PRIVATE
            )
            field("trackedPluginAlert\$delegate").set(service, lazyOf(TrackedPluginAlert(preferences)))
            field("dataRepository").set(service, repo)
            field("serviceScope").set(service, collectorScope)
            field("capRepo\$delegate").set(service, lazyOf(companion))
            val dispatcher = CotEndpointDispatcher(
                sendTak = { _, _ -> error("Unexpected TAK route") },
                sendSitx = { error("Unexpected SitX route") },
                sendDitto = { _, _ -> error("Unexpected Ditto route") },
                sendMulticast = { sent += it },
                sendPliMulticast = { error("Unexpected PLI route") },
                sendCompanion = {
                    when (it) {
                        is CotCompanionPayload.Emergency -> payloads += it
                        is CotCompanionPayload.Chat -> chats += it
                        else -> error("Unexpected payload")
                    }
                },
                sendPlugin = {
                    pluginGenericDispatches += it
                    pluginDispatches += it
                    sent += it
                    firstSend.complete(Unit)
                },
                sendPluginWithoutDitto = {
                    pluginWithoutDittoDispatches += it
                    pluginDispatches += it
                    sent += it
                    firstSend.complete(Unit)
                },
            )
            field("endpointDispatcher\$delegate").set(service, lazyOf(dispatcher))
        }

        fun setDittoPluginAbsent() {
            every { app.pluginManager.dittoCotSelected() } returns false
            every { app.pluginManager.connectionFor(TrustedCommunityPlugin.PACKAGE) } returns null
        }

        fun enableTrackedPlugin(
            waitForReadiness: Boolean = false,
            preservePreferences: Boolean = false,
            send: suspend (String, String) -> Submission,
        ) {
            val connection = mockk<PluginConnection>()
            every { app.pluginManager.dittoCotSelected() } answers { pluginSelected.value }
            every { app.pluginManager.connectionFor(TrustedCommunityPlugin.PACKAGE) } returns connection
            every { connection.resumeDittoForAlert() } just Runs
            every { connection.dittoCotAvailable() } answers { pluginReady.value }
            every { connection.quiescentReplacementSession() } answers { verifiedSession.value }
            every { connection.offerTrackedDittoCoT(any(), any(), any(), any(), any(), any()) } answers {
                val xml = firstArg<String>()
                val id = arg<String>(3)
                val eligible = arg<() -> Boolean>(4)
                val expected = arg<String>(5)
                val wait: suspend () -> Submission = {
                    if (expected != verifiedSession.value) Submission.NOT_ADMITTED
                    else if (eligible()) send(xml, id) else Submission.UNCERTAIN
                }
                wait
            }
            val preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
                "alert-readiness-test", android.content.Context.MODE_PRIVATE
            )
            if (!preservePreferences) preferences.edit().clear().commit()
            var clock = 0L
            field("trackedPluginAlert\$delegate").set(service, lazyOf(
                if (waitForReadiness) TrackedPluginAlert(preferences, { scope.testScheduler.currentTime }, { delay(it) })
                else TrackedPluginAlert(preferences, { clock }, { clock += it })
            ))
        }

        fun startRecoveryCollector() {
            CotDispatchService::class.java.getDeclaredMethod("startAnyEndpointConnectedCollector")
                .apply { isAccessible = true }.invoke(service)
        }

        fun startClearRecovery() {
            CotDispatchService::class.java.getDeclaredMethod("startPendingPluginAlertClears")
                .apply { isAccessible = true }.invoke(service)
        }

        fun startAutomaticAlertCollector() {
            CotDispatchService::class.java.getDeclaredMethod("startAddPhysioAndAutoGenAlertCollector")
                .apply { isAccessible = true }.invoke(service)
        }

        fun persistedClears(): List<TrackedPluginAlert.PendingClear> {
            val preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
                "alert-readiness-test", android.content.Context.MODE_PRIVATE
            )
            return TrackedPluginAlert(preferences).pendingClears()
        }

        fun stopRecoveryCollector() { collectorScope.coroutineContext[Job]?.cancel() }
        suspend fun awaitFirstSend() = withTimeout(1_000) { firstSend.await() }
        fun alert(uid: String) = DataRepository.CustomEmergencyAlert(uid, DataRepository.AlertState.ALERT, "Alert", "Help", 1, false, 0, false)
        fun location(time: Long) = DataRepository.LocationSnapshot(time, 41.0, -87.0, 100.0, 4f, 5f, null, null)
        suspend fun send(automatic: Boolean, alert: DataRepository.CustomEmergencyAlert) {
            call(sendMethod(automatic), CotDispatchService.CustomEmergencyAlertWithAddedState(alert = alert))
        }
        suspend fun sendChat() {
            call("sendAllChatMessages", DataRepository.SendChatRequest("room", "Room", "hello", 0))
        }
        suspend fun drain(automatic: Boolean) { call(sendMethod(automatic), null) }
        suspend fun clear(automatic: Boolean, uid: String) {
            call(if (automatic) "handleClearPhysioAndAutoGenAlertUid" else "handleClearManualAlertUid", uid)
        }
        @Suppress("UNCHECKED_CAST")
        fun activeAlerts(automatic: Boolean) = field(if (automatic) "physioAndAutoGenAlertMap" else "manualAlertMap")
            .get(service) as MutableMap<String, DataRepository.CustomEmergencyAlert>
        @Suppress("UNCHECKED_CAST")
        fun queue(automatic: Boolean) = field(if (automatic) "pendingPhysioAndAutoGenAlertQueue" else "pendingManualAlertQueue")
            .get(service) as ArrayDeque<CotDispatchService.CustomEmergencyAlertWithAddedState>
        private fun sendMethod(automatic: Boolean) = if (automatic) "sendAllPhysioAndAutoGenAlerts" else "sendAllManualAlerts"
        private fun field(name: String) = CotDispatchService::class.java.getDeclaredField(name).apply { isAccessible = true }
        private suspend fun call(name: String, argument: Any?): Any? =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                CotDispatchService::class.java.declaredMethods.single { it.name == name }
                    .apply { isAccessible = true }.invoke(service, argument, continuation)
            }
    }
}
