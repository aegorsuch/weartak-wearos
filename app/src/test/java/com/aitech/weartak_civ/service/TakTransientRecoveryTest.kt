package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import com.aitech.weartak_civ.data.takserver.TakEnrollmentException
import com.aitech.weartak_civ.data.takserver.TakEnrollmentStage
import com.aitech.weartak_civ.data.takserver.TakFailureCategory
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TakTransientRecoveryTest {
    private val companion = MutableStateFlow(false)
    private val scopeField = TAKConnectService::class.java.getDeclaredField("connectionScope").apply { isAccessible = true }
    private var oldScope: Any? = null
    private var oldStopping = false
    private var oldNetwork = false
    private var oldDetails = mutableListOf<TAKConnectService.TakServerDetail>()

    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = mockk<MainActivity>(relaxed = true) {
            every { applicationContext } returns app
            every { getExternalFilesDir(any()) } answers { app.getExternalFilesDir(firstArg()) }
        }
        MainActivity.dataRepository = mockk<DataRepository>(relaxed = true) {
            every { isCompanionConnected } returns companion
            every { shouldSuppressWatchTransportNow() } answers { companion.value }
            coEvery { getApprovedServerCa(any(), any(), any()) } returns null
        }
        oldScope = scopeField.get(null)
        oldStopping = TAKConnectService.serviceStopping
        oldNetwork = TAKConnectService.isNetworkConnected
        oldDetails = TAKConnectService.takServerDetails
        TAKConnectService.serviceStopping = false
        TAKConnectService.isNetworkConnected = true
        TAKConnectService.takServerDetails = mutableListOf()
    }

    @After fun cleanup() = kotlinx.coroutines.runBlocking {
        val scope = scopeField.get(null) as kotlinx.coroutines.CoroutineScope
        val children = scope.coroutineContext[Job]?.children?.toList().orEmpty()
        children.forEach { it.cancel() }
        kotlinx.coroutines.withTimeout(5_000) { children.forEach { it.join() } }
        scopeField.set(null, oldScope)
        TAKConnectService.serviceStopping = oldStopping
        TAKConnectService.isNetworkConnected = oldNetwork
        TAKConnectService.takServerDetails = oldDetails
        unmockkAll()
    }

    @Test fun classificationRetriesOnlyIoWithoutCredentialTrustOrCancellationCauses() {
        listOf(IOException(), ConnectException(), SocketException(), SocketTimeoutException(), UnknownHostException())
            .forEach { assertTrue(it.javaClass.name, TakTransientRecovery.isRetryable(it)) }
        val terminal = listOf(SSLException("tls"), SSLHandshakeException("handshake"),
            SSLPeerUnverifiedException("hostname"), CertificateException(), SecurityException(),
            CancellationException(), IllegalArgumentException(),
            TakEnrollmentException(TakConnectionFailure(TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401)),
            TakEnrollmentException(TakConnectionFailure(TakEnrollmentStage.SIGN, TakFailureCategory.HTTP, 500)))
        terminal.forEach {
            assertFalse(it.javaClass.name, TakTransientRecovery.isRetryable(it))
            assertFalse(TakTransientRecovery.isRetryable(IOException("wrapped", it)))
        }
        assertTrue(TakTransientRecovery.isRetryable(RuntimeException(ConnectException())))
        assertTrue(TakTransientRecovery.isRetryable(UnknownHostException().apply {
            initCause(RuntimeException("native resolver failure"))
        }))
        val cycle = IOException()
        cycle.initCause(IOException(cycle))
        assertFalse(TakTransientRecovery.isRetryable(cycle))
    }

    @Test fun failureBackoffStartsAtExistingFiveSecondsAndCapsAtTwoMinutes() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L),
            (1..7).map(TakTransientRecovery::delayMs))
        assertEquals(120_000L, TakTransientRecovery.delayMs(Int.MAX_VALUE))
    }

    @Test fun repeatedFailureCallbacksOwnOneRetryAndDoNotChangeFailedUiOrCertificate() = runTest {
        scopeField.set(null, this)
        val detail = detail()
        val attempt = current(detail)
        val originalCertificate = detail.p12Cert
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.getClientCertificateAndConnect(detail) } just Runs
        repeat(50) { TAKConnectService.recoverTransientFailure(detail, attempt, ConnectException()) }
        val retry = detail.runtime.retryJob
        assertNotNull(retry)
        assertEquals(1, detail.runtime.transientFailureStreak)
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, detail.connectionStatus.get())
        assertEquals(originalCertificate, detail.p12Cert)
        runCurrent()
        advanceTimeBy(4_999)
        verify(exactly = 0) { TAKConnectService.getClientCertificateAndConnect(detail) }
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 1) { TAKConnectService.getClientCertificateAndConnect(detail) }
        assertNull(detail.runtime.retryJob)
        assertTrue(retry!!.isCompleted)
    }

    @Test fun everyGateBlocksSchedulingOrExecutionAndStaleAttemptsCannotRetry() = runTest {
        scopeField.set(null, this)
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.getClientCertificateAndConnect(any()) } just Runs
        for (gate in 0..6) {
            val detail = detail()
            val attempt = current(detail)
            TAKConnectService.recoverTransientFailure(detail, attempt, ConnectException())
            assertNotNull(detail.runtime.retryJob)
            when (gate) {
                0 -> detail.isEnabled = false
                1 -> companion.value = true
                2 -> TAKConnectService.isNetworkConnected = false
                3 -> TAKConnectService.serviceStopping = true
                4 -> detail.runtime.terminated = true
                5 -> detail.duplicateEndpointSuppressed = true
                6 -> detail.runtime.generation++
            }
            advanceUntilIdle()
            assertNull(detail.runtime.retryJob)
            TAKConnectService.recoverTransientFailure(detail, attempt, ConnectException())
            assertNull(detail.runtime.retryJob)
            companion.value = false
            TAKConnectService.isNetworkConnected = true
            TAKConnectService.serviceStopping = false
        }
        verify(exactly = 0) { TAKConnectService.getClientCertificateAndConnect(any()) }
    }

    @Test fun invalidateCancelsPendingJobAndResetsFailureStreak() = runTest {
        scopeField.set(null, this)
        val detail = detail()
        val attempt = current(detail)
        TAKConnectService.recoverTransientFailure(detail, attempt, ConnectException())
        val retry = detail.runtime.retryJob!!
        TAKConnectService.Companion::class.java.getDeclaredMethod("invalidateConnection",
            TAKConnectService.TakServerDetail::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(TAKConnectService.Companion, detail, true, false)
        assertTrue(retry.isCancelled)
        assertNull(detail.runtime.retryJob)
        assertEquals(0, detail.runtime.transientFailureStreak)
        advanceUntilIdle()
        assertNull(detail.runtime.currentAttempt)
    }

    @Test fun enrollmentIoContinuesBeyondOldAttemptLimitWithoutDeletingLoadedCertificate() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns true
        every { TAKConnectService.connectTAKServer(detail, any()) } throws ConnectException()
        every { TAKConnectService.deleteFile(any()) } just Runs
        TAKConnectService.takServerDetails = mutableListOf(detail)
        TAKConnectService.getClientCertificateAndConnect(detail)
        runCurrent()
        for (delay in listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L)) {
            assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, detail.connectionStatus.get())
            assertNotNull(detail.runtime.retryJob)
            assertFalse(TAKConnectService.isAnyTakConnecting)
            advanceTimeBy(delay)
            runCurrent()
        }
        assertEquals(6, detail.numConnAttempts)
        assertEquals(6, detail.runtime.transientFailureStreak)
        verify(exactly = 6) { TAKConnectService.loadKeyStore(any(), any(), any()) }
        verify(exactly = 0) { TAKConnectService.deleteFile(any()) }
        detail.runtime.retryJob!!.cancel()
    }

    @Test fun firstConfigIoRetriesButAuthenticationAndTlsRemainActionableWithoutRetry() = runTest {
        scopeField.set(null, this)
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        every { TAKConnectService.deleteFile(any()) } just Runs
        for (error in listOf(ConnectException(), SSLHandshakeException("trust"),
            TakEnrollmentException(TakConnectionFailure(TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401)))) {
            val detail = detail().copy(isP12Cert = false, p12Cert = null)
            every { TAKConnectService.fetchAndParseCertificateConfig(detail, any()) } throws error
            TAKConnectService.getClientCertificateAndConnect(detail)
            runCurrent()
            assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, detail.connectionStatus.get())
            assertEquals(error is ConnectException, detail.runtime.retryJob != null)
            detail.runtime.retryJob?.cancel()
        }
    }

    @Test fun concurrentFailureCallbacksScheduleOnlyOneJob() = runTest {
        scopeField.set(null, this)
        val detail = detail()
        val attempt = current(detail)
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.getClientCertificateAndConnect(detail) } just Runs
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..50).map {
                executor.submit { TAKConnectService.recoverTransientFailure(detail, attempt, ConnectException()) }
            }
            tasks.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, detail.runtime.transientFailureStreak)
        advanceUntilIdle()
        verify(exactly = 1) { TAKConnectService.getClientCertificateAndConnect(detail) }
    }

    @Test fun freshAttemptCancelsOldRetryAndConnectedRequestsDoNotResetStatus() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        val old = current(detail)
        TAKConnectService.recoverTransientFailure(detail, old, ConnectException())
        val retry = detail.runtime.retryJob!!
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } throws
            TakEnrollmentException(TakConnectionFailure(TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401))
        TAKConnectService.getClientCertificateAndConnect(detail)
        assertTrue(retry.isCancelled)
        advanceUntilIdle()
        verify(exactly = 1) { TAKConnectService.fetchAndParseCertificateConfig(detail, any()) }
        TAKConnectService.recoverTransientFailure(detail, old, ConnectException())
        assertNull(detail.runtime.retryJob)
        val current = detail.runtime.currentAttempt
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
        TAKConnectService.getClientCertificateAndConnect(detail)
        assertSame(current, detail.runtime.currentAttempt)
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT, detail.connectionStatus.get())
        verify(exactly = 1) { TAKConnectService.fetchAndParseCertificateConfig(detail, any()) }
    }

    @Test fun cancelledScopeCannotStartPendingRetry() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scopeField.set(null, scope)
        val detail = detail()
        TAKConnectService.recoverTransientFailure(detail, current(detail), ConnectException())
        val retry = detail.runtime.retryJob!!
        scope.cancel()
        advanceUntilIdle()
        assertTrue(retry.isCancelled)
        assertNull(detail.runtime.retryJob)
    }

    @Test fun csrTransportFailurePropagatesWithoutBecomingNull() {
        mockkObject(TAKConnectService.Companion, recordPrivateCalls = true)
        every { TAKConnectService.Companion["postCsrAndGetResponse"](
            any<String>(), any<String>(), any<String>(), any<String>(),
            isNull<String>(),
            isNull<com.aitech.weartak_civ.data.takserver.TakHttpSession>()) } throws ConnectException()
        assertThrows(ConnectException::class.java) {
            TAKConnectService.postCsrAndParse(detail(), "test-csr")
        }
    }

    @Test fun actualSocketFailureUsesBackoffAndSuccessfulHandshakeResetsIt() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        TAKConnectService.takServerDetails = mutableListOf(detail)
        val socket = mockTlsSocket()
        every { socket.startHandshake() } throws ConnectException()
        TAKConnectService.getClientCertificateAndConnect(detail)
        runCurrent()
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, detail.connectionStatus.get())
        assertEquals(1, detail.runtime.transientFailureStreak)
        verify(exactly = 1) { socket.startHandshake() }
        advanceTimeBy(4_999)
        verify(exactly = 1) { socket.startHandshake() }
        every { socket.startHandshake() } just Runs
        advanceTimeBy(1)
        runCurrent()
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT, detail.connectionStatus.get())
        assertEquals(0, detail.runtime.transientFailureStreak)
        assertEquals(0, detail.numConnAttempts)
        assertNull(detail.runtime.retryJob)
        verify(exactly = 2) { socket.startHandshake() }
        verify { socket.soTimeout = 15_000; socket.soTimeout = 0 }
        verify(exactly = 0) { TAKConnectService.deleteFile(any()) }
    }

    @Test fun actualTrustFailureRemainsFailedWithoutRetryOrCertificateDeletion() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        val socket = mockTlsSocket()
        every { socket.startHandshake() } throws SSLHandshakeException("test trust rejection").apply {
            initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."))
        }
        TAKConnectService.getClientCertificateAndConnect(detail)
        advanceUntilIdle()
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, detail.connectionStatus.get())
        assertNull(detail.runtime.retryJob)
        verify(exactly = 1) { socket.startHandshake() }
        verify(exactly = 0) { TAKConnectService.deleteFile(any()) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(detail.connectionFailureLiveData.value!!.untrustedServerCa)
    }

    @Test fun stalledHandshakeHasReadDeadlineAndRetriesWithoutDeletingCertificate() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        val socket = mockTlsSocket()
        every { socket.startHandshake() } throws SocketTimeoutException("TLS handshake timed out")
        TAKConnectService.getClientCertificateAndConnect(detail)
        runCurrent()
        verify { socket.soTimeout = 15_000 }
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, detail.connectionStatus.get())
        assertNotNull(detail.runtime.retryJob)
        advanceTimeBy(5_000)
        runCurrent()
        verify(exactly = 2) { socket.startHandshake() }
        verify(exactly = 0) { TAKConnectService.deleteFile(any()) }
        detail.runtime.retryJob!!.cancel()
    }

    @Test fun staleConnectingStatusCannotBlockAnExplicitReconnect() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        val stale = current(detail)
        val raw = mockk<java.net.Socket>(relaxed = true)
        stale.rawSocket = raw
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTING_COT_PORT)
        detail.runtime.generation++
        val socket = mockTlsSocket()
        TAKConnectService.getClientCertificateAndConnect(detail)
        runCurrent()
        assertNotSame(stale, detail.runtime.currentAttempt)
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT, detail.connectionStatus.get())
        verify(exactly = 1) { socket.startHandshake() }
        verify { raw.close() }
    }

    @Test fun supersededHandshakeFailureIsFencedAndReplacementOwnsTheRetry() = runTest {
        scopeField.set(null, this)
        var stored = DataRepository.TakServerItem(
            true, "Test", "recovery.invalid", 8089, false, "user", "old-password", "", "",
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )
        coEvery { MainActivity.dataRepository.tryAwaitTakServerList() } answers { listOf(stored) }
        every { MainActivity.dataRepository.takChannels } returns MutableStateFlow(emptyList())
        val service = TAKConnectService.getInstance()
        TAKConnectService.isNetworkConnected = false
        service.registerServer(stored.serverId!!)
        val obsolete = TAKConnectService.getTakServerDetail(stored.serverId!!)!!
        val socket = mockTlsSocket()
        var handshakes = 0
        every { socket.startHandshake() } answers {
            if (++handshakes == 1) {
                stored = stored.copy(password = "new-password")
                service.onMessageEvent(com.aitech.weartak_civ.events.MessageEvent(
                    com.aitech.weartak_civ.events.MessageEvent.SERVER_CHANGED, 0))
                throw SocketException("Socket closed by configuration replacement")
            }
            throw ConnectException("Replacement transport failure")
        }
        TAKConnectService.isNetworkConnected = true
        TAKConnectService.getClientCertificateAndConnect(obsolete)
        runCurrent()
        val replacement = TAKConnectService.getTakServerDetail(stored.serverId!!)!!
        assertNotSame(obsolete, replacement)
        assertTrue(obsolete.runtime.terminated)
        assertNull(obsolete.runtime.currentAttempt)
        assertNull(obsolete.runtime.retryJob)
        assertEquals("new-password", replacement.password)
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, replacement.connectionStatus.get())
        assertNotNull(replacement.runtime.retryJob)
        advanceTimeBy(5_000)
        runCurrent()
        verify(exactly = 3) { socket.startHandshake() }
        replacement.runtime.retryJob!!.cancel()
    }

    @Test fun sideloadedCertificateDoesNotTurnUnchangedReloadIntoHandshakeReplacement() = runTest {
        scopeField.set(null, this)
        val stored = DataRepository.TakServerItem(
            true, "Sideload", "sideload-recovery.invalid", 8089, false, "user", "password", "", "",
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        )
        coEvery { MainActivity.dataRepository.tryAwaitTakServerList() } returns listOf(stored)
        every { MainActivity.dataRepository.takChannels } returns MutableStateFlow(emptyList())
        val service = TAKConnectService.getInstance()
        TAKConnectService.isNetworkConnected = false
        service.registerServer(stored.serverId!!)
        val detail = TAKConnectService.getTakServerDetail(stored.serverId!!)!!
        val certDir = java.io.File(RuntimeEnvironment.getApplication().getExternalFilesDir(null), "certs")
        certDir.mkdirs()
        val certificate = java.io.File(certDir, "${stored.address}.p12")
        try {
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            certificate.outputStream().use { keyStore.store(it, "atakatak".toCharArray()) }
            mockkObject(TAKConnectService.Companion)
            every { TAKConnectService.connectTAKServer(any(), any()) } returns Unit
            TAKConnectService.isNetworkConnected = true
            TAKConnectService.getClientCertificateAndConnect(detail)
            runCurrent()
            val attempt = detail.runtime.currentAttempt
            assertNotNull(attempt)
            assertEquals("atakatak", detail.runtime.sideloadedP12Password)
            assertFalse(detail.isP12Cert)
            assertEquals("", detail.p12CertPassword)
            service.onMessageEvent(com.aitech.weartak_civ.events.MessageEvent(
                com.aitech.weartak_civ.events.MessageEvent.SERVER_CHANGED, 0))
            assertSame(detail, TAKConnectService.getTakServerDetail(stored.serverId!!))
            assertSame(attempt, detail.runtime.currentAttempt)
            assertFalse(detail.runtime.terminated)
            verify(exactly = 1) { TAKConnectService.connectTAKServer(detail, any()) }
        } finally {
            certificate.delete()
        }
    }

    @Test fun selectedNetworkBindsTcpDnsAndTlsToTheSameRoute() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        val socket = mockTlsSocket()
        val factory = SSLContext.getInstance("TLSv1.3").socketFactory
        val network = mockk<android.net.Network>()
        val sockets = mockk<javax.net.SocketFactory>()
        val raw = mockk<java.net.Socket>(relaxed = true)
        val address = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        every { network.socketFactory } returns sockets
        every { sockets.createSocket() } returns raw
        every { network.getAllByName(detail.address) } returns arrayOf(address)
        every { factory.createSocket(raw, detail.address, detail.port, true) } returns socket
        TAKConnectService.getClientCertificateAndConnect(detail)
        detail.runtime.currentAttempt!!.httpSession =
            com.aitech.weartak_civ.data.takserver.TakHttpSession(network) { true }
        runCurrent()
        verify(exactly = 1) { network.getAllByName(detail.address) }
        verify { raw.connect(java.net.InetSocketAddress(address, detail.port), 15_000) }
        verify { factory.createSocket(raw, detail.address, detail.port, true); socket.startHandshake() }
        verify(exactly = 0) { factory.createSocket(any<String>(), any<Int>()) }
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT, detail.connectionStatus.get())
    }

    @Test fun selectedRouteDnsFailureClosesRawSocketWithoutDefaultDnsFallback() = runTest {
        scopeField.set(null, this)
        val detail = detail().copy(isP12Cert = false, p12Cert = null)
        val socket = mockTlsSocket()
        val network = mockk<android.net.Network>()
        val sockets = mockk<javax.net.SocketFactory>()
        val raw = mockk<java.net.Socket>(relaxed = true)
        every { network.socketFactory } returns sockets
        every { sockets.createSocket() } returns raw
        every { network.getAllByName(detail.address) } throws java.net.UnknownHostException()
        TAKConnectService.getClientCertificateAndConnect(detail)
        detail.runtime.currentAttempt!!.httpSession =
            com.aitech.weartak_civ.data.takserver.TakHttpSession(network) { true }
        runCurrent()
        verify { raw.close() }
        verify(exactly = 0) { socket.startHandshake() }
        detail.runtime.retryJob?.cancel()
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTION_FAILED, detail.connectionStatus.get())
    }

    private fun mockTlsSocket(): SSLSocket {
        mockkObject(TAKConnectService.Companion, recordPrivateCalls = true)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } answers {
            firstArg<KeyStore>().load(null, null)
            true
        }
        every { TAKConnectService.findRootInChain(any()) } returns null
        every { TAKConnectService.deleteFile(any()) } just Runs
        every { TAKConnectService.Companion["loadTakChannelsAfterConnect"](
            any<TAKConnectService.TakServerDetail>()) } returns Unit
        every { TAKConnectService.Companion["loadTakMissionsAfterConnect"](
            any<TAKConnectService.TakServerDetail>(), any<TAKConnectService.ConnectionAttempt>()) } returns Unit
        every { TAKConnectService.Companion["startCotReader"](
            any<TAKConnectService.TakServerDetail>(), any<SSLSocket>(),
            any<TAKConnectService.ConnectionAttempt>()) } returns Unit
        mockkStatic(SSLContext::class)
        val ssl = mockk<SSLContext>(relaxed = true)
        every { SSLContext.getInstance("TLSv1.3") } returns ssl
        val factory = mockk<SSLSocketFactory>()
        every { ssl.socketFactory } returns factory
        val socket = mockk<SSLSocket>(relaxed = true)
        every { factory.createSocket(any<String>(), any<Int>()) } returns socket
        return socket
    }

    private fun current(detail: TAKConnectService.TakServerDetail): TAKConnectService.ConnectionAttempt {
        val attempt = TAKConnectService.ConnectionAttempt(1)
        detail.runtime.generation = 1
        detail.runtime.currentAttempt = attempt
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTION_FAILED)
        return attempt
    }

    private fun detail() = TAKConnectService.TakServerDetail(
        "recovery-test", true, "Test", "recovery.invalid", 8089, true, "", "", "preserved-cert", ""
    )
}
