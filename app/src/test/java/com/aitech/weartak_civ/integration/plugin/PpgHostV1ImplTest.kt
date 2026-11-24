/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.PpgSampleDelivery
import com.aitech.weartak_civ.service.RawPpgSample
import com.weartak.plugin.sdk.ppg.v1.IPpgCallbackV1
import com.weartak.plugin.sdk.ppg.v1.PpgBatchV1
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import com.weartak.plugin.sdk.ppg.v1.PpgStateV1
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PpgHostV1ImplTest {
    private val crm = mockk<CrmDataRepository>(relaxed = true)
    private val repo = mockk<DataRepository>(relaxed = true)
    private val app = mockk<WearTakApplication>(relaxed = true)

    init {
        every { app.crmRepo } returns crm
        every { app.repo } returns repo
    }

    private fun host(uid: Int, active: () -> Boolean = { true }): PpgHostV1Impl {
        every { crm.samplingState } returns MutableStateFlow(CrmDataRepository.CrmSamplingState.IDLE)
        every { repo.latestDeviceWornObservation } returns MutableStateFlow(true)
        return PpgHostV1Impl(app, expectedUid = 7, isSessionActive = active, authorize = { true }, callerUid = { uid })
    }

    @Test
    fun rejectsUnauthorizedAndInactiveSubscriptions() {
        val callback = RecordingCallback()
        val unauthorized = host(8)
        val inactive = host(7) { false }
        try {
            assertEquals(PpgContractV1.UNAUTHORIZED, unauthorized.subscribe("session", callback))
            assertEquals(PpgContractV1.INACTIVE_SESSION, inactive.subscribe("session", callback))
            assertEquals(PpgContractV1.UNAUTHORIZED, unauthorized.unsubscribe("session"))
        } finally {
            unauthorized.close()
            inactive.close()
        }
    }

    @Test
    fun rejectsRevokedTrustForEveryCall() {
        var trusted = true
        val host = PpgHostV1Impl(
            app, expectedUid = 7, isSessionActive = { true },
            authorize = { trusted }, callerUid = { 7 },
        )
        val callback = RecordingCallback()
        try {
            trusted = false
            assertEquals(PpgContractV1.UNAUTHORIZED, host.subscribe("session", callback))
            assertEquals(PpgContractV1.UNAUTHORIZED, host.unsubscribe("session"))
        } finally {
            host.close()
        }
    }

    @Test
    fun deliversDistinctSamplesInSequenceAndReleasesOnClose() {
        val host = host(7)
        val callback = RecordingCallback()
        val sample = RawPpgSample(42f, 100, 200, sourceAvailable = true, deviceWorn = true)
        try {
            assertEquals(PpgContractV1.OK, host.subscribe("session", callback))
            assertEquals(PpgContractV1.BUSY, host.subscribe("other", callback))
            assertEquals(PpgContractV1.INVALID_REQUEST, host.unsubscribe("other"))
            PpgSampleDelivery.publish(sample)
            PpgSampleDelivery.publish(sample)
            assertTrue(callback.samplesReceived.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 1L), callback.batches.map { it.sequence })
            assertEquals(listOf(42f, 42f), callback.batches.flatMap { it.samples }.map { it.rawValue })
        } finally {
            host.close()
        }
        val next = PpgSampleDelivery.Consumer { }
        assertTrue(PpgSampleDelivery.acquire(next))
        assertTrue(PpgSampleDelivery.release(next))
    }

    @Test
    fun overflowStopsDeliveryAndReportsTerminalError() {
        val host = host(7)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val errorReported = CountDownLatch(1)
        val callback = object : IPpgCallbackV1.Stub() {
            override fun onState(state: PpgStateV1?) {
                if (state?.state == PpgContractV1.ERROR && state.reason == "delivery_overflow") {
                    errorReported.countDown()
                }
            }

            override fun onSamples(batch: PpgBatchV1?) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val sample = RawPpgSample(42f, 100, 200, sourceAvailable = true, deviceWorn = true)
        try {
            assertEquals(PpgContractV1.OK, host.subscribe("session", callback))
            PpgSampleDelivery.publish(sample)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(300) { PpgSampleDelivery.publish(sample) }
            val next = PpgSampleDelivery.Consumer { }
            assertTrue(PpgSampleDelivery.acquire(next))
            assertTrue(PpgSampleDelivery.release(next))
            release.countDown()
            assertTrue(errorReported.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            host.close()
        }
    }

    private class RecordingCallback : IPpgCallbackV1.Stub() {
        val batches = Collections.synchronizedList(mutableListOf<PpgBatchV1>())
        val samplesReceived = CountDownLatch(2)

        override fun onState(state: PpgStateV1?) = Unit

        override fun onSamples(batch: PpgBatchV1?) {
            if (batch != null) {
                batches.add(batch)
                samplesReceived.countDown()
            }
        }
    }
}
