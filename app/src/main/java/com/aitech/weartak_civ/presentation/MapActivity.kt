/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.InputDevice
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.CotAffiliation
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.ipc.AtosMapMarkerResult
import com.aitech.weartak_civ.ipc.toAtosMapMarkerResult
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MapActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MapActivity.Constants.brown
import com.aitech.weartak_civ.presentation.MapActivity.Constants.dark_blue
import com.aitech.weartak_civ.presentation.MapActivity.Constants.dark_green
import com.aitech.weartak_civ.presentation.MapActivity.Constants.maroon
import com.aitech.weartak_civ.presentation.MapActivity.Constants.orange
import com.aitech.weartak_civ.presentation.MapActivity.Constants.purple
import com.aitech.weartak_civ.presentation.MapActivity.Constants.teal

import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.provider.RepoLocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationPuckColors
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.material3.ScaleBar
import org.maplibre.compose.style.BaseStyle
import kotlin.math.cos
import kotlin.math.min
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlin.math.roundToInt
import kotlin.math.sin
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.presentation.MarkerActivity.Constants.markerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.aitech.weartak_civ.Utils.latLonToMgrs
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.observePassiveSaDemand
import com.aitech.weartak_civ.data.RemoteEmergencyMapItem
import androidx.compose.ui.res.stringResource
import com.aitech.weartak_civ.data.DataRepository.MarkerType
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bhRepo
import org.greenrobot.eventbus.EventBus
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.expressions.dsl.Feature.get
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.util.ClickResult
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.border
import org.json.JSONObject
import org.maplibre.spatialk.geojson.Feature.Companion.getStringProperty
import org.maplibre.compose.expressions.dsl.collator
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.SymbolZOrder
import org.maplibre.compose.layers.LineLayer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import kotlin.String
import kotlin.collections.component1
import kotlin.collections.component2
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.data.BloodhoundRepository.BloodhoundItem
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.service.BloodhoundService
import com.aitech.weartak_civ.cot.ChatDestinations
import org.maplibre.compose.expressions.dsl.asNumber
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aitech.weartak_civ.data.model.TakChannelStatus
import com.aitech.weartak_civ.data.model.TakServerChannels
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.viewmodel.MapViewModel
import com.aitech.weartak_civ.presentation.viewmodel.RoutePointDetails
import com.aitech.weartak_civ.presentation.viewmodel.RoutePointsDetailsView
import com.aitech.weartak_civ.presentation.viewmodel.RouteDetailsView
import com.aitech.weartak_civ.presentation.viewmodel.findRoutePoint
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import com.fasterxml.jackson.databind.util.CompactStringObjectMap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.oer.its.ieee1609dot2.basetypes.Latitude
import org.bouncycastle.oer.its.ieee1609dot2.basetypes.Longitude
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import java.io.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow


// MAP ACTIVITY SETUP
// Creates the map screen and loads the main MapApp composable
open class MapActivity : ComponentActivity() {

    companion object Constants {
        const val EXTRA_FOCUS_LAT = "bloodhound_focus_lat"
        const val EXTRA_FOCUS_LON = "bloodhound_focus_lon"
        const val EXTRA_FOCUS_ROUTE_SERVER = "bloodhound_focus_route_server"
        const val EXTRA_FOCUS_ROUTE_UID = "bloodhound_focus_route_uid"
        val TAG = "WearTAK-" + MapActivity::class.java.simpleName
        lateinit var mapActivity: MapActivity
        val orange = Color(0xFFFF7700)
        val maroon = Color(0xFF7F0000)
        val purple = Color(0xFF7F007F)
        val dark_blue = Color(0xFF00007F)
        val teal = Color(0xFF007F7F)
        val dark_green = Color(0xFF007F00)
        val brown = Color(0xFF964B00)
    }

    private val requestedFocus = mutableStateOf<BloodhoundMapFocus?>(null)

    private fun readFocus(intent: Intent): BloodhoundMapFocus? {
        val server = intent.getStringExtra(EXTRA_FOCUS_ROUTE_SERVER)
        val route = intent.getStringExtra(EXTRA_FOCUS_ROUTE_UID)
        if (server != null && route != null) return BloodhoundMapFocus(serverId = server, routeUid = route)
        if (!intent.hasExtra(EXTRA_FOCUS_LAT) || !intent.hasExtra(EXTRA_FOCUS_LON)) return null
        val point = com.aitech.weartak_civ.data.model.TakRoutePoint(
            intent.getDoubleExtra(EXTRA_FOCUS_LAT, Double.NaN), intent.getDoubleExtra(EXTRA_FOCUS_LON, Double.NaN))
        return point.takeIf(com.aitech.weartak_civ.data.RouteFollow::validLocation)?.let { BloodhoundMapFocus(point = it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedFocus.value = readFocus(intent)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)

            if (scroll != 0f && bezelZoomHandler?.invoke(scroll) == true) {
                return true
            }
        }

        return super.dispatchGenericMotionEvent(event)
    }

    private var bezelZoomHandler: ((Float) -> Boolean)? = null

    fun setBezelZoomHandler(handler: ((Float) -> Boolean)?) {
        bezelZoomHandler = handler
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedFocus.value = readFocus(intent)
        mapActivity = this
        Log.i(TAG, "MapActivity onCreate called")
        observePassiveSaDemand(lifecycle, (application as WearTakApplication).repo.passiveSaDemand)

        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                val context = LocalContext.current
                val mapVm: MapViewModel = viewModel ()

                val selectingChatContact = intent.getBooleanExtra(ChatContactContract.EXTRA_SELECT_CHAT_CONTACT, false)
                MapCivApp({ if (selectingChatContact) finish() else finishAndRemoveTask() }, context, mapVm, selectingChatContact, requestedFocus.value) { contact ->
                    setResult(Activity.RESULT_OK, ChatContactContract.result(contact))
                    finish()
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun MapCivApp(
    exitActivity: () -> Unit,
    context: Context,
    mapVm: MapViewModel,
    selectingChatContact: Boolean = false,
    mapFocus: BloodhoundMapFocus? = null,
    onChatContactSelected: (DirectChatContact) -> Unit = {}
) {
    val app = context.applicationContext as WearTakApplication

    val dataRepository = remember(app) { app.repo }
    val receivedUserListSetting by dataRepository
        .receivedUserList
        .collectAsStateWithLifecycle(initialValue = emptyList())

    MapUserNavigation(
        users = receivedUserListSetting,
        context = context,
        selectingChatContact = selectingChatContact,
        onChatContactSelected = onChatContactSelected,
        mapFocus = mapFocus
    ) { onUserClick ->
        MapApp(exitActivity, mapVm, selectingChatContact, mapFocus, onUserClick)
    }
}

internal fun zoomMapWithBezel(
    scroll: Float,
    overlayOpen: Boolean,
    lifecycleState: Lifecycle.State,
    changeZoom: (Double) -> Unit
): Boolean {
    if (overlayOpen || lifecycleState != Lifecycle.State.RESUMED) return false
    changeZoom(-scroll.toDouble())
    return true
}

@Composable
internal fun MapUserNavigation(
    users: List<DataRepository.ReceivedUser>,
    context: Context,
    selectingChatContact: Boolean = false,
    onChatContactSelected: (DirectChatContact) -> Unit = {},
    onBloodhoundUserSelected: (DataRepository.ReceivedUser) -> Boolean = { user ->
        startMapUserBloodhound(context, user)
    },
    mapFocus: BloodhoundMapFocus? = null,
    mapContent: @Composable ((String) -> Unit) -> Unit
) {
    val navController = rememberSwipeDismissableNavController()
    LaunchedEffect(mapFocus) {
        if (mapFocus != null) navController.popBackStack("map_screen", inclusive = false)
    }
    val currentUsers by rememberUpdatedState(users)
    val currentMapContent by rememberUpdatedState(mapContent)
    val currentOnChatContactSelected by rememberUpdatedState(onChatContactSelected)
    val currentOnBloodhoundUserSelected by rememberUpdatedState(onBloodhoundUserSelected)
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "map_screen"
    ) {
        composable("map_screen" ) {
            currentMapContent { uid ->
                navController.navigate("selected_user/${Uri.encode(uid)}") { launchSingleTop = true }
            }
        }

        composable(
            "selected_user/{uid}",
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val selectedUid = backStackEntry.arguments?.getString("uid")
            SelectedUser(navController, selectedUid, currentUsers, context,
                selectingChatContact, currentOnChatContactSelected, currentOnBloodhoundUserSelected)
        }
    }
}

// DROPPED AND RECEIVED MARKER DATA
// Converts marker data into GeoJSON features for MapLibre

@Composable
private fun EmergencyAlertsOnMap(
    alerts: List<RemoteEmergencyMapItem>,
    onClick: (String) -> Unit
) {
    if (alerts.isEmpty()) return
    val staleLabel = stringResource(R.string.remote_emergency_stale)
    val features = remember(alerts, staleLabel) {
        FeatureCollection(alerts.mapNotNull { alert ->
            val position = validMapPositionOrNull(alert.point.lat, alert.point.lon) ?: return@mapNotNull null
            Feature(
                geometry = Point(position),
                properties = JsonObject(mapOf(
                    "uid" to JsonPrimitive(alert.uid),
                    "label" to JsonPrimitive(
                        "${alert.callsign}: ${alert.category}".ellipsizeMarkerTitle(24) +
                            if (alert.isStale) " ($staleLabel)" else ""
                    )
                ))
            )
        })
    }
    val source = rememberGeoJsonSource(data = features.toSafeGeoJsonData())
    SymbolLayer(
        id = "remote-emergency-alerts",
        source = source,
        iconImage = image(painterResource(R.drawable.ic_alert_triangle)),
        iconSize = const(1.2f),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        textField = get("label").asString(),
        textSize = const(0.35.em),
        textAnchor = const(SymbolAnchor.Bottom),
        textOffset = offset(0f.em, (-1.5f).em),
        textColor = const(Color.Yellow),
        textHaloColor = const(Color.Black),
        textHaloWidth = const(2.dp),
        onClick = { clicked ->
            val feature = clicked.firstOrNull()
            if (feature == null) ClickResult.Pass else {
                onClick(feature.getStringProperty("uid").orEmpty())
                ClickResult.Consume
            }
        }
    )
}

@Composable
fun rememberMarkerFeatures(markers: List<MapMarkerItem>) =
    remember(markers) {
        FeatureCollection(
            features = markers.mapNotNull { m ->
                val position = validMapPositionOrNull(m.lat, m.lon) ?: return@mapNotNull null
                Feature(
                    geometry = Point(coordinates = position),
                    properties = JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(m.uid),
                            "selectionKey" to JsonPrimitive(m.selectionKey.orEmpty()),
                            "title" to JsonPrimitive(m.mapLabel ?: m.title.ifBlank { markerTextFromString(m.markerType) }.ellipsizeMarkerTitle()),
                            "markerType" to JsonPrimitive(m.markerType),
                            "enQueuedToBeSent" to JsonPrimitive(m.enQueuedToBeSent),
                            "cotType" to JsonPrimitive(m.cotType.name),
                            "sourceServerId" to JsonPrimitive(m.sourceServerId ?: "unattributed"))
                    )
                )
            }
        )
    }

// Shortens long marker titles so they do not cover nearby map content
fun String.ellipsizeMarkerTitle(maxLength: Int = 12): String {
    return if (length > maxLength) {
        take(maxLength) + "..."
    } else {
        this
    }
}

// Map-ready version of a dropped or received marker
data class MapMarkerItem(
    val uid: String,
    val markerType: String,
    val title: String,
    val lat: Double?,
    val lon: Double?,
    val enQueuedToBeSent: Boolean,
    val cotType: DataRepository.MarkerCotType,
    val sourceServerId: String? = null,
    val circleRadiusMeters: Double? = null,
    val mapLabel: String? = null,
    val selectionKey: String? = null
)

// Selects the gray icon used for cleared markers
@Composable
fun grayMarkerIconFromString(markerType: String): Painter {
    return markerPainter(markerVisualSpec(markerType, cleared = true))
}

