package com.aitech.weartak_civ.service.openvpn

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.OpenVpnMainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OpenVpnForegroundServiceTest {
    @Test
    fun stoppedDisconnectDoesNotStartRuntimeServiceOrPostForegroundNotification() {
        val activity = launchActivity()
        try {
            OpenVpnService.disconnect()

            assertFalse(isRuntimeServiceRunning(activity))
            assertNoRuntimeNotification(activity)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun inactiveErrorDisconnectDoesNotRestartServiceOrClearFailure() {
        val activity = launchActivity()
        try {
            OpenVpnService.connect(activity, "missing-${UUID.randomUUID()}")
            await("failed connect did not stop in ERROR state") {
                OpenVpnService.currentState().status == OpenVpnStatus.ERROR &&
                    !isRuntimeServiceRunning(activity)
            }

            val errorState = OpenVpnService.currentState()
            assertNotNull(errorState.error)
            assertEquals(OpenVpnNativeBridge.State.STOPPED, OpenVpnNativeBridge.getState())
            assertNoRuntimeNotification(activity)

            OpenVpnService.disconnect()

            assertEquals(errorState, OpenVpnService.currentState())
            assertEquals(OpenVpnNativeBridge.State.STOPPED, OpenVpnNativeBridge.getState())
            assertFalse(isRuntimeServiceRunning(activity))
            assertNoRuntimeNotification(activity)
        } finally {
            activity.finish()
        }
    }

    private fun launchActivity(): Activity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        return instrumentation.startActivitySync(
            Intent(context, OpenVpnMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Suppress("DEPRECATION")
    private fun isRuntimeServiceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == OpenVpnService::class.java.name }

    private fun assertNoRuntimeNotification(activity: Activity) {
        assertFalse(
            activity.getSystemService(NotificationManager::class.java)
                .activeNotifications.any { it.notification.channelId == "openvpn_service" },
        )
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }
}
