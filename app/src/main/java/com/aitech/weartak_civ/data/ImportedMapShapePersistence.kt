package com.aitech.weartak_civ.data

import android.content.SharedPreferences
import android.util.Log
import com.aitech.weartak_civ.cot.CotMapShape
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class ImportedMapShapeRecord(
    val uid: String,
    val title: String,
    val markerType: String,
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val sourceId: String,
    val shape: CotMapShape,
)

/** Durable catalog for user-imported shapes; CoT marker expiry remains independent. */
internal class ImportedMapShapePersistence(private val preferences: SharedPreferences) {
    private companion object {
        const val TAG = "ImportedMapShapes"
        const val CATALOG_KEY = "catalog"
        const val MAX_CATALOG_BYTES = 8 * 1024 * 1024
        const val MAX_SHAPES = 2_000
        const val MAX_CATALOG_VERTICES = 50_000
    }

    fun load(): List<ImportedMapShapeRecord> {
        return try {
            readCatalog()
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid persisted map-shape catalog", error)
            emptyList()
        }
    }

    fun loadStrict(): List<ImportedMapShapeRecord> = readCatalog()

    private fun readCatalog(): List<ImportedMapShapeRecord> {
        return try {
            val raw = preferences.getString(CATALOG_KEY, null) ?: return emptyList()
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_CATALOG_BYTES) {
                "Persisted imported shape catalog exceeds size limit"
            }
            val array = JSONObject(raw).getJSONArray("shapes")
            require(array.length() <= MAX_SHAPES) { "Persisted imported shape catalog exceeds count limit" }
            val records = (0 until array.length()).map { decode(array.getJSONObject(it)) }
            require(records.sumOf { geometryVertexCount(it.shape.geometryJson) } <= MAX_CATALOG_VERTICES) {
                "Persisted imported shape catalog exceeds vertex limit"
            }
            records
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid persisted map-shape catalog", error)
        }
    }

    fun save(shapes: Collection<ImportedMapShapeRecord>) {
        require(shapes.size <= MAX_SHAPES) { "Imported map-shape catalog exceeds $MAX_SHAPES shapes" }
        require(shapes.sumOf { geometryVertexCount(it.shape.geometryJson) } <= MAX_CATALOG_VERTICES) {
            "Imported map-shape catalog exceeds $MAX_CATALOG_VERTICES vertices"
        }
        val json = JSONObject().put("shapes", JSONArray().also { array ->
            shapes.forEach { shape -> array.put(encode(shape)) }
        }).toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_CATALOG_BYTES) {
            "Imported map-shape catalog exceeds 8 MiB"
        }
        if (preferences.getString(CATALOG_KEY, null) == json) return
        if (!preferences.edit().putString(CATALOG_KEY, json).commit()) {
            Log.e(TAG, "Unable to persist imported map shapes")
            throw IllegalStateException("Unable to persist imported map shapes")
        }
    }

    private fun encode(shape: ImportedMapShapeRecord) = JSONObject()
        .put("uid", shape.uid)
        .put("title", shape.title)
        .put("type", shape.markerType)
        .put("time", shape.timeMillis)
        .put("latitude", shape.latitude)
        .put("longitude", shape.longitude)
        .put("source", shape.sourceId)
        .put("geometry", shape.shape.geometryJson)
        .put("stroke", shape.shape.strokeColor)
        .put("fill", shape.shape.fillColor)
        .put("width", shape.shape.strokeWidth?.toDouble())

    private fun decode(json: JSONObject): ImportedMapShapeRecord {
        val geometry = json.getString("geometry")
        require(geometry.toByteArray(Charsets.UTF_8).size <= MAX_CATALOG_BYTES) {
            "Persisted imported geometry exceeds size limit"
        }
        val width = if (json.isNull("width")) null else json.getDouble("width").toFloat()
        require(width == null || width.isFinite() && width in 0.5f..20f) {
            "Invalid persisted imported stroke width"
        }
        val record = ImportedMapShapeRecord(
            uid = json.getString("uid"),
            title = json.getString("title"),
            markerType = json.getString("type"),
            timeMillis = json.getLong("time"),
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            sourceId = json.getString("source"),
            shape = CotMapShape(
                geometryJson = geometry,
                strokeColor = if (json.isNull("stroke")) null else json.getInt("stroke"),
                fillColor = if (json.isNull("fill")) null else json.getInt("fill"),
                strokeWidth = width
            )
        )
        require(record.uid.isNotBlank() && record.title.isNotBlank() &&
            record.markerType in setOf("u-d-c-c", "u-d-e", "u-d-f", "u-d-r") &&
            record.sourceId.startsWith("import:") &&
            record.latitude.isFinite() && record.latitude in -90.0..90.0 &&
            record.longitude.isFinite() && record.longitude in -180.0..180.0
        ) { "Invalid persisted imported map shape" }
        geometryVertexCount(geometry)
        return record
    }

    private fun geometryVertexCount(raw: String): Int {
        requireJsonNestingWithinLimit(raw)
        val geometry = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Invalid persisted imported geometry")
        }
        require(geometry.get("type")?.asString in
            setOf("LineString", "MultiLineString", "Polygon", "MultiPolygon")
        ) { "Unsupported persisted imported geometry" }
        val coordinates = geometry.get("coordinates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: throw IllegalArgumentException("Persisted geometry has no coordinate array")
        fun count(element: JsonElement, depth: Int = 0): Int {
            if (!element.isJsonArray) throw IllegalArgumentException("Invalid persisted coordinate")
            require(depth <= 4) { "Persisted imported geometry is too deeply nested" }
            val array: JsonArray = element.asJsonArray
            if (array.size() >= 2 && array[0].isJsonPrimitive && array[0].asJsonPrimitive.isNumber &&
                array[1].isJsonPrimitive && array[1].asJsonPrimitive.isNumber
            ) {
                require(array.size() in 2..3 && (array.size() == 2 ||
                    array[2].isJsonPrimitive && array[2].asJsonPrimitive.isNumber)
                ) { "Invalid persisted coordinate dimensions" }
                val longitude = array[0].asDouble
                val latitude = array[1].asDouble
                require(longitude.isFinite() && longitude in -180.0..180.0 &&
                    latitude.isFinite() && latitude in -90.0..90.0
                ) { "Invalid persisted coordinate" }
                if (array.size() == 3) require(array[2].asDouble.isFinite()) {
                    "Invalid persisted altitude"
                }
                return 1
            }
            require(array.size() > 0) { "Empty persisted coordinate array" }
            return array.sumOf { count(it, depth + 1) }
        }
        fun line(element: JsonElement, ring: Boolean = false): Int {
            require(element.isJsonArray) { "Invalid persisted line" }
            val positions = element.asJsonArray
            require(positions.size() >= if (ring) 4 else 2) { "Persisted line has too few positions" }
            val count = positions.sumOf { position ->
                require(position.isJsonArray) { "Invalid persisted line position" }
                count(position)
            }
            if (ring) require(positions.first() == positions.last()) {
                "Persisted polygon ring is not closed"
            }
            return count
        }
        when (geometry.get("type").asString) {
            "LineString" -> line(coordinates)
            "MultiLineString" -> coordinates.forEach { line(it) }
            "Polygon" -> coordinates.forEach { line(it, ring = true) }
            "MultiPolygon" -> coordinates.forEach { polygon ->
                require(polygon.isJsonArray) { "Invalid persisted polygon" }
                polygon.asJsonArray.forEach { line(it, ring = true) }
            }
        }
        return count(coordinates).also {
            require(it in 2..10_000) { "Invalid persisted geometry vertex count" }
        }
    }
}
