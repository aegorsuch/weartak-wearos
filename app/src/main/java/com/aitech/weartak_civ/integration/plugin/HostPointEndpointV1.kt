package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.aitech.weartak_civ.command.SensitiveEnrollment
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.cot.DittoCotReceiver
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.weartak.plugin.sdk.point.v1.IPointEndpointV1
import com.weartak.plugin.sdk.point.v1.IPointHostV1
import com.weartak.plugin.sdk.point.v1.PointContractV1 as Contract
import com.weartak.plugin.sdk.point.v1.PointOperationV1
import com.weartak.plugin.sdk.point.v1.PointResultV1
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.runBlocking

internal class HostPointEndpointV1(
    private val expectedUid: Int,
    private val authorized: () -> Boolean,
    private val sessionActive: (String) -> Boolean,
    private val repository: DataRepository,
    private val bloodhounds: BloodhoundRepository,
    private val callingUid: () -> Int = Binder::getCallingUid,
    private val payloadReader: ((ParcelFileDescriptor, Int) -> String)? = null,
) : IPointHostV1.Stub() {
    private val closed = AtomicBoolean(false)
    private val gate = ReentrantReadWriteLock()
    private val lock = Any()
    private var registeredSession: String? = null
    private var endpointBinder: IBinder? = null
    private var endpointDeath: IBinder.DeathRecipient? = null
    private val results = LinkedHashMap<String, Pair<PointOperationV1, PointResultV1>>()

    override fun register(sessionId: String?, plugin: IPointEndpointV1?): Int {
        val uid = callingUid()
        if (uid != expectedUid || !authorized()) return Contract.REGISTER_UNAUTHORIZED
        if (sessionId.isNullOrBlank() || plugin == null) return Contract.REGISTER_INVALID
        if (!live(uid, sessionId)) return Contract.REGISTER_INACTIVE
        val binder = plugin.asBinder()
        if (!binder.isBinderAlive) return Contract.REGISTER_UNAVAILABLE
        val write = gate.writeLock()
        if (!write.tryLock()) return Contract.REGISTER_BUSY
        try {
            synchronized(lock) {
                if (!live(uid, sessionId)) return Contract.REGISTER_INACTIVE
                val oldSession = registeredSession
                if (oldSession != null && sessionActive(oldSession)) return Contract.REGISTER_BUSY
                val death = IBinder.DeathRecipient {
                    synchronized(lock) {
                        if (endpointBinder === binder && registeredSession == sessionId &&
                            sessionActive(sessionId)) closed.set(true)
                    }
                }
                try {
                    binder.linkToDeath(death, 0)
                } catch (_: RemoteException) {
                    return Contract.REGISTER_UNAVAILABLE
                }
                if (!live(uid, sessionId) || !binder.isBinderAlive) {
                    try { binder.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { }
                    return Contract.REGISTER_INACTIVE
                }
                endpointBinder?.let { old ->
                    endpointDeath?.let { recipient ->
                        try { old.unlinkToDeath(recipient, 0) } catch (_: NoSuchElementException) { }
                    }
                }
                endpointBinder = binder
                endpointDeath = death
                registeredSession = sessionId
                results.clear()
                return Contract.REGISTERED
            }
        } finally {
            write.unlock()
        }
    }

    override fun apply(sessionId: String?, operation: PointOperationV1?, payload: ParcelFileDescriptor?): PointResultV1 {
        try {
            val uid = callingUid()
            if (uid != expectedUid || !authorized()) return result(Contract.REJECTED)
            if (sessionId == null || operation == null ||
                !Contract.isValidOperation(operation, payload != null) ||
                operation.direction != Contract.PLUGIN_TO_HOST) return result(Contract.REJECTED)
            if (operation.owner == MainActivity.device_uid || operation.pointUid == MainActivity.device_uid ||
                operation.eventTimeMillis > System.currentTimeMillis() + DittoCotReceiver.MAX_FUTURE_MS
            ) return result(Contract.REJECTED)
            if (!liveRegistered(uid, sessionId)) return result(Contract.RETRYABLE)
            val read = gate.readLock()
            if (!read.tryLock()) return result(Contract.RETRYABLE)
            try {
                if (!operationLock.tryLock()) return result(Contract.RETRYABLE)
                try {
                    if (!liveRegistered(uid, sessionId)) return result(Contract.RETRYABLE)
                    synchronized(lock) {
                        val prior = results[operation.operationId]
                        if (prior != null && prior.first != operation) return result(Contract.REJECTED)
                        if (prior == null && results.size >= MAX_SESSION_OPERATIONS) {
                            return result(Contract.RETRYABLE)
                        }
                    }
                    val source = "ditto:${DittoCotDocument.digest(requireNotNull(operation.peer))}"
                    val marker = if (operation.kind == Contract.UPSERT) {
                        val xml = try {
                            val descriptor = requireNotNull(payload)
                            payloadReader?.invoke(descriptor, operation.payloadLength)
                                ?: readPayload(descriptor, operation.payloadLength)
                        } catch (_: IOException) {
                            Log.w("WearTAK-PointHost", "Point payload read failed")
                            return result(Contract.REJECTED)
                        } catch (_: android.system.ErrnoException) {
                            Log.w("WearTAK-PointHost", "Point payload descriptor rejected")
                            return result(Contract.REJECTED)
                        } catch (_: IllegalArgumentException) {
                            return result(Contract.REJECTED)
                        }
                        if (SensitiveEnrollment.containsSensitiveXml(xml) ||
                            DittoCotDocument.digest(xml) != operation.revision) return result(Contract.REJECTED)
                        val parsed = CotMessageParser.parseMarkerOrNull(xml) ?: return result(Contract.REJECTED)
                        if (parsed.uid != operation.pointUid || parsed.time != operation.eventTimeMillis ||
                            parsed.stale < parsed.time
                        ) return result(Contract.REJECTED)
                        parsed
                    } else null
                    if (!liveRegistered(uid, sessionId)) return result(Contract.RETRYABLE)
                    synchronized(lock) {
                        val prior = results[operation.operationId]
                        if (prior != null) {
                            return prior.second
                        }
                    }
                    val outcome = try {
                        runBlocking {
                            if (marker == null) {
                                when (repository.receiveSourcePointDeletionFenced(
                                    operation.pointUid, source, operation.eventTimeMillis, bloodhounds,
                                    isSessionActive = {
                                        liveRegistered(uid, sessionId) && !repository.isCompanionConnected.value
                                    },
                                    isPersistentlyLocalPoint = { repository.markerIntentJournal.read().effective.containsKey(it) },
                                )) {
                                    DataRepository.SourcePointDeletionResult.PROCESSED -> Contract.LOCAL_PROCESSED
                                    DataRepository.SourcePointDeletionResult.LOCAL_POINT_PROTECTED -> Contract.LOCAL_POINT_PROTECTED
                                    DataRepository.SourcePointDeletionResult.INACTIVE -> Contract.RETRYABLE
                                }
                            } else {
                                when (repository.receiveSourcePointUpsertFenced(
                                    marker, source, bloodhounds,
                                    isSessionActive = { liveRegistered(uid, sessionId) },
                                    isPersistentlyLocalPoint = { repository.markerIntentJournal.read().effective.containsKey(it) },
                                    deviceUid = MainActivity.device_uid,
                                )) {
                                    DataRepository.SourcePointUpsertResult.PROCESSED -> Contract.LOCAL_PROCESSED
                                    DataRepository.SourcePointUpsertResult.LOCAL_POINT_PROTECTED -> Contract.REJECTED
                                    DataRepository.SourcePointUpsertResult.INACTIVE -> Contract.RETRYABLE
                                }
                            }
                        }
                    } catch (_: RuntimeException) {
                        Log.e("WearTAK-PointHost", "Point deletion outcome uncertain")
                        return result(Contract.UNKNOWN)
                    }
                    val reply = result(outcome)
                    if (!liveRegistered(uid, sessionId)) return result(Contract.UNKNOWN)
                    if (outcome != Contract.RETRYABLE) {
                        synchronized(lock) { results[operation.operationId] = operation to reply }
                    }

                    return reply
                } finally {
                    operationLock.unlock()
                }
            } finally {
                read.unlock()
            }
        } finally {
            payload?.close()
        }
    }

    private fun readPayload(payload: ParcelFileDescriptor, length: Int): String {
        require(length in 1..DittoCotDocument.MAX_PAYLOAD_BYTES)
        val fd = payload.fileDescriptor
        val stat = Os.fstat(fd)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == length.toLong())
        require(Os.fcntlInt(fd, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
        require(Os.lseek(fd, 0, OsConstants.SEEK_CUR) == 0L)
        val bytes = ByteArray(length)
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd)).use { stream ->
            var offset = 0
            while (offset < bytes.size) {
                val count = stream.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw IOException("Truncated point payload")
                offset += count
            }
            require(stream.read() == -1)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 point payload")
        }
    }

    /** Revocation is immediate; true means all already-admitted calls drained. */
    fun revoke() { closed.set(true) }

    fun closeAndDrain(timeoutMillis: Long): Boolean {
        revoke()
        val write = gate.writeLock()
        if (!write.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        try {
            synchronized(lock) {
                endpointBinder?.let {
                    endpointDeath?.let { death ->
                        try { it.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { }
                    }
                }
                endpointBinder = null
                endpointDeath = null
                registeredSession = null
                results.clear()
            }
            return true
        } finally {
            write.unlock()
        }
    }

    private fun live(uid: Int, id: String): Boolean =
        !closed.get() && uid == expectedUid && authorized() && sessionActive(id) && !closed.get()

    private fun liveRegistered(uid: Int, id: String): Boolean {
        if (!live(uid, id)) return false
        return synchronized(lock) {
            registeredSession == id && endpointBinder?.isBinderAlive == true && live(uid, id)
        }
    }

    private fun result(code: Int) = PointResultV1(code)

    private companion object {
        const val MAX_SESSION_OPERATIONS = 256
        val operationLock = ReentrantLock()
    }
}
