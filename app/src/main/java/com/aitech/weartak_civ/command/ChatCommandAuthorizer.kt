/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.command

import java.util.Locale

enum class ChatCommandDecision { ALLOWED, SENDER_NOT_ALLOWED, STALE, DUPLICATE }

/**
 * Decides whether a parsed chat command may run. Senders are matched by callsign (case-insensitive),
 * so the allow-list is a convenience filter, not authentication: anyone who can pick an allowed
 * callsign on the same channel can send commands. Credential enrollment explicitly accepts
 * this risk; transport addressing and this filter must not be described as authentication.
 */
class ChatCommandAuthorizer(
    private val maxAgeMs: Long = 5 * 60_000L,
    private val maxRemembered: Int = 128
) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun authorize(
        senderCallsign: String,
        allowedCallsigns: Collection<String>,
        messageKey: String,
        sentAtMs: Long,
        nowMs: Long
    ): ChatCommandDecision {
        val sender = normalize(senderCallsign)
        if (sender.isEmpty() || allowedCallsigns.none { normalize(it) == sender }) {
            return ChatCommandDecision.SENDER_NOT_ALLOWED
        }
        if (sentAtMs <= 0 || nowMs - sentAtMs > maxAgeMs || sentAtMs - nowMs > maxAgeMs) {
            return ChatCommandDecision.STALE
        }
        if (!seen.add(messageKey)) return ChatCommandDecision.DUPLICATE
        while (seen.size > maxRemembered) seen.remove(seen.first())
        return ChatCommandDecision.ALLOWED
    }

    companion object {
        fun normalize(callsign: String) = callsign.trim().lowercase()

        fun parseAllowList(text: String): List<String> =
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::normalize)

        fun sortedAllowList(callsigns: Collection<String>): List<String> =
            callsigns.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy(::normalize)
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

        fun validatedCallsign(value: String): String? =
            ChatCommandValidation.callsign(value)?.uppercase(Locale.ROOT)
    }
}