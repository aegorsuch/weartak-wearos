/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import com.aitech.weartak_civ.service.audio.AudioDetectionRuntime
import com.aitech.weartak_civ.service.audio.AudioDetectionSpec
import com.aitech.weartak_civ.service.audio.AudioDetectionTrigger
import com.aitech.weartak_civ.service.audio.collectAudioDetectionFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

abstract class BaseAudioDetectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    protected val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    protected val audioRepo by lazy {
        try {
            (applicationContext as WearTakApplication).audioRepo
        } catch (_: Throwable) {
            AudioCaptureRepository(applicationContext)
        }
    }

    protected abstract val logTag: String
    protected abstract val notificationId: Int
    protected abstract val runningFlag: AtomicBoolean
    protected abstract val spec: AudioDetectionSpec
    protected abstract val feedbackFlow: Flow<AudioDetectionFeedback>
    protected abstract val thresholdFlow: Flow<Float>
    protected abstract val runtime: AudioDetectionRuntime

    private var feedbackJob: Job? = null
    private var thresholdJob: Job? = null
    @Volatile private var currentTriggerThreshold: Float = 0f

    protected fun createRuntime(onTrigger: suspend (AudioDetectionTrigger) -> Unit): AudioDetectionRuntime =
        AudioDetectionRuntime(
            context = applicationContext,
            spec = spec,
            scope = scope,
            audioRepo = audioRepo,
            pcmFlow = audioRepo.getPcmChunks,
            thresholdProvider = { currentTriggerThreshold },
            onTrigger = onTrigger
        )

    protected abstract fun getEventFromUid(uid: String): AudioDetectionEvent?

    protected abstract suspend fun logFeedback(
        event: AudioDetectionEvent,
        feedback: AudioDetectionFeedback
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "${javaClass.simpleName} onCreate()")
        runningFlag.set(true)
        currentTriggerThreshold = spec.threshold
        startForegroundServiceCompat(notificationId, importance = NotificationManager.IMPORTANCE_LOW)
        thresholdJob = scope.launch {
            thresholdFlow.collect { threshold ->
                currentTriggerThreshold = threshold
            }
        }
        runtime.start()
        feedbackJob = scope.launch {
            collectAudioDetectionFeedback(
                logTag = logTag,
                feedbackPrefix = spec.feedbackPrefix,
                feedbackFlow = feedbackFlow,
                getEventFromUid = ::getEventFromUid,
                logFeedback = ::logFeedback
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "${javaClass.simpleName} onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runningFlag.set(false)
        thresholdJob?.cancel()
        feedbackJob?.cancel()
        runtime.shutdown()
        scope.cancel()
        stopForegroundServiceCompat()
        super.onDestroy()
        Log.d(logTag, "${javaClass.simpleName} onDestroy()")
    }
}
