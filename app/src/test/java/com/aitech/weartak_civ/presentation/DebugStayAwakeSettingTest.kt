package com.aitech.weartak_civ.presentation

import android.app.Application
import android.Manifest
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.service.DebugStayAwakePreferences
import com.aitech.weartak_civ.service.DebugStayAwakeService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round",
    shadows = [com.aitech.weartak_civ.service.DebugStayAwakeServiceTest.StayAwakeWifiShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugStayAwakeSettingTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun resetPreference() {
        app.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB))
    }

    @Test fun smallRoundHasTopTogglesAndPendingStatus() = verifyTopSplitChip()

    @Test fun chargingToggleDefaultsOffAndPersistsIndependently() {
        compose.setContent { ChargingDeveloperSetting() }
        compose.onNodeWithText("While charging").assertIsOff().performClick()
        compose.waitUntil { DebugStayAwakePreferences(app).chargingEnabled }
        compose.onNodeWithText("While charging").assertIsOn()
        compose.runOnIdle {
            assertFalse(DebugStayAwakePreferences(app).enabled)
            assertNull(shadowOf(app).nextStartedService)
        }

        compose.onNodeWithText("While charging").performClick()
        compose.waitUntil { !DebugStayAwakePreferences(app).chargingEnabled }
        compose.onNodeWithText("While charging").assertIsOff()
    }

    @Test fun missingPermissionPreservesEnabledChoiceAndShowsGrantSubtitle() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        DebugStayAwakePreferences(app).setChargingEnabled(true)
        compose.setContent { ChargingDeveloperSetting() }
        compose.onNodeWithText("While charging").assertIsOn().assertIsEnabled()
        compose.onNodeWithText("Wireless debugging needs adb grant").assertExists()
        compose.onNodeWithText(
            "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
            substring = true
        ).assertExists()
        compose.runOnIdle { assertTrue(DebugStayAwakePreferences(app).chargingEnabled) }
        compose.onNodeWithText("While charging").performClick()
        compose.waitUntil { !DebugStayAwakePreferences(app).chargingEnabled }
        compose.onNodeWithText("While charging").assertIsOff().performClick()
        compose.waitUntil { DebugStayAwakePreferences(app).chargingEnabled }
        compose.onNodeWithText("While charging").assertIsOn()
    }

    @Test fun wirelessGrantedDefaultsOffAndPersistsIndependently() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        compose.setContent { ChargingDeveloperSetting() }
        compose.onNodeWithText("While charging").assertIsOff().assertIsEnabled().performClick()
        compose.waitUntil { DebugStayAwakePreferences(app).chargingEnabled }
        compose.onNodeWithText("While charging").assertIsOn()
        compose.onNodeWithText("Stay awake and wireless debugging").assertExists()
        compose.runOnIdle {
            assertFalse(DebugStayAwakePreferences(app).enabled)
            assertTrue(DebugStayAwakePreferences(app).chargingEnabled)
        }
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundHasTopTogglesAndPendingStatus() = verifyTopSplitChip()

    @Test fun loggingLabelsResolveInEverySupportedTranslation() {
        val labels = mapOf(
            "en" to ("Logging" to "Logs"),
            "ar" to ("التسجيل" to "السجلات"),
            "de" to ("Protokollierung" to "Protokolle"),
            "es" to ("Registro" to "Registros"),
            "fr" to ("Journalisation" to "Journaux"),
            "ja" to ("ログ記録" to "ログ"),
            "ko" to ("로깅" to "로그"),
            "tr" to ("Günlük kaydı" to "Günlükler"),
            "vi" to ("Ghi nhật ký" to "Nhật ký"),
            "zh" to ("日志记录" to "日志")
        )
        labels.forEach { (language, expected) ->
            val configuration = android.content.res.Configuration(app.resources.configuration)
            configuration.setLocale(java.util.Locale.forLanguageTag(language))
            val context = app.createConfigurationContext(configuration)
            assertEquals(language, expected.first, context.getString(com.aitech.weartak_civ.R.string.app_diagnostics))
            assertEquals(language, expected.second, context.getString(com.aitech.weartak_civ.R.string.app_diagnostics_logs))
        }
    }

    @Test fun diagnosticsToggleAndLogActionsRemainDistinctFromStayAwake() {
        var enabled by mutableStateOf(false)
        var changes = 0
        var openedLogs = 0
        compose.setContent {
            DebugInformationPage(
                DebugDisplayInfo("test", 1, "debug", "test", "test", "WIFI", "test", "N/A", 8000),
                onBack = {}, onShowQrCode = {}, onShowLocationDiagnostics = {},
                onShowDiagnostics = { openedLogs++ }, diagnosticsEnabled = enabled,
                onDiagnosticsEnabledChange = { enabled = it; changes++ }
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Logging"))
        compose.onNodeWithText("Logging").assertIsOff().performClick()
        compose.onNodeWithText("Logging").assertIsOn()
        compose.runOnIdle {
            assertTrue(enabled)
            assertEquals(1, changes)
            assertFalse(DebugStayAwakePreferences(app).enabled)
        }
        compose.onNodeWithText(app.getString(com.aitech.weartak_civ.R.string.app_diagnostics_description))
            .assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Logs"))
        compose.onNodeWithText("Logs").performClick()
        compose.runOnIdle {
            assertEquals(1, openedLogs)
            assertEquals(1, changes)
        }
    }

    private fun verifyTopSplitChip() {
        compose.setContent {
            DebugInformationPage(
                DebugDisplayInfo("test", 1, "debug", "test", "test", "WIFI", "test", "N/A", 8000),
                onBack = {}, onShowQrCode = {}, onShowLocationDiagnostics = {}
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Stay awake"))
        compose.onNodeWithText("Stay awake").assertIsDisplayed()
        val stayAwake = compose.onNodeWithText("Stay awake")
        stayAwake.assertIsOff()
        compose.onNodeWithText("Resume after update").assertDoesNotExist()
        stayAwake.performClick()
        compose.waitUntil { DebugStayAwakePreferences(app).enabled }
        stayAwake.assertIsOn()
        compose.onNodeWithText("Enabled, not running").assertExists()
        val start = shadowOf(app).nextStartedService
        assertEquals(DebugStayAwakeService::class.java.name, start?.component?.className)
        assertEquals(DebugStayAwakeService.ACTION_RESTORE, start?.action)
        assertNull(shadowOf(app).nextStartedService)
        stayAwake.performClick()
        compose.waitUntil { !DebugStayAwakePreferences(app).enabled }
        stayAwake.assertIsOff()
        val stopped = shadowOf(app).nextStoppedService
        assertEquals(DebugStayAwakeService::class.java.name, stopped?.component?.className)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("While charging"))
        compose.onNodeWithText("While charging").assertIsDisplayed().assertIsOff()
        compose.onNodeWithText("Stay awake when charging").assertDoesNotExist()
        compose.onNodeWithText("Wireless debugging when charging").assertDoesNotExist()
    }

    @Test fun savedOnIsNotShownAsOffWhenAndroidHasNotStartedService() {
        DebugStayAwakePreferences(app).recordSession(true)
        compose.setContent { DebugStayAwakeSetting() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Enabled, not running").assertExists()
        compose.onNodeWithText(
            "Keeps screen, CPU and Wi-Fi awake when Android allows. Your choice survives updates and restart until you turn it off. Cannot unlock the watch or keep it awake while powered off."
        ).assertDoesNotExist()
        compose.runOnIdle { assertTrue(DebugStayAwakePreferences(app).enabled) }
        compose.onNodeWithText("Extra battery use. May heat watch.").assertDoesNotExist()
    }

    @Test fun externalStopUpdatesSwitchWithoutReopeningPage() {
        DebugStayAwakePreferences(app).recordSession(true)
        compose.setContent { DebugStayAwakeSetting() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { DebugStayAwakeService.stop(app) }
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Off").assertDoesNotExist()
    }

    @Test fun refusedEnableKeepsSwitchOffAndExplainsWhy() {
        shadowOf(app.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        compose.setContent { DebugStayAwakeSetting() }
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertIsOff()
        compose.runOnIdle {
            assertFalse(DebugStayAwakePreferences(app).enabled)
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(app.getString(com.aitech.weartak_civ.R.string.debug_stay_awake_wifi_enable_failed),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun unpluggedEnableTurnsSwitchOnAndRequestsService() {
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        compose.setContent { DebugStayAwakeSetting() }
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle {
            assertTrue(DebugStayAwakePreferences(app).enabled)
            assertEquals(DebugStayAwakeService::class.java.name,
                shadowOf(app).nextStartedService?.component?.className)
        }
    }

    @Test fun radioEnablingShowsPendingStatusUntilReadyAndUnplugKeepsSwitchOn() {
        shadowOf(app.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_ENABLING)
        val controller = org.robolectric.Robolectric.buildService(DebugStayAwakeService::class.java).create()
        try {
            compose.setContent { DebugStayAwakeSetting() }
            compose.onNode(isToggleable()).performClick()
            compose.runOnIdle {
                controller.get().onStartCommand(shadowOf(app).nextStartedService, 0, 1)
                assertFalse(DebugStayAwakeService.active.value)
            }
            compose.onNodeWithText("Enabled, not running").assertExists()
            compose.runOnIdle {
                shadowOf(app.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_ENABLED)
                app.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
            }
            compose.waitForIdle()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText("Enabled, not running").assertDoesNotExist()
            compose.runOnIdle { app.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED)) }
            compose.waitForIdle()
            compose.onNode(isToggleable()).assertIsOn()
            compose.runOnIdle { assertTrue(DebugStayAwakeService.active.value) }
        } finally {
            controller.destroy()
        }
    }

    @Test fun autoDisableUpdatesVisibleSwitchWithoutReopeningPage() {
        DebugStayAwakePreferences(app).recordSession(true)
        val controller = org.robolectric.Robolectric.buildService(DebugStayAwakeService::class.java).create()
        try {
            compose.runOnIdle { controller.get().onStartCommand(null, 0, 1) }
            compose.setContent { DebugStayAwakeSetting() }
            compose.onNode(isToggleable()).assertIsOn()
            compose.runOnIdle {
                app.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED))
            }
            compose.waitForIdle()
            compose.onNode(isToggleable()).assertIsOff()
            compose.runOnIdle {
                assertFalse(DebugStayAwakePreferences(app).enabled)
                assertFalse(DebugStayAwakeService.active.value)
            }
        } finally {
            controller.destroy()
        }
    }
}
