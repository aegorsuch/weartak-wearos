package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportDeliveryStage
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PluginOnlyPliProducerTest {
    @Test fun capturesFreshOwnPliAndFencesOnCompanionTakeover() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val repo = mockk<DataRepository>(relaxed = true)
        val ingress = mockk<PluginIngressPort>()
        val manager = mockk<PluginManager>(relaxed = true)
        val context = RuntimeEnvironment.getApplication()
        val companion = MutableStateFlow(false)
        val identity = MutableStateFlow("synthetic-identity")
        val location = Location("fused").apply {
            latitude = 1.0; longitude = 2.0; altitude = 3.0; accuracy = 4f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        val locationSnapshot = DataRepository.LocationSnapshot(
            location.time, location.latitude, location.longitude, location.altitude, 4f, 4f,
            null, null, location.provider, location.elapsedRealtimeNanos,
        )
        val pli = mockk<DataRepository.PliSnapshot>(relaxed = true)
        every { pli.monitoredPhysio } returns null
        every { pli.remarks } returns "synthetic"
        every { pli.moppGeneration } returns null
        every { app.repo } returns repo
        every { app.pluginManager } returns manager
        every { app.getSystemService(PowerManager::class.java) } returns
            context.getSystemService(PowerManager::class.java)
        every { app.contentResolver } returns context.contentResolver
        every { app.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ingress.packagesForUid(app, any()) } returns setOf(TrustedCommunityPlugin.PACKAGE)
        every { repo.androidDeviceUid } returns "synthetic-watch"
        every { repo.getEnableDittoService } returns MutableStateFlow(true)
        every { repo.getEnableLocationService } returns MutableStateFlow(true)
        every { repo.watchTransportSuppressed } returns companion
        every { repo.shouldSuppressWatchTransportNow() } answers { companion.value }
        every { repo.getEffectiveDittoPdl } returns identity
        every { repo.getBatteryWarnPercentage } returns flowOf(20)
        every { repo.latestDeviceWornObservation } returns MutableStateFlow<Boolean?>(true)
        every { repo.chargingState } returns MutableStateFlow(false)
        every { repo.latestBatteryPercentage } returns MutableStateFlow<Int?>(80)
        every { repo.isAlerting } returns MutableStateFlow(false)
        every { repo.stayConnectedOffWrist } returns flowOf(false)
        every { repo.latestLocation } returns MutableStateFlow(location)
        every { repo.selfStatusInputs() } returns flowOf(mockk(relaxed = true))
        every { repo.bestKnownLocation() } returns location
        every { repo.isCurrentLocationInput(any()) } returns true
        every { repo.isCurrentLocationSnapshot(any()) } returns true
        every { repo.hasCompanionEverBeenReady() } returns false
        coEvery { repo.snapshotPliLocation() } returns locationSnapshot
        coEvery { repo.snapshotPli() } returns pli
        MainActivity.callsign = "SYNTHETIC"
        val received = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val callback = object : IPluginTransportCallbackV1.Stub() {
            @Volatile var request: TransportSendRequestParcel? = null
            override fun sendMessage(request: TransportSendRequestParcel?): TransportResultParcel {
                this.request = request
                received.countDown()
                return TransportResultParcel(TransportResultCode.ACCEPTED, request?.sessionId)
            }
            override fun cancelMessage(sessionId: String?, messageId: String?) =
                TransportResultParcel(TransportResultCode.ACCEPTED, sessionId)
            override fun onSessionClosed(sessionId: String?, reason: Int) = Unit
        }
        val host = PluginTransportHostV1Impl(app, ingress, TrustedCommunityPlugin.PACKAGE, Process.myUid(),
            pluginOnlyPli = true, trustedDittoPlugin = { true }, onPliUnavailable = { disconnected.countDown() })
        val caps = TransportCapabilitiesParcel(TransportContract.VERSION,
            listOf(TransportPayloadKind.COT_XML_UTF8), emptyList(), listOf(DittoPliTransportSession.CHANNEL),
            TransportContract.MAX_PAYLOAD_BYTES, 1, true, false, false)
        val session = requireNotNull(host.registerTransport(caps, callback).sessionId)
        host.activateBinding()
        val producer = PluginOnlyPliProducer(app, host, { true }, { "synthetic-identity" }, host::invalidatePluginPli)
        try {
            producer.start()
            assertTrue("Fresh snapshot was not offered", received.await(10, TimeUnit.SECONDS))
            val request = requireNotNull(callback.request)
            assertEquals("synthetic-watch", request.message.senderUid)
            assertTrue(request.message.payload.toString(Charsets.UTF_8).contains("synthetic-watch"))
            assertEquals(TransportResultCode.ACCEPTED, host.reportDelivery(session,
                TransportDeliveryParcel(request.message.messageId, request.channelId, 1,
                    TransportDeliveryStage.CHANNEL_SUBMITTED)).code)
            companion.value = true
            assertTrue("Companion takeover did not fence connection", disconnected.await(10, TimeUnit.SECONDS))
            assertFalse(host.isTransportRegistered())
        } finally {
            producer.close()
            host.closeBinding()
        }
    }
}
