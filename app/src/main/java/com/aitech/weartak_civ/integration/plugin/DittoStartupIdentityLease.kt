package com.aitech.weartak_civ.integration.plugin

import android.os.IBinder
import com.aitech.weartak_civ.service.DittoCommunityLicenseHelper
import com.weartak.plugin.sdk.community.v1.CommunityIdentityContract
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Inactive lease-scoped read bookkeeping. Callers must invalidate it on observed connection,
 * trust, or lease loss; finalization alone does not prove runtime shutdown or clear the host fence.
 */
internal class DittoStartupIdentityLease(
    private val expectedUid: Int,
    private val session: IBinder,
    private val generation: Long,
    private val authenticate: () -> Boolean,
    private val resolve: () -> Identity?,
) {
    class Identity(val uid: String, val encodedIdentity: String)

    enum class Result {
        OK, INVALID_ARGUMENT, UNAUTHORIZED, INACTIVE_LEASE, NOT_READY, BUSY, PENDING, UNCERTAIN,
        INTERNAL_ERROR,
    }

    class ReadResult private constructor(
        val result: Result,
        val readId: Long? = null,
        val identity: Identity? = null,
        val nonce: ByteArray? = null,
    ) {
        companion object {
            fun failure(result: Result) = ReadResult(result)
            fun success(id: Long, identity: Identity, nonce: ByteArray) =
                ReadResult(Result.OK, id, identity, nonce)
        }
    }

    private enum class State { ACTIVE, DRAINING, FINALIZED, UNCERTAIN }
    private class Read(val id: Long, var nonce: ByteArray? = null)

    private val lock = Any()
    private val random = SecureRandom()
    private var state = State.ACTIVE
    private var lastReadId = 0L
    private var outstanding: Read? = null

    init {
        require(generation > 0)
    }

    fun readStartup(callerUid: Int, leaseSession: IBinder?, leaseGeneration: Long, readId: Long): ReadResult {
        val access = checkAccess(callerUid, leaseSession, leaseGeneration)
        if (access != Result.OK) return ReadResult.failure(access)
        val read = synchronized(lock) {
            if (state != State.ACTIVE) return ReadResult.failure(stateResult())
            if (readId <= 0 || readId <= lastReadId) return ReadResult.failure(Result.INVALID_ARGUMENT)
            if (outstanding != null) return ReadResult.failure(Result.BUSY)
            lastReadId = readId
            Read(readId).also { outstanding = it }
        }

        var issued = false
        try {
            val identity = resolve() ?: return ReadResult.failure(Result.NOT_READY)
            if (!validUid(identity.uid) || !validIdentity(identity.encodedIdentity)) {
                return ReadResult.failure(Result.INVALID_ARGUMENT)
            }
            val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
            val recheck = checkAccess(callerUid, leaseSession, leaseGeneration)
            if (recheck != Result.OK) return ReadResult.failure(recheck)
            return synchronized(lock) {
                if (state != State.ACTIVE || outstanding !== read) {
                    ReadResult.failure(stateResult())
                } else {
                    read.nonce = nonce
                    issued = true
                    ReadResult.success(readId, identity, nonce.copyOf())
                }
            }
        } finally {
            if (!issued) synchronized(lock) {
                if (outstanding === read) outstanding = null
            }
        }
    }

    fun finishRead(
        callerUid: Int, leaseSession: IBinder?, leaseGeneration: Long, readId: Long,
        nonce: ByteArray?, used: Boolean?,
    ): Result {
        val access = checkAccess(callerUid, leaseSession, leaseGeneration)
        if (access != Result.OK) return access
        return synchronized(lock) {
            if (state == State.UNCERTAIN || state == State.FINALIZED) return stateResult()
            val read = outstanding
            if (read == null || read.id != readId || used == null || nonce?.size != NONCE_SIZE ||
                read.nonce == null || !MessageDigest.isEqual(read.nonce, nonce)) {
                return Result.INVALID_ARGUMENT
            }
            outstanding = null
            Result.OK
        }
    }

    fun beginDrain(callerUid: Int, leaseSession: IBinder?, leaseGeneration: Long): Result {
        val access = checkAccess(callerUid, leaseSession, leaseGeneration)
        if (access != Result.OK) return access
        return synchronized(lock) {
            when (state) {
                State.ACTIVE -> { state = State.DRAINING; Result.OK }
                State.DRAINING -> Result.OK
                else -> stateResult()
            }
        }
    }

    fun finalizeRevoke(callerUid: Int, leaseSession: IBinder?, leaseGeneration: Long): Result {
        val access = checkAccess(callerUid, leaseSession, leaseGeneration)
        if (access != Result.OK) return access
        return synchronized(lock) {
            when (state) {
                State.ACTIVE -> Result.NOT_READY
                State.DRAINING -> if (outstanding != null) Result.PENDING else {
                    state = State.FINALIZED
                    Result.OK
                }
                else -> stateResult()
            }
        }
    }

    fun invalidate() = synchronized(lock) { state = State.UNCERTAIN }

    private fun checkAccess(callerUid: Int, leaseSession: IBinder?, leaseGeneration: Long): Result {
        if (callerUid != expectedUid) return Result.UNAUTHORIZED
        if (leaseSession != session || leaseGeneration != generation) return Result.INACTIVE_LEASE
        val authorized = try {
            authenticate()
        } catch (error: RuntimeException) {
            invalidate()
            throw error
        }
        if (!authorized) {
            invalidate()
            return Result.UNAUTHORIZED
        }
        return synchronized(lock) {
            if (state == State.UNCERTAIN || state == State.FINALIZED) stateResult() else Result.OK
        }
    }

    private fun stateResult(): Result = when (state) {
        State.ACTIVE -> Result.OK
        State.DRAINING -> Result.NOT_READY
        State.FINALIZED -> Result.INACTIVE_LEASE
        State.UNCERTAIN -> Result.UNCERTAIN
    }

    private fun validUid(uid: String): Boolean {
        if (uid.isBlank() || uid == "WEAROS_null" || uid == "WEAROS_" ||
            uid.length > MAX_UID_LENGTH || uid.any(Char::isISOControl)) return false
        val encoder = Charsets.UTF_8.newEncoder()
        return encoder.canEncode(uid) && uid.toByteArray(Charsets.UTF_8).size <= MAX_UID_UTF8_BYTES
    }

    private fun validIdentity(identity: String): Boolean {
        if (identity.length > CommunityIdentityContract.MAX_IDENTITY_LENGTH) return false
        return try {
            !DittoCommunityLicenseHelper.parse(identity).isExpired
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private companion object {
        const val MAX_UID_LENGTH = 512
        const val MAX_UID_UTF8_BYTES = 1536
        const val NONCE_SIZE = 16
    }
}
