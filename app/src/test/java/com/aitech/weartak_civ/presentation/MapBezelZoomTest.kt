package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapBezelZoomTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clockwiseZoomsInAndCounterclockwiseZoomsOut() {
        val deltas = mutableListOf<Double>()
        assertTrue(zoomMapWithBezel(-1f, false, Lifecycle.State.RESUMED) { deltas += it })
        assertTrue(zoomMapWithBezel(0.5f, false, Lifecycle.State.RESUMED) { deltas += it })
        assertEquals(listOf(1.0, -0.5), deltas)
    }

    @Test fun overlayReturnsFalseWithoutChangingCameraAndZoomResumesAfterDismissal() {
        var zoomChanges = 0
        val changeZoom: (Double) -> Unit = { zoomChanges++ }
        for (scroll in listOf(-1f, 1f)) {
            assertFalse(zoomMapWithBezel(scroll, true, Lifecycle.State.RESUMED, changeZoom))
        }
        assertEquals(0, zoomChanges)
        assertTrue(zoomMapWithBezel(-1f, false, Lifecycle.State.RESUMED, changeZoom))
        assertEquals(1, zoomChanges)
    }

    @Test fun onlyResumedMapConsumesRotaryInput() {
        var zoomChanges = 0
        for (state in Lifecycle.State.entries) {
            assertEquals(state == Lifecycle.State.RESUMED,
                zoomMapWithBezel(-1f, false, state) { zoomChanges++ })
        }
        assertEquals(1, zoomChanges)
    }

    @Test fun selectedUserNavigationStopsRetainedMapZoomAndBackRestoresIt() {
        var handleRotary: ((Float) -> Boolean)? = null
        var zoomChanges = 0
        compose.setContent {
            MapUserNavigation(emptyList(), LocalContext.current) { onUserClick ->
                val owner = LocalLifecycleOwner.current
                SideEffect {
                    handleRotary = { scroll ->
                        zoomMapWithBezel(scroll, false, owner.lifecycle.currentState) { zoomChanges++ }
                    }
                }
                Chip(onClick = { onUserClick("alice") }, label = { Text("Select map user") })
            }
        }
        compose.runOnIdle {
            assertTrue(handleRotary!!(-1f))
            assertEquals(1, zoomChanges)
        }
        compose.onNodeWithText("Select map user").performClick()
        compose.runOnIdle {
            assertFalse(handleRotary!!(-1f))
            assertEquals(1, zoomChanges)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertTrue(handleRotary!!(-1f))
            assertEquals(2, zoomChanges)
        }
    }
}
