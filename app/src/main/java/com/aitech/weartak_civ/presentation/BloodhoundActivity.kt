/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.presentation.BloodhoundActivity.Constants.bloodhoundDefaultList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitech.weartak_civ.data.BloodhoundRepository
import java.io.Closeable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.ChipDefaults.gradientBackgroundChipColors
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.complication.BloodhoundComplicationService
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.BloodhoundActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.ChatActivity.Companion.EXTRA_OPEN_ROOM_UID
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class BloodhoundActivity  : ComponentActivity() {
    companion object Constants{
        const val EXTRA_REMOTE_EMERGENCY_UID = "remote_emergency_uid"
        lateinit var bloodhoundActivity : BloodhoundActivity
        val TAG = "WearTAK-BloodHActivity"
        var bloodhoundDefaultList = mutableStateListOf(
            Utils.BloodhoundItem(
                target = "Denny's",
                uid = "",
                callsign = "TAB-01",
                timeReceived = 1760459784,
                distance = 89.4,
                orientation = 104F,
                bloodhounding = false
            ),
            Utils.BloodhoundItem(
                target = "John",
                uid = "",
                callsign = "PHN-34",
                timeReceived = 1760459784,
                distance = 103.1,
                orientation = 54F,
                bloodhounding = false
            ),
            Utils.BloodhoundItem(
                target = "Hostile Marker",
                uid = "",
                callsign = "ALPHACRISIS",
                timeReceived = 1760459784,
                distance = 174.3,
                orientation = 134F,
                bloodhounding = false
            )
        )
    }
    private val requestedRemoteEmergencyUid = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bloodhoundActivity = this
        requestedRemoteEmergencyUid.value = intent.getStringExtra(EXTRA_REMOTE_EMERGENCY_UID)
        val app = applicationContext as WearTakApplication
        Log.i(TAG, "onCreate() Bloodhound clearing unread state")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            app.repo.clearBloodhoundUnreadCount("BloodhoundActivity.onCreate")
        }
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val settingsVm: WearTAKSettingsViewModel = viewModel()

                    BloodhoundCivApp(
                        exitActivity = {finishAndRemoveTask()}, context, settingsVm,
                        requestedRemoteEmergencyUid.value,
                        onRemoteEmergencyOpened = { requestedRemoteEmergencyUid.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleInstance reuses this activity when an alert is opened from the map.
        requestedRemoteEmergencyUid.value = intent.getStringExtra(EXTRA_REMOTE_EMERGENCY_UID)
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause() Bloodhound")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy() Bloodhound")
    }
    
    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart() Bloodhound")
    }

    
}


internal fun observeBloodhoundPresentationDemand(
    lifecycle: Lifecycle,
    repository: BloodhoundRepository,
    uid: String? = null,
): Closeable {
    val consumer = Any()
    fun update() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            repository.requestPresentation(consumer, uid)
        else repository.releasePresentation(consumer)
    }
    val observer = LifecycleEventObserver { _, _ -> update() }
    lifecycle.addObserver(observer)
    update()
    return Closeable {
        lifecycle.removeObserver(observer)
        repository.releasePresentation(consumer)
    }
}

