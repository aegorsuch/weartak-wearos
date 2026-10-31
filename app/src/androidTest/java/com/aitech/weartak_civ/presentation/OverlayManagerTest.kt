package com.aitech.weartak_civ.presentation

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OverlayManagerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun categoryDisclosureAndVisibilityActionsSurviveCollapse() {
        var toggles = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        OverlayManagerPopup(
                            mapSource = MapSource.DEFAULT, onMapSourceSelected = {},
                            mapError = null, onRetryMap = {},
                            topoLayers = listOf("contours" to "Contours"),
                            hiddenLayers = emptySet(), onToggle = { toggles++ },
                            hideControls = false, onHideControlsToggle = {},
                            visibleUserTeams = emptySet(), visibleUserRoles = emptySet(),
                            visibleUserRolesLEO = emptySet(), myRole = "",
                            roleCounts = emptyMap(), teamColorCount = emptyMap(),
                            onRoleToggle = {}, onLeoRoleToggle = {}, onTeamToggle = {},
                            overlays = listOf(OverlayToggle("Markers", true) { toggles++ }),
                            availableTeams = emptySet(), availableUserRoles = emptySet(),
                            availableUserRolesLEO = emptySet(), onDismiss = {}
                        )
                    }
                }
            }
            awaitNode("Collapse Layers")
            capture("overlay-manager-expanded.png")
            for (category in listOf("Layers", "Overlays", "Maps", "Roles (0)", "Teams (0)")) {
                click(scrollTo("Collapse $category"))
                assertState("Expand $category", "Collapsed")
            }
            instrumentation.runOnMainSync { assertEquals(0, toggles) }
            scrollTo("Expand Maps")
            capture("overlay-manager-collapsed.png")
            click(awaitNode("Expand Maps"))
            assertState("Collapse Maps", "Expanded")
            scrollTo("OSM Vector")
            click(scrollTo("Expand Layers"))
            click(scrollTo("Contours"))
            instrumentation.runOnMainSync { assertEquals(1, toggles) }
        }
    }

    private fun findNode(label: String? = null, scrollable: Boolean = false): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (if (scrollable) node.isScrollable
                else node.contentDescription?.toString() == label || node.text?.toString() == label)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { find(it)?.let { match -> return match } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        repeat(50) {
            findNode(label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Visible accessibility node not found: $label")
    }

    private fun scrollTo(label: String): AccessibilityNodeInfo {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(15) {
                findNode(label)?.let { return it }
                val scroller = findNode(scrollable = true) ?: error("No Overlay Manager scroll container")
                if (scroller.performAction(direction)) SystemClock.sleep(150)
            }
        }
        return awaitNode(label)
    }

    private fun click(node: AccessibilityNodeInfo) {
        val button = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
            ?: error("No clickable ancestor for $node")
        assertTrue("Accessibility click failed: ${node.contentDescription ?: node.text}",
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
    }

    private fun assertState(label: String, expected: String) {
        val state = generateSequence(awaitNode(label)) { it.parent }
            .mapNotNull { it.stateDescription?.toString() }.firstOrNull()
        assertEquals(expected, state)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
