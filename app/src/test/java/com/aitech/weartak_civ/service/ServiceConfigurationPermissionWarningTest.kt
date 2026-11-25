package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import io.mockk.mockk
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.integration.plugin.PluginDetectionEventBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ServiceConfigurationPermissionWarningTest {
    @Test
    fun dynamicMotionDemandDoesNotEnablePhysiologyOrRequireBodySensors() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "configuration-test-watch"
        val repo = DataRepository(app)
        val configuration = ServiceConfiguration(app, repo, mockk(relaxed = true))
        repo.storeEnablePhysiologicalServices(false)
        repo.storeEnableActivityRecognitionService(true)
        repo.storeEnableHeartRateService(true)
        repo.storeEnableDynamicReportingStrategy(true)
        repo.storeEnableLocationService(true)
        assertTrue(configuration.getServiceEnabledFlow(ServiceType.ACTIVITY_RECOGNITION).first())
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.HEART_RATE).first())
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.SKIN_TEMP).first())
        shadowOf(app).denyPermissions(Manifest.permission.BODY_SENSORS)
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertTrue(configuration.permissionsGranted(ServiceType.ACTIVITY_RECOGNITION))
        assertFalse(configuration.permissionsGranted(ServiceType.HEART_RATE))
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertFalse(configuration.permissionsGranted(ServiceType.ACTIVITY_RECOGNITION))
        repo.storeEnableActivityRecognitionService(false)
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.ACTIVITY_RECOGNITION).first())
        repo.storeEnableActivityRecognitionService(true)
        repo.storeEnableLocationService(false)
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.ACTIVITY_RECOGNITION).first())
        repo.storeEnableLocationService(true)
        repo.storeEnableDynamicReportingStrategy(false)
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.ACTIVITY_RECOGNITION).first())
        repo.storeEnablePhysiologicalServices(true)
        assertTrue(configuration.getServiceEnabledFlow(ServiceType.ACTIVITY_RECOGNITION).first())
    }

    @Test
    fun logsOncePerFeatureAndResumesAfterPermissionStateChanges() {
        val app = RuntimeEnvironment.getApplication()
        val repository = mockk<com.aitech.weartak_civ.data.DataRepository>(relaxed = true)
        val configuration = ServiceConfiguration(
            app,
            repository,
            mockk(relaxed = true)
        )
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        ShadowLog.clear()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        assertFalse(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        assertFalse(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        assertEquals(
            1,
            ShadowLog.getLogsForTag("WearTAK-ServiceConfig")
                .count { it.msg.contains("Missing permissions for AUDIO_CAP") }
        )
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        assertEquals(
            2,
            ShadowLog.getLogsForTag("WearTAK-ServiceConfig")
                .count { it.msg.contains("Missing permissions for AUDIO_CAP") }
        )
    }

    @Test
    fun audioCaptureIgnoresLegacyPreferencesButHonorsPluginDemand() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "configuration-test-watch"
        val repo = DataRepository(app)
        val audio = AudioCaptureRepository(app)
        val configuration = ServiceConfiguration(app, repo, audio)
        repo.storeEnableOnnxGunshotService(true)
        repo.storeEnableOnnxUasService(false)
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.AUDIO_CAP).first())
        audio.acquireConsumer("plugin:${PluginDetectionEventBridge.DART_PACKAGE}")
        assertTrue(configuration.getServiceEnabledFlow(ServiceType.AUDIO_CAP).first())
        audio.releaseConsumer("plugin:${PluginDetectionEventBridge.DART_PACKAGE}")
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.AUDIO_CAP).first())

        repo.storeEnableOnnxUasService(true)
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.AUDIO_CAP).first())
        audio.acquireConsumer("plugin:${PluginDetectionEventBridge.FALCON_PACKAGE}")
        assertTrue(configuration.getServiceEnabledFlow(ServiceType.AUDIO_CAP).first())
        audio.releaseConsumer("plugin:${PluginDetectionEventBridge.FALCON_PACKAGE}")
        assertFalse(configuration.getServiceEnabledFlow(ServiceType.AUDIO_CAP).first())
    }
}
