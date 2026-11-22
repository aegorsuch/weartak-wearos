package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-binding transfer state; acknowledgment is not a runtime revocation or shutdown proof. */
internal class PdlMigrationSession(
    private val sessionId: String,
    private val authorized: (Int) -> Boolean,
    private val bindingActive: () -> Boolean,
    private val readSelection: suspend () -> DittoPdlMigrationSelection,
    private val selectionCurrent: suspend (DittoPdlMigrationSelection) -> Boolean,
    private val acknowledgeIfCurrent: suspend (DittoPdlMigrationSelection, () -> Status) -> Status?,
    private val maxIdentityLength: Int,
    private val validTransferId: (String) -> Boolean,
    private val random: SecureRandom = SecureRandom(),
) {
    internal enum class Status { OK, INVALID, UNAUTHORIZED, INACTIVE, CHANGED, BUSY, TOO_LARGE }

    internal class ReadResult(
        val status: Status,
        val selection: DittoPdlMigrationSelection? = null,
        val nonce: ByteArray? = null,
    )

    private class Transfer(
        val id: String,
        val selection: DittoPdlMigrationSelection,
        val nonce: ByteArray,
    ) {
        var acknowledged = false
        var changed = false
    }

    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private var transfer: Transfer? = null

    init {
        require(sessionId.isNotBlank() && maxIdentityLength >= 0)
    }

    suspend fun read(callerUid: Int, requestedSession: String, transferId: String): ReadResult =
        mutex.withLock {
            reject(callerUid, requestedSession, transferId)?.let { return@withLock ReadResult(it) }
            val existing = transfer
            if (existing != null && existing.id != transferId) return@withLock ReadResult(Status.BUSY)
            if (existing != null) {
                if (existing.changed || !selectionCurrent(existing.selection)) {
                    existing.changed = true
                    return@withLock ReadResult(Status.CHANGED)
                }
                reject(callerUid, requestedSession, transferId)?.let { return@withLock ReadResult(it) }
                return@withLock ReadResult(Status.OK, existing.selection, existing.nonce.copyOf())
            }
            val selection = readSelection()
            if (selection.identity.length > maxIdentityLength) return@withLock ReadResult(Status.TOO_LARGE)
            if (!selectionCurrent(selection)) return@withLock ReadResult(Status.CHANGED)
            reject(callerUid, requestedSession, transferId)?.let { return@withLock ReadResult(it) }
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            transfer = Transfer(transferId, selection, nonce)
            ReadResult(Status.OK, selection, nonce.copyOf())
        }

    suspend fun acknowledge(
        callerUid: Int,
        requestedSession: String,
        transferId: String,
        nonce: ByteArray,
    ): Status = mutex.withLock {
        reject(callerUid, requestedSession, transferId)?.let { return@withLock it }
        val current = transfer ?: return@withLock Status.INVALID
        if (current.id != transferId || nonce.size != NONCE_BYTES ||
            !MessageDigest.isEqual(current.nonce, nonce)) return@withLock Status.INVALID
        if (current.changed) return@withLock Status.CHANGED
        val result = acknowledgeIfCurrent(current.selection) {
            reject(callerUid, requestedSession, transferId) ?: Status.OK
        }
        if (result == null) {
            current.changed = true
            return@withLock Status.CHANGED
        }
        reject(callerUid, requestedSession, transferId)?.let { return@withLock it }
        if (result == Status.OK) current.acknowledged = true
        result
    }

    /** Action must not perform Binder calls or wait for DataStore-dependent locks. */
    suspend fun <T : Any> withAcknowledgedCurrent(
        callerUid: Int,
        requestedSession: String,
        action: () -> T,
    ): T? = mutex.withLock {
        val current = transfer ?: return@withLock null
        if (!current.acknowledged || current.changed ||
            reject(callerUid, requestedSession, current.id) != null) return@withLock null
        var value: T? = null
        val result = acknowledgeIfCurrent(current.selection) {
            reject(callerUid, requestedSession, current.id) ?: run {
                value = action()
                Status.OK
            }
        }
        if (result == null) current.changed = true
        if (result != Status.OK) null else value
    }

    /** The action must revalidate this selection in its own serialized storage transaction. */
    suspend fun <T : Any> withAcknowledgedSelection(
        callerUid: Int,
        requestedSession: String,
        action: suspend (DittoPdlMigrationSelection) -> T?,
    ): T? = mutex.withLock {
        val current = transfer ?: return@withLock null
        if (!current.acknowledged || current.changed ||
            reject(callerUid, requestedSession, current.id) != null) return@withLock null
        action(current.selection)
    }

    fun close() {
        closed.set(true)
    }

    private fun reject(callerUid: Int, requestedSession: String, transferId: String): Status? {
        if (!authorized(callerUid)) return Status.UNAUTHORIZED
        if (closed.get() || !bindingActive() || requestedSession != sessionId) return Status.INACTIVE
        if (!validTransferId(transferId)) return Status.INVALID
        return null
    }

    private companion object {
        const val NONCE_BYTES = 16
    }
}
