package com.aitech.weartak_civ.integration.plugin

import android.os.Process
import android.os.Bundle
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.service.TAKConnectService
import com.aitech.weartak_civ.presentation.MainActivity
import com.weartak.plugin.sdk.model.CotRequestParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsFieldParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.CepRuleEventParcel
import com.weartak.plugin.sdk.model.CepRuleOperation
import com.weartak.plugin.sdk.model.HostCapabilitiesParcel
import com.weartak.plugin.sdk.DeviceStatusContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HostControlImplTest {
    @Test
    fun offlineDeviceStatusReportsActualTakUidAndCurrentCallsignWithoutTraffic() {
        mockkObject(TAKConnectService.Companion)
        try {
            every { TAKConnectService.isAnyTakConnected } returns false
            val repo = deviceStatusRepository()
            val callsign = MutableStateFlow("")
            every { repo.getCallsign } returns callsign
            val app = mockk<WearTakApplication>()
            every { app.repo } returns repo
            val control = HostControlImpl(app, mockk(relaxed = true), "com.plugin.pkg", Process.myUid())

            val initial = control.deviceStatus
            assertFalse(initial.online)
            assertEquals("WEAROS_0123456789abcdef",
                initial.telemetry.getString(DeviceStatusContract.KEY_TAK_UID))
            assertFalse(initial.telemetry.containsKey(DeviceStatusContract.KEY_CALLSIGN))

            callsign.value = "ALPHA"
            val configured = control.deviceStatus
            assertEquals("ALPHA", configured.telemetry.getString(DeviceStatusContract.KEY_CALLSIGN))
            callsign.value = "BRAVO"
            val updated = control.deviceStatus
            assertEquals("BRAVO", updated.telemetry.getString(DeviceStatusContract.KEY_CALLSIGN))
            assertEquals(initial.telemetry.getString(DeviceStatusContract.KEY_TAK_UID),
                updated.telemetry.getString(DeviceStatusContract.KEY_TAK_UID))
        } finally {
            unmockkObject(TAKConnectService.Companion)
        }
    }

    @Test
    fun unavailableTakIdentityIsNotAdvertisedAndCallsignReadFailurePropagates() {
        mockkObject(TAKConnectService.Companion)
        try {
            every { TAKConnectService.isAnyTakConnected } returns false
            val repo = deviceStatusRepository()
            val app = mockk<WearTakApplication>()
            every { app.repo } returns repo
            val control = HostControlImpl(app, mockk(relaxed = true), "com.plugin.pkg", Process.myUid())
            for (uid in listOf("", "WEAROS_", "WEAROS_null")) {
                every { repo.androidDeviceUid } returns uid
                assertFalse(control.deviceStatus.telemetry.containsKey(DeviceStatusContract.KEY_TAK_UID))
            }
            val failure = IllegalStateException("Preference read failed")
            every { repo.getCallsign } returns flow { throw failure }
            val result = runCatching { control.deviceStatus }
            assertEquals(failure, result.exceptionOrNull())
        } finally {
            unmockkObject(TAKConnectService.Companion)
        }
    }

    private fun deviceStatusRepository(): DataRepository = mockk<DataRepository>().also { repo ->
        every { repo.androidDeviceUid } returns "WEAROS_0123456789abcdef"
        every { repo.getCallsign } returns MutableStateFlow("ALPHA")
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        every { repo.latestBatteryPercentage } returns MutableStateFlow(null)
        every { repo.latestHeartRate } returns MutableStateFlow(null)
        every { repo.latestLocation } returns MutableStateFlow(null)
        coEvery { repo.snapshotLocation(null) } returns null
    }

    @Test
    fun injectedCotIsIngestedLocallyAndRelayCanBeDisabled() {
        MainActivity.mainActivity = mockk {
            every { applicationContext } returns RuntimeEnvironment.getApplication()
        }
        mockkObject(TAKConnectService.Companion)
        try {
            coEvery { TAKConnectService.handleInboundCot(any(), any()) } returns Unit
            every { TAKConnectService.sendDataTAKServers(any(), any()) } returns Unit
            val app = mockk<WearTakApplication>(relaxed = true)
            val coordinator = mockk<PluginSettingsCoordinator>()
            every { app.pluginManager.settingsCoordinator } returns coordinator
            every { coordinator.getState("com.plugin.pkg") } returns null
            val control = HostControlImpl(app, mockk(relaxed = true), "com.plugin.pkg", Process.myUid())
            val request = mockk<CotRequestParcel>()
            val xml = "<event uid='test' type='a-f-G-U-C'><detail/></event>"
            every { request.payloadFormat } returns "cot_xml"
            every { request.payload } returns Bundle().apply { putString("xml", xml) }

            control.requestCotDispatch(request)
            coVerify(exactly = 1) { TAKConnectService.handleInboundCot(xml, "plugin:com.plugin.pkg") }
            verify(exactly = 1) { TAKConnectService.sendDataTAKServers(xml, true) }

            every { coordinator.getState("com.plugin.pkg") } returns PluginSettingsState(
                pluginId = "com.plugin.pkg",
                schema = SettingsSchemaParcel(fields = listOf(SettingsFieldParcel(
                    fieldId = "relayInboundToNetwork", label = "Relay", fieldType = SettingsFieldTypes.TOGGLE
                ))),
                values = Bundle().apply { putBoolean("relayInboundToNetwork", false) }
            )
            control.requestCotDispatch(request)
            coVerify(exactly = 2) { TAKConnectService.handleInboundCot(xml, "plugin:com.plugin.pkg") }
            verify(exactly = 1) { TAKConnectService.sendDataTAKServers(xml, true) }

            every { request.payload } returns Bundle().apply {
                putString("xml", xml)
                putBoolean(HostControlImpl.KEY_INGEST_LOCAL, false)
                putBoolean(HostControlImpl.KEY_RELAY_TO_NETWORK, true)
            }
            control.requestCotDispatch(request)
            coVerify(exactly = 2) { TAKConnectService.handleInboundCot(xml, "plugin:com.plugin.pkg") }
            verify(exactly = 2) { TAKConnectService.sendDataTAKServers(xml, true) }

            every { request.payload } returns Bundle().apply {
                putString("xml", xml)
                putBoolean(HostControlImpl.KEY_INGEST_LOCAL, true)
                putBoolean(HostControlImpl.KEY_RELAY_TO_NETWORK, false)
            }
            control.requestCotDispatch(request)
            coVerify(exactly = 3) { TAKConnectService.handleInboundCot(xml, "plugin:com.plugin.pkg") }
            verify(exactly = 2) { TAKConnectService.sendDataTAKServers(xml, true) }
        } finally {
            unmockkObject(TAKConnectService.Companion)
        }
    }


    @Test
    fun chatPayloadIsSentThroughWearTakChat() {
        mockkObject(TAKConnectService.Companion)
        try {
            coEvery { TAKConnectService.handleInboundCot(any(), any()) } returns Unit
            every { TAKConnectService.sendDataTAKServers(any(), any()) } returns Unit
            val repo = mockk<DataRepository>(relaxed = true)
            val app = mockk<WearTakApplication>(relaxed = true)
            every { app.repo } returns repo
            val control = HostControlImpl(app, mockk(relaxed = true), "com.plugin.pkg", Process.myUid())
            val request = mockk<CotRequestParcel>()
            every { request.payloadFormat } returns "chat"

            every { request.payload } returns Bundle().apply { putString("message", "  ALPHA-1 LACE: L:GREEN  ") }
            control.requestCotDispatch(request)
            coVerify(exactly = 1) {
                repo.requestAutomatedChatSend("All Chat Rooms", "All Chat Rooms", "ALPHA-1 LACE: L:GREEN", any())
            }

            every { request.payload } returns Bundle().apply {
                putString("message", "direct")
                putString("roomUid", "ANDROID-peer")
                putString("roomTitle", "PEER")
            }
            control.requestCotDispatch(request)
            coVerify(exactly = 1) { repo.requestAutomatedChatSend("ANDROID-peer", "PEER", "direct", any()) }

            every { request.payload } returns Bundle().apply { putString("message", "   ") }
            control.requestCotDispatch(request)
            every { request.payload } returns Bundle().apply { putString("message", "x".repeat(4001)) }
            control.requestCotDispatch(request)
            coVerify(exactly = 2) { repo.requestAutomatedChatSend(any(), any(), any(), any()) }
            coVerify(exactly = 0) { TAKConnectService.handleInboundCot(any(), any()) }
            verify(exactly = 0) { TAKConnectService.sendDataTAKServers(any(), any()) }
        } finally {
            unmockkObject(TAKConnectService.Companion)
        }
    }

    @Test
    fun getHostCapabilities_reportsToastAndCepStreaming() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val control = HostControlImpl(
            app = app,
            ingress = ingress,
            pluginPackage = "com.plugin.pkg",
            expectedPluginUid = Process.myUid(),
        )

        val caps: HostCapabilitiesParcel = control.hostCapabilities
        assertTrue(caps.toasts)
        assertTrue(caps.cepRuleStreaming)
        assertTrue(caps.markers)
        assertEquals(false, caps.mapLayers)
        assertEquals(false, caps.sharedMemory)
    }

    @Test
    fun applyCepRuleEvent_routesThroughIngress() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every {
            ingress.applyCepRuleEventBlocking(any(), any(), any(), any())
        } returns CepProvider.ApplyResult.Applied("hash", 1L)
        val control = HostControlImpl(
            app = app,
            ingress = ingress,
            pluginPackage = "com.plugin.pkg",
            expectedPluginUid = Process.myUid(),
        )
        val event = CepRuleEventParcel(
            operation = CepRuleOperation.ADD,
            pluginId = "com.plugin.pkg",
            sensorKey = "com.plugin.pkg.sensor",
            ruleSetId = "default",
            sequence = 1L,
            rulesJson = """{"rules":[{"id":"r1"}]}""",
        )

        control.applyCepRuleEvent(event)

        verify(exactly = 1) {
            ingress.applyCepRuleEventBlocking(
                "com.plugin.pkg",
                event,
                Process.myUid(),
                any(),
            )
        }
    }
}
