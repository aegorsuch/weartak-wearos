package com.aitech.weartak_civ.service.openvpn

/**
 * WEAR-590 Ticket 1's minimal native bridge; the seam for future Ticket 2 work.
 *
 * This object declares calls and fixed result meanings. NativeTunnelSession
 * owns networking, streams and the worker. This class owns no Activity,
 * coroutine scope, retry policy, profile storage or tunnel lifecycle policy.
 *
 * First use loads libwearvpn.so. A missing library/signature is a LinkageError,
 * not a SessionError; the diagnostic caller must report it separately.
 *
 * Calls run synchronously on their calling JVM thread. Validation, start,
 * awaitConnected, openStream, readStream and writeStream belong off the UI
 * thread. Coroutine cancellation alone does not cancel blocking JNI: use the
 * native timeout, closeStream or stop. Keep an independent control execution
 * path so a waiting read cannot prevent the caller from requesting stop.
 *
 * Arrays belong to the caller. Do not mutate profiles/sources or reuse a
 * destination concurrently with a call. Wipe sensitive caller-owned arrays
 * with fill(0) after their last use. JNI wipes its own copies, not yours.
 *
 * The native session survives Activity recreation. Stop is asynchronous;
 * observe State.STOPPED before restarting. No unload/destroy call is exposed.
 */
object OpenVpnNativeBridge {
    const val MAX_PROFILE_BYTES: Int = 256 * 1024
    const val MAX_IO_BYTES: Int = 64 * 1024
    const val MAX_TIMEOUT_MS: Long = 60_000L

    /** Operation results, not tunnel state codes. Positive I/O results count bytes. */
    object Result {
        const val OK = 0
        const val INVALID_ARGUMENT = -1
        const val INVALID_PROFILE = -2
        const val UNSUPPORTED_AUTHENTICATION = -3
        const val NOT_CONNECTED = -4
        const val ALREADY_RUNNING = -5
        const val STOPPING = -6
        const val TIMEOUT = -7
        const val QUEUE_FULL = -8
        const val STREAM_NOT_FOUND = -9
        const val STREAM_CLOSED = -10
        const val NETWORK_FAILURE = -11
        const val INTERNAL_ERROR = -12
    }

    /** Only getState() returns this value domain. ERROR is 4, not -12. */
    object State {
        const val STOPPED = 0
        const val CONNECTING = 1
        const val CONNECTED = 2
        const val STOPPING = 3
        const val ERROR = 4
    }

    init {
        System.loadLibrary("wearvpn")
    }

    /**
     * Evaluates a self-contained, non-interactive UTF-8 profile without
     * starting the tunnel. Returns OK or a negative result. No profile String
     * is made. Empty, oversized and malformed profiles are INVALID_PROFILE.
     */
    fun validateProfile(profile: ByteArray): Int = nativeValidateProfile(profile)

    /**
     * Copies the profile and begins an attempt. OK means accepted, not
     * connected: follow with awaitConnected(). An active session returns
     * ALREADY_RUNNING; an attempt during cleanup returns STOPPING.
     */
    fun start(profile: ByteArray): Int = nativeStart(profile)

    /**
     * Waits 1..MAX_TIMEOUT_MS milliseconds. TIMEOUT does not cancel the
     * connection attempt; call stop() explicitly if abandoning it.
     */
    fun awaitConnected(timeoutMs: Long): Int = nativeAwaitConnected(timeoutMs)

    /** Returns State; failure to construct/query the native session is ERROR. */
    fun getState(): Int = nativeGetState()

    /**
     * Idempotently requests shutdown. OK does not mean it has finished.
     * Observe getState() with a bounded deadline off the UI thread; do not
     * spin, forcibly destroy, or recreate the native session during cleanup.
     */
    fun stop(): Int = nativeStop()

    /**
     * Connects an inner TCP stream through an already connected VPN.
     * Requires literal IPv4, port 1..65535 and timeout 1..MAX_TIMEOUT_MS.
     * Returns a positive opaque ID only after the TCP handshake succeeds,
     * or a negative Result widened to Long. IDs are not pointers and must
     * not be reused after close or tunnel restart.
     */
    fun openStream(ipv4: String, port: Int, timeoutMs: Long): Long =
        nativeOpenStream(ipv4, port, timeoutMs)

