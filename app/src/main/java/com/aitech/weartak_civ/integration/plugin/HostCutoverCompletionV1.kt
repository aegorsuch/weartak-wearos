package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.data.DittoPdlFrozenSelection
import com.aitech.weartak_civ.data.DittoPdlHandoffState
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.cutover.v1.CutoverCompletionContractV1 as Contract
import com.weartak.plugin.sdk.cutover.v1.ICutoverCompletionV1
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * One gate owns every completion binding in this process. A replacement is refused until
 * the retired binding's in-flight call finishes, including an uncertain DataStore commit.
 */
internal object HostCutoverBindings {
    private val gate = Semaphore(1)
    @Volatile private var current: HostCutoverCompletionV1? = null

    internal fun tryEnter(): Boolean = gate.tryAcquire()
    internal fun leave() = gate.release()

    internal fun <T> transferOrBusy(busy: () -> T, action: () -> T): T {
        if (!gate.tryAcquire()) return busy()
        try { return action() } finally { gate.release() }
    }

    fun open(
        sessionId: String,
        expectedUid: Int,
        authorized: () -> Boolean,
        bindingActive: () -> Boolean,
        quiescenceStillProven: () -> Boolean,
        repository: DataRepository,
        pdl: HostPdlSnapshotV1,
        points: HostPointCutoverV1,
        callingUid: () -> Int = Binder::getCallingUid,
    ): HostCutoverCompletionV1? = open(
        sessionId, expectedUid, authorized, bindingActive, quiescenceStillProven,
        repository::readDittoPdlHandoffState,
        { uid, session, action -> pdl.withAcknowledgedSnapshot(uid, session, action) },
        { uid, session, action -> points.withAcknowledgedCurrent(uid, session, action) },
        repository::completeDittoPdlHandoff,
        { pdl.close(); points.close() },
        callingUid,
    )

    @Synchronized
    fun open(
        sessionId: String,
        expectedUid: Int,
        authorized: () -> Boolean,
        bindingActive: () -> Boolean,
        quiescenceStillProven: () -> Boolean,
        readState: suspend () -> DittoPdlHandoffState?,
        withPdlAck: suspend (Int, String, suspend (DittoPdlFrozenSelection) -> Boolean?) -> Boolean?,
        withPointAck: (Int, String, () -> Boolean) -> Boolean?,
        persistCompletion: suspend (DittoPdlFrozenSelection, () -> Boolean) -> Boolean,
        retireTransfers: () -> Unit = {},
        callingUid: () -> Int = Binder::getCallingUid,
    ): HostCutoverCompletionV1? {
        if (sessionId.isBlank() || expectedUid < 0 || !authorized() || !bindingActive() ||
            !quiescenceStillProven()) return null
        current?.close()
        if (!gate.tryAcquire()) return null
        try {
            return HostCutoverCompletionV1(
                sessionId, expectedUid, authorized, bindingActive, quiescenceStillProven,
                readState, withPdlAck, withPointAck, persistCompletion, retireTransfers, callingUid,
                gate, { current },
            ).also { current = it }
        } finally {
            gate.release()
        }
    }
}

