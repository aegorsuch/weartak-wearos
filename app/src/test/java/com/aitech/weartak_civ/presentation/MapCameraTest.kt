package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MapCameraTest {
    private val chicago = Position(-87.640974, 41.880029)
    private val startup = CameraPosition(target = Position(0.0, 0.0), zoom = 15.5, tilt = 45.0)
    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("camera-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun cameraSurvivesColdLaunchWithoutChangingServerSourceOrTiltPreferences() {
        val preferences = preferences()
        preferences.edit().putString("map_source", "default").putBoolean("map_tilted_view", true)
            .putString("server", "unchanged").putStringSet("hidden_layers", setOf("contours")).commit()
        val unrelated = preferences.all
        val camera = startup.copy(target = chicago, zoom = 17.25, bearing = 123.0)
        MapCameraPreferences(preferences).save(camera)
        val restored = requireNotNull(MapCameraPreferences(preferences).load())
        assertEquals(camera.target, restored.target)
        assertEquals(camera.zoom, restored.zoom, 0.0)
        assertEquals(camera.bearing, restored.bearing, 0.0)
        assertEquals(unrelated, preferences.all.filterKeys { it != "map_camera" })
        val bootstrap = MapCameraBootstrap(restored, false, true)
        assertNull(bootstrap.firstFix(Position(-122.084, 37.422), false))
        assertTrue(bootstrap.canPersist)
    }

    @Test fun delayedFirstSelfFixCentersOnceWithoutFiveSecondTimeout() {
        val bootstrap = MapCameraBootstrap(startup, true, false)
        bootstrap.observe(startup)
        assertFalse("Uninitialized Null Island must not be persisted", bootstrap.canPersist)
        repeat(5) { assertNull(bootstrap.firstFix(null, false)) }
        assertEquals(chicago, bootstrap.firstFix(chicago, false))
        assertTrue(bootstrap.canPersist)
        assertNull(bootstrap.firstFix(Position(-122.084, 37.422), false))
    }

    @Test fun userPanBeforeDelayedFixWinsEvenBeforeNativeCameraReportsMovement() {
        val bootstrap = MapCameraBootstrap(startup, true, false)
        bootstrap.userPanned()
        assertNull(bootstrap.firstFix(chicago, false))
        assertFalse(bootstrap.canPersist)
        bootstrap.observe(startup.copy(target = Position(1.0, 2.0)))
        assertTrue(bootstrap.canPersist)
    }

    @Test fun cameraNavigationAndExplicitMapFocusPreventAutomaticRecentering() {
        for (moved in listOf(startup.copy(target = chicago), startup.copy(zoom = 16.0),
            startup.copy(bearing = 20.0))) {
            val bootstrap = MapCameraBootstrap(startup, true, false)
            bootstrap.observe(moved)
            assertNull(bootstrap.firstFix(chicago, false))
            assertTrue(bootstrap.canPersist)
        }
        val focused = MapCameraBootstrap(startup, true, false)
        assertNull(focused.firstFix(null, true))
        assertNull(focused.firstFix(chicago, false))
        assertFalse(focused.canPersist)
    }

    @Test fun pitchAndStyleChangesDoNotConsumeFirstFixOrInventCameraCoordinates() {
        val bootstrap = MapCameraBootstrap(startup, true, false)
        bootstrap.observe(startup.copy(tilt = 0.0))
        assertFalse(bootstrap.canPersist)
        assertEquals(chicago, bootstrap.firstFix(chicago, false))
        assertNull(MapCameraPreferences(preferences()).load())
    }

    @Test fun realInitialLocationAndIntentionalNullIslandViewAreRetained() {
        assertNull(MapCameraBootstrap(startup.copy(target = chicago), false, true).firstFix(Position(1.0, 2.0), false))
        val preferences = preferences()
        MapCameraPreferences(preferences).save(startup)
        assertEquals(startup.target, MapCameraPreferences(preferences).load()!!.target)
    }

    @Test fun malformedCameraIsRejectedAndInvalidWritesPreserveLastValidView() {
        val preferences = preferences()
        val store = MapCameraPreferences(preferences)
        preferences.edit().putString("map_camera", "{invalid").commit()
        assertNull(store.load())
        preferences.edit().putString("map_camera",
            """{"longitude":181,"latitude":0,"zoom":15,"bearing":0}""").commit()
        assertNull(store.load())
        store.save(startup.copy(target = chicago))
        store.save(startup.copy(zoom = Double.NaN))
        assertEquals(chicago, store.load()!!.target)
    }

    @Test fun firstProcessOpenPrefersSelfOverSavedViewButLaterOpensKeepSavedView() {
        val session = MapOpenSession()
        val saved = startup.copy(target = chicago, zoom = 17.0, bearing = 123.0)
        val self = Position(-122.084, 37.422)
        val first = initialMapCamera(saved, self, session.claimFirstOpen(), false)
        assertEquals(self, first.target)
        assertEquals(saved.zoom, first.zoom, 0.0)
        assertEquals(saved.bearing, first.bearing, 0.0)
        assertEquals(saved, initialMapCamera(saved, self, session.claimFirstOpen(), false))
        assertEquals(saved, initialMapCamera(saved, self, true, true))
        assertTrue("A new process starts a new first-open policy", MapOpenSession().claimFirstOpen())
    }

    @Test fun missingSelfRestoresSavedViewThenFirstFixOverridesItUnlessUserNavigates() {
        val saved = startup.copy(target = chicago)
        assertEquals(saved, initialMapCamera(saved, null, true, false))
        val bootstrap = MapCameraBootstrap(saved, true, true)
        assertTrue(bootstrap.canPersist)
        val self = Position(-122.084, 37.422)
        assertEquals(self, bootstrap.firstFix(self, false))
        assertNull(bootstrap.firstFix(chicago, false))
        val zoomed = MapCameraBootstrap(saved, true, true)
        zoomed.observe(saved.copy(zoom = saved.zoom + 1))
        assertNull(zoomed.firstFix(self, false))
        val panned = MapCameraBootstrap(saved, true, true)
        panned.userPanned()
        assertNull(panned.firstFix(self, false))
        assertNull(MapCameraBootstrap(saved, false, true).firstFix(self, false))
    }

    @Test fun nativeFloatingPointJitterDoesNotCancelDelayedSelfFixOrSaveNullIsland() {
        val bootstrap = MapCameraBootstrap(startup, true, false)
        bootstrap.observe(startup.copy(target = Position(1e-14, -1e-14),
            zoom = 15.500000000000002, bearing = -0.0))
        assertFalse(bootstrap.canPersist)
        assertEquals(chicago, bootstrap.firstFix(chicago, false))
    }
}
