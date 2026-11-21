package com.aitech.weartak_civ.service

import android.util.Log
import com.aitech.weartak_civ.command.SensitiveEnrollment
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.cot.DittoCotReceiver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import live.ditto.DittoQueryResult
import live.ditto.DittoStore
import org.json.JSONObject

/** Materializes native results inside the SDK callback; only detached maps cross threads. */
internal class DittoStoreObservers(
    private val store: DittoStore,
    parentScope: CoroutineScope,
    private val receiver: DittoCotReceiver
) : Closeable {
    private val lock = Any()
    private var closed = false
    @Volatile
    internal var closedSuccessfully = false
        private set
    private val job = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val observers = mutableListOf<Closeable>()

    init {
        try {
            DittoCotDocument.COLLECTIONS.forEach { collection ->
                observers += store.registerObserver(
                    "SELECT * FROM $collection",
                    changeHandler = { result: DittoQueryResult, next: () -> Unit ->
                        onSnapshot(collection, result, next)
                    }
                )
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun onSnapshot(collection: String, result: DittoQueryResult, next: () -> Unit) {
        synchronized(lock) {
            var detached = emptyList<Map<String, Any?>>()
            val snapshot = try {
                result.use {
                    if (closed) return
                    val items = it.items
                    if (items.size > MAX_SNAPSHOT_DOCUMENTS) {
                        items.forEach { item ->
                            try {
                                evictSensitiveDocument(collection, item.value)
                            } catch (_: Exception) {
                                Log.w(TAG, "Could not inspect Ditto document for cleanup")
                            }
                        }
                        throw IllegalArgumentException("Too many Ditto documents")
                    }
                    items.mapNotNull { item ->
                        try {
                            item.value
                        } catch (error: Exception) {
                            Log.w(TAG, "Rejected unmaterializable Ditto $collection document")
                            null
                        }
                    }.also { docs ->
                        detached = docs
                        require(docs.sumOf { doc ->
                            ((doc["r"] as? String)?.length?.toLong() ?: 0L) +
                                ((doc["msg"] as? String)?.length?.toLong() ?: 0L)
                        } <= MAX_SNAPSHOT_CHARACTERS) { "Oversized Ditto snapshot detail" }
                    }
                }
            } catch (error: Exception) {
                detached.forEach { evictSensitiveDocument(collection, it) }
                Log.w(TAG, "Rejected Ditto $collection snapshot")
                signalNext(next)
                return
            }
            // Initial snapshots scrub existing local stores; subsequent snapshots scrub resyncs.
            // ATOMIC enters receive's finally even if close cancels a queued ingestion.
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    receiver.receive(collection, snapshot, ::evictSensitiveDocument)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Ditto $collection ingestion failed")
                } finally {
                    synchronized(lock) { if (!closed) signalNext(next) }
                }
            }
        }
    }

    private fun evictSensitiveDocument(collection: String, document: Map<String, Any?>) {
        if (!SensitiveEnrollment.containsSensitiveDocument(document)) return
        try {
            val id = requireNotNull(document["_id"])
            // Use local eviction, never a replicated deletion or a payload-bearing predicate.
            val literal = when (id) {
                is String -> JSONObject.quote(id)
                is Map<*, *> -> JSONObject(id).toString()
                is Number, is Boolean -> id.toString()
                else -> throw IllegalArgumentException("Unsupported Ditto document ID")
            }
            store.collection(collection).find("_id == $literal").evict()
        } catch (_: Exception) {
            Log.w(TAG, "Sensitive Ditto cleanup failed; retrying on the next snapshot")
        }
    }

    private fun signalNext(next: () -> Unit) {
        try {
            next()
        } catch (error: Exception) {
            Log.e(TAG, "Ditto observer could not request its next snapshot", error)
        }
    }

    override fun close() {
        val handles = synchronized(lock) {
            if (closed) return
            closed = true
            observers.toList().also { observers.clear() }
        }
        scope.cancel()
        var allClosed = true
        handles.forEach {
            try {
                it.close()
            } catch (error: Exception) {
                allClosed = false
                Log.e(TAG, "Ditto observer cleanup failed", error)
            }
        }
        closedSuccessfully = allClosed
    }

    companion object {
        const val MAX_SNAPSHOT_DOCUMENTS = 4096
        const val MAX_SNAPSHOT_CHARACTERS = 2 * 1024 * 1024
        private const val TAG = "WearTAK-DittoObservers"
    }
}
