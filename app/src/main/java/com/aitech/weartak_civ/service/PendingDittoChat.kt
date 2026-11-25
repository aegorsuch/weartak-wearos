package com.aitech.weartak_civ.service

import android.util.Log
import com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl.Submission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Process-local retry queue; an uncertain write is never replayed. */
internal class PendingDittoChat(
    private val scope: CoroutineScope,
    private val offer: suspend (Message) -> Submission,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    data class Message(val xml: String, val senderUid: String, val senderCallsign: String?, val id: String)
    private data class Entry(val message: Message, var uncertain: Boolean = false)

    private val lock = Any()
    private val pending = mutableListOf<Entry>()
    private var worker: Job? = null

    fun enqueue(xml: String, senderUid: String, senderCallsign: String?) {
        synchronized(lock) {
            pending.add(Entry(Message(xml, senderUid, senderCallsign, UUID.randomUUID().toString())))
            if (worker == null) {
                val next = scope.launch(start = CoroutineStart.LAZY) { drain() }
                worker = next
                next.start()
            }
        }
    }

    private suspend fun drain() {
        val currentWorker = currentCoroutineContext()[Job]
        var retryDelay = 1_000L
        try {
            while (true) {
                val entry = synchronized(lock) {
                    pending.firstOrNull { !it.uncertain } ?: run {
                        worker = null
                        return
                    }
                }
                val result = try {
                    offer(entry.message)
                } catch (error: CancellationException) {
                    synchronized(lock) { entry.uncertain = true }
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Ditto chat submission outcome uncertain: ${error.javaClass.simpleName}")
                    Submission.UNCERTAIN
                }
                when (result) {
                    Submission.SUBMITTED -> {
                        synchronized(lock) { pending.remove(entry) }
                        retryDelay = 1_000L
                    }
                    Submission.UNCERTAIN -> {
                        synchronized(lock) { entry.uncertain = true }
                        Log.w(TAG, "Ditto chat submission uncertain; replay blocked")
                        retryDelay = 1_000L
                    }
                    Submission.CANCELLED_BEFORE_EFFECT -> {
                        synchronized(lock) {
                            val index = pending.indexOf(entry)
                            if (index >= 0) pending[index] = Entry(entry.message.copy(id = UUID.randomUUID().toString()))
                        }
                        pause(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                    }
                    Submission.NOT_ADMITTED -> {
                        pause(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                    }
                }
            }
        } finally {
            synchronized(lock) { if (worker === currentWorker) worker = null }
        }
    }

    internal fun pendingForTest(): List<Pair<Message, Boolean>> =
        synchronized(lock) { pending.map { it.message to it.uncertain } }

    companion object {
        private const val TAG = "WearTAK-DittoChat"
    }
}
