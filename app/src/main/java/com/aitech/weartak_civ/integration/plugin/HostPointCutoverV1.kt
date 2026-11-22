package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.cot.DittoPointCutoverSnapshot
import com.weartak.plugin.sdk.pdltransfer.v1.PdlTransferContractV1
import com.weartak.plugin.sdk.pointcutover.v1.IPointCutoverV1
import com.weartak.plugin.sdk.pointcutover.v1.PointCutoverContractV1 as Contract
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.runBlocking

internal class HostPointCutoverV1(
    private val sessionId: String,
    private val expectedUid: Int,
    private val authorized: () -> Boolean,
    private val bindingActive: () -> Boolean,
    private val quiescenceStillProven: () -> Boolean,
    private val snapshots: DittoPointCutoverSnapshot,
    private val directory: File,
    private val callingUid: () -> Int = Binder::getCallingUid,
    private val random: SecureRandom = SecureRandom(),
    private val exportAllowed: suspend () -> Boolean = { false },
) : IPointCutoverV1.Stub() {
    private class Transfer(
        val id: String,
        val nonce: ByteArray,
        val pair: DittoPointCutoverSnapshot.Pair,
        val frame: PointCutoverFrame,
    ) {
        var changed = false
        var acknowledged = false
    }

    private val closed = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private var transfer: Transfer? = null

    fun close() {
        closed.set(true)
        if (lock.tryLock()) {
            try { cleanup() } finally { lock.unlock() }
        }
    }

    override fun read(sessionId: String?, transferId: String?): Bundle =
        HostCutoverBindings.transferOrBusy({ failure(Contract.BUSY) }) { readLocked(sessionId, transferId) }

    private fun readLocked(sessionId: String?, transferId: String?): Bundle {
        val uid = callingUid()
        reject(uid, sessionId, transferId)?.let { return failure(it) }
        if (!lock.tryLock()) return failure(Contract.BUSY)
        try {
            reject(uid, sessionId, transferId)?.let { return failure(it) }
            if (!runBlocking { exportAllowed() }) return failure(Contract.UNAVAILABLE)
            val id = requireNotNull(transferId)
            val existing = transfer
            if (existing != null && existing.id != id) return failure(Contract.BUSY)
            val current = existing ?: run {
                val pair = runBlocking { snapshots.capture() }
                if (reject(uid, sessionId, id) != null || !quiescenceStillProven()) {
                    return failure(Contract.INACTIVE_SESSION)
                }
                val frame = PointCutoverFrame.create(directory, pair)
                Transfer(id, ByteArray(16).also(random::nextBytes), pair, frame).also { transfer = it }
            }
            if (!isCurrent(current)) return failure(Contract.CHANGED)
            val descriptor = current.frame.openReadOnly()
            val rejected = reject(uid, sessionId, id)
            if (rejected != null || !isCurrent(current)) {
                descriptor.close()
                return failure(rejected ?: Contract.CHANGED)
            }
            return Bundle().apply {
                putInt(Contract.KEY_CODE, Contract.OK)
                putInt(Contract.KEY_VERSION, 1)
                putString(Contract.KEY_TRANSFER_ID, id)
                putByteArray(Contract.KEY_NONCE, current.nonce.copyOf())
                putParcelable(Contract.KEY_PAYLOAD, descriptor)
                putLong(Contract.KEY_LENGTH, current.frame.length)
                putInt(Contract.KEY_LOCAL_COUNT, current.frame.localCount)
                putInt(Contract.KEY_WATERMARK_COUNT, current.frame.watermarkCount)
                putByteArray(Contract.KEY_DIGEST, current.frame.digest())
            }
        } catch (_: IOException) {
            Log.e(TAG, "Point cutover export failed")
            return failure(Contract.INTERNAL_ERROR)
        } catch (_: IllegalArgumentException) {
            Log.e(TAG, "Point cutover data cannot be exported")
            return failure(Contract.UNAVAILABLE)
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Point cutover state unavailable")
            return failure(Contract.UNAVAILABLE)
        } finally {
            try { if (closed.get()) cleanup() } finally { lock.unlock() }
        }
    }

    override fun acknowledge(sessionId: String?, transferId: String?, nonce: ByteArray?): Int =
        HostCutoverBindings.transferOrBusy({ Contract.BUSY }) { acknowledgeLocked(sessionId, transferId, nonce) }

    private fun acknowledgeLocked(sessionId: String?, transferId: String?, nonce: ByteArray?): Int {
        val uid = callingUid()
        reject(uid, sessionId, transferId)?.let { return it }
        if (nonce == null || nonce.size != 16) return Contract.INVALID_ARGUMENT
        if (!lock.tryLock()) return Contract.BUSY
        try {
            reject(uid, sessionId, transferId)?.let { return it }
            if (!runBlocking { exportAllowed() }) return Contract.UNAVAILABLE
            val current = transfer ?: return Contract.MISSING
            if (current.id != transferId || !MessageDigest.isEqual(current.nonce, nonce)) {
                return Contract.INVALID_ARGUMENT
            }
            if (!isCurrent(current)) return Contract.CHANGED
            val result = snapshots.withCurrentPair(current.pair) {
                reject(uid, sessionId, transferId) ?: if (quiescenceStillProven()) Contract.OK else Contract.UNAVAILABLE
            }
            if (result == null) {
                current.changed = true
                return Contract.CHANGED
            }
            if (result == Contract.OK) current.acknowledged = true
            return result
        } catch (_: IOException) {
            Log.e(TAG, "Point cutover state unavailable")
            return Contract.UNAVAILABLE
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Point cutover state invalid")
            return Contract.UNAVAILABLE
        } finally {
            try { if (closed.get()) cleanup() } finally { lock.unlock() }
        }
    }

    /** Never waits for point operations while a caller holds DataStore's transaction. */
    internal fun <T : Any> withAcknowledgedCurrent(callerUid: Int, requestedSession: String, action: () -> T): T? {
        if (!lock.tryLock()) return null
        try {
            val current = transfer ?: return null
            if (!current.acknowledged || reject(callerUid, requestedSession, current.id) != null ||
                !isCurrent(current)) return null
            val result: Pair<Boolean, T?>? = snapshots.withCurrentPair(current.pair) {
                if (reject(callerUid, requestedSession, current.id) != null || !quiescenceStillProven()) false to null
                else true to action()
            }
            if (result == null || !result.first) current.changed = true
            return result?.second
        } finally {
            try { if (closed.get()) cleanup() } finally { lock.unlock() }
        }
    }

    private fun reject(uid: Int, session: String?, id: String?): Int? = when {
        uid != expectedUid || !authorized() -> Contract.UNAUTHORIZED
        closed.get() || !bindingActive() || session != sessionId -> Contract.INACTIVE_SESSION
        !PdlTransferContractV1.isValidTransferId(id) -> Contract.INVALID_ARGUMENT
        else -> null
    }

    private fun failure(code: Int) = Bundle().apply { putInt(Contract.KEY_CODE, code) }

    private fun isCurrent(current: Transfer): Boolean {
        if (current.changed) return false
        if (!quiescenceStillProven() || snapshots.withCurrentPair(current.pair) { true } != true) {
            current.changed = true
            return false
        }
        return true
    }

    private fun cleanup() {
        val current = transfer
        transfer = null
        current?.frame?.close()
    }

    private companion object { const val TAG = "WearTAK-PointCutover" }
}
