package com.aitech.weartak_civ.presentation

import android.app.Application
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionPluginUiTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun offOverridesEveryTransportState() {
        for (bluetooth in CompanionBluetoothState.entries)
            for (connection in BleConnectionState.entries)
                assertEquals(R.string.companion_status_off, companionStatusLabel(false, bluetooth, connection))
    }

    @Test fun bluetoothFailuresCannotLookConnected() {
        val expected = mapOf(
            CompanionBluetoothState.OFF to R.string.companion_status_bluetooth_off,
            CompanionBluetoothState.PERMISSION_REQUIRED to R.string.companion_status_permission,
            CompanionBluetoothState.UNAVAILABLE to R.string.companion_status_unavailable
        )
        expected.forEach { (bluetooth, label) ->
            for (connection in BleConnectionState.entries)
                assertEquals(label, companionStatusLabel(true, bluetooth, connection))
        }
    }

    @Test fun onlyReadySubscriptionIsConnectedAndErrorsRemainExplicit() {
        val labels = mapOf(
            BleConnectionState.NOT_CONNECTED to R.string.companion_status_waiting,
            BleConnectionState.ADVERTISING to R.string.companion_status_waiting,
            BleConnectionState.DISCONNECTED to R.string.companion_status_waiting,
            BleConnectionState.CONNECTING to R.string.companion_status_connecting,
            BleConnectionState.CONNECTED to R.string.companion_status_connecting,
            BleConnectionState.DISCONNECTING to R.string.companion_status_disconnecting,
            BleConnectionState.SERVICES_READY to R.string.companion_status_connected,
            BleConnectionState.CONNECTION_FAILED to R.string.companion_status_failed,
            BleConnectionState.ERROR to R.string.companion_status_error
        )
        assertEquals(BleConnectionState.entries.toSet(), labels.keys)
        labels.forEach { (connection, label) ->
            assertEquals(label, companionStatusLabel(true, CompanionBluetoothState.ON, connection))
        }
    }

    @Test fun splitBodyNavigatesWithoutTogglingAndSwitchDoesNotNavigate() {
        val enabled = mutableStateOf(false)
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "plugins") {
                composable("plugins") {
                    CompanionPluginChip(enabled.value, if (enabled.value) "Waiting for phone" else "Off",
                        onToggle = { toggles += it; enabled.value = it },
                        onDetails = { nav.navigate(COMPANION_DETAILS_ROUTE) })
                }
                composable(COMPANION_DETAILS_ROUTE) { CompanionDetailsPage("Waiting for phone", nav, false, {}) }
            }
        }
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { assertEquals(listOf(true), toggles) }
        compose.onNodeWithText("Waiting for phone").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick().assertIsOff()
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(true, false), toggles) }
        compose.onNodeWithText("WearTAK Companion").assertIsDisplayed().performClick()
        compose.onNodeWithText("Choose connection method in the phone companion app.").assertExists()
        compose.runOnIdle { assertEquals(listOf(true, false), toggles) }
    }

    @Test fun detailsHasDisabledPriorityAndNormalBluetoothSettingsLink() {
        compose.setContent { CompanionDetailsPage("Connected", rememberNavController(), false, {}) }
        compose.onNodeWithText("Connected").assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Team Lead EUD Priority"))
        compose.onNodeWithText("Team Lead EUD Priority").assertIsNotEnabled()
        compose.onNodeWithText("Not available yet").assertExists()
        compose.onNodeWithText("Not available yet").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Android Bluetooth settings"))
        compose.onNodeWithText("Android Bluetooth settings").assertIsEnabled().assertIsDisplayed()
    }

    @Test fun bluetoothLinkOpensSettingsWithoutRequestingBondOrEnablingAdapter() {
        val context = mockk<Context>()
        every { context.startActivity(any()) } returns Unit
        openCompanionBluetoothSettings(context)
        verify(exactly = 1) { context.startActivity(match {
            it.action == Settings.ACTION_BLUETOOTH_SETTINGS && it.extras == null
        }) }
    }

    @Test fun adapterStateDistinguishesDeniedPermissionOffAndOn() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context)
        app.denyPermissions(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        assertEquals(CompanionBluetoothState.PERMISSION_REQUIRED, readCompanionBluetoothState(context))
        app.grantPermissions(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(false)
        assertEquals(CompanionBluetoothState.OFF, readCompanionBluetoothState(context))
        shadowOf(adapter).setEnabled(true)
        assertEquals(CompanionBluetoothState.ON, readCompanionBluetoothState(context))
    }

    @Test fun absentAdapterIsUnavailableNotOffOrConnected() {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(BluetoothManager::class.java) } returns null
        assertEquals(CompanionBluetoothState.UNAVAILABLE, readCompanionBluetoothState(context))
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundChipShowsCompanionNameAndToggle() {
        compose.setContent {
            CompanionPluginChip(true, "Waiting for phone", onToggle = {}, onDetails = {})
        }
        compose.onNodeWithText("WearTAK Companion").assertIsDisplayed()
        compose.onNodeWithText("Waiting for phone").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundDetailsCanReachBothActions() {
        compose.setContent { CompanionDetailsPage("Connection failed", rememberNavController(), false, {}) }
        compose.onNodeWithText("Connection failed").assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Team Lead EUD Priority"))
        compose.onNodeWithText("Team Lead EUD Priority").assertIsDisplayed().assertIsNotEnabled()
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Android Bluetooth settings"))
        compose.onNodeWithText("Android Bluetooth settings").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun smallRoundDiagnosticsPreservesSavedStateAndHelperIsReachable() {
        diagnosticsPreservesSavedStateAndHelperIsReachable()
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundDiagnosticsPreservesSavedStateAndHelperIsReachable() {
        diagnosticsPreservesSavedStateAndHelperIsReachable()
    }

    private fun diagnosticsPreservesSavedStateAndHelperIsReachable() {
        MainActivity.device_uid = "test-watch"
        val repository = DataRepository(RuntimeEnvironment.getApplication())
        runBlocking {
            repository.storeBondedLocationDiagnosticsEnabled(true)
            repository.storeEnableCompanionAppService(false)
        }
        val showDetails = mutableStateOf(true)
        val completedWrites = AtomicInteger()
        compose.setContent {
            if (showDetails.value) {
                val enabled by repository.getBondedLocationDiagnosticsEnabled.collectAsState(initial = true)
                val scope = rememberCoroutineScope()
                CompanionDetailsPage("Off", rememberNavController(), enabled) {
                    scope.launch {
                        repository.storeBondedLocationDiagnosticsEnabled(it)
                        completedWrites.incrementAndGet()
                    }
                }
            }
        }
        fun scrollToDiagnostics() {
            compose.onAllNodes(hasScrollAction()).onFirst()
                .performScrollToNode(hasText("Bonded Location Diagnostics"))
        }
        scrollToDiagnostics()
        compose.waitUntil { compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().size == 1 }
        compose.onNode(isToggleable()).assertIsOn().performClick()
        compose.waitUntil { completedWrites.get() == 1 }
        compose.waitUntil { runBlocking { !repository.getBondedLocationDiagnosticsEnabled.first() } }
        compose.waitUntil { compose.onAllNodes(isToggleable() and isOff()).fetchSemanticsNodes().size == 1 }
        compose.runOnIdle { showDetails.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showDetails.value = true }
        scrollToDiagnostics()
        compose.waitUntil { compose.onAllNodes(isToggleable() and isOff()).fetchSemanticsNodes().size == 1 }
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.waitUntil { completedWrites.get() == 2 }
        compose.waitUntil { runBlocking { repository.getBondedLocationDiagnosticsEnabled.first() } }
        compose.waitUntil { compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().size == 1 }
        val helper = "Compare watch and phone GNSS quality over a bonded Companion connection."
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(helper))
        compose.onNodeWithText(helper).assertIsDisplayed()
        runBlocking {
            assertFalse(repository.getEnableCompanionAppService.first())
            repository.storeBondedLocationDiagnosticsEnabled(false)
        }
    }
}
