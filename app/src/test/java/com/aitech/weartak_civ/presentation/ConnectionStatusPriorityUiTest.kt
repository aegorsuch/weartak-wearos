package com.aitech.weartak_civ.presentation

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.defaultConnectionPriority
import com.aitech.weartak_civ.data.moveConnectionPriority
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
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
class ConnectionStatusPriorityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reorderAndResetWorkOn192dpRound() = verifyOrdering()

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun reorderAndResetWorkOn227dpRound() = verifyOrdering()

    private fun verifyOrdering() {
        var priority by mutableStateOf(defaultConnectionPriority)
        val pluginKey = pluginRoutePriorityKey("atom", "mesh")
        val pluginName = "ATOM: Meshtastic"
        compose.setContent {
            ConnectionStatusPriorityPage(priority, mapOf(pluginKey to pluginName),
                onMove = { available, key, offset ->
                    priority = moveConnectionPriority(priority, available, key, offset)
                }, onReset = { priority = defaultConnectionPriority }, onBack = {})
        }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToKey("companion")
        compose.onNodeWithContentDescription("Move WearTAK Companion up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move WearTAK Companion down").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("tak", priority.first())
            assertEquals("companion", priority[1])
        }
        list.performScrollToKey("ditto")
        compose.onNodeWithContentDescription("Move Ditto Edge Sync up").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(priority.indexOf("ditto") < priority.indexOf("multicast")) }
        list.performScrollToKey(pluginKey)
        compose.onNodeWithContentDescription("Move $pluginName down").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move $pluginName up").performClick()
        compose.runOnIdle { assertTrue(priority.indexOf(pluginKey) < priority.indexOf("multicast")) }
        list.performScrollToKey("reset")
        compose.onNodeWithText("Reset priority").performClick()
        compose.runOnIdle { assertEquals(defaultConnectionPriority, priority) }
    }

    @Test fun homePluginIconAnnouncesSelectedRouteInsteadOfCompanionAndChangesLive() {
        var status by mutableStateOf(HomeConnectionStatus(
            "plugin:atom:mesh", R.drawable.tak_server_connected, R.string.network_preferences,
            R.string.plugin_route_connected, "ATOM: Meshtastic", ColorDrawable(Color.RED)))
        compose.setContent { WearTAKCIVTheme { HomeConnectionIcon(status, Modifier.size(25.dp)) } }
        compose.onNodeWithContentDescription("ATOM: Meshtastic, Connected. Open Network Preferences")
            .assertIsDisplayed()
        compose.runOnIdle {
            status = HomeConnectionStatus("ditto", R.drawable.ic_integration_ditto,
                R.string.ditto, R.string.ditto_sync_active)
        }
        compose.onNodeWithContentDescription("Ditto Edge Sync, Sync active. Open Network Preferences")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("ATOM: Meshtastic, Connected. Open Network Preferences")
            .assertDoesNotExist()
    }

    @Test fun reportingPluginWithoutToggleShowsReadOnlyLiveStatus() {
        var subtitle by mutableStateOf("Meshtastic: Connecting")
        compose.setContent { WearTAKCIVTheme {
            NetworkConnectivityChip(
                NetworkConnectivityRow("plugin:atom", "ATOM", subtitle, true, {},
                    toggleAvailable = false),
                false, false, {}, {}, {})
        } }
        compose.onNodeWithText("Meshtastic: Connecting").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.runOnIdle { subtitle = "Meshtastic: Connected" }
        compose.onNodeWithText("Meshtastic: Connected").assertIsDisplayed()
    }
}
