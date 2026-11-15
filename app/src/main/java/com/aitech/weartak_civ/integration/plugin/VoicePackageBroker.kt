package com.aitech.weartak_civ.integration.plugin

import android.content.pm.PackageManager
import android.os.*
import com.aitech.weartak_civ.WearTakApplication
import com.weartak.voice.api.*
import com.weartak.voice.api.VoicePackageContract as C
import java.security.MessageDigest
import java.util.UUID

class VoicePackageStageException(val code: Int, message: String) : java.io.IOException(message)

/** One bounded, host-owned memory handoff for TAK and Companion. Never contains credentials. */
class VoicePackageBroker(private val app: WearTakApplication) {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val bindings = mutableSetOf<Binding>()
    private val offers = mutableMapOf<String, Offer>()
    private val records = linkedMapOf<String, Record>()
    private data class Record(val id: String, val hash: String, val origin: String, val recipient: String,
        val owner: String, var state: Int = C.DOWNLOADED, var missionHash: String = "", var error: Int = C.OK, var taken: Boolean = false)
    private data class Offer(val handle: String, val record: Record, val kind: Int, val bytes: ByteArray,
        val binding: Binding, val expires: Long, val originCurrent: () -> Boolean)
    private val metadata = android.util.AtomicFile(java.io.File(app.filesDir, "voice-package-receipts.json"))
    init {
        runCatching {
            val bytes = metadata.readFully(); require(bytes.size <= 131072)
            val array = org.json.JSONArray(bytes.toString(Charsets.UTF_8)); require(array.length() <= 64)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val state = item.getInt("state").let { if (it == C.DOWNLOADED) C.EXPIRED else it }
                val record = Record(item.getString("id"), item.getString("hash"), item.getString("origin"),
                    item.getString("recipient"), "", state, item.optString("missionHash"), item.optInt("error"), item.optBoolean("taken"))
                records[record.id] = record
            }
        }.onFailure { records.clear() }
    }
    private fun persist() {
        val array = org.json.JSONArray()
        records.values.forEach { r -> array.put(org.json.JSONObject().put("id", r.id).put("hash", r.hash)
            .put("origin", r.origin).put("recipient", r.recipient).put("state", r.state)
            .put("missionHash", r.missionHash).put("error", r.error).put("taken", r.taken)) }
        val stream = metadata.startWrite()
        try { stream.write(array.toString().toByteArray()); metadata.finishWrite(stream) }
        catch (failure: Exception) { metadata.failWrite(stream); throw failure }
    }
    fun statusForOrigin(id: String, origin: String): Bundle = synchronized(lock) {
        val record = records[id] ?: return Bundle().apply { putInt("code", C.UNAVAILABLE) }
        check(record.origin == origin) { "Foreign voice origin" }; statusBundle(record)
    }
    fun newBinding(pkg: String, uid: Int, available: (() -> Boolean)? = null, current: () -> Boolean): Binding =
        Binding(pkg, uid, signer(pkg), current, available ?: current).also { synchronized(lock) { bindings.add(it) } }
    private fun signer(pkg: String): String {
        val info = app.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        return requireNotNull(info.signingInfo).apkContentsSigners.map { digest(it.toByteArray()) }.sorted().joinToString(",")
    }
    fun readyCount(): Int = synchronized(lock) { bindings.count { it.ready() } }
    fun canDeliver() = readyCount() == 1
    fun status(id: String): Bundle = synchronized(lock) { records[id]?.let(::statusBundle) ?: Bundle().apply { putInt("code", C.UNAVAILABLE) } }
    private fun statusBundle(record: Record) = Bundle().apply {
        putInt("version", C.VERSION); putInt("code", C.OK); putString("transferId", record.id)
        putString("payloadSha256", record.hash); putString("missionSha256", record.missionHash)
        putInt("state", record.state); putString("status", C.stateName(record.state)); putInt("errorCode", record.error)
    }
    fun stage(kind: Int, bytes: ByteArray, id: String, origin: String, originCurrent: () -> Boolean = { true }): Bundle {
        require(kind == C.JSON || kind == C.ZIP)
        if (bytes.isEmpty()) throw VoicePackageStageException(C.INVALID_PACKAGE, "Empty voice package")
        if (bytes.size > if (kind == C.JSON) C.MAX_JSON else C.MAX_ZIP)
            throw VoicePackageStageException(C.TOO_LARGE, "Voice package exceeds receiver limit")
        require(UUID.fromString(id).toString() == id && origin.length in 1..256)
        val stale = synchronized(lock) { offers.values.filter {
            it.expires <= SystemClock.elapsedRealtime() || !it.binding.ready() || !it.originCurrent()
        }.map { it.handle } }
        stale.forEach { fail(it, C.UNAVAILABLE) }
        val hash = digest(bytes)
        val offer = synchronized(lock) {
            check(originCurrent()) { "Origin session ended" }
            val receiver = bindings.filter { it.ready() }.singleOrNull() ?: throw VoicePackageStageException(C.UNAVAILABLE, "Enable exactly one voice receiver")
            val recipient = receiver.identity
            records.values.firstOrNull { it.origin == origin && it.hash == hash && it.recipient == recipient &&
                it.state in setOf(C.DOWNLOADED, C.STAGED, C.APPLIED, C.REJECTED) }?.let { return statusBundle(it) }
            records[id]?.let { previous ->
                require(previous.hash == hash && previous.origin == origin && previous.recipient == recipient &&
                    previous.state in setOf(C.FAILED, C.EXPIRED)) { "Transfer ID already used" }
                records.remove(id)
            }
            if (offers.size >= 4) throw VoicePackageStageException(C.BUSY, "Voice staging busy")
            // Keep total receipts bounded, including pending reviews. Never evict an active lease/review.
            while (records.size >= 64) {
                val oldest = records.values.firstOrNull { it.state !in setOf(C.DOWNLOADED, C.STAGED) }
                    ?: error("Voice receipt store full")
                records.remove(oldest.id)
            }
            val record = Record(id, hash, origin, recipient, receiver.token); records[id] = record
            Offer(UUID.randomUUID().toString(), record, kind, bytes.copyOf(), receiver,
                SystemClock.elapsedRealtime() + 120_000, originCurrent).also { offers[it.handle] = it }
        }
        synchronized(lock) { persist() }
        main.postDelayed({
            fail(offer.handle, C.UNAVAILABLE)
            synchronized(lock) {
                if (offer.record.state == C.DOWNLOADED) { offer.record.state = C.EXPIRED; offer.record.error = C.UNAVAILABLE; persist() }
            }
        }, 120_000)
        val metadata = Bundle().apply {
            putInt("version", 1); putString("handle", offer.handle); putString("transferId", id)
            putString("payloadSha256", hash); putInt("payloadKind", kind); putInt("byteCount", bytes.size)
            putString("originId", origin); putLong("expiresAtElapsedRealtime", offer.expires)
        }
        try { offer.binding.callback?.onOffer(metadata) ?: error("Voice receiver disconnected") }
        catch (failure: Exception) { fail(offer.handle, C.UNAVAILABLE); throw failure }
        return status(id)
    }
    private fun fail(handle: String, code: Int) {
        val offer = synchronized(lock) {
            offers.remove(handle)?.also { it.bytes.fill(0); it.record.state = C.EXPIRED; it.record.error = code; persist() }
        } ?: return
        runCatching { offer.binding.callback?.onRevoked(handle, code) }
    }
    private fun expire(handle: String) = fail(handle, C.UNAVAILABLE)
    inner class Binding internal constructor(val pkg: String, private val uid: Int, private val cert: String,
        private val current: () -> Boolean, private val available: () -> Boolean) : IVoicePackageControlV1.Stub() {
        val identity = "$pkg:$cert"
        val token = UUID.randomUUID().toString()
        @Volatile private var closed = false
        @Volatile internal var callback: IVoicePackageReceiverV1? = null
        private var death: IBinder.DeathRecipient? = null
        fun active(): Boolean = !closed && current() && runCatching {
            app.packageManager.getPackageUid(pkg, 0) == uid && signer(pkg) == cert
        }.getOrDefault(false)
        fun ready(): Boolean = available() && active() && callback?.asBinder()?.isBinderAlive == true
        private fun authenticate() {
            if (Binder.getCallingUid() != uid || !active()) throw SecurityException("Inactive voice receiver binding")
        }
        override fun getCapabilities(): Bundle { authenticate(); return Bundle().apply {
            putInt("version", 1); putInt("maxZipBytes", C.MAX_ZIP); putInt("maxJsonBytes", C.MAX_JSON)
            putLong("leaseMillis", 120_000); putIntArray("payloadKinds", intArrayOf(C.JSON, C.ZIP))
        } }
        override fun registerReceiver(receiver: IVoicePackageReceiverV1?): Bundle {
            authenticate(); requireNotNull(receiver)
            synchronized(lock) {
                check(callback == null || callback?.asBinder() == receiver.asBinder()) { "Receiver already registered" }
                if (callback == null) {
                    val recipient = IBinder.DeathRecipient { revokeReceiver() }
                    receiver.asBinder().linkToDeath(recipient, 0); death = recipient; callback = receiver
                }
            }
            return Bundle().apply { putInt("code", C.OK); putInt("version", 1) }
        }
        override fun takePackage(handle: String?): Bundle {
            authenticate(); requireNotNull(handle)
            synchronized(lock) {
                val offer = offers[handle] ?: return Bundle().apply { putInt("version", 1); putInt("code", C.UNAVAILABLE) }
                if (offer.binding !== this || offer.record.recipient != identity) throw SecurityException("Foreign voice handle")
                if (!ready() || offer.expires <= SystemClock.elapsedRealtime() || !offer.originCurrent())
                    return Bundle().apply { putInt("version", 1); putInt("code", C.UNAVAILABLE) }
                offers.remove(handle)
                offer.record.taken = true; persist()
                return Bundle().apply { putInt("version", 1); putInt("code", C.OK); putByteArray("payload", offer.bytes.copyOf()) }.also { offer.bytes.fill(0) }
            }
        }
        override fun reportState(id: String?, hash: String?, missionHash: String?, state: Int, error: Int): Bundle {
            authenticate(); requireNotNull(id)
            require(hash?.matches(Regex("[a-f0-9]{64}")) == true)
            require(missionHash.isNullOrEmpty() || missionHash.matches(Regex("[a-f0-9]{64}")))
            require(state in setOf(C.STAGED, C.APPLIED, C.REJECTED, C.FAILED) && error in 0..C.LOCAL_IO)
            synchronized(lock) {
                val record = records[id] ?: error("Unknown voice transfer")
                check(record.recipient == identity && record.hash == hash) { "Foreign voice receipt" }
                check(record.missionHash.isEmpty() || record.missionHash == missionHash) { "Mission digest changed" }
                check(record.state == state || when (record.state) {
                    C.DOWNLOADED -> state == C.STAGED || state == C.FAILED
                    C.STAGED -> state == C.APPLIED || state == C.REJECTED || state == C.FAILED
                    C.FAILED, C.EXPIRED -> record.taken && record.error == C.UNAVAILABLE && state == C.STAGED
                    else -> false
                }) { "Invalid voice state transition" }
                if (state == C.STAGED || state == C.APPLIED) require(!missionHash.isNullOrEmpty())
                record.state = state; record.error = error; record.missionHash = missionHash.orEmpty()
                persist()
                offers.values.filter { it.record === record }.map { it.handle }.forEach { handle -> offers.remove(handle)?.bytes?.fill(0) }
                return statusBundle(record)
            }
        }
        override fun getStatus(id: String?): Bundle {
            authenticate(); synchronized(lock) {
                val record = records[id] ?: return Bundle().apply { putInt("code", C.UNAVAILABLE) }
                check(record.recipient == identity) { "Foreign voice status" }; return statusBundle(record)
            }
        }
        override fun unregisterReceiver(receiver: IVoicePackageReceiverV1?) {
            authenticate(); if (callback?.asBinder() == receiver?.asBinder()) revokeReceiver()
        }
        private fun revokeReceiver() {
            val handles = synchronized(lock) {
                callback?.let { cb -> death?.let { runCatching { cb.asBinder().unlinkToDeath(it, 0) } } }
                callback = null; death = null
                records.values.filter { it.owner == token && it.state == C.DOWNLOADED }.forEach {
                    it.state = C.FAILED; it.error = C.UNAVAILABLE
                }
                persist()
                offers.values.filter { it.binding === this }.map { it.handle }
            }
            handles.forEach { fail(it, C.UNAVAILABLE) }
        }
        fun close() { closed = true; revokeReceiver(); synchronized(lock) { bindings.remove(this) } }
    }
    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