@Composable
private fun TrackBloodhoundPresentationDemand(repository: BloodhoundRepository, uid: String? = null) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, repository, uid) {
        val demand = observeBloodhoundPresentationDemand(owner.lifecycle, repository, uid)
        onDispose { demand.close() }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun BloodhoundCivApp(
    exitActivity: () -> Unit,
    context: Context,
    settingsVm: WearTAKSettingsViewModel,
    remoteEmergencyUid: String? = null,
    onRemoteEmergencyOpened: () -> Unit = {}
) {
    val app = context.applicationContext as WearTakApplication

    val dataRepository = remember(app) { app.repo }
    val bhRepo = remember(app) { app.bhRepo }

    val navController = rememberSwipeDismissableNavController()

    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    val routes by dataRepository.takRoutes.collectAsStateWithLifecycle()
    val location by dataRepository.latestLocation.collectAsStateWithLifecycle()
    val routeSession by bhRepo.routeFollow.collectAsStateWithLifecycle()
    val activeTarget by bhRepo.activeBhItemUi.collectAsStateWithLifecycle()
    val routeLocation = location?.let { TakRoutePoint(it.latitude, it.longitude) }
    LaunchedEffect(Unit) {
        dataRepository.requestInteractionPliRefresh(com.aitech.weartak_civ.events.PliRefreshReason.POSITION_VIEWED)
    }

    fun routeDestination(route: TakRoute) =
        "bloodhound_route/${Uri.encode(route.serverId)}/${Uri.encode(route.uid)}"

    LaunchedEffect(Unit) {
        dataRepository.bloodhoundUiCommands.collectLatest { command ->
            when (command) {
                is DataRepository.BloodhoundUiCommand.OpenChatRoom -> {
                    val intent = Intent(context, ChatActivity::class.java).apply {
                        putExtra(EXTRA_OPEN_ROOM_UID, command.roomUid)
                    }
                    context.startActivity(intent)
                    exitActivity()
                }

                DataRepository.BloodhoundUiCommand.CloseBloodhoundScreen -> {
                    exitActivity()
                }
            }
        }
    }

    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        Log.i(TAG, "isValid: $isValid, currentDest: $currentDestination")
        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
                exitActivity()
            }
            isValid && currentDestination != "bloodhound_screen" -> {
                navController.navigate("bloodhound_screen") {
                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
        }
    }

    LaunchedEffect(remoteEmergencyUid) {
        val uid = remoteEmergencyUid ?: return@LaunchedEffect
        if (isLicenseValid()) {
            navController.navigate("bloodhound_screen_detailed/${Uri.encode(uid)}") {
                popUpTo("bloodhound_screen")
                launchSingleTop = true
            }
        }
        onRemoteEmergencyOpened()
    }

    LaunchedEffect(remoteEmergencyUid) {
        val uid = remoteEmergencyUid ?: return@LaunchedEffect
        if (isLicenseValid()) {
            navController.navigate("bloodhound_screen_detailed/${Uri.encode(uid)}") {
                popUpTo("bloodhound_screen")
                launchSingleTop = true
            }
        }
        onRemoteEmergencyOpened()
    }

    LaunchedEffect(remoteEmergencyUid) {
        val uid = remoteEmergencyUid ?: return@LaunchedEffect
        if (isLicenseValid()) {
            navController.navigate("bloodhound_screen_detailed/${Uri.encode(uid)}") {
                popUpTo("bloodhound_screen")
                launchSingleTop = true
            }
        }
        onRemoteEmergencyOpened()
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (isLicenseValid()) "bloodhound_screen" else "invalid_license_screen"
    ) {
        composable("bloodhound_screen" ) {
            TrackBloodhoundPresentationDemand(bhRepo)
            val uiList by bhRepo.listBhItemsUi.collectAsStateWithLifecycle()
            BloodhoundScreen(bloodhounds = uiList, navController, exitActivity, context,
                routes, routeLocation, { navController.navigate(routeDestination(it)) }, routeSession,
                onMapTarget = { uid ->
                    bhRepo.bloodhoundMap.value[uid]?.target?.point?.let { point ->
                        context.startActivity(Intent(context, MapActivity::class.java)
                            .putExtra(MapActivity.EXTRA_FOCUS_LAT, point.lat)
                            .putExtra(MapActivity.EXTRA_FOCUS_LON, point.lon))
                    }
                },
                onMapRoute = { route ->
                    context.startActivity(Intent(context, MapActivity::class.java)
                        .putExtra(MapActivity.EXTRA_FOCUS_ROUTE_SERVER, route.serverId)
                        .putExtra(MapActivity.EXTRA_FOCUS_ROUTE_UID, route.uid))
                },
                onStartTarget = { bhRepo.requestActivateBloodhound(it) },
                onChooseRoute = { navController.navigate(
                    "bloodhound_route_start/${Uri.encode(it.serverId)}/${Uri.encode(it.uid)}") },
                onStopTarget = {
                    bhRepo.stopTracking(it)
                    requestBloodhoundComplicationUpdate(context)
                },
                onStopRoute = {
                    bhRepo.stopRoute()
                    requestBloodhoundComplicationUpdate(context)
                })
        }

        listOf("bloodhound_route", "bloodhound_route_start").forEach { destination ->
        composable(
            "$destination/{serverId}/{routeUid}",
            arguments = listOf(navArgument("serverId") { type = NavType.StringType },
                navArgument("routeUid") { type = NavType.StringType })
        ) { entry ->
            val route = routes.firstOrNull { it.serverId == entry.arguments?.getString("serverId") &&
                it.uid == entry.arguments?.getString("routeUid") }
            if (route == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                RouteBloodhoundDetails(route, routeSession, routeLocation, activeTarget,
                    onStart = { mode ->
                        if (bhRepo.startRoute(route, mode, routeLocation) && destination == "bloodhound_route_start") {
                            exitActivity()
                        }
                        requestBloodhoundComplicationUpdate(context)
                    },
                    onStop = {
                        bhRepo.stopRoute()
                        requestBloodhoundComplicationUpdate(context)
                    },
                    onBack = { navController.popBackStack() },
                    choosingStart = destination == "bloodhound_route_start",
                    onRemove = { dataRepository.removeRouteFromWatch(route, bhRepo) })
            }
        }
        }

        composable(
            "bloodhound_screen_detailed/{uid}",
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid").orEmpty()
            val session = routeSession?.takeIf { it.uid == uid }
            if (session != null) {
                LaunchedEffect(uid) {
                    navController.navigate(routeDestination(session.route)) {
                        popUpTo("bloodhound_screen")
                    }
                }
            } else {
                BloodhoundScreenDetailed(navController, uid, context,{ exitActivity() })
            }
        }

        composable(
            "invalid_license_screen") {
            LaunchedEffect(Unit) {
                context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
                Log.i(TAG, "Launching Settings from invalid_license_screen")
                exitActivity()   // <-- actually call it so the user returns to MainActivity
            }
        }
    }
}


