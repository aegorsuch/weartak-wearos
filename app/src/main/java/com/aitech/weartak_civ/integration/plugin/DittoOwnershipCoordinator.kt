package com.aitech.weartak_civ.integration.plugin

import android.content.SharedPreferences
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.json.JSONException
import org.json.JSONObject

private fun isCanonicalUuid(value: String): Boolean = try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) {
    false
}

/**
 * Host-process durable ownership fence. Proof providers are deliberately supplied by callers;
 * this process-local coordinator does not treat Binder replies, disconnects, or missing state
 * as shutdown proof.
 */
internal class DittoOwnershipCoordinator(private val preferences: SharedPreferences) {
    enum class Phase {
        UNINITIALIZED,
        CLEAN,
        UNSUPPORTED,
        UNCERTAIN,
        ACTIVE,
        DRAINING,
        CORRUPT,
        STORAGE_ERROR,
    }

    data class Lease(val sessionId: String, val generation: Long, val incarnationId: String) {
        init {
            require(isCanonicalUuid(sessionId))
            require(generation > 0)
            require(isCanonicalUuid(incarnationId))
        }
    }

    data class Snapshot(
        val phase: Phase,
        val lease: Lease? = null,
        val operationId: String? = null,
        val everGranted: Boolean = false,
    )

    fun interface FreshInstallVerifier {
        fun verify(): Boolean
    }

    fun interface NoOwnerVerifier {
        fun verify(): Boolean
    }

    fun interface GrantVerifier {
        fun verify(lease: Lease): Boolean
    }

    fun interface CleanStopVerifier {
        fun verify(lease: Lease, operationId: String): Boolean
    }

    private val lock = Any()
    private var snapshot = load()
    private var revision = 0L
    private var inFlight = 0
    private var drain = completedDrain()

