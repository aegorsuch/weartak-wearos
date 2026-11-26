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
import android.os.IBinder
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.weartak.plugin.sdk.v3.aidl.IWearTakPcmCallback
import com.weartak.plugin.sdk.v3.model.PcmBatchParcel
import com.weartak.plugin.sdk.v3.model.PcmStreamState
import com.weartak.plugin.sdk.v3.model.PcmStreamStateParcel
import com.weartak.plugin.sdk.v3.model.PcmSubscriptionRequestParcel
import com.weartak.plugin.sdk.v3.model.PcmSubscriptionStatus
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    fun dartOptOutRevokesOnlyDartAndRequiresOptInBeforeSubscribing() = runBlocking {
        val repository = AudioCaptureRepository(context)
        val consent = DartAudioConsent({ false }, {})
        val dart = HostPcmControlV3Impl(
            audioRepo = repository,
            pluginPackage = PluginDetectionEventBridge.DART_PACKAGE,
            expectedPluginUid = 101,
            callerUidProvider = { 101 },
            dispatcher = Dispatchers.Unconfined,
            dartAudioConsent = consent,
        )
        val other = control(repository, "com.test.uas", callbackUid = 102)
        val callback = RecordingPcmCallback()
        val request = PcmSubscriptionRequestParcel()
        assertFalse(dart.subscribePcm(request, callback).accepted)
        consent.initialize()
        assertFalse(dart.subscribePcm(request, callback).accepted)
        assertTrue(other.subscribePcm(request, RecordingPcmCallback()).accepted)
        consent.setEnabled(true)
        assertTrue(dart.subscribePcm(request, callback).accepted)
        consent.setEnabled(false)
        assertEquals(setOf("plugin:com.test.uas"), repository.activeConsumers.value)
        assertFalse(dart.subscribePcm(request, callback).accepted)
        dart.close()
        other.close()
    }

    @Test
    fun dartOptOutDuringListeningCallbackDoesNotDispatchQueuedBatchAfterReenable() = runBlocking {
        val repository = AudioCaptureRepository(context)
        val consent = DartAudioConsent({ true }, {})
        consent.initialize()
        val listening = CountDownLatch(1)
        val releaseListening = CountDownLatch(1)
        val batch = CountDownLatch(1)
        val callback = object : IWearTakPcmCallback.Stub() {
            override fun onPcmStreamState(state: PcmStreamStateParcel?) {
                if (state?.state == PcmStreamState.LISTENING) {
                    listening.countDown()
                    releaseListening.await(5, TimeUnit.SECONDS)
                }
            }
            override fun onPcmBatch(value: PcmBatchParcel?) { batch.countDown() }
        }
        val dart = HostPcmControlV3Impl(
            audioRepo = repository,
            pluginPackage = PluginDetectionEventBridge.DART_PACKAGE,
            expectedPluginUid = 101,
            callerUidProvider = { 101 },
            dispatcher = Dispatchers.IO,
            dartAudioConsent = consent,
        )
        try {
            assertTrue(dart.subscribePcm(PcmSubscriptionRequestParcel(requestedChunksPerBatch = 1), callback).accepted)
            for (sequence in 0 until 100) {
                repository.tryEmitChunk(chunk(sequence.toLong()))
                if (listening.await(20, TimeUnit.MILLISECONDS)) break
            }
            assertEquals(0L, listening.count)
            consent.setEnabled(false)
            consent.setEnabled(true)
            releaseListening.countDown()
            assertFalse(batch.await(250, TimeUnit.MILLISECONDS))
            assertTrue(dart.subscribePcm(PcmSubscriptionRequestParcel(), RecordingPcmCallback()).accepted)
        } finally {
            releaseListening.countDown()
            dart.close()
        }
    }

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

    @Test
    fun callbackDeathReleasesCaptureLease() {
        val repository = AudioCaptureRepository(context)
        val callback = mockk<IWearTakPcmCallback>(relaxed = true)
        val binder = mockk<IBinder>(relaxed = true)
        var deathRecipient: IBinder.DeathRecipient? = null
        every { callback.asBinder() } returns binder
        every { binder.linkToDeath(any(), any()) } answers {
            deathRecipient = firstArg()
            Unit
        }
        val control = control(repository, "com.test.gunshot", callbackUid = 100)

        val result = control.subscribePcm(PcmSubscriptionRequestParcel(), callback)
        assertTrue(result.accepted)
        assertEquals(setOf("plugin:com.test.gunshot"), repository.activeConsumers.value)

        requireNotNull(deathRecipient).binderDied()

        assertTrue(repository.activeConsumers.value.isEmpty())
        assertEquals(
            PcmSubscriptionStatus.NOT_ACTIVE,
            control.unsubscribePcm(result.subscriptionId).status,
        )
        control.close()
    }

    @Test
    fun callbackDeathRegistrationFailureDoesNotAcquireCaptureLease() {
        val repository = AudioCaptureRepository(context)
        val callback = mockk<IWearTakPcmCallback>(relaxed = true)
        val binder = mockk<IBinder>(relaxed = true)
        every { callback.asBinder() } returns binder
        every { binder.linkToDeath(any(), any()) } throws RemoteException("callback is dead")
        val control = control(repository, "com.test.gunshot", callbackUid = 100)

        val result = control.subscribePcm(PcmSubscriptionRequestParcel(), callback)

        assertFalse(result.accepted)
        assertEquals(PcmSubscriptionStatus.REMOTE_ERROR, result.status)
        assertTrue(repository.activeConsumers.value.isEmpty())
        control.close()
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
