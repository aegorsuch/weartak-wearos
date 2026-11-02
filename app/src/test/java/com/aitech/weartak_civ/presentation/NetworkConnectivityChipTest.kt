package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class NetworkConnectivityChipTest {
    @get:Rule val compose = createComposeRule()

    private val key = mutableStateOf("tak")
    private val checked = mutableStateOf(true)
    private val networkLocked = mutableStateOf(false)
    private val phoneManaged = mutableStateOf(false)
    private val routes = mutableListOf<String>()
    private var toggles = 0
    private var phoneNotices = 0
    private var lockNotices = 0

    private fun render() {
        compose.setContent {
            WearTAKCIVTheme {
                NetworkConnectivityChip(
                    row = NetworkConnectivityRow(key.value, "Transport", "Subtitle", checked.value,
                        { checked.value = it; toggles++ }, statusDescription = "Connected"),
                    networkPreferencesLocked = networkLocked.value,
                    phoneManagedSettings = phoneManaged.value,
                    navigate = { routes += it },
                    showPhoneManagedNotice = { phoneNotices++ },
                    showNetworkLockedNotice = { lockNotices++ }
                )
            }
        }
    }

    @Test fun everyBuiltInLabelRoutesWithoutTogglingAndSwitchOnlyToggles() {
        render()
        val expected = mapOf("tak" to "tak_servers", "companion" to COMPANION_DETAILS_ROUTE,
            "multicast" to "tak_sa_multicast", "sitx" to "sitx_tak_screen", "ditto" to "ditto_pdl")
        expected.forEach { (transport, destination) ->
            compose.runOnIdle { key.value = transport; checked.value = true }
            val before = toggles
            compose.onNodeWithTag("network_${transport}_settings").performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(destination, routes.last())
                assertTrue(checked.value)
                assertEquals(before, toggles)
            }
            val navigations = routes.size
            compose.onNodeWithTag("network_${transport}_toggle").assertIsOn().performTouchInput { click() }
            compose.onNodeWithTag("network_${transport}_toggle").assertIsOff()
            compose.runOnIdle {
                assertEquals(navigations, routes.size)
                assertEquals(before + 1, toggles)
            }
        }
    }

    @Test fun separateActionsAnnounceSettingsAndToggleStateWithStatusAndHave48dpTargets() {
        render()
        compose.onNodeWithText("Subtitle").assertIsDisplayed()
        val label = compose.onNodeWithText("Transport")
        label.assert(SemanticsMatcher("named navigation action") {
            it.config[SemanticsActions.OnClick].label == "Open Transport settings"
        })
        val toggle = compose.onNode(isToggleable())
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On, Connected"))
        toggle.assertContentDescriptionEquals("Transport")
        for (target in listOf(label, toggle)) {
            val bounds = target.getUnclippedBoundsInRoot()
            assertTrue("bounds $bounds", (bounds.right - bounds.left).value >= 48f)
            assertTrue("bounds $bounds", (bounds.bottom - bounds.top).value >= 48f)
        }
        toggle.performClick().assertIsOff()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off, Connected"))
    }

    @Test fun eitherLockDisablesTransportSwitchesButKeepsNonServerNavigation() {
        render()
        for (managed in listOf(false, true)) {
            for (transport in listOf("multicast", "sitx", "ditto")) {
                compose.runOnIdle {
                    key.value = transport
                    phoneManaged.value = managed
                    networkLocked.value = !managed
                }
                compose.onNode(isToggleable()).assertIsNotEnabled().assertIsOn()
                    .performTouchInput { click() }
                compose.onNodeWithText("Transport").assertIsEnabled().performClick()
                compose.runOnIdle {
                    assertEquals(networkSettingsRoute(transport), routes.last())
                    assertEquals(0, toggles)
                    assertTrue(checked.value)
                }
            }
        }
    }

    @Test fun lockedServerLabelShowsExistingNoticeInsteadOfNavigating() {
        render()
        compose.runOnIdle { phoneManaged.value = true }
        compose.onNodeWithText("Transport").performClick()
        compose.onNode(isToggleable()).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, phoneNotices)
            assertTrue(routes.isEmpty())
            assertEquals(0, toggles)
            phoneManaged.value = false
            networkLocked.value = true
        }
        compose.onNodeWithText("Transport").performClick()
        compose.runOnIdle {
            assertEquals(1, lockNotices)
            assertTrue(routes.isEmpty())
            assertEquals(0, toggles)
        }
    }

    @Test fun pluginWithoutDestinationRetainsToggleOnlyAndLocks() {
        key.value = "plugin:example"
        render()
        compose.onNodeWithText("Transport").performClick().assertIsOff()
        compose.runOnIdle {
            assertNull(networkSettingsRoute(key.value))
            assertTrue(routes.isEmpty())
            assertEquals(1, toggles)
            networkLocked.value = true
        }
        compose.onNode(isToggleable()).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, toggles); networkLocked.value = false; phoneManaged.value = true }
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test fun companionKeepsOriginalStatusDuringBondButRespectsPolicyLock() {
        key.value = "companion"
        phoneManaged.value = true
        render()
        compose.onNodeWithText("Bonded").assertDoesNotExist()
        compose.onNodeWithText("Subtitle").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On, Connected"))
        compose.runOnIdle { networkLocked.value = true }
        compose.onNode(isToggleable()).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, toggles) }
        compose.onNodeWithText("Bonded").assertDoesNotExist()
    }

    @Test fun bondReplacesSubtitleAndAccessibleStatusOnLockedChecklistAndPluginEquivalent() {
        render()
        for (transport in listOf("tak", "multicast", "sitx", "ditto", "plugin:example")) {
            compose.runOnIdle { key.value = transport; phoneManaged.value = true }
            compose.onNodeWithText("Bonded").assertIsDisplayed()
            compose.onNodeWithText("Subtitle").assertDoesNotExist()
            val toggle = compose.onNode(isToggleable()).assertIsNotEnabled().assertIsOn()
            if (transport.startsWith("plugin:")) {
                toggle.assert(hasText("Bonded"))
            } else {
                toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On, Bonded"))
            }
            compose.runOnIdle { phoneManaged.value = false; networkLocked.value = true }
            compose.onNodeWithText("Bonded").assertDoesNotExist()
            compose.onNodeWithText("Subtitle").assertIsDisplayed()
            compose.onNode(isToggleable()).assertIsNotEnabled()
            compose.runOnIdle { networkLocked.value = false }
            compose.onNode(isToggleable()).assertIsEnabled()
        }
    }

    @Test fun bondAddsSubtitleToEmptyRowsAndTakMasterEquivalentAndRestoresEmptyOnRelease() {
        var bonded by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            WearTAKCIVTheme {
                WearTAKToggleChip(true, {}, title = "TAK Servers",
                    description = networkControlSubtitle("", bonded), enabled = !bonded)
            }
        }
        compose.onNodeWithText("Bonded").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.runOnIdle { bonded = false }
        compose.onNodeWithText("Bonded").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsEnabled()
    }
}
