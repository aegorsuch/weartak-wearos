package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakFailureCategory
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import com.aitech.weartak_civ.data.takserver.TakEnrollmentException
import com.aitech.weartak_civ.data.takserver.TakEnrollmentStage
import com.aitech.weartak_civ.data.takserver.TakServerRegistration
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.events.MessageEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TakServerRegistrationServiceTest {
    private lateinit var repository: DataRepository
    private val companionConnected = MutableStateFlow(false)
    private val serverId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private var stored = DataRepository.TakServerItem(
        true, "Test", "tak.example.invalid", 8089, false, "user", "password", "", "", serverId,
    )

    @Before fun setUp() {
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.applicationContext } returns RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = activity
        repository = mockk(relaxed = true)
        MainActivity.dataRepository = repository
        every { repository.takChannels } returns MutableStateFlow(emptyList())
        every { repository.isCompanionConnected } returns companionConnected
        every { repository.shouldSuppressWatchTransportNow() } answers { companionConnected.value }
        coEvery { repository.tryAwaitTakServerList() } answers { listOf(stored) }
        coEvery { repository.getApprovedServerCa(any(), any(), any()) } returns null
        // Online-path tests mock configuration transport; no test performs network I/O.
        TAKConnectService.isNetworkConnected = false
        TAKConnectService.takServerDetails = mutableListOf()
    }

    @After fun tearDown() = runBlocking {
        val jobs = TAKConnectService.takServerDetails.flatMap {
            listOfNotNull(it.runtime.currentAttempt?.job, it.runtime.readerJob)
        }
        TAKConnectService.isNetworkConnected = false
        TAKConnectService.getInstance().closeTakServerSockets()
        withTimeout(5_000) { jobs.forEach { it.join() } }
        val scopeField = TAKConnectService::class.java.getDeclaredField("connectionScope").apply { isAccessible = true }
        val scope = scopeField.get(null) as kotlinx.coroutines.CoroutineScope
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList().orEmpty()
        children.forEach { it.cancel() }
        withTimeout(5_000) { children.forEach { it.join() } }
        unmockkAll()
    }

    private fun register() = runBlocking {
        TAKConnectService.serverRegistration.saveAndConnect(
            serverId,
            save = { TakServerRegistration.SaveResult.SAVED },
            connect = { TAKConnectService.getInstance().registerServer(serverId) },
        )
    }

    @Test fun addWithoutNetworkTerminatesSpinnerWithConnectivityFailure() {
        register()
        assertFalse(TAKConnectService.serverRegistration.state.value.isLoading)
        assertEquals(TakFailureCategory.CONNECTIVITY, TAKConnectService.serverRegistration.state.value.failure?.category)
    }

    @Test fun unchangedEditAndRetryBothTerminateInsteadOfLeavingConnecting() {
        register()
        val existing = TAKConnectService.getTakServerDetail(serverId)
        repeat(2) {
            register()
            assertSame(existing, TAKConnectService.getTakServerDetail(serverId))
            assertFalse(TAKConnectService.serverRegistration.state.value.isLoading)
            assertEquals(TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status)
        }
    }

    @Test fun changedCredentialsReplaceRuntimeAndStillTerminateObservedRegistration() {
        register()
        val previous = TAKConnectService.getTakServerDetail(serverId)
        val observed = TAKConnectService.serverRegistration.state
        stored = stored.copy(password = "changed-password")
        register()
        val replacement = TAKConnectService.getTakServerDetail(serverId)
        assertNotSame(previous, replacement)
        assertTrue(previous!!.runtime.terminated)
        assertEquals("changed-password", replacement!!.password)
        assertFalse(observed.value.isLoading)
        assertEquals(TakServerRegistration.Status.FAILED, observed.value.status)
        assertSame(replacement, TAKConnectService.takServerRuntimeDetails.value.single())
    }

    @Test fun trustChangesReplaceSameServerRuntimeAndTerminateExistingConnection() {
        coEvery { repository.getApprovedServerCa(any(), any(), any()) } returns null
        register()
        val previous = TAKConnectService.getTakServerDetail(serverId)!!
        coEvery { repository.getApprovedServerCa(serverId, stored.address, stored.port) } returns "approved-test-ca"
        register()
        val approved = TAKConnectService.getTakServerDetail(serverId)!!
        assertTrue(previous.runtime.terminated)
        assertNotSame(previous, approved)
        assertEquals(serverId, approved.serverId)
        assertEquals("approved-test-ca", approved.approvedCa)
        coEvery { repository.getApprovedServerCa(any(), any(), any()) } returns null
        register()
        assertTrue(approved.runtime.terminated)
        assertNull(TAKConnectService.getTakServerDetail(serverId)!!.approvedCa)
    }

    @Test fun alreadyReconciledEditStillCompletesTheRequestedRegistration() {
        register()
        stored = stored.copy(password = "edited-password")
        TAKConnectService.getInstance().onMessageEvent(MessageEvent(MessageEvent.SERVER_CHANGED, 0))
        val reconciled = TAKConnectService.getTakServerDetail(serverId)
        assertEquals("edited-password", reconciled!!.password)
        register()
        assertSame(reconciled, TAKConnectService.getTakServerDetail(serverId))
        assertFalse(TAKConnectService.serverRegistration.state.value.isLoading)
        assertEquals(TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status)
    }

    @Test fun disabledServerDoesNotLeaveSpinnerEvenWhenNetworkExists() {
        stored = stored.copy(isEnabled = false)
        TAKConnectService.isNetworkConnected = true
        register()
        assertFalse(TAKConnectService.serverRegistration.state.value.isLoading)
        assertEquals(TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status)
    }

    @Test fun companionGateTerminatesRegistrationWithoutPretendingSuccess() {
        TAKConnectService.isNetworkConnected = true
        companionConnected.value = true
        register()
        assertFalse(TAKConnectService.serverRegistration.state.value.isLoading)
        assertEquals(TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status)
    }

    @Test fun config401ReplacesSpinnerAfterAddEditAndUnchangedRetry() = runBlocking {
        val failure = TakConnectionFailure(TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401)
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } throws TakEnrollmentException(failure)
        TAKConnectService.isNetworkConnected = true
        val observed = TAKConnectService.serverRegistration.state
        repeat(3) { attempt ->
            if (attempt == 1) stored = stored.copy(password = "edited-password")
            if (attempt == 2) TAKConnectService.retryServerRegistration() else register()
            val terminal = withTimeout(5_000) { observed.first { !it.isLoading } }
            assertEquals(TakServerRegistration.Status.FAILED, terminal.status)
            assertEquals(failure, terminal.failure)
            assertEquals(1, TAKConnectService.getTakServerDetail(serverId)!!.numConnAttempts)
        }
        verify(exactly = 3) { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) }
    }

    @Test fun repositoryCompanionGateAlsoTerminatesRegistration() {
        companionConnected.value = true
        TAKConnectService.isNetworkConnected = true
        register()
        assertEquals(TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status)
    }

    @Test fun duplicateEndpointTerminatesRegistrationWithoutConnecting() {
        val other = stored.copy(serverId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        coEvery { repository.tryAwaitTakServerList() } returns listOf(other, stored)
        TAKConnectService.isNetworkConnected = true
        register()
        assertEquals(TakServerRegistration.Status.DUPLICATE, TAKConnectService.serverRegistration.state.value.status)
        assertNull(TAKConnectService.getTakServerDetail(serverId)!!.runtime.currentAttempt)
    }

    @Test fun missingSavedServerTerminatesRegistration() {
        coEvery { repository.tryAwaitTakServerList() } returns emptyList()
        register()
        assertEquals(TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status)
    }

    @Test fun delayedRemovalAndToggleEventsReconcileByIdWithoutTouchingSurvivingAttempt() {
        val removed = stored.copy(serverId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            address = "removed.example.invalid")
        coEvery { repository.tryAwaitTakServerList() } returns listOf(removed, stored)
        register()
        val survivor = TAKConnectService.getTakServerDetail(serverId)!!
        val obsolete = TAKConnectService.getTakServerDetail(removed.serverId!!)!!
        val attempt = TAKConnectService.ConnectionAttempt(survivor.runtime.generation)
        survivor.runtime.currentAttempt = attempt
        survivor.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTING_COT_PORT)

        coEvery { repository.tryAwaitTakServerList() } answers { listOf(stored) }
        val service = TAKConnectService.getInstance()
        repeat(2) {
            service.onMessageEvent(MessageEvent(MessageEvent.SERVER_REMOVED, 0))
            service.onMessageEvent(MessageEvent(MessageEvent.SERVER_TOGGLED, 1))
        }

        assertSame(survivor, TAKConnectService.getTakServerDetail(serverId))
        assertSame(attempt, survivor.runtime.currentAttempt)
        assertFalse(survivor.runtime.terminated)
        assertTrue(survivor.isEnabled)
        assertTrue(obsolete.runtime.terminated)
        assertNull(TAKConnectService.getTakServerDetail(removed.serverId!!))
        assertSame(survivor, TAKConnectService.takServerRuntimeDetails.value.single())
    }

    @Test fun repeatedEnableEventsUseSavedIntentAndRestartFailedOrTerminatedRuntime() {
        register()
        val obsolete = TAKConnectService.getTakServerDetail(serverId)!!
        obsolete.runtime.terminated = true
        stored = stored.copy(isEnabled = false)
        val service = TAKConnectService.getInstance()
        service.onMessageEvent(MessageEvent(MessageEvent.SERVER_TOGGLED, 0))
        val disabled = TAKConnectService.getTakServerDetail(serverId)!!
        assertNotSame(obsolete, disabled)
        assertFalse(disabled.isEnabled)

        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.getClientCertificateAndConnect(any()) } returns Unit
        TAKConnectService.isNetworkConnected = true
        stored = stored.copy(isEnabled = true)
        service.onMessageEvent(MessageEvent(MessageEvent.SERVER_TOGGLED, 0))
        val enabled = TAKConnectService.getTakServerDetail(serverId)!!
        assertNotSame(disabled, enabled)
        assertTrue(enabled.isEnabled)
        assertFalse(enabled.runtime.terminated)
        enabled.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTION_FAILED)
        // A coalesced off/on pair or duplicate notification must not invert saved intent.
        service.onMessageEvent(MessageEvent(MessageEvent.SERVER_TOGGLED, 0))
        assertSame(enabled, TAKConnectService.getTakServerDetail(serverId))
        assertTrue(enabled.isEnabled)
        verify(exactly = 2) { TAKConnectService.getClientCertificateAndConnect(enabled) }
    }

    @Test fun concurrentRefreshReadsAreSerializedAndLatestConfigurationWins() = runBlocking {
        register()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { repository.tryAwaitTakServerList() } coAnswers {
            reads++
            val snapshot = listOf(stored)
            if (reads == 1) {
                readStarted.complete(Unit)
                releaseRead.await()
            }
            snapshot
        }
        val service = TAKConnectService.getInstance()
        val first = async(start = CoroutineStart.UNDISPATCHED) { service.registerServer(serverId) }
        readStarted.await()
        stored = stored.copy(password = "latest-password")
        val second = async(start = CoroutineStart.UNDISPATCHED) { service.registerServer(serverId) }
        try {
            assertEquals("Only one refresh may read/reconcile at a time", 1, reads)
        } finally {
            releaseRead.complete(Unit)
        }
        withTimeout(5_000) { first.await(); second.await() }
        assertEquals(2, reads)
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        assertEquals("latest-password", detail.password)
        assertFalse(detail.runtime.terminated)
        assertSame(detail, TAKConnectService.takServerRuntimeDetails.value.single())
    }

    @Test fun cancelledEnrollmentTerminatesRegistrationAndPreservesCancellation() = runBlocking {
        mockEnrollment()
        every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } throws CancellationException("Stopped")
        register()
        val job = TAKConnectService.getTakServerDetail(serverId)!!.runtime.currentAttempt!!.job!!
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status)
    }

    @Test fun invalidCertificateTerminatesRegistrationWithCertificateFailure() = runBlocking {
        stored = stored.copy(isP12Cert = true, p12Cert = "not-base64")
        TAKConnectService.isNetworkConnected = true
        register()
        val terminal = withTimeout(5_000) {
            TAKConnectService.serverRegistration.state.first { !it.isLoading }
        }
        assertEquals(TakServerRegistration.Status.FAILED, terminal.status)
        assertEquals(TakFailureCategory.CERTIFICATE, terminal.failure?.category)
    }

    @Test fun successfulHandshakePublishesConnectedRegistration() = runBlocking {
        mockEnrollment()
        // Keep post-connect REST loading outside this handshake-only test.
        every { repository.updateTakServerChannels(any()) } throws CancellationException("Channels not under test")
        every { repository.updateTakMissionLoading(any(), any(), any()) } throws CancellationException("Missions not under test")
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } answers {
            firstArg<KeyStore>().load(null, null)
            true
        }
        every { TAKConnectService.findRootInChain(any()) } returns null
        mockkStatic(SSLContext::class)
        val sslContext = mockk<SSLContext>(relaxed = true)
        every { SSLContext.getInstance("TLSv1.3") } returns sslContext
        val socketFactory = mockk<SSLSocketFactory>()
        every { sslContext.socketFactory } returns socketFactory
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socketFactory.createSocket(stored.address, stored.port) } returns socket
        every { socket.inputStream } answers {
            TAKConnectService.isNetworkConnected = false
            ByteArrayInputStream(byteArrayOf())
        }

        register()
        val terminal = withTimeout(5_000) {
            TAKConnectService.serverRegistration.state.first { !it.isLoading }
        }
        assertEquals(TakServerRegistration.Status.CONNECTED, terminal.status)
        assertNull(terminal.failure)
        verify(exactly = 1) { socket.startHandshake() }
        verify(exactly = 0) { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) }
    }

    private fun mockEnrollment() {
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        TAKConnectService.isNetworkConnected = true
    }
}
