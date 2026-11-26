/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.aitech.weartak_civ.integration.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DartAudioConsent(
    private val read: suspend () -> Boolean,
    private val write: suspend (Boolean) -> Unit,
) {
    private val lock = Any()
    private val operations = Mutex()
    private val enabledState = MutableStateFlow(false)
    val enabled = enabledState.asStateFlow()
    private val revocations = mutableSetOf<() -> Unit>()
    private var initialized = false
    private var revision = 0L

    suspend fun initialize() = operations.withLock {
        initializeLocked()
    }

    suspend fun setEnabled(enabled: Boolean) {
        val request = synchronized(lock) {
            revision++
            if (!enabled) updateLocked(false)
            revision
        }
        operations.withLock {
            if (synchronized(lock) { request != revision }) return@withLock
            if (enabled) synchronized(lock) {
                if (request == revision) updateLocked(true)
            }
            var written = false
            try {
                write(enabled)
                written = true
                initialized = true
            } finally {
                if (!written) synchronized(lock) {
                    if (request == revision) updateLocked(false)
                }
            }
            synchronized(lock) {
                if (request == revision) updateLocked(enabled)
            }
        }
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        val saved = read()
        synchronized(lock) {
            if (revision == 0L) updateLocked(saved)
        }
        initialized = true
    }

    fun <T> whileEnabled(action: () -> T): T? = synchronized(lock) {
        if (enabledState.value) action() else null
    }

    fun addRevocation(action: () -> Unit) = synchronized(lock) {
        revocations.add(action)
    }

    fun removeRevocation(action: () -> Unit) = synchronized(lock) {
        revocations.remove(action)
    }

    private fun updateLocked(enabled: Boolean) {
        val wasEnabled = enabledState.value
        enabledState.value = enabled
        if (wasEnabled && !enabled) revocations.toList().forEach { it() }
    }
}
