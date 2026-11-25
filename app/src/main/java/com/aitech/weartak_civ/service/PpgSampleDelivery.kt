/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import java.util.concurrent.atomic.AtomicReference

internal data class RawPpgSample(
    val value: Float,
    val uptimeMillis: Long,
    val emittedAtMillis: Long,
    val sourceAvailable: Boolean,
    val deviceWorn: Boolean,
)

internal object PpgSampleDelivery {
    internal fun interface Consumer {
        fun onSample(sample: RawPpgSample)
    }

    private val consumer = AtomicReference<Consumer?>(null)

    fun acquire(candidate: Consumer): Boolean =
        consumer.compareAndSet(null, candidate)

    fun release(candidate: Consumer): Boolean =
        consumer.compareAndSet(candidate, null)

    fun publish(sample: RawPpgSample) {
        consumer.get()?.onSample(sample)
    }
}
