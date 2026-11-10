/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.weartak.plugin.sdk.v3.aidl.IWearTakPcmCallback
import com.weartak.plugin.sdk.v3.model.PcmBatchParcel
import com.weartak.plugin.sdk.v3.model.PcmStreamStateParcel
import com.weartak.plugin.sdk.v3.model.PcmSubscriptionRequestParcel
import com.weartak.plugin.sdk.v3.model.PcmSubscriptionStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostPcmControlV3ImplTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun subscribe_isIdempotentAndUnsubscribeReleasesLease() {
        val repository = AudioCaptureRepository(context)
        val callback = RecordingPcmCallback()
        val control = control(repository, "com.test.gunshot", callbackUid = 100)

        val first = control.subscribePcm(PcmSubscriptionRequestParcel(), callback)
        val duplicate = control.subscribePcm(PcmSubscriptionRequestParcel(), callback)

        assertTrue(first.accepted)
        assertEquals(first.subscriptionId, duplicate.subscriptionId)
        assertEquals(setOf("plugin:com.test.gunshot"), repository.activeConsumers.value)

        val stopped = control.unsubscribePcm(first.subscriptionId)
        assertTrue(stopped.accepted)
        assertTrue(repository.activeConsumers.value.isEmpty())
        control.close()
    }

    @Test
    fun twoConsumers_receiveTheSameOrderedChunks() {
        val repository = AudioCaptureRepository(context)
        val gunshotCallback = RecordingPcmCallback()
        val uasCallback = RecordingPcmCallback()
        val gunshot = control(repository, "com.test.gunshot", callbackUid = 101)
        val uas = control(repository, "com.test.uas", callbackUid = 102)
        val request = PcmSubscriptionRequestParcel(requestedChunksPerBatch = 4)

        assertTrue(gunshot.subscribePcm(request, gunshotCallback).accepted)
        assertTrue(uas.subscribePcm(request, uasCallback).accepted)
        assertEquals(2, repository.activeConsumers.value.size)

        repeat(4) { sequence ->
            assertTrue(repository.tryEmitChunk(chunk(sequence.toLong())))
        }

        assertTrue(gunshotCallback.batchLatch.await(1, TimeUnit.SECONDS))
        assertTrue(uasCallback.batchLatch.await(1, TimeUnit.SECONDS))
        val gunshotBatch = requireNotNull(gunshotCallback.lastBatch)
        val uasBatch = requireNotNull(uasCallback.lastBatch)
        assertEquals(listOf(0L, 1L, 2L, 3L), gunshotBatch.chunks.map { it.sequence })
        assertEquals(gunshotBatch.chunks.map { it.sequence }, uasBatch.chunks.map { it.sequence })
        assertArrayEquals(shortArrayOf(0, 1), gunshotBatch.chunks.first().samples)

        gunshot.close()
        assertEquals(setOf("plugin:com.test.uas"), repository.activeConsumers.value)
        uas.close()
        assertTrue(repository.activeConsumers.value.isEmpty())
    }

    @Test
    fun unauthorizedOrUnsupportedRequests_areRejectedWithoutCaptureDemand() {
        val repository = AudioCaptureRepository(context)
        val callback = RecordingPcmCallback()
        val unauthorized = HostPcmControlV3Impl(
            audioRepo = repository,
            pluginPackage = "com.test.denied",
            expectedPluginUid = 200,
            callerUidProvider = { 201 },
            dispatcher = Dispatchers.Unconfined,
        )

        val denied = unauthorized.subscribePcm(PcmSubscriptionRequestParcel(), callback)
        assertFalse(denied.accepted)
        assertEquals(PcmSubscriptionStatus.UNAUTHORIZED, denied.status)

        val authorized = control(repository, "com.test.invalid", callbackUid = 202)
        val invalid = authorized.subscribePcm(
            PcmSubscriptionRequestParcel(sampleRateHz = 16_000),
            callback,
        )
        assertFalse(invalid.accepted)
        assertEquals(PcmSubscriptionStatus.INVALID_REQUEST, invalid.status)
        assertTrue(repository.activeConsumers.value.isEmpty())

        unauthorized.close()
        authorized.close()
    }

    private fun control(
        repository: AudioCaptureRepository,
        packageName: String,
        callbackUid: Int,
    ) = HostPcmControlV3Impl(
        audioRepo = repository,
        pluginPackage = packageName,
        expectedPluginUid = callbackUid,
        callerUidProvider = { callbackUid },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun chunk(sequence: Long) = AudioCaptureRepository.AudioPcmChunk(
        sessionId = "audio-session",
        seq = sequence,
        timestampMs = 1_700_000_000_000L + sequence,
        timestampElMs = 10_000L + sequence,
        sampleRateHz = 44_100,
        channels = 1,
        samples = shortArrayOf(sequence.toShort(), (sequence + 1L).toShort()),
    )

    private class RecordingPcmCallback : IWearTakPcmCallback.Stub() {
        val batchLatch = CountDownLatch(1)
        @Volatile var lastBatch: PcmBatchParcel? = null
        val states = mutableListOf<PcmStreamStateParcel>()

        override fun onPcmBatch(batch: PcmBatchParcel?) {
            lastBatch = batch
            batchLatch.countDown()
        }

        override fun onPcmStreamState(state: PcmStreamStateParcel?) {
            if (state != null) states += state
        }
    }
}
