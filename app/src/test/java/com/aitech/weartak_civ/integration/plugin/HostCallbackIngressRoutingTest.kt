package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.model.SensorEventParcel
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [HostCallbackImpl] routes plugin callbacks into [PluginIngressPort] without crashing on
 * null / empty payloads.
 */
@RunWith(RobolectricTestRunner::class)
class HostCallbackIngressRoutingTest {

    @Test
    fun onSensorEvent_nullIsIgnored() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val cb = HostCallbackImpl(ingress, PluginSettingsCoordinator(mockk(relaxed = true)), "com.plugin.pkg", 100)
        cb.onSensorEvent(null)
        verify(exactly = 0) { ingress.onSensorEventFromPlugin(any(), any(), any()) }
    }

    @Test
    fun onSensorEvent_routesToIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val cb = HostCallbackImpl(ingress, PluginSettingsCoordinator(mockk(relaxed = true)), "com.plugin.pkg", 100)
        val evt = SensorEventParcel(
            sensorKey = "com.plugin.pkg.temp",
            timestampUtcMillis = System.currentTimeMillis(),
            values = Bundle().apply { putDouble("value", 1.0) },
        )
        cb.onSensorEvent(evt)
        verify(exactly = 1) { ingress.onSensorEventFromPlugin("com.plugin.pkg", 100, evt) }
    }

    @Test
    fun onSensorBatchEvent_nullOrEmptyIgnored() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val cb = HostCallbackImpl(ingress, PluginSettingsCoordinator(mockk(relaxed = true)), "com.plugin.pkg", 100)
        cb.onSensorBatchEvent(null)
        cb.onSensorBatchEvent(mutableListOf())
        verify(exactly = 0) { ingress.onSensorBatchFromPlugin(any(), any(), any()) }
    }

    @Test
    fun onSensorBatchEvent_routesList() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val cb = HostCallbackImpl(ingress, PluginSettingsCoordinator(mockk(relaxed = true)), "com.plugin.pkg", 100)
        val e1 = SensorEventParcel("com.plugin.pkg.a", System.currentTimeMillis(), values = Bundle())
        val e2 = SensorEventParcel("com.plugin.pkg.b", System.currentTimeMillis(), values = Bundle())
        cb.onSensorBatchEvent(mutableListOf(e1, e2))
        verify(exactly = 1) {
            ingress.onSensorBatchFromPlugin("com.plugin.pkg", 100, any())
        }
    }

    @Test
    fun interceptedSensorEventsDoNotEnterCepIncludingBatches() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val cb = HostCallbackImpl(
            ingress, PluginSettingsCoordinator(mockk(relaxed = true)), "com.plugin.pkg", 100,
            interceptSensorEvent = { it.sensorKey == "crm_status_v1" },
        )
        val intercepted = SensorEventParcel("crm_status_v1", System.currentTimeMillis())
        val ordinary = SensorEventParcel("other", System.currentTimeMillis())

        cb.onSensorEvent(intercepted)
        cb.onSensorBatchEvent(mutableListOf(intercepted, ordinary))

        verify(exactly = 0) { ingress.onSensorEventFromPlugin(any(), any(), any()) }
        verify(exactly = 1) {
            ingress.onSensorBatchFromPlugin("com.plugin.pkg", 100, listOf(ordinary))
        }
    }
}
