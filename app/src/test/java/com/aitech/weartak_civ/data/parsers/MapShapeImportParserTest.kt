package com.aitech.weartak_civ.data.parsers

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MapShapeImportParserTest {
    @Test
    fun importsGeoJsonPolygonsLinesAndProperties() {
        val source = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Area","stroke":"#ff0000","fill":"#7f00ff00"},
               "geometry":{"type":"Polygon","coordinates":[[[1,2],[1,3],[2,3],[1,2]]]}},
              {"type":"Feature","properties":{"title":"Path"},
               "geometry":{"type":"MultiLineString","coordinates":[[[4,5],[6,7]],[[8,9],[10,11]]]}}
            ]}
        """.trimIndent()
        val shapes = MapShapeImportParser.parse("shapes.geojson", source.toByteArray())
        assertEquals(listOf("Area", "Path"), shapes.map { it.title })
        assertEquals("Polygon", geometry(shapes[0])["type"].asString)
        assertEquals(0x7f00ff00, shapes[0].shape.fillColor)
        assertEquals("u-d-f", shapes[1].markerType)
        assertEquals("MultiLineString", geometry(shapes[1])["type"].asString)
        assertEquals(2, geometry(shapes[1])["coordinates"].asJsonArray.size())
    }

    @Test
    fun rejectsUnsupportedGeoJsonGeometryAndUnsafeCoordinates() {
        val point = """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}"""
        val invalid = """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[181,0],[0,0]]}}"""
        assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("point.geojson", point.toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("invalid.geojson", invalid.toByteArray())
        }
    }

    @Test
    fun rejectsExcessiveJsonNestingBeforeBuildingGeometryTree() {
        val nested = """{"type":"Feature","properties":{"extra":${"[".repeat(40)}0${"]".repeat(40)}},"geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]}}"""
        val error = assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("nested.geojson", nested.toByteArray())
        }
        assertTrue(error.message!!.contains("nesting"))
    }

    @Test
    fun preservesGeoJsonMultipartHolesAndRejectsVertexLimit() {
        val source = """
            {"type":"Feature","properties":{"name":"Parcel","stroke":"#123456","fill":"#7fabcdef","stroke-width":4},
             "geometry":{"type":"MultiPolygon","coordinates":[
               [[[0,0],[0,4],[4,4],[4,0],[0,0]],[[1,1],[3,1],[3,3],[1,3],[1,1]]],
               [[[10,0],[10,4],[14,4],[14,0],[10,0]]]
             ]}}
        """.trimIndent()
        val shape = MapShapeImportParser.parse("parcel.geojson", source.toByteArray()).single()
        val geometry = geometry(shape)
        assertEquals("MultiPolygon", geometry["type"].asString)
        assertEquals(2, geometry["coordinates"].asJsonArray.size())
        assertEquals(2, geometry["coordinates"].asJsonArray[0].asJsonArray.size())
        assertEquals(0xff123456.toInt(), shape.shape.strokeColor)
        assertEquals(0x7fabcdef, shape.shape.fillColor)
        assertEquals(4f, shape.shape.strokeWidth)

        val oversized = """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${(0..10_000).joinToString(",") { "[0,0]" }}]}}"""
        val error = assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("oversized.geojson", oversized.toByteArray())
        }
        assertTrue(error.message!!.contains("vertices"))
    }

    @Test
    fun rejectsExplicitUnsupportedGeoJsonCoordinateReferenceSystem() {
        val source = """
            {"type":"Feature","crs":{"type":"name","properties":{"name":"EPSG:3857"}},
             "properties":{},"geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]}}
        """.trimIndent()
        val error = assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("projected.geojson", source.toByteArray())
        }
        assertTrue(error.message!!.contains("only WGS84"))
    }

    @Test
    fun importsKmlPolygonHolesAltitudeAndStyleMap() {
        val kml = """
            <kml><Document>
              <Style id="s"><LineStyle><color>ff0000ff</color><width>3</width></LineStyle>
                <PolyStyle><color>7f00ff00</color></PolyStyle></Style>
              <StyleMap id="sm"><Pair><key>normal</key><styleUrl>#s</styleUrl></Pair></StyleMap>
              <Placemark><name>Boundary</name><styleUrl>#sm</styleUrl><Polygon>
                <outerBoundaryIs><LinearRing><coordinates>
                  -87,41,20 -86,41,20 -86,42,20 -87,41,20
                </coordinates></LinearRing></outerBoundaryIs>
                <innerBoundaryIs><LinearRing><coordinates>
                  -86.8,41.2,0 -86.8,41.4,0 -86.6,41.4,0 -86.8,41.2,0
                </coordinates></LinearRing></innerBoundaryIs>
              </Polygon></Placemark>
            </Document></kml>
        """.trimIndent()
        val shape = MapShapeImportParser.parse("areas.kml", kml.toByteArray()).single()
        val geometry = geometry(shape)
        assertEquals("Polygon", geometry["type"].asString)
        assertEquals(2, geometry["coordinates"].asJsonArray.size())
        assertEquals(3, geometry["coordinates"].asJsonArray[0].asJsonArray[0].asJsonArray.size())
        assertEquals(0xffff0000.toInt(), shape.shape.strokeColor)
        assertEquals(0x7f00ff00, shape.shape.fillColor)
        assertEquals(3f, shape.shape.strokeWidth)
    }

    @Test
    fun importsKmzAndRejectsUnsafeOrExternalXml() {
        val kml = """<kml><Placemark><name>Route</name><LineString>
            <coordinates>1,2 3,4</coordinates></LineString></Placemark></kml>"""
        val kmz = zip("nested/doc.kml" to kml.toByteArray())
        assertEquals("LineString", geometry(MapShapeImportParser.parse("route.kmz", kmz).single())["type"].asString)
        val external = """<!DOCTYPE kml [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <kml><Placemark><LineString><coordinates>&x;</coordinates></LineString></Placemark></kml>"""
        assertThrows(Exception::class.java) {
            MapShapeImportParser.parse("unsafe.kml", external.toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("unsafe.kmz", zip("../escape.kml" to kml.toByteArray()))
        }
    }

    @Test
    fun rejectsUnsupportedKmlGeometryAndExpandedArchiveLimit() {
        val point = """<kml><Placemark><Point><coordinates>1,2</coordinates></Point></Placemark></kml>"""
        val error = assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("point.kml", point.toByteArray())
        }
        assertTrue(error.message!!.contains("unsupported"))

        val archive = zip(
            "one.bin" to ByteArray(8 * 1024 * 1024),
            "two.bin" to ByteArray(8 * 1024 * 1024),
            "three.bin" to ByteArray(1)
        )
        val archiveError = assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("oversized.kmz", archive)
        }
        assertTrue(archiveError.message!!.contains("16 MiB") || archiveError.message!!.contains("8 MiB"))
    }

    @Test
    fun importsWgs84ShapefilePolygonAndRequiresSupportedProjection() {
        val shape = shapefilePolygon()
        val zipped = zip(
            "parcel.shp" to shape,
            "parcel.prj" to """GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984"]]""".toByteArray()
        )
        val imported = MapShapeImportParser.parse("parcel.zip", zipped).single()
        assertEquals("Polygon", geometry(imported)["type"].asString)
        assertEquals("u-d-r", imported.markerType)
        assertEquals(5, geometry(imported)["coordinates"].asJsonArray[0].asJsonArray.size())
        val missingProjection = zip("parcel.shp" to shape)
        assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("parcel.zip", missingProjection)
        }
        val unsupportedProjection = zip(
            "parcel.shp" to shape,
            "parcel.prj" to """PROJCS["WGS_1984_Web_Mercator",GEOGCS["GCS_WGS_1984"],AUTHORITY["EPSG","3857"]]""".toByteArray()
        )
        val projectionError = assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("parcel.zip", unsupportedProjection)
        }
        assertTrue(projectionError.message!!.contains("only geographic WGS84"))
    }

    @Test
    fun shapefileMultipartLinesAndPolygonHolesRemainMultipart() {
        val lineZip = zip(
            "paths.shp" to shapefile(3, listOf(
                listOf(0.0 to 0.0, 0.0 to 1.0),
                listOf(1.0 to 1.0, 1.0 to 0.0)
            )),
            "paths.dbf" to byteArrayOf(1, 2, 3),
            "paths.prj" to """GEOGCS["GCS_WGS_1984"]""".toByteArray()
        )
        val line = MapShapeImportParser.parse("paths.zip", lineZip).single()
        assertEquals("MultiLineString", geometry(line)["type"].asString)
        assertEquals(2, geometry(line)["coordinates"].asJsonArray.size())
        assertTrue(line.warnings.single().contains("DBF attributes are not imported"))

        val polygonZip = zip(
            "areas.shp" to shapefile(5, listOf(
                listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0, 0.0 to 0.0),
                listOf(0.2 to 0.2, 0.8 to 0.2, 0.8 to 0.8, 0.2 to 0.8, 0.2 to 0.2),
                listOf(2.0 to 0.0, 2.0 to 1.0, 3.0 to 1.0, 3.0 to 0.0, 2.0 to 0.0)
            )),
            "areas.prj" to """GEOGCS["GCS_WGS_1984"]""".toByteArray()
        )
        val polygon = geometry(MapShapeImportParser.parse("areas.zip", polygonZip).single())
        assertEquals("MultiPolygon", polygon["type"].asString)
        assertEquals(2, polygon["coordinates"].asJsonArray.size())
        assertEquals(2, polygon["coordinates"].asJsonArray[0].asJsonArray.size())
    }

    @Test
    fun reportsUnsupportedShapefileStyleSidecar() {
        val archive = zip(
            "parcel.shp" to shapefilePolygon(),
            "parcel.prj" to """GEOGCS["GCS_WGS_1984"]""".toByteArray(),
            "parcel.qml" to "<style/>".toByteArray()
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            MapShapeImportParser.parse("parcel.zip", archive)
        }
        assertTrue(error.message!!.contains("style sidecars"))
    }

    private fun geometry(shape: ImportedMapShape) =
        JsonParser.parseString(shape.shape.geometryJson).asJsonObject

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun shapefilePolygon(): ByteArray {
        return shapefile(5, listOf(
            listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0, 0.0 to 0.0)
        ))
    }

    private fun shapefile(shapeType: Int, parts: List<List<Pair<Double, Double>>>): ByteArray {
        val points = parts.flatten()
        val body = ByteBuffer.allocate(44 + parts.size * 4 + points.size * 16)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(shapeType)
                putDouble(0.0); putDouble(0.0); putDouble(1.0); putDouble(1.0)
                putInt(parts.size)
                putInt(points.size)
                var start = 0
                parts.forEach { part ->
                    putInt(start)
                    start += part.size
                }
                points.forEach { (longitude, latitude) -> putDouble(longitude); putDouble(latitude) }
            }.array()
        val totalBytes = 100 + 8 + body.size
        val header = ByteArray(100)
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 9994)
            putInt(24, totalBytes / 2)
        }
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(28, 1000)
            putInt(32, shapeType)
            putDouble(36, 0.0); putDouble(44, 0.0); putDouble(52, 1.0); putDouble(60, 1.0)
        }
        val record = ByteBuffer.allocate(8 + body.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1)
            putInt(body.size / 2)
            put(body)
        }.array()
        assertTrue(totalBytes == header.size + record.size)
        return header + record
    }
}
