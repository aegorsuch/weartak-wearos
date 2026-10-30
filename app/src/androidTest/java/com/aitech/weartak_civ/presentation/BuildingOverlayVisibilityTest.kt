package com.aitech.weartak_civ.presentation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.TextPitchAlignment
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BuildingOverlayVisibilityTest {
    @Test fun tacticalGeometrySurvivesBuildingsAndTiltedSourceReloads() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val center = Position(-74.009, 40.706)
        val loaded = AtomicReference(CountDownLatch(1))
        val hidden = mutableStateOf(emptySet<String>())
        val selectedSource = mutableStateOf(MapSource.DEFAULT)
        val cameraReference = AtomicReference<CameraState>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val camera = rememberCameraState(firstPosition = CameraPosition(
                        target = center, zoom = 16.0, tilt = 45.0, bearing = 25.0))
                    cameraReference.set(camera)
                    val style = rememberBaseMapStyle(context, selectedSource.value)
                    Box(Modifier.fillMaxSize()) {
                        key(style) {
                        MaplibreMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraState = camera,
                            baseStyle = style,
                            onMapLoadFinished = { loaded.get().countDown() }
                        ) {
                            HiddenBaseMapLayers(baseMapLayerIds(style), hidden.value)
                            val source = rememberGeoJsonSource(GeoJsonData.Features(
                                FeatureCollection(Feature(Point(Position(-74.009, 40.7057)),
                                    kotlinx.serialization.json.JsonObject(emptyMap())))))
                            SymbolLayer(
                                id = "test-tactical-label", source = source,
                                textField = const("TAK"), textSize = const(1.em),
                                textColor = const(Color.Green), textHaloColor = const(Color.Black),
                                textHaloWidth = const(1.dp),
                                textPitchAlignment = const(TextPitchAlignment.Viewport),
                                textAllowOverlap = const(true), textIgnorePlacement = const(true)
                            )
                        }
                        }
                        TacticalGeometryOverlay(
                            camera,
                            listOf(TacticalScreenPoint(center, Color.Magenta, 7f)),
                            listOf(TacticalScreenLine(listOf(
                                Position(-74.0098, 40.706), Position(-74.0082, 40.706)
                            ), Color.Cyan, 4f))
                        )
                    }
                }
            }
            assertTrue("Urban map did not load", loaded.get().await(40, TimeUnit.SECONDS))
            Thread.sleep(2000)
            fun capture(name: String): Bitmap {
                instrumentation.waitForIdleSync()
                return requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { image ->
                    File(context.getExternalFilesDir(null), name).outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            fun count(image: Bitmap, color: Int): Int {
                // Native glyph edges vary with fractional placement and background blending.
                fun channelMatches(actual: Int, expected: Int) =
                    if (expected == 0) actual < 80 else actual > 120
                var count = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val pixel = image.getPixel(x, y)
                    if (channelMatches(android.graphics.Color.red(pixel), android.graphics.Color.red(color)) &&
                        channelMatches(android.graphics.Color.green(pixel), android.graphics.Color.green(color)) &&
                        channelMatches(android.graphics.Color.blue(pixel), android.graphics.Color.blue(color))) count++
                }
                return count
            }
            val on = capture("tactical-buildings-on.png")
            instrumentation.runOnMainSync { hidden.value = setOf(BUILDINGS_3D_LAYER) }
            Thread.sleep(500)
            val off = capture("tactical-buildings-off.png")
            var changed = 0
            for (y in 0 until on.height) for (x in 0 until on.width) {
                if (on.getPixel(x, y) != off.getPixel(x, y)) changed++
            }
            assertTrue("Building toggle did not change rendered geometry", changed > 1000)
            for (color in listOf(android.graphics.Color.MAGENTA, android.graphics.Color.CYAN,
                android.graphics.Color.GREEN)) {
                val visible = count(on, color)
                val reference = count(off, color)
                assertTrue("Tactical color $color missing", reference > 30)
                assertTrue("Buildings occluded tactical color $color: $visible vs $reference",
                    visible >= reference * 0.95)
            }
            val before = cameraReference.get().position
            for ((source, tilted) in listOf(
                MapSource.GOOGLE_HYBRID to false,
                MapSource.DEFAULT to false,
                MapSource.BING_HYBRID to false,
                MapSource.DEFAULT to true,
                MapSource.GOOGLE_HYBRID to false,
                MapSource.BING_HYBRID to false,
                MapSource.DEFAULT to false
            )) {
                val latch = CountDownLatch(1)
                loaded.set(latch)
                instrumentation.runOnMainSync {
                    selectedSource.value = source
                    hidden.value = mapHiddenLayersForPitch(emptySet(), if (tilted) 45.0 else 0.0)
                    cameraReference.get().position = before.copy(tilt = if (tilted) 45.0 else 0.0)
                }
                assertTrue("$source did not reload", latch.await(20, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val after = cameraReference.get().position
                assertEquals(before.target.longitude, after.target.longitude, 0.00001)
                assertEquals(before.target.latitude, after.target.latitude, 0.00001)
                assertEquals(before.zoom, after.zoom, 0.01)
                assertEquals(before.bearing, after.bearing, 0.01)
                assertEquals(if (tilted) 45.0 else 0.0, after.tilt, 0.01)
            }
        }
    }
}
