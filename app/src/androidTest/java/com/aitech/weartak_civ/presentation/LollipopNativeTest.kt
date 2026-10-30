package com.aitech.weartak_civ.presentation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.elevation.Egm96Grid
import com.aitech.weartak_civ.elevation.ElevationProvider
import com.aitech.weartak_civ.elevation.GroundElevation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Test-only NYC towers/HAE; never introduces synthetic contacts into the real map. */
@RunWith(AndroidJUnit4::class)
class LollipopNativeTest {
    @Test fun sixtyMeterIndoorAnd150MeterMarkersRemainAboveNativeBuildings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val center = Position(-74.009, 40.706)
        val geoid = context.assets.open("egm96-15.pgm").use(Egm96Grid::read)
        val points = listOf(Position(-74.0093, 40.706), Position(-74.0087, 40.706))
        val targets = points.mapIndexed { index, point ->
            val agl = if (index == 0) 60.0 else 150.0
            LollipopTarget("fixture-$index", point,
                10.0 + requireNotNull(geoid.undulation(point.latitude, point.longitude)) + agl,
                10.0, "", "Fixture $agl m", if (index == 0) "Magenta" else "Cyan", self = true)
        }
        fun tower(point: Position) = """
            {"type":"Feature","properties":{"render_height":150,"render_min_height":0},
            "geometry":{"type":"Polygon","coordinates":[[
            [${point.longitude - 0.00006},40.705955],[${point.longitude + 0.00006},40.705955],
            [${point.longitude + 0.00006},40.706045],[${point.longitude - 0.00006},40.706045],
            [${point.longitude - 0.00006},40.705955]]]}}
        """.trimIndent()
        val style = BaseStyle.Json("""
            {"version":8,"sources":{"fixtures":{"type":"geojson","data":
            {"type":"FeatureCollection","features":[${points.joinToString(",") { tower(it) }}]}}},
            "layers":[{"id":"background","type":"background","paint":{"background-color":"#101010"}},
            {"id":"$BUILDINGS_3D_LAYER","type":"fill-extrusion","source":"fixtures",
            "paint":{"fill-extrusion-color":"#ff8800","fill-extrusion-height":["get","render_height"],
            "fill-extrusion-opacity":0.6}}]}
        """.trimIndent())
        val provider = ElevationProvider { _, _ -> GroundElevation(10.0, "native test fixture") }
        val loaded = CountDownLatch(1)
        val overlayVisible = mutableStateOf(true)
        val state = AtomicReference(LollipopState())
        val origin = AtomicReference(ScreenCoordinate(0.0, 0.0))
        var density = 1f
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    density = LocalDensity.current.density
                    val camera = rememberCameraState(firstPosition = CameraPosition(
                        target = center, zoom = 14.5, tilt = 45.0))
                    val footprints = remember { RenderedBuildingFootprints() }
                    val queryBuildings = rememberBuildingFootprintQuery(true, camera, footprints)
                    val current = rememberLollipops(true, targets, camera, footprints.features, provider)
                    state.set(current)
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        val offset = it.positionInWindow()
                        origin.set(ScreenCoordinate(offset.x.toDouble(), offset.y.toDouble()))
                    }) {
                        MaplibreMap(Modifier.fillMaxSize(), cameraState = camera, baseStyle = style,
                            onFrame = { queryBuildings() },
                            onMapLoadFinished = { queryBuildings(); loaded.countDown() })
                        if (overlayVisible.value) LollipopOverlay(current) {}
                    }
                }
            }
            assertTrue("Fixture style did not load", loaded.await(20, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                val resolved = state.get().markers
                if (resolved.size == 2 && resolved[0].segments.isNotEmpty() &&
                    resolved[0].segments.all { it.inside }) break
                Thread.sleep(100)
            }
            val markers = state.get().markers
            assertEquals("Both HAE fixtures must resolve", 2, markers.size)
            assertEquals(60.0, markers[0].agl, 0.001)
            assertTrue("Indoor stem must be dashed", markers[0].segments.all { it.inside })
            assertTrue("Indoor footprint must actually be queried", markers[0].segments.isNotEmpty())
            assertEquals(150.0, markers[1].agl, 0.001)
            fun capture(name: String): Bitmap {
                instrumentation.waitForIdleSync()
                return requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { image ->
                    File(context.getExternalFilesDir(null), name).outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            Thread.sleep(300)
            val on = capture("lollipops-60m-inside-150m-roof.png")
            fun pixel(point: ScreenCoordinate) = Pair(
                (origin.get().x + point.x * density).roundToInt(),
                (origin.get().y + point.y * density).roundToInt())
            markers.forEachIndexed { index, marker ->
                val (x, y) = pixel(marker.raised)
                val color = on.getPixel(x, y)
                if (index == 0) assertTrue("Indoor marker obscured",
                    android.graphics.Color.red(color) > 160 && android.graphics.Color.blue(color) > 160)
                else assertTrue("150 m marker obscured",
                    android.graphics.Color.green(color) > 160 && android.graphics.Color.blue(color) > 160)
                assertTrue("Marker must rise from its ground position", marker.raised.y < marker.projection.ground.y)
            }
            val indoor = markers[0]
            var white = 0
            var gaps = 0
            for (height in 1..59) {
                val sample = requireNotNull(indoor.projection.atHeight(height.toDouble()))
                if (abs(sample.y - indoor.projection.ground.y) < 3 ||
                    abs(sample.y - indoor.raised.y) < 9) continue
                val (x, y) = pixel(sample)
                val color = on.getPixel(x, y)
                if (android.graphics.Color.red(color) > 160 &&
                    android.graphics.Color.green(color) > 160 &&
                    android.graphics.Color.blue(color) > 160) white++
                else gaps++
            }
            assertTrue("Dashed indoor stem must contain visible strokes and gaps", white > 0 && gaps > 0)
            instrumentation.runOnMainSync { overlayVisible.value = false }
            Thread.sleep(200)
            val native = capture("lollipops-native-roof-reference.png")
            val (roofX, projectedRoofY) = pixel(markers[1].raised)
            val roofTop = (0 until native.height).firstOrNull { y ->
                val color = native.getPixel(roofX, y)
                android.graphics.Color.red(color) > 80 &&
                    android.graphics.Color.red(color) > android.graphics.Color.green(color) * 1.4 &&
                    android.graphics.Color.blue(color) < 70
            }
            assertNotNull("Native 150 m tower missing at projected roof column", roofTop)
            assertTrue("Vertical lift must agree with native extrusion roof, not a flat pixel offset",
                abs(requireNotNull(roofTop) - projectedRoofY) < 8 * density)
        }
    }
}
