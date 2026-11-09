package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.presentation.viewmodel.RouteDetailsView
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
class RouteDetailsViewTest {
    @get:Rule val compose = createComposeRule()
    private val route = TakRoute("route", "Test route", listOf(
        TakRoutePoint(34.0, -117.0, name = "Start"),
        TakRoutePoint(34.5, -117.5),
        TakRoutePoint(35.0, -118.0, name = "Finish")
    ), "server")

    @Test fun compactRouteRowHasBloodhoundThenTargetAndNoChat() {
        var bloodhound = 0
        var target = 0
        compose.setContent {
            RouteDetailsView(route, {}, {}, {},
                onBloodhound = { bloodhound++ }, onTarget = { target++ })
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Start Bloodhound"))
        val first = compose.onNodeWithContentDescription("Start Bloodhound")
        val second = compose.onNodeWithContentDescription("Center and zoom to target")
        first.assertIsDisplayed().performClick()
        second.assertIsDisplayed().performClick()
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val secondBounds = second.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(firstBounds.center.x < secondBounds.center.x)
        assertEquals(firstBounds.center.y, secondBounds.center.y, 0.1f)
        compose.onAllNodesWithContentDescription("Open Chat").assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(1, bloodhound)
            assertEquals(1, target)
        }
    }

    @Test fun emptyRouteDisablesBothActions() {
        compose.setContent { RouteDetailsView(route.copy(points = emptyList()), {}, {}, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Start Bloodhound"))
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Center and zoom to target").assertIsNotEnabled()
    }

    @Test fun wholeRouteRemovalRequiresConfirmationAndCanBeCancelled() {
        var removed = 0
        compose.setContent { RouteDetailsView(route, {}, { removed++ }, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Delete"))
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-remove-cancel"))
        compose.onNodeWithTag("route-remove-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Delete"))
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-remove-confirm"))
        compose.onNodeWithTag("route-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun routeListShowsOnlyNonblankNamesAndSelectsOriginalIndices() {
        val selected = mutableListOf<Int>()
        val mixed = route.copy(points = route.points + listOf(
            TakRoutePoint(35.1, -118.1, name = ""),
            TakRoutePoint(35.2, -118.2, name = " \t\n")
        ))
        compose.setContent { RouteDetailsView(mixed, { selected += it }, {}, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("2 named points / 5 total points"))
        compose.onNodeWithText("2 named points / 5 total points").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-point-0"))
        compose.onNodeWithTag("route-point-0").assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-point-2"))
        compose.onNodeWithTag("route-point-2").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(0, 2), selected) }
        listOf(1, 3, 4).forEach {
            compose.onNodeWithTag("route-point-$it").assertDoesNotExist()
        }
    }

    @Test fun routeWithoutNamedPointsShowsExplanationAndKeepsWholeRouteActions() {
        val unnamed = route.copy(points = route.points.map { it.copy(name = " ") })
        var backed = 0
        compose.setContent { RouteDetailsView(unnamed, { error("No list point should be selectable") },
            {}, { backed++ }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("0 named points / 3 total points"))
        compose.onNodeWithText("0 named points / 3 total points").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Delete"))
        compose.onNodeWithContentDescription("Delete").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(
            "No named points. Tap the route on the map to select unnamed points."))
        compose.onNodeWithText("No named points. Tap the route on the map to select unnamed points.")
            .assertIsDisplayed()
        unnamed.points.indices.forEach {
            compose.onNodeWithTag("route-point-$it").assertDoesNotExist()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Back"))
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals(1, backed) }
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun routeActionsFitLargerWatchAndBackDoesNotRemove() {
        var removed = 0
        var backed = 0
        compose.setContent { RouteDetailsView(route, {}, { removed++ }, { backed++ }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Back"))
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, backed)
            assertEquals(0, removed)
        }
    }

    @Test fun missionRouteKeepsExistingConfirmedWatchLocalRemoval() {
        var removed = 0
        compose.setContent {
            RouteDetailsView(route.copy(missionName = "Mission"), {}, { removed++ }, {})
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Delete"))
        compose.onNodeWithContentDescription("Delete").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-remove-confirm"))
        compose.onNodeWithTag("route-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }
}
