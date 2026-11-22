package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
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
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BloodhoundPickerActionsTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)
    private val marker = Utils.BloodhoundItem("Rally Point", "marker", "Sender", 1L, 1.2, 0f, false)

    @Test fun markerButtonsMapStartAndStopWithoutOpeningDetails() {
        val active = mutableStateOf(false)
        var mapped = 0
        var started = 0
        var stopped = 0
        var opened = 0
        compose.setContent {
            BloodhoundPickerList(listOf(marker.copy(bloodhounding = active.value)), emptyList(), null,
                { opened++ }, {}, Modifier.fillMaxSize(), onMapTarget = { mapped++ },
                onStartTarget = { started++; active.value = true },
                onStopTarget = { stopped++; active.value = false })
        }
        compose.onNodeWithText("Marker · 1200 m").assertExists()
        compose.onNodeWithContentDescription("Show Rally Point on map").performClick()
        compose.runOnIdle { assertEquals(1, mapped); assertEquals(0, started); assertEquals(0, opened) }
        compose.onNodeWithContentDescription("Start Bloodhound to Rally Point").performClick()
        compose.onNodeWithContentDescription("Stop Bloodhound to Rally Point").performClick()
        compose.onNodeWithTag("bloodhound-target-marker-body").performClick()
        compose.runOnIdle { assertEquals(1, started); assertEquals(1, stopped); assertEquals(1, opened) }
    }

    @Test fun contactsAndPointsHaveTypedSubtitlesAndImmediateHoundActions() {
        val item = mutableStateOf(marker.copy(targetKind = Utils.BloodhoundTargetKind.CONTACT, distance = 0.34))
        var started: String? = null
        compose.setContent {
            BloodhoundPickerList(listOf(item.value), emptyList(), null, {}, {}, Modifier.fillMaxSize(),
                onStartTarget = { started = it })
        }
        compose.onNodeWithText("Contact · 340 m").assertExists()
        compose.onNodeWithTag("bloodhound-target-marker-hound").performClick()
        compose.runOnIdle { assertEquals("marker", started); item.value = item.value.copy(targetKind = Utils.BloodhoundTargetKind.POINT) }
        compose.onNodeWithText("Point · 340 m").assertExists()
    }

    @Test fun locationlessAlertCannotMapOrStart() {
        compose.setContent {
            BloodhoundPickerList(listOf(marker.copy(hasLocation = false)), emptyList(), null, {}, {}, Modifier.fillMaxSize())
        }
        compose.onNodeWithTag("bloodhound-target-marker-map").assertIsNotEnabled()
        compose.onNodeWithTag("bloodhound-target-marker-hound").assertIsNotEnabled()
        compose.onNodeWithTag("bloodhound-target-marker-body").assertHasClickAction()
    }

    @Test fun removeMarkerDoesNotOpenOrStartIt() {
        var removed: String? = null
        var opened = 0
        compose.setContent {
            BloodhoundPickerList(listOf(marker), emptyList(), null, { opened++ }, {}, Modifier.fillMaxSize(),
                onRemoveTarget = { removed = it })
        }
        compose.onNodeWithContentDescription("Remove Rally Point from watch").performClick()
        compose.runOnIdle { assertEquals("marker", removed); assertEquals(0, opened) }
    }

    @Test fun smallWatchPickerActionsFitWithoutOverlap() {
        verifyPickerLayout()
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerWatchPickerActionsFitWithoutOverlap() {
        verifyPickerLayout()
    }

    private fun verifyPickerLayout() {
        compose.setContent {
            BloodhoundPickerList(listOf(marker), emptyList(), null, {}, {}, Modifier.fillMaxSize())
        }
        val body = compose.onNodeWithTag("bloodhound-target-marker-body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("bloodhound-target-marker-map").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val hound = compose.onNodeWithTag("bloodhound-target-marker-hound").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val remove = compose.onNodeWithTag("bloodhound-target-marker-remove").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("bloodhound-target-marker").fetchSemanticsNode().boundsInRoot
        assertTrue(body.right <= map.left)
        assertEquals(map.left, hound.left)
        assertEquals(hound.left, remove.left)
        assertTrue(map.bottom < hound.top)
        assertTrue(hound.bottom < remove.top)
        assertTrue(remove.right <= row.right)
        assertTrue(body.width > 0)
    }
}
