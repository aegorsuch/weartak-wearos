package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.data.RouteFollow
import com.aitech.weartak_civ.data.RouteFollowSession
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteBloodhoundTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)
    private val route = TakRoute("route", "testroute01", (0 until 2469).map {
        TakRoutePoint(0.0, it * 0.00001, name = if (it == 0) "Named start" else null)
    }, "server")
    private fun scroll(tag: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
    }

    @Test fun pickerShowsOneWholeRouteWithCountAndNearestDistance() {
        var selected: TakRoute? = null
        compose.setContent {
            BloodhoundPickerList(emptyList(), listOf(route), route.points[0], {}, { selected = it },
                Modifier.fillMaxSize())
        }
        compose.onAllNodesWithTag("bloodhound-route").assertCountEquals(1)
        compose.onNodeWithText("testroute01").assertExists()
        compose.onNodeWithText("Route · 2469 pts · 0 m").assertExists()
        compose.onNodeWithText("Named start").assertDoesNotExist()
        compose.onNodeWithTag("bloodhound-route-body").performClick()
        compose.runOnIdle { assertEquals(route, selected) }
    }

    @Test fun detailOffersBothStartModesAndBack() {
        val starts = mutableListOf<RouteFollow.StartMode>()
        var back = 0
        compose.setContent {
            RouteBloodhoundDetails(route, null, route.points[0], null, { starts += it }, {}, { back++ })
        }
        scroll("route-follow-nearest")
        compose.onNodeWithTag("route-follow-nearest").assertIsEnabled().performClick()
        scroll("route-follow-beginning")
        compose.onNodeWithTag("route-follow-beginning").assertIsEnabled().performClick()
        scroll("route-follow-back")
        compose.onNodeWithTag("route-follow-back").performClick()
        compose.runOnIdle {
            assertEquals(listOf(RouteFollow.StartMode.NEAREST, RouteFollow.StartMode.BEGINNING), starts)
            assertEquals(1, back)
        }
    }

    @Test fun noFixDisablesNearestButNotBeginning() {
        compose.setContent { RouteBloodhoundDetails(route, null, null, null, {}, {}, {}) }
        scroll("route-follow-nearest")
        compose.onNodeWithTag("route-follow-nearest").assertIsNotEnabled()
        scroll("route-follow-beginning")
        compose.onNodeWithTag("route-follow-beginning").assertIsEnabled()
        compose.onNodeWithText("Waiting for location. Start nearest needs a location fix.").assertExists()
    }

    @Test fun activeRouteShowsProgressRemainingAndStopInsteadOfStart() {
        val navigation = RouteFollow.start(route.points, RouteFollow.StartMode.BEGINNING)
        val session = RouteFollowSession(route, navigation, 200.0)
        var stopped = 0
        compose.setContent { RouteBloodhoundDetails(route, session, route.points[0], null,
            {}, { stopped++ }, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Point 1 of 2469"))
        compose.onNodeWithText("Point 1 of 2469").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Remaining: 200 m"))
        compose.onNodeWithText("Remaining: 200 m").assertExists()
        scroll("route-follow-stop")
        compose.onNodeWithTag("route-follow-stop").performClick()
        compose.onNodeWithTag("route-follow-nearest").assertDoesNotExist()
        compose.onNodeWithTag("route-follow-beginning").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, stopped) }
    }

    @Test fun completeRouteShowsCompletionAndCanRestart() {
        val last = route.points.last()
        val navigation = RouteFollow.start(route.points, RouteFollow.StartMode.NEAREST, last).advance(last)
        compose.setContent { RouteBloodhoundDetails(route, RouteFollowSession(route, navigation, 0.0),
            last, null, {}, {}, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Route complete"))
        compose.onNodeWithText("Route complete").assertExists()
        scroll("route-follow-beginning")
        compose.onNodeWithTag("route-follow-beginning").assertIsEnabled()
        compose.onNodeWithTag("route-follow-stop").assertDoesNotExist()
    }

    @Test fun routeMapAndChooserButtonsAreIndependentOfRowDetails() {
        var mapped = 0
        var chosen = 0
        var details = 0
        compose.setContent {
            BloodhoundPickerList(emptyList(), listOf(route), null, {}, { details++ }, Modifier.fillMaxSize(),
                onMapRoute = { mapped++ }, onChooseRoute = { chosen++ })
        }
        compose.onNodeWithTag("bloodhound-route-map").performClick()
        compose.runOnIdle { assertEquals(1, mapped); assertEquals(0, chosen); assertEquals(0, details) }
        compose.onNodeWithTag("bloodhound-route-hound").performClick()
        compose.runOnIdle { assertEquals(1, chosen); assertEquals(0, details) }
        compose.onNodeWithTag("bloodhound-route-body").performClick()
        compose.runOnIdle { assertEquals(1, details) }
    }

    @Test fun activeRouteRowShowsProgressAndHoundStopsRatherThanStarts() {
        val navigation = RouteFollow.start(route.points, RouteFollow.StartMode.BEGINNING)
        val session = RouteFollowSession(route, navigation, 1400.0)
        var chosen = 0
        var stopped = 0
        compose.setContent {
            BloodhoundPickerList(emptyList(), listOf(route), null, {}, {}, Modifier.fillMaxSize(), session,
                onChooseRoute = { chosen++ }, onStopRoute = { stopped++ })
        }
        compose.onNodeWithText("Pt 1 of 2469 · 1400 m left").assertExists()
        compose.onNodeWithContentDescription("Stop Bloodhound to testroute01").assertExists()
        compose.onNodeWithTag("bloodhound-route-stop-icon", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("bloodhound-route-hound").performClick()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(0, chosen) }
    }

    @Test fun routeChooserHasCancelAndDoesNotStartWithoutAnAction() {
        var started = 0
        var cancelled = 0
        compose.setContent {
            RouteBloodhoundDetails(route, null, null, null, { started++ }, {}, { cancelled++ }, choosingStart = true)
        }
        scroll("route-follow-nearest")
        compose.onNodeWithTag("route-follow-nearest").assertIsNotEnabled()
        scroll("route-follow-back")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, started); assertEquals(1, cancelled) }
    }

    @Test fun routeCanBeRemovedDirectlyFromPicker() {
        var removed: TakRoute? = null
        compose.setContent {
            BloodhoundPickerList(emptyList(), listOf(route), null, {}, {}, Modifier.fillMaxSize(),
                onRemoveRoute = { removed = it })
        }
        compose.onNodeWithTag("bloodhound-route-remove").performClick()
        compose.runOnIdle { assertEquals(route, removed) }
    }

    @Test fun routeCanBeRemovedFromNavigationDetails() {
        var removed = 0
        compose.setContent {
            RouteBloodhoundDetails(route, null, null, null, {}, {}, {}, onRemove = { removed++ })
        }
        scroll("route-follow-remove")
        compose.onNodeWithTag("route-follow-remove").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }
}
