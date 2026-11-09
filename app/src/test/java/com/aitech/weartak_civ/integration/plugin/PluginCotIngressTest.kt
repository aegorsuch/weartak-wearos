package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginCotIngressTest {
    @Test
    fun allNetworkSourcesUseOptInInboundActionWithMetadata() {
        val coordinator = mockk<PluginSettingsCoordinator>(relaxed = true)
        val xml = "<event uid='test' type='a-f-G-U-C'><detail/></event>"
        listOf("tak:server", "multicast:239.2.3.1:6969", "sitx:server", "companion:42").forEach { source ->
            assertFalse(PluginCotIngress.offer(TakServerCotEnvelope(source, source, 123L, xml), coordinator))
            verify(exactly = 1) {
                coordinator.invokeCotOnPluginsWithSetting(
                    fieldId = "receiveInboundCot",
                    actionId = "cot.inbound",
                    args = match { it.getString("xml") == xml && it.getString("source") == source &&
                        it.getLong("receivedAt") == 123L },
                    sourceEnvelope = match { it.sourceServerId == source && it.receivedAt == 123L },
                )
            }
        }
    }

    @Test
    fun pluginInjectionDoesNotReturnToPluginBearers() {
        val coordinator = mockk<PluginSettingsCoordinator>(relaxed = true)
        assertFalse(PluginCotIngress.offer(
            TakServerCotEnvelope("plugin:com.example", "com.example", 123L,
                "<event uid='test' type='a-f-G-U-C'><detail/></event>"),
            coordinator
        ))
        verify(exactly = 0) { coordinator.invokeCotOnPluginsWithSetting(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun sensitiveEnrollmentIsConsumedWithoutCreatingPluginBundle() {
        val coordinator = mockk<PluginSettingsCoordinator>(relaxed = true)
        for (xml in listOf("<event><remarks>#weartakcommand enrollserver N,h,8089,u,fake-secret</remarks></event>",
            "<event><remarks>#weartakcommandenrollserver malformed")) {
            assertTrue(PluginCotIngress.offer(TakServerCotEnvelope("tak", "tak", 123L, xml), coordinator))
        }
        verify(exactly = 0) { coordinator.invokeCotOnPluginsWithSetting(any(), any(), any(), any(), any(), any()) }
    }
}
