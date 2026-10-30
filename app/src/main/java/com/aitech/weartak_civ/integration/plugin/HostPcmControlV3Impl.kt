/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.weartak.plugin.sdk.v3.WearTakPluginV3Contract
import com.weartak.plugin.sdk.v3.aidl.IWearTakPcmCallback
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import com.weartak.plugin.sdk.v3.model.HostCapabilitiesV3Parcel
import com.weartak.plugin.sdk.v3.model.PcmBatchParcel
import com.weartak.plugin.sdk.v3.model.PcmChunkParcel
import com.weartak.plugin.sdk.v3.model.PcmStreamState
import com.weartak.plugin.sdk.v3.model.PcmStreamStateParcel
import com.weartak.plugin.sdk.v3.model.PcmSubscriptionRequestParcel
import com.weartak.plugin.sdk.v3.model.PcmSubscriptionResultParcel
import com.weartak.plugin.sdk.v3.model.PcmSubscriptionStatus
import com.weartak.plugin.sdk.v3.validation.PcmContractValidator
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-connection v3 PCM control plane.
 *
 * Each trusted plugin gets one isolated worker and a bounded drop-oldest queue. A blocked or slow
 * callback can therefore lose old chunks (visible through sequence gaps) without blocking capture
 * or another plugin. The connection owns this object and closes it during every teardown path.
 */
