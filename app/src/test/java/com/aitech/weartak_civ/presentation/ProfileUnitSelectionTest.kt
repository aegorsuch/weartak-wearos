package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import com.aitech.weartak_civ.data.ProfileUnit
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileUnitSelectionTest {
    @get:Rule val compose = createComposeRule()

    private fun reveal(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    @Test fun legacyValueIsExplainedWithoutSelectingFirstEntryOrWriting() {
        val writes = mutableListOf<ProfileUnit>()
        val nav = mockk<NavHostController>(relaxed = true)
        compose.setContent {
            WearTAKUnitSelectionPage("Coalition Military", nav) { writes += it }
        }
        reveal("Legacy value: Coalition Military. Select a Unit.")
        compose.onNodeWithText("Legacy value: Coalition Military. Select a Unit.").assertIsDisplayed()
        for (label in listOf("Air Force", "Army", "Civilian", "Foreign National", "Marine Corps", "Navy", "None")) {
            reveal(label)
            compose.onNodeWithText(label).assertIsNotSelected()
        }
        assertTrue(writes.isEmpty())
        reveal("Army")
        compose.onNodeWithText("Army").performClick().assertIsSelected()
        assertEquals(listOf(ProfileUnit.ARMY), writes)
    }

    @Test fun persistedNoneAndManagedUpdatesSelectCorrectEntryWithoutWrites() {
        val stored = mutableStateOf("N/A")
        val writes = mutableListOf<ProfileUnit>()
        val nav = mockk<NavHostController>(relaxed = true)
        compose.setContent {
            WearTAKUnitSelectionPage(stored.value, nav) { writes += it }
        }
        reveal("None")
        compose.onNodeWithText("None").assertIsSelected()
        compose.runOnIdle { stored.value = "air_force" }
        reveal("Air Force")
        compose.onNodeWithText("Air Force").assertIsSelected()
        compose.runOnIdle { stored.value = "Navy" }
        reveal("Navy")
        compose.onNodeWithText("Navy").assertIsSelected()
        assertTrue(writes.isEmpty())
        reveal("None")
        compose.onNodeWithText("None").performClick()
        assertEquals(listOf(ProfileUnit.NONE), writes)
    }
}
