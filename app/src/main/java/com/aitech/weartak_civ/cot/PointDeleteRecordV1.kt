package com.aitech.weartak_civ.cot

import android.util.JsonReader
import android.util.JsonToken
import com.weartak.plugin.sdk.point.v1.PointContractV1
import com.weartak.plugin.sdk.point.v1.PointOperationV1
import java.io.StringReader
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/** Data-only profile; this does not dispatch an operation or prove its outcome. */
internal object PointDeleteRecordV1 {
    data class Record(
        val uid: String,
        val owner: String,
        val peer: String,
        val revision: String,
        val eventTime: Long,
        val deletedAt: Long,
    )

    fun serializeLedgerRecord(ledgerJson: String, localPeer: String): ByteArray {
        val ledger = parseObject(ledgerJson)
        require(ledger.opt("deleted") == true)
        val document = ledger.optJSONObject("document")
            ?: throw IllegalArgumentException("Deleted point has no document")
        val payload = JSONObject().put("version", 1).put("document", document).toString()
        val record = parse(payload, localPeer)
        require(ledger.opt("uid") == record.uid && ledger.opt("owner") == record.owner)
        return utf8(payload).also { require(it.size <= PointContractV1.MAX_POINT_RECORD_BYTES) }
    }

    fun parse(payload: String, localPeer: String): Record {
        val root = parseObject(payload)
        require(root.keys().asSequence().toSet() == setOf("version", "document") && root.opt("version") == 1)
        val document = root.getJSONObject("document")
        require(document.opt("_r") == true && document.opt("visible") == false && document.opt("f") == false)
        val map = asMap(document)
        val decoded = DittoCotDocument.decode(map + ("_r" to false))
        require(CotMessageParser.isMarkerType(decoded.type))
        val metadata = document.getJSONObject(DittoPointLedger.METADATA)
        require(metadata.opt("version") == 1 && metadata.opt("deleted") == true &&
            metadata.opt("owner") == decoded.author && metadata.opt("revision") == decoded.fingerprint)
        val deletionNumber = metadata.opt("deletedAt") as? Number
            ?: throw IllegalArgumentException("Missing point deletion time")
        val deletion = deletionNumber.toDouble()
        require(deletion.isFinite() && deletion >= decoded.time &&
            deletion <= PointContractV1.MAX_EPOCH_MILLIS && deletion == deletion.toLong().toDouble())
        require(decoded.peer == localPeer && validText(decoded.peer))
        val record = Record(decoded.uid, requireNotNull(decoded.author), decoded.peer, decoded.fingerprint,
            decoded.time, deletion.toLong())
        val validation = PointOperationV1(PointContractV1.HOST_TO_PLUGIN, PointContractV1.DELETE,
            "validation", null, record.owner, record.uid, record.revision, record.eventTime,
            record.deletedAt, null, PointContractV1.PAYLOAD_POINT_RECORD_JSON_V1, utf8(payload).size)
        require(PointContractV1.isValidOperation(validation, true))
        return record
    }

    private fun validText(text: String): Boolean =
        text.isNotBlank() && text.length <= PointContractV1.MAX_TEXT_UTF16_UNITS &&
            text.none(Char::isISOControl) && utf8(text).size <= PointContractV1.MAX_TEXT_UTF8_BYTES

    private fun utf8(text: String): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun parseObject(json: String): JSONObject {
        require(utf8(json).size in 1..PointContractV1.MAX_POINT_RECORD_BYTES)
        var count = 0
        fun consume(reader: JsonReader, depth: Int) {
            require(depth <= 16 && ++count <= 8192) { "Point record nesting or item limit exceeded" }
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val keys = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        utf8(key)
                        require(keys.add(key)) { "Duplicate point record field" }
                        consume(reader, depth + 1)
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) consume(reader, depth + 1)
                    reader.endArray()
                }
                JsonToken.STRING -> utf8(reader.nextString())
                JsonToken.NUMBER -> require(reader.nextString().toDouble().isFinite()) { "Invalid point number" }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                else -> throw IllegalArgumentException("Invalid point record JSON")
            }
        }
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            require(reader.peek() == JsonToken.BEGIN_OBJECT)
            consume(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return JSONObject(json)
    }

    private fun asMap(value: JSONObject): Map<String, Any?> =
        value.keys().asSequence().associateWith { unwrap(value.get(it)) }

    private fun unwrap(value: Any?): Any? = when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> asMap(value)
        is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        else -> value
    }
}