@Composable
fun BloodhoundScreen(
    bloodhounds: List<Utils.BloodhoundItem>,
    navController: NavController,
    exitActivity: () -> Unit,
    context: Context,
    routes: List<TakRoute> = emptyList(),
    routeLocation: TakRoutePoint? = null,
    onRoute: (TakRoute) -> Unit = {},
    session: com.aitech.weartak_civ.data.RouteFollowSession? = null,
    onMapTarget: (String) -> Unit = {},
    onMapRoute: (TakRoute) -> Unit = {},
    onStartTarget: (String) -> Unit = {},
    onChooseRoute: (TakRoute) -> Unit = {},
    onStopTarget: (String) -> Unit = {},
    onStopRoute: () -> Unit = {}
) {
    val actionScope = rememberCoroutineScope()
    var removingAll by remember { mutableStateOf(false) }
    val showRemoveAllConfirmation = remember { mutableStateOf(false) }
    val canRemove = (bloodhounds.isNotEmpty() || routes.isNotEmpty() || session != null) && !removingAll
    val goBack = {
        exitActivity()
        context.startActivity(Intent(context, MainActivity::class.java))
    }
    WearTAKCIVTheme {
        if (showRemoveAllConfirmation.value) {
            BloodhoundRemoveAllDialog(
                shouldShowDialog = showRemoveAllConfirmation,
                onRemoveAll = {
                    showRemoveAllConfirmation.value = false
                    removingAll = true
                    actionScope.launch {
                        try {
                            val app = context.applicationContext as WearTakApplication
                            app.bhRepo.removeAllFromMenu()
                            app.repo.clearRoutesFromWatch(app.bhRepo)
                        } finally {
                            removingAll = false
                        }
                    }
                },
                onCancel = { showRemoveAllConfirmation.value = false },
                enabled = canRemove
            )
        }
        BloodhoundPickerList(
            bloodhounds, routes, routeLocation,
            onTarget = { uid ->
                if (uid.isNotBlank()) navController.navigate("bloodhound_screen_detailed/${Uri.encode(uid)}")
            },
            onRoute = onRoute,
            modifier = Modifier.fillMaxSize().background(colors.background),
            session = session, onMapTarget = onMapTarget, onMapRoute = onMapRoute,
            onStartTarget = onStartTarget, onChooseRoute = onChooseRoute,
            onStopTarget = onStopTarget, onStopRoute = onStopRoute,
            onRemoveTarget = { uid ->
                actionScope.launch {
                    (context.applicationContext as WearTakApplication).bhRepo.removeFromMenu(uid)
                }
            },
            onRemoveRoute = { route ->
                val app = context.applicationContext as WearTakApplication
                app.repo.removeRouteFromWatch(route, app.bhRepo)
            },
            header = {
                BloodhoundPickerHeader(canRemove, { showRemoveAllConfirmation.value = true }, goBack)
            },
        )
    }
}

@Composable
internal fun BloodhoundPickerHeader(canRemove: Boolean, onRemoveAll: () -> Unit, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bloodhound), color = colors.primary,
            modifier = Modifier.padding(bottom = 8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onRemoveAll, enabled = canRemove) {
                Text(stringResource(R.string.bloodhound_remove_all))
            }
            WearTAKBackButton(onClick = onBack, compact = true)
        }
    }
}

@Composable
fun BloodhoundChip(
    title: String,
    distance: Double?,
    bloodhounding: Boolean,
    directionDegrees: Float,
    alertCategory: String? = null,
    isStale: Boolean = false,
    onClick: () -> Unit
) {
    val distanceText = com.aitech.weartak_civ.ui.formatter.UnitFormatter.distanceKm(
        distance, currentUnitPrefs(), separator = ""
    )
    val gradientColors = listOf(
        colors.surface
            .copy(alpha = 0f)
            .compositeOver(colors.surface),
        colors.primary
            .copy(alpha = 0.5f)
            .compositeOver(colors.surface),
    )
    Chip(
        icon = {
            if (alertCategory != null) Icon(
                painterResource(R.drawable.ic_alert_triangle),
                contentDescription = stringResource(R.string.remote_emergency_alert),
                tint = Color.Unspecified,
                modifier = Modifier.size(ChipDefaults.IconSize)
            ) else RotatingArrowIcon(
                degrees = directionDegrees,
                color = Color.White,
                modifier = Modifier.size(ChipDefaults.IconSize)
            )
        },
        colors =
            if (bloodhounding) {
                gradientBackgroundChipColors(
                    startBackgroundColor =
                        colors.primary
                            .copy(alpha = 0.5f)
                            .compositeOver(colors.surface),
                    endBackgroundColor =
                        colors.surface
                            .copy(alpha = 0f)
                            .compositeOver(colors.surface),
                )
            } else {
                ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                )
            },
        label = {
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterVertically),
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        },
        secondaryLabel = {
            Text(text = if (isStale) stringResource(R.string.remote_emergency_stale_category, alertCategory.orEmpty())
                else alertCategory ?: distanceText,
                maxLines = if (isStale) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                fontSize = 13.sp
            )
        },
        enabled = true,
        onClick = { onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
    )
    if (bloodhounding){
        Spacer(modifier = Modifier.height(10.dp))

    }
}

