package com.aitech.weartak_civ.data.parsers

import com.aitech.weartak_civ.data.requireJsonNestingWithinLimit
import com.aitech.weartak_civ.cot.CotMapShape
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader

internal data class ImportedMapShape(
    val title: String,
    val shape: CotMapShape,
    val latitude: Double,
    val longitude: Double,
    val markerType: String,
    val warnings: List<String> = emptyList(),
)

internal object MapShapeImportParser {
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 256
    private const val MAX_FEATURES = 500
    private const val MAX_VERTICES = 10_000
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun parse(fileName: String, bytes: ByteArray): List<ImportedMapShape> {
        require(fileName.length in 1..512 && fileName.none(Char::isISOControl)) {
            "Invalid map file name"
        }
        require(bytes.size in 1..MAX_FILE_BYTES) { "Map file must be 1..8 MiB" }
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".geojson") || lower.endsWith(".json") -> parseGeoJson(bytes)
            lower.endsWith(".kml") -> parseKml(bytes)
            lower.endsWith(".kmz") || lower.endsWith(".zip") || isZip(bytes) -> parseArchive(bytes)
            else -> throw IllegalArgumentException("Supported files: KML, KMZ, GeoJSON, or zipped shapefile")
        }.also { shapes ->
            require(shapes.isNotEmpty()) { "File contains no supported line or polygon shapes" }
            require(shapes.size <= MAX_FEATURES) { "Map file contains more than $MAX_FEATURES shapes" }
        }
    }

    private fun parseGeoJson(bytes: ByteArray): List<ImportedMapShape> {
        val source = bytes.toString(Charsets.UTF_8)
        requireJsonNestingWithinLimit(source)
        val root = try {
            JsonParser.parseString(source)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Invalid GeoJSON")
        }
        require(root.isJsonObject) { "GeoJSON root must be Feature or FeatureCollection" }
        val objectRoot = root.asJsonObject
        validateGeoJsonCrs(objectRoot)
        val features = when (objectRoot.get("type")?.asString) {
        "FeatureCollection" -> objectRoot.get("features")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw IllegalArgumentException("GeoJSON FeatureCollection missing features array")
            "Feature" -> JsonArray().apply { add(root) }
            else -> throw IllegalArgumentException("GeoJSON root must be Feature or FeatureCollection")
        }
        require(features.size() in 1..MAX_FEATURES) { "GeoJSON must contain 1..$MAX_FEATURES features" }
        var vertexCount = 0
        return features.mapIndexed { index, item ->
            require(item.isJsonObject) { "GeoJSON feature ${index + 1} is not an object" }
            val feature = item.asJsonObject
            require(feature.get("type")?.asString == "Feature") {
                "GeoJSON item ${index + 1} is not a Feature"
            }
            validateGeoJsonCrs(feature)
            val geometry = feature.get("geometry")?.takeUnless(JsonElement::isJsonNull)?.asJsonObject
                ?: throw IllegalArgumentException("GeoJSON feature ${index + 1} has no geometry")
            validateGeoJsonCrs(geometry)
            val geometryType = geometry.get("type")?.asString
                ?: throw IllegalArgumentException("GeoJSON feature ${index + 1} has no geometry type")
            require(geometryType in SUPPORTED_GEOJSON_GEOMETRIES) {
                "Unsupported GeoJSON geometry '$geometryType' in feature ${index + 1}"
            }

            val coordinates = geometry.get("coordinates")
                ?: throw IllegalArgumentException("GeoJSON feature ${index + 1} has no coordinates")
            vertexCount += validateCoordinates(coordinates, geometryType)
            require(vertexCount <= MAX_VERTICES) { "Map file contains more than $MAX_VERTICES vertices" }
            val normalizedGeometry = JsonObject().apply {
                addProperty("type", geometryType)
                add("coordinates", coordinates.deepCopy())
            }
            val properties = feature.get("properties")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val title = properties?.let(::featureTitle)?.takeIf(String::isNotBlank) ?: "Shape ${index + 1}"
            val stroke = properties?.let { colorProperty(it, "stroke", "strokeColor", "line") }
            val fill = properties?.let { colorProperty(it, "fill", "fillColor") }
            val rawWidth = properties?.let { numericProperty(it, "stroke-width", "strokeWidth", "width") }
            val width = rawWidth?.toFloat()?.also {
                require(it.isFinite() && it in 0.5f..20f) { "Unsupported GeoJSON stroke width" }
            }
            shapeFromGeometry(title, normalizedGeometry, stroke, fill, width)
        }
    }

    private fun validateGeoJsonCrs(value: JsonObject) {
        val crs = value.get("crs")?.takeUnless(JsonElement::isJsonNull) ?: return
        require(crs.isJsonObject && crs.asJsonObject.get("type")?.asString == "name") {
            "Unsupported GeoJSON CRS declaration"
        }
        val name = crs.asJsonObject.getAsJsonObject("properties")?.get("name")?.asString
            ?: throw IllegalArgumentException("GeoJSON CRS declaration has no name")
        val supported = setOf(
            "EPSG:4326",
            "CRS:84",
            "urn:ogc:def:crs:EPSG::4326",
            "urn:ogc:def:crs:OGC:1.3:CRS84"
        )
        require(supported.any { it.equals(name.trim(), ignoreCase = true) }) {
            "GeoJSON CRS '$name' unsupported; only WGS84 / EPSG:4326 accepted"
        }
    }

    private fun parseKml(bytes: ByteArray): List<ImportedMapShape> {
        require(bytes.size <= MAX_ENTRY_BYTES) { "KML document is too large" }
        val xml = bytes.toString(Charsets.UTF_8)
        val document = secureXml(xml)
        val styles = mutableMapOf<String, KmlStyle>()
        val styleNodes = document.getElementsByTagName("Style")
        for (index in 0 until styleNodes.length) {
            val style = styleNodes.item(index) as? Element ?: continue
            val id = style.getAttribute("id").takeIf(String::isNotBlank) ?: continue
            styles[id] = kmlStyle(style)
        }
        val styleMaps = document.getElementsByTagName("StyleMap")
        for (index in 0 until styleMaps.length) {
            val styleMap = styleMaps.item(index) as? Element ?: continue
            val id = styleMap.getAttribute("id").takeIf(String::isNotBlank) ?: continue
            val normal = (0 until styleMap.getElementsByTagName("Pair").length)
                .mapNotNull { styleMap.getElementsByTagName("Pair").item(it) as? Element }
                .firstOrNull { it.firstChildElement("key")?.textContent?.trim() == "normal" }
                ?.firstChildElement("styleUrl")?.textContent?.trim()?.removePrefix("#")
                ?: throw IllegalArgumentException("KML StyleMap '$id' has no normal style")
            styles[id] = styles[normal] ?: throw IllegalArgumentException(
                "KML StyleMap '$id' references unsupported style '$normal'"
            )
        }
        val placemarks = document.getElementsByTagName("Placemark")
        require(placemarks.length in 1..MAX_FEATURES) {
            "KML must contain 1..$MAX_FEATURES placemarks"
        }
        val result = mutableListOf<ImportedMapShape>()
        var vertexCount = 0
        for (index in 0 until placemarks.length) {
            val placemark = placemarks.item(index) as? Element ?: continue
            val title = placemark.firstChildElement("name")?.textContent?.trim()
                ?.takeIf(String::isNotBlank) ?: "Shape ${index + 1}"
            val styleUrl = placemark.firstChildElement("styleUrl")?.textContent?.trim()
                ?.removePrefix("#")?.takeIf(String::isNotBlank)
            val inlineStyle = placemark.firstChildElement("Style")?.let(::kmlStyle)
            val style = inlineStyle ?: styleUrl?.let { styles[it] ?: throw IllegalArgumentException(
                "KML placemark '$title' references unsupported style '$it'"
            ) } ?: KmlStyle()
            val geometryNodes = descendantElements(placemark).filter {
                it.tagName == "LineString" || it.tagName == "Polygon" || it.tagName == "Point"
            }
            require(geometryNodes.isNotEmpty()) {
                "KML placemark '$title' has no supported geometry"
            }
            require(geometryNodes.none { it.tagName == "Point" }) {
                "KML point geometry is unsupported in placemark '$title'"
            }
            geometryNodes.forEachIndexed { geometryIndex, element ->
                val geometry = when (element.tagName) {
                    "LineString" -> lineStringFromKml(element)
                    "Polygon" -> polygonFromKml(element)
                    else -> throw IllegalArgumentException("Unsupported KML geometry")
                }
                vertexCount += validateCoordinates(geometry.get("coordinates"), geometry.get("type").asString)
                require(vertexCount <= MAX_VERTICES) { "Map file contains more than $MAX_VERTICES vertices" }
                result += shapeFromGeometry(
                    if (geometryNodes.size == 1) title else "$title ${geometryIndex + 1}",
                    geometry,
                    style.stroke,
                    style.fill,
                    style.width
                )
                require(result.size <= MAX_FEATURES) { "KML contains more than $MAX_FEATURES shapes" }
            }
        }
        return result
    }

    private data class KmlStyle(val stroke: Int? = null, val fill: Int? = null, val width: Float? = null)

    private fun kmlStyle(element: Element): KmlStyle {
        val line = element.firstDescendant("LineStyle")
        val poly = element.firstDescendant("PolyStyle")
        val rawWidth = line?.firstChildElement("width")?.textContent?.trim()
        val width = rawWidth?.toFloatOrNull()?.also {
            require(it.isFinite() && it in 0.5f..20f) { "Unsupported KML line width" }
        }
        val lineColor = line?.firstChildElement("color")?.textContent?.trim()
        val fillColor = poly?.firstChildElement("color")?.textContent?.trim()
        require(lineColor == null || parseKmlColor(lineColor) != null) { "Invalid KML line color" }
        require(fillColor == null || parseKmlColor(fillColor) != null) { "Invalid KML fill color" }
        val noOutline = line?.firstChildElement("outline")?.textContent?.trim() == "0"
        val noFill = poly?.firstChildElement("fill")?.textContent?.trim() == "0"
        return KmlStyle(
            stroke = if (noOutline) 0x00ffffff else lineColor?.let(::parseKmlColor),
            fill = if (noFill) 0x00ffffff else fillColor?.let(::parseKmlColor),
            width = width
        )
    }

    private fun parseKmlColor(value: String): Int? {
        if (!value.matches(Regex("[a-fA-F0-9]{8}"))) return null
        val alpha = value.substring(0, 2).toInt(16)
        val blue = value.substring(2, 4).toInt(16)
        val green = value.substring(4, 6).toInt(16)
        val red = value.substring(6, 8).toInt(16)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun lineStringFromKml(element: Element): JsonObject {
        val coordinates = parseKmlCoordinates(element.firstDescendant("coordinates")?.textContent)
        require(coordinates.size() >= 2) { "KML LineString needs at least two coordinates" }
        return geometry("LineString", coordinates)
    }

    private fun polygonFromKml(element: Element): JsonObject {
        val outer = element.firstDescendant("outerBoundaryIs")
            ?.firstDescendant("coordinates")?.textContent
            ?: throw IllegalArgumentException("KML Polygon has no outer boundary")
        val rings = JsonArray()
        val outerRing = closeRing(parseKmlCoordinates(outer))
        require(outerRing.size() >= 4) { "KML Polygon ring needs at least three vertices" }
        rings.add(outerRing)
        val boundaries = element.getElementsByTagName("innerBoundaryIs")
        for (index in 0 until boundaries.length) {
            val boundary = boundaries.item(index) as? Element ?: continue
            val coordinates = boundary.firstDescendant("coordinates")?.textContent
                ?: throw IllegalArgumentException("KML Polygon has invalid inner boundary")
            rings.add(closeRing(parseKmlCoordinates(coordinates)))
        }
        return geometry("Polygon", rings)
    }

    private fun parseKmlCoordinates(text: String?): JsonArray {
        require(!text.isNullOrBlank()) { "KML geometry has no coordinates" }
        val points = JsonArray()
        text.trim().split(Regex("\\s+")).forEach { tuple ->
            val parts = tuple.split(',')
            require(parts.size in 2..3) { "Invalid KML coordinate tuple" }
            val lon = parts[0].toDoubleOrNull()
            val lat = parts[1].toDoubleOrNull()
            require(validCoordinate(lon, lat)) { "KML coordinate is outside WGS84 bounds" }
            points.add(JsonArray().apply {
                add(lon)
                add(lat)
                if (parts.size == 3) {
                    val altitude = parts[2].toDoubleOrNull()
                    require(altitude != null && altitude.isFinite()) { "Invalid KML altitude" }
                    add(altitude)
                }
            })
        }
        require(points.size() <= MAX_VERTICES) { "KML geometry has too many vertices" }
        return points
    }

    private fun closeRing(ring: JsonArray): JsonArray {
        require(ring.size() >= 3) { "Polygon ring needs at least three vertices" }
        if (ring.first() != ring.last()) ring.add(ring.first().deepCopy())
        return ring
    }

    private fun parseArchive(bytes: ByteArray): List<ImportedMapShape> {
        val entries = linkedMapOf<String, ByteArray>()
        var expanded = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entries.size < MAX_ENTRIES) { "Archive contains too many entries" }
                val path = entry.name
                val safePath = if (entry.isDirectory) path.removeSuffix("/") else path
                require(safePath.length in 1..1024 && !safePath.startsWith('/') &&
                    path.none { it == '\\' || it == ':' || it.isISOControl() } &&
                    safePath.split('/').none { it == ".." || it == "." || it.isEmpty() }
                ) { "Archive contains unsafe entry path" }
                require(!entries.containsKey(path)) { "Archive contains duplicate entries" }
                if (!entry.isDirectory) {
                    val content = zip.readBounded(MAX_ENTRY_BYTES)
                    expanded += content.size
                    require(expanded <= MAX_EXPANDED_BYTES) { "Archive expands beyond 16 MiB" }
                    entries[path] = content
                }
                zip.closeEntry()
            }
        }
        val shapefiles = entries.entries.filter { it.key.endsWith(".shp", ignoreCase = true) }
        require(shapefiles.size <= 1) { "Archive contains multiple shapefiles" }
        val shp = shapefiles.singleOrNull()
        if (shp != null) {
            require(entries.keys.none {
                it.endsWith(".sld", ignoreCase = true) || it.endsWith(".qml", ignoreCase = true)
            }) { "Shapefile style sidecars (.sld/.qml) are unsupported; import geometry without sidecar styles" }
            return parseShapefile(shp.key, shp.value, entries)
        }
        val kmlEntries = entries.filterKeys { it.endsWith(".kml", ignoreCase = true) }
        val docEntries = kmlEntries.filterKeys { it.substringAfterLast('/').equals("doc.kml", true) }
        require(kmlEntries.size <= 1 || docEntries.size == 1) {
            "Archive contains multiple KML documents"
        }
        val kml = docEntries.values.singleOrNull() ?: kmlEntries.values.singleOrNull()
            ?: throw IllegalArgumentException("KMZ must contain one doc.kml file")
        return parseKml(kml)
    }

    private fun parseShapefile(
        shpPath: String,
        bytes: ByteArray,
        entries: Map<String, ByteArray>,
    ): List<ImportedMapShape> {
        val sibling = shpPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val base = shpPath.substringAfterLast('/').substringBeforeLast('.')
        val hasDbf = entries.keys.any { it.equals("$sibling$base.dbf", ignoreCase = true) }
        val projection = entries.entries.firstOrNull {
            it.key.equals("$sibling$base.prj", ignoreCase = true)
        }?.value?.toString(Charsets.UTF_8) ?: throw IllegalArgumentException(
            "Shapefile missing .prj coordinate reference; WGS84 projection required"
        )
        require(!Regex("\\bPROJ(?:CS|CRS)\\s*\\[", RegexOption.IGNORE_CASE).containsMatchIn(projection)) {
            "Shapefile projection unsupported; only geographic WGS84 / EPSG:4326 accepted"
        }
        require(projection.contains("WGS_1984", ignoreCase = true) ||
            projection.contains("WGS 84", ignoreCase = true) ||
            Regex("AUTHORITY\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"4326\"\\s*\\]", RegexOption.IGNORE_CASE)
                .containsMatchIn(projection)
        ) { "Shapefile projection unsupported; only WGS84 / EPSG:4326 accepted" }
        require(bytes.size >= 100) { "Shapefile header truncated" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int == 9994) {
            "Invalid shapefile header"
        }
        val declaredBytes = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(24).toLong() * 2
        require(declaredBytes == bytes.size.toLong()) { "Shapefile declared length mismatch" }
        require(header.getInt(28) == 1000) { "Unsupported shapefile version" }
        val fileShapeType = header.getInt(32)
        require(fileShapeType in setOf(3, 5)) {
            "Shapefile geometry type $fileShapeType unsupported; PolyLine and Polygon required"
        }
        val result = mutableListOf<ImportedMapShape>()
        var offset = 100
        var vertexTotal = 0
        var recordCount = 0
        while (offset < bytes.size) {
            require(++recordCount <= MAX_FEATURES) {
                "Shapefile contains more than $MAX_FEATURES records"
            }
            require(offset + 8 <= bytes.size) { "Truncated shapefile record header" }
            val recordHeader = ByteBuffer.wrap(bytes, offset, 8).slice().order(ByteOrder.BIG_ENDIAN)
            val contentBytes = recordHeader.getInt(4).toLong() * 2L
            require(contentBytes in 4..MAX_ENTRY_BYTES.toLong() &&
                offset.toLong() + 8 + contentBytes <= bytes.size
            ) { "Invalid shapefile record size" }
            val body = ByteBuffer.wrap(bytes, offset + 8, contentBytes.toInt())
                .slice().order(ByteOrder.LITTLE_ENDIAN)
            val shapeType = body.int
            require(shapeType == 0 || shapeType == fileShapeType) {
                "Mixed shapefile geometry types are unsupported"
            }
            if (shapeType != 0) {
                require(body.limit() >= 44) { "Truncated shapefile geometry" }
                val partsCount = body.getInt(36)
                val pointsCount = body.getInt(40)
                require(partsCount in 1..MAX_VERTICES && pointsCount in 2..MAX_VERTICES) {
                    "Invalid shapefile part or point count"
                }
                require(vertexTotal + pointsCount <= MAX_VERTICES) {
                    "Shapefile contains more than $MAX_VERTICES vertices"
                }
                val pointsOffset = 44L + partsCount.toLong() * 4
                require(pointsOffset + pointsCount.toLong() * 16 <= body.limit()) {
                    "Truncated shapefile coordinates"
                }
                val partStarts = (0 until partsCount).map { body.getInt(44 + it * 4) }
                require(partStarts.first() == 0 &&
                    partStarts.zipWithNext().all { (left, right) -> left < right } &&
                    partStarts.last() < pointsCount
                ) { "Invalid shapefile parts" }
                val points = (0 until pointsCount).map { index ->
                    val at = pointsOffset.toInt() + index * 16
                    val lon = body.getDouble(at)
                    val lat = body.getDouble(at + 8)
                    require(validCoordinate(lon, lat)) { "Shapefile coordinate outside WGS84 bounds" }
                    JsonArray().apply { add(lon); add(lat) }
                }
                val parts = partStarts.mapIndexed { index, start ->
                    val end = partStarts.getOrNull(index + 1) ?: pointsCount
                    JsonArray().apply { for (point in start until end) add(points[point].deepCopy()) }
                }
                val name = "$base ${result.size + 1}"
                val geometry = if (shapeType == 3) {
                    require(parts.all { it.size() >= 2 }) { "Shapefile line part has fewer than two points" }
                    if (parts.size == 1) geometry("LineString", parts.single())
                    else geometry("MultiLineString", JsonArray().apply { parts.forEach(::add) })
                } else {
                    shapefilePolygon(parts, name)
                }
                val count = validateCoordinates(geometry.get("coordinates"), geometry.get("type").asString)
                vertexTotal += count
                result += shapeFromGeometry(name, geometry).copy(
                    warnings = if (hasDbf) listOf(
                        "Shapefile DBF attributes are not imported; generated record names used"
                    ) else emptyList()
                )
                require(result.size <= MAX_FEATURES) { "Shapefile contains more than $MAX_FEATURES shapes" }
            }
            offset += 8 + contentBytes.toInt()
        }
        require(result.isNotEmpty()) { "Shapefile contains no non-empty records" }
        return result
    }

    private fun shapefilePolygon(rings: List<JsonArray>, name: String): JsonObject {
        data class Ring(val points: JsonArray, val area: Double, val firstLon: Double, val firstLat: Double)
        val classified = rings.map { source ->
            val points = closeRing(source)
            val area = (0 until points.size() - 1).sumOf { index ->
                val a = points[index].asJsonArray
                val b = points[index + 1].asJsonArray
                a[0].asDouble * b[1].asDouble - b[0].asDouble * a[1].asDouble
            }
            require(area.isFinite() && area != 0.0) { "Shapefile polygon contains degenerate ring" }
            val first = points[0].asJsonArray
            Ring(points, area, first[0].asDouble, first[1].asDouble)
        }
        val outers = classified.filter { it.area < 0 }
        val inners = classified.filter { it.area > 0 }
        require(outers.isNotEmpty()) {
            "Shapefile polygon ring orientation unsupported in '$name'"
        }
        val polygons = outers.map { outer ->
            JsonArray().apply { add(outer.points.deepCopy()) }
        }
        inners.forEach { inner ->
            val owner = outers.indexOfFirst { pointInRing(inner.firstLon, inner.firstLat, it.points) }
            require(owner >= 0) { "Shapefile polygon hole has no containing outer ring" }
            polygons[owner].add(inner.points.deepCopy())
        }
        return if (polygons.size == 1) geometry("Polygon", polygons.single())
        else geometry("MultiPolygon", JsonArray().apply {
            polygons.forEach { add(it.deepCopy()) }
        })
    }

    private fun pointInRing(longitude: Double, latitude: Double, ring: JsonArray): Boolean {
        var inside = false
        var previous = ring.size() - 1
        for (index in 0 until ring.size()) {
            val currentPoint = ring[index].asJsonArray
            val previousPoint = ring[previous].asJsonArray
            val x = currentPoint[0].asDouble
            val y = currentPoint[1].asDouble
            val px = previousPoint[0].asDouble
            val py = previousPoint[1].asDouble
            if ((y > latitude) != (py > latitude) &&
                longitude < (px - x) * (latitude - y) / (py - y) + x
            ) inside = !inside
            previous = index
        }
        return inside
    }

    private fun shapeFromGeometry(
        title: String,
        geometry: JsonObject,
        stroke: Int? = null,
        fill: Int? = null,
        width: Float? = null,
    ): ImportedMapShape {
        val first = firstCoordinate(geometry.get("coordinates"))
        val longitude = first[0].asDouble
        val latitude = first[1].asDouble
        return ImportedMapShape(
            title.take(128),
            CotMapShape(geometry.toString(), stroke, fill, width),
            latitude,
            longitude,
            if (geometry.get("type").asString == "LineString" ||
                geometry.get("type").asString == "MultiLineString"
            ) "u-d-f" else "u-d-r"
        )
    }

    private fun firstCoordinate(coordinates: JsonElement): JsonArray {
        var current = coordinates
        while (current.isJsonArray && current.asJsonArray.size() > 0 &&
            current.asJsonArray[0].isJsonArray
        ) current = current.asJsonArray[0]
        require(current.isJsonArray && current.asJsonArray.size() >= 2) {
            "Geometry has no coordinates"
        }
        return current.asJsonArray
    }

    private fun validateCoordinates(coordinates: JsonElement, type: String): Int {
        var count = 0
        fun walk(value: JsonElement, depth: Int) {
            require(depth <= 4 && value.isJsonArray) { "Invalid $type coordinates" }
            val array = value.asJsonArray
            if (array.size() >= 2 && array[0].isJsonPrimitive && array[1].isJsonPrimitive &&
                array[0].asJsonPrimitive.isNumber && array[1].asJsonPrimitive.isNumber
            ) {
                val lon = array[0].asDouble
                val lat = array[1].asDouble
                require(validCoordinate(lon, lat)) { "Geometry coordinate outside WGS84 bounds" }
                require(array.size() == 2 || (array.size() == 3 && array[2].isJsonPrimitive &&
                    array[2].asJsonPrimitive.isNumber && array[2].asDouble.isFinite())
                ) { "Only 2D and 3D coordinates are supported" }
                count++
                require(count <= MAX_VERTICES) { "Geometry has too many vertices" }
            } else {
                require(array.size() > 0) { "Empty coordinate array" }
                array.forEach { walk(it, depth + 1) }
            }
        }
        walk(coordinates, 0)
        fun positions(line: JsonElement): List<JsonElement> {
            require(line.isJsonArray) { "Invalid $type line coordinates" }
            val points = line.asJsonArray.toList()
            require(points.size >= 2 && points.all { point ->
                point.isJsonArray && point.asJsonArray.size() in 2..3 &&
                    point.asJsonArray[0].isJsonPrimitive && point.asJsonArray[1].isJsonPrimitive &&
                    point.asJsonArray[0].asJsonPrimitive.isNumber &&
                    point.asJsonArray[1].asJsonPrimitive.isNumber
            }) { "$type line needs at least two coordinate positions" }
            return points
        }
        fun validateRing(ring: JsonElement) {
            val points = positions(ring)
            val first = points.firstOrNull()?.asJsonArray
            val last = points.lastOrNull()?.asJsonArray
            val closed = first != null && last != null && first.size() == last.size() &&
                (0 until first.size()).all { first[it].asDouble == last[it].asDouble }
            require(points.size >= 4 && closed) {
                "$type polygon rings must be closed and have at least three vertices"
            }
        }
        when (type) {
            "LineString" -> positions(coordinates)
            "MultiLineString" -> coordinates.asJsonArray.forEach(::positions)
            "Polygon" -> coordinates.asJsonArray.forEach(::validateRing)
            "MultiPolygon" -> coordinates.asJsonArray.forEach { polygon ->
                polygon.asJsonArray.forEach(::validateRing)
            }
        }
        return count
    }

    private fun validCoordinate(longitude: Double?, latitude: Double?): Boolean =
        longitude != null && latitude != null && longitude.isFinite() && latitude.isFinite() &&
            longitude in -180.0..180.0 && latitude in -90.0..90.0

    private fun geometry(type: String, coordinates: JsonArray): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            add("coordinates", coordinates)
        }

    private fun featureTitle(properties: JsonObject): String? =
        listOf("title", "name", "label").firstNotNullOfOrNull { key ->
            properties.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        }

    private fun colorProperty(properties: JsonObject, vararg names: String): Int? {
        val property = names.firstNotNullOfOrNull { name ->
            properties.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        } ?: return null
        val value = property
        val hex = value.removePrefix("#")
        require(hex.matches(Regex("[a-fA-F0-9]{6}([a-fA-F0-9]{2})?"))) {
            "Unsupported GeoJSON color '$value'"
        }
        return hex.toLong(16).let { raw ->
            if (hex.length == 8) raw.toInt() else (0xff000000L or raw).toInt()
        }
    }

    private fun numericProperty(properties: JsonObject, vararg names: String): Double? =
        names.firstNotNullOfOrNull { key ->
            properties.get(key)?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive?.takeIf { it.isNumber }?.asDouble
        }

    private fun secureXml(xml: String): org.w3c.dom.Document {
        require(xml.length <= MAX_ENTRY_BYTES) { "XML document is too large" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
            setErrorHandler(org.xml.sax.helpers.DefaultHandler())
        }.parse(InputSource(StringReader(xml)))
    }

    private fun Element.firstChildElement(name: String): Element? {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == name) return node
        }
        return null
    }

    private fun Element.firstDescendant(name: String): Element? =
        getElementsByTagName(name).item(0) as? Element

    private fun descendantElements(root: Element): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(node: Node) {
            val children = node.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is Element) {
                    if (child.tagName in setOf("LineString", "Polygon", "Point")) result += child
                    visit(child)
                }
            }
        }
        visit(root)
        return result
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] in setOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte())

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(output.size() + read <= limit) { "Archive entry exceeds size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private val SUPPORTED_GEOJSON_GEOMETRIES = setOf(
        "LineString", "MultiLineString", "Polygon", "MultiPolygon"
    )
}
