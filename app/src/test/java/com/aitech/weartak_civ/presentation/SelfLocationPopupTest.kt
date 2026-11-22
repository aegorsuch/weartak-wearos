package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.latLonToMgrs
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
class SelfLocationPopupTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun ownDotPopupShowsExactlySelfAndFiveDecimalsAboveFullUnroundedMgrs() {
        val position = mutableStateOf(38.1234567 to -77.9876543)
        compose.setContent {
            PopupBox(true, position.value.first, position.value.second, {})
        }
        val heading = compose.onNodeWithText("Self").assertIsDisplayed()
        assertEquals("Self", RuntimeEnvironment.getApplication().getString(R.string.map_self_location))
        for ((point, expected) in listOf(
            (38.1234567 to -77.9876543) to "38.12346, -77.98765",
            (-38.1234567 to 77.9876543) to "-38.12346, 77.98765",
            (-0.0 to 0.0) to "-0.00000, 0.00000"
        )) {
            compose.runOnIdle { position.value = point }
            val coordinates = compose.onNodeWithText(expected).assertIsDisplayed()
            val mgrs = compose.onNodeWithText(latLonToMgrs(point.first, point.second)).assertIsDisplayed()
            assertTrue(heading.fetchSemanticsNode().boundsInRoot.bottom <=
                coordinates.fetchSemanticsNode().boundsInRoot.top)
            assertTrue(coordinates.fetchSemanticsNode().boundsInRoot.bottom <=
                mgrs.fetchSemanticsNode().boundsInRoot.top)
        }
        compose.onNodeWithContentDescription("Latitude -0.00000, longitude 0.00000").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w192dp-h192dp-round")
    fun smallRoundScreenAtLargeNativeFontKeepsBothCoordinateRowsOnOneLine() {
        val lat = -79.1234567
        val lon = -179.9876543
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                PopupBox(true, lat, lon, {})
            }
        }
        for (text in listOf("-79.12346, -179.98765", latLonToMgrs(lat, lon))) {
            val node = compose.onNodeWithText(text).assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val result = results.single()
            assertEquals(1, result.lineCount)
            assertFalse(result.didOverflowWidth)
            assertFalse(result.didOverflowHeight)
        }
    }

    @Test fun closedPopupDoesNotAddSelfLabelOrCoordinatesToUnderlyingScreen() {
        compose.setContent {
            PopupBox(false, 38.0, -77.0, {})
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.map_self_location))
            .assertDoesNotExist()
        compose.onNodeWithText("38.00000, -77.00000").assertDoesNotExist()
    }
}
