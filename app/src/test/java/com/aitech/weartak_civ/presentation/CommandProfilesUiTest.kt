package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aitech.weartak_civ.command.ProfilePresetPreview
import com.aitech.weartak_civ.command.ProfilePresetServerPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommandProfilesUiTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun smallRoundListOpensReadOnlyProfileAndServerDetails() = opensProfile()

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundListOpensReadOnlyProfileAndServerDetails() = opensProfile()

    private fun opensProfile() {
        val preview = ProfilePresetPreview(
            mapOf("callsign" to "ODIN-WEARTAK", "pants" to "30", "waist" to "32"),
            listOf(ProfilePresetServerPreview("North TAK", "tak.example", 8089))
        )
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "profiles") {
                composable("profiles") {
                    CommandProfilesContent(nav, null, Result.success(listOf("odin") to null))
                }
                composable("chat_command_profile/{name}") {
                    CommandProfilesContent(nav, "odin", Result.success(emptyList<String>() to preview))
                }
            }
        }
        compose.onNodeWithText("odin").performScrollTo().performClick()
        scrollTo("Callsign: ODIN-WEARTAK")
        compose.onNodeWithText("Callsign: ODIN-WEARTAK").assertIsDisplayed()
        scrollTo("Pants length (in): 30")
        compose.onNodeWithText("Pants length (in): 30").assertIsDisplayed()
        scrollTo("North TAK\ntak.example:8089")
        compose.onNodeWithText("North TAK\ntak.example:8089").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Load").assertDoesNotExist()
    }

    @Test fun loadingEmptyAndErrorStatesAreExplicit() {
        val state = mutableStateOf<Result<Pair<List<String>, ProfilePresetPreview?>>?>(null)
        compose.setContent { CommandProfilesContent(rememberNavController(), null, state.value) }
        compose.onNodeWithText("Loading saved profiles...").assertExists()
        compose.runOnIdle { state.value = Result.success(emptyList<String>() to null) }
        scrollTo("No saved profiles. Add preset JSON files to the app's presets folder.")
        compose.onNodeWithText("No saved profiles. Add preset JSON files to the app's presets folder.")
            .assertIsDisplayed()
        compose.runOnIdle { state.value = Result.failure(java.io.IOException("SECRET")) }
        compose.onNodeWithText("Could not read saved profiles. Check the preset files and reopen Profiles.")
            .assertExists()
        compose.onNodeWithText("SECRET", substring = true).assertDoesNotExist()
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }
}
