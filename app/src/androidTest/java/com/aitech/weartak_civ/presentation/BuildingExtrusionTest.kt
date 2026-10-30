package com.aitech.weartak_civ.presentation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.VectorSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BuildingExtrusionTest {
    @Test fun realUrbanBuildingsRenderAndToggleWithoutMovingCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = context.assets.open("style.json").bufferedReader().use { it.readText() }
        val styleJson = withBuildingExtrusions(original)
        var view: MapView? = null
        var map: MapLibreMap? = null
        val loaded = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MapLibre.getInstance(activity)
                val mapView = MapView(activity)
                view = mapView
                activity.setContentView(mapView)
                mapView.onCreate(null)
                mapView.onStart()
                mapView.onResume()
                mapView.getMapAsync { nativeMap ->
                    map = nativeMap
                    nativeMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(40.706, -74.009))
                            .zoom(16.0).tilt(60.0).bearing(25.0).build()
                    ))
                    nativeMap.setStyle(Style.Builder().fromJson(styleJson)) { loaded.countDown() }
                }
            }
            assertTrue("Native style did not load", loaded.await(30, TimeUnit.SECONDS))
            var buildings = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (buildings == 0 && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    val source = map?.style?.getSourceAs<VectorSource>("tak-maps")
                    buildings = source?.querySourceFeatures(arrayOf("building"), null)
                        ?.count { it.hasProperty("render_height") &&
                            it.getNumberProperty("render_height").toDouble() > 0 } ?: 0
                }
                if (buildings == 0) Thread.sleep(250)
            }
            assertTrue("No height-bearing real building polygons loaded", buildings > 0)
            Thread.sleep(1500)
            fun snapshot(name: String): Bitmap {
                val ready = CountDownLatch(1)
                var result: Bitmap? = null
                instrumentation.runOnMainSync {
                    requireNotNull(map).snapshot { image ->
                        result = image
                        ready.countDown()
                    }
                }
                assertTrue("Snapshot timed out", ready.await(15, TimeUnit.SECONDS))
                return requireNotNull(result).also { bitmap ->
                    File(context.getExternalFilesDir(null), name).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            val on = snapshot("buildings-on.png")
            var camera: CameraPosition? = null
            instrumentation.runOnMainSync {
                camera = requireNotNull(map).cameraPosition
                val layer = requireNotNull(map?.style?.getLayer(BUILDINGS_3D_LAYER))
                assertTrue(layer is FillExtrusionLayer)
                layer.setProperties(visibility(Property.NONE))
            }
            Thread.sleep(500)
            val off = snapshot("buildings-off.png")
            var changed = 0
            for (y in 0 until on.height) for (x in 0 until on.width) {
                if (on.getPixel(x, y) != off.getPixel(x, y)) changed++
            }
            assertTrue("Extrusions made no visible geometry difference: $changed", changed > 1000)
            instrumentation.runOnMainSync {
                requireNotNull(map?.style?.getLayer(BUILDINGS_3D_LAYER))
                    .setProperties(visibility(Property.VISIBLE))
                assertEquals(camera, map?.cameraPosition)
            }
            for (source in listOf(MapSource.GOOGLE_HYBRID, MapSource.BING_HYBRID, MapSource.DEFAULT)) {
                val switched = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    requireNotNull(map).setStyle(Style.Builder().fromJson(
                        if (source == MapSource.DEFAULT) styleJson else mapSourceStyle(original, source)
                    )) { switched.countDown() }
                }
                assertTrue(switched.await(20, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertEquals(camera, map?.cameraPosition)
                    assertEquals(source == MapSource.DEFAULT,
                        map?.style?.getLayer(BUILDINGS_3D_LAYER) != null)
                }
            }
            instrumentation.runOnMainSync {
                view?.onPause()
                view?.onStop()
                view?.onDestroy()
            }
        }
    }
}
