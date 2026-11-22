package com.aitech.weartak_civ.cot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.events.TakChatEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires the separately installed test-fixtures/ditto-chat-ipc debug APK and a host built with
 * -PweartakDittoUiDemo=true. Both must use the same debug signer. Run only on a disposable emulator.
 * The protocol and payload are synthetic, not production discovery, ownership, or V2 wire format.
 */
@RunWith(AndroidJUnit4::class)
class InertChatIpcIntegrationTest {
    private val descriptor = "org.weartak.chatipcfixture.IFakeChat"
    private val fixturePackage = "org.weartak.chatipcfixture"
    private val maxWireBytes = 263_168

    @Test
    fun separateUidStreamsSyntheticChatThroughRealDecoderAndIngestor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "com.aitech.weartak_civ.ditto.diagnostics.fixture")
        val connected = CountDownLatch(1)
        val remote = AtomicReference<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote.set(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val intent = Intent().setClassName(fixturePackage, "$fixturePackage.FakeChatService")
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Fixture service did not connect", connected.await(10, TimeUnit.SECONDS))
            val info = context.packageManager.getApplicationInfo(fixturePackage, 0)
            assertNotEquals(context.applicationInfo.uid, info.uid)
            assertEquals(
                PackageManager.SIGNATURE_MATCH,
                context.packageManager.checkSignatures(context.applicationInfo.uid, info.uid)
            )
            val hostSigners = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo!!.apkContentsSigners
            val fixtureSigners = context.packageManager.getPackageInfo(
                fixturePackage, PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo!!.apkContentsSigners
            assertEquals(1, hostSigners.size)
            assertEquals(1, fixtureSigners.size)
            assertEquals(hostSigners.single(), fixtureSigners.single())
            assertEquals(listOf(fixturePackage), context.packageManager.getPackagesForUid(info.uid)?.toList())
            val service = requireNotNull(remote.get())
            val first = Binder()
            val firstGeneration = acquire(service, first)
            val session = Binder()
            val generation = acquire(service, session)
            assertTrue(generation > firstGeneration)
            assertThrows(SecurityException::class.java) {
                readDocument(service, first, firstGeneration, 0)
            }
            assertThrows(SecurityException::class.java) {
                readDocument(service, Binder(), generation, 0)
            }

            val timestamp = System.currentTimeMillis() - 1000
            fun document(length: Int) = readDocument(service, session, generation, length, timestamp)
            val source = document(10)
            val decoded = DittoChatDocument.decode(source)
            val events = mutableListOf<TakChatEvent>()
            val sink = object : TakServerCotSink {
                override suspend fun onChat(event: TakChatEvent) { events += event }
                override suspend fun onGeofence(geofence: GeofenceCot) = Unit
                override suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope) = Unit
                override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) = Unit
                override suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope) = Unit
            }
            val ingestor = TakServerCotIngestor(sink, { true }, { false }, { "watch" }, { "WATCH" })
            fun envelope(xml: String) = TakServerCotEnvelope("fixture", "", timestamp, xml)
            assertEquals(TakServerCotIngestResult.CHAT, ingestor.ingest(envelope(decoded.xml)))
            val message = events.single().parsedRooms!!.flatMap { it.messageList }.single()
            assertEquals("snow \u2603 & < > \" " + "x".repeat(10), message.message)
            assertEquals("fixture-sender", message.uid)
            assertEquals("fixture-message", message.messageId)
            val other = TakServerCotIngestor(sink, { true }, { false }, { "other" }, { "OTHER" })
            assertNotEquals(TakServerCotIngestResult.CHAT, other.ingest(envelope(decoded.xml)))
            assertEquals(1, events.size)

            assertThrows(IllegalArgumentException::class.java) {
                DittoChatDocument.decode(source - "roomId")
            }
            assertThrows(IllegalArgumentException::class.java) {
                DittoChatDocument.decode(source + ("r" to "<detail><broken>"))
            }
            assertThrows(JSONException::class.java) { document(-1) }
            assertThrows(IllegalArgumentException::class.java) { document(-2) }

            // Find the exact complete reconstructed XML limit, rather than a message-size proxy.
            var low = 0
            var high = DittoCotDocument.MAX_PAYLOAD_BYTES
            while (low < high) {
                val middle = (low + high + 1) / 2
                val accepted = try {
                    DittoChatDocument.decode(document(middle))
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
                if (accepted) low = middle else high = middle - 1
            }
            val atLimit = DittoChatDocument.decode(document(low))
            assertEquals(DittoCotDocument.MAX_PAYLOAD_BYTES, atLimit.xml.toByteArray(Charsets.UTF_8).size)
            assertThrows(IllegalArgumentException::class.java) {
                DittoChatDocument.decode(document(low + 1))
            }
            transact(service, 3, session, generation) { }
            assertThrows(SecurityException::class.java) {
                readDocument(service, session, generation, 0)
            }
            Unit
        } finally {
            context.unbindService(connection)
        }
    }

    private fun acquire(service: IBinder, session: IBinder): Long =
        transact(service, 1, session, null) { it.readLong() }

    private fun readDocument(
        service: IBinder, session: IBinder, generation: Long, length: Int,
        timestamp: Long = System.currentTimeMillis()
    ): Map<String, Any?> = transact(service, 2, session, generation, length, timestamp) { reply ->
        val descriptor = requireNotNull(ParcelFileDescriptor.CREATOR.createFromParcel(reply))
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= maxWireBytes) { "Oversized fixture stream" }
                bytes.write(buffer, 0, count)
            }
            parseDocument(bytes.toByteArray())
        }
    }

    private fun parseDocument(bytes: ByteArray): Map<String, Any?> {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return json.keys().asSequence().associateWith { key ->
            json.get(key).let { if (it === JSONObject.NULL) null else it }
        }
    }

    private fun <T> transact(
        service: IBinder, code: Int, session: IBinder, generation: Long?,
        length: Int = 0, timestamp: Long = 0, read: (Parcel) -> T
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.writeStrongBinder(session)
            if (generation != null) data.writeLong(generation)
            if (code == 2) {
                data.writeInt(length)
                data.writeLong(timestamp)
            }
            check(service.transact(code, data, reply, 0)) { "Fixture transaction rejected" }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
