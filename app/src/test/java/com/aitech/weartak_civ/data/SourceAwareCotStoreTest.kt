/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random

class SourceAwareCotStoreTest {
    @Test
    fun batchTransformPublishesOnlyCompletedStateAndKeepsSourceOrdering() = runBlocking {
        val store = store()
        val hidden = Item("server-a", "same", 100L, 100L, "hidden")
        store.upsert(hidden)
        store.upsert(Item("server-b", "same", 200L, 200L, "visible"))
        store.upsert(Item("server-a", "other", 100L, 100L, "other"))
        val snapshots = mutableListOf<List<Item>>()
        val collector = launch(Dispatchers.Unconfined) {
            store.visibleItems.collect { snapshots.add(it) }
        }
        try {
            val changed = store.transformAll { key, item ->
                if (key == SourceCotKey("server-a", "same")) item
                else item.copy(value = "updated-${item.value}")
            }

            assertTrue(changed)
            assertEquals(2, snapshots.size)
            assertEquals(listOf("updated-visible", "updated-other"),
                snapshots.last().map(Item::value))
            assertSame(hidden, store.item(SourceCotKey("server-a", "same")))
            assertEquals(SourceCotKey("server-b", "same"), store.visibleKey("same"))

            val all = store.allItems.value
            val visible = store.visibleItems.value
            assertFalse(store.transformAll { _, item -> item })
            assertSame(all, store.allItems.value)
            assertSame(visible, store.visibleItems.value)
            assertEquals(2, snapshots.size)
        } finally {
            collector.cancel()
        }
    }

    private data class Item(
        val sourceId: String,
        val uid: String,
        val eventTime: Long,
        val receivedAt: Long,
        val value: String
    )

    private fun store() = SourceAwareCotStore(
        sourceIdOf = Item::sourceId,
        cotUidOf = Item::uid,
        eventTimeOf = Item::eventTime,
        receivedAtOf = Item::receivedAt
    )

    @Test
    fun keepsDuplicateUidsAndRestoresTheRemainingCopyAfterSourceClear() {
        val store = store()
        store.upsert(Item("server-a", "same-uid", 100L, 100L, "A"))
        store.upsert(Item("server-b", "same-uid", 200L, 200L, "B"))

        assertEquals(listOf("B"), store.visibleItems.value.map(Item::value))
        assertEquals(2, store.allItems.value.size)

        store.removeSource("server-b")

        assertEquals(listOf("A"), store.visibleItems.value.map(Item::value))
        assertEquals(1, store.allItems.value.size)
    }

    @Test
    fun clearSourceDoesNotRemoveOtherServerOrLocalItems() {
        val store = store()
        store.upsert(Item("server-a", "a", 100L, 100L, "A"))
        store.upsert(Item("server-b", "b", 100L, 100L, "B"))
        store.upsert(Item("non-tak:local", "local", 100L, 100L, "Local"))

        store.removeSource("server-a")

        assertEquals(setOf("B", "Local"), store.visibleItems.value.map(Item::value).toSet())
        assertTrue(store.allItems.value.keys.none { it.sourceId == "server-a" })
    }

    @Test
    fun newerEventTimeWinsEvenWhenOlderEventArrivesLater() {
        val store = store()
        store.upsert(Item("server-a", "same-uid", 200L, 100L, "Newer event"))
        store.upsert(Item("server-b", "same-uid", 100L, 200L, "Later arrival"))

        assertEquals(listOf("Newer event"), store.visibleItems.value.map(Item::value))
        assertEquals("server-a", store.visibleKey("same-uid")?.sourceId)
    }

    @Test
    fun olderSameSourceUpdateIsIgnored() {
        val store = store()
        val key = store.upsert(Item("server-a", "same-uid", 200L, 100L, "Newer event"))

        val result = store.upsertWithResult(
            Item("server-a", "same-uid", 100L, 200L, "Older event")
        )

        assertFalse(result.applied)
        assertEquals("Newer event", store.item(key)?.value)
        assertEquals(listOf("Newer event"), store.visibleItems.value.map(Item::value))
    }

