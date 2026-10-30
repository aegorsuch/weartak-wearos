package com.aitech.weartak_civ.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.presentation.viewmodel.RouteDetailsView
import com.aitech.weartak_civ.presentation.viewmodel.RoutePointsDetailsView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MapTargetMenusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wholeRouteHasOrderedBloodhoundTargetActionsWithoutChat() {
        var starts = 0
        var focuses = 0
        val route = TakRoute("fixture", "Fixture route", listOf(
            TakRoutePoint(41.88, -87.64), TakRoutePoint(41.881, -87.641)), "fixture")
        compose.setContent {
            RouteDetailsView(route, {}, {}, {},
                onBloodhound = { starts++ }, onTarget = { focuses++ })
        }
        compose.onNodeWithTag("route-details")
            .performScrollToNode(hasContentDescription("Start Bloodhound"))
        assertOrderedRow("Start Bloodhound")
        val targetBounds = compose.onNodeWithContentDescription("Center and zoom to target")
            .fetchSemanticsNode().boundsInRoot
        val trashBounds = compose.onNodeWithContentDescription("Delete").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(targetBounds.center.x < trashBounds.center.x)
        assertEquals(targetBounds.center.y, trashBounds.center.y, 0.1f)
        compose.onNodeWithTag("route-remove").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start Bloodhound").performClick()
        compose.onNodeWithContentDescription("Center and zoom to target").performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, focuses)
        }
    }

    @Test fun routePointHasOrderedActionsWithoutChatAndKeepsWholeRouteNavigation() {
        var starts = 0
        var focuses = 0
        var wholeRoutes = 0
        compose.setContent {
            RoutePointsDetailsView("Fixture point", 41.88, -87.64, sender = "Fixture sender",
                onBloodhound = { starts++ }, onTarget = { focuses++ },
                onWholeRoute = { wholeRoutes++ }, onBack = {})
        }
        compose.onNodeWithContentDescription("Bloodhound to Marker").performScrollTo()
        assertOrderedRow("Bloodhound to Marker")
        compose.onNodeWithContentDescription("Bloodhound to Marker").performClick()
        compose.onNodeWithContentDescription("Center and zoom to target").performClick()
        compose.onAllNodesWithText("Chat").assertCountEquals(0)
        compose.onNodeWithText("Whole route").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, focuses)
            assertEquals(1, wholeRoutes)
        }
    }

    @Test fun sharedMapPointRowRetainsInPositionAndTargetWithoutStartingAgain() {
        var starts = 0
        var acknowledgements = 0
        var focuses = 0
        compose.setContent {
            PointBloodhoundAction(true, { starts++ }, { acknowledgements++ }, { focuses++ })
        }
        assertOrderedRow("nPos")
        compose.onNodeWithContentDescription("nPos").performClick()
        compose.onNodeWithContentDescription("Center and zoom to target").performClick()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(1, acknowledgements)
            assertEquals(1, focuses)
        }
    }

    @Test fun deletablePointAddsTrashAfterTargetWithoutDeleteText() {
        var deletes = 0
        compose.setContent {
            PointBloodhoundAction(false, {}, {}, {}, onDelete = { deletes++ })
        }
        assertOrderedRow("Bloodhound to Marker")
        val target = compose.onNodeWithContentDescription("Center and zoom to target")
            .fetchSemanticsNode().boundsInRoot
        val trash = compose.onNodeWithContentDescription("Delete").assertIsDisplayed()
        val bounds = trash.fetchSemanticsNode().boundsInRoot
        assertTrue(target.center.x < bounds.center.x)
        assertEquals(target.center.y, bounds.center.y, 0.1f)
        compose.onNodeWithText("Delete").assertDoesNotExist()
        trash.performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun routeTrashKeepsConfirmationAndCancel() {
        var deletes = 0
        val route = TakRoute("fixture", "Fixture route",
            listOf(TakRoutePoint(41.88, -87.64)), "fixture")
        compose.setContent { RouteDetailsView(route, {}, { deletes++ }, {}) }
        compose.onNodeWithTag("route-details").performScrollToNode(hasContentDescription("Delete"))
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithTag("route-details").performScrollToNode(hasTestTag("route-remove-cancel"))
        compose.onNodeWithTag("route-remove-cancel").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithTag("route-details").performScrollToNode(hasContentDescription("Delete"))
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithTag("route-details").performScrollToNode(hasTestTag("route-remove-confirm"))
        compose.onNodeWithTag("route-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    private fun assertOrderedRow(firstDescription: String) {
        val first = compose.onNodeWithContentDescription(firstDescription).assertIsDisplayed()
        val second = compose.onNodeWithContentDescription("Center and zoom to target").assertIsDisplayed()
        val left = first.fetchSemanticsNode().boundsInRoot
        val right = second.fetchSemanticsNode().boundsInRoot
        assertTrue("Bloodhound must precede Target", left.center.x < right.center.x)
        assertEquals("Both icons must share one row", left.center.y, right.center.y, 0.1f)
        compose.onAllNodesWithContentDescription("Open Chat").assertCountEquals(0)
    }
}
