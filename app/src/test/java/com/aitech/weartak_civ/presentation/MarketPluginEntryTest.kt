package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aitech.weartak_civ.market.MarketInstallState
import com.aitech.weartak_civ.market.MarketPlugin
import com.aitech.weartak_civ.market.MarketPublisherType
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarketPluginEntryTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test
    fun downloadShowsSpinnerThenInlineFailureAndRetryAction() {
        val installing = mutableStateOf(true)
        val result = mutableStateOf<String?>(null)
        var retries = 0
        val plugin = hostPlugin().copy(publisher = " aegorsuch ")
        compose.setContent {
            WearTAKCIVTheme {
                MarketPluginEntry(
                    plugin = plugin,
                    installState = MarketInstallState.UPDATE,
                    canStartInstall = !installing.value,
                    installing = installing.value,
                    installResult = result.value,
                    onInstall = { retries++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Downloading and verifying…", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Downloading and verifying…").assertDoesNotExist()
        compose.onNodeWithText("WearTAK-WearOS").assertIsNotEnabled()
        compose.onNodeWithText("WearTAK by  aegorsuch ").assertDoesNotExist()
        compose.onNodeWithContentDescription("Update", useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle {
            installing.value = false
            result.value = "Download timed out."
        }

        compose.onNodeWithContentDescription("Downloading and verifying…", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Download timed out.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Update", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("WearTAK-WearOS").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun pluginRowsKeepPublisherAttributionEvenWhenNamedWearTak() {
        compose.setContent {
            WearTAKCIVTheme {
                MarketPluginEntry(
                    plugin = hostPlugin().copy(
                        packageName = "org.example.plugin",
                        hostUpdate = false,
                    ),
                    installState = MarketInstallState.INSTALL,
                    canStartInstall = true,
                    installing = false,
                    installResult = null,
                    onInstall = {},
                )
            }
        }
        compose.onNodeWithText("WearTAK by WearTAK").assertIsDisplayed()
        compose.onNodeWithText("WearTAK-WearOS").assertDoesNotExist()
    }

    @Test
    fun aegorsuchPluginRowsHidePublisherIgnoringCaseAndWhitespace() {
        val publisher = mutableStateOf("aegorsuch")
        compose.setContent {
            WearTAKCIVTheme {
                MarketPluginEntry(
                    plugin = hostPlugin().copy(
                        name = "Example",
                        publisher = publisher.value,
                        packageName = "org.example.plugin",
                        hostUpdate = false,
                    ),
                    installState = MarketInstallState.INSTALL,
                    canStartInstall = true,
                    installing = false,
                    installResult = null,
                    onInstall = {},
                )
            }
        }

        compose.onNodeWithText("Example").assertIsDisplayed()
        compose.onNodeWithText("Example by aegorsuch").assertDoesNotExist()
        compose.runOnIdle { publisher.value = " \tAeGoRsUcH \n" }
        compose.onNodeWithText("Example").assertIsDisplayed()
        compose.onNodeWithText("Example by  \tAeGoRsUcH \n").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "zh-w227dp-h227dp-round-340dpi")
    fun localizedPluginRowsKeepOtherPublisherAttribution() {
        val publisher = mutableStateOf(" aegorsuch ")
        compose.setContent {
            WearTAKCIVTheme {
                MarketPluginEntry(
                    plugin = hostPlugin().copy(
                        name = "Example",
                        publisher = publisher.value,
                        packageName = "org.example.plugin",
                        hostUpdate = false,
                    ),
                    installState = MarketInstallState.INSTALL,
                    canStartInstall = true,
                    installing = false,
                    installResult = null,
                    onInstall = {},
                )
            }
        }

        compose.onNodeWithText("Example").assertIsDisplayed()
        compose.runOnIdle { publisher.value = "Other" }
        compose.onNodeWithText("Example（由 Other 提供）").assertIsDisplayed()
    }

    private fun hostPlugin() = MarketPlugin(
        id = "weartak",
        name = "WearTAK",
        description = "",
        publisher = "WearTAK",
        publisherType = MarketPublisherType.OFFICIAL,
        packageName = "com.aitech.weartak_civ",
        versionName = "5.8.4-p727",
        versionCode = 5840727,
        minHostVersionCode = 1,
        maxHostVersionCode = null,
        apkUrl = "https://example.org/release.apk",
        apkSha256 = "a".repeat(64),
        signerSha256 = "b".repeat(64),
        sourceUrl = null,
        hostUpdate = true,
    )
}
