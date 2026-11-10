/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service.crm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TfliteCrmModelRunner(
    context: Context,
    assetName: String = CrmPpgPreprocessor.MODEL_PARAMETERS.modelAssetName
) : CrmModelRunner {
    private val lock = ReentrantLock()
    private val interpreter: Interpreter

    init {
        val start = SystemClock.elapsedRealtime()
        try {
            interpreter = Interpreter(
                loadModel(context, assetName),
                Interpreter.Options().setNumThreads(1)
            )
            val inputShape = interpreter.getInputTensor(0).shape().contentToString()
            val outputShape = interpreter.getOutputTensor(0).shape().contentToString()
            Log.i(TAG, "CRM_TFLITE_LOAD_OK asset=$assetName inputShape=$inputShape outputShape=$outputShape loadMs=${SystemClock.elapsedRealtime() - start}")
        } catch (t: Throwable) {
            Log.e(TAG, "CRM_TFLITE_LOAD_FAIL asset=$assetName", t)
            throw t
        }
    }

    override fun run(scaledSamples: FloatArray): Float {
        val targetSamples =
            CrmPpgPreprocessor.MODEL_PARAMETERS.targetSamplesPerWindow

        require(scaledSamples.size == targetSamples) {
            "CRM input must contain $targetSamples samples"
        }

        val input =
            Array(1) {
                Array(targetSamples) {
                    FloatArray(1)
                }
            }

        for (i in scaledSamples.indices) {
            input[0][i][0] = scaledSamples[i]
        }

        val output = Array(1) { FloatArray(1) }

        return lock.withLock {
            interpreter.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        }
    }

    override fun close() {
        lock.withLock { interpreter.close() }
    }

    companion object {
        private const val TAG = "WearTAK-CrmTflite"

        val ASSET_NAME: String
            get() = CrmPpgPreprocessor.MODEL_PARAMETERS.modelAssetName

        private fun loadModel(
            context: Context,
            assetName: String,
        ): MappedByteBuffer {
            context.assets.openFd(assetName).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    return channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength,
                    )
                }
            }
        }
    }
}
