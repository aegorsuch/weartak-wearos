package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.presentation.viewmodel.RoutePointsDetailsView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutePointDetailsViewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun standardPointActionAndWholeRouteRemainReachableOnSmallWatch() {
        var bloodhound = 0
        var wholeRoute = 0
        var target = 0
        compose.setContent {
            RoutePointsDetailsView(
                "Point 1235", 34.0, -117.0,
                onBloodhound = { bloodhound++ },
                onWholeRoute = { wholeRoute++ },
                onTarget = { target++ },
                onBack = {}
            )
        }
        compose.onNodeWithContentDescription("Bloodhound to Marker").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, bloodhound) }
        compose.onNodeWithContentDescription("Center and zoom to target").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, target) }
        compose.onAllNodesWithContentDescription("Open Chat").assertCountEquals(0)
        compose.onNodeWithText("Whole route").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, wholeRoute) }
    }

    @Test
    fun activePointUsesPickerStopActionInsteadOfInPosition() {
        var stops = 0
        var bloodhound = 0
        compose.setContent {
            RoutePointsDetailsView(
                "Start", 34.0, -117.0, isBloodhounding = true,
                onBloodhound = { bloodhound++ }, onStop = { stops++ }, onBack = {}
            )
        }
        compose.onNodeWithContentDescription("Bloodhound to Marker").assertDoesNotExist()
        compose.onNodeWithContentDescription("nPos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop Bloodhound to Start")
            .performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(0, bloodhound)
        }
    }

    @Test fun activeMarkerCanStopWithoutLocationAndKeepsTargetAndDeleteActions() {
        var stops = 0
        var deletes = 0
        compose.setContent {
            PointBloodhoundAction(true, { error("Must not start active marker") }, { stops++ }, {},
                enabled = false, onDelete = { deletes++ }, targetName = "Rally Point")
        }
        compose.onNodeWithContentDescription("Stop Bloodhound to Rally Point").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Center and zoom to target").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(1, deletes)
        }
    }

    @Test fun pointTargetUsesExistingTypedMapFocusIntent() {
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            PointBloodhoundAction(false, {}, {},
                onTarget = { focusPointOnMap(context, 41.880029, -87.640974) })
        }
        compose.onNodeWithContentDescription("Center and zoom to target").performClick()
        compose.runOnIdle {
            val intent = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
                .nextStartedActivity
            assertEquals(MapActivity::class.java.name, intent.component?.className)
            assertEquals(41.880029, intent.getDoubleExtra(MapActivity.EXTRA_FOCUS_LAT, 0.0), 0.0)
            assertEquals(-87.640974, intent.getDoubleExtra(MapActivity.EXTRA_FOCUS_LON, 0.0), 0.0)
        }
    }

    @Test fun invalidPointDisablesBothIcons() {
        compose.setContent {
            RoutePointsDetailsView("Invalid", Double.NaN, -117.0, onBack = {})
        }
        compose.onNodeWithContentDescription("Bloodhound to Marker").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Center and zoom to target").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist()
    }

    @Test fun deletablePointShowsTrashAfterTargetOnOneRowWithoutTextButton() {
        var deletes = 0
        var starts = 0
        var focuses = 0
        compose.setContent {
            PointBloodhoundAction(false, { starts++ }, {}, { focuses++ },
                onDelete = { deletes++ })
        }
        val bloodhound = compose.onNodeWithContentDescription("Bloodhound to Marker").assertIsDisplayed()
        val target = compose.onNodeWithContentDescription("Center and zoom to target").assertIsDisplayed()
        val trash = compose.onNodeWithContentDescription("Delete").assertIsDisplayed()
        val bounds = listOf(bloodhound, target, trash).map { it.fetchSemanticsNode().boundsInRoot }
        org.junit.Assert.assertTrue(bounds[0].center.x < bounds[1].center.x &&
            bounds[1].center.x < bounds[2].center.x)
        assertEquals(bounds[0].center.y, bounds[2].center.y, 0.1f)
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Delete Marker").assertDoesNotExist()
        trash.performClick()
        compose.runOnIdle {
            assertEquals(1, deletes)
            assertEquals(0, starts)
            assertEquals(0, focuses)
        }
    }

    @Test fun missingPositionStillAllowsExistingDeleteFlow() {
        var deletes = 0
        compose.setContent {
            PointBloodhoundAction(false, {}, {}, {}, enabled = false, onDelete = { deletes++ })
        }
        compose.onNodeWithContentDescription("Bloodhound to Marker").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Center and zoom to target").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun savedPinDeleteCallbackCanKeepExistingConfirmationBeforeDeletion() {
        val confirming = androidx.compose.runtime.mutableStateOf(false)
        var deletes = 0
        compose.setContent {
            if (confirming.value) {
                androidx.wear.compose.material.Chip(
                    onClick = { deletes++ },
                    label = { androidx.wear.compose.material.Text("Confirm delete fixture") })
            } else {
                PointBloodhoundAction(false, {}, {}, {}, onDelete = { confirming.value = true })
            }
        }
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("Confirm delete fixture").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }
}
