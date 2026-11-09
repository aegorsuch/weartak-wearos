package com.aitech.weartak_civ.presentation

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.audio.AudioDetectionChunkRef
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WarningReviewEmulatorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun syntheticDetectionAndCrmUseProductionWidgetsWithoutOperationalServices() {
        org.junit.Assume.assumeTrue("Run with -PweartakWarningUiDemo=true",
            instrumentation is WarningUiTestRunner)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        var dart by mutableStateOf<AudioDetectionEvent?>(null)
        var falcon by mutableStateOf<AudioDetectionEvent?>(null)
        var crm by mutableStateOf<CrmHomeWarning?>(null)
        val history = mutableMapOf<String, AudioDetectionEvent>()
        val responses = mutableListOf<AudioDetectionFeedback>()
        fun event(detector: String) = AudioDetectionEvent(
            detectorKey = detector, timestamp = System.currentTimeMillis(), confidence = .91f,
            loc = null, uid = "synthetic-$detector", alertLevel = AlertChipState.Warning,
            chunkRef = AudioDetectionChunkRef("ui-only", 1, 0, 44_100, 1),
            triggerPcmPath = null, audioSource = "synthetic-ui"
        ).also { history[it.uid] = it }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    WearTAKCIVTheme {
                        HomeWarningReviewHost(dart, falcon,
                            findEvent = { _, uid -> history[uid] },
                            emitFeedback = { displayed, answer ->
                                responses += answer
                                if (displayed.detectorKey == "gunshot") dart = null else falcon = null
                                true
                            },
                            crmWarning = crm,
                            onCrmDetails = {}
                        ) { warnings, review ->
                            HomeDashboardColumn {
                                Row(Modifier.fillMaxWidth().weight(1f)) {
                                    HomeTopMetricCard("Heart Rate", "105bpm", TopDisplayMetric.HEART_RATE,
                                        R.drawable.heart_rate, AlertChipState.None, null, {}, warnings, review)
                                }
                                repeat(2) { Spacer(Modifier.weight(1f)) }
                                androidx.wear.compose.material.Text("Synthetic UI fixture")
                                Spacer(Modifier.height(48.dp))
                            }
                        }
                    }
                }
            }
            try {
                scenario.onActivity { dart = event("gunshot") }
                waitForTag("audio-detection-warning")
                assertNull(findTag("audio-detection-dialog"))
                capture("dart-indicator")
                click("audio-detection-warning")
                waitForTag("audio-detection-yes")
                capture("dart-review")
                click("audio-detection-yes")
                waitForTag("audio-detection-dialog", exists = false)
                assertEquals(AudioDetectionUserLabel.YES, responses.single().label)
                assertEquals("synthetic-gunshot", responses.single().eventUid)

                scenario.onActivity { falcon = event("uas") }
                waitForTag("audio-detection-warning")
                capture("falcon-indicator")
                click("audio-detection-warning")
                waitForTag("audio-detection-no")
                capture("falcon-review")
                click("audio-detection-no")
                waitForTag("audio-detection-dialog", exists = false)
                assertEquals(AudioDetectionUserLabel.NO, responses.last().label)
                assertEquals("synthetic-uas", responses.last().eventUid)

                listOf(CrmHomeWarning(50, CrmDisplayState.MODERATE), CrmHomeWarning(25, CrmDisplayState.POOR)).forEach { warning ->
                    scenario.onActivity { crm = warning }
                    waitForTag("crm-warning-indicator")
                    assertNull(findTag("crm-warning-dialog"))
                    val prefix = if (warning.status == CrmDisplayState.POOR) "crm-poor" else "crm-moderate"
                    capture("$prefix-indicator")
                    click("crm-warning-indicator")
                    waitForTag("crm-warning-details")
                    assertNull(findTag("audio-detection-yes"))
                    capture("$prefix-review")
                    click("settings_back")
                    waitForTag("crm-warning-dialog", exists = false)
                    waitForTag("crm-warning-indicator")
                    capture("$prefix-return")
                }
                assertEquals(2, responses.size)
            } finally {
                scenario.onActivity { dart = null; falcon = null; crm = null }
            }
        }
    }

    // Platform accessibility actions avoid Espresso's removed InputManager API on Wear API 37.
    private fun findTag(tag: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.viewIdResourceName == tag) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun waitForTag(tag: String, exists: Boolean = true): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            instrumentation.waitForIdleSync()
            val node = findTag(tag)
            if ((node != null) == exists) return node
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Expected $tag exists=$exists")
    }

    private fun click(tag: String) {
        val node = checkNotNull(waitForTag(tag))
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot activate $tag" }
    }

    private fun capture(name: String) {
        // Accessibility nodes can precede the dialog window's first settled frame.
        SystemClock.sleep(500)
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "warning-ui-demo")
        check(directory.exists() || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