@Composable
fun BloodhoundScreenDetailed(
    navController: NavController,
    uid: String,
    context: Context,
    exitActivity: () -> Unit,
) {
    val app = context.applicationContext as WearTakApplication
    val bhRepo = app.bhRepo
    TrackBloodhoundPresentationDemand(bhRepo, uid)
    val item by remember(bhRepo, uid) { bhRepo.bloodhoundItemUi(uid) }
        .collectAsStateWithLifecycle(initialValue = null)
    val actionScope = rememberCoroutineScope()
    if (uid.isBlank()) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    val currentItem = item
    if (currentItem == null) {
        LaunchedEffect(uid) {
            if (uid !in bhRepo.bloodhoundMap.value) navController.popBackStack()
        }
        return
    }

    val bhoundTarget = currentItem.target
    val bhoundCallsign = currentItem.callsign
    val bhoundTimeReceived = currentItem.timeReceived
    val bhoundDistance = currentItem.distance
    val bhoundOrientation = currentItem.orientation
    var bhoundBloodhounding = currentItem.bloodhounding
    val timeUtc = DateTimeFormatter
        .ofPattern("HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(bhoundTimeReceived))
    //TODO: WEAR-450: Test this
    WearTAKPageWithBackArrow(
        title = bhoundTarget,
        columnContent = {
            item {
                OrientationAware()
            }
            item {
                Text(
                    //TODO: WEAR-161: Translate "Status" to stringResource that already exists when merged
                    text = "Status: " + bloodhoundingTextFromString(bhoundBloodhounding),
                    fontSize = 14.sp,
                    overflow = TextOverflow.Ellipsis,
                    color = if (bhoundBloodhounding) Color.White else Color.LightGray,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    currentItem.alertCategory?.let { category ->
                        Text(
                            stringResource(R.string.remote_emergency_description, category),
                            color = Color.Yellow,
                            fontSize = 14.sp
                        )
                    }
                    if (!currentItem.hasLocation) {
                        Text(stringResource(R.string.remote_emergency_no_location), fontSize = 14.sp)
                    }
                    if (currentItem.isStale) {
                        Text(
                            stringResource(
                                if (currentItem.hasLocation) R.string.remote_emergency_stale_location
                                else R.string.remote_emergency_stale
                            ),
                            color = Color.Yellow,
                            fontSize = 14.sp
                        )
                    }
                    DetailedBloodhoundInfo(
                        distance = bhoundDistance,
                        callsign = bhoundCallsign,
                        timeReceived = timeUtc,
                        orientation = bhoundOrientation,
                        showDirection = currentItem.hasLocation
                    )
                }
            }
            item {
                if (bhoundBloodhounding) {
                    Button(
                        onClick = {
//                        bloodhoundDefaultList.removeAt(index)
                            bhRepo.requestInPosition(currentItem.uid)
//                        activeBhoundItem.value = null
                            requestBloodhoundComplicationUpdate(context)
                        },
                        modifier = Modifier.padding(top = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = Color.Black
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.npos),
                            fontSize = 15.sp,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.Black,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    //TODO: WEAR-161: Add when chat backend is finished
//            Button(
//                onClick = {},
//                modifier = Modifier.padding(top = 7.dp),
//                colors = ButtonDefaults.buttonColors(
//                    containerColor = Color.DarkGray,
//                    contentColor = Color.White
//                ),
//                contentPadding = PaddingValues(10.dp)
//            ) {
//                Icon(
//                    painterResource(R.drawable.chat_nomessage),
//                    modifier = Modifier.size(30.dp),
//                    contentDescription = "Chat",
//                    tint = colors.primary
//                )
//                Spacer(modifier = Modifier.width(5.dp))
//                Text(
//                    text = stringResource(R.string.view_chat),
//                    fontSize = 13.sp,
//                    overflow = TextOverflow.Ellipsis,
//                    fontWeight = FontWeight.SemiBold,
//                )
//            }
                } else {
                    Button(
                        enabled = currentItem.hasLocation,
                        onClick = {
//                        activeBhoundItem.value = bloodhoundItem
//                        setActiveBloodhound(index)

                            bhRepo.requestActivateBloodhound(currentItem.uid)
                            requestBloodhoundComplicationUpdate(context)
//                        Log.d(TAG, bloodhoundDefaultList.toString())
                            Log.i(TAG, "Requesting Ack and Activation of bloodhound")
                        },
                        modifier = Modifier.padding(top = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = Color.Black
                        ),
                    ) {
                        Text(
                            text = stringResource(
                                if (currentItem.isRemoteAlert) R.string.remote_emergency_bloodhound_to_alert
                                else R.string.bloodhound_start
                            ),
                            fontSize = 15.sp,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.Black,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        actionScope.launch {
                            bhRepo.removeFromMenu(currentItem.uid)
                            requestBloodhoundComplicationUpdate(context)
                            navController.navigate("bloodhound_screen") {
                                popUpTo("bloodhound_screen") { inclusive = true }
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        contentColor = Color.White
                    ),
                ) {
                    Text(
                        text = stringResource(if (currentItem.isRemoteAlert) R.string.remote_emergency_dismiss else R.string.delete),
                        fontSize = 15.sp,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        navController = navController,
        exitFunction = {
            navController.navigate("bloodhound_screen") {
                popUpTo("bloodhound_screen") { inclusive = true }
            }
        }
    )
}

@Composable
fun bloodhoundingTextFromString(bloodhounding: Boolean?): String {
    val bhoundString: String = when (bloodhounding) {
        true -> stringResource(R.string.bloodhounding)
        false -> stringResource(R.string.not_bloodhounding)
        null -> "NULL"
    }
    return bhoundString
}

@Composable
fun DetailedBloodhoundInfo(
    distance: Double?,
    callsign: String,
    timeReceived: String,
    orientation: Float,
    showDirection: Boolean = true
) {
    val distanceText = com.aitech.weartak_civ.ui.formatter.UnitFormatter.distanceKm(
        distance, currentUnitPrefs(), separator = ""
    )
    Row(
        verticalAlignment = Alignment.CenterVertically
    ){
        if (showDirection) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ){
                RotatingArrowIcon(
                    degrees = orientation,
                    color = Color.White,
                    modifier = Modifier.size(ChipDefaults.IconSize)
                )
                Text(
                    text = orientation.toString(),
                    fontSize = 13.sp,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.LightGray,
                    fontWeight = FontWeight.Normal
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(){
            Text(
                text = distanceText,
                fontSize = 18.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = callsign,
                fontSize = 15.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.LightGray,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = stringResource(R.string.sent) + timeReceived,
                fontSize = 15.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.LightGray,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
fun RotatingArrowIcon(
    degrees: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = degrees.mod(360f),
        label = "bearing-rotation"
    )

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val pad = minOf(w, h) * 0.1f
            val iw = w - pad * 2
            val ih = h - pad

            // Same arrowhead, thinner shaft
            val path = Path().apply {
                // Tip
                moveTo(pad + iw * 0.5f, pad)
                // Right wing outer
                lineTo(pad + iw * 0.8f, pad + ih * 0.45f)
                // Shaft right (thinner)
                lineTo(pad + iw * 0.55f, pad + ih * 0.45f)
                lineTo(pad + iw * 0.55f, pad + ih)
                // Shaft left (thinner)
                lineTo(pad + iw * 0.45f, pad + ih)
                lineTo(pad + iw * 0.45f, pad + ih * 0.45f)
                // Left wing outer
                lineTo(pad + iw * 0.2f, pad + ih * 0.45f)
                close()
            }

            drawPath(path = path, color = color, style = Fill)
        }
    }
}



fun requestBloodhoundComplicationUpdate(context: Context) {
//    Log.d(MainActivity.Constants.TAG, "requestBloodhoundComplicationUpdate called")
    val componentName = ComponentName(context, BloodhoundComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}
/*
fun isAnyBloodhoundingActive(/*items: List<Utils.BloodhoundItem>*/): Boolean {
    val items = bloodhoundDefaultList
    return items.any { it.bloodhounding }
}

fun setActiveBloodhound(index: Int) {
    // Iterate and update each item
    val bloodhoundList = bloodhoundDefaultList
//    for (i in bloodhoundList.indices) {
//        val currentItem = bloodhoundList[i]
//        // Only modify if state actually changes (to avoid redundant recompositions)
//        if (currentItem.bloodhounding != (i == index)) {
//            bloodhoundList[i] = currentItem.copy(bloodhounding = (i == index))
//        }
//    }
    if (index !in bloodhoundList.indices) return

    // Copy the selected item and set it to active
    val selected = bloodhoundList[index].copy(bloodhounding = true)

    // Build a list of all non-active items, setting them to false and sorting by distance
    val others = bloodhoundList
        .filterIndexed { i, _ -> i != index }
        .map { it.copy(bloodhounding = false) }
        .sortedBy { it.distance }

    // Combine the selected item first, followed by the sorted others
    val reorderedList = listOf(selected) + others

    // Update the state list to trigger recomposition
    bloodhoundList.clear()
    bloodhoundList.addAll(reorderedList)
}
*/
@Preview(widthDp = 192, heightDp = 300, showBackground = true)
@Composable
fun BloodhoundScreenPreview() {
    BloodhoundScreen(bloodhoundDefaultList, rememberSwipeDismissableNavController(), {}, LocalContext.current)
}

@Preview(widthDp = 192, heightDp = 300, showBackground = true)
@Composable
fun BloodhoundDetailedScreenPreview() {
    BloodhoundScreenDetailed(rememberSwipeDismissableNavController(), bloodhoundDefaultList.first().uid, LocalContext.current, {})
}
@Preview(widthDp = 192, heightDp = 60, showBackground = true)
@Composable
fun BhoundTextPreview() {
    DetailedBloodhoundInfo(
        distance = 1.0,
     callsign = "HEllo",
     timeReceived = "0",
     orientation = 1F
    )
}