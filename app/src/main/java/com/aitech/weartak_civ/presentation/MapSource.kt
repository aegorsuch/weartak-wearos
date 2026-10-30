package com.aitech.weartak_civ.presentation

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class MapSource(
    val preferenceValue: String,
    val title: String,
    val tileTemplate: String?,
    val attribution: String
) {
    DEFAULT("default", "OSM Vector", null,
        "\u00a9 OpenStreetMap contributors / SRTM / \u00a9 OpenTopoMap (CC-BY-SA 3.0)"),
    BING_HYBRID(
        "bing-hybrid", "Bing Hybrid",
        "https://ecn.t2.tiles.virtualearth.net/tiles/h{quadkey}?g=761&mkt=en-us",
        "\u00a9 Microsoft Bing"
    ),
    GOOGLE_HYBRID(
        "google-hybrid", "Google Hybrid",
        "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
        "Google Maps"
    );

    companion object {
        val selectable = listOf(BING_HYBRID, GOOGLE_HYBRID, DEFAULT)
    }

    val maxCameraZoom: Double get() = if (this == DEFAULT) 20.0 else 22.0
}

internal data class MapCredit(val label: String, val url: String)

internal const val BUILDINGS_3D_LAYER = "buildings-3d"

internal fun mapPitchForSource(source: MapSource, tilted: Boolean): Double =
    if (source == MapSource.DEFAULT && tilted) 45.0 else 0.0

internal fun showMapPitchButton(buttonsHidden: Boolean, managerOpen: Boolean): Boolean =
    !buttonsHidden && !managerOpen

internal fun mapHiddenLayersForPitch(hidden: Set<String>, tilt: Double): Set<String> =
    if (tilt >= 22.5) hidden - BUILDINGS_3D_LAYER else hidden + BUILDINGS_3D_LAYER

internal fun mapPitchPosition(
    current: org.maplibre.compose.camera.CameraPosition,
    tilted: Boolean
): org.maplibre.compose.camera.CameraPosition = current.copy(tilt = if (tilted) 45.0 else 0.0)

internal fun withBuildingExtrusions(originalJson: String): String {
    val style = JSONObject(originalJson)
    style.getJSONObject("sources").getJSONObject("hillshade").put("maxzoom", 17)
    val layers = style.getJSONArray("layers")
    val result = JSONArray()
    var inserted = false
    for (index in 0 until layers.length()) {
        val layer = layers.getJSONObject(index)
        if (!inserted && layer.getString("type") == "symbol") {
            result.put(buildingExtrusionLayer())
            inserted = true
        }
        result.put(layer)
    }
    if (!inserted) result.put(buildingExtrusionLayer())
    return style.put("layers", result).toString()
}

private fun buildingExtrusionLayer(): JSONObject = JSONObject("""
    {
      "id": "$BUILDINGS_3D_LAYER",
      "type": "fill-extrusion",
      "source": "tak-maps",
      "source-layer": "building",
      "minzoom": 14,
      "filter": ["all",
        ["!=", ["get", "hide_3d"], true],
        [">", ["number", ["get", "render_height"], 0], 0]
      ],
      "paint": {
        "fill-extrusion-color": "#b9c3cc",
        "fill-extrusion-height": ["max", 0, ["number", ["get", "render_height"], 0]],
        "fill-extrusion-base": ["max", 0, ["min",
          ["number", ["get", "render_min_height"], 0],
          ["number", ["get", "render_height"], 0]]],
        "fill-extrusion-opacity": 0.6,
        "fill-extrusion-vertical-gradient": true
      }
    }
""".trimIndent())

internal fun mapCredits(source: MapSource): List<MapCredit> = when (source) {
    MapSource.DEFAULT -> listOf(
        MapCredit("\u00a9 OpenStreetMap contributors", "https://www.openstreetmap.org/copyright"),
        MapCredit("SRTM elevation data", "https://www.usgs.gov/centers/eros/science/usgs-eros-archive-digital-elevation-shuttle-radar-topography-mission-srtm"),
        MapCredit("\u00a9 OpenTopoMap", "https://opentopomap.org/about#verwendung"),
        MapCredit("CC BY-SA 3.0", "https://creativecommons.org/licenses/by-sa/3.0/"),
        MapCredit("MapLibre demo terrain source", "https://github.com/maplibre/demotiles"),
        MapCredit("AWS Terrain Tiles / Mapzen elevation (approximate)", "https://registry.opendata.aws/terrain-tiles/"),
        MapCredit("EGM96 geoid grid / NGA via GeographicLib", "https://geographiclib.sourceforge.io/C++/doc/geoid.html"),
        MapCredit("ArcticDEM: DigitalGlobe imagery; NSF awards 1043681, 1559691, 1542736", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("Australia terrain \u00a9 Commonwealth of Australia (Geoscience Australia) 2017", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("Austria terrain \u00a9 offene Daten \u00d6sterreichs - DGM \u00d6sterreich", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("Canada: Open Government Licence - Canada", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("EU-DEM: Copernicus data and information funded by the European Union", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("ETOPO1: U.S. NOAA; Mexico: INEGI Continental relief 2016", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("New Zealand: Copyright 2011 Crown copyright, LINZ and New Zealand Government; all rights reserved", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("Norway terrain \u00a9 Kartverket", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("UK: \u00a9 Environment Agency copyright/database right 2015; all rights reserved", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"),
        MapCredit("3DEP / NED, GMTED2010, SRTM: U.S. Geological Survey", "https://github.com/tilezen/joerd/blob/master/docs/attribution.md")
    )
    MapSource.BING_HYBRID -> listOf(
        MapCredit("\u00a9 Microsoft Bing", "https://www.bing.com/maps"),
        MapCredit("Microsoft Services Agreement", "https://www.microsoft.com/en-us/servicesagreement")
    )
    MapSource.GOOGLE_HYBRID -> listOf(
        MapCredit("Google Maps", "https://maps.google.com"),
        MapCredit("Google Maps attribution policies", "https://developers.google.com/maps/documentation/tile/policies")
    )
}

internal class MapSourcePreferences(private val preferences: SharedPreferences) {
    fun tiltedView(): Boolean = preferences.getBoolean("map_tilted_view", false)

    fun saveTiltedView(tilted: Boolean) {
        preferences.edit().putBoolean("map_tilted_view", tilted).apply()
    }

    fun load(): MapSource {
        val value = preferences.getString(KEY, MapSource.DEFAULT.preferenceValue)
        val source = MapSource.entries.firstOrNull { it.preferenceValue == value }
        if (source == null) android.util.Log.w("MapSource", "Unknown saved map source: $value")
        return source ?: MapSource.DEFAULT
    }

    fun save(source: MapSource) {
        preferences.edit().putString(KEY, source.preferenceValue).apply()
    }

    private companion object {
        const val KEY = "map_source"
    }
}

internal fun mapSourceStyle(originalJson: String, source: MapSource, retry: Int = 0): String {
    if (source == MapSource.DEFAULT && retry == 0) return originalJson
    val original = JSONObject(originalJson)
    if (source == MapSource.DEFAULT) {
        return original.put("name", "${original.optString("name")} retry $retry").toString()
    }

    // Keep glyphs for tactical labels; provider imagery replaces only the base map.
    return JSONObject()
        .put("version", 8)
        .put("name", "${source.title} experimental $retry")
        .put("glyphs", original.getString("glyphs"))
        .put("sources", JSONObject().put("hybrid", JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray().put(source.tileTemplate))
            .put("tileSize", 256)
            .put("scheme", "xyz")
            .put("minzoom", 1)
            .put("maxzoom", 20)
            .put("attribution", source.attribution)))
        .put("layers", JSONArray()
            .put(JSONObject().put("id", "background").put("type", "background")
                .put("paint", JSONObject().put("background-color", "#2a2a2a")))
            .put(JSONObject().put("id", "hybrid").put("type", "raster").put("source", "hybrid")))
        .toString()
}

internal fun isBaseMapTileFailure(source: MapSource, message: String): Boolean {
    val sourceIds = if (source == MapSource.DEFAULT) listOf("tak-maps", "hillshade", "contours")
        else listOf("hybrid")
    return message.contains("Failed to load tile") &&
        sourceIds.any { message.contains("for source $it:") }
}
