package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.weartak.plugin.sdk.nearbyimport.model.*
import com.weartak.plugin.sdk.serverpackage.*
import com.weartak.plugin.sdk.serverpackage.aidl.IWearTakServerPackageControlV1
import com.weartak.plugin.sdk.serverpackage.model.*
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HostServerPackageControl(
    expectedUid: Int,
    authenticateSession: () -> Boolean,
    launchReview: (String) -> Unit,
    private val launchExport: (String) -> Unit,
    callerUid: () -> Int = { Binder.getCallingUid() },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val consumeWearerLaunch: (() -> Boolean)? = null,
) : IWearTakServerPackageControlV1.Stub() {
    val imports = HostPkcs12ImportControl(expectedUid, authenticateSession, launchReview,
        callerUid, clock, handler, packageMode = true, consumeWearerLaunch = consumeWearerLaunch)
    private class Export(val handle: String, val expires: Long) {
        val status = MutableStateFlow(ServerPackageExportStatusParcel(ServerPackageExportState.PENDING, expires))
        var bytes: ByteArray? = null
        var authorized: (() -> Boolean)? = null
        var claimed = false
    }
    private val lock = Any()
    private val exports = LinkedHashMap<String, Export>()
    private val requests = HashSet<String>()
    private var launchAllowedUntil = 0L
    private var active = true
    private val transactionPayload = ThreadLocal<ByteArray>()
    private val inTransaction = ThreadLocal<Boolean>()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        inTransaction.set(true)
        return try { super.onTransact(code, data, reply, flags) }
        finally {
            transactionPayload.get()?.fill(0)
            transactionPayload.remove()
            inTransaction.remove()
        }
    }

    private fun authenticated(): Boolean {
        val valid = imports.authenticated()
        if (!valid && !imports.sessionAuthorized()) close()
        return valid
    }

    fun capability() = if (authenticated()) ServerPackageCapabilityParcel(
        ServerPackageContract.CAPABILITY_KEY, 1, ServerPackageContract.MAX_PACKAGE_BYTES,
        ServerPackageContract.MAX_STAGE_LIFETIME_MS, ServerPackageContract.MAX_EXPORT_LIFETIME_MS,
        supportsReceive = true, supportsExport = true,
    ) else ServerPackageCapabilityParcel()

    fun authenticatedBinder(): IWearTakServerPackageControlV1? = if (authenticated()) this else null

    fun allowFromWearerLaunch() {
        imports.allowReviewFromWearerLaunch()
        synchronized(lock) { launchAllowedUntil = clock() + ServerPackageContract.MAX_EXPORT_LIFETIME_MS }
    }

    override fun stageServerPackage(request: ServerPackageStageRequestParcel?) =
        imports.stagePkcs12(request?.let { Pkcs12StageRequestParcel(it.requestId, it.encodedPackage) })
    override fun requestReview(handle: String?) = imports.requestReview(handle)
    override fun getStatus(handle: String?) = imports.getStatus(handle)
    override fun cancel(handle: String?) = imports.cancel(handle)

    override fun requestExport(requestId: String?): ServerPackageExportResultParcel {
        if (!authenticated()) return ServerPackageExportResultParcel(ServerPackageExportCode.UNAUTHORIZED)
        synchronized(lock) {
            expireLocked()
            if (!active || clock() >= launchAllowedUntil) return ServerPackageExportResultParcel(ServerPackageExportCode.UNAUTHORIZED)
            if (requestId == null || !Regex("[A-Za-z0-9_-]{16,64}").matches(requestId))
                return ServerPackageExportResultParcel(ServerPackageExportCode.INVALID_REQUEST)
            if (requestId in requests) return ServerPackageExportResultParcel(ServerPackageExportCode.REPLAYED_REQUEST)
            if (requests.size >= 128 || exports.values.any { it.status.value.state in 1..2 })
                return ServerPackageExportResultParcel(ServerPackageExportCode.HOST_BUSY)
            if (consumeWearerLaunch?.invoke() == false)
                return ServerPackageExportResultParcel(ServerPackageExportCode.UNAUTHORIZED)
            launchAllowedUntil = 0L
            requests += requestId
            val handle = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            val export = Export(handle, clock() + ServerPackageContract.MAX_EXPORT_LIFETIME_MS)
            exports[handle] = export
            ServerExportRegistry.put(handle, this)
            handler.postDelayed({ synchronized(lock) { expireLocked() } }, ServerPackageContract.MAX_EXPORT_LIFETIME_MS)
            handler.post {
                if (exportLive(handle)) try { launchExport(handle) }
                catch (_: android.content.ActivityNotFoundException) { rejectExport(handle, ServerPackageExportReason.FAILED) }
                catch (_: SecurityException) { rejectExport(handle, ServerPackageExportReason.FAILED) }
            }
            return ServerPackageExportResultParcel(ServerPackageExportCode.PENDING, handle, export.expires)
        }
    }

    override fun getExportStatus(handle: String?): ServerPackageExportStatusParcel {
        if (!authenticated()) return ServerPackageExportStatusParcel(ServerPackageExportState.REJECTED,
            errorCode = ServerPackageExportReason.UNAUTHORIZED)
        return synchronized(lock) {
            expireLocked()
            exports[handle]?.status?.value ?: ServerPackageExportStatusParcel(ServerPackageExportState.REJECTED,
                errorCode = ServerPackageExportReason.INVALID_HANDLE)
        }
    }

    override fun takeExport(handle: String?): ServerPackageExportPayloadParcel {
        if (!authenticated()) return ServerPackageExportPayloadParcel(ServerPackageTakeCode.UNAUTHORIZED)
        synchronized(lock) {
            expireLocked()
            val export = exports[handle] ?: return ServerPackageExportPayloadParcel(ServerPackageTakeCode.INVALID_HANDLE)
            when (export.status.value.state) {
                ServerPackageExportState.PENDING -> return ServerPackageExportPayloadParcel(ServerPackageTakeCode.NOT_READY)
                ServerPackageExportState.EXPIRED -> return ServerPackageExportPayloadParcel(ServerPackageTakeCode.EXPIRED)
                ServerPackageExportState.READY -> Unit
                else -> return ServerPackageExportPayloadParcel(ServerPackageTakeCode.TERMINAL)
            }
            val allowed = try { export.authorized?.invoke() == true } catch (_: Exception) {
                android.util.Log.e("ServerExport", "Export authorization unavailable")
                finishLocked(export, ServerPackageExportState.REJECTED, ServerPackageExportReason.FAILED)
                return ServerPackageExportPayloadParcel(ServerPackageTakeCode.FAILED)
            }
            if (!allowed) {
                finishLocked(export, ServerPackageExportState.REJECTED, ServerPackageExportReason.MANAGED_RESTRICTED)
                return ServerPackageExportPayloadParcel(ServerPackageTakeCode.UNAUTHORIZED)
            }
            val payload = requireNotNull(export.bytes).copyOf()
            finishLocked(export, ServerPackageExportState.TAKEN)
            if (inTransaction.get() == true) transactionPayload.set(payload)
            return ServerPackageExportPayloadParcel(ServerPackageTakeCode.OK, payload)
        }
    }

    override fun cancelExport(handle: String?): Pkcs12CancelResultParcel {
        if (!authenticated()) return Pkcs12CancelResultParcel(ServerPackageExportState.REJECTED,
            ServerPackageExportReason.UNAUTHORIZED)
        return synchronized(lock) {
            expireLocked()
            val export = exports[handle] ?: return Pkcs12CancelResultParcel(ServerPackageExportState.REJECTED,
                ServerPackageExportReason.INVALID_HANDLE)
            if (export.status.value.state in 1..2) finishLocked(export, ServerPackageExportState.CANCELLED)
            Pkcs12CancelResultParcel(export.status.value.state)
        }
    }

    fun observeExport(handle: String) = synchronized(lock) { exports[handle]?.status?.asStateFlow() }

    fun claimExport(handle: String): Boolean = synchronized(lock) {
        if (!exportLive(handle)) return false
        val export = exports[handle] ?: return false
        if (export.claimed || export.status.value.state != ServerPackageExportState.PENDING) return false
        export.claimed = true
        true
    }

    fun dismissPendingExport(handle: String) = synchronized(lock) {
        exports[handle]?.let { if (it.status.value.state == ServerPackageExportState.PENDING)
            finishLocked(it, ServerPackageExportState.CANCELLED, ServerPackageExportReason.USER_DECLINED) }
    }

    fun approveExport(handle: String, bytes: ByteArray, authorized: () -> Boolean): Boolean = synchronized(lock) {
        try {
            expireLocked()
            val export = exports[handle] ?: return false
            if (!active || !imports.sessionAuthorized() || !export.claimed ||
                export.status.value.state != ServerPackageExportState.PENDING ||
                !authorized() || ServerPackageCodec.validate(bytes) != null) return false
            export.bytes = bytes.copyOf()
            export.authorized = authorized
            export.status.value = ServerPackageExportStatusParcel(ServerPackageExportState.READY, export.expires)
            true
        } finally { bytes.fill(0) }
    }

    fun exportLive(handle: String): Boolean = synchronized(lock) {
        expireLocked()
        if (!imports.sessionAuthorized()) { close(); return false }
        active && exports[handle]?.status?.value?.state in 1..2
    }

    fun rejectExport(handle: String, reason: Int) = synchronized(lock) {
        exports[handle]?.let { if (it.status.value.state in 1..2) finishLocked(it, ServerPackageExportState.REJECTED, reason) }
    }

    fun close() {
        imports.close()
        synchronized(lock) {
            active = false
            launchAllowedUntil = 0
            exports.values.forEach { if (it.status.value.state in 1..2) finishLocked(it, ServerPackageExportState.CANCELLED) }
        }
    }

    private fun expireLocked() {
        exports.values.forEach {
            if (it.status.value.state in 1..2 && clock() >= it.expires) finishLocked(it, ServerPackageExportState.EXPIRED)
        }
    }

    private fun finishLocked(export: Export, state: Int, reason: Int = ServerPackageExportReason.NONE) {
        export.bytes?.fill(0)
        export.bytes = null
        export.authorized = null
        export.status.value = ServerPackageExportStatusParcel(state, export.expires, reason)
        ServerExportRegistry.remove(export.handle, this)
    }
}

internal object ServerExportRegistry {
    private val exports = ConcurrentHashMap<String, HostServerPackageControl>()
    fun put(handle: String, control: HostServerPackageControl) { exports[handle] = control }
    fun get(handle: String) = exports[handle]
    fun remove(handle: String, control: HostServerPackageControl) { exports.remove(handle, control) }
}
