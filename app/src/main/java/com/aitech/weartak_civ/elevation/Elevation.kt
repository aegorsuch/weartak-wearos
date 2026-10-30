package com.aitech.weartak_civ.elevation

import java.io.InputStream
import kotlin.math.*

internal data class GroundElevation(val orthometricMeters: Double, val source: String)

// Future DTED providers return orthometric heights too; callers own the datum conversion.
internal fun interface ElevationProvider {
    suspend fun groundAt(latitude: Double, longitude: Double): GroundElevation?
}

internal fun validHae(hae: Double?): Boolean =
    hae != null && hae.isFinite() && hae in -12_000.0..100_000.0

internal fun heightAboveGround(hae: Double?, ground: GroundElevation?, geoidN: Double?): Double? {
    if (!validHae(hae) || ground == null || !ground.orthometricMeters.isFinite() ||
        geoidN == null || !geoidN.isFinite()) return null
    return (hae!! - ground.orthometricMeters - geoidN).takeIf { it > 3.0 }
}

internal data class TerrainPixel(val x: Int, val y: Int, val column: Int, val row: Int)

internal fun terrainPixel(latitude: Double, longitude: Double, zoom: Int = 13): TerrainPixel? {
    if (!latitude.isFinite() || latitude !in -85.05112878..85.05112878 ||
        !longitude.isFinite() || longitude !in -180.0..180.0 || zoom !in 0..15) return null
    val size = 1 shl zoom
    val x = ((longitude + 180.0) / 360.0 * size).coerceIn(0.0, Math.nextDown(size.toDouble()))
    val y = ((1.0 - asinh(tan(Math.toRadians(latitude))) / PI) / 2.0 * size)
        .coerceIn(0.0, Math.nextDown(size.toDouble()))
    return TerrainPixel(floor(x).toInt(), floor(y).toInt(),
        ((x % 1) * 256).toInt(), ((y % 1) * 256).toInt())
}

internal fun terrariumMeters(argb: Int): Double? {
    if (argb ushr 24 != 255) return null
    val height = ((argb ushr 16) and 255) * 256 + ((argb ushr 8) and 255) +
        (argb and 255) / 256.0 - 32768
    return height.takeIf { it in -11_000.0..9_000.0 }
}

/** GeographicLib's unmodified egm96-15 PGM: north-to-south, 0..360 east, unsigned big-endian. */
internal class Egm96Grid private constructor(private val data: ByteArray) {
    fun undulation(latitude: Double, longitude: Double): Double? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..360.0) return null
        val x = ((longitude % 360 + 360) % 360) * 4
        val y = (90 - latitude) * 4
        val column = floor(x).toInt()
        val row = floor(y).toInt().coerceAtMost(719)
        val dx = x - column
        val dy = y - row
        fun sample(r: Int, c: Int): Double {
            val index = (r * 1440 + c % 1440) * 2
            return -108.0 + (((data[index].toInt() and 255) shl 8) +
                (data[index + 1].toInt() and 255)) * 0.003
        }
        return (sample(row, column) * (1 - dx) + sample(row, column + 1) * dx) * (1 - dy) +
            (sample(row + 1, column) * (1 - dx) + sample(row + 1, column + 1) * dx) * dy
    }

    companion object {
        fun read(input: InputStream): Egm96Grid {
            val header = StringBuilder()
            val tokens = mutableListOf<String>()
            while (tokens.size < 4) {
                val line = buildString {
                    while (true) {
                        val ch = input.read()
                        require(ch != -1) { "Truncated EGM96 header" }
                        if (ch == 10) break
                        append(ch.toChar())
                        require(length < 1024) { "Invalid EGM96 header" }
                    }
                }
                header.appendLine(line)
                if (!line.startsWith("#")) tokens += line.trim().split(Regex("\\s+"))
            }
            require(tokens == listOf("P5", "1440", "721", "65535") &&
                "# Offset -108\n" in header && "# Scale 0.003\n" in header) { "Unexpected EGM96 format" }
            val data = input.readBytes()
            require(data.size == 1440 * 721 * 2) { "Truncated EGM96 samples" }
            return Egm96Grid(data)
        }
    }
}