class HostPcmControlV3Impl(
    private val audioRepo: AudioCaptureRepository,
    private val pluginPackage: String,
    private val expectedPluginUid: Int,
    private val callerUidProvider: () -> Int = { Binder.getCallingUid() },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IWearTakPluginHostControlV3.Stub() {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val consumerId = "plugin:$pluginPackage"
    private var activeSubscription: ActiveSubscription? = null
    @Volatile private var closed = false

    override fun subscribePcm(
        request: PcmSubscriptionRequestParcel?,
        callback: IWearTakPcmCallback?,
    ): PcmSubscriptionResultParcel {
        if (!verifyCaller()) return rejected(PcmSubscriptionStatus.UNAUTHORIZED)
        if (request == null) return rejected(PcmSubscriptionStatus.INVALID_REQUEST)
        if (callback == null) return rejected(PcmSubscriptionStatus.CALLBACK_REQUIRED)
        if (!PcmContractValidator.isSupported(request)) {
            return rejected(PcmSubscriptionStatus.INVALID_REQUEST)
        }

        synchronized(lock) {
            if (closed) return rejected(PcmSubscriptionStatus.UNAUTHORIZED)
            activeSubscription?.let { current ->
                // Repeated starts are idempotent. A caller must unsubscribe before replacing its
                // callback so two workers can never exist for the same plugin connection.
                return accepted(current.id)
            }

            val id = UUID.randomUUID().toString()
            val deathRecipient = IBinder.DeathRecipient { stopSubscription(id, notify = false) }
            val subscription = ActiveSubscription(
                id = id,
                request = request,
                callback = callback,
                deathRecipient = deathRecipient,
            )
            activeSubscription = subscription

            try {
                callback.asBinder().linkToDeath(deathRecipient, 0)
            } catch (t: Throwable) {
                activeSubscription = null
                Log.w(TAG, "PCM callback already dead pkg=$pluginPackage", t)
                return rejected(PcmSubscriptionStatus.REMOTE_ERROR)
            }

            audioRepo.acquireConsumer(consumerId)
            subscription.job = scope.launch { runSubscription(subscription) }
            Log.i(TAG, "PCM subscribed pkg=$pluginPackage id=$id batch=${request.requestedChunksPerBatch}")
            return accepted(id)
        }
    }

    override fun unsubscribePcm(subscriptionId: String?): PcmSubscriptionResultParcel {
        if (!verifyCaller()) return rejected(PcmSubscriptionStatus.UNAUTHORIZED)
        val current = synchronized(lock) { activeSubscription }
            ?: return PcmSubscriptionResultParcel(
                accepted = true,
                status = PcmSubscriptionStatus.NOT_ACTIVE,
            )
        if (!subscriptionId.isNullOrBlank() && subscriptionId != current.id) {
            return rejected(PcmSubscriptionStatus.NOT_ACTIVE)
        }
        stopSubscription(current.id, notify = true)
        return PcmSubscriptionResultParcel(accepted = true, subscriptionId = current.id)
    }

    override fun getHostCapabilities(): HostCapabilitiesV3Parcel {
        if (!verifyCaller()) return HostCapabilitiesV3Parcel()
        return HostCapabilitiesV3Parcel(
            contractApiVersion = WearTakPluginV3Contract.CONTRACT_API_VERSION,
            pcmStreaming = true,
        )
    }

    /** Connection-owned cleanup for unbind, disconnect, bind failure, and binder death. */
    fun close() {
        val currentId = synchronized(lock) {
            closed = true
            activeSubscription?.id
        }
        if (currentId != null) stopSubscription(currentId, notify = false)
        scope.cancel()
    }

    private suspend fun runSubscription(subscription: ActiveSubscription) {
        val queue = Channel<AudioCaptureRepository.AudioPcmChunk>(
            capacity = WearTakPluginV3Contract.MAX_PENDING_CHUNKS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        try {
            subscription.callback.onPcmStreamState(
                PcmStreamStateParcel(subscription.id, PcmStreamState.STARTING),
            )
            coroutineScope {
                val collectJob = launch {
                    audioRepo.getPcmChunks.collect { queue.trySend(it) }
                }
                try {
                    dispatchBatches(subscription, queue)
                } finally {
                    collectJob.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (remote: RemoteException) {
            Log.w(TAG, "PCM callback failed pkg=$pluginPackage id=${subscription.id}", remote)
        } catch (t: Throwable) {
            Log.e(TAG, "PCM subscription failed pkg=$pluginPackage id=${subscription.id}", t)
        } finally {
            queue.close()
            finishSubscription(subscription)
        }
    }

    private suspend fun dispatchBatches(
        subscription: ActiveSubscription,
        queue: Channel<AudioCaptureRepository.AudioPcmChunk>,
    ) {
        var listeningSent = false
        while (currentCoroutineContext().isActive) {
            val chunks = mutableListOf(queue.receive())
            val deadline = SystemClock.elapsedRealtime() + WearTakPluginV3Contract.MAX_BATCH_LATENCY_MS
            while (chunks.size < subscription.request.requestedChunksPerBatch) {
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break
                val next = withTimeoutOrNull(remainingMs) { queue.receive() } ?: break
                chunks += next
            }

            if (!listeningSent) {
                subscription.callback.onPcmStreamState(
                    PcmStreamStateParcel(subscription.id, PcmStreamState.LISTENING),
                )
                listeningSent = true
            }
            subscription.callback.onPcmBatch(
                PcmBatchParcel(
                    subscriptionId = subscription.id,
                    chunks = chunks.map(::toParcel),
                ),
            )
        }
    }

    private fun toParcel(chunk: AudioCaptureRepository.AudioPcmChunk): PcmChunkParcel =
        PcmChunkParcel(
            sessionId = chunk.sessionId,
            sequence = chunk.seq,
            timestampUtcMillis = chunk.timestampMs,
            timestampElapsedMillis = chunk.timestampElMs,
            sampleRateHz = chunk.sampleRateHz,
            channelCount = chunk.channels,
            encoding = WearTakPluginV3Contract.PCM_ENCODING,
            samples = chunk.samples,
        )

    private fun stopSubscription(id: String, notify: Boolean) {
        val subscription = synchronized(lock) {
            val current = activeSubscription
            if (current?.id != id) return
            activeSubscription = null
            current
        }
        runCatching { subscription.callback.asBinder().unlinkToDeath(subscription.deathRecipient, 0) }
        subscription.job?.cancel()
        audioRepo.releaseConsumer(consumerId)
        if (notify) {
            scope.launch {
                runCatching {
                    subscription.callback.onPcmStreamState(
                        PcmStreamStateParcel(id, PcmStreamState.STOPPED),
                    )
                }
            }
        }
        Log.i(TAG, "PCM unsubscribed pkg=$pluginPackage id=$id")
    }

    private fun finishSubscription(subscription: ActiveSubscription) {
        val removed = synchronized(lock) {
            if (activeSubscription?.id != subscription.id) {
                false
            } else {
                activeSubscription = null
                true
            }
        }
        if (!removed) return
        runCatching { subscription.callback.asBinder().unlinkToDeath(subscription.deathRecipient, 0) }
        audioRepo.releaseConsumer(consumerId)
    }

    private fun verifyCaller(): Boolean {
        val uid = callerUidProvider()
        if (uid != expectedPluginUid) {
            Log.w(TAG, "v3 PCM uid mismatch expected=$expectedPluginUid got=$uid pkg=$pluginPackage")
            return false
        }
        return true
    }

    private fun accepted(id: String): PcmSubscriptionResultParcel =
        PcmSubscriptionResultParcel(accepted = true, subscriptionId = id)

    private fun rejected(status: Int): PcmSubscriptionResultParcel =
        PcmSubscriptionResultParcel(accepted = false, status = status)

    private data class ActiveSubscription(
        val id: String,
        val request: PcmSubscriptionRequestParcel,
        val callback: IWearTakPcmCallback,
        val deathRecipient: IBinder.DeathRecipient,
        var job: Job? = null,
    )

    companion object {
        private const val TAG = "WearTAK-HostPcmV3"
    }
}
