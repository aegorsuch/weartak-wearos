package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.data.DittoPdlFrozenSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import com.weartak.plugin.sdk.pdlsnapshot.v1.IPdlSnapshotV1
import com.weartak.plugin.sdk.pdlsnapshot.v1.PdlSnapshotContractV1 as Contract
import com.weartak.plugin.sdk.pdltransfer.v1.PdlTransferContractV1
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

internal class HostPdlSnapshotV1(
    private val sessionId: String,
    private val expectedUid: Int,
    private val authorized: () -> Boolean,
    private val bindingActive: () -> Boolean,
    private val quiescenceStillProven: () -> Boolean,
    private val readFrozen: suspend () -> DittoPdlFrozenSelection,
    private val withCurrent: suspend (DittoPdlFrozenSelection, () -> Int) -> Int?,
    private val callingUid: () -> Int = Binder::getCallingUid,
    private val random: SecureRandom = SecureRandom(),
) : IPdlSnapshotV1.Stub() {
    private class Transfer(
        val id: String,
        val snapshot: DittoPdlFrozenSelection,
        val nonce: ByteArray,
    ) {
        var acknowledged = false
        var changed = false
    }

    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private var transfer: Transfer? = null

    fun close() { closed.set(true) }

    override fun read(sessionId: String?, transferId: String?): Bundle =
        HostCutoverBindings.transferOrBusy({ failure(Contract.BUSY) }) { readLocked(sessionId, transferId) }

    private fun readLocked(sessionId: String?, transferId: String?): Bundle {
        val uid = callingUid()
        reject(uid, sessionId, transferId)?.let { return failure(it) }
        return try {
            runBlocking {
                if (!mutex.tryLock()) return@runBlocking failure(Contract.BUSY)
                try {
                    reject(uid, sessionId, transferId)?.let { return@runBlocking failure(it) }
                    val id = requireNotNull(transferId)
                    val prior = transfer
                    if (prior != null && prior.id != id) return@runBlocking failure(Contract.BUSY)
                    val current = prior ?: run {
                        val snapshot = readFrozen()
                        if (snapshot.communityIdentity.length > Contract.MAX_IDENTITY_UTF16_UNITS ||
                            (snapshot.customIdentity?.length ?: 0) > Contract.MAX_IDENTITY_UTF16_UNITS
                        ) return@runBlocking failure(Contract.TOO_LARGE)
                        Transfer(id, snapshot, ByteArray(Contract.NONCE_BYTES).also(random::nextBytes))
                            .also { transfer = it }
                    }
                    if (current.changed) return@runBlocking failure(Contract.CHANGED)
                    val code = withCurrent(current.snapshot) {
                        reject(uid, sessionId, id) ?: if (quiescenceStillProven()) Contract.OK else Contract.UNAVAILABLE
                    }
                    if (code == null) {
                        current.changed = true
                        return@runBlocking failure(Contract.CHANGED)
                    }
                    if (code != Contract.OK) {
                        if (code == Contract.UNAVAILABLE) current.changed = true
                        return@runBlocking failure(code)
                    }
                    reject(uid, sessionId, id)?.let { return@runBlocking failure(it) }
                    if (!quiescenceStillProven()) {
                        current.changed = true
                        return@runBlocking failure(Contract.UNAVAILABLE)
                    }
                    Bundle().apply {
                        putInt(Contract.KEY_CODE, Contract.OK)
                        putString(Contract.KEY_TRANSFER_ID, id)
                        putBoolean(Contract.KEY_ENABLED, current.snapshot.initiallyEnabled)
                        putInt(Contract.KEY_SOURCE, when (current.snapshot.selection.source) {
                            DittoPdlSource.COMMUNITY -> Contract.COMMUNITY
                            DittoPdlSource.CUSTOM -> Contract.CUSTOM
                        })
                        putString(Contract.KEY_COMMUNITY_IDENTITY, current.snapshot.communityIdentity)
                        putString(Contract.KEY_CUSTOM_IDENTITY, current.snapshot.customIdentity)
                        putByteArray(Contract.KEY_NONCE, current.nonce.copyOf())
                    }
                } finally {
                    mutex.unlock()
                }
            }
        } catch (_: IOException) {
            Log.e(TAG, "Frozen Ditto settings read failed")
            failure(Contract.INTERNAL_ERROR)
        } catch (_: GeneralSecurityException) {
            Log.e(TAG, "Frozen Ditto settings unavailable")
            failure(Contract.INTERNAL_ERROR)
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Frozen Ditto settings unavailable")
            failure(Contract.UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            Log.e(TAG, "Frozen Ditto settings invalid")
            failure(Contract.INTERNAL_ERROR)
        }
    }

    override fun acknowledge(sessionId: String?, transferId: String?, nonce: ByteArray?): Int =
        HostCutoverBindings.transferOrBusy({ Contract.BUSY }) { acknowledgeLocked(sessionId, transferId, nonce) }

    private fun acknowledgeLocked(sessionId: String?, transferId: String?, nonce: ByteArray?): Int {
        val uid = callingUid()
        reject(uid, sessionId, transferId)?.let { return it }
        if (nonce == null || nonce.size != Contract.NONCE_BYTES) return Contract.INVALID_ARGUMENT
        return try {
            runBlocking {
                if (!mutex.tryLock()) return@runBlocking Contract.BUSY
                try {
                    reject(uid, sessionId, transferId)?.let { return@runBlocking it }
                    val current = transfer ?: return@runBlocking Contract.MISSING
                    if (current.id != transferId || !MessageDigest.isEqual(current.nonce, nonce)) {
                        return@runBlocking Contract.INVALID_ARGUMENT
                    }
                    if (current.changed) return@runBlocking Contract.CHANGED
                    val code = withCurrent(current.snapshot) {
                        reject(uid, sessionId, transferId) ?: if (quiescenceStillProven()) Contract.OK else Contract.UNAVAILABLE
                    }
                    if (code == null) {
                        current.changed = true
                        return@runBlocking Contract.CHANGED
                    }
                    if (code == Contract.UNAVAILABLE || !quiescenceStillProven()) {
                        current.changed = true
                        return@runBlocking Contract.UNAVAILABLE
                    }
                    reject(uid, sessionId, transferId)?.let { return@runBlocking it }
                    if (code == Contract.OK) current.acknowledged = true
                    code
                } finally {
                    mutex.unlock()
                }
            }
        } catch (_: IOException) {
            Log.e(TAG, "Frozen Ditto settings acknowledgment failed")
            Contract.INTERNAL_ERROR
        } catch (_: GeneralSecurityException) {
            Log.e(TAG, "Frozen Ditto settings unavailable")
            Contract.INTERNAL_ERROR
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Frozen Ditto settings unavailable")
            Contract.UNAVAILABLE
        } catch (_: IllegalArgumentException) {
            Log.e(TAG, "Frozen Ditto settings invalid")
            Contract.INTERNAL_ERROR
        }
    }

    internal suspend fun <T : Any> withAcknowledgedSnapshot(
        callerUid: Int,
        requestedSession: String,
        action: suspend (DittoPdlFrozenSelection) -> T?,
    ): T? {
        if (!mutex.tryLock()) return null
        try {
            val current = transfer ?: return null
            if (!current.acknowledged || current.changed ||
                reject(callerUid, requestedSession, current.id) != null) return null
            if (!quiescenceStillProven()) {
                current.changed = true
                return null
            }
            return action(current.snapshot)
        } finally {
            mutex.unlock()
        }
    }

    private fun reject(uid: Int, session: String?, id: String?): Int? = when {
        uid != expectedUid || !authorized() -> Contract.UNAUTHORIZED
        closed.get() || !bindingActive() || session != sessionId -> Contract.INACTIVE_SESSION
        !PdlTransferContractV1.isValidTransferId(id) -> Contract.INVALID_ARGUMENT
        else -> null
    }

    private fun failure(code: Int) = Bundle().apply { putInt(Contract.KEY_CODE, code) }

    private companion object { const val TAG = "WearTAK-PdlSnapshot" }
}
