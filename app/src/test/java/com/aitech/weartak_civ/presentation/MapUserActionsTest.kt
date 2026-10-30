package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.DataRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapUserActionsTest {
    @get:Rule val compose = createComposeRule()

    private fun user(uid: String = "alice") = DataRepository.ReceivedUser(
        uid, "a-f-G-U-C", "Blue", "Alice", "Team Member", 1, 100,
        DataRepository.LocationSnapshot(1, 38.0, -77.0, null, null, null, null, null), "server", chatEndpoint = "*:4242:tcp")

    @Test fun pointWithoutChatEndpointKeepsNavigationButDisablesChat() {
        val point = user().copy(chatEndpoint = null)
        compose.setContent {
            SelectedUser(androidx.wear.compose.navigation.rememberSwipeDismissableNavController(),
                point.uid, listOf(point), LocalContext.current)
        }
        compose.onNodeWithContentDescription("Open Chat").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsEnabled()
        compose.onNodeWithContentDescription("Center and zoom to contact").assertIsEnabled()
    }

    @Before fun setDeviceUid() {
        MainActivity.device_uid = "watch"
    }

    @Test fun mapUserTapShowsCompactOrderedIconRowAndStartsLatestSelectedUser() {
        val alice = user("alice/with space")
        val users = mutableStateOf(listOf(alice))
        val selections = mutableListOf<DataRepository.ReceivedUser>()
        compose.setContent {
            MapUserNavigation(
                users.value, LocalContext.current,
                onBloodhoundUserSelected = { selections += it; true }
            ) { onUserClick ->
                Chip(onClick = { onUserClick(alice.uid) }, label = { Text("Alice on map") })
            }
        }
        compose.onNodeWithText("Alice on map").performClick()
        val bloodhound = compose.onNodeWithContentDescription("Start Bloodhound")
        val chat = compose.onNodeWithContentDescription("Open Chat")
        val target = compose.onNodeWithContentDescription("Center and zoom to contact")
        bloodhound.assertIsDisplayed().assertIsEnabled()
        chat.assertIsDisplayed().assertIsEnabled()
        target.assertIsDisplayed().assertIsEnabled()
        val bounds = listOf(bloodhound, chat, target).map { it.fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds[0].center.x < bounds[1].center.x && bounds[1].center.x < bounds[2].center.x)
        assertEquals(bounds[0].center.y, bounds[1].center.y, 0.1f)
        assertEquals(bounds[1].center.y, bounds[2].center.y, 0.1f)
        val updated = alice.copy(title = "Alice moving", location = alice.location?.copy(lat = 39.0))
        compose.runOnIdle { users.value = listOf(user("bob"), updated) }
        compose.onNodeWithContentDescription("Start Bloodhound").performClick()
        compose.runOnIdle { assertEquals(listOf(updated), selections) }
        compose.onNodeWithText("Alice on map").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w192dp-h192dp-round")
    fun smallRoundScreenCanScrollToStartAndRejectsMissingInvalidOrSelfTargets() {
        val alice = user()
        val users = mutableStateOf(listOf(alice))
        var selections = 0
        compose.setContent {
            MapUserNavigation(
                users.value, LocalContext.current,
                onBloodhoundUserSelected = { selections++; false }
            ) { onUserClick ->
                Chip(onClick = { onUserClick(alice.uid) }, label = { Text("Alice on map") })
            }
        }
        compose.onNodeWithText("Alice on map").performClick()
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Open Chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Center and zoom to contact").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, selections) }
        for (invalid in listOf(
            emptyList(),
            listOf(alice.copy(location = null)),
            listOf(alice.copy(location = alice.location?.copy(lat = Double.NaN))),
            listOf(alice.copy(location = alice.location?.copy(lon = 181.0)))
        )) {
            compose.runOnIdle { users.value = invalid }
            compose.onNodeWithContentDescription("Start Bloodhound").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Center and zoom to contact").assertIsNotEnabled()
        }
        compose.runOnIdle {
            MainActivity.device_uid = alice.uid
            users.value = listOf(alice)
        }
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Center and zoom to contact").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun chatPickerMapCanStartBloodhoundWithoutReturningAChatSelection() {
        val alice = user()
        val contacts = mutableListOf<DirectChatContact>()
        val targets = mutableListOf<DataRepository.ReceivedUser>()
        compose.setContent {
            MapUserNavigation(
                listOf(alice), LocalContext.current,
                selectingChatContact = true,
                onChatContactSelected = { contacts += it },
                onBloodhoundUserSelected = { targets += it; true }
            ) { onUserClick ->
                Chip(onClick = { onUserClick(alice.uid) }, label = { Text("Alice on map") })
            }
        }
        compose.onNodeWithText("Alice on map").performClick()
        compose.onNodeWithContentDescription("Open Chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(alice), targets)
            assertTrue(contacts.isEmpty())
        }
        compose.onNodeWithText("Alice on map").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Open Chat").performClick()
        compose.runOnIdle { assertEquals(listOf(DirectChatContact(alice.uid, alice.title)), contacts) }
    }

    @Test fun targetReturnsToMapWithLatestSelectedContactWithoutStartingBloodhound() {
        val alice = user()
        val users = mutableStateOf(listOf(alice))
        val focused = mutableListOf<DataRepository.ReceivedUser>()
        var bloodhoundStarts = 0
        compose.setContent {
            MapUserNavigation(
                users.value, LocalContext.current,
                onBloodhoundUserSelected = { bloodhoundStarts++; true },
                onFocusUserSelected = { focused += it }
            ) { onUserClick ->
                Chip(onClick = { onUserClick(alice.uid) }, label = { Text("Alice on map") })
            }
        }
        compose.onNodeWithText("Alice on map").performClick()
        val updated = alice.copy(location = alice.location?.copy(lat = 39.0))
        compose.runOnIdle { users.value = listOf(updated) }
        compose.onNodeWithContentDescription("Center and zoom to contact").performClick()
        compose.runOnIdle {
            assertEquals(listOf(updated), focused)
            assertEquals(0, bloodhoundStarts)
        }
        compose.onNodeWithText("Alice on map").assertIsDisplayed()
    }
}
