package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.integration.plugin.HostedPluginMainUiContract
import com.aitech.weartak_civ.integration.plugin.HostedPluginMainUiResult
import com.aitech.weartak_civ.integration.plugin.hostedActionContent
import com.aitech.weartak_civ.integration.plugin.presentedCard
import com.weartak.plugin.sdk.MainUiActionContract as C
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostedPluginMainUiPageTest {
    @get:Rule val compose = createComposeRule()

    private fun cards() = HostedPluginMainUiContract.parse(PluginMainUiSchemaParcel(cards = listOf(
        presentedCard("back", C.BACK, placement = C.HEADER_END),
        presentedCard("note", C.NOTE, group = "incident.actions"),
        presentedCard("bloodhound", C.BLOODHOUND, group = "incident.actions"),
        presentedCard("target", C.TARGET, group = "incident.actions", enabled = false),
        MainUiCardParcel(MainUiCardTypes.STATUS, "detail", "Incident details"),
    )))

    @Test fun iconsShareTopRowAndHeaderBackReplacesTextAndBottomNavigation() {
        val invoked = mutableListOf<String>()
        var exits = 0
        compose.setContent {
            HostedPluginMainUiScreen("TAK CAD", HostedPluginMainUiResult.Ready(cards()),
                onBack = { exits++ }, onAction = invoked::add)
        }
        listOf("note", "bloodhound", "target", "back").forEach {
            compose.onNodeWithText("$it title").assertDoesNotExist()
            compose.onNodeWithText("$it label").assertDoesNotExist()
        }
        val note = compose.onNodeWithContentDescription("note label")
        val bloodhound = compose.onNodeWithContentDescription("bloodhound label")
        val target = compose.onNodeWithContentDescription("target label")
        val back = compose.onNodeWithContentDescription("back label")
        note.assertIsEnabled()
        bloodhound.assertIsEnabled()
        target.assertIsNotEnabled()
        val n = note.fetchSemanticsNode().positionInRoot
        val b = bloodhound.fetchSemanticsNode().positionInRoot
        val t = target.fetchSemanticsNode().positionInRoot
        assertEquals(n.y, b.y, 0.1f)
        assertEquals(b.y, t.y, 0.1f)
        assertTrue(n.x < b.x && b.x < t.x)
        assertTrue(back.fetchSemanticsNode().positionInRoot.y < n.y)
        assertTrue(back.fetchSemanticsNode().positionInRoot.x > b.x)
        assertTrue(n.y < compose.onNodeWithText("Incident details").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithContentDescription("Go back to Main Settings Screen").assertDoesNotExist()
        note.performClick()
        back.performClick()
        compose.runOnIdle { assertEquals(listOf("note", "back"), invoked); assertEquals(0, exits) }
    }

    @Test fun liveStopIconKeepsAccessibleLabelAndDisabledActionsDoNotDispatch() {
        val card = mutableStateOf(cards()[2])
        var clicks = 0
        compose.setContent { HostedPluginIconAction(card.value) { clicks++ } }
        compose.onNodeWithContentDescription("bloodhound label")
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        compose.runOnIdle {
            val action = card.value.action!!
            card.value = card.value.copy(action = action.copy(
                actionIcon = C.STOP, actionLabel = "Stop Bloodhound", enabled = false,
            ))
        }
        compose.onNodeWithContentDescription("bloodhound label").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop Bloodhound").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Stop Bloodhound").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun legacyTextAndTitleFreeServerChoicesRemainActionable() {
        val cards = HostedPluginMainUiContract.parse(PluginMainUiSchemaParcel(cards = listOf(
            MainUiCardParcel("ACTION", "legacy", "Refresh status",
                content = hostedActionContent("refresh", "Refresh")),
            MainUiCardParcel("ACTION", "server", "Server separator",
                content = hostedActionContent("server.select", "Server Alpha").apply {
                    putBoolean(C.SHOW_TITLE, false)
                }),
        )))
        val invoked = mutableListOf<String>()
        compose.setContent {
            HostedPluginMainUiScreen("Plugins", HostedPluginMainUiResult.Ready(cards), {},
                invoked::add)
        }
        compose.onNodeWithText("Refresh status").assertExists()
        compose.onNodeWithText("Refresh").assertExists().performClick()
        compose.onNodeWithText("Server separator").assertDoesNotExist()
        compose.onNodeWithText("Server Alpha").assertExists().performClick()
        compose.runOnIdle { assertEquals(listOf("legacy", "server"), invoked) }
    }

    @Test fun groupsAreBoundedAndNeverMergeAcrossStatusCards() {
        val base = cards()
        val many = listOf(base[1], base[2], base[3], base[1].copy(cardId = "four"), base[4], base[2])
        val rows = hostedPluginBodyRows(many)
        assertEquals(listOf(3, 1, 1, 1), rows.map { it.size })
        assertEquals(many.map { it.cardId }, rows.flatten().map { it.cardId })
        assertFalse(hostedPluginBodyRows(base).flatten().any { it.cardId == "back" })
    }

    @Test fun incidentFieldTitlesAreBoldAccessibleHeadingsWithNormalBodyText() {
        val cards = HostedPluginMainUiContract.parse(PluginMainUiSchemaParcel(cards = listOf(
            MainUiCardParcel(MainUiCardTypes.STATUS, "address", "Address", "100 Training Road"),
        )))
        compose.setContent {
            HostedPluginMainUiScreen("TAK CAD", HostedPluginMainUiResult.Ready(cards), {}, {})
        }
        val heading = compose.onNodeWithText("Address")
        heading.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val results = mutableListOf<TextLayoutResult>()
            assertTrue(read(results))
            assertEquals(FontWeight.Bold, results.single().layoutInput.style.fontWeight)
        }
        compose.onNodeWithText("100 Training Road")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
    }
}
