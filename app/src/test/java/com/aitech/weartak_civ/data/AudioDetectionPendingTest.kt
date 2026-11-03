package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.data.audio.AudioDetectionStore
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import com.aitech.weartak_civ.service.audio.collectAudioDetectionFeedback
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AudioDetectionPendingTest {
    private val repo = mockk<DataRepository> {
        every { latestLocation } returns MutableStateFlow(null)
        coEvery { snapshotLocation(any()) } returns null
    }
    private val chunk = AudioCaptureRepository.AudioPcmChunk("synthetic", 1, 100, 100,
        44_100, 1, shortArrayOf())

    @Test fun burstKeepsOnlyLatestPerDetectorWithoutDroppingHistoryOrLabellingSupersededEvents() = runTest {
        val dart = AudioDetectionStore("gunshot", repo)
        val falcon = AudioDetectionStore("uas", repo)
        val feedback = mutableListOf<AudioDetectionFeedback>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { dart.feedback.collect { feedback += it } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { falcon.feedback.collect { feedback += it } }
        repeat(100) {
            dart.createEvent("dart-$it", .91f, chunk, null, "synthetic")
            falcon.createEvent("falcon-$it", .82f, chunk, null, "synthetic")
        }
        runCurrent()
        assertEquals(listOf("dart-99", "falcon-99"),
            listOfNotNull(dart.pendingEvent.value, falcon.pendingEvent.value).map { it.uid })
        assertEquals(100, dart.recentEventsFlow.value.size)
        assertEquals(100, falcon.recentEventsFlow.value.size)
        assertTrue(feedback.isEmpty())
        assertEquals(Utils.AlertChipState.Warning, dart.getEventFromUid("dart-0")!!.alertLevel)
    }

    @Test fun answeringFrozenSupersededEventLogsExactIdentityOnceAndPreservesReplacement() = runTest {
        val store = AudioDetectionStore("gunshot", repo)
        val logged = mutableListOf<Pair<String, AudioDetectionFeedback>>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            collectAudioDetectionFeedback("test", "GS", store.feedback, store::getEventFromUid) { event, answer ->
                logged += event.uid to answer
            }
        }
        val original = store.createEvent("old", .91f, chunk, null, "synthetic")
        val replacement = store.createEvent("new", .95f, chunk, null, "synthetic")
        val yes = AudioDetectionFeedback(original.uid, AudioDetectionUserLabel.YES, 1234, 321)
        assertTrue(store.tryEmitFeedback(yes))
        assertFalse(store.tryEmitFeedback(yes))
        assertEquals(replacement, store.pendingEvent.value)
        val no = AudioDetectionFeedback(replacement.uid, AudioDetectionUserLabel.NO, 2345, 654)
        assertTrue(store.tryEmitFeedback(no))
        runCurrent()
        assertEquals(listOf("old" to yes, "new" to no), logged)
        assertNull(store.pendingEvent.value)
        assertEquals(2, store.recentEventsFlow.value.size)
        // The incumbent services redundantly upsert after creation; this must not resurrect a review.
        store.upsertRecent(replacement)
        assertNull(store.pendingEvent.value)
        assertFalse(store.tryEmitFeedback(no))
    }

    @Test fun unavailableFeedbackLeavesReviewPendingAndDoesNotConsumeAnswer() = runTest {
        val store = AudioDetectionStore("uas", repo)
        val event = store.createEvent("uas", .8f, chunk, null, "synthetic")
        val answer = AudioDetectionFeedback(event.uid, AudioDetectionUserLabel.NO, 1234, 200)
        assertFalse(store.tryEmitFeedback(answer))
        assertEquals(event, store.pendingEvent.value)
        val logged = mutableListOf<AudioDetectionFeedback>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { store.feedback.collect { logged += it } }
        assertTrue(store.tryEmitFeedback(answer))
        runCurrent()
        assertEquals(listOf(answer), logged)
        assertNull(store.pendingEvent.value)
    }

    @Test fun removalAndClearDropSelectorsWithoutManufacturingFeedback() = runTest {
        val store = AudioDetectionStore("uas", repo)
        store.createEvent("first", .8f, chunk, null, "synthetic")
        store.createEvent("second", .8f, chunk, null, "synthetic")
        assertTrue(store.remove("first"))
        assertEquals("second", store.pendingEvent.value?.uid)
        assertTrue(store.remove("second"))
        assertNull(store.pendingEvent.value)
        store.createEvent("third", .8f, chunk, null, "synthetic")
        store.clearAll()
        assertNull(store.pendingEvent.value)
        assertTrue(store.recentEventsFlow.value.isEmpty())
    }
}