    init {
        if (snapshot.phase == Phase.ACTIVE || snapshot.phase == Phase.DRAINING) {
            persist(snapshot.copy(phase = Phase.UNCERTAIN))
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshot }

    /** Missing state is not proof of first use. */
    fun initializeAfterVerifiedFreshInstall(verifier: FreshInstallVerifier): Boolean {
        if (!verifier.verify()) return false
        return synchronized(lock) {
            if (snapshot.phase != Phase.UNINITIALIZED) return@synchronized false
            persist(Snapshot(Phase.CLEAN))
            true
        }
    }

    /** Used only after independent evidence establishes that no old runtime remains active. */
    fun initializeAfterVerifiedNoOwner(verifier: NoOwnerVerifier): Boolean {
        if (!verifier.verify()) return false
        return synchronized(lock) {
            if (snapshot.phase != Phase.UNINITIALIZED && snapshot.phase != Phase.UNSUPPORTED) {
                return@synchronized false
            }
            persist(snapshot.copy(phase = Phase.CLEAN))
            true
        }
    }

    /** Records capability absence without clearing an unresolved or previously active lease. */
    fun recordUnsupportedProtocol(): Boolean = synchronized(lock) {
        if (snapshot.phase != Phase.UNINITIALIZED && snapshot.phase != Phase.CLEAN &&
            snapshot.phase != Phase.UNSUPPORTED
        ) return@synchronized false
        if (inFlight != 0) return@synchronized false
        if (snapshot.phase != Phase.UNSUPPORTED) {
            persist(snapshot.copy(phase = Phase.UNSUPPORTED))
        }
        true
    }

    /** Persists uncertainty before callers perform any side-effectful remote operation. */
    fun beginAcquire(lease: Lease): Boolean = synchronized(lock) {
        if (snapshot.phase != Phase.CLEAN || inFlight != 0 || snapshot.lease == lease) {
            return@synchronized false
        }
        drain = completedDrain()
        persist(snapshot.copy(phase = Phase.UNCERTAIN, lease = lease, operationId = null))
        true
    }

    fun confirmVerifiedGrant(lease: Lease, verifier: GrantVerifier): Boolean {
        val verifiedRevision = synchronized(lock) {
            if (snapshot.phase != Phase.UNCERTAIN || snapshot.lease != lease ||
                snapshot.operationId != null
            ) return@synchronized null
            revision
        } ?: return false
        if (!verifier.verify(lease)) return false
        return synchronized(lock) {
            if (revision != verifiedRevision || snapshot.phase != Phase.UNCERTAIN ||
                snapshot.lease != lease ||
                snapshot.operationId != null
            ) {
                return@synchronized false
            }
            persist(snapshot.copy(phase = Phase.ACTIVE, everGranted = true))
            true
        }
    }

    /** Returns null unless exact current lease is active. */
    fun admit(lease: Lease): AutoCloseable? = synchronized(lock) {
        if (snapshot.phase != Phase.ACTIVE || snapshot.lease != lease ||
            inFlight == Int.MAX_VALUE
        ) return@synchronized null
        inFlight += 1
        val closed = AtomicBoolean(false)
        AutoCloseable {
            if (closed.compareAndSet(false, true)) finishAdmission()
        }
    }

    /** Closes admission durably before returning a drain for work already admitted. */
    fun beginDrain(lease: Lease, operationId: String): Deferred<Unit>? {
        require(isCanonicalUuid(operationId))
        var completeDrain = false
        val result = synchronized(lock) {
            if (snapshot.phase == Phase.DRAINING && snapshot.lease == lease &&
                snapshot.operationId == operationId
            ) return@synchronized drain
            if (snapshot.phase != Phase.ACTIVE || snapshot.lease != lease) {
                return@synchronized null
            }
            drain = CompletableDeferred()
            persist(snapshot.copy(phase = Phase.DRAINING, operationId = operationId))
            completeDrain = inFlight == 0
            drain
        }
        if (completeDrain) result?.let { it.complete(Unit) }
        return result
    }

    /** Revokes admission but leaves durable ownership uncertain. */
    fun markUncertain(lease: Lease): Boolean {
        var completeDrain = false
        val changed = synchronized(lock) {
            if (snapshot.lease != lease ||
                snapshot.phase !in setOf(Phase.UNCERTAIN, Phase.ACTIVE, Phase.DRAINING)
            ) return@synchronized false
            if (snapshot.phase == Phase.ACTIVE) drain = CompletableDeferred()
            if (snapshot.phase != Phase.UNCERTAIN) {
                persist(snapshot.copy(phase = Phase.UNCERTAIN))
            } else {
                revision += 1
            }
            completeDrain = inFlight == 0
            true
        }
        if (completeDrain) drain.complete(Unit)
        return changed
    }

    /** Clean terminal state requires completed host drain and independent stop evidence. */
    fun confirmCleanStop(
        lease: Lease,
        operationId: String,
        verifier: CleanStopVerifier,
    ): Boolean {
        val ready = synchronized(lock) {
            snapshot.phase == Phase.DRAINING && snapshot.lease == lease &&
                snapshot.operationId == operationId && inFlight == 0 && drain.isCompleted
        }
        if (!ready || !verifier.verify(lease, operationId)) return false
        return synchronized(lock) {
            if (snapshot.phase != Phase.DRAINING || snapshot.lease != lease ||
                snapshot.operationId != operationId || inFlight != 0 || !drain.isCompleted
            ) return@synchronized false
            persist(snapshot.copy(phase = Phase.CLEAN, operationId = null))
            true
        }
    }

    private fun finishAdmission() {
        val complete = synchronized(lock) {
            check(inFlight > 0)
            inFlight -= 1
            inFlight == 0 && snapshot.phase in setOf(
                Phase.DRAINING, Phase.UNCERTAIN, Phase.STORAGE_ERROR,
            )
        }
        if (complete) drain.complete(Unit)
    }

    private fun load(): Snapshot {
        if (!preferences.contains(KEY)) return Snapshot(Phase.UNINITIALIZED)
        val raw = try {
            preferences.getString(KEY, null)
        } catch (_: ClassCastException) {
            return Snapshot(Phase.CORRUPT)
        } ?: return Snapshot(Phase.CORRUPT)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Snapshot(Phase.CORRUPT)
        return try {
            val root = JSONObject(raw)
            require(root.keys().asSequence().toSet() == ROOT_KEYS)
            require(root.get("version") == VERSION)
            val phase = Phase.valueOf(root.getString("phase"))
            require(phase !in setOf(
                Phase.UNINITIALIZED, Phase.CORRUPT, Phase.STORAGE_ERROR,
            ))
            val lease = if (root.isNull("lease")) null else readLease(root.getJSONObject("lease"))
            val operationId = if (root.isNull("operationId")) null else root.getString("operationId")
            val everGranted = root.get("everGranted") as? Boolean
                ?: throw IllegalArgumentException("Invalid ownership journal")
            if (operationId != null) require(isCanonicalUuid(operationId))
            validate(Snapshot(phase, lease, operationId, everGranted))
        } catch (_: JSONException) {
            Snapshot(Phase.CORRUPT)
        } catch (_: IllegalArgumentException) {
            Snapshot(Phase.CORRUPT)
        } catch (_: ClassCastException) {
            Snapshot(Phase.CORRUPT)
        }
    }

    private fun readLease(value: JSONObject): Lease {
        require(value.keys().asSequence().toSet() == LEASE_KEYS)
        return Lease(
            value.getString("sessionId"),
            exactLong(value.get("generation")),
            value.getString("incarnationId"),
        )
    }

    private fun exactLong(value: Any): Long {
        return when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("Invalid generation")
        }
    }

