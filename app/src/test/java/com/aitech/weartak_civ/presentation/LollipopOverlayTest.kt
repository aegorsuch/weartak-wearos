package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Position
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LollipopOverlayTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(): LollipopRender {
        val pitch = PI / 4
        val center = ScreenCoordinate(113.5, 113.5)
        fun ground(forward: Double) = ScreenCoordinate(center.x,
            center.y - 340.5 * forward * cos(pitch) / (500 + forward * sin(pitch)))
        val lift = requireNotNull(VerticalProjection.create(ground(-60.0),
            ground(-10.0), ground(-110.0), center, 50.0, 45.0))
        val target = LollipopTarget("fixture", Position(-74.009, 40.706),
            37.0, 10.0, "a-f-G-U-C", "Fixture", "Cyan")
        return LollipopRender(target, 60.0, lift, requireNotNull(lift.atHeight(60.0)),
            stemSegments(60.0, listOf(HeightRange(0.0, 150.0))))
    }

    @Test fun raisedMarkerRemainsClickableWithoutNumericHeightLabel() {
        val marker = fixture()
        var selected: String? = null
        compose.setContent {
            LollipopOverlay(LollipopState(listOf(marker))) { selected = it.key }
        }
        val node = compose.onNodeWithContentDescription("Fixture")
        node.assertIsDisplayed().performClick()
        compose.onNodeWithText("GPS altitude is approximate").assertDoesNotExist()
        assertEquals("fixture", selected)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val ground = marker.projection.ground
        assertTrue("Hit target must follow raised icon, not ground dot", bounds.center.y < ground.y)
        compose.onNodeWithText("Approx. 60 m AGL\nHAE error +/- 10 m").assertDoesNotExist()
        assertFalse(node.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        compose.onAllNodesWithText("AGL", substring = true).assertCountEquals(0)
    }

    @Test fun unresolvedAltitudeHasNoHelperTextStemOrHitTarget() {
        compose.setContent { LollipopOverlay(LollipopState()) {} }
        compose.onAllNodesWithText("Altitude unavailable", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Fixture").assertDoesNotExist()
        compose.onNodeWithText("GPS altitude is approximate").assertDoesNotExist()
    }
}