    /**
     * Writes a valid source slice of 1..MAX_IO_BYTES bytes.
     * Returns bytes accepted into lwIP, not acknowledged/delivered bytes.
     * A partial positive count takes precedence over a simultaneous timeout
     * or error. Advance offset by that count; retry only the remaining suffix.
     * A negative Result means no bytes were accepted by that call.
     * Use one writer per stream; competing writes can return QUEUE_FULL.
     */
    fun writeStream(
        streamId: Long,
        source: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Long,
    ): Int = nativeWriteStream(streamId, source, offset, length, timeoutMs)

    /**
     * Fills destination starting at offset, requesting 1..MAX_IO_BYTES bytes.
     * Positive: that many new bytes (possibly fewer than requested).
     * Zero: EOF after buffered data is drained. TIMEOUT (-7): no data before
     * the deadline, not EOF. Other negatives: an error.
     * Only the returned region contains new read data. The caller owns and
     * can reuse the array after the call. Use one reader per stream for
     * straightforward application ordering.
     */
    fun readStream(
        streamId: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Long,
    ): Int = nativeReadStream(streamId, destination, offset, length, timeoutMs)

    /**
     * Requests closure of one stream, not its siblings or the outer tunnel.
     * Repeated/unknown positive IDs close harmlessly. Zero/negative IDs are
     * INVALID_ARGUMENT. Reads/writes on removed IDs are STREAM_NOT_FOUND.
     */
    fun closeStream(streamId: Long): Int = nativeCloseStream(streamId)

    /** Fixed diagnostic labels, never profile or dependency-generated text. */
    fun resultName(result: Int): String = when (result) {
        Result.OK -> "OK"
        Result.INVALID_ARGUMENT -> "INVALID_ARGUMENT"
        Result.INVALID_PROFILE -> "INVALID_PROFILE"
        Result.UNSUPPORTED_AUTHENTICATION -> "UNSUPPORTED_AUTHENTICATION"
        Result.NOT_CONNECTED -> "NOT_CONNECTED"
        Result.ALREADY_RUNNING -> "ALREADY_RUNNING"
        Result.STOPPING -> "STOPPING"
        Result.TIMEOUT -> "TIMEOUT"
        Result.QUEUE_FULL -> "QUEUE_FULL"
        Result.STREAM_NOT_FOUND -> "STREAM_NOT_FOUND"
        Result.STREAM_CLOSED -> "STREAM_CLOSED"
        Result.NETWORK_FAILURE -> "NETWORK_FAILURE"
        Result.INTERNAL_ERROR -> "INTERNAL_ERROR"
        else -> if (result > 0) "BYTE_COUNT" else "UNKNOWN_ERROR"
    }

    fun stateName(state: Int): String = when (state) {
        State.STOPPED -> "STOPPED"
        State.CONNECTING -> "CONNECTING"
        State.CONNECTED -> "CONNECTED"
        State.STOPPING -> "STOPPING"
        State.ERROR -> "ERROR"
        else -> "UNKNOWN_STATE"
    }

    // Instance methods on the Kotlin object. Do not add @JvmStatic without
    // updating/verifying JNI signatures. JNI validates even though ordinary
    // Kotlin callers cannot supply null to these non-null declarations.
    private external fun nativeValidateProfile(profile: ByteArray): Int
    private external fun nativeStart(profile: ByteArray): Int
    private external fun nativeAwaitConnected(timeoutMs: Long): Int
    private external fun nativeGetState(): Int
    private external fun nativeStop(): Int
    private external fun nativeOpenStream(ipv4: String, port: Int, timeoutMs: Long): Long
    private external fun nativeWriteStream(
        streamId: Long, source: ByteArray, offset: Int, length: Int, timeoutMs: Long,
    ): Int
    private external fun nativeReadStream(
        streamId: Long, destination: ByteArray, offset: Int, length: Int, timeoutMs: Long,
    ): Int
    private external fun nativeCloseStream(streamId: Long): Int
}
