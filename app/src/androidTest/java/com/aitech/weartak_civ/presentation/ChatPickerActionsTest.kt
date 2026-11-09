package com.aitech.weartak_civ.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.cot.ChatDestinations
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatPickerActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun broadcastAndMapSelectionAreSeparateEntryActions() {
        var broadcasts = 0
        var mapSelections = 0
        compose.setContent {
            WearTAKCIVTheme {
                ChatScreen(rememberSwipeDismissableNavController(), {},
                    listOf(ChatDestinations.direct("alice", "Existing chat", "watch"),
                        ChatDestinations.allRooms("All Chat Rooms")),
                    { broadcasts++ }, { mapSelections++ }, {})
            }
        }
        compose.onNodeWithText("Existing chat").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("All Chat Rooms").performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithContentDescription("All Chat Rooms").assertCountEquals(1)
        compose.runOnIdle {
            assertEquals(1, broadcasts)
            assertEquals(0, mapSelections)
        }
        compose.onNodeWithContentDescription("Select User From Map").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, broadcasts)
            assertEquals(1, mapSelections)
        }
    }

    @Test fun selectedPersonConfirmationSurvivesReorderingAndDisablesWhenPersonDisappears() {
        MainActivity.device_uid = "watch"
        fun user(uid: String, title: String) = DataRepository.ReceivedUser(
            uid, "a-f-G-U-C", "Blue", title, "Team Member", 1, 100, null, "server", chatEndpoint = "*:4242:tcp")
        val alice = user("alice", "Alice")
        val bob = user("bob", "Bob")
        val users = mutableStateOf(listOf(alice, bob))
        val selections = mutableListOf<DirectChatContact>()
        compose.setContent {
            SelectedUser(rememberSwipeDismissableNavController(), "alice", users.value,
                LocalContext.current, selectingChatContact = true,
                onChatContactSelected = { selections += it })
        }
        compose.runOnIdle { users.value = listOf(bob, alice.copy(title = "Alice updated")) }
        compose.onNodeWithContentDescription("Open Chat").performClick()
        compose.runOnIdle { assertEquals(listOf(DirectChatContact("alice", "Alice updated")), selections) }
        compose.runOnIdle { users.value = listOf(bob) }
        compose.onNodeWithContentDescription("Open Chat").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Center and zoom to contact").assertIsNotEnabled()
    }

    @Test fun selectedMapUserOffersCompactOrderedIconsAndUsesLatestUidSnapshot() {
        MainActivity.device_uid = "watch"
        fun user(uid: String, title: String) = DataRepository.ReceivedUser(
            uid, "a-f-G-U-C", "Blue", title, "Team Member", 1, 100,
            DataRepository.LocationSnapshot(1, 38.0, -77.0, null, null, null, null, null), "server", chatEndpoint = "*:4242:tcp")
        val alice = user("alice", "Alice")
        val bob = user("bob", "Bob")
        val users = mutableStateOf(listOf(alice, bob))
        val selections = mutableListOf<DataRepository.ReceivedUser>()
        compose.setContent {
            SelectedUser(rememberSwipeDismissableNavController(), "alice", users.value,
                LocalContext.current,
                onBloodhoundUserSelected = { selections += it; true })
        }
        val bloodhound = compose.onNodeWithContentDescription("Start Bloodhound")
        val chat = compose.onNodeWithContentDescription("Open Chat")
        val target = compose.onNodeWithContentDescription("Center and zoom to contact")
        bloodhound.assertIsDisplayed()
        chat.assertIsDisplayed()
        target.assertIsDisplayed()
        val bounds = listOf(bloodhound, chat, target).map { it.fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds[0].center.x < bounds[1].center.x && bounds[1].center.x < bounds[2].center.x)
        assertEquals(bounds[0].center.y, bounds[1].center.y, 0.1f)
        assertEquals(bounds[1].center.y, bounds[2].center.y, 0.1f)
        val updated = alice.copy(title = "Alice updated")
        compose.runOnIdle { users.value = listOf(bob, updated) }
        compose.onNodeWithContentDescription("Start Bloodhound").performClick()
        compose.runOnIdle { assertEquals(listOf(updated), selections) }
        compose.runOnIdle { users.value = listOf(bob) }
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsNotEnabled()
        compose.runOnIdle { users.value = listOf(alice.copy(location = null)) }
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsNotEnabled()
        compose.runOnIdle { users.value = listOf(alice.copy(
            location = alice.location?.copy(lat = Double.NaN))) }
        compose.onNodeWithContentDescription("Start Bloodhound").assertIsNotEnabled()
    }

    @Test fun selectedContactTargetUsesLatestLocationWithoutStartingBloodhound() {
        MainActivity.device_uid = "watch"
        val alice = DataRepository.ReceivedUser(
            "fixture-alice", "a-f-G-U-C", "Blue", "Alice", "Team Member", 1, 100,
            DataRepository.LocationSnapshot(1, 38.0, -77.0, null, null, null, null, null),
            "fixture", chatEndpoint = "*:4242:tcp")
        val users = mutableStateOf(listOf(alice))
        val focused = mutableListOf<DataRepository.ReceivedUser>()
        var starts = 0
        compose.setContent {
            SelectedUser(rememberSwipeDismissableNavController(), alice.uid, users.value,
                LocalContext.current,
                onBloodhoundUserSelected = { starts++; true },
                onFocusUserSelected = { focused += it })
        }
        val updated = alice.copy(location = alice.location?.copy(lat = 39.0))
        compose.runOnIdle { users.value = listOf(updated) }
        compose.onNodeWithContentDescription("Center and zoom to contact").performClick()
        compose.runOnIdle {
            assertEquals(listOf(updated), focused)
            assertEquals(0, starts)
        }
    }
}
