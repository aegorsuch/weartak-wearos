package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.edit
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkChecklistPreferenceTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() = runBlocking {
        MainActivity.device_uid = "checklist-preference-test"
        DataRepository(app).legacyDataStore().edit { it.clear() }
        Unit
    }

    @Test fun defaultsOffAndBothChoicesSurviveRepositoryRecreation() = runBlocking {
        val repository = DataRepository(app)
        assertFalse(repository.networkPreferencesChecklistEnabled.first())
        repository.storeNetworkPreferencesChecklistEnabled(true)
        assertTrue(DataRepository(app).networkPreferencesChecklistEnabled.first())
        assertTrue(repository.legacyDataStore().data.first().asMap().any {
            it.key.name == "network_preferences_checklist_enabled" && it.value == true
        })
        repository.storeNetworkPreferencesChecklistEnabled(false)
        assertFalse(DataRepository(app).networkPreferencesChecklistEnabled.first())
    }

    @Test fun persistedChangesShowAndHideOnlyChecklistLive() {
        val repository = DataRepository(app)
        val row = NetworkConnectivityRow("multicast", "TAK SA Multicast", "", true, {})
        compose.setContent {
            val enabled by repository.networkPreferencesChecklistEnabled.collectAsState(initial = false)
            WearTAKCIVTheme {
                ScalingLazyColumn {
                    networkPreferencesChecklist(enabled, listOf(row)) {
                        NetworkConnectivityChip(it, false, false, {}, {}, {})
                    }
                    item { Text("Actual preferences") }
                }
            }
        }
        compose.onNodeWithText("TAK SA Multicast").assertDoesNotExist()
        compose.onNodeWithText("Actual preferences").assertExists()
        runBlocking { repository.storeNetworkPreferencesChecklistEnabled(true) }
        compose.waitUntil {
            compose.onAllNodesWithText("TAK SA Multicast").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("TAK SA Multicast", useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onNodeWithText("Actual preferences").assertExists()
        runBlocking { repository.storeNetworkPreferencesChecklistEnabled(false) }
        compose.waitUntil {
            compose.onAllNodesWithText("TAK SA Multicast").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Actual preferences").assertExists()
    }

    @Test fun developerToggleStartsOffAndControlsOnlyItsOwnState() {
        var checklist by mutableStateOf(false)
        var changes = 0
        compose.setContent {
            DebugInformationPage(
                DebugDisplayInfo("test", 1, "debug", "test", "test", "WIFI", "test", "N/A", 8000),
                {}, {}, {}, networkChecklistEnabled = checklist,
                onNetworkChecklistEnabledChange = { checklist = it; changes++ })
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Network Preferences Checklist"))
        compose.onNodeWithText("Network Preferences Checklist").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            assertTrue(checklist)
            assertEquals(1, changes)
        }
    }

    @Test fun developerOptionsOffersDirectLogExport() {
        var exports = 0
        compose.setContent {
            DebugInformationPage(
                DebugDisplayInfo("test", 1, "debug", "test", "test", "WIFI", "test", "N/A", 8000),
                {}, {}, {}, onExportLogs = { exports++ })
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Export logs"))
        compose.onNodeWithText("Export logs").performClick()
        compose.runOnIdle { assertEquals(1, exports) }
    }
}