    @Test
    fun equalEventTimesUseReceiptThenInsertionOrderAsTieBreakers() {
        val store = store()
        store.upsert(Item("server-a", "same-uid", 100L, 100L, "A"))
        store.upsert(Item("server-b", "same-uid", 100L, 200L, "B"))

        assertEquals(listOf("B"), store.visibleItems.value.map(Item::value))

        store.upsert(Item("server-a", "same-uid", 100L, 200L, "A refreshed"))

        assertEquals(listOf("A refreshed"), store.visibleItems.value.map(Item::value))
    }

    @Test
    fun olderRemovalDoesNotDeleteNewerSourceCopy() {
        val store = store()
        val key = store.upsert(Item("server-a", "same-uid", 200L, 100L, "Current"))

        assertNull(store.removeIfNotOlder(key, eventTime = 100L, receivedAt = 200L))
        assertEquals("Current", store.item(key)?.value)

        assertEquals(
            "Current",
            store.removeIfNotOlder(key, eventTime = 200L, receivedAt = 300L)?.value
        )
        assertNull(store.item(key))
    }

    @Test
    fun removalWatermarkRejectsReplayIncludingEqualTimeButNotOtherSources() {
        val store = store()
        val key = SourceCotKey("ble", "point")
        assertNull(store.removeIfNotOlder(key, 200, 300))
        assertFalse(store.upsertWithResult(Item("ble", "point", 100, 400, "Old")).applied)
        assertFalse(store.upsertWithResult(Item("ble", "point", 200, 500, "Replay")).applied)
        assertTrue(store.upsertWithResult(Item("tak", "point", 100, 400, "TAK")).applied)
        assertTrue(store.upsertWithResult(Item("ble", "point", 201, 600, "New")).applied)
        assertNull(store.removeIfNotOlder(key, 200, 700))
        assertEquals("New", store.item(key)?.value)
    }

    @Test
    fun acceptedUpsertComparesOnlyItsUidWhileKeepingEverySourceCopy() {
        var timeReads = 0
        val store = SourceAwareCotStore(Item::sourceId, Item::uid,
            eventTimeOf = { timeReads++; it.eventTime }, receivedAtOf = Item::receivedAt)
        repeat(512) { uid ->
            store.upsert(Item("a", "$uid", 100, 100, "A"))
            store.upsert(Item("b", "$uid", 200, 200, "B"))
        }
        timeReads = 0
        assertTrue(store.upsertWithResult(Item("a", "250", 300, 300, "new")).applied)
        assertEquals(4, timeReads)
        assertEquals(1024, store.allItems.value.size)
        assertEquals(512, store.visibleItems.value.size)
        assertEquals("new", store.visibleItems.value[250].value)
        timeReads = 0
        assertTrue(store.upsertWithResult(Item("b", "250", 201, 301, "hidden")).applied)
        assertEquals(4, timeReads)
        assertEquals("hidden", store.item(SourceCotKey("b", "250"))?.value)
        assertEquals("new", store.visibleItem("250")?.value)
    }

    @Test
    fun equalPayloadStillUpdatesTieSequenceAndSourceRemovalRevealsCorrectCopy() {
        val store = store()
        val first = Item("a", "same", 100, 100, "A")
        store.upsert(first)
        store.upsert(Item("b", "same", 100, 100, "B"))
        store.upsert(first)
        assertEquals("a", store.visibleKey("same")?.sourceId)
        store.upsert(first)
        store.removeSource("a")
        assertEquals("b", store.visibleKey("same")?.sourceId)
        assertEquals("B", store.visibleItems.value.single().value)
    }

    @Test
    fun synchronousAllItemsCollectorCanReenterWithoutAnIncompleteVisibleProjection() = runBlocking {
        val store = store()
        val first = Item("a", "first", 100, 100, "first")
        val second = Item("b", "second", 100, 100, "second")
        val snapshots = mutableListOf<List<Item>>()
        val visible = launch(Dispatchers.Unconfined) {
            store.visibleItems.collect { snapshots.add(it) }
        }
        var reentered = false
        val all = launch(Dispatchers.Unconfined) {
            store.allItems.collect {
                if (it.size == 1 && !reentered) {
                    reentered = true
                    store.upsert(second)
                }
            }
        }
        try {
            store.upsert(first)
            assertTrue(reentered)
            assertEquals(listOf(emptyList(), listOf(first, second)), snapshots)
            assertEquals(listOf(first, second), store.visibleItems.value)
        } finally {
            all.cancel()
            visible.cancel()
        }
    }

