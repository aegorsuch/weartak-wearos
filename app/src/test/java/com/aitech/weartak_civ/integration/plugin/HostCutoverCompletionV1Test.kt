package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import com.aitech.weartak_civ.data.DittoPdlFrozenSelection
import com.aitech.weartak_civ.data.DittoPdlHandoffState
import com.aitech.weartak_civ.data.DittoPdlMigrationSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import com.weartak.plugin.sdk.cutover.v1.CutoverCompletionContractV1 as Contract
import com.weartak.plugin.sdk.cutover.v1.ICutoverCompletionV1
import com.weartak.plugin.sdk.pdlsnapshot.v1.PdlSnapshotContractV1 as Snapshot
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostCutoverCompletionV1Test {
    private val id = "0123456789abcdef0123456789abcdef"
    private val frozen = DittoPdlFrozenSelection(
        DittoPdlMigrationSelection(DittoPdlSource.COMMUNITY, "community"),
        id, "community", null, true,
    )
    @Volatile private var state = DittoPdlHandoffState(id, false)
    private var uid = 42
    private var trusted = true
    private var active = true
    private var quiescent = true
    private var pdlAck = false
    private var pointAck = false
    private var commitFailure = false
    private var entered: CountDownLatch? = null
    private var release: CountDownLatch? = null

    private fun open(session: String = "session") = HostCutoverBindings.open(
        sessionId = session,
        expectedUid = 42,
        authorized = { trusted },
        bindingActive = { active },
        quiescenceStillProven = { quiescent },
        readState = { state },
        withPdlAck = { _, _, action -> if (pdlAck) action(frozen) else null },
        withPointAck = { _, _, action -> if (pointAck) action() else null },
        persistCompletion = { _, proof ->
            if (commitFailure) throw IOException()
            if (!proof()) false else {
                entered?.countDown()
                check(release?.await(10, TimeUnit.SECONDS) != false)
                state = DittoPdlHandoffState(id, true)
                true
            }
        },
        callingUid = { uid },
    )

    @Test fun requiresBothAcknowledgementsAndMatchingAuthenticatedReceipt() {
        val host = requireNotNull(open())
        val remote = object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                host.asBinder().transact(code, data, reply, flags)
        }
        val proxy = ICutoverCompletionV1.Stub.asInterface(remote)
        val prepared = proxy.readCutover("session")
        assertTrue(Contract.isValidReadResult(prepared))
        assertEquals(Contract.PREPARED, prepared.getInt(Contract.KEY_PHASE))
        assertEquals(Contract.RECOVERY_REQUIRED, proxy.commitCutover("session", "f".repeat(32)))
        assertEquals(Contract.UNAVAILABLE, proxy.commitCutover("session", id))
        pdlAck = true
        assertEquals(Contract.UNAVAILABLE, proxy.commitCutover("session", id))
        assertFalse(state.completed)
        pointAck = true
        assertEquals(Contract.OK, proxy.commitCutover("session", id))
        assertTrue(state.completed)
        assertTrue(host.hasReconciliationGrant())
        assertEquals(Contract.COMPLETED, proxy.readCutover("session").getInt(Contract.KEY_PHASE))
        host.close()
        assertFalse(host.hasReconciliationGrant())
        val rebound = requireNotNull(open("new-session"))
        assertFalse(rebound.hasReconciliationGrant())
        assertEquals(Contract.RECOVERY_REQUIRED, rebound.commitCutover("new-session", "f".repeat(32)))
        assertFalse(rebound.hasReconciliationGrant())
        assertEquals(Contract.OK, rebound.commitCutover("new-session", id))
        assertTrue(rebound.hasReconciliationGrant())
        rebound.close()
    }

    @Test fun authorizationAndUncertainPersistenceFailClosed() {
        val host = requireNotNull(open())
        uid = 43
        assertEquals(Contract.UNAUTHORIZED, host.readCutover("session").getInt(Contract.KEY_CODE))
        uid = 42
        trusted = false
        assertEquals(Contract.UNAUTHORIZED, host.commitCutover("session", id))
        trusted = true
        active = false
        assertEquals(Contract.INACTIVE_SESSION, host.commitCutover("session", id))
        active = true
        quiescent = false
        assertEquals(Contract.UNAVAILABLE, host.commitCutover("session", id))
        quiescent = true
        pdlAck = true
        pointAck = true
        commitFailure = true
        assertEquals(Contract.UNCERTAIN, host.commitCutover("session", id))
        assertFalse(host.hasReconciliationGrant())
        host.close()
    }

    @Test fun replacementWaitsForRetiredCommitAndReconcilesDurableOutcome() {
        pdlAck = true
        pointAck = true
        entered = CountDownLatch(1)
        release = CountDownLatch(1)
        val host = requireNotNull(open())
        val result = AtomicReference<Int>()
        val thread = Thread { result.set(host.commitCutover("session", id)) }
        thread.start()
        try {
            assertTrue(requireNotNull(entered).await(10, TimeUnit.SECONDS))
            assertNull(open("new-session"))
        } finally {
            requireNotNull(release).countDown()
            thread.join(10_000)
        }
        assertFalse(thread.isAlive)
        assertEquals(Contract.UNCERTAIN, result.get())
        assertFalse(host.hasReconciliationGrant())
        val rebound = requireNotNull(open("new-session"))
        assertEquals(Contract.COMPLETED, rebound.readCutover("new-session").getInt(Contract.KEY_PHASE))
        assertFalse(rebound.hasReconciliationGrant())
        assertEquals(Contract.OK, rebound.commitCutover("new-session", id))
        rebound.close()
    }

    @Test fun replacementWaitsForRetiredSnapshotRead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val snapshot = HostPdlSnapshotV1(
            "session", 42, { true }, { true }, { true },
            readFrozen = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                frozen
            },
            withCurrent = { _, action -> action() },
            callingUid = { 42 },
        )
        val host = requireNotNull(HostCutoverBindings.open(
            "session", 42, { true }, { true }, { true },
            { state }, { _, _, _ -> null }, { _, _, _ -> null },
            { _, _ -> false }, { snapshot.close() }, { 42 },
        ))
        val result = AtomicReference<Int>()
        val thread = Thread { result.set(snapshot.read("session", "pdl").getInt(Snapshot.KEY_CODE)) }
        thread.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertNull(open("new-session"))
        } finally {
            release.countDown()
            thread.join(10_000)
        }
        assertFalse(thread.isAlive)
        assertEquals(Snapshot.INACTIVE_SESSION, result.get())
        assertEquals(Contract.INACTIVE_SESSION, host.readCutover("session").getInt(Contract.KEY_CODE))
        requireNotNull(open("new-session")).close()
    }
}
