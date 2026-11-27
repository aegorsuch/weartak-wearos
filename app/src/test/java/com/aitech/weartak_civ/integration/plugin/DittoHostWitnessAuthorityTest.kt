package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Binder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoHostWitnessAuthorityTest {
    private val callerUid = 12_345

    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences(
        "ditto-host-witness-${UUID.randomUUID()}", Context.MODE_PRIVATE,
    )

    private fun lease(number: Long) = DittoOwnershipCoordinator.Lease(
        UUID(0, number).toString(),
        number,
        number.toString(16).padStart(32, '0'),
    )

    private data class Fixture(
        val coordinator: DittoOwnershipCoordinator,
        val binding: DittoHostWitnessAuthority.LeaseBinding,
        val authority: DittoHostWitnessAuthority,
    )

    private fun fixture(
        authorizeCaller: (Int) -> Boolean = { true },
        prefs: SharedPreferences = preferences(),
    ): Fixture {
        val coordinator = DittoOwnershipCoordinator(prefs)
        assertTrue(coordinator.initializeAfterVerifiedFreshInstall { true })
        val lease = lease(1)
        assertTrue(coordinator.beginAcquireIntent(lease.sessionId, lease.incarnationId))
        assertTrue(coordinator.recordAcquiredLease(lease.sessionId, lease.generation, lease.incarnationId))
        val binding = DittoHostWitnessAuthority.LeaseBinding(
            lease = lease,
            sessionBinder = Binder(),
            connectionToken = Any(),
            isActive = { true },
        )
        val authority = DittoHostWitnessAuthority(
            coordinator = coordinator,
            expectedUid = callerUid,
            authorizeCaller = authorizeCaller,
            currentLease = { binding },
        )
        return Fixture(coordinator, binding, authority)
    }

    @Test
    fun exactAuthenticatedLeaseAndLifetimePersistGrant() {
        val fixture = fixture()
        val lifetime = Binder()

        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.register(
                callerUid,
                fixture.binding.sessionBinder,
                fixture.binding.lease.generation,
                fixture.binding.lease.incarnationId,
                lifetime,
            ),
        )
        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.register(
                callerUid,
                fixture.binding.sessionBinder,
                fixture.binding.lease.generation,
                fixture.binding.lease.incarnationId,
                lifetime,
            ),
        )
        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.getGrantStatus(
                callerUid,
                fixture.binding.sessionBinder,
                fixture.binding.lease.generation,
                fixture.binding.lease.incarnationId,
                lifetime,
            ),
        )
        val admission = requireNotNull(fixture.coordinator.admit(fixture.binding.lease))
        admission.close()
    }

    @Test
    fun unauthorizedOrWrongSessionCannotRegisterWitness() {
        val fixture = fixture()
        val lifetime = Binder()
        val lease = fixture.binding.lease

        assertEquals(
            DittoHostWitnessAuthority.Status.UNAUTHORIZED,
            fixture.authority.register(
                callerUid + 1,
                fixture.binding.sessionBinder,
                lease.generation,
                lease.incarnationId,
                lifetime,
            ),
        )
        val untrustedSigner = fixture(authorizeCaller = { false })
        assertEquals(
            DittoHostWitnessAuthority.Status.UNAUTHORIZED,
            untrustedSigner.authority.register(
                callerUid,
                untrustedSigner.binding.sessionBinder,
                untrustedSigner.binding.lease.generation,
                untrustedSigner.binding.lease.incarnationId,
                Binder(),
            ),
        )
        assertEquals(
            DittoHostWitnessAuthority.Status.INACTIVE_LEASE,
            fixture.authority.register(
                callerUid,
                Binder(),
                lease.generation,
                lease.incarnationId,
                lifetime,
            ),
        )
        assertNull(fixture.coordinator.admit(lease))
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, fixture.coordinator.snapshot().phase)
    }

    @Test
    fun conflictingLifetimeCannotReplaceRegisteredWitness() {
        val fixture = fixture()
        val lease = fixture.binding.lease
        val registeredLifetime = Binder()
        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, registeredLifetime,
            ),
        )

        assertEquals(
            DittoHostWitnessAuthority.Status.OPERATION_CONFLICT,
            fixture.authority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, Binder(),
            ),
        )
        assertEquals(DittoOwnershipCoordinator.Phase.ACTIVE, fixture.coordinator.snapshot().phase)
    }

    @Test
    fun drainRevokesWitnessStatusAndAdmission() {
        val fixture = fixture()
        val lease = fixture.binding.lease
        val lifetime = Binder()
        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, lifetime,
            ),
        )

        assertNotNull(fixture.authority.beginDrain(lease, UUID.randomUUID().toString()))
        assertEquals(
            DittoHostWitnessAuthority.Status.REVOKED,
            fixture.authority.getGrantStatus(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, lifetime,
            ),
        )
        assertNull(fixture.coordinator.admit(lease))
    }

    @Test
    fun cleanStopRetiresWitnessOnlyAfterIndependentProof() {
        val fixture = fixture()
        val lease = fixture.binding.lease
        val lifetime = Binder()
        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, lifetime,
            ),
        )
        val operationId = UUID.randomUUID().toString()
        assertTrue(requireNotNull(fixture.authority.beginDrain(lease, operationId)).isCompleted)
        assertFalse(fixture.authority.confirmCleanStop(lease, operationId) { _, _ -> false })
        assertEquals(DittoOwnershipCoordinator.Phase.DRAINING, fixture.coordinator.snapshot().phase)

        assertTrue(fixture.authority.confirmCleanStop(lease, operationId) { _, _ -> true })
        assertEquals(DittoOwnershipCoordinator.Phase.CLEAN, fixture.coordinator.snapshot().phase)
        assertEquals(
            DittoHostWitnessAuthority.Status.REVOKED,
            fixture.authority.getGrantStatus(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, lifetime,
            ),
        )
    }

    @Test
    fun closingWitnessRetainsUncertainty() {
        val fixture = fixture()
        val lease = fixture.binding.lease
        val lifetime = Binder()
        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, lifetime,
            ),
        )

        fixture.authority.close()
        assertEquals(DittoOwnershipCoordinator.Phase.UNCERTAIN, fixture.coordinator.snapshot().phase)
        assertNull(fixture.coordinator.admit(lease))
    }

    @Test
    fun newLifetimeCannotReopenPreviouslyGrantedUncertainty() {
        val fixture = fixture()
        val lease = fixture.binding.lease
        val originalLifetime = Binder()
        assertEquals(
            DittoHostWitnessAuthority.Status.GRANTED,
            fixture.authority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, originalLifetime,
            ),
        )

        fixture.authority.close()
        val restartedAuthority = DittoHostWitnessAuthority(
            coordinator = fixture.coordinator,
            expectedUid = callerUid,
            authorizeCaller = { true },
            currentLease = { fixture.binding },
        )
        assertEquals(
            DittoHostWitnessAuthority.Status.UNCERTAIN,
            restartedAuthority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, Binder(),
            ),
        )
        assertNull(fixture.coordinator.admit(lease))
    }

    @Test
    fun failedGrantPersistenceReturnsUncertainAndBlocksAdmission() {
        val delegate = preferences()
        val failWrites = AtomicBoolean(false)
        val failing = object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean =
                        if (failWrites.get()) false else editor.commit()
                }
            }
        }
        val fixture = fixture(prefs = failing)
        val lease = fixture.binding.lease
        failWrites.set(true)

        assertEquals(
            DittoHostWitnessAuthority.Status.UNCERTAIN,
            fixture.authority.register(
                callerUid, fixture.binding.sessionBinder, lease.generation,
                lease.incarnationId, Binder(),
            ),
        )
        assertEquals(DittoOwnershipCoordinator.Phase.STORAGE_ERROR, fixture.coordinator.snapshot().phase)
        assertNull(fixture.coordinator.admit(lease))
    }

    @Test
    fun missingJournalCannotBeReportedAsNotFoundOrGranted() {
        val coordinator = DittoOwnershipCoordinator(preferences())
        val lease = lease(1)
        val binding = DittoHostWitnessAuthority.LeaseBinding(
            lease = lease,
            sessionBinder = Binder(),
            connectionToken = Any(),
            isActive = { true },
        )
        val authority = DittoHostWitnessAuthority(
            coordinator = coordinator,
            expectedUid = callerUid,
            authorizeCaller = { true },
            currentLease = { binding },
        )

        assertEquals(
            DittoHostWitnessAuthority.Status.UNCERTAIN,
            authority.getGrantStatus(
                callerUid, binding.sessionBinder, lease.generation,
                lease.incarnationId, Binder(),
            ),
        )
    }
}
