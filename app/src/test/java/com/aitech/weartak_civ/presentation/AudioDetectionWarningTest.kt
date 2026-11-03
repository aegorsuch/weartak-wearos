package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.data.audio.AudioDetectionStore
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.BleLinkWarning
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioDetectionWarningTest {
    @get:Rule val compose = createComposeRule()
    private val repo = mockk<DataRepository> {
        every { latestLocation } returns MutableStateFlow(null)
        coEvery { snapshotLocation(any()) } returns null
    }
    private val dart = AudioDetectionStore("gunshot", repo)
    private val falcon = AudioDetectionStore("uas", repo)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val answers = mutableListOf<Pair<String, AudioDetectionFeedback>>()
    private val physio = mutableStateOf(Utils.AlertChipState.None)
    private val available = mutableStateOf(true)
    private val crm = mutableStateOf<CrmHomeWarning?>(null)
    private val ditto = mutableStateOf(BleLinkWarning.NONE)
    private var dittoRetries = 0
    private var metricTaps = 0
    private var crmDetails = 0
    private lateinit var rootView: android.view.View

    @Before fun init() {
        WearTakApplication.AppCtx.init(ApplicationProvider.getApplicationContext())
        scope.launch { dart.feedback.collect { answers += "gunshot" to it } }
        scope.launch { falcon.feedback.collect { answers += "uas" to it } }
    }

    @After fun cleanup() { scope.cancel() }

    private fun detect(store: AudioDetectionStore, uid: String, confidence: Float = .91f): AudioDetectionEvent =
        runBlocking {
            store.createEvent(uid, confidence,
                AudioCaptureRepository.AudioPcmChunk("synthetic", 1, 100, 100, 44_100, 1, shortArrayOf()),
                null, "synthetic")
        }

    private fun show(fontScale: Float = 1f): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val view = LocalView.current
            SideEffect { rootView = view }
            val gunshot by dart.pendingEvent.collectAsState()
            val uas by falcon.pendingEvent.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WearTAKCIVTheme {
                    HomeWarningReviewHost(gunshot, uas,
                        findEvent = { detector, uid ->
                            if (detector == "gunshot") dart.getEventFromUid(uid) else falcon.getEventFromUid(uid)
                        },
                        emitFeedback = { event, feedback ->
                            available.value && if (event.detectorKey == "gunshot")
                                dart.tryEmitFeedback(feedback) else falcon.tryEmitFeedback(feedback)
                        },
                        crmWarning = crm.value,
                        onCrmDetails = { crmDetails++ },
                        physioWarning = if (physio.value == Utils.AlertChipState.None) null else
                            HomeStatusWarning("physio", TopMetricAlertSource.PHYSIO,
                                "High Exertion", physio.value, { metricTaps++ }),
                        dittoWarning = ditto.value,
                        onDittoRetry = { dittoRetries++ }
                    ) { warning, review ->
                        Box(Modifier.requiredSize(227.dp).testTag("round-home")) {
                            HomeDashboardColumn {
                                Row(Modifier.fillMaxWidth().weight(1f)) {
                                    HomeTopMetricCard("Heart Rate", "105bpm", TopDisplayMetric.HEART_RATE,
                                        R.drawable.heart_rate, physio.value,
                                        if (physio.value == Utils.AlertChipState.None) null else "High Exertion",
                                        { metricTaps++ }, warning, review)
                                }
                                repeat(2) { Spacer(Modifier.weight(1f)) }
                                Spacer(Modifier.height(48.dp))
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
        return restoration
    }

    private fun back() {
        compose.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun assertReview(title: String, score: Int) {
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("audio-detection-dialog"))).assertIsDisplayed()
        compose.onNodeWithText("Score: $score%").assertIsDisplayed()
    }

    @Test fun warningRequiresTapNeverTimesOutAndYesUsesDisplayedEvent() {
        show()
        compose.onNodeWithTag("audio-detection-warning").assertDoesNotExist()
        compose.runOnIdle { detect(dart, "dart") }
        compose.onNodeWithTag("audio-detection-warning").assertIsDisplayed()
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        assertEquals("dart", dart.pendingEvent.value?.uid)
        compose.onNodeWithTag("audio-detection-warning").performClick()
        assertReview("Gunshot detected?", 91)
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithTag("audio-detection-dialog").assertIsDisplayed()
        compose.onNodeWithTag("audio-detection-yes").performClick()
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        compose.onNodeWithTag("audio-detection-warning").assertDoesNotExist()
        assertEquals("gunshot", answers.single().first)
        assertEquals("dart", answers.single().second.eventUid)
        assertEquals(AudioDetectionUserLabel.YES, answers.single().second.label)
        assertTrue(answers.single().second.respondedAtMs > 0)
        assertTrue(answers.single().second.responseLatencyMs >= 0)
        assertEquals(0, metricTaps)
    }

    @Test fun interleavedEventsUseLatestPerDetectorAndRequireAnotherTapAfterEachAnswer() {
        detect(dart, "dart-old")
        detect(falcon, "falcon")
        Thread.sleep(2)
        detect(dart, "dart-latest", .97f)
        show()
        compose.onNodeWithText("Review detections (2)").assertIsDisplayed()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        compose.onNodeWithTag("home-warning-gunshot").performClick()
        assertReview("Gunshot detected?", 97)
        compose.onNodeWithTag("audio-detection-no").performClick()
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        assertReview("UAS detected?", 91)
        compose.onNodeWithTag("audio-detection-yes").performClick()
        assertEquals(listOf("dart-latest", "falcon"), answers.map { it.second.eventUid })
        assertEquals(listOf(AudioDetectionUserLabel.NO, AudioDetectionUserLabel.YES),
            answers.map { it.second.label })
        assertEquals(2, dart.recentEventsFlow.value.size)
        compose.onNodeWithTag("audio-detection-warning").assertDoesNotExist()
    }

    @Test fun replacementDuringOpenReviewCannotChangeAnswerIdentityEvenAcrossRecreation() {
        detect(dart, "original", .81f)
        val restoration = show()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        compose.runOnIdle { detect(dart, "replacement", .99f) }
        restoration.emulateSavedInstanceStateRestore()
        assertReview("Gunshot detected?", 81)
        compose.onNodeWithTag("audio-detection-no").performClick()
        assertEquals("original", answers.single().second.eventUid)
        assertEquals("replacement", dart.pendingEvent.value?.uid)
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        assertReview("Gunshot detected?", 99)
    }

    @Test fun backLeavesLatestPendingWithoutAnyAnswerAndDoesNotReopenAutomatically() {
        detect(falcon, "uas", .86f)
        val restoration = show()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        back()
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        assertTrue(answers.isEmpty())
        assertEquals("uas", falcon.pendingEvent.value?.uid)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        assertReview("UAS detected?", 86)
    }

    @Test fun failedFeedbackStaysVisibleForRetryWithoutLosingPendingEvent() {
        detect(dart, "dart")
        show()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        compose.runOnIdle { available.value = false }
        compose.onNodeWithTag("audio-detection-yes").performClick()
        compose.onNodeWithText("Response not recorded. Try again.", substring = true).assertIsDisplayed()
        assertTrue(answers.isEmpty())
        assertNotNull(dart.pendingEvent.value)
        compose.runOnIdle { available.value = true }
        compose.onNodeWithTag("audio-detection-yes").performClick()
        assertEquals(1, answers.size)
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
    }

    @Test fun physioAndEmergencyKeepOwnClickWhileDetectionRemainsReachable() {
        detect(dart, "dart")
        show()
        listOf(Utils.AlertChipState.Warning, Utils.AlertChipState.Emergency).forEach { level ->
            compose.runOnIdle { physio.value = level }
            compose.onNodeWithText("High Exertion").assertIsDisplayed().performClick()
            compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
            compose.onNodeWithTag("home-secondary-warnings").assertIsDisplayed().performClick()
            compose.onNodeWithTag("home-warning-gunshot").performClick()
            compose.onNodeWithTag("audio-detection-dialog").assertIsDisplayed()
            back()
        }
        assertEquals(2, metricTaps)
        assertTrue(answers.isEmpty())
    }

    @Test fun replacementThenBackLeavesOnlyLatestReviewWithoutLabellingEitherEvent() {
        detect(dart, "old", .81f)
        show()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        compose.runOnIdle { detect(dart, "latest", .99f) }
        back()
        assertTrue(answers.isEmpty())
        assertEquals("latest", dart.pendingEvent.value?.uid)
        assertEquals(2, dart.recentEventsFlow.value.size)
        compose.onNodeWithTag("audio-detection-dialog").assertDoesNotExist()
        compose.onNodeWithTag("audio-detection-warning").performClick()
        assertReview("Gunshot detected?", 99)
    }

    @Test fun warningAndYesNoRemainReadableAtLargeFontScale() {
        detect(dart, "dart")
        detect(falcon, "falcon")
        show(1.3f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Review detections (2)")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("audio-detection-warning").performClick()
        compose.onNodeWithTag("home-warning-gunshot").performClick()
        compose.onNodeWithTag("audio-detection-yes").assertIsDisplayed()
        compose.onNodeWithTag("audio-detection-no").assertIsDisplayed()
        listOf("Yes", "No").forEach { label ->
            val labelLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayouts) }
            assertFalse(labelLayouts.single().hasVisualOverflow)
        }
    }

    @Test fun crmModerateUsesSharedShellWithDetailsAndBackNotHealthConfirmation() {
        crm.value = CrmHomeWarning(50, CrmDisplayState.MODERATE)
        show()
        compose.onNodeWithTag("crm-warning-dialog").assertDoesNotExist()
        compose.onNodeWithTag("crm-warning-indicator").performClick()
        compose.onNodeWithText("CRM status").assertIsDisplayed()
        compose.onNodeWithText("50 / 100\nMODERATE").assertIsDisplayed()
        compose.onNodeWithTag("audio-detection-yes").assertDoesNotExist()
        compose.onNodeWithTag("audio-detection-no").assertDoesNotExist()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("crm-warning-dialog").assertDoesNotExist()
        compose.onNodeWithTag("crm-warning-indicator").performClick()
        compose.onNodeWithTag("crm-warning-details").performClick()
        assertEquals(1, crmDetails)
        compose.onNodeWithTag("crm-warning-dialog").assertDoesNotExist()
        assertNotNull(crm.value)
        assertTrue(answers.isEmpty())
    }

    @Test fun poorCrmOutranksYellowButActualEmergencyStaysFirstAndAllDomainsRemainReachable() {
        detect(dart, "dart")
        crm.value = CrmHomeWarning(25, CrmDisplayState.POOR)
        physio.value = Utils.AlertChipState.Warning
        show()
        compose.onNodeWithText("CRM POOR: 25").assertIsDisplayed()
        compose.onNodeWithTag("crm-warning-indicator").performClick()
        compose.runOnIdle { physio.value = Utils.AlertChipState.Emergency }
        compose.onNodeWithText("25 / 100\nPOOR").assertIsDisplayed()
        compose.onNodeWithTag("crm-warning-details").assertIsDisplayed()
        compose.onNodeWithTag("audio-detection-yes").assertDoesNotExist()
        back()
        compose.onNodeWithText("High Exertion").assertIsDisplayed().performClick()
        assertEquals(1, metricTaps)
        compose.onNodeWithTag("home-secondary-warnings").performClick()
        compose.onNodeWithTag("home-warning-gunshot").performClick()
        assertReview("Gunshot detected?", 91)
        back()
        compose.onNodeWithTag("home-secondary-warnings").performClick()
        compose.onNodeWithTag("home-warning-crm").performClick()
        compose.onNodeWithText("25 / 100\nPOOR").assertIsDisplayed()
        compose.runOnIdle { crm.value = null }
        compose.onNodeWithTag("crm-warning-dialog").assertDoesNotExist()
        assertTrue(answers.isEmpty())
    }

    @Test fun nativeRenderKeepsWarningReadableAndFallbackIconInsideRoundDisplay() {
        detect(dart, "dart")
        detect(falcon, "falcon")
        show()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Review detections (2)")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        assertTrue(layouts.single().layoutInput.style.fontSize.value >= 10f)
        saveRender("detection-warning")
        compose.onNodeWithTag("audio-detection-warning").performClick()
        compose.onNodeWithTag("home-warning-gunshot").performClick()
        saveRender("detection-review", dialog = true)
        back()
        compose.runOnIdle { physio.value = Utils.AlertChipState.Emergency }
        val warning = compose.onNodeWithTag("home-secondary-warnings")
        val target = warning.fetchSemanticsNode().touchBoundsInRoot
        val root = compose.onNodeWithTag("round-home").fetchSemanticsNode().boundsInRoot
        val density = root.width / 227f
        assertTrue(target.width / density >= 48f)
        assertTrue(target.height / density >= 48f)
        val icon = compose.onNodeWithContentDescription("Review warnings", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val radius = root.width / 2f
        listOf(icon.topLeft, icon.topRight, icon.bottomLeft, icon.bottomRight).forEach {
            val dx = it.x - root.center.x
            val dy = it.y - root.center.y
            assertTrue("Fallback icon outside round screen: $icon", dx * dx + dy * dy <= radius * radius)
        }
        saveRender("detection-physio-fallback")
    }

    @Test fun dittoReviewStaysBoundAcrossPriorityChangeAndBackNeverRetries() {
        ditto.value = BleLinkWarning.DISCONNECTED
        val restoration = show()
        compose.onNodeWithTag("ditto_ble_recovery_dialog").assertDoesNotExist()
        compose.onNodeWithTag("ditto_ble_warning_indicator").performClick()
        compose.runOnIdle {
            physio.value = Utils.AlertChipState.Emergency
            crm.value = CrmHomeWarning(25, CrmDisplayState.POOR)
            detect(dart, "dart")
            detect(falcon, "falcon")
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ditto_ble_retry_now").assertIsDisplayed()
        compose.onNodeWithTag("audio-detection-yes").assertDoesNotExist()
        compose.onNodeWithTag("crm-warning-details").assertDoesNotExist()
        back()
        assertEquals(0, dittoRetries)
        assertEquals(BleLinkWarning.DISCONNECTED, ditto.value)
        compose.onNodeWithText("High Exertion").performClick()
        assertEquals(1, metricTaps)
        compose.onNodeWithTag("home-secondary-warnings").performClick()
        compose.onNodeWithTag("home-warning-ditto").performScrollTo().performClick()
        compose.onNodeWithTag("ditto_ble_retry_now").performClick()
        assertEquals(1, dittoRetries)
        compose.onNodeWithTag("ditto_ble_recovery_dialog").assertDoesNotExist()
        assertTrue(answers.isEmpty())
        assertEquals(0, crmDetails)
    }

    @Test fun clearedDittoReviewCannotReopenOnReturnAndDetectionKeepsItsOwnIdentity() {
        ditto.value = BleLinkWarning.PAUSED
        show()
        compose.onNodeWithTag("ditto_ble_warning_indicator").performClick()
        compose.runOnIdle { ditto.value = BleLinkWarning.NONE }
        compose.onNodeWithTag("ditto_ble_recovery_dialog").assertDoesNotExist()
        compose.runOnIdle { ditto.value = BleLinkWarning.DISCONNECTED }
        compose.onNodeWithTag("ditto_ble_recovery_dialog").assertDoesNotExist()
        compose.runOnIdle { detect(falcon, "displayed") }
        compose.onNodeWithTag("audio-detection-warning").performClick()
        assertReview("UAS detected?", 91)
        compose.runOnIdle {
            detect(falcon, "latest", .99f)
            physio.value = Utils.AlertChipState.Emergency
            crm.value = CrmHomeWarning(25, CrmDisplayState.POOR)
        }
        compose.onNodeWithTag("ditto_ble_retry_now").assertDoesNotExist()
        compose.onNodeWithTag("audio-detection-no").performClick()
        assertEquals("displayed", answers.single().second.eventUid)
        assertEquals("latest", falcon.pendingEvent.value?.uid)
        assertEquals(0, dittoRetries)
        compose.onNodeWithTag("home-secondary-warnings").performClick()
        compose.onNodeWithTag("home-warning-crm").performScrollTo().performClick()
        compose.runOnIdle { physio.value = Utils.AlertChipState.None; ditto.value = BleLinkWarning.NONE }
        compose.onNodeWithTag("crm-warning-details").performClick()
        assertEquals(1, crmDetails)
        assertEquals(1, answers.size)
    }

    private fun saveRender(name: String, dialog: Boolean = false) {
        val file = File("build/outputs/detection-ui/$name.png")
        file.parentFile.mkdirs()
        compose.runOnIdle {
            val view = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView else rootView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
