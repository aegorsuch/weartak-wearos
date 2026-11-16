package com.aitech.weartak_civ

import android.os.Binder
import com.aitech.weartak_civ.service.openvpn.OpenVpnNativeBridge
import com.aitech.weartak_civ.service.openvpn.OpenVpnService
import com.aitech.weartak_civ.service.openvpn.OpenVpnStatus
import com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as C
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

internal class OpenVpnRawTcpProviderV1(
    private val plugin: OpenVpnPluginBinder,
    private val hostUid: Int,
) : IOpenVpnRawTcpProviderV1.Stub() {
    private data class StreamLease(
        val serverId: String,
        val profileId: String,
        val generation: Long,
    )

    private val streamPermits = Semaphore(C.MAX_ACTIVE_STREAMS)
    private val streams = ConcurrentHashMap<Long, StreamLease>()
    private val streamGeneration = AtomicLong()

    override fun getApiVersion(): Int =
        if (authorized()) C.PROVIDER_API_VERSION else 0

    override fun getRuntimeState(profileId: String?): Int {
        if (!authorized() || !plugin.isProviderActive() || !validProfileId(profileId)) {
            return C.RUNTIME_ERROR
        }
        val state = OpenVpnService.runtimeState.value
        return if (state.profileId != profileId) {
            C.RUNTIME_STOPPED
        } else {
            when (state.status) {
                OpenVpnStatus.STOPPED -> C.RUNTIME_STOPPED
                OpenVpnStatus.CONNECTING -> C.RUNTIME_CONNECTING
                OpenVpnStatus.CONNECTED -> C.RUNTIME_CONNECTED
                OpenVpnStatus.STOPPING -> C.RUNTIME_STOPPING
                OpenVpnStatus.ERROR -> C.RUNTIME_ERROR
            }
        }
    }

    override fun openStream(
        serverId: String?,
        profileId: String?,
        endpointAddress: String?,
        port: Int,
        connectionGeneration: Long,
        timeoutMs: Long,
    ): Long {
        if (!activeCaller()) return OpenVpnNativeBridge.Result.INVALID_ARGUMENT.toLong()
        if (!validServerId(serverId) || !validProfileId(profileId) ||
            !isLiteralAddress(endpointAddress) || port !in 1..65535 || connectionGeneration <= 0 ||
            timeoutMs !in 1..C.MAX_TIMEOUT_MS
        ) return OpenVpnNativeBridge.Result.INVALID_ARGUMENT.toLong()

        val lifecycleGeneration = streamGeneration.get()
        val state = OpenVpnService.runtimeState.value
        if (state.status != OpenVpnStatus.CONNECTED || state.profileId != profileId ||
            OpenVpnNativeBridge.getState() != OpenVpnNativeBridge.State.CONNECTED
        ) return OpenVpnNativeBridge.Result.NOT_CONNECTED.toLong()

        if (!streamPermits.tryAcquire()) return OpenVpnNativeBridge.Result.QUEUE_FULL.toLong()
        val id = try {
            OpenVpnNativeBridge.openStream(endpointAddress!!, port, timeoutMs)
        } catch (error: RuntimeException) {
            streamPermits.release()
            throw error
        }
        if (id <= 0) {
            streamPermits.release()
            return id
        }
        val lease = StreamLease(serverId!!, profileId!!, connectionGeneration)
        if (!activeCaller() || streamGeneration.get() != lifecycleGeneration) {
            streamPermits.release()
            OpenVpnNativeBridge.closeStream(id)
            return OpenVpnNativeBridge.Result.NOT_CONNECTED.toLong()
        }
        streams[id] = lease
        if (!activeCaller() || streamGeneration.get() != lifecycleGeneration) {
            if (streams.remove(id, lease)) {
                streamPermits.release()
                OpenVpnNativeBridge.closeStream(id)
            }
            return OpenVpnNativeBridge.Result.NOT_CONNECTED.toLong()
        }
        return id
    }

    override fun readStream(
        profileId: String?,
        streamId: Long,
        destination: ByteArray?,
        offset: Int,
        length: Int,
        timeoutMs: Long,
    ): Int {
        if (!activeCaller()) return OpenVpnNativeBridge.Result.INVALID_ARGUMENT
        if (!validIo(profileId, streamId, destination, offset, length, timeoutMs)) {
            return OpenVpnNativeBridge.Result.INVALID_ARGUMENT
        }
        return OpenVpnNativeBridge.readStream(
            streamId, destination!!, offset, length, timeoutMs,
        )
    }

    override fun writeStream(
        profileId: String?,
        streamId: Long,
        source: ByteArray?,
        offset: Int,
        length: Int,
        timeoutMs: Long,
    ): Int {
        if (!activeCaller()) return OpenVpnNativeBridge.Result.INVALID_ARGUMENT
        if (!validIo(profileId, streamId, source, offset, length, timeoutMs)) {
            return OpenVpnNativeBridge.Result.INVALID_ARGUMENT
        }
        return OpenVpnNativeBridge.writeStream(
            streamId, source!!, offset, length, timeoutMs,
        )
    }

    override fun closeStream(profileId: String?, streamId: Long): Int {
        if (!authorized()) return OpenVpnNativeBridge.Result.INVALID_ARGUMENT
        if (!validProfileId(profileId) || streamId <= 0) {
            return OpenVpnNativeBridge.Result.INVALID_ARGUMENT
        }
        val lease = streams[streamId] ?: return OpenVpnNativeBridge.Result.STREAM_NOT_FOUND
        if (lease.profileId != profileId) return OpenVpnNativeBridge.Result.INVALID_ARGUMENT
        if (streams.remove(streamId, lease)) {
            streamPermits.release()
            return OpenVpnNativeBridge.closeStream(streamId)
        }
        return OpenVpnNativeBridge.Result.STREAM_NOT_FOUND
    }

    fun closeAll() {
        streamGeneration.incrementAndGet()
        streams.keys.toList().forEach { streamId ->
            val lease = streams[streamId] ?: return@forEach
            closeStreamForHost(lease.profileId, streamId)
        }
    }

    private fun closeStreamForHost(profileId: String, streamId: Long) {
        val lease = streams[streamId] ?: return
        if (lease.profileId == profileId && streams.remove(streamId, lease)) {
            streamPermits.release()
            OpenVpnNativeBridge.closeStream(streamId)
        }
    }

    private fun validIo(
        profileId: String?,
        streamId: Long,
        bytes: ByteArray?,
        offset: Int,
        length: Int,
        timeoutMs: Long,
    ): Boolean {
        if (!validProfileId(profileId) || streamId <= 0 || bytes == null ||
            length !in 1..C.MAX_IO_BYTES || offset < 0 || length > bytes.size - offset ||
            timeoutMs !in 1..C.MAX_TIMEOUT_MS
        ) return false
        return streams[streamId]?.profileId == profileId
    }

    private fun authorized(): Boolean = Binder.getCallingUid() == hostUid && hostUid == plugin.hostUid()
    private fun activeCaller(): Boolean = authorized() && plugin.isProviderActive()
    private fun validProfileId(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= C.MAX_PROFILE_ID_LENGTH
    private fun validServerId(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= C.MAX_SERVER_ID_LENGTH

    private fun isLiteralAddress(value: String?): Boolean {
        if (value.isNullOrBlank() || value.length > 15 || '%' in value || ':' in value) return false
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
