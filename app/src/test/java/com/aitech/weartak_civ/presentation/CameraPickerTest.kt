package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.Utils
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
class CameraPickerTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)
    private fun target(camera: Boolean) = Utils.BloodhoundItem(
        "North sensor", "north", "", 100L, 0.125, 0f, false, isCamera = camera)

    @Test fun cameraIconPreservesTitleDistanceAndIndependentActions() {
        val actions = mutableListOf<String>()
        compose.setContent {
            BloodhoundPickerList(listOf(target(true)), emptyList(), null,
                { actions += "body:$it" }, {}, Modifier.fillMaxSize(),
                onMapTarget = { actions += "map:$it" }, onStartTarget = { actions += "hound:$it" },
                onRemoveTarget = { actions += "remove:$it" })
        }
        compose.onNodeWithContentDescription("Camera").assertExists()
        compose.onNodeWithText("North sensor").assertExists()
        compose.onNodeWithText("Camera · 125 m").assertExists()
        for (action in listOf("body", "map", "hound", "remove")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("bloodhound-target-north-$action"))
            compose.onNodeWithTag("bloodhound-target-north-$action").performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("body:north", "map:north", "hound:north", "remove:north"), actions)
        }
    }

    @Test fun ordinarySensorKeepsExistingMarkerIconAndLabel() {
        compose.setContent {
            BloodhoundPickerList(listOf(target(false)), emptyList(), null, {}, {}, Modifier.fillMaxSize())
        }
        compose.onNodeWithContentDescription("Camera").assertDoesNotExist()
        compose.onNodeWithText("Marker · 125 m").assertExists()
    }
}
