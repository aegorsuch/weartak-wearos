package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    fun hasPending(): Boolean = records.values.any { JSONObject(it).optBoolean("pending") }

    @Synchronized
    fun owns(uid: String): Boolean = DittoCotDocument.digest(uid) in records

    @Synchronized
    fun created(uid: String, owner: String) {
        require(uid.isNotBlank() && uid.length <= 512 && owner.isNotBlank())
        val key = DittoCotDocument.digest(uid)
        val existing = read(key)
        require(existing == null) { "Ditto point UID already used; create a new UID" }
        require(records.size < MAX_ENTRIES) { "Ditto local point ledger full" }
        save(key, JSONObject().put("uid", uid).put("owner", owner).put("deleted", false))
    }

    @Synchronized
    fun publish(document: Map<String, Any?>, write: (Map<String, Any?>) -> Unit): Boolean {
        val decoded = DittoCotDocument.decode(document)
        val key = DittoCotDocument.digest(decoded.uid)
        val record = read(key) ?: return false
        require(record.getString("owner") == decoded.author) { "Ditto local point owner mismatch" }
        if (record.getBoolean("deleted")) return false
        require(CotMessageParser.isMarkerType(decoded.type)) { "Not a supported Ditto point" }
        val previous = record.optJSONObject("document")
        if (previous != null) {
            val prior = DittoCotDocument.decode(toMap(previous))
            require(decoded.peer == prior.peer) { "Ditto point origin changed; create a new UID" }
            if (decoded.time < prior.time) return false
            require(decoded.time != prior.time || decoded.fingerprint == prior.fingerprint) {
                "Conflicting equal-time local point revision"
            }
        }
        val metadata = mapOf(
            "version" to 1, "owner" to decoded.author,
            "revision" to decoded.fingerprint, "deleted" to false
        )
        val doc = document + (METADATA to metadata)
        record.put("document", JSONObject(doc)).put("pending", true)
        save(key, record)
        mutableChanges.value++
        flush(key, record, write)
        return true
    }

    /** Persists intent before local removal; unpublished points close without a mesh tombstone. */
    @Synchronized
    fun delete(uid: String, owner: String, now: Long) {
        val key = DittoCotDocument.digest(uid)
        val record = read(key) ?: return
        require(record.getString("owner") == owner) { "Cannot delete another local point owner" }
        if (record.getBoolean("deleted")) return
        val document = record.optJSONObject("document")
        if (document != null) {
            val metadata = document.getJSONObject(METADATA)
            metadata.put("deleted", true).put("deletedAt", maxOf(now, document.getLong("b")))
            document.put("_r", true).put("visible", false).put("f", false)
            record.put("pending", true)
        }
        record.put("deleted", true)
        save(key, record)
        mutableChanges.value++
    }

    @Synchronized
    fun retry(write: (Map<String, Any?>) -> Unit, onError: (Exception) -> Unit) {
        records.keys.toList().forEach { key ->
            try {
                val record = requireNotNull(read(key))
                if (record.optBoolean("pending")) flush(key, record, write)
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private fun flush(key: String, record: JSONObject, write: (Map<String, Any?>) -> Unit) = mutate {
        write(toMap(record.getJSONObject("document")))
        record.put("pending", false)
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
        failedKey?.let { failed ->
            val repair = preferences.edit()
            records[failed]?.let { repair.putString(failed, it) } ?: repair.remove(failed)
            check(commit(repair)) { "Could not repair unacknowledged Ditto point intent" }
            failedKey = null
        }
        val editor = preferences.edit()
        if (!commit(editor.putString(key, value))) {
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
