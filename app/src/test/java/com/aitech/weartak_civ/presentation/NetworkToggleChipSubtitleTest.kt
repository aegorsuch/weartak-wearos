package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class NetworkToggleChipSubtitleTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun rowStatusDefaultsToSubtitleAndCanBeAccessibilityOnly() {
        assertEquals("1/6", NetworkConnectivityRow("a", "A", "1/6", true, {}).statusDescription)
        val row = NetworkConnectivityRow("b", "B", "", true, {}, statusDescription = "Enabled")
        assertEquals("", row.subtitle)
        assertEquals("Enabled", row.statusDescription)
    }

    @Test fun emptySubtitleShowsNoSecondaryTextButKeepsStatusForAccessibility() {
        compose.setContent {
            WearTAKToggleChip(true, {}, title = "TAK SA Multicast", description = "", statusDescription = "Enabled")
        }
        compose.onNodeWithText("TAK SA Multicast").assertIsDisplayed()
        compose.onNodeWithText("Enabled").assertDoesNotExist()
        val node = compose.onNode(hasText("TAK SA Multicast")).fetchSemanticsNode()
        assertEquals("On, Enabled", node.config[SemanticsProperties.StateDescription])
    }

    @Test fun serverCountSubtitleKeepsInformativeStateWithToggleState() {
        compose.setContent {
            WearTAKToggleChip(false, {}, title = "TAK Servers", description = "1/6", statusDescription = "Connected, 1 of 6 servers")
        }
        compose.onNodeWithText("1/6").assertIsDisplayed()
        val node = compose.onNode(hasText("TAK Servers")).fetchSemanticsNode()
        assertEquals("Off, Connected, 1 of 6 servers", node.config[SemanticsProperties.StateDescription])
    }

    @Test fun announcementOnlyOverridesWhenStatusAddsInformation() {
        assertEquals("On, Connected, 1 of 6 servers", toggleStateAnnouncement(true, "On", "Off", "Connected, 1 of 6 servers", "1/6"))
        assertEquals("Off, Status", toggleStateAnnouncement(false, "On", "Off", "Status", ""))
        assertNull(toggleStateAnnouncement(true, "On", "Off", "", ""))
        assertNull(toggleStateAnnouncement(true, "On", "Off", "Enabled", "Enabled"))
    }
}