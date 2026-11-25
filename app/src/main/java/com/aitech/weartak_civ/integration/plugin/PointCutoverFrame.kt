package com.aitech.weartak_civ.integration.plugin

import android.os.ParcelFileDescriptor
import com.aitech.weartak_civ.cot.DittoPointCutoverSnapshot
import com.aitech.weartak_civ.cot.DittoPointLedger
import com.weartak.plugin.sdk.pointcutover.v1.PointCutoverContractV1 as Contract
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Private temporary export; the original preference stores are never modified. */
internal class PointCutoverFrame private constructor(
    private val file: File,
    val length: Long,
    val localCount: Int,
    val watermarkCount: Int,
    private val digest: ByteArray,
) : AutoCloseable {
    fun openReadOnly(): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    fun digest(): ByteArray = digest.copyOf()

    override fun close() {
        check(!file.exists() || file.delete()) { "Point cutover temporary file cleanup failed" }
    }

    companion object {
        fun create(directory: File, pair: DittoPointCutoverSnapshot.Pair): PointCutoverFrame {
            val local = pair.ledger.records
            val watermarks = pair.watermarks.records
            require(local.size <= Contract.MAX_LOCAL_RECORDS && watermarks.size <= Contract.MAX_WATERMARK_RECORDS)
            val file = File.createTempFile("point-cutover-", ".tmp", directory)
            var success = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(file).use { stream ->
                    val out = DataOutputStream(DigestOutputStream(stream, digest))
                    out.write(Contract.FRAME_MAGIC.toByteArray(Charsets.US_ASCII))
                    out.writeInt(Contract.VERSION)
                    out.writeInt(local.size)
                    out.writeInt(watermarks.size)
                    writeRecords(out, local, Contract.MAX_LOCAL_RECORD_BYTES)
                    writeRecords(out, watermarks, Contract.MAX_STORE_JSON_BYTES)
                    out.flush()
                }
                val length = file.length()
                check(length in Contract.FRAME_HEADER_BYTES.toLong()..Contract.MAX_FRAME_BYTES.toLong())
                return PointCutoverFrame(file, length, local.size, watermarks.size, digest.digest()).also {
                    success = true
                }
            } finally {
                if (!success && file.exists()) check(file.delete()) { "Point cutover temporary file cleanup failed" }
            }
        }

        private fun writeRecords(
            out: DataOutputStream,
            records: List<DittoPointLedger.MigrationRecord>,
            maxRecordBytes: Int,
        ) {
            var previous: String? = null
            var total = 0L
            for (record in records) {
                val key = record.key
                require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' })
                require(previous == null || previous < key)
                require(record.json.length <= maxRecordBytes)
                val encoded = Charsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(record.json))
                val size = encoded.remaining()
                require(size <= maxRecordBytes)
                total += size
                require(total <= Contract.MAX_STORE_JSON_BYTES)
                val bytes = ByteArray(size)
                encoded.get(bytes)
                out.write(key.toByteArray(Charsets.US_ASCII))
                out.writeInt(size)
                out.write(bytes)
                previous = key
            }
        }
    }
}
