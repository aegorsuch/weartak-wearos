package com.aitech.weartak_civ.elevation

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AwsTerrainProviderTest {
    @Test fun tilesAreCachedAndOfflineUncachedRequestsReturnNoElevation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff800a00.toInt()) // 10 m orthometric
        val png = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val requests = AtomicInteger()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 3000
        val port = server.localPort
        val served = thread(name = "terrain-test-response") {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                requests.incrementAndGet()
                socket.getOutputStream().use {
                    it.write(("HTTP/1.1 200 OK\r\nContent-Length: ${png.size}\r\n" +
                        "Cache-Control: public, max-age=86400\r\n" +
                        "Content-Type: image/png\r\nConnection: close\r\n\r\n").toByteArray())
                    it.write(png)
                }
            }
        }
        val directory = File(context.cacheDir, "terrain-test-${System.nanoTime()}")
        val client = OkHttpClient.Builder()
            .cache(Cache(directory, 1024 * 1024))
            .callTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder()
                    .scheme("http").host("127.0.0.1").port(port).build()).build())
            }.build()
        try {
            val provider = AwsTerrainProvider(context, client)
            assertEquals(10.0, provider.groundAt(40.706, -74.009)!!.orthometricMeters, 0.0)
            assertEquals(10.0, provider.groundAt(40.70601, -74.00901)!!.orthometricMeters, 0.0)
            assertEquals(1, requests.get())
            client.cache!!.flush()
            served.join(3000)
            server.close()
            // Fresh provider has no in-memory tiles; succeeds from HTTP disk cache.
            assertEquals(10.0, AwsTerrainProvider(context, client)
                .groundAt(40.706, -74.009)!!.orthometricMeters, 0.0)
            assertNull(AwsTerrainProvider(context, client).groundAt(41.0, -75.0))
            assertEquals(1, requests.get())
        } finally {
            server.close()
            served.join(3000)
            client.cache!!.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test fun providerInterfaceAllowsLocalTerrainToReplaceAwsWithoutDatumChanges() = runBlocking {
        val dtedFixture = ElevationProvider { _, _ -> GroundElevation(10.0, "DTED fixture") }
        assertEquals(60.0, heightAboveGround(37.0, dtedFixture.groundAt(40.706, -74.009), -33.0)!!, 0.0)
    }
}
