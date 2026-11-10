/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.DataRepository.TakServerItem

/** Matches managed records to saved records without treating an editable endpoint as identity. */
object TakServerManagedReconciler {
    fun matchExistingIndexes(
        configured: List<TakServerItem>,
        existing: List<TakServerItem>
    ): List<Int?> {
        val matches = MutableList<Int?>(configured.size) { null }
        val claimed = mutableSetOf<Int>()

        assignUniqueMatches(matches, claimed, configured, existing) { managed, saved ->
            val managedId = TakServerId.normalizeOrNull(managed.serverId)
            managedId != null && managedId == TakServerId.normalizeOrNull(saved.serverId)
        }
        assignUniqueMatches(matches, claimed, configured, existing) { managed, saved ->
            TakServerEndpointIdentity.key(managed) == TakServerEndpointIdentity.key(saved)
        }
        assignUniqueMatches(matches, claimed, configured, existing) { managed, saved ->
            managed.name.trim().equals(saved.name.trim(), ignoreCase = true)
        }

        if (configured.size == existing.size) {
            // Preserve relative position for records whose editable fields all changed.
            val unmatchedConfigured = matches.indices.filter { matches[it] == null }
            val unmatchedExisting = existing.indices.filterNot(claimed::contains)
            unmatchedConfigured.zip(unmatchedExisting).forEach { (configuredIndex, existingIndex) ->
                matches[configuredIndex] = existingIndex
            }
        }
        return matches
    }

    private inline fun assignUniqueMatches(
        matches: MutableList<Int?>,
        claimed: MutableSet<Int>,
        configured: List<TakServerItem>,
        existing: List<TakServerItem>,
        predicate: (TakServerItem, TakServerItem) -> Boolean
    ) {
        matches.indices.forEach { configuredIndex ->
            if (matches[configuredIndex] != null) return@forEach
            val candidates = existing.indices.filter { existingIndex ->
                existingIndex !in claimed && predicate(configured[configuredIndex], existing[existingIndex])
            }
            if (candidates.size == 1) {
                matches[configuredIndex] = candidates.single()
                claimed += candidates.single()
            }
        }
    }
}
