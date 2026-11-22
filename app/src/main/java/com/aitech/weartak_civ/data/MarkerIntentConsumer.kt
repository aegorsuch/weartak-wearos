package com.aitech.weartak_civ.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes actual effects and records their materialized bytes before attempting them. */
internal class MarkerIntentConsumer(private val journal: MarkerIntentJournal) {
    suspend fun <T> drain(
        prepare: suspend (MarkerIntentJournal.Intent, MarkerIntentJournal.State) -> T?,
        payload: (T) -> String,
        execute: suspend (MarkerIntentJournal.Intent, T) -> MarkerIntentJournal.Outcome?,
        executeOtherRoutes: (suspend (MarkerIntentJournal.Intent, T) -> Boolean)? = null,
        onError: (Exception) -> Unit,
    ): Boolean = mutex.withLock {
        val deferred = mutableSetOf<String>()
        while (true) {
            val examined = mutableSetOf<Long>()
            if (executeOtherRoutes != null) {
                val blocked = mutableSetOf<String>()
                for (intent in journal.read().intents) {
                    examined.add(intent.sequence)
                    if (intent.phase == MarkerIntentJournal.Phase.ACKNOWLEDGED ||
                        intent.otherRoutes == MarkerIntentJournal.OtherRoutes.OFFERED) continue
                    if (intent.uid in blocked) continue
                    if (intent.otherRoutes == MarkerIntentJournal.OtherRoutes.STARTED) {
                        blocked.add(intent.uid)
                        continue
                    }
                    val prepared = prepare(intent, journal.read())
                    if (prepared == null) {
                        blocked.add(intent.uid)
                        continue
                    }
                    journal.beginOtherRoutes(intent.sequence, payload(prepared))
                    val offered = try {
                        executeOtherRoutes(intent, prepared)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        onError(error)
                        blocked.add(intent.uid)
                        continue
                    }
                    journal.finishOtherRoutes(intent.sequence, offered)
                    if (!offered) blocked.add(intent.uid)
                }
            }
            val eligible = journal.dispatchable().filter {
                it.uid !in deferred && (executeOtherRoutes == null || it.sequence in examined)
            }
            var progressed = false
            for (intent in eligible) {
                val prepared = prepare(intent, journal.read()) ?: continue
                val started = try {
                    journal.begin(intent.sequence, payload(prepared))
                } catch (error: IllegalStateException) {
                    if (journal.read().closed) return@withLock false
                    throw error
                }
                val outcome = try {
                    execute(started, prepared)
                } catch (error: CancellationException) {
                    journal.markUncertain(started.sequence)
                    throw error
                } catch (error: Exception) {
                    onError(error)
                    null
                }
                if (outcome == MarkerIntentJournal.Outcome.RETRY_LATER) {
                    journal.deferNotSent(started.sequence)
                    deferred.add(started.uid)
                    continue
                }
                if (outcome == null) journal.markUncertain(started.sequence)
                else journal.markCommitted(started.sequence, outcome)
                progressed = true
            }
            if (!progressed && (executeOtherRoutes == null ||
                    journal.dispatchable().none { it.uid !in deferred && it.sequence !in examined })) break
        }
        deferred.isNotEmpty()
    }

    /** Drains journal consumers only; other point writers require independent fencing. */
    suspend fun closeAndDrain(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0)
        journal.closeAdmission()
        return withTimeoutOrNull(timeoutMillis) {
            mutex.withLock {
                journal.read().intents.none {
                    it.phase == MarkerIntentJournal.Phase.DISPATCH_STARTED ||
                        it.phase == MarkerIntentJournal.Phase.UNCERTAIN ||
                        it.otherRoutes == MarkerIntentJournal.OtherRoutes.STARTED
                }
            }
        } ?: false
    }

    private companion object {
        val mutex = Mutex()
    }
}