// Creates MapLibre symbol layers for dropped and received markers
@Composable
internal fun MarkersOnMap(
    markers: List<MapMarkerItem>,
    collection: MarkerLayerCollection,
    onMarkerClick: (id: String, title: String) -> Unit = { _, _ -> },
    camera: CameraState,
    onSelectionKey: ((String) -> Unit)? = null,
) {

    // Skip marker layer creation when there are no markers
    if (markers.isEmpty()) return

    // Convert the marker list into a MapLibre GeoJSON source
    val fc = rememberMarkerFeatures(markers)

    // Group markers by type so each type can share an icon layer
    val typeGroups = remember(markers) { markers.filterNot {
        com.aitech.weartak_civ.cot.CotMessageParser.isGenericMapType(it.markerType)
    }.groupBy { it.markerType } }

    val source = rememberGeoJsonSource(
        data = fc.toSafeGeoJsonData()
    )
    val c = remember { collator(caseSensitive = true, diacriticSensitive = true, locale = "en") }

    GenericPointsOnMap(markers, collection, camera, onMarkerClick, onSelectionKey)
    // Create one symbol layer for each marker type
    typeGroups.keys.forEach { markerType ->
        androidx.compose.runtime.key(collection, markerType) {
        val iconRes = markerIconFromString(markerType)
        val iconResGray = grayMarkerIconFromString(markerType)
        SymbolLayer(
            id = collection.layerId(markerType),
            zOrder = const(SymbolZOrder.Source),
            sortKey = const(0),
            source = source,
            filter = eq(get("markerType").asString(),const(markerType), c),
            iconImage =
                switch(
                    condition(
                        eq(get("cotType").asString(), const("CLEAR"), c),
                        image(iconResGray)
                    ),
                    fallback = image(iconRes)
                ),
            iconOpacity = switch(
                condition(
                    get("enQueuedToBeSent").asBoolean(),
                    const(.5F)
                ),
                fallback = const(1F)
            ),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
            textAllowOverlap = const(false),
            textIgnorePlacement = const(false),
            textOptional = const(true),
            onClick = { features ->
                val f = features.firstOrNull() ?: return@SymbolLayer ClickResult.Pass
                if (onSelectionKey != null) onSelectionKey(f.getStringProperty("selectionKey").orEmpty())
                else onMarkerClick(f.getStringProperty("id").orEmpty(), f.getStringProperty("title").orEmpty())
                ClickResult.Consume
            },
            iconSize = const(1),
            textField = if (camera.position.zoom > 10.0) {
                get("title").asString()
            } else {
                const("")
            },
            textSize = const(0.35.em),
            textMaxWidth = const(4.em),
            textAnchor = const(SymbolAnchor.Bottom),
            textColor = const(Color.White),
            textHaloColor = const(Color.Black),
            textHaloWidth =const(2.dp),
            textOffset = offset(0f.em, (-1f).em),

            )
        }
    }
}

// ATOS TAG DATA
// Converts and displays ATOS tags on the map
@Composable
fun rememberATOSFeatures(markers: List<ATOSItem>) =
    remember(markers) {
        FeatureCollection(
            features = markers.mapNotNull { m ->
                val position = validMapPositionOrNull(m.lat, m.lon) ?: return@mapNotNull null
                Feature(
                    geometry = Point(coordinates = position),
                    properties = JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(m.uid),
                            "title" to JsonPrimitive(m.title),
                            "type" to JsonPrimitive(m.iconType),
                            "color" to JsonPrimitive(m.color),
                            "hae" to JsonPrimitive(m.hae),
                            "gpsOn" to JsonPrimitive((m.hae != 9999999.0).toString())
                        )
                    )
                )
            }
        )
    }

// Map-ready version of an ATOS tag
data class ATOSItem(
    val uid: String?,
    val iconType: String?,
    val title: String?,
    val lat: Double?,
    val lon: Double?,
    val color: String?,
    val hae: Double?
)

// Creates MapLibre symbol layers for ATOS tags
@Composable
fun ATOSTagsOnMap(
    tags: List<ATOSItem>,
    onClick: (id: String, title: String) -> Unit = { _, _ -> },
    camera: CameraState,
) {
    if (tags.isEmpty()) return
    val fc = rememberATOSFeatures(tags)
    val iconGroups = remember(tags) {
        tags.groupBy { tag ->
            Triple(
                tag.iconType,
                tag.color,
                tag.hae != 9999999.0
            )
        }
    }
    val source = rememberGeoJsonSource(
        data = fc.toSafeGeoJsonData()
    )
    val c = remember { collator(caseSensitive = true, diacriticSensitive = true, locale = "en") }

    iconGroups.forEach { (key, group) ->
        val type = key.first
        val colorName = key.second
        val gpsOn = key.third

        SymbolLayer(
            id = "atos-$type-$colorName-$gpsOn",
            source = source,
            filter = all(
                eq(get("type").asString(), const(type.toString()), c),
                eq(get("color").asString(), const(colorName.toString()), c),
                eq(get("gpsOn").asString(), const(gpsOn.toString()), c)
            ),
            iconImage = image(atosMarkerPainter(colorName, type, gpsOn)),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
            onClick = { features ->
                val f = features.firstOrNull() ?: return@SymbolLayer ClickResult.Pass
                onClick(f.getStringProperty("id").orEmpty(), f.getStringProperty("title").orEmpty())
                ClickResult.Pass
            },
            iconSize = const(1.2F),
            textField = if (camera.position.zoom > 10.0) {
                get("title").asString()
            } else {
                const("")
            },
            textSize = const(0.6.em),
            textAnchor = const(SymbolAnchor.Bottom),
            textColor = const(Color.White),
            textHaloColor = const(Color.Black),
            textHaloWidth =const(2.dp),
            textOffset = offset(0f.em, (-1f).em),
        )
    }
}

private fun atosMarkerColor(colorName: String?): Color {
    return when (colorName) {
        "White" -> Color(0xFFFFFFFF)
        "Yellow" -> Color(0xFFFFFF00)
        "Orange" -> Color(0xFFFFA500)
        "Magenta" -> Color(0xFFFF00FF)
        "Red" -> Color(0xFFFF0000)
        "Maroon" -> Color(0xFF800000)
        "Purple" -> Color(0xFF800080)
        "Blue" -> Color(0xFF0000FF)
        "Dark Blue" -> dark_blue
        "Cyan" -> Color(0xFF00FFFF)
        "Teal" -> Color(0xFF00B2B2)
        "Green" -> Color(0xFF00FF00)
        "Dark Green" -> Color(0xFF004C00)
        else -> Color.White
    }
}

@Composable
fun atosMarkerPainter(colorName: String?, iconType: String?, gpsOn: Boolean): Painter {
    val spec = markerVisualSpec(iconType.orEmpty(), cleared = !gpsOn, atos = true)
    val affiliation = CotAffiliation.fromType(iconType.orEmpty())
    // Friendly ATOS tags retain their configured team color and GPS-off silhouette.
    val tint = if (affiliation == CotAffiliation.FRIENDLY ||
        iconType == "a-g-G-E") atosMarkerColor(colorName) else Color(affiliation.argb)
    return if (spec.tintArgb != null) tintedPainter(spec.icon, tint) else markerPainter(spec)
}

@Composable
fun tintedPainter(@DrawableRes id: Int, color: Color): Painter {
    val basePainter = painterResource(id = id)

    return remember(basePainter, color) {
        object : Painter() {
            override val intrinsicSize = basePainter.intrinsicSize

            override fun DrawScope.onDraw() {
                with(basePainter) {
                    draw(
                        size = size,
                        colorFilter = ColorFilter.tint(color, BlendMode.SrcIn)
                    )
                }
            }
        }
    }
}

// OTHER USER DATA
// Converts and displays connected users on the map
@Composable
fun rememberOtherUsersFeatures(users: List<MapUserItem>) =
    remember(users) {
        FeatureCollection(
            features = users.mapNotNull { m ->
                val position = validMapPositionOrNull(m.lat, m.lon) ?: return@mapNotNull null
                Feature(
                    geometry = Point(coordinates = position),
                    properties = JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(m.uid),
                            "selectionKey" to JsonPrimitive(m.selectionKey.orEmpty()),
                            "title" to JsonPrimitive(m.mapLabel ?: m.title),
                            "type" to JsonPrimitive(m.type),
                            "team" to JsonPrimitive(m.team),
                            "sourceServerId" to JsonPrimitive(m.sourceServerId ?: "unattributed")),
                    )
                )
            }
        )
    }

// Map-ready version of another connected user
data class MapUserItem(
    val uid: String,
    val type: String,
    val team: String,
    val title: String,
    val lat: Double?,
    val lon: Double?,
    val role: String?,
    val sourceServerId: String? = null,
    val mapLabel: String? = null,
    val selectionKey: String? = null
)

// Creates MapLibre symbol layers for connected users
@Composable
fun UsersOnMap(
    users: List<MapUserItem>,
    onUserClick: (id: String, title: String) -> Unit = { _, _ -> },
    camera: CameraState,
    onSelectionKey: ((String) -> Unit)? = null,
) {
    // Skip user layer creation when there are no users
    if (users.isEmpty()) return

    // Convert the user list into a MapLibre GeoJSON source
    val fc = rememberOtherUsersFeatures(users)

    // Group users by type so each type can share a symbol layer
    val typeGroups = remember(users) {
        users.filterNot { CotMessageParser.isGenericMapType(it.type) }.groupBy { it.type }
    }

    val genericUsers = remember(users) {
        users.filter { CotMessageParser.isGenericMapType(it.type) && it.lat != null && it.lon != null }
            .map { MapMarkerItem(it.uid, it.type, it.title.ifBlank { it.uid }, it.lat, it.lon,
                false, DataRepository.MarkerCotType.DROP, it.sourceServerId, mapLabel = it.mapLabel,
                selectionKey = it.selectionKey) }
    }
    GenericPointsOnMap(genericUsers, MarkerLayerCollection.USERS, camera, onUserClick, onSelectionKey)
    val source = rememberGeoJsonSource(
        data = fc.toSafeGeoJsonData()
    )
    val c = remember { collator(caseSensitive = true, diacriticSensitive = true, locale = "en") }

    typeGroups.forEach { (type, group) ->
        SymbolLayer(
            id = group.first().uid,
            source = source,
            sortKey = const(0),
            zOrder = const(SymbolZOrder.Source),
            filter = eq(get("type").asString(),const(type), c),
            iconImage = if (!CotMessageParser.isUserPositionType(type) ||
                markerVisualSpec(type).tintArgb != null) image(markerIconFromString(type)) else switch(
                condition(eq(get("team").asString(), const("White"), c), image(painterResource(R.drawable.user_white))),
                condition(eq(get("team").asString(), const("Yellow"), c), image(painterResource(R.drawable.user_yellow))),
                condition(eq(get("team").asString(), const("Orange"), c), image(painterResource(R.drawable.user_orange))),
                condition(eq(get("team").asString(), const("Magenta"), c), image(painterResource(R.drawable.user_magenta))),
                condition(eq(get("team").asString(), const("Red"), c), image(painterResource(R.drawable.user_red))),
                condition(eq(get("team").asString(), const("Maroon"), c), image(painterResource(R.drawable.user_maroon))),
                condition(eq(get("team").asString(), const("Purple"), c), image(painterResource(R.drawable.user_purple))),
                condition(eq(get("team").asString(), const("Dark Blue"), c), image(painterResource(R.drawable.user_dark_blue))),
                condition(eq(get("team").asString(), const("Blue"), c), image(painterResource(R.drawable.user_blue))),
                condition(eq(get("team").asString(), const("Cyan"), c), image(painterResource(R.drawable.user_cyan))),
                condition(eq(get("team").asString(), const("Teal"), c), image(painterResource(R.drawable.user_yellow))),
                condition(eq(get("team").asString(), const("Green"), c), image(painterResource(R.drawable.user_green))),
                condition(eq(get("team").asString(), const("Dark Green"), c), image(painterResource(R.drawable.user_dark_green))),
                condition(eq(get("team").asString(), const("Brown"), c), image(painterResource(R.drawable.user_brown))),
                fallback = image(painterResource(R.drawable.user_orange)),
            ),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
            iconHaloColor = const(Color.Black),
            textAllowOverlap = const(false),
            textIgnorePlacement = const(false),
            textOptional = const(true),
            onClick = { features ->
                val f = features.firstOrNull() ?: return@SymbolLayer ClickResult.Pass
                if (onSelectionKey != null) onSelectionKey(f.getStringProperty("selectionKey").orEmpty())
                else onUserClick(f.getStringProperty("id").orEmpty(), f.getStringProperty("title").orEmpty())
                ClickResult.Consume
            },
            iconSize = const(1),
            textField = if (camera.position.zoom > 10.0) {
                get("title").asString()
            } else {
                const("")
            },
            textSize = const(0.6.em),
            textAnchor = const(SymbolAnchor.Bottom),
            textColor = const(Color.White),
            textHaloColor = const(Color.Black),
            textHaloWidth =const(2.dp),
            textOffset = offset(0f.em, (-1f).em),
        )
    }
}

// Converts a team color name into the matching Compose color
fun TeamColors(team: String) : Color {
    val teamColor =  when (team){
        "White" -> Color.White
        "Yellow" -> Color.Yellow
        "Orange" -> orange
        "Magenta" -> Color.Magenta
        "Red" -> Color.Red
        "Maroon" -> maroon
        "Purple" ->purple
        "Dark Blue" -> dark_blue
        "Blue" -> Color.Blue
        "Cyan" -> Color.Cyan
        "Teal" -> teal
        "Green" -> Color.Green
        "Dark Green" -> dark_green
        "Brown" -> brown
        else -> Color.Unspecified
    }
    return teamColor
}

// BLOODHOUND TRACKING
// Displays the active Bloodhound target and direction line
data class MapBhoundItem(
    val uid: String,
    val distance: Double?,
    val lat: Double?,
    val lon: Double?,
    val orientation: Double?,
)

// Creates the GeoJSON line between the user and Bloodhound target
@Composable
fun rememberBhoundMapItem(
    bloodhound: MapBhoundItem,
    userLat: Double?,
    userLon: Double?,
) = remember(bloodhound, userLat, userLon) {
    val user = validMapPositionOrNull(userLat, userLon)
    val target = validMapPositionOrNull(bloodhound.lat, bloodhound.lon)
    if (user == null || target == null) {
        FeatureCollection(emptyList())
    } else {
        FeatureCollection(
            Feature(
                geometry = LineString(
                    coordinates = listOf(user, target)
                ),
                properties = JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(bloodhound.uid),
                        "distance" to JsonPrimitive(bloodhound.distance),
                        "orientation" to JsonPrimitive(bloodhound.orientation)
                    )
                )
            )
        )
    }
}

// Creates the GeoJSON feature used for the Bloodhound label
@Composable
fun rememberBhoundLabelItem(
    bloodhound: MapBhoundItem,
    userLat: Double?,
    userLon: Double?,
    unitPrefs: com.aitech.weartak_civ.ui.formatter.UnitPrefs = currentUnitPrefs(),
) = remember(bloodhound, userLat, userLon, unitPrefs) {
    val user = validMapPositionOrNull(userLat, userLon)
    val target = validMapPositionOrNull(bloodhound.lat, bloodhound.lon)
    if (user == null || target == null) {
        FeatureCollection(emptyList())
    } else {
        val midLat = (user.latitude + target.latitude) / 2.0
        val midLon = (user.longitude + target.longitude) / 2.0

        val distanceText = bloodhound.distance
            ?.let { com.aitech.weartak_civ.ui.formatter.UnitFormatter.distanceKm(it, unitPrefs) }
            ?: ""

        val orientationText = bloodhound.orientation
            ?.let { com.aitech.weartak_civ.ui.formatter.UnitFormatter.trueBearing(it) }
            ?: ""
        val orientationFloat = bloodhound.orientation?.toFloat()

        FeatureCollection(
            Feature(
                geometry = Point(
                    coordinates = Position(midLon, midLat)
                ),
                properties = JsonObject(
                    mapOf(
                        "label" to JsonPrimitive("$orientationText  $distanceText"),
                        "orientation" to JsonPrimitive(orientationFloat)

                    )
                )
            )
        )
    }
}

// Draws the Bloodhound line, target, direction, and distance label
@Composable
fun BhoundMap(
    item: MapBhoundItem,
    userLat: Double?,
    userLon: Double?,
    camera: CameraState,
    ) {
    if (item.uid.isEmpty()) return

    val fc = rememberBhoundMapItem(
        bloodhound = item,
        userLat = userLat,
        userLon = userLon
    )
    val labelFc = rememberBhoundLabelItem(
        bloodhound = item,
        userLat = userLat,
        userLon = userLon
    )

    val source = rememberGeoJsonSource(
        data = fc.toSafeGeoJsonData()
    )
    val labelSource = rememberGeoJsonSource(
        data = labelFc.toSafeGeoJsonData()
    )

    LineLayer(
        id = "bhound-line-${item.uid}",
        source = source,
        color = const(Color.Green),
        width = const(2.dp)
    )

    SymbolLayer(
        id = "bhound-label-${item.uid}",
        source = labelSource,
        textField = if (camera.position.zoom > 10.0) {
            get("label").asString()
        } else {
            const("")
        },
        iconImage = image(painterResource(R.drawable.arrow)),
        iconRotate = (get("orientation").asNumber()),
        iconAnchor = const(SymbolAnchor.Center),
        iconHaloColor = const(Color.Black),
        iconHaloWidth = const(50.dp),
        textSize = const(12.sp),
        textAnchor = const(SymbolAnchor.Left),
        textOffset = offset(0.8f.em, 0f.em),
        textColor = const(Color.White),
        textHaloColor = const(Color.Black),
        textHaloWidth = const(50.dp),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true)
    )
}

data class RouteCollection(
    val serverId: String,
    val uid: String,
    val name: String,
    val color: String,
    val points: List<RouteItem>
)

data class RouteItem(
    val lat: Double?,
    val lon: Double?,
    val hasPoint: Boolean = false,
    val pointTitle: String? = null
)

fun RouteCollection.toLineString(): LineString? {
    val positions = points
        .mapNotNull { point ->
            val lat = point.lat
            val lon = point.lon

            validMapPositionOrNull(lat, lon)
        }

    return if (positions.size >= 2) {
        LineString(positions)
    } else {
        null
    }
}

fun RouteCollection.toFeature(): Feature<LineString, JsonObject?>? {
    val lineString = toLineString() ?: return null

    return Feature(
        geometry = lineString,
        properties = buildJsonObject {
            put("serverId", serverId)
            put("uid", uid)
            put("name", name)
            put("color", color)
        }
    )
}

fun List<RouteCollection>.toFeatureCollection():
        FeatureCollection<LineString, JsonObject?> {

    val features = mapNotNull { route ->
        route.toFeature()
    }

    return FeatureCollection(features)
}

fun RouteCollection.toPointFeatures(): List<Feature<Point, JsonObject?>> {
    return points.mapIndexedNotNull { index, item ->

        val lat = item.lat
        val lon = item.lon

        if (
            item.hasPoint &&
            !item.pointTitle.isNullOrBlank() &&
            lat != null &&
            lon != null
        ) {
            Feature(
                geometry = Point(Position(lon, lat)),
                properties = buildJsonObject {
                    put("serverId", serverId)
                    put("routeUid", uid)
                    put("pointIndex", index)
                    put("title", item.pointTitle ?: "")
                    put("color", color)
                }
            )
        } else {
            null
        }
    }
}

@Composable
fun RoutesLayer(
    routes: List<RouteCollection>,
    camera: CameraState,
    onPointClick: (String, String, Int) -> Unit,
    onRouteClick: (String, String) -> Unit = { _, _ -> }
) {
    if (routes.isEmpty()) return
    val featureCollection = remember(routes) {
        routes.toFeatureCollection()
    }
    val source = rememberGeoJsonSource(
        data = featureCollection.toSafeGeoJsonData()
    )

    val pointFeatures = remember(routes) {
        routes.flatMap { route ->
            route.toPointFeatures()
        }
    }
    val pointFeatureCollection = remember(pointFeatures) {
        FeatureCollection(pointFeatures)
    }

    val pointSource = rememberGeoJsonSource(
        data = pointFeatureCollection.toSafeGeoJsonData()
    )

    val c = remember { collator(caseSensitive = true, diacriticSensitive = true, locale = "en") }

    LineLayer(
        id = "routes-hit-layer",
        source = source,
        color = const(Color.Transparent),
        width = const(24.dp),
        onClick = { features ->
            val feature = features.firstOrNull() ?: return@LineLayer ClickResult.Pass
            val serverId = feature.getStringProperty("serverId").orEmpty()
            val routeUid = feature.getStringProperty("uid").orEmpty()
            if (serverId.isBlank() || routeUid.isBlank()) {
                ClickResult.Pass
            } else {
                onRouteClick(serverId, routeUid)
                ClickResult.Consume
            }
        }
    )
    LineLayer(
        id = "routes-layer",
        source = source,
        color = switch(
            //TODO: WEAR-47 - Add ability for hex coloring
            condition(eq(get("color").asString(), const("White"), c), const(Color.White)),
            condition(eq(get("color").asString(), const("Yellow"), c), const(Color.Yellow)),
            condition(eq(get("color").asString(), const("Orange"), c), const(orange)),
            condition(eq(get("color").asString(), const("Magenta"), c), const(Color.Magenta)),
            condition(eq(get("color").asString(), const("Red"), c), const(Color.Red)),
            condition(eq(get("color").asString(), const("Maroon"), c), const(maroon)),
            condition(eq(get("color").asString(), const("Purple"), c), const(purple)),
            condition(eq(get("color").asString(), const("Dark Blue"), c), const(dark_blue)),
            condition(eq(get("color").asString(), const("Blue"), c), const(Color.Blue)),
            condition(eq(get("color").asString(), const("Cyan"), c), const(Color.Cyan)),
            condition(eq(get("color").asString(), const("Teal"), c), const(teal)),
            condition(eq(get("color").asString(), const("Green"), c), const(Color.Green)),
            condition(eq(get("color").asString(), const("Dark Green"), c), const(dark_green)),
            condition(eq(get("color").asString(), const("Brown"), c), const(brown)),
            fallback = const(Color.White),
        ),
        width = const(4.dp)
    )
    SymbolLayer(
        id = "route-points-text-layer",
        source = pointSource,
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
        textOptional = const(true),
        textField = if (camera.position.zoom > 10.0) {
            get("title").asString()
        } else {
            const("")
        },
        textSize = const(0.45.em),
        textMaxWidth = const(4.em),
        textAnchor = const(SymbolAnchor.Bottom),
        textColor = const(Color.White),
        textHaloColor = const(Color.Black),
        textHaloWidth =const(2.dp),
        textOffset = offset(0f.em, (-1f).em),
    )

    org.maplibre.compose.layers.CircleLayer(
        id = "route-points-layer",
        source = pointSource,
        color = switch(
            //TODO: WEAR-47 - Add ability for hex coloring
            condition(eq(get("color").asString(), const("White"), c), const(Color.White)),
            condition(eq(get("color").asString(), const("Yellow"), c), const(Color.Yellow)),
            condition(eq(get("color").asString(), const("Orange"), c), const(orange)),
            condition(eq(get("color").asString(), const("Magenta"), c), const(Color.Magenta)),
            condition(eq(get("color").asString(), const("Red"), c), const(Color.Red)),
            condition(eq(get("color").asString(), const("Maroon"), c), const(maroon)),
            condition(eq(get("color").asString(), const("Purple"), c), const(purple)),
            condition(eq(get("color").asString(), const("Dark Blue"), c), const(dark_blue)),
            condition(eq(get("color").asString(), const("Blue"), c), const(Color.Blue)),
            condition(eq(get("color").asString(), const("Cyan"), c), const(Color.Cyan)),
            condition(eq(get("color").asString(), const("Teal"), c), const(teal)),
            condition(eq(get("color").asString(), const("Green"), c), const(Color.Green)),
            condition(eq(get("color").asString(), const("Dark Green"), c), const(dark_green)),
            condition(eq(get("color").asString(), const("Brown"), c), const(brown)),
            fallback = const(Color.White)),
        radius = if (camera.position.zoom > 10.0) {
            const(5.dp)
        } else {
            const(0.dp)
        },
        // Detect when a route point is tapped
        onClick = { features ->

            // Get the clicked route point
            val feature = features.firstOrNull()
                ?: return@CircleLayer ClickResult.Pass

            // Get the route UID
            val routeUid = feature.getStringProperty("routeUid").orEmpty()
            val serverId = feature.getStringProperty("serverId").orEmpty()

            // Get the point index from the GeoJSON properties
            val pointIndex = feature.properties
                ?.get("pointIndex")
                ?.toString()
                ?.toIntOrNull() ?: -1

            // Open the selected point
            if (serverId.isNotBlank() && routeUid.isNotBlank() && pointIndex >= 0) {
                onPointClick(serverId, routeUid, pointIndex)
                ClickResult.Consume
            } else {
                Log.w("MissionRoutes", "Route point feature is missing its server, route or point identity")
                ClickResult.Pass
            }
        }
    )
}

private val missionRouteColors = listOf(
    "Red",
    "Blue",
    "Green",
    "Orange",
    "Purple",
    "Cyan",
    "Yellow",
    "Magenta",
    "Teal"
)

private fun getMissionRouteColor(
    serverId: String,
    missionName: String?
): String {
    val missionKey = "$serverId:${missionName ?: ""}"

    val index = Math.floorMod(
        missionKey.hashCode(),
        missionRouteColors.size
    )

    return missionRouteColors[index]
}

@Composable
internal fun rememberBaseMapStyle(context: Context): BaseStyle.Json = remember(context) {
    BaseStyle.Json(context.assets.open("style.json").bufferedReader().use { it.readText() })
}

internal fun baseMapLayerIds(style: BaseStyle.Json): Set<String> {
    val layers = JSONObject(style.json).getJSONArray("layers")
    return (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }.toSet()
}

@Composable
internal fun HiddenBaseMapLayers(baseLayerIds: Set<String>, hiddenLayerIds: Set<String>) {
    baseLayerIds.filter { it in hiddenLayerIds }.forEach { layerId ->
        androidx.compose.runtime.key(layerId) {
            // Replacing just this layer preserves every other native layer and queued update.
            Anchor.Replace(layerId) {
                BackgroundLayer(
                    id = "weartak-hidden-base-$layerId", visible = false
                )
            }
        }
    }
}

// SHARED LAYER MENU COMPONENTS
// Reusable models and rows for visibility options
data class OverlayToggle(
    // Holds the label, current visibility, and action for an overlay
    val label: String,
    val visible: Boolean,
    val onToggle: () -> Unit
)

// Displays one clickable visibility option with an eye icon
@Composable
fun ToggleRow(label: String, visible: Boolean, onToggle: () -> Unit) {
    // Make the entire row clickable instead of only the icon
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 2.dp)
    ) {
        Image(
            painter = painterResource(
                if (visible)
                    R.drawable.visibility_visible
                else
                    R.drawable.visibility_invisible
            ),
            contentDescription = if (visible) "Visible" else "Hidden",
            modifier = Modifier
                .size(14.dp)
                .width(16.dp)
        )
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
fun ServerDropdown(label: String, opened: MutableState<Boolean>, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                opened.value = !opened.value
                onToggle() }
            .padding(vertical = 2.dp)
    ) {
        Image(
            painter = painterResource(
                R.drawable.tak_server_connected
            ),
            contentDescription = "Top Left Icon",
            modifier = Modifier
                .size(22.dp)
                .padding(end = 2.dp),
        )
        Text(label, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(
            imageVector =
                if (opened.value)
                    Icons.Filled.KeyboardArrowUp
                else
                    Icons.Filled.KeyboardArrowDown
            ,
            contentDescription = if (opened.value) "Opened" else "Closed",
            modifier = Modifier
                .size(14.dp)
                .width(16.dp)
        )
    }
}

// LOCATION DETAIL POPUP
// Displays coordinates and MGRS for the user location puck
@Composable
fun PopupBox(
    showPopup: Boolean,
    lat: Double,
    lon: Double,
    onClickOutside: () -> Unit,
){
    val popupWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 64.dp

    // Only create the popup while it is requested
    if (showPopup) {
        // Keep the popup above the map and other overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10F),
            contentAlignment = Alignment.Center
        ) {
            Popup(
                alignment = Alignment.Center,
                properties = PopupProperties(
                    excludeFromSystemGesture = true,
                ),
                // to dismiss on click outside
                onDismissRequest = { onClickOutside() }
            ) {
                Box(
                    Modifier
                        .width(popupWidth)
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column (modifier = Modifier.padding(5.dp)){
                        Text(
                            text = stringResource(R.string.map_self_location),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        val latitude = String.format(java.util.Locale.US, "%.5f", lat)
                        val longitude = String.format(java.util.Locale.US, "%.5f", lon)
                        val coordinateDescription = stringResource(
                            R.string.map_self_coordinates_description, latitude, longitude)
                        val coordinateStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White, fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp, textAlign = TextAlign.Center)
                        FittedTopMetricText(
                            "$latitude, $longitude",
                            modifier = Modifier.semantics { contentDescription = coordinateDescription },
                            style = coordinateStyle, minFontSize = 8.sp
                        )
                        FittedTopMetricText(
                            latLonToMgrs(lat, lon), style = coordinateStyle, minFontSize = 8.sp
                        )
                    }
                }
            }
        }
    }
}


// LAYERS MENU
// Controls map layers, overlays, teams, roles, and map controls
@Composable
fun LayerTogglePopup(
    topoLayers: List<Pair<String, String>>,
    hiddenLayers: Set<String>,
    onToggle: (String) -> Unit,
    hideControls: Boolean,
    onHideControlsToggle: () -> Unit,
    visibleUserTeams: Set<String>,
    visibleUserRoles: Set<String>,
    visibleUserRolesLEO: Set<String>,
    myRole: String,
    roleCounts: Map<String, Int>,
    teamColorCount: Map<String, Int>,
    onRoleToggle: (String) -> Unit,
    onLeoRoleToggle: (String) -> Unit,
    onTeamToggle: (String) -> Unit,
    overlays: List<OverlayToggle>,
    availableTeams : Set<String>,
    availableUserRoles : Set<String>,
    availableUserRolesLEO : Set<String>,
    onDismiss: () -> Unit
) {
    // Center the menu within the circular watch display
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss
    ) {
        Box(
            Modifier
                .width(150.dp)
                .height(165.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Color.Black.copy(alpha = 0.9f),
                    RoundedCornerShape(8.dp)
                ),
        ) {
            // Allow every option to remain reachable on the small screen
            val scrollState = rememberScrollState()

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    // Layers menu heading
                    "Layers Menu", color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    // Layers menu heading
                    "Map Buttons", color = Color.White, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                // Menu option that hides or restores the extra map controls.
                ToggleRow(
                    label = "Map Buttons",

                    // ToggleRow represents visibility, so the eye appears when
                    // the map controls are currently visible.
                    visible = !hideControls,

                    // Switch between showing and hiding the controls.
                    onToggle = onHideControlsToggle
                )

                Spacer(Modifier.height(4.dp))


                Spacer(Modifier.height(4.dp))
                Text(
                    // Layers menu heading
                    "Layers", color = Color.White, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                // style.json layers
                topoLayers.forEach { (id, label) ->
                    // A grouped option is visible when at least one of its layers is visible
                    val visible = when (id) {
                        "water" -> !listOf(
                            "water-fill",
                            "water-lines",
                            "water-outline"
                        ).all { it in hiddenLayers }

                        "roads" -> !listOf(
                            "motorway",
                            "trunk",
                            "primary",
                            "secondary",
                            "tertiary",
                            "residential",
                            "roads-bridge-casing",
                            "roads-bridge-stroke",
                            "roads-tunnel-overlay",
                            "paths",
                            "paths-tunnel"
                        ).all { it in hiddenLayers }

                        "labels" -> !listOf(
                            "place-labels",
                            "road-labels"
                        ).all { it in hiddenLayers }

                        else -> id !in hiddenLayers
                    }

                    ToggleRow(
                        label = label,
                        visible = visible,
                        onToggle = { onToggle(id) }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.height(4.dp))

                // Build the team color section using teams currently on the map
                val userTeams = listOf(
                    "White", "Yellow", "Orange", "Magenta", "Red", "Maroon",
                    "Purple", "Dark Blue", "Blue", "Cyan", "Teal",
                    "Green", "Dark Green", "Brown"
                ).filter { (it in availableTeams) && ((teamColorCount[it] ?: 0) > 0) }
                val totalOtherTeamCategories = userTeams.count { team ->
                    (teamColorCount[team] ?: 0) > 0
                }
                Text(
                    text = "Team Colors ($totalOtherTeamCategories)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                userTeams.forEach { team ->
                    val count = teamColorCount[team] ?: 0
                    val meSuffix = if (team == MainActivity.Constants.team) ", me" else ""
                    ToggleRow(

                        "$team ($count$meSuffix)",
                        visible = team in visibleUserTeams,
                        onToggle = { onTeamToggle(team) }
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Build the standard role section using roles currently on the map
                val userRoles = listOf(
                    "Forward Observer", "HQ", "K9", "Medic", "RTO", "Sniper", "Team Lead", "Team Member"
                ).filter { (it in availableUserRoles) && ((roleCounts[it] ?: 0) > 0)}
                val totalOtherDefaultRoleCategories = userRoles.count { role ->
                    (roleCounts[role] ?: 0) > 0
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Default Roles ($totalOtherDefaultRoleCategories)",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 10.sp
                )
                userRoles.forEach { role ->
                    val count = roleCounts[role] ?: 0
                    val meSuffix = if (role ==  myRole) ", me" else ""
                    ToggleRow(
                        label = "$role ($count$meSuffix)",
                        visible = role in visibleUserRoles,
                        onToggle = { onRoleToggle(role) }
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Build the law enforcement role section using roles currently on the map
                val userRolesLEO = listOf(
                    "Armed Surveillance", "Assistant Team Leader", "Aviation", "Bomb Tech", "Command Post", "Critical Response", "Hazards", "Negotiator", "Surveillance", "Tactical Communicator", "TOC"
                ).filter { (it in availableUserRolesLEO) && ((roleCounts[it] ?: 0) > 0) }
                val totalLeoRoleCategories = userRolesLEO.count { role ->
                    val count = roleCounts[role] ?: 0
                    count > 0
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "LEO / Civil Use Roles (${totalLeoRoleCategories})",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 11.sp
                )
                userRolesLEO.forEach { role ->
                    val count = roleCounts[role] ?: 0
                    val meSuffix = if (role == myRole) ", me" else ""
                    ToggleRow(
                        label = "$role ($count$meSuffix)",
                        visible = role in visibleUserRolesLEO,
                        onToggle = { onLeoRoleToggle(role) }
                    )
                }

            }
        }
    }
}

@Composable
fun LayerChannelsPopup(
    onDismiss: () -> Unit,
    mapVm: MapViewModel,
    takServers: List<TakServerChannels>
) {
    // Center the menu within the circular watch display
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss
    ) {
        Box(
            Modifier
                .width(150.dp)
                .height(165.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Color.Black.copy(alpha = 0.9f),
                    RoundedCornerShape(8.dp)
                ),
        ) {
            // Allow every option to remain reachable on the small screen
            val scrollState = rememberScrollState()

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    "Channels", color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier
                        .wrapContentHeight()
                        .heightIn(max = 200.dp)
                ) {
                    itemsIndexed(
                        takServers.alphabetizedServerEntries { it.serverName },
                        key = { _, entry -> entry.value.serverId }
                    ) { _, (_, serverItem) ->
                        val channelsVisible = remember(serverItem.serverId) { mutableStateOf(false) }
                        ServerDropdown(
                            label = serverItem.serverName,
                            opened = channelsVisible,
                            onToggle = {
                                if (channelsVisible.value) {
                                    Log.i(
                                        TAG,
                                        "Channel UI refresh serverId=${serverItem.serverId} " +
                                            "serverName=${serverItem.serverName}"
                                    )
                                    mapVm.refreshTakChannels(serverItem.serverId)
                                }
                            }
                        )
                        if (channelsVisible.value) {
                            when (serverItem.status) {
                                TakChannelStatus.LOADING -> Text(
                                    "Loading...",
                                    color = Color.LightGray,
                                    fontSize = 9.sp,
                                )
                                TakChannelStatus.EMPTY -> Text(
                                    "No channels assigned",
                                    color = Color.LightGray,
                                    fontSize = 9.sp,
                                )
                                TakChannelStatus.UNSUPPORTED -> Text(
                                    "Channels unsupported",
                                    color = Color.LightGray,
                                    fontSize = 9.sp,
                                )
                                TakChannelStatus.ERROR -> Text(
                                    serverItem.errorMessage ?: "Channel request failed",
                                    color = Color.Red,
                                    fontSize = 9.sp,
                                )
                                TakChannelStatus.READY -> Unit
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .wrapContentHeight()
                                    .heightIn(max = 200.dp)
                                    .padding(start = 10.dp)
                            ) {
                                itemsIndexed(serverItem.channels) { index, channelItem ->
                                    ToggleRow(
                                        label = if (channelItem.updating) {
                                            "${channelItem.name}..."
                                        } else {
                                            channelItem.name
                                        },
                                        visible = channelItem.active,
                                        onToggle = {
                                            if (!channelItem.updating) {
                                                Log.i(
                                                    TAG,
                                                    "Channel UI toggle serverId=${serverItem.serverId} " +
                                                        "serverName=${serverItem.serverName} " +
                                                        "channel=${channelItem.name} bitpos=${channelItem.bitPosition} " +
                                                        "previousActive=${channelItem.active} " +
                                                        "desiredActive=${!channelItem.active}"
                                                )
                                                mapVm.updateTakChannel(
                                                    serverId = serverItem.serverId,
                                                    channelBitPosition = channelItem.bitPosition,
                                                    shouldBeActive = !channelItem.active
                                                )
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// MAIN MAP SCREEN
// Connects repository data, map layers, popups, and controls
@Composable
fun MapApp(exitActivity: () -> Unit, mapVm: MapViewModel,
           selectingChatContact: Boolean = false, mapFocus: BloodhoundMapFocus? = null,
           onUserClick: (String) -> Unit) {
    // Access the application repository used by the map
    val context = LocalContext.current

    val takChannels by mapVm.takChannels.collectAsStateWithLifecycle()
    val networkSettingsLocked by mapVm.networkSettingsLocked.collectAsStateWithLifecycle()

    // Saves Layers menu settings between app sessions
    val mapPreferences = remember {
        context.getSharedPreferences(
            "map_layer_preferences",
            Context.MODE_PRIVATE
        )
    }

    val dataRepository = remember(context) {
        (context.applicationContext as WearTakApplication).repo
    }
    val takRoutes by dataRepository.takRoutes.collectAsStateWithLifecycle()

    LaunchedEffect(takRoutes) {
        Log.d(
            "MissionRoutes",
            "Route count: ${takRoutes.size}"
        )

        takRoutes.forEach { route ->
            Log.d(
                "MissionRoutes",
                "Route: ${route.name}, UID: ${route.uid}, " +
                        "Points: ${route.points.size}, " +
                        "Server: ${route.serverId}"
            )
        }
    }

    val missionMapRoutes = remember(takRoutes) {
        takRoutes.map { route ->
            RouteCollection(
                serverId = route.serverId,
                uid = route.uid,
                name = route.name,
                color = getMissionRouteColor(
                    serverId = route.serverId,
                    missionName = route.missionName
                ),
                points = route.points.map { point ->
                    RouteItem(
                        lat = point.latitude,
                        lon = point.longitude,
                        hasPoint = point.name != null,
                        pointTitle = point.name
                    )
                }
            )
        }
    }
    //
    var hiddenLayers by remember {
        mutableStateOf(mapPreferences.getStringSet(
            "hidden_layers",
            emptySet()
        )?.toSet() ?: emptySet()
        )
    }

    // Latest device location used by the puck and camera
    val locationFlow by dataRepository.latestLocation.collectAsState()
    // TODO: Will these always be not null?
    // Use zero until the first valid location is received
    val lat = locationFlow?.latitude ?: 0.0
    val lon = locationFlow?.longitude ?: 0.0

    // Initial map camera centered on the latest known location
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(lon, lat),
            zoom = 15.5,
        )
    )
    var selfRecenterRequestedNanos by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(locationFlow, selfRecenterRequestedNanos) {
        val requested = selfRecenterRequestedNanos ?: return@LaunchedEffect
        val fix = locationFlow ?: return@LaunchedEffect
        if (fix.elapsedRealtimeNanos >= requested &&
            android.os.SystemClock.elapsedRealtimeNanos() - requested <= 5_000_000_000L &&
            fix.latitude.isFinite() && fix.longitude.isFinite() && mapFocus == null
        ) {
            selfRecenterRequestedNanos = null
            cameraState.animateTo(cameraState.position.copy(target = Position(fix.longitude, fix.latitude)))
        }
    }



    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val focusPoints = mapFocus?.point?.let { listOf(it) } ?: takRoutes.firstOrNull {
        it.serverId == mapFocus?.serverId && it.uid == mapFocus?.routeUid
    }?.points
    LaunchedEffect(mapFocus, focusPoints, screen.screenWidthDp, screen.screenHeightDp) {
        if (mapFocus != null && !focusPoints.isNullOrEmpty()) {
            val frame = runCatching {
                routeCameraFrame(focusPoints, screen.screenWidthDp.toDouble(), screen.screenHeightDp.toDouble())
            }.getOrNull() ?: return@LaunchedEffect
            cameraState.animateTo(CameraPosition(
                target = Position(frame.longitude, frame.latitude), zoom = frame.zoom, bearing = 0.0, tilt = 0.0))
        }
    }

    // LAYER MENU CONFIGURATION
    // Connects each menu option to its MapLibre style layer IDs
    val layerGroups = mapOf(
        "water" to setOf("water-fill", "water-lines", "water-outline"),

        "roads" to setOf(
            "motorway",
            "trunk",
            "primary",
            "secondary",
            "tertiary",
            "residential",
            "roads-bridge-casing",
            "roads-bridge-stroke",
            "roads-tunnel-overlay",
            "paths",
            "paths-tunnel"
        ),
        "labels" to setOf(
            "place-labels",
            "road-labels"
        ),
        "land" to setOf(
            "landcover",
            "landuse",
            "landuse-railway",
            "landuse-railway-outline",
            "park"
        )
    )

    // Define the map style options shown in the Layers menu
    val topoLayers = remember {
        listOf(
            "hillshade" to "Hillshade",
            "contours" to "Contours",
            "water" to "Water",
            "roads" to "Roads",
            "labels" to "Labels"
        )
    }

    val scope = rememberCoroutineScope()

    // CAMERA ACTIONS
    // Handles zooming and recentering the map
    // Change zoom while preserving target, bearing, tilt, and padding

    fun changeZoom(delta: Double) {
        scope.launch {
            val current = cameraState.position
            val newZoom = (current.zoom + delta).coerceIn(0.0, 25.5)

            cameraState.animateTo(
                CameraPosition(
                    target = current.target,
                    zoom = newZoom,
                    bearing = current.bearing,
                    tilt = current.tilt,
                    padding = current.padding
                )
            )
        }
    }

    // Recenter the camera on the latest device location
    fun snapToSelf() {
        selfRecenterRequestedNanos = android.os.SystemClock.elapsedRealtimeNanos()
        dataRepository.requestInteractionPliRefresh(PliRefreshReason.MAP_RECENTERED)
        // Stop when the device does not have a valid location
        val currentLocation = locationFlow ?: return

        scope.launch {
            val current = cameraState.position

            cameraState.animateTo(
                CameraPosition(
                    target = Position(
                        longitude = currentLocation.longitude,
                        latitude = currentLocation.latitude
                    ),
                    zoom = 15.5,
                    bearing = current.bearing,
                    tilt = current.tilt
                )
            )
        }
    }

    val myRole by dataRepository.getRole.collectAsStateWithLifecycle(initialValue = "")
    val markerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val locationProvider = remember(scope) {
        RepoLocationProvider(dataRepository.latestLocation, scope)
    }
    val userLocationState = rememberUserLocationState(locationProvider)
    // Show the coordinate popup for the user location puck
    var showPopup by remember { mutableStateOf(false) }
    var selectedRoutePoint by remember {mutableStateOf<RoutePointDetails?>(null)}
    var selectedRoute by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Show or hide connected users
    var showUsers by remember { mutableStateOf(mapPreferences.getBoolean(
        "show_Users",
        true
            )
        )
    }

    // Show or hide the Layers menu
    var showLayerToggle by remember { mutableStateOf(false) }

    var showChannelToggle by remember {mutableStateOf(false)}

    LaunchedEffect(networkSettingsLocked) {
        if (networkSettingsLocked) showChannelToggle = false
    }

    // Hide zoom, snap-to-self, and the scale while keeping Layers and Back
    var hideControls by remember { mutableStateOf(mapPreferences.getBoolean(
        "hide_controls",
        false
            )
        )
    }

    // Show or hide dropped and received markers
    var showMarkers by remember { mutableStateOf(mapPreferences.getBoolean(
        "show_markers",
        true
            )
        )
    }

    // Show or hide ATOS tags
    var showAtosTags by remember { mutableStateOf(mapPreferences.getBoolean(
        "show_Atos_Tags",
        true
            )
        )
    }

    // Default team selections used when no saved setting exists
    val defaultVisibleTeams = setOf(
        "White", "Yellow", "Orange", "Magenta", "Red", "Maroon",
        "Purple", "Dark Blue", "Blue", "Cyan", "Teal",
        "Green", "Dark Green", "Brown"
    )

    // Loads the last selected team filters
    var visibleUserTeams by remember {
        mutableStateOf(
            mapPreferences.getStringSet(
                "visible_user_teams",
                defaultVisibleTeams
            )?.toSet() ?: defaultVisibleTeams
        )
    }
//    // Team colors currently selected for display
//    var visibleUserTeams by remember {
//        mutableStateOf(
//            setOf(
//                "White", "Yellow", "Orange", "Magenta", "Red", "Maroon",
//                "Purple", "Dark Blue", "Blue", "Cyan", "Teal",
//                "Green", "Dark Green", "Brown"
//            )
//        )
//    }

    val userRolePreferences = remember(mapPreferences) { MapUserRolePreferences(mapPreferences) }
    var visibleUserRoles by remember {
        mutableStateOf(userRolePreferences.standardRoles())
    }
    var visibleUserRolesLEO by remember {
        mutableStateOf(userRolePreferences.leoRoles())
    }
    val projection = cameraState.projection

    // LOCATION PUCK POPUP POSITION
    // Converts the map location into a screen position for the popup
    // Recalculate when the location, projection, or camera changes
    val puckScreenOffset: DpOffset? = remember(
        locationFlow,
        projection,
        cameraState.position
    ) {
        val loc = locationFlow
        val proj = projection

        if (loc != null && proj != null) {
            proj.screenLocationFromPosition(
                Position(
                    latitude = loc.latitude,
                    longitude = loc.longitude
                )
            )
        } else {
            null
        }
    }

    // REPOSITORY MAP DATA
    // Collects markers, users, ATOS tags, and Bloodhound data

    // Markers created locally by the current user
    val droppedMarkerListSetting by dataRepository
        .droppedMarkerList
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Markers received from other users
    val receivedMarkerListSetting by dataRepository
        .receivedMarkerList
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // ATOS tags received from the repository
    val receivedAtosTagsSetting by dataRepository.receivedAtosTagList.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )

    // Connected users received from the repository
    val receivedUserListSetting by dataRepository
        .receivedUserList
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val selectRoutePoint: (String, String, Int) -> Unit = { serverId, routeUid, index ->
        val point = findRoutePoint(takRoutes, serverId, routeUid, index)
        if (point != null) {
            selectedRoutePoint = point.copy(
                droppedBy = point.droppedBy?.let { senderUid ->
                    receivedUserListSetting.firstOrNull {
                        it.uid == senderUid && it.sourceServerId == serverId
                    }?.title ?: senderUid
                }
            )
        } else {
            Log.w(TAG, "Selected route point is no longer available")
        }
    }
    val bhRepo = remember(context) {
        (context.applicationContext as WearTakApplication).bhRepo
    }

    // Active Bloodhound tracking data
    val activeItem by bhRepo.activeBhItemMap.collectAsStateWithLifecycle(initialValue = null)
    val remoteEmergencyMapItems by bhRepo.remoteEmergencyMapItems.collectAsStateWithLifecycle()


    // MAP DATA CONVERSION
    // Converts repository objects into map rendering models

    // Convert locally dropped markers into map marker items
    val droppedMapMarkers = remember(droppedMarkerListSetting) {
        buildList {
            addAll(
                droppedMarkerListSetting.map { marker ->
                    Log.d(
                        TAG,
                        "raw dropped marker lat=${marker.location?.lat}, lon=${marker.location?.lon}"
                    )
                    MapMarkerItem(
                        uid = marker.uid,
                        markerType = marker.markerType.type,
                        title = marker.title,
                        lat = marker.location?.lat,
                        lon = marker.location?.lon,
                        marker.enqueuedToBeSent,
                        marker.cotType
                    )
                }
            )
        }
    }

    // Convert received markers and skip markers without a location
    val receivedMapMarkers = remember(receivedMarkerListSetting) {
        Log.i(
            TAG,
            "Map render marker snapshot count=${receivedMarkerListSetting.size} " +
                "items=${receivedMarkerListSetting.take(20).map { "${it.uid}@${it.sourceServerId}" }}"
        )
        buildList {
            addAll(
                receivedMarkerListSetting.mapNotNull { marker ->
                    val location = marker.location
                    if (location == null) {
                        Log.w(
                            TAG,
                            "Map render marker skipped reason=no-location uid=${marker.uid} " +
                                "sourceServerId=${marker.sourceServerId}"
                        )
                        return@mapNotNull null
                    }
                    Log.d(
                        TAG,
                        "Map render marker created uid=${marker.uid} sourceServerId=${marker.sourceServerId} " +
                            "sourceAddress=${marker.source} eventTime=${marker.timeReceived} " +
                            "receivedAt=${marker.receivedAt} lat=${location.lat} lon=${location.lon} " +
                            "channelOwnership=unavailable"
                    )

                    MapMarkerItem(
                        uid = marker.uid,
                        markerType = marker.markerType,
                        title = marker.title,
                        lat = location.lat,
                        lon = location.lon,
                        false,
                        //TODO: Is this okay to have here?
                        DataRepository.MarkerCotType.DROP,
                        sourceServerId = marker.sourceServerId,
                        circleRadiusMeters = marker.circleRadiusMeters

                    )
                }
            )
        }
    }

    // Parse received ATOS tags and skip invalid results
    val receivedAtosTags = remember(receivedAtosTagsSetting) {
        buildList {
            addAll(
                receivedAtosTagsSetting.mapNotNull { tag ->
                    when (val markerResult = tag.toAtosMapMarkerResult()) {
                        is AtosMapMarkerResult.Success -> {
                            val marker = markerResult.marker
                            ATOSItem(
                                uid = marker.uid,
                                iconType = marker.iconCotType,
                                title = marker.title ?: marker.uid,
                                lat = marker.lat,
                                lon = marker.lon,
                                color = marker.color,
                                hae = marker.hae
                            )
                        }

                        is AtosMapMarkerResult.Failure -> {
                            Log.w(
                                TAG,
                                "ATOS local map render skipped uid=${tag.uid ?: "null"} type=${tag.type ?: "null"} tagType=${tag.atosDetail?.tagType ?: "null"} lat=${tag.point?.lat ?: "null"} lon=${tag.point?.lon ?: "null"} hae=${tag.point?.hae ?: "null"} reason=${markerResult.reason} warnings=${
                                    tag.warnings.joinToString(
                                        separator = " | "
                                    )
                                }"
                            )
                            null
                        }
                    }
                }
            )
        }
    }

    // Track ATOS IDs so the same contact is not rendered twice
    val renderedAtosUids = remember(receivedAtosTags) {
        receivedAtosTags.mapNotNull { it.uid }.toSet()
    }

    // Convert connected users while excluding contacts already rendered as ATOS
    val receivedMapUsers = remember(receivedUserListSetting, renderedAtosUids) {
        Log.i(
            TAG,
            "Map render user snapshot count=${receivedUserListSetting.size} " +
                "items=${receivedUserListSetting.take(20).map { "${it.uid}@${it.sourceServerId}" }} " +
                "renderedAtosUids=${renderedAtosUids.take(20)}"
        )
        buildList {
            addAll(
                receivedUserListSetting.mapNotNull { user ->
                    if (user.uid in renderedAtosUids) {
                        Log.d(
                            TAG,
                            "Map render user suppressed reason=ATOS uid=${user.uid} " +
                                "sourceServerId=${user.sourceServerId}"
                        )
                        return@mapNotNull null
                    }
                    val location = user.location
                    if (location == null) {
                        Log.w(
                            TAG,
                            "Map render user skipped reason=no-location uid=${user.uid} " +
                                "sourceServerId=${user.sourceServerId}"
                        )
                        return@mapNotNull null
                    }
                    Log.d(
                        TAG,
                        "Map render user created uid=${user.uid} sourceServerId=${user.sourceServerId} " +
                            "sourceAddress=${user.source} eventTime=${user.timeReceived} " +
                            "receivedAt=${user.receivedAt} team=${user.team} role=${user.role} " +
                            "lat=${location.lat} lon=${location.lon} channelOwnership=unavailable"
                    )
                    MapUserItem(
                        uid = user.uid,
                        type = user.type,
                        team = user.team,
                        title = user.title,
                        lat = location.lat,
                        lon = location.lon,
                        role = user.role,
                        sourceServerId = user.sourceServerId
                    )
                }
            )
        }
    }
    LaunchedEffect(receivedMapUsers) {
    }

    // TEAM AND ROLE FILTERING
    // Builds the options displayed in the Layers menu

    // Teams currently present on the map
    val presentTeams = remember(receivedMapUsers, team) {
        buildSet {
            receivedMapUsers
                .map { it.team }
                .filter { it.isNotBlank() }
                .forEach { add(it) }

            if (team.isNotBlank()) {
                add(team)
            }
        }
    }

    // Number of connected users assigned to each role
    val roleCounts = remember(receivedMapUsers) {
        receivedMapUsers
            .mapNotNull { it.role }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()

    }

    // Number of connected users assigned to each role
    val teamColorCount = remember(receivedMapUsers) {
        receivedMapUsers
            .map { it.team }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()

    }

    // Standard roles displayed in the normal role section
    val defaultRoleNames = MapUserRolePreferences.STANDARD_ROLES
    val leoRoleNames = MapUserRolePreferences.LEO_ROLES

    val presentUserRoles = remember(roleCounts, myRole) {
        roleCounts.keys.filter { it in defaultRoleNames }.toSet() +
                if (myRole in defaultRoleNames) setOf(myRole) else emptySet()
    }

    val presentUserRolesLEO = remember(roleCounts, myRole) {
        roleCounts.keys
            .filter { it in leoRoleNames }
            .toMutableSet()
            .apply {
                if (myRole in leoRoleNames) add(myRole)
            }
    }

/*
    Log.d(TAG, receivedMapUsers.toString())
*/

    val activeBloodhound = remember(activeItem) {
        MapBhoundItem(
            uid = activeItem?.uid ?: "",
            distance = activeItem?.vector?.distance,
            lat = activeItem?.bhEvent?.mapItem?.cotEvent?.point?.lat,
            lon = activeItem?.bhEvent?.mapItem?.cotEvent?.point?.lon,
            orientation = activeItem?.vector?.bearing,
        )
    }

    // MAP LIFECYCLE
    // Requests a fresh location when the map returns to the screen
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.i(TAG, "MapApp Lifecycle.Event.ON_RESUME called")
                selfRecenterRequestedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                dataRepository.requestInteractionPliRefresh(PliRefreshReason.MAP_OPENED)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val visibleMapUsers = if (showUsers || selectingChatContact) receivedMapUsers.filter {
        if (selectingChatContact) {
            it.uid.isNotBlank() && it.uid != MainActivity.device_uid &&
                it.uid != ChatDestinations.ALL_CHAT_ROOMS && it.title.isNotBlank() &&
                receivedUserListSetting.any { user -> user.uid == it.uid && !user.chatEndpoint.isNullOrBlank() }
        } else {
            CotMessageParser.isGenericMapType(it.type) || (it.team in visibleUserTeams &&
                (it.role in visibleUserRoles || it.role in visibleUserRolesLEO))
        }
    } else emptyList()
    val visibleDropped = if (showMarkers) droppedMapMarkers else emptyList()
    val visibleReceived = if (showMarkers) receivedMapMarkers else emptyList()
    val selectableMapTargets = remember(visibleMapUsers, visibleDropped, visibleReceived) {
        buildList {
            visibleMapUsers.forEach {
                validMapPositionOrNull(it.lat, it.lon)?.let { point ->
                    add(MapSelectionTarget(it.uid, it.title, MapTargetKind.CONTACT, point.latitude, point.longitude,
                        it.sourceServerId))
                }
            }
            fun addMarkers(markers: List<MapMarkerItem>, kind: MapTargetKind) {
                markers.filter { kind != MapTargetKind.DROPPED || it.cotType != DataRepository.MarkerCotType.CLEAR }.forEach {
                    validMapPositionOrNull(it.lat, it.lon)?.let { point ->
                        add(MapSelectionTarget(it.uid, it.title.ifBlank { markerTextFromString(it.markerType) },
                            kind, point.latitude, point.longitude, it.sourceServerId))
                    }
                }
            }
            addMarkers(visibleDropped, MapTargetKind.DROPPED)
            addMarkers(visibleReceived, MapTargetKind.RECEIVED)
        }
    }
    val targetGroups = remember(selectableMapTargets, projection, cameraState.position) {
        groupMapTargets(selectableMapTargets) { latitude, longitude ->
            projection?.screenLocationFromPosition(Position(longitude, latitude))?.let {
                MapTargetScreenPoint(it.x.value.toDouble(), it.y.value.toDouble())
            }
        }
    }
    val groupsByTarget = targetGroups.flatMap { group -> group.map { it.key to group } }.toMap()
    val targetLabels = buildMap {
        targetGroups.filter { it.size > 1 }.forEach { group ->
            group.forEachIndexed { index, target ->
                put(target.key, if (index == 0)
                    context.resources.getQuantityString(R.plurals.map_target_count, group.size, group.size) else "")
            }
        }
    }
    var selectedMapTargetKeys by remember { mutableStateOf<Set<String>?>(null) }

    val activity = context as? MapActivity
    val mapOverlayOpen by rememberUpdatedState(
        showLayerToggle || showChannelToggle || showPopup ||
            selectedMapTargetKeys != null || selectedRoute != null || selectedRoutePoint != null
    )
    DisposableEffect(activity, lifecycleOwner) {
        activity?.setBezelZoomHandler { scroll ->
            // A retained map destination must not consume input on the selected-user screen.
            zoomMapWithBezel(scroll, mapOverlayOpen, lifecycleOwner.lifecycle.currentState, ::changeZoom)
        }
        onDispose {
            activity?.setBezelZoomHandler(null)
        }
    }

    fun openMapTarget(target: MapSelectionTarget) {
        if (target.kind == MapTargetKind.CONTACT) {
            onUserClick(target.uid)
            return
        }
        val dropped = target.kind == MapTargetKind.DROPPED
        val index = if (dropped) droppedMarkerListSetting.indexOfFirst {
            it.uid == target.uid && it.cotType != DataRepository.MarkerCotType.CLEAR
        } else receivedMarkerListSetting.indexOfFirst {
            it.uid == target.uid && it.sourceServerId == target.sourceServerId
        }
        if (index < 0) {
            Log.w(TAG, "Selected map target is no longer available")
            return
        }
        context.startActivity(Intent(context, MarkerActivity::class.java).apply {
            putExtra("marker_index", index)
            putExtra("start_route", if (dropped) "edit_marker_screen/$index" else "received_marker_detail/$index")
            putExtra("from_maps", true)
        })
    }
    fun selectMapTarget(key: String) {
        val target = selectableMapTargets.firstOrNull { it.key == key }
        if (target == null) {
            Log.w(TAG, "Clicked map target is no longer available")
            return
        }
        val group = groupsByTarget[target.key].orEmpty()
        if (group.size > 1) selectedMapTargetKeys = group.map { it.key }.toSet()
        else openMapTarget(target)
    }

// MAP RENDERING
// Draws the map and all map content
    Box(Modifier.fillMaxSize()) {

        // Stores where the user long pressed the map
        var pointerOffset by remember {
            mutableStateOf<DpOffset?>(null)
        }

        val baseStyle = rememberBaseMapStyle(context)
        val baseLayerIds = remember(baseStyle) { baseMapLayerIds(baseStyle) }

        // Main MapLibre map
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),

            baseStyle = baseStyle,

            // Controls the map camera position and zoom
            cameraState = cameraState,

            // Disables MapLibre controls and prevents map rotation
            options = MapOptions(
                ornamentOptions = OrnamentOptions.AllDisabled,
                gestureOptions = GestureOptions.RotationLocked
            ),

            // Keeps zoom within the supported range
            zoomRange = 0f..25.5f,

            onMapClick = { _, offset ->
                val mapProjection = cameraState.projection
                val hit = mapProjection?.let { map ->
                    findRouteMapSelection(
                        takRoutes,
                        RouteScreenPoint(offset.x.value.toDouble(), offset.y.value.toDouble()),
                        pointsVisible = cameraState.position.zoom > 10.0
                    ) { latitude, longitude ->
                        val screen = map.screenLocationFromPosition(Position(longitude, latitude))
                        RouteScreenPoint(screen.x.value.toDouble(), screen.y.value.toDouble())
                    }
                }
                if (hit == null) {
                    ClickResult.Pass
                } else {
                    selectedRoute = hit.serverId to hit.routeUid
                    selectedRoutePoint = null
                    hit.pointIndex?.let { selectRoutePoint(hit.serverId, hit.routeUid, it) }
                    ClickResult.Consume
                }
            },

            // Drops a marker when the user long presses the map
            onMapLongClick = { coordinates, offset ->

                // Vibrates the watch to confirm the long press
                Utils.vibrateWatch(
                    vibDurationMs = 100L,
                    numberOfVibes = 1,
                    timeBtwnVibesMs = 10L
                )

                // Saves the selected screen position
                pointerOffset = offset

                // Uses the current time as the marker timestamp
                val time = System.currentTimeMillis()

                Log.d(TAG, "LongClick Map before launch")

                // Sends the marker request in the background
                markerScope.launch {
                    try {
                        Log.d(TAG, "LongClick coroutine started")
                        Log.d(
                            TAG,
                            "About to call requestMarkerDrop type=$markerType time=$time coord=$coordinates"
                        )

                        // Creates a marker at the selected coordinates
                        dataRepository.requestMarkerDrop(
                            markerType,
                            "",
                            "",
                            time,
                            lat = coordinates.latitude,
                            lon = coordinates.longitude
                        )

                        Log.d(TAG, "requestMarkerDrop finished")
                    } catch (e: Exception) {
                        Log.e(TAG, "requestMarkerDrop failed", e)
                    }
                }

                // Allows MapLibre to continue handling the click
                ClickResult.Pass
            }
        ) {
            HiddenBaseMapLayers(baseLayerIds, hiddenLayers)

            // Displays the location puck when a location is available
            // Guard on the same state LocationPuck reads: it builds an empty FeatureCollection
            // for a null location, which crashes GeoJSON serialization (no/invalid GPS indoors).
            if (userLocationState.location != null) {
                LocationPuck(
                    idPrefix = "me",
                    locationState = userLocationState,
                    cameraState = cameraState,

                    // Uses the current team color for the location puck
                    colors = LocationPuckColors(
                        dotFillColorCurrentLocation = TeamColors(teamValue)
                    ),

                    // Recenters the map and opens location details
                    onClick = {
                        scope.launch {
                            val current = cameraState.position

                            // Moves the camera to the current location
                            cameraState.animateTo(
                                CameraPosition(
                                    target = Position(
                                        latitude = lat,
                                        longitude = lon
                                    ),

                                    // Keeps the current zoom and map orientation
                                    zoom = current.zoom,
                                    bearing = current.bearing,
                                    tilt = current.tilt,

                                    // Shifts the location left to make room for the popup
                                    padding = PaddingValues(
                                        start = 0.dp,
                                        top = 0.dp,
                                        end = 84.dp,
                                        bottom = 30.dp
                                    )
                                )
                            )

                            // Opens the location detail popup
                            showPopup = true
                        }
                    }
                )
            }

            // Emergencies remain visible independently of ordinary marker/contact filters.
            EmergencyAlertsOnMap(remoteEmergencyMapItems) { uid ->
                context.startActivity(Intent(context, BloodhoundActivity::class.java).apply {
                    putExtra(BloodhoundActivity.EXTRA_REMOTE_EMERGENCY_UID, uid)
                })
            }

            // Displays dropped and received markers
            if (showMarkers) {

                // Displays markers dropped by the current user
                MarkersOnMap(
                    collection = MarkerLayerCollection.DROPPED,
                    markers = visibleDropped.map {
                        val key = mapSelectionKey(MapTargetKind.DROPPED, it.uid, it.sourceServerId)
                        it.copy(mapLabel = targetLabels[key], selectionKey = key)
                    },

                    onSelectionKey = ::selectMapTarget,
                    camera = cameraState
                )

                // Displays markers received from other users
                MarkersOnMap(
                    collection = MarkerLayerCollection.RECEIVED,
                    markers = visibleReceived.map {
                        val key = mapSelectionKey(MapTargetKind.RECEIVED, it.uid, it.sourceServerId)
                        it.copy(mapLabel = targetLabels[key], selectionKey = key)
                    },

                    onSelectionKey = ::selectMapTarget,
                    camera = cameraState
                )
            }

            // Displays ATOS tags
            if (showAtosTags) {
                ATOSTagsOnMap(
                    tags = receivedAtosTags,
                    camera = cameraState
                )
            }

            // Displays users that match the selected filters
            if (showUsers || selectingChatContact) {
                UsersOnMap(

                    // Filters users by team and role
                    users = visibleMapUsers.map {
                        val key = mapSelectionKey(MapTargetKind.CONTACT, it.uid, it.sourceServerId)
                        it.copy(mapLabel = targetLabels[key], selectionKey = key)
                    },
                    onSelectionKey = ::selectMapTarget,
                    camera = cameraState
                )
            }

            // Displays the active Bloodhound target
            BhoundMap(
                item = activeBloodhound,
                userLat = lat,
                userLon = lon,
                camera = cameraState
            )

            RoutesLayer(
                routes = missionMapRoutes,
                camera = cameraState,
                onPointClick = { serverId, routeUid, index ->
                    selectedRoute = serverId to routeUid
                    selectRoutePoint(serverId, routeUid, index)
                },
                onRouteClick = { serverId, routeUid ->
                    selectedRoutePoint = null
                    selectedRoute = serverId to routeUid
                }
            )
        }

        if (selectingChatContact) {
            Text(
                text = stringResource(if (receivedMapUsers.none { it.uid != MainActivity.device_uid })
                    R.string.chat_select_person_empty else R.string.chat_select_person),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp, start = 35.dp, end = 35.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(4.dp),
                fontSize = 12.sp
            )
        }

        // Displays location details selected from the location puck
        if (showPopup && puckScreenOffset != null) {
            PopupBox(
                showPopup = showPopup,
                lat = lat,
                lon = lon,

                // Closes the popup when the user taps outside
                onClickOutside = {
                    showPopup = false
                }
            )
        }

        // Displays the controls around the map
        MapControlsOverlay(
            cameraState = cameraState,

            // Enables snap-to-self when a location is available
            locationAvailable = locationFlow != null,

            // Zooms in one level
            onZoomIn = {
                changeZoom(1.0)
            },

            // Controls whether optional map controls are hidden
            hideControls = if (selectingChatContact) false else hideControls,

            // Zooms out one level
            onZoomOut = {
                changeZoom(-1.0)
            },

            // Recenters the map on the current location
            onCenterOnSelf = {
                snapToSelf()
            },

            // Opens the Layers menu
            onOpenLayers = {
                showLayerToggle = true
            },
            onOpenChannels = {
                if (!networkSettingsLocked) showChannelToggle = true
            },
            channelsEnabled = !networkSettingsLocked,
            // Exits the map screen
            onBack = {
                exitActivity()
            }
        )

        // Displays the Layers menu
        if (showLayerToggle) {
            LayerTogglePopup(
                topoLayers = topoLayers,
                hiddenLayers = hiddenLayers,
                visibleUserTeams = visibleUserTeams,
                visibleUserRoles = visibleUserRoles,
                visibleUserRolesLEO = visibleUserRolesLEO,
                availableTeams = presentTeams,
                myRole = myRole,
                roleCounts = roleCounts,
                teamColorCount = teamColorCount,
                availableUserRoles = presentUserRoles,
                availableUserRolesLEO = presentUserRolesLEO,

                // Shows whether the optional map controls are hidden
                hideControls = hideControls,

                // Hides or restores the optional map controls
                onHideControlsToggle = {
                    val updatedValue = !hideControls

                    // Updates the screen
                    hideControls = updatedValue

                    // Saves the selection
                    mapPreferences.edit()
                        .putBoolean(
                            "hide_controls",
                            updatedValue
                        )
                        .apply()
                },

                // Shows or hides users from the selected team
                onTeamToggle = { team ->
                    val updatedTeams =
                        if (team in visibleUserTeams) {
                            visibleUserTeams - team
                        } else {
                            visibleUserTeams + team
                        }

                    visibleUserTeams = updatedTeams

                    mapPreferences.edit()
                        .putStringSet(
                            "visible_user_teams",
                            updatedTeams
                        )
                        .apply()
                },

                // Shows or hides the selected map layer
                onToggle = { id ->

                    // Gets every style layer in the selected group
                    val layers = layerGroups[id] ?: setOf(id)

                    val updatedHiddenLayers =
                        if (layers.all { it in hiddenLayers }) {
                            hiddenLayers - layers
                        } else {
                            hiddenLayers + layers
                        }

                    // Updates the screen
                    hiddenLayers = updatedHiddenLayers

                    // Saves the selection for the next app session
                    mapPreferences.edit()
                        .putStringSet(
                            "hidden_layers",
                            updatedHiddenLayers
                        )
                        .apply()
                },

//                    // Removes hidden layers or adds visible layers
//                    hiddenLayers =
//                        if (layers.all { it in hiddenLayers }) {
//                            hiddenLayers - layers
//                        } else {
//                            hiddenLayers + layers
//                        }




                // Shows or hides users with the selected role
                onRoleToggle = { role ->
                    val updatedRoles =
                        if (role in visibleUserRoles) {
                            visibleUserRoles - role
                        } else {
                            visibleUserRoles + role
                        }
                    // update the screen
                    visibleUserRoles = updatedRoles

                    // save the selected role
                    userRolePreferences.saveStandardRoles(updatedRoles)
                },

                // Shows or hides users with the selected LEO role
                onLeoRoleToggle = { role ->
                    val updatedRoles =
                        if (role in visibleUserRolesLEO) {
                            visibleUserRolesLEO - role
                        } else {
                            visibleUserRolesLEO + role
                        }

                    // update the screen
                    visibleUserRolesLEO = updatedRoles

                    // save the selected role
                    userRolePreferences.saveLeoRoles(updatedRoles)

                },

                // Creates the main overlay visibility options
                overlays = listOf(
                    OverlayToggle(
                        "Markers",
                        showMarkers
                    ) {
                        val updatedValue = !showMarkers

                        // Updates and saves marker visibility
                        showMarkers = updatedValue
                        mapPreferences.edit()
                            .putBoolean("show_markers", updatedValue)
                            .apply()
                    },
                    OverlayToggle(
                        "Users",
                        showUsers
                    ) {
                        val updatedValue = !showUsers
                        // Updates and saves marker visibility
                        showUsers = updatedValue
                        mapPreferences.edit()
                            .putBoolean("show_users", updatedValue)
                            .apply()
                    },
                    OverlayToggle(
                        "ATOS",
                        showAtosTags
                    ) {
                        val updatedValue = !showAtosTags
                        // Updates and saves marker visibility
                        showAtosTags = updatedValue
                        mapPreferences.edit()
                            .putBoolean("show_Atos_Tags", updatedValue)
                            .apply()
                    },
                ),

                // Closes the Layers menu
                onDismiss = {
                    showLayerToggle = false
                }
            )
        }

        if (showChannelToggle && !networkSettingsLocked) {
            LayerChannelsPopup(
                onDismiss = {
                    showChannelToggle = false
                },
                mapVm,
                takServers = takChannels
            )
        }
        selectedMapTargetKeys?.let { keys ->
            MapTargetPicker(selectableMapTargets.filter { it.key in keys },
                onSelect = { target ->
                    selectedMapTargetKeys = null
                    openMapTarget(target)
                },
                onBack = { selectedMapTargetKeys = null })
        }
        selectedRoute?.let { (serverId, routeUid) ->
            val route = takRoutes.firstOrNull { it.serverId == serverId && it.uid == routeUid }
            if (route != null && selectedRoutePoint == null) {
                RouteDetailsView(
                    route = route,
                    onPoint = { index -> selectRoutePoint(serverId, routeUid, index) },
                    onRemove = {
                        dataRepository.removeRouteFromWatch(route, bhRepo)
                        selectedRoutePoint = null
                        selectedRoute = null
                    },
                    onBack = { selectedRoute = null }
                )
            }
        }
        selectedRoutePoint?.let { point ->
            val pointUid = "route-point-${point.serverId}-${point.routeUid}-${point.pointIndex}"
            LaunchedEffect(pointUid) {
                dataRepository.requestInteractionPliRefresh(PliRefreshReason.POSITION_VIEWED)
            }
            val heading by dataRepository.azimuthTrueDegSmoothed.collectAsStateWithLifecycle()
            val vector = locationFlow?.let {
                com.aitech.weartak_civ.provider.LocationVectorCalculator.calculate(
                    it.latitude, it.longitude, point.latitude, point.longitude
                )
            }
            val orientation = com.aitech.weartak_civ.provider.LocationVectorCalculator
                .needleAngle(vector?.bearing, heading) ?: vector?.bearing ?: 0.0
            RoutePointsDetailsView(
                pointName = point.title.ifBlank {
                    context.getString(R.string.route_point_number, point.pointIndex + 1)
                },
                latitude = point.latitude,
                longitude = point.longitude,
                sender = point.droppedBy,
                missionName = point.missionName,
                distance = vector?.distance,
                orientation = orientation.toFloat(),
                isBloodhounding = activeItem?.uid == pointUid,
                onInPosition = {
                    bhRepo.requestInPosition(pointUid)
                    requestBloodhoundComplicationUpdate(context)
                },
                onWholeRoute = {
                    selectedRoute = point.serverId to point.routeUid
                    selectedRoutePoint = null
                },

                onChat = {
                    val senderUid = takRoutes
                        .firstOrNull { it.serverId == point.serverId && it.uid == point.routeUid }
                        ?.sender

                    val contact = try {
                        resolveChatContact(
                            senderUid,
                            receivedUserListSetting.filter { it.sourceServerId == point.serverId },
                            MainActivity.device_uid
                        )
                    } catch (error: IllegalArgumentException) {
                        Log.w(TAG, "Cannot open route sender chat", error)
                        Toast.makeText(
                            context,
                            R.string.chat_invalid_contact,
                            Toast.LENGTH_SHORT
                        ).show()
                        null
                    }

                    if (contact != null) {
                        val intent = Intent(context, ChatActivity::class.java).apply {
                            putExtra("user_uid", contact.uid)
                            putExtra("user_callsign", contact.callsign)
                            putExtra("from_maps", true)
                        }

                        context.startActivity(intent)
                        selectedRoutePoint = null
                        selectedRoute = null
                    }
                },

                onBloodhound = {
                    // Create a unique UID for the selected route point
                    val pointUid =
                        "route-point-${point.serverId}-${point.routeUid}-${point.pointIndex}"

                    // Add the route point as a Bloodhound target
                    bhRepo.addMarkerTarget(
                        uid = pointUid,
                        title = point.title.ifBlank { "Route Point" },
                        markerType = "b-m-p-s-m",
                        lat = point.latitude,
                        lon = point.longitude,
                        time = System.currentTimeMillis()
                    )

                    // Activate Bloodhound
                    bhRepo.requestActivateBloodhound(pointUid)

                    // Refresh the Bloodhound complication
                    requestBloodhoundComplicationUpdate(context)

                },

                onBack = {
                    selectedRoutePoint = null
                }
            )
        }

    }
}

@Composable
fun SelectedUser(
    navController: NavController,
    selectedUid: String?,
    receivedUserSetting: List<DataRepository.ReceivedUser>,
    context: Context,
    selectingChatContact: Boolean = false,
    onChatContactSelected: (DirectChatContact) -> Unit = {},
    onBloodhoundUserSelected: (DataRepository.ReceivedUser) -> Boolean = { user ->
        startMapUserBloodhound(context, user)
    },
) {
    Log.i(TAG, "User list: $receivedUserSetting")
    val user = receivedUserSetting.firstOrNull { it.uid == selectedUid }
    val uid = user?.uid.orEmpty()
    val title = user?.title.orEmpty()
    val team = user?.team.orEmpty()

    WearTAKCIVTheme {
        val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)
        Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp)
                        .testTag("map-user-actions"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.user_white),
                                contentDescription = null,
                                tint = TeamColors(team),
                                modifier = Modifier.size(25.dp),
                            )
                            androidx.compose.material3.Text(
                                text = if (user == null) stringResource(R.string.chat_invalid_contact)
                                    else title.ellipsizeMarkerTitle(14),
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                    item {
                        Chip(
                            onClick = {
                                val contact = try {
                                    resolveChatContact(selectedUid, receivedUserSetting, MainActivity.device_uid)
                                } catch (error: IllegalArgumentException) {
                                    Log.w(TAG, "Invalid selected chat contact", error)
                                    Toast.makeText(context, R.string.chat_invalid_contact, Toast.LENGTH_SHORT).show()
                                    return@Chip
                                }
                                if (selectingChatContact) {
                                    onChatContactSelected(contact)
                                } else {
                                    val intent = Intent(context, ChatActivity::class.java).apply {
                                        putExtra("user_uid", contact.uid)
                                        putExtra("user_callsign", contact.callsign)
                                        putExtra("from_maps", true)
                                    }
                                    context.startActivity(intent)
                                    navController.popBackStack()
                                }
                            },
                            enabled = user != null && !user.chatEndpoint.isNullOrBlank() && uid.isNotBlank() && title.isNotBlank() &&
                                uid != MainActivity.device_uid && uid != ChatDestinations.ALL_CHAT_ROOMS,
                            label = { Text(stringResource(if (selectingChatContact)
                                R.string.chat_select_person_confirm else R.string.chat_open)) },
                            colors = ChipDefaults.chipColors(
                                backgroundColor = Color.DarkGray,
                                contentColor = Color.White,
                                secondaryContentColor = Color.LightGray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        val location = user?.location
                        Chip(
                            onClick = {
                                if (user != null && onBloodhoundUserSelected(user)) {
                                    navController.popBackStack()
                                }
                            },
                            enabled = user != null && uid.isNotBlank() &&
                                uid != MainActivity.device_uid && uid != ChatDestinations.ALL_CHAT_ROOMS &&
                                location != null && location.lat.isFinite() && location.lon.isFinite() &&
                                location.lat in -90.0..90.0 && location.lon in -180.0..180.0,
                            label = { Text(stringResource(R.string.bloodhound_start)) },
                            colors = ChipDefaults.chipColors(
                                backgroundColor = Color.DarkGray,
                                contentColor = Color.White,
                                secondaryContentColor = Color.LightGray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                    WearTAKBackButton(
                        onClick = { navController.popBackStack()}
                    )
                }
            }
        }
    }
}

private fun startMapUserBloodhound(context: Context, user: DataRepository.ReceivedUser): Boolean {
    val repository = (context.applicationContext as WearTakApplication).bhRepo
    if (!repository.addUserTarget(user)) {
        Toast.makeText(context, R.string.remote_emergency_no_location, Toast.LENGTH_SHORT).show()
        return false
    }
    repository.requestActivateBloodhound(user.uid)
    requestBloodhoundComplicationUpdate(context)
    return true
}


@Composable
fun MapUi(
    moveUp: () -> Unit,
    moveDown: () -> Unit,
    moveLeft: () -> Unit,
    moveRight: () -> Unit,
    zoomIn: () -> Unit,
    zoomOut: () -> Unit,
) {

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

        val radiusPx = min(wPx, hPx) / 2f
        val centerX = wPx / 2f
        val centerY = hPx / 2f

        // We pull the button *in* by half its size so it stays fully visible,
        // while still visually "touching" the circular edge.
        val btnPx = with(density) { 40.dp.toPx() }
        val btnR = btnPx / 2f
        val edgeDist = radiusPx - btnR

        fun polarOffset(angleDeg: Float, dist: Float = edgeDist): IntOffset {
            val a = Math.toRadians(angleDeg.toDouble())
            val x = centerX + (dist * cos(a)).toFloat() - btnR
            val y = centerY + (dist * sin(a)).toFloat() - btnR
            return IntOffset(x.roundToInt(), y.roundToInt())
        }

        val context = LocalContext.current

        val interactionSource = remember { MutableInteractionSource() }

        val viewConfiguration = LocalViewConfiguration.current


        LaunchedEffect(interactionSource) {
            var isLongClick = false

            interactionSource.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        isLongClick = false
                        delay(viewConfiguration.longPressTimeoutMillis)
                        isLongClick = true
                        Toast.makeText(context, "LONG", Toast.LENGTH_SHORT).show()

                    }

                    is PressInteraction.Release -> {
                        if (isLongClick.not()) {
                            Toast.makeText(context, "click", Toast.LENGTH_SHORT).show()
                        }

                    }

                }
            }
            while (isLongClick) {
                moveUp()
                delay(5000) // Delay for 5 seconds
                Log.d(TAG, "isLongClick = $isLongClick")
            }
        }


        // --- 4 arrow buttons on top/bottom/left/right ---
        Button(
//            interactionSource = interactionSource,
            onClick = { moveUp() },
            modifier = Modifier
                .offset { polarOffset(-90f) }
                .size(40.dp), // top,
            shape = RoundedCornerShape(0.dp, 0.dp, 40.dp, 40.dp),
        ) { Text("▲") }

        Button(
//            interactionSource = interactionSource,
            onClick = { moveDown() },
            modifier = Modifier
                .offset { polarOffset(90f) }
                .size(40.dp),  // bottom
            shape = RoundedCornerShape(40.dp, 40.dp, 0.dp, 0.dp),
        ) { Text("▼") }

        Button(
//            interactionSource = interactionSource,
            onClick = { moveLeft() },
            modifier = Modifier
                .offset { polarOffset(180f) }
                .size(40.dp), // left
            shape = RoundedCornerShape(0.dp, 40.dp, 40.dp, 0.dp),
        ) { Text("◀") }

        Button(
//            interactionSource = interactionSource,
            onClick = { moveRight() },
            modifier = Modifier
                .offset { polarOffset(0f) }
                .size(40.dp),   // right
            shape = RoundedCornerShape(40.dp, 0.dp, 0.dp, 40.dp),
        ) { Text("▶") }

        // --- + / − on lower-right ---
        // Place a *cluster anchor* on the circle at 315° (lower-right),
        // then offset + and − up/down from that anchor.
        val gapPx = with(density) { 10.dp.toPx() }
        val stackDelta = (btnPx + gapPx) / 2f

        // Pull the anchor inward so the top/bottom buttons don't clip out of the circle.
        val clusterDist = edgeDist - stackDelta * 0.6f
        val anchor = polarOffset(angleDeg = 315f, dist = clusterDist)

        Button(
            onClick = { zoomIn() },
            modifier = Modifier
                .offset { polarOffset(33f) }
                .size(40.dp)
                .rotate(45f),  // bottom
            shape = RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp),
        ) { Text("+", modifier = Modifier.rotate(-45f)) }

        Button(
            onClick = { zoomOut() },
            modifier = Modifier
                .offset { polarOffset(57f) }
                .size(40.dp)
                .rotate(45f),  // bottom
            shape = RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp),
        ) { Text("−", modifier = Modifier.rotate(-45f)) }
    }
}

@Composable
private fun MapControlsOverlay(
    cameraState: CameraState,
    locationAvailable: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onCenterOnSelf: () -> Unit,
    onOpenLayers: () -> Unit,
    onOpenChannels: () -> Unit,
    channelsEnabled: Boolean,
    // Determines whether the optional map controls should be hidden.
    hideControls: Boolean,
    onBack: () -> Unit
) {
    val buttonSize = 40.dp
    val iconSize = 40.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        // Hide these optional controls when Hide Controls is selected.
        if (!hideControls) {

            // Zoom in.
            CircularMapButton(
                onClick = onZoomIn,
                size = buttonSize,
                showBorder = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 26.dp, y = 20.dp)
            ) {
                Text(
                    text = "+",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light
                )
            }

            // Center the map on the user's location.
            CircularMapButton(
                onClick = onCenterOnSelf,
                enabled = locationAvailable,
                size = buttonSize,
                showBorder = false,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 4.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.lock_on_self),
                    contentDescription = "Center on my location",
                    modifier = Modifier.size(iconSize),
                    colorFilter = ColorFilter.tint(Color.White)
                )
            }

            // Zoom out.
            CircularMapButton(
                onClick = onZoomOut,
                size = buttonSize,
                showBorder = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 24.dp, y = (-24).dp)
            ) {
                Text(
                    text = "−",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light
                )
            }
            DynamicScaleBar(
                metersPerDp = cameraState.metersPerDpAtTarget,
                useDefaultDistances =
                    abs(cameraState.position.zoom - 15.5) < 0.01,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(
                        x = 18.dp,
                        y = (-14).dp
                    )
                    .width(110.dp)
            )
//            ScaleBar(
//                metersPerDp = cameraState.metersPerDpAtTarget,
//                modifier = Modifier
//                    .align(Alignment.BottomCenter)
//                    .offset(x = 18.dp, y = (-14).dp)
//                    .width(110.dp),
//                color = Color.White,
//                textStyle = MaterialTheme.typography.body2.copy(
//                    color = Color.White,
//                    fontSize = 9.sp
//                ),
//                barWidth = 1.dp
//            )
        }

        // Always visible so the user can reopen the menu and restore controls.
        CircularMapButton(
            onClick = onOpenLayers,
            size = buttonSize,
            showBorder = false,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-2).dp)
        ) {
            Image(
                painter = painterResource(R.drawable.overlay_manager),
                contentDescription = "Layers",
                modifier = Modifier.size(iconSize),
                colorFilter = ColorFilter.tint(Color.White)
            )
        }

        MapChannelsButton(
            onClick = onOpenChannels,
            enabled = channelsEnabled,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = -26.dp, y = 20.dp)
        )

        // Always visible so the user can leave the map.
        CircularMapButton(
            onClick = onBack,
            size = buttonSize,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Text(
                text = "‹",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun MapChannelsButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CircularMapButton(
        onClick = onClick,
        enabled = enabled,
        showBorder = false,
        modifier = modifier
    ) {
        Image(
            painter = painterResource(R.drawable.channels),
            contentDescription = "Channels",
            modifier = Modifier.size(30.dp),
            colorFilter = ColorFilter.tint(if (enabled) Color.White else Color.Gray)
        )
    }
}

@Composable
private fun CircularMapButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    showBorder: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (showBorder) {
                    Color.Black.copy(alpha = 0.18f)
                } else {
                    Color.Transparent
                }
            )
            .then(
                if (showBorder) {
                    Modifier.border(
                        width = 2.dp,
                        color = if (enabled) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.4f)
                        },
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember {
                    MutableInteractionSource()
                }
            ) {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
@Composable
private fun DynamicScaleBar(
    metersPerDp: Double,
    useDefaultDistances: Boolean,
    modifier: Modifier = Modifier
) {
    if (!metersPerDp.isFinite() || metersPerDp <= 0.0) return

    val unitPrefs = currentUnitPrefs()
    BoxWithConstraints(modifier = modifier) {
        val maxDistanceMeters = metersPerDp * maxWidth.value
        val (label, distanceMeters) = scaleBarFor(maxDistanceMeters, useDefaultDistances, unitPrefs)
        ScaleLine(
            label = label,
            distanceMeters = distanceMeters,
            metersPerDp = metersPerDp
        )
    }
}

/** Picks a round scale length in the user's range units; returns its label and length in meters. */
internal fun scaleBarFor(
    maxDistanceMeters: Double,
    useDefaultDistances: Boolean,
    prefs: com.aitech.weartak_civ.ui.formatter.UnitPrefs,
): Pair<String, Double> {
    val units = com.aitech.weartak_civ.ui.formatter.UnitFormatter
    return when {
        prefs.isImperial -> {
            val maxFeet = maxDistanceMeters / units.METERS_PER_FOOT
            when {
                useDefaultDistances -> "200 ft" to 200 * units.METERS_PER_FOOT
                maxFeet >= 5280.0 -> {
                    val miles = niceScaleDistance(maxDistanceMeters / units.METERS_PER_MILE)
                    "${formatScaleDistance(miles)} mi" to miles * units.METERS_PER_MILE
                }
                else -> {
                    val feet = niceScaleDistance(maxFeet)
                    "${formatScaleDistance(feet)} ft" to feet * units.METERS_PER_FOOT
                }
            }
        }
        else -> {
            val meters = if (useDefaultDistances) 50.0 else niceScaleDistance(maxDistanceMeters)
            if (meters >= 1000.0) "${formatScaleDistance(meters / 1000.0)} km" to meters
            else "${formatScaleDistance(meters)} m" to meters
        }
    }
}

@Composable
private fun ScaleLine(
    label: String,
    distanceMeters: Double,
    metersPerDp: Double
) {
    val lineWidth =
        (distanceMeters / metersPerDp).toFloat().dp

    Column {
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.sp,
            lineHeight = 9.sp
        )

        Canvas(
            modifier = Modifier
                .width(lineWidth)
                .height(4.dp)
        ) {
            val stroke = 1.dp.toPx()

            // Horizontal line
            drawLine(
                color = Color.White,
                start = Offset(
                    0f,
                    0f
                ),
                end = Offset(
                    size.width,
                    0f
                ),
                strokeWidth = stroke
            )

            // Left tick
            drawLine(
                color = Color.White,
                start = Offset(
                    0f,
                    0f
                ),
                end = Offset(
                    0f,
                    size.height
                ),
                strokeWidth = stroke
            )

            // Right tick
            drawLine(
                color = Color.White,
                start = Offset(
                    size.width,
                    0f
                ),
                end = Offset(
                    size.width,
                    size.height
                ),
                strokeWidth = stroke
            )
        }
    }
}

private fun niceScaleDistance(
    maxDistance: Double
): Double {
    if (!maxDistance.isFinite() || maxDistance <= 0.0) {
        return 1.0
    }

    val power =
        10.0.pow(floor(log10(maxDistance)))

    val normalized = maxDistance / power

    val multiplier = when {
        normalized >= 5.0 -> 5.0
        normalized >= 2.0 -> 2.0
        else -> 1.0
    }

    return multiplier * power
}

private fun formatScaleDistance(
    distance: Double
): String {
    return if (distance >= 1.0) {
        distance.roundToInt().toString()
    } else {
        java.math.BigDecimal(distance).round(java.math.MathContext(2)).stripTrailingZeros().toPlainString()
    }
}




@Preview(widthDp = 192, heightDp = 192, showBackground = true)
@Composable
fun MyAppPreview() {
    PopupBox(showPopup = true, lat = 41.8832, lon = -87.6324, onClickOutside = {false})
}

//@Preview(widthDp = 192, heightDp = 192, showBackground = true)
//@Composable
//fun ChannelsPreview() {
//    LayerChannelsPopup({} )
//}
