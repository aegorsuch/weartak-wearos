/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import com.aitech.weartak_civ.presentation.SosActivity.Constants.TAG
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
                SuccessDialogActivity(false)
            }
        }
    }
    @Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
    @Composable
    fun PreviewAlertScreen() {
        WearTAKCIVTheme {
            Surface {
                val isConfirmed = remember { mutableStateOf(true) }

                ConfirmationAlertPage(
                    isConfirmed,
                    isConfirmed,
                    true,
                    "",
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
    isAlerting: Boolean,
    alertDescription: String,
    confirmFunction: () -> Unit,
    exitActivity: () -> Unit,
    ) {
    if (shouldShowDialog.value) {
        AlertDialog(
            containerColor = if (isAlerting) Color.Red else colors.background,
            modifier = (if (isAlerting) {
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
                if (isAlerting) exitActivity()
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
                        isAlerting && alertDescription.isEmpty() -> "Cancel Manual Alert?"
                        isAlerting -> "Cancel Alert: ${alertDescription.trim()}?"
                        manualAlertType.value.isNullOrEmpty() -> "Send Manual Alert?"
                        else -> "Send Alert: ${manualAlertTypeSaved.value.orEmpty().trim()}?"
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
                        containerColor = if (isAlerting) Color.Black else Color.Red),
                ) {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.confirm),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isAlerting) Color.White else Color.Black
                    )
                }
            },

            dismissButton = {
                Button(
                    onClick = {
                        if (isAlerting){
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
fun SuccessDialogActivity(cancelling: Boolean) {
    Dialog(onDismissRequest = {}) {
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
                Text(
                    text = stringResource(if (cancelling) R.string.manual_alert_cancel_requested
                        else R.string.manual_alert_requested),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

fun manualAlertOrClearRequest(alert: DataRepository.CustomEmergencyAlert?){
    CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
        val timeMillis = System.currentTimeMillis()
        if (alert == null) {
            Log.d(TAG, "Manual Alert Triggered")
            val descManualAlert = manualAlertTypeSaved.value
            dataRepository.addManualAlertRequest(DataRepository.AlertState.ALERT, CAT_MANUAL_ALERT, if (descManualAlert.isNullOrEmpty()) DESC_SOS_ALERT else descManualAlert.trim(), timeMillis)
        } else {
            Log.d(TAG, "Cancel Manual Alert Triggered")
            dataRepository.clearManualAlertRequestUid(alert.uuid, alert.activationId)
        }
    }
}

@Composable
private fun SuccessfulCotSentCheck(
    shouldShowConfirm: MutableState<Boolean>,
    exitActivity: () -> Unit,
    dialog: @Composable () -> Unit,
    successFunction: () -> Unit
){
    if (shouldShowConfirm.value) {
        LaunchedEffect(Unit) {
            delay(1000)
            shouldShowConfirm.value = false
            exitActivity()
            successFunction()
        }
        dialog()
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun SosCivApp(exitActivity: () -> Unit, context: Context) {
    val isConfirmed = remember { mutableStateOf(false) }
    val shouldShowConfirm = remember { mutableStateOf(false) }
    val manualAlerts by dataRepository.manualAlertList.collectAsStateWithLifecycle()
    val activeAlert = manualAlerts.filter { it.alertState == DataRepository.AlertState.ALERT }
        .maxByOrNull { it.timeMillis }
    var cancellationTarget by remember { mutableStateOf<DataRepository.CustomEmergencyAlert?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    val navController = rememberSwipeDismissableNavController()
    val savedAlertsStore = remember(context.applicationContext) {
        (context.applicationContext as WearTakApplication).savedCustomAlerts
    }
    val savedAlerts by savedAlertsStore.alerts.collectAsStateWithLifecycle(initialValue = emptyList())
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
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
            cancelling = false
            isConfirmed.value = true
            manualAlertOrClearRequest(null)
        }
    }
    LaunchedEffect(activeAlert?.uuid, isConfirmed.value) {
        if (activeAlert != null && cancellationTarget == null && !isConfirmed.value) {
            cancellationTarget = activeAlert
            shouldShowConfirm.value = true
        }
    }
    if (activeAlert == null && cancellationTarget == null && !isConfirmed.value) {
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
            }

            ConfirmationAlertPage(
                shouldShowDialog = shouldShowConfirm,
                isConfirmed = isConfirmed,
                isAlerting = cancellationTarget != null,
                alertDescription = cancellationTarget?.description.orEmpty(),
                confirmFunction = {
                    cancellationTarget?.let {
                        cancelling = true
                        manualAlertOrClearRequest(it)
                    }
                },
                exitActivity = { exitActivity() }
            )

            SuccessfulCotSentCheck(
                isConfirmed,
                {
                    exitActivity()
                    manualAlertType.value = ""
                },
                {
                    SuccessDialogActivity(cancelling)
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
