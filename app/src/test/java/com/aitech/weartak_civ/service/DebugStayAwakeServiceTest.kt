package com.aitech.weartak_civ.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.aitech.weartak_civ.R
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowWifiManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    shadows = [DebugStayAwakeServiceTest.StayAwakeConnectivityShadow::class,
        DebugStayAwakeServiceTest.StayAwakeWifiShadow::class])
class DebugStayAwakeServiceTest {
    private lateinit var controller: ServiceController<DebugStayAwakeService>
    private lateinit var service: DebugStayAwakeService
    private lateinit var connectivity: StayAwakeConnectivityShadow

    @Before
    fun setUp() {
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        connectivity = Shadow.extract(service.getSystemService(ConnectivityManager::class.java))
        service.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 1)
        setWirelessDebugging(false)
        setWifiState(WifiManager.WIFI_STATE_ENABLED)
        setPlugged(BatteryManager.BATTERY_PLUGGED_USB)
    }

    @After
    fun tearDown() {
        controller.destroy()
        DiagnosticResources.stop()
    }

    @Test
    fun keepsAllLocksWhenWirelessDebuggingStops() {
        setWirelessDebugging(true)
        start()
        assertTrue(DebugStayAwakeService.active.value)
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertTrue(screen.isHeld)
        assertTrue(cpu.isHeld)
        assertTrue(wifi.isHeld)

        setWirelessDebugging(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(screen.isHeld)
        assertTrue(cpu.isHeld)
        assertTrue(wifi.isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun startsWithoutWirelessDebugging() {
        assertFalse(DebugStayAwakeService.active.value)
        start()
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertTrue(lock<WifiManager.WifiLock>("wifiLock").isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(0, Settings.Global.getInt(service.contentResolver, "adb_wifi_enabled", 0))
        assertEquals(1, connectivity.networkCallbacks.size)
        val request = connectivity.requests.single()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertEquals(null, service.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
    }

    @Test
    fun stopActionReleasesLocksWithoutChangingWirelessDebugging() {
        setWirelessDebugging(true)
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2))
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertTrue(Settings.Global.getInt(
            service.contentResolver, "adb_wifi_enabled", 0
        ) == 1)
    }

    @Test
    fun destructionReleasesLocks() {
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        service.onDestroy()
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun nullRestartDoesNotEnableMode() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun nullRestartRetainsEnabledIntentAndExistingLocks() {
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(screen.isHeld)
        assertTrue(cpu.isHeld)
        assertTrue(wifi.isHeld)
        assertTrue(DebugStayAwakeService.active.value)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun removingRecentTaskKeepsSessionActive() {
        start()
        service.onTaskRemoved(Intent())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertTrue(lock<WifiManager.WifiLock>("wifiLock").isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun repeatedStartKeepsOnlyOneWifiRequest() {
        start()
        start()
        assertEquals(1, connectivity.requests.size)
        assertEquals(1, connectivity.networkCallbacks.size)
        assertEquals(1, conditionReceiverCount())
    }

    @Test
    fun deniedWifiRequestReleasesLocksAndStopsSession() {
        legacyOptIn(true)
        lateinit var screen: PowerManager.WakeLock
        lateinit var cpu: PowerManager.WakeLock
        lateinit var wifi: WifiManager.WifiLock
        connectivity.beforeRequest = {
            screen = lock("screenLock")
            cpu = lock("cpuLock")
            wifi = lock("wifiLock")
            assertTrue(screen.isHeld)
            assertTrue(cpu.isHeld)
            assertTrue(wifi.isHeld)
        }
        connectivity.denyRequest = true
        start()
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(wifi.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(screen.isHeld)
        assertEquals(0, shadowOf(service.getSystemService(WifiManager::class.java)).activeLockCount)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(DebugStayAwakePreferences(service).enabled)
        connectivity.denyRequest = false
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun optedInSessionResumesAfterPackageReplacement() {
        legacyOptIn(true)
        start()
        service.onDestroy()
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(DebugStayAwakePreferences(service).enabled)

        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val restart = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(DebugStayAwakeService::class.java.name, restart?.component?.className)
        assertEquals(Service.START_STICKY, service.onStartCommand(restart, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun updateResumesWithoutOldOptInButNeverWhenExplicitlyOff() {
        start()
        service.onDestroy()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        legacyOptIn(true)
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun explicitSwitchStopAndNotificationStopPreventUpdateResume() {
        val preferences = DebugStayAwakePreferences(service)
        legacyOptIn(true)
        start()
        DebugStayAwakeService.stop(service)
        assertFalse(preferences.enabled)
        start()
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        assertFalse(preferences.enabled)
    }

    @Test
    fun legacyOptOutDoesNotOverrideEnabledIntent() {
        legacyOptIn(true)
        start()
        legacyOptIn(false)
        service.onDestroy()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        assertFalse(DebugStayAwakeService.active.value)
    }

    @Test
    fun rebootRestoresEnabledIntentRegardlessOfOldBootCount() {
        val preferences = DebugStayAwakePreferences(service)
        legacyOptIn(true)
        start()
        service.onDestroy()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 2)
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        assertTrue(preferences.enabled)
    }

    @Test
    fun migrationPreservesEveryLegacyEnabledOffCombination() {
        for (enabled in listOf(false, true)) for (oldOptIn in listOf(false, true)) {
            val preferences = DebugStayAwakePreferences(service)
            legacyOptIn(oldOptIn)
            preferences.recordSession(enabled)
            Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 99)
            assertEquals(enabled, DebugStayAwakePreferences(service).enabled)
        }
    }

    @Test
    fun processRecreationRestoresLocksFromIntentWithoutStartingOtherServices() {
        start()
        controller.destroy()
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        connectivity = Shadow.extract(service.getSystemService(ConnectivityManager::class.java))
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertTrue(lock<WifiManager.WifiLock>("wifiLock").isHeld)
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun alreadyReleasedWifiRequestDoesNotPreventLockCleanup() {
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        connectivity.alreadyReleased = true
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        service.onDestroy()
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertEquals(1, connectivity.unregisterCalls)
    }

    @Test
    fun queuedRestoreCannotOverrideExplicitOff() {
        DebugStayAwakePreferences(service).recordSession(true)
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_BOOT_COMPLETED))
        val restore = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        DebugStayAwakeService.stop(service)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(restore, 0, 1))
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun freshInstallNeverStartsFromBootOrPackageReplacement() {
        assertFalse(DebugStayAwakePreferences(service).enabled)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            DebugStayAwakeUpdateReceiver().onReceive(service, Intent(action))
            assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun defaultOffAndExplicitOffNeverStartFromBootUpdateOrUnrelatedBroadcast() {
        for (enabled in listOf(false, true)) {
            DebugStayAwakePreferences(service).recordSession(enabled)
            DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_TIME_CHANGED))
            assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
        DebugStayAwakeService.stop(service)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            DebugStayAwakeUpdateReceiver().onReceive(service, Intent(action))
            assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
    }

    @Test
    fun restoreStartDeniedByAndroidKeepsSavedIntentAndOnlyTargetsStayAwake() {
        DebugStayAwakePreferences(service).recordSession(true)
        val context = object : android.content.ContextWrapper(service) {
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                assertEquals(DebugStayAwakeService::class.java.name, intent.component?.className)
                throw IllegalStateException("Background foreground-service start denied")
            }
        }
        DebugStayAwakeUpdateReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("WearTAK-DebugStayAwake")
            .any { it.msg.contains("saved intent remains enabled") && it.throwable is IllegalStateException })
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun bareOrUnknownStartCannotEnableSavedOff() {
        for (intent in listOf(Intent(), Intent().setAction("unknown"))) {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertFalse(DebugStayAwakePreferences(service).enabled)
            assertFalse(DebugStayAwakeService.active.value)
            assertTrue(connectivity.requests.isEmpty())
        }
    }

    @Test
    fun wifiDisablingImmediatelyReleasesDemandAndPersistsOff() {
        verifyAutoOff(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLING))
    }

    @Test
    fun wifiDisabledAlsoStopsWithoutDisablingBroadcast() {
        verifyAutoOff(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED))
    }

    @Test
    fun unplugDoesNotStopManualMode() {
        verifyManualSurvivesPowerChange(Intent(Intent.ACTION_POWER_DISCONNECTED))
    }

    @Test
    fun batteryUnplugStateDoesNotStopManualMode() {
        verifyManualSurvivesPowerChange(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 0))
    }

    @Test
    fun unknownBatteryStateDoesNotStopManualMode() {
        verifyManualSurvivesPowerChange(Intent(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun verifyManualSurvivesPowerChange(trigger: Intent) {
        start()
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        broadcast(trigger)
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(wifi.isHeld)
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    private fun verifyAutoOff(trigger: Intent) {
        val app = RuntimeEnvironment.getApplication()
        val resources = mutableListOf<Map<DiagnosticMetric, Long>>()
        DiagnosticResources.start { _, metrics -> resources.add(metrics); true }
        val receiversBefore = conditionReceiverCount()
        start()
        shadowOf(Looper.getMainLooper()).idle()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertEquals(receiversBefore + 1, conditionReceiverCount())
        assertEquals(1, connectivity.networkCallbacks.size)
        val saved = service.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE)
        var observedDisabled = false
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "session_active" && !DebugStayAwakePreferences(service).enabled) {
                observedDisabled = true
                assertTrue(connectivity.networkCallbacks.isEmpty())
                assertFalse(screen.isHeld)
                assertFalse(cpu.isHeld)
                assertFalse(wifi.isHeld)
            }
        }
        saved.registerOnSharedPreferenceChangeListener(listener)
        try {
            broadcast(trigger)
            assertTrue(observedDisabled)
        } finally {
            saved.unregisterOnSharedPreferenceChangeListener(listener)
        }

        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertEquals(1, connectivity.unregisterCalls)
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertEquals(0, shadowOf(service.getSystemService(WifiManager::class.java)).activeLockCount)
        assertEquals(receiversBefore, conditionReceiverCount())
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        listOf(DiagnosticResource.DEBUG_CPU, DiagnosticResource.DEBUG_SCREEN,
            DiagnosticResource.DEBUG_WIFI, DiagnosticResource.DEBUG_NETWORK).forEach { resource ->
            val records = resources.filter { it[DiagnosticMetric.RESOURCE_ID] == resource.code }
            val begin = records.single { it[DiagnosticMetric.ACTION] == ResourceAction.BEGIN.code }
            val end = records.single { it[DiagnosticMetric.ACTION] == ResourceAction.END.code }
            assertEquals(begin[DiagnosticMetric.TOKEN], end[DiagnosticMetric.TOKEN])
            assertEquals(begin.getValue(DiagnosticMetric.ACTIVE) - 1, end[DiagnosticMetric.ACTIVE])
        }

        broadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        setPlugged(BatteryManager.BATTERY_PLUGGED_USB)
        setWifiState(WifiManager.WIFI_STATE_ENABLED)
        broadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
        DebugStayAwakeService.restoreIfEnabled(service)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            DebugStayAwakeUpdateReceiver().onReceive(service, Intent(action))
        }
        val starts = generateSequence { shadowOf(app).nextStartedService }.toList()
        assertTrue(starts.none { it.component?.className == DebugStayAwakeService::class.java.name })
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent().setAction(DebugStayAwakeService.ACTION_RESTORE), 0, 2))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3))
        controller.destroy()
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 4))
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakeService.active.value)
        assertEquals(1, connectivity.requests.size)
    }

    @Test
    fun ordinaryDisconnectAndUnknownRadioBroadcastDoNotStopOrDuplicateRequest() {
        start()
        broadcast(Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION))
        broadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN))
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertEquals(1, connectivity.requests.size)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun wifiOffRefusesManualEnableAndDirectServiceStartWithShortToast() {
        setWifiState(WifiManager.WIFI_STATE_DISABLED)
        verifyRefusal(R.string.debug_stay_awake_wifi_enable_failed)
    }

    @Test
    fun unpluggedAllowsManualEnableAndDirectServiceStart() {
        setPlugged(0)
        DebugStayAwakeService.setEnabled(service, true)
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun unknownRadioFailsClosedButManualDoesNotRequireBatteryState() {
        setWifiState(WifiManager.WIFI_STATE_UNKNOWN)
        verifyRefusal(R.string.debug_stay_awake_requires_wifi)
        setWifiState(WifiManager.WIFI_STATE_ENABLED)
        setPlugged(-1)
        start()
        assertTrue(DebugStayAwakeService.active.value)
    }

    private fun verifyRefusal(message: Int) {
        val app = RuntimeEnvironment.getApplication()
        val receiverCount = conditionReceiverCount()
        DebugStayAwakeService.setEnabled(service, true)
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertEquals(null, shadowOf(app).nextStartedService)
        assertEquals(service.getString(message), ShadowToast.getTextOfLatestToast())
        assertEquals(android.widget.Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
        DebugStayAwakePreferences(service).recordSession(true)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertEquals(service.getString(message), ShadowToast.getTextOfLatestToast())
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
        assertEquals(receiverCount, conditionReceiverCount())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun restoreBootAndUpdateRefuseRejectedWifiButAllowBatteryAndPersistIntent() {
        for (action in listOf(null, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            for (wifiOff in listOf(true, false)) {
                setWifiState(if (wifiOff) WifiManager.WIFI_STATE_DISABLED else WifiManager.WIFI_STATE_ENABLED)
                setPlugged(if (wifiOff) BatteryManager.BATTERY_PLUGGED_USB else 0)
                DebugStayAwakePreferences(service).recordSession(true)
                if (action == null) DebugStayAwakeService.restoreIfEnabled(service)
                else DebugStayAwakeUpdateReceiver().onReceive(service, Intent(action))
                assertEquals(!wifiOff, DebugStayAwakePreferences(service).enabled)
                val next = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
                assertEquals(if (wifiOff) null else DebugStayAwakeService::class.java.name,
                    next?.component?.className)
            }
        }
    }

    @Test
    fun fullBatteryStillPluggedInAllowsStartup() {
        setPlugged(BatteryManager.BATTERY_PLUGGED_WIRELESS, BatteryManager.BATTERY_STATUS_FULL)
        start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun conditionsChangingDuringRequestCannotLeaveSessionRunning() {
        connectivity.beforeRequest = { setWifiState(WifiManager.WIFI_STATE_DISABLING) }
        start()
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertEquals(1, connectivity.unregisterCalls)
        assertEquals(0, shadowOf(service.getSystemService(WifiManager::class.java)).activeLockCount)
        assertEquals(0, conditionReceiverCount())
    }

    @Test
    fun stopDestroyAndFailedStartUnregisterConditionReceiver() {
        start()
        assertEquals(1, conditionReceiverCount())
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        assertEquals(0, conditionReceiverCount())
        start()
        controller.destroy()
        assertEquals(0, conditionReceiverCount())
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        connectivity.denyRequest = true
        start()
        assertEquals(0, conditionReceiverCount())
    }

    @Test
    fun diagnosticsRecordEachAutoDisableAndStartRefusalReason() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val field = AppDiagnostics::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null)
        val diagnostics = AppDiagnostics(app)
        field.set(null, diagnostics)
        try {
            diagnostics.clear()
            diagnostics.setEnabled(true)
            start()
            broadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
                .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLING))
            setWifiState(WifiManager.WIFI_STATE_DISABLED)
            DebugStayAwakeService.setEnabled(service, true)
            setWifiState(WifiManager.WIFI_STATE_UNKNOWN)
            DebugStayAwakeService.setEnabled(service, true)
            val records = JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
                .map { it.asJsonObject }
            assertEquals(listOf(1L), records.filter { it["event"].asString == "STAY_AWAKE_AUTO_DISABLED" }
                .map { it["metrics"].asJsonObject["REASON"].asLong })
            assertEquals(listOf(5L, 3L), records.filter { it["event"].asString == "STAY_AWAKE_START_REFUSED" }
                .map { it["metrics"].asJsonObject["REASON"].asLong })
        } finally {
            diagnostics.setEnabled(false)
            diagnostics.close()
            field.set(null, previous)
        }
    }

    @Test
    fun acceptedWifiEnableWaitsForRadioBeforeAcquiringLocksOnBattery() {
        setPlugged(0)
        setWifiState(WifiManager.WIFI_STATE_DISABLED)
        val wifi: StayAwakeWifiShadow = Shadow.extract(service.getSystemService(WifiManager::class.java))
        wifi.acceptEnable = true
        DebugStayAwakeService.setEnabled(service, true)
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertEquals(1, wifi.enableCalls)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        service.onStartCommand(intent, 0, 1)
        service.onStartCommand(intent, 0, 2)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
        assertEquals(0, wifi.activeLockCount)
        broadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        setWifiState(WifiManager.WIFI_STATE_ENABLED)
        broadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
        assertTrue(DebugStayAwakeService.active.value)
        assertEquals(1, connectivity.networkCallbacks.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DebugStayAwakeService.WIFI_ENABLE_TIMEOUT_MS))
        assertTrue(DebugStayAwakeService.active.value)
        assertEquals(1, wifi.enableCalls)
    }

    @Test
    fun enablingTimeoutClearsManualIntentAndNeverAcquiresLocks() {
        setWifiState(WifiManager.WIFI_STATE_ENABLING)
        start()
        shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(DebugStayAwakeService.WIFI_ENABLE_TIMEOUT_MS - 1))
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakeService.active.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertTrue(connectivity.requests.isEmpty())
        assertEquals(0, conditionReceiverCount())
        assertEquals(service.getString(R.string.debug_stay_awake_wifi_enable_failed),
            ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun enablingFailureAndPermissionDenialClearIntent() {
        setWifiState(WifiManager.WIFI_STATE_ENABLING)
        start()
        broadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED))
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertTrue(connectivity.requests.isEmpty())
        setWifiState(WifiManager.WIFI_STATE_DISABLED)
        val wifi: StayAwakeWifiShadow = Shadow.extract(service.getSystemService(WifiManager::class.java))
        wifi.denyEnable = true
        verifyRefusal(R.string.debug_stay_awake_wifi_enable_failed)
    }

    @Test
    fun explicitOffDuringWifiEnablePreventsLateActivation() {
        setWifiState(WifiManager.WIFI_STATE_ENABLING)
        start()
        DebugStayAwakeService.setEnabled(service, false)
        service.onDestroy()
        setWifiState(WifiManager.WIFI_STATE_ENABLED)
        broadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DebugStayAwakeService.WIFI_ENABLE_TIMEOUT_MS))
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun notificationStopDuringWifiEnableCancelsDeadlineAndBothChoices() {
        setWifiState(WifiManager.WIFI_STATE_ENABLING)
        DebugStayAwakePreferences(service).setChargingEnabled(true)
        start()
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakePreferences(service).chargingEnabled)
        assertEquals(0, conditionReceiverCount())
        setWifiState(WifiManager.WIFI_STATE_ENABLED)
        broadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DebugStayAwakeService.WIFI_ENABLE_TIMEOUT_MS))
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun pendingWifiEnableSurvivesProcessRecreationWithoutResettingSavedChoice() {
        setWifiState(WifiManager.WIFI_STATE_ENABLING)
        start()
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DebugStayAwakeService.WIFI_ENABLE_TIMEOUT_MS))
        assertTrue(DebugStayAwakePreferences(service).enabled)
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        setWifiState(WifiManager.WIFI_STATE_ENABLED)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun unavailableCallbackFromStoppedSessionCannotDisableReplacementSession() {
        start()
        val old = connectivity.networkCallbacks.single()
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        start()
        old.onUnavailable()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun unavailableNetworkReleasesDemandAndReportsFailure() {
        start()
        connectivity.networkCallbacks.single().onUnavailable()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertEquals(service.getString(R.string.debug_stay_awake_requires_wifi),
            ShadowToast.getTextOfLatestToast())
    }

    @Implements(WifiManager::class)
    class StayAwakeWifiShadow : ShadowWifiManager() {
        var acceptEnable = false
        var denyEnable = false
        var enableCalls = 0

        @Implementation
        public override fun setWifiEnabled(enabled: Boolean): Boolean {
            enableCalls++
            if (denyEnable) throw SecurityException("Wi-Fi activation denied")
            if (!acceptEnable) return false
            setWifiState(WifiManager.WIFI_STATE_ENABLING)
            return true
        }
    }

    @Implements(ConnectivityManager::class)
    class StayAwakeConnectivityShadow : ShadowConnectivityManager() {
        val requests = mutableListOf<NetworkRequest>()
        var denyRequest = false
        var alreadyReleased = false
        var unregisterCalls = 0
        var beforeRequest: (() -> Unit)? = null

        @Implementation
        public override fun requestNetwork(
            request: NetworkRequest,
            callback: ConnectivityManager.NetworkCallback
        ) {
            beforeRequest?.invoke()
            if (denyRequest) throw SecurityException("Wi-Fi request denied")
            requests.add(request)
            super.requestNetwork(request, callback)
        }

        @Implementation
        public override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            unregisterCalls++
            super.unregisterNetworkCallback(callback)
            if (alreadyReleased) throw IllegalArgumentException("Request already released")
        }
    }

    private fun start() {
        DebugStayAwakePreferences(service).recordSession(true)
        service.onStartCommand(Intent(service, DebugStayAwakeService::class.java), 0, 1)
    }

    private fun legacyOptIn(value: Boolean) {
        service.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE)
            .edit().putBoolean("resume_after_update", value).commit()
    }

    private fun setWirelessDebugging(enabled: Boolean) {
        Settings.Global.putInt(
            RuntimeEnvironment.getApplication().contentResolver,
            "adb_wifi_enabled",
            if (enabled) 1 else 0
        )
    }

    private fun setWifiState(state: Int) {
        shadowOf(service.getSystemService(WifiManager::class.java)).setWifiState(state)
    }

    private fun setPlugged(plugged: Int, status: Int = BatteryManager.BATTERY_STATUS_CHARGING) {
        service.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            .putExtra(BatteryManager.EXTRA_STATUS, status))
    }

    private fun broadcast(intent: Intent) {
        service.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun conditionReceiverCount(): Int =
        shadowOf(RuntimeEnvironment.getApplication()).registeredReceivers.count {
            it.broadcastReceiver.javaClass.enclosingClass == DebugStayAwakeService::class.java
        }

    private inline fun <reified T> lock(name: String): T {
        val field = DebugStayAwakeService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(service) as T
    }
}
