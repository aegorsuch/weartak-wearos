package com.aitech.weartak_civ.presentation

import android.app.KeyguardManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RouteMapInteractionTest {
    @Test
    fun nativeMapSelectsLineNearMissAndExactVertexWithoutChangingStoredRoutes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(!instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "Unlock the watch before running map interaction checks"
        }
        val route = TakRoute("test-only", "Touch test route", listOf(
            TakRoutePoint(0.0, -0.001, name = "Start"),
            TakRoutePoint(0.0, 0.001, name = "Finish")
        ), "test-only-server")
        var cameraState: CameraState? = null
        var density = 1f
        val selection = AtomicReference<RouteMapSelection?>()
        val clicked = AtomicReference(CountDownLatch(1))
        val loaded = CountDownLatch(1)
        var origin = Offset.Zero
        var viewport = androidx.compose.ui.unit.IntSize.Zero
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val camera = rememberCameraState(firstPosition =
                        CameraPosition(target = Position(0.0, 0.0), zoom = 15.0))
                    cameraState = camera
                    density = LocalDensity.current.density
                    MaplibreMap(
                        modifier = Modifier.fillMaxSize().onGloballyPositioned {
                            origin = it.positionInWindow()
                            viewport = it.size
                        },
                        baseStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}"""),
                        cameraState = camera,
                        onMapLoadFinished = { loaded.countDown() },
                        onMapClick = { _, offset ->
                            val hit = camera.projection?.let { projection ->
                                findRouteMapSelection(listOf(route),
                                    RouteScreenPoint(offset.x.value.toDouble(), offset.y.value.toDouble()), true
                                ) { lat, lon ->
                                    val screen = projection.screenLocationFromPosition(Position(lon, lat))
                                    RouteScreenPoint(screen.x.value.toDouble(), screen.y.value.toDouble())
                                }
                            }
                            selection.set(hit)
                            clicked.get().countDown()
                            if (hit == null) ClickResult.Pass else ClickResult.Consume
                        }
                    ) {
                        RoutesLayer(listOf(RouteCollection(route.serverId, route.uid, route.name, "White",
                            route.points.map { RouteItem(it.latitude, it.longitude, true, it.name) })),
                            camera, { _, _, _ -> }, { _, _ -> })
                    }
                }
            }
            assertEquals("Fixture style must load", true, loaded.await(20, TimeUnit.SECONDS))
            var ready = false
            repeat(50) {
                instrumentation.runOnMainSync { ready = cameraState?.projection != null }
                if (!ready) SystemClock.sleep(200)
            }
            check(ready) { "Map projection did not become ready" }
            fun screen(position: Position): Offset {
                var offset = Offset.Zero
                instrumentation.runOnMainSync {
                    val projected = requireNotNull(cameraState?.projection).screenLocationFromPosition(position)
                    val local = Offset(projected.x.value * density, projected.y.value * density)
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    check((local - center).getDistance() < minOf(viewport.width, viewport.height) / 2f - 12f * density) {
                        "Route fixture target must be inside round watch: $local in $viewport"
                    }
                    offset = origin + local
                }
                return offset
            }
            fun select(offset: Offset) {
                val latch = CountDownLatch(1)
                clicked.set(latch)
                selection.set(null)
                tap(offset)
                check(latch.await(5, TimeUnit.SECONDS)) { "Injected tap did not reach fixture map" }
            }
            select(screen(Position(0.0, 0.0)) + Offset(0f, 8f * density))
            assertEquals(RouteMapSelection(route.serverId, route.uid), selection.get())
            select(screen(Position(-0.001, 0.0)))
            assertEquals(RouteMapSelection(route.serverId, route.uid, 0), selection.get())
        }
    }

    private fun tap(offset: Offset) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, offset.x, offset.y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, offset.x, offset.y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            check(automation.injectInputEvent(down, true))
            check(automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
        SystemClock.sleep(600)
    }
}
