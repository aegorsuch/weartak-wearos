/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks whether anyone is looking at passive SA (other users' and sensor tracks).
 *
 * With no viewer, coalescible track moves only update the source-owned store and the
 * projection is deferred; the first viewer triggers one refresh from the store. New tracks,
 * removals, stale events and anything non-coalescible (chat, alerts, points, routes) are
 * never deferred. The active Bloodhound target is exempt so proximity alerts stay live.
 * Disabled until [enable] so tests and secondary repositories keep immediate projection.
 */
internal class PassiveSaDemand {
    private val lock = Any()
    private val consumers = mutableSetOf<Any>()
    @Volatile private var enabled = false
    @Volatile private var viewed = false
    @Volatile private var exemptUid: String? = null
    private var onDemand: () -> Unit = {}

    private val deferredCount = AtomicLong()
    private val flushCount = AtomicLong()
    val deferredUpdates: Long get() = deferredCount.get()
    val flushes: Long get() = flushCount.get()

    fun enable() {
        enabled = true
    }

    /** Invoked outside the demand lock whenever a deferred projection may now be needed. */
    fun setOnDemand(listener: () -> Unit) = synchronized(lock) { onDemand = listener }

    fun shouldDefer(uid: String): Boolean = enabled && !viewed && uid != exemptUid

    fun recordDeferred() {
        deferredCount.incrementAndGet()
    }

    fun recordFlush() {
        flushCount.incrementAndGet()
    }

    fun request(consumer: Any) = update { consumers.add(consumer) }

    fun release(consumer: Any) = update { consumers.remove(consumer) }

    /** The active Bloodhound target needs live positions even with the screen off. */
    fun setExemptUid(uid: String?) {
        val listener = synchronized(lock) {
            val changed = exemptUid != uid
            exemptUid = uid
            onDemand.takeIf { changed && uid != null }
        }
        listener?.invoke()
    }

    private fun update(change: () -> Unit) {
        val listener = synchronized(lock) {
            val wasViewed = viewed
            change()
            viewed = consumers.isNotEmpty()
            onDemand.takeIf { !wasViewed && viewed }
        }
        listener?.invoke()
    }
}

/** Requests passive SA while [lifecycle] is resumed, i.e. visible and interactive. */
internal fun observePassiveSaDemand(lifecycle: Lifecycle, demand: PassiveSaDemand) {
    val consumer = Any()
    fun update() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) demand.request(consumer)
        else demand.release(consumer)
    }
    lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            update()
            if (event == Lifecycle.Event.ON_DESTROY) source.lifecycle.removeObserver(this)
        }
    })
    update()
}
