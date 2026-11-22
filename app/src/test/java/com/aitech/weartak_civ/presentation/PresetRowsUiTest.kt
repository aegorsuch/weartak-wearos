package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetRowsUiTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun trashIsSeparateFromSelectionAndOnlyAppearsForCustomRows() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            WearTAKCIVTheme {
                Column {
                    ManualAlertActionRow("Built in", { selected += "Built in" })
                    ManualAlertActionRow("Custom", { selected += "Custom" },
                        onDelete = { deleted += "Custom" }, allowLongLabel = true)
                }
            }
        }
        compose.onNodeWithContentDescription("Delete Built in").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete Custom").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Custom"), deleted)
            assertEquals(emptyList<String>(), selected)
        }
        compose.onNodeWithText("Custom").performClick()
        compose.runOnIdle { assertEquals(listOf("Custom"), selected) }
    }

    @Test fun chatTrashOpensConfirmationAndDoesNotSend() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            WearTAKCIVTheme {
                ChatPresetRows(listOf("Bravo"), listOf("alpha"), selected::add,
                    onAdd = { true }, onDelete = { deleted += it })
            }
        }
        val alpha = compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInRoot
        val bravo = compose.onNodeWithText("Bravo").fetchSemanticsNode().boundsInRoot
        assertTrue(alpha.top < bravo.top)
        compose.onNodeWithContentDescription("Delete alpha").performClick()
        compose.onNodeWithText("Delete saved message?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(listOf("alpha"), deleted)
            assertEquals(emptyList<String>(), selected)
        }
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundScreenKeepsLongCustomTextAndTrashIndependentlyAccessible() {
        val text = "Check the north perimeter and report to the command post"
        var selected = false
        var deleted = false
        compose.setContent {
            WearTAKCIVTheme {
                ManualAlertActionRow(text, { selected = true },
                    onDelete = { deleted = true }, allowLongLabel = true)
            }
        }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete $text").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(deleted)
            assertEquals(false, selected)
        }
    }

    @Test fun alertTrashOpensConfirmationWithoutSendingOrLongPress() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            WearTAKCIVTheme {
                ManualAlertListScreen(listOf("Bravo"), listOf("alpha"), selected::add,
                    onAddAlert = { true }, onDeleteAlert = { deleted += it }, onCancel = {})
            }

        }
        compose.onNodeWithText("Hold a saved alert", substring = true).assertDoesNotExist()
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasContentDescription("Delete alpha"))
        compose.onNodeWithContentDescription("Delete alpha").performClick()
        compose.onNodeWithText("Delete saved alert?").assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Delete"))
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(listOf("alpha"), deleted)
            assertEquals(emptyList<String>(), selected)
        }
    }

    @Test fun scrolledRoomPickerRevealsNewSaveAndDeleteDestinations() = verifyScrolledEditors(false)

    @Test fun scrolledNewChatPickerRevealsNewSaveAndDeleteDestinations() = verifyScrolledEditors(true)

    private fun verifyScrolledEditors(scaling: Boolean) {
        var sent = 0
        var removed = 0
        compose.setContent {
            WearTAKCIVTheme {
                val rows: @Composable () -> Unit = {
                    ChatPresetRows((1..20).map { "Reply %02d".format(it) }, listOf("Zulu custom"),
                        { sent++ }, onAdd = { true }, onDelete = { removed++ })
                }
                if (scaling) {
                    ScalingLazyColumn(Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(60.dp)) }
                        item { rows() }
                        item { Spacer(Modifier.height(120.dp)) }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(60.dp)) }
                        item { rows() }
                        item { Spacer(Modifier.height(120.dp)) }
                    }
                }
            }
        }
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        list.performScrollToNode(hasContentDescription("Type Custom Message"))
        compose.onNodeWithContentDescription("Type Custom Message").performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertTrue(compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top >= 0)
        compose.onNodeWithText("Save message").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        list.performScrollToNode(hasContentDescription("Delete Zulu custom"))
        compose.onNodeWithContentDescription("Delete Zulu custom").performClick()
        compose.onNodeWithText("Delete saved message?").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Delete saved message?").fetchSemanticsNode().boundsInRoot.top >= 0)
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, sent); assertEquals(0, removed) }
    }
}
