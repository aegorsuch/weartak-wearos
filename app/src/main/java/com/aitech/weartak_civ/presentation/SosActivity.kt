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
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.complication.SosComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.CAT_MANUAL_ALERT
import com.aitech.weartak_civ.DESC_SOS_ALERT
import com.aitech.weartak_civ.cot.ManualAlertTypes
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.SosActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.SosActivity.Constants.isAlertingLiveData
import com.aitech.weartak_civ.presentation.SosActivity.Constants.manualAlertOptions
import com.aitech.weartak_civ.presentation.SosActivity.Constants.manualAlertType
import com.aitech.weartak_civ.presentation.SosActivity.Constants.manualAlertTypeSaved
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.collections.List
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp



class SosActivity : ComponentActivity() {
companion object Constants{
    lateinit var sosActivity : SosActivity
    val TAG = "WearTAK-" + SosActivity::class.java.simpleName
    val isAlertingLiveData = MutableLiveData<Boolean>(false)
    val manualAlertOptions: List<Utils.TextSelectionButton> = ManualAlertTypes.options.map {
        Utils.TextSelectionButton(it.shortCode, it.displayName)
    }

    val manualAlertType = MutableLiveData<String>("")
    val manualAlertTypeSaved = MutableLiveData<String>("")
}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sosActivity = this
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    SosCivApp(exitActivity = {finishAndRemoveTask()}, context)
                }
            }
        }
    }

    @Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
    @Composable
    fun PreviewMessageCardAlerting() {
        WearTAKCIVTheme {
            Surface {
                val isAlerting = MutableLiveData<Boolean>(false)
                val showDialog = remember { mutableStateOf(true) }
                val successFailure = remember { mutableStateOf(true) }

                SuccessDialogActivity(
                    showDialog,
                    successFailure,
                    isAlerting,
                    LocalContext.current
                )
            }
        }
    }
    @Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
    @Composable
    fun PreviewAlertScreen() {
        WearTAKCIVTheme {
            Surface {
                val isAlerting = MutableLiveData<Boolean>(true)
                val isConfirmed = remember { mutableStateOf(true) }

                ConfirmationAlertPage(
                    isConfirmed,
                    isConfirmed,
                    isAlerting,
                    {},
                    {}
                )
            }
        }
    }
    @Preview(widthDp = 230, heightDp = 400, showBackground = true)
    @Composable
    fun PreviewAlertTypeScreen() {
        WearTAKCIVTheme {
            Surface {
                ManualAlertListScreen(
                    builtInAlerts = ManualAlertTypes.options.map { it.displayName },
                    savedAlerts = emptyList(),
                    onAlertSelected = {},
                    onAddAlert = { true },
                    onDeleteAlert = {},
                    onCancel = {},
                )
            }
        }
    }
}

@Composable
fun ConfirmationAlertPage(
    shouldShowDialog: MutableState<Boolean>,
    isConfirmed: MutableState<Boolean>,
    isAlerting: MutableLiveData<Boolean>,
    confirmFunction: () -> Unit,
    exitActivity: () -> Unit,
    ) {
    if (shouldShowDialog.value) {
        AlertDialog(
            containerColor = if (isAlerting.value == true) Color.Red else colors.background,
            modifier = (if (isAlerting.value == true) {
                Modifier
                    .fillMaxSize()
                    .background(Color.Red)
                    .padding(0.dp)
            } else {
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(0.dp)
            }).automationRoot("sos_confirmation"),
            onDismissRequest = {
                shouldShowDialog.value = false
            },
            icon = {
                Icon(
                    painterResource(R.drawable.tak_alert_icon),
                    modifier = Modifier.size(35.dp),
                    contentDescription = null,
                    tint = Color.White
                )
            },
            text = {
                Text(
                    text = when {
                        isAlerting.value == true && manualAlertTypeSaved.value.isNullOrEmpty() -> "Cancel Manual Alert?"
                        isAlerting.value == true && !manualAlertTypeSaved.value.isNullOrEmpty() -> "Cancel Alert: ${manualAlertTypeSaved.value!!.trim()}?"
                        isAlerting.value == false && manualAlertType.value.isNullOrEmpty() -> "Send Manual Alert?"
                        isAlerting.value == false && !manualAlertType.value.isNullOrEmpty() -> "Send Alert: ${manualAlertTypeSaved.value!!.trim()}?"
                        else -> {"ERROR"} // Note: This will never be reached
                    },
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        shouldShowDialog.value = false
                        isConfirmed.value = true
                        confirmFunction()
                    },
                    modifier = Modifier.fillMaxWidth(0.45f).automationRoot("sos_confirm"),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAlerting.value == true) Color.Black else Color.Red),
                ) {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.confirm),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isAlerting.value == true) Color.White else Color.Black
                    )
                }
            },

            dismissButton = {
                Button(
                    onClick = {
                        if (isAlertingLiveData.value == true){
                            exitActivity()
                        }
                        shouldShowDialog.value = false
                        isConfirmed.value = false
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth(0.45f).automationRoot("sos_cancel"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
    }
}

@Composable
fun SuccessDialogActivity(
    shouldShowDialog: MutableState<Boolean>,
    successBoolean : MutableState<Boolean>,
    isAlerting: MutableLiveData<Boolean>,
    context:Context) {
    Dialog(onDismissRequest = { shouldShowDialog.value = false }) {
        Card(
            modifier = Modifier
                .automationRoot("sos_result")
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
                    Icon(
                        Icons.Rounded.Check,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Green
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.success),
                        textAlign = TextAlign.Center,
                    )
                    isAlerting.value = isAlerting.value != true
                    isAlerting.postValue(isAlerting.value)
                    requestSosComplicationUpdate(context)

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = when {
                            isAlerting.value == true && manualAlertType.value.isNullOrEmpty() -> "Manual Alert Sent"
                            isAlerting.value == true && !manualAlertType.value.isNullOrEmpty() -> "Alert Sent: ${manualAlertTypeSaved.value!!.trim()}"
                            isAlerting.value == false && manualAlertTypeSaved.value.isNullOrEmpty() -> "Manual Alert Cancelled"
                            isAlerting.value == false && !manualAlertTypeSaved.value.isNullOrEmpty() -> "Alert Cancelled: ${manualAlertTypeSaved.value!!.trim()}"
                            else -> {"ERROR"} // Note: This will never be reached
                        },
                        textAlign = TextAlign.Center,
                    )
                    Log.w(TAG, "SuccessDialogActivity (successBoolean=true) isAlertingLiveData = ${isAlertingLiveData.value}")
                } else {
                    Icon(
                        Icons.Rounded.Clear,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Red
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.error),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isAlerting.value == true)
                                    "Cancel Alert will be sent when reconnected"
                                else
                                    "Alert will be sent when reconnected",
                        textAlign = TextAlign.Center,
                    )

                    isAlerting.value = isAlerting.value == true
                    isAlerting.postValue(isAlerting.value)
                    Log.w(TAG, "SuccessDialogActivity (successBoolean=false) isAlertingLiveData = ${isAlertingLiveData.value}")
                }
            }
        }
    }
}

