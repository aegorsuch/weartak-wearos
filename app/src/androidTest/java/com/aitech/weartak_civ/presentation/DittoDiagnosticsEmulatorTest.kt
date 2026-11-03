package com.aitech.weartak_civ.presentation

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.debug.DittoDiagnosticsFixtureActivity
import com.aitech.weartak_civ.service.DittoRecoveryStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DittoDiagnosticsEmulatorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var previousRow = -1

    @Test fun productionSettingsRenderLiveChangesReadOnlyOnRoundWatch() {
        org.junit.Assume.assumeTrue("Run with -PweartakDittoUiDemo=true", BuildConfig.DITTO_UI_DEMO)
        assertTrue(instrumentation.targetContext.resources.configuration.isScreenRound)
        ActivityScenario.launch(DittoDiagnosticsFixtureActivity::class.java).use { scenario ->
            click { it.text?.toString() == "Review Ditto settings" }
            show("total", "3")
            show("ble", "2")
            capture("direct-ble")
            show("lan", "1")
            show("recovery", "Active")
            assertNull(findNode { it.viewIdResourceName == "ditto-diagnostics-p2p" })
            assertNull(findNode { it.viewIdResourceName == "ditto-diagnostics-websocket" })
            assertNull(findNode { it.text?.toString() == "Diagnostics" })
            capture("supported-transports")
            scenario.onActivity { activity ->
                activity.fixtureStatus = activity.fixtureStatus.copy(
                    transports = checkNotNull(activity.fixtureStatus.transports).copy(websocket = true))
            }
            show("websocket", "0")
            capture("configured-websocket")
            scenario.onActivity { it.fixtureStatus = DittoDiagnosticsFixtureActivity.sampleStatus() }
            DittoRecoveryStatus.entries.forEach { status ->
                scenario.onActivity { it.fixtureStatus = it.fixtureStatus.copy(peers = null, recovery = status) }
                show("recovery", when (status) {
                    DittoRecoveryStatus.ACTIVE -> "Active"
                    DittoRecoveryStatus.TIMED_OUT -> "Timed out"
                    DittoRecoveryStatus.USER_PAUSED -> "User paused"
                    DittoRecoveryStatus.INELIGIBLE -> "Ineligible"
                    DittoRecoveryStatus.STOPPED -> "Stopped"
                    DittoRecoveryStatus.UNAVAILABLE -> "Unavailable"
                })
            }
            capture("unavailable-recovery")
            show("ble", "Unavailable")
            capture("unavailable-peers")
            scenario.onActivity { it.fixtureFontScale = 1.3f }
            show("lan", "Unavailable")
            show("recovery", "Unavailable")
            capture("large-font-unavailable")
            scenario.onActivity {
                assertEquals(0, it.settingsMutations)
                it.fixtureStatus = DittoDiagnosticsFixtureActivity.sampleStatus()
            }
            click { it.viewIdResourceName == "settings_back" }
            awaitNode { it.text?.toString() == "Review Ditto settings" }
        }
    }

    private fun show(tag: String, value: String) {
        val id = "ditto-diagnostics-$tag"
        val index = listOf("total", "ble", "lan", "p2p", "websocket", "recovery").indexOf(tag)
        var direction = if (index < previousRow) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        repeat(15) {
            instrumentation.waitForIdleSync()
            val node = findNode { it.viewIdResourceName == id && it.isVisibleToUser }
            if (node != null && text(node).contains(value)) {
                assertFalse("diagnostic is read-only", node.isClickable)
                previousRow = index
                return
            }
            val visibleRows = mutableListOf<Int>()
            findNode { node ->
                val row = listOf("total", "ble", "lan", "p2p", "websocket", "recovery")
                    .indexOf(node.viewIdResourceName?.removePrefix("ditto-diagnostics-"))
                if (row >= 0 && node.isVisibleToUser) visibleRows += row
                false
            }
            if (visibleRows.isNotEmpty()) direction = if (index < visibleRows.min()) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (visibleRows.isEmpty()) direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val list = awaitNode { it.isScrollable }
            list.performAction(direction)
            SystemClock.sleep(700)
        }
        capture("failure")
        val tree = buildString {
            findNode { node ->
                appendLine("${node.viewIdResourceName}: ${node.text} visible=${node.isVisibleToUser}")
                false
            }
        }
        error("Expected $id containing $value\n$tree")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ditto-ui-demo")
        check(directory.exists() || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    // Platform accessibility avoids Espresso's removed InputManager API on Wear API 37.
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { find(it)?.let { match -> return match } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun text(node: AccessibilityNodeInfo): String = buildString {
        append(node.text ?: "")
        for (index in 0 until node.childCount) node.getChild(index)?.let { append(text(it)) }
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(50) {
            instrumentation.waitForIdleSync()
            findNode(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected accessibility node")
    }

    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var node = awaitNode(predicate)
        while (!node.isClickable) node = checkNotNull(node.parent) { "No clickable ancestor" }
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
