package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only thrown when the native write was definitely not entered. */
internal class DittoWriteNotStartedException(message: String) : IllegalStateException(message)

/** Shared-key mission writers are trusted; these records enforce application ownership, not signatures. */
internal class DittoPointLedger(
    private val preferences: SharedPreferences,
    private val mutationFence: DittoPointMutationFence? = null,
) {
    private val records = preferences.all.mapValues { it.value as String }.toMutableMap()
    private var failedKey: String? = null
    private var migrationRevision = 0L
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    internal class MigrationRecord(val key: String, val json: String)

    internal class MigrationSnapshot internal constructor(
        internal val owner: DittoPointLedger,
        internal val revision: Long,
        val records: List<MigrationRecord>,
    ) {
        val version: Int = 1
    }

    @Synchronized
    fun snapshotForMigration(): MigrationSnapshot {
        check(failedKey == null) { "Unresolved Ditto point intent write" }
        check(preferences.all == records) { "Ditto point ledger changed outside this instance" }
        require(records.size <= MAX_ENTRIES) { "Ditto local point ledger full" }
        var total = 0L
        val snapshot = records.entries.sortedBy { it.key }.map { (key, value) ->
            val size = value.toByteArray(Charsets.UTF_8).size
            require(size <= MAX_RECORD_BYTES) { "Ditto point record too large" }
            total += size
            require(total <= MAX_TOTAL_BYTES) { "Ditto point ledger byte capacity reached" }
            val record = JSONObject(value)
            val uid = record.get("uid") as? String
                ?: throw IllegalArgumentException("Invalid Ditto point ownership record")
            require(uid.isNotBlank() && uid.length <= 512 && key == DittoCotDocument.digest(uid)) {
                "Invalid Ditto point ownership record"
            }
            val owner = record.get("owner") as? String
            require(!owner.isNullOrBlank() && record.get("deleted") is Boolean) {
                "Invalid Ditto point ownership record"
            }
            if (record.has("pending")) require(record.get("pending") is Boolean)
            if (record.has("document")) require(record.get("document") is JSONObject)
            require(!record.optBoolean("pending") || record.has("document")) {
                "Pending Ditto point lacks document"
            }
            MigrationRecord(key, value)
        }
        return MigrationSnapshot(this, migrationRevision, Collections.unmodifiableList(snapshot))
    }

    @Synchronized
    fun isMigrationSnapshotCurrent(snapshot: MigrationSnapshot): Boolean =
        failedKey == null && snapshot.owner === this && snapshot.revision == migrationRevision &&
            preferences.all == records

    @Synchronized
    fun hasUnexportableActivePoints(): Boolean = snapshotForMigration().records.any {
        val record = JSONObject(it.json)
        if (record.getBoolean("deleted")) return@any false
        val document = record.optJSONObject("document") ?: return@any true
        val decoded = DittoCotDocument.decode(toMap(document))
        val metadata = document.optJSONObject(METADATA)
        require(decoded.uid == record.getString("uid") && decoded.author == record.getString("owner") &&
            CotMessageParser.isMarkerType(decoded.type) && metadata != null &&
            metadata.optInt("version", -1) == 1 && metadata.optString("owner") == decoded.author &&
            metadata.opt("deleted") == false && metadata.optString("revision") == decoded.fingerprint &&
            decoded.fingerprint.isNotEmpty()) { "Invalid active Ditto point document" }
        false
    }

    @Synchronized
    fun hasPending(): Boolean = records.values.any { JSONObject(it).optBoolean("pending") }

    @Synchronized
    fun hasRetryablePending(): Boolean = records.values.any {
        val record = JSONObject(it)
        record.optBoolean("pending") && record.optString("nativePhase") == "NEVER_DISPATCHED"
    }

    @Synchronized
    fun hasUncertainNativeWrites(): Boolean = failedKey != null || records.values.any {
        val record = JSONObject(it)
        record.optBoolean("pending") && record.optString("nativePhase") != "NEVER_DISPATCHED"
    }

    @Synchronized
    fun owns(uid: String): Boolean = DittoCotDocument.digest(uid) in records

    @Synchronized
    fun lastEventTime(uid: String): Long? =
        read(DittoCotDocument.digest(uid))?.optJSONObject("document")?.getLong("b")

    @Synchronized
    fun created(uid: String, owner: String) = mutate {
        require(uid.isNotBlank() && uid.length <= 512 && owner.isNotBlank())
        val key = DittoCotDocument.digest(uid)
        val existing = read(key)
        require(existing == null) { "Ditto point UID already used; create a new UID" }
        require(records.size < MAX_ENTRIES) { "Ditto local point ledger full" }
        save(key, JSONObject().put("uid", uid).put("owner", owner).put("deleted", false))
    }

    @Synchronized
    fun publish(
        document: Map<String, Any?>,
        validateBeforeWrite: (Map<String, Any?>) -> Unit = {},
        write: (Map<String, Any?>) -> Unit,
    ): Boolean = mutate {
        val decoded = DittoCotDocument.decode(document)
        val key = DittoCotDocument.digest(decoded.uid)
        val record = read(key) ?: return@mutate false
        require(record.getString("owner") == decoded.author) { "Ditto local point owner mismatch" }
        check(!record.optBoolean("pending") || record.optString("nativePhase") == "NEVER_DISPATCHED") {
            "Prior Ditto point write outcome uncertain"
        }
        if (record.getBoolean("deleted")) return@mutate false
        require(CotMessageParser.isMarkerType(decoded.type)) { "Not a supported Ditto point" }
        validateBeforeWrite(document)
        val previous = record.optJSONObject("document")
        if (previous != null) {
            val prior = DittoCotDocument.decode(toMap(previous))
            require(decoded.peer == prior.peer) { "Ditto point origin changed; create a new UID" }
            if (decoded.time < prior.time) return@mutate false
            require(decoded.time != prior.time || decoded.fingerprint == prior.fingerprint) {
                "Conflicting equal-time local point revision"
            }
        }
        val metadata = mapOf(
            "version" to 1, "owner" to decoded.author,
            "revision" to decoded.fingerprint, "deleted" to false
        )
        val doc = document + (METADATA to metadata)
        record.put("document", JSONObject(doc)).put("pending", true).put("nativePhase", "NEVER_DISPATCHED")
        save(key, record)
        mutableChanges.value++
        flush(key, record, write)
        true
    }

    /** Persists intent before local removal; unpublished points close without a mesh tombstone. */
    @Synchronized
    fun delete(uid: String, owner: String, now: Long) = mutate {
        val key = DittoCotDocument.digest(uid)
        val record = read(key) ?: return@mutate
        require(record.getString("owner") == owner) { "Cannot delete another local point owner" }
        check(!record.optBoolean("pending") || record.optString("nativePhase") == "NEVER_DISPATCHED") {
            "Prior Ditto point write outcome uncertain"
        }
        if (record.getBoolean("deleted")) return@mutate
        val document = record.optJSONObject("document")
        if (document != null) {
            val metadata = document.getJSONObject(METADATA)
            metadata.put("deleted", true).put("deletedAt", maxOf(now, document.getLong("b")))
            document.put("_r", true).put("visible", false).put("f", false)
            record.put("pending", true).put("nativePhase", "NEVER_DISPATCHED")
        }
        record.put("deleted", true)
        save(key, record)
        mutableChanges.value++
    }

    @Synchronized
    fun retry(
        write: (Map<String, Any?>) -> Unit,
        onError: (Exception) -> Unit,
        validateBeforeWrite: (Map<String, Any?>) -> Unit = {},
    ) {
        try {
            mutate {
                if (failedKey != null) {
                    onError(IllegalStateException("Unresolved Ditto point intent write"))
                    return@mutate
                }
                records.keys.toList().forEach { key ->
                    try {
                        val record = requireNotNull(read(key))
                        if (record.optBoolean("pending") && record.optString("nativePhase") == "NEVER_DISPATCHED") {
                            validateBeforeWrite(toMap(record.getJSONObject("document")))
                            flush(key, record, write)
                        }
                    } catch (error: Exception) {
                        onError(error)
                    }
                }
            }
        } catch (error: DittoPointAdmissionClosedException) {
            onError(error)
        }
    }

    private fun flush(key: String, record: JSONObject, write: (Map<String, Any?>) -> Unit) = mutate {
        check(record.optBoolean("pending") && record.optString("nativePhase") == "NEVER_DISPATCHED")
        record.put("nativePhase", "DISPATCH_STARTED")
        save(key, record)
        mutableChanges.value++
        try {
            write(toMap(record.getJSONObject("document")))
        } catch (error: DittoWriteNotStartedException) {
            record.put("nativePhase", "NEVER_DISPATCHED")
            save(key, record)
            mutableChanges.value++
            throw error
        }
        record.put("pending", false).remove("nativePhase")
        save(key, record)
        mutableChanges.value++
    }

    private fun read(key: String): JSONObject? = records[key]?.let(::JSONObject)

    private fun save(key: String, record: JSONObject) = mutate {
        val value = record.toString()
        check(migrationRevision < Long.MAX_VALUE) { "Ditto point migration revision exhausted" }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) { "Ditto point record too large" }
        val oldSize = records[key]?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val bytes = records.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        require(bytes - oldSize + value.toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES) {
            "Ditto point ledger byte capacity reached"
        }
        check(failedKey == null) { "Unresolved Ditto point intent write" }
        val editor = preferences.edit()
        val persisted = try {
            commit(editor.putString(key, value))
        } catch (error: Exception) {
            failedKey = key
            throw error
        }
        if (!persisted) {
            failedKey = key
            throw IllegalStateException("Could not persist Ditto point intent")
        }
        records[key] = value
        migrationRevision++
    }

    private inline fun <T> mutate(crossinline block: () -> T): T =
        if (mutationFence == null) block() else mutationFence.mutate { block() }

    private fun commit(editor: SharedPreferences.Editor): Boolean =
        mutationFence?.commit(editor) ?: editor.commit()

    companion object {
        const val METADATA = "weartakPoint"
        const val MAX_ENTRIES = 4096
        const val MAX_RECORD_BYTES = 384 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024

        private fun toMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith {
            when (val value = json.get(it)) {
                is JSONObject -> toMap(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
    }
}
