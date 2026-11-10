//  /*
// * Copyright (C) 2025, Ascent Integrated Tech.
// *
// * SPDX-License-Identifier: GPL-3.0-only
// *
// * Licensed under the GNU General Public License, version 3 only.
// * See the LICENSE file in the repository root for the full license text.
// */
//
package com.aitech.weartak_civ.presentation
//
//import android.R.id.icon
//import android.annotation.SuppressLint
//import android.content.Context
//import android.content.Intent
//import android.graphics.Bitmap
//import android.graphics.drawable.Drawable
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.util.Log
//import android.view.MotionEvent
//import android.view.Gravity
//import android.widget.ImageView
//import android.widget.Toast
//import androidx.activity.ComponentActivity
//import androidx.lifecycle.viewmodel.compose.viewModel
//import androidx.activity.compose.setContent
//import androidx.activity.enableEdgeToEdge
//import androidx.compose.foundation.Image
//import androidx.compose.foundation.background
//import androidx.compose.foundation.border
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.gestures.scrollable
//import androidx.compose.foundation.layout.Arrangement
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.IntrinsicSize
//import androidx.compose.foundation.layout.PaddingValues
//import androidx.compose.foundation.layout.Row
//import androidx.compose.foundation.layout.Spacer
//import androidx.compose.foundation.layout.fillMaxHeight
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.height
//import androidx.compose.foundation.layout.padding
//import androidx.compose.foundation.layout.size
//import androidx.compose.foundation.layout.width
//import androidx.compose.foundation.layout.wrapContentHeight
//import androidx.compose.foundation.layout.wrapContentSize
//import androidx.compose.foundation.lazy.LazyColumn
//import androidx.compose.foundation.lazy.itemsIndexed
//import androidx.compose.foundation.rememberScrollState
//import androidx.compose.foundation.shape.CircleShape
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.foundation.text.KeyboardActions
//import androidx.compose.foundation.text.KeyboardOptions
//import androidx.compose.foundation.verticalScroll
//import androidx.compose.material.icons.Icons
//import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
//import androidx.compose.material.icons.filled.Add
//import androidx.compose.material.icons.filled.Check
//import androidx.compose.material.icons.filled.Close
//import androidx.compose.material.icons.filled.Warning
//import androidx.compose.material.icons.rounded.Clear
//import androidx.compose.material3.AlertDialog
//import androidx.compose.material3.Button
//import androidx.compose.material3.ButtonDefaults
//import androidx.compose.material3.Card
//import androidx.compose.material3.CardDefaults
//import androidx.compose.material3.Checkbox
//import androidx.compose.material3.IconButton
//import androidx.compose.material3.IconButtonDefaults
//import androidx.compose.material3.OutlinedTextField
//import androidx.compose.material3.ShapeDefaults
//import androidx.compose.material3.Surface
//import androidx.compose.material3.Switch
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.DisposableEffect
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.MutableState
//import androidx.compose.runtime.collectAsState
//import androidx.compose.runtime.derivedStateOf
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.livedata.observeAsState
//import androidx.compose.runtime.mutableStateListOf
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.rememberCoroutineScope
//import androidx.compose.runtime.saveable.rememberSaveable
//import androidx.compose.runtime.setValue
//import androidx.compose.runtime.snapshots.SnapshotStateList
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.ExperimentalComposeUiApi
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.alpha
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.graphics.RectangleShape
//import androidx.compose.ui.graphics.vector.ImageVector
//import androidx.compose.ui.input.pointer.pointerInteropFilter
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.platform.LocalFocusManager
//import androidx.compose.ui.res.painterResource
//import androidx.compose.ui.res.stringResource
//import androidx.compose.ui.semantics.CustomAccessibilityAction
//import androidx.compose.ui.semantics.customActions
//import androidx.compose.ui.semantics.semantics
//import androidx.compose.ui.text.TextStyle
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.text.input.ImeAction
//import androidx.compose.ui.text.input.KeyboardType
//import androidx.compose.ui.text.input.TextFieldValue
//import androidx.compose.ui.text.style.TextAlign
//import androidx.compose.ui.text.style.TextOverflow
//import androidx.compose.ui.tooling.preview.Devices
//import androidx.compose.ui.tooling.preview.Preview
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.compose.ui.viewinterop.AndroidView
//import androidx.compose.ui.window.Dialog
//import androidx.compose.ui.window.DialogProperties
//import androidx.compose.ui.window.Popup
//import androidx.compose.ui.window.PopupProperties
//import androidx.compose.ui.zIndex
//import androidx.lifecycle.MutableLiveData
//import androidx.lifecycle.Lifecycle
//import androidx.lifecycle.LifecycleEventObserver
//import androidx.lifecycle.compose.LocalLifecycleOwner
//import androidx.lifecycle.compose.collectAsStateWithLifecycle
//import androidx.navigation.NavController
//import androidx.navigation.NavType
//import androidx.navigation.compose.rememberNavController
//import androidx.navigation.navArgument
//import androidx.wear.compose.foundation.RevealState
//import androidx.wear.compose.foundation.RevealValue
//import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
//import androidx.wear.compose.foundation.rememberRevealState
//import androidx.wear.compose.material.Chip
//import androidx.wear.compose.material.ChipDefaults
//import androidx.wear.compose.material.CircularProgressIndicator
//import androidx.wear.compose.material.ExperimentalWearMaterialApi
//import androidx.wear.compose.material.Icon
//import androidx.wear.compose.material.MaterialTheme
//import androidx.wear.compose.material.MaterialTheme.colors
//import androidx.wear.compose.material.Picker
//import androidx.wear.compose.material.PickerState
//import androidx.wear.compose.material.PositionIndicator
//import androidx.wear.compose.material.Scaffold
//import androidx.wear.compose.material.SelectableChip
//import androidx.wear.compose.material.SplitToggleChip
//import androidx.wear.compose.material.SplitToggleChipColors
//import androidx.wear.compose.material.SwipeToRevealChip
//import androidx.wear.compose.material.SwipeToRevealDefaults
//import androidx.wear.compose.material.SwipeToRevealPrimaryAction
//import androidx.wear.compose.material.SwipeToRevealSecondaryAction
//import androidx.wear.compose.material.SwipeToRevealUndoAction
//import androidx.wear.compose.material.Text
//import androidx.wear.compose.material.ToggleChip
//import androidx.wear.compose.material.ToggleChipDefaults
//import androidx.wear.compose.material.rememberPickerState
//import androidx.wear.compose.navigation.SwipeDismissableNavHost
//import androidx.wear.compose.navigation.composable
//import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
//import com.aitech.weartak_civ.DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
//import com.aitech.weartak_civ.DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
//import com.aitech.weartak_civ.R
//import com.aitech.weartak_civ.WearTakApplication
//import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_BASIC
//import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED
//import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT
//import com.aitech.weartak_civ.FEATURE_ENABLE_BLOODHOUND_PROXIMITY_INTENSITY_SELECTION
//import com.aitech.weartak_civ.cep.di.CepProvider
//import com.aitech.weartak_civ.Utils.setAirplaneMode
//import com.aitech.weartak_civ.data.DataRepository
//import com.aitech.weartak_civ.data.secure.sitx.SitxSecretsRepository
//import com.aitech.weartak_civ.events.MessageEvent
//import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
//import com.aitech.weartak_civ.events.MessageEvent.Companion.HR_ALERT_THRESHOLDS_CHANGED
//import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
//import com.aitech.weartak_civ.events.MessageEvent.Companion.PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED
//import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED_VIA_UI
//import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
//import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
//import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
//import com.aitech.weartak_civ.integration.plugin.PluginCompatibilityStatus
//import com.aitech.weartak_civ.integration.plugin.PluginLoadingStatus
//import com.aitech.weartak_civ.integration.plugin.PluginUI
//import com.aitech.weartak_civ.integration.plugin.PluginViewModel
//import com.aitech.weartak_civ.manager.NetworkManager
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.ALL_WIFI_NETWORKS
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.EVENT_SEND_DELAY_MS
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.SOME_WIFI_NETWORKS
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.birthYearSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.birthYearState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.birthYearValue
////import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyList
////import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyListSettingsState
////import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyListState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodTypeSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodTypeState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodTypeValue
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimization
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServices
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServicesSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableCompanionAppService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableCompanionAppServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSmsForAlerts
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlerts
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlertsSettingState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSmsForAlertsSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimization
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.generateSaveBattOnWifiString
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightFeetSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightFeetState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightFeetValue
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightInchesSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightInchesState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightInchesValue
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.license
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.licenseSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiString
//import androidx.wear.compose.material.SplitToggleChip
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyList
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyListSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyListState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiStringState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersListString
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersListStringSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sexSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sexState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sexValue
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerListState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.userTypeSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.userTypeState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.userTypeValue
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.versionNumber
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.weightSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.weightState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.weightValue
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidList
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListState
//import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.TAG
//import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.allergiesOptionsList
//import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.bloodTypeOptionsList
//import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.settingsActivity
//import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.sexOptionsList
//import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.userTypeOptionsList
//import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
//import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
//import com.aitech.weartak_civ.service.LicenseManager.validateLicense
//import com.aitech.weartak_civ.service.TAKConnectService
//import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
//import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
//import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
//import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DUPLICATE_STRING
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.category
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.categoryRolesEntryLists
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.categorySettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDynamicReportingStrategy
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDynamicReportingStrategySettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDynamicReportingStrategyState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHailService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHailServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.onFootReportingInterval
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.onFootReportingIntervalSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.onFootReportingIntervalState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHeartRate
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHeartRateSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHeartRateState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.constantReportingInterval
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.constantReportingIntervalSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.constantReportingIntervalState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCot
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCotSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatteryAlerts
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatteryAlertsSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBetaFeatures
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBetaFeaturesSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundCompassService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundCompassServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundProximityVibration
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodhoundProximityIntensity
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodhoundProximityIntensitySettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodhoundProximityRadiusMeters
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodhoundProximityRadiusMetersSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.crmModerateMaxThreshold
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.crmModerateMaxThresholdSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.crmPoorMaxThreshold
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.crmPoorMaxThresholdSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundProximityVibrationSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableCrmService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableCrmServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.loadoutWeight
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.loadoutWeightSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.loadoutWeightState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdAlert
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdAlertSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdAlertState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdWarning
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdWarningSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdWarningState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionWarningThresholdLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionWarningThresholdLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionWarningThresholdLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionAlertThresholdLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionAlertThresholdLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionAlertThresholdLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrHighAlertBpmThreshold
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrHighAlertBpmThresholdSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrHighAlertBpmThresholdState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrLowAlertBpmThreshold
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrLowAlertBpmThresholdSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrLowAlertBpmThresholdState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingInterval
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingIntervalSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingIntervalState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.strideLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.strideLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.strideLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformPantsLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformPantsLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformPantsLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformWaistSize
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformWaistSizeSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformWaistSizeState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpointSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHighAtmPressureAlert
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHighAtmPressureAlertSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableImmersionAlerts
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableImmersionAlertsSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLowAtmPressureAlert
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLowAtmPressureAlertSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableMulticastOnWifi
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableMulticastSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSitxService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSitxServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxGunshotService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxGunshotServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxUasService
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxUasServiceSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.onnxGunshotTriggerThresholdPercent
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.onnxGunshotTriggerThresholdPercentSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.onnxUasTriggerThresholdPercent
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.onnxUasTriggerThresholdPercentSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpointSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.esriEndpoint
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.esriEndpointSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highAtmPressureAlertThreshold
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highAtmPressureAlertThresholdSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrAlertThresholdLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrAlertThresholdLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrAlertThresholdLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrWarningThresholdLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrWarningThresholdLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrWarningThresholdLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowAtmPressureAlertThreshold
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowAtmPressureAlertThresholdSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrAlertThresholdLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrAlertThresholdLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrAlertThresholdLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrWarningThresholdLength
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrWarningThresholdLengthSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrWarningThresholdLengthState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastAddress
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastAddressSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastProtocol
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastProtocolSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastPort
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastPortSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxAvailableGroupList
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxAvailableGroupListState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxRepo
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakGroup
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakGroupSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakUrl
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakUrlSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandApikey
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandApikeySettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandEndpoint
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandEndpointSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingInterval
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingIntervalSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingIntervalState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingInterval
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingIntervalSettingsState
//import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingIntervalState
//import com.aitech.weartak_civ.presentation.viewmodel.SitxViewModel
//import com.aitech.weartak_civ.service.HailService.HailLicenseStates
//import com.aitech.weartak_civ.service.SitxTakService
//import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
//import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus
//import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.runBlocking
//import kotlinx.coroutines.withContext
//import org.greenrobot.eventbus.EventBus
//import java.text.SimpleDateFormat
//import java.time.LocalDate
//import java.time.Year
//import java.time.ZoneId
//import java.time.format.DateTimeFormatter
//import java.time.temporal.ChronoUnit
//import java.util.Locale
//import kotlin.Boolean
//import kotlin.Int
//import kotlin.String
//import kotlin.collections.first
//
//
//class SettingsActivity : ComponentActivity() {
//
//    companion object Constants {
//        val TAG = "WearTAK-" + SettingsActivity::class.java.simpleName
//        lateinit var settingsActivity: SettingsActivity
//        lateinit var sitxRepo: SitxSecretsRepository
//        val callsignState = MutableLiveData<String>("")
//        val licenseState = MutableLiveData<String>("")
//        val sexOptionsList : List<String> = listOf("N/A", "Male", "Female")
//        val bloodTypeOptionsList : List<String> = listOf("N/A", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
//        val allergiesOptionsList : List<String> = listOf("N/A", "Antibiotics", "Anti-Inflammatory (Ibuprofen)", "Antiseizure", "Aspirin", "Insulin", "Muscle Relaxers", "Sulfa Drugs")
//        val userTypeOptionsList : List<String> = listOf("N/A", "Child", "Coalition Civilian", "Coalition Military", "Non-Coalition Civilian", "Non-Coalition Military", "Opposing Force Detainee")
//        val bloodhoundIntensityOptions: List<String> = listOf(
//            BLOODHOUND_PROXIMITY_INTENSITY_BASIC,
//            BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED,
//            BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT
//        )
//        const val LABEL_WARNING_LENGTH = "Warning Length"
//        const val LABEL_ALERT_LENGTH = "Alert Length"
//        const val UNIT_MINUTES = " minutes"
//        const val UNIT_SECONDS = " seconds"
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        Log.i(TAG, "SettingsActivity onCreate()")
//        settingsActivity = this
//        val app = applicationContext as WearTakApplication
//        MainActivity.Constants.dataRepository = app.repo
//        MainActivity.Constants.bhRepo = app.bhRepo
//        MainActivity.Constants.sitxRepo = app.sitxRepo
//        loadSettingsValues()
//        Log.d(TAG, "SettingsActivity onCreate called with enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState and enablePhysiologicalAlerts=$enablePhysiologicalAlerts")
//
//        enableEdgeToEdge()
//        setContent {
//            val sitxVm: SitxViewModel = viewModel()
//            val pluginVm: PluginViewModel = viewModel()
//            WearTAKCIVTheme {
//                Surface(modifier = Modifier.fillMaxSize()) {
//                    WearTAKCivSettings(callsignState, licenseState, sitxVm = sitxVm, pluginVm = pluginVm)
//                }
//            }
//        }
//    }
//}
//
//suspend fun refreshTakServerSettings() {
//    dataRepository.tryAwaitTakServerList()?.let { loadedTakServers ->
//        takServerList = loadedTakServers
//    }
//    takServerListState.postValue(takServerList)
//    constantReportingInterval = dataRepository.getConstantReportingInterval.first()
//    constantReportingIntervalState.postValue(constantReportingInterval)
//    constantReportingIntervalSettingsState.postValue(constantReportingInterval)
//}
//
//private data class BloodhoundSettingsValues(
//    val proximityVibrationEnabled: Boolean,
//    val proximityRadiusMeters: Int,
//    val proximityIntensity: String
//)
//
//private data class BloodhoundSettingsChanges(
//    val proximityVibrationChanged: Boolean,
//    val proximityRadiusChanged: Boolean,
//    val proximityIntensityChanged: Boolean
//) {
//    val anyChanged: Boolean
//        get() = proximityVibrationChanged || proximityRadiusChanged || proximityIntensityChanged
//}
//
//private fun currentBloodhoundSettingsValues() = BloodhoundSettingsValues(
//    proximityVibrationEnabled = enableBloodhoundProximityVibration,
//    proximityRadiusMeters = bloodhoundProximityRadiusMeters,
//    proximityIntensity = bloodhoundProximityIntensity
//)
//
//private fun stagedBloodhoundSettingsValues() = BloodhoundSettingsValues(
//    proximityVibrationEnabled = enableBloodhoundProximityVibrationSettingsState.value ?: enableBloodhoundProximityVibration,
//    proximityRadiusMeters = bloodhoundProximityRadiusMetersSettingsState.value ?: bloodhoundProximityRadiusMeters,
//    proximityIntensity = bloodhoundProximityIntensitySettingsState.value ?: bloodhoundProximityIntensity
//)
//
//private fun postBloodhoundSettingsValues(values: BloodhoundSettingsValues) {
//    enableBloodhoundProximityVibrationSettingsState.postValue(values.proximityVibrationEnabled)
//    bloodhoundProximityRadiusMetersSettingsState.postValue(values.proximityRadiusMeters)
//    bloodhoundProximityIntensitySettingsState.postValue(values.proximityIntensity)
//}
//
//private fun applyStagedBloodhoundSettingsValues(): BloodhoundSettingsChanges {
//    val stagedValues = stagedBloodhoundSettingsValues()
//    val changes = BloodhoundSettingsChanges(
//        proximityVibrationChanged = enableBloodhoundProximityVibration != stagedValues.proximityVibrationEnabled,
//        proximityRadiusChanged = bloodhoundProximityRadiusMeters != stagedValues.proximityRadiusMeters,
//        proximityIntensityChanged = bloodhoundProximityIntensity != stagedValues.proximityIntensity
//    )
//
//    enableBloodhoundProximityVibration = stagedValues.proximityVibrationEnabled
//    bloodhoundProximityRadiusMeters = stagedValues.proximityRadiusMeters
//    bloodhoundProximityIntensity = stagedValues.proximityIntensity
//
//    return changes
//}
//
//fun loadSettingsValues(){
//    enableLocationServiceSettingsState.postValue(enableLocationService)
//    enableCrmServiceSettingsState.postValue(enableCrmService)
//    crmPoorMaxThresholdSettingsState.postValue(crmPoorMaxThreshold)
//    crmModerateMaxThresholdSettingsState.postValue(crmModerateMaxThreshold)
//    enableBetaFeaturesSettingsState.postValue(enableBetaFeatures)
//    enableOnnxGunshotServiceSettingsState.postValue(enableOnnxGunshotService)
//    enableOnnxUasServiceSettingsState.postValue(enableOnnxUasService)
//    enableDittoServiceSettingsState.postValue(enableDittoService)
//    enableChatServiceSettingsState.postValue(enableChatService)
//    enableImmersionAlertsSettingsState.postValue(enableImmersionAlerts)
//    enableLowAtmPressureAlertSettingsState.postValue(enableLowAtmPressureAlert)
//    lowAtmPressureAlertThresholdSettingsState.postValue(lowAtmPressureAlertThreshold)
//    enableHighAtmPressureAlertSettingsState.postValue(enableHighAtmPressureAlert)
//    highAtmPressureAlertThresholdSettingsState.postValue(highAtmPressureAlertThreshold)
//    enableCompanionAppServiceSettingsState.postValue(enableCompanionAppService)
//    enableBatteryAlertsSettingsState.postValue(enableBatteryAlerts)
//    enableSmsForAlertsSettingsState.postValue(enableSmsForAlerts)
//    enablePhysiologicalServicesSettingsState.postValue(enablePhysiologicalServices)
//    enableHailServiceSettingsState.postValue(enableHailService)
//    enableBatdokCotSettingsState.postValue(enableBatdokCot)
//    enableBloodhoundCompassServiceSettingsState.postValue(enableBloodhoundCompassService)
//    postBloodhoundSettingsValues(currentBloodhoundSettingsValues())
//    callsignSettingsState.postValue(callsign)
//    teamSettingsState.postValue(team)
//    categorySettingsState.postValue(category)
//    roleSettingsState.postValue(role)
//    multicastAddressSettingsState.postValue(multicastAddress)
//    multicastProtocolSettingsState.postValue(multicastProtocol)
//    multicastPortSettingsState.postValue(multicastPort)
//    tabletCommandEndpointSettingsState.postValue(tabletCommandEndpoint)
//    tabletCommandApikeySettingsState.postValue(tabletCommandApikey)
//    esriEndpointSettingsState.postValue(esriEndpoint)
//    enableMulticastSettingsState.postValue(enableMulticastOnWifi)
//    enableTabletCommandEndpointSettingsState.postValue(enableTabletCommandEndpoint)
//    enableEsriEndpointSettingsState.postValue(enableEsriEndpoint)
//    enableSitxServiceSettingsState.postValue(enableSitxService)
//    sitxTakUrlSettingsState.postValue(sitxTakUrl)
//    //sitxTakClientIdSettingsState.postValue(sitxTakClientId)
//
//    enableWifiBatteryOptimizationState.postValue(enableWifiBatteryOptimization)
//    enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimization)
//    enableAllWifiBatteryOptimizationState.postValue(enableAllWifiBatteryOptimization)
//    enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimization)
//    wifiSsidListState.postValue(wifiSsidList)
//    wifiSsidListSettingsState.postValue(wifiSsidList)
//    enableDynamicReportingStrategyState.postValue(enableDynamicReportingStrategy)
//    enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategy)
//    constantReportingIntervalState.postValue(constantReportingInterval)
//    constantReportingIntervalSettingsState.postValue(constantReportingInterval)
//    stationaryReportingIntervalState.postValue(stationaryReportingInterval)
//    stationaryReportingIntervalSettingsState.postValue(stationaryReportingInterval)
//    onFootReportingIntervalState.postValue(onFootReportingInterval)
//    onFootReportingIntervalSettingsState.postValue(onFootReportingInterval)
//    vehicleReportingIntervalState.postValue(vehicleReportingInterval)
//    vehicleReportingIntervalSettingsState.postValue(vehicleReportingInterval)
//    whileAlertingReportingIntervalState.postValue(whileAlertingReportingInterval)
//    whileAlertingReportingIntervalSettingsState.postValue(whileAlertingReportingInterval)
//    takServerListState.postValue(takServerList)
//    saveBattOnWifiStringState.postValue(saveBattOnWifiString)
//    smsNumbersListStringSettingsState.postValue(convertSmsNumbersListToStringWithSeparator(smsNumbersList, ","))
//    enablePhysiologicalAlertsSettingState.postValue(enablePhysiologicalAlerts)
//    restingHrHighAlertBpmThresholdState.postValue(restingHrHighAlertBpmThreshold)
//    restingHrLowAlertBpmThresholdState.postValue(restingHrLowAlertBpmThreshold)
//    highRestingHrWarningThresholdLengthState.postValue(highRestingHrWarningThresholdLength)
//    lowRestingHrWarningThresholdLengthState.postValue(lowRestingHrWarningThresholdLength)
//    highRestingHrAlertThresholdLengthState.postValue(highRestingHrAlertThresholdLength)
//    lowRestingHrAlertThresholdLengthState.postValue(lowRestingHrAlertThresholdLength)
//    exertionThresholdWarningState.postValue(exertionThresholdWarning)
//    exertionThresholdAlertState.postValue(exertionThresholdAlert)
//    exertionWarningThresholdLengthState.postValue(exertionWarningThresholdLength)
//    exertionAlertThresholdLengthState.postValue(exertionAlertThresholdLength)
//
//    //hrHighAlertBpmThresholdState.postValue(hrHighAlertBpmThreshold)
//    //hrHighAlertNumSecsThresholdState.postValue(hrHighAlertNumSecsThreshold)
//    birthYearState.postValue(birthYearValue)
//    heightFeetState.postValue(heightFeetValue)
//    heightInchesState.postValue(heightInchesValue)
//    weightState.postValue(weightValue)
//    sexState.postValue(sexValue)
//    bloodTypeState.postValue(bloodTypeValue)
////    allergyListState.postValue(allergyList)
//    userTypeState.postValue(userTypeValue)
//    uniformWaistSizeState.postValue(uniformWaistSize)
//    strideLengthState.postValue(strideLength)
//    uniformPantsLengthState.postValue(uniformPantsLength)
//    loadoutWeightState.postValue(loadoutWeight)
//    restingHeartRateState.postValue(restingHeartRate)
//}
//
//@Composable
//fun WearTAKCivSettings(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>, sitxVm: SitxViewModel, pluginVm: PluginViewModel) {
//    val navController = rememberSwipeDismissableNavController()
//    val context = LocalContext.current
////    var selectedCategoryAndRoleIndex by remember { mutableStateOf<Pair<Int, Int>?>(null) }
//    var selectedCategoryAndRoleIndex by remember {
//        mutableStateOf(findRoleSelectionFromString(roleSettingsState.value))
//    }
//    // collect license from data repo so we can see if it changes.
//    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
//    val pluginList by pluginVm.plugins.collectAsState()
//
//    SwipeDismissableNavHost(
//        navController = navController,
//        startDestination = if (isLicenseValid()) "settings_screen" else "invalid_license_screen"
//    ) {
//        composable("main_screen" ) {
////            MainScreen(
////                navController = navController,
////                callsignState = callsignLiveDataState,
////                dataRepository = dataRepository,
////                context = context
////            )
//        }
//        composable(
//            "settings_screen") {
//            SettingsScreen(navController, callsignLiveDataState, licenseLiveDataState, context)
//        }
//        composable("callsign_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = callsignSettingsState,
//                label = stringResource(R.string.callsign),
//                route = "settings_screen",
//                parseValue = { it },
//                context = context,
//            )
//        }
//        composable(
//            "role_screen") {
//            ReusablePageWithBackArrow(
//                stringResource(R.string.role),
//                {
//                    categoryRolesEntryLists.forEachIndexed { index, lrCategory ->
//                    run {
//                        ReusableTitleChip(
//                            title = lrCategory.category,
//                            route = "role_category_screen/$index",
//                            navController = navController
//                        )
//                    }
//                }
//                    Spacer(modifier = Modifier.height(15.dp))},
//                navController,
//                "settings_screen"
//            )
//        }
//        composable(
//            "role_category_screen/{index}",
//            arguments = listOf(navArgument("index") { type = NavType.IntType })
//        ) { backStackEntry ->
//            val index = backStackEntry.arguments?.getInt("index") ?: -1
//            Log.d(TAG, "role_category_screen index: $index")
//            RoleCategorySetting(
//                navController = navController,
//                categoryIndex = index,
//                selectedCategoryAndRoleIndex = selectedCategoryAndRoleIndex,
//                onRoleSelected = { selectedCategoryAndRoleIndex = it }
//            )
//        }
//        composable(
//            "team_screen") {
//            TeamSetting(navController)
//        }
//        composable(
//            "tak_server_screen") {
//            ReusablePageWithBackArrow(
//                stringResource(R.string.tak_server_connections),
//                {
//                    val takServerSettingList by takServerListState.observeAsState(initial = emptyList())
//
//                    LazyColumn(modifier = Modifier
//                        .height(200.dp)){
//                        itemsIndexed(takServerSettingList){ index, takServerItem ->
//                            var takServerChecked by remember{ mutableStateOf(takServerItem.isEnabled)}
//                            Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
//                            //TODO WEAR-450: REPLACE WITH REUSABLE CHIP
//                            SwipeableTakServerChip(
//                                index,
//                                takServerItem.name,
//                                takServerItem.address,
//                                checked = takServerChecked,
//                                onCheckedChange =  {
//                                    Log.d(TAG, "onCheckedChange index($index): $it")
//                                    takServerChecked = it
//                                    Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
//                                    takServerSettingList[index].isEnabled = it
//                                    val mutableTakServerList = takServerSettingList.toMutableList()
//                                    mutableTakServerList.get(index).isEnabled = it
//                                    takServerList = mutableTakServerList.toList()
//                                    takServerListState.postValue(takServerList)
//                                    CoroutineScope(Dispatchers.Main).launch {
//                                        dataRepository.updateTakServerItem(index, takServerItem)
//                                        Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(
//                                            MessageEvent(SERVER_TOGGLED, index)
//                                        ) }, EVENT_SEND_DELAY_MS)
//                                    } },
//                                onPrimaryActionClick = {
//                                    Log.d(TAG, "Remove takServerItem ($takServerItem)")
//                                    val mutableTakServerList = takServerSettingList.toMutableList()
//                                    mutableTakServerList.removeAt(index)
//                                    takServerList = mutableTakServerList.toList()
//                                    takServerListState.postValue(takServerList)
//                                    Log.d(TAG, "onPrimaryActionClick index($index): REMOVE")
//                                    CoroutineScope(Dispatchers.Main).launch {
//                                        dataRepository.removeTakServerItem(takServerItem)
//                                        Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(
//                                            MessageEvent(SERVER_REMOVED, index)
//                                        ) }, EVENT_SEND_DELAY_MS)
//                                    }
//                                },
//                                onSecondaryActionClick = {
//                                    navController.navigate("edit_server_screen/$index")
//                                }
//                            )
//                        }
//
//                        item {
//                            Spacer(Modifier.height(10.dp))
//
//                            ReusableTitleChip(
//                                icon = {Icon(Icons.Filled.Add, contentDescription = "Add")},
//                                title = stringResource(R.string.add_network),
//                                route= "new_server_screen",
//                                navController = navController
//                            )
//                        }
//                    }
//                    Spacer(modifier = Modifier.height(10.dp))
//                },
//                navController,
//                "settings_screen"
//            )
////            TakServerSetting(navController)
//        }
//        composable(
//            "new_server_screen") {
//            NewServerPage(navController)
//        }
//        composable(
//            "edit_server_screen/{index}",
//            arguments = listOf(navArgument("index") { type = NavType.IntType })
//        ) { backStackEntry ->
//            val index = backStackEntry.arguments?.getInt("index") ?: -1
//            Log.d(TAG, "edit_server_screen index: $index")
//            EditServerPage(navController, index)
//        }
//        composable("optimize_battery_on_wifi_screen"){
//            OptimizeBatteryOnWifiSelectScreen(navController)
//        }
//        composable(
//            "wifi_ssid_screen") {
//            WifiSsidSelectScreen(navController)
//        }
//        composable(
//            "wifi_ssid_setting_screen") {
//            NewWiFiSSIDSetting(navController)
//        }
//        composable(
//            "edit_wifi_ssid_setting_screen/{index}",
//            arguments = listOf(navArgument("index") { type = NavType.IntType })
//        ) { backStackEntry ->
//            val index = backStackEntry.arguments?.getInt("index") ?: -1
//            Log.d(TAG, "edit_wifi_ssid_setting_screen index: $index")
//            EditedWiFiSSIDSetting(navController, index)
//        }
//        composable(
//            "reporting_settings_screen") {
//            ReportingStrategySettings(navController)
//        }
//        composable(
//            "reporting_dynamic_constant_screen") {
//            ReportingStrategyDynamicConstantSetting(navController)
//        }
//        composable(
//            "constant_reporting_interval_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = constantReportingIntervalSettingsState,
//                label = "Constant Reporting Interval",
//                isInt = true,
//                route = "reporting_settings_screen",
//                maximum = 1000000,
//                minimum = 1,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "stationary_reporting_interval_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = stationaryReportingIntervalSettingsState,
//                label = "Stationary Reporting Interval",
//                isInt = true,
//                route = "reporting_settings_screen",
//                maximum = 1000000,
//                minimum = 1,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "on_foot_reporting_interval_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = onFootReportingIntervalSettingsState,
//                label = "On Foot Reporting Interval",
//                isInt = true,
//                route = "reporting_settings_screen",
//                maximum = 1000000,
//                minimum = 1,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "vehicle_reporting_interval_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = vehicleReportingIntervalSettingsState,
//                label = "Vehicle Reporting Interval",
//                isInt = true,
//                route = "reporting_settings_screen",
//                maximum = 1000000,
//                minimum = 1,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "while_alerting_reporting_interval_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = whileAlertingReportingIntervalSettingsState,
//                label = "While Alerting Reporting Interval",
//                isInt = true,
//                route = "reporting_settings_screen",
//                maximum = 1000000,
//                minimum = 1,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//
//        composable(
//            "manage_outputs_screen") {
//            ManageOutputsSetting(navController)
//        }
//        composable(
//            "tak_sa_multicast_screen") {
//            ManageOutputsMulticastSetting(navController)
//        }
//        composable(
//            "tak_sa_multicast_address_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = multicastAddressSettingsState,
//                label = stringResource(R.string.address),
//                route = "tak_sa_multicast_screen",
//                parseValue = { it },
//                context = context,
//            )
//        }
//        composable(
//            "tak_sa_multicast_output_protocol_screen") {
//            //val outputProtocolList = listOf(stringResource(R.string.udp), stringResource(R.string.tcp), stringResource(R.string.quic))
//            val outputProtocolList = listOf(stringResource(R.string.udp))
//            RadioChoiceSetting(navController, multicastProtocolSettingsState, outputProtocolList, stringResource(R.string.output_protocol), "tak_sa_multicast_screen")
//
//        }
//        composable(
//            "tak_sa_multicast_server_port_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = multicastPortSettingsState,
//                label = stringResource(R.string.server_port),
//                isInt = true,
//                route = "tak_sa_multicast_screen",
//                maximum = 99999,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable("sitx_tak_screen") {
//            ManageOutputsSitxSetting(navController, sitxVm = sitxVm)
//        }
//        composable("sitx_tak_url_screen") {
//            SitxUrlEntryPage(navController)
//            /*
//                 navController = navController,
//                metricSettingState = sitxTakUrlSettingsState,
//                label = stringResource(R.string.address_url),
//                route = "sitx_tak_screen",
//                parseValue = { it },
//                context = context,
//            )
//             */
//        }
//        composable("sitx_tak_group_screen"){
//            val loadingDialog = remember{ mutableStateOf(false)}
//            SitxGroupSetting (navController)
//        }
//        /*
//        composable("sitx_tak_client_id_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = sitxTakClientIdSettingsState,
//                label = stringResource(R.string.client_id),
//                route = "sitx_tak_screen",
//                parseValue = { it },
//                context = context,
//            )
//        } */
//        composable(
//            "tablet_command_screen") {
//            ManageOutputsTabletCommandSetting(navController)
//
//        }
//        composable(
//            "tablet_command_endpoint_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = tabletCommandEndpointSettingsState,
//                label = stringResource(R.string.endpoint),
//                route = "tablet_command_screen",
//                parseValue = { it },
//                context = context,
//            )
//        }
//        composable(
//            "tablet_command_apikey_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = tabletCommandApikeySettingsState,
//                label = stringResource(R.string.api_key),
//                route = "tablet_command_screen",
//                parseValue = { it },
//                context = context,
//            )
//        }
//        composable(
//            "esri_screen") {
//            ManageOutputsEsriSetting(navController)
//
//        }
//        composable(
//            "esri_endpoint_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = esriEndpointSettingsState,
//                label = stringResource(R.string.endpoint),
//                route = "esri_screen",
//                parseValue = { it },
//                context = context,
//            )
//        }
//        composable(
//            "atm_pressure_alerts_screen") {
//            AtmPressureAlertSettings(navController)
//        }
//        composable(
//            "bloodhound_settings_screen") {
//            BloodhoundSettings(navController)
//        }
//        composable(
//            "bloodhound_proximity_radius_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = bloodhoundProximityRadiusMetersSettingsState,
//                label = stringResource(R.string.bloodhound_proximity_radius),
//                isInt = true,
//                route = "bloodhound_settings_screen",
//                maximum = 100000,
//                minimum = 1,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "bloodhound_proximity_intensity_screen") {
//            BloodhoundIntensitySetting(navController)
//        }
//        composable(
//            "low_atm_pressure_threshold_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = lowAtmPressureAlertThresholdSettingsState,
//                label = stringResource(R.string.low_atm_pressure_threshold),
//                isInt = true,
//                route = "atm_pressure_alerts_screen",
//                maximum = 1500,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "high_atm_pressure_threshold_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = highAtmPressureAlertThresholdSettingsState,
//                label = stringResource(R.string.high_atm_pressure_threshold),
//                isInt = true,
//                route = "atm_pressure_alerts_screen",
//                maximum = 99999,
//                minimum = 500,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "gunshot_detection_threshold_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = onnxGunshotTriggerThresholdPercentSettingsState,
//                label = stringResource(R.string.gunshot_trigger_threshold),
//                isInt = true,
//                route = "settings_screen",
//                maximum = 100,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "uas_detection_threshold_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = onnxUasTriggerThresholdPercentSettingsState,
//                label = stringResource(R.string.uas_trigger_threshold),
//                isInt = true,
//                route = "settings_screen",
//                maximum = 100,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//
//        composable(
//            "license_setting_screen") {
//            LicenseKeyScreen(navController, licenseSettingsState, context)
//        }
//        composable(
//            "invalid_license_screen") {
//            InvalidLicenseScreen(navController)
//        }
//        composable(
//            "physiological_alerts_setting_screen") {
//            PhysiologicalAlertsSetting(navController)
//        }
//        composable(
//            "physiological_heart_rate_alerts_setting_screen") {
//            HeartRateAlertsSetting(navController)
//        }
//        composable(
//            "high_resting_hr_bpm_threshold_screen") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = restingHrHighAlertBpmThresholdSettingsState,
//                label = "High HR Threshold",
//                isInt = true,
//                route = "physiological_heart_rate_alerts_setting_screen",
//                maximum = 300,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "high_resting_hr_warning_threshold_length") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = highRestingHrWarningThresholdLengthSettingsState,
//                label = "Warning Length - High HR",
//                isInt = true,
//                route = "physiological_heart_rate_alerts_setting_screen",
//                maximum = 300,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "high_resting_hr_alert_threshold_length") {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = highRestingHrAlertThresholdLengthSettingsState,
//                label = "Alert Length - High HR",
//                isInt = true,
//                route = "physiological_heart_rate_alerts_setting_screen",
//                maximum = 300,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "low_resting_hr_bpm_threshold_screen"
//        ) {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = restingHrLowAlertBpmThresholdSettingsState,
//                label = "Low HR Threshold",
//                isInt = true,
//                route = "physiological_heart_rate_alerts_setting_screen",
//                maximum = 300,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "low_resting_hr_warning_threshold_length"
//        ) {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = lowRestingHrWarningThresholdLengthSettingsState,
//                label = "Warning Length - Low HR",
//                isInt = true,
//                route = "physiological_heart_rate_alerts_setting_screen",
//                maximum = 300,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "low_resting_hr_alert_threshold_length"
//        ) {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = lowRestingHrAlertThresholdLengthSettingsState,
//                label = "Alert Length - Low HR",
//                isInt = true,
//                route = "physiological_heart_rate_alerts_setting_screen",
//                maximum = 300,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "physiological_exertion_alerts_setting_screen"
//        ) {
//            ExertionAlertsSetting(navController)
//        }
//        composable(
//            "exertion_threshold_warning_screen"
//        ) {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = exertionThresholdWarningSettingsState,
//                label = "ExertionWarning Threshold",
//                isInt = true,
//                route = "physiological_exertion_alerts_setting_screen",
//                maximum = 201,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "exertion_warning_threshold_length"
//        ) {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = exertionWarningThresholdLengthSettingsState,
//                label = SettingsActivity.LABEL_WARNING_LENGTH,
//                isInt = true,
//                route = "physiological_exertion_alerts_setting_screen",
//                maximum = 201,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "exertion_threshold_alert_screen"
//        ) {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = exertionThresholdAlertSettingsState,
//                label = "Exertion Alert Threshold",
//                isInt = true,
//                route = "physiological_exertion_alerts_setting_screen",
//                maximum = 1201,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "exertion_alert_threshold_length"
//        ) {
//            ReusableTextEntryPage(
//                navController = navController,
//                metricSettingState = exertionAlertThresholdLengthSettingsState,
//                label = SettingsActivity.LABEL_ALERT_LENGTH,
//                isInt = true,
//                route = "physiological_exertion_alerts_setting_screen",
//                maximum = 1201,
//                minimum = 0,
//                parseValue = { it.toIntOrNull() },
//                context = context,
//            )
//        }
//        composable(
//            "physiological_metrics_setting_screen") {
//            PhysiologicalMetricsSetting(navController)
//        }
//        composable("physiological_metrics_medical_profile_batdok") {
//            PhysioMetricsMedicalProfileSetting(navController)
//        }
//        composable(
//            "physiological_metrics_birth_year_screen") {
//            ReusableTextEntryPage(
//                navController,
//                birthYearSettingsState,
//                stringResource(R.string.birth_year),
//                true,
//                "physiological_metrics_medical_profile_batdok",
//                Year.now().value,
//                1900,
//                { it.toIntOrNull() },
//                context,
//            )
//        }
//        composable(
//            "physiological_metrics_height_screen") {
//            HeightPickerSetting(navController)
//        }
//        composable(
//            "physiological_metrics_weight_screen"
//        ) {
//            ReusableTextEntryPage(
//                navController,
//                weightSettingsState,
//                stringResource(R.string.weight),
//                true,
//                "physiological_metrics_medical_profile_batdok",
//                1000,
//                0,
//                { it.toIntOrNull() },
//                context,
//            )
//        }
//        composable(
//            "physiological_metrics_sex_screen"
//        ) {
//            RadioChoiceSetting(
//                navController,
//                sexSettingsState,
//                sexOptionsList,
//                stringResource(R.string.sex),
//                "physiological_metrics_medical_profile_batdok"
//            )
//        }
//        composable(
//            "physiological_metrics_bloodtype_screen"
//        ) {
//            RadioChoiceSetting(
//                navController,
//                bloodTypeSettingsState,
//                bloodTypeOptionsList,
//                stringResource(R.string.blood_type),
//                "physiological_metrics_medical_profile_batdok"
//            )
//        }
////        composable(
////            "physiological_metrics_allergies_screen"
////        ) {
////            AllergySetting(navController, stringResource(R.string.allergies))
////        }
//        composable(
//            "physiological_metrics_usertype_screen"
//        ) {
//            RadioChoiceSetting(
//                navController,
//                userTypeSettingsState,
//                userTypeOptionsList,
//                stringResource(R.string.user_type),
//                "physiological_metrics_medical_profile_batdok"
//            )
//        }
//        composable(
//            "physiological_metrics_resting_hr_screen"
//        ) {
//            ReusableTextEntryPage(
//                navController,
//                restingHeartRateSettingsState,
//                stringResource(R.string.resting_heart_rate),
//                true,
//                "physiological_metrics_medical_profile_batdok",
//                150,
//                0,
//                { it.toIntOrNull() },
//                context,
//            )
//        }
//        composable("physiological_metrics_gait_tracking") {
//            PhysioMetricsGaitTrackingSettings(navController)
//        }
//        composable(
//            "physiological_metrics_uniform_waist_size"
//        ) {
//            ReusableTextEntryPage(
//                navController,
//                uniformWaistSizeSettingsState,
//                stringResource(R.string.uniform_waist_size_text),
//                true,
//                "physiological_metrics_gait_tracking",
//                100,
//                0,
//                { it.toIntOrNull() },
//                context,
//            )
//        }
//        composable(
//            "physiological_metrics_stride_length"
//        ) {
//            ReusableTextEntryPage(
//                navController,
//                strideLengthSettingsState,
//                stringResource(R.string.stride_length_text),
//                true,
//                "physiological_metrics_gait_tracking",
//                300,
//                0,
//                { it.toIntOrNull() },
//                context,
//            )
//        }
//        composable(
//            "physiological_metrics_uniform_pants_length"
//        ) {
//            ReusableTextEntryPage(
//                navController,
//                uniformPantsLengthSettingsState,
//                stringResource(R.string.uniform_pants_length_text),
//                true,
//                "physiological_metrics_gait_tracking",
//                100,
//                0,
//                { it.toIntOrNull() },
//                context,
//            )
//        }
//        composable(
//            "physiological_metrics_loadout_weight"
//        ) {
//            ReusableTextEntryPage(
//                navController,
//                loadoutWeightSettingsState,
//                stringResource(R.string.loadout_weight_text),
//                true,
//                "physiological_metrics_gait_tracking",
//                1000,
//                0,
//                { it.toIntOrNull() },
//                context,
//            )
//        }
//
//        composable(
//            "hail_setting_screen"
//        ) {
//            HailSetting(navController)
//        }
//        composable(
//            "hail_pairing_screen"
//        ) {
//            InvalidHailScreen(navController)
//        }
//        composable("sitx_status_authorization_screen") {
//            SitxStatusAuthorizationScreen(navController, sitxVm)
//        }
//        composable("crm_thresholds_screen") {
//            CrmThresholdSettings(navController)
//        }
//
//        composable("crm_poor_threshold_screen") {
//            NumericEntryPage(
//                navController = navController,
//                metricSettingState = crmPoorMaxThresholdSettingsState,
//                maximum = 100,
//                minimum = 0,
//                label = "Poor Maximum",
//                context = context,
//                route = "crm_thresholds_screen"
//            )
//        }
//        composable("plugins_screen"){
//            PluginsScreen(navController, pluginVm, pluginList)
//        }
//        composable("debug_tools") {
//            DebugScreen(navController)
//        }
//
//        composable("specific_plugin_screen/{index}",
//            arguments = listOf(navArgument("index") { type = NavType.IntType })
//        ) { backStackEntry ->
//            val index = backStackEntry.arguments?.getInt("index") ?: -1
//            Log.d(TAG, "specific_plugin_screen index: $index")
//            SpecificPluginScreen(navController, index, pluginList)
//        }
//        composable("crm_moderate_threshold_screen") {
//            NumericEntryPage(
//                navController = navController,
//                metricSettingState = crmModerateMaxThresholdSettingsState,
//                maximum = 100,
//                minimum = 0,
//                label = "Moderate Maximum",
//                context = context,
//                route = "crm_thresholds_screen"
//            )
//        }
//    }
//}
//
//// Used for higher-level settings that direct you somewhere else, typically somewhere with multiple settings options
//@Composable
//fun ReusableTitleChip(
//    icon: @Composable () -> Unit = {},
//    title: String,
//    route: String,
//    onClick: () -> Unit = {},
//    navController: NavController
//){
//    Chip(
//        onClick = {
//            onClick()
//            navController.navigate(route) {
//                popUpTo(route) { inclusive = true }
//            }
//        },
//        enabled = true,
//        label = { Text(text = title) },
//        colors = ChipDefaults.chipColors(
//            backgroundColor = Color.DarkGray,
//            contentColor = Color.White,
//            secondaryContentColor = Color.LightGray
//        ),
//        icon = { icon() },
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(bottom = 3.dp)
//    )
//}
//
//// Used for directing to a settings page, usually with a directly editable value inside. State used directly reflects this value
//@Composable
//fun <T> ReusableTitleChipWithState(
//    route: String,
//    title: String,
//    state: MutableLiveData<T>,
//    icon: @Composable () -> Unit = {},
//    onClick: () -> Unit = {},
//    navController: NavController,
//    formatState: @Composable (T?) -> String = { it?.toString() ?: "" }
//) {
//    val stateValue by state.observeAsState()
//
//    Chip(
//        onClick = {
//            onClick()
//            navController.navigate(route) },
//        enabled = true,
//        label = { Text(text = title) },
//        secondaryLabel = {
//            Text(
//                text = formatState(stateValue),
//                maxLines = 1,
//                overflow = TextOverflow.Ellipsis
//            )
//        },
//        icon = { icon() },
//        colors = ChipDefaults.chipColors(
//            backgroundColor = Color.DarkGray,
//            contentColor = Color.White,
//            secondaryContentColor = Color.LightGray
//        ),
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(bottom = 3.dp)
//    )
//}
//
////Used for services and settings with a boolean state of ON/OFF
//@Composable
//fun ReusableToggleChip(
//    checkedLive: Boolean?,
//    onCheckedChange: (Boolean) -> Unit,
//    appIcon: @Composable () -> Unit = {},
//    title: String,
//    description: String,
//) {
//    ToggleChip(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 3.dp),
//        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
//        secondaryLabel = {
//            Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis)
//        },
//        appIcon = {appIcon()},
//        checked = checkedLive == true,
//        colors =
//            ToggleChipDefaults.toggleChipColors(
//                uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
//            ),
//        toggleControl = {
//            Switch(
//                checked = checkedLive == true,
//                onCheckedChange = null,
//                enabled = true,
//            )
//        },
//        onCheckedChange = onCheckedChange,
//        enabled = true,
//    )
//}
//
////Radio button chip, typically rendered within a list that references a state change to know which chip to have selected.  Can display either as separate chips or one block of radio chips
//@Composable
//fun <T> ReusableSelectableChip(
//    selected: Boolean,
//    onClick: () -> Unit,
//    title: String,
//    combined: Boolean = false,
//    list: List<T>
//){
//    SelectableChip(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(bottom = if (combined) 0.dp else 5.dp),
//        selected = selected,
//        onClick = { onClick() },
//        label = {
//            // The primary label should have a maximum 3 lines of text
//            Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis)
//        },
//        enabled = true,
//        shape =
//            if (combined)
//                when (title) {
//                    list.first() -> RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
//                    list.last() -> RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
//                    else -> RoundedCornerShape(0.dp)
//                } else MaterialTheme.shapes.large
//    )
//}
////Used when an item in a list needs to be individually edited.
//// Typically the primary option is to delete it, and secondary is to edit the item. The chip that expands can be any chip type (toggle, radio, etc)
//@OptIn(ExperimentalWearMaterialApi::class)
//@Composable
//fun ReusableSwipeToRevealChip (
//    onPrimaryActionClick: () -> Unit,
//    onSecondaryActionClick: () -> Unit,
//    chipType: @Composable () -> Unit = {},
//    revealState: RevealState = rememberRevealState(),
//    scope: CoroutineScope = rememberCoroutineScope()
//    ){
//
//    SwipeToRevealChip(
//        revealState = revealState,
//        modifier = Modifier
//            .fillMaxWidth()
//            .semantics {
//                // Use custom actions to make the primary and secondary actions accessible
//                customActions =
//                    listOf(
//                        CustomAccessibilityAction("Delete TAK Server") {
//                            /* Add the primary action click handler here */
//                            true
//                        },
//                        CustomAccessibilityAction("Edit TAK Server") {
//                            /* Add the secondary click handler here */
//                            true
//                        }
//                    )
//            },
//        primaryAction = {
//            SwipeToRevealPrimaryAction(
//                revealState = revealState,
//                icon = { Icon(SwipeToRevealDefaults.Delete, stringResource(R.string.delete)) },
//                label = { Text(stringResource(R.string.delete)) },
//                onClick = { onPrimaryActionClick(); scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealed)}}
//            )
//        },
//        secondaryAction = {
//            SwipeToRevealSecondaryAction(
//                revealState = revealState,
//                onClick = { onSecondaryActionClick() }
//            ) {
//                Icon(SwipeToRevealDefaults.MoreOptions, "More Options")
//            }
//        },
//        undoPrimaryAction = {
//            SwipeToRevealUndoAction(
//                revealState = revealState,
//                label = { Text(stringResource(R.string.undo)) },
//                onClick = { /* Add the undo handler for primary action */ }
//            )
//        },
//        undoSecondaryAction = {
//            SwipeToRevealUndoAction(
//                revealState = revealState,
//                label = { Text(stringResource(R.string.undo)) },
//                onClick = { /* Add the undo handler for secondary action */ }
//            )
//        },
//        onFullSwipe = {onPrimaryActionClick() }
//    ) {
//        chipType()
//    }
//}
//// Used when multiple items in a list can be selected at the same time.  Currently only used for allergy selection
//@Composable
//fun <T> ReusableMultipleChoiceCheckboxChip(
//    selectedIndices: SnapshotStateList<Int>,
//    index: Int,
//    dataItem: String,
//    checked: Boolean,
//    list: List<T>
//){
//    ToggleChip(
//        modifier = Modifier.fillMaxWidth(),
//        checked = checked,
//        onCheckedChange = { checked ->
//            if (checked) selectedIndices += index
//            else selectedIndices -= index
//        },
//        toggleControl = { Checkbox(checked = checked, enabled = true,  onCheckedChange = null) },
//        label = {
//            // The primary label should have a maximum 3 lines of text
//            Text(dataItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
//        },
//        enabled = true,
//        shape =
//            if (dataItem == list.first())
//                RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
//            else if (dataItem == list.last())
//                RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
//            else
//                RoundedCornerShape(0.dp)
//    )
//}
//
//@Composable
//fun ReusableOutlinedTextField(
//    string: String,
//    label: String,
//    keyboardType: KeyboardType = KeyboardType.Unspecified,
//    keyboardActions: KeyboardActions = KeyboardActions.Default
//): TextFieldValue {
//    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
//        mutableStateOf(TextFieldValue(string ?: "null"))
//    }
//    OutlinedTextField(
//        value = textFieldValue,
//        onValueChange = {
//            textFieldValue = it
//        },
//        singleLine = true,
//        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
//        label = { Text(text = label, color = colors.primary) },
//        placeholder = { Text(text = label, color = colors.primary) },
//        textStyle = TextStyle(color = colors.primary),
//        keyboardActions = keyboardActions
//    )
//    return textFieldValue
//}
//
//@Composable
//fun ReusableConfirmCancelButtons(
//    onClickCancel: () -> Unit,
//    onClickConfirm: () -> Unit
//) {
//    Box(
//        contentAlignment = Alignment.Center,
//    ) {
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            IconButton(
//                onClick = onClickCancel,
//                modifier = Modifier
//                    .size(50.dp)
//                    .border(1.dp, color = Color.Black, shape = CircleShape),
//                colors = IconButtonDefaults.iconButtonColors(
//                    contentColor = Color.Red,
//                    containerColor = Color.DarkGray,
//                ),
//            ) {
//                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close icon")
//
//            }
//            Spacer(modifier = Modifier.width(20.dp))
//            IconButton(
//                onClick = onClickConfirm,
//                modifier = Modifier
//                    .size(50.dp)
//                    .border(1.dp, color = Color.Black, shape = CircleShape),
//                colors = IconButtonDefaults.iconButtonColors(
//                    contentColor = Color.Black,
//                    containerColor = colors.primary
//                )
//            ) {
//                Icon(
//                    imageVector = Icons.Filled.Check,
//                    contentDescription = "Confirm icon",
//                    tint = Color.Black
//                )
//
//            }
//        }
//    }
//}
//
//@Composable
//fun ReusableBackButton(onClick: () -> Unit) {
//    Button(
//        onClick = { onClick() },
//        modifier = Modifier.size(height = 35.dp, width = 65.dp),
//        colors = ButtonDefaults.buttonColors(
//            containerColor = Color.Gray,
//            contentColor = Color.Red
//        ),
//    ) {
//        Icon(
//            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
//            contentDescription = "Go back to Main Settings Screen",
//        )
//    }
//}
////Used for callsign and output string entry
//@Composable
//fun <T> ReusableTextEntryPage(
//    navController: NavController,
//    metricSettingState: MutableLiveData<T>,
//    label: String,
//    isInt: Boolean = false,
//    route: String,
//    maximum: Int = 0,
//    minimum: Int = 0,
//    parseValue: (String) -> T?,
//    context: Context,
//) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        var textField = TextFieldValue()
//        val focusManager = LocalFocusManager.current
//
//        fun key() = run {
//            if (textField.text.isEmpty()) {
//                Toast.makeText(
//                    mainActivity,
//                    context.getString(R.string.please_enter_a_value),
//                    Toast.LENGTH_LONG
//                ).show()
//            } else if (textField.text.toInt() > maximum) {
//                Toast.makeText(
//                    mainActivity,
//                    context.getString(R.string.value_exceeds_maximum_please_re_enter),
//                    Toast.LENGTH_LONG
//                ).show()
//            } else if (textField.text.toInt() < minimum) {
//                Toast.makeText(
//                    mainActivity,
//                    " Value is less than minimum, please re-enter", Toast.LENGTH_LONG
//                ).show()
//            } else {
//                focusManager.clearFocus()
//            }
//        }
//        textField = ReusableOutlinedTextField(
//            metricSettingState.value.toString(),
//            label,
//            if (isInt){
//                KeyboardType.Number
//            } else {
//                KeyboardType.Unspecified
//            },
//            if (isInt) {
//                KeyboardActions(
//                    onDone = {
//                        key()
//                    }
//                )
//            } else {
//                KeyboardActions.Default
//            }
//        )
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "$label Settings NOT saved")
//                    navController.navigate(route) {
//                        popUpTo((route)) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    val parsed = parseValue(textField.text)
//                    metricSettingState.value = parsed
//
////                    metricSettingState.setValue(textField.text as T?)
//                    Log.d(TAG, "Settings saved with $label: ${metricSettingState.value}")
//                    navController.navigate(route) {
//                        popUpTo((route)) {
//                            inclusive = true
//                        }
//                    }
//                })
//        }
//    }
//}
//
//@Composable
//fun ReusablePageWithBackArrow(
//    title: String,
//    columnContent: @Composable () -> Unit,
//    navController: NavController,
//    route: String
//) {
//    WearTakCivScreenDimensions(
//        columnContent = { columnContent() },
//        rowContent = {
//            ReusableBackButton(
//                onClick = {
//                    navController.navigate(route) {
//                        popUpTo((route)) {
//                            inclusive = true
//                        }
//                    }
//                },
//            )
//        },
//        title
//    )
//}
//
//@Composable
//fun GenericOutlinedTextField(
//    liveDataState: MutableLiveData<String>,
//    label: String,
//    placeholder: String
//): TextFieldValue {
//    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
//        mutableStateOf(TextFieldValue(liveDataState.value ?: "null"))
//    }
//    OutlinedTextField(
//        value = textFieldValue,
//        onValueChange = {
//            textFieldValue = it
//        },
//        singleLine = true,
//        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
//        label = { Text(text = label, color = colors.primary) },
//        placeholder = { Text(text = placeholder, color = colors.primary) },
//        textStyle = TextStyle(color = colors.primary)
//    )
//    return textFieldValue
//}
//
//@Composable
//fun WearTakCivScreenDimensions(
//    columnContent: @Composable () -> Unit,
//    rowContent: @Composable () -> Unit,
//    text: String
//) {
//    WearTAKCIVTheme {
//        val listState = rememberScalingLazyListState()
//        Scaffold(
//            positionIndicator = {
//                PositionIndicator(scalingLazyListState = listState)
//            }
//        ) {
//            Box(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .background(colors.background)
//                    .padding(bottom = 16.dp, top = 15.dp)
//                    .padding(horizontal = 16.dp),
//                contentAlignment = Alignment.Center
//            ) {
//                Column(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .fillMaxHeight(0.9f)
//                        .align(Alignment.TopCenter)
//                        .padding(bottom = 30.dp)
//                        .verticalScroll(rememberScrollState()),
//
//                    horizontalAlignment = Alignment.CenterHorizontally
//                ) {
//                    Text(
//                        text = text,
//                        fontSize = if (text.length!! < 20) 15.sp else 13.sp,
//                        textAlign = TextAlign.Center,
//                        fontWeight = FontWeight.SemiBold,
//                        modifier = Modifier
//                            .padding(vertical = 10.dp, horizontal = 20.dp)
//                            .align(Alignment.CenterHorizontally)
//                    )
//                    columnContent()
//                }
//                Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//                    rowContent()
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun CallsignRoleTeamChip(
//    route: String,
//    title: String,
//    nameState: MutableLiveData<String>,
//    navController: NavController
//) {
//    val nameSecondary: String? by nameState.observeAsState()
//    Chip(
//        onClick = { navController.navigate(route) },
//        enabled = true,
//        label = { Text(text = title) },
//        secondaryLabel = {
//            Text(text = nameSecondary.toString(), maxLines =1, overflow = TextOverflow.Ellipsis)
//        },
//        colors = ChipDefaults.chipColors(
//            backgroundColor = Color.DarkGray,
//            contentColor = Color.White,
//            secondaryContentColor = Color.LightGray
//        ),
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(bottom = 3.dp)
//    )
//}
//
//@Composable
//fun CrmThresholdSettings(navController: NavController) {
//    WearTakCivScreenDimensions(
//        columnContent = {
//            DataMetricsChip(
//                route = "crm_poor_threshold_screen",
//                title = "Poor Maximum",
//                nameSecondary = crmPoorMaxThresholdSettingsState.value.toString(),
//                navController = navController
//            )
//
//            DataMetricsChip(
//                route = "crm_moderate_threshold_screen",
//                title = "Moderate Maximum",
//                nameSecondary = crmModerateMaxThresholdSettingsState.value.toString(),
//                navController = navController
//            )
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "CRM threshold settings not saved")
//
//                    crmPoorMaxThresholdSettingsState.setValue(
//                        crmPoorMaxThreshold
//                    )
//                    crmModerateMaxThresholdSettingsState.setValue(
//                        crmModerateMaxThreshold
//                    )
//
//                    navController.navigate("settings_screen") {
//                        popUpTo("settings_screen") {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    val poorMax =
//                        crmPoorMaxThresholdSettingsState.value
//                            ?: crmPoorMaxThreshold
//
//                    val moderateMax =
//                        crmModerateMaxThresholdSettingsState.value
//                            ?: crmModerateMaxThreshold
//
//                    if (poorMax >= moderateMax) {
//                        Toast.makeText(
//                            mainActivity,
//                            "Poor maximum must be less than moderate maximum",
//                            Toast.LENGTH_LONG
//                        ).show()
//
//                        return@ConfirmCancelButtons
//                    }
//
//                    navController.navigate("settings_screen") {
//                        popUpTo("settings_screen") {
//                            inclusive = true
//                        }
//                    }
//                }
//            )
//        },
//        text = "CRM Thresholds"
//    )
//}
//
////TODO: WEAR-450- DELETE THIS!!!!
//@Composable
//fun CallsignSetting(navController: NavController, callsignLiveDataState: MutableLiveData<String>) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val textField = ReusableOutlinedTextField(callsignLiveDataState.value ?: "null", stringResource(R.string.callsign))
//
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Callsign Settings NOT saved")
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    callsignLiveDataState.postValue(textField.text)
//                    Log.d(TAG, "Settings saved with callsign: ${callsignLiveDataState.value}")
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        }
//    }
//}
//
//@Composable
//fun TeamSetting(navController: NavController) {
//    val index : Int = findTeamIndex(teamSettingsState.value)
//    var selectedRadioIndex by remember { mutableStateOf(index) }
//    WearTakCivScreenDimensions(
//        columnContent = {
//            teamEntriesValuesList.ltEntries.forEachIndexed { index, ltItem ->
//                run {
//                    ReusableSelectableChip(
//                        selected = index == selectedRadioIndex,
//                        onClick = { selectedRadioIndex = index},
//                        title = ltItem,
//                        combined = true,
//                        list = teamEntriesValuesList.ltEntries
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(15.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Team Settings NOT saved")
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    teamSettingsState.postValue(teamEntriesValuesList.ltEntries[selectedRadioIndex])
//                    Log.d(TAG, "Settings saved with team: ${teamSettingsState.value}")
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                }
//            )
//        },
//        text = stringResource(R.string.team)
//    )
//}
//
////TODO: WEAR-450- DELETE THIS!!!!
//@Composable
//fun RoleSetting (navController: NavController) {
////    val index = findRoleIndex(roleSettingsState.value)
////    var selectedRadioIndex by remember { mutableStateOf(index) }
//    WearTakCivScreenDimensions(
//        columnContent = {
//            categoryRolesEntryLists.forEachIndexed { index, lrCategory ->
//                run {
//                    ReusableTitleChip(
//                        title = lrCategory.category,
//                        route = "role_category_screen/$index",
//                        navController = navController
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(15.dp))
//        },
//    rowContent = {
//        ReusableBackButton(
//            onClick = {
//                //                roleSettingsState.postValue(roleEntriesValuesList.lrEntries[selectedRadioIndex])
////                Log.d(TAG, "Settings saved with role: ${roleSettingsState.value}")
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    } },
//            )
//        },
//        text = stringResource(R.string.role)
//    )
//}
//
//@Composable
//fun RoleCategorySetting (
//    navController: NavController,
//    categoryIndex: Int,
//    selectedCategoryAndRoleIndex: Pair<Int, Int>?,
//    onRoleSelected: (Pair<Int, Int>) -> Unit
//) {
//    var localSelection by remember { mutableStateOf(selectedCategoryAndRoleIndex)}   // whatever the user's current pick is, e.g. (2,5)
////    val index = findRoleIndex(roleSettingsState.value)
//    val categoryRoleListSetting = categoryRolesEntryLists.getOrNull(categoryIndex)
//    val roleList = categoryRoleListSetting?.roles.orEmpty()
//    WearTakCivScreenDimensions(
//        columnContent = {
//            LazyColumn(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(300.dp)) {
//                itemsIndexed(roleList) { roleIndex, role ->
//                    val isSelected = selectedCategoryAndRoleIndex?.let {
//                        it.first == categoryIndex && it.second == roleIndex
//                    } ?: false
//                    ReusableSelectableChip(
//                        selected = isSelected,
//                        onClick = {
//                            localSelection = categoryIndex to roleIndex
//                            onRoleSelected(categoryIndex to roleIndex)},
//                        title = role,
//                        combined = true,
//                        list = roleList
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(15.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Role Settings NOT saved")
//                    navController.navigate("role_screen") {
//                        popUpTo(("role_screen")) {
//                            inclusive = true
//                        }
//                    }                },
//                onClickConfirm = {
////                    val selectedRole = selectedCategoryAndRoleIndex?.let { (categoryIndex, roleIndex) ->
////                        roleCategoryEntryLists.getOrNull(categoryIndex)?.roles?.getOrNull(roleIndex)
////                    }
////                    if (selectedRole != null) {
////                        Log.d(TAG, "Selected role to save: $selectedRole")
////                        roleSettingsState.postValue(selectedRole)
////
////                    } else {
////                        Log.d(TAG, "No role selected.")
////                    }
////                    Log.d(TAG, "Settings saved with role: ${roleSettingsState.value}")
//
//                    localSelection?.let{ (categoryIndex, roleIndex) ->
//                        val entry = categoryRolesEntryLists[categoryIndex]
//                        val selectedRole = entry.roles[roleIndex]
//
//                        Log.d(TAG, "Saving category=${entry.category}, role=$selectedRole")
//                        categorySettingsState.postValue(entry.category)
//                        roleSettingsState.postValue(selectedRole)
//                    } ?: Log.d(TAG, "No role selected")
//                    navController.navigate("role_screen") {
//                        popUpTo(("role_screen")) { inclusive = true }
//                    }
//                })
//        },
//        text = "Role - " + categoryRoleListSetting?.category
//    )
//}
//
//fun findRoleSelectionFromString(roleString: String?): Pair<Int, Int>? {
//    if (roleString == null) return null
//    for ((categoryIndex, categoryRole) in categoryRolesEntryLists.withIndex()) {
//        val roleIndex = categoryRole.roles.indexOf(roleString)
//        if (roleIndex != -1) return Pair(categoryIndex, roleIndex)
//    }
//    return null
//}
//
////TODO: WEAR-450- DELETE THIS!!!!
//@SuppressLint("CoroutineCreationDuringComposition")
//@Composable
//fun TakServerSetting(navController: NavController) {
//    val takServerSettingList by takServerListState.observeAsState(initial = emptyList())
//    //val selectedIndexState = MutableLiveData<Int?>(null)
//    //val selectedIndex by selectedIndexState.observeAsState(initial = null)
//    Log.d(TAG, "TakServerSetting called")
//    WearTakCivScreenDimensions(
//        columnContent = {
//            LazyColumn(modifier = Modifier
//                .height(200.dp)){
//                itemsIndexed(takServerSettingList){ index, takServerItem ->
//                    var takServerChecked by remember{ mutableStateOf(takServerItem.isEnabled)}
//                    Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
//                    SwipeableTakServerChip(
//                        index,
//                        takServerItem.name,
//                        takServerItem.address,
//                        checked = takServerChecked,
//                        onCheckedChange =  {
//                            Log.d(TAG, "onCheckedChange index($index): $it")
//                            takServerChecked = it
//                            Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
//                            takServerSettingList[index].isEnabled = it
//                            val mutableTakServerList = takServerSettingList.toMutableList()
//                            mutableTakServerList.get(index).isEnabled = it
//                            takServerList = mutableTakServerList.toList()
//                            takServerListState.postValue(takServerList)
//                            //selectedIndexState.postValue(null)
//                            CoroutineScope(Dispatchers.Main).launch {
//                                dataRepository.updateTakServerItem(index, takServerItem)
//                                Handler(Looper.getMainLooper()).postDelayed({
//                                    EventBus.getDefault().post(MessageEvent(SERVER_TOGGLED, index))
//                                }, EVENT_SEND_DELAY_MS)
//                            } },
//                        onPrimaryActionClick = {
//                            Log.d(TAG, "Remove takServerItem ($takServerItem)")
//                            val mutableTakServerList = takServerSettingList.toMutableList()
//                            mutableTakServerList.removeAt(index)
//                            takServerList = mutableTakServerList.toList()
//                            takServerListState.postValue(takServerList)
//                            //selectedIndexState.postValue(null)
//                            Log.d(TAG, "onPrimaryActionClick index($index): REMOVE")
//                            CoroutineScope(Dispatchers.Main).launch {
//                                dataRepository.removeTakServerItem(takServerItem)
//                                Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(
//                                    MessageEvent(SERVER_REMOVED, index)
//                                ) }, EVENT_SEND_DELAY_MS)
//                            }
//                        },
//                        onSecondaryActionClick = {
//                            //selectedIndexState.postValue(index)
//                            navController.navigate("edit_server_screen/$index")
//                        }
//                    )
//                }
//
//                item {
//                    Spacer(Modifier.height(10.dp))
//
//                    ReusableTitleChip(
//                        icon = {Icon(Icons.Filled.Add, contentDescription = "Add")},
//                        title = stringResource(R.string.add_network),
//                        route= "new_server_screen",
//                        navController = navController
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(10.dp))
//        },
//        rowContent = {
//            ReusableBackButton(
//                onClick = {
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    } },
//            )
//        },
//        stringResource(R.string.tak_server_connections)
//    )
//    Log.d(TAG, "Tak Server List: $takServerSettingList")
//}
//
//@OptIn(ExperimentalWearMaterialApi::class)
//@Composable
//fun SwipeableTakServerChip(
//    index: Int,
//    title: String,
//    address: String,
//    checked: Boolean,
//    onCheckedChange: (Boolean) -> Unit,
//    onPrimaryActionClick: () -> Unit,
//    onSecondaryActionClick: () -> Unit
//) {
//    val takServerStatus by TAKConnectService.takServerDetails.get(index).connectionStatusLiveData.observeAsState(initial = TAK_SERVER_DISCONNECTED_STRING)
//    val revealState = rememberRevealState()
//    val scope = rememberCoroutineScope()
//    Log.d(TAG, "SwipeableTakServerChip called with index $index: takServerStatus($takServerStatus)")
//    ReusableSwipeToRevealChip(
//        onPrimaryActionClick = { onPrimaryActionClick() },
//        onSecondaryActionClick = { onSecondaryActionClick() },
//        revealState = revealState,
//        scope = scope,
//        chipType = {
//            ReusableToggleChip(
//                checkedLive = checked,
//                onCheckedChange = onCheckedChange,
//                appIcon = {Image(
//                    painter = if (takServerStatus == TAK_SERVER_CONNECTED_STRING) painterResource(R.drawable.tak_server_connected) else if (takServerStatus == TAK_SERVER_CONNECTING_STRING) painterResource(R.drawable.tak_server_connecting) else painterResource(R.drawable.tak_server_disconnected),
//                    contentDescription = "Top Left Icon",
//                    modifier = Modifier
////                    .align(Alignment.CenterVertically)
//                        .size(22.dp)
//                        .padding(end = 2.dp),
//                )},
//                title = title,
//                description = address
//            )
//        }
//    )
//}
//
//@Composable
//fun NewServerPage(navController: NavController) {
//    val isNetworkConnected = remember { mutableStateOf(false) }
//    WearTAKCIVTheme {
//        var name by remember { mutableStateOf("") }
//        var address by remember { mutableStateOf("") }
//        var port by remember { mutableStateOf("8089") }
//        var username by remember { mutableStateOf("") }
//        var password by remember { mutableStateOf("") }
//        var isP12Cert = false
//        val serverSettingsChanged = remember { mutableStateOf(false) }
//        val scope = rememberCoroutineScope()
//
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(colors.background)
//                .padding(bottom = 16.dp, top = 15.dp)
//                .padding(horizontal = 16.dp),
//            contentAlignment = Alignment.Center
//        ) {
//            Column(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .fillMaxHeight(0.9f)
//                    .align(Alignment.TopCenter)
//                    .padding(bottom = 20.dp)
//                    .verticalScroll(rememberScrollState()),
//                horizontalAlignment = Alignment.CenterHorizontally
//            ) {
//                Text(
//                    text = stringResource(R.string.new_server),
//                    textAlign = TextAlign.Center,
//                    fontWeight = FontWeight.SemiBold,
//                    modifier = Modifier.padding(7.dp)
//                )
//                name = ReusableOutlinedTextField(
//                    string = name,
//                    label = stringResource(R.string.connection_name),
//                    keyboardType = KeyboardType.Text
//                ).text
//                address = ReusableOutlinedTextField(
//                    string = address,
//                    label = stringResource(R.string.address_url),
//                    keyboardType = KeyboardType.Text
//                ).text
//                port = ReusableOutlinedTextField(
//                    string = port,
//                    label = stringResource(R.string.port),
//                    keyboardType = KeyboardType.Number
//                ).text
//                username = ReusableOutlinedTextField(
//                    string = username,
//                    label = stringResource(R.string.username),
//                    keyboardType = KeyboardType.Text
//                ).text
//                password = ReusableOutlinedTextField(
//                string = password,
//                label = stringResource(R.string.password),
//                keyboardType = KeyboardType.Text
//                ).text
//                Spacer(modifier = Modifier.height(10.dp))
//            }
//
//            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//
//                ReusableConfirmCancelButtons(
//                    onClickCancel = {
//                        Log.d(TAG, "NewServerPage Settings NOT saved")
//                        navController.navigate("tak_server_screen") {
//                            popUpTo(("tak_server_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    },
//                    onClickConfirm = {
//                        scope.launch {
//                            if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() && username.isNotBlank() &&
//                                password.isNotBlank()) {
//                                val takServerItem = DataRepository.TakServerItem(true, name, address, port.toInt(),
//                                    isP12Cert, username, password, "", "")
//                                val addResult = dataRepository.addTakServerItem(takServerItem)
//                                serverSettingsChanged.value = true
//                                if (addResult == DataRepository.TakServerAddResult.ADDED) {
//                                    val persistedList = dataRepository.tryAwaitTakServerList()
//                                        ?: return@launch
//                                    takServerList = persistedList
//                                    takServerListState.postValue(takServerList)
//                                    Handler(Looper.getMainLooper()).postDelayed({
//                                        EventBus.getDefault().post(
//                                            MessageEvent(SERVER_ADDED_VIA_UI, persistedList.lastIndex)
//                                        )
//                                    }, EVENT_SEND_DELAY_MS)
//                                } else {
//                                    TAKConnectService.newServerAddedViaUiConnectionStatusLiveData
//                                        .postValue(TAK_SERVER_DUPLICATE_STRING)
//                                }
//                            } else {
//                                Toast.makeText(
//                                    mainActivity,
//                                    mainActivity.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
//                            }
//                        }
//                    }
//                )
//                Log.d(TAG, "NewServerPage serverSettingsChanged=${serverSettingsChanged.value}")
//                if (serverSettingsChanged.value) {
//                    val loadingDialog by TAKConnectService.newServerAddedViaUiConnectionStatusLiveData.observeAsState()
//                    val shouldShowLoadingDialog = remember { mutableStateOf(false) }
//                    val shouldShowConfirmDialog = remember { mutableStateOf(false) }
//                    val isServerConnected = remember { mutableStateOf(false) }
//
//                    if (NetworkManager.isNetworkConnected) {
//                        Log.d(TAG, "NewServerPage: serverSettingsChanged w/ Network connected")
//                        isNetworkConnected.value = true
//                        val isDuplicate = remember { mutableStateOf(false) }
//                        displayServerDialogs(true, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected, isDuplicate)
//                        LoadingDialog(shouldShowLoadingDialog, stringResource(R.string.registering_device_with_tak_server))
//                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog,shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged, isDuplicate)
//                    } else {
//                        Log.d(TAG, "NewServerPage: serverSettingsChanged w/ Network disconnected")
//                        isNetworkConnected.value = false
//                        val isDuplicate = remember { mutableStateOf(false) }
//                        displayServerDialogs(false, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected, isDuplicate)
//                        //LoadingDialog(shouldShowLoadingDialog)
//                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged, isDuplicate)
//                       }
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun EditServerPage(
//    navController: NavController,
//    index: Int
//) {
//    WearTAKCIVTheme {
//        val takServerListLocal by takServerListState.observeAsState(initial = emptyList())
//        Log.d(TAG, "Edited tak server list: $takServerListLocal")
//        var name by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.name.orEmpty()) }
//        var address by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.address.orEmpty()) }
//        var port by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.port.toString().orEmpty()) }
//        var username by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.username.orEmpty()) }
//        var password by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.password.orEmpty()) }
//        var isP12Cert by remember { mutableStateOf(takServerListLocal.get(index)?.isP12Cert ?: false) }
//        val serverConnSettingsChanged = remember { mutableStateOf(false) }
//        Log.d(TAG, "address=$address, serverSettingsChanged=${serverConnSettingsChanged.value}")
//
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(colors.background)
//                .padding(bottom = 16.dp, top = 15.dp)
//                .padding(horizontal = 16.dp),
//            contentAlignment = Alignment.Center
//        ) {
//
//            Column(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .fillMaxHeight(0.9f)
//                    .align(Alignment.TopCenter)
//                    .padding(bottom = 20.dp)
//                    .verticalScroll(rememberScrollState()),
//                horizontalAlignment = Alignment.CenterHorizontally
//            ) {
//                Text(
//                    text = stringResource(R.string.edit_server),
//                    textAlign = TextAlign.Center,
//                    fontWeight = FontWeight.SemiBold,
//                    modifier = Modifier.padding(7.dp)
//                )
//                name = ReusableOutlinedTextField(
//                    string = name,
//                    label = stringResource(R.string.connection_name),
//                    keyboardType = KeyboardType.Text
//                ).text
//                if (isP12Cert) {
//                    address = ReusableOutlinedTextField(
//                        string = address,
//                        label = stringResource(R.string.address_url),
//                        keyboardType = KeyboardType.Text
//                    ).text
//                    port = ReusableOutlinedTextField(
//                        string = port,
//                        label = stringResource(R.string.port),
//                        keyboardType = KeyboardType.Number
//                    ).text
//                } else {
//                    address = ReusableOutlinedTextField(
//                        string = address,
//                        label = stringResource(R.string.address_url),
//                        keyboardType = KeyboardType.Text
//                    ).text
//                    port = ReusableOutlinedTextField(
//                        string = port,
//                        label = stringResource(R.string.port),
//                        keyboardType = KeyboardType.Number
//                    ).text
//                    username = ReusableOutlinedTextField(
//                        string = username,
//                        label = stringResource(R.string.username),
//                        keyboardType = KeyboardType.Text
//                    ).text
//                    password = ReusableOutlinedTextField(
//                        string = password,
//                        label = stringResource(R.string.password),
//                        keyboardType = KeyboardType.Text
//                    ).text
//                }
//                if (isP12Cert) {
//                    Text(
//                        text = "Using client certificate",
//                        textAlign = TextAlign.Center,
//                        fontSize = 12.sp,
//                        modifier = Modifier.padding(5.dp)
//                    )
//                }
//                Spacer(modifier = Modifier.height(10.dp))
//            }
//
//            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//
//                ReusableConfirmCancelButtons(
//                    onClickCancel = {
//                        Log.d(TAG, "EditServerPage Settings NOT saved")
//                        navController.navigate("tak_server_screen") {
//                            popUpTo(("tak_server_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    },
//                    onClickConfirm = {
//                        if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() &&
//                            (if (isP12Cert) { true } else { username.isNotBlank() } ) &&
//                            (if (isP12Cert) { true } else { password.isNotBlank() } ) ) {
//                            val takServerDetails = takServerList.get(index)
//                            Log.d(TAG, "EditServerPage: onClickConfirm all settings good")
//                            Log.d(TAG, "takServerDetails: $takServerDetails")
//                            Log.d(TAG, "name: $name, address: $address, port=$port, username=$username, password=$password")
//                            if (name != takServerDetails.name || address != takServerDetails.address || port != takServerDetails.port.toString() ||
//                                username != takServerDetails.username || password != takServerDetails.password) {
//                                if (name == takServerDetails.name) {
//                                    // Name is same, so something else in address, port, username, or password has changed
//                                    serverConnSettingsChanged.value = true
//                                    Log.d(TAG, "serverConnSettingsChanged set to true")
//                                } else { // Name is different
//                                    if (address != takServerDetails.address || port.toInt() != takServerDetails.port ||
//                                        username != takServerDetails.username || password != takServerDetails.password) {
//                                        // Apart from name, something else is different
//                                        serverConnSettingsChanged.value = true
//                                        Log.d(TAG, "serverConnSettingsChanged set to true with name being different")
//                                    }
//                                }
//                                val changedTakServerDetail: DataRepository.TakServerItem =
//                                    if (isP12Cert == true) {
//                                        DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
//                                            takServerListLocal.get(index).username, takServerListLocal.get(index).password, takServerListLocal.get(index).p12Cert, takServerListLocal.get(index).p12CertPassword)
//                                    } else {
//                                        DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
//                                            username, password, "", "")
//                                    }
//
//                                val updateResult = runBlocking {
//                                    dataRepository.updateTakServerItem(index, changedTakServerDetail)
//                                }
//                                if (updateResult == DataRepository.TakServerUpdateResult.UPDATED) {
//                                    val mutableTakServerList = takServerListLocal.toMutableList()
//                                    mutableTakServerList.set(index, changedTakServerDetail)
//                                    takServerList = mutableTakServerList.toList()
//                                    takServerListState.postValue(takServerList)
//
//                                    if (serverConnSettingsChanged.value) {
//                                        Log.d(TAG, "EditServerPage serverSettingsChanged=${serverConnSettingsChanged.value}")
//                                        EventBus.getDefault().post(MessageEvent(SERVER_CHANGED, index))
//                                    } else {
//                                        navController.navigate("tak_server_screen") {
//                                            popUpTo(("tak_server_screen")) { inclusive = true }
//                                        }
//                                    }
//                                } else if (updateResult == DataRepository.TakServerUpdateResult.DUPLICATE) {
//                                    TAKConnectService.takServerDetails.getOrNull(index)
//                                        ?.connectionStatusLiveData
//                                        ?.postValue(TAK_SERVER_DUPLICATE_STRING)
//                                }
//                            } else {
//                                Log.d(TAG, "EditServerPage onClickConfirm: nothing has changed")
//                                serverConnSettingsChanged.value = false
//                                navController.navigate("tak_server_screen") {
//                                    popUpTo(("tak_server_screen")) {
//                                        inclusive = true
//                                    }
//                                }
//                            }
//                        } else {
//                            Toast.makeText(mainActivity, mainActivity.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
//                        }
//                    }
//                )
//                Log.d(TAG, "EditServerPage: loadingDialog serverSettingsChanged=${serverConnSettingsChanged.value}")
//                if (serverConnSettingsChanged.value) {
//                    val loadingDialog by TAKConnectService.takServerDetails.get(index).connectionStatusLiveData.observeAsState()
//                    val shouldShowLoadingDialog = remember { mutableStateOf(false) }
//                    val shouldShowConfirmDialog = remember { mutableStateOf(false) }
//                    val isServerConnected = remember { mutableStateOf(false) }
//                    if (NetworkManager.isNetworkConnected) {
//                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network connected")
//                        val isNetworkConnected = remember { mutableStateOf(true)}
//
//                        val isDuplicate = remember { mutableStateOf(false) }
//                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected, isDuplicate)
//                        LoadingDialog(shouldShowLoadingDialog, stringResource(R.string.registering_device_with_tak_server))
//                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverConnSettingsChanged, isDuplicate)
//                    } else {
//                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network disconnected")
//                        val isNetworkConnected = remember { mutableStateOf(false)}
//
//                        val isDuplicate = remember { mutableStateOf(false) }
//                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected, isDuplicate)
//                        //LoadingDialog(shouldShowLoadingDialog)
//                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverConnSettingsChanged, isDuplicate)
//                    }
//                }
//            }
//        }
//    }
//}
//
//@Composable
////TODO: Edit Selected Options no longer jumps to bottom of screen, need to fix
//fun OptimizeBatteryOnWifiSelectScreen(navController: NavController) {
//    val enableWifiOpt = enableWifiBatteryOptimizationSettingsState.value ?: true
//    val enableAllWifiOpt = enableAllWifiBatteryOptimizationSettingsState.value ?: true
//    val SOME_WIFI_CONNECTIONS = stringResource(R.string.some_wifi_connections)
//    val ALL_WIFI_CONNECTIONS = stringResource(R.string.all_wifi_connections)
//    val NO_WIFI_CONNECTIONS = stringResource(R.string.no_wifi_connections)
//    val radioOptions = listOf(ALL_WIFI_CONNECTIONS, NO_WIFI_CONNECTIONS, SOME_WIFI_CONNECTIONS)
//    val initialValue = when {
//        enableWifiOpt && enableAllWifiOpt -> radioOptions[0]  // "All"
//        !enableWifiOpt -> radioOptions[1] // "No"
//        else -> radioOptions[2] // "Some"
//    }
//    val selectedValue = remember { mutableStateOf(initialValue) }
//    val isSelectedItem: (String) -> Boolean = { selectedValue.value == it }
//    val onChangeState: (String) -> Unit = { selectedValue.value = it }
//    val scrollState = rememberScrollState()
//    val scope = rememberCoroutineScope()
//
//    //Log.d(TAG, "OptimizeBatteryonWifiSelectScreen called")
//    WearTakCivScreenDimensions(
//        columnContent = {
//            Text(
//                text = stringResource(R.string.slows_update_rate_on_enabled_wifi_networks),
//                textAlign = TextAlign.Center,
//                fontSize = 12.sp,
//                lineHeight = 15.sp,
//                modifier = Modifier
//                    .padding(bottom = 16.dp)
//                    .padding(horizontal = 10.dp)
//            )
//
//            Text(
//                text = stringResource(R.string.optimize_on),
//                textAlign = TextAlign.Left,
//                modifier = Modifier
//                    .padding(bottom = 3.dp)
//                    .padding(horizontal = 10.dp)
////                    .align(Alignment.Start)
//            )
//            Log.d(TAG, "initial  value = $initialValue, selected value: ${selectedValue.value}")
//            radioOptions.forEach { text ->
//                ReusableSelectableChip(
//                    selected = isSelectedItem(text),
//                    onClick = {
//                        onChangeState(text)
//                        if (text == SOME_WIFI_CONNECTIONS) {
//                            enableWifiBatteryOptimizationSettingsState.postValue(true)
//                            enableAllWifiBatteryOptimizationSettingsState.postValue(false)
//                            scope.launch { scrollState.animateScrollTo(scrollState.maxValue + 150) }
//                        } else if (text == ALL_WIFI_CONNECTIONS) {
//                            enableWifiBatteryOptimizationSettingsState.postValue(true)
//                            enableAllWifiBatteryOptimizationSettingsState.postValue(true)
//                        } else {
//                            enableWifiBatteryOptimizationSettingsState.postValue(false)
//                            enableAllWifiBatteryOptimizationSettingsState.postValue(false)
//                        }
//                    },
//                    title = text,
//                    list = radioOptions
//                )
//
//            }
//            if (selectedValue.value == SOME_WIFI_CONNECTIONS) {
//                Spacer(Modifier.height(10.dp))
//
//                ReusableTitleChip(
//                title = stringResource(R.string.edit_optimized_wifi_connections),
//                route = "wifi_ssid_screen",
//                onClick = {
//                    Log.d(TAG, "Some WiFi Connections selected: initial value = $initialValue, selected value: ${selectedValue.value}, isWifiOpt = $enableWifiOpt, isAllWifiOpt = $enableAllWifiOpt, wifiSsidList = ${wifiSsidListSettingsState.value}")
//                },
//                navController = navController
//                )
//            }
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "OptimizeBatteryonWifiSelectScreen Settings NOT saved")
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    // TODO: check why enableWifiBatteryOptimizationState.value is Boolean?
//                    val prevWifiOpt: Boolean = enableWifiBatteryOptimizationState.value ?: true
//                    val prevAllOpt: Boolean = enableAllWifiBatteryOptimizationState.value ?: true
//                    val currWifiOpt = enableWifiBatteryOptimizationSettingsState.value ?: true
//                    val currAllOpt = enableAllWifiBatteryOptimizationSettingsState.value ?: true
//
//                    if ((prevWifiOpt != currWifiOpt) || (prevAllOpt != currAllOpt) || (wifiSsidListState.value != wifiSsidListSettingsState.value)) {
//                        Log.d(TAG, "Changed sthg: enableWifiOpt(old=$prevWifiOpt, new=$currWifiOpt), enableOptimAllWifi(old=$prevAllOpt, new=$currAllOpt), wifiSsidList(old=${wifiSsidListState.value}, new=${wifiSsidListSettingsState.value})")
//                        saveBattOnWifiStringState.postValue(generateSaveBattOnWifiString(currWifiOpt ?: true, currAllOpt ?: true))
//                        enableWifiBatteryOptimizationState.postValue(currWifiOpt)
//                        enableAllWifiBatteryOptimizationState.postValue(currAllOpt)
//                        wifiSsidListState.postValue(wifiSsidListSettingsState.value)
//                    } else {
//                        Log.d(TAG, "Settings NOT changed, NOT saved")
//                    }
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//
//                })
//        },
//        text = stringResource(R.string.save_battery_on_wifi)
//    )
//}
//
//@Composable
//fun WifiSsidSelectScreen(navController: NavController) {
//    //Log.d(TAG, "WifiSsidSelectScreen called")
//    val wifiSsidList by wifiSsidListSettingsState.observeAsState(initial = emptyList())
//    WearTakCivScreenDimensions(
//        columnContent = {
//            LazyColumn ( modifier = Modifier
//                .height(100.dp)){
//                Log.d(TAG, "SSID List = $wifiSsidList")
//                itemsIndexed(wifiSsidList){ index, wifiSsidItem ->
//                    WifiSsidItemChip(
//                        wifiSsidItem.name,
//                        onPrimaryActionClick = {
//                            Log.d(TAG, "Remove wifiSsidItem ($wifiSsidItem)")
//                            val mutableWifiSsidList = wifiSsidList.toMutableList()
//                            mutableWifiSsidList.removeAt(index)
//                            wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())
//                        },
//                        onSecondaryActionClick = {
//                            // Navigate to "edit_wifi_ssid_setting_screen/<index>"
//                            //Log.d(TAG, "onSecondaryActionClick called")
//                            navController.navigate("edit_wifi_ssid_setting_screen/$index")
//                        }
//                    )
//                }
//
//                item {
//                    Spacer(Modifier.height(10.dp))
//
//                    ReusableTitleChip(
//                    icon = {Icon(Icons.Filled.Add, contentDescription = "Add")},
//                    title = stringResource(R.string.add_network),
//                    route= "wifi_ssid_setting_screen",
//                    navController = navController
//                    )
//                }
//
//            }
//            Spacer(modifier = Modifier.height(10.dp))
//        },
//        rowContent = {
//            ReusableBackButton(
//                onClick = { navController.navigate("optimize_battery_on_wifi_screen") {
//                    popUpTo(("optimize_battery_on_wifi_screen")) {
//                        inclusive = true
//                    }
//                } })
//        },
//        text = stringResource(R.string.enabled_wifi_connections)
//    )
//}
//
//@OptIn(ExperimentalWearMaterialApi::class)
//@Composable
//fun WifiSsidItemChip(
//    ssid: String,
//    onPrimaryActionClick: () -> Unit,
//    onSecondaryActionClick: () -> Unit
//) {
//    val revealState = rememberRevealState()
//    val scope = rememberCoroutineScope()
//
//    //Log.d(TAG, "WifiSsidItemChip called")
//    ReusableSwipeToRevealChip(
//        onPrimaryActionClick = { onPrimaryActionClick() },
//        onSecondaryActionClick = { onSecondaryActionClick() },
//        scope = scope,
//        revealState = revealState,
//        chipType = {
//            Chip(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(bottom = 5.dp),
//                onClick = {
//                    scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealing)}
//                },
//                colors = ChipDefaults.chipColors(Color.DarkGray),
//                label = { Text(ssid, maxLines = 3) }
//            )
//        }
//    )
//}
//
//@Composable
//fun NewWiFiSSIDSetting(
//    navController: NavController,
//) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val textField = ReusableOutlinedTextField(
//           "",
//            stringResource(R.string.wifi_ssid),
//        )
//
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "NewWiFiSSIDSetting Settings NOT saved")
//                    navController.navigate("wifi_ssid_screen") {
//                        popUpTo(("wifi_ssid_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    if (textField.text.isNotBlank()) {
//                        val mutableWifiSsidList = wifiSsidListSettingsState.value?.toMutableList() ?: emptyList<DataRepository.WifiSsidItem>().toMutableList()
//                        mutableWifiSsidList.add(DataRepository.WifiSsidItem(textField.text))
//                        wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())
//                    }
//
//                    navController.navigate("wifi_ssid_screen") {
//                        popUpTo(("wifi_ssid_screen")) {
//                            inclusive = true
//                        }
//                    }
//
//                })
//        }
//    }
//}
//
//@Composable
//fun EditedWiFiSSIDSetting(
//    navController: NavController,
//    index: Int
//) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val wifiSsidList by wifiSsidListSettingsState.observeAsState(initial = emptyList())
//        Log.d(TAG, "EditedWiFiSSIDSetting SSID list: $wifiSsidList")
//
//        Log.d(TAG, "text = ${wifiSsidList.getOrNull(index)?.name.orEmpty()}")
//        val textField = ReusableOutlinedTextField(
//            wifiSsidList.getOrNull(index)?.name.orEmpty(),
//            stringResource(R.string.wifi_ssid),
//        )
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "EditedWiFiSSIDSetting Settings NOT saved")
//                    navController.navigate("wifi_ssid_screen") {
//                        popUpTo(("wifi_ssid_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    val mutableWifiSsidList = wifiSsidList.toMutableList()
//                    mutableWifiSsidList[index] = DataRepository.WifiSsidItem(textField.text)
//                    wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())
//
//                    navController.navigate("wifi_ssid_screen") {
//                        popUpTo(("wifi_ssid_screen")) {
//                            inclusive = true
//                        }
//                    }
//
//                })
//        }
//    }
//}
//
//@Composable
//fun LoadingDialog(
//    shouldShowDialog: MutableState<Boolean>,
//    secondaryText: String
//) {
//    if (shouldShowDialog.value) {
//        Dialog(
//            onDismissRequest = { shouldShowDialog.value = false },
//            properties = DialogProperties(
//                dismissOnBackPress = false,
//                dismissOnClickOutside = false
//            )
//        ) {
//
//            Card(
//                colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
//            ) {
//                Column(
//                    modifier = Modifier
//                        .padding(16.dp),
//                    verticalArrangement = Arrangement.Center,
//                    horizontalAlignment = Alignment.CenterHorizontally,
//                ) {
//
//                    CircularProgressIndicator()
//                    Spacer(modifier = Modifier.height(10.dp))
//                    Text(
//                        text = secondaryText,
//                        textAlign = TextAlign.Center,
//                    )
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun ServerRegistrationDialog(
//    isNetworkConnected: MutableState<Boolean>,
//    shouldShowDialog: MutableState<Boolean>,
//    shouldShowLoadingDialog: MutableState<Boolean>,
//    navController: NavController,
//    isServerConnected: MutableState<Boolean>,
//    serverDetailsChanged: MutableState<Boolean>,
//    isDuplicate: MutableState<Boolean>
//) {
//    val invalidCredText = stringResource(R.string.invalid_credentials_try_again)
//    val retryText = stringResource(R.string.retry)
//    if (shouldShowDialog.value) {
//        Log.d(TAG, "ServerRegistrationDialog: shouldShowDialog=${shouldShowDialog.value}, shouldShowLoadingDialog=${shouldShowLoadingDialog.value}")
//        shouldShowLoadingDialog.value = false
//        AlertDialog(
//            modifier = Modifier.wrapContentHeight(),
//            containerColor = Color.DarkGray,
//            onDismissRequest = {
//                Log.d(TAG, "onDismissRequest")
//                shouldShowDialog.value = false
//            },
//            title = {
//                if (isDuplicate.value) {
//                    Text(stringResource(R.string.duplicate_tak_server), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
//                } else if (isServerConnected.value) {
//                    Text(stringResource(R.string.registration_complete), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
//                } else {
//                    Text(stringResource(R.string.registration_failed), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp))
//                }
//            },
//            text = {
//                if (!isDuplicate.value) {
//                    if (isNetworkConnected.value) {
//                        if (isServerConnected.value) {
//                            Text(stringResource(R.string.tak_server_registration_succeeded), textAlign = TextAlign.Center)
//                        } else {
//                            Text(invalidCredText, textAlign = TextAlign.Center, fontSize = if(invalidCredText.length!!<35) 15.sp else 13.sp)
//                        }
//                    } else {
//                        Log.d(TAG, "text=(${stringResource(R.string.network_disconnected_try_later)}), isNetworkConnected=${isNetworkConnected.value}, isServerConnected=${isServerConnected.value}")
//                        Text(stringResource(R.string.registration_failed), textAlign = TextAlign.Center)
//                    }
//                }
//            },
//            confirmButton = {
//                Log.d(TAG, "ServerRegistrationDialog confirmButton: isNetworkConnected=${isNetworkConnected.value}, isServerConnected=${isServerConnected.value}, shouldShowDialog=${shouldShowDialog.value}")
//                if (!isDuplicate.value && isNetworkConnected.value && !isServerConnected.value) {
//                    // Create retry button only if network is connected && server is NOT connected
//                    Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is shown")
//                    Button(
//                        onClick = { shouldShowDialog.value = false
//                            serverDetailsChanged.value = false
//                        },
//                        modifier = Modifier.fillMaxWidth(0.45f),
//                        contentPadding = PaddingValues(0.dp),
//                        colors = ButtonDefaults.buttonColors(
//                            containerColor = Color.LightGray,
//                        ),
//                    ) {
//                        Text(text = retryText, fontSize = if(retryText.length!!<9) 15.sp else 13.sp, color = Color.Black)
//                    }
//                } else {
//                    Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is NOT shown")
//                }
//            },
//
//            dismissButton = {
//                Button(
//                    onClick = {
//                        shouldShowDialog.value = false
//                        serverDetailsChanged.value = false
//                        isDuplicate.value = false
//                        navController.navigate("tak_server_screen") {
//                            popUpTo(("tak_server_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    },
//                    contentPadding = PaddingValues(0.dp),
//                    modifier = if (isServerConnected.value) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.45f),
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = Color.Gray,
//                    )
//                ) {
//                    if (isServerConnected.value) {
//                        Text(text = stringResource(R.string.okay))
//                    } else {
//                        Text(text = stringResource(R.string.cancel))
//                    }
//                }
//            }
//        )
//    }
//}
//
//@Composable
//fun ConfirmationDialog(
//    shouldShowDialog: MutableState<Boolean>,
//    description: String,
//    icon: ImageVector,
//    confirmColor: Color,
//    isConfirmed: MutableState<Boolean>
//) {
//    if (shouldShowDialog.value) {
//        AlertDialog(
//            containerColor = colors.background,
//            modifier = Modifier.fillMaxSize(),
//            onDismissRequest = {
//                shouldShowDialog.value = false
//            },
//            icon = {
//                Icon(
//                    icon,
//                    modifier = Modifier.size(25.dp),
//                    contentDescription = null,
//                    tint = Color.LightGray
//                )
//            },
//            text = {
//                Text(
//                    description,
//                    textAlign = TextAlign.Center,
//                    fontSize = if (description.length > 40) 13.sp else 14.sp
//                )
//            },
//            confirmButton = {
//                Button(
//                    onClick = {
//                        shouldShowDialog.value = false
//                        isConfirmed.value = true
//                    },
//                    modifier = Modifier.fillMaxWidth(0.45f),
//                    contentPadding = PaddingValues(0.dp),
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = confirmColor,
//                    ),
//                ) {
//                    Text(
//                        text = stringResource(R.string.confirm),
//                        color = Color.Black
//                    )
//                }
//            },
//
//            dismissButton = {
//                Button(
//                    onClick = {
//                        shouldShowDialog.value = false
//                        isConfirmed.value = false
//                    },
//                    contentPadding = PaddingValues(0.dp),
//                    modifier = Modifier.fillMaxWidth(0.45f),
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = Color.Gray,
//                    )
//                ) {
//                    Text(
//                        text = stringResource(R.string.dismiss),
//                    )
//                }
//            }
//        )
//    }
//}
//
//  //Used for PhysioAlerts, Reporting Rates, and some User Metrics
//  @Composable
//  fun NumericEntryPage(
//      navController: NavController,
//      metricSettingState: MutableLiveData<Int>,
//      maximum: Int,
//      minimum: Int,
//      label: String,
//      context: Context,
//      route: String
//  ) {
//      Box(
//          modifier = Modifier
//              .fillMaxSize()
//              .background(colors.background)
//              .padding(bottom = 16.dp)
//              .padding(horizontal = 16.dp),
//          contentAlignment = Alignment.Center
//      ) {
//          var metricTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(metricSettingState.value.toString())) }
//          //Log.d(TAG, "metricTextFieldValue=" + metricTextFieldValue.text)
//          val focusManager = LocalFocusManager.current
//
//          OutlinedTextField(
//              value = metricTextFieldValue,
//              onValueChange = {
//                  metricTextFieldValue = it
//              },
//              singleLine = true,
//              label = { Text(text = label, color = colors.primary) },
//              placeholder = { Text(text = label, color = colors.primary) },
//              textStyle = TextStyle(color = colors.primary),
//              keyboardOptions = KeyboardOptions(
//                  keyboardType = KeyboardType.Number,
//                  imeAction = ImeAction.Done
//              ),
//              keyboardActions = KeyboardActions(
//                  onDone = {
//                      if (metricTextFieldValue.text.isEmpty()) {
//                          Toast.makeText(
//                              mainActivity,
//                              context.getString(R.string.please_enter_a_value),
//                              Toast.LENGTH_LONG
//                          ).show()
//                      } else if (metricTextFieldValue.text.toInt() > maximum) {
//                          Toast.makeText(
//                              mainActivity,
//                              context.getString(R.string.value_exceeds_maximum_please_re_enter), Toast.LENGTH_LONG).show()
//                      } else if (metricTextFieldValue.text.toInt() < minimum){
//                          Toast.makeText(
//                              mainActivity,
//                              //TODO: WEAR-272 this doesnt recognize the new string resource for it?
//                              " Value is less than minimum, please re-enter", Toast.LENGTH_LONG).show()                    } else {
//                          focusManager.clearFocus()
//                      }
//                  }
//              ),
//
//              )
//          Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//              ConfirmCancelButtons(
//                  onClickCancel = {
//                      Log.d(TAG, "Settings NOT saved")
//                      navController.navigate(route) {
//                          popUpTo((route)) {
//                              inclusive = true
//                          }
//                      }
//                  },
//                  onClickConfirm = {
//                      val intValue = metricTextFieldValue.text.toIntOrNull()
//                      if (null != intValue) {
//                          metricSettingState.setValue(intValue)
//                          Log.d(TAG, "Settings State saved with numeric entry threshold: ${metricSettingState.value}")
//                          navController.navigate(route) {
//                              popUpTo((route)) {
//                                  inclusive = true
//                              }
//                          }
//                      } else {
//                          Toast.makeText(mainActivity, context.getString(R.string.please_enter_a_value), Toast.LENGTH_LONG).show()
//                      }
//                  })
//          }
//      }
//  }
//
//@Composable
//fun ReportingStrategySettings(navController: NavController) {
//    val enableDynamicReporting by enableDynamicReportingStrategySettingsState.observeAsState()
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableTitleChipWithState(
//                route = "reporting_dynamic_constant_screen",
//                title = "Reporting Strategy",
//                state = enableDynamicReportingStrategySettingsState,
//                navController = navController,
//                formatState = {
//                    if (enableDynamicReporting == true) {
//                        "Dynamic Reporting"
//                    } else {
//                        "Constant Reporting"
//                    }
//                }
//            )
//
//            Spacer(modifier = Modifier.height(5.dp))
//
//            if (enableDynamicReporting == true) {
//                ReusableTitleChipWithState(route = "stationary_reporting_interval_screen", title = "Stationary Reporting Interval", state = stationaryReportingIntervalSettingsState, navController = navController,)
//                ReusableTitleChipWithState(route = "on_foot_reporting_interval_screen", title = "On Foot Reporting Interval", state = onFootReportingIntervalSettingsState, navController = navController,)
//                ReusableTitleChipWithState(route = "vehicle_reporting_interval_screen", title = "Vehicle Reporting Interval", state = vehicleReportingIntervalSettingsState, navController = navController,)
//                ReusableTitleChipWithState(route = "while_alerting_reporting_interval_screen", title = "While Alerting Reporting Interval", state = whileAlertingReportingIntervalSettingsState, navController = navController,)
//
//            } else {
//                ReusableTitleChipWithState(route = "constant_reporting_interval_screen", title = "Constant Reporting Interval", state = constantReportingIntervalSettingsState, navController = navController,)
//            }
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Settings NOT saved")
//                    enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategy)
//                    constantReportingIntervalSettingsState.setValue(constantReportingInterval)
//                    stationaryReportingIntervalSettingsState.setValue(stationaryReportingInterval)
//                    onFootReportingIntervalSettingsState.setValue(onFootReportingInterval)
//                    vehicleReportingIntervalSettingsState.setValue(vehicleReportingInterval)
//                    whileAlertingReportingIntervalSettingsState.setValue(whileAlertingReportingInterval)
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    constantReportingIntervalState.setValue(constantReportingIntervalSettingsState.value)
//                    stationaryReportingIntervalState.setValue(stationaryReportingIntervalSettingsState.value)
//                    onFootReportingIntervalState.setValue(onFootReportingIntervalSettingsState.value)
//                    vehicleReportingIntervalState.setValue(vehicleReportingIntervalSettingsState.value)
//                    whileAlertingReportingIntervalState.setValue(whileAlertingReportingIntervalSettingsState.value)
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = "Reporting Settings"
//    )
//}
//
//@Composable
//fun ReportingStrategyDynamicConstantSetting (
//    navController: NavController,
//) {
//    val enableDynamicReporting = enableDynamicReportingStrategySettingsState.value ?: true
//    val radioOptions = listOf("Constant Reporting", "Dynamic Reporting")
//    val initialValue = when {
//        !enableDynamicReporting -> radioOptions[0]  // "Constant"
//        else  -> radioOptions[1] // "Dynamic"
//    }
//    val selectedValue = remember { mutableStateOf(initialValue) }
//    val isSelectedItem: (String) -> Boolean = { selectedValue.value == it }
//    val onChangeState: (String) -> Unit = { selectedValue.value = it }
//    val scrollState = rememberScrollState()
//    val scope = rememberCoroutineScope()
//
//    //Log.d(TAG, "OptimizeBatteryonWifiSelectScreen called")
//    WearTakCivScreenDimensions(
//        columnContent = {
//            Log.d(TAG, "initial  value = $initialValue, selected value: ${selectedValue.value}")
//            radioOptions.forEach { text ->
//                ReusableSelectableChip(
//                    selected = isSelectedItem(text),
//                    onClick = { onChangeState(text)
//                        if (text == "Constant Reporting") {
//                            enableDynamicReportingStrategySettingsState.postValue(false)
//                        } else {
//                            enableDynamicReportingStrategySettingsState.postValue(true)
//                        }
//                    },
//                    title = text,
//                    list = radioOptions
//                )
//
//            }
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Reporting Strategy Settings NOT saved")
//                    enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategyState.value)
//                    navController.navigate("reporting_settings_screen") {
//                        popUpTo(("reporting_settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    val prevSelection: Boolean = enableDynamicReportingStrategyState.value ?: true
//                    val currSelection = enableDynamicReportingStrategySettingsState.value ?: true
//
//                    if ((prevSelection != currSelection)) {
//                        Log.d(TAG, "Changed Reporting Strategy: $currSelection")
//                        enableDynamicReportingStrategyState.postValue(currSelection)
//                    } else {
//                        Log.d(TAG, "Settings NOT changed, NOT saved")
//                    }
//
//                    navController.navigate("reporting_settings_screen") {
//                        popUpTo(("reporting_settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//
//                })
//        },
//        text = "Reporting Strategy"
//    )
//}
//
////TODO: Can combine with physio alerts settings
//@Composable
//fun ReportingStrategyIntervalSettings(
//    navController: NavController,
//    intervalSettingState: MutableLiveData<Int>,
//    label: String,
//    placeholder: String
//) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        var intervalSettingTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
//            mutableStateOf(
//                TextFieldValue(
//                    intervalSettingState.value.toString() ?: "null"
//                )
//            )
//        }
//
//        OutlinedTextField(
//            value = intervalSettingTextFieldValue,
//            onValueChange = {
//                intervalSettingTextFieldValue = it
//            },
//            keyboardOptions = KeyboardOptions(
//                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
//            singleLine = true,
//            label = { Text(text = label, color = colors.primary, fontSize =  11.sp) },
//            placeholder = { Text(text = placeholder, color = colors.primary) },
//            textStyle = TextStyle(color = colors.primary)
//        )
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Settings NOT saved")
//                    navController.navigate("reporting_settings_screen") {
//                        popUpTo(("reporting_settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    val intValue = intervalSettingTextFieldValue.text.toIntOrNull()
//                    if (null != intValue) {
//                        intervalSettingState.postValue(intValue)
//                        Log.d(TAG, "Settings saved with reporting interval: ${intervalSettingState.value}")
//                        navController.navigate("reporting_settings_screen") {
//                            popUpTo(("reporting_settings_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    } else {
//                        Toast.makeText(mainActivity, mainActivity.getString(R.string.please_enter_a_value), Toast.LENGTH_LONG).show()
//                    }
//                })
//        }
//    }
//}
//
//@Composable
//fun enableDisableFromBoolean(boolean: Boolean?): String {
//    val enableDisableString: String = when (boolean) {
//        false -> stringResource(R.string.disabled)
//        true -> stringResource(R.string.enabled)
//        else -> stringResource(R.string.error)
//    }
//    return enableDisableString
//}
//
//@Composable
//fun bloodhoundIntensityText(intensity: String?): String {
//    return when (intensity) {
//        BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED -> stringResource(R.string.bloodhound_proximity_intensity_escalated)
//        BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT -> stringResource(R.string.bloodhound_proximity_intensity_persistent)
//        else -> stringResource(R.string.bloodhound_proximity_intensity_basic)
//    }
//}
//
//@Composable
//fun AtmPressureAlertSettings(navController: NavController) {
//    Log.i(TAG, "AtmPressureAlertSettings called")
//    val lowAtmPressureAlertCheckedLive by enableLowAtmPressureAlertSettingsState.observeAsState()
//    val highAtmPressureAlertCheckedLive by enableHighAtmPressureAlertSettingsState.observeAsState()
//
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableToggleChip(
//                lowAtmPressureAlertCheckedLive,
//                { enableLowAtmPressureAlertSettingsState.postValue(it) },
//                title = stringResource(R.string.low_atm_pressure_alert),
//                description = stringResource(R.string.on_off)
//
//            )
//            DataMetricsChip("low_atm_pressure_threshold_screen", stringResource(R.string.low_atm_pressure_threshold), lowAtmPressureAlertThresholdSettingsState.value.toString(), navController)
//            Spacer(modifier = Modifier.height(16.dp))
//            ReusableToggleChip(
//                highAtmPressureAlertCheckedLive,
//                { enableHighAtmPressureAlertSettingsState.postValue(it) },
//                title = stringResource(R.string.high_atm_pressure_alert),
//                description = stringResource(R.string.on_off)
//
//            )
//            DataMetricsChip("high_atm_pressure_threshold_screen", stringResource(R.string.high_atm_pressure_threshold), highAtmPressureAlertThresholdSettingsState.value.toString(), navController)
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.i(TAG, "Atm Pressure Alert Settings NOT saved")
//                    enableLowAtmPressureAlertSettingsState.setValue(enableLowAtmPressureAlert)
//                    lowAtmPressureAlertThresholdSettingsState.setValue(lowAtmPressureAlertThreshold)
//                    enableHighAtmPressureAlertSettingsState.setValue(enableHighAtmPressureAlert)
//                    highAtmPressureAlertThresholdSettingsState.setValue(highAtmPressureAlertThreshold)
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    Log.d(
//                        TAG,
//                        "Saving Atm Pressure Settings: " +
//                                "low=${enableLowAtmPressureAlertSettingsState.value}, " +
//                                "high=${enableHighAtmPressureAlertSettingsState.value}, " +
//                                "lowThreshold=${lowAtmPressureAlertThresholdSettingsState.value}, " +
//                                "highThreshold=${highAtmPressureAlertThresholdSettingsState.value}"
//                    )
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.atm_pressure_alerts)
//    )
//}
//
//@Composable
//fun BloodhoundSettings(navController: NavController) {
//    val bloodhoundProximityVibrationCheckedLive by enableBloodhoundProximityVibrationSettingsState.observeAsState()
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableToggleChip(
//                bloodhoundProximityVibrationCheckedLive,
//                { enableBloodhoundProximityVibrationSettingsState.postValue(it) },
//                title = stringResource(R.string.bloodhound_proximity_vibration),
//                description = stringResource(R.string.on_off)
//
//            )
//            DataMetricsChip(
//                "bloodhound_proximity_radius_screen",
//                stringResource(R.string.bloodhound_proximity_radius),
//                "${bloodhoundProximityRadiusMetersSettingsState.value} m",
//                navController
//            )
//            if (FEATURE_ENABLE_BLOODHOUND_PROXIMITY_INTENSITY_SELECTION) {
//                DataMetricsChip(
//                    "bloodhound_proximity_intensity_screen",
//                    stringResource(R.string.bloodhound_proximity_intensity),
//                    bloodhoundIntensityText(bloodhoundProximityIntensitySettingsState.value),
//                    navController
//                )
//            }
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    postBloodhoundSettingsValues(currentBloodhoundSettingsValues())
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                }
//            )
//        },
//        text = stringResource(R.string.bloodhound_settings)
//    )
//}
//
//@Composable
//fun BloodhoundIntensitySetting(navController: NavController) {
//    val options = remember { SettingsActivity.bloodhoundIntensityOptions }
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(top = 20.dp, bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val index = findRadioIndex(bloodhoundProximityIntensitySettingsState.value, options)
//        var selectedRadioIndex by remember { mutableStateOf(index) }
//
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(horizontal = 5.dp)
//                .padding(bottom = 40.dp)
//                .verticalScroll(state = rememberScrollState()),
//            verticalArrangement = Arrangement.Center,
//            horizontalAlignment = Alignment.CenterHorizontally,
//        ) {
//            Text(
//                text = stringResource(R.string.bloodhound_proximity_intensity),
//                fontSize = 13.sp,
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier
//                    .padding(vertical = 10.dp, horizontal = 20.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
//
//            options.forEachIndexed { index, option ->
//                SelectableChip(
//                    modifier = Modifier.fillMaxWidth(),
//                    selected = index == selectedRadioIndex,
//                    onClick = { selectedRadioIndex = index },
//                    label = {
//                        Text(
//                            bloodhoundIntensityText(option),
//                            maxLines = 3,
//                            overflow = TextOverflow.Ellipsis
//                        )
//                    },
//                    secondaryLabel = {
//                        Text(
//                            when (option) {
//                                BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED -> stringResource(R.string.bloodhound_proximity_intensity_escalated_desc)
//                                BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT -> stringResource(R.string.bloodhound_proximity_intensity_persistent_desc)
//                                else -> stringResource(R.string.bloodhound_proximity_intensity_basic_desc)
//                            },
//                            maxLines = 2,
//                            overflow = TextOverflow.Ellipsis
//                        )
//                    },
//                    enabled = true,
//                    shape =
//                        if (option == options.first()) {
//                            RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
//                        } else if (option == options.last()) {
//                            RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
//                        } else {
//                            RoundedCornerShape(0.dp)
//                        }
//                )
//            }
//            Spacer(modifier = Modifier.height(15.dp))
//        }
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    navController.navigate("bloodhound_settings_screen") {
//                        popUpTo(("bloodhound_settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    bloodhoundProximityIntensitySettingsState.setValue(options[selectedRadioIndex])
//                    navController.navigate("bloodhound_settings_screen") {
//                        popUpTo(("bloodhound_settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                }
//            )
//        }
//    }
//}
//
//@Composable
//fun ManageOutputsSetting(navController: NavController) {
//    Log.d(TAG, "ManageOutputsSetting called")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableTitleChipWithState(route = "tak_sa_multicast_screen", title = stringResource(R.string.tak_sa_multicast), state = enableMulticastSettingsState, navController = navController, formatState = {enableDisableFromBoolean(enableMulticastSettingsState.value)})
////            ReusableTitleChipWithState(route = "sitx_tak_screen", title = stringResource(R.string.sitx_tak), state = enableSitxServiceSettingsState, navController = navController, formatState = {enableDisableFromBoolean(enableSitxServiceSettingsState.value)})
//            ReusableTitleChipWithState(route = "tablet_command_screen", title = stringResource(R.string.tablet_command), state = enableTabletCommandEndpointSettingsState, navController = navController, formatState = {enableDisableFromBoolean(enableTabletCommandEndpointSettingsState.value)})
//            ReusableTitleChipWithState(route = "esri_screen", title = stringResource(R.string.esri), state = enableEsriEndpointSettingsState, navController = navController, formatState = {enableDisableFromBoolean(enableEsriEndpointSettingsState.value)})
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Manage Outputs Settings NOT saved")
//                    enableMulticastSettingsState.setValue(enableMulticastOnWifi)
//                    enableTabletCommandEndpointSettingsState.setValue(enableTabletCommandEndpoint)
//                    enableEsriEndpointSettingsState.setValue(enableEsriEndpoint)
//                    enableSitxServiceSettingsState.setValue(enableSitxService)
//
//                    multicastAddressSettingsState.setValue(multicastAddress)
//                    multicastProtocolSettingsState.setValue(multicastProtocol)
//                    multicastPortSettingsState.setValue(multicastPort)
//                    tabletCommandEndpointSettingsState.setValue(tabletCommandEndpoint)
//                    tabletCommandApikeySettingsState.setValue(tabletCommandApikey)
//                    esriEndpointSettingsState.setValue(esriEndpoint)
//                    sitxTakUrlSettingsState.setValue(sitxTakUrl)
//                    //sitxTakClientIdSettingsState.setValue(sitxTakClientId)
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.manage_outputs)
//    )
//}
//
//@Composable
//fun ManageOutputsMulticastSetting(navController: NavController) {
//    Log.d(TAG, "ManageOutputsMulticastSetting called")
//    val multicastCheckedLive by enableMulticastSettingsState.observeAsState()
////    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableToggleChip(
//                multicastCheckedLive,
//                { enableMulticastSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.tak_sa_multicast),
//                stringResource(R.string.on_off)
//            )
//            ReusableTitleChipWithState(route = "tak_sa_multicast_address_screen", title = stringResource(R.string.address), state = multicastAddressSettingsState, navController = navController)
//            ReusableTitleChipWithState(route = "tak_sa_multicast_output_protocol_screen", title = stringResource(R.string.output_protocol), state = multicastProtocolSettingsState, navController = navController)
//            ReusableTitleChipWithState(route = "tak_sa_multicast_server_port_screen", title = stringResource(R.string.server_port), state = multicastPortSettingsState, navController = navController)
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Multicast Settings NOT saved")
//                    enableMulticastSettingsState.setValue(enableMulticastOnWifi)
//                    multicastAddressSettingsState.setValue(multicastAddress)
//                    multicastProtocolSettingsState.setValue(multicastProtocol)
//                    multicastPortSettingsState.setValue(multicastPort)
//
//                    navController.navigate("manage_outputs_screen") {
//                        popUpTo(("manage_outputs_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//
//                    navController.navigate("manage_outputs_screen") {
//                        popUpTo(("manage_outputs_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.tak_sa_multicast)
//    )
//}
//
//@Composable
//fun SitxStatusAuthorizationScreen(navController: NavController, sitxVm: SitxViewModel) {
//    val sitxEvent by sitxVm.lastEvent.collectAsStateWithLifecycle(initialValue=null)
//    var title = ""
//    var secondaryText = ""
//    var userCode = ""
//
//    when (val e = sitxEvent) {
//        is SitxServiceEvent.NeedsUserAction -> {
//            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Needs User Action: (${e.userCode}) and (${e.verificationUriComplete})")
//            title = "Authorize Device"
//            userCode = e.userCode
//            secondaryText = "Authorize device at: ${e.verificationUriComplete} and then press Authorize below"
//        }
//        is SitxServiceEvent.Pending -> {
//            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Pending:(${e.userCode}) and (${e.verificationUriComplete}) with (${e.message})")
//            title = "Pending Auth"
//            userCode = e.userCode
//            secondaryText = "Device authorization pending: ${e.message}"
//        }
//        is SitxServiceEvent.Error -> {
//            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Error: ${e.message}")
//            title = "Error"
//            secondaryText = "Error: ${e.message}"
//        }
//        is SitxServiceEvent.Info -> {
//            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Info: ${e.message}")
//            title = "Info"
//            secondaryText = "Info: ${e.message}"
//        }
//        is SitxServiceEvent.Authorized -> {
//            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Authorized: ${e.message}")
//            title = "Device Authorized"
//            secondaryText = "Device Authorized: ${e.message}"
//        }
//        is SitxServiceEvent.SocketsConnected -> {
//            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) SocketsConnected(${e.count}): ${e.message}")
//            title = "Sit(x) Connected"
//            secondaryText = "Connected to Sit(x): ${e.message}"
//        }
//        is SitxServiceEvent.SocketStatus -> {
//            title = "Socket Status"
//            Log.d(TAG, "SitxStatusAuthorizationScreen Sit(x) SocketStatus(flowTag=${e.flowTag}, status=${e.status}): ${e.message}")
//            secondaryText = "Connected to Sit(x) w/ ${e.status}: ${e.message}"
//        }
//        is SitxServiceEvent.Disconnected -> {
//            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Disconnected")
//            title = "Sit(x) Disconnected"
//            secondaryText = "Disconnected from Sit(x)"
//        }
//        else -> Unit
//    }
//
//    WearTAKCIVTheme {
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(colors.background)
//                .padding(10.dp),
//            horizontalAlignment = Alignment.CenterHorizontally,
//            verticalArrangement = Arrangement.Center
//        ) {
//            Text(
//                text = title,
//                fontSize = 15.sp,
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier
//                    .padding(vertical = 10.dp, horizontal = 20.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
//            if (sitxEvent is SitxServiceEvent.NeedsUserAction) {
//                Text(
//                    text = userCode,
//                    textAlign = TextAlign.Center,
//                    fontWeight = FontWeight.SemiBold,
//                    fontSize = 25.sp,
//                    modifier = Modifier
//                        .padding(vertical = 7.dp)
//                        .align(Alignment.CenterHorizontally)
//                )
//            }
//            Text(
//                text = secondaryText,
//                textAlign = TextAlign.Center,
//                fontSize = 12.sp,
//                lineHeight = 14.sp,
//            )
//            Spacer(modifier = Modifier.size(5.dp))
//            Row(
//                modifier = Modifier
//                    .padding(bottom = 10.dp)
//                    .fillMaxWidth(),
//                horizontalArrangement = Arrangement.Center) {
//                Button(
//                    onClick = {
//                        navController.navigate("sitx_tak_screen") {
//                            popUpTo("sitx_tak_screen") { inclusive = true }
//                        }
//                    },
//                    contentPadding = PaddingValues(10.dp),
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = Color.Gray,
//                    )
//                ) {
//                    Text(
//                        text = stringResource(R.string.dismiss),
//                    )
//                }
//
//                if (sitxEvent is SitxServiceEvent.NeedsUserAction) {
//                    Spacer(modifier = Modifier.size(10.dp))
//
//                    Button(
//                        onClick = {
//                            SitxServiceCommandBus.trySend(SitxServiceCommand.PollDeviceToken)
//                            //TODO: Might be a better method of this but it works for now
//                            sitxTakGroupSettingsState.postValue(null)
//                            navController.navigate("sitx_tak_group_screen") {
//                                popUpTo(("sitx_tak_group_screen")) {
//                                    inclusive = true
//                                }
//                            }
//                        },
//                        contentPadding = PaddingValues(10.dp),
//                        colors = ButtonDefaults.buttonColors(colors.primary),
//                    ) {
//                        Text(
//                            text = "Authorize",
//                            color = Color.Black
//                        )
//                    }
//                } else if (sitxEvent is SitxServiceEvent.Error) {
//                    Spacer(modifier = Modifier.size(10.dp))
//
//                    Button(
//                        onClick = {
//                            runBlocking {
//                                SitxServiceCommandBus.trySend(SitxServiceCommand.BeginAuth(
//                                    baseUrl = sitxRepo.baseUrl.first(),
//                                    clientId = sitxRepo.clientId.first() ?: "",
//                                    callsign = callsign,
//                                    deviceName = device_uid,
//                                    deviceId = device_uid
//                                )
//                                )
//                            }
//                        },
//                        contentPadding = PaddingValues(10.dp),
//                        colors = ButtonDefaults.buttonColors(colors.primary),
//                    ) {
//                        Text(
//                            text = "Re-Auth",
//                            color = Color.Black
//                        )
//                    }
//                }
//            }
//        }
//        // Render the dialog ONCE, using the remembered state (no inline mutableStateOf(true))
//        //LoadingDialog(shouldShowDialog = showLoadingDialog, secondaryText = "Checking License Status")
//    }
//}
//
//@Composable
//fun sitxStatusText(sitxEvent: SitxServiceEvent?): String {
//    return when (sitxEvent) {
//        is SitxServiceEvent.NeedsUserAction -> "Needs User Action"
//        is SitxServiceEvent.Pending -> "Pending Authorization"
//        is SitxServiceEvent.Authorized -> "Device Authorized"
//        is SitxServiceEvent.SocketStatus -> "Connected"
//        is SitxServiceEvent.SocketsConnected -> "Connected"
//        is SitxServiceEvent.Info -> "Info"
//        is SitxServiceEvent.Error -> "Error"
//        is SitxServiceEvent.Disconnected -> "Disconnected"
//        else -> "Unknown"
//    }
//}
//
//@Composable
//fun SitxGroupSetting (
//    navController: NavController,
//) {
//    val groupChanged = remember { mutableStateOf(false) }
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(top = 20.dp, bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val dataList by dataRepository
//            .sitxGroups
//            .collectAsStateWithLifecycle(initialValue = emptyList())
//        val index = findSitxGroupIndex(sitxTakGroupSettingsState.value, dataList)
//        var selectedRadioIndex by remember(index) { mutableStateOf(index) }
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(horizontal = 5.dp)
//                .padding(bottom = 40.dp)
//                .verticalScroll(state = rememberScrollState()),
//            verticalArrangement = Arrangement.Center,
//            horizontalAlignment = Alignment.CenterHorizontally,
//        ) {
//            Text(
//                text = "Sit(x) Group",
//                fontSize = if ("Sit(x) Group".length < 20) 15.sp else 13.sp,
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier
//                    .padding(vertical = 10.dp, horizontal = 20.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
//            if (dataList.isNullOrEmpty()) {
//                CircularProgressIndicator()
//                Text(
//                    text = "Loading Sit(x) Groups",
//                    fontSize = 15.sp,
//                    textAlign = TextAlign.Center,
//                    modifier = Modifier
//                        .padding(vertical = 10.dp, horizontal = 20.dp)
//                        .align(Alignment.CenterHorizontally)
//                )
//            }
//                dataList.forEachIndexed { index, dataItem ->
//                run {
//                    SelectableChip(
//                        modifier = Modifier.fillMaxWidth(),
//                        selected = index == selectedRadioIndex,
//                        onClick = { selectedRadioIndex = index},
//                        label = {
//                            // The primary label should have a maximum 3 lines of text
//                            Text(dataItem.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
//                        },
//                        enabled = true,
//                        shape =
//                            if (dataItem == dataList.first())
//                                RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
//                            else if (dataItem == dataList.last())
//                                RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
//                            else
//                                RoundedCornerShape(0.dp)
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(15.dp))
//        }
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            if (dataList.isNullOrEmpty()){
//                BackButton { Log.d(TAG, "RadioChoiceSetting Settings NOT saved: Sit(x) Group")
//                    groupChanged.value = false
//                    navController.navigate("sitx_tak_screen") {
//                        popUpTo(("sitx_tak_screen")) {
//                            inclusive = true
//                        }
//                    } }
//            }else {
//                ConfirmCancelButtons(
//                    onClickCancel = {
//                        Log.d(TAG, "RadioChoiceSetting Settings NOT saved: Sit(x) Group")
//                        groupChanged.value = false
//                        navController.navigate("sitx_tak_screen") {
//                            popUpTo(("sitx_tak_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    },
//                    onClickConfirm = {
//                        if (selectedRadioIndex != -1) {
//                            if (sitxTakGroupSettingsState.value?.flowTag != dataList[selectedRadioIndex].flowTag) {
//                                groupChanged.value = true
//                                runBlocking {
//                                    SitxServiceCommandBus.trySend(
//                                        SitxServiceCommand.SwitchGroup(dataList[selectedRadioIndex].flowTag)
//                                    )
//                                }
//                                Log.d(
//                                    TAG,
//                                    "RadioChoiceSetting Settings saved (Sit(x) Group): ${sitxTakGroupSettingsState.value}"
//                                )
//                            } else {
//                                navController.navigate("sitx_tak_screen") {
//                                    popUpTo(("sitx_tak_screen")) {
//                                        inclusive = true
//                                    }
//                                }
//                            }
//                        } else {
//                            Toast.makeText(mainActivity, "Please select a group", Toast.LENGTH_LONG).show()
//                        }
//                    })
//            }
//            Log.d(TAG, "groupChanged: ${groupChanged.value}")
//            if (groupChanged.value) {
//                val loadingDialog by dataRepository
//                    .sitxGroupStatus
//                    .collectAsStateWithLifecycle(initialValue = null)
//                Log.d(TAG, "loadingDialog: $loadingDialog")
//                val shouldShowLoadingDialog = remember { mutableStateOf(false) }
//                val shouldShowConfirmDialog = remember { mutableStateOf(false) }
//                val isGroupConnected = remember { mutableStateOf(false) }
//
//                displaySitxGroupDialog(loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isGroupConnected)
//                LoadingDialog(shouldShowLoadingDialog, "Selecting Group")
//                SitxGroupRegistrationDialog( shouldShowConfirmDialog,shouldShowLoadingDialog, navController, isGroupConnected, groupChanged)
//                if (loadingDialog == DataRepository.SitxGroupStatus.CONNECTED){
//                    sitxTakGroupSettingsState.setValue(dataList[selectedRadioIndex])
////                    selectedSitxGroupTitle = dataList[selectedRadioIndex].name
//                }
//            }
//        }
//    }
//}
//
//fun findSitxGroupIndex(dataName: SitxTakService.TakServerItem?, dataList: List<SitxTakService.TakServerItem>) : Int {
//    if (null == dataName) {
//        return -1
//    }
//    val index = dataList.indexOf(dataName)
//
//    return index
//}
//
//@Composable
//fun SitxGroupRegistrationDialog(
//    shouldShowDialog: MutableState<Boolean>,
//    shouldShowLoadingDialog: MutableState<Boolean>,
//    navController: NavController,
//    isGroupConnected: MutableState<Boolean>,
//    groupChanged: MutableState<Boolean>
//) {
//    val retryText = stringResource(R.string.retry)
//    if (shouldShowDialog.value) {
//        shouldShowLoadingDialog.value = false
//        AlertDialog(
//            modifier = Modifier.wrapContentHeight(),
//            containerColor = Color.DarkGray,
//            onDismissRequest = {
//                Log.d(TAG, "onDismissRequest")
//                shouldShowDialog.value = false
//            },
//            title = {
//                if (isGroupConnected.value) {
//                    Text("Group Connected", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
//                } else {
//                    Text("Group Connection Failed", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp))
//                }
//            },
//            confirmButton = {
//                if (!isGroupConnected.value) {
//                    Button(
//                        onClick = { shouldShowDialog.value = false
//                            groupChanged.value = false
//                        },
//                        modifier = Modifier.fillMaxWidth(0.45f),
//                        contentPadding = PaddingValues(0.dp),
//                        colors = ButtonDefaults.buttonColors(
//                            containerColor = Color.LightGray,
//                        ),
//                    ) {
//                        Text(text = retryText, fontSize = if(retryText.length!!<9) 15.sp else 13.sp, color = Color.Black)
//                    }
//                } else {
//                    Log.d(TAG, "SitxGroupRegistrationDialog confirmButton: RetryButton is NOT shown")
//                }
//            },
//
//            dismissButton = {
//                Button(
//                    onClick = {
//                        shouldShowDialog.value = false
//                        groupChanged.value = false
//                        navController.navigate("sitx_tak_screen") {
//                            popUpTo(("sitx_tak_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    },
//                    contentPadding = PaddingValues(0.dp),
//                    modifier = if (isGroupConnected.value) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.45f),
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = Color.Gray,
//                    )
//                ) {
//                    if (isGroupConnected.value) {
//                        Text(text = stringResource(R.string.okay))
//                    } else {
//                        Text(text = stringResource(R.string.cancel))
//                    }
//                }
//            }
//        )
//    }
//}
//
////TODO: WEAR-515-UI: Fix wonky behavior
//fun displaySitxGroupDialog(
//    loadingDialog: DataRepository.SitxGroupStatus?,
//    shouldShowLoadingDialog: MutableState<Boolean>,
//    shouldShowConfirmDialog: MutableState<Boolean>,
//    isGroupConnected: MutableState<Boolean>
//) {
//    if (loadingDialog == DataRepository.SitxGroupStatus.CONNECTING) {
//            Log.d(TAG, "displaySitxGroupDialog: CONNECTING")
//            shouldShowLoadingDialog.value = true
//            shouldShowConfirmDialog.value = false
//        } else if (loadingDialog == DataRepository.SitxGroupStatus.CONNECTED) {
//            Log.d(TAG, "displaySitxGroupDialog: CONNECTED")
//            shouldShowLoadingDialog.value = false
//            shouldShowConfirmDialog.value = true
//            isGroupConnected.value = true
//        } else if ((loadingDialog == DataRepository.SitxGroupStatus.FAILED) ||
//            (loadingDialog == DataRepository.SitxGroupStatus.DISCONNECTED)
//        ) {
//            Log.d(TAG, "displaySitxGroupDialog: $loadingDialog")
//            shouldShowLoadingDialog.value = false
//            shouldShowConfirmDialog.value = true
//            isGroupConnected.value = false
//        }
//        Log.d(TAG, "displaySitxGroupDialog: $loadingDialog")
//}
//
//@Composable
//fun SitxUrlEntryPage(
//    navController: NavController,
//) {
//    val label = "Address/URL"
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val textField = GenericOutlinedTextField(sitxTakUrlSettingsState, label, label)
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "$label Settings NOT saved")
//                    navController.navigate("sitx_tak_screen") {
//                        popUpTo(("sitx_tak_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    if (textField.text.contains(".sitx.io")) {
//                        val replaceText = textField.text.replace(".sitx.io", "")
//                        sitxTakUrlSettingsState.setValue(replaceText)
//                    } else {
//                        sitxTakUrlSettingsState.setValue(textField.text)
//                    }
//                    Log.d(TAG, "Settings saved with $label: ${sitxTakUrlSettingsState.value}")
//                    navController.navigate("sitx_tak_screen") {
//                        popUpTo(("sitx_tak_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        }
//    }
//}
//
//@Composable
//fun ManageOutputsSitxSetting(navController: NavController, sitxVm: SitxViewModel) {
//    Log.d(TAG, "ManageOutputsSitxSetting called")
//    val sitxServiceCheckedLive by enableSitxServiceSettingsState.observeAsState()
//    val sitxEvent by sitxVm.lastEvent.collectAsStateWithLifecycle(initialValue=null)
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableToggleChip(
//                sitxServiceCheckedLive,
//                { enableSitxServiceSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.sitx_tak),
//                stringResource(R.string.on_off)
//            )
//            ReusableTitleChipWithState(route = "sitx_tak_url_screen", title = stringResource(R.string.address), state = sitxTakUrlSettingsState, navController = navController)
////            ReusableTitleChipWithState(route = "sitx_tak_client_id_screen", title = stringResource(R.string.client_id), state = sitxTakClientIdSettingsState, navController = navController)
//
//            DataMetricsChip("sitx_tak_url_screen", stringResource(R.string.address), sitxTakUrlSettingsState.value.toString() + ".sitx.io", navController)
//
//            if (sitxStatusText(sitxEvent) == "Connected") {
//                DataMetricsChip(
//                    "sitx_tak_group_screen", stringResource(R.string.group),
//                    sitxTakGroupSettingsState.value?.name ?: "No Group Selected", navController
//                )
//            }
//            //DataMetricsChip("sitx_tak_client_id_screen", stringResource(R.string.client_id), sitxTakClientIdSettingsState.value.toString(), navController)
//            if (true == enableSitxServiceSettingsState.value) {
//                val nameSecondary = if (enableSitxService) sitxStatusText(sitxEvent) else "Service not enabled"
//                ReusableTitleChipWithState(route = "sitx_status_authorization_screen", title = "Sit(x) State", state = enableSitxServiceSettingsState, navController = navController, formatState = {nameSecondary})
//                Button(
//                    onClick = {
//                        runBlocking {
//                            SitxServiceCommandBus.trySend(SitxServiceCommand.BeginAuth(
//                                baseUrl = sitxRepo.baseUrl.first(),
//                                clientId = sitxRepo.clientId.first() ?: "",
//                                callsign = callsign,
//                                deviceName = device_uid,
//                                deviceId = device_uid
//                            )
//                            )
//                        }
//                    },
//                    contentPadding = PaddingValues(10.dp),
//                    colors = ButtonDefaults.buttonColors(colors.primary),
//                ) {
//                    Text(
//                        text = "Re-Auth",
//                        color = Color.Black
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Sit(x) TAK Settings NOT saved")
//                    enableSitxServiceSettingsState.setValue(enableSitxService)
//                    sitxTakUrlSettingsState.setValue(sitxTakUrl)
//                    //sitxTakClientIdSettingsState.setValue(sitxTakClientId)
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.sitx_tak)
//    )
//}
//
//@Composable
//fun ManageOutputsTabletCommandSetting(navController: NavController) {
//    Log.d(TAG, "ManageOutputsTabletCommandSetting called")
//    val enableTabletCommandCheckedLive by enableTabletCommandEndpointSettingsState.observeAsState()
////    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableToggleChip(
//                enableTabletCommandCheckedLive,
//                { enableTabletCommandEndpointSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.tablet_command),
//                stringResource(R.string.on_off)
//            )
//            ReusableTitleChipWithState(route = "tablet_command_endpoint_screen", title = stringResource(R.string.endpoint), state = tabletCommandEndpointSettingsState, navController = navController)
//            ReusableTitleChipWithState(route = "tablet_command_apikey_screen", title = stringResource(R.string.api_key), state = tabletCommandApikeySettingsState, navController = navController)
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Tablet Command Settings NOT saved")
//                    enableTabletCommandEndpointSettingsState.setValue(enableTabletCommandEndpoint)
//                    tabletCommandEndpointSettingsState.setValue(tabletCommandEndpoint)
//                    tabletCommandApikeySettingsState.setValue(tabletCommandApikey)
//
//                    navController.navigate("manage_outputs_screen") {
//                        popUpTo(("manage_outputs_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//
//                    navController.navigate("manage_outputs_screen") {
//                        popUpTo(("manage_outputs_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.tablet_command)
//    )
//}
//
//@Composable
//fun ManageOutputsEsriSetting(navController: NavController) {
//    Log.d(TAG, "ManageOutputsEsriSetting called")
//    val enableEsriCheckedLive by enableEsriEndpointSettingsState.observeAsState()
////    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableToggleChip(
//                enableEsriCheckedLive,
//                { enableEsriEndpointSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.esri),
//                stringResource(R.string.on_off)
//            )
//            ReusableTitleChipWithState(route = "esri_endpoint_screen", title = stringResource(R.string.endpoint), state = esriEndpointSettingsState, navController = navController)
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Esri Settings NOT saved")
//                    enableEsriEndpointSettingsState.setValue(enableEsriEndpoint)
//                    esriEndpointSettingsState.setValue(esriEndpoint)
//
//                    navController.navigate("manage_outputs_screen") {
//                        popUpTo(("manage_outputs_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//
//                    navController.navigate("manage_outputs_screen") {
//                        popUpTo(("manage_outputs_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.esri)
//    )
//}
//
//@Composable
//fun LicenseKeyScreen(navController: NavController, licenseLiveDataState: MutableLiveData<String>, context: Context) {
//    var offsetX by remember { mutableStateOf(0f) }
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val textField = ReusableOutlinedTextField(licenseLiveDataState.value ?: "null",stringResource(R.string.license_key))
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            var settingsChanged: Boolean = false
//            var licenseChanged: Boolean = false
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "LicenseKeyScreen Settings NOT saved")
//                    settingsChanged = false; licenseChanged = false
//                    if (isLicenseValid()) {
//                        navController.navigate("settings_screen") {
//                            popUpTo(("settings_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    } else {
//                        navController.navigate("invalid_license_screen")
//                    }
//                },
//                onClickConfirm = {
//                    val enteredLicense = textField.text
//                    val currentLicense = licenseSettingsState.value
//                    Log.d(
//                        TAG, """
//                            === === Licensing Information === ===
//                            License Entered: $enteredLicense
//                            License Current: $currentLicense
//                            """.trimIndent()
//                    )
//
//                    if (enteredLicense != currentLicense) {
//                        settingsChanged = true; licenseChanged = true
//                        license = textField.text ?: license
//                        Log.d(TAG, "License Changed. Updating DataRepository.")
//                        CoroutineScope(Dispatchers.Main).launch {
//                            licenseSettingsState.postValue(license)
//                            licenseLiveDataState.postValue(license)
//                            dataRepository.storeLicense(license)
//                        }
//                        requestCallsignComplicationUpdate(context)
//                        requestSosComplicationUpdate(context)
//                        requestStatusIconsComplicationUpdate(context)
//                        requestChatComplicationUpdate(context)
//                        requestBloodhoundComplicationUpdate(context)
//                        requestPhysioStatusAndAlertingComplicationUpdate(context)
//
//                        Log.d(TAG, "Sending License for Validation.")
//                    } else {
//                        Log.d(TAG, "License Did not Change.")
//                    }
//                    validateLicense(license)
//
//                    if (isLicenseValid()) {
//                        Log.d(
//                            TAG, """
//                        License is valid. Navigating to Settings Screen & Starting Application Services.
//                        =====================================
//                        """.trimIndent()
//                        )
//                        navController.navigate("settings_screen") {
//                            popUpTo(("invalid_license_screen")) {
//                                inclusive = true
//                            }
//                        }
//                    } else {
//                        Log.d(
//                            TAG, """
//                            License is invalid. Navigating to Invalid License Screen & Stopping Services if started
//                            =====================================
//                            """.trimIndent()
//                        )
//                        navController.navigate("invalid_license_screen"){
//                            popUpTo("settings_screen") { inclusive = true }
//                        }
//                    }
////                    startStopServicesLicense(context)
//                }
//            )
//        }
//    }
//}
//
//@Composable
//fun InvalidLicenseScreen (navController: NavController) {
//    WearTAKCIVTheme {
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(colors.background)
//                .padding(16.dp),
//            horizontalAlignment = Alignment.CenterHorizontally,
//            verticalArrangement = Arrangement.Center
//        ) {
//            Text(
//                text = stringResource(R.string.invalid_license_key),
//                fontWeight = FontWeight.SemiBold,
//                textAlign = TextAlign.Center
//            )
//            Spacer(modifier = Modifier.size(5.dp))
//            //Maybe add info here for how to contact Ascent for the key?
//            Text(
//                text = stringResource(R.string.please_add_correct_key_to_use_weartak),
//                textAlign = TextAlign.Center
//            )
//            Spacer(modifier = Modifier.size(10.dp))
//            Button(
//                onClick = { navController.navigate("license_setting_screen") {
//                    popUpTo("license_setting_screen") { inclusive = true }
//                } },
//                colors = ButtonDefaults.buttonColors(
//                    containerColor = colors.primary,
//                    contentColor = Color.Black
//                )
//            ) {
//                Text (
//                    text = stringResource(R.string.update_license),
//                    color = Color.Black,
//                    fontWeight = FontWeight.SemiBold,
//                    textAlign = TextAlign.Center
//                )
//            }
//        }
//    }
//}
//
//@Composable
//fun PhysiologicalAlertsSetting(navController: NavController) {
//    Log.d(TAG, "PhysioAlertSetting called")
//    val physiologicalAlertsCheckedLive by enablePhysiologicalAlertsSettingState.observeAsState()
//    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//
//        ReusableToggleChip(
//            physiologicalAlertsCheckedLive,
//            { enablePhysiologicalAlertsSettingState.postValue(it) },
//            {},
//            stringResource(R.string.physiological_alerts),
//            stringResource(R.string.on_off)
//        )
//
////        DataMetricsChip("physiological_alerts_bpm_screen", stringResource(R.string.threshold_high_heart_rate), hrHighAlertBpmThresholdSettingsState.value.toString(), navController)
////        DataMetricsChip("physiological_alerts_bpm_length_screen", stringResource(R.string.threshold_high_heart_rate_length), hrHighAlertNumSecsThresholdSettingsState.value.toString() + " seconds", navController)
//
//        ReusableTitleChip(
//            title = "Resting Heart Rate Alerts",
//            route= "physiological_heart_rate_alerts_setting_screen",
//            navController = navController
//        )
//
//        ReusableTitleChip(
//            title = "Exertion Alerts",
//            route= "physiological_exertion_alerts_setting_screen",
//            navController = navController
//        )
//
//        Spacer(modifier = Modifier.height(16.dp))
//    },
//    rowContent = {
//        ReusableConfirmCancelButtons(
//            onClickCancel = {
//                Log.d(TAG, "PhysiologicalAlertsSetting Settings NOT saved")
//                restingHrHighAlertBpmThresholdSettingsState.setValue(restingHrHighAlertBpmThreshold)
//                highRestingHrWarningThresholdLengthSettingsState.setValue(highRestingHrWarningThresholdLength)
//                highRestingHrAlertThresholdLengthSettingsState.setValue(highRestingHrAlertThresholdLength)
//                restingHrLowAlertBpmThresholdSettingsState.setValue(restingHrLowAlertBpmThreshold)
//                lowRestingHrWarningThresholdLengthSettingsState.setValue(lowRestingHrWarningThresholdLength)
//                lowRestingHrAlertThresholdLengthSettingsState.setValue(lowRestingHrAlertThresholdLength)
//                exertionThresholdWarningSettingsState.setValue(exertionThresholdWarning)
//                exertionWarningThresholdLengthSettingsState.setValue(exertionWarningThresholdLength)
//                exertionThresholdAlertSettingsState.setValue(exertionThresholdAlert)
//                exertionAlertThresholdLengthSettingsState.setValue(exertionAlertThresholdLength)
//                navController.navigate("settings_screen") {
//                    popUpTo(("settings_screen")) {
//                        inclusive = true
//                    }
//                }
//            },
//            onClickConfirm = {
//                navController.navigate("settings_screen") {
//                    popUpTo(("settings_screen")) {
//                        inclusive = true
//                    }
//                }
//            })
//    },
//    text = stringResource(R.string.physiological_alerts)
//)
//}
//
//@Composable
//fun HeartRateAlertsSetting(navController: NavController) {
//    Log.d(TAG, "HeartRateAlertsSetting called")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            Text(
//                text = "High Resting Heart Rate:",
//                textAlign = TextAlign.Left,
//                fontSize = 14.sp,
//                modifier = Modifier
//                    .padding(bottom = 5.dp, top = 10.dp)
//            )
//            ReusableTitleChipWithState(
//                route = "high_resting_hr_bpm_threshold_screen",
//                title = "High HR Threshold",
//                state = restingHrHighAlertBpmThresholdSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0} bpm" }
//            )
//            ReusableTitleChipWithState(
//                route = "high_resting_hr_warning_threshold_length",
//                title = SettingsActivity.LABEL_WARNING_LENGTH,
//                state = highRestingHrWarningThresholdLengthSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}" + SettingsActivity.UNIT_MINUTES }
//            )
//            ReusableTitleChipWithState(
//                route = "high_resting_hr_alert_threshold_length",
//                title = SettingsActivity.LABEL_ALERT_LENGTH,
//                state = highRestingHrAlertThresholdLengthSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}" + SettingsActivity.UNIT_MINUTES }
//            )
//            Spacer(modifier = Modifier.height(15.dp))
//            Text(
//                text = "Low Resting Heart Rate:",
//                textAlign = TextAlign.Left,
//                fontSize = 14.sp,
//                modifier = Modifier
//                    .padding(bottom = 5.dp, top = 10.dp)
//            )
//            ReusableTitleChipWithState(
//                route = "low_resting_hr_bpm_threshold_screen",
//                title = "Low HR Threshold",
//                state = restingHrLowAlertBpmThresholdSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0} bpm" }
//            )
//            ReusableTitleChipWithState(
//                route = "low_resting_hr_warning_threshold_length",
//                title = SettingsActivity.LABEL_WARNING_LENGTH,
//                state = lowRestingHrWarningThresholdLengthSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}" + " bpm" }
//            )
//            ReusableTitleChipWithState(
//                route = "low_resting_hr_alert_threshold_length",
//                title = SettingsActivity.LABEL_ALERT_LENGTH,
//                state = lowRestingHrAlertThresholdLengthSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}" + SettingsActivity.UNIT_MINUTES }
//            )
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Heart Rate Alert Settings NOT saved")
//                    restingHrHighAlertBpmThresholdSettingsState.setValue(restingHrHighAlertBpmThreshold)
//                    highRestingHrWarningThresholdLengthSettingsState.setValue(highRestingHrWarningThresholdLength)
//                    highRestingHrAlertThresholdLengthSettingsState.setValue(highRestingHrAlertThresholdLength)
//                    restingHrLowAlertBpmThresholdSettingsState.setValue(restingHrLowAlertBpmThreshold)
//                    lowRestingHrWarningThresholdLengthSettingsState.setValue(lowRestingHrWarningThresholdLength)
//                    lowRestingHrAlertThresholdLengthSettingsState.setValue(lowRestingHrAlertThresholdLength)
//
//                    navController.navigate("physiological_alerts_setting_screen") {
//                        popUpTo(("physiological_alerts_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    restingHrHighAlertBpmThresholdState.setValue(restingHrHighAlertBpmThresholdSettingsState.value)
//                    highRestingHrWarningThresholdLengthState.setValue(highRestingHrWarningThresholdLengthSettingsState.value)
//                    highRestingHrAlertThresholdLengthState.setValue(highRestingHrAlertThresholdLengthSettingsState.value)
//                    restingHrLowAlertBpmThresholdState.setValue(restingHrLowAlertBpmThresholdSettingsState.value)
//                    lowRestingHrWarningThresholdLengthState.setValue(lowRestingHrWarningThresholdLengthSettingsState.value)
//                    lowRestingHrAlertThresholdLengthState.setValue(lowRestingHrAlertThresholdLengthSettingsState.value)
//
//                    navController.navigate("physiological_alerts_setting_screen") {
//                        popUpTo(("physiological_alerts_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = "Resting Heart Rate Alerts"
//    )
//}
//
//@Composable
//fun ExertionAlertsSetting(navController: NavController) {
//    Log.d(TAG, "ExertionAlertsSetting called")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableTitleChipWithState(
//                route = "exertion_threshold_warning_screen",
//                title = "Exertion Warning Threshold",
//                state = exertionThresholdWarningSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}%" }
//            )
//            ReusableTitleChipWithState(
//                route = "exertion_warning_threshold_length",
//                title = SettingsActivity.LABEL_WARNING_LENGTH,
//                state = exertionWarningThresholdLengthSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}" + SettingsActivity.UNIT_SECONDS }
//            )
//            Spacer(modifier = Modifier.height(15.dp))
//            ReusableTitleChipWithState(
//                route = "exertion_threshold_alert_screen",
//                title = "Exertion Alert Threshold",
//                state = exertionThresholdAlertSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}%"}
//            )
//            ReusableTitleChipWithState(
//                route = "exertion_alert_threshold_length",
//                title = SettingsActivity.LABEL_ALERT_LENGTH,
//                state = exertionAlertThresholdLengthSettingsState,
//                navController = navController,
//                formatState = { value -> "${value ?: 0}" + SettingsActivity.UNIT_SECONDS}
//            )
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Exertion Alerts Settings NOT saved")
//                    exertionThresholdWarningSettingsState.setValue(exertionThresholdWarning)
//                    exertionWarningThresholdLengthSettingsState.setValue(exertionWarningThresholdLength)
//                    exertionThresholdAlertSettingsState.setValue(exertionThresholdAlert)
//                    exertionAlertThresholdLengthSettingsState.setValue(exertionAlertThresholdLength)
//
//                    navController.navigate("physiological_alerts_setting_screen") {
//                        popUpTo(("physiological_alerts_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    exertionThresholdWarningState.setValue(exertionThresholdWarningSettingsState.value)
//                    exertionWarningThresholdLengthState.setValue(exertionWarningThresholdLengthSettingsState.value)
//                    exertionThresholdAlertState.setValue(exertionThresholdAlertSettingsState.value)
//                    exertionAlertThresholdLengthState.setValue(exertionAlertThresholdLengthSettingsState.value)
//
//                    navController.navigate("physiological_alerts_setting_screen") {
//                        popUpTo(("physiological_alerts_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = "Exertion Alerts"
//    )
//}
//
//@Composable
//fun DataMetricsChip(
//    route: String,
//    title: String,
//    nameSecondary: String,
//    navController: NavController
//) {
//    Chip(
//        onClick = { navController.navigate(route) },
//        enabled = true,
//        label = { Text(text = title) },
//        secondaryLabel = {
//            Text(text = nameSecondary, maxLines =1, overflow = TextOverflow.Ellipsis)
//        },
//        colors = ChipDefaults.chipColors(
//            backgroundColor = Color.DarkGray,
//            contentColor = Color.White,
//            secondaryContentColor = Color.LightGray
//        ),
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(bottom = 3.dp)
//    )
//}
//
//@Composable
//fun SettingsToggleWithClickOnEnabledChip(
//    checkedLive: Boolean?,
//    onCheckedChange: (Boolean) -> Unit,
//    title: String,
//    description: String,
//    detailsEnabled: Boolean,
//    onDetailsClick: () -> Unit
//) {
//    SplitToggleChip(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 3.dp),
//        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
//        secondaryLabel = { Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
//        checked = checkedLive == true,
//        toggleControl = {
//            Switch(
//                checked = checkedLive == true,
//                onCheckedChange = null,
//                enabled = true,
//            )
//        },
//        onCheckedChange = onCheckedChange,
//        enabled = true,
//        onClick = {
//            if (detailsEnabled) {
//                onDetailsClick()
//            }
//        }
//    )
//}
//
//@OptIn(ExperimentalComposeUiApi::class)
//@Composable
//fun HeightPickerSetting(navController: NavController){
//    var selectedColumn by remember { mutableStateOf(0) }
//    val textStyle = MaterialTheme.typography.display1
//    val feetSettingsState by heightFeetSettingsState.observeAsState()
//    val inchesSettingsState by heightInchesSettingsState.observeAsState()
//
//    val feetState =
//        rememberPickerState(initialNumberOfOptions = 8, initiallySelectedOption = feetSettingsState!!.toInt()-1)
//    val feetContentDescription by remember {
//        derivedStateOf { "${feetState.selectedOption + 1} feet" }
//    }
//    val inchesState =
//        rememberPickerState(initialNumberOfOptions = 12, initiallySelectedOption = inchesSettingsState!!.toInt())
//    val inchesContentDescription by remember {
//        derivedStateOf { "${inchesState.selectedOption} inches" }
//    }
//    WearTakCivScreenDimensions(
//        columnContent = {
//            @Composable
//            fun Option(column: Int, text: String) =
//                Box(modifier = Modifier.fillMaxSize()) {
//                    Text(
//                        text = text,
//                        style = textStyle,
//                        color =
//                            if (selectedColumn == column) colors.secondary
//                            else colors.onBackground,
//                        modifier =
//                            Modifier
//                                .align(Alignment.Center)
//                                .wrapContentSize()
//                                .pointerInteropFilter {
//                                    if (it.action == MotionEvent.ACTION_DOWN) selectedColumn =
//                                        column
//                                    true
//                                },
//                    )
//                }
//
//            Row(
//                modifier = Modifier.fillMaxSize(),
//                verticalAlignment = Alignment.CenterVertically,
//                horizontalArrangement = Arrangement.Center,
//            ) {
//                Picker(
//                    readOnly = selectedColumn != 0,
//                    state = feetState,
//                    modifier = Modifier.size(64.dp, 100.dp),
//                    contentDescription = feetContentDescription,
//                    option = { foot: Int -> Option(0, "%2d".format(foot + 1)) },
//                )
//                Spacer(Modifier.width(8.dp))
//                Text(text = "'", style = textStyle, color = colors.onBackground)
//                Spacer(Modifier.width(8.dp))
//                Picker(
//                    readOnly = selectedColumn != 1,
//                    state = inchesState,
//                    modifier = Modifier.size(64.dp, 100.dp),
//                    contentDescription = inchesContentDescription,
//                    option = { inch: Int -> Option(1, "%02d".format(inch)) },
//                )
//                Text(text = "''", style = textStyle, color = colors.onBackground)
//            }
//        },
//        rowContent = {
//            val feetIndex = feetInchesIndex(heightFeetSettingsState.value, feetState)
//            val inchesIndex = feetInchesIndex(heightInchesSettingsState.value, inchesState)
//
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "HeightPickerSetting Settings NOT saved")
//                    navController.navigate("physiological_metrics_medical_profile_batdok") {
//                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
//                            inclusive = true
//                        }
//                    } },
//                onClickConfirm = {
//                    heightFeetSettingsState.postValue(feetIndex+1)
//                    heightInchesSettingsState.postValue(inchesIndex)
//                    navController.navigate("physiological_metrics_medical_profile_batdok") {
//                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
//                            inclusive = true
//                        }
//                    } })
//        },
//        text = stringResource(R.string.height)
//    )
//}
//
//fun feetInchesIndex(dataName: Int?, picker: PickerState) : Int {
//    if (null == dataName) {
//        return 0
//    }
//    val index = picker.selectedOption
//    if (index == -1) {
//        return 0
//    }
//    return index
//}
//
////Used for User metrics and output protocol
//@Composable
//fun RadioChoiceSetting (
//    navController: NavController,
//    metricSettingState: MutableLiveData<String>,
//    dataList: List<String>,
//    title: String,
//    route: String
//) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(top = 20.dp, bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        val index = findRadioIndex(metricSettingState.value, dataList)
//        var selectedRadioIndex by remember { mutableStateOf(index) }
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(horizontal = 5.dp)
//                .padding(bottom = 40.dp)
//                .verticalScroll(state = rememberScrollState()),
//            verticalArrangement = Arrangement.Center,
//            horizontalAlignment = Alignment.CenterHorizontally,
//        ) {
//            Text(
//                text = title,
//                fontSize = if (title.length < 20) 15.sp else 13.sp,
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier
//                    .padding(vertical = 10.dp, horizontal = 20.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
//
//            dataList.forEachIndexed { index, dataItem ->
//                run {
//                    ReusableSelectableChip(
//                        selected = index == selectedRadioIndex,
//                        onClick = { selectedRadioIndex = index},
//                        title = dataItem,
//                        combined = true,
//                        list = dataList
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(15.dp))
//        }
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "RadioChoiceSetting Settings NOT saved: $title")
//                    navController.navigate(route) {
//                        popUpTo((route)) {
//                            inclusive = true
//                        }
//                    }                },
//                onClickConfirm = {
//                    metricSettingState.setValue(dataList[selectedRadioIndex])
//                    Log.d(TAG, "RadioChoiceSetting Settings saved ($title): ${metricSettingState.value}")
//                    navController.navigate(route) {
//                        popUpTo((route)) {
//                            inclusive = true
//                        }
//                    }
//                })
//        }
//    }
//}
//
//fun findRadioIndex(dataName: String?, dataList: List<String>) : Int {
//    if (null == dataName) {
//        return 0
//    }
//    val index = dataList.indexOf(dataName)
//    if (index == -1) {
//        return 0
//    }
//    return index
//}
//
//@Composable
//fun AllergySetting (
//    navController: NavController,
//    title: String
//) {
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(colors.background)
//            .padding(top = 20.dp, bottom = 16.dp)
//            .padding(horizontal = 16.dp),
//        contentAlignment = Alignment.Center
//    ) {
////        val storedAllergies by allergyListSettingsState.observeAsState(initial = emptyList())
//
////        val initialIndices = remember(storedAllergies) {
////            storedAllergies.mapNotNull { item ->
////                allergiesOptionsList.indexOf(item.name)
////                    .takeIf { it >= 0 }
////            }
////        }
////
////        val selectedIndices = remember {
////            // start with whatever LiveData says
////            mutableStateListOf<Int>().apply { addAll(initialIndices) }
////        }
////
////        LaunchedEffect(storedAllergies) {
////            selectedIndices.clear()
////            selectedIndices.addAll(initialIndices)
////        }
////TODO: DUPLICATE CODE - Same column and title credentials vvv
//        //TODO: Convert to a lazyColumn
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(horizontal = 5.dp)
//                .padding(bottom = 40.dp)
//                .verticalScroll(state = rememberScrollState()),
//            verticalArrangement = Arrangement.Center,
//            horizontalAlignment = Alignment.CenterHorizontally,
//        ) {
//            Text(
//                text = title,
//                fontSize = if (title.length < 20) 15.sp else 13.sp,
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier
//                    .padding(vertical = 10.dp, horizontal = 20.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
//            //TODO: DUPLICATE CODE - Same column and title credentials ^^^
//
//            allergiesOptionsList.forEachIndexed { index, dataItem ->
//                run {
////                    var checked = index in selectedIndices
////                    ReusableMultipleChoiceCheckboxChip( selectedIndices,index,dataItem, checked, allergiesOptionsList)
//                }
//            }
//            Spacer(modifier = Modifier.height(15.dp))
//        }
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Allergy Settings NOT saved")
//                    navController.navigate("physiological_metrics_medical_profile_batdok") {
//                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
//                            inclusive = true
//                        }
//                    }                },
//                onClickConfirm = {
////                    val toSave = selectedIndices
////                        .map { idx -> DataRepository.AllergyItem(allergiesOptionsList[idx]) }
////                    allergyListSettingsState.postValue(toSave)
//                    navController.navigate("physiological_metrics_medical_profile_batdok") {
//                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
//                            inclusive = true
//                        }
//                    }                })
//        }
//    }
//}
//
//@Composable
//fun PhysiologicalMetricsSetting(navController: NavController) {
//    Log.d(TAG, "PhysioMetricsSetting called")
//
//    WearTakCivScreenDimensions(
//    columnContent = {
//        ReusableTitleChip(
//            title = stringResource(R.string.medical_profile_batdok),
//            route= "physiological_metrics_medical_profile_batdok",
//            navController = navController
//        )
//
//        ReusableTitleChip(
//            title = stringResource(R.string.gait_tracking),
//            route= "physiological_metrics_gait_tracking",
//            navController = navController
//        )
//
//        Spacer(modifier = Modifier.height(16.dp))
//    },
//    rowContent = {
//        ReusableConfirmCancelButtons(
//            onClickCancel = {
//                Log.d(TAG, "Physiological Settings NOT saved")
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//
//                    Log.d(TAG, "Finished saving physio state settings")
//
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.physiological_user_metrics)
//    )
//}
//@Composable
//fun PhysioMetricsMedicalProfileSetting(navController: NavController) {
//    Log.d(TAG, "PhysioMetricsMedicalProfileSetting called")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableTitleChipWithState(route = "physiological_metrics_birth_year_screen", title = stringResource(R.string.birth_year), state = birthYearSettingsState, navController = navController,)
//            ReusableTitleChipWithState(route = "physiological_metrics_height_screen", title = stringResource(R.string.height), state = heightFeetSettingsState, navController = navController, formatState = { "${heightFeetSettingsState.value ?: 0}'" + heightInchesSettingsState.value.toString() + "''" })
//            ReusableTitleChipWithState(route = "physiological_metrics_weight_screen", title = stringResource(R.string.weight), state = weightSettingsState, navController = navController, formatState = { value -> "${value ?: 0} lbs" })
//            ReusableTitleChipWithState(route = "physiological_metrics_sex_screen", title = stringResource(R.string.sex), state = sexSettingsState, navController = navController)
//            ReusableTitleChipWithState(route = "physiological_metrics_bloodtype_screen", title = stringResource(R.string.blood_type), state = bloodTypeSettingsState, navController = navController,)
////            ReusableTitleChipWithState(route = "physiological_metrics_allergies_screen", title = stringResource(R.string.allergies), state = allergyListSettingsState, navController = navController, formatState = { value -> value.takeIf { it!!.isNotEmpty() }?.joinToString(", ") { it.name } ?: "None" })
//            ReusableTitleChipWithState(route = "physiological_metrics_usertype_screen", title = stringResource(R.string.user_type), state = userTypeSettingsState, navController = navController,)
//            ReusableTitleChipWithState(route = "physiological_metrics_resting_hr_screen", title = stringResource(R.string.resting_heart_rate), state = restingHeartRateSettingsState, navController = navController,formatState = { value -> "${value ?: 0} bpm" })
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Physio Metrics Med Profile Settings NOT saved")
//                    birthYearState.setValue(birthYearValue)
//                    heightFeetSettingsState.setValue(heightFeetValue)
//                    heightInchesSettingsState.setValue(heightInchesValue)
//                    weightSettingsState.setValue(weightValue)
//                    sexSettingsState.setValue(sexValue)
//                    bloodTypeSettingsState.setValue(bloodTypeValue)
//                    //TODO: vv this might break
////                    allergyListSettingsState.setValue(allergyList)
//                    userTypeSettingsState.setValue(userTypeValue)
//                    restingHeartRateSettingsState.setValue(restingHeartRate)
//
//                    navController.navigate("physiological_metrics_setting_screen") {
//                        popUpTo(("physiological_metrics_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    birthYearState.setValue(birthYearSettingsState.value)
//                    heightFeetState.setValue(heightFeetSettingsState.value)
//                    heightInchesState.setValue(heightInchesSettingsState.value)
//                    weightState.setValue(weightSettingsState.value)
//                    sexState.setValue(sexSettingsState.value)
//                    bloodTypeState.setValue(bloodTypeSettingsState.value)
////                    allergyListState.setValue(allergyListSettingsState.value)
//                    userTypeState.setValue(userTypeSettingsState.value)
//                    restingHeartRateState.setValue(restingHeartRateSettingsState.value)
//
//                    navController.navigate("physiological_metrics_setting_screen") {
//                        popUpTo(("physiological_metrics_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.medical_profile_batdok)
//    )
//}
//
//@Composable
//fun PhysioMetricsGaitTrackingSettings(navController: NavController) {
//    Log.d(TAG, "PhysioMetricsGaitTrackingSettings called")
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableTitleChipWithState(route = "physiological_metrics_uniform_waist_size", title = stringResource(R.string.uniform_waist_size_text), state = uniformWaistSizeSettingsState, navController = navController,formatState = { value -> "${value ?: 0}''" })
//            ReusableTitleChipWithState(route = "physiological_metrics_stride_length", title = stringResource(R.string.stride_length_text), state = strideLengthSettingsState, navController = navController,formatState = { value -> "${value ?: 0}''" })
//            ReusableTitleChipWithState(route = "physiological_metrics_uniform_pants_length", title = stringResource(R.string.uniform_pants_length_text), state = uniformPantsLengthSettingsState, navController = navController,formatState = { value -> "${value ?: 0}''" })
//            ReusableTitleChipWithState(route = "physiological_metrics_loadout_weight", title = stringResource(R.string.loadout_weight_text), state = loadoutWeightSettingsState, navController = navController,formatState = { value -> "${value ?: 0} lbs" })
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "Gait Tracking Settings NOT saved")
//                    uniformWaistSizeSettingsState.setValue(uniformWaistSize)
//                    strideLengthSettingsState.setValue(strideLength)
//                    uniformPantsLengthSettingsState.setValue(uniformPantsLength)
//                    loadoutWeightSettingsState.setValue(loadoutWeight)
//
//                    navController.navigate("physiological_metrics_setting_screen") {
//                        popUpTo(("physiological_metrics_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                },
//                onClickConfirm = {
//                    uniformWaistSizeState.setValue(uniformWaistSizeSettingsState.value)
//                    strideLengthState.setValue(strideLengthSettingsState.value)
//                    uniformPantsLengthState.setValue(uniformPantsLengthSettingsState.value)
//                    loadoutWeightState.setValue(loadoutWeightSettingsState.value)
//
//                    navController.navigate("physiological_metrics_setting_screen") {
//                        popUpTo(("physiological_metrics_setting_screen")) {
//                            inclusive = true
//                        }
//                    }
//                })
//        },
//        text = stringResource(R.string.gait_tracking)
//    )
//}
//
//// vv No longer used
//@Composable
//fun HailTitleText(text: String, canChangeColor: Boolean, status: HailLicenseStates?){
//    Text(text, modifier = Modifier.padding(top = 15.dp, bottom = 5.dp), textAlign = TextAlign.Center,
//        color =
//            if (canChangeColor)
//                if (status == HailLicenseStates.SOFT_EXPIRED) Color.Yellow
//                else if (status == HailLicenseStates.EXPIRED) Color.Red
//                else Color.White
//            else Color.White)
//}
//@Composable
//fun HailDataText(text: String, canChangeColor: Boolean, status: HailLicenseStates?){
//    Text(text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
//        color =
//            if (canChangeColor)
//                if (status == HailLicenseStates.SOFT_EXPIRED) Color.Yellow
//                else if (status == HailLicenseStates.EXPIRED) Color.Red
//                else Color.White
//            else Color.White)
//}
//
//@Composable
//fun hailLicenseStatusTextFromString(status: HailLicenseStates?): String {
//    val statusString: String = when (status) {
//        HailLicenseStates.VALID -> "License Valid"
//        HailLicenseStates.CHECKING -> "Checking License"
//        HailLicenseStates.SOFT_EXPIRED -> "Expiring Soon"
//        HailLicenseStates.EXPIRED -> "License Expired"
//        HailLicenseStates.INVALID -> "License Invalid"
//        else -> stringResource(R.string.error)
//    }
//    return statusString
//}
//
//
//@Composable
//fun DateDisplay(serverDateString: String, status: HailLicenseStates?) {
//    // Parse the incoming date (pattern matches "Tue Sep 30 00:00:00 CDT 2025")
//    if (serverDateString != "null") {
//        val parser = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)
//        val parsedDate = parser.parse(serverDateString)!!
//
//        // Convert java.util.Date to LocalDate
//        val localDate = parsedDate.toInstant()
//            .atZone(ZoneId.systemDefault())
//            .toLocalDate()
//
//        // Format as "9/30/2025"
//        val displayDate = localDate.format(DateTimeFormatter.ofPattern("M/d/yyyy"))
//
//        // Calculate days difference
//        val today = LocalDate.now()
//        val daysDiff = ChronoUnit.DAYS.between(today, localDate)
//        val daysDisplay = "$daysDiff Days"
//
//        // Display in Compose
//        HailDataText(text = displayDate, true, status)
//        if (status != HailLicenseStates.EXPIRED)
//            HailDataText(text = daysDisplay, true, status)
//    } else {
//        HailDataText(text = stringResource(R.string.error), true, status)
//    }
//}
//
//@Composable
//fun HailSetting(navController: NavController) {
//    Log.d(TAG, "HailSetting called")
//    val info = dataRepository.hailLicenseInfoFlow.collectAsState(initial = null).value
//    val hailLicenseState = dataRepository.hailLicenseState.collectAsState().value
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            Text(
//                "Status:",
//                modifier = Modifier.padding(bottom = 5.dp),
//                textAlign = TextAlign.Center,
//                color = if (hailLicenseState == HailLicenseStates.SOFT_EXPIRED) Color.Yellow
//                else if (hailLicenseState == HailLicenseStates.EXPIRED) Color.Red
//                else Color.White
//            )
//
//            HailDataText(hailLicenseStatusTextFromString(hailLicenseState), true, hailLicenseState)
//
//            if (hailLicenseState == HailLicenseStates.EXPIRED || hailLicenseState == HailLicenseStates.SOFT_EXPIRED) {
//                Text(
//                    if (hailLicenseState == HailLicenseStates.EXPIRED) "Contact support to renew license" else "Contact support to extend license",
//                    textAlign = TextAlign.Center,
//                    fontSize = 12.sp
//                )
//            }
//            HailTitleText(
//                if (hailLicenseState == HailLicenseStates.EXPIRED) "License Expired:" else "License Expires:",
//                true, hailLicenseState)
//            DateDisplay(info?.expirationDate.toString(), hailLicenseState)
//
//            HailTitleText("Account Number:", false, hailLicenseState)
//            HailDataText(info?.accountNumber.toString(), false, hailLicenseState)
//
//            HailTitleText("License Identification:", false, hailLicenseState)
//            HailDataText(info?.licenseId.toString(), false, hailLicenseState)
//
//            HailTitleText("Device Identification:", false, hailLicenseState)
//            HailDataText(info?.deviceId.toString(), false, hailLicenseState)
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableBackButton(
//                onClick = {
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    } },
//            )
//        },
//        text = "HAIL Settings"
//    )
//}
//
//// ^^ No longer used
//
//@SuppressLint("StateFlowValueCalledInComposition")
//@Composable
//fun InvalidHailScreen(navController: NavController) {
//    val info = dataRepository.hailLicenseInfoFlow.collectAsState(initial = null).value // <-- observe backend license information changes
//    val hailLicenseState = dataRepository.hailLicenseState.collectAsState().value // <-- observe backend license state changes
//    val buttonPressed = dataRepository.hailCheckLicenseButtonPressed.collectAsState().value  // <-- observe button clicks
//    val scope = rememberCoroutineScope()
//    val showLoadingDialog = remember { mutableStateOf(false) } // single, stable state object for the dialog
//
//    // When user presses "Register", show loading while we await state updates
//    LaunchedEffect(buttonPressed) {
//        if (buttonPressed == true) showLoadingDialog.value = true
//    }
//
//    // React to license state transitions
//    LaunchedEffect(hailLicenseState) {
//        when (hailLicenseState) {
//            HailLicenseStates.CHECKING -> showLoadingDialog.value = true
//            HailLicenseStates.INVALID  -> showLoadingDialog.value = false   // stay on screen
//            HailLicenseStates.VALID,
//            HailLicenseStates.SOFT_EXPIRED -> {
//                showLoadingDialog.value = false
//                navController.navigate("hail_setting_screen") {
//                    popUpTo("settings_screen") { inclusive = true }
//                }
//            }
//            HailLicenseStates.EXPIRED -> showLoadingDialog.value = false // show message, stay
//        }
//    }
//
//    WearTAKCIVTheme {
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(colors.background)
//                .padding(10.dp),
//            horizontalAlignment = Alignment.CenterHorizontally,
//            verticalArrangement = Arrangement.Center
//        ) {
//            Text(
//                text = "Activate Hail",
//                fontSize = 15.sp,
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier
//                    .padding(vertical = 10.dp, horizontal = 20.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
//            Text(
//                text = "Pairing Code:",
//                textAlign = TextAlign.Center,
//            )
//            Text(
//                text = info?.pairingCode.toString(),
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                fontSize = 25.sp,
//                modifier = Modifier
//                    .padding(vertical = 7.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
//            Text(
//                text = "Enter the code on your HAIL license portal, then press Register below",
//                textAlign = TextAlign.Center,
//                fontSize = 12.sp,
//                lineHeight = 14.sp,
//            )
//            Spacer(modifier = Modifier.size(5.dp))
//            Row(
//                modifier = Modifier
//                    .padding(bottom = 10.dp)
//                    .fillMaxWidth(),
//                horizontalArrangement = Arrangement.Center) {
//                Button(
//                    onClick = {
//                        navController.navigate("settings_screen") {
//                            popUpTo("settings_screen") { inclusive = true }
//                        }
//                    },
//                    contentPadding = PaddingValues(10.dp),
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = Color.Gray,
//                    )
//                ) {
//                    Text(
//                        text = stringResource(R.string.dismiss),
//                    )
//                }
//
//                Spacer(modifier = Modifier.size(10.dp))
//
//                Button(
//                    onClick = {
//                        scope.launch {
//                            dataRepository.hailCheckLicenseButtonPressed.emit(true)
//                        }
//                    },
//                    contentPadding = PaddingValues(10.dp),
//                    colors = ButtonDefaults.buttonColors(colors.primary),
//                ) {
//                    Text(
//                        text = "Register",
//                        color = Color.Black
//                    )
//                }
//            }
//        }
//        // Render the dialog ONCE, using the remembered state (no inline mutableStateOf(true))
//        LoadingDialog(shouldShowDialog = showLoadingDialog, secondaryText = "Checking License Status")
//    }
//}
//
//fun displayHailLoadingDialog(
//    state: HailLicenseStates?,
//    shouldShowLoadingDialog: MutableState<Boolean>,
//    navController: NavController) {
//    Log.d(TAG, "$state")
//    if (state == HailLicenseStates.CHECKING){
//        shouldShowLoadingDialog.value = true
//    } else if (state == HailLicenseStates.INVALID){
//        shouldShowLoadingDialog.value = false
//    } else {
//        shouldShowLoadingDialog.value = false
//        navController.navigate("hail_setting_screen") {
//            popUpTo("settings_screen") { inclusive = true }
//        }
//    }
//    Log.d(TAG, state.toString())
//}
//
//@Composable
//fun PluginsScreen(navController: NavController, pluginViewModel: PluginViewModel, pluginList: List<PluginUI> ) {
//    val lifecycleOwner = LocalLifecycleOwner.current
//    DisposableEffect(lifecycleOwner) {
//        val observer = LifecycleEventObserver { _, event ->
//            if (event == Lifecycle.Event.ON_RESUME) {
//                pluginViewModel.refresh()
//            }
//        }
//        lifecycleOwner.lifecycle.addObserver(observer)
//        onDispose {
//            lifecycleOwner.lifecycle.removeObserver(observer)
//        }
//    }
//    var showPopup = remember { mutableStateOf(false) }
//    val shouldShowDialog = remember { mutableStateOf(false) }
//    val isConfirmed = remember { mutableStateOf(false) }
//    //TODO: Make this actually change based on success
//    val successBoolean = remember { mutableStateOf(true) }
////    val sortedUsers = remember(pluginList) {
////        pluginList.sortedBy { it.loadingStatus }
////    }
//    var pluginitem =  remember { mutableStateOf(PluginUI(
//        icon = null,
//        title = "",
//        desc = "",
//        loadingStatus = PluginLoadingStatus.NOT_LOADED,
//        pluginId = "",
//        versionName = "",
//        packageName = ""
//    )) }
//    WearTakCivScreenDimensions(
//        columnContent = {
//            LazyColumn(modifier = Modifier
//                .height(200.dp)){
//                itemsIndexed(
//                    items = pluginList,
//                    key = { _, item -> item.pluginId },
//                ) { index, item ->
//                    SwipeablePluginChip(
//                        index = index,
//                        title = item.title,
//                        loadingStatus = item.loadingStatus,
//                        showPopup = showPopup,
//                        appIcon = item.icon,
//                        navController = navController,
//                        onPrimaryActionClick = {
//                            pluginitem.value = item
//                            shouldShowDialog.value = true},
//                        onSecondaryActionClick = {
//                            pluginitem.value = item
//                            showPopup.value = true},
//                        checked = item.isLoaded,
//                        onCheckedChange = {
//                            when (it) {
//                                true -> pluginViewModel.loadPlugin(item.pluginId)
//                                false -> pluginViewModel.unloadPlugin(item.pluginId)
//                            }
//                        }
//                    )
//                }
//            }
//            Spacer(modifier = Modifier.height(10.dp))
//        },
//        rowContent = {
//            BackButton(
//                onClick = {
//                    navController.navigate("settings_screen") {
//                        popUpTo(("settings_screen")) {
//                            inclusive = true
//                        }
//                    } },
//            )
//        },
//        "Plugins"
//    )
//    PopupBox(
//        showPopup,
//        pluginitem.value.title,
//        description = pluginitem.value.desc,
//        versionNumber = pluginitem.value.versionName,
//        compatibility = pluginitem.value.compatibility,
//        loadingStatus = pluginitem.value.loadingStatus,
//        errorMessage = pluginitem.value.errorMessage
//    )
//
//    ConfirmationDialog(
//        shouldShowDialog,
//        description = "Remove ${pluginitem.value.title}?",
//        icon = Icons.Default.Warning,
//        confirmColor = Color.Green,
//        isConfirmed = isConfirmed
//    )
//    if (isConfirmed.value) {
//        LaunchedEffect(Unit) {
//            delay(1000) // Delay for 5 seconds
//            isConfirmed.value = false // Dismiss the dialog
//            successBoolean.value = false // Dismiss the dialog
//            pluginViewModel.removePlugin(pluginitem.value.pluginId)
//        }
//        SuccessDialog(
//            isConfirmed,
//            successBoolean
//        )
//    }
//}
//
//@Composable
//fun SuccessDialog(
//    shouldShowSuccessDialog: MutableState<Boolean>,
//    successBoolean : MutableState<Boolean>
//) {
//    Dialog(onDismissRequest = { shouldShowSuccessDialog.value = false }) {
//        Card(
//            modifier = Modifier
//                .fillMaxSize()
//                .height(200.dp),
//            colors = CardDefaults.cardColors(containerColor = colors.background)
//        ) {
//            Column(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .padding(16.dp),
//                verticalArrangement = Arrangement.Center,
//                horizontalAlignment = Alignment.CenterHorizontally,
//            ) {
//                if (successBoolean.value) {
////                    successImage()
//                    Spacer(modifier = Modifier.height(10.dp))
//                    androidx.compose.material3.Text(
//                        text = stringResource(R.string.success),
//                        color = Color.White,
//                        textAlign = TextAlign.Center,
//                    )
////                    additionalText()
//                } else {
//                    Icon(
//                        Icons.Rounded.Clear,
//                        modifier = Modifier.size(25.dp),
//                        contentDescription = null,
//                        tint = Color.Red
//                    )
//                    Spacer(modifier = Modifier.height(10.dp))
//                    // TODO: Add more context to the error, for example "not connected to server & mesh/ditto is disabled. Enable meshing to fix"
//                    Text(
//                        text = stringResource(R.string.marker_stored_error_msg),
//                        textAlign = TextAlign.Center,
//                    )
//                }
//            }
//        }
//    }
//}
//
//@OptIn(ExperimentalWearMaterialApi::class)
//@Composable
//fun SwipeablePluginChip(
//    index: Int,
//    title: String,
//    loadingStatus: PluginLoadingStatus,
//    showPopup: MutableState<Boolean>,
//    appIcon: Drawable?,
//    navController: NavController,
//    onPrimaryActionClick: () -> Unit,
//    onSecondaryActionClick: () -> Unit,
//    checked: Boolean,
//    onCheckedChange: (Boolean) -> Unit
//) {
//    val revealState = rememberRevealState()
//    val scope = rememberCoroutineScope()
//
//
//    SwipeToRevealChip(
//        revealState = revealState,
//        modifier = Modifier
//            .fillMaxWidth()
//            .semantics {
//                // Use custom actions to make the primary and secondary actions accessible
//                customActions =
//                    listOf(
//                        CustomAccessibilityAction("Delete TAK Server") {
//                            /* Add the primary action click handler here */
//                            true
//                        },
//                        CustomAccessibilityAction("Edit TAK Server") {
//                            /* Add the secondary click handler here */
//                            true
//                        }
//                    )
//            },
//        primaryAction = {
//            SwipeToRevealPrimaryAction(
//                revealState = revealState,
//                icon = { Icon(SwipeToRevealDefaults.Delete, stringResource(R.string.delete)) },
//                label = { Text(stringResource(R.string.delete)) },
//                onClick = { onPrimaryActionClick();
//                     /* scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealed)} */
//                }
//            )
//        },
//        secondaryAction = {
//            SwipeToRevealSecondaryAction(
//                revealState = revealState,
//                onClick = { onSecondaryActionClick() }
//            ) {
//                Icon(
//                    painterResource( R.drawable.info_outline ),
//                    "More Options")
//            }
//        },
//        undoPrimaryAction = {
//            SwipeToRevealUndoAction(
//                revealState = revealState,
//                label = { Text(stringResource(R.string.undo)) },
//                onClick = { /* Add the undo handler for primary action */ }
//            )
//        },
//        undoSecondaryAction = {
//            SwipeToRevealUndoAction(
//                revealState = revealState,
//                label = { Text(stringResource(R.string.undo)) },
//                onClick = { /* Add the undo handler for secondary action */ }
//            )
//        },
//        onFullSwipe = {onPrimaryActionClick() }
//    ) {
//        SplitToggleChip(
//            contentPadding = PaddingValues(end = 15.dp, start = 5.dp, top = 5.dp, bottom = 5.dp),
//            label = {
//                appIcon.let {
//                    AndroidView(
//                        modifier = Modifier
//                            .size(30.dp)
//                            .align(Alignment.CenterVertically),
//                        factory = { context ->
//                            ImageView(context).apply {
//                                setImageDrawable(it)
//                            }
//                        }
//                    )
//                }
//                Spacer(modifier = Modifier.width(5.dp))
//                Column {
//                    Text(
//                        title,
//                        fontSize = 13.sp,
//                        maxLines = 2,
//                        overflow = TextOverflow.Ellipsis,
//                        modifier = Modifier
//                    )
//                    Text(
//                        loadingStatus.toString(),
//                        fontSize = 11.sp,
//                        maxLines = 1,
//                        overflow = TextOverflow.Ellipsis,
//                        color = Color.LightGray
//                    )
//                }
//            },
//            checked = checked,
//            toggleControl = {
//                Switch(
//                    checked = checked,
//                    onCheckedChange =  null,
//                    modifier = Modifier.size(25.dp)
//                )
//            },
//            onCheckedChange = onCheckedChange,
//            enabled = true,
//            onClick = {
//                if (loadingStatus == PluginLoadingStatus.LOADED){
//                    navController.navigate("specific_plugin_screen/$index")
//                } else {
//                    //Nothing happens
//                }
//            },
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(bottom = 3.dp),
//            colors = ToggleChipDefaults.splitToggleChipColors(
//                backgroundColor = if (loadingStatus == PluginLoadingStatus.ERROR) Color.Red.copy(alpha = 0.5f) else colors.surface,
//                splitBackgroundOverlayColor = if (loadingStatus == PluginLoadingStatus.ERROR) Color.Red.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.05f)
//            )
//        )
//    }
//}
//
//@Composable
//fun SpecificPluginScreen(
//    navController: NavController,
//    index: Int,
//    pluginList: List<PluginUI>
//) {
//    var title by remember { mutableStateOf(pluginList.getOrNull(index)?.title.orEmpty()) }
//
//    WearTakCivScreenDimensions(
//        columnContent = { },
//        rowContent = {
//            BackButton(
//                onClick = {
//                    navController.navigate("plugins_screen") {
//                        popUpTo(("plugins_screen")) {
//                            inclusive = true
//                        }
//                    } },
//            )
//        },
//        title
//    )
//}
//
//@Composable
//fun PopupBox(
//    showPopup: MutableState<Boolean>,
//    titleText: String,
//    description: String,
//    versionNumber: String,
//    compatibility: PluginCompatibilityStatus,
//    loadingStatus: PluginLoadingStatus,
//    errorMessage: String
//) {
//    if (showPopup.value) {
//        Dialog(
//            onDismissRequest = { showPopup.value = false },
//        ) {
//            Card(
//                colors = CardDefaults.cardColors(
//                    containerColor = Color.DarkGray),
//                shape = RectangleShape,
//                modifier = Modifier
//                    .fillMaxHeight(.6f)
//                    .fillMaxWidth(.8f)
//            ) {
//                Column(
//                    modifier = Modifier
//                        .padding(10.dp),
//                    verticalArrangement = Arrangement.Center,
//                    horizontalAlignment = Alignment.Start,
//                ) {
//                    Text(
//                        text = titleText,
//                        maxLines = 1,
//                        fontSize = 13.sp,
//                        fontWeight = FontWeight.SemiBold
//                    )
//                    Text(
//                        text = description,
//                        maxLines = 2,
//                        fontSize = 11.sp,
//                        lineHeight = 11.sp
//                    )
//                    Spacer(modifier = Modifier.height(10.dp))
//                    if (loadingStatus == PluginLoadingStatus.ERROR){
//                        Column(
//                            //TODO WEAR-535: Make modular
//                            Modifier
//                                .background(Color.Red.copy(alpha = 0.5f))
//                                .padding(5.dp)
//                                .verticalScroll(rememberScrollState())
//                        ) {
//                            Text(
//                                text = "ERROR: $errorMessage",
//                                color = Color.White,
//                                maxLines = 4,
//                                fontSize = 12.sp,
//                                lineHeight = 11.sp
//                            )
//                        }
//                    } else {
//                        Column(
//                            //TODO WEAR-535: Make modular
//                            Modifier
//                                .background(Color.Green)
//                                .padding(5.dp)
//                                .verticalScroll(rememberScrollState())
//                        ) {
//                            Text(
//                                text = "Version: $versionNumber",
//                                color = Color.Black,
//                                maxLines = 3,
//                                fontSize = 10.sp,
//                                lineHeight = 11.sp
//                            )
//                            Spacer(modifier = Modifier.height(10.dp))
//                            Text(
//                                text = "Is $compatibility with WearTAK ${MainActivity.Constants.versionNumber} and is $loadingStatus",
//                                color = Color.Black,
//                                maxLines = 2,
//                                fontSize = 10.sp,
//                                lineHeight = 11.sp
//                            )
//                        }
//                    }
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun SettingSwitchToggle(
//    checked: Boolean,
//    onCheckedChange: (Boolean) -> Unit,
//    title: String,
//    description: String,
//    enabled: Boolean = true,
//) {
//    // The primary label should have a maximum 3 lines of text
//    // and the secondary label should have max 2 lines of text.
//    ToggleChip(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 3.dp),
//        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
//        secondaryLabel = {
//            Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis)
//        },
//        checked = checked,
//        // For Switch  toggle controls the Wear Material UX guidance is to set the
//        // unselected toggle control color to ToggleChipDefaults.switchUncheckedIconColor()
//        // rather than the default.
//        colors =
//            ToggleChipDefaults.toggleChipColors(
//                uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
//            ),
//        toggleControl = {
//            Switch(
//                checked = checked,
//                onCheckedChange = null,
//                enabled = enabled,
//            )
//        },
//        onCheckedChange = onCheckedChange,
//        enabled = true,
//    )
//}
//@Composable
//fun SettingSwitchToggleLiveData(
//    checkedLive: Boolean?,
//    onCheckedChange: (Boolean) -> Unit,
//    appIcon: @Composable () -> Unit,
//    title: String,
//    description: String,
//) {
//    // The primary label should have a maximum 3 lines of text
//    // and the secondary label should have max 2 lines of text.
//    ToggleChip(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 3.dp),
//        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
//        secondaryLabel = {
//            Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis)
//        },
//        appIcon = {appIcon()},
//        checked = checkedLive == true,
//        // For Switch  toggle controls the Wear Material UX guidance is to set the
//        // unselected toggle control color to ToggleChipDefaults.switchUncheckedIconColor()
//        // rather than the default.
//        colors =
//            ToggleChipDefaults.toggleChipColors(
//                uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
//            ),
//        toggleControl = {
//            Switch(
//                checked = checkedLive == true,
//                onCheckedChange = null,
//                enabled = true,
//            )
//        },
//        onCheckedChange = onCheckedChange,
//        enabled = true,
//    )
//}
//
////Contains functionality for settings screen
//@Composable
//fun SettingsScreen(
//    navController: NavController,
//    callsignLiveDataState: MutableLiveData<String>,
//    licenseLiveDataState: MutableLiveData<String>,
//    context: Context
//) {
//    val locationCheckedLive by enableLocationServiceSettingsState.observeAsState()
//    val dittoCheckedLive by enableDittoServiceSettingsState.observeAsState()
//    val companionAppCheckedLive by enableCompanionAppServiceSettingsState.observeAsState()
//    val chatCheckedLive by enableChatServiceSettingsState.observeAsState()
//    val immersionAlertsCheckedLive by enableImmersionAlertsSettingsState.observeAsState()
//    val batteryAlertsCheckedLive by enableBatteryAlertsSettingsState.observeAsState()
//    val enableSmsCheckedLive by enableSmsForAlertsSettingsState.observeAsState()
//    var smsListTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(smsNumbersListString)) }
//    val physioCheckedLive by enablePhysiologicalServicesSettingsState.observeAsState()
//    val hailCheckedLive by enableHailServiceSettingsState.observeAsState()
//    val hailLicenseState = dataRepository.hailLicenseState.collectAsState(initial = null).value
//    val batdokCheckedLive by enableBatdokCotSettingsState.observeAsState()
//    val betaFeaturesCheckedLive by enableBetaFeaturesSettingsState.observeAsState()
//    val bloodhoundCompassCheckedLive by enableBloodhoundCompassServiceSettingsState.observeAsState()
//    val displayedSecondaryText by saveBattOnWifiStringState.observeAsState()
//    val onnxGunshotDetectCheckedLive by enableOnnxGunshotServiceSettingsState.observeAsState()
//    val onnxUasDetectCheckedLive by enableOnnxUasServiceSettingsState.observeAsState()
//    val coroutineScope = rememberCoroutineScope()
//    var versionTapCount by remember { mutableStateOf(0) }
//    var lastVersionTapTime by remember { mutableStateOf(0L) }
//    val tapsRequired = 8
//    val timeoutMs = 1500L
//    val devModeEnabled by dataRepository
//        .getEnableWearTakDevMode
//        .collectAsState(initial = false)
//
//    WearTakCivScreenDimensions(
//        columnContent = {
//            ReusableTitleChipWithState(
//                route = "callsign_screen",
//                title = stringResource(R.string.callsign),
//                state = callsignSettingsState,
//                navController = navController
//            )
//
//            ReusableTitleChipWithState(
//                route = "team_screen",
//                title = stringResource(R.string.team),
//                state = teamSettingsState,
//                navController = navController
//            )
//
//            ReusableTitleChipWithState(
//                route = "role_screen",
//                title = stringResource(R.string.role),
//                state = roleSettingsState,
//                navController = navController
//            )
//
//            Spacer(modifier = Modifier.height(10.dp))
//
//            Text(
//                text = stringResource(R.string.connection_settings),
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                fontSize = 15.sp,
//                modifier = Modifier.padding(7.dp)
//            )
//
//            ReusableTitleChip(
//                title = stringResource(R.string.tak_servers),
//                route = "tak_server_screen",
//                onClick = { takServerListState.postValue(takServerList) },
//                navController = navController
//            )
//            DataMetricsChip("sitx_tak_screen", stringResource(R.string.sitx_tak),
//                enableDisableFromBoolean(enableSitxServiceSettingsState.value), navController)
//            ReusableTitleChip(
//                title = "Reporting Settings",
//                route = "reporting_settings_screen",
//                navController = navController
//            )
//
//            ReusableTitleChip(
//                title = stringResource(R.string.manage_outputs),
//                route = "manage_outputs_screen",
//                navController = navController
//            )
//
//            ReusableTitleChipWithState(
//                route = "optimize_battery_on_wifi_screen",
//                title = stringResource(R.string.save_battery_on_wifi),
//                state = callsignSettingsState,
//                onClick = { enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimizationState.value)
//                    enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimizationState.value)
//                    wifiSsidListSettingsState.postValue(wifiSsidListState.value)},
//                navController = navController,
//                formatState = { when (displayedSecondaryText) {
//                        ALL_WIFI_NETWORKS -> stringResource(R.string.all_wifi_connections)
//                        SOME_WIFI_NETWORKS -> stringResource(R.string.some_wifi_connections)
//                        else -> stringResource(R.string.no_wifi_connections)
//                    }
//                }
//            )
//
//            Spacer(modifier = Modifier.height(10.dp))
//
//            Text(
//                text = stringResource(R.string.general_settings),
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier.padding(7.dp)
//            )
//
//            ReusableToggleChip(
//                locationCheckedLive,
//                { enableLocationServiceSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.location_service),
//                stringResource(R.string.service_on_off)
//            )
//
//            ReusableToggleChip(
//                dittoCheckedLive,
//                { enableDittoServiceSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.ditto),
//                stringResource(R.string.service_on_off)
//            )
//
//            ReusableToggleChip(
//                companionAppCheckedLive,
//                { enableCompanionAppServiceSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.companion_app),
//                stringResource(R.string.service_on_off)
//            )
//
//            ReusableToggleChip(
//                chatCheckedLive,
//                { enableChatServiceSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.chat),
//                stringResource(R.string.service_on_off)
//            )
//
//            SettingsToggleWithClickOnEnabledChip(
//                bloodhoundCompassCheckedLive,
//                { enableBloodhoundCompassServiceSettingsState.postValue(it) },
//                "Bloodhound & Compass",
//                if (bloodhoundCompassCheckedLive == true) {
//                    "Settings →"
//                } else {
//                    stringResource(R.string.service_on_off)
//                },
//                detailsEnabled = bloodhoundCompassCheckedLive == true,
//                onDetailsClick = {
//                    navController.navigate("bloodhound_settings_screen") {
//                        popUpTo("bloodhound_settings_screen") { inclusive = true }
//                    }
//                }
//            )
//            ReusableToggleChip(
//                immersionAlertsCheckedLive,
//                { enableImmersionAlertsSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.immersion_alerts),
//                stringResource(R.string.on_off)
//            )
//            Chip(
//                onClick = {
//                    navController.navigate("atm_pressure_alerts_screen") {
//                        popUpTo("atm_pressure_alerts_screen") { inclusive = true }
//                    }
//                },
//                enabled = true,
//                label = { Text(text = stringResource(R.string.atm_pressure_alerts)) },
//                colors = ChipDefaults.chipColors(
//                    backgroundColor = Color.DarkGray,
//                    contentColor = Color.White,
//                    secondaryContentColor = Color.LightGray
//                ),
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(bottom = 3.dp)
//            )
//
//            if (betaFeaturesCheckedLive == true) {
//                SettingsToggleWithClickOnEnabledChip(
//                    onnxGunshotDetectCheckedLive,
//                    { enableOnnxGunshotServiceSettingsState.postValue(it) },
//                    stringResource(R.string.gunshot_detection),
//                    stringResource(R.string.audio_detection_trigger_threshold) + " " +
//                        stringResource(
//                            R.string.threshold_percent_format,
//                            onnxGunshotTriggerThresholdPercentSettingsState.value ?: DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
//                        ),
//                    detailsEnabled = true,
//                    onDetailsClick = {
//                        navController.navigate("gunshot_detection_threshold_screen") {
//                            popUpTo("gunshot_detection_threshold_screen") { inclusive = true }
//                        }
//                    }
//                )
//                SettingsToggleWithClickOnEnabledChip(
//                    onnxUasDetectCheckedLive,
//                    { enableOnnxUasServiceSettingsState.postValue(it) },
//                    stringResource(R.string.uas_detection),
//                    stringResource(R.string.audio_detection_trigger_threshold) + " " +
//                        stringResource(
//                            R.string.threshold_percent_format,
//                            onnxUasTriggerThresholdPercentSettingsState.value ?: DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
//                        ),
//                    detailsEnabled = true,
//                    onDetailsClick = {
//                        navController.navigate("uas_detection_threshold_screen") {
//                            popUpTo("uas_detection_threshold_screen") { inclusive = true }
//                        }
//                    }
//                )
//            }
//
//            ReusableToggleChip(
//                enableSmsCheckedLive,
//                { enableSmsForAlertsSettingsState.postValue(it) },
//                {},
//                "SMS Alerts",
//                stringResource(R.string.on_off)
//            )
//            val focusManager = LocalFocusManager.current
//
//                if (enableSmsForAlertsSettingsState.value == true) {
//                    //TODO: WEAR-450: Conform to outlinedtextfield
//                    OutlinedTextField(
//                        value = smsListTextFieldValue,
//                        onValueChange = {
//                            smsListTextFieldValue = it
//                            smsNumbersListStringSettingsState.postValue(smsListTextFieldValue.text)
//                        },
//                        modifier = Modifier.padding(5.dp),
//                        maxLines = 3,
//                        label = {
//                            Text(
//                                text = stringResource(R.string.sms_recipients),
//                                color = colors.primary,
//                                fontSize = 12.sp
//                            )
//                        },
//                        placeholder = {
//                            Text(
//                                text = stringResource(R.string.sms_recipients),
//                                color = colors.primary
//                            )
//                        },
//                        textStyle = TextStyle(color = colors.primary),
//                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
//                        keyboardActions = KeyboardActions(
//                            onDone = {
//                                if (smsListTextFieldValue.text.contains("[A-Za-z!\"#$%&'()*./:;\\\\<=>?@^_`{|}~]".toRegex())) {
//                                    Toast.makeText(
//                                        mainActivity,
//                                        context.getString(R.string.invalid_characters),
//                                        Toast.LENGTH_LONG
//                                    ).show()
//                                } else {
//                                    focusManager.clearFocus()
//                                }
//                            }
//                        )
//                    )
//                }
//
//            ReusableToggleChip(
//                batteryAlertsCheckedLive,
//                { enableBatteryAlertsSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.battery_alerts),
//                stringResource(R.string.on_off)
//            )
//
//            SettingSwitchToggleLiveData(
//                betaFeaturesCheckedLive,
//                { enableBetaFeaturesSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.beta_features),
//                stringResource(R.string.beta_features_desc)
//            )
//            Spacer(modifier = Modifier.height(10.dp))
//
//            Text(
//                text = stringResource(R.string.physiological_settings),
//                textAlign = TextAlign.Center,
//                fontWeight = FontWeight.SemiBold,
//                modifier = Modifier.padding(7.dp)
//            )
//            ReusableToggleChip(
//                physioCheckedLive,
//                { enablePhysiologicalServicesSettingsState.postValue(it) },
//                {},
//                stringResource(R.string.physiological_monitoring),
//                stringResource(R.string.service_on_off)
//            )
//            /*
//            SettingSwitchToggleLiveData(
//                hailCheckedLive,
//                { enableHailServiceSettingsState.postValue(it) },
//                { Icon(
//                    painter = painterResource(id = R.drawable.hail_icon),
//                    tint = Color.Unspecified,
//                    contentDescription = "null",
//                    modifier = Modifier
//                        .size(20.dp),
//                ) },
//                "HAIL",
//                stringResource(R.string.service_on_off)
//            )
//            if (enableHailServiceSettingsState.value == true){
//                DataMetricsChip(
//                    if (hailLicenseState == HailLicenseStates.INVALID) {
//                        "hail_pairing_screen"
//                    } else {
//                        "hail_setting_screen"
//                    },
//                    "HAIL Settings", hailLicenseStatusTextFromString(hailLicenseState), navController)
//            }
//             */
//            ReusableToggleChip(
//                batdokCheckedLive,
//                { enableBatdokCotSettingsState.postValue(it) },
//                { Icon(
//                    painter = painterResource(id = R.drawable.batdok),
//                    tint = Color.Unspecified,
//                    contentDescription = "null",
//                    modifier = Modifier
//                        .size(20.dp),
//                ) },
//                stringResource(R.string.batdok),
//                stringResource(R.string.on_off)
//            )
//
//            ReusableTitleChip(
//                title = stringResource(R.string.physiological_alerts),
//                route = "physiological_alerts_setting_screen",
//                navController = navController,
//                onClick = {
//                    //hrHighAlertBpmThresholdSettingsState.postValue(hrHighAlertBpmThresholdState.value)
//                    //hrHighAlertNumSecsThresholdSettingsState.postValue(hrHighAlertNumSecsThresholdState.value)
//                }
//            )
///*
//ReusableTitleChip(
//                title = stringResource(R.string.physiological_user_metrics),
//                route = "physiological_metrics_setting_screen",
//                navController = navController,
//                onClick = {
//                    birthYearSettingsState.postValue(birthYearState.value)
//                    heightFeetSettingsState.postValue(heightFeetState.value)
//                    heightInchesSettingsState.postValue(heightInchesState.value)
//                    weightSettingsState.postValue(weightState.value)
//                    sexSettingsState.postValue(sexState.value)
//                    bloodTypeSettingsState.postValue(bloodTypeState.value)
////                    allergyListSettingsState.postValue(allergyListState.value)
//                    userTypeSettingsState.postValue(userTypeState.value)
//                    restingHeartRateSettingsState.postValue(restingHeartRateState.value)
//                }
//            )
// */
//            if (enableBetaFeatures) {
//                Chip(
//                    onClick = {
//                        crmPoorMaxThresholdSettingsState.postValue(
//                            crmPoorMaxThreshold
//                        )
//                        crmModerateMaxThresholdSettingsState.postValue(
//                            crmModerateMaxThreshold
//                        )
//
//                        navController.navigate("crm_thresholds_screen") {
//                            popUpTo("crm_thresholds_screen") {
//                                inclusive = true
//                            }
//                        }
//                    },
//                    enabled = true,
//                    label = {
//                        Text(text = "CRM Settings")
//                    },
//                    secondaryLabel = {
//                        Text(
//                            text = "Thresholds →",
//                            maxLines = 1,
//                            overflow = TextOverflow.Ellipsis
//                        )
//                    },
//                    colors = ChipDefaults.chipColors(
//                        backgroundColor = Color.DarkGray,
//                        contentColor = Color.White,
//                        secondaryContentColor = Color.LightGray
//                    ),
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(top = 3.dp)
//                )
//            }
//
//            Chip(
//                onClick = {
//                    birthYearSettingsState.postValue(birthYearState.value)
//                    heightFeetSettingsState.postValue(heightFeetState.value)
//                    heightInchesSettingsState.postValue(heightInchesState.value)
//                    weightSettingsState.postValue(weightState.value)
//                    sexSettingsState.postValue(sexState.value)
//                    bloodTypeSettingsState.postValue(bloodTypeState.value)
//                    allergyListSettingsState.postValue(allergyListState.value)
//                    userTypeSettingsState.postValue(userTypeState.value)
//                    restingHeartRateSettingsState.postValue(restingHeartRateState.value)
//
//                    navController.navigate("physiological_metrics_setting_screen") {
//                        popUpTo("physiological_metrics_setting_screen") { inclusive = true }
//                    } },
//                enabled = true,
//                label = { Text(text = stringResource(R.string.physiological_user_metrics)) },
//                colors = ChipDefaults.chipColors(
//                    backgroundColor = Color.DarkGray,
//                    contentColor = Color.White,
//                    secondaryContentColor = Color.LightGray
//                ),
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(top = 3.dp)
//            )
//            Chip(
//                onClick = {
//                    navController.navigate("plugins_screen") {
//                        popUpTo("plugins_screen") { inclusive = true }
//                    }
//                },
//                enabled = true,
//                label = { Text(text = "Plugins") },
//                colors = ChipDefaults.chipColors(
//                    backgroundColor = Color.DarkGray,
//                    contentColor = Color.White,
//                    secondaryContentColor = Color.LightGray
//                ),
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(top = 3.dp)
//            )
//
//            if (devModeEnabled) {
//                Chip(
//                    modifier = Modifier.fillMaxWidth(),
//                    onClick = {
//                        navController.navigate("debug_tools")
//                    },
//                    label = {
//                        Text("Dev Debug Tools")
//                    },
//                    secondaryLabel = {
//                        Text("View network and build information")
//                    }
//                )
//            }
//
//            Spacer(modifier = Modifier.height(10.dp))
//
//            var versionTapCount by remember { mutableStateOf(0) }
//            var lastVersionTapTime by remember { mutableStateOf(0L) }
//
//            DebugVersionText(
//                wearTakInfo = dataRepository.wearTakInfo,
//                dataRepository = dataRepository,
//                onDebugToggled = { enabled ->
//                    // Optional: if you want to immediately test something when debug is enabled
//                    if (enabled) {
//                        // e.g., trigger a manual battery-alert test hook
//                        // triggerTestBatteryAlert()
//                    }
//                    setAirplaneMode("0")
//                }
//            )
//
//            Spacer(modifier = Modifier.height(16.dp))
//        },
//        rowContent = {
//            ReusableConfirmCancelButtons(
//                onClickCancel = {
//                    Log.d(TAG, "SettingsScreen Settings NOT saved")
////                        navController.navigate("main_screen") {
////                            popUpTo(("main_screen")) {
////                                inclusive = true
////                            }
////                        }
//                    loadSettingsValues()
//
//                    settingsActivity.finish()
//                    context.startActivity(Intent(context, MainActivity::class.java))
//                },
//                onClickConfirm = {
//                    var settingsChanged: Boolean = false
//                    var callsignChanged: Boolean = false
//                    var teamChanged = false
//                    var roleChanged = false
//                    var categoryChanged = false
//                    var licenseChanged: Boolean = false
//                    val maxReportRate: Int = 100000000
//                    val minReportRate: Int = 1
//                    var locationCheckedChanged: Boolean = false
//                    var physioCheckedChanged: Boolean = false
//                    var hailCheckedChanged: Boolean = false
//                    var batdokCheckedChanged: Boolean = false
//                    var dittoCheckedChanged: Boolean = false
//                    var chatCheckedChanged: Boolean = false
//                    var immersionAlertsChanged = false
//                    var lowPressureAlertChanged = false
//                    var lowPressureAlertThresholdChanged = false
//                    var highPressureAlertChanged = false
//                    var highPressureAlertThresholdChanged = false
//                    var onnxGunDetectChanged: Boolean = false
//                    var onnxGunThresholdChanged: Boolean = false
//                    var onnxUasDetectChanged: Boolean = false
//                    var onnxUasThresholdChanged: Boolean = false
//                    var betaFeaturesChanged: Boolean = false
//                    var batteryAlertsCheckedChanged: Boolean = false
//                    var bloodhoundCompassCheckedChanged: Boolean = false
//                    var bloodhoundProximityVibrationChanged: Boolean = false
//                    var bloodhoundProximityRadiusChanged: Boolean = false
//                    var bloodhoundProximityIntensityChanged: Boolean = false
//                    var dynamicReportingChanged: Boolean = false
//                    var constantReportingIntervalChanged: Boolean = false
//                    var stationaryReportingIntervalChanged: Boolean = false
//                    var onFootReportingIntervalChanged: Boolean = false
//                    var vehicleReportingIntervalChanged: Boolean = false
//                    var whileAlertingReportingIntervalChanged: Boolean = false
//                    var smsAlertsCheckedChanged: Boolean = false
//                    var smsNumbersChanged: Boolean = false
//                    var restingHeartRateHighThresholdChanged: Boolean = false
//                    var highRestingHrWarningThresholdLengthChanged: Boolean = false
//                    var highRestingHrAlertThresholdLengthChanged: Boolean = false
//                    var restingHeartRateLowThresholdChanged: Boolean = false
//                    var lowRestingHrWarningThresholdLengthChanged: Boolean = false
//                    var lowRestingHrAlertThresholdLengthChanged: Boolean = false
//                    var exertionWarningThresholdChanged: Boolean = false
//                    var exertionWarningThresholdLengthChanged: Boolean = false
//                    var exertionAlertThresholdChanged: Boolean = false
//                    var exertionAlertThresholdLengthChanged: Boolean = false
//                    var physiologicalAlertsCheckedChanged: Boolean = false
//                    var saveBattOnWifiChanged = false
//                    var takServerListChanged = false
//                    var birthYearChanged: Boolean = false
//                    var enableMulticastChanged = false
//                    var multicastAddressChanged = false
//                    var multicastOutputProtocolChanged = false
//                    var multicastServerPortChanged = false
//                    var enableTabletCommandChanged = false
//                    var tabletCommandEndpointChanged = false
//                    var tabletCommandAPIKeyChanged = false
//                    var enableEsriChanged = false
//                    var esriEndpointChanged = false
//                    var sitxCheckedChanged = false
//                    var sitxUrlChanged = false
//                    var sitxGroupChanged = false
//                    //var sitxClientIdChanged = false
//                    var heightFeetChanged: Boolean = false
//                    var heightInchesChanged: Boolean = false
//                    var weightChanged: Boolean = false
//                    var sexChanged: Boolean = false
//                    var bloodTypeChanged: Boolean = false
//                    var allergiesChanged: Boolean = false
//                    var userTypeChanged: Boolean = false
//                    var uniformWaistSizeChanged: Boolean = false
//                    var strideLengthChanged: Boolean = false
//                    var uniformPantsLengthChanged: Boolean = false
//                    var loadoutWeightChanged: Boolean = false
//                    var restingHeartRateChanged: Boolean = false
//                    var companionAppCheckedChanged: Boolean = false
//                    var crmCheckedChanged: Boolean = false
//                    var crmPoorThresholdChanged: Boolean = false
//                    var crmModerateThresholdChanged: Boolean = false
//
//                    if (restingHrHighAlertBpmThreshold != restingHrHighAlertBpmThresholdState.value) {
//                        settingsChanged = true; restingHeartRateHighThresholdChanged = true
//                        restingHrHighAlertBpmThreshold = restingHrHighAlertBpmThresholdState.value ?: restingHrHighAlertBpmThreshold
//                    }
//                    if (highRestingHrWarningThresholdLength != highRestingHrWarningThresholdLengthState.value) {
//                        settingsChanged = true; highRestingHrWarningThresholdLengthChanged = true
//                        highRestingHrWarningThresholdLength = highRestingHrWarningThresholdLengthState.value ?: highRestingHrWarningThresholdLength
//                    }
//                    if (highRestingHrAlertThresholdLength != highRestingHrAlertThresholdLengthState.value) {
//                        settingsChanged = true; highRestingHrAlertThresholdLengthChanged = true
//                        highRestingHrAlertThresholdLength = highRestingHrAlertThresholdLengthState.value ?: highRestingHrAlertThresholdLength
//                    }
//                    if (restingHrLowAlertBpmThreshold != restingHrLowAlertBpmThresholdState.value) {
//                        settingsChanged = true; restingHeartRateLowThresholdChanged = true
//                        restingHrLowAlertBpmThreshold = restingHrLowAlertBpmThresholdState.value ?: restingHrLowAlertBpmThreshold
//                    }
//                    if (lowRestingHrWarningThresholdLength != lowRestingHrWarningThresholdLengthState.value) {
//                        settingsChanged = true; lowRestingHrWarningThresholdLengthChanged = true
//                        lowRestingHrWarningThresholdLength = lowRestingHrWarningThresholdLengthState.value ?: lowRestingHrWarningThresholdLength
//                    }
//                    if (lowRestingHrAlertThresholdLength != lowRestingHrAlertThresholdLengthState.value) {
//                        settingsChanged = true; lowRestingHrAlertThresholdLengthChanged = true
//                        lowRestingHrAlertThresholdLength = lowRestingHrAlertThresholdLengthState.value ?: lowRestingHrAlertThresholdLength
//                    }
//                    if (exertionThresholdWarning != exertionThresholdWarningState.value) {
//                        settingsChanged = true; exertionWarningThresholdChanged = true
//                        exertionThresholdWarning = exertionThresholdWarningState.value ?: exertionThresholdWarning
//                    }
//                    if (exertionWarningThresholdLength != exertionWarningThresholdLengthState.value) {
//                        settingsChanged = true; exertionWarningThresholdLengthChanged = true
//                        exertionWarningThresholdLength = exertionWarningThresholdLengthState.value ?: exertionWarningThresholdLength
//                    }
//                    if (exertionThresholdAlert != exertionThresholdAlertState.value) {
//                        settingsChanged = true; exertionAlertThresholdChanged = true
//                        exertionThresholdAlert = exertionThresholdAlertState.value ?: exertionThresholdAlert
//                    }
//                    if (exertionAlertThresholdLength != exertionAlertThresholdLengthState.value) {
//                        settingsChanged = true; exertionAlertThresholdLengthChanged = true
//                        exertionAlertThresholdLength = exertionAlertThresholdLengthState.value ?: exertionAlertThresholdLength
//                    }
//                    if (birthYearValue != birthYearState.value) {
//                        settingsChanged = true; birthYearChanged = true
//                        birthYearValue = birthYearState.value ?: birthYearValue
//                    }
//                    if (heightFeetValue != heightFeetState.value) {
//                        settingsChanged = true; heightFeetChanged = true
//                        heightFeetValue = heightFeetState.value ?: heightFeetValue
//                    }
//                    if (heightInchesValue != heightInchesState.value) {
//                        settingsChanged = true; heightInchesChanged = true
//                        heightInchesValue = heightInchesState.value ?: heightInchesValue
//                    }
//                    if (weightValue != weightState.value) {
//                        settingsChanged = true; weightChanged = true
//                        weightValue = weightState.value ?: weightValue
//                    }
//                    if (sexValue != sexState.value) {
//                        settingsChanged = true; sexChanged = true
//                        sexValue = sexState.value ?: sexValue
//                    }
//                    if (bloodTypeValue != bloodTypeState.value) {
//                        settingsChanged = true; bloodTypeChanged = true
//                        bloodTypeValue = bloodTypeState.value ?: bloodTypeValue
//                    }
////                    if (allergyList != allergyListState.value) {
////                        settingsChanged = true; allergiesChanged = true
////                        allergyList = allergyListState.value ?: allergyList
////                    }
//                    if (userTypeValue != userTypeState.value) {
//                        settingsChanged = true; userTypeChanged = true
//                        userTypeValue = userTypeState.value ?: userTypeValue
//                    }
//                    if (restingHeartRate != restingHeartRateState.value) {
//                        settingsChanged = true; restingHeartRateChanged = true
//                        restingHeartRate = restingHeartRateState.value ?: restingHeartRate
//                    }
//                    if (uniformWaistSize != uniformWaistSizeState.value) {
//                        settingsChanged = true; uniformWaistSizeChanged = true
//                        uniformWaistSize = uniformWaistSizeState.value ?: uniformWaistSize
//                    }
//                    if (strideLength != strideLengthState.value) {
//                        settingsChanged = true; strideLengthChanged = true
//                        strideLength = strideLengthState.value ?: strideLength
//                    }
//                    if (uniformPantsLength != uniformPantsLengthState.value) {
//                        settingsChanged = true; uniformPantsLengthChanged = true
//                        uniformPantsLength = uniformPantsLengthState.value ?: uniformPantsLength
//                    }
//                    if (loadoutWeight != loadoutWeightState.value) {
//                        settingsChanged = true; loadoutWeightChanged = true
//                        loadoutWeight = loadoutWeightState.value ?: loadoutWeight
//                    }
//
//                    if (enableCompanionAppService != enableCompanionAppServiceSettingsState.value) {
//                        settingsChanged = true; companionAppCheckedChanged = true
//                        enableCompanionAppService =
//                            enableCompanionAppServiceSettingsState.value ?: enableCompanionAppService
//                        runBlocking {
//                            // keep SettingsState in sync with whatever we ended up using
//                            enableCompanionAppServiceSettingsState.postValue(enableCompanionAppService)
//                        }
//                    }
//                    val bloodhoundSettingsChanges = applyStagedBloodhoundSettingsValues()
//                    if (bloodhoundSettingsChanges.anyChanged) {
//                        settingsChanged = true
//                    }
//                    bloodhoundProximityVibrationChanged =
//                        bloodhoundSettingsChanges.proximityVibrationChanged
//                    bloodhoundProximityRadiusChanged =
//                        bloodhoundSettingsChanges.proximityRadiusChanged
//                    bloodhoundProximityIntensityChanged =
//                        bloodhoundSettingsChanges.proximityIntensityChanged
//
//                    if (enableDynamicReportingStrategy != enableDynamicReportingStrategySettingsState.value) {
//                        settingsChanged = true; dynamicReportingChanged = true
//                        enableDynamicReportingStrategy = enableDynamicReportingStrategySettingsState.value ?: enableDynamicReportingStrategy
//                        runBlocking {
//                            enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategy)
//                        }
//                    }
//
//                    if (constantReportingInterval != constantReportingIntervalState.value) {
//                        settingsChanged = true; constantReportingIntervalChanged = true
//                        constantReportingInterval = constantReportingIntervalState.value ?: constantReportingInterval
//                    }
//
//                    if (stationaryReportingInterval != stationaryReportingIntervalState.value) {
//                        settingsChanged = true; stationaryReportingIntervalChanged = true
//                        stationaryReportingInterval = stationaryReportingIntervalState.value ?: stationaryReportingInterval
//                    }
//
//                    if (onFootReportingInterval != onFootReportingIntervalState.value) {
//                        settingsChanged = true; onFootReportingIntervalChanged = true
//                        onFootReportingInterval = onFootReportingIntervalState.value ?: onFootReportingInterval
//                    }
//
//                    if (vehicleReportingInterval != vehicleReportingIntervalState.value) {
//                        settingsChanged = true; vehicleReportingIntervalChanged = true
//                        vehicleReportingInterval = vehicleReportingIntervalState.value ?: vehicleReportingInterval
//                    }
//
//                    if (whileAlertingReportingInterval != whileAlertingReportingIntervalState.value) {
//                        settingsChanged = true; whileAlertingReportingIntervalChanged = true
//                        whileAlertingReportingInterval = whileAlertingReportingIntervalState.value ?: whileAlertingReportingInterval
//                    }
//
//                    if (!callsign.equals(callsignSettingsState.value)) {
//                        settingsChanged = true; callsignChanged = true
//                        callsign = callsignSettingsState.value ?: callsign
//                        requestCallsignComplicationUpdate(context)
//
//                        runBlocking {
//                            callsignLiveDataState.postValue(callsign)
//                        }
//                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
//                    }
//                    if (!team.equals(teamSettingsState.value)) {
//                        settingsChanged = true; teamChanged = true
//                        team = teamSettingsState.value ?: team
//                        var teamIndex = teamEntriesValuesList.ltEntries.indexOf(team)
//                        // This cannot happen, but still...
//                        if (teamIndex == -1) {
//                            Log.e(TAG, "ERROR SettingsScreen: teamIndex is -1. Setting team entry and value to that of index 0")
//                            teamIndex = 0
//                            team = teamEntriesValuesList.ltEntries[0]
//                            teamSettingsState.postValue(team)
//                        }
//                        teamValue = teamEntriesValuesList.ltValues[teamIndex]
//                        runBlocking {
//                            teamState.postValue(team)
//                        }
//                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
//                    }
//                    if (!role.equals(roleSettingsState.value)) {
//                        settingsChanged = true; roleChanged = true
//                        role = roleSettingsState.value ?: role
////                        var roleIndex = roleEntriesValuesList.lrEntries.indexOf(role)
////                        // This cannot happen, but still...
////                        if (roleIndex == -1) {
////                            Log.e(TAG, "ERROR SettingsScreen: roleIndex is -1. Setting role entry and value to that of index 0")
////                            roleIndex = 0
////                            role = roleEntriesValuesList.lrEntries[0]
////                            roleSettingsState.postValue(role)
////                        }
////                        roleValue = roleEntriesValuesList.lrValues[roleIndex]
//                        runBlocking {
//                            roleState.postValue(role)
//                        }
//                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
//                    }
//                    if (!category.equals(categorySettingsState.value)) {
//                        settingsChanged = true; categoryChanged = true; roleChanged = true
//                        role = roleSettingsState.value ?: role
//                        category = categorySettingsState.value ?: category
//
//                        runBlocking {
//                            roleState.postValue(role)
//                        }
//                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
//                    }
//
//                    if ((saveBattOnWifiString != saveBattOnWifiStringState.value) || (wifiSsidList != wifiSsidListState.value)) {
//                        settingsChanged = true; saveBattOnWifiChanged = true
//                        wifiSsidList = wifiSsidListState.value ?: wifiSsidList
//                        saveBattOnWifiString = saveBattOnWifiStringState.value ?: saveBattOnWifiString
//                        enableWifiBatteryOptimization = enableWifiBatteryOptimizationState.value ?: enableWifiBatteryOptimization
//                        enableAllWifiBatteryOptimization = enableAllWifiBatteryOptimizationState.value ?: enableAllWifiBatteryOptimization
//                    }
////                            if (!license.equals(licenseSettingsState.value)) {
////                                settingsChanged = true; licenseChanged = true
////                                license = licenseSettingsState.value ?: license
////                                licenseLiveDataState.postValue(license)
////                            }
//
//                    if (takServerList != takServerListState.value) {
//                        settingsChanged = true; takServerListChanged = true
//                        takServerList = takServerListState.value ?: takServerList
//                    }
//                    Log.d(TAG, "takServerList: $takServerList")
//                    Log.d(TAG, "takServerListState: " + takServerListState.value)
//                    Log.d(TAG, "takServerListChanged: $takServerListChanged")
//
//                    if (enableMulticastOnWifi != enableMulticastSettingsState.value) {
//                        settingsChanged = true; enableMulticastChanged = true
//                        enableMulticastOnWifi = enableMulticastSettingsState.value ?: enableMulticastOnWifi
//                    }
//                    if (multicastAddress != multicastAddressSettingsState.value) {
//                        settingsChanged = true; multicastAddressChanged = true
//                        multicastAddress = multicastAddressSettingsState.value ?: multicastAddress
//                    }
//                    if (multicastProtocol != multicastProtocolSettingsState.value) {
//                        settingsChanged = true; multicastOutputProtocolChanged = true
//                        multicastProtocol = multicastProtocolSettingsState.value ?: multicastProtocol
//                    }
//                    if (multicastPort != multicastPortSettingsState.value) {
//                        settingsChanged = true; multicastServerPortChanged = true
//                        multicastPort = multicastPortSettingsState.value ?: multicastPort
//                    }
//                    if (enableTabletCommandEndpoint != enableTabletCommandEndpointSettingsState.value) {
//                        settingsChanged = true; enableTabletCommandChanged = true
//                        enableTabletCommandEndpoint = enableTabletCommandEndpointSettingsState.value ?: enableTabletCommandEndpoint
//                    }
//                    if (tabletCommandEndpoint != tabletCommandEndpointSettingsState.value) {
//                        settingsChanged = true; tabletCommandEndpointChanged = true
//                        tabletCommandEndpoint = tabletCommandEndpointSettingsState.value ?: tabletCommandEndpoint
//                    }
//                    if (tabletCommandApikey != tabletCommandApikeySettingsState.value) {
//                        settingsChanged = true; tabletCommandAPIKeyChanged = true
//                        tabletCommandApikey = tabletCommandApikeySettingsState.value ?: tabletCommandApikey
//                    }
//                    if (enableEsriEndpoint != enableEsriEndpointSettingsState.value) {
//                        settingsChanged = true; enableEsriChanged = true
//                        enableEsriEndpoint = enableEsriEndpointSettingsState.value ?: enableEsriEndpoint
//                    }
//                    if (esriEndpoint != esriEndpointSettingsState.value) {
//                        settingsChanged = true; esriEndpointChanged = true
//                        esriEndpoint = esriEndpointSettingsState.value ?: esriEndpoint
//                    }
//
//                    if (enableLocationService != enableLocationServiceSettingsState.value) {
//                        settingsChanged = true; locationCheckedChanged = true
//                        enableLocationService = enableLocationServiceSettingsState.value ?: enableLocationService
//                        requestStatusIconsComplicationUpdate(context)
//                        runBlocking {
//                            // This is in case enableLocationService above is NOT set to enableLocationServiceSettingsState.value
//                            enableLocationServiceSettingsState.postValue(enableLocationService)
//                        }
//                    }
//
//                    if (enableDittoService != enableDittoServiceSettingsState.value) {
//                        settingsChanged = true; dittoCheckedChanged = true
//                        enableDittoService = enableDittoServiceSettingsState.value ?: enableDittoService
//                        runBlocking {
//                            enableDittoServiceSettingsState.postValue(enableDittoService)
//                        }
//                    }
//
//                    if (enableSitxService != enableSitxServiceSettingsState.value) {
//                        settingsChanged = true; sitxCheckedChanged = true
//                        enableSitxService = enableSitxServiceSettingsState.value ?: enableSitxService
//                        runBlocking {
//                            enableSitxServiceSettingsState.postValue(enableSitxService)
//                        }
//                    }
//                    if (sitxTakUrl != sitxTakUrlSettingsState.value) {
//                        settingsChanged = true; sitxUrlChanged = true
//                        sitxTakUrl = sitxTakUrlSettingsState.value ?: sitxTakUrl
//                    }
//                    if (sitxTakGroup != sitxTakGroupSettingsState.value) {
//                        settingsChanged = true; sitxGroupChanged = true
//                        sitxTakGroup = sitxTakGroupSettingsState.value ?: sitxTakGroup
//                    }
//                    /*
//                    if (sitxTakClientId != sitxTakClientIdSettingsState.value) {
//                        settingsChanged = true; sitxClientIdChanged = true
//                        sitxTakClientId = sitxTakClientIdSettingsState.value ?: sitxTakClientId
//                    } */
//
//                    if (enableChatService != enableChatServiceSettingsState.value) {
//                        settingsChanged = true; chatCheckedChanged = true
//                        enableChatService = enableChatServiceSettingsState.value ?: enableChatService
//                        runBlocking {
//                            enableChatServiceSettingsState.postValue(enableChatService)
//                        }
//                        //restartTAKConnectService()
//                        //TODO: Find out if we actually need to restart the tak connect service (manager should handle)
//                        requestChatComplicationUpdate(context)
//
//                    }
//                    if (enableCrmService != enableCrmServiceSettingsState.value) {
//                        settingsChanged = true
//                        crmCheckedChanged = true
//
//                        enableCrmService = enableCrmServiceSettingsState.value ?: enableCrmService
//
//                        runBlocking {
//                            enableCrmServiceSettingsState.postValue(enableCrmService)
//                        }
//                    }
//
//                    if (crmPoorMaxThreshold != crmPoorMaxThresholdSettingsState.value) {
//                        settingsChanged = true
//                        crmPoorThresholdChanged = true
//
//                        crmPoorMaxThreshold =
//                            crmPoorMaxThresholdSettingsState.value ?: crmPoorMaxThreshold
//
//                        runBlocking {
//                            crmPoorMaxThresholdSettingsState.postValue(crmPoorMaxThreshold)
//                        }
//                    }
//
//                    if (crmModerateMaxThreshold != crmModerateMaxThresholdSettingsState.value) {
//                        settingsChanged = true
//                        crmModerateThresholdChanged = true
//
//                        crmModerateMaxThreshold =
//                            crmModerateMaxThresholdSettingsState.value ?: crmModerateMaxThreshold
//
//                        runBlocking {
//                            crmModerateMaxThresholdSettingsState.postValue(crmModerateMaxThreshold)
//                        }
//                    }
//                    if (enableImmersionAlerts != enableImmersionAlertsSettingsState.value) {
//                        settingsChanged = true; immersionAlertsChanged = true
//                        enableImmersionAlerts = enableImmersionAlertsSettingsState.value ?: enableImmersionAlerts
//                        runBlocking {
//                            enableImmersionAlertsSettingsState.postValue(enableImmersionAlerts)
//                        }
//                    }
//                    if (enableLowAtmPressureAlert != enableLowAtmPressureAlertSettingsState.value) {
//                        settingsChanged = true; lowPressureAlertChanged = true
//                        enableLowAtmPressureAlert = enableLowAtmPressureAlertSettingsState.value ?: enableLowAtmPressureAlert
//                        runBlocking {
//                            enableLowAtmPressureAlertSettingsState.postValue(enableLowAtmPressureAlert)
//                        }
//                    }
//                    if (lowAtmPressureAlertThreshold != lowAtmPressureAlertThresholdSettingsState.value) {
//                        settingsChanged = true; lowPressureAlertThresholdChanged = true
//                        lowAtmPressureAlertThreshold = lowAtmPressureAlertThresholdSettingsState.value ?: lowAtmPressureAlertThreshold
//                        runBlocking {
//                            lowAtmPressureAlertThresholdSettingsState.postValue(lowAtmPressureAlertThreshold)
//                        }
//                    }
//                    if (enableHighAtmPressureAlert != enableHighAtmPressureAlertSettingsState.value) {
//                        settingsChanged = true; highPressureAlertChanged = true
//                        enableHighAtmPressureAlert = enableHighAtmPressureAlertSettingsState.value ?: enableHighAtmPressureAlert
//                        runBlocking {
//                            enableHighAtmPressureAlertSettingsState.postValue(enableHighAtmPressureAlert)
//                        }
//                    }
//                    if (highAtmPressureAlertThreshold != highAtmPressureAlertThresholdSettingsState.value) {
//                        settingsChanged = true; highPressureAlertThresholdChanged = true
//                        highAtmPressureAlertThreshold = highAtmPressureAlertThresholdSettingsState.value ?: highAtmPressureAlertThreshold
//                        runBlocking {
//                            highAtmPressureAlertThresholdSettingsState.postValue(highAtmPressureAlertThreshold)
//                        }
//                    }
//
//                    if (enableOnnxGunshotService != enableOnnxGunshotServiceSettingsState.value) {
//                        settingsChanged = true; onnxGunDetectChanged = true
//                        enableOnnxGunshotService = enableOnnxGunshotServiceSettingsState.value ?: enableOnnxGunshotService
//                        runBlocking {
//                            enableOnnxGunshotServiceSettingsState.postValue(enableOnnxGunshotService)
//                        }
//                    }
//                    if (onnxGunshotTriggerThresholdPercent != onnxGunshotTriggerThresholdPercentSettingsState.value) {
//                        settingsChanged = true; onnxGunThresholdChanged = true
//                        onnxGunshotTriggerThresholdPercent =
//                            onnxGunshotTriggerThresholdPercentSettingsState.value ?: onnxGunshotTriggerThresholdPercent
//                        runBlocking {
//                            onnxGunshotTriggerThresholdPercentSettingsState.postValue(onnxGunshotTriggerThresholdPercent)
//                        }
//                    }
//
//                    if (enableOnnxUasService != enableOnnxUasServiceSettingsState.value) {
//                        settingsChanged = true; onnxUasDetectChanged = true
//                        enableOnnxUasService = enableOnnxUasServiceSettingsState.value ?: enableOnnxUasService
//                        runBlocking {
//                            enableOnnxUasServiceSettingsState.postValue(enableOnnxUasService)
//                        }
//                    }
//                    if (onnxUasTriggerThresholdPercent != onnxUasTriggerThresholdPercentSettingsState.value) {
//                        settingsChanged = true; onnxUasThresholdChanged = true
//                        onnxUasTriggerThresholdPercent =
//                            onnxUasTriggerThresholdPercentSettingsState.value ?: onnxUasTriggerThresholdPercent
//                        runBlocking {
//                            onnxUasTriggerThresholdPercentSettingsState.postValue(onnxUasTriggerThresholdPercent)
//                        }
//                    }
//
//                    if (enableBetaFeatures != enableBetaFeaturesSettingsState.value) {
//                        settingsChanged = true; betaFeaturesChanged = true
//                        enableBetaFeatures = enableBetaFeaturesSettingsState.value ?: enableBetaFeatures
//                        runBlocking {
//                            enableBetaFeaturesSettingsState.postValue(enableBetaFeatures)
//                        }
//                    }
//
//                    if (enableBatteryAlerts != enableBatteryAlertsSettingsState.value) {
//                        settingsChanged = true; batteryAlertsCheckedChanged = true
//                        enableBatteryAlerts = enableBatteryAlertsSettingsState.value
//                            ?: enableBatteryAlerts
//                        runBlocking {
//                            enableBatteryAlertsSettingsState.postValue(enableBatteryAlerts)
//                        }
//                    }
//
//                    if (enableBloodhoundCompassService != enableBloodhoundCompassServiceSettingsState.value) {
//                        settingsChanged = true; bloodhoundCompassCheckedChanged = true
//                        enableBloodhoundCompassService = enableBloodhoundCompassServiceSettingsState.value ?: enableBloodhoundCompassService
//                        runBlocking {
//                            enableBloodhoundCompassServiceSettingsState.postValue(enableBloodhoundCompassService)
//                        }
//                        requestBloodhoundComplicationUpdate(context)
//                    }
//
//                    if (enableHailService != enableHailServiceSettingsState.value) {
//                        settingsChanged = true; hailCheckedChanged = true
//                        enableHailService = enableHailServiceSettingsState.value ?: enableHailService
//                        runBlocking {
//                            // This is in case enableLocationService above is NOT set to enableLocationServiceSettingsState.value
//                            enableHailServiceSettingsState.postValue(enableHailService)
//                        }
//                    }
//
//                    if (enableBatdokCot != enableBatdokCotSettingsState.value) {
//                        settingsChanged = true; batdokCheckedChanged = true
//                        enableBatdokCot = enableBatdokCotSettingsState.value ?: enableBatdokCot
//                        runBlocking {
//                            enableBatdokCotSettingsState.postValue(enableBatdokCot)
//                        }
//                    }
//
//                    if (enableHailService != enableHailServiceSettingsState.value) {
//                        settingsChanged = true; hailCheckedChanged = true
//                        enableHailService = enableHailServiceSettingsState.value ?: enableHailService
//                        runBlocking {
//                            // This is in case enableLocationService above is NOT set to enableLocationServiceSettingsState.value
//                            enableHailServiceSettingsState.postValue(enableHailService)
//                        }
//                    }
//
//                    if (enableSmsForAlerts != enableSmsForAlertsSettingsState.value) {
//                        settingsChanged = true; smsAlertsCheckedChanged = true
//                        enableSmsForAlerts = enableSmsForAlertsSettingsState.value ?: enableSmsForAlerts
//                        if (enableSmsForAlerts) {
//                            Log.d(TAG, "Trying enable start sms alerts")
//                        } else {
//                            Log.d(TAG, "Trying disable start sms alerts")
//                        }
//                        runBlocking {
//                            enableSmsForAlertsSettingsState.postValue(enableSmsForAlerts)
//                        }
//                    }
//
//                    if (smsNumbersListString != smsNumbersListStringSettingsState.value) {
//                        settingsChanged = true; smsNumbersChanged = true
//                        smsNumbersListString =
//                            smsNumbersListStringSettingsState.value ?: smsNumbersListString
//                    }
//
//                    if (enablePhysiologicalServices != enablePhysiologicalServicesSettingsState.value) {
//                        settingsChanged = true; physioCheckedChanged = true
//                        enablePhysiologicalServices = enablePhysiologicalServicesSettingsState.value ?: enablePhysiologicalServices
//                        runBlocking {
//                            enablePhysiologicalServicesSettingsState.postValue(enablePhysiologicalServices)
//                        }
//                        requestPhysioStatusAndAlertingComplicationUpdate(context)
//
//                    }
//
//                    if (enablePhysiologicalAlerts != enablePhysiologicalAlertsSettingState.value) {
//                        settingsChanged = true; physiologicalAlertsCheckedChanged = true
//                        enablePhysiologicalAlerts = enablePhysiologicalAlertsSettingState.value ?: enablePhysiologicalAlerts
//                        runBlocking {
//                            enablePhysiologicalAlertsSettingState.postValue(enablePhysiologicalAlerts)
//                        }
//                        requestPhysioStatusAndAlertingComplicationUpdate(context)
//                    }
//
//                    if (settingsChanged) {
//                        CoroutineScope(Dispatchers.Main).launch {
//                            if (callsignChanged) {
//                                Log.d(TAG, "Storing Callsign ($callsign)")
//                                dataRepository.storeCallsign(callsign)
//                            }
//                            if (teamChanged) {
//                                Log.d(TAG, "Storing Team ($team)")
//                                dataRepository.storeTeam(team)
//                            }
//                            if (roleChanged) {
//                                Log.d(TAG, "Storing Role ($role)")
//                                dataRepository.storeRole(role)
//                                dataRepository.storeRoleCategory(category)
//                            }
//                            if (categoryChanged) {
//                                Log.d(TAG, "Category Changed. Storing Category & Role ($role)")
//                                dataRepository.storeRole(role)
//                                dataRepository.storeRoleCategory(category)
//                            }
////                                    if(licenseChanged) {
////                                        dataRepository.storeLicense(license)
////
////                                    }
//                            if (crmCheckedChanged) {
//                                Log.d(TAG, "Storing enableCrmService $enableCrmService")
//                                dataRepository.storeEnableCrmService(enableCrmService)
//                            }
//
//                            if (crmPoorThresholdChanged || crmModerateThresholdChanged) {
//                                Log.d(
//                                    TAG,
//                                    "Storing CRM thresholds poorMax=$crmPoorMaxThreshold moderateMax=$crmModerateMaxThreshold"
//                                )
//
//                                dataRepository.storeCrmThresholdRanges(
//                                    poorMax = crmPoorMaxThreshold,
//                                    moderateMax = crmModerateMaxThreshold,
//                                )
//                            }
//                             if (birthYearChanged) {
//                                 Log.d(TAG, "Storing Birth Year ($birthYearValue)")
//                                 dataRepository.storeYearOfBirth(birthYearValue)
//                             }
//                             if (heightFeetChanged) {
//                                 Log.d(TAG, "Storing Height(Feet) ($heightFeetValue)")
//                                 dataRepository.storePhysiologicalMetricHeightFeet(heightFeetValue)
//                             }
//                             if (heightInchesChanged) {
//                                 Log.d(TAG, "Storing Height(Inches) ($heightInchesValue)")
//                                 dataRepository.storePhysiologicalMetricHeightInches(heightInchesValue)
//                             }
//                             if (weightChanged) {
//                                 Log.d(TAG, "Storing Weight ($weightValue)")
//                                 dataRepository.storePhysiologicalMetricWeight(weightValue)
//                             }
//                             if (sexChanged) {
//                                 Log.d(TAG, "Storing Sex ($sexValue)")
//                                 dataRepository.storePhysiologicalMetricSex(sexValue)
//                             }
//                             if (bloodTypeChanged) {
//                                 Log.d(TAG, "Storing Blood Type ($bloodTypeValue)")
//                                 dataRepository.storePhysiologicalMetricBloodType(bloodTypeValue)
//                             }
//                             if (allergiesChanged) {
//                                 Log.d(TAG, "Storing Allergy List ($allergyList)")
//                                 dataRepository.storePhysiologicalMetricAllergyList(allergyList)
//                             }
//                             if (userTypeChanged) {
//                                 Log.d(TAG, "Storing User Type ($userTypeValue)")
//                                 dataRepository.storePhysiologicalMetricUserType(userTypeValue)
//                             }
//                             if (restingHeartRateChanged) {
//                                 Log.d(TAG, "Storing Resting Heart Rate ($restingHeartRate)")
//                                 dataRepository.storeRestingHeartRate(restingHeartRate)
//                             }
//                             if (uniformWaistSizeChanged) {
//                                 Log.d(TAG, "Storing Uniform Waist Size ($uniformWaistSize)")
//                                 dataRepository.storePhysiologicalMetricUniformWaistSize(uniformWaistSize)
//                             }
//                             if (strideLengthChanged) {
//                                 Log.d(TAG, "Storing Stride Length ($strideLength)")
//                                 dataRepository.storePhysiologicalMetricStrideLength(strideLength)
//                             }
//                             if (uniformPantsLengthChanged) {
//                                 Log.d(TAG, "Storing Uniform Pants Length ($uniformPantsLength)")
//                                 dataRepository.storePhysiologicalMetricUniformPantsLength(uniformPantsLength)
//                             }
//                             if (loadoutWeightChanged) {
//                                 Log.d(TAG, "Storing Loadout Weight ($loadoutWeight)")
//                                 dataRepository.storePhysiologicalMetricLoadoutWeight(loadoutWeight)
//                             }
//                             if (dynamicReportingChanged){
//                                 dataRepository.storeEnableDynamicReportingStrategy(enableDynamicReportingStrategy)
//                             }
//                             if (constantReportingIntervalChanged){
//                                 dataRepository.storeConstantReportingInterval(constantReportingInterval)
//                             }
//                             if (stationaryReportingIntervalChanged){
//                                 dataRepository.storeStationaryReportingInterval(stationaryReportingInterval)
//                             }
//                             if (onFootReportingIntervalChanged){
//                                 dataRepository.storeOnFootReportingInterval(onFootReportingInterval)
//                             }
//                             if (vehicleReportingIntervalChanged){
//                                 dataRepository.storeVehicleReportingInterval(vehicleReportingInterval)
//                             }
//                             if (whileAlertingReportingIntervalChanged){
//                                 dataRepository.storeWhileAlertingReportingInterval(whileAlertingReportingInterval)
//                             }
//                             if (saveBattOnWifiChanged) {
//                                  Log.d(TAG, "Storing batt optimization on wifi: enable($enableWifiBatteryOptimization), allWifi($enableAllWifiBatteryOptimization), wifiSsidList=($wifiSsidList)")
//                                  runBlocking {
//                                      dataRepository.storeEnableWifiBatteryOptimization(
//                                          enableWifiBatteryOptimization
//                                      )
//                                      dataRepository.storeEnableAllWifiBatteryOptimization(
//                                          enableAllWifiBatteryOptimization
//                                      )
//                                      dataRepository.storeWifiSsidList(wifiSsidList)
//                                  }
//                                  EventBus.getDefault().post(MessageEvent(OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED, 0))
//                             }
//                             if (takServerListChanged) {
//                                 Log.d(TAG, "Storing updated TAK Server List: $takServerList")
//                                 dataRepository.storeTakServerList(takServerList)
//                             }
//
//                             if(enableMulticastChanged){
//                                 Log.d(TAG, "Storing updated Enable Multicast: $enableMulticastOnWifi")
//                                 dataRepository.storeEnableMulticast(enableMulticastOnWifi)
//                             }
//                             if (multicastAddressChanged) {
//                                 Log.d(TAG, "Storing updated Multicast Address: $multicastAddress")
//                                 dataRepository.storeMulticastAddress(multicastAddress)
//                             }
//                             if (multicastOutputProtocolChanged) {
//                                 Log.d(TAG, "Storing updated Multicast Output Protocol: $multicastProtocol")
//                                 dataRepository.storeMulticastProtocol(multicastProtocol)
//                             }
//                             if (multicastServerPortChanged) {
//                                 Log.d(TAG, "Storing updated Multicast Server Port: $multicastPort")
//                                 dataRepository.storeMulticastPort(multicastPort)
//                             }
//                             if (enableTabletCommandChanged){
//                                 Log.d(TAG, "Storing updated Enable Tablet Command: $enableTabletCommandEndpoint")
//                                 dataRepository.storeEnableTabletCommandEndpoint(enableTabletCommandEndpoint)
//                             }
//                             if (tabletCommandEndpointChanged) {
//                                 Log.d(TAG, "Storing updated Tablet Command Endpoint: $tabletCommandEndpoint")
//                                 dataRepository.storeTabletCommandEndpoint(tabletCommandEndpoint)
//                             }
//                             if (tabletCommandAPIKeyChanged) {
//                                 Log.d(TAG, "Storing updated Tablet Command API Key: $tabletCommandApikey")
//                                 dataRepository.storeTabletCommandApikey(tabletCommandApikey)
//                             }
//                             if (enableEsriChanged){
//                                 Log.d(TAG, "Storing updated Enable Esri: $enableEsriEndpoint")
//                                 dataRepository.storeEnableEsriEndpoint(enableEsriEndpoint)
//                             }
//                             if (esriEndpointChanged) {
//                                 Log.d(TAG, "Storing updated ESRI Endpoint: $esriEndpoint")
//                                 dataRepository.storeEsriEndpoint(esriEndpoint)
//                             }
//                             if (locationCheckedChanged) {
//                                 Log.d(TAG, "Storing enableLocationService $enableLocationService")
//                                 dataRepository.storeEnableLocationService(enableLocationService)
//                             }
//                             if (dittoCheckedChanged) {
//                                 Log.d(TAG, "Storing enableDittoService $enableDittoService")
//                                 dataRepository.storeEnableDittoService(enableDittoService)
//                             }
//                             if (sitxCheckedChanged) {
//                                 Log.d(TAG, "Storing enableSitxService $enableSitxService")
//                                 dataRepository.storeEnableSitxService(enableSitxService)
//                             }
//                             if (sitxUrlChanged) {
//                                 Log.d(TAG, "Storing Sitx URL: $sitxTakUrl")
//                                 sitxRepo.setBaseUrl(sitxTakUrl)
//                                 // TODO Senni: Need to restart the device authorization
//                             }
//                            if (sitxGroupChanged) {
//                                Log.d(TAG, "Storing Sitx Group: $sitxTakGroup")
//                                dataRepository.setSelectedSitxGroup(sitxTakGroup)
//                            }
//                            /*
//                             if (sitxClientIdChanged) {
//                                 Log.d(TAG, "Storing Sitx Client ID: $sitxTakClientId")
//                                 sitxRepo.setClientId(sitxTakClientId)
//                                 // TODO Senni: Need to restart the device authorization
//                             }
//                             */
//
//                             if (companionAppCheckedChanged) {
//                                 Log.d(TAG, "Storing enableCompanionAppService $enableCompanionAppService")
//                                 dataRepository.storeEnableCompanionAppService(enableCompanionAppService)
//                             }
//                             if (chatCheckedChanged) {
//                                 Log.d(TAG, "Storing enableChatService $enableChatService")
//                                 dataRepository.storeEnableChatService(enableChatService)
//                             }
//                             if (onnxGunDetectChanged) {
//                                 Log.d(TAG, "Storing enableOnnxGunshotService $enableOnnxGunshotService")
//                                 dataRepository.storeEnableOnnxGunshotService(enableOnnxGunshotService)
//                             }
//                             if (betaFeaturesChanged) {
//                                 Log.d(TAG, "Storing enableBetaFeatures $enableBetaFeatures")
//                                 dataRepository.storeEnableBetaFeatures(enableBetaFeatures)
//                             }
//                             if (batteryAlertsCheckedChanged) {
//                                 Log.d(TAG, "Storing enableBatteryAlerts $enableBatteryAlerts")
//                                 dataRepository.storeEnableBatteryAlerts(enableBatteryAlerts)
//                             }
//                             if (bloodhoundCompassCheckedChanged) {
//                                 Log.d(TAG, "Storing enableBloodhoundCompassService $enableBloodhoundCompassService")
//                                 dataRepository.storeEnableBloodhoundCompassService(enableBloodhoundCompassService)
//                             }
//                             if (hailCheckedChanged) {
//                                 Log.d(TAG, "Storing enableHailService $enableHailService")
//                                 dataRepository.storeEnableHailService(enableHailService)
//                             }
//                             if (batdokCheckedChanged) {
//                                 Log.d(TAG, "Storing enableBatdokCot $enableBatdokCot")
//                                 dataRepository.storeEnableBatdokCot(enableBatdokCot)
//                             }
//                             if (smsAlertsCheckedChanged) {
//                                 Log.d(TAG, "Storing enableSmsForAlerts ${enableSmsForAlerts}")
//                                 dataRepository.storeEnableSmsForAlerts(enableSmsForAlerts)
//                             }
//                             if (smsNumbersChanged){
//                                 val smsNumbersListConversion: List<DataRepository.SmsNumber> =
//                                        smsNumbersListString.split(",")
//                                            .map { it.trim() } // Trim whitespace from each SSID
//                                            .filter { it.isNotEmpty() } // Remove any empty values
//                                            .map { DataRepository.SmsNumber(it) }
//                                 Log.d(TAG, "Storing smsNumbersList ${smsNumbersListConversion}")
//
//                                 dataRepository.storeSmsNumbersList(smsNumbersListConversion)
//                                 smsNumbersList = smsNumbersListConversion
//                             }
//
//                             if (physioCheckedChanged) {
//                                 Log.d(TAG, "Storing enablePhysiologicalServices $enablePhysiologicalServices")
//                                 dataRepository.storeEnablePhysiologicalServices(enablePhysiologicalServices)
//                             }
//
//                             if (physiologicalAlertsCheckedChanged) {
//                                 Log.d(TAG, "Storing enablePhysiologicalAlerts $enablePhysiologicalAlerts")
//                                 dataRepository.storeEnablePhysiologicalAlerts(enablePhysiologicalAlerts)
//                                 CepProvider.getOrCreateEngine(context).updatePhysioAlertProfile()
//                                 Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED, if (enablePhysiologicalAlerts) {1} else {0}))}, EVENT_SEND_DELAY_MS)
//                             }
//
//                             if (restingHeartRateHighThresholdChanged) {
//                                 Log.d(TAG, "Storing restingHrHighAlertBpmThreshold $restingHrHighAlertBpmThreshold")
//                                 dataRepository.storeRestingHrHighAlertBpmThreshold(restingHrHighAlertBpmThreshold)
//                             }
//                             if (highRestingHrWarningThresholdLengthChanged) {
//                                 Log.d(TAG, "Storing highRestingHrWarningThresholdLength $highRestingHrWarningThresholdLength")
//                                 dataRepository.storeHighRestingHrWarningThresholdLength(highRestingHrWarningThresholdLength)
//                             }
//                             if (highRestingHrAlertThresholdLengthChanged) {
//                                 Log.d(TAG, "Storing highRestingHrAlertThresholdLength $highRestingHrAlertThresholdLength")
//                                 dataRepository.storeHighRestingHrAlertThresholdLength(highRestingHrAlertThresholdLength)
//                             }
//                             if (restingHeartRateLowThresholdChanged) {
//                                 Log.d(TAG, "Storing hrHighAlertBpmThreshold $restingHrLowAlertBpmThreshold")
//                                 dataRepository.storeRestingHrLowAlertBpmThreshold(restingHrLowAlertBpmThreshold)
//                             }
//                             if (lowRestingHrWarningThresholdLengthChanged) {
//                                 Log.d(TAG, "Storing lowRestingHrWarningThresholdLength $lowRestingHrWarningThresholdLength")
//                                 dataRepository.storeLowRestingHrWarningThresholdLength(lowRestingHrWarningThresholdLength)
//                             }
//                             if (lowRestingHrAlertThresholdLengthChanged) {
//                                 Log.d(TAG, "Storing lowRestingHrAlertThresholdLength $lowRestingHrAlertThresholdLength")
//                                 dataRepository.storeLowRestingHrAlertThresholdLength(lowRestingHrAlertThresholdLength)
//                             }
//                             if (exertionWarningThresholdChanged) {
//                                 Log.d(TAG, "Storing exertionThresholdWarning $exertionThresholdWarning")
//                                 dataRepository.storeExertionThresholdWarning(exertionThresholdWarning)
//                             }
//                             if (exertionWarningThresholdLengthChanged) {
//                                 Log.d(TAG, "Storing exertionWarningThresholdLength $exertionWarningThresholdLength")
//                                 dataRepository.storeExertionWarningThresholdLength(exertionWarningThresholdLength)
//                             }
//                             if (exertionAlertThresholdChanged) {
//                                 Log.d(TAG, "Storing exertionThresholdAlert $exertionThresholdAlert")
//                                 dataRepository.storeExertionThresholdAlert(exertionThresholdAlert)
//                             }
//                             if (exertionAlertThresholdLengthChanged) {
//                                 Log.d(TAG, "Storing exertionAlertThresholdLength $exertionAlertThresholdLength")
//                                 dataRepository.storeExertionAlertThresholdLength(exertionAlertThresholdLength)
//                             }
//
//                            if (restingHeartRateChanged ||
//                                restingHeartRateHighThresholdChanged ||
//                                highRestingHrWarningThresholdLengthChanged ||
//                                highRestingHrAlertThresholdLengthChanged ||
//                                restingHeartRateLowThresholdChanged ||
//                                lowRestingHrWarningThresholdLengthChanged ||
//                                lowRestingHrAlertThresholdLengthChanged ||
//                                exertionWarningThresholdChanged ||
//                                exertionWarningThresholdLengthChanged ||
//                                exertionAlertThresholdChanged ||
//                                exertionAlertThresholdLengthChanged) {
//                                Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(HR_ALERT_THRESHOLDS_CHANGED, if (enablePhysiologicalAlerts) {1} else {0}))}, EVENT_SEND_DELAY_MS)
//                            }
//                            if (restingHeartRateChanged ||
//                                restingHeartRateHighThresholdChanged ||
//                                highRestingHrWarningThresholdLengthChanged ||
//                                highRestingHrAlertThresholdLengthChanged ||
//                                restingHeartRateLowThresholdChanged ||
//                                lowRestingHrWarningThresholdLengthChanged ||
//                                lowRestingHrAlertThresholdLengthChanged) {
//                                CepProvider.getOrCreateEngine(context).updateHrProfile()
//                            }
//                            if (exertionWarningThresholdChanged || exertionAlertThresholdChanged) {
//                                CepProvider.getOrCreateEngine(context).updateHrrProfile()
//                            }
//                            if (highRestingHrWarningThresholdLengthChanged ||
//                                highRestingHrAlertThresholdLengthChanged ||
//                                lowRestingHrWarningThresholdLengthChanged ||
//                                lowRestingHrAlertThresholdLengthChanged ||
//                                exertionWarningThresholdLengthChanged ||
//                                exertionAlertThresholdLengthChanged) {
//                                mainActivity.updateCepSustainOverridesMap()
//                                CepProvider.getOrCreateEngine(context).updateSustainOverrides()
//                            }
//                            Log.d(
//                                TAG,
//                                "Pressure changed flags: " +
//                                        "immersion=$immersionAlertsChanged, " +
//                                        "low=$lowPressureAlertChanged, " +
//                                        "lowThreshold=$lowPressureAlertThresholdChanged, " +
//                                        "high=$highPressureAlertChanged, " +
//                                        "highThreshold=$highPressureAlertThresholdChanged"
//                            )
//                            if (immersionAlertsChanged ||
//                                lowPressureAlertChanged || lowPressureAlertThresholdChanged ||
//                                highPressureAlertChanged || highPressureAlertThresholdChanged) {
//                                dataRepository.storeEnableImmersionAlerts(enableImmersionAlerts)
//                                dataRepository.storeEnableLowAtmPressureAlert(enableLowAtmPressureAlert)
//                                dataRepository.storeLowAtmPressureAlertThreshold(lowAtmPressureAlertThreshold)
//                                dataRepository.storeEnableHighAtmPressureAlert(enableHighAtmPressureAlert)
//                                dataRepository.storeHighAtmPressureAlertThreshold(highAtmPressureAlertThreshold)
//
//                                Log.d(TAG, "Calling updatePressureProfile() after pressure setting change")
//
//                                CepProvider.getOrCreateEngine(context).updatePressureProfile()
//                            }
//                            Log.d(TAG, "Finished saving settings")
//                        }
//                    } else {
//                        Log.v(TAG, "Settings not changed and hence NOT saved")
//                    }
////                        navController.navigate("main_screen") {
////                            popUpTo(("main_screen")) {
////                                inclusive = true
////                            }
////                        }
//                    settingsActivity.finish()
//                    context.startActivity(Intent(context, MainActivity::class.java))
//
//                })
//        },
//        text = stringResource(R.string.callsign_settings)
//    )
//}
//
//@Composable
//fun DebugVersionText(
//    wearTakInfo: DataRepository.WearTakInfo,
//    dataRepository: DataRepository,
//    onDebugToggled: ((Boolean) -> Unit)? = null, // optional hook if you want to do extra stuff
//) {
//    val context = LocalContext.current
//    val coroutineScope = rememberCoroutineScope()
//    val colors = colors
//
//    val devModeEnabled by dataRepository
//        .getEnableWearTakDevMode
//        .collectAsState(false)
//
//    var versionTapCount by remember { mutableStateOf(0) }
//    var lastVersionTapTime by remember { mutableStateOf(0L) }
//    var currentToast by remember { mutableStateOf<Toast?>(null) }
//
//    val tapsRequired = 8
//    val timeoutMs = 1500L   // max gap between taps
//
//    fun showTopToast(message: String) {
//        // Cancel any old toast so they don't queue up
//        currentToast?.cancel()
//
//        val toast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
//        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 50)
//        toast.show()
//        currentToast = toast
//    }
//
//    Column(
//        modifier = Modifier.padding(top = 7.dp),
//        horizontalAlignment = Alignment.CenterHorizontally
//    ) {
//        Text(
//            modifier = Modifier.clickable {
//                val now = System.currentTimeMillis()
//
//                if (now - lastVersionTapTime > timeoutMs) {
//                    versionTapCount = 0
//                }
//
//                versionTapCount++
//                lastVersionTapTime = now
//
//                if (versionTapCount in 5 until tapsRequired) {
//                    val remaining = tapsRequired - versionTapCount
//                    val msg = if (remaining == 1) {
//                        "1 more tap to toggle debug"
//                    } else {
//                        "$remaining more taps to toggle debug"
//                    }
//                    showTopToast(msg)
//                }
//
//                if (versionTapCount >= tapsRequired) {
//                    versionTapCount = 0
//
//                    coroutineScope.launch {
//                        val currentlyEnabled =
//                            dataRepository.getEnableWearTakDevMode.first()
//                        val newValue = !currentlyEnabled
//
//                        dataRepository.storeEnableWearTakDevMode(newValue)
//
//                        Log.d(
//                            TAG,
//                            "~~~ DEBUG MODE ${if (newValue) "ENABLED" else "DISABLED"} ~~~"
//                        )
//
//                        withContext(Dispatchers.Main) {
//                            val toastText = if (newValue) {
//                                "Debug tools enabled (airplane mode off)"
//                            } else {
//                                "Debug tools disabled (airplane mode off)"
//                            }
//                            showTopToast(toastText)
//                        }
//
//                        onDebugToggled?.invoke(newValue)
//                    }
//                }
//            },
//            text = stringResource(
//                R.string.version,
//                wearTakInfo.versionNumber
//            ),
//            fontSize = 11.sp,
//            color = if (devModeEnabled) Color.Red else colors.primary,
//        )
//
//        if (devModeEnabled) {
//            Text(
//                text = "Code ${wearTakInfo.versionCode}",
//                fontSize = 9.sp,
//                color = Color.Red,
//            )
//
//            Text(
//                text = "${wearTakInfo.gitBranch}@${wearTakInfo.gitCommit}",
//                fontSize = 9.sp,
//                color = Color.Red,
//                maxLines = 1,
//            )
//
//            Text(
//                text = wearTakInfo.buildType,
//                fontSize = 9.sp,
//                color = Color.Red,
//            )
//        }
//    }
//}
//
//fun convertSmsNumbersListToStringWithSeparator(smsNumbersList: List<DataRepository.SmsNumber>, separatorStr: String): String {
//    var result = ""
//    if (smsNumbersList.isNotEmpty()) {
//        for (smsNumber in smsNumbersList) {
//            result = if ("" == result) {
//                smsNumber.telephoneNumber
//            } else {
//                "$result$separatorStr${smsNumber.telephoneNumber}"
//            }
//        }
//    }
//    return result
//}
//
//@Composable
//fun ConfirmCancelButtons(
//    onClickCancel: () -> Unit,
//    onClickConfirm: () -> Unit
//) {
//    Box(
//        contentAlignment = Alignment.Center,
//    ) {
//        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//            IconButton(
//                onClick = onClickCancel,
//                modifier = Modifier
//                    .size(50.dp)
//                    .border(1.dp, color = Color.Black, shape = CircleShape),
//                colors = IconButtonDefaults.iconButtonColors(
//                    contentColor = Color.Red,
//                    containerColor = Color.DarkGray,
//                ),
//            ) {
//                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close icon")
//
//            }
//            Spacer(modifier = Modifier.width(20.dp))
//            IconButton(
//                onClick = onClickConfirm,
//                modifier = Modifier
//                    .size(50.dp)
//                    .border(1.dp, color = Color.Black, shape = CircleShape),
//                colors = IconButtonDefaults.iconButtonColors(
//                    contentColor = Color.Black,
//                    containerColor = colors.primary
//                )
//            ) {
//                Icon(
//                    imageVector = Icons.Filled.Check,
//                    contentDescription = "Confirm icon",
//                    tint = Color.Black
//                )
//
//            }
//        }
//    }
//}
//
//@Composable
//fun BackButton(onClick: () -> Unit) {
//    Button(
//        onClick = { onClick() },
//        modifier = Modifier.size(height = 35.dp, width = 65.dp),
//        colors = ButtonDefaults.buttonColors(
//            containerColor = Color.Gray,
//            contentColor = Color.Red
//        ),
//    ) {
//        Icon(
//            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
//            contentDescription = "Go back to Main Settings Screen",
//        )
//    }
//}
//
////@Preview(widthDp = 192, heightDp = 1200, showBackground = true)
////@Composable
////fun SettingsPreview() {
////    var callsignState = MutableLiveData<String>("WEAROS-0")
////    var licenseState = MutableLiveData<String>("AscentLicense")
////
////    SettingsScreen(rememberSwipeDismissableNavController(),callsignState, licenseState, LocalContext.current)
////}
////
////@Preview(widthDp = 192, heightDp = 800, showBackground = true)
////@Composable
////fun PhysioSettingsPreview() {
////
////    PhysiologicalMetricsSetting(rememberSwipeDismissableNavController())
////}
////@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
////@Composable
////fun SexMetricPreview() {
////    var licenseState = MutableLiveData<String>("")
////
////    RadioChoiceSetting(rememberSwipeDismissableNavController(), licenseState, sexOptionsList, "Blood Type", "physiological_metrics_setting_screen")
////}
////@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
////@Composable
////fun RoleSettingPreview() {
////    RoleSetting(rememberSwipeDismissableNavController())
////}
////
////@Preview(widthDp = 192, heightDp = 500, showBackground = true)
////@Composable
////fun AllergyMetricPreview() {
////
////    AllergySetting(rememberSwipeDismissableNavController(),"Allergies")
////}
////
////@Preview(widthDp = 192, heightDp = 800, showBackground = true)
////@Composable
////fun PhysioAlertsPreview() {
////    HeartRateAlertsSetting(rememberSwipeDismissableNavController())
////}
////@Preview(widthDp = 192, heightDp = 600, showBackground = true)
////@Composable
////fun ReportingStratPreview() {
////    ReportingStrategySettings(rememberSwipeDismissableNavController())
////}
////@Preview(widthDp = 192, heightDp = 600, showBackground = true)
////@Composable
////fun ReportingStrategyPreview() {
////    ReportingStrategyDynamicConstantSetting(rememberSwipeDismissableNavController())
////}
////@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
////@Composable
////fun ReportingIntPreview() {
////    ReportingStrategyIntervalSettings(rememberSwipeDismissableNavController(), constantReportingIntervalSettingsState, "Static Reporting Interval", "0")
////}
//
//@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
//@Composable
//fun PopupBoxPreview() {
//    var showPopup = remember { mutableStateOf(true) }
//    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
//        PopupBox(
//            showPopup,
//            "HelloWorld",
//            description = "Description text belongs here and describes the plugin",
//            versionNumber = "v5.5.1.8 (7f381e4d) - [5.5.1] (1761249075)",
//            compatibility = PluginCompatibilityStatus.UNKNOWN,
//            loadingStatus = PluginLoadingStatus.ERROR,
//            errorMessage = "This is an error message you goofed"
//        )
//    }
//}
//
////@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
////@Composable
////fun PluginScreenPreview() {
////    PluginsScreen(rememberNavController())
////}
