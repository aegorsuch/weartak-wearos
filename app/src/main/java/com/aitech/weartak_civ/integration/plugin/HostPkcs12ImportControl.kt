package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.takserver.Pkcs12CertificateImport
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportContract
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportErrorCode as Error
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState as State
import com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode as Code
import com.weartak.plugin.sdk.nearbyimport.aidl.IWearTakPkcs12ImportControlV1
import com.weartak.plugin.sdk.nearbyimport.model.*
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One authenticated PluginConnection owns this binder, its replay ledger and all staged bytes. */
class HostPkcs12ImportControl(
    private val expectedUid: Int,
    private val authenticateSession: () -> Boolean,
    private val launchReview: (String) -> Unit,
    private val callerUid: () -> Int = { Binder.getCallingUid() },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    val packageMode: Boolean = false,
    private val consumeWearerLaunch: (() -> Boolean)? = null,
) : IWearTakPkcs12ImportControlV1.Stub() {
    private class Entry(val handle: String, val expires: Long, var bytes: ByteArray?) {
        val status = MutableStateFlow(Pkcs12ImportStatusParcel(State.STAGED, expires))
    }

    private val lock = Any()
    private val requests = HashSet<String>()
    private val entries = LinkedHashMap<String, Entry>()
    private var active = true
    private var launchAllowedUntil = 0L

    fun capability(): Pkcs12ImportCapabilityParcel =
        if (verifyCaller()) Pkcs12ImportCapabilityParcel(
            Pkcs12ImportContract.CAPABILITY_KEY, Pkcs12ImportContract.API_VERSION,
            Pkcs12ImportContract.MAX_PAYLOAD_BYTES, Pkcs12ImportContract.MAX_STAGE_LIFETIME_MS,
        ) else Pkcs12ImportCapabilityParcel()

    fun authenticatedBinder(): IWearTakPkcs12ImportControlV1? = if (verifyCaller()) this else null

    /** Granted only by WearTAK's foreground, wearer-operated plugin launcher, never by IPC. */
    fun allowReviewFromWearerLaunch() = synchronized(lock) {
        launchAllowedUntil = clock() + Pkcs12ImportContract.MAX_STAGE_LIFETIME_MS
    }

    override fun stagePkcs12(request: Pkcs12StageRequestParcel?): Pkcs12StageResultParcel {
        try {
            if (!verifyCaller()) return Pkcs12StageResultParcel(Code.UNAUTHORIZED)
            if (request == null) return Pkcs12StageResultParcel(Code.INVALID_REQUEST)
            val bytes = request.pkcs12Bytes
            if (bytes.size > Pkcs12ImportContract.MAX_PAYLOAD_BYTES) return Pkcs12StageResultParcel(Code.TOO_LARGE)
            val validPayload = if (packageMode)
                com.weartak.plugin.sdk.serverpackage.ServerPackageCodec.validate(bytes) == null
            else isPfxEnvelope(bytes)
            if (!REQUEST_ID.matches(request.requestId) || !validPayload) {
                return Pkcs12StageResultParcel(Code.INVALID_REQUEST)
            }
            synchronized(lock) {
                if (!active) return Pkcs12StageResultParcel(Code.UNAUTHORIZED)
                expireLocked()
                if (request.requestId in requests) return Pkcs12StageResultParcel(Code.REPLAYED_REQUEST)
                if (requests.size >= MAX_REQUESTS || entries.values.any { !terminal(it.status.value.state) }) {
                    return Pkcs12StageResultParcel(Code.HOST_BUSY)
                }
                requests += request.requestId
                val handle = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(RANDOM::nextBytes))
                val entry = Entry(handle, clock() + Pkcs12ImportContract.MAX_STAGE_LIFETIME_MS, bytes.copyOf())
                entries[handle] = entry
                handler.postDelayed({ synchronized(lock) { expireLocked() } }, Pkcs12ImportContract.MAX_STAGE_LIFETIME_MS)
                return Pkcs12StageResultParcel(Code.STAGED, handle, entry.expires)
            }
        } finally {
            request?.pkcs12Bytes?.fill(0)
        }
    }

    override fun requestReview(handle: String?): Pkcs12ReviewResultParcel {
        if (!verifyCaller()) return Pkcs12ReviewResultParcel(State.REJECTED, Error.UNAUTHORIZED)
        synchronized(lock) {
            val entry = findLocked(handle) ?: return Pkcs12ReviewResultParcel(State.REJECTED, Error.INVALID_HANDLE)
            if (terminal(entry.status.value.state)) return Pkcs12ReviewResultParcel(entry.status.value.state, Error.TERMINAL)
            if (entry.status.value.state != State.STAGED) return Pkcs12ReviewResultParcel(entry.status.value.state, Error.TERMINAL)
            if (clock() >= launchAllowedUntil || consumeWearerLaunch?.invoke() == false) {
                return Pkcs12ReviewResultParcel(State.STAGED, Error.UNAUTHORIZED)
            }
            launchAllowedUntil = 0
            entry.status.value = Pkcs12ImportStatusParcel(State.REVIEW_PENDING, entry.expires)
            ReviewRegistry.put(entry.handle, this)
            // Dispatch on main; failure is observable through getStatus and never becomes APPLIED.
            handler.post {
                if (isLive(entry.handle)) {
                    try { launchReview(entry.handle) }
                    catch (_: android.content.ActivityNotFoundException) { reject(entry.handle) }
                    catch (_: SecurityException) { reject(entry.handle) }
                }
            }
            return Pkcs12ReviewResultParcel(State.REVIEW_PENDING)
        }
    }

    override fun getStatus(handle: String?): Pkcs12ImportStatusParcel {
        if (!verifyCaller()) return Pkcs12ImportStatusParcel(State.REJECTED, errorCode = Error.UNAUTHORIZED)
        return synchronized(lock) {
            findLocked(handle)?.status?.value
                ?: Pkcs12ImportStatusParcel(State.REJECTED, errorCode = Error.INVALID_HANDLE)
        }
    }

    override fun cancel(handle: String?): Pkcs12CancelResultParcel {
        if (!verifyCaller()) return Pkcs12CancelResultParcel(State.REJECTED, Error.UNAUTHORIZED)
        return synchronized(lock) {
            val entry = findLocked(handle) ?: return Pkcs12CancelResultParcel(State.REJECTED, Error.INVALID_HANDLE)
            if (!terminal(entry.status.value.state)) finishLocked(entry, State.CANCELLED)
            Pkcs12CancelResultParcel(entry.status.value.state)
        }
    }

    fun claimReview(handle: String): Boolean = synchronized(lock) {
        val entry = findLocked(handle) ?: return false
        if (!sessionValid() || entry.status.value.state != State.REVIEW_PENDING) return false
        entry.status.value = Pkcs12ImportStatusParcel(State.IN_REVIEW, entry.expires)
        true
    }

    fun observe(handle: String) = synchronized(lock) { entries[handle]?.status?.asStateFlow() }

    fun open(handle: String, password: String): Pkcs12CertificateImport.Opened {
        val bytes = synchronized(lock) {
            check(isLive(handle)) { "Review expired or cancelled" }
            requireNotNull(entries[handle]?.bytes).copyOf()
        }

        return try { Pkcs12CertificateImport.open(bytes, password) }
        finally { bytes.fill(0) }
    }

    fun openPackage(handle: String): com.weartak.plugin.sdk.serverpackage.ServerPackage {
        check(packageMode)
        val bytes = synchronized(lock) {
            check(isLive(handle))
            requireNotNull(entries[handle]?.bytes).copyOf()
        }
        try {
            val result = com.weartak.plugin.sdk.serverpackage.ServerPackageCodec.decode(bytes)
            return (result as? com.weartak.plugin.sdk.serverpackage.ServerPackageDecodeResult.Valid)
                ?.serverPackage ?: error("Invalid server package")
        } finally { bytes.fill(0) }
    }

    fun authenticated() = verifyCaller()
    fun sessionAuthorized() = synchronized(lock) { sessionValid() }

    fun isLive(handle: String): Boolean = synchronized(lock) {
        val entry = findLocked(handle) ?: return false
        active && !terminal(entry.status.value.state) && sessionValid()
    }

    /** Called at the guarded persistence boundary, after save/runtime reconciliation succeeds. */
    fun completeApplied(handle: String): Boolean = synchronized(lock) {
        val entry = findLocked(handle) ?: return false
        if (!sessionValid() || entry.status.value.state != State.IN_REVIEW) return false
        finishLocked(entry, State.APPLIED)
        true
    }

    fun reject(handle: String, error: Int = Error.FAILED) = synchronized(lock) {
        entries[handle]?.let { if (!terminal(it.status.value.state)) finishLocked(it, State.REJECTED, error) }
    }

    fun dismiss(handle: String) = synchronized(lock) {
        entries[handle]?.let { if (!terminal(it.status.value.state)) finishLocked(it, State.CANCELLED) }
    }

    fun close() = synchronized(lock) {
        active = false
        launchAllowedUntil = 0
        entries.values.forEach { if (!terminal(it.status.value.state)) finishLocked(it, State.CANCELLED) }
    }

    private fun verifyCaller(): Boolean = callerUid() == expectedUid && synchronized(lock) { sessionValid() }

    private fun sessionValid(): Boolean {
        if (!active) return false
        val valid = authenticateSession()
        if (!valid) close()
        return valid
    }

    private fun findLocked(handle: String?): Entry? {
        expireLocked()
        return entries[handle]
    }

    private fun expireLocked() {
        entries.values.forEach {
            if (!terminal(it.status.value.state) && clock() >= it.expires) finishLocked(it, State.EXPIRED, Error.EXPIRED)
        }
    }

    private fun finishLocked(entry: Entry, state: Int, error: Int = Error.NONE) {
        entry.bytes?.fill(0)
        entry.bytes = null
        entry.status.value = Pkcs12ImportStatusParcel(state, entry.expires, error)
        ReviewRegistry.remove(entry.handle, this)
    }

    companion object {
        private val RANDOM = SecureRandom()
        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{16,64}")
        private const val MAX_REQUESTS = 128
        fun terminal(state: Int) = state in setOf(State.REJECTED, State.CANCELLED, State.EXPIRED, State.APPLIED)

        /** Check exact DER PFX envelope/version before accepting encrypted data, without passwords. */
        internal fun isPfxEnvelope(bytes: ByteArray): Boolean {
            if (bytes.size < 8 || bytes[0] != 0x30.toByte()) return false
            val first = bytes[1].toInt() and 255
            var offset = 2
            var size = first
            if (first >= 128) {
                val count = first and 127
                if (count !in 1..2 || bytes.size < 2 + count || bytes[2] == 0.toByte()) return false
                size = 0
                repeat(count) { size = (size shl 8) or (bytes[offset++].toInt() and 255) }
                if (size < 128 || (count == 2 && size < 256)) return false
            }
            return offset + size == bytes.size && bytes.size > offset + 3 &&
                bytes[offset] == 2.toByte() && bytes[offset + 1] == 1.toByte() && bytes[offset + 2] == 3.toByte()
        }
    }
}

internal object ReviewRegistry {
    private val reviews = ConcurrentHashMap<String, HostPkcs12ImportControl>()
    fun put(handle: String, control: HostPkcs12ImportControl) { reviews[handle] = control }
    fun get(handle: String) = reviews[handle]
    fun remove(handle: String, control: HostPkcs12ImportControl) { reviews.remove(handle, control) }
}