    @Test
    fun concurrentSourcesKeepAllAcceptedCopiesAndRestoreWinnersOnRemoval() = runBlocking {
        val store = store()
        (0 until 4).map { source ->
            async(Dispatchers.Default) {
                repeat(256) { index ->
                    assertTrue(store.upsertWithResult(Item("s$source", "${index % 16}",
                        index.toLong(), index.toLong(), "value-$index")).applied)
                }
            }
        }.awaitAll()
        assertEquals(64, store.allItems.value.size)
        assertEquals(16, store.visibleItems.value.size)
        assertTrue(store.visibleItems.value.all { it.eventTime == 240L + it.uid.toInt() })
        repeat(4) { source ->
            assertEquals(16, store.removeSource("s$source").size)
            assertEquals(if (source == 3) 0 else 16, store.visibleItems.value.size)
            assertTrue(store.visibleItems.value.all { it.eventTime == 240L + it.uid.toInt() })
        }
    }

    @Test
    fun incrementalUpsertsMatchFullProjectionAcrossMixedMutationsAndEveryEmission() = runBlocking {
        val random = Random(704)
        val store = store()
        val reference = ReferenceProjection()
        val visibleEmissions = mutableListOf<List<Item>>()
        val allEmissions = mutableListOf<Map<SourceCotKey, Item>>()
        val visibleCollector = launch(Dispatchers.Unconfined) {
            store.visibleItems.collect { visibleEmissions.add(it) }
        }
        val allCollector = launch(Dispatchers.Unconfined) {
            store.allItems.collect { allEmissions.add(it) }
        }
        val expectedVisible = mutableListOf<List<Item>>(emptyList())
        val expectedAll = mutableListOf<Map<SourceCotKey, Item>>(emptyMap())
        try {
            repeat(3000) { step ->
                val key = SourceCotKey("source-${random.nextInt(4)}", "${random.nextInt(24)}")
                val item = Item(key.sourceId, key.cotUid, random.nextLong(1, 100),
                    random.nextLong(1, 100), "value-${random.nextInt(6)}")
                when (random.nextInt(20)) {
                    in 0..9 -> assertEquals(reference.upsert(item), store.upsertWithResult(item).applied)
                    10 -> assertEquals(reference.remove(key), store.remove(key))
                    11 -> assertEquals(reference.removeIfNotOlder(key, item.eventTime),
                        store.removeIfNotOlder(key, item.eventTime, item.receivedAt))
                    12 -> assertEquals(reference.removeSource(key.sourceId), store.removeSource(key.sourceId))
                    13 -> assertEquals(reference.removeUid(key.cotUid), store.removeUid(key.cotUid))
                    14 -> {
                        val transform: (Item) -> Item = { it.copy(eventTime = item.eventTime, value = item.value) }
                        assertEquals(reference.update(key, transform), store.update(key, transform))
                    }
                    15 -> {
                        val transform: (Item) -> Item = { it.copy(receivedAt = item.receivedAt, value = item.value) }
                        assertEquals(reference.updateVisible(key.cotUid, transform),
                            store.updateVisible(key.cotUid, transform))
                    }
                    16 -> {
                        val transform: (SourceCotKey, Item) -> Item = { k, old ->
                            if (k.sourceId == key.sourceId) old.copy(eventTime = item.eventTime) else old
                        }
                        assertEquals(reference.transformAll(transform), store.transformAll(transform))
                    }
                    17 -> {
                        assertEquals(reference.prune(), store.pruneSelected(
                            selected = { it.uid.toInt() % 2 == 0 }, expired = { it.eventTime < 20 }, maxCount = 5))
                    }
                    18 -> if (step % 41 == 0) { reference.clear(); store.clear() }
                    else -> Unit
                }
                val expected = reference.all()
                val visible = reference.visible()
                assertEquals("all at step $step", expected, store.allItems.value)
                assertEquals(expected.keys.toList(), store.allItems.value.keys.toList())
                assertEquals("visible at step $step", visible, store.visibleItems.value)
                repeat(24) { uid ->
                    assertEquals(reference.visibleKey("$uid"), store.visibleKey("$uid"))
                    assertEquals(reference.visible().firstOrNull { it.uid == "$uid" }, store.visibleItem("$uid"))
                }
                if (expectedAll.last() != expected) expectedAll.add(expected)
                if (expectedVisible.last() != visible) expectedVisible.add(visible)
                assertEquals("all emissions at step $step", expectedAll, allEmissions)
                assertEquals("visible emissions at step $step", expectedVisible, visibleEmissions)
            }
        } finally {
            visibleCollector.cancel()
            allCollector.cancel()
        }
    }

