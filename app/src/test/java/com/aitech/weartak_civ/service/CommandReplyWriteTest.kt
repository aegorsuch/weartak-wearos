package com.aitech.weartak_civ.service

import android.app.Application
import android.net.Network
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CommandReplyWriteTest {
    private var previousStopping = false
    private var previousEnabled = true
    private var previousMulticast = true
    private var previousNetwork = false
    private lateinit var socket: SSLSocket
    private lateinit var detail: TAKConnectService.TakServerDetail

    @Before fun setup() {
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.applicationContext } returns RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = activity
        previousStopping = TAKConnectService.serviceStopping
        previousEnabled = TAKConnectService.takEndpointEnabled
        previousMulticast = MainActivity.enableMulticastOnWifi
        previousNetwork = TAKConnectService.isNetworkConnected
        TAKConnectService.isNetworkConnected = false
        TAKConnectService.serviceStopping = false
        TAKConnectService.takEndpointEnabled = true
        MainActivity.enableMulticastOnWifi = true
        MainActivity.dataRepository = mockk<DataRepository> {
            every { isCompanionConnected } returns MutableStateFlow(false)
            every { shouldSuppressWatchTransportNow() } returns false
        }
        socket = mockk {
            every { isConnected } returns true
            every { isClosed } returns false
            every { close() } just Runs
        }
        detail = TAKConnectService.TakServerDetail("server", true, "test", "tak.invalid",
            8089, false, "", "", null, null)
        detail.runtime.generation = 7
        detail.runtime.currentAttempt = TAKConnectService.ConnectionAttempt(7).apply { socket = this@CommandReplyWriteTest.socket }
        detail.secureSocket = socket
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
    }

    @After fun cleanup() {
        NetworkUtils.MulticastPublisher.stop()
        TAKConnectService.serviceStopping = previousStopping
        TAKConnectService.takEndpointEnabled = previousEnabled
        MainActivity.enableMulticastOnWifi = previousMulticast
        TAKConnectService.isNetworkConnected = previousNetwork
        unmockkAll()
    }

    @Test fun takTrueRequiresActualWriteAndFlushBeforeReturning() = runBlocking {
        val bytes = ByteArrayOutputStream()
        var flushed = false
        every { socket.outputStream } returns object : OutputStream() {
            override fun write(b: Int) { bytes.write(b) }
            override fun flush() { flushed = true }
        }
        assertTrue(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        assertEquals("<warning/>", bytes.toString("UTF-8"))
        assertTrue(flushed)
    }

    @Test fun failedWriteOrFlushNeverReturnsSuccess() = runBlocking {
        for (failOnFlush in listOf(false, true)) {
            detail.secureSocket = socket
            detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
            var wrote = false
            every { socket.outputStream } returns object : OutputStream() {
                override fun write(b: Int) {
                    wrote = true
                    if (!failOnFlush) throw IOException("write failure")
                }
                override fun flush() { throw IOException("flush failure") }
            }
            assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
            assertTrue(wrote)
        }
    }

    @Test fun unavailableOrStaleTakSessionCannotReportWriteAccepted() = runBlocking {
        detail.runtime.generation++
        assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        detail.runtime.generation--
        every { socket.isClosed } returns true
        assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        every { socket.isClosed } returns false
        TAKConnectService.takEndpointEnabled = false
        assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        verify(exactly = 0) { socket.outputStream }
    }

    @Test fun medicalGateIsRecheckedInsideOutputQueueAndNewSessionIsNotUsed() = runBlocking {
        val bytes = ByteArrayOutputStream()
        every { socket.outputStream } returns bytes
        val attempt = detail.runtime.currentAttempt!!
        var consent = true
        attempt.outputMutex.lock()
        val pending = async(Dispatchers.Default) {
            TAKConnectService.writeCommandReply(detail, "<synthetic-patient/>", beforeWrite = { consent })
        }
        delay(100)
        consent = false
        attempt.outputMutex.unlock()
        assertFalse(pending.await())
        assertEquals(0, bytes.size())
        assertFalse(TAKConnectService.writeCommandReply(detail, "<synthetic-patient/>", beforeWrite = {
            detail.runtime.generation++
            true
        }))
        assertEquals(0, bytes.size())
    }

    @Test fun patientWritesOnlyEnabledConnectedNonduplicateTakStreamsAndHonorsGate() = runBlocking {
        mockkObject(LicenseManager)
        every { LicenseManager.isLicenseValid() } returns true
        TAKConnectService.isNetworkConnected = true
        val previous = TAKConnectService.takServerDetails
        val outputs = linkedMapOf<String, ByteArrayOutputStream>()
        fun server(id: String): TAKConnectService.TakServerDetail {
            val output = ByteArrayOutputStream().also { outputs[id] = it }
            val sessionSocket = mockk<SSLSocket> {
                every { isClosed } returns false
                every { isConnected } returns true
                every { outputStream } returns output
            }
            return TAKConnectService.TakServerDetail(id, true, id, "synthetic.invalid",
                8089, false, "", "", null, null).apply {
                runtime.generation = 1
                runtime.currentAttempt = TAKConnectService.ConnectionAttempt(1).apply { socket = sessionSocket }
                secureSocket = sessionSocket
                connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
            }
        }
        try {
            TAKConnectService.takServerDetails = mutableListOf(
                server("first"), server("second"),
                server("disabled").apply { isEnabled = false },
                server("duplicate").apply { duplicateEndpointSuppressed = true },
                server("disconnected").apply { connectionStatus.set(TAKConnectService.TAK_SERVER_DISCONNECTED) }
            )
            assertEquals(BatdokTakWrite(2, 2), TAKConnectService.sendBatdokPatient("<synthetic-patient/>") { true })
            assertEquals("<synthetic-patient/>", outputs.getValue("first").toString("UTF-8"))
            assertEquals("<synthetic-patient/>", outputs.getValue("second").toString("UTF-8"))
            listOf("disabled", "duplicate", "disconnected").forEach { assertEquals(0, outputs.getValue(it).size()) }
            outputs.values.forEach { it.reset() }
            assertEquals(BatdokTakWrite(0, 2), TAKConnectService.sendBatdokPatient("<revoked/>") { false })
            assertTrue(outputs.values.all { it.size() == 0 })
            TAKConnectService.takEndpointEnabled = false
            assertEquals(BatdokTakWrite(0, 0, "tak_endpoint_off"),
                TAKConnectService.sendBatdokPatient("<disabled-endpoint/>") { true })
            assertTrue(outputs.values.all { it.size() == 0 })
            TAKConnectService.takEndpointEnabled = true
            TAKConnectService.isNetworkConnected = false
            assertEquals(BatdokTakWrite(0, 0, "network"), TAKConnectService.sendBatdokPatient("<offline/>") { true })
            assertTrue(outputs.values.all { it.size() == 0 })
        } finally {
            TAKConnectService.takServerDetails = previous
        }
    }

    @Test fun normalAndConfirmedWritesShareOneConnectionSerializer() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        var flushes = 0
        every { socket.outputStream } returns object : OutputStream() {
            override fun write(b: Int) = bytes.write(b)
            override fun write(b: ByteArray) {
                bytes.write(b[0].toInt())
                if (b.toString(Charsets.UTF_8) == "normal") {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                bytes.write(b, 1, b.size - 1)
            }
            override fun flush() { flushes++ }
        }
        try {
            TAKConnectService.sendData(detail, "normal", true, 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val warning = async(Dispatchers.Default) { TAKConnectService.writeCommandReply(detail, "warning") }
            delay(100)
            assertFalse(warning.isCompleted)
            assertEquals("n", bytes.toString("UTF-8"))
            release.countDown()
            assertTrue(warning.await())
            assertEquals("normalwarning", bytes.toString("UTF-8"))
            assertEquals(2, flushes)
        } finally {
            release.countDown()
        }
    }

    @Test fun blockedWriteDoesNotHoldLifecycleMonitorAndTimeoutClosesOnlyItsSocket() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { socket.close() } answers { closed.countDown(); release.countDown() }
        every { socket.outputStream } returns object : OutputStream() {
            override fun write(b: Int) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                throw IOException("closed")
            }
        }
        try {
            val warning = async(Dispatchers.Default) { TAKConnectService.writeCommandReply(detail, "warning", 500) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val replacement = mockk<SSLSocket>(relaxed = true)
            withTimeout(1_000) {
                withContext(Dispatchers.Default) {
                    synchronized(detail.runtime.lock) {
                        detail.runtime.generation++
                        detail.runtime.currentAttempt = TAKConnectService.ConnectionAttempt(8).apply { socket = replacement }
                        detail.secureSocket = replacement
                    }
                }
            }
            assertFalse(withTimeout(1_500) { warning.await() })
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertSame(replacement, detail.secureSocket)
            verify(exactly = 0) { replacement.close() }
        } finally {
            release.countDown()
        }
    }

    @Test fun cancellationOfBlockedWriteReturnsPromptlyAndNeverReportsAccepted() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        every { socket.close() } answers { closed.countDown() }
        every { socket.outputStream } returns object : OutputStream() {
            override fun write(b: Int) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            override fun flush() { finished.countDown() }
        }
        var accepted = false
        try {
            val warning = launch(Dispatchers.Default) {
                accepted = TAKConnectService.writeCommandReply(detail, "warning")
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(1_000) { warning.cancelAndJoin() }
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertFalse(accepted)
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun cancelledQueuedWriteDoesNotWriteAndStaleQueuedAttemptIsRevalidated() = runBlocking {
        val attempt = detail.runtime.currentAttempt!!
        val bytes = ByteArrayOutputStream()
        every { socket.outputStream } returns bytes
        attempt.outputMutex.lock()
        try {
            assertFalse(TAKConnectService.writeCommandReply(detail, "timed out in queue", 100))
            val cancelled = async(Dispatchers.Default) { TAKConnectService.writeCommandReply(detail, "cancelled") }
            delay(100)
            withTimeout(1_000) { cancelled.cancelAndJoin() }
            val stale = async(Dispatchers.Default) { TAKConnectService.writeCommandReply(detail, "stale") }
            delay(100)
            synchronized(detail.runtime.lock) { detail.runtime.generation++ }
            attempt.outputMutex.unlock()
            assertFalse(stale.await())
            assertEquals(0, bytes.size())
            verify(exactly = 0) { socket.close() }
        } finally {
            if (attempt.outputMutex.isLocked) attempt.outputMutex.unlock()
        }
    }

    @Test fun multicastTrueRequiresDatagramSendAndSendFailurePropagates() {
        mockkConstructor(MulticastSocket::class)
        every { anyConstructed<MulticastSocket>().reuseAddress = any() } just Runs
        every { anyConstructed<MulticastSocket>().timeToLive = any() } just Runs
        every { anyConstructed<MulticastSocket>().bind(any()) } just Runs
        every { anyConstructed<MulticastSocket>().close() } just Runs
        val network = mockk<Network>(relaxed = true)
        assertTrue(NetworkUtils.MulticastPublisher.start(network, "239.2.3.1", 6969))
        var sent = false
        every { anyConstructed<MulticastSocket>().send(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            assertEquals(6969, packet.port)
            assertEquals("239.2.3.1", packet.address.hostAddress)
            sent = true
        }
        assertTrue(NetworkUtils.MulticastPublisher.sendCommandReply("<warning/>", "239.2.3.1", 6969))
        assertTrue(sent)
        every { anyConstructed<MulticastSocket>().send(any()) } throws IOException("send failed")
        assertThrows(IOException::class.java) {
            NetworkUtils.MulticastPublisher.sendCommandReply("<warning/>", "239.2.3.1", 6969)
        }
        NetworkUtils.MulticastPublisher.stop()
        assertFalse(NetworkUtils.MulticastPublisher.sendCommandReply("<warning/>", "239.2.3.1", 6969))
    }
}
