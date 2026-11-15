package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoIdentityApplyTest {
    private val repo = mockk<DataRepository>(relaxed = true)
    private val app = mockk<WearTakApplication>(relaxed = true)
    private val context = mockk<Context>()
    private val old = mockk<Ditto>(relaxed = true)
    private val replacement = mockk<Ditto>(relaxed = true)
    private val enabled = MutableStateFlow(false)
    private val companion = MutableStateFlow(false)
    private val companionGrace = MutableStateFlow(false)
    private var initializeOk = true

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.applicationContext } returns app
        every { context.packageName } returns "com.aitech.weartak_civ"
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
        activeService().value = null
        shutdownFailure().value = false
        ReactiveServiceManager.isRunning.set(false)
        unmockkObject(DittoService.Companion)
        unmockkConstructor(ServiceConfiguration::class)
        Dispatchers.resetMain()
    }

    @Test fun changingDisabledIdentityClosesOldInstanceAndAppliesNewWithoutSync() = runTest {
        val result = DittoService.applySelectedIdentity(context, repo)
        assertTrue(logs(), result)
        assertSame(replacement, DittoService.getDittoInstance())
        assertFalse(DittoService.isReconfiguring.value)
        verify(exactly = 0) { replacement.startSync() }
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
