package com.aitech.weartak_civ.service

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    shadows = [RoutineServiceNotificationTest.ForegroundShadow::class])
class RoutineServiceNotificationTest {
    private lateinit var manager: NotificationManager
    private val services = mutableListOf<Service>()

    @Before
    fun setUp() {
        ForegroundShadow.denied = null
        ForegroundShadow.refused = null
        manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        RoutineServiceNotification.resetForTest()
        RoutineServiceNotification.reconcileProcessStart(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        ForegroundShadow.denied = null
        services.forEach { RoutineServiceNotification.stop(it) }
        manager.cancelAll()
    }

    @Test
    fun sharedIdKeepsEveryServiceForegroundAndLastOwnerRemovesCard() {
        val chat = service(ChatService::class.java)
        val tak = service(TAKConnectService::class.java)
        chat.startForegroundServiceCompat(101, importance = NotificationManager.IMPORTANCE_LOW)
        tak.startForegroundServiceCompat(101, importance = NotificationManager.IMPORTANCE_LOW)
        assertEquals(RoutineServiceNotification.ID, shadowOf(chat).lastForegroundNotificationId)
        assertEquals(RoutineServiceNotification.ID, shadowOf(tak).lastForegroundNotificationId)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("WearTAK active", card().extras.getString(Notification.EXTRA_TITLE))
        assertTrue(card().extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains("TAK transport"))
        assertNotNull(card().contentIntent)
        assertEquals(Notification.CATEGORY_SERVICE, card().category)
        assertTrue(card().flags and Notification.FLAG_ONGOING_EVENT != 0)
        chat.stopForegroundServiceCompat()
        assertEquals("1 background service running", card().extras.getString(Notification.EXTRA_TEXT))
        assertFalse(card().extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains("Chat"))
        tak.stopForegroundServiceCompat()
        assertTrue(manager.activeNotifications.isEmpty())
        tak.stopForegroundServiceCompat()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun repeatedStartIsIdempotentAndOldInstanceStopCannotRemoveReplacement() {
        val first = service(ChatService::class.java)
        val replacement = service(ChatService::class.java)
        RoutineServiceNotification.start(first, R.string.notification_chat)
        RoutineServiceNotification.start(first, R.string.notification_chat)
        assertEquals("1 background service running", card().extras.getString(Notification.EXTRA_TEXT))
        RoutineServiceNotification.start(replacement, R.string.notification_chat)
        RoutineServiceNotification.stop(first)
        RoutineServiceNotification.stop(first)
        assertEquals("1 background service running", card().extras.getString(Notification.EXTRA_TEXT))
        RoutineServiceNotification.reconcileProcessStart(first)
        assertEquals(1, manager.activeNotifications.size)
        RoutineServiceNotification.stop(replacement)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun failedForegroundPromotionPropagatesAndRollsBackOnlyFailedOwner() {
        val good = service(ChatService::class.java)
        val denied = service(TAKConnectService::class.java)
        RoutineServiceNotification.start(good, R.string.notification_chat)
        ForegroundShadow.denied = denied
        assertThrows(SecurityException::class.java) {
            RoutineServiceNotification.start(denied, R.string.notification_tak_transport)
        }
        assertEquals("1 background service running", card().extras.getString(Notification.EXTRA_TEXT))
        assertFalse(card().extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains("TAK transport"))
        RoutineServiceNotification.stop(denied)
        assertEquals(1, manager.activeNotifications.size)
        RoutineServiceNotification.stop(good)
        assertTrue(manager.activeNotifications.isEmpty())
        assertThrows(SecurityException::class.java) {
            RoutineServiceNotification.start(denied, R.string.notification_tak_transport)
        }
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun concurrentMembershipChangesDoNotLoseSurvivingOwner() {
        val anchor = service(ChatService::class.java)
        RoutineServiceNotification.start(anchor, R.string.notification_chat)
        val workers = List(24) { service(TAKConnectService::class.java) }
        val executor = Executors.newFixedThreadPool(6)
        try {
            executor.invokeAll(workers.map { worker ->
                Callable {
                    repeat(5) {
                        RoutineServiceNotification.start(worker, R.string.notification_tak_transport)
                        RoutineServiceNotification.stop(worker)
                    }
                }
            }).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("1 background service running", card().extras.getString(Notification.EXTRA_TEXT))
        RoutineServiceNotification.stop(anchor)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun refusedForegroundStartStopsServiceWithoutCrashingAndKeepsOtherOwners() {
        val good = service(ChatService::class.java)
        good.startForegroundServiceCompat(101, importance = NotificationManager.IMPORTANCE_LOW)
        val activity = service(ActivityRecognitionService::class.java)
        ForegroundShadow.refused = activity
        assertFalse(activity.startForegroundServiceCompat(192, importance = NotificationManager.IMPORTANCE_LOW))
        assertTrue(shadowOf(activity).isStoppedBySelf)
        assertEquals("1 background service running", card().extras.getString(Notification.EXTRA_TEXT))
        assertFalse(card().extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains("Activity"))

        val dedicated = service(AudioCaptureService::class.java)
        ForegroundShadow.refused = dedicated
        assertFalse(dedicated.startForegroundServiceCompat(201, importance = NotificationManager.IMPORTANCE_LOW))
        assertTrue(shadowOf(dedicated).isStoppedBySelf)

        ForegroundShadow.refused = null
        val retried = service(ActivityRecognitionService::class.java)
        assertTrue(retried.startForegroundServiceCompat(192, importance = NotificationManager.IMPORTANCE_LOW))
        assertFalse(shadowOf(retried).isStoppedBySelf)
        assertEquals("2 background services running", card().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun startupRemovesOnlyKnownLegacyRoutineCardsAndRebuildsFromNewOwners() {
        post(101, "ChatService_channel")
        post(102, "BatteryService_channel")
        post(919, "GunshotOnnxService_channel")
        post(999, "test_alert_channel")
        post(201, "AudioCaptureService_channel")
        post(1167, "beacon-ref-notification-id")
        post(7130, "chat_commands")
        post(8123, "cep_sim_channel")
        post(64001, "debug_stay_awake")
        post(RoutineServiceNotification.ID, RoutineServiceNotification.CHANNEL_ID)
        val context = RuntimeEnvironment.getApplication()
        RoutineServiceNotification.reconcileProcessStart(context)
        assertEquals(setOf(999, 201, 1167, 7130, 8123, 64001),
            manager.activeNotifications.map { it.id }.toSet())
        manager.cancel(999)
        post(999, "CompanionAppService_channel")
        RoutineServiceNotification.reconcileProcessStart(context)
        assertNull(shadowOf(manager).getNotification(999))
        val fresh = service(ChatService::class.java)
        RoutineServiceNotification.start(fresh, R.string.notification_chat)
        assertEquals("1 background service running", card().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun dedicatedMicrophoneAndActionableIdsRemainIndependent() {
        val microphone = service(AudioCaptureService::class.java)
        microphone.startForegroundServiceCompat(201, importance = NotificationManager.IMPORTANCE_LOW)
        post(RoutineServiceNotification.SIMULATION_ALERT_ID, "test_alert_channel")
        val routine = service(ChatService::class.java)
        routine.startForegroundServiceCompat(101, importance = NotificationManager.IMPORTANCE_LOW)
        assertEquals(201, shadowOf(microphone).lastForegroundNotificationId)
        routine.stopForegroundServiceCompat()
        assertNotNull(shadowOf(manager).getNotification(201))
        assertNotNull(shadowOf(manager).getNotification(RoutineServiceNotification.SIMULATION_ALERT_ID))
        microphone.stopForegroundServiceCompat()
        assertNull(shadowOf(manager).getNotification(201))
        assertEquals(1, manager.activeNotifications.size)
        val reserved = listOf(RoutineServiceNotification.ID, RoutineServiceNotification.SIMULATION_ALERT_ID,
            64001, 201, 1167, 7130, 8123)
        assertEquals(reserved.size, reserved.toSet().size)
    }

    private fun card() = shadowOf(manager).getNotification(RoutineServiceNotification.ID)!!

    private fun post(id: Int, channel: String) {
        manager.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_LOW))
        manager.notify(id, Notification.Builder(RuntimeEnvironment.getApplication(), channel)
            .setSmallIcon(R.drawable.weartak_logo).build())
    }

    private fun <T : Service> service(type: Class<T>): T =
        Robolectric.buildService(type).create().get().also { services.add(it) }

    open class ChatService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }
    class TAKConnectService : ChatService()
    class AudioCaptureService : ChatService()
    class ActivityRecognitionService : ChatService()

    @Implements(Service::class)
    class ForegroundShadow : ShadowService() {
        @org.robolectric.annotation.RealObject
        private lateinit var service: Service

        @Implementation
        override fun startForeground(id: Int, notification: Notification) {
            if (service === denied) throw SecurityException("foreground permission denied")
            if (service === refused) throw android.app.ForegroundServiceStartNotAllowedException(
                "startForeground() not allowed due to mAllowStartForeground false")
            super.startForeground(id, notification)
        }

        companion object {
            @Volatile var denied: Service? = null
            @Volatile var refused: Service? = null
        }
    }
}
