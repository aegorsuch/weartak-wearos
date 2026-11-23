package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PendingDittoChatTest {
    @Test fun startupAndRegistrationRaceKeepChatUntilSubmission() = runTest {
        var ready = false
        val attempts = mutableListOf<PendingDittoChat.Message>()
        val queue = PendingDittoChat(backgroundScope, {
            attempts += it
            if (ready && attempts.size > 2) Submission.SUBMITTED else Submission.NOT_ADMITTED
        })
        queue.enqueue("original", "sender", "callsign")
        runCurrent()
        assertEquals(1, queue.pendingForTest().size)
        ready = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, queue.pendingForTest().size)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(queue.pendingForTest().isEmpty())
        assertEquals(3, attempts.size)
        assertEquals(1, attempts.toSet().size)
    }

    @Test fun uncertainDisconnectIsNeverReplayedOrTreatedAsSubmitted() = runTest {
        val attempts = mutableListOf<PendingDittoChat.Message>()
        val queue = PendingDittoChat(backgroundScope, {
            attempts += it
            if (it.xml == "uncertain") Submission.UNCERTAIN else Submission.SUBMITTED
        })
        queue.enqueue("uncertain", "sender", null)
        runCurrent()
        assertEquals(1, queue.pendingForTest().size)
        assertTrue(queue.pendingForTest().single().second)
        queue.enqueue("next", "sender", null)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("uncertain", "next"), attempts.map { it.xml })
        assertEquals("uncertain", queue.pendingForTest().single().first.xml)
    }

    @Test fun cancelledBeforeEffectRetriesWithFreshTransportId() = runTest {
        val attempts = mutableListOf<PendingDittoChat.Message>()
        val queue = PendingDittoChat(backgroundScope, {
            attempts += it
            if (attempts.size == 1) Submission.CANCELLED_BEFORE_EFFECT else Submission.SUBMITTED
        })
        queue.enqueue("payload", "sender", "callsign")
        runCurrent()
        assertFalse(queue.pendingForTest().single().second)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(queue.pendingForTest().isEmpty())
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].xml, attempts[1].xml)
        assertNotEquals(attempts[0].id, attempts[1].id)
    }
}
