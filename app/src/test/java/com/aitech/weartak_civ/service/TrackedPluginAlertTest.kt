package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.aitech.weartak_civ.data.DataRepository.AlertState
import com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TrackedPluginAlertTest {
    private val preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
        "tracked-plugin-alert-test", Context.MODE_PRIVATE
    ).also { it.edit().clear().commit() }
    private var clock = 0L

    private fun tracker() = TrackedPluginAlert(preferences, { clock }, { clock += it })

    private suspend fun TrackedPluginAlert.testSubmit(
        uid: String, activationId: String, state: AlertState, positional: Boolean, xml: String,
        selected: suspend () -> Boolean, ready: () -> Boolean = { true },
        send: suspend (String, String) -> Submission,
    ): Boolean = submit(uid, activationId, state, positional, xml, selected, ready,
        verifiedSession = { "test-session" }) { payload, id, eligible, _ ->
        { if (eligible()) send(payload, id) else Submission.UNCERTAIN }
    }

    @Test fun retriesOnlyKnownRefusalsWithSamePayloadAndId() = runTest {
        val tracker = tracker()
        val calls = mutableListOf<Pair<String, String>>()
        val sent = tracker.testSubmit("alert", "first", AlertState.ALERT, true, "<event/>", { true }) { xml, id ->
            calls += xml to id
            if (calls.size < 3) Submission.NOT_ADMITTED else Submission.SUBMITTED
        }
        assertTrue(sent)
        assertEquals(3, calls.size)
        assertEquals(1, calls.toSet().size)
        assertEquals(500L, clock)
        assertTrue(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "different", { true }) { _, _ ->
            fail("Already submitted alert must not be replayed")
            Submission.UNCERTAIN
        })
    }

    @Test fun uncertainAdmissionIsNotReplayedAfterRestart() = runTest {
        assertFalse(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "payload", { true }) { _, _ ->
            Submission.UNCERTAIN
        })
        assertFalse(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "payload", { true }) { _, _ ->
            fail("Uncertain alert must not be replayed")
            Submission.SUBMITTED
        })
    }

    @Test fun retryAfterBoundedWaitKeepsCapturedPayload() = runTest {
        val tracker = tracker()
        assertFalse(tracker.testSubmit("alert", "first", AlertState.ALERT, true, "original", { true }) { _, _ ->
            Submission.NOT_ADMITTED
        })
        assertTrue(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "rebuilt", { true }) { xml, _ ->
            assertEquals("original", xml)
            Submission.SUBMITTED
        })
    }

    @Test fun waitsForColdRuntimeBeforeAdmission() = runTest {
        var sends = 0
        assertTrue(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "payload",
            selected = { true }, ready = { clock >= 500 }) { _, _ ->
            sends++
            Submission.SUBMITTED
        })
        assertEquals(500L, clock)
        assertEquals(1, sends)
    }

    @Test fun cancellationFencesEarlierActiveAlertEvenWhenNotAdmitted() = runTest {
        val tracker = tracker()
        assertFalse(tracker.testSubmit("alert", "first", AlertState.CANCEL, true, "cancel", { clock < 250 }) { _, _ ->
            Submission.NOT_ADMITTED
        })
        assertFalse(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "active", { true }) { _, _ ->
            fail("Cancelled alert must not be replayed")
            Submission.SUBMITTED
        })
        assertTrue(tracker().testSubmit("alert", "first", AlertState.CANCEL, true, "cancel", { true }) { _, _ ->
            Submission.SUBMITTED
        })
        assertTrue(tracker().testSubmit("alert", "second", AlertState.ALERT, true, "new active", { true }) { _, _ ->
            Submission.SUBMITTED
        })
    }

    @Test fun unavailableOrOptedOutDoesNotSubmit() = runTest {
        assertFalse(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "payload", { false }) { _, _ ->
            fail("Opted-out plugin must not receive alerts")
            Submission.SUBMITTED
        })
        var attempts = 0
        assertFalse(tracker().testSubmit("alert", "first", AlertState.ALERT, true, "payload", { true }) { _, _ ->
            attempts++
            Submission.NOT_ADMITTED
        })
        assertEquals(10_000L, clock)
        assertEquals(41, attempts)
    }

    @Test fun failedFenceCommitNeverSubmits() = runTest {
        val broken = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { broken.getBoolean(any(), any()) } returns false
        every { broken.getString(any(), null) } returns null
        every { broken.all } returns emptyMap()
        every { broken.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false
        val tracker = TrackedPluginAlert(broken)
        repeat(2) {
            assertFalse(tracker.testSubmit("alert", "first", AlertState.ALERT, true, "payload", { true }) { _, _ ->
                fail("A failed durable fence must prevent submission")
                Submission.SUBMITTED
            })
        }
    }

    @Test fun heldReadinessDoesNotBlockAnotherUidAndCancelFencesIt() = runTest {
        val tracker = TrackedPluginAlert(preferences, { testScheduler.currentTime }, { delay(it) })
        val first = async {
            tracker.testSubmit("alert", "first", AlertState.ALERT, true, "old", { true }, ready = { false }) { _, _ ->
                fail("Cancelled alert must not be sent")
                Submission.SUBMITTED
            }
        }
        runCurrent()
        assertTrue(tracker.testSubmit("other-alert", "second", AlertState.ALERT, true, "new", { true }) { _, _ ->
            Submission.SUBMITTED
        })
        assertTrue(tracker.cancel("alert", "first"))
        advanceTimeBy(251)
        runCurrent()
        assertFalse(first.await())
    }

    @Test fun newerActivationFencesPendingClearAcrossTrackerRecreation() = runTest {
        val tracker = tracker()
        assertTrue(tracker.activate("alert", "first"))
        val clear = TrackedPluginAlert.PendingClear("alert", "first", true, "old clear", "watch", "callsign")
        assertTrue(tracker.rememberClear(clear))
        assertEquals(listOf(clear), tracker().pendingClears())
        assertFalse(tracker.testSubmit("alert", "first", AlertState.CANCEL, true, "old clear", { false }) { _, _ ->
            fail("Unavailable clear must not be sent")
            Submission.SUBMITTED
        })
        assertTrue(tracker.activate("alert", "second"))
        assertTrue(tracker().pendingClears().isEmpty())
        assertTrue(tracker.testSubmit("alert", "second", AlertState.ALERT, true, "new", { true }) { _, _ ->
            Submission.SUBMITTED
        })
        assertFalse(tracker().testSubmit("alert", "first", AlertState.CANCEL, true, "old clear", { true }) { _, _ ->
            fail("Old clear must not clear the new activation")
            Submission.SUBMITTED
        })
    }

    @Test fun submittedClearRemovesDurableRetry() = runTest {
        val tracker = tracker()
        val clear = TrackedPluginAlert.PendingClear("alert", "first", true, "captured", "watch", "callsign")
        assertTrue(tracker.activate(clear.uid, clear.activationId))
        assertTrue(tracker.rememberClear(clear))
        assertTrue(tracker().testSubmit(clear.uid, clear.activationId, AlertState.CANCEL, true,
            clear.xml, { true }) { xml, _ ->
            assertEquals("captured", xml)
            Submission.SUBMITTED
        })
        assertTrue(tracker().pendingClears().isEmpty())
        assertTrue(tracker.rememberClear(clear))
        assertTrue(tracker().pendingClears().isEmpty())
    }

    @Test fun uncertainEarlierEffectFencesNewSameUidButNotOtherUids() = runTest {
        val tracker = tracker()
        assertTrue(tracker.activate("alert", "first"))
        assertFalse(tracker.testSubmit("alert", "first", AlertState.ALERT, true, "old", { true }) { _, _ ->
            Submission.UNCERTAIN
        })
        assertTrue(tracker.activate("alert", "second"))
        assertFalse(tracker().testSubmit("alert", "second", AlertState.ALERT, true, "new", { true }) { _, _ ->
            fail("New activation must not overtake an uncertain old effect")
            Submission.SUBMITTED
        })
        assertTrue(tracker.testSubmit("other", "first", AlertState.ALERT, true, "independent", { true }) { _, _ ->
            Submission.SUBMITTED
        })
    }

    @Test fun replacementAllowsClearWithoutReplayingUncertainAlert() = runTest {
        var session = "old-session"
        val tracker = tracker()
        assertTrue(tracker.activate("alert", "first"))
        assertFalse(tracker.submit("alert", "first", AlertState.ALERT, true, "active", { true },
            verifiedSession = { session }) { _, _, _, expected ->
            assertEquals("old-session", expected)
            val receipt: suspend () -> Submission = { Submission.UNCERTAIN }
            receipt
        })
        assertFalse(tracker.submit("alert", "first", AlertState.CANCEL, true, "clear", { true },
            verifiedSession = { session }) { _, _, _, _ -> error("Same session cannot clear") })
        session = "new-session"
        assertTrue(tracker().submit("alert", "first", AlertState.CANCEL, true, "clear", { true },
            verifiedSession = { session }) { xml, _, _, expected ->
            assertEquals("clear", xml)
            assertEquals("new-session", expected)
            val receipt: suspend () -> Submission = { Submission.SUBMITTED }
            receipt
        })
        assertFalse(tracker().submit("alert", "first", AlertState.ALERT, true, "active", { true },
            verifiedSession = { session }) { _, _, _, _ -> error("Uncertain alert must not replay") })
    }

    @Test fun replacementAllowsNewActivationAfterUncertainClearWithoutReplayingClear() = runTest {
        var session = "old-session"
        val tracker = tracker()
        assertTrue(tracker.activate("alert", "first"))
        assertTrue(tracker.rememberClear(TrackedPluginAlert.PendingClear(
            "alert", "first", true, "clear", "watch", "callsign")))
        assertFalse(tracker.submit("alert", "first", AlertState.CANCEL, true, "clear", { true },
            verifiedSession = { session }) { _, _, _, _ -> { Submission.UNCERTAIN } })
        assertTrue(tracker.activate("alert", "second"))
        assertFalse(tracker.submit("alert", "second", AlertState.ALERT, true, "new", { true },
            verifiedSession = { session }) { _, _, _, _ -> error("Same session cannot overtake clear") })
        session = "new-session"
        assertTrue(tracker().submit("alert", "second", AlertState.ALERT, true, "new", { true },
            verifiedSession = { session }) { xml, _, _, _ ->
            assertEquals("new", xml)
            val receipt: suspend () -> Submission = { Submission.SUBMITTED }
            receipt
        })
        assertFalse(tracker().submit("alert", "first", AlertState.CANCEL, true, "clear", { true },
            verifiedSession = { session }) { _, _, _, _ -> error("Old clear must not replay") })
    }

    @Test fun unverifiedOrLegacySessionsCannotRetireUncertainEffect() = runTest {
        val tracker = tracker()
        assertTrue(tracker.activate("alert", "first"))
        assertFalse(tracker.submit("alert", "first", AlertState.ALERT, true, "old", { true },
            verifiedSession = { "legacy-session" }) { _, _, _, _ -> { Submission.UNCERTAIN } })
        val ownerKey = preferences.all.keys.single { it.startsWith("owner-") }
        assertTrue(preferences.edit().remove(ownerKey).commit())
        assertFalse(tracker().submit("alert", "first", AlertState.CANCEL, true, "clear", { true },
            verifiedSession = { "new-session" }) { _, _, _, _ -> error("Unknown old runtime cannot be retired") })
        assertTrue(tracker.activate("other", "first"))
        assertFalse(tracker.submit("other", "first", AlertState.ALERT, true, "old", { true },
            verifiedSession = { "verified-old" }) { _, _, _, _ -> { Submission.UNCERTAIN } })
        assertFalse(tracker().submit("other", "first", AlertState.CANCEL, true, "clear", { true }) {
                _, _, _, _ -> error("Unverified new runtime cannot retire old effect")
        })
    }

    @Test fun missingVerifiedSessionNeverAdmitsOrCreatesStartedFence() = runTest {
        val tracker = tracker()
        val clear = TrackedPluginAlert.PendingClear("alert", "first", true, "captured", "watch", "callsign")
        assertTrue(tracker.activate(clear.uid, clear.activationId))
        assertTrue(tracker.rememberClear(clear))
        assertFalse(tracker.submit(clear.uid, clear.activationId, AlertState.CANCEL, true,
            clear.xml, { true }, verifiedSession = { null }) { _, _, _, _ ->
            error("Unverified session must not be offered")
        })
        assertFalse(preferences.all.values.contains("started"))
        assertTrue(preferences.all.keys.none { it.startsWith("owner-") || it.startsWith("inflight-") })
        assertEquals(listOf(clear), tracker().pendingClears())
    }

    @Test fun sessionChangeBetweenFenceAndOfferRejectsOldAdmissionAndKeepsPayload() = runTest {
        var session = "old-session"
        val attempts = mutableListOf<Pair<String, String>>()
        val tracker = tracker()
        assertTrue(tracker.submit("alert", "first", AlertState.ALERT, true, "captured", { true },
            verifiedSession = { session }) { xml, id, _, expected ->
            attempts += xml to id
            if (attempts.size == 1) {
                assertTrue(preferences.all.values.contains("old-session"))
                session = "new-session"
            }
            val receipt: suspend () -> Submission = {
                if (expected != session) Submission.NOT_ADMITTED else Submission.SUBMITTED
            }
            receipt
        })
        assertEquals(2, attempts.size)
        assertEquals(1, attempts.toSet().size)
    }

    @Test fun replacementFencesQueuedOldAlertBeforeSendingNewActivation() = runTest {
        val tracker = tracker()
        val offered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        assertTrue(tracker.activate("alert", "first"))
        val old = async {
            tracker.submit("alert", "first", AlertState.ALERT, true, "old", { true },
                verifiedSession = { "test-session" }) { _, _, eligible, _ ->
                offered.complete(Unit)
                val receipt: suspend () -> Submission = {
                    resume.await()
                    assertFalse(eligible())
                    Submission.CANCELLED_BEFORE_EFFECT
                }
                receipt
            }
        }
        offered.await()
        assertTrue(tracker.activate("alert", "second"))
        val newer = async {
            tracker.testSubmit("alert", "second", AlertState.ALERT, true, "new", { true }) { _, _ ->
                Submission.SUBMITTED
            }
        }
        runCurrent()
        assertFalse(newer.isCompleted)
        resume.complete(Unit)
        assertFalse(old.await())
        assertTrue(newer.await())
    }

    @Test fun cancellationFencesQueuedAdmissionAndBlocksClearOnUncertainEffect() = runTest {
        val tracker = tracker()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = async {
            tracker.submit("alert", "first", AlertState.ALERT, true, "active", { true },
                verifiedSession = { "test-session" }) { _, _, eligible, _ ->
                admitted.complete(Unit)
                val wait: suspend () -> Submission = {
                    release.await()
                    assertFalse(eligible())
                    Submission.UNCERTAIN
                }
                wait
            }
        }
        admitted.await()
        assertTrue(tracker.cancel("alert", "first"))
        release.complete(Unit)
        assertFalse(active.await())
        assertFalse(tracker.testSubmit("alert", "first", AlertState.CANCEL, true, "clear", { true }) { _, _ ->
            fail("Clear must not overtake an uncertain active submission")
            Submission.SUBMITTED
        })
    }

    @Test fun cancelledBeforeEffectAllowsClearWithoutSendingOldAlert() = runTest {
        val tracker = tracker()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = async {
            tracker.submit("alert", "first", AlertState.ALERT, true, "active", { true },
                verifiedSession = { "test-session" }) { _, _, eligible, _ ->
                admitted.complete(Unit)
                val wait: suspend () -> Submission = {
                    release.await()
                    assertFalse(eligible())
                    Submission.CANCELLED_BEFORE_EFFECT
                }
                wait
            }
        }
        admitted.await()
        assertTrue(tracker.cancel("alert", "first"))
        val clear = async {
            tracker.testSubmit("alert", "first", AlertState.CANCEL, true, "clear", { true }) { _, _ ->
                Submission.SUBMITTED
            }
        }
        release.complete(Unit)
        assertFalse(active.await())
        assertTrue(clear.await())
    }
}
