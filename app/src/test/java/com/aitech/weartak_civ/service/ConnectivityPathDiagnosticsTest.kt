package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.integration.plugin.PluginSettingsCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConnectivityPathDiagnosticsTest {
    @Test fun optInTransitionsPeriodicAndChargingBoundariesContainOnlyNumericClassifications() = runTest {
        val enabled = MutableStateFlow(false)
        val toggles = MutableStateFlow<Map<String, PluginSettingsCoordinator.ConnectivityToggle>>(emptyMap())
        val charging = MutableStateFlow(false)
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.chargingState } returns charging
        every { repo.getEnableTakEndpoint } returns flowOf(true)
        every { repo.getTakServerListReady } returns flowOf(emptyList())
        every { repo.outboundReadyRoutes } returns MutableStateFlow(emptyMap())
        every { repo.getEnableSitxService } returns flowOf(false)
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        every { repo.getSelectedSitxGroup } returns flowOf(null)
        every { repo.getEnableMulticast } returns flowOf(false)
        every { repo.multicastPublisherRunning } returns MutableStateFlow(false)
        every { repo.getEnableCompanionAppService } returns flowOf(false)
        every { repo.isCompanionConnected } returns MutableStateFlow(false)
        val plugins = mockk<PluginSettingsCoordinator>()
        every { plugins.connectivityToggles } returns toggles
        every { plugins.outboundCotReadyRoutes } returns MutableStateFlow(emptyMap())
        val events = mutableListOf<Map<DiagnosticMetric, Long>>()
        var powerSnapshots = 0
        ConnectivityPathDiagnostics(backgroundScope, enabled, repo, plugins, events::add,
            powerSnapshot = { powerSnapshots++ })
        runCurrent()
        assertTrue(events.isEmpty())
        enabled.value = true
        runCurrent()
        assertEquals(5, events.size)
        toggles.value = mapOf("sensitive-plugin-id" to PluginSettingsCoordinator.ConnectivityToggle(true, mockk()))
        runCurrent()
        assertEquals(6, events.size)
        assertEquals(1L, events.last()[DiagnosticMetric.ENABLED])
        assertEquals(1L, events.last()[DiagnosticMetric.CONFIGURED_COUNT])
        assertTrue(events.all { it.values.all { value -> value in 0L..5L } })
        charging.value = true
        runCurrent()
        assertEquals(11, events.size)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(16, events.size)
        assertEquals(1, powerSnapshots)
        enabled.value = false
        runCurrent()
        charging.value = false
        toggles.value = emptyMap()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(16, events.size)
        assertEquals(1, powerSnapshots)
    }
}
