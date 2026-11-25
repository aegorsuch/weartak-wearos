package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Collections

/** Permanent source/UID ownership and terminal deletion records, separate from receive dedup expiry. */
internal class DittoPointIngress(
    private val preferences: SharedPreferences,
    private val mutationFence: DittoPointMutationFence? = null,
) {
    private val records = preferences.all.mapValues { it.value as String }.toMutableMap()
    private var failedKey: String? = null
    private var migrationRevision = 0L
    data class Point(val document: DittoCotDocument, val deleted: Boolean)

    internal class MigrationSnapshot internal constructor(
        internal val owner: DittoPointIngress,
        internal val revision: Long,
        val records: List<DittoPointLedger.MigrationRecord>,
    ) {
        val version: Int = 1
    }

    @Synchronized
    fun snapshotForMigration(): MigrationSnapshot {
        check(failedKey == null) { "Unresolved Ditto point watermark write" }
        check(preferences.all == records) { "Ditto point watermarks changed outside this instance" }
        require(records.size <= MAX_ENTRIES) { "Ditto permanent point ledger full" }
        var total = 0L
        val snapshot = records.entries.sortedBy { it.key }.map { (key, value) ->
            require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid Ditto point watermark key"
            }
            total += value.toByteArray(Charsets.UTF_8).size
            require(total <= MAX_BYTES) { "Ditto permanent point ledger byte capacity reached" }
            val record = JSONObject(value)
            require(record.keys().asSequence().toSet() == setOf("owner", "time", "revision", "deleted")) {
                "Invalid Ditto point watermark fields"
            }
            val owner = record.get("owner") as? String
            val revision = record.get("revision") as? String
            val time = record.get("time") as? Number
            require(!owner.isNullOrBlank() && owner.length <= 512 && record.get("deleted") is Boolean &&
                revision != null && (revision.isEmpty() || (revision.length == 64 &&
                    revision.all { it in '0'..'9' || it in 'a'..'f' })) &&
                time != null && time.toDouble().isFinite() && time.toDouble() > 0 &&
                time.toDouble() <= 253402300799999.0 && time.toDouble() == time.toLong().toDouble()) {
                "Invalid Ditto point watermark"
            }
            DittoPointLedger.MigrationRecord(key, value)
        }
        return MigrationSnapshot(this, migrationRevision, Collections.unmodifiableList(snapshot))
    }

    @Synchronized
    fun isMigrationSnapshotCurrent(snapshot: MigrationSnapshot): Boolean =
        failedKey == null && snapshot.owner === this && snapshot.revision == migrationRevision &&
            preferences.all == records

    fun decode(raw: Map<String, Any?>): Point {
        val metadata = raw[DittoPointLedger.METADATA] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing WearTAK point contract")
        require((metadata["version"] as? Number)?.toDouble() == 1.0) { "Unsupported WearTAK point version" }
        val deleted = metadata["deleted"] as? Boolean
            ?: throw IllegalArgumentException("Missing WearTAK point lifecycle")
        require(raw["_r"] == deleted) { "Inconsistent WearTAK point lifecycle" }
        val doc = DittoCotDocument.decode(raw + ("_r" to false))
        require(CotMessageParser.isMarkerType(doc.type)) { "WearTAK deletion only supports points" }
        require(metadata["owner"] == doc.author && !doc.author.isNullOrBlank()) {
            "WearTAK point owner mismatch"
        }
        require(metadata["revision"] == doc.fingerprint) { "WearTAK point revision mismatch" }
        if (deleted) {
            val at = (metadata["deletedAt"] as? Number)?.toDouble()
            require(at != null && at.isFinite() && at >= doc.time &&
                at <= 253402300799999.0 && at == at.toLong().toDouble()) { "Invalid point deletion time" }
        }
        return Point(doc, deleted)
    }

    @Synchronized
    fun accept(point: Point): Boolean = mutate {
        val doc = point.document
        val previous = records[doc.key]?.let(::JSONObject)
        if (previous != null) {
            require(previous.getString("owner") == doc.author.orEmpty()) { "Cross-owner Ditto point revision" }
            if (previous.getBoolean("deleted")) {
                return@mutate point.deleted && previous.getLong("time") == doc.time &&
                    previous.getString("revision") == doc.fingerprint
            }
            val time = previous.getLong("time")
            if (doc.time < time) return@mutate false
            if (doc.time == time && previous.getString("revision") != doc.fingerprint) return@mutate false
        } else {
            require(records.size < MAX_ENTRIES) { "Ditto permanent point ledger full" }
        }
        val record = JSONObject().put("owner", doc.author.orEmpty()).put("time", doc.time)
            .put("revision", doc.fingerprint).put("deleted", point.deleted)
        val value = record.toString()
        if (records[doc.key] == value) return@mutate true
        val bytes = records.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        val oldSize = records[doc.key]?.toByteArray(Charsets.UTF_8)?.size ?: 0
        require(bytes - oldSize + record.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Ditto permanent point ledger byte capacity reached"
        }
        failedKey?.let { failed ->
            val repair = preferences.edit()
            records[failed]?.let { repair.putString(failed, it) } ?: repair.remove(failed)
            check(commit(repair)) { "Could not repair unacknowledged Ditto point watermark" }
            failedKey = null
        }
        val editor = preferences.edit()
        check(migrationRevision < Long.MAX_VALUE) { "Ditto point watermark migration revision exhausted" }
        if (!commit(editor.putString(doc.key, value))) {
            failedKey = doc.key
            throw IllegalStateException("Could not persist Ditto point watermark")
        }
        records[doc.key] = value
        migrationRevision++
        true
    }

    @Synchronized
    fun deleted(doc: DittoCotDocument): Boolean =
        records[doc.key]?.let { JSONObject(it).getBoolean("deleted") } == true

    private fun commit(editor: SharedPreferences.Editor): Boolean =
        mutationFence?.commit(editor) ?: editor.commit()

    private inline fun <T> mutate(crossinline block: () -> T): T =
        if (mutationFence == null) block() else mutationFence.mutate { block() }

    companion object {
        const val MAX_ENTRIES = 8192
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
