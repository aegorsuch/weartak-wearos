package com.aitech.weartak_civ.service

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

internal class TrackedPluginAlert(
    private val preferences: SharedPreferences,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    data class PendingClear(
        val uid: String,
        val activationId: String,
        val positional: Boolean,
        val xml: String,
        val senderUid: String,
        val senderCallsign: String,
    )
    private data class IntentLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    @Volatile private var storageFailed = false

    fun activate(uid: String, activationId: String): Boolean = synchronized(fenceLock) {
        if (storageFailed) return@synchronized false
        val key = latestKey(uid)
        val previous = preferences.getString(key, null)
        if (previous == activationId) return@synchronized true
        val edit = preferences.edit().putString(key, activationId)
        if (previous != null) {
            for (state in DataRepository.AlertState.values()) {
                for (positional in listOf(false, true)) {
                    edit.remove(payloadKey(messageId(uid, previous, state, positional)))
                }
            }
            for (positional in listOf(false, true)) {
                edit.remove(clearKey(messageId(uid, previous, DataRepository.AlertState.CANCEL, positional)))
            }
        }
        if (!edit.commit()) {
            storageFailed = true
            Log.e(TAG, "Unable to persist latest alert activation")
            return@synchronized false
        }
        true
    }

    fun rememberClear(clear: PendingClear): Boolean = synchronized(fenceLock) {
        if (isFenced(clear.uid, clear.activationId, DataRepository.AlertState.CANCEL)) return@synchronized false
        val id = messageId(clear.uid, clear.activationId, DataRepository.AlertState.CANCEL, clear.positional)
        val key = clearKey(id)
        if (preferences.getString(id, null) in listOf(SUBMITTED, RETIRED)) return@synchronized true
        if (preferences.contains(key)) return@synchronized true
        if (preferences.all.keys.count { it.startsWith(CLEAR_PREFIX) } >= MAX_PENDING) {
            Log.e(TAG, "Pending alert clear capacity exhausted")
            return@synchronized false
        }
        val value = JSONObject().put("uid", clear.uid).put("activation", clear.activationId)
            .put("positional", clear.positional).put("xml", clear.xml)
            .put("senderUid", clear.senderUid).put("senderCallsign", clear.senderCallsign).toString()
        if (!preferences.edit().putString(key, value).commit()) {
            storageFailed = true
            Log.e(TAG, "Unable to persist pending alert clear")
            return@synchronized false
        }
        true
    }

    fun pendingClears(): List<PendingClear> = synchronized(fenceLock) {
        if (storageFailed) return@synchronized emptyList()
        try {
            preferences.all.filterKeys { it.startsWith(CLEAR_PREFIX) }.map { (key, value) ->
                val json = JSONObject(value as? String ?: throw JSONException("Invalid clear record"))
                val clear = PendingClear(json.getString("uid"), json.getString("activation"),
                    json.getBoolean("positional"), json.getString("xml"), json.getString("senderUid"),
                    json.getString("senderCallsign"))
                val expected = clearKey(messageId(clear.uid, clear.activationId,
                    DataRepository.AlertState.CANCEL, clear.positional))
                if (key != expected) throw JSONException("Invalid clear record key")
                clear
            }.filterNot { isFenced(it.uid, it.activationId, DataRepository.AlertState.CANCEL) }
        } catch (error: JSONException) {
            storageFailed = true
            Log.e(TAG, "Unable to read pending alert clears", error)
            emptyList()
        }
    }

    fun cancel(uid: String, activationId: String): Boolean = synchronized(fenceLock) {
        if (storageFailed) return@synchronized false
        val key = cancellationKey(uid, activationId)
        if (preferences.getBoolean(key, false)) return@synchronized true
        if (!preferences.edit().putBoolean(key, true)
                .remove(payloadKey(messageId(uid, activationId, DataRepository.AlertState.ALERT, false)))
                .remove(payloadKey(messageId(uid, activationId, DataRepository.AlertState.ALERT, true)))
                .commit()) {
            storageFailed = true
            Log.e(TAG, "Unable to persist alert cancellation fence")
            return@synchronized false
        }
        true
    }

    fun submitted(uid: String, activationId: String, state: DataRepository.AlertState, positional: Boolean): Boolean =
        synchronized(fenceLock) {
            !isFenced(uid, activationId, state) &&
                preferences.getString(messageId(uid, activationId, state, positional), null) == SUBMITTED
        }

    fun retryableWithoutAdmission(
        uid: String, activationId: String, state: DataRepository.AlertState, positional: Boolean,
    ): Boolean = synchronized(fenceLock) {
        val id = messageId(uid, activationId, state, positional)
        !isFenced(uid, activationId, state) &&
            preferences.contains(payloadKey(id)) &&
            preferences.getString(id, null) == null &&
            preferences.getString(inflightKey(uid), null) == null
    }

    suspend fun submit(
        uid: String,
        activationId: String,
        state: DataRepository.AlertState,
        positional: Boolean,
        xml: String,
        selected: suspend () -> Boolean,
        ready: () -> Boolean = { true },
        verifiedSession: () -> String? = { null },
        offer: (String, String, () -> Boolean, String) -> suspend () -> PluginTransportHostV1Impl.Submission,
    ): Boolean {
        val lockKey = latestKey(uid)
        val intentLock = synchronized(intentLocks) {
            intentLocks.getOrPut(lockKey) { IntentLock() }.also { it.users++ }
        }
        try {
            return intentLock.mutex.withLock {
                val id = messageId(uid, activationId, state, positional)
                if (state == DataRepository.AlertState.CANCEL && !cancel(uid, activationId)) return@withLock false
                if (isFenced(uid, activationId, state)) return@withLock false
                when (preferences.getString(id, null)) {
                    SUBMITTED -> return@withLock true
                    RETIRED -> return@withLock false
                    STARTED -> if (preferences.getString(inflightKey(uid), null) != id) return@withLock false
                    null -> Unit
                    else -> {
                        Log.e(TAG, "Unrecognized alert submission state")
                        return@withLock false
                    }
                }
                val payload = synchronized(fenceLock) {
                    if (isFenced(uid, activationId, state)) return@synchronized null
                    preferences.getString(payloadKey(id), null) ?: run {
                        if (preferences.all.keys.count { it.startsWith(PAYLOAD_PREFIX) } >= MAX_PENDING) {
                            Log.e(TAG, "Alert pending payload capacity exhausted")
                            return@synchronized null
                        }
                        if (!preferences.edit().putString(payloadKey(id), xml).commit()) {
                            storageFailed = true
                            Log.e(TAG, "Unable to persist alert payload")
                            return@synchronized null
                        }
                        xml
                    }
                } ?: return@withLock false
                val deadline = now() + WAIT_MS
                while (!isFenced(uid, activationId, state) && selected() && now() <= deadline) {
                    if (!ready()) {
                        val remaining = deadline - now()
                        if (remaining <= 0) break
                        pause(minOf(RETRY_MS, remaining))
                        continue
                    }
                    val session = verifiedSession()
                    if (session == null) {
                        val remaining = deadline - now()
                        if (remaining <= 0) break
                        pause(minOf(RETRY_MS, remaining))
                        continue
                    }
                    val awaitResult = try {
                        synchronized(fenceLock) {
                            if (isFenced(uid, activationId, state)) return@synchronized null
                            val inflight = preferences.getString(inflightKey(uid), null)
                            if (inflight != null) {
                                val previousSession = preferences.getString(ownerKey(inflight), null)
                                if (previousSession == null || previousSession == session ||
                                    preferences.getString(inflight, null) != STARTED) {
                                    Log.w(TAG, "Alert blocked by uncertain prior submission for UID")
                                    return@synchronized null
                                }
                                if (!preferences.edit().putString(inflight, RETIRED)
                                        .remove(inflightKey(uid)).remove(ownerKey(inflight))
                                        .remove(clearKey(inflight)).remove(payloadKey(inflight)).commit()) {
                                    storageFailed = true
                                    Log.e(TAG, "Unable to persist retired alert submission")
                                    return@synchronized null
                                }
                            }
                            if (preferences.getString(id, null) == RETIRED) return@synchronized null
                            if (state == DataRepository.AlertState.CANCEL &&
                                listOf(false, true).any {
                                    preferences.getString(messageId(uid, activationId,
                                        DataRepository.AlertState.ALERT, it), null) == STARTED
                                }) return@synchronized null
                            if (!preferences.edit().putString(id, STARTED)
                                    .putString(inflightKey(uid), id).putString(ownerKey(id), session).commit()) {
                                storageFailed = true
                                Log.e(TAG, "Unable to persist alert submission fence")
                                return@synchronized null
                            }
                            offer(payload, id, { !isFenced(uid, activationId, state) }, session)
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Alert admission outcome uncertain", error)
                        return@withLock false
                    }
                    if (awaitResult == null) return@withLock false
                    val result = try {
                        awaitResult()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Alert submission outcome uncertain", error)
                        return@withLock false
                    }
                    when (result) {
                        PluginTransportHostV1Impl.Submission.SUBMITTED -> {
                            if (!preferences.edit().putString(id, SUBMITTED).remove(inflightKey(uid))
                                    .remove(ownerKey(id))
                                    .remove(clearKey(id))
                                    .remove(payloadKey(id)).commit()) {
                                storageFailed = true
                                Log.e(TAG, "Unable to persist alert submission result")
                                return@withLock false
                            }
                            return@withLock true
                        }
                        PluginTransportHostV1Impl.Submission.UNCERTAIN -> {
                            if (!preferences.edit().remove(payloadKey(id)).commit()) {
                                storageFailed = true
                                Log.e(TAG, "Unable to retire uncertain alert payload")
                            }
                            Log.w(TAG, "Alert submission outcome uncertain; replay blocked")
                            return@withLock false
                        }
                        PluginTransportHostV1Impl.Submission.NOT_ADMITTED -> {
                            if (!preferences.edit().remove(id).remove(inflightKey(uid)).remove(ownerKey(id)).commit()) {
                                storageFailed = true
                                Log.e(TAG, "Unable to clear alert admission fence")
                                return@withLock false
                            }
                        }
                        PluginTransportHostV1Impl.Submission.CANCELLED_BEFORE_EFFECT -> {
                            if (!preferences.edit().remove(id).remove(inflightKey(uid))
                                    .remove(ownerKey(id))
                                    .remove(payloadKey(id)).commit()) {
                                storageFailed = true
                                Log.e(TAG, "Unable to retire cancelled alert")
                            }
                            return@withLock false
                        }
                    }
                    val remaining = deadline - now()
                    if (remaining <= 0) break
                    pause(minOf(RETRY_MS, remaining))
                }
                false
            }
        } finally {
            synchronized(intentLocks) {
                if (--intentLock.users == 0) intentLocks.remove(lockKey)
            }
        }
    }

    private fun isFenced(uid: String, activationId: String, state: DataRepository.AlertState): Boolean =
        synchronized(fenceLock) {
            val latest = preferences.getString(latestKey(uid), null)
            storageFailed || (latest != null && latest != activationId) ||
                (state == DataRepository.AlertState.ALERT &&
                    preferences.getBoolean(cancellationKey(uid, activationId), false))
        }

    private fun latestKey(uid: String): String =
        "latest-" + messageId(uid, "", DataRepository.AlertState.ALERT, false)

    private fun inflightKey(uid: String): String =
        "inflight-" + messageId(uid, "", DataRepository.AlertState.ALERT, false)

    private fun cancellationKey(uid: String, activationId: String): String =
        "cancelled-" + messageId(uid, activationId, DataRepository.AlertState.CANCEL, false)

    private fun payloadKey(id: String): String = PAYLOAD_PREFIX + id
    private fun clearKey(id: String): String = CLEAR_PREFIX + id
    private fun ownerKey(id: String): String = "owner-$id"

    private fun messageId(uid: String, activationId: String, state: DataRepository.AlertState, positional: Boolean): String {
        val input = "${uid.length}:$uid:${activationId.length}:$activationId:${state.name}:$positional"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return "alert-" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private val fenceLock = Any()
        private val intentLocks = mutableMapOf<String, IntentLock>()
        private const val TAG = "WearTAK-PluginAlert"
        private const val STARTED = "started"
        private const val SUBMITTED = "submitted"
        private const val RETIRED = "uncertain-retired"
        private const val PAYLOAD_PREFIX = "payload-"
        private const val CLEAR_PREFIX = "clear-"
        private const val WAIT_MS = 10_000L
        private const val RETRY_MS = 250L
        private const val MAX_PENDING = 1024
    }
}
