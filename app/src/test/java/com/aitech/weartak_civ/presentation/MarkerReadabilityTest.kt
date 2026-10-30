package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository.MarkerCotType
import com.aitech.weartak_civ.data.DataRepository.MarkerType
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.MarkerViewModel
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w300dp-h300dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkerReadabilityTest {
    @get:Rule val compose = createComposeRule()
    private val lat = 35.12345678901234
    private val lon = -117.98765432109876
    private val title = "TEST01-W_193243Z"

    @Test fun completePinDetailsAndCallsignAt480Font13() = details(480, 1.3f)
    @Test fun completePinDetailsAndCallsignAt498Font11() = details(498, 1.1f)

    @Test fun ownedDeleteWarningAndActionsRemainVisibleAtFont13() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val description = "Delete Marker?\nFriendly: $title\n" + app.getString(R.string.owned_pin_delete_help)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(340f / 160, 1.3f)) {
                val visible = remember { mutableStateOf(true) }
                val confirmed = remember { mutableStateOf(false) }
                WearTAKCIVTheme {
                    ClearMarkerConfirmationDialog(visible, description, {}, confirmed, {})
                }
            }
        }
        compose.onNodeWithText(description).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.confirm)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.cancel)).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(description).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().didOverflowHeight)
        assertFalse(layouts.single().didOverflowWidth)
    }

    private fun details(pixels: Int, fontScale: Float) {
        val density = 340f / 160
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WearTAKCIVTheme {
                    Column(Modifier.requiredWidth((pixels / density).dp).padding(horizontal = 28.dp)) {
                        Callsign(LocalContext.current, "TEST01-WATCH")
                        Box(Modifier.height(100.dp)) { MarkerBearingDistance(.094, lat, lon, 170f) }
                        SwipeableMarkerChip(MarkerType.FRIENDLY.type, title, MarkerCotType.DROP,
                            false, 100L, {}, {})
                    }
                }
            }
        }
        for (text in listOf("TEST01-WATCH", "35.12346", "-117.98765", Utils.latLonToMgrs(lat, lon), title)) {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals(text, 1, layout.lineCount)
            assertFalse(text, layout.didOverflowWidth)
            assertFalse(text, layout.didOverflowHeight)
            assertFalse(text, layout.isLineEllipsized(0))
            assertTrue("$text is too small", layout.layoutInput.style.fontSize.value >= 8f)
        }
        compose.onNodeWithText("35.12345678901234", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(35.12345678901234, lat, 0.0)
        assertEquals(-117.98765432109876, lon, 0.0)
    }

    @Test fun emptyDroppedMarkerPageHasReadableStateAndReturnsToExistingScreen() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(340f / 160, 1.3f)) {
                val nav = rememberSwipeDismissableNavController()
                val dialog = remember { mutableStateOf(false) }
                val success = remember { mutableStateOf(false) }
                val remove = remember { mutableStateOf(false) }
                WearTAKCIVTheme {
                    Box(Modifier.requiredSize((480 / (340f / 160)).dp)) {
                        SwipeDismissableNavHost(nav, startDestination = "long_press_marker_screen") {
                            composable("long_press_marker_screen") {
                                LongPressScreen(nav, emptyList(), dialog, success, remove)
                            }
                            composable("dropped_marker_screen") {
                                DroppedMarkerSetting(nav, mockk<MarkerViewModel>(relaxed = true),
                                    emptyList(), dialog, success)
                            }
                        }
                    }
                }
            }
        }
        repeat(2) {
            compose.onNodeWithText("Dropped Markers").performClick()
            compose.onNodeWithText("No Dropped Markers").assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Clear Dropped Markers")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(Color.White, layouts.single().layoutInput.style.color)
            compose.onNodeWithText("Clear All Markers").assertDoesNotExist()
            compose.onNodeWithContentDescription("Go back to Main Settings Screen").performClick()
            compose.onNodeWithText("Dropped Markers").assertIsDisplayed()
        }
    }

    @Test fun overlapPickerRetainsEveryContactAndPinAtFont13AndSelectsExactIdentity() {
        val targets = listOf(
            MapSelectionTarget("same", "Contact One", MapTargetKind.CONTACT, lat, lon),
            MapSelectionTarget("same", title, MapTargetKind.DROPPED, lat, lon),
            MapSelectionTarget("received", "Other Pin", MapTargetKind.RECEIVED, lat, lon))
        var selected: MapSelectionTarget? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(340f / 160, 1.3f)) {
                MapTargetPicker(targets, { selected = it }, {})
            }
        }
        for (target in targets) {
            compose.onNodeWithText(target.title, useUnmergedTree = true).performScrollTo().performClick()
            assertEquals(target, selected)
        }
    }

    @Test fun groupedLabelsStayEmptyWithoutChangingFeatureIdentityOrCoordinates() {
        val marker = MapMarkerItem("pin", MarkerType.FRIENDLY.type, title, lat, lon, false,
            MarkerCotType.DROP, "server", mapLabel = "", selectionKey = "selected-pin")
        val user = MapUserItem("user", "a-f-G-U-C", "Blue", "Contact One", lat, lon,
            "Team Member", "server", mapLabel = "", selectionKey = "selected-user")
        var checked = false
        compose.setContent {
            val markers = rememberMarkerFeatures(listOf(marker))
            val users = rememberOtherUsersFeatures(listOf(user))
            SideEffect {
                val pin = markers.features.single()
                val contact = users.features.single()
                assertEquals("\"\"", pin.properties?.get("title")?.toString())
                assertEquals("\"\"", contact.properties?.get("title")?.toString())
                assertEquals("\"pin\"", pin.properties?.get("id")?.toString())
                assertEquals("\"selected-pin\"", pin.properties?.get("selectionKey")?.toString())
                assertEquals(lat, pin.geometry.coordinates.latitude, 0.0)
                assertEquals(lon, pin.geometry.coordinates.longitude, 0.0)
                assertEquals(lat, contact.geometry.coordinates.latitude, 0.0)
                assertEquals(lon, contact.geometry.coordinates.longitude, 0.0)
                checked = true
            }
        }
        compose.runOnIdle { assertTrue(checked) }
        assertEquals(title, marker.title)
        assertEquals("Contact One", user.title)
    }
}
