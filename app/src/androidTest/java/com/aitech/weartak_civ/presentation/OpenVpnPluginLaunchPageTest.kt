package com.aitech.weartak_civ.presentation

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenVpnPluginLaunchPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingOptionalPluginShowsInstallMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(context.packageManager.getLaunchIntentForPackage(
            OpenVpnHostContract.PLUGIN_PACKAGE,
        ))

        composeRule.setContent {
            OpenVpnPluginLaunchPage(NavController(context))
        }

        composeRule.onNodeWithText("Open OpenVPN plugin").performClick()
        composeRule.onNodeWithText("Install WearTAK OpenVPN plugin first").assertIsDisplayed()
    }
}