fun manualAlertOrClearRequest(isAlerting: Boolean){
    CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
        val timeMillis = System.currentTimeMillis()
        if (isAlerting != true) {
            Log.d(TAG, "Manual Alert Triggered")
            val descManualAlert = manualAlertTypeSaved.value
            dataRepository.addManualAlertRequest(DataRepository.AlertState.ALERT, CAT_MANUAL_ALERT, if (descManualAlert.isNullOrEmpty()) DESC_SOS_ALERT else descManualAlert.trim(), timeMillis)
        } else {
            Log.d(TAG, "Cancel Manual Alert Triggered")
            dataRepository.clearManualAlertRequestLast()
        }
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
        successBoolean.value = locationChecked && (anyEndpointConnected == true)
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

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun SosCivApp(exitActivity: () -> Unit, context: Context) {
    val isConfirmed = remember { mutableStateOf(false) }
    val isDismissed = remember { mutableStateOf(false) }
    val successBoolean = remember { mutableStateOf(false) }
    val shouldShowConfirm = remember { mutableStateOf(false) }
    isAlertingLiveData.observeAsState()
    val anyEndpointConnected by dataRepository.anyEndpointConnected.collectAsStateWithLifecycle(initialValue = false)
//    val anyEndpointConnected = connectedEndpoints.isNotEmpty()

    val location = dataRepository.latestLocation.value
    var locationEnabled by remember { mutableStateOf(enableLocationService) }
    val navController = rememberSwipeDismissableNavController()
    val savedAlertsStore = remember(context.applicationContext) {
        (context.applicationContext as WearTakApplication).savedCustomAlerts
    }
    val savedAlerts by savedAlertsStore.alerts.collectAsStateWithLifecycle(initialValue = emptyList())
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    LaunchedEffect(locationEnabled, anyEndpointConnected) {
        successBoolean.value = locationEnabled && anyEndpointConnected
    }

    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
                exitActivity()
            }
            isValid == true && currentDestination != "sos_screen" -> {
                navController.navigate("sos_screen") {
                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
        }
    }

    SwipeDismissableNavHost(
        modifier = Modifier.automationRoot("sos"),
        navController = navController,
        startDestination = if (isLicenseValid()) "sos_screen" else "invalid_license_screen"
    ) {
//        composable(
//            "license_setting_screen") {
//            LicenseKeyScreen(navController, licenseSettingsState, context)
//        }
        composable("sos_screen"){
    val sendManualAlert: (String) -> Unit = { description ->
        if (!isConfirmed.value) {
            manualAlertType.value = description
            manualAlertTypeSaved.value = description
            successBoolean.value = locationEnabled && anyEndpointConnected
            isConfirmed.value = true
            manualAlertOrClearRequest(false)
        }
    }
    if (isAlertingLiveData.value == false) {
                ManualAlertListScreen(
                    builtInAlerts = manualAlertOptions.map { it.longText },
                    savedAlerts = savedAlerts,
                    onAlertSelected = sendManualAlert,
                    onAddAlert = savedAlertsStore::add,
                    onDeleteAlert = savedAlertsStore::remove,
                    onCancel = {
                        manualAlertType.value = ""
                        exitActivity()
                    },
                )
            } else {
                shouldShowConfirm.value = true
            }

            ConfirmationAlertPage(
                shouldShowDialog = shouldShowConfirm,
                isConfirmed = isConfirmed,
                isAlerting = isAlertingLiveData,
                confirmFunction = {
                    successBoolean.value = locationEnabled && anyEndpointConnected
                    manualAlertOrClearRequest(true)
                },
                exitActivity = { exitActivity() }
            )

            SuccessfulCotSentCheck(
                isConfirmed,
                anyEndpointConnected,
                context,
                {
                    exitActivity()
                    manualAlertType.value = ""
                },
                successBoolean,
                location,
                locationEnabled,
                {
                    SuccessDialogActivity(
                    isConfirmed,
                    successBoolean,
                    isAlertingLiveData,
                    context
                )
                },
                {}
            )
            Log.d(TAG, "Manual Alert Type: ${manualAlertType.value},"+" Manual Alert Type Saved: ${manualAlertTypeSaved.value}")
        }
        composable(
            "invalid_license_screen") {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
        }
    }
}

fun requestSosComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, SosComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}
