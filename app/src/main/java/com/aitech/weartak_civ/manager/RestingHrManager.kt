/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.manager

import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.Utils.PhysioUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File


object RestingHrManager {
    private const val TAG = "WearTAK-RestHrMgr"
    private val aggregator           = PhysioUtils.RestingHeartRateAggregator()
    private val hrStream             = MutableSharedFlow<Double>(extraBufferCapacity = 64)
    private val activityStream       = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private lateinit var repo        : DataRepository
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var initialized = false
    private val logFile = File("/sdcard/Download", "hrMgr.log")

    fun init(repository: DataRepository, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        if (initialized) return
        initialized = true
        repo = repository
        ioDispatcher = dispatcher
//        Utils.writeToLogFileLocation(logFile, "Initialized RestingHrManager \n ----------------------------- \n")
//
//        scope.launch {
//            hrStream
//                .combine(activityStream) { hr, act -> hr to act }
//                .filter { (_, act) -> act == "STILL" }
//                .collect { (hr, _) ->
//                    aggregator.pushSample(hr)
//                    aggregator.computeAverage()?.let { avg ->
//                        repo.storeRestingHeartRate(avg.roundToInt());
//                        val logString = "RestingAvgHR: ${avg.roundToInt()}"
//                        Log.i(TAG, logString)
//                        Utils.writeToLogFileLocation(logFile, logString)
//                    }
//                }
//        }
    }

    fun stop() {
        if (!initialized) return
        scope.cancel()
        initialized = false
    }

    suspend fun onHeartRateSample(bpm: Double)    = hrStream.emit(bpm)
    suspend fun onActivityRecognized(act: String) = activityStream.emit(act)
}