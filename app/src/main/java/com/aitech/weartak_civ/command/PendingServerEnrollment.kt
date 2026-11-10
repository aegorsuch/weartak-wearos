/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.command

import java.util.UUID

/** A server enrollment requested over chat that is waiting for the user to enter credentials. */
data class ServerEnrollment(val token: String, val name: String, val address: String, val port: Int)

/**
 * Holds at most one pending enrollment in memory. It never contains credentials; those are entered
 * on the watch. The token ties the prompt that was shown to the settings screen that opens it.
 */
object PendingServerEnrollment {
    @Volatile private var pending: ServerEnrollment? = null

    fun offer(name: String, address: String, port: Int): ServerEnrollment =
        ServerEnrollment(UUID.randomUUID().toString(), name, address, port).also { pending = it }

    fun take(token: String?): ServerEnrollment? {
        val current = pending ?: return null
        if (token == null || current.token != token) return null
        pending = null
        return current
    }

    fun clear() { pending = null }
}