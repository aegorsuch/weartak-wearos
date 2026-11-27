package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoOwnershipCoordinatorTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences(
        "ditto-ownership-${UUID.randomUUID()}", Context.MODE_PRIVATE,
    )

    private fun lease(number: Long) = DittoOwnershipCoordinator.Lease(
        UUID(0, number).toString(),
        number,
        UUID(1, number).toString(),
    )

    private fun initialize(coordinator: DittoOwnershipCoordinator) {
        assertTrue(coordinator.initializeAfterVerifiedFreshInstall { true })
    }

    private fun grant(coordinator: DittoOwnershipCoordinator, lease: DittoOwnershipCoordinator.Lease) {
        assertTrue(coordinator.beginAcquire(lease))
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, coordinator.snapshot().phase)
        assertTrue(coordinator.confirmVerifiedGrant(lease) { verified -> verified == lease })
    }

    private fun stopCleanly(
        coordinator: DittoOwnershipCoordinator,
        lease: DittoOwnershipCoordinator.Lease,
    ) {
        val operationId = UUID.randomUUID().toString()
        val drain = requireNotNull(coordinator.beginDrain(lease, operationId))
        assertTrue(drain.isCompleted)
        assertTrue(coordinator.confirmCleanStop(lease, operationId) { _, _ -> true })
    }

    @Test
    fun missingStateRequiresFreshOrNoOwnerProof() {
        val prefs = preferences()
        val coordinator = DittoOwnershipCoordinator(prefs)
        assertEquals(DittoOwnershipCoordinator.Phase.UNINITIALIZED, coordinator.snapshot().phase)
        assertFalse(coordinator.beginAcquire(lease(1)))
        assertFalse(coordinator.initializeAfterVerifiedFreshInstall { false })
        assertEquals(DittoOwnershipCoordinator.Phase.UNINITIALIZED, coordinator.snapshot().phase)

        assertTrue(coordinator.initializeAfterVerifiedFreshInstall { true })
        assertEquals(DittoOwnershipCoordinator.Phase.CLEAN, coordinator.snapshot().phase)
    }

    @Test
    fun acquirePersistsUncertaintyBeforeGrantAndRestartNeverReopensAdmission() {
        val prefs = preferences()
        val lease = lease(1)
        val coordinator = DittoOwnershipCoordinator(prefs)
        initialize(coordinator)
        assertTrue(coordinator.beginAcquire(lease))
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, coordinator.snapshot().phase)

        assertFalse(coordinator.confirmVerifiedGrant(lease) {
            false
        })
        val restarted = DittoOwnershipCoordinator(prefs)
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, restarted.snapshot().phase)
        assertNull(restarted.admit(lease))
    }

    @Test
    fun activeAndStoppingStatesRecoverAsUncertainAndRequireExactGrantRevalidation() {
        val prefs = preferences()
        val lease = lease(1)
        val coordinator = DittoOwnershipCoordinator(prefs)
        initialize(coordinator)
        grant(coordinator, lease)

        val afterActiveRestart = DittoOwnershipCoordinator(prefs)
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, afterActiveRestart.snapshot().phase)
        assertFalse(afterActiveRestart.confirmVerifiedGrant(lease) {
            false
        })
        assertTrue(afterActiveRestart.confirmVerifiedGrant(lease) {
            it == lease
        })

        val operationId = UUID.randomUUID().toString()
        assertNotNull(afterActiveRestart.beginDrain(lease, operationId))
        val afterStoppingRestart = DittoOwnershipCoordinator(prefs)
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, afterStoppingRestart.snapshot().phase)
        assertFalse(afterStoppingRestart.confirmVerifiedGrant(lease) { true })
        assertNull(afterStoppingRestart.admit(lease))
    }

    @Test
    fun drainRevokesNewAdmissionsAndRequiresHostDrainAndIndependentProof() {
        val coordinator = DittoOwnershipCoordinator(preferences())
        val lease = lease(1)
        initialize(coordinator)
        grant(coordinator, lease)
        val first = requireNotNull(coordinator.admit(lease))
        val second = requireNotNull(coordinator.admit(lease))
        val operationId = UUID.randomUUID().toString()
        val drain = requireNotNull(coordinator.beginDrain(lease, operationId))

        assertEquals(DittoOwnershipCoordinator.Phase.DRAINING, coordinator.snapshot().phase)
        assertNull(coordinator.admit(lease))
        assertFalse(drain.isCompleted)
        var proofCalled = false
        assertFalse(coordinator.confirmCleanStop(lease, operationId) { _, _ ->
            proofCalled = true
            true
        })
        assertFalse(proofCalled)

        first.close()
        first.close()
        assertFalse(drain.isCompleted)
        second.close()
        assertTrue(drain.isCompleted)
        assertFalse(coordinator.confirmCleanStop(lease, operationId) { _, _ ->
            false
        })
        assertEquals(DittoOwnershipCoordinator.Phase.DRAINING, coordinator.snapshot().phase)
        assertTrue(coordinator.confirmCleanStop(lease, operationId) { _, _ ->
            true
        })
        assertEquals(DittoOwnershipCoordinator.Phase.CLEAN, coordinator.snapshot().phase)
    }

    @Test
    fun cleanTerminalSurvivesRestartAndAllowsNextLeaseWithoutFreshInitialization() {
        val prefs = preferences()
        val first = lease(1)
        val coordinator = DittoOwnershipCoordinator(prefs)
        initialize(coordinator)
        grant(coordinator, first)
        stopCleanly(coordinator, first)

        val restarted = DittoOwnershipCoordinator(prefs)
        assertEquals(DittoOwnershipCoordinator.Phase.CLEAN, restarted.snapshot().phase)
        assertTrue(restarted.snapshot().everGranted)
        assertNull(restarted.snapshot().operationId)
        assertTrue(restarted.beginAcquire(lease(2)))
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, restarted.snapshot().phase)
    }

    @Test
    fun staleGrantAndUnsupportedProtocolCannotClearCurrentUncertainty() {
        val coordinator = DittoOwnershipCoordinator(preferences())
        val stale = lease(1)
        val current = lease(2)
        initialize(coordinator)
        grant(coordinator, stale)
        stopCleanly(coordinator, stale)
        assertTrue(coordinator.beginAcquire(current))

        assertFalse(coordinator.recordUnsupportedProtocol())
        var staleVerifierCalled = false
        assertFalse(coordinator.confirmVerifiedGrant(stale) {
            staleVerifierCalled = true
            true
        })
        assertFalse(staleVerifierCalled)
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, coordinator.snapshot().phase)
        assertNull(coordinator.admit(stale))
        assertNull(coordinator.admit(current))
    }

    @Test
    fun uncertaintyDuringGrantVerificationInvalidatesResult() {
        val coordinator = DittoOwnershipCoordinator(preferences())
        val lease = lease(1)
        initialize(coordinator)
        assertTrue(coordinator.beginAcquire(lease))
        val verifierEntered = CountDownLatch(1)
        val verifierRelease = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val worker = thread {
            result.set(coordinator.confirmVerifiedGrant(lease) {
                verifierEntered.countDown()
                verifierRelease.await(5, TimeUnit.SECONDS)
                true
            })
        }

        try {
            assertTrue(verifierEntered.await(5, TimeUnit.SECONDS))
            assertTrue(coordinator.markUncertain(lease))
        } finally {
            verifierRelease.countDown()
        }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(false, result.get())
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, coordinator.snapshot().phase)
    }

    @Test
    fun unsupportedMigrationNeedsExplicitNoOwnerProofBeforeAcquisition() {
        val coordinator = DittoOwnershipCoordinator(preferences())
        assertTrue(coordinator.recordUnsupportedProtocol())
        assertEquals(DittoOwnershipCoordinator.Phase.UNSUPPORTED, coordinator.snapshot().phase)
        assertFalse(coordinator.beginAcquire(lease(1)))
        assertFalse(coordinator.initializeAfterVerifiedNoOwner {
            false
        })
        assertEquals(DittoOwnershipCoordinator.Phase.UNSUPPORTED, coordinator.snapshot().phase)
        assertTrue(coordinator.initializeAfterVerifiedNoOwner {
            true
        })
        assertTrue(coordinator.beginAcquire(lease(1)))
    }

    @Test
    fun malformedJournalFailsClosedWithoutOverwritingIt() {
        val prefs = preferences()
        val malformed = "{not-json"
        assertTrue(prefs.edit().putString("ditto-ownership-journal-v1", malformed).commit())
        val coordinator = DittoOwnershipCoordinator(prefs)
        assertEquals(DittoOwnershipCoordinator.Phase.CORRUPT, coordinator.snapshot().phase)
        assertFalse(coordinator.initializeAfterVerifiedNoOwner {
            true
        })
        assertFalse(coordinator.beginAcquire(lease(1)))
        assertEquals(malformed, prefs.getString("ditto-ownership-journal-v1", null))
    }

    @Test
    fun fractionalAndOverflowedGenerationsAreCorrupt() {
        listOf("1.5", "9223372036854775808").forEach { generation ->
            val prefs = preferences()
            val malformed = """{"version":1,"phase":"UNCERTAIN","lease":{"sessionId":"00000000-0000-0000-0000-000000000001","generation":$generation,"incarnationId":"00000000-0000-0001-0000-000000000001"},"operationId":null,"everGranted":false}"""
            assertTrue(prefs.edit().putString("ditto-ownership-journal-v1", malformed).commit())
            assertEquals(
                DittoOwnershipCoordinator.Phase.CORRUPT,
                DittoOwnershipCoordinator(prefs).snapshot().phase,
            )
        }
    }

    @Test
    fun impossibleJournalPhaseCombinationsAreCorrupt() {
        val leaseJson =
            """"lease":{"sessionId":"00000000-0000-0000-0000-000000000001","generation":1,"incarnationId":"00000000-0000-0001-0000-000000000001"}"""
        val invalidStates = listOf(
            """{"version":1,"phase":"CLEAN",$leaseJson,"operationId":null,"everGranted":false}""",
            """{"version":1,"phase":"ACTIVE",$leaseJson,"operationId":"00000000-0000-0000-0000-000000000002","everGranted":true}""",
        )

        invalidStates.forEach { raw ->
            val prefs = preferences()
            assertTrue(prefs.edit().putString("ditto-ownership-journal-v1", raw).commit())
            assertEquals(
                DittoOwnershipCoordinator.Phase.CORRUPT,
                DittoOwnershipCoordinator(prefs).snapshot().phase,
            )
        }
    }

    @Test
    fun failedJournalCommitPoisonsCoordinator() {
        val delegate = preferences()
        val failing = object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun commit(): Boolean = false
                }
            }
        }
        val coordinator = DittoOwnershipCoordinator(failing)
        try {
            coordinator.initializeAfterVerifiedFreshInstall { true }
            fail("Expected uncertain durable write to reject initialization")
        } catch (_: IllegalStateException) {
            assertEquals(DittoOwnershipCoordinator.Phase.STORAGE_ERROR, coordinator.snapshot().phase)
        }
        assertFalse(coordinator.beginAcquire(lease(1)))
    }
}
