package com.aitech.weartak_civ.elevation

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal class AwsTerrainProvider(
    context: Context,
    httpClient: OkHttpClient? = null
) : ElevationProvider {
    private val lock = Mutex()
    private val tiles = object : LinkedHashMap<Pair<Int, Int>, IntArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, IntArray>) = size > 8
    }
    private val failures = mutableMapOf<Pair<Int, Int>, Long>()
    private val client = httpClient ?: OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "terrain-terrarium"), 32L * 1024 * 1024))
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun groundAt(latitude: Double, longitude: Double): GroundElevation? =
        withContext(Dispatchers.IO) {
            val pixel = terrainPixel(latitude, longitude) ?: return@withContext null
            lock.withLock {
                coroutineContext.ensureActive()
                val key = pixel.x to pixel.y
                val samples = tiles[key] ?: run {
                    val now = android.os.SystemClock.elapsedRealtime()
                    failures.entries.removeAll { now - it.value > 60_000 }
                    if (key in failures) return@withLock null
                    try {
                        val request = Request.Builder()
                            .url("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/13/${pixel.x}/${pixel.y}.png")
                            .build()
                        // Static terrain: use disk cache even offline, never invent a missing tile.
                        val cached = client.newCall(request.newBuilder()
                            .cacheControl(CacheControl.FORCE_CACHE).build()).execute()
                        val response = if (cached.code == 504) {
                            cached.close()
                            client.newCall(request).execute()
                        } else cached
                        response.use {
                            if (!it.isSuccessful) throw IOException("Terrain HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Empty terrain response")
                            val source = body.source()
                            source.request(1_048_577)
                            val bytes = source.readByteArray(minOf(source.buffer.size, 1_048_577))
                            if (bytes.size > 1_048_576) throw IOException("Oversized terrain PNG")
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            if (bounds.outWidth != 256 || bounds.outHeight != 256)
                                throw IOException("Unexpected terrain dimensions")
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inScaled = false })
                                ?: throw IOException("Invalid terrain PNG")
                            try {
                                if (bitmap.width != 256 || bitmap.height != 256)
                                    throw IOException("Unexpected terrain dimensions")
                                IntArray(256 * 256).also { pixels ->
                                    bitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                                    tiles[key] = pixels
                                }
                            } finally { bitmap.recycle() }
                        }
                    } catch (error: IOException) {
                        Log.w("TerrainElevation", "AWS terrain unavailable at tile ${pixel.x}/${pixel.y}", error)
                        failures[key] = now
                        return@withLock null
                    }
                }
                coroutineContext.ensureActive()
                val meters = terrariumMeters(samples[pixel.row * 256 + pixel.column])
                if (meters == null) Log.w("TerrainElevation", "Terrain sample is void or invalid")
                meters?.let {
                    GroundElevation(it, "AWS Terrain Tiles (approximate EGM96)")
                }
            }
        }

    companion object {
        @Volatile private var instance: AwsTerrainProvider? = null
        fun get(context: Context): AwsTerrainProvider = instance ?: synchronized(this) {
            instance ?: AwsTerrainProvider(context.applicationContext).also { instance = it }
        }
    }
}
