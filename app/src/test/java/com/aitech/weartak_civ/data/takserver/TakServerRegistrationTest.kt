package com.aitech.weartak_civ.data.takserver
import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
class TakServerRegistrationTest {
    private val registration = TakServerRegistration()
    private val serverId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val authFailure = TakConnectionFailure(
        TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401,
    )

    @Test fun config401ReplacesSpinnerWithFailureAtomically() = runTest {
        val observed = mutableListOf<TakServerRegistration.State>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            registration.state.collect { observed += it }
        }
        val runtime = Any()
        registration.saveAndConnect(serverId, { TakServerRegistration.SaveResult.SAVED }) {
            registration.bind(serverId, runtime)
            registration.publish(serverId, runtime, TakServerRegistration.Status.FAILED, authFailure)
        }
        assertTrue(observed.any { it.isLoading })
        assertFalse(observed.last().isLoading)
        assertEquals(TakServerRegistration.Status.FAILED, observed.last().status)
        assertEquals(authFailure, observed.last().failure)
    }

    @Test fun registrationWaitsForPersistenceBeforeStartingAttempt() = runTest {
        val saved = CompletableDeferred<Unit>()
        var connected = false
        val job = launch {
            registration.saveAndConnect(serverId, {
                saved.await()
                TakServerRegistration.SaveResult.SAVED
            }) { connected = true }
        }
        testScheduler.runCurrent()
        assertEquals(TakServerRegistration.Status.SAVING, registration.state.value.status)
        assertFalse(connected)
        saved.complete(Unit)
        job.join()
        assertTrue(connected)
    }

    @Test fun editedRuntimeReplacementStillPublishesFailureToSameObserver() {
        val observedFlow = registration.state
        val oldRuntime = Any()
        val newRuntime = Any()
        registration.prepare(serverId)
        registration.bind(serverId, oldRuntime)
        registration.replaceRuntime(serverId, oldRuntime, newRuntime)
        registration.publish(serverId, oldRuntime, TakServerRegistration.Status.CONNECTED)
        assertTrue(observedFlow.value.isLoading)
        registration.publish(serverId, newRuntime, TakServerRegistration.Status.FAILED, authFailure)
        assertSame(observedFlow, registration.state)
        assertFalse(observedFlow.value.isLoading)
        assertEquals(authFailure, observedFlow.value.failure)
    }

    @Test fun preparationDoesNotAcceptOutcomeOfOldConnectionBeforeSave() {
        val runtime = Any()
        registration.prepare(serverId)
        registration.bind(serverId, runtime)
        registration.prepare(serverId)
        registration.publish(serverId, runtime, TakServerRegistration.Status.CONNECTED)
        assertEquals(TakServerRegistration.Status.SAVING, registration.state.value.status)
    }

    @Test fun retryClearsFailureAndPublishesNewFailureEvenWithUnchangedConfiguration() = runTest {
        val runtime = Any()
        repeat(2) {
            registration.saveAndConnect(serverId, { TakServerRegistration.SaveResult.SAVED }) {
                assertNull(registration.state.value.failure)
                registration.bind(serverId, runtime)
                assertTrue(registration.state.value.isLoading)
                registration.publish(serverId, runtime, TakServerRegistration.Status.FAILED, authFailure)
            }
            assertFalse(registration.state.value.isLoading)
            assertEquals(authFailure, registration.state.value.failure)
        }
    }

    @Test fun collectorConsumedEditDoesNotSkipRequestedAttempt() = runTest {
        var attempts = 0
        val runtime = Any()
        registration.saveAndConnect(serverId, {
            // The persisted edit has already been reconciled before the UI continuation.
            attempts++
            TakServerRegistration.SaveResult.SAVED
        }) {
            attempts++
            registration.bind(serverId, runtime)
            registration.publish(serverId, runtime, TakServerRegistration.Status.FAILED, authFailure)
        }
        assertEquals(2, attempts)
        assertFalse(registration.state.value.isLoading)
        assertEquals(authFailure, registration.state.value.failure)
    }

    @Test fun terminalFailureIsNotOverwrittenByBackgroundReconnect() {
        val runtime = Any()
        registration.prepare(serverId)
        registration.bind(serverId, runtime)
        registration.publish(serverId, runtime, TakServerRegistration.Status.FAILED, authFailure)
        registration.bind(serverId, Any())
        registration.publish(serverId, runtime, TakServerRegistration.Status.CONNECTING)
        assertFalse(registration.state.value.isLoading)
        assertEquals(authFailure, registration.state.value.failure)
    }

    @Test fun identicalImmediateRetryFailureStillProducesANewUiSnapshot() {
        val runtime = Any()
        registration.prepare(serverId)
        registration.bind(serverId, runtime)
        registration.publish(serverId, runtime, TakServerRegistration.Status.FAILED, authFailure)
        val previousFailure = registration.state.value
        registration.prepare(serverId)
        registration.bind(serverId, runtime)
        registration.publish(serverId, runtime, TakServerRegistration.Status.FAILED, authFailure)
        assertNotEquals(previousFailure, registration.state.value)
        assertFalse(registration.state.value.isLoading)
        assertEquals(authFailure, registration.state.value.failure)
    }

    @Test fun otherServersCannotCompleteRegistration() {
        val runtime = Any()
        registration.prepare(serverId)
        registration.bind(serverId, runtime)
        registration.publish("other-server", runtime, TakServerRegistration.Status.CONNECTED)
        assertTrue(registration.state.value.isLoading)
    }

    @Test fun rejectedSavesTerminateWithoutConnecting() = runTest {
        listOf(TakServerRegistration.SaveResult.DUPLICATE, TakServerRegistration.SaveResult.INVALID).forEach { result ->
            registration.saveAndConnect(serverId, { result }) { fail("Must not connect a rejected save") }
            assertFalse(registration.state.value.isLoading)
            assertEquals(
                if (result == TakServerRegistration.SaveResult.DUPLICATE)
                    TakServerRegistration.Status.DUPLICATE else TakServerRegistration.Status.FAILED,
                registration.state.value.status,
            )
        }
    }

    @Test fun saveOrConnectionSetupExceptionTerminatesSpinner() = runTest {
        registration.saveAndConnect(serverId, { error("Encrypted store unavailable") }) {
            fail("Must not connect after a failed save")
        }
        assertFalse(registration.state.value.isLoading)
        assertNotNull(registration.state.value.failure)
        registration.saveAndConnect(serverId, { TakServerRegistration.SaveResult.SAVED }) {
            error("Saved server unavailable")
        }
        assertFalse(registration.state.value.isLoading)
        assertNotNull(registration.state.value.failure)
    }

    @Test fun cancelledSaveTerminatesSpinner() = runTest {
        val neverSaved = CompletableDeferred<Unit>()
        val job = launch {
            registration.saveAndConnect(serverId, {
                neverSaved.await()
                TakServerRegistration.SaveResult.SAVED
            }) { fail("Cancelled save must not connect") }
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertFalse(registration.state.value.isLoading)
    }

    @Test fun cancelledLaunchTerminatesPreparedSpinnerWithoutAffectingNewRequest() {
        val ticket = registration.prepare(serverId)
        registration.failPending(ticket)
        assertFalse(registration.state.value.isLoading)
        registration.prepare("new-server")
        registration.failPending(ticket)
        assertTrue(registration.state.value.isLoading)
        assertEquals("new-server", registration.state.value.serverId)
    }

    @Test fun supersededSaveCannotCompleteNewRequest() = runTest {
        val saved = CompletableDeferred<Unit>()
        val old = launch {
            registration.saveAndConnect(serverId, {
                saved.await()
                TakServerRegistration.SaveResult.DUPLICATE
            }) { fail("Old save must not connect") }
        }
        testScheduler.runCurrent()
        registration.prepare("new-server")
        saved.complete(Unit)
        old.join()
        assertEquals("new-server", registration.state.value.serverId)
        assertTrue(registration.state.value.isLoading)
    }

    @Test fun registrationStepsPreserveInnermostStageAndCancellationWithoutSensitiveValues() = runTest {
        val logs = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { logs.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val logger = Logger.getLogger("WearTAK-Registration")
        logger.addHandler(handler)
        try {
            TakServerRegistration.Step.entries.forEach { step ->
                registration.saveAndConnect(serverId, { TakServerRegistration.SaveResult.SAVED }) {
                    registration.atStep(step) { error("private-key password server.example") }
                }
                assertTrue(logs.last().message.contains("registrationStep=$step"))
                assertTrue(logs.last().message.contains("StepFailure -> IllegalStateException"))
                assertFalse(registration.state.value.isLoading)
            }
            registration.saveAndConnect(serverId, { TakServerRegistration.SaveResult.SAVED }) {
                registration.atStep(TakServerRegistration.Step.RUNTIME_RECONCILIATION) {
                    registration.atStep(TakServerRegistration.Step.ID_NORMALIZATION) {
                        throw TakEnrollmentException(TakConnectionFailure(
                            TakEnrollmentStage.CERTIFICATE, TakFailureCategory.CERTIFICATE))
                    }
                }
            }
            assertTrue(logs.last().message.contains("registrationStep=ID_NORMALIZATION"))
            assertFalse(logs.last().message.contains("registrationStep=RUNTIME_RECONCILIATION"))
            assertEquals(TakFailureCategory.CERTIFICATE, registration.state.value.failure?.category)
            val count = logs.size
            val cancellation = CancellationException("private-key")
            try {
                registration.atStep(TakServerRegistration.Step.PERSISTED_LIST_RELOAD) { throw cancellation }
                fail("Cancellation must propagate")
            } catch (error: CancellationException) {
                assertSame(cancellation, error)
            }
            assertEquals(count, logs.size)
            logs.forEach {
                assertNull(it.thrown)
                listOf("private-key", "password", "server.example", serverId).forEach { secret ->
                    assertFalse(it.message.contains(secret))
                }
            }
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test fun failureLogsPreserveSafePhaseCauseAndEnrollmentDetailsWithoutSensitiveValues() = runTest {
        val logs = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { logs.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val logger = Logger.getLogger("WearTAK-Registration")
        logger.addHandler(handler)
        try {
            registration.saveAndConnect(serverId, {
                throw IllegalStateException("fake-secret password server.example",
                    SSLHandshakeException("private key and hostname"))
            }) { fail("Must not connect after failed save") }
            assertTrue(logs.last().message.contains("phase=save"))
            assertTrue(logs.last().message.contains("IllegalStateException -> SSLHandshakeException"))
            val failure = TakConnectionFailure(TakEnrollmentStage.SIGN, TakFailureCategory.AUTHENTICATION, 401)
            registration.saveAndConnect(serverId, { TakServerRegistration.SaveResult.SAVED }) {
                throw IllegalStateException("fake-secret token", TakEnrollmentException(failure))
            }
            assertTrue(logs.last().message.contains("phase=connect"))
            assertTrue(logs.last().message.contains("stage=SIGN category=AUTHENTICATION httpCode=401"))
            assertEquals(failure, registration.state.value.failure)
            assertFalse(registration.state.value.isLoading)
            val deep = (1..20).fold<Int, Exception>(SSLHandshakeException("fake-secret")) { cause, _ ->
                IllegalStateException("fake-secret", cause)
            }
            registration.saveAndConnect(serverId, { throw deep }) { fail("Save failed") }
            assertEquals(8, logs.last().message.substringAfter("causes=").split(" -> ").size)
            logs.forEach {
                assertNull(it.thrown)
                listOf("fake-secret", "password", "private key", "server.example", serverId, "token").forEach { secret ->
                    assertFalse(it.message.contains(secret))
                }
            }
        } finally {
            logger.removeHandler(handler)
        }
    }
}
