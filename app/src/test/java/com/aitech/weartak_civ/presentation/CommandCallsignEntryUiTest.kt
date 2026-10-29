package com.aitech.weartak_civ.presentation

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommandCallsignEntryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val writes = mutableListOf<List<String>>()
    private lateinit var nav: NavHostController

    private fun showEntry() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "allowed") {
                composable("allowed") {
                    WearTAKTitleChip(
                        title = "Add", route = "add", navController = nav
                    )
                }
                composable("add") { CommandCallsignEntryPage(nav, listOf("ZULU"), onSave = writes::add) }
            }
        }
        compose.onNodeWithText("Add").performClick()
    }

    @Test fun smallRoundKeyboardDoneAddsAndReturnsWithoutAnAddDialog() = keyboardDoneAdds()

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundKeyboardDoneAddsAndReturnsWithoutAnAddDialog() = keyboardDoneAdds()

    private fun keyboardDoneAdds() {
        showEntry()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.typePlatformText(" alpha-1 ")
        compose.pressPlatformImeDone()
        compose.waitUntil { writes.isNotEmpty() }
        assertEquals(listOf(listOf("ALPHA-1", "ZULU")), writes)
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test fun confirmButtonUsesTheSameValidationAndCancelNeverWrites() {
        showEntry()
        for (value in listOf("", " ", "BAD!", "A".repeat(33), " zulu ")) {
            compose.replacePlatformText(value)
            compose.onNodeWithContentDescription("Confirm icon").performClick()
            compose.waitForIdle()
            assertTrue(writes.isEmpty())
            assertEquals("add", nav.currentDestination?.route)
            assertNotNull(ShadowToast.getTextOfLatestToast())
        }
        assertEquals("That callsign is already allowed", ShadowToast.getTextOfLatestToast())
        compose.replacePlatformText("BRAVO")
        compose.onNodeWithContentDescription("Close icon").performClick()
        compose.onNodeWithText("Add").assertIsDisplayed()
        assertTrue(writes.isEmpty())
        compose.onNodeWithText("Add").performClick()
        assertEquals("", compose.platformText())
        compose.typePlatformText("B".repeat(32))
        compose.onNodeWithContentDescription("Confirm icon").performClick()
        assertEquals(listOf(listOf("B".repeat(32), "ZULU")), writes)
    }

    @Test fun invalidKeyboardDoneDoesNotSaveOrNavigateAway() {
        showEntry()
        compose.typePlatformText("BAD!")
        compose.pressPlatformImeDone()
        compose.waitForIdle()
        assertTrue(writes.isEmpty())
        assertEquals("add", nav.currentDestination?.route)
        compose.replacePlatformText(" zulu ")
        compose.pressPlatformImeDone()
        compose.waitForIdle()
        assertTrue(writes.isEmpty())
        assertEquals("That callsign is already allowed", ShadowToast.getTextOfLatestToast())
    }

    private fun showEditableEntry(callsign: String = "Alpha One") {
        compose.setContent {
            nav = rememberNavController()
            val callsigns = listOf(callsign, "ZULU")
            NavHost(nav, startDestination = "allowed") {
                composable("allowed") {
                    Column {
                        CommandCallsignRow(
                            callsign,
                            onEdit = { nav.navigate("edit/${Uri.encode(callsign)}") },
                            onRemove = { writes.add(listOf("ZULU")) }
                        )
                    }
                }
                composable("edit/{callsign}") { entry ->
                    CommandCallsignEntryPage(
                        nav, callsigns,
                        originalCallsign = requireNotNull(entry.arguments?.getString("callsign")),
                        onSave = writes::add
                    )
                }
            }
        }
    }

    @Test fun tappingExistingCallsignFocusesPrefilledEditorAndDoneReplacesOnlyThatEntry() {
        showEditableEntry()
        compose.onNodeWithText("Alpha One").performClick()
        assertTrue(compose.platformField().hasFocus())
        assertEquals("Alpha One", compose.platformText())
        compose.replacePlatformText(" bravo-2 ")
        compose.pressPlatformImeDone()
        compose.onNodeWithText("Alpha One").assertIsDisplayed()
        assertEquals(listOf(listOf("BRAVO-2", "ZULU")), writes)
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun editingRejectsInvalidAndOtherDuplicateButAcceptsOwnNameWithoutChangingStoredCase() {
        showEditableEntry()
        compose.onNodeWithText("Alpha One").performClick()
        for (value in listOf("", "BAD!", "A".repeat(33), " zulu ")) {
            compose.replacePlatformText(value)
            compose.pressPlatformImeDone()
            compose.waitForIdle()
            assertTrue(writes.isEmpty())
            assertEquals("edit/{callsign}", nav.currentDestination?.route)
        }
        assertEquals("That callsign is already allowed", ShadowToast.getTextOfLatestToast())
        compose.replacePlatformText(" alpha one ")
        compose.onNodeWithContentDescription("Confirm icon").performClick()
        assertEquals(listOf(listOf("Alpha One", "ZULU")), writes)
    }

    @Test fun cancelAndBackDiscardEditsAndReopeningRestoresOriginalValue() {
        showEditableEntry()
        compose.onNodeWithText("Alpha One").performClick()
        compose.replacePlatformText("BRAVO")
        compose.onNodeWithContentDescription("Close icon").performClick()
        assertTrue(writes.isEmpty())
        compose.onNodeWithText("Alpha One").performClick()
        assertEquals("Alpha One", compose.platformText())
        compose.replacePlatformText("CHARLIE")
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Alpha One").assertIsDisplayed()
        assertTrue(writes.isEmpty())
    }

    @Test fun deleteControlRemovesWithoutOpeningEditor() {
        showEditableEntry()
        compose.onNodeWithContentDescription("Remove Alpha One").performClick()
        assertEquals(listOf(listOf("ZULU")), writes)
        assertEquals("allowed", nav.currentDestination?.route)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun legacyCallsignWithRouteCharactersOpensWithoutChangingItsValue() {
        val legacy = "ALPHA/ONE%?#"
        showEditableEntry(legacy)
        compose.onNodeWithText(legacy).performClick()
        assertEquals(legacy, compose.platformText())
        compose.onNodeWithContentDescription("Close icon").performClick()
        assertTrue(writes.isEmpty())
    }
}
