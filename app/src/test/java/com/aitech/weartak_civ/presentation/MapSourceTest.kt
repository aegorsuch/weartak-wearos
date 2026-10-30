package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapSourceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun original() = context.assets.open("style.json").bufferedReader().use { it.readText() }

    @Test fun existingMapIsUnchangedUntilExplicitSelectionAndCanBeRestored() {
        val prefs = context.getSharedPreferences("map-source-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = MapSourcePreferences(prefs)
        assertEquals(MapSource.DEFAULT, store.load())
        assertEquals("default", MapSource.DEFAULT.preferenceValue)
        assertEquals("OSM Vector", store.load().title)
        val original = original()
        assertEquals(original, mapSourceStyle(original, store.load()))
        for (source in MapSource.selectable) {
            store.save(source)
            assertEquals(source, MapSourcePreferences(prefs).load())
        }
        prefs.edit().putString("map_source", "removed-provider").commit()
        assertEquals(MapSource.DEFAULT, store.load())
    }

    @Test fun changingSourceDoesNotModifySavedOverlayAndLayerFilters() {
        val prefs = context.getSharedPreferences("map-source-filters-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putStringSet("hidden_layers", setOf("contours", "road-labels"))
            .putBoolean("show_markers", false).putStringSet("visible_user_roles_standard", setOf("Medic"))
            .commit()
        val before = prefs.all
        MapSourcePreferences(prefs).save(MapSource.BING_HYBRID)
        assertEquals(before, prefs.all.filterKeys { it != "map_source" })
    }

    @Test fun pitchChoicePersistsAcrossSourcesAndPreservesCamera() {
        val prefs = context.getSharedPreferences("building-opt-in", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = MapSourcePreferences(prefs)
        assertFalse(store.tiltedView())
        store.saveTiltedView(true)
        MapSource.selectable.forEach {
            store.save(it)
            assertTrue(MapSourcePreferences(prefs).tiltedView())
            assertEquals(if (it == MapSource.DEFAULT) 45.0 else 0.0,
                mapPitchForSource(it, store.tiltedView()), 0.0)
            assertTrue(showMapPitchButton(false, false))
            assertFalse(showMapPitchButton(true, false))
            assertFalse(showMapPitchButton(false, true))
        }
        store.saveTiltedView(false)
        assertFalse(MapSourcePreferences(prefs).tiltedView())
        val original = org.maplibre.compose.camera.CameraPosition(
            target = org.maplibre.spatialk.geojson.Position(-74.009, 40.706),
            zoom = 16.0, bearing = 25.0, tilt = 12.0)
        for (tilted in listOf(true, false)) {
            val updated = mapPitchPosition(original, tilted)
            assertEquals(if (tilted) 45.0 else 0.0, updated.tilt, 0.0)
            assertEquals(original, updated.copy(tilt = original.tilt))
            val hidden = setOf("contours", "hillshade")
            assertEquals(!tilted, BUILDINGS_3D_LAYER in mapHiddenLayersForPitch(hidden, updated.tilt))
            assertTrue(mapHiddenLayersForPitch(hidden, updated.tilt).containsAll(hidden))
        }

    }

    @Test fun tiltedSafetyGeometryPreservesRoutesPointsRingsAndSelf() {
            val route = RouteCollection("server", "route", "Route", "Cyan", listOf(
                RouteItem(40.706, -74.009, true, "Start"),
                RouteItem(40.707, -74.008, true, "End")))
            val self = org.maplibre.spatialk.geojson.Position(-74.009, 40.706)
            val geometry = tacticalGroundGeometry(listOf(route), emptyList(), self,
                androidx.compose.ui.graphics.Color.Green, self, true)
            assertEquals(3, geometry.first.size)
            assertEquals(2, geometry.second.size)
            assertEquals(2, geometry.second.first().positions.size)
            assertEquals(self, geometry.first.last().position)
            val zoomedOut = tacticalGroundGeometry(listOf(route), emptyList(), null,
                androidx.compose.ui.graphics.Color.Green, null, false)
            assertTrue(zoomedOut.first.isEmpty())
            assertEquals(1, zoomedOut.second.size)
    }

    @Test fun pitchButtonShowsCurrentModeAndAccessibleSnapAction() {
        val tilted = mutableStateOf(false)
        compose.setContent { MapPitchButton(tilted.value, { tilted.value = !tilted.value }) }
        compose.onNodeWithText("2D").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to 3D tilted view").performClick()
        compose.onNodeWithText("3D").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to 2D overhead view").performClick()
        compose.onNodeWithText("2D").assertIsDisplayed()
    }

    @Test fun unavailablePitchButtonKeepsPositionButCannotChangeMode() {
        var clicks = 0
        compose.setContent {
            MapPitchButton(tilted = true, onClick = { clicks++ }, enabled = false)
        }
        compose.onNodeWithText("2D").assertIsDisplayed()
        compose.onNodeWithContentDescription("2D view. Tilt is only available on OSM Vector")
            .assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test fun hybridStylesReplaceOnlyBasemapAndRetainTacticalGlyphs() {
        val original = original()
        for (source in MapSource.selectable.filter { it != MapSource.DEFAULT }) {
            val style = JSONObject(mapSourceStyle(original, source))
            assertEquals(8, style.getInt("version"))
            assertEquals(JSONObject(original).getString("glyphs"), style.getString("glyphs"))
            assertEquals(setOf("hybrid"), style.getJSONObject("sources").keys().asSequence().toSet())
            val raster = style.getJSONObject("sources").getJSONObject("hybrid")
            assertEquals("raster", raster.getString("type"))
            assertEquals("xyz", raster.getString("scheme"))
            assertEquals(256, raster.getInt("tileSize"))
            assertEquals(20, raster.getInt("maxzoom"))
            assertEquals(source.attribution, raster.getString("attribution"))
            assertEquals(source.tileTemplate, raster.getJSONArray("tiles").getString(0))
            val layers = style.getJSONArray("layers")
            assertEquals(listOf("background", "hybrid"),
                (0 until layers.length()).map { layers.getJSONObject(it).getString("id") })
        }
    }

    @Test fun templatesUseNativeMapLibreTokensNotAtakPlaceholders() {
        val bing = MapSource.BING_HYBRID.tileTemplate!!
        assertTrue(bing.startsWith("https://"))
        assertTrue(bing.contains("h{quadkey}?"))
        assertFalse(bing.contains("{\$q}"))
        val google = MapSource.GOOGLE_HYBRID.tileTemplate!!
        assertTrue(google.startsWith("https://"))
        assertTrue(google.contains("lyrs=y&x={x}&y={y}&z={z}"))
    }

    @Test fun retryChangesStyleIdentityWithoutChangingSourcesOrLayers() {
        for (source in MapSource.selectable) {
            val first = JSONObject(mapSourceStyle(original(), source))
            val retried = JSONObject(mapSourceStyle(original(), source, 1))
            assertNotEquals(first.getString("name"), retried.getString("name"))
            assertEquals(first.getJSONObject("sources").toString(), retried.getJSONObject("sources").toString())
            assertEquals(first.getJSONArray("layers").toString(), retried.getJSONArray("layers").toString())
        }
    }

    @Test fun nativeTileErrorsAreMatchedOnlyToActiveBasemapSources() {
        val originalFailure = "Failed to load tile 15/1/1=>15 for source tak-maps: Chain validation failed"
        val hybridFailure = "Failed to load tile 15/1/1=>15 for source hybrid: HTTP 403"
        assertTrue(isBaseMapTileFailure(MapSource.DEFAULT, originalFailure))
        assertFalse(isBaseMapTileFailure(MapSource.BING_HYBRID, originalFailure))
        assertTrue(isBaseMapTileFailure(MapSource.BING_HYBRID, hybridFailure))
        assertFalse(isBaseMapTileFailure(MapSource.DEFAULT, hybridFailure))
        assertFalse(isBaseMapTileFailure(MapSource.DEFAULT, "This request was cancelled"))
        assertFalse(isBaseMapTileFailure(MapSource.DEFAULT,
            "Failed to load tile 1/1/1 for source tactical-overlay: HTTP 404"))
    }

    @Test fun pickerSelectsEachProviderAndOffersVisibleFailureRecovery() {
        val source = mutableStateOf(MapSource.DEFAULT)
        var retries = 0
        compose.setContent {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                MapSourcePicker(source.value, { source.value = it }, "Map failed", { retries++ })
            }
        }
        compose.onNodeWithText("OSM Vector").assertIsSelected()
        compose.onNodeWithText("Google Hybrid").assertExists()
        compose.onNodeWithText("Bing Hybrid").performScrollTo().performClick()
        compose.onNodeWithText("Bing Hybrid").assertIsSelected()
        compose.runOnIdle { assertEquals(MapSource.BING_HYBRID, source.value) }
        compose.onNodeWithText("Google Hybrid").performScrollTo().performClick()
        compose.onNodeWithText("Google Hybrid").assertIsSelected()
        compose.runOnIdle { assertEquals(MapSource.GOOGLE_HYBRID, source.value) }
        compose.onNodeWithText("Retry map").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Restore original map").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MapSource.DEFAULT, source.value) }
    }

    @Test fun unifiedRolesKeepStandardAndLeoActionsIndependent() {
        var standard = ""
        var leo = ""
        var dismissals = 0
        compose.setContent {
            LayerTogglePopup(
                mapSource = MapSource.DEFAULT, onMapSourceSelected = {},
                mapError = null, onRetryMap = {},
                topoLayers = emptyList(), hiddenLayers = emptySet(), onToggle = {},
                hideControls = false, onHideControlsToggle = {},
                visibleUserTeams = setOf("White"),
                visibleUserRoles = setOf("Medic"), visibleUserRolesLEO = setOf("TOC"),
                myRole = "", roleCounts = mapOf("Medic" to 1, "TOC" to 1),
                teamColorCount = mapOf("White" to 1),
                onRoleToggle = { standard = it }, onLeoRoleToggle = { leo = it },
                onTeamToggle = {}, overlays = emptyList(),
                availableTeams = setOf("White"), availableUserRoles = setOf("Medic"),
                availableUserRolesLEO = setOf("TOC"), onDismiss = { dismissals++ }
            )
        }
        compose.onNodeWithText("Overlay Manager").assertIsDisplayed()
        compose.onNodeWithText("Roles (2)").performScrollTo().assertExists()
        compose.onNodeWithText("LEO / Civil Use Roles", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Medic (1)").performScrollTo().performClick()
        compose.onNodeWithText("TOC (1)").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Medic", standard)
            assertEquals("TOC", leo)
            assertEquals(listOf("Bing Hybrid", "Google Hybrid", "OSM Vector"),
                MapSource.selectable.map { it.title })
        }
        compose.onNodeWithTag("layers-menu-top-back").performScrollTo().performClick()
        compose.onNodeWithTag("layers-menu-bottom-back").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }

    }

    @Test fun mapBackIsAbsentFromAccessibilityWhileLayersAreOpen() {
        val layersOpen = mutableStateOf(true)
        var exits = 0
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                MapBackButton(!layersOpen.value, { exits++ })
                if (layersOpen.value) LayersMenuBackButton({ layersOpen.value = false })
            }
        }
        compose.onNodeWithContentDescription("Back from map").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to map").performClick()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithContentDescription("Back to map").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back from map").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun channelsAreAbsentWhenButtonsHiddenAndRestoreWithoutHidingManager() {
        val hidden = mutableStateOf(false)
        var channelClicks = 0
        var managerClicks = 0
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                MapChannelsButton(true, { channelClicks++ }, visible = !hidden.value)
                MapLayersButton({ managerClicks++ })
            }
        }
        compose.onNodeWithContentDescription("Channels").assertIsDisplayed().performClick()
        compose.runOnIdle { hidden.value = true }
        compose.onNodeWithContentDescription("Channels").assertDoesNotExist()
        compose.onNodeWithContentDescription("Overlay Manager").assertIsDisplayed().performClick()
        compose.runOnIdle { hidden.value = false }
        compose.onNodeWithContentDescription("Channels").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(2, channelClicks)
            assertEquals(1, managerClicks)
        }
    }

    @Test fun compactTakCreditFitsTwoLinesAtSimulatorWidth() {
        var lineCount = 0
        val credit = compactTakAttribution(
            context.getString(com.aitech.weartak_civ.R.string.map_tak_attribution_compact))
        assertEquals(6.sp, credit.spanStyles.single().item.fontSize)
        assertEquals(0, credit.spanStyles.single().start)
        assertEquals(credit.length, credit.spanStyles.single().end)
        compose.setContent {
            androidx.compose.foundation.layout.Box(Modifier.width(151.dp)) {
                androidx.wear.compose.material.Text(
                    credit,
                    fontSize = 8.sp, lineHeight = 9.sp,
                    modifier = Modifier.padding(horizontal = 3.dp),
                    onTextLayout = { lineCount = it.lineCount }
                )
            }
        }
        compose.runOnIdle { assertEquals(2, lineCount) }
    }

    @Test fun layersButtonBoundsStayFixedBelowEveryProviderCredit() {
        val source = mutableStateOf(MapSource.DEFAULT)
        compose.setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.size(227.dp)
            ) {
                androidx.wear.compose.material.Text(
                    if (source.value == MapSource.DEFAULT)
                        compactTakAttribution(context.getString(
                            com.aitech.weartak_civ.R.string.map_tak_attribution_compact))
                    else androidx.compose.ui.text.AnnotatedString(source.value.attribution),
                    fontSize = 6.sp, lineHeight = 9.sp,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        .padding(top = 8.dp).testTag("top-credit")
                )
                androidx.compose.foundation.layout.Box(
                    Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(10.dp)
                ) { MapLayersButton({}) }
            }
        }
        var baseline: androidx.compose.ui.geometry.Rect? = null
        MapSource.selectable.forEach { selected ->
            compose.runOnIdle { source.value = selected }
            val button = compose.onNodeWithContentDescription("Overlay Manager")
                .fetchSemanticsNode().boundsInRoot
            val credit = compose.onNodeWithTag("top-credit")
                .fetchSemanticsNode().boundsInRoot
            if (baseline == null) baseline = button else assertEquals(baseline, button)
            assertTrue("Credits must end above Layers", credit.bottom <= button.top)
        }
    }

    @Test fun overlayManagerHeadingFitsOneLineBesideBackArrow() {
        var lines = 0
        var overflow = true
        compose.setContent {
            androidx.compose.foundation.layout.Row(
                Modifier.width(126.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.wear.compose.material.Text(
                    context.getString(com.aitech.weartak_civ.R.string.map_overlay_manager),
                    fontSize = 10.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    onTextLayout = {
                        lines = it.lineCount
                        overflow = it.hasVisualOverflow
                    }
                )
                LayersMenuBackButton({})
            }
        }
        compose.runOnIdle {
            assertEquals(1, lines)
            assertFalse(overflow)
        }
    }

    @Test fun creditsExposeActiveSourceLinksForEveryProvider() {
        val source = mutableStateOf(MapSource.DEFAULT)
        val opened = mutableListOf<String>()
        compose.setContent {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                MapCreditsSection(source.value) { opened += it }
            }
        }
        for (provider in MapSource.selectable) {
            compose.runOnIdle { source.value = provider }
            compose.onNodeWithText("Credits").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("map-credits-disclosure").assert(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
            mapCredits(provider).forEach {
                compose.onNodeWithText(it.label).assertDoesNotExist()
            }
            compose.onNodeWithTag("map-credits-disclosure").performClick().assert(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Expanded"))
            for (credit in mapCredits(provider)) {
                compose.onNodeWithText(credit.label).performScrollTo().assertIsEnabled().performClick()
                compose.runOnIdle {
                    assertEquals(credit.url, opened.last())
                    assertTrue(credit.url.startsWith("https://"))
                }
            }
            compose.onNodeWithTag("map-credits-disclosure").performScrollTo().performClick()
            mapCredits(provider).forEach {
                compose.onNodeWithText(it.label).assertDoesNotExist()
            }
        }
    }

    @Test fun compactPickerKeepsThreeDistinctNonOverlappingRows() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                MapSourcePicker(MapSource.DEFAULT, {}, null, {})
            }
        }
        val rows = MapSource.selectable.map {
            compose.onNodeWithTag("map-source-mark-${it.preferenceValue}", useUnmergedTree = true)
                .assertHeightIsEqualTo(14.dp)
            compose.onNodeWithTag("map-source-${it.preferenceValue}")
                .assertIsDisplayed().assertHeightIsEqualTo(28.dp)
                .fetchSemanticsNode().boundsInRoot
        }

        rows.zipWithNext().forEach { (first, next) ->
            assertTrue(first.bottom <= next.top)
        }
        compose.onNodeWithText("OSM Vector").assertIsSelected()
    }

    @Test fun buildingExtrusionsRetainExistingCompositionAndUseOnlySourceHeights() {
            val original = JSONObject(original())
            val updated = JSONObject(withBuildingExtrusions(original.toString()))
            val expectedSources = original.getJSONObject("sources")
            expectedSources.getJSONObject("hillshade").put("maxzoom", 17)
            assertEquals(expectedSources.toString(), updated.getJSONObject("sources").toString())
            assertEquals(20.0, MapSource.DEFAULT.maxCameraZoom, 0.0)
            assertEquals(22.0, MapSource.GOOGLE_HYBRID.maxCameraZoom, 0.0)
            val layers = updated.getJSONArray("layers")
            val building = (0 until layers.length()).map { layers.getJSONObject(it) }
                .single { it.getString("id") == BUILDINGS_3D_LAYER }
            assertEquals("fill-extrusion", building.getString("type"))
            assertEquals("tak-maps", building.getString("source"))
            assertEquals("building", building.getString("source-layer"))
            assertEquals(14, building.getInt("minzoom"))
            assertTrue(building.getJSONObject("paint").getJSONArray("fill-extrusion-height")
                .toString().contains("render_height"))
            assertTrue(building.getJSONObject("paint").getJSONArray("fill-extrusion-base")
                .toString().contains("render_min_height"))
            assertTrue(building.getJSONArray("filter").toString().contains("hide_3d"))
            val unchanged = JSONArray()
            (0 until layers.length()).map { layers.getJSONObject(it) }
                .filter { it.getString("id") != BUILDINGS_3D_LAYER }.forEach { unchanged.put(it) }
            assertEquals(original.getJSONArray("layers").toString(), unchanged.toString())
        }
    @Test fun hybridSourcesDisableOnlyBaseLayerControlsAndTakReenablesThem() {
            val source = mutableStateOf(MapSource.BING_HYBRID)
            var toggles = 0
            compose.setContent {
                LayerTogglePopup(
                    mapSource = source.value, onMapSourceSelected = { source.value = it },
                    mapError = null, onRetryMap = {},
                    topoLayers = listOf("contours" to "Contours"),
                    hiddenLayers = setOf("contours"), onToggle = { toggles++ },
                    hideControls = false, onHideControlsToggle = {},
                    visibleUserTeams = emptySet(), visibleUserRoles = emptySet(),
                    visibleUserRolesLEO = emptySet(), myRole = "",
                    roleCounts = emptyMap(), teamColorCount = emptyMap(),
                    onRoleToggle = {}, onLeoRoleToggle = {}, onTeamToggle = {},
                    overlays = emptyList(), availableTeams = emptySet(),
                    availableUserRoles = emptySet(), availableUserRolesLEO = emptySet(),
                    onDismiss = {}
                )
            }
            compose.onNodeWithText("Contours").performScrollTo().assertIsNotEnabled().performClick()
            compose.onNodeWithText("Buttons").performScrollTo().assertIsEnabled()
            compose.runOnIdle { assertEquals(0, toggles) }
            compose.onNodeWithText("Google Hybrid").performScrollTo().performClick()
            compose.onNodeWithText("Contours").performScrollTo().assertIsNotEnabled().performClick()
            compose.runOnIdle { assertEquals(0, toggles) }
            compose.onNodeWithText("OSM Vector").performScrollTo().performClick()
            compose.onNodeWithText("Contours").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(1, toggles) }
    }
}
