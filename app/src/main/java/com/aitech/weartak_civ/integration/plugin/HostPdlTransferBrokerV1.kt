package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Bundle
import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import com.weartak.plugin.sdk.pdltransfer.v1.IPdlTransferV1
import com.weartak.plugin.sdk.pdltransfer.v1.PdlTransferContractV1 as Contract

internal class HostPdlTransferBrokerV1(
    private val expectedUid: Int,
    private val authorized: () -> Boolean,
    private val isPreactivationSession: (String) -> Boolean,
    private val readSelection: suspend () -> DittoPdlMigrationSelection,
    private val selectionCurrent: suspend (DittoPdlMigrationSelection) -> Boolean,
    private val acknowledgeIfCurrent: suspend (DittoPdlMigrationSelection, () -> PdlMigrationSession.Status) -> PdlMigrationSession.Status?,
    private val callingUid: () -> Int = Binder::getCallingUid,
) : IPdlTransferV1.Stub() {
    private val lock = Any()
    private var closed = false
    private var currentId: String? = null
    private var current: HostPdlTransferV1? = null

    fun close() = synchronized(lock) {
        closed = true
        current?.close()
        current = null
        currentId = null
    }

    internal suspend fun <T : Any> withAcknowledgedCurrent(sessionId: String, action: () -> T): T? =
        endpoint(sessionId)?.withAcknowledgedCurrent(sessionId, action)

    internal suspend fun <T : Any> withAcknowledgedSelection(
        sessionId: String,
        action: suspend (DittoPdlMigrationSelection) -> T?,
    ): T? = endpoint(sessionId)?.withAcknowledgedSelection(sessionId, action)

    override fun read(sessionId: String?, transferId: String?): Bundle {
        val result = endpoint(sessionId) ?: return Bundle().apply {
            putInt(Contract.KEY_CODE, rejectedCode(sessionId))
        }
        return result.read(sessionId, transferId)
    }

    override fun acknowledge(sessionId: String?, transferId: String?, nonce: ByteArray?): Int {
        val result = endpoint(sessionId) ?: return rejectedCode(sessionId)
        return result.acknowledge(sessionId, transferId, nonce)
    }

    private fun endpoint(sessionId: String?): HostPdlTransferV1? {
        if (sessionId == null || callingUid() != expectedUid || !authorized()) return null
        return synchronized(lock) {
            if (closed || !isPreactivationSession(sessionId)) return@synchronized null
            if (currentId != sessionId) {
                current?.close()
                currentId = sessionId
                current = HostPdlTransferV1(
                    sessionId = sessionId,
                    expectedUid = expectedUid,
                    bindingActive = { isPreactivationSession(sessionId) },
                    authorize = authorized,
                    readSelection = readSelection,
                    selectionCurrent = selectionCurrent,
                    acknowledgeIfCurrent = acknowledgeIfCurrent,
                    callingUid = callingUid,
                )
            }
            current
        }
    }

    private fun rejectedCode(sessionId: String?): Int = when {
        callingUid() != expectedUid || !authorized() -> Contract.UNAUTHORIZED
        sessionId == null -> Contract.INVALID_ARGUMENT
        else -> Contract.INACTIVE_SESSION
    }
}
