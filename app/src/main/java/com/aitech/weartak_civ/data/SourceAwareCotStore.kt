/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SourceCotKey(
    val sourceId: String,
    val cotUid: String
)

data class SourceCotUpsertResult(
    val key: SourceCotKey,
    val applied: Boolean,
    val skipped: Boolean = false
)

/**
 * Keeps every source-owned copy while exposing the newest event-time copy of each CoT UID.
 * An optional source priority breaks equal-event-time ties before arrival order.
 * All compound mutations and projections are serialized on [lock].
 */
class SourceAwareCotStore<T>(
    private val sourceIdOf: (T) -> String,
    private val cotUidOf: (T) -> String,
    private val eventTimeOf: (T) -> Long,
    private val receivedAtOf: (T) -> Long,
    private val publicationScope: CoroutineScope? = null,
    private val equivalent: (T, T) -> Boolean = { a, b -> a == b },
    private val onReduction: (coalesced: Long, skipped: Long) -> Unit = { _, _ -> },
    private val sourcePriorityOf: (T) -> Int = { 0 },
    // Coalescible updates for which this returns true stay pending until [flushDeferred]
    // or the next immediate mutation; no timer or projection runs for them.
    private val deferProjection: (SourceCotKey) -> Boolean = { false },
    private val onDeferred: () -> Unit = {}
) {
    private data class Stored<T>(
        val value: T,
        val sequence: Long
    )

    private val lock = Any()
    private val storedByKey = LinkedHashMap<SourceCotKey, Stored<T>>()
    private val removedAtByKey = object : LinkedHashMap<SourceCotKey, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceCotKey, Long>?) = size > 2048
    }
    private val uidOrder = LinkedHashMap<String, Long>()
    private val visibleByUid = HashMap<String, Stored<T>>()
    private var sequence = 0L
    private val pendingKeys = mutableSetOf<SourceCotKey>()
    private var publicationJob: Job? = null
    private var publicationGeneration = 0L

    private val _allItems = MutableStateFlow<Map<SourceCotKey, T>>(emptyMap())
    val allItems: StateFlow<Map<SourceCotKey, T>> = _allItems.asStateFlow()

    private val _visibleItems = MutableStateFlow<List<T>>(emptyList())
    val visibleItems: StateFlow<List<T>> = _visibleItems.asStateFlow()
    private val _publicationVersion = MutableStateFlow(0L)
    val publicationVersion: StateFlow<Long> = _publicationVersion.asStateFlow()

    fun upsert(item: T): SourceCotKey = upsertWithResult(item).key

    fun upsertWithResult(
        item: T,
        coalesce: Boolean = false,
        deferrable: Boolean = false
    ): SourceCotUpsertResult = synchronized(lock) {
        val key = SourceCotKey(sourceIdOf(item), cotUidOf(item))
        require(key.sourceId.isNotBlank()) { "Source ID cannot be blank" }
        require(key.cotUid.isNotBlank()) { "CoT UID cannot be blank" }
        if (removedAtByKey[key]?.let { eventTimeOf(item) <= it } == true) {
            return@synchronized SourceCotUpsertResult(key, applied = false)
        }

        val candidate = nextStored(item)
        val current = storedByKey[key]
        if (current != null && !candidate.isNewerThan(current)) {
            return@synchronized SourceCotUpsertResult(key, applied = false)
        }

        uidOrder.putIfAbsent(key.cotUid, sequence)
        storedByKey[key] = candidate
        val visible = visibleByUid[key.cotUid]
        val unchanged = coalesce && current != null && equivalent(current.value, item) &&
            visible?.let { sourceIdOf(it.value) == key.sourceId } == true
        publishLocked(key, coalesce && current != null, unchanged, deferrable && current != null)
        SourceCotUpsertResult(key, applied = true, skipped = unchanged)
    }

    fun hasPendingProjection(key: SourceCotKey): Boolean = synchronized(lock) { key in pendingKeys }

    /** Projects all pending updates once; returns false when nothing was pending. */
    fun flushDeferred(): Boolean = synchronized(lock) {
        if (pendingKeys.isEmpty()) return@synchronized false
        publishLocked()
        true
    }

    /** Authoritative state, including accepted updates not yet projected to observers. */
    fun snapshot(): Map<SourceCotKey, T> = synchronized(lock) {
        storedByKey.mapValues { it.value.value }
    }

    fun item(key: SourceCotKey): T? = synchronized(lock) {
        storedByKey[key]?.value
    }

    fun visibleItem(cotUid: String): T? = synchronized(lock) {
        visibleEntryLocked(cotUid)?.value?.value
    }

    fun visibleKey(cotUid: String): SourceCotKey? = synchronized(lock) {
        visibleEntryLocked(cotUid)?.key
    }

    fun update(key: SourceCotKey, transform: (T) -> T): T? = synchronized(lock) {
        val current = storedByKey[key] ?: return@synchronized null
        val updated = transform(current.value)
        storedByKey[key] = current.copy(value = updated)
        publishLocked()
        updated
    }

    fun updateVisible(cotUid: String, transform: (T) -> T): T? = synchronized(lock) {
        val key = visibleEntryLocked(cotUid)?.key ?: return@synchronized null
        val current = storedByKey[key] ?: return@synchronized null
        val updated = transform(current.value)
        storedByKey[key] = current.copy(value = updated)
        publishLocked()
        updated
    }

    fun transformAll(transform: (SourceCotKey, T) -> T) = synchronized(lock) {
        var changed = false
        storedByKey.entries.forEach { entry ->
            val updated = transform(entry.key, entry.value.value)
            if (updated !== entry.value.value) {
                entry.setValue(entry.value.copy(value = updated))
                changed = true
            }
        }
        if (changed) publishLocked()
        changed
    }

    fun remove(key: SourceCotKey): T? = synchronized(lock) {
        val removed = storedByKey.remove(key)?.value ?: return@synchronized null
        removeUnusedUidOrderLocked(key.cotUid)
        publishLocked()
        removed
    }

    /** Records a source removal watermark; equal event-time removals win over live copies. */
    fun removeIfNotOlder(
        key: SourceCotKey,
        eventTime: Long,
        receivedAt: Long
    ): T? = synchronized(lock) {
        // A removal is an event-time watermark, even if this source has no current copy.
        removedAtByKey[key] = maxOf(removedAtByKey[key] ?: Long.MIN_VALUE, eventTime)
        val current = storedByKey[key] ?: return@synchronized null
        if (eventTime < eventTimeOf(current.value)) {
            return@synchronized null
        }

        storedByKey.remove(key)
        removeUnusedUidOrderLocked(key.cotUid)
        publishLocked()
        current.value
    }

    fun removeSource(sourceId: String): List<T> = synchronized(lock) {
        val removed = storedByKey
            .filterKeys { it.sourceId == sourceId }
            .values
            .map { it.value }
        if (removed.isEmpty()) return@synchronized emptyList()

        val affectedUids = storedByKey.keys
            .filter { it.sourceId == sourceId }
            .mapTo(mutableSetOf()) { it.cotUid }
        storedByKey.keys.removeAll { it.sourceId == sourceId }
        affectedUids.forEach(::removeUnusedUidOrderLocked)
        publishLocked()
        removed
    }

    /** Bounds only the selected class of objects; legacy contacts/markers keep their behavior. */
    fun pruneSelected(
        selected: (T) -> Boolean,
        expired: (T) -> Boolean,
        maxCount: Int
    ): List<T> = synchronized(lock) {
        require(maxCount > 0)
        val candidates = storedByKey.entries.filter { selected(it.value.value) }
        val expiredKeys = candidates.filter { expired(it.value.value) }.mapTo(mutableSetOf()) { it.key }
        val excess = candidates.filter { it.key !in expiredKeys }
            .sortedBy { receivedAtOf(it.value.value) }.dropLast(maxCount)
        val keys = expiredKeys + excess.map { it.key }
        val removed = keys.mapNotNull { storedByKey.remove(it)?.value }
        if (removed.isNotEmpty()) {
            keys.map { it.cotUid }.forEach(::removeUnusedUidOrderLocked)
            publishLocked()
        }
        removed
    }

    fun removeUid(cotUid: String): List<T> = synchronized(lock) {
        val removed = storedByKey
            .filterKeys { it.cotUid == cotUid }
            .values
            .map { it.value }
        if (removed.isEmpty()) return@synchronized emptyList()

        storedByKey.keys.removeAll { it.cotUid == cotUid }
        uidOrder.remove(cotUid)
        publishLocked()
        removed
    }

    fun clear() = synchronized(lock) {
        storedByKey.clear()
        removedAtByKey.clear()
        uidOrder.clear()
        publishLocked()
    }

    private fun visibleEntryLocked(cotUid: String): Map.Entry<SourceCotKey, Stored<T>>? =
        storedByKey.entries
            .asSequence()
            .filter { it.key.cotUid == cotUid }
            .maxWithOrNull(
                compareBy<Map.Entry<SourceCotKey, Stored<T>>> { eventTimeOf(it.value.value) }
                    .thenBy { sourcePriorityOf(it.value.value) }
                    .thenBy { receivedAtOf(it.value.value) }
                    .thenBy { it.value.sequence }
            )

    private fun removeUnusedUidOrderLocked(cotUid: String) {
        if (storedByKey.keys.none { it.cotUid == cotUid }) {
            uidOrder.remove(cotUid)
        }
    }

    private fun publishLocked(
        upsertKey: SourceCotKey? = null,
        coalesce: Boolean = false,
        unchanged: Boolean = false,
        deferrable: Boolean = false
    ) {
        var visibleChanged = true
        val upserted = upsertKey?.let(storedByKey::get)
        if (upsertKey != null && upserted != null) {
            // An accepted upsert cannot make its source's previous winner older.
            val current = visibleByUid[upsertKey.cotUid]
            if (current != null && !upserted.isNewerThan(current)) {
                visibleChanged = false
            } else {
                visibleByUid[upsertKey.cotUid] = upserted
                visibleChanged = current == null || current.value != upserted.value
            }
        } else {
            visibleByUid.clear()
            storedByKey.forEach { (key, candidate) ->
                val current = visibleByUid[key.cotUid]
                if (current == null || candidate.isNewerThan(current)) {
                    visibleByUid[key.cotUid] = candidate
                }
            }
        }
        // Ordering and watermarks are committed synchronously; only projections wait. A timer
        // never owns an event, so source/session clears and deletes cannot replay queued data.
        if (unchanged) {
            onReduction(0, 1)
            return
        }
        if (deferrable && upsertKey != null && deferProjection(upsertKey)) {
            if (!pendingKeys.add(upsertKey)) onReduction(1, 0)
            onDeferred()
            return
        }
        if (coalesce && publicationScope != null && upsertKey != null) {
            if (!pendingKeys.add(upsertKey)) onReduction(1, 0)
            if (publicationJob?.isActive != true) {
                val generation = ++publicationGeneration
                publicationJob = publicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(1_500)
                    } finally {
                        // Cancellation must not leave an accepted projection permanently pending.
                        // Invalidated timers cannot publish after an immediate mutation or clear.
                        synchronized(lock) {
                            if (generation == publicationGeneration) publishLocked()
                        }
                    }
                }
            }
            return
        }
        publicationGeneration++
        publicationJob?.cancel()
        publicationJob = null
        val hadPending = pendingKeys.isNotEmpty()
        pendingKeys.clear()
        // Keep the cache coherent before a synchronous all-items collector can re-enter.
        _allItems.value = storedByKey.mapValues { it.value.value }
        if (visibleChanged || hadPending) {
            _visibleItems.value = uidOrder.keys.mapNotNull { uid ->
                visibleByUid[uid]?.value
            }
        }
        // Derived repositories observe a completed pair, including hidden-source-only changes.
        _publicationVersion.value += 1
    }

    private fun Stored<T>.isNewerThan(other: Stored<T>): Boolean {
        return isNewer(
            eventTime = eventTimeOf(value),
            priority = sourcePriorityOf(value),
            receivedAt = receivedAtOf(value),
            sequence = sequence,
            other = other
        )
    }

    private fun isNewer(
        eventTime: Long,
        priority: Int,
        receivedAt: Long,
        sequence: Long,
        other: Stored<T>
    ): Boolean {
        val eventTimeComparison = eventTime.compareTo(eventTimeOf(other.value))
        if (eventTimeComparison != 0) return eventTimeComparison > 0

        val priorityComparison = priority.compareTo(sourcePriorityOf(other.value))
        if (priorityComparison != 0) return priorityComparison > 0

        val receivedAtComparison = receivedAt.compareTo(receivedAtOf(other.value))
        return receivedAtComparison > 0 ||
            (receivedAtComparison == 0 && sequence > other.sequence)
    }

    private fun nextStored(item: T): Stored<T> {
        sequence += 1
        return Stored(item, sequence)
    }
}
