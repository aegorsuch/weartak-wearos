/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import java.util.UUID

object TakServerId {
    fun newId(): String = UUID.randomUUID().toString()

    fun normalizeOrNull(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val canonical = runCatching { UUID.fromString(candidate) }
            .getOrNull()
            ?.toString()
            ?: return null
        return canonical.takeIf { it.equals(candidate, ignoreCase = true) }
    }

    fun ensure(value: String?): String = normalizeOrNull(value) ?: newId()

    fun ensureUnique(value: String?, usedIds: MutableSet<String>): String {
        val existing = normalizeOrNull(value)
        if (existing != null && usedIds.add(existing)) return existing

        var generated: String
        do {
            generated = newId()
        } while (!usedIds.add(generated))
        return generated
    }
}
