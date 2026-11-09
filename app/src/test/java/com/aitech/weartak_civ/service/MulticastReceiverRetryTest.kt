package com.aitech.weartak_civ.service

import android.app.Application
import android.net.Network
import com.aitech.weartak_civ.TakMulticastEndpoints
import com.aitech.weartak_civ.WifiMulticastReceiver
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MulticastReceiverRetryTest {
    private val scheduler = TestCoroutineScheduler()
    private val configs = Channel<TAKConnectService.MulticastConfig>(Channel.UNLIMITED)
    private val publishers = Channel<Pair<TAKConnectService.MulticastConfig, Boolean>>(Channel.UNLIMITED)
    private val receiving = Channel<Pair<MulticastSocket, Pair<*, *>>>(Channel.UNLIMITED)
    private val closed = AtomicInteger()
    private val enabled = TAKConnectService.MulticastConfig(mockk<Network>(), true, "239.2.3.1", 6969)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        MainActivity.mainActivity = mockk {
            every { applicationContext } returns RuntimeEnvironment.getApplication()
        }
        ShadowToast.reset()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun activeSource(): Pair<*, *>? =
        TAKConnectService::class.java.getDeclaredField("activeMulticastSource").let {
            it.isAccessible = true
            it.get(null) as Pair<*, *>?
        }

    private fun receiver(failRead: Boolean = false): WifiMulticastReceiver {
        val announced = AtomicBoolean()
        val socket = object : MulticastSocket(null) {
            override fun receive(packet: DatagramPacket) {
                if (announced.compareAndSet(false, true)) {
                    receiving.trySend(this to requireNotNull(activeSource()))
                }
                super.receive(packet)
            }
        }.apply {
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = 50
            if (failRead) close()
        }
        return WifiMulticastReceiver(
            listOf(WifiMulticastReceiver.Subscription(TakMulticastEndpoints.directCot, socket))
        ) {
            assertNull(activeSource())
            assertTrue(socket.isClosed)
            closed.incrementAndGet()
        }
    }

    private suspend fun awaitFailure(count: Int) {
        withTimeout(3000) {
            while (ShadowToast.shownToastCount() < count) {
                scheduler.runCurrent()
                delay(10)
            }
        }
        // Main-dispatcher failure reporting finishes before the retry timer is inspected.
        scheduler.runCurrent()
    }

    @Test fun openFailuresRetryEveryFiveSecondsWithoutConfigurationEmission() = runBlocking {
        val attempts = AtomicInteger()
        val collector = launch(UnconfinedTestDispatcher(scheduler)) {
            TAKConnectService().collectMulticastConfigurations(
                configs.receiveAsFlow(),
                updatePublisher = { cfg, running -> publishers.trySend(cfg to running) },
                openReceiver = {
                    if (attempts.incrementAndGet() <= 2) throw IOException("Wi-Fi temporarily unavailable")
                    receiver()
                }
            )
        }
        try {
            configs.send(enabled)
            assertEquals(enabled to true, withTimeout(3000) { publishers.receive() })
            repeat(2) { index ->
                awaitFailure(index + 1)
                assertNull(activeSource())
                assertEquals(index + 1, attempts.get())
                scheduler.advanceTimeBy(4999)
                scheduler.runCurrent()
                assertEquals(index + 1, attempts.get())
                scheduler.advanceTimeBy(1)
                scheduler.runCurrent()
            }
            val (socket, source) = withTimeout(3000) { receiving.receive() }
            assertEquals(3, attempts.get())
            assertEquals(10_000L, scheduler.currentTime)
            assertEquals(enabled.net, source.first)
            assertFalse(socket.isClosed)
            assertTrue(publishers.tryReceive().isFailure)
        } finally {
            withTimeout(3000) { collector.cancelAndJoin() }
        }
        assertEquals(1, closed.get())
        assertNull(activeSource())
    }

    @Test fun readFailureClosesFailedSessionAndRecoveryCreatesFreshSession() = runBlocking {
        val attempts = AtomicInteger()
        val ingested = Channel<TakServerCotEnvelope>(Channel.UNLIMITED)
        val collector = launch(UnconfinedTestDispatcher(scheduler)) {
            TAKConnectService().collectMulticastConfigurations(
                configs.receiveAsFlow(),
                updatePublisher = { _, _ -> },
                openReceiver = { receiver(failRead = attempts.incrementAndGet() == 1) },
                ingestCot = { ingested.send(it) }
            )
        }
        try {
            configs.send(enabled)
            val (failedSocket, failedSource) = withTimeout(3000) { receiving.receive() }
            awaitFailure(1)
            assertTrue(failedSocket.isClosed)
            assertEquals(1, closed.get())
            assertNull(activeSource())
            scheduler.advanceTimeBy(5000)
            scheduler.runCurrent()
            val (recoveredSocket, recoveredSource) = withTimeout(3000) { receiving.receive() }
            assertFalse(recoveredSocket.isClosed)
            assertEquals(enabled.net, recoveredSource.first)
            assertNotEquals(failedSource.second, recoveredSource.second)
            assertEquals(2, attempts.get())
            val xml = "<event uid='recovered-marker' type='a-u-G'><point lat='41' lon='-87'/></event>"
            DatagramSocket().use { sender ->
                val bytes = xml.toByteArray(Charsets.UTF_8)
                sender.send(DatagramPacket(bytes, bytes.size, recoveredSocket.localAddress, recoveredSocket.localPort))
            }
            val envelope = withTimeout(3000) { ingested.receive() }
            assertEquals(xml, envelope.rawXml)
            assertEquals(TakMulticastEndpoints.directCot.sourceId, envelope.sourceServerId)
            assertEquals(recoveredSource.second, envelope.sourceSessionId)
            assertEquals(recoveredSocket.localAddress.hostAddress, envelope.sourcePeerAddress)
        } finally {
            withTimeout(3000) { collector.cancelAndJoin() }
        }
        assertEquals(2, closed.get())
        assertNull(activeSource())
    }

    @Test fun shutdownDuringBackoffCancelsPendingRetry() = runBlocking {
        val attempts = AtomicInteger()
        val collector = launch(UnconfinedTestDispatcher(scheduler)) {
            TAKConnectService().collectMulticastConfigurations(
                configs.receiveAsFlow(),
                updatePublisher = { _, _ -> },
                openReceiver = {
                    attempts.incrementAndGet()
                    throw IOException("Offline")
                }
            )
        }
        try {
            configs.send(enabled)
            awaitFailure(1)
            withTimeout(3000) { collector.cancelAndJoin() }
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            assertEquals(1, attempts.get())
            assertNull(activeSource())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun reconfigurationCancelsBackoffAndUsesOnlyLatestEligibleEndpoint() = runBlocking {
        val attempts = Channel<TAKConnectService.MulticastConfig>(Channel.UNLIMITED)
        val collector = launch(UnconfinedTestDispatcher(scheduler)) {
            TAKConnectService().collectMulticastConfigurations(
                configs.receiveAsFlow(),
                updatePublisher = { cfg, running -> publishers.trySend(cfg to running) },
                openReceiver = { cfg ->
                    attempts.trySend(cfg)
                    throw IOException("Offline")
                }
            )
        }
        try {
            val ineligible = listOf(
                enabled.copy(enable = false),
                enabled.copy(watchTransportSuppressed = true),
                enabled.copy(net = null),
                enabled.copy(address = "192.0.2.1"),
                enabled.copy(port = 65536)
            )
            ineligible.forEachIndexed { index, stopped ->
                configs.send(enabled)
                assertEquals(enabled to true, withTimeout(3000) { publishers.receive() })
                assertEquals(enabled, withTimeout(3000) { attempts.receive() })
                awaitFailure(index + 1)
                configs.send(stopped)
                assertEquals(stopped to false, withTimeout(3000) { publishers.receive() })
                scheduler.advanceTimeBy(5000)
                scheduler.runCurrent()
                assertTrue(attempts.tryReceive().isFailure)
            }
            configs.send(enabled)
            withTimeout(3000) { publishers.receive() }
            withTimeout(3000) { attempts.receive() }
            awaitFailure(6)
            val replacement = enabled.copy(net = mockk<Network>(), address = "239.2.3.2", port = 7000)
            configs.send(replacement)
            assertEquals(replacement to true, withTimeout(3000) { publishers.receive() })
            assertEquals(replacement, withTimeout(3000) { attempts.receive() })
            awaitFailure(7)
            scheduler.advanceTimeBy(5000)
            scheduler.runCurrent()
            assertEquals(replacement, withTimeout(3000) { attempts.receive() })
            awaitFailure(8)
            assertTrue(attempts.tryReceive().isFailure)
        } finally {
            withTimeout(3000) { collector.cancelAndJoin() }
        }
    }

    @Test fun permissionFailureIsReportedButNotRetried() = runBlocking {
        val attempts = AtomicInteger()
        val collector = launch(UnconfinedTestDispatcher(scheduler)) {
            TAKConnectService().collectMulticastConfigurations(
                configs.receiveAsFlow(),
                updatePublisher = { _, _ -> },
                openReceiver = {
                    attempts.incrementAndGet()
                    throw SecurityException("Permission denied")
                }
            )
        }
        try {
            configs.send(enabled)
            awaitFailure(1)
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            assertEquals(1, attempts.get())
            assertNull(activeSource())
        } finally {
            withTimeout(3000) { collector.cancelAndJoin() }
        }
    }
}
