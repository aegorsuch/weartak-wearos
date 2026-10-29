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
        val scope = DittoService::class.java.getDeclaredField("ioScope").run {
            isAccessible = true
            get(service) as CoroutineScope
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
