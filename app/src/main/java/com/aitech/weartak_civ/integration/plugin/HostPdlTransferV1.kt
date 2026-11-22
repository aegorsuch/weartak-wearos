package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import com.weartak.plugin.sdk.pdltransfer.v1.IPdlTransferV1
import com.weartak.plugin.sdk.pdltransfer.v1.PdlTransferContractV1 as Contract
import java.io.IOException
import kotlinx.coroutines.runBlocking

internal class HostPdlTransferV1(
    sessionId: String,
    expectedUid: Int,
    bindingActive: () -> Boolean,
    authorize: () -> Boolean,
    readSelection: suspend () -> DittoPdlMigrationSelection,
    selectionCurrent: suspend (DittoPdlMigrationSelection) -> Boolean,
    acknowledgeIfCurrent: suspend (DittoPdlMigrationSelection, () -> PdlMigrationSession.Status) -> PdlMigrationSession.Status?,
    private val callingUid: () -> Int = Binder::getCallingUid,
) : IPdlTransferV1.Stub() {
    private val session = PdlMigrationSession(
        sessionId = sessionId,
        authorized = { uid -> uid == expectedUid && authorize() },
        bindingActive = bindingActive,
        readSelection = readSelection,
        selectionCurrent = selectionCurrent,
        acknowledgeIfCurrent = acknowledgeIfCurrent,
        maxIdentityLength = Contract.MAX_IDENTITY_UTF16_UNITS,
        validTransferId = Contract::isValidTransferId,
    )

    fun close() = session.close()

    internal suspend fun <T : Any> withAcknowledgedCurrent(sessionId: String, action: () -> T): T? =
        session.withAcknowledgedCurrent(callingUid(), sessionId, action)

    internal suspend fun <T : Any> withAcknowledgedSelection(
        sessionId: String,
        action: suspend (DittoPdlMigrationSelection) -> T?,
    ): T? = session.withAcknowledgedSelection(callingUid(), sessionId, action)

    override fun read(sessionId: String?, transferId: String?): Bundle {
        if (sessionId == null || transferId == null) return rejected(Contract.INVALID_ARGUMENT)
        val result = try {
            runBlocking { session.read(callingUid(), sessionId, transferId) }
        } catch (_: IOException) {
            Log.e(TAG, "PDL transfer storage read failed")
            return rejected(Contract.INTERNAL_ERROR)
        } catch (_: IllegalStateException) {
            Log.e(TAG, "PDL transfer state invalid")
            return rejected(Contract.INTERNAL_ERROR)
        }
        if (result.status != PdlMigrationSession.Status.OK) return rejected(result.status.toCode())
        val selection = checkNotNull(result.selection)
        return Bundle().apply {
            putInt(Contract.KEY_CODE, Contract.OK)
            putString(Contract.KEY_TRANSFER_ID, transferId)
            putInt(Contract.KEY_SOURCE, when (selection.source) {
                DittoPdlSource.COMMUNITY -> Contract.COMMUNITY
                DittoPdlSource.CUSTOM -> Contract.CUSTOM
            })
            putString(Contract.KEY_IDENTITY, selection.identity)
            putByteArray(Contract.KEY_NONCE, checkNotNull(result.nonce))
        }
    }

    override fun acknowledge(sessionId: String?, transferId: String?, nonce: ByteArray?): Int {
        if (sessionId == null || transferId == null || nonce == null) return Contract.INVALID_ARGUMENT
        return try {
            runBlocking { session.acknowledge(callingUid(), sessionId, transferId, nonce) }.toCode()
        } catch (_: IOException) {
            Log.e(TAG, "PDL transfer storage acknowledgement failed")
            Contract.INTERNAL_ERROR
        } catch (_: IllegalStateException) {
            Log.e(TAG, "PDL transfer state invalid")
            Contract.INTERNAL_ERROR
        }
    }

    private fun rejected(code: Int) = Bundle().apply { putInt(Contract.KEY_CODE, code) }

    private fun PdlMigrationSession.Status.toCode(): Int = when (this) {
        PdlMigrationSession.Status.OK -> Contract.OK
        PdlMigrationSession.Status.INVALID -> Contract.INVALID_ARGUMENT
        PdlMigrationSession.Status.UNAUTHORIZED -> Contract.UNAUTHORIZED
        PdlMigrationSession.Status.INACTIVE -> Contract.INACTIVE_SESSION
        PdlMigrationSession.Status.CHANGED -> Contract.CHANGED
        PdlMigrationSession.Status.BUSY -> Contract.BUSY
        PdlMigrationSession.Status.TOO_LARGE -> Contract.TOO_LARGE
    }

    private companion object { const val TAG = "WearTAK-PdlTransfer" }
}
