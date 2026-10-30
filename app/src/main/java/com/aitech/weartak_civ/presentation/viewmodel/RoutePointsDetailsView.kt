package com.aitech.weartak_civ.presentation.viewmodel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MarkerBearingDistance
import com.aitech.weartak_civ.presentation.WearTAKBackButton
import com.aitech.weartak_civ.presentation.PointBloodhoundAction
import com.aitech.weartak_civ.presentation.markerIconFromString
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import com.aitech.weartak_civ.data.model.TakRoute
import androidx.activity.compose.BackHandler

data class RoutePointDetails(
    val routeUid: String,
    val pointIndex: Int,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val droppedBy: String? = null,
    val missionName: String? = null,
    val serverId: String = ""
)

fun findRoutePoint(
    routes: List<TakRoute>,
    serverId: String,
    routeUid: String,
    pointIndex: Int
): RoutePointDetails? {
    val route = routes.firstOrNull { it.serverId == serverId && it.uid == routeUid } ?: return null
    val point = route.points.getOrNull(pointIndex) ?: return null
    return RoutePointDetails(
        routeUid = route.uid,
        pointIndex = pointIndex,
        title = point.name.orEmpty(),
        latitude = point.latitude,
        longitude = point.longitude,
        droppedBy = route.sender,
        missionName = route.missionName,
        serverId = route.serverId
    )
}

@Composable
fun RoutePointsDetailsView(
    pointName: String,
    latitude: Double,
    longitude: Double,
    sender: String? = null,
    missionName: String? = null,
    distance: Double? = null,
    orientation: Float = 0f,
    isBloodhounding: Boolean = false,
    onBloodhound: () -> Unit = {},
    onInPosition: () -> Unit = {},
    onWholeRoute: () -> Unit = {},
    onTarget: () -> Unit = {},
    onBack: () -> Unit
) {
    val validPosition = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(bottom = 16.dp, top = 15.dp)
            .padding(horizontal = 16.dp)
            .testTag("route-point-details")
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
                painter = markerIconFromString("b-m-p-s-m"),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(25.dp)
            )
            Text(
                text = pointName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)
            )
            if (validPosition) MarkerBearingDistance(distance, latitude, longitude, orientation)
            else Text(stringResource(R.string.remote_emergency_no_location),
                color = Color.LightGray, textAlign = TextAlign.Center)
            PointBloodhoundAction(isBloodhounding, onBloodhound, onInPosition, onTarget,
                enabled = validPosition)
            if (!sender.isNullOrBlank()) {
                Text(sender, color = Color.LightGray, fontSize = 13.sp)
            }
            if (!missionName.isNullOrBlank()) {
                Text(missionName, color = Color.LightGray, fontSize = 13.sp)
            }
            Button(
                onClick = onWholeRoute,
                modifier = Modifier.padding(vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) {
                Text(stringResource(R.string.route_whole), color = Color.Black, fontSize = 15.sp)
            }
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            WearTAKBackButton(onClick = onBack)
        }
    }
}