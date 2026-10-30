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
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.RevealValue
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.rememberRevealState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.SwipeToRevealSecondaryAction
import com.aitech.weartak_civ.Utils.latLonToMgrs
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.MarkerCotType
import com.aitech.weartak_civ.data.DataRepository.MarkerType
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bhRepo
import com.aitech.weartak_civ.presentation.MarkerActivity.Constants.markerType
import com.aitech.weartak_civ.presentation.MarkerActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MarkerActivity.Constants.markerRemarkSettingsState
import com.aitech.weartak_civ.presentation.MarkerActivity.Constants.markerTitleSettingsState
import com.aitech.weartak_civ.presentation.viewmodel.MarkerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToInt

class MarkerActivity : ComponentActivity() {
    companion object Constants{
        lateinit var markerActivity : MarkerActivity
        var markerType: String = MarkerType.UNKNOWN.type
        const val TAG = "WearTAK-MarkerActivity"
        var markerTitleSettingsState = MutableLiveData<String>("")
        var markerRemarkSettingsState = MutableLiveData<String>("")

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markerActivity = this

        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val markerVm: MarkerViewModel = viewModel()
                    val index = intent.getIntExtra("marker_index", -1)
                    Log.d(TAG, "Index to markeractivity: $index")
                    val startRoute = intent.getStringExtra("start_route")
                    var fromMaps = intent.getBooleanExtra("from_maps", false)
                    NewMarkerCivApp(exitActivity = {finishAndRemoveTask(); }, context, markerVm, index, startRoute, fromMaps)
                }
            }
        }
    }

}

@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun RadialSelectionScreen(
    exitActivity: () -> Unit,
    shouldShowDialog: MutableState<Boolean>,
    onClickRightButton: () -> Unit,
    onClickBottomButton: () -> Unit,
    onClickLeftButton: () -> Unit,
    onClickTopButton: () -> Unit,
    longPressBoolean: Boolean,
    navController: NavController
) {
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            val context = LocalContext.current

            DonutArcButton(
                startAngle = -45f,
                sweepAngle = 85f,
                Alignment.CenterEnd,
                "Friendly",
                marker = painterResource(R.drawable.friendly),
                onClick = { onClickRightButton() }
            )
            DonutArcButton(
                startAngle = 45f,
                sweepAngle = 85f,
                Alignment.BottomCenter,
                "Unknown",
                marker = painterResource(R.drawable.unknown),
                onClick = { onClickBottomButton() }
            )
            DonutArcButton(
                startAngle = 135f,
                sweepAngle = 85f,
                Alignment.CenterStart,
                "Neutral",
                marker = painterResource(R.drawable.neutral),
                onClick = { onClickLeftButton() }
            )
            DonutArcButton(
                startAngle = 225f,
                sweepAngle = 85f,
                Alignment.TopCenter,
                "Hostile",
                marker = painterResource(R.drawable.hostile),
                onClick = { onClickTopButton() }
            )
            if (longPressBoolean) {
                LongPressProgressButtonWithInteraction(
                    navController,
                    onShortPress = {
                        shouldShowDialog.value = false
//                        markerType = ""
                        exitActivity()
                        context.startActivity(Intent(context, MainActivity::class.java))
                    }
                )
            } else {
                IconButton(
                    onClick = {
//                        markerType = ""
                        navController.popBackStack()}, // No-op: we handle interaction manually
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(60.dp)
                        .border(1.dp, Color.Black, CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.Black,
                        containerColor = Color.LightGray
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close icon",
                        tint = Color.Black
                    )
                }
            }
        }
    }
}
private suspend fun handleLongPressProgress(
    isPressed: () -> Boolean,
    durationMillis: Int,
    updateProgress: (Float) -> Unit,
    onComplete: () -> Unit
) {
    val startTime = withFrameNanos { it }

    var completed = false
    while (isPressed() && !completed) {
        completed = updateProgressAndCheckCompletion(
            startTime = startTime,
            durationMillis = durationMillis,
            updateProgress = updateProgress
        )
    }

    if (completed) {
        onComplete()
    } else {
        updateProgress(0f)
    }
}

private suspend fun updateProgressAndCheckCompletion(
    startTime: Long,
    durationMillis: Int,
    updateProgress: (Float) -> Unit
): Boolean {
    val currentTime = withFrameNanos { it }
    val elapsed = (currentTime - startTime) / 1_000_000
    val progress = (elapsed.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    updateProgress(progress)
    return progress >= 1f
}

@Composable
fun LongPressProgressButtonWithInteraction(
    navController: NavController,
    modifier: Modifier = Modifier,
    durationMillis: Int = 650,
    onShortPress: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isPressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isPressed = true
                    job = scope.launch {
                        handleLongPressProgress(
                            isPressed = { isPressed },
                            durationMillis = durationMillis,
                            updateProgress = { progress = it },
                            onComplete = {
                                navController.navigate("long_press_marker_screen") {
                                    popUpTo("long_press_marker_screen") { inclusive = true }
                                }
                            }
                        )
                    }
                }

                is PressInteraction.Release,
                is PressInteraction.Cancel -> {
                    if (progress < 1f) {
                        onShortPress()
                    }
                    isPressed = false
                    job?.cancel()
                    job = null
                    progress = 0f
                }
            }
        }
    }
    Box(modifier = modifier.size(100.dp)) {
        if (isPressed) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxSize(),
                startAngle = 180f,
                endAngle = 0f,
                strokeWidth = 7.dp
            )
        } else {
            CurvedLayout(modifier = Modifier.fillMaxSize()) {
                basicCurvedText(
                    "Hold for more",
                    CurvedModifier.padding(4.dp),
                    style = {
                        CurvedTextStyle(fontSize = 12.sp, color = Color.White)
                    },
                )
            }
        }

        IconButton(
            onClick = {}, // No-op: we handle interaction manually
            interactionSource = interactionSource,
            modifier = Modifier
                .align(Alignment.Center)
                .size(60.dp)
                .border(1.dp, Color.Black, CircleShape),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = Color.Black,
                containerColor = Color.LightGray
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close icon",
                tint = Color.Black
            )
        }
    }
}


@Composable
fun LongPressScreen(navController: NavController, droppedMarkerListSetting: List<DataRepository.DroppedMarker>, shouldShowDialog: MutableState<Boolean>, shouldShowSuccess: MutableState<Boolean>, removeMarker: MutableState<Boolean>) {
    var visible by remember { mutableStateOf(true) }

    WearTAKCIVTheme {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .animateEnterExit(
                        // Slide in/out the inner box.
                        enter = slideInVertically(), exit = slideOutVertically()
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 27.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        modifier = Modifier.padding(7.dp),
                        onClick = {
                            navController.navigate("dropped_marker_screen") { launchSingleTop = true }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.DarkGray,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Dropped Markers",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                    Row(

                    ) {
                        //Currently back button, can change to cancel button or another long press if needed
                        IconButton(
                            onClick = {
                                navController.navigate("marker_screen") {
                                    popUpTo(("marker_screen")) {
                                        inclusive = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(60.dp)
                                .border(1.dp, color = Color.Black, shape = CircleShape),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color.Black,
                                containerColor = Color.LightGray
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Close icon",
                                tint = Color.Black
                            )
                        }
//                        Button(
//                            modifier = Modifier.padding(7.dp),
//                            onClick = {
//                                Log.d(TAG, "View Received Markers clicked")
//                                navController.navigate("received_marker_screen")
//                            },
//                            colors = ButtonDefaults.buttonColors(
//                                containerColor = Color.DarkGray,
//                                contentColor = Color.White
//                            )
//                        ) {
//                            Text(
//                                text = "Received Markers",
//                                fontSize = 13.sp,
//                                fontWeight = FontWeight.SemiBold,
//                                textAlign = TextAlign.Center
//                            )
//                        }
                    }

                    Button(
                        modifier = Modifier.padding(7.dp),
                        enabled = (droppedMarkerListSetting.isNotEmpty() && droppedMarkerListSetting.all { it.cotType != MarkerCotType.CLEAR }),
                        onClick = {
                            removeMarker.value = false
                            shouldShowDialog.value = true
                            shouldShowSuccess.value = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Clear Last Marker",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }

            }
        }
    }
}

@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun DroppedMarkerSetting(navController: NavController, viewModel: MarkerViewModel, droppedMarkerListSetting: List<DataRepository.DroppedMarker>, shouldShowDialog: MutableState<Boolean>, shouldShowSuccess: MutableState<Boolean>) {

    Log.d(TAG, "DropMarkerSetting called")

    WearTAKCIVTheme {
        val listState = rememberLazyListState()
        Scaffold(
            positionIndicator = {
                PositionIndicator(lazyListState = listState)
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(bottom = 16.dp, top = 15.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    state = listState,
                    contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.clear_dropped_markers),
                            color = Color.White,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp)
                        )
                    }
                    if (droppedMarkerListSetting.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_dropped_markers),
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 20.dp)
                            )
                        }
                    }
                        //TODO: Ideally have list in reverse order, but doing this causes UI to glitch right now
                        itemsIndexed(
                            items = droppedMarkerListSetting,
                            key = { _, droppedMarker -> droppedMarker.uid }
                        ){ index, droppedMarker ->
                            SwipeableMarkerChip(
                                droppedMarker.markerType.type,
                                droppedMarker.title,
                                droppedMarker.cotType,
                                droppedMarker.enqueuedToBeSent,
                                droppedMarker.timeDropped,
                                onPrimaryActionClick = {
                                    Log.d(TAG, "Request clear for marker uid=${droppedMarker.uid}")
                                    CoroutineScope(Dispatchers.Main).launch {
                                        viewModel.requestClearSpecificMarker(droppedMarker.uid)
                                    }
                                },
                                onSecondaryActionClick = {
                                    navController.navigate("edit_marker_screen/$index")
                                },
                            )
                        }
                        if (droppedMarkerListSetting.isNotEmpty() && droppedMarkerListSetting.all { it.cotType != MarkerCotType.CLEAR }) {
                            item {
                                Chip(
                                    onClick = {
                                        shouldShowDialog.value = true
                                        shouldShowSuccess.value = false
                                    },
                                    label = {
                                        Text(
                                            text = "Clear All Markers",
                                            textAlign = TextAlign.Center,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    colors = ChipDefaults.chipColors(
                                        backgroundColor = Color.Red,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                )
                            }
                        }
                }
                Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                    WearTAKBackButton(
                        onClick = {
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun SwipeableMarkerChip(
    markerType: String,
    markerTitle: String,
    cotType: MarkerCotType,
    enQueuedToBeSent: Boolean,
    timeStamp: Long,
    onPrimaryActionClick: () -> Unit,
    onSecondaryActionClick: () -> Unit
) {
    val revealState = rememberRevealState()
    val scope = rememberCoroutineScope()
    if (cotType != MarkerCotType.CLEAR) {
        SwipeToRevealChip(
            revealState = revealState,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    // Use custom actions to make the primary and secondary actions accessible
                    customActions = listOf(
                        CustomAccessibilityAction("Delete Marker") {
                            /* Add the primary action click handler here */
                            true
                        },
                        CustomAccessibilityAction("Edit Marker") {
                            /* Add the secondary click handler here */
                            true
                        }
                    )
                },
            primaryAction = {
                SwipeToRevealPrimaryAction(
                    revealState = revealState,
                    icon = { Icon(SwipeToRevealDefaults.Delete, stringResource(R.string.delete)) },
                    label = { androidx.wear.compose.material.Text(stringResource(R.string.delete)) },
                    onClick = {
                        onPrimaryActionClick()
                    }
                )
            },
            secondaryAction = {

                SwipeToRevealSecondaryAction(
                    revealState = revealState,
                    onClick = { onSecondaryActionClick() }
                ) {
                    Icon(SwipeToRevealDefaults.MoreOptions, "More Options")
                }
            },
            onFullSwipe = { onPrimaryActionClick() }
        ) {
            Chip(
                enabled = true,
                icon = {
                    Icon(
                        markerIconFromString(markerType),
                        contentDescription = "",
                        tint = Color.Unspecified,
                        modifier =
                            if (enQueuedToBeSent)
                                Modifier
                                    .size(30.dp)
                                    .alpha(.5F)
                            else
                                Modifier.size(30.dp)
                    )
                },
                colors = if (enQueuedToBeSent) {
                    ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray.copy(alpha = .5F),
                        contentColor = Color.White.copy(alpha = .5F),
                        secondaryContentColor = Color.LightGray.copy(alpha = .5F)
                    )
                } else {
                    ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    )
                },
                label = {
                    Column {
                        FittedTopMetricText(
                            value = markerTitle.ifBlank { markerTextFromString(markerType) },
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = if (enQueuedToBeSent) Color.White.copy(alpha = .5F) else Color.White,
                                fontWeight = FontWeight.SemiBold)
                        )
                        if (markerTitle.isNotEmpty()) {
                            Text(
                                text = markerTextFromString(markerType),
                                maxLines = 2,
                                fontSize = 12.sp,
                                overflow = TextOverflow.Ellipsis,
//                        modifier = Modifier
//                            .align(Alignment.CenterVertically),
                                color = if (enQueuedToBeSent) Color.White.copy(alpha = .5F) else Color.White,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                },
                secondaryLabel = {
                    Text(
                        utcToHoursMinutesSeconds(timeStamp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (enQueuedToBeSent) Color.White.copy(alpha = .5F) else Color.White,
                    )
                },
                onClick = { scope.launch { revealState.animateTo(targetValue = RevealValue.RightRevealing) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )
        }
    } else {
        Chip(
            enabled = false,
            icon = {
                Icon(
                    markerIconFromString(markerType),
                    contentDescription = "",
                    tint = Color.Gray,
                    modifier = Modifier.size(30.dp)
                )
            },
            colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
            label = {
                Column {
                    Text(
                        text =
                            markerTitle.ifBlank { markerTextFromString(markerType) },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
//                    modifier = Modifier
//                        .align(Alignment.CenterVertically),
                        color = Color.White.copy(alpha = .5F),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (markerTitle.isNotEmpty()) {
                        Text(
                            text = markerTextFromString(markerType),
                            maxLines = 2,
                            fontSize = 12.sp,
                            overflow = TextOverflow.Ellipsis,
//                        modifier = Modifier
//                            .align(Alignment.CenterVertically),
                            color = Color.White.copy(alpha = .5F),
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            },
            secondaryLabel = {
                Text(
                    utcToHoursMinutesSeconds(timeStamp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = .5F),
                )
            },
            onClick = {  },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 3.dp)
        )
    }
}

fun utcToHoursMinutesSeconds(time: Long): String {
    val instant = Instant.ofEpochMilli(time)
    val localTime = instant.atZone(ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss a")
    return localTime.format(formatter)
}

@Composable
fun DonutArcButton(
    startAngle: Float,
    sweepAngle: Float,
    alignment: Alignment,
    markerTypeText: String,
    marker: Painter,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(donutArcShape(startAngle, sweepAngle))
            .background(Color.DarkGray)
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = alignment
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = marker,
                contentDescription = "marker picture",
                modifier = Modifier.size(25.dp),
                alignment = alignment
            )
            Text(
                text = markerTypeText,
                color = Color.White,
                fontSize = 10.sp
            )
        }
    }
}

fun donutArcShape(
    startAngle: Float,
    sweepAngle: Float,
    innerRadiusRatio: Float = 0.5f
): GenericShape {
    return GenericShape { size, _ ->
        val outerRadius = min(size.width, size.height) / 2f
        val innerRadius = outerRadius * innerRadiusRatio
        val center = Offset(size.width / 2f, size.height / 2f)

        val outerRect = Rect(center = center, radius = outerRadius)
        val innerRect = Rect(center = center, radius = innerRadius)

        val path = Path().apply {
            arcTo(
                rect = outerRect,
                startAngleDegrees = startAngle,
                sweepAngleDegrees = sweepAngle,
                forceMoveTo = true
            )
            arcTo(
                rect = innerRect,
                startAngleDegrees = startAngle + sweepAngle,
                sweepAngleDegrees = -sweepAngle,
                forceMoveTo = false
            )
            close()
        }

        addPath(path)

    }
}

@Composable
fun markerIconFromString(markerType: String): Painter {
    return markerPainter(markerVisualSpec(markerType))
}

fun markerTextFromString(markerType: String): String {
    return markerVisualSpec(markerType).label
}

@Composable
internal fun markerPainter(spec: MarkerVisualSpec): Painter =
    spec.tintArgb?.let { tintedPainter(spec.icon, Color(it)) } ?: painterResource(spec.icon)

@Composable
fun MarkerSuccessDialog(
    shouldShowDialog: MutableState<Boolean>,
    successBoolean : MutableState<Boolean>,
    successImage: @Composable () -> Unit,
    additionalText: @Composable () -> Unit
    ) {
    Dialog(onDismissRequest = { shouldShowDialog.value = false }) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .height(200.dp),
            colors = CardDefaults.cardColors(containerColor = colors.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (successBoolean.value) {
                    successImage()
                    Spacer(modifier = Modifier.height(10.dp))
                   Text(
                        text = stringResource(R.string.success),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    additionalText()
                } else {
                    Icon(
                        Icons.Rounded.Clear,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Red
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // TODO: Add more context to the error, for example "not connected to server & mesh/ditto is disabled. Enable meshing to fix"
                    androidx.wear.compose.material.Text(
                        text = stringResource(R.string.marker_stored_error_msg),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun DropMarkerConfirmationDialog(
    viewModel: MarkerViewModel,
    shouldShowDialog: MutableState<Boolean>,
    isConfirmed: MutableState<Boolean>,
    confirmFunction: () -> Unit,
) {
    val markerScope = CoroutineScope(Dispatchers.IO)
    if (shouldShowDialog.value) {
        WearTAKCIVTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(bottom = 16.dp, top = 15.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter =
                            markerIconFromString(markerType),
                        modifier = Modifier.size(30.dp),
                        contentDescription = "Marker Type",
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        "Drop $markerType marker?",
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    var textFieldTitle = GenericOutlinedTextField(
                        markerTitleSettingsState,
                        "Optional Title",
                        ""
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    var textFieldRemark = GenericOutlinedTextField(
                        markerRemarkSettingsState,
                        "Optional Remark",
                        ""
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(
                            onClick = {
                                shouldShowDialog.value = false
                                isConfirmed.value = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Gray,
                                contentColor = Color.Black
                            ),
                                ) { Text("Cancel")}
                        TextButton(
                            onClick = {
                                shouldShowDialog.value = false
                                isConfirmed.value = true
                                markerTitleSettingsState.value = textFieldTitle.text
                                markerRemarkSettingsState.value = textFieldRemark.text
                                val time = System.currentTimeMillis()
                                markerScope.launch {
                                    viewModel.requestMarkerDrop(markerType,
                                        textFieldTitle.text, textFieldRemark.text, time)
                                }
                                Log.d(TAG, "${markerTitleSettingsState.value}")
                                confirmFunction()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = Color.Black
                            ),
                            ) { Text("Confirm")}
                    }
                }
            }
        }
    }
}

@Composable
fun ClearMarkerConfirmationDialog(
    shouldShowDialog: MutableState<Boolean>,
    description: String,
    icon: @Composable () -> Unit,
    isConfirmed: MutableState<Boolean>,
    confirmFunction: () -> Unit,
) {
    if (shouldShowDialog.value) {
        AlertDialog(
            containerColor = colors.background,
            modifier = Modifier.fillMaxSize(),
            onDismissRequest = {
                shouldShowDialog.value = false
            },
            icon = {
                icon()
            },
            text = {
                Text(
                    description,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = if (description.length > 40) 13.sp else 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        shouldShowDialog.value = false
                        isConfirmed.value = true
                        confirmFunction()
                    },
                    modifier = Modifier.fillMaxWidth(0.45f),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        color = Color.Black
                    )
                }
            },

            dismissButton = {
                Button(
                    onClick = {
                        shouldShowDialog.value = false
                        isConfirmed.value = false
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth(0.45f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                    )
                }
            }
        )
    }
}

@Composable
private fun SuccessfulCotSentCheck(
    shouldShowConfirm: MutableState<Boolean>,
    anyEndpointConnected: Boolean?,
    context: Context,
    exitActivity: () -> Unit,
    successBoolean: MutableState<Boolean>,
    location: Location?,
    locationChecked: Boolean,
    dialog: @Composable () -> Unit,
    successFunction: () -> Unit
){
    if (shouldShowConfirm.value) {
        successBoolean.value = (location != null && locationChecked && anyEndpointConnected == true)
        LaunchedEffect(Unit) {
            delay(1000) // Delay for 5 seconds
            shouldShowConfirm.value = false // Dismiss the dialog
            exitActivity()
            successFunction()
//            context.startActivity(Intent(context, MainActivity::class.java))
        }
        dialog()
    }
}

@Composable
fun EditSelectedMarker(
    navController: NavController,
    index: Int,
    context: Context,
    viewModel: MarkerViewModel,
    droppedMarkerListSetting: List<DataRepository.DroppedMarker>,
    showLocationChangeDialog: MutableState<Boolean>,
    showDeleteMarkerDialog: MutableState<Boolean>,
    exitActivity: () -> Unit
) {
    Log.i(TAG, "Edited marker list: $droppedMarkerListSetting")
    var uid by remember { mutableStateOf(droppedMarkerListSetting.getOrNull(index)?.uid.orEmpty()) }
    var title by remember { mutableStateOf(droppedMarkerListSetting.getOrNull(index)?.title.orEmpty()) }
    var locationLat by remember { mutableDoubleStateOf(droppedMarkerListSetting.getOrNull(index)?.location?.lat ?: 0.0) }
    var locationLon by remember { mutableDoubleStateOf(droppedMarkerListSetting.getOrNull(index)?.location?.lon ?: 0.0) }
    val markerType = droppedMarkerListSetting.getOrNull(index)?.markerType?.type.orEmpty()
    val markerLabel = markerTextFromString(markerType)
    val enQueuedToBeSent = droppedMarkerListSetting.getOrNull(index)?.enqueuedToBeSent
    var remark by remember { mutableStateOf(droppedMarkerListSetting.getOrNull(index)?.remark.orEmpty()) }
    val time = droppedMarkerListSetting.getOrNull(index)?.timeDropped ?: 0L
    val markerVectorFlow = remember(index) { viewModel.markerVectorUi(index) }
    val markerVectorUi by markerVectorFlow.collectAsStateWithLifecycle()
    val activeBloodhound by bhRepo.activeBhItemMap.collectAsStateWithLifecycle(initialValue = null)
    val validPosition = droppedMarkerListSetting.getOrNull(index)?.location != null &&
        locationLat.isFinite() && locationLon.isFinite() &&
        locationLat in -90.0..90.0 && locationLon in -180.0..180.0

    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 30.dp)
                    .verticalScroll(rememberScrollState()),

                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painter = markerIconFromString(markerType),
                    contentDescription = "Favorite icon",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(25.dp),
                )
                FittedTopMetricText(
                    value = title,
                    style = TextStyle(fontSize = 15.sp, textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold, color = Color.White),
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp, bottom = 5.dp)
                        .align(Alignment.CenterHorizontally)
                )
                if (enQueuedToBeSent == true) {
                    Text(
                        text = "Queued to be sent",
                        fontSize = if ("text".length!! < 20) 14.sp else 12.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 5.dp)
                    )
                }
                Text(
                    text = "${markerTextFromString(markerType)} | ${utcToHoursMinutesSeconds(time)}",
                    fontSize = if ("text".length!! < 20) 13.sp else 11.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 5.dp)
                )
                if (remark.isNotEmpty()) {
                    Text(
                        text = "Remark: $remark",
                        fontSize = if ("text".length!! < 20) 13.sp else 11.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 5.dp)
                    )
                }
                if (activeBloodhound?.uid == uid) {
                    Text(
                        text = "Bloodhounding",
                        fontSize = if ("text".length!! < 20) 13.sp else 11.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 5.dp)
                    )
                }
                if (validPosition) MarkerBearingDistance(
                    distance = markerVectorUi?.distance,
                    lat = locationLat,
                    lon = locationLon,
                    orientation = markerVectorUi?.orientation ?: 0F
                )
                PointBloodhoundAction(
                    isActive = activeBloodhound?.uid == uid,
                    enabled = uid.isNotBlank() && validPosition,
                    onBloodhound = {
                            bhRepo.addMarkerTarget(
                                uid = uid,
                                title = title.ifBlank { markerLabel },
                                markerType = markerType,
                                lat = locationLat,
                                lon = locationLon,
                                time = time,
                                tracksLocalMarker = true
                            )

                            bhRepo.requestActivateBloodhound(uid)
                            requestBloodhoundComplicationUpdate(context)

                            Log.i(BloodhoundActivity.Constants.TAG, "Requesting Ack and Activation of bloodhound")
                    },
                    onInPosition = {
                        bhRepo.requestInPosition(uid)
                        requestBloodhoundComplicationUpdate(context)
                    },
                    onTarget = { focusPointOnMap(context, locationLat, locationLon) },
                    onDelete = { showDeleteMarkerDialog.value = true }
                )
                Spacer(modifier = Modifier.height(10.dp))

                Chip(
                    onClick = { navController.navigate("edit_marker_title/$index")},
                    enabled = true,
                    label = { androidx.wear.compose.material.Text(text = "Change Title") },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )
                Chip(
                    onClick = { navController.navigate("edit_marker_remark/$index")},
                    enabled = true,
                    label = { androidx.wear.compose.material.Text(text = if (remark.isNotEmpty()) "Change Remark" else "Add Remark") },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )
                Chip(
                    onClick = { navController.navigate("edit_marker_type/$index")},
                    enabled = true,
                    label = { androidx.wear.compose.material.Text(text = "Change Marker") },
                    secondaryLabel = {androidx.wear.compose.material.Text(markerTextFromString(markerType))},
                    icon = {Icon(
                        markerIconFromString(markerType),
                        contentDescription = "",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(30.dp)
                    )},
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )
                Chip(
                    onClick = { showLocationChangeDialog.value = true },
                    enabled = true,
                    label = { androidx.wear.compose.material.Text(text = "Move to Current Location") },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                WearTAKBackButton(
                    onClick = {
                        exitActivity()
//                        navController.popBackStack()
                    })
            }
        }
    }
}

@Composable
fun MarkerBearingDistance(
    distance: Double?,
    lat: Double,
    lon: Double,
    orientation: Float
) {
    val distanceText = distance?.let {
        com.aitech.weartak_civ.ui.formatter.UnitFormatter.distanceKm(it, currentUnitPrefs()) + " away"
    } ?: "-- away"
    val orientationText = "${orientation.roundToInt()}° ${bearingToCardinal(orientation)}"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ){
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
            androidx.wear.compose.material.Text(
                text = orientationText,
                fontSize = 13.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.LightGray,
                fontWeight = FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FittedTopMetricText(
                value = distanceText,
                style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            )
            FittedTopMetricText(
                value = String.format(java.util.Locale.US, "%.5f", lat),
                style = TextStyle(fontSize = 11.sp, color = Color.LightGray)
            )
            FittedTopMetricText(
                value = String.format(java.util.Locale.US, "%.5f", lon),
                style = TextStyle(fontSize = 11.sp, color = Color.LightGray)
            )
            FittedTopMetricText(
                value = latLonToMgrs(lat, lon),
                style = TextStyle(fontSize = 11.sp, color = Color.LightGray)
            )
        }
    }
}

@Composable
fun MarkerTitleRemarkSetting(
    navController: NavController,
    index: Int,
    string: String,
    remarkOrTitle: String,
    droppedMarkerListSetting: List<DataRepository.DroppedMarker>,
    viewModel: MarkerViewModel) {

    var item by remember { mutableStateOf(string) }

    val uid = droppedMarkerListSetting.getOrNull(index)?.uid.orEmpty()

    var newItem by remember {mutableStateOf(item)}
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        GenericOutlinedTextField1(
            value = item,
            onValueChange = { newText ->
                newItem = newText},
            label = if (remarkOrTitle == "remark") "Remark" else "Title",
            placeholder = if (remarkOrTitle == "remark") "Enter a remark" else "Enter a title"
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            WearTAKConfirmCancelButtons(
                onClickCancel = {
                    Log.i(TAG, "Title or Remark NOT saved")
                    navController.popBackStack()
                },
                onClickConfirm = {
                    if (item != newItem) {
                        if (remarkOrTitle == "title") {
                            viewModel.requestChangeMarkerTitle(uid, newItem)
                        } else if (remarkOrTitle == "remark"){
                            viewModel.requestChangeMarkerRemark(uid, newItem)
                        }
                    }
                    Log.i(TAG, "Settings saved with marker title or remark: $newItem")
                    navController.popBackStack()
                })
        }
    }
}

@Composable
fun GenericOutlinedTextField1(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String
): TextFieldValue {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value))
    }

    // Keep internal TextFieldValue in sync if 'value' changes externally
    LaunchedEffect(value) {
        if (value != textFieldValue.text) {
            textFieldValue = TextFieldValue(value)
        }
    }

    OutlinedTextField(
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
            onValueChange(it.text)   // <- push changes upward (to ViewModel)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        label = { Text(text = label, color = colors.primary) },
        placeholder = { Text(text = placeholder, color = colors.primary) },
        textStyle = TextStyle(color = colors.primary)
    )

    return textFieldValue
}

@Composable
fun ChangeMarkerLocationConfirmationDialog(
    showLocationChangeDialog: MutableState<Boolean>,
    index: Int,
    viewModel: MarkerViewModel,
    droppedMarkerListSetting: List<DataRepository.DroppedMarker>,
    isConfirmed: MutableState<Boolean>,
    confirmFunction: () -> Unit,
) {
//    val uid = droppedMarkerListSetting.getOrNull(index)?.uid.orEmpty()
    var locationLat by remember { mutableDoubleStateOf(droppedMarkerListSetting.getOrNull(index)?.location?.lat ?: 0.0) }
    var locationLon by remember { mutableDoubleStateOf(droppedMarkerListSetting.getOrNull(index)?.location?.lon ?: 0.0) }

    if (showLocationChangeDialog.value) {
        AlertDialog(
            containerColor = colors.background,
            modifier = Modifier.fillMaxSize(),
            onDismissRequest = {
                showLocationChangeDialog.value = false
            },
            icon = {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = ""
                )
            },
            text = {
                Column {
                    Text(
                        "Move marker to current location?",
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        "$locationLat, $locationLon",
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocationChangeDialog.value = false
                        isConfirmed.value = true
                        confirmFunction()
                    },
                    modifier = Modifier.fillMaxWidth(0.45f),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        color = Color.Black
                    )
                }
            },

            dismissButton = {
                Button(
                    onClick = {
                        showLocationChangeDialog.value = false
                        isConfirmed.value = false
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth(0.45f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                    )
                }
            }
        )
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun NewMarkerCivApp(exitActivity: () -> Unit, context: Context, viewModel: MarkerViewModel, index: Int, route: String?, fromMaps: Boolean) {
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by viewModel.licenseKey.collectAsStateWithLifecycle()
    //    val shouldShowDialog = remember { mutableStateOf(false) }
    val shouldShowConfirm = remember { mutableStateOf(false) }
    val shouldShowDropMarkerConfirm = remember {mutableStateOf(false)}
    val shouldShowClearLastDialog = remember { mutableStateOf(false) }
    val shouldShowClearLastConfirm = remember { mutableStateOf(false) }
    val shouldShowClearAllDialog = remember { mutableStateOf(false) }
    val shouldShowClearAllConfirm = remember { mutableStateOf(false) }
    val showLocationChangeDialog = remember {mutableStateOf(false)}
    val showLocationChangeConfirm = remember {mutableStateOf(false)}
    val showTypeChangeConfirm = remember {mutableStateOf(false)}
    val removeMarker = remember {mutableStateOf(false)}
    val shouldShowClearMarkerDialog = remember { mutableStateOf(false) }
    val shouldShowClearMarkerConfirm = remember { mutableStateOf(false) }
    val anyEndpointConnected by viewModel.anyEndpointConnected.collectAsStateWithLifecycle()
    val droppedMarkerListSetting by viewModel.droppedMarkerListSetting.collectAsStateWithLifecycle()
    val receivedMarkerListSetting by viewModel.receivedMarkerListSetting.collectAsStateWithLifecycle()
    val location = viewModel.location
    var locationEnabled by remember { mutableStateOf(enableLocationService) }
    val successBoolean = remember { mutableStateOf(false) }
// react to changes in the license.
    val actualStartDestination = when {
        route?.contains("edit_marker_screen") == true && index != -1 ->
            "edit_marker_screen/$index"
        route?.contains("received_marker_detail") == true && index != -1 ->  // add this
            "received_marker_detail/$index"
        else ->
            "marker_screen"
    }

    LaunchedEffect(licenseKey) {
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
                exitActivity()
                navController.navigate(actualStartDestination) {
//                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
        }
    }


    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (!isLicenseValid()) "invalid_license_screen" else actualStartDestination
    ) {
        composable("marker_screen" ) {
            RadialSelectionScreen(
                exitActivity = { exitActivity() },
                shouldShowDialog = shouldShowConfirm,
                onClickRightButton = {
                    markerType = MarkerType.FRIENDLY.displayName
                    shouldShowConfirm.value = true
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Drop request for $markerType ...")
                },
                onClickBottomButton = {
                    markerType = MarkerType.UNKNOWN.displayName
                    shouldShowConfirm.value = true
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Drop request for $markerType ...")
                },
                onClickLeftButton = {
                    markerType = MarkerType.NEUTRAL.displayName
                    shouldShowConfirm.value = true
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Drop request for $markerType ...")
                },
                onClickTopButton = {
                    markerType = MarkerType.HOSTILE.displayName
                    shouldShowConfirm.value = true
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Drop request for $markerType ...")
                },
                longPressBoolean = true,
                navController = navController
            )
//            MarkerSelectionScreen(exitActivity = {exitActivity()}, shouldShowConfirm, navController)
            markerTitleSettingsState.value = ""
            markerRemarkSettingsState.value = ""

            DropMarkerConfirmationDialog(
                viewModel = viewModel,
                shouldShowDialog = shouldShowConfirm,
                isConfirmed = shouldShowDropMarkerConfirm,
                // TODO Senni: Should UI check for location?? We are anyway queuing it to be sent later
                // TODO Senni: Also if anyEndpointConnected is false, backend will send it later. So UI should just say that it will be sent later
                confirmFunction = {
                    successBoolean.value = (location != null && locationEnabled && anyEndpointConnected)
                }
            )

            SuccessfulCotSentCheck(
                shouldShowDropMarkerConfirm,
                anyEndpointConnected,
                context,
                {
                    exitActivity()
                    context.startActivity(Intent(context, MainActivity::class.java))
                },
                successBoolean,
                location,
                locationEnabled,
                {
                    MarkerSuccessDialog(
                        shouldShowConfirm,
                        successBoolean,
                        {
                            Image(
                                painter =
                                    markerIconFromString(markerType),
                                contentDescription = "Marker Type",
                            )
                        },
                        {
                            Spacer(modifier = Modifier.height(10.dp))
                            when {
                                markerTitleSettingsState.value.isNullOrEmpty() && markerRemarkSettingsState.value.isNullOrEmpty() ->
                                    Text(
                                    text = "$markerType Marker Dropped",
                                    textAlign = TextAlign.Center,
                                    color = Color.White
                                )
                                !markerTitleSettingsState.value.isNullOrEmpty() && markerRemarkSettingsState.value.isNullOrEmpty() ->
                                    Text(
                                        text = "Dropped $markerType: ${markerTitleSettingsState.value}",
                                        textAlign = TextAlign.Center,
                                        color = Color.White
                                    )
                                markerTitleSettingsState.value.isNullOrEmpty() && !markerRemarkSettingsState.value.isNullOrEmpty() ->
                                    Text(
                                        text = "Dropped $markerType\nRemark: ${markerRemarkSettingsState.value}",
                                        textAlign = TextAlign.Center,
                                        color = Color.White
                                    )
                                else ->
                                    Text(
                                        text = "Dropped $markerType: ${markerTitleSettingsState.value}\nRemark: ${markerRemarkSettingsState.value}",
                                        textAlign = TextAlign.Center,
                                        color = Color.White
                                    )
                            }
                        }
                    )
                },
                {}
            )
        }
//        composable(
//            "license_setting_screen") {
//            LicenseKeyScreen(navController, licenseSettingsState, context)
//        }
        composable(
            "long_press_marker_screen"
        ) {
            LongPressScreen(
                navController,
                droppedMarkerListSetting,
                shouldShowClearLastDialog,
                shouldShowClearLastConfirm,
                removeMarker
            )

            if (droppedMarkerListSetting.isNotEmpty() ) {
                ClearMarkerConfirmationDialog(
                    shouldShowClearLastDialog,
                    "Clear Last Marker?\n\r${markerTextFromString(droppedMarkerListSetting.last().markerType.type)}: ${droppedMarkerListSetting.last().title}\n\r" + utcToHoursMinutesSeconds(
                        droppedMarkerListSetting.last().timeDropped
                    ) + "\n" + stringResource(R.string.owned_pin_delete_help),
                    {
                        Icon(
                            markerIconFromString(droppedMarkerListSetting.last().markerType.type),
                            modifier = Modifier.size(35.dp),
                            contentDescription = null,
                            tint = Color.Unspecified
                        )
                    }, shouldShowClearLastConfirm,
                    {
                        // Trigger backend clear-last;
                        CoroutineScope(Dispatchers.Main).launch {
                            viewModel.requestClearLast()
                        }
                        // dialog UX logic
                        successBoolean.value = (location != null && locationEnabled && anyEndpointConnected)
                    }
                )
            }

                if (shouldShowClearLastConfirm.value) {

                    LaunchedEffect(Unit) {
                        delay(2000) // Delay for 5 seconds
                        shouldShowClearLastConfirm.value = false // Dismiss the dialog
                        successBoolean.value = false
                        removeMarker.value = false
                        exitActivity()
                        context.startActivity(Intent(context, MainActivity::class.java))
                    }
                    Icon(
                        Icons.Rounded.Check,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Green
                    )
                    MarkerSuccessDialog(
                        shouldShowClearLastConfirm,
                        successBoolean,
                        {Icon(
                            Icons.Rounded.Check,
                            modifier = Modifier.size(25.dp),
                            contentDescription = null,
                            tint = Color.Green
                        )},
                        {}
                    )

                }
        }
        composable(
            "dropped_marker_screen") {
            DroppedMarkerSetting(navController, viewModel, droppedMarkerListSetting, shouldShowClearAllDialog, shouldShowClearAllConfirm)

            ClearMarkerConfirmationDialog(
                shouldShowClearAllDialog,
                "Clear All Dropped Markers?\n" + stringResource(R.string.owned_pin_delete_help),
                {Icon(
                    Icons.Outlined.Delete,
                    modifier = Modifier.size(25.dp),
                    contentDescription = null,
                    tint = Color.LightGray)
                },
                shouldShowClearAllConfirm,
                {
                    // Trigger backend clear-last;
                    CoroutineScope(Dispatchers.Main).launch {
                        viewModel.requestClearAllMarkers()
                    }
                    // dialog UX logic
                    successBoolean.value = (location != null && locationEnabled && anyEndpointConnected)
                }
            )


            SuccessfulCotSentCheck(
                shouldShowClearAllConfirm,
                anyEndpointConnected,
                context,
                {
                    markerTitleSettingsState.value = ""
                    markerRemarkSettingsState.value = ""
                    exitActivity()
                    context.startActivity(Intent(context, MainActivity::class.java))
                },
                successBoolean,
                location,
                locationEnabled,
                {
                    MarkerSuccessDialog(
                        shouldShowClearAllConfirm,
                        successBoolean,
                        {Icon(
                            Icons.Rounded.Check,
                            modifier = Modifier.size(25.dp),
                            contentDescription = null,
                            tint = Color.Green
                        )},
                        {}
                    )
                },
                {}
            )
        }
        composable(
            "received_marker_detail/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            val marker = receivedMarkerListSetting.getOrNull(index)

            if (marker == null) {
                Box(
                    modifier = Modifier.fillMaxSize().background(colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.wear.compose.material.CircularProgressIndicator()
                }
                return@composable
            }

            val markerVectorFlow = remember(index) { viewModel.markerVectorUiForReceived(index) }
            val markerVectorUi by markerVectorFlow.collectAsStateWithLifecycle()
            val loc = marker.location
            val validPosition = loc != null && loc.lat.isFinite() && loc.lon.isFinite() &&
                loc.lat in -90.0..90.0 && loc.lon in -180.0..180.0
            val activeBloodhound by bhRepo.activeBhItemMap.collectAsStateWithLifecycle(initialValue = null)

            WearTAKCIVTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .padding(bottom = 16.dp, top = 15.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                            .align(Alignment.TopCenter)
                            .padding(bottom = 30.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = markerIconFromString(marker.markerType),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(25.dp)
                        )
                        Text(
                            text = marker.title.ellipsizeMarkerTitle(14),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)
                        )
                        Text(
                            text = "${markerTextFromString(marker.markerType)} | ${utcToHoursMinutesSeconds(marker.timeReceived)}",
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                        if (loc != null && validPosition) MarkerBearingDistance(
                                distance = markerVectorUi?.distance,
                                lat = loc.lat,
                                lon = loc.lon,
                                orientation = markerVectorUi?.orientation ?: 0F
                        )
                        PointBloodhoundAction(
                                isActive = activeBloodhound?.uid == marker.uid,
                                onBloodhound = {
                                    if (loc == null) return@PointBloodhoundAction
                                    bhRepo.addMarkerTarget(
                                        uid = marker.uid,
                                        title = marker.title.ifBlank { "Marker" },
                                        markerType = marker.markerType,
                                        lat = loc.lat,
                                        lon = loc.lon,
                                        time = marker.timeReceived,
                                        isCamera = marker.isCamera
                                    )
                                    bhRepo.requestActivateBloodhound(marker.uid)
                                    requestBloodhoundComplicationUpdate(context)
                                    Log.i(BloodhoundActivity.Constants.TAG, "Requesting Ack and Activation of bloodhound")
                                },
                                onInPosition = {
                                    bhRepo.requestInPosition(marker.uid)
                                    requestBloodhoundComplicationUpdate(context)
                                },
                                onTarget = {
                                    if (loc != null) focusPointOnMap(context, loc.lat, loc.lon)
                                },
                                enabled = marker.uid.isNotBlank() && validPosition,
                                onDelete = {
                                    viewModel.deleteReceivedMarker(marker.uid)
                                    requestBloodhoundComplicationUpdate(context)
                                    exitActivity()
                                }
                        )
                    }
                    Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                        WearTAKBackButton(onClick = { exitActivity() })
                    }
                }
            }
        }


        composable(
            "edit_marker_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.i(TAG, "edit_marker_screen index: $index")
            val uid = droppedMarkerListSetting.getOrNull(index)?.uid.orEmpty()
            val markerType = droppedMarkerListSetting.getOrNull(index)?.markerType?.type.orEmpty()
            val markerTitle = droppedMarkerListSetting.getOrNull(index)?.title.orEmpty()
            val markerLabel = markerTextFromString(markerType)

            EditSelectedMarker(
                navController,
                index,
                context,
                viewModel,
                droppedMarkerListSetting,
                showLocationChangeDialog,
                shouldShowClearMarkerDialog,
                exitActivity
            )

            ChangeMarkerLocationConfirmationDialog(
                showLocationChangeDialog = showLocationChangeDialog,
                index = index,
                viewModel = viewModel,
                droppedMarkerListSetting = droppedMarkerListSetting,
                isConfirmed = showLocationChangeConfirm,
                confirmFunction = {
                    CoroutineScope(Dispatchers.Main).launch {
                        viewModel.requestUpdateMarkerLocation(uid)
                    }
                    successBoolean.value =
                        (location != null && locationEnabled && anyEndpointConnected)
                }
            )

            SuccessfulCotSentCheck(
                showLocationChangeConfirm,
                anyEndpointConnected,
                context,
                { },
                successBoolean,
                location,
                locationEnabled,
                {
                    MarkerSuccessDialog(
                        showLocationChangeConfirm,
                        successBoolean,
                        {
                            Icon(
                                Icons.Rounded.Check,
                                modifier = Modifier.size(25.dp),
                                contentDescription = null,
                                tint = Color.Green
                            )
                        },
                        {}
                    )
                },
                {}
            )
            ClearMarkerConfirmationDialog(
                shouldShowClearMarkerDialog,
                "Delete Marker?\n\r${markerTextFromString(markerType)}: ${markerTitle}\n" +
                    stringResource(R.string.owned_pin_delete_help),
                {
                    Icon(
                        markerIconFromString(markerType),
                        modifier = Modifier.size(35.dp),
                        contentDescription = null,
                        tint = Color.Unspecified
                    )
                }, shouldShowClearMarkerConfirm,
                {
                    // Trigger backend clear-last;
                    CoroutineScope(Dispatchers.Main).launch {
                        viewModel.requestClearSpecificMarker(uid)
                    }
                    // dialog UX logic
                    successBoolean.value =
                        (location != null && locationEnabled && anyEndpointConnected)
                }
            )

            SuccessfulCotSentCheck(
                shouldShowClearMarkerConfirm,
                anyEndpointConnected,
                context,
                { },
                successBoolean,
                location,
                locationEnabled,
                {
                    MarkerSuccessDialog(
                        shouldShowClearMarkerConfirm,
                        successBoolean,
                        {
                            Icon(
                                Icons.Rounded.Check,
                                modifier = Modifier.size(25.dp),
                                contentDescription = null,
                                tint = Color.Green
                            )
                        },
                        {}
                    )
                },
                {
                        exitActivity()
                }
            )
        }
        composable(
            "edit_marker_title/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            val uid = droppedMarkerListSetting.getOrNull(index)?.uid.orEmpty()

            Log.i(TAG, "edit_marker_title index: $index")
            MarkerTitleRemarkSetting(
                navController = navController,
                index = index,
                droppedMarkerListSetting.getOrNull(index)?.title.orEmpty(),
                "title",
                droppedMarkerListSetting,
                viewModel
            )

        }
        composable(
            "edit_marker_remark/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.i(TAG, "edit_marker_remark index: $index")
            MarkerTitleRemarkSetting(
                navController = navController,
                index = index,
                droppedMarkerListSetting.getOrNull(index)?.remark.orEmpty(),
                "remark",
                droppedMarkerListSetting,
                viewModel
            )        }
        composable(
            "edit_marker_type/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.i(TAG, "edit_marker_title index: $index")
            var markerType by remember { mutableStateOf(droppedMarkerListSetting.getOrNull(index)?.markerType?.type.orEmpty()) }
            val uid = droppedMarkerListSetting.getOrNull(index)?.uid.orEmpty()
            RadialSelectionScreen(
                exitActivity = exitActivity,
                shouldShowDialog = showTypeChangeConfirm,
                onClickRightButton = {
                    markerType = MarkerType.FRIENDLY.type
                    showTypeChangeConfirm.value = true
                    viewModel.requestChangeMarkerType(uid, MarkerType.FRIENDLY)
                    MarkerActivity.Constants.markerType = markerType
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Change request for $markerType ...")
                },
                onClickBottomButton = {
                    markerType = MarkerType.UNKNOWN.type
                    showTypeChangeConfirm.value = true
                    viewModel.requestChangeMarkerType(uid, MarkerType.UNKNOWN)
                    MarkerActivity.Constants.markerType = markerType
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Change request for $markerType ...")
                },
                onClickLeftButton = {
                    markerType = MarkerType.NEUTRAL.type
                    showTypeChangeConfirm.value = true
                    viewModel.requestChangeMarkerType(uid, MarkerType.NEUTRAL)
                    MarkerActivity.Constants.markerType = markerType
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Change request for $markerType ...")
                },
                onClickTopButton = {
                    markerType = MarkerType.HOSTILE.type
                    showTypeChangeConfirm.value = true
                    viewModel.requestChangeMarkerType(uid, MarkerType.HOSTILE)
                    MarkerActivity.Constants.markerType = markerType
                    Log.i(TAG, "Marker Button Clicked! Sending Marker Change request for $markerType ...")
                },
                longPressBoolean = false,
                navController = navController
            )

            SuccessfulCotSentCheck(
                showTypeChangeConfirm,
                anyEndpointConnected,
                context,
                { navController.popBackStack()},
                successBoolean,
                location,
                locationEnabled,
                {
                    MarkerSuccessDialog(
                        showTypeChangeConfirm,
                        successBoolean,
                        {Icon(
                            markerIconFromString(markerType),
                            modifier = Modifier.size(25.dp),
                            contentDescription = null,
                            tint = Color.Unspecified
                        )},
                        {}
                    )
                },
                {}
            )
        }
        composable(
            "invalid_license_screen") {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
        }
    }
}

//@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = false)
//@Composable
//fun PreviewMarkerSelectionScreen() {
//    val shouldShowConfirm = remember { mutableStateOf(true) }
//    val navController = rememberSwipeDismissableNavController()
//
//    MarkerSelectionScreen(exitActivity = {},shouldShowConfirm, navController)
//}
//@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = false)
//@Composable
//fun PreviewLongPressScreen() {
//    val navController = rememberSwipeDismissableNavController()
//    val successBoolean = remember { mutableStateOf(true) }
//    val shouldShowConfirm = remember { mutableStateOf(true) }
//    LongPressScreen(navController, successBoolean, shouldShowConfirm, successBoolean)
//}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = false)
@Composable
fun PreviewDroppedMarkerList() {
    SwipeableMarkerChip(
        "a-h-G",
        "I am a remarkable marker",
        MarkerCotType.CLEAR,
        true,
        1752686500,
        onPrimaryActionClick = {},
        onSecondaryActionClick = {}
    )
}

fun bearingToCardinal(bearing: Float): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((bearing + 22.5f) / 45f).toInt() % 8]
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun ClearMarkerConfirmationDialogPreview() {
    val shouldShowDialog = remember { mutableStateOf(true) }
    val isConfirmed = remember { mutableStateOf(false) }

    ClearMarkerConfirmationDialog(
        shouldShowDialog = shouldShowDialog,
        "This is a text box",
        { Icon(
            Icons.Outlined.Delete,
            modifier = Modifier.size(25.dp),
            contentDescription = null,
            tint = Color.LightGray) },
        isConfirmed,
        {}
    )

}
//@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
//@Composable
//fun DropMarkerConfirmationDialogPreview() {
//    val shouldShowDialog = remember { mutableStateOf(true) }
//    val isConfirmed = remember { mutableStateOf(true) }
//
//    DropMarkerConfirmationDialog(
//        shouldShowDialog = shouldShowDialog,
//        isConfirmed,
//        {}
//    )
//}
