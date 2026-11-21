package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PdlMigrationSessionTest {
    private var current = DittoPdlMigrationSelection(DittoPdlSource.CUSTOM, "")
    private var active = true
    private var trusted = true

    private fun session(
        afterAcknowledgment: suspend () -> Unit = {},
        checkSelection: suspend (DittoPdlMigrationSelection) -> Boolean = {
            it.source == current.source && it.identity == current.identity
        },
    ) = PdlMigrationSession(
        sessionId = "session",
        authorized = { trusted && it == 42 },
        bindingActive = { active },
        readSelection = { current },
        selectionCurrent = checkSelection,
        acknowledgeIfCurrent = { selection, action ->
            if (checkSelection(selection)) action().also { afterAcknowledgment() } else null
        },
        maxIdentityLength = 16_384,
        validTransferId = { it.isNotBlank() && it.length <= 128 },
    )

    @Test fun transferIsStableAndPreservesEmptyCustomIdentity() = runTest {
        val session = session()
        val first = session.read(42, "session", "transfer")
        val nonce = requireNotNull(first.nonce)
        assertEquals(PdlMigrationSession.Status.OK, first.status)
        assertEquals(DittoPdlSource.CUSTOM, first.selection?.source)
        assertEquals("", first.selection?.identity)
        val again = session.read(42, "session", "transfer")
        assertArrayEquals(nonce, again.nonce)
        requireNotNull(again.nonce)[0] = (nonce[0].toInt() xor 1).toByte()
        assertEquals(PdlMigrationSession.Status.OK, session.acknowledge(42, "session", "transfer", nonce))
        assertEquals(PdlMigrationSession.Status.OK, session.acknowledge(42, "session", "transfer", nonce))
        assertEquals(PdlMigrationSession.Status.BUSY, session.read(42, "session", "other").status)
    }

    @Test fun selectionChangeOrUntrustedCallerCannotAcknowledge() = runTest {
        val session = session()
        val read = session.read(42, "session", "transfer")
        val nonce = requireNotNull(read.nonce)
        val wrongNonce = nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(PdlMigrationSession.Status.INVALID,
            session.acknowledge(42, "session", "transfer", wrongNonce))
        assertEquals(PdlMigrationSession.Status.UNAUTHORIZED,
            session.acknowledge(43, "session", "transfer", nonce))
        current = DittoPdlMigrationSelection(DittoPdlSource.COMMUNITY, "")
        assertEquals(PdlMigrationSession.Status.CHANGED, session.read(42, "session", "transfer").status)
        assertEquals(PdlMigrationSession.Status.CHANGED,
            session.acknowledge(42, "session", "transfer", nonce))
        current = DittoPdlMigrationSelection(DittoPdlSource.CUSTOM, "")
        assertEquals(PdlMigrationSession.Status.CHANGED,
            session.acknowledge(42, "session", "transfer", nonce))
        assertEquals(PdlMigrationSession.Status.INACTIVE,
            session.acknowledge(42, "old-session", "transfer", nonce))
    }

    @Test fun closeDuringSelectionCheckCannotReturnSuccess() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val session = session {
            entered.complete(Unit)
            resume.await()
            true
        }
        val read = async { session.read(42, "session", "transfer") }
        entered.await()
        session.close()
        resume.complete(Unit)
        assertEquals(PdlMigrationSession.Status.INACTIVE, read.await().status)
    }

    @Test fun trustLossDuringAcknowledgmentCannotReturnSuccess() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var checks = 0
        val session = session {
            checks++
            if (checks == 2) {
                entered.complete(Unit)
                resume.await()
            }
            true
        }
        val nonce = requireNotNull(session.read(42, "session", "transfer").nonce)
        val ack = async { session.acknowledge(42, "session", "transfer", nonce) }
        entered.await()
        trusted = false
        resume.complete(Unit)
        assertEquals(PdlMigrationSession.Status.UNAUTHORIZED, ack.await())
    }

    @Test fun oversizedSelectionIsNotTruncated() = runTest {
        current = DittoPdlMigrationSelection(DittoPdlSource.CUSTOM, "x".repeat(16_385))
        val read = session().read(42, "session", "transfer")
        assertEquals(PdlMigrationSession.Status.TOO_LARGE, read.status)
        assertNull(read.selection)
        assertEquals(16_385, current.identity.length)
    }

    @Test fun closeAfterSerializedAcknowledgmentCannotReturnSuccess() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val session = session(afterAcknowledgment = {
            entered.complete(Unit)
            resume.await()
        })
        val nonce = requireNotNull(session.read(42, "session", "transfer").nonce)
        val ack = async { session.acknowledge(42, "session", "transfer", nonce) }
        entered.await()
        session.close()
        resume.complete(Unit)
        assertEquals(PdlMigrationSession.Status.INACTIVE, ack.await())
    }
}
