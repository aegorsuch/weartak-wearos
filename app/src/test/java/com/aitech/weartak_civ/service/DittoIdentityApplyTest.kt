package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import live.ditto.Ditto
import live.ditto.DittoError
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoIdentityApplyTest {
    private val repo = mockk<DataRepository>(relaxed = true)
    private val app = mockk<WearTakApplication>(relaxed = true)
    private val context = mockk<Context>()
    private val ownershipPreferences = mockk<SharedPreferences>()
    private val ownershipEditor = mockk<SharedPreferences.Editor>()
    private val old = mockk<Ditto>(relaxed = true)
    private val replacement = mockk<Ditto>(relaxed = true)
    private val enabled = MutableStateFlow(false)
    private val companion = MutableStateFlow(false)
    private val companionGrace = MutableStateFlow(false)
    private var initializeOk = true
    private var ownershipFenced = false
    private var fenceCommitOk = true

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.applicationContext } returns app
        every { context.packageName } returns "com.aitech.weartak_civ"
        every { context.getSharedPreferences(any(), any()) } returns ownershipPreferences
        every { ownershipPreferences.all } answers {
            if (ownershipFenced) mapOf("bundled-engine-fenced" to true) else emptyMap()
        }
        every { ownershipPreferences.edit() } returns ownershipEditor
        every { ownershipEditor.putBoolean("bundled-engine-fenced", true) } returns ownershipEditor
        every { ownershipEditor.commit() } answers {
            if (fenceCommitOk) ownershipFenced = true
            fenceCommitOk
        }
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        every { context.stopService(any()) } returns false
        every { app.audioRepo } returns mockk(relaxed = true)
        every { repo.getEnableDittoService } returns enabled
        every { repo.phoneManagedSettingsLocked } returns flowOf(false)
        every { repo.getNetworkPreferencesLocked } returns flowOf(false)
        coEvery { repo.storeEnableDittoService(any()) } answers { enabled.value = firstArg() }
        every { repo.isCompanionConnected } returns companion
        every { repo.companionDisconnectGraceActive } returns companionGrace
        every { repo.watchTransportSuppressed } returns combine(companion, companionGrace) {
            connected, grace -> connected || grace
        }
        every { repo.shouldSuppressWatchTransportNow() } answers { companion.value }
        set("dittoInstance", old)
        mockkObject(DittoService.Companion, recordPrivateCalls = true)
        every { DittoService.Companion["initializeDitto"](context, repo) } answers {
            assertNull(DittoService.getDittoInstance())
            verify(exactly = 1) { old.close() }
            if (initializeOk) set("dittoInstance", replacement)
            initializeOk
        }
    }

    @After fun cleanup() {
        set("dittoInstance", null)
        initializationAttempted().set(false)
        activeService().value = null
        shutdownFailure().value = false
        DittoService.isRunning.set(false)
        ReactiveServiceManager.isRunning.set(false)
        unmockkObject(DittoService.Companion)
        unmockkConstructor(ServiceConfiguration::class)
        Dispatchers.resetMain()
    }

    @Test fun coldPluginFenceRequiresNeverStartedEngineAndRejectsExistingFence() = runTest {
        set("dittoInstance", null)
        assertTrue(DittoService.fenceColdStartForPlugin(context))
        assertTrue(DittoService.isOwnershipFenced(context))
        // Simulate fresh process-local state while keeping the persisted marker.
        initializationAttempted().set(false)
        assertFalse(DittoService.fenceColdStartForPlugin(context))
        verify(exactly = 0) { context.stopService(any()); old.close() }
    }

    @Test fun pluginOnlyModeDoesNotInitializeBundledEngineWhenIdentityChanges() = runTest {
        val mode = com.aitech.weartak_civ.integration.plugin.PluginOnlyDittoHost
        mockkObject(mode)
        try {
            every { mode.enabled() } returns true
            assertFalse(DittoService.applySelectedIdentity(context, repo))
            verify(exactly = 0) { old.close(); context.stopService(any()) }
        } finally {
            unmockkObject(mode)
        }
    }

    @Test fun coldPluginFenceRejectsActiveOrPreviouslyAttemptedEngine() = runTest {
        assertFalse(DittoService.fenceColdStartForPlugin(context))
        assertTrue(DittoService.isOwnershipFenced(context))
        verify(exactly = 0) { context.stopService(any()); old.close() }

        ownershipFenced = false
        set("dittoInstance", null)
        initializationAttempted().set(true)
        assertFalse(DittoService.fenceColdStartForPlugin(context))
        assertTrue(DittoService.isOwnershipFenced(context))

        ownershipFenced = false
        initializationAttempted().set(false)
        activeService().value = runningService()
        assertFalse(DittoService.fenceColdStartForPlugin(context))
        assertTrue(DittoService.isOwnershipFenced(context))
    }

    @Test fun coldPluginFenceRejectsPersistenceFailure() = runTest {
        set("dittoInstance", null)
        fenceCommitOk = false
        assertFalse(DittoService.fenceColdStartForPlugin(context))
        verify(exactly = 0) { context.stopService(any()); old.close() }
    }

    @Test fun changingDisabledIdentityClosesOldInstanceAndAppliesNewWithoutSync() = runTest {
        val result = DittoService.applySelectedIdentity(context, repo)
        assertTrue(logs(), result)
        assertSame(replacement, DittoService.getDittoInstance())
        assertFalse(DittoService.isReconfiguring.value)
        verify(exactly = 0) { replacement.startSync() }
    }

    @Test fun ownershipQuiescenceStopsWorkersAndClosesBeforeSuccess() = runTest {
        val service = runningService()
        DittoService.isRunning.set(true)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        DittoService::class.java.getDeclaredField("ioScope").apply {
            isAccessible = true
            set(service, scope)
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        val closeCalled = CountDownLatch(1)
        every { old.close() } answers { closeCalled.countDown() }
        val handoff = async(UnconfinedTestDispatcher(testScheduler)) {
            DittoService.quiesceBundledEngineForPlugin(context)
        }
        try {
            assertTrue(ownershipFenced)
            assertFalse(handoff.isCompleted)
            verify(exactly = 0) { old.close() }
            releaseWorker.complete(Unit)
            worker.join()
            assertTrue(closeCalled.await(5, TimeUnit.SECONDS))
            assertTrue(handoff.await())
            assertNull(DittoService.getDittoInstance())
            assertTrue(DittoService.isOwnershipFenced(context))
            verifyOrder { old.stopSync(); old.close() }
            assertFalse(service.startPeerSync())
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    @Test fun hungNativeCloseTimesOutAndBlocksRetryEvenIfItLaterReturns() = runTest {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        every { old.close() } answers {
            closeStarted.countDown()
            releaseClose.await()
            closeReturned.countDown()
        }
        val handoff = async(UnconfinedTestDispatcher(testScheduler)) {
            DittoService.quiesceBundledEngineForPlugin(context)
        }
        try {
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            advanceTimeBy(15_001)
            runCurrent()
            assertFalse(handoff.await())
            assertTrue(DittoService.shutdownFailed.value)
            assertTrue(DittoService.isOwnershipFenced(context))
            assertSame(old, DittoService.getDittoInstance())
            assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
        } finally {
            releaseClose.countDown()
            assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
        }
        assertTrue(DittoService.shutdownFailed.value)
        assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
        verify(exactly = 1) { old.close() }
    }

    @Test fun cancelledNativeHandoffCloseKeepsFenceAfterCloseReturns() = runTest {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        every { old.close() } answers {
            closeStarted.countDown()
            releaseClose.await()
            closeReturned.countDown()
        }
        val handoff = async(UnconfinedTestDispatcher(testScheduler)) {
            DittoService.quiesceBundledEngineForPlugin(context)
        }
        try {
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            handoff.cancelAndJoin()
            assertTrue(DittoService.shutdownFailed.value)
            assertTrue(DittoService.isOwnershipFenced(context))
        } finally {
            releaseClose.countDown()
            assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
        }
        assertSame(old, DittoService.getDittoInstance())
        assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
        verify(exactly = 1) { old.close() }
    }

    @Test fun ownershipQuiescenceDoesNotStopEngineWhenFenceCannotBePersisted() = runTest {
        fenceCommitOk = false
        val service = runningService()
        DittoService.isRunning.set(true)

        assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
        assertSame(service, activeService().value)
        assertSame(old, DittoService.getDittoInstance())
        verify(exactly = 0) { context.stopService(any()); old.stopSync(); old.close() }
    }

    @Test fun ownershipQuiescenceRefusesFailedShutdownAndKeepsFence() = runTest {
        val service = runningService()
        DittoService.isRunning.set(true)
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        every { old.stopSync() } throws mockk<DittoError>(relaxed = true)

        assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
        assertTrue(DittoService.isOwnershipFenced(context))
        assertTrue(DittoService.shutdownFailed.value)
        assertSame(old, DittoService.getDittoInstance())
        verify(exactly = 0) { old.close() }
        assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
    }

    @Test fun ownershipQuiescenceRefusesWhileCancelledWorkerRemainsActive() = runTest {
        val service = runningService()
        DittoService.isRunning.set(true)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        DittoService::class.java.getDeclaredField("ioScope").apply {
            isAccessible = true
            set(service, scope)
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        try {
            assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
            assertTrue(DittoService.isOwnershipFenced(context))
            assertTrue(DittoService.shutdownFailed.value)
            assertSame(old, DittoService.getDittoInstance())
            assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
            verify(exactly = 0) { old.close() }
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    @Test fun cancelledOwnershipQuiescenceBlocksRetryUntilProcessRestart() = runTest {
        val service = runningService()
        DittoService.isRunning.set(true)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        DittoService::class.java.getDeclaredField("ioScope").apply {
            isAccessible = true
            set(service, scope)
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        val handoff = async(UnconfinedTestDispatcher(testScheduler)) {
            DittoService.quiesceBundledEngineForPlugin(context)
        }
        try {
            assertFalse(handoff.isCompleted)
            handoff.cancelAndJoin()
            assertTrue(DittoService.shutdownFailed.value)
            assertTrue(DittoService.isOwnershipFenced(context))
            assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
            verify(exactly = 0) { old.close() }
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    @Test fun callerTimeoutStillCancelsOwnershipQuiescence() = runTest {
        val service = runningService()
        DittoService.isRunning.set(true)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        DittoService::class.java.getDeclaredField("ioScope").apply {
            isAccessible = true
            set(service, scope)
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        try {
            try {
                withTimeout(1_000) { DittoService.quiesceBundledEngineForPlugin(context) }
                fail("Expected caller timeout")
            } catch (_: TimeoutCancellationException) {
                assertTrue(DittoService.shutdownFailed.value)
                assertTrue(DittoService.isOwnershipFenced(context))
                assertFalse(DittoService.quiesceBundledEngineForPlugin(context))
                verify(exactly = 0) { old.close() }
            }
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    @Test fun explicitEnableDisableUsesPersistedToggleWithoutReplacingIdentity() = runTest {
        mockkConstructor(ServiceConfiguration::class)
        every { anyConstructed<ServiceConfiguration>().getServiceEnabledFlow(any()) } returns flowOf(false)
        every { anyConstructed<ServiceConfiguration>().isServiceAvailable(any()) } returns false
        repeat(2) {
            assertTrue(DittoService.applyEnabled(context, repo, true))
            assertTrue(enabled.value)
        }
        repeat(2) {
            assertTrue(DittoService.applyEnabled(context, repo, false))
            assertFalse(enabled.value)
        }
        assertSame(old, DittoService.getDittoInstance())
        verify(exactly = 0) { old.close() }
        verify(exactly = 0) { DittoService.Companion["initializeDitto"](context, repo) }
    }

    @Test fun managedToggleNeverMutatesSettings() = runTest {
        every { repo.getNetworkPreferencesLocked } returns flowOf(true)
        assertFalse(DittoService.applyEnabled(context, repo, true))
        coVerify(exactly = 0) { repo.storeEnableDittoService(any()) }
    }

    @Test fun companionConnectionStillSuppressesSyncAfterIdentityApply() = runTest {
        enabled.value = true
        companion.value = true
        val result = DittoService.applySelectedIdentity(context, repo)
        assertTrue(logs(), result)
        verify(exactly = 0) { replacement.startSync() }
    }

    @Test fun nativeRejectionIsFailureAndAlwaysReleasesReconfigurationGate() = runTest {
        initializeOk = false
        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertNull(logs(), DittoService.getDittoInstance())
        assertFalse(DittoService.isReconfiguring.value)
    }

    @Test fun runningServiceIsStoppedAndWorkersCancelledBeforeNativeClose() = runTest {
        val service = Robolectric.buildService(DittoService::class.java).get()
        DittoService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true
            set(service, repo)
        }
        DittoService::class.java.getDeclaredField("ownsDitto").apply {
            isAccessible = true
            set(service, true)
        }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        DittoService::class.java.getDeclaredField("ioScope").apply {
            isAccessible = true
            set(service, scope)
        }
        val diagnosticsStarted = CompletableDeferred<Unit>()
        val diagnosticsClosed = CompletableDeferred<Unit>()
        DittoDiagnostics(scope, MutableStateFlow(true), old, { _, _ -> },
            observe = { _, _ ->
                diagnosticsStarted.complete(Unit)
                AutoCloseable { diagnosticsClosed.complete(Unit) }
            })
        diagnosticsStarted.await()
        activeService().value = service
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        every { old.close() } answers {
            assertFalse(scope.isActive)
            assertTrue(diagnosticsClosed.isCompleted)
        }
        assertTrue(DittoService.applySelectedIdentity(context, repo))
        verifyOrder { old.stopSync(); old.close() }
    }

    @Test fun configuredIdentityDoesNotClaimSuccessWhenServiceStartupFails() = runTest {
        mockkConstructor(ServiceConfiguration::class)
        every { anyConstructed<ServiceConfiguration>().getServiceEnabledFlow(any()) } returns flowOf(true)
        every { anyConstructed<ServiceConfiguration>().isServiceAvailable(any()) } returns true
        ReactiveServiceManager.isRunning.set(true)
        @Suppress("UNCHECKED_CAST")
        val startup = DittoService::class.java.getDeclaredField("startupResult").run {
            isAccessible = true
            get(null) as MutableStateFlow<Boolean?>
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            DittoService.isReconfiguring.first { it }
            DittoService.isReconfiguring.first { !it }
            startup.value = false
        }
        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertSame(replacement, DittoService.getDittoInstance())
    }

    @Test fun failedSdkStopBlocksIdentityReplacementAndSubsequentRequests() = runTest {
        val service = runningService()
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        every { old.stopSync() } throws mockk<DittoError>(relaxed = true)

        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertTrue(DittoService.shutdownFailed.value)
        assertSame(old, DittoService.getDittoInstance())
        assertNull(activeService().value)
        assertFalse(DittoService.isReconfiguring.value)
        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertFalse(DittoService.applyEnabled(context, repo, true))
        verify(exactly = 1) { old.stopSync() }
        verify(exactly = 0) { old.close() }
        verify(exactly = 0) { DittoService.Companion["initializeDitto"](context, repo) }
        assertFalse(service.startPeerSync())
    }

    @Test fun failedNativeCloseFencesRestartAndIdentityReplacement() = runTest {
        every { old.close() } throws IllegalStateException("synthetic close failure")

        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertTrue(DittoService.shutdownFailed.value)
        assertSame(old, DittoService.getDittoInstance())
        assertFalse(DittoService.isReconfiguring.value)
        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertFalse(DittoService.applyEnabled(context, repo, true))
        verify(exactly = 1) { old.close() }
        verify(exactly = 0) { DittoService.Companion["initializeDitto"](context, repo) }
    }

    @Test fun cancelledNativeCloseAlsoFencesRestart() = runTest {
        every { old.close() } throws CancellationException("synthetic close cancellation")

        try {
            DittoService.applySelectedIdentity(context, repo)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(DittoService.shutdownFailed.value)
            assertSame(old, DittoService.getDittoInstance())
            assertFalse(DittoService.isReconfiguring.value)
            assertFalse(DittoService.applyEnabled(context, repo, true))
        }
    }

    @Test fun failedCandidateCleanupFencesRestart() = runTest {
        every { replacement.close() } throws IllegalStateException("synthetic cleanup failure")

        assertThrows(IllegalStateException::class.java) {
            DittoService.closeNativeDitto(replacement)
        }
        assertTrue(DittoService.shutdownFailed.value)
        assertFalse(DittoService.applyEnabled(context, repo, true))
    }

    @Test fun failedDisableRestoresIntentButFencesAutomaticRestart() = runTest {
        enabled.value = true
        val service = runningService()
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        every { old.stopSync() } throws mockk<DittoError>(relaxed = true)

        assertFalse(DittoService.applyEnabled(context, repo, false))
        assertTrue(enabled.value)
        assertTrue(DittoService.shutdownFailed.value)
        val configuration = ServiceConfiguration(context, repo, app.audioRepo, flowOf(false), null)
        assertFalse(configuration.getServiceEnabledFlow(ReactiveServiceManager.ServiceType.DITTO).first())
        val next = runningService(ownsDitto = false)
        assertFalse(next.startPeerSync())
        next.onDestroy()
        assertTrue(DittoService.shutdownFailed.value)
        verify(exactly = 1) { old.stopSync() }
    }

    @Test fun disableWaitsForCancelledWorkersBeforeAllowingRestart() = runTest {
        enabled.value = true
        val service = runningService()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        DittoService::class.java.getDeclaredField("ioScope").apply {
            isAccessible = true
            set(service, scope)
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        val disable = async(UnconfinedTestDispatcher(testScheduler)) {
            DittoService.applyEnabled(context, repo, false)
        }
        try {
            assertFalse(disable.isCompleted)
            assertTrue(DittoService.isReconfiguring.value)
            assertFalse(enabled.value)
            releaseWorker.complete(Unit)
            worker.join()
            assertTrue(logs(), disable.await())
            assertFalse(DittoService.isReconfiguring.value)
            assertFalse(DittoService.shutdownFailed.value)
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    @Test fun disableWorkerTimeoutRestoresIntentAndFencesRestart() = runTest {
        enabled.value = true
        val service = runningService()
        val scope = DittoService::class.java.getDeclaredField("ioScope").run {
            isAccessible = true
            get(service) as CoroutineScope
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        try {
            assertFalse(DittoService.applyEnabled(context, repo, false))
            assertTrue(enabled.value)
            assertTrue(DittoService.shutdownFailed.value)
            assertFalse(DittoService.isReconfiguring.value)
            assertFalse(DittoService.applyEnabled(context, repo, true))
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    @Test fun cancelledDisableRestoresIntentAndFencesRestart() = runTest {
        enabled.value = true
        val service = runningService()
        val scope = DittoService::class.java.getDeclaredField("ioScope").run {
            isAccessible = true
            get(service) as CoroutineScope
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        val disable = async(UnconfinedTestDispatcher(testScheduler)) {
            DittoService.applyEnabled(context, repo, false)
        }
        try {
            assertFalse(disable.isCompleted)
            disable.cancelAndJoin()
            assertTrue(enabled.value)
            assertTrue(DittoService.shutdownFailed.value)
            assertFalse(DittoService.isReconfiguring.value)
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    @Test fun missingOwnedInstanceCannotAcknowledgeShutdown() = runTest {
        val service = runningService()
        set("dittoInstance", null)
        every { context.stopService(any()) } answers { service.onDestroy(); true }

        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertTrue(DittoService.shutdownFailed.value)
        verify(exactly = 0) { old.close() }
        verify(exactly = 0) { DittoService.Companion["initializeDitto"](context, repo) }
    }

    @Test fun failedShutdownBlocksServiceCreationBeforeNativeAccess() = runTest {
        val service = runningService()
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        every { old.stopSync() } throws mockk<DittoError>(relaxed = true)
        assertFalse(DittoService.applySelectedIdentity(context, repo))

        every { app.repo } returns repo
        val next = spyk(runningService(ownsDitto = false))
        every { next.applicationContext } returns app
        next.onCreate()
        try {
            verify(exactly = 0) { old.startSync(); old.sync; old.presence }
            assertTrue(DittoService.shutdownFailed.value)
            assertFalse(DittoService.syncActive.value)
        } finally {
            next.onDestroy()
        }
    }

    @Test fun failedResourceCleanupDoesNotAcknowledgeSuccessfulSdkStop() = runTest {
        val service = runningService()
        val observers = mockk<DittoStoreObservers>()
        every { observers.close() } throws IllegalStateException("synthetic cleanup failure")
        DittoService::class.java.getDeclaredField("storeObservers").apply {
            isAccessible = true
            set(service, observers)
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }

        assertFalse(DittoService.applySelectedIdentity(context, repo))
        assertTrue(DittoService.shutdownFailed.value)
        verify(exactly = 1) { old.stopSync() }
        verify(exactly = 0) { old.close() }
        verify(exactly = 0) { DittoService.Companion["initializeDitto"](context, repo) }
    }

    @Test fun workerJoinTimeoutBlocksReplacementAndRestartUntilProcessRestart() = runTest {
        val service = runningService()
        val scope = DittoService::class.java.getDeclaredField("ioScope").run {
            isAccessible = true
            get(service) as CoroutineScope
        }
        val releaseWorker = CompletableDeferred<Unit>()
        val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { releaseWorker.await() }
        }
        every { context.stopService(any()) } answers { service.onDestroy(); true }
        try {
            assertFalse(DittoService.applySelectedIdentity(context, repo))
            assertTrue(DittoService.shutdownFailed.value)
            assertFalse(DittoService.isReconfiguring.value)
            assertFalse(DittoService.applySelectedIdentity(context, repo))
            verify(exactly = 1) { old.stopSync() }
            verify(exactly = 0) { old.close() }
        } finally {
            releaseWorker.complete(Unit)
            worker.join()
        }
    }

    private fun runningService(ownsDitto: Boolean = true): DittoService =
        Robolectric.buildService(DittoService::class.java).get().also { service ->
            DittoService::class.java.getDeclaredField("dataRepository").apply {
                isAccessible = true
                set(service, repo)
            }
            DittoService::class.java.getDeclaredField("ownsDitto").apply {
                isAccessible = true
                set(service, ownsDitto)
            }
            activeService().value = service
        }

    private fun initializationAttempted() =
        DittoService::class.java.getDeclaredField("engineInitializationAttempted").run {
            isAccessible = true
            get(null) as java.util.concurrent.atomic.AtomicBoolean
        }

    @Suppress("UNCHECKED_CAST")
    private fun shutdownFailure() = DittoService::class.java.getDeclaredField("shutdownFailureState").run {
        isAccessible = true
        get(null) as MutableStateFlow<Boolean>
    }

    @Suppress("UNCHECKED_CAST")
    private fun activeService() = DittoService::class.java.getDeclaredField("activeService").run {
        isAccessible = true
        get(null) as MutableStateFlow<DittoService?>
    }

    private fun set(name: String, value: Any?) {
        DittoService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(null, value)
        }
    }

    private fun logs() = org.robolectric.shadows.ShadowLog.getLogsForTag("WearTAK-DittoService")
        .joinToString { it.msg }
}
