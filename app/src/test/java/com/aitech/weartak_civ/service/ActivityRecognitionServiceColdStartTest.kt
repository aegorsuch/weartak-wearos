package com.aitech.weartak_civ.service

import android.app.Application
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.HealthEvent
import com.aitech.weartak_civ.CAT_FALL_DETECTED
import com.aitech.weartak_civ.DESC_GOOGLE_SDK_FALL_DETECTED
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Tasks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.clearMocks
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * Regression for the p137 cold-start crash: the system can (re)start ActivityRecognitionService
 * before any MainActivity has initialized MainActivity.dataRepository. The service must use the
 * application-owned repository instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ActivityRecognitionServiceColdStartTest {
    private val repo = mockk<DataRepository>(relaxed = true)
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var service: ActivityRecognitionService
    private val monitoring = MutableStateFlow(true)

    @Before
    fun setUp() {
        clearMainActivityRepository()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }

        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.repo } returns repo
        every { repo.getEnablePhysiologicalServices } returns monitoring
        every { repo.getEnableActRecService } returns MutableStateFlow(true)
        every { repo.getEnableDynamicReportingStrategy } returns MutableStateFlow(true)
        every { repo.getEnableLocationService } returns MutableStateFlow(true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
        val base = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        service = ActivityRecognitionService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, base)
    }

    @After
    fun tearDown() {
        service.onDestroy()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        clearMainActivityRepository()
    }

    @Test
    fun passiveRequestsContainOnlyStepsFloorsAndElevation() {
        assertEquals(
            setOf(DataType.STEPS_DAILY, DataType.FLOORS_DAILY, DataType.ELEVATION_GAIN_DAILY),
            ActivityRecognitionService.PASSIVE_DAILY_DATA_TYPES
        )
        assertTrue(ActivityRecognitionService.PASSIVE_DAILY_DATA_TYPES.intersect(
            setOf(DataType.CALORIES_DAILY, DataType.CALORIES, DataType.CALORIES_TOTAL)
        ).isEmpty())
        assertEquals(
            setOf(DataType.STEPS_DAILY),
            ActivityRecognitionService.PASSIVE_DAILY_DATA_TYPES.intersect(
                setOf(DataType.STEPS_DAILY, DataType.CALORIES_DAILY)
            )
        )
    }

    @Test
    fun passiveCallbackPreservesStepsFloorsAndElevationAndIgnoresUnsolicitedCalories() {
        service.onCreate()
        val field = ActivityRecognitionService::class.java.getDeclaredField("passiveMonitoringEnabled")
            .apply { isAccessible = true }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!field.getBoolean(service) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(field.getBoolean(service))
        passiveCallback().onNewDataPointsReceived(DataPointContainer(listOf(
            IntervalDataPoint(DataType.STEPS_DAILY, 1_234L, Duration.ZERO, Duration.ofMinutes(1)),
            IntervalDataPoint(DataType.FLOORS_DAILY, 6.7, Duration.ZERO, Duration.ofMinutes(1)),
            IntervalDataPoint(DataType.ELEVATION_GAIN_DAILY, 9.0, Duration.ZERO, Duration.ofMinutes(1)),
            IntervalDataPoint(DataType.CALORIES_DAILY, 42.4, Duration.ZERO, Duration.ofMinutes(1))
        )))
        verifyRepo { repo.updateDailyMetrics(any(), 1_234L, 6.7, 9.0) }
    }

    @Test
    fun coldStartAndStickyRestartWithoutActivityRepositoryUseApplicationRepository() {
        service.onCreate()
        assertTrue(ActivityRecognitionService.isRunning.get())
        assertEquals(Service.START_STICKY, service.onStartCommand(null, Service.START_FLAG_REDELIVERY, 1))

        val result = ActivityRecognitionResult(DetectedActivity(DetectedActivity.WALKING, 80), 1_000L, 1_000L)
        val intent = Intent("com.aitech.weartak_civ.ACTION_RECOGNITION")
            .putExtra("com.google.android.location.internal.EXTRA_ACTIVITY_RESULT", result)
        assertTrue(ActivityRecognitionResult.hasResult(intent))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 2))

        verifyRepo { repo.storeLatestActivityRecognized(ActivityRecognized.WALKING) }
        verifyRepo { repo.storeLatestActivityConfidence(80) }

        val callback = passiveCallback()
        callback.onNewDataPointsReceived(
            DataPointContainer(listOf(IntervalDataPoint(DataType.STEPS_DAILY, 1_234L, Duration.ZERO, Duration.ofMinutes(1))))
        )
        verifyRepo {
            repo.updateDailyMetrics(any(), 1_234L, null, null)
        }

        callback.onHealthEventReceived(
            HealthEvent(HealthEvent.Type.FALL_DETECTED, Instant.now(), DataPointContainer(emptyList()))
        )
        verifyRepo {
            repo.addPhysioAndAutoGenAlertRequest(
                any(), CAT_FALL_DETECTED, DESC_GOOGLE_SDK_FALL_DETECTED, 1, true, any()
            )
        }

        assertTrue("Unexpected uncaught exceptions: $uncaught", uncaught.isEmpty())
    }

    private fun verifyRepo(block: suspend io.mockk.MockKVerificationScope.() -> Unit) {
        try {
            coVerify(timeout = 3_000, verifyBlock = block)
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; uncaught service exceptions: $uncaught", uncaught.firstOrNull() ?: e)
        }
    }

    @Test
    fun motionOnlyUpdatesActivityWithoutAcceptingPassiveHealthOrFallCallbacks() {
        monitoring.value = false
        service.onCreate()
        val intent = Intent("com.aitech.weartak_civ.ACTION_RECOGNITION").putExtra(
            "com.google.android.location.internal.EXTRA_ACTIVITY_RESULT",
            ActivityRecognitionResult(DetectedActivity(DetectedActivity.STILL, 80), 1_000L, 1_000L)
        )
        service.onStartCommand(intent, 0, 1)
        verifyRepo { repo.storeLatestActivityRecognized(ActivityRecognized.STILL) }
        clearMocks(repo, answers = false)
        passiveCallback().onNewDataPointsReceived(DataPointContainer(listOf(
            IntervalDataPoint(DataType.STEPS_DAILY, 123L, Duration.ZERO, Duration.ofMinutes(1))
        )))
        passiveCallback().onHealthEventReceived(
            HealthEvent(HealthEvent.Type.FALL_DETECTED, Instant.now(), DataPointContainer(emptyList()))
        )
        coVerify(exactly = 0) { repo.updateDailyMetrics(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.addPhysioAndAutoGenAlertRequest(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun incumbentConfidenceThresholdIsNotLoweredByMotionOnlyDemand() {
        monitoring.value = false
        service.onCreate()
        fun detect(confidence: Int) = service.onStartCommand(
            Intent("com.aitech.weartak_civ.ACTION_RECOGNITION").putExtra(
                "com.google.android.location.internal.EXTRA_ACTIVITY_RESULT",
                ActivityRecognitionResult(DetectedActivity(DetectedActivity.WALKING, confidence), 1_000L, 1_000L)
            ), 0, 1
        )
        detect(49)
        coVerify(exactly = 0) { repo.storeLatestActivityRecognized(any()) }
        detect(50)
        verifyRepo { repo.storeLatestActivityRecognized(ActivityRecognized.WALKING) }
        verifyRepo { repo.storeLatestActivityConfidence(50) }
    }

    @Test
    fun monitoringToggleAndBodyPermissionContinueToGateRetainedPassiveCallbacks() {
        service.onCreate()
        fun awaitMonitoring(expected: Boolean) {
            val field = ActivityRecognitionService::class.java.getDeclaredField("passiveMonitoringEnabled")
                .apply { isAccessible = true }
            val deadline = System.nanoTime() + 3_000_000_000L
            while (field.getBoolean(service) != expected && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(expected, field.getBoolean(service))
        }
        fun sample() = passiveCallback().onNewDataPointsReceived(DataPointContainer(listOf(
            IntervalDataPoint(DataType.STEPS_DAILY, 456L, Duration.ZERO, Duration.ofMinutes(1))
        )))
        awaitMonitoring(true)
        monitoring.value = false
        awaitMonitoring(false)
        sample()
        coVerify(exactly = 0) { repo.updateDailyMetrics(any(), any(), any(), any()) }
        monitoring.value = true
        awaitMonitoring(true)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BODY_SENSORS)
        sample()
        coVerify(exactly = 0) { repo.updateDailyMetrics(any(), any(), any(), any()) }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BODY_SENSORS)
        sample()
        verifyRepo { repo.updateDailyMetrics(any(), 456L, null, null) }
    }

    @Test
    fun revokedActivityPermissionDuringRemovalCancelsCallbackWithoutCrashing() {
        val client = mockk<ActivityRecognitionClient>()
        val callback = prepareActivityRemoval(client)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        every { client.removeActivityUpdates(callback) } throws SecurityException("Permission revoked")

        service.onDestroy()

        verify(exactly = 1) { client.removeActivityUpdates(callback) }
        assertThrows(PendingIntent.CanceledException::class.java) { callback.send() }
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun failedActivityRemovalTaskStillCancelsCallback() {
        val client = mockk<ActivityRecognitionClient>()
        val callback = prepareActivityRemoval(client)
        every { client.removeActivityUpdates(callback) } returns
            Tasks.forException(SecurityException("Permission revoked"))

        service.onDestroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        verify(exactly = 1) { client.removeActivityUpdates(callback) }
        assertThrows(PendingIntent.CanceledException::class.java) { callback.send() }
    }

    @Test
    fun successfulActivityRemovalStillCancelsCallback() {
        val client = mockk<ActivityRecognitionClient>()
        val callback = prepareActivityRemoval(client)
        every { client.removeActivityUpdates(callback) } returns Tasks.forResult(null)

        service.onDestroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        verify(exactly = 1) { client.removeActivityUpdates(callback) }
        assertThrows(PendingIntent.CanceledException::class.java) { callback.send() }
    }

    private fun prepareActivityRemoval(client: ActivityRecognitionClient): PendingIntent {
        val callback = PendingIntent.getService(service, 0,
            Intent(service, ActivityRecognitionService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        ActivityRecognitionService::class.java.getDeclaredField("activityRecognitionClient")
            .apply { isAccessible = true }.set(service, client)
        ActivityRecognitionService::class.java.getDeclaredField("callbackIntent")
            .apply { isAccessible = true }.set(service, callback)
        ActivityRecognitionService.isRunning.set(true)
        return callback
    }

    private fun passiveCallback(): PassiveListenerCallback =
        ActivityRecognitionService::class.java.getDeclaredField("passiveDailyListenerCallback")
            .apply { isAccessible = true }.get(service) as PassiveListenerCallback

    private fun clearMainActivityRepository() {
        MainActivity::class.java.getDeclaredField("dataRepository")
            .apply { isAccessible = true }.set(null, null)
    }
}
