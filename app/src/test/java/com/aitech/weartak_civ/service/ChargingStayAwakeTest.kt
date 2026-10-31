package com.aitech.weartak_civ.service

import android.app.Application
import android.Manifest
import android.provider.Settings
import android.app.Service
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.*
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
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    shadows = [DebugStayAwakeServiceTest.StayAwakeConnectivityShadow::class,
        DebugStayAwakeServiceTest.StayAwakeWifiShadow::class,
        ChargingStayAwakeTest.ChargingPowerShadow::class])
class ChargingStayAwakeTest {
    private lateinit var application: Application
    private lateinit var awake: ChargingStayAwake
    private lateinit var controller: ServiceController<DebugStayAwakeService>
    private lateinit var service: DebugStayAwakeService
    private lateinit var context: ContextWrapper
    private lateinit var preferences: DebugStayAwakePreferences
    private lateinit var connectivity: DebugStayAwakeServiceTest.StayAwakeConnectivityShadow
    private var starts = 0

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE).edit().clear().commit()
        preferences = DebugStayAwakePreferences(application)
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        connectivity = Shadow.extract(service.getSystemService(ConnectivityManager::class.java))
        shadowOf(service.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        // Model Android's service delivery without starting the rest of WearTAK.
        context = object : ContextWrapper(application) {
            override fun startForegroundService(intent: Intent): ComponentName? {
                starts++
                service.onStartCommand(intent, 0, starts)
                return intent.component
            }
            override fun stopService(intent: Intent): Boolean {
                if (DebugStayAwakeService.active.value) service.onDestroy()
                return true
            }
        }
        awake = ChargingStayAwake(context)
        ReactiveServiceManager.isRunning.set(true)
    }

    @After
    fun tearDown() {
        awake.stop()
        controller.destroy()
        ReactiveServiceManager.isRunning.set(false)
        DiagnosticResources.stop()
    }

    @Test
    fun defaultOffAcquiresNothingEvenWhenPlugged() {
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        idle()
        assertFalse(preferences.chargingEnabled)
        assertFalse(DebugStayAwakeService.active.value)
        assertEquals(0, starts)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun combinedOptInWithoutPermissionKeepsAwakeWithoutSettingsWrites() {
        shadowOf(application).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(application.contentResolver, "adb_enabled", "0")
        Settings.Global.putString(application.contentResolver, "adb_wifi_enabled", "0")
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        idle()
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_enabled"))
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
        assertTrue(preferences.chargingEnabled)
        assertAllHeld()
        assertEquals(1, starts)
        assertEquals(1, connectivity.networkCallbacks.size)
        battery(0)
        idle()
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun combinedOptInHoldsChargingLocksEnablesDebuggingAndUnplugRestores() {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(application.contentResolver, "adb_enabled", "0")
        Settings.Global.putString(application.contentResolver, "adb_wifi_enabled", "0")
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        idle()
        assertTrue(preferences.chargingEnabled)
        assertFalse(preferences.enabled)
        assertAllHeld()
        assertEquals(1, connectivity.networkCallbacks.size)
        assertEquals("1", Settings.Global.getString(application.contentResolver, "adb_enabled"))
        assertEquals("1", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
        battery(0)
        idle()
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_enabled"))
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
        assertTrue(preferences.chargingEnabled)
    }

    @Test
    fun combinedToggleOffRestoresButDoesNotStopIndependentManualDemand() {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(application.contentResolver, "adb_wifi_enabled", "0")
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        preferences.setChargingEnabled(true)
        idle()
        assertAllHeld()
        DebugStayAwakeService.setEnabled(context, true)
        ChargingStayAwake.setEnabled(context, false)
        idle()
        assertAllHeld()
        assertTrue(preferences.enabled)
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
    }

    @Test
    fun combinedUnknownPowerAndManagerStopRestoreAndReleaseWifi() {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(application.contentResolver, "adb_wifi_enabled", "0")
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        idle()
        application.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        idle()
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        idle()
        assertAllHeld()
        awake.stop()
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertTrue(preferences.chargingEnabled)
    }

    @Test
    fun notificationStopDisablesCombinedChoiceAndRestoresWithoutRestartingOnPower() {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(application.contentResolver, "adb_wifi_enabled", "0")
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        idle()
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        idle()
        assertFalse(preferences.chargingEnabled)
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
        assertFalse(DebugStayAwakeService.active.value)
        application.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        idle()
        assertEquals(1, starts)
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun savedOptInStartsWhenAlreadyPluggedIncludingFullBattery() {
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_WIRELESS, BatteryManager.BATTERY_STATUS_FULL)
        awake.start()
        idle()
        assertTrue(DebugStayAwakeService.active.value)
        assertAllHeld()
        val power: ChargingPowerShadow = Shadow.extract(service.getSystemService(PowerManager::class.java))
        assertEquals(PowerManager.SCREEN_DIM_WAKE_LOCK,
            power.levels["WearTAK-DebugStayAwake:screen"])
        assertEquals(1, connectivity.networkCallbacks.size)
        assertFalse(preferences.enabled)
    }

    @Test
    fun enabledUnpluggedDoesNothingUntilPowerConnectedAndUnplugReleasesAll() {
        preferences.setChargingEnabled(true)
        battery(0)
        awake.start()
        idle()
        assertEquals(0, starts)
        // A connected signal alone cannot override the sticky plugged source of truth.
        application.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        idle()
        assertEquals(0, starts)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        application.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        idle()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertAllHeld()
        application.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        idle()
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertTrue(preferences.chargingEnabled)
        assertFalse(preferences.enabled)
        // A restore queued before disconnect must not reacquire against stale sticky power.
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent().setAction(DebugStayAwakeService.ACTION_RESTORE), 0, 3))
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun toggleOnWhilePluggedStartsAndToggleOffStopsWithoutLosingManualChoice() {
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        preferences.setChargingEnabled(true)
        idle()
        assertAllHeld()
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        preferences.setChargingEnabled(false)
        idle()
        assertFalse(wifi.isHeld)
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(DebugStayAwakePreferences(application).chargingEnabled)
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun manualOnWinsAndChargingToggleOffCannotStopItWhilePlugged() {
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        preferences.setChargingEnabled(true)
        awake.start()
        idle()
        DebugStayAwakeService.setEnabled(context, true)
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        preferences.setChargingEnabled(false)
        idle()
        assertTrue(preferences.enabled)
        assertTrue(DebugStayAwakeService.active.value)
        assertSame(wifi, lock<WifiManager.WifiLock>("wifiLock"))
        assertAllHeld()
        awake.stop()
        assertAllHeld()
    }

    @Test
    fun unplugPreservesManualModeAndRestoresChargingDebugging() {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(application.contentResolver, "adb_wifi_enabled", "0")
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        preferences.setChargingEnabled(true)
        awake.start()
        idle()
        DebugStayAwakeService.setEnabled(context, true)
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        val power: ChargingPowerShadow = Shadow.extract(service.getSystemService(PowerManager::class.java))
        assertEquals(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            power.levels["WearTAK-DebugStayAwake:screen"])
        battery(0)
        application.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        idle()
        assertTrue(preferences.enabled)
        assertTrue(preferences.chargingEnabled)
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(wifi.isHeld)
        assertEquals(1, connectivity.networkCallbacks.size)
        assertEquals("0", Settings.Global.getString(application.contentResolver, "adb_wifi_enabled"))
        DebugStayAwakeService.setEnabled(context, false)
        idle()
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun turningManualOffFallsBackToChargingOnlyWhilePlugged() {
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        preferences.setChargingEnabled(true)
        awake.start()
        DebugStayAwakeService.setEnabled(context, true)
        idle()
        DebugStayAwakeService.setEnabled(context, false)
        assertFalse(preferences.enabled)
        assertAllHeld()
        val power: ChargingPowerShadow = Shadow.extract(service.getSystemService(PowerManager::class.java))
        assertEquals(PowerManager.SCREEN_DIM_WAKE_LOCK,
            power.levels["WearTAK-DebugStayAwake:screen"])
        battery(0)
        idle()
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(preferences.chargingEnabled)
    }

    @Test
    fun unknownPowerFailsClosedAndStopsExistingChargingSession() {
        preferences.setChargingEnabled(true)
        application.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        awake.start()
        idle()
        assertEquals(0, starts)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        idle()
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        application.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        idle()
        assertFalse(wifi.isHeld)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(preferences.chargingEnabled)
    }

    @Test
    fun persistedOptInSurvivesMonitorRestartAndManagerStopReleasesLocks() {
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        preferences.setChargingEnabled(true)
        awake.start()
        idle()
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        awake.stop()
        assertFalse(wifi.isHeld)
        assertTrue(DebugStayAwakePreferences(application).chargingEnabled)
        assertFalse(DebugStayAwakeService.active.value)
        awake = ChargingStayAwake(context)
        awake.start()
        idle()
        assertAllHeld()
        assertEquals(2, starts)
    }

    @Test
    fun noManagerMeansNoAcquisitionAndQueuedStartAfterOffIsRejected() {
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        ReactiveServiceManager.isRunning.set(false)
        DebugStayAwakeService.restoreIfEnabled(context)
        assertEquals(0, starts)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        ReactiveServiceManager.isRunning.set(true)
        preferences.setChargingEnabled(false)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent().setAction(DebugStayAwakeService.ACTION_RESTORE), 0, 2))
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun notificationStopDisablesBothChoicesAndCannotRestartOnPower() {
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        idle()
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        idle()
        assertFalse(preferences.chargingEnabled)
        assertFalse(preferences.enabled)
        assertFalse(DebugStayAwakeService.active.value)
        application.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        idle()
        assertEquals(1, starts)
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun bootOrUpdatePreservesChargingChoiceWithoutStartingWearTakAndStartupRestoresIt() {
        preferences.setChargingEnabled(true)
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        ReactiveServiceManager.isRunning.set(false)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            DebugStayAwakeUpdateReceiver().onReceive(context, Intent(action))
            assertEquals(0, starts)
            assertTrue(DebugStayAwakePreferences(application).chargingEnabled)
        }
        ReactiveServiceManager.isRunning.set(true)
        awake.start()
        idle()
        assertAllHeld()
    }

    @Test
    fun deniedManualEnableDoesNotEraseChargingOptIn() {
        preferences.setChargingEnabled(true)
        shadowOf(service.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        DebugStayAwakeService.setEnabled(context, true)
        assertFalse(preferences.enabled)
        assertTrue(preferences.chargingEnabled)
        assertEquals(0, starts)
    }

    @Test
    fun chargingModeNeverRequestsRadioActivationAndManualCanStartOnBattery() {
        preferences.setChargingEnabled(true)
        shadowOf(service.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        val wifi: DebugStayAwakeServiceTest.StayAwakeWifiShadow =
            Shadow.extract(service.getSystemService(WifiManager::class.java))
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        awake.start()
        idle()
        assertFalse(DebugStayAwakeService.active.value)
        assertEquals(0, wifi.enableCalls)
        assertTrue(preferences.chargingEnabled)
        battery(0)
        shadowOf(service.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        DebugStayAwakeService.setEnabled(context, true)
        idle()
        assertTrue(preferences.enabled)
        assertAllHeld()
        awake.stop()
        assertAllHeld()
        DebugStayAwakeService.setEnabled(context, false)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(preferences.chargingEnabled)
    }

    private fun battery(plugged: Int, status: Int = BatteryManager.BATTERY_STATUS_CHARGING) {
        application.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            .putExtra(BatteryManager.EXTRA_STATUS, status))
    }

    private fun assertAllHeld() {
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertTrue(lock<WifiManager.WifiLock>("wifiLock").isHeld)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Implements(PowerManager::class)
    class ChargingPowerShadow : ShadowPowerManager() {
        val levels = mutableMapOf<String, Int>()

        @Implementation
        public override fun newWakeLock(levelAndFlags: Int, tag: String): PowerManager.WakeLock {
            levels[tag] = levelAndFlags
            return super.newWakeLock(levelAndFlags, tag)
        }
    }

    private inline fun <reified T> lock(name: String): T =
        DebugStayAwakeService::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(service) as T
}