    // Independent full-scan model of the incumbent event/receipt/sequence projection.
    private class ReferenceProjection {
        data class Entry(val item: Item, val sequence: Long)
        private val entries = linkedMapOf<SourceCotKey, Entry>()
        private val order = linkedSetOf<String>()
        private val removedAt = mutableMapOf<SourceCotKey, Long>()
        private var sequence = 0L
        private val rank = compareBy<Entry> { it.item.eventTime }.thenBy { it.item.receivedAt }.thenBy { it.sequence }

        fun upsert(item: Item): Boolean {
            val key = SourceCotKey(item.sourceId, item.uid)
            if (removedAt[key]?.let { item.eventTime <= it } == true) return false
            val next = Entry(item, ++sequence)
            if (entries[key]?.let { rank.compare(next, it) <= 0 } == true) return false
            entries[key] = next
            order.add(key.cotUid)
            return true
        }

        fun all() = entries.mapValues { it.value.item }
        fun visible() = order.mapNotNull { uid -> visibleKey(uid)?.let { entries[it]?.item } }
        fun visibleKey(uid: String) = entries.entries.filter { it.key.cotUid == uid }
            .maxWithOrNull(compareBy<Map.Entry<SourceCotKey, Entry>> { it.value.item.eventTime }.thenBy { it.value.item.receivedAt }
                .thenBy { it.value.sequence })?.key
        fun remove(key: SourceCotKey): Item? = entries.remove(key)?.item?.also {
            if (entries.keys.none { it.cotUid == key.cotUid }) order.remove(key.cotUid)
        }
        fun removeIfNotOlder(key: SourceCotKey, time: Long): Item? {
            removedAt[key] = maxOf(removedAt[key] ?: Long.MIN_VALUE, time)
            return entries[key]?.takeIf { time >= it.item.eventTime }?.let { remove(key) }
        }
        fun removeSource(source: String) = entries.keys.filter { it.sourceId == source }.mapNotNull(::remove)
        fun removeUid(uid: String) = entries.keys.filter { it.cotUid == uid }.mapNotNull(::remove)
        fun update(key: SourceCotKey, transform: (Item) -> Item): Item? {
            val current = entries[key] ?: return null
            val updated = transform(current.item)
            entries[key] = current.copy(item = updated)
            return updated
        }
        fun updateVisible(uid: String, transform: (Item) -> Item) = visibleKey(uid)?.let { update(it, transform) }
        fun transformAll(transform: (SourceCotKey, Item) -> Item): Boolean {
            var changed = false
            entries.replaceAll { key, value ->
                val updated = transform(key, value.item)
                if (updated !== value.item) changed = true
                value.copy(item = updated)
            }
            return changed
        }
        fun prune(): List<Item> {
            val candidates = entries.filter { it.key.cotUid.toInt() % 2 == 0 }
            val expired = candidates.filter { it.value.item.eventTime < 20 }.keys
            val excess = candidates.filter { it.key !in expired }.entries
                .sortedBy { it.value.item.receivedAt }.dropLast(5).map { it.key }
            return (expired + excess).mapNotNull(::remove)
        }
        fun clear() { entries.clear(); order.clear(); removedAt.clear() }
    }
}
