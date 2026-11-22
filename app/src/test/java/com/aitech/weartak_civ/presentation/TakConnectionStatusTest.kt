package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.TAKConnectService
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
class TakConnectionStatusTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)
    private val disconnected = TAKConnectService.TAK_SERVER_DISCONNECTED_STRING
    private val connected = TAKConnectService.TAK_SERVER_CONNECTED_STRING
    private val connecting = TAKConnectService.TAK_SERVER_CONNECTING_STRING
    private val failed = TAKConnectService.TAK_SERVER_CONNECTION_FAILED_STRING

    @Test fun onlyEnabledDisconnectedServersInBatchingGapShowOffWrist() {
        assertEquals(R.string.physio_not_worn, takServerStatusLabel(true, disconnected, true))
        assertEquals(R.string.tak_status_connected, takServerStatusLabel(true, connected, true))
        assertEquals(R.string.server_connecting, takServerStatusLabel(true, connecting, true))
        assertEquals(R.string.connection_failed, takServerStatusLabel(true, failed, true))
        assertEquals(R.string.tak_status_off, takServerStatusLabel(false, disconnected, true))
        assertEquals(R.string.tak_status_not_connected, takServerStatusLabel(true, disconnected, false))
    }

    @Test fun networkSubtitleRequiresEnabledServersAndErrorsBeatScheduledGaps() {
        assertEquals(R.string.physio_not_worn, takNetworkStatusOverride(true,
            listOf(disconnected, disconnected), true))
        assertNull(takNetworkStatusOverride(true, listOf(connected), false))
        assertNull(takNetworkStatusOverride(true, listOf(connecting), false))
        assertNull(takNetworkStatusOverride(true, listOf(disconnected), false))
        assertNull(takNetworkStatusOverride(false, listOf(disconnected), true))
        assertNull(takNetworkStatusOverride(true, emptyList(), true))
        assertNull(takNetworkStatusOverride(true, listOf(disconnected, connected), true))
        assertEquals(R.string.connection_failed, takNetworkStatusOverride(true,
            listOf(disconnected, failed, connected), true))
    }

    @Test fun renderedServerSubtitleUpdatesForWindowsChargingErrorsAndDisablement() {
        var waiting by mutableStateOf(true)
        var status by mutableStateOf(disconnected)
        var enabled by mutableStateOf(true)
        compose.setContent {
            WearTAKCIVTheme {
                WearTAKSplitToggleChip(
                    title = "Test TAK",
                    label = stringResource(takServerStatusLabel(enabled, status, waiting)),
                    checked = enabled, onCheckedChange = {}, onClick = {},
                )
            }
        }
        compose.onNodeWithText("Off-Wrist").assertExists()
        compose.runOnIdle { waiting = false; status = connected }
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText("Off-Wrist").assertDoesNotExist()
        compose.runOnIdle { status = disconnected }
        compose.onNodeWithText("Not connected").assertExists()
        compose.runOnIdle { waiting = true; status = failed }
        compose.onNodeWithText("Connection Failed").assertExists()
        compose.onNodeWithText("Off-Wrist").assertDoesNotExist()
        compose.runOnIdle { enabled = false; status = disconnected }
        compose.onNodeWithText("Switched off").assertExists()
    }

    @Test fun networkRowRendersOffWristAndRetainsFailurePriority() {
        var statuses by mutableStateOf(listOf(disconnected))
        var waiting by mutableStateOf(true)
        compose.setContent {
            val override = takNetworkStatusOverride(true, statuses, waiting)
            val subtitle = override?.let { stringResource(it) } ?: "0/1"
            WearTAKCIVTheme {
                NetworkConnectivityChip(
                    NetworkConnectivityRow("tak", "TAK Servers", subtitle, true, {},
                        statusDescription = subtitle),
                    networkPreferencesLocked = false, phoneManagedSettings = false,
                    navigate = {}, showPhoneManagedNotice = {}, showNetworkLockedNotice = {},
                )
            }
        }
        compose.onNodeWithText("Off-Wrist").assertExists()
        compose.runOnIdle { statuses = listOf(failed) }
        compose.onNodeWithText("Connection Failed").assertExists()
        compose.runOnIdle { statuses = listOf(disconnected); waiting = false }
        compose.onNodeWithText("0/1").assertExists()
        compose.onNodeWithText("Off-Wrist").assertDoesNotExist()
    }
}
