package com.aitech.weartak_civ.elevation

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ElevationTest {
    @Test fun geoidAgreesWithIndependentNgaHarmonicSynthesisVectors() {
        val grid = RuntimeEnvironment.getApplication().assets.open("egm96-15.pgm").use(Egm96Grid::read)
        // GeographicLib GeoidHeights.dat.gz, first eight EGM96 reference vectors (not grid-derived).
        val references = listOf(
            Triple(-76.981466, 34.170160, 11.423169),
            Triple(79.695484, 88.805571, 0.891749),
            Triple(-15.244804, 168.747961, 65.225787),
            Triple(-19.379357, 15.855110, 24.463147),
            Triple(43.377784, 229.448418, -29.682024),
            Triple(-16.074554, 21.795470, 10.503430),
            Triple(-11.256389, 286.249745, 29.325619),
            Triple(69.016564, 344.078885, 60.423629)
        )
        references.forEach { (lat, lon, expected) ->
            assertEquals(expected, requireNotNull(grid.undulation(lat, lon)), 0.05)
        }
        assertEquals(requireNotNull(grid.undulation(43.377784, 229.448418)),
            requireNotNull(grid.undulation(43.377784, -130.551582)), 1e-10)
        assertEquals(grid.undulation(90.0, 0.0), grid.undulation(90.0, 179.0))
        assertEquals(grid.undulation(-90.0, 0.0), grid.undulation(-90.0, -179.0))
        assertNull(grid.undulation(Double.NaN, 0.0))
        assertNull(grid.undulation(91.0, 0.0))
    }

    @Test fun aglAppliesGeoidWithCorrectSignAndNeverInventsUnknownHeights() {
        val ground = GroundElevation(10.0, "fixture")
        val geoidN = -32.778303456
        assertEquals(150.0, heightAboveGround(150 + 10 + geoidN, ground, geoidN)!!, 1e-9)
        assertEquals(60.0, heightAboveGround(60 + 10 + geoidN, ground, geoidN)!!, 1e-9)
        for (invalid in listOf(null, 9_999_999.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(heightAboveGround(invalid, ground, geoidN))
        }
        assertNull(heightAboveGround(150.0, null, geoidN))
        assertNull(heightAboveGround(150.0, ground, null))
        assertNull(heightAboveGround(10 + geoidN + 3, ground, geoidN))
        assertNull(heightAboveGround(10 + geoidN - 1, ground, geoidN))
    }

    @Test fun terrariumDecodesFractionsNegativeHeightsAndRejectsNoData() {
        assertEquals(2523.265625, terrariumMeters(0xff89db44.toInt())!!, 0.0)
        assertEquals(-10.0, terrariumMeters(0xff7ff600.toInt())!!, 0.0)
        assertEquals(0.0, terrariumMeters(0xff800000.toInt())!!, 0.0)
        assertNull(terrariumMeters(0x00800000))
        assertNull(terrariumMeters(0xff000000.toInt()))
        assertNull(terrariumMeters(0xffffffff.toInt()))
    }

    @Test fun chicagoWatchHaeProducesTenthFloorHeightAndInBuildingStem() {
        val grid = RuntimeEnvironment.getApplication().assets.open("egm96-15.pgm").use(Egm96Grid::read)
        // Received ODIN-WEARTAK HAE; 181 m ground is a fixture, not a live terrain measurement.
        val agl = requireNotNull(heightAboveGround(179.2, GroundElevation(181.0, "Chicago fixture"),
            grid.undulation(41.880029, -87.640974)))
        assertTrue("Expected approximately tenth-floor height, got $agl m", agl in 30.0..35.0)
        assertEquals(listOf(com.aitech.weartak_civ.presentation.StemSegment(0.0, agl, true)),
            com.aitech.weartak_civ.presentation.stemSegments(agl,
                listOf(com.aitech.weartak_civ.presentation.HeightRange(0.0, 100.0))))
    }

    @Test fun terrainPixelsRespectMercatorLimitsAndTileEdges() {
        assertEquals(TerrainPixel(4096, 4096, 0, 0), terrainPixel(0.0, 0.0))
        assertEquals(0, terrainPixel(0.0, -180.0)!!.x)
        assertEquals(8191, terrainPixel(0.0, 180.0)!!.x)
        assertEquals(255, terrainPixel(0.0, 180.0)!!.column)
        assertEquals(0, terrainPixel(85.05112878, 0.0)!!.y)
        assertEquals(8191, terrainPixel(-85.05112878, 0.0)!!.y)
        assertNull(terrainPixel(86.0, 0.0))
        assertNull(terrainPixel(0.0, Double.NaN))
        assertNull(terrainPixel(0.0, 181.0))
    }

    @Test fun malformedGeoidIsRejectedInsteadOfReturningZeroUndulation() {
        assertThrows(IllegalArgumentException::class.java) {
            Egm96Grid.read("P5\n1440 721\n65535\n".byteInputStream())
        }
    }
}
