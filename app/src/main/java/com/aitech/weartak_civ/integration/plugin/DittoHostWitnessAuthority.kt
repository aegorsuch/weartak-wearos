package com.aitech.weartak_civ.integration.plugin

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core-side authority behind the canonical SDK witness Binder adapter.
 * Lifecycle revocation must use this gate; direct coordinator mutation bypasses
 * grant/revoke serialization. The SDK adapter remains inactive until its pin qualifies.
 * [authorizeCaller] must verify package identity and the current single signer.
 */
internal class DittoHostWitnessAuthority(
    private val coordinator: DittoOwnershipCoordinator,
    private val expectedUid: Int,
    private val authorizeCaller: (Int) -> Boolean,
    private val currentLease: () -> LeaseBinding?,
) : AutoCloseable {
    data class LeaseBinding(
        val lease: DittoOwnershipCoordinator.Lease,
        val sessionBinder: IBinder,
        val connectionToken: Any,
        val isActive: () -> Boolean,
    )

    enum class Status {
        GRANTED,
        PENDING,
        UNCERTAIN,
        INVALID_ARGUMENT,
        UNAUTHORIZED,
        INACTIVE_LEASE,
        OPERATION_CONFLICT,
        NOT_FOUND,
        REVOKED,
    }

    private class Record(
        val binding: LeaseBinding,
        val lifetime: IBinder,
    ) {
        val dead = AtomicBoolean(false)
        val linked = AtomicBoolean(false)
        lateinit var deathRecipient: IBinder.DeathRecipient
    }

    private val lock = Any()
    private var pending: Record? = null
    private var registered: Record? = null
    private var closed = false

    fun register(
        callingUid: Int,
        session: IBinder?,
        generation: Long,
        incarnationId: String?,
        lifetime: IBinder?,
    ): Status {
        if (session == null || lifetime == null || generation <= 0 || incarnationId == null ||
            !isCanonicalDittoIncarnationId(incarnationId)
        ) return Status.INVALID_ARGUMENT
        if (!isAuthorized(callingUid)) return Status.UNAUTHORIZED

        val binding = currentLease()
        if (binding == null || !matches(binding, session, generation, incarnationId)) {
            return Status.INACTIVE_LEASE
        }

        val candidate = Record(binding, lifetime)
        synchronized(lock) {
            if (closed) return Status.INACTIVE_LEASE
            registered?.let {
                return if (sameRecord(it, binding, session, generation, incarnationId, lifetime)) {
                    statusFor(it)
                } else {
                    Status.OPERATION_CONFLICT
                }
            }
            pending?.let {
                return if (sameRecord(it, binding, session, generation, incarnationId, lifetime)) {
                    Status.UNCERTAIN
                } else {
                    Status.OPERATION_CONFLICT
                }
            }
            val snapshot = coordinator.snapshot()
            if (snapshot.lease != binding.lease || snapshot.phase != DittoOwnershipCoordinator.Phase.UNCERTAIN ||
                snapshot.operationId != null || snapshot.everGranted
            ) {
                return statusForSnapshot(snapshot, binding.lease)
            }
            pending = candidate
        }

        candidate.deathRecipient = IBinder.DeathRecipient { onLifetimeDeath(candidate) }
        try {
            lifetime.linkToDeath(candidate.deathRecipient, 0)
            candidate.linked.set(true)
        } catch (_: RemoteException) {
            clearPending(candidate)
            coordinator.markUncertain(binding.lease)
            return Status.UNCERTAIN
        }

        val current = currentLease()
        if (!isCurrent(binding, current) || candidate.dead.get() || !lifetime.isBinderAlive) {
            clearPending(candidate)
            unlink(candidate)
            coordinator.markUncertain(binding.lease)
            return Status.UNCERTAIN
        }

        val granted = synchronized(lock) {
            if (closed || pending !== candidate || candidate.dead.get() ||
                !lifetime.isBinderAlive || !isCurrent(binding, currentLease())
            ) return@synchronized false
            try {
                val persisted = coordinator.confirmVerifiedGrant(binding.lease) {
                    !candidate.dead.get() && lifetime.isBinderAlive &&
                        isCurrent(binding, currentLease())
                }
                if (!persisted) return@synchronized false
                if (candidate.dead.get() || !lifetime.isBinderAlive ||
                    !isCurrent(binding, currentLease())
                ) {
                    coordinator.markUncertain(binding.lease)
                    return@synchronized false
                }
                pending = null
                registered = candidate
                true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Witness grant could not be persisted", error)
                false
            }
        }
        if (granted) return Status.GRANTED

        clearPending(candidate)
        unlink(candidate)
        return Status.UNCERTAIN
    }

    fun getGrantStatus(
        callingUid: Int,
        session: IBinder?,
        generation: Long,
        incarnationId: String?,
        lifetime: IBinder?,
    ): Status {
        if (session == null || lifetime == null || generation <= 0 || incarnationId == null ||
            !isCanonicalDittoIncarnationId(incarnationId)
        ) return Status.INVALID_ARGUMENT
        if (!isAuthorized(callingUid)) return Status.UNAUTHORIZED

        val binding = currentLease()
        if (binding == null || !matches(binding, session, generation, incarnationId)) {
            return Status.INACTIVE_LEASE
        }
        return synchronized(lock) {
            if (closed) return@synchronized Status.INACTIVE_LEASE
            val exactPending = pending?.takeIf {
                sameRecord(it, binding, session, generation, incarnationId, lifetime)
            }
            if (exactPending != null) {
                val snapshot = coordinator.snapshot()
                return@synchronized if (exactPending.dead.get() || !lifetime.isBinderAlive) {
                    Status.UNCERTAIN
                } else if (snapshot.phase == DittoOwnershipCoordinator.Phase.UNCERTAIN &&
                    snapshot.lease == binding.lease && !snapshot.everGranted
                ) {
                    Status.PENDING
                } else {
                    statusForSnapshot(snapshot, binding.lease)
                }
            }
            val exact = registered?.takeIf {
                sameRecord(it, binding, session, generation, incarnationId, lifetime)
            }
            if (exact != null) return@synchronized statusFor(exact)
            statusForSnapshot(coordinator.snapshot(), binding.lease)
        }
    }

    fun beginDrain(
        lease: DittoOwnershipCoordinator.Lease,
        operationId: String,
    ) = synchronized(lock) {
        if (closed || registered?.binding?.lease != lease) return@synchronized null
        coordinator.beginDrain(lease, operationId)
    }

    fun confirmCleanStop(
        lease: DittoOwnershipCoordinator.Lease,
        operationId: String,
        verifier: DittoOwnershipCoordinator.CleanStopVerifier,
    ): Boolean {
        val record = synchronized(lock) {
            if (closed) return@synchronized null
            registered?.takeIf { it.binding.lease == lease }
        } ?: return false
        if (record.dead.get() || !record.lifetime.isBinderAlive ||
            !isCurrent(record.binding, currentLease())
        ) {
            return false
        }
        if (!coordinator.confirmCleanStop(lease, operationId) { verifiedLease, verifiedOperation ->
                verifier.verify(verifiedLease, verifiedOperation) &&
                    !record.dead.get() && record.lifetime.isBinderAlive &&
                    isCurrent(record.binding, currentLease())
            }
        ) return false
        val retired = synchronized(lock) {
            if (registered !== record) return@synchronized false
            registered = null
            record.dead.set(true)
            true
        }
        if (retired) unlink(record)
        return true
    }

    override fun close() {
        val record = synchronized(lock) {
            if (closed) return
            closed = true
            val current = pending ?: registered
            pending = null
            registered = null
            current?.dead?.set(true)
            val lease = current?.binding?.lease ?: coordinator.snapshot().lease
            lease?.let { coordinator.markUncertain(it) }
            current
        }
        record?.let(::unlink)
    }

    private fun isAuthorized(callingUid: Int): Boolean =
        callingUid == expectedUid && authorizeCaller(callingUid)

    private fun matches(
        binding: LeaseBinding,
        session: IBinder,
        generation: Long,
        incarnationId: String,
    ): Boolean =
        binding.sessionBinder === session &&
            binding.lease.generation == generation &&
            binding.lease.incarnationId == incarnationId &&
            binding.sessionBinder.isBinderAlive &&
            binding.isActive()

    private fun isCurrent(expected: LeaseBinding, current: LeaseBinding?): Boolean =
        current != null &&
            current.connectionToken === expected.connectionToken &&
            current.sessionBinder === expected.sessionBinder &&
            current.lease == expected.lease &&
            current.sessionBinder.isBinderAlive &&
            current.isActive()

    private fun sameRecord(
        record: Record,
        binding: LeaseBinding,
        session: IBinder,
        generation: Long,
        incarnationId: String,
        lifetime: IBinder,
    ): Boolean =
        record.binding.connectionToken === binding.connectionToken &&
            record.binding.sessionBinder === session &&
            record.binding.lease.generation == generation &&
            record.binding.lease.incarnationId == incarnationId &&
            record.lifetime === lifetime

    private fun statusFor(record: Record): Status {
        if (record.dead.get() || !record.lifetime.isBinderAlive || !record.binding.isActive()) {
            coordinator.markUncertain(record.binding.lease)
            return Status.UNCERTAIN
        }
        val snapshot = coordinator.snapshot()
        if (snapshot.lease != record.binding.lease) return Status.NOT_FOUND
        return when (snapshot.phase) {
            DittoOwnershipCoordinator.Phase.ACTIVE -> Status.GRANTED
            DittoOwnershipCoordinator.Phase.DRAINING,
            DittoOwnershipCoordinator.Phase.CLEAN -> Status.REVOKED
            DittoOwnershipCoordinator.Phase.UNCERTAIN,
            DittoOwnershipCoordinator.Phase.STORAGE_ERROR,
            DittoOwnershipCoordinator.Phase.CORRUPT -> Status.UNCERTAIN
            DittoOwnershipCoordinator.Phase.UNINITIALIZED,
            DittoOwnershipCoordinator.Phase.UNSUPPORTED -> Status.UNCERTAIN
        }
    }

    private fun statusForSnapshot(
        snapshot: DittoOwnershipCoordinator.Snapshot,
        lease: DittoOwnershipCoordinator.Lease,
    ): Status {
        return when (snapshot.phase) {
            DittoOwnershipCoordinator.Phase.ACTIVE ->
                if (snapshot.lease == lease) Status.UNCERTAIN else Status.NOT_FOUND
            DittoOwnershipCoordinator.Phase.DRAINING,
            DittoOwnershipCoordinator.Phase.CLEAN ->
                if (snapshot.lease == lease) Status.REVOKED else Status.NOT_FOUND
            DittoOwnershipCoordinator.Phase.UNINITIALIZED,
            DittoOwnershipCoordinator.Phase.UNSUPPORTED,
            DittoOwnershipCoordinator.Phase.UNCERTAIN,
            DittoOwnershipCoordinator.Phase.CORRUPT,
            DittoOwnershipCoordinator.Phase.STORAGE_ERROR -> Status.UNCERTAIN
        }
    }

    private fun clearPending(record: Record) {
        synchronized(lock) {
            if (pending === record) pending = null
        }
    }

    private fun onLifetimeDeath(record: Record) {
        record.dead.set(true)
        synchronized(lock) {
            when {
                pending === record -> {
                    pending = null
                    coordinator.markUncertain(record.binding.lease)
                }
                registered === record -> {
                    registered = null
                    coordinator.markUncertain(record.binding.lease)
                }
                else -> Unit
            }
        }
    }

    private fun unlink(record: Record) {
        if (!record.linked.compareAndSet(true, false)) return
        try {
            record.lifetime.unlinkToDeath(record.deathRecipient, 0)
        } catch (_: NoSuchElementException) {
            Log.w(TAG, "Witness lifetime death recipient already removed")
        }
    }

    private companion object {
        const val TAG = "DittoHostWitness"
    }
}