    private fun validate(value: Snapshot): Snapshot {
        when (value.phase) {
            Phase.UNCERTAIN ->
                require(value.lease != null)
            Phase.ACTIVE -> {
                require(value.lease != null)
                require(value.everGranted)
                require(value.operationId == null)
            }
            Phase.DRAINING -> {
                require(value.lease != null)
                require(value.everGranted)
                require(value.operationId != null)
            }
            Phase.CLEAN, Phase.UNSUPPORTED -> {
                require(value.operationId == null)
                if (value.lease != null) require(value.everGranted)
            }
            Phase.UNINITIALIZED, Phase.CORRUPT, Phase.STORAGE_ERROR ->
                throw IllegalArgumentException("Invalid persisted ownership phase")
        }
        if (value.everGranted) require(value.lease != null)
        if (value.operationId != null) require(value.everGranted)
        return value
    }

    private fun persist(value: Snapshot) {
        validate(value)
        val lease = value.lease?.let {
            JSONObject()
                .put("sessionId", it.sessionId)
                .put("generation", it.generation)
                .put("incarnationId", it.incarnationId)
        } ?: JSONObject.NULL
        val encoded = JSONObject()
            .put("version", VERSION)
            .put("phase", value.phase.name)
            .put("lease", lease)
            .put("operationId", value.operationId ?: JSONObject.NULL)
            .put("everGranted", value.everGranted)
            .toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val committed = try {
            preferences.edit().putString(KEY, encoded).commit()
        } catch (error: RuntimeException) {
            storageFailed()
            throw IllegalStateException("Ditto ownership journal write failed", error)
        }
        if (!committed) {
            storageFailed()
            throw IllegalStateException("Ditto ownership journal write outcome uncertain")
        }
        snapshot = value
        revision += 1
    }

    private fun storageFailed() {
        val previous = snapshot
        snapshot = Snapshot(Phase.STORAGE_ERROR, previous.lease, previous.operationId, previous.everGranted)
        revision += 1
        if (drain.isCompleted) drain = CompletableDeferred()
        if (inFlight == 0) drain.complete(Unit)
    }

    private fun completedDrain() = CompletableDeferred<Unit>().apply { complete(Unit) }

    private companion object {
        const val KEY = "ditto-ownership-journal-v1"
        const val VERSION = 1
        const val MAX_BYTES = 2048
        val ROOT_KEYS = setOf("version", "phase", "lease", "operationId", "everGranted")
        val LEASE_KEYS = setOf("sessionId", "generation", "incarnationId")
    }
}
