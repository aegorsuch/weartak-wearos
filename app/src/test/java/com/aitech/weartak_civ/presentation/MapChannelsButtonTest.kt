package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapChannelsButtonTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun lockDisablesChannelEntryAndUnlockRestoresItLive() {
        val locked = mutableStateOf(true)
        var opens = 0
        compose.setContent {
            MapChannelsButton(enabled = !locked.value, onClick = { opens++ })
        }
        val button = compose.onNodeWithContentDescription("Channels")
        button.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, opens); locked.value = false }
        button.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opens); locked.value = true }
        button.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
