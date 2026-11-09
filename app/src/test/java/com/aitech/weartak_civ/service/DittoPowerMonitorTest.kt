package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DittoPowerMonitorTest {
    @Test fun batteryWristChargingAlertingAndOsCallbacksAreEventDrivenAndCleanedUp() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val worn = MutableStateFlow<Boolean?>(null)
        val charging = MutableStateFlow(false)
        val battery = MutableStateFlow<Int?>(80)
        val threshold = MutableStateFlow(20)
        val alerting = MutableStateFlow(false)
        val stayPreference = MutableStateFlow(true)
        val repo = mockk<DataRepository> {
            every { latestDeviceWornObservation } returns worn
            every { chargingState } returns charging
            every { latestBatteryPercentage } returns battery
            every { getBatteryWarnPercentage } returns threshold
            every { isAlerting } returns alerting
            every { stayConnectedOffWrist } returns stayPreference
        }
        val states = mutableListOf<DittoPowerState>()
        val monitor = DittoPowerMonitor(context, repo, this, states::add)
        assertNull(monitor.initial(20).worn)
        assertTrue(monitor.initial(20).stayConnectedOffWrist)
        monitor.start()
        runCurrent()
        assertTrue(states.last().stayConnectedOffWrist)
        stayPreference.value = false
        runCurrent()
        assertFalse(states.last().stayConnectedOffWrist)
        worn.value = false
        battery.value = 19
        runCurrent()
        assertEquals(false, states.last().worn)
        assertEquals(19, states.last().battery)
        charging.value = true
        alerting.value = true
        threshold.value = 30
        runCurrent()
        assertTrue(states.last().charging)
        assertTrue(states.last().alerting)
        assertEquals(30, states.last().lowBatteryThreshold)
        Settings.Global.putInt(context.contentResolver, "bedtime_mode", 1)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertTrue(states.last().sleeping)
        Settings.Global.putInt(context.contentResolver, "bedtime_mode", 0)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsPowerSaveMode(true)
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertFalse(states.last().sleeping)
        assertTrue(states.last().powerSave)
        monitor.close()
        monitor.close()
        val count = states.size
        worn.value = true
        stayPreference.value = true
        Settings.Global.putInt(context.contentResolver, "bedtime_mode", 1)
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(count, states.size)
    }
}
