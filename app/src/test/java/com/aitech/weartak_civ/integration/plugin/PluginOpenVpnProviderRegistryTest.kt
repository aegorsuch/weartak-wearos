package com.aitech.weartak_civ.integration.plugin

import android.os.IBinder
import android.os.IInterface
import com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as C
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginOpenVpnProviderRegistryTest {
    @Test
    fun selectedRouteAllowsOnlyHostApprovedPortsAtConfiguredAddress() {
        val provider = FakeProvider()
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))

        val factory = factory(registry, 17) { true }

        val wrongAddress = factory.createSocket()
        try {
            assertThrows<SocketException> {
                wrongAddress.connect(InetSocketAddress("192.0.2.11", 8089), 100)
            }
            assertEquals(0, provider.openCount)
        } finally {
            wrongAddress.close()
        }

        val wrongPort = factory.createSocket()
        try {
            assertThrows<SocketException> {
                wrongPort.connect(InetSocketAddress("192.0.2.10", 9443), 100)
            }
            assertEquals(0, provider.openCount)
        } finally {
            wrongPort.close()
        }

        assertThrows<SocketException> { factory.createSocket().getInputStream() }
        listOf(8089, 8443, 8446).forEach { port ->
            factory.createSocket().use { socket ->
                socket.connect(InetSocketAddress("192.0.2.10", port), 100)
            }
        }
        assertEquals(listOf(8089, 8443, 8446), provider.openPorts)
    }

    @Test
    fun staleAttemptFailsClosedAndProviderRemovalClosesOpenStream() {
        val provider = FakeProvider()
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))
        val factory = factory(registry, 18) { false }
        val stale = factory.createSocket()
        try {
            assertThrows<IOException> {
                stale.connect(InetSocketAddress("192.0.2.10", 8089), 100)
            }
            assertEquals(0, provider.openCount)
        } finally {
            stale.close()
        }

        val liveFactory = factory(registry, 19) { true }
        val live = liveFactory.createSocket()
        live.connect(InetSocketAddress("192.0.2.10", 8089), 100)
        assertEquals(1, provider.openCount)

        registry.unregister(C.PLUGIN_PACKAGE, HOST_UID)

        assertTrue(live.isClosed)
        assertEquals(1, provider.closeCount)
        assertFalse(registry.isConnected(C.PLUGIN_PACKAGE, "selected-profile"))
    }

    @Test
    fun providerRegistrationRejectsOtherPackage() {
        val provider = FakeProvider()
        val registry = PluginOpenVpnProviderRegistry()

        assertFalse(registry.register("com.example.unselected", HOST_UID, provider))
        assertEquals(null, registry.socketFactory(
            pluginPackage = C.PLUGIN_PACKAGE,
            serverId = "configured-server",
            profileId = "selected-profile",
            serverAddress = "192.0.2.10",
            serverPort = 8089,
            allowedPorts = setOf(8089, 8443, 8446),
            network = null,
            connectionGeneration = 20,
            allowed = { true },
        ))
    }

    @Test
    fun closeDuringOpenReleasesLateStreamAndInputRequiresConnection() {
        val provider = FakeProvider().apply {
            openEntered = CountDownLatch(1)
            releaseOpen = CountDownLatch(1)
        }
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))
        val socket = factory(registry, 21) { true }.createSocket()
        assertThrows<SocketException> { socket.getInputStream() }
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                socket.connect(InetSocketAddress("192.0.2.10", 8089), 100)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        thread.start()
        assertTrue(provider.openEntered!!.await(5, TimeUnit.SECONDS))
        socket.close()
        provider.releaseOpen!!.countDown()
        thread.join(5_000)

        assertFalse(thread.isAlive)
        assertTrue(failure.get() is IOException)
        assertTrue(socket.isClosed)
        assertEquals(1, provider.openCount)
        assertEquals(1, provider.closeCount)
    }

    @Test
    fun providerLossDuringOpenReleasesLateStream() {
        val provider = FakeProvider().apply {
            openEntered = CountDownLatch(1)
            releaseOpen = CountDownLatch(1)
        }
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))
        val socket = factory(registry, 22) { true }.createSocket()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                socket.connect(InetSocketAddress("192.0.2.10", 8089), 100)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        thread.start()
        assertTrue(provider.openEntered!!.await(5, TimeUnit.SECONDS))
        registry.unregister(C.PLUGIN_PACKAGE, HOST_UID)
        provider.releaseOpen!!.countDown()
        thread.join(5_000)

        assertFalse(thread.isAlive)
        assertTrue(failure.get() is IOException)
        assertTrue(socket.isClosed)
        assertEquals(1, provider.closeCount)
    }

    @Test
    fun staleGenerationDuringOpenReleasesLateStream() {
        val provider = FakeProvider().apply {
            openEntered = CountDownLatch(1)
            releaseOpen = CountDownLatch(1)
        }
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))
        val attemptAllowed = AtomicBoolean(true)
        val socket = factory(registry, 23) { attemptAllowed.get() }.createSocket()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                socket.connect(InetSocketAddress("192.0.2.10", 8089), 100)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        thread.start()
        assertTrue(provider.openEntered!!.await(5, TimeUnit.SECONDS))
        attemptAllowed.set(false)
        provider.releaseOpen!!.countDown()
        thread.join(5_000)

        assertFalse(thread.isAlive)
        assertTrue(failure.get() is IOException)
        assertEquals(1, provider.closeCount)
    }

    @Test
    fun concurrentRevocationAndAdoptionNeverLeaveAnUntrackedLiveSocket() {
        repeat(50) { generation ->
            val provider = FakeProvider()
            val registry = PluginOpenVpnProviderRegistry()
            assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))
            val socket = factory(registry, 100L + generation) { true }.createSocket() as PluginOpenVpnSocket
            val start = CountDownLatch(1)
            val adopted = AtomicBoolean(false)
            val adopter = Thread {
                start.await()
                adopted.set(registry.adoptSocket(socket.registration, socket))
            }
            val revoker = Thread {
                start.await()
                registry.unregister(C.PLUGIN_PACKAGE, HOST_UID)
            }

            adopter.start()
            revoker.start()
            start.countDown()
            adopter.join(5_000)
            revoker.join(5_000)

            assertFalse(adopter.isAlive)
            assertFalse(revoker.isAlive)
            assertTrue(socket.registration.sockets.isEmpty())
            if (adopted.get()) assertTrue(socket.isClosed)
            socket.close()
        }
    }

    @Test
    fun remoteIoCopiesOnlyBoundedSlicesAndPreservesCallerBuffers() {
        val provider = FakeProvider()
        val remote = IOpenVpnRawTcpProviderV1.Stub.asInterface(RemoteBinder(provider.asBinder()))
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, remote))
        val registration = requireNotNull(
            registry.socketFactory(C.PLUGIN_PACKAGE, "configured-server", "selected-profile",
                "192.0.2.10", 8089, setOf(8089, 8443, 8446), null, 24) { true },
        )
        val socket = registration.createSocket() as PluginOpenVpnSocket
        socket.connect(InetSocketAddress("192.0.2.10", 8089), 100)

        val offset = 13
        val destination = ByteArray(C.MAX_IO_BYTES + 100) { TAIL_SENTINEL }
        val read = registry.readStream(
            socket.registration, "selected-profile", 1, destination, offset,
            C.MAX_IO_BYTES + 50, C.MAX_TIMEOUT_MS,
        )
        assertEquals(C.MAX_IO_BYTES, read)
        assertEquals(C.MAX_IO_BYTES, provider.readArraySize)
        assertEquals(0, provider.readOffset)
        assertTrue(destination.take(offset).all { it == TAIL_SENTINEL })
        assertEquals(1.toByte(), destination[offset])
        assertEquals(TAIL_SENTINEL, destination[offset + read])
        assertTrue(destination.drop(offset + read).all { it == TAIL_SENTINEL })

        val sourceOffset = 19
        val source = ByteArray(C.MAX_IO_BYTES + 500) { it.toByte() }
        val written = registry.writeStream(
            socket.registration, "selected-profile", 1, source, sourceOffset,
            C.MAX_IO_BYTES + 200, C.MAX_TIMEOUT_MS,
        )
        assertEquals(C.MAX_IO_BYTES, written)
        assertEquals(C.MAX_IO_BYTES, provider.writeArraySize)
        assertEquals(0, provider.writeOffset)
        assertEquals(
            source.copyOfRange(sourceOffset, sourceOffset + C.MAX_IO_BYTES).toList(),
            provider.writePayload!!.toList(),
        )
    }

    @Test
    fun remoteProviderCannotReturnMoreBytesThanRequested() {
        val provider = FakeProvider().apply { readResult = C.MAX_IO_BYTES + 1 }
        val remote = IOpenVpnRawTcpProviderV1.Stub.asInterface(RemoteBinder(provider.asBinder()))
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, remote))
        val factory = requireNotNull(
            registry.socketFactory(C.PLUGIN_PACKAGE, "configured-server", "selected-profile",
                "192.0.2.10", 8089, setOf(8089, 8443, 8446), null, 25) { true },
        )
        val socket = factory.createSocket() as PluginOpenVpnSocket
        socket.connect(InetSocketAddress("192.0.2.10", 8089), 100)

        assertThrows<IOException> {
            registry.readStream(
                socket.registration, "selected-profile", 1, ByteArray(C.MAX_IO_BYTES),
                0, C.MAX_IO_BYTES, C.MAX_TIMEOUT_MS,
            )
        }
        assertTrue(socket.isClosed)
    }

    @Test
    fun zeroReadTimeoutPollsAgainAfterProviderTimeout() {
        val provider = FakeProvider().apply {
            readResponses.add(C.IO_TIMEOUT_RESULT)
            readResponses.add(1)
        }
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))
        val socket = factory(registry, 26) { true }.createSocket()
        socket.connect(InetSocketAddress("192.0.2.10", 8089), 100)
        socket.soTimeout = 0

        assertEquals(1, socket.getInputStream().read(ByteArray(1)))
        assertEquals(2, provider.readCount)
        assertTrue(provider.readTimeouts.all { it <= C.MAX_TIMEOUT_MS })
    }

    @Test
    fun finiteReadTimeoutExpiresWhenProviderPollTimesOut() {
        val provider = FakeProvider().apply {
            readResponses.add(C.IO_TIMEOUT_RESULT)
            readDelayMs = 20
        }
        val registry = PluginOpenVpnProviderRegistry()
        assertTrue(registry.register(C.PLUGIN_PACKAGE, HOST_UID, provider))
        val socket = factory(registry, 27) { true }.createSocket()
        socket.connect(InetSocketAddress("192.0.2.10", 8089), 100)
        socket.soTimeout = 1

        assertThrows<SocketTimeoutException> { socket.getInputStream().read() }
        assertEquals(1, provider.readCount)
    }

    private fun factory(
        registry: PluginOpenVpnProviderRegistry,
        generation: Long,
        allowed: () -> Boolean,
    ) = requireNotNull(
        registry.socketFactory(
            pluginPackage = C.PLUGIN_PACKAGE,
            serverId = "configured-server",
            profileId = "selected-profile",
            serverAddress = "192.0.2.10",
            serverPort = 8089,
            allowedPorts = setOf(8089, 8443, 8446),
            network = null,
            connectionGeneration = generation,
            allowed = allowed,
        ),
    )

    private class FakeProvider : IOpenVpnRawTcpProviderV1.Stub() {
        var openCount = 0
        var closeCount = 0
        var openPorts = mutableListOf<Int>()
        var readArraySize = 0
        var readOffset = -1
        var readResult: Int? = null
        val readResponses = mutableListOf<Int>()
        val readTimeouts = mutableListOf<Long>()
        var readCount = 0
        var readDelayMs = 0L
        var writeArraySize = 0
        var writeOffset = -1
        var writePayload: ByteArray? = null
        var openEntered: CountDownLatch? = null
        var releaseOpen: CountDownLatch? = null

        override fun getApiVersion(): Int = C.PROVIDER_API_VERSION
        override fun getRuntimeState(profileId: String?): Int = C.RUNTIME_CONNECTED
        override fun openStream(
            serverId: String?,
            profileId: String?,
            ipv4: String?,
            port: Int,
            connectionGeneration: Long,
            timeoutMs: Long,
        ): Long {
            openCount++
            openPorts += port
            openEntered?.countDown()
            releaseOpen?.await(5, TimeUnit.SECONDS)
            return openCount.toLong()
        }

        override fun readStream(
            profileId: String?,
            streamId: Long,
            destination: ByteArray?,
            offset: Int,
            length: Int,
            timeoutMs: Long,
        ): Int {
            readArraySize = destination?.size ?: 0
            readOffset = offset
            readCount++
            readTimeouts += timeoutMs
            if (readDelayMs > 0) Thread.sleep(readDelayMs)
            if (readResponses.isNotEmpty()) {
                val result = readResponses.removeAt(0)
                if (result > 0) {
                    repeat(result) { index -> requireNotNull(destination)[offset + index] = (index + 1).toByte() }
                }
                return result
            }
            val bytes = requireNotNull(destination)
            repeat(length) { index -> bytes[offset + index] = (index + 1).toByte() }
            return readResult ?: length
        }

        override fun writeStream(
            profileId: String?,
            streamId: Long,
            source: ByteArray?,
            offset: Int,
            length: Int,
            timeoutMs: Long,
        ): Int {
            writeArraySize = source?.size ?: 0
            writeOffset = offset
            writePayload = requireNotNull(source).copyOfRange(offset, offset + length)
            return length
        }

        override fun closeStream(profileId: String?, streamId: Long): Int {
            closeCount++
            return 0
        }
    }

    private class RemoteBinder(private val target: IBinder) : IBinder by target {
        override fun queryLocalInterface(descriptor: String): IInterface? = null
    }

    private companion object {
        const val HOST_UID = 10_001
        const val TAIL_SENTINEL: Byte = 0x55

        inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                if (error is T) return
                throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
            }
            throw AssertionError("Expected ${T::class.java.name}")
        }
    }
}
