/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CrmDataRepository.CrmSamplingState
import com.aitech.weartak_civ.service.CrmInferenceService
import com.aitech.weartak_civ.service.PpgSampleDelivery
import com.aitech.weartak_civ.service.RawPpgSample
import com.aitech.weartak_civ.service.SkinTempService
import com.weartak.plugin.sdk.ppg.v1.IPpgCallbackV1
import com.weartak.plugin.sdk.ppg.v1.IPpgHostV1
import com.weartak.plugin.sdk.ppg.v1.PpgBatchV1
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import com.weartak.plugin.sdk.ppg.v1.PpgSampleV1
import com.weartak.plugin.sdk.ppg.v1.PpgStateV1
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class PpgHostV1Impl(
    private val app: WearTakApplication,
    private val expectedUid: Int,
    private val isSessionActive: () -> Boolean,
    private val callerUid: () -> Int = { Binder.getCallingUid() },
) : IPpgHostV1.Stub() {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active: Subscription? = null
    private var closed = false

    override fun subscribe(subscriptionId: String?, callback: IPpgCallbackV1?): Int {
        if (callerUid() != expectedUid) return PpgContractV1.UNAUTHORIZED
        if (subscriptionId.isNullOrBlank() ||
            subscriptionId.length > PpgContractV1.MAX_SUBSCRIPTION_ID_CHARS ||
            callback == null
        ) return PpgContractV1.INVALID_REQUEST
        if (!isSessionActive()) return PpgContractV1.INACTIVE_SESSION

        synchronized(lock) {
            if (closed || !isSessionActive()) return PpgContractV1.INACTIVE_SESSION
            if (active != null || CrmInferenceService.isRunning.get()) return PpgContractV1.BUSY
            val subscription = Subscription(subscriptionId, callback)
            if (!PpgSampleDelivery.acquire(subscription)) return PpgContractV1.BUSY
            try {
                callback.asBinder().linkToDeath(subscription.death, 0)
            } catch (error: RemoteException) {
                PpgSampleDelivery.release(subscription)
                Log.w(TAG, "PPG callback already dead", error)
                return PpgContractV1.UNAVAILABLE
            }
            active = subscription
            app.crmRepo.updateSamplingState(CrmSamplingState.STARTING)
            subscription.deliveryJob = scope.launch { deliver(subscription) }
            subscription.stateJob = scope.launch {
                app.crmRepo.samplingState.collect { state ->
                    if (state == CrmSamplingState.ERROR) {
                        fail(subscription, "sensor_error")
                    } else {
                        subscription.offer(Frame.State(toState(subscription.id, state)))
                    }
                }
            }
            mainHandler.post {
                if (isActive(subscription)) {
                    try {
                        ContextCompat.startForegroundService(
                            app,
                            Intent(app, SkinTempService::class.java).setAction(
                                SkinTempService.ACTION_START_CRM_PPG,
                            ),
                        )
                        subscription.startIssued = true
                    } catch (error: IllegalStateException) {
                        Log.e(TAG, "Unable to start PPG source", error)
                        fail(subscription, "source_start_failed")
                    } catch (error: SecurityException) {
                        Log.e(TAG, "PPG source permission denied", error)
                        fail(subscription, "source_permission_denied")
                    }
                } else {
                    stop(subscription)
                }
            }
        }
        return PpgContractV1.OK
    }

    override fun unsubscribe(subscriptionId: String?): Int {
        if (callerUid() != expectedUid) return PpgContractV1.UNAUTHORIZED
        if (subscriptionId.isNullOrBlank() ||
            subscriptionId.length > PpgContractV1.MAX_SUBSCRIPTION_ID_CHARS
        ) return PpgContractV1.INVALID_REQUEST
        if (!isSessionActive()) return PpgContractV1.INACTIVE_SESSION
        val subscription = synchronized(lock) {
            active?.takeIf { it.id == subscriptionId }
        } ?: return PpgContractV1.INVALID_REQUEST
        stop(subscription)
        return PpgContractV1.OK
    }

    fun close() {
        val subscription = synchronized(lock) {
            closed = true
            active
        }
        if (subscription != null) stop(subscription)
        scope.cancel()
    }

    private inner class Subscription(
        val id: String,
        val callback: IPpgCallbackV1,
    ) : PpgSampleDelivery.Consumer {
        val queue = Channel<Frame>(QUEUE_CAPACITY)
        val terminal = AtomicBoolean(false)
        val death = IBinder.DeathRecipient { stop(this) }
        var deliveryJob: Job? = null
        var stateJob: Job? = null
        var startIssued = false

        override fun onSample(sample: RawPpgSample) {
            offer(Frame.Sample(sample))
        }

        fun offer(frame: Frame) {
            if (!terminal.get() && queue.trySend(frame).isFailure) fail(this, "delivery_overflow")
        }
    }

    private sealed interface Frame {
        data class State(val value: PpgStateV1) : Frame
        data class Sample(val value: RawPpgSample) : Frame
        data class Failure(val reason: String) : Frame
    }

    private suspend fun deliver(subscription: Subscription) {
        var sequence = 0L
        try {
            for (frame in subscription.queue) {
                if (!isActive(subscription)) return
                when (frame) {
                    is Frame.State -> subscription.callback.onState(frame.value)
                    is Frame.Sample -> {
                        val sample = frame.value
                        subscription.callback.onSamples(
                            PpgBatchV1(
                                subscriptionId = subscription.id,
                                sequence = sequence++,
                                samples = listOf(
                                    PpgSampleV1(
                                        rawValue = sample.value,
                                        uptimeMillis = sample.uptimeMillis,
                                        sourceAvailable = sample.sourceAvailable,
                                        deviceWorn = sample.deviceWorn,
                                        emittedAtMillis = sample.emittedAtMillis,
                                        reason = null,
                                    )
                                ),
                            )
                        )
                    }
                    is Frame.Failure -> {
                        subscription.callback.onState(
                            PpgStateV1(
                                subscription.id, PpgContractV1.ERROR,
                                PpgContractV1.SOURCE_SAMSUNG_PPG_ON_DEMAND,
                                currentWornState(), frame.reason,
                            )
                        )
                        return
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RemoteException) {
            Log.w(TAG, "PPG callback failed", error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "PPG callback delivery failed", error)
        } finally {
            stop(subscription)
        }
    }

    private fun fail(subscription: Subscription, reason: String) {
        if (!subscription.terminal.compareAndSet(false, true)) return
        PpgSampleDelivery.release(subscription)
        subscription.stateJob?.cancel()
        synchronized(lock) {
            if (active !== subscription) return
            postStop(subscription)
        }
        while (subscription.queue.tryReceive().isSuccess) { }
        subscription.queue.trySend(Frame.Failure(reason))
        subscription.queue.close()
    }

    private fun stop(subscription: Subscription) {
        synchronized(lock) {
            if (active !== subscription) return
            active = null
            subscription.terminal.set(true)
            PpgSampleDelivery.release(subscription)
            postStop(subscription)
        }
        try {
            subscription.callback.asBinder().unlinkToDeath(subscription.death, 0)
        } catch (error: NoSuchElementException) {
            Log.d(TAG, "PPG callback death recipient already removed", error)
        }
        subscription.stateJob?.cancel()
        subscription.queue.cancel()
        subscription.deliveryJob?.cancel()
    }

    private fun postStop(subscription: Subscription) {
        mainHandler.post {
            if (subscription.startIssued) {
                try {
                    app.startService(
                        Intent(app, SkinTempService::class.java).setAction(
                            SkinTempService.ACTION_STOP_CRM_PPG,
                        ),
                    )
                } catch (error: IllegalStateException) {
                    Log.e(TAG, "Unable to stop PPG source", error)
                } catch (error: SecurityException) {
                    Log.e(TAG, "Unable to stop PPG source", error)
                }
            }
        }
    }

    private fun isActive(subscription: Subscription): Boolean =
        synchronized(lock) { !closed && active === subscription } && isSessionActive()

    private fun toState(id: String, state: CrmSamplingState): PpgStateV1 =
        PpgStateV1(
            id,
            when (state) {
                CrmSamplingState.IDLE -> PpgContractV1.IDLE
                CrmSamplingState.STARTING -> PpgContractV1.STARTING
                CrmSamplingState.SAMPLING -> PpgContractV1.SAMPLING
                CrmSamplingState.STOPPING -> PpgContractV1.STOPPING
                CrmSamplingState.NOT_WORN -> PpgContractV1.STATE_NOT_WORN
                CrmSamplingState.UNAVAILABLE -> PpgContractV1.STATE_UNAVAILABLE
                CrmSamplingState.ERROR -> PpgContractV1.ERROR
            },
            PpgContractV1.SOURCE_SAMSUNG_PPG_ON_DEMAND,
            currentWornState(),
            null,
        )

    private fun currentWornState(): Int =
        when (app.repo.latestDeviceWornObservation.value) {
            true -> PpgContractV1.WORN
            false -> PpgContractV1.NOT_WORN
            null -> PpgContractV1.WORN_UNKNOWN
        }

    private companion object {
        const val QUEUE_CAPACITY = 256
        const val TAG = "WearTAK-PpgHost"
    }
}
