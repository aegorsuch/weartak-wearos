package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import io.mockk.mockk
import io.mockk.verify
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.viewmodel.updateCompanionServiceSetting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
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
class NetworkMulticastBondTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun companionOffReleasesBondAndUnlocksMulticastWithOriginalSubtitle() {
        MainActivity.device_uid = "network-companion-exit-test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        runBlocking {
            repo.legacyDataStore().edit { it.clear() }
            repo.storeEnableCompanionAppService(true)
            repo.storeEnableMulticast(true)
            repo.enablePhoneManagedSettings("AA:BB")
            repo.setPhoneManagementSessionActive(true)
        }
        var showCompanion by mutableStateOf(false)
        compose.setContent {
            val bonded by repo.phoneManagedSettingsLocked.collectAsState(initial = true)
            val enabled by repo.getEnableCompanionAppService.collectAsState(initial = true)
            val scope = rememberCoroutineScope()
            WearTAKCIVTheme {
                if (showCompanion) NetworkConnectivityChip(
                    NetworkConnectivityRow("companion", "WearTAK Companion", "Connected", enabled,
                        { scope.launch { updateCompanionServiceSetting(repo, it) } }),
                    false, bonded, {}, {}, {})
                else MulticastNetworkToggle(true, bonded, false) {}
            }
        }
        compose.onNodeWithText("Bonded").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsNotEnabled().assertIsOn()
        compose.runOnIdle { showCompanion = true }
        compose.onNodeWithText("Bonded").assertDoesNotExist()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOn().performClick()
        compose.waitUntil { runBlocking { !repo.getEnableCompanionAppService.first() } }
        runBlocking {
            assertFalse(repo.phoneManagedSettingsLocked.first())
            assertFalse(repo.phoneManagedSettingsEnabled.first())
            assertNull(repo.phoneManagedCompanionAddress.first())
            assertTrue(repo.getEnableMulticast.first())
        }
        compose.runOnIdle { showCompanion = false }
        compose.onNodeWithText("Bonded").assertDoesNotExist()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.service_on_off))
            .assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOn()
    }

    @Test fun independentPolicyLockStillBlocksCompanionRelease() = runBlocking {
        MainActivity.device_uid = "network-companion-policy-test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { it.clear() }
        repo.storeEnableCompanionAppService(true)
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        repo.storeNetworkPreferencesLocked(true)
        updateCompanionServiceSetting(repo, false)
        assertTrue(repo.getEnableCompanionAppService.first())
        assertTrue(repo.phoneManagedSettingsLocked.first())
        repo.setPhoneManagementSessionActive(false)
    }

    @Test fun bondedEntryBlocksNavigationAndRestoresOriginalStateOnRelease() {
        var bonded by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var notices = 0
        val navigation = mockk<NavController>(relaxed = true)
        compose.setContent {
            WearTAKCIVTheme {
                MulticastNetworkPreference(enabled, bonded, navigation) { notices++ }
            }
        }
        compose.onNodeWithText("Bonded").assertIsDisplayed()
        compose.onNodeWithText("TAK SA Multicast").performClick()
        assertEquals(1, notices)
        verify(exactly = 0) { navigation.navigate(any<String>()) }
        compose.runOnIdle { bonded = false }
        compose.onNodeWithText("Bonded").assertDoesNotExist()
        compose.onNodeWithText("Enabled").assertIsDisplayed().performClick()
        verify(exactly = 1) { navigation.navigate("tak_sa_multicast") }
        compose.runOnIdle { enabled = false; bonded = true }
        compose.onNodeWithText("Bonded").assertIsDisplayed()
        compose.runOnIdle { bonded = false }
        compose.onNodeWithText("Disabled").assertIsDisplayed()
    }

    @Test fun detailTogglePreservesStateWhileBondedAndPolicyLockAloneDoesNotSayBonded() {
        var bonded by mutableStateOf(true)
        var locked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var changes = 0
        compose.setContent {
            WearTAKCIVTheme {
                MulticastNetworkToggle(enabled, bonded, locked) { enabled = it; changes++ }
            }
        }
        compose.onNodeWithText("Bonded").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, changes)
        compose.runOnIdle { bonded = false; locked = true }
        compose.onNodeWithText("Bonded").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.runOnIdle { locked = false }
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOn().performClick().assertIsOff()
        assertEquals(1, changes)
    }

    @Test fun bondedMulticastControlsFit192dpRound() = verifyRound(192)

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun bondedMulticastControlsFit227dpRound() = verifyRound(227)

    private fun verifyRound(size: Int) {
        var detail by mutableStateOf(false)
        var density = 1f
        val navigation = mockk<NavController>(relaxed = true)
        compose.setContent {
            density = LocalDensity.current.density
            WearTAKCIVTheme {
                Box(Modifier.requiredSize(size.dp).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center) {
                    if (detail) MulticastNetworkToggle(true, true, false) {}
                    else MulticastNetworkPreference(true, true, navigation) {}
                }
            }
        }
        for (isDetail in listOf(false, true)) {
            compose.runOnIdle { detail = isDetail }
            for (label in listOf("TAK SA Multicast", "Bonded")) {
                val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse("$label overflow at $size", layouts.single().hasVisualOverflow)
                val bounds = node.fetchSemanticsNode().boundsInRoot
                val radius = size * density / 2
                for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
                    val dx = corner.x - radius
                    val dy = corner.y - radius
                    assertTrue("$label outside round face: $bounds", dx * dx + dy * dy <= radius * radius + 1)
                }
            }
        }
    }
}
