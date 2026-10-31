package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.mockk
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
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
class HomeBloodhoundLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeContactAndPinAt480Font13() = verifyHome(480, 1.3f)
    @Test fun activeContactAndPinAt480Font10() = verifyHome(480, 1f)
    @Test fun activeContactAndPinAt480Font11() = verifyHome(480, 1.1f)
    @Test fun activeContactAndPinAt498Font10() = verifyHome(498, 1f)
    @Test fun activeContactAndPinAt498Font11() = verifyHome(498, 1.1f)
    @Test fun activeContactAndPinAt498Font13() = verifyHome(498, 1.3f)
    @Test fun pickerBackAt480Font13() = verifyHeader(480, 1.3f)
    @Test fun pickerBackAt498Font11() = verifyHeader(498, 1.1f)

    private fun verifyHome(pixels: Int, fontScale: Float) {
        val density = 340f / 160
        val repository = BloodhoundRepository(mockk<android.content.Context>(relaxed = true))
        repository.addUserTarget(DataRepository.ReceivedUser(
            "mesh", "a-f-G-U-C", "Blue", "MDJ0 mesh tag", "Team Member", 100, 300_100,
            DataRepository.LocationSnapshot(100, 38.0, -77.0, null, null, null, null, null),
            "TAK", "server"))
        repository.activate("mesh")
        var pickerOpens = 0
        var mapOpens = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WearTAKCIVTheme {
                    val active by repository.activeBhItemUi.collectAsState()
                    Box(Modifier.requiredSize((pixels / density).dp).padding(5.dp)) {
                        HomeDashboardColumn {
                            repeat(3) { Spacer(Modifier.weight(1f)) }
                            Spacer(Modifier.height(4.dp))
                            HomeNavigationRow(active, 45.0, true,
                                { pickerOpens++ }, { mapOpens++ },
                                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp))
                        }
                        TimeWithSecondsAware(LocalContext.current)
                    }
                }
            }
        }
        fun assertControls(): androidx.compose.ui.geometry.Rect {
            val picker = compose.onNodeWithContentDescription("Open Bloodhound")
            val map = compose.onNodeWithContentDescription("Map")
            picker.assertIsDisplayed().performClick()
            map.assertIsDisplayed().performClick()
            val compass = compose.onNodeWithContentDescription("Compass", useUnmergedTree = true)
            compass.assertIsDisplayed()
            val c = compass.fetchSemanticsNode().boundsInRoot
            val p = picker.fetchSemanticsNode().boundsInRoot
            val m = map.fetchSemanticsNode().boundsInRoot
            assertTrue("Picker/map overlap", p.right <= m.left)
            assertTrue("Picker too short", p.height >= 48 * density - 1)
            assertTrue("Map too narrow", m.width >= 48 * density - 1)
            assertTrue("Navigation overlaps reserved clock area", m.bottom <= pixels - 24 * density)
            assertTrue("Compass lost its visual size", c.width >= 28 * density - 1 && c.height >= 28 * density - 1)
            assertTrue("Compass detached from picker", p.contains(c.center))
            assertTrue("Compass overlaps Map or clock", c.right <= m.left && c.bottom <= pixels - 24 * density)
            val radius = pixels / 2f
            for (corner in listOf(c.topLeft, c.topRight, c.bottomLeft, c.bottomRight)) {
                val dx = corner.x - radius
                val dy = corner.y - radius
                assertTrue("Compass outside round safe area: compass=$c picker=$p map=$m pixels=$pixels",
                    dx * dx + dy * dy <= radius * radius)
            }
            return m
        }
        compose.waitUntil { repository.activeBhItemUi.value?.targetKind == Utils.BloodhoundTargetKind.CONTACT }
        compose.onNodeWithText("MDJ0 mesh tag").assertIsDisplayed()
        val contactMap = assertControls()
        compose.runOnIdle {
            repository.addMarkerTarget("pin", "My pin", "a-f-G", 38.0, -77.0, 100,
                tracksLocalMarker = true)
            repository.activate("pin")
        }
        compose.waitUntil { repository.activeBhItemUi.value?.targetKind == Utils.BloodhoundTargetKind.MARKER }
        compose.onNodeWithText("My pin").assertIsDisplayed()
        assertEquals(contactMap, assertControls())
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(contactMap, assertControls())
        compose.runOnIdle { repository.stopTracking("pin") }
        compose.waitUntil { repository.activeBhItemUi.value == null }
        assertEquals(contactMap, assertControls())
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Compass").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Compass truncated", layouts.single().isLineEllipsized(0))
        compose.onNodeWithContentDescription("Unread Bloodhound targets").assertExists()
        assertEquals(4, pickerOpens)
        assertEquals(4, mapOpens)
    }

    private fun verifyHeader(pixels: Int, fontScale: Float) {
        val density = 340f / 160
        var backs = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WearTAKCIVTheme {
                    BloodhoundPickerList(emptyList(), emptyList(), null, {}, {},
                        modifier = Modifier.requiredSize((pixels / density).dp),
                        header = { BloodhoundPickerHeader(true, {}, { backs++ }) })
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Go back to Main Settings Screen")
        back.assertIsDisplayed().performClick()
        val bounds = back.fetchSemanticsNode().boundsInRoot
        val removeAllBounds = compose.onNodeWithText("Remove All").fetchSemanticsNode().boundsInRoot
        assertTrue("Back touch target too small", bounds.width >= 48 * density - 1)
        assertTrue("Back is not right of Remove All", bounds.left >= removeAllBounds.right)
        assertTrue("Back is not aligned with Remove All", kotlin.math.abs(bounds.center.y - removeAllBounds.center.y) <= density)
        assertTrue("Back clipped at screen edge", bounds.right <= pixels - 8 * density + 1)
        val radius = pixels / 2f
        for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
            val dx = corner.x - radius
            val dy = corner.y - radius
            assertTrue("Back outside round safe area", dx * dx + dy * dy <= radius * radius)
        }
        assertEquals(1, backs)
    }
}