internal class HostCutoverCompletionV1(
    private val sessionId: String,
    private val expectedUid: Int,
    private val authorized: () -> Boolean,
    private val bindingActive: () -> Boolean,
    private val quiescenceStillProven: () -> Boolean,
    private val readState: suspend () -> DittoPdlHandoffState?,
    private val withPdlAck: suspend (Int, String, suspend (DittoPdlFrozenSelection) -> Boolean?) -> Boolean?,
    private val withPointAck: (Int, String, () -> Boolean) -> Boolean?,
    private val persistCompletion: suspend (DittoPdlFrozenSelection, () -> Boolean) -> Boolean,
    private val retireTransfers: () -> Unit,
    private val callingUid: () -> Int,
    private val gate: Semaphore,
    private val currentBinding: () -> HostCutoverCompletionV1?,
) : ICutoverCompletionV1.Stub() {
    private val closed = AtomicBoolean(false)
    private val reconciled = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) {
            reconciled.set(false)
            retireTransfers()
        }
    }

    fun hasReconciliationGrant(): Boolean {
        if (reject(expectedUid, sessionId) != null || !quiescenceStillProven()) reconciled.set(false)
        return reconciled.get()
    }

    override fun readCutover(sessionId: String?): Bundle {
        val uid = callingUid()
        reject(uid, sessionId)?.let { return failure(it) }
        if (!gate.tryAcquire()) return failure(Contract.BUSY)
        try {
            reject(uid, sessionId)?.let { return failure(it) }
            if (!quiescenceStillProven()) return failure(Contract.UNAVAILABLE)
            val state = runBlocking { readState() } ?: return failure(Contract.UNAVAILABLE)
            if (!Contract.isValidCutoverId(state.cutoverId)) return failure(Contract.RECOVERY_REQUIRED)
            reject(uid, sessionId)?.let { return failure(it) }
            if (!quiescenceStillProven()) return failure(Contract.UNAVAILABLE)
            return Bundle().apply {
                putInt(Contract.KEY_CODE, Contract.OK)
                putInt(Contract.KEY_PHASE, if (state.completed) Contract.COMPLETED else Contract.PREPARED)
                putString(Contract.KEY_CUTOVER_ID, state.cutoverId)
            }
        } catch (_: IOException) {
            Log.e(TAG, "Ditto cutover state unavailable")
            return failure(Contract.UNAVAILABLE)
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Ditto cutover state invalid")
            return failure(Contract.RECOVERY_REQUIRED)
        } finally {
            gate.release()
        }
    }

    override fun commitCutover(sessionId: String?, cutoverId: String?): Int {
        val uid = callingUid()
        reject(uid, sessionId)?.let { return it }
        if (!Contract.isValidCutoverId(cutoverId)) return Contract.INVALID_ARGUMENT
        if (!gate.tryAcquire()) return Contract.BUSY
        var commitStarted = false
        try {
            reject(uid, sessionId)?.let { return it }
            if (!quiescenceStillProven()) return Contract.UNAVAILABLE
            val state = runBlocking { readState() } ?: return Contract.UNAVAILABLE
            if (state.cutoverId != cutoverId) return Contract.RECOVERY_REQUIRED
            if (!state.completed) {
                val completed = runBlocking {
                    withPdlAck(uid, requireNotNull(sessionId)) { frozen ->
                        if (frozen.cutoverId != cutoverId || reject(uid, sessionId) != null ||
                            !quiescenceStillProven()) return@withPdlAck false
                        commitStarted = true
                        persistCompletion(frozen) {
                            withPointAck(uid, sessionId) {
                                reject(uid, sessionId) == null && quiescenceStillProven()
                            } == true
                        }
                    }
                }
                if (reject(uid, sessionId) != null || !quiescenceStillProven()) {
                    return if (commitStarted) Contract.UNCERTAIN else Contract.INACTIVE_SESSION
                }
                if (completed != true) return Contract.UNAVAILABLE
            }
            if (reject(uid, sessionId) != null || !quiescenceStillProven()) return Contract.UNCERTAIN
            reconciled.set(true)
            if (reject(uid, sessionId) != null || !quiescenceStillProven()) {
                reconciled.set(false)
                return Contract.UNCERTAIN
            }
            return Contract.OK
        } catch (_: IOException) {
            Log.e(TAG, "Ditto cutover commit failed")
            return if (commitStarted) Contract.UNCERTAIN else Contract.UNAVAILABLE
        } catch (_: GeneralSecurityException) {
            Log.e(TAG, "Ditto cutover state unavailable")
            return if (commitStarted) Contract.UNCERTAIN else Contract.UNAVAILABLE
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Ditto cutover state invalid")
            return if (commitStarted) Contract.UNCERTAIN else Contract.RECOVERY_REQUIRED
        } catch (_: IllegalArgumentException) {
            Log.e(TAG, "Ditto cutover state invalid")
            return if (commitStarted) Contract.UNCERTAIN else Contract.RECOVERY_REQUIRED
        } finally {
            gate.release()
        }
    }

    private fun reject(uid: Int, session: String?): Int? = when {
        uid != expectedUid || !authorized() -> Contract.UNAUTHORIZED
        session == null || session.isBlank() -> Contract.INVALID_ARGUMENT
        closed.get() || currentBinding() !== this || !bindingActive() || session != sessionId ->
            Contract.INACTIVE_SESSION
        else -> null
    }

    private fun failure(code: Int) = Bundle().apply { putInt(Contract.KEY_CODE, code) }

    private companion object { const val TAG = "WearTAK-Cutover" }
}
