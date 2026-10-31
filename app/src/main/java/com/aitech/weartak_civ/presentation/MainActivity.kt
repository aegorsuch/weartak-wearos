/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getString
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.DEFAULT_BIRTH_YEAR
import com.aitech.weartak_civ.DEFAULT_ON_FOOT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_CONSTANT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.DEFAULT_RESTING_HR
import com.aitech.weartak_civ.DEFAULT_STATIONARY_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_VEHICLE_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.SettingsUtils.CategoryRolesEntry
import com.aitech.weartak_civ.Utils.SettingsUtils.defaultRoleCategoryEntriesFromRaw
import com.aitech.weartak_civ.Utils.isStringEmptyJson
import com.aitech.weartak_civ.Utils.listEqualsIgnoreOrder
import com.aitech.weartak_civ.Utils.secureRandom
import androidx.compose.runtime.State as ComposeState
import com.aitech.weartak_civ.complication.CallsignComplicationService
import com.aitech.weartak_civ.complication.ChatComplicationService
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_DISABLED
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_ENABLED_NEW_MESSAGE
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_ENABLED_NO_NEW_MESSAGE
import com.aitech.weartak_civ.complication.StatusIconsComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakServerEndpointIdentity
import com.aitech.weartak_civ.data.takserver.TakServerManagedReconciler
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.HR_ALERT_THRESHOLDS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.LOCALIZATION_BEACONS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.chatComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.networkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takConnectService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.ipc.WearTakCotIpcService
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.HeartrateService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import com.aitech.weartak_civ.service.PowerManagerService
import com.aitech.weartak_civ.service.ReportingIntervalStrategyService.Companion.calcDynamicReportingInterval
import com.aitech.weartak_civ.service.TAKConnectService
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import com.aitech.weartak_civ.DEFAULT_ALLERGY_LIST
import com.aitech.weartak_civ.DEFAULT_BATT_ALERT_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_BATT_WARNING_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_BLOOD_TYPE
import com.aitech.weartak_civ.DEFAULT_ENABLE_ACTIVITY_RECOGNITION_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION
import com.aitech.weartak_civ.DEFAULT_ENABLE_BETA_FEATURES
import com.aitech.weartak_civ.DEFAULT_ENABLE_BATDOK_COT
import com.aitech.weartak_civ.DEFAULT_ENABLE_BATTERY_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLE_BEACON_SERVICE
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
import com.aitech.weartak_civ.DEFAULT_CRM_MODERATE_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_CRM_POOR_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
import com.aitech.weartak_civ.DEFAULT_ENABLE_COMPANION_APP_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_DITTO_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_DYNAMIC_REPORTING
import com.aitech.weartak_civ.DEFAULT_ENABLE_ESRI_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_GEOFENCE_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_HAIL_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_HEART_RATE_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_ENABLE_LOCATION_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_MULTICAST_ON_WIFI
import com.aitech.weartak_civ.DEFAULT_ENABLE_ONNX_GUNSHOT
import com.aitech.weartak_civ.DEFAULT_ENABLE_ONNX_UAS
import com.aitech.weartak_civ.DEFAULT_ENABLE_PHYSIOLOGICAL_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES
import com.aitech.weartak_civ.DEFAULT_ENABLE_SITX_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_SKIN_TEMPERATURE_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_SMS_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_TABLET_COMMAND_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_TAK_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_WIFI_BATTERY_OPTIMIZATION
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_LENGTH_SECS
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_LENGTH_SECS
import com.aitech.weartak_civ.DEFAULT_HEIGHT_FEET
import com.aitech.weartak_civ.DEFAULT_HEIGHT_INCHES
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_LOADOUT_WEIGHT
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_SEX
import com.aitech.weartak_civ.DEFAULT_STRIDE_LENGTH
import com.aitech.weartak_civ.DEFAULT_UNIFORM_PANTS_LENGTH
import com.aitech.weartak_civ.DEFAULT_UNIFORM_WAIST_SIZE
import com.aitech.weartak_civ.DEFAULT_USER_TYPE
import com.aitech.weartak_civ.DEFAULT_WEIGHT
import com.aitech.weartak_civ.DEFAULT_MULTICAST_ADDRESS
import com.aitech.weartak_civ.DEFAULT_MULTICAST_PORT
import com.aitech.weartak_civ.DEFAULT_MULTICAST_PROTOCOL
import com.aitech.weartak_civ.DEFAULT_SELECTED_PHYSIO_METRIC
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTION_FAILED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DUPLICATE_STRING
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.aitech.weartak_civ.Utils
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.aitech.weartak_civ.service.ReactiveServiceManager
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import com.aitech.weartak_civ.Utils.getNullableBoolean
import com.aitech.weartak_civ.Utils.setAirplaneMode
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.GunshotOnnxRepository
import com.aitech.weartak_civ.data.UasOnnxRepository
import com.aitech.weartak_civ.data.DataRepository.SitxServerStatus
import com.aitech.weartak_civ.presentation.audio.AudioDetectionPromptHost
import com.aitech.weartak_civ.data.secure.sitx.SitxSecretsRepository
import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bhRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSitxService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricIconComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricStringComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricTitleComplication
import com.aitech.weartak_civ.presentation.viewmodel.SitxViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import com.aitech.weartak_civ.DEFAULT_ENABLE_IMMERSION_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.EnumMap
import com.aitech.weartak_civ.data.model.PressureReading
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableMulticastOnWifi
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.ui.formatter.UnitPrefs
import java.io.File
import com.aitech.weartak_civ.service.SitxTakService
import com.aitech.weartak_civ.service.TAKConnectService.Companion.takServerDetails
import kotlinx.serialization.SerialName
import kotlin.Long
import kotlin.text.get


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        const val EVENT_SEND_DELAY_MS = 1000L
        const val MOVEMENT_DETECTION_SERVICE_START_DELAY = 20_000L
        lateinit var mainActivity : MainActivity
        var mainActivityIsInit : MainActivity? = null
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        // TODO: Use MC and dataRepo; Can remove this finally.
        var quickReplyOptions: List<Utils.TextSelectionButton> = listOf(
            Utils.TextSelectionButton("RGR", "Roger"),
            Utils.TextSelectionButton("NEG", "Negative"),
            Utils.TextSelectionButton("Obj", "Objective sighted"),
            Utils.TextSelectionButton("nPos", "In Position")
        )
        var enableLocationService : Boolean =  DEFAULT_ENABLE_LOCATION_SERVICE
        var enableBetaFeatures : Boolean = DEFAULT_ENABLE_BETA_FEATURES
        var enableOnnxGunshotService : Boolean = DEFAULT_ENABLE_ONNX_GUNSHOT
        var onnxGunshotTriggerThresholdPercent : Int = DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
        var enableOnnxUasService : Boolean = DEFAULT_ENABLE_ONNX_UAS
        var onnxUasTriggerThresholdPercent : Int = DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
        var enableBleBeaconService : Boolean = DEFAULT_ENABLE_BLE_BEACON_SERVICE
        var localizationBleBeaconItemList : List<DataRepository.LocalizationBleBeaconItem> = listOf()
        var enablePhysiologicalServices : Boolean = DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES
        var enableHeartrateService : Boolean = DEFAULT_ENABLE_HEART_RATE_SERVICE
        var enableActRecService: Boolean = DEFAULT_ENABLE_ACTIVITY_RECOGNITION_SERVICE
        var enableSkinTempService: Boolean = DEFAULT_ENABLE_SKIN_TEMPERATURE_SERVICE
        var enableDittoService : Boolean = DEFAULT_ENABLE_DITTO_SERVICE
        var enableCompanionAppService: Boolean = DEFAULT_ENABLE_COMPANION_APP_SERVICE
        var enableBloodhoundProximityVibration: Boolean = DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
        var bloodhoundProximityRadiusMeters: Int = DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
        var bloodhoundProximityIntensity: String = DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
        var enableBatteryAlerts: Boolean = DEFAULT_ENABLE_BATTERY_ALERTS
        var enableHailService: Boolean = DEFAULT_ENABLE_HAIL_SERVICE
        var enableBatdokCot: Boolean = DEFAULT_ENABLE_BATDOK_COT
        var enableSitxService: Boolean = DEFAULT_ENABLE_SITX_SERVICE
        var sitxTakUrl : String = ""

        var sitxTakGroup: SitxTakService.TakServerItem? =
            SitxTakService.TakServerItem(
            id = 0L,
            flowTag = "",
            name = "",
            description = ""
            )

        var sitxAvailableGroupList : List<SitxTakService.TakServerItem> = listOf()

        var sitxTakClientId : String = ""
        var enableImmersionAlerts: Boolean = DEFAULT_ENABLE_IMMERSION_ALERTS
        var enableLowAtmPressureAlert: Boolean = DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT
        var lowAtmPressureAlertThreshold: Int = DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
        var enableHighAtmPressureAlert: Boolean = DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT
        var highAtmPressureAlertThreshold: Int = DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
        //Enable Global Health Events
        var enablePassiveDataService = false

        // CRM
        var enableCrmService: Boolean = false
        var crmPoorMaxThreshold: Int = DEFAULT_CRM_POOR_MAX_THRESHOLD
        var crmModerateMaxThreshold: Int = DEFAULT_CRM_MODERATE_MAX_THRESHOLD


        var connectivityStatusComplication : String = ""
        var takServerStatusComplication : String = ""
        var chatComplication : String = ""

        var physioAlertComplication : String = ""


        var enableCellularService: Boolean = true
        var isConnected : Boolean = true
        lateinit var dataRepository: DataRepository
        lateinit var bhRepo: BloodhoundRepository
        lateinit var sitxRepo: SitxSecretsRepository

        @SuppressLint("StaticFieldLeak")
        var networkManager : NetworkManager? = null
        var takConnectService : TAKConnectService? = null
        private val klaxon = Klaxon()

        var team = ""
        // Unlike team and teamValue, role does not have a roleValue. So removing roleValue. Please use role wherever roleValue was previously used.
        var role = ""
        var categoryRolesEntryLists: List<CategoryRolesEntry> = emptyList()
        var category = ""
        var license = "AscentFreeLicense"
        var teamValue = ""
        var dittoAppId = ""
        var dittoAuthToken = ""
        lateinit var teamEntriesValuesList : TeamsJson

        var enableGeofenceService: Boolean = DEFAULT_ENABLE_GEOFENCE_SERVICE
        var enableMulticastOnWifi: Boolean = DEFAULT_ENABLE_MULTICAST_ON_WIFI
        var multicastAddress = DEFAULT_MULTICAST_ADDRESS
        var multicastProtocol = DEFAULT_MULTICAST_PROTOCOL
        var multicastPort = DEFAULT_MULTICAST_PORT


        var battWarnPercent = DEFAULT_BATT_WARNING_PERCENTAGE
        var battAlertPercent = DEFAULT_BATT_ALERT_PERCENTAGE
        var birthYearValue = DEFAULT_BIRTH_YEAR
        var heightFeetValue = DEFAULT_HEIGHT_FEET
        var heightInchesValue = DEFAULT_HEIGHT_INCHES
        var weightValue = DEFAULT_WEIGHT
        var sexValue = DEFAULT_SEX
        var bloodTypeValue = DEFAULT_BLOOD_TYPE
        var allergyList : List<DataRepository.AllergyItem> = listOf(DataRepository.AllergyItem(name=DEFAULT_ALLERGY_LIST))
        var restingHeartRate = DEFAULT_RESTING_HR
        var userTypeValue = DEFAULT_USER_TYPE

        var uniformWaistSize = DEFAULT_UNIFORM_WAIST_SIZE
        var strideLength = DEFAULT_STRIDE_LENGTH
        var uniformPantsLength = DEFAULT_UNIFORM_PANTS_LENGTH
        var loadoutWeight = DEFAULT_LOADOUT_WEIGHT

        var takServerList : List<DataRepository.TakServerItem> = listOf()

        var bloodhoundList : List<Utils.BloodhoundItem> = listOf()
        var SOME_WIFI_NETWORKS = "Some WiFi Networks"
        var ALL_WIFI_NETWORKS = "All WiFi Networks"
        var NO_WIFI_NETWORKS = "No WiFi Networks"
        var saveBattOnWifiString: String = ALL_WIFI_NETWORKS
        var enableWifiBatteryOptimization : Boolean = DEFAULT_ENABLE_WIFI_BATTERY_OPTIMIZATION
        var enableAllWifiBatteryOptimization : Boolean = DEFAULT_ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION
        var wifiSsidList : List<DataRepository.WifiSsidItem> = listOf()
        var enableTakEndpoint : Boolean = DEFAULT_ENABLE_TAK_ENDPOINT
        var enableEsriEndpoint : Boolean = DEFAULT_ENABLE_ESRI_ENDPOINT
        var esriEndpoint : String = ""
        var esriReportingUpdateInterval : Int = DEFAULT_CONSTANT_REPORTING_INTERVAL
        var enableTabletCommandEndpoint: Boolean = DEFAULT_ENABLE_TABLET_COMMAND_ENDPOINT
        var tabletCommandEndpoint : String = ""
        var tabletCommandApikey : String = ""
        var tabletCommandReportingUpdateInterval : Int = DEFAULT_CONSTANT_REPORTING_INTERVAL

        var enableDynamicReportingStrategy = DEFAULT_ENABLE_DYNAMIC_REPORTING
        var constantReportingInterval = DEFAULT_CONSTANT_REPORTING_INTERVAL
        var stationaryReportingInterval = DEFAULT_STATIONARY_REPORTING_INTERVAL
        var onFootReportingInterval = DEFAULT_ON_FOOT_REPORTING_INTERVAL
        var vehicleReportingInterval = DEFAULT_VEHICLE_REPORTING_INTERVAL
        var whileAlertingReportingInterval = DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL

        var enableSmsForAlerts = DEFAULT_ENABLE_SMS_ALERTS
        var smsNumbersList : List<DataRepository.SmsNumber> = listOf()
        var smsNumbersListString : String = ""

        var selectedPhysioMetric: String = DEFAULT_SELECTED_PHYSIO_METRIC
        var physioMetricStringComplication: String = ""
        var physioMetricIconComplication: Int = 0
        var physioMetricTitleComplication: String = ""

        var enablePhysiologicalAlerts = DEFAULT_ENABLE_PHYSIOLOGICAL_ALERTS

        var restingHrHighAlertBpmThreshold: Int = DEFAULT_HIGH_RESTING_HR_THRESHOLD
        var restingHrLowAlertBpmThreshold: Int = DEFAULT_LOW_RESTING_HR_THRESHOLD

        var highRestingHrWarningThresholdLength: Int = DEFAULT_HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES

        var lowRestingHrWarningThresholdLength: Int = DEFAULT_LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES

        var highRestingHrAlertThresholdLength: Int = DEFAULT_HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES

        var lowRestingHrAlertThresholdLength: Int = DEFAULT_LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES

        var exertionThresholdWarning: Int = DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
        var exertionThresholdAlert: Int = DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
        var exertionWarningThresholdLength: Int = DEFAULT_EXERTION_WARNING_THRESHOLD_LENGTH_SECS
        var exertionAlertThresholdLength: Int = DEFAULT_EXERTION_ALERT_THRESHOLD_LENGTH_SECS


        var versionNumber : String = ""
        var commitHash: String = ""


        var logFile: File? = null

//        private lateinit var healthTrackingService: com.samsung.android.service.health.tracking.HealthTrackingService

        fun generateSaveBattOnWifiString(enableWifiOpt: Boolean, enableAllWifiOpt: Boolean) : String {
            return when {
                enableWifiOpt && enableAllWifiOpt -> ALL_WIFI_NETWORKS  // "All"
                !enableWifiOpt -> NO_WIFI_NETWORKS // "No"
                else -> SOME_WIFI_NETWORKS // "Some"
            }
        }

        data class RolesJson (
            val lrEntries: List<String>,
            val lrValues: List<String>
        ) {
            public fun toJson() = klaxon.toJsonString(this)

            companion object {
                public fun fromJson(json: String) = klaxon.parse<RolesJson>(json)
            }
        }

        data class TeamsJson (
            val ltEntries: List<String>,
            val ltValues: List<String>
        ) {
            public fun toJson() = klaxon.toJsonString(this)

            companion object {
                public fun fromJson(json: String) = klaxon.parse<TeamsJson>(json)
            }
        }

        fun getActivity(): MainActivity {
            return mainActivity
        }

        fun startReactiveServiceManager(context: Context) {
            val intent = Intent(context, ReactiveServiceManager::class.java)
            if (!ReactiveServiceManager.isRunning.get()) {
                Log.i(TAG, "MainActivity startReactiveServiceManager(): Starting ReactiveServiceManager")
                ContextCompat.startForegroundService(context, intent)
            } else {
                Log.i(
                    TAG,
                    "MainActivity startReactiveServiceManager(): ReactiveManager is already Running!"
                )
            }
        }

        fun stopReactiveServiceManager(context: Context) {
            val intent = Intent(context, ReactiveServiceManager::class.java)
            if (ReactiveServiceManager.isRunning.get()) {
                Log.i(TAG, "MainActivity stopReactiveServiceManager(): Stopping ReactiveServiceManager")
                context.stopService(intent)
            } else {
                Log.i(TAG, "MainActivity stopReactiveServiceManager(): ReactiveServiceManager is not Running!")
            }
        }
    }

    private val audioRepo by lazy {
        try {
            (applicationContext as WearTakApplication).audioRepo
        } catch (_: Throwable) {
            AudioCaptureRepository(applicationContext)
        }
    }

    private val gunshotOnnxRepo by lazy {
        try {
            (applicationContext as WearTakApplication).gunshotOnnxRepo
        } catch (_: Throwable) {
            GunshotOnnxRepository(applicationContext)
        }
    }

    private fun collectAudioRecordingFlows() {
        lifecycleScope.launch {
            audioRepo.getRecordingSessionState
                .collect { state ->
                    when (state) {
                        true -> Log.v(TAG, "Audio Recording Started ")
                        false -> return@collect //makeToast("Audio Rec Session ENDED!")
                    }
                }
        }
    }

    private fun makeToast(toast: String) {
        val duration = Toast.LENGTH_SHORT
        val toast = Toast.makeText(this, toast, duration) // in Activity
        toast.show()
    }

    val callsignState = MutableLiveData<String>("")
    val licenseState = MutableLiveData<String>("")
    val nameState = MutableLiveData<String>("")
    val addressState = MutableLiveData<String>("")
    val portState = MutableLiveData<String>("")
    val usernameState = MutableLiveData<String>("")
    val passwordState = MutableLiveData<String>("")

    private lateinit var restrictionsManager: RestrictionsManager
    private lateinit var restrictionReceiver: BroadcastReceiver

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionRequestInFlight = false

            val denied = result.filterValues { granted -> !granted }.keys.toList()
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Permissions are denied. Enable in Settings → Apps → WearTAK → Permissions.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // allow a future request if new perms appear
                lastRequestedPermSet = null
            }
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "MainActivity onNewIntent(): categories = (${intent.categories}), action = (${intent.action})")
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        ((application as WearTakApplication).repo as DataRepository).noteLocationMotion()
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "MainActivity onPause()")
    }

    override fun onRestart() {
        super.onRestart()
        Log.i(TAG, "MainActivity onRestart()")
    }

    override fun onStart() {
        super.onStart()
        com.aitech.weartak_civ.service.DebugStayAwakeService.restoreIfEnabled(this)
        try { registerReceiver(restrictionReceiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)) } catch (_: Exception) {}
        Log.i(TAG, "MainActivity onStart()")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "MainActivity onStop()")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "MainActivity onResume()")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy()")
        stopReactiveServiceManager(applicationContext)
        networkManager?.deInit()
        try { unregisterReceiver(restrictionReceiver) } catch (_: Exception) {}
    }

    fun printSensorDetails(context: Context?) {
        var resSensorList: MutableList<Sensor> = ArrayList()
        try {
            val smm = context?.getSystemService(SENSOR_SERVICE) as SensorManager
            resSensorList.addAll(smm.getSensorList(Sensor.TYPE_ALL))
            Log.d(TAG, "Sensor List: ${resSensorList.size}")
            resSensorList.forEach { sensor ->
                Log.d(TAG, "    ${sensor.name}")
            }
        } catch (e: Exception) {
            resSensorList = ArrayList()
            Log.e(TAG, "Sensor List ERROR: Unable to obtain", e)
        }
    }

    fun initTeamEntriesValuesList(teamNamesList: List<String>, teamColorsList: List<String>) {
        var teamNames = teamNamesList
        var teamColors = teamColorsList
        val teamNamesSize = teamNames.size
        val teamColorsSize = teamColors.size
        if (teamNamesSize != teamColorsSize) {
            // Mistake maybe made in MDM. Make size of teamNames the same as that of teamColors
            Log.w(TAG, "Initial teamNames size($teamNamesSize) != teamColors size($teamColorsSize)")
            if (teamNamesSize > teamColorsSize) {
                // Reduce teamNames to that of teamColors
                teamNames = teamNames.take(teamColorsSize)
            } else {
                teamColors = teamColors.take(teamNamesSize)
            }
        }
        teamEntriesValuesList = TeamsJson(teamNames, teamColors)
    }

    private suspend fun initTeamAndTeamValue() {
        // which team member am I?
        team = dataRepository.getTeam.first()
        var teamIndex = teamEntriesValuesList.ltEntries.indexOf(team)
        // needed in the event my current team doesn't exist in the list of available teams.
        if (teamIndex == -1) {
            team = teamEntriesValuesList.ltEntries[0]
            teamIndex = 0
        }
        teamValue = teamEntriesValuesList.ltValues[teamIndex]
    }

    private suspend fun initCategoryAndRole() {
        // what role and in which category am I?
        role = dataRepository.getRole.first()
        category = dataRepository.getRoleCategory.first()

        val catIdx = categoryRolesEntryLists.indexOfFirst { it.category == category}
        val roleIdx = categoryRolesEntryLists.getOrNull(catIdx)?.roles?.indexOf(role) ?: -1

        // needed in the event my current role doesn't exist in the list of available roles.
        if (catIdx == -1 || roleIdx == -1) {
            category = categoryRolesEntryLists.first().category
            role = categoryRolesEntryLists.first().roles.first()
            dataRepository.storeRoleCategory(category)
            dataRepository.storeRole(role)
        }
        Log.i(TAG, "Exited initCategoryAndRole() w/ category ($category) and role ($role)")
    }

    private suspend fun initCategoryAndRoleAndCategoryRolesList(dataRepoCategoryRolesList: List<CategoryRolesEntry> ) {
        try {
            Log.i(TAG, "Just before loading category entries from DataStore")
            // Exclude FES from cached and managed role lists as well as the defaults.
            categoryRolesEntryLists = Utils.SettingsUtils.selectableRoleCategories(dataRepoCategoryRolesList)

            if (categoryRolesEntryLists.isEmpty()) {
                Log.i(TAG, "DataStore empty → loading defaults from raw JSON")
                val defaults = applicationContext.defaultRoleCategoryEntriesFromRaw()

                if (defaults.isNotEmpty()) {
                    categoryRolesEntryLists = defaults
                    Log.i(TAG, "Caching ${defaults.size} defaults back to DataStore")
                    try {
                        // **Use the correct method name** from DataRepository:
                        dataRepository.storeCategoryRolesList(defaults)
                    } catch (storeEx: Exception) {
                        Log.e(TAG, "Failed to store default categories", storeEx)
                    }
                } else {
                    Log.w(TAG, "No defaults found in raw resource; UI will be empty")
                }
            }

            Log.i(TAG, "About to init category and role")
            initCategoryAndRole()

        } catch (e: Exception) {
            Log.e(TAG, "Uncaught error in initCategoryAndRoleAndCatogoryRolesList(): ${e.message}", e)
            // At this point you can either rethrow or fall back to a minimal safe state:
            categoryRolesEntryLists = emptyList()
            role = ""             // or some safe default
            category = ""
        }
    }

    suspend fun updateCepSustainOverridesMap() {
        val mainMap = mapOf(
            "hr_high_threshold_resting_alert" to (highRestingHrAlertThresholdLength * 60L).coerceAtLeast(0L),
            "hr_high_threshold_resting_warning" to (highRestingHrWarningThresholdLength * 60L).coerceAtLeast(0L),
            "hr_low_threshold_resting_alert" to (lowRestingHrAlertThresholdLength * 60L).coerceAtLeast(0L),
            "hr_low_threshold_resting_warning" to (lowRestingHrWarningThresholdLength * 60L).coerceAtLeast(0L),
            "exertion_warning_hr_zone4_exercise" to (exertionWarningThresholdLength.toLong()).coerceAtLeast(0L),
            "exertion_alert_hr_zone5_exercise" to (exertionAlertThresholdLength.toLong()).coerceAtLeast(0L)
        )
        val warnMap = emptyMap<String, Long>()
        dataRepository.storeCepSustainOverridesDual(mainMap, warnMap)
    }

        fun seedVariablesFromRepo() {
        // This function reads in data from dataRepository and sets MainActivity settings (except those for UI settings screens) as well as other service settings
            runBlocking {
            Log.d(TAG, """=== === Initializing Settings === === ===""".trimIndent())

            // --- Identity / Callsign ---

            // what is my callsign?
            callsign = dataRepository.getCallsign.first()
            // on the first install, callsign has no value, we need to fill it with a random number
            if (callsign.equals("")) {
                callsign = "WEAROS-" + (secureRandom.nextInt(9999) + 1).toString()
                dataRepository.storeCallsign(callsign)
                Log.v(
                    TAG,
                    "MainActivity seedVariablesFromRepo(): First install --> Assigning a random callsign: $callsign"
                )
            }

            // --- Team / Role configuration ---

            // what are the teams I can be?
            val teamNames = dataRepository.getTeamNames.first().split(",")
            val teamColors = dataRepository.getTeamColors.first().split(",")
            initTeamEntriesValuesList(teamNames, teamColors)
            initTeamAndTeamValue()

            initCategoryAndRoleAndCategoryRolesList(dataRepository.getCategoryRolesList.first())

            // --- License / TAK server list ---
            license       = dataRepository.getLicense.first()
            dataRepository.tryAwaitTakServerList()?.let { loadedTakServers ->
                takServerList = loadedTakServers
            }

            // --- Core service enable flags ---
            enableLocationService          = dataRepository.getEnableLocationService.first()
            enablePhysiologicalServices    = dataRepository.getEnablePhysiologicalServices.first()
            enableHeartrateService         = dataRepository.getEnableHeartRateService.first()
            enableActRecService            = dataRepository.getEnableActRecService.first()
            enableSkinTempService          = dataRepository.getEnableSkinTempService.first()
            enableImmersionAlerts          = dataRepository.getEnableImmersionAlerts.first()
            enableLowAtmPressureAlert      = dataRepository.getEnableLowAtmPressureAlert.first()
            enableHighAtmPressureAlert     = dataRepository.getEnableHighAtmPressureAlert.first()
            enableDittoService             = dataRepository.getEnableDittoService.first()
            enableCompanionAppService      = dataRepository.getEnableCompanionAppService.first()
            enableBatteryAlerts            = dataRepository.getEnableBatteryAlerts.first()
            enableHailService              = dataRepository.getEnableHailService.first()
            enableSitxService              = dataRepository.getEnableSitxService.first()
            enableBatdokCot                = dataRepository.getEnableBatdokCot.first()
            enableMulticastOnWifi          = dataRepository.getEnableMulticast.first()
            enableBloodhoundProximityVibration = dataRepository.getEnableBloodhoundProximityVibration.first()
            bloodhoundProximityRadiusMeters = dataRepository.getBloodhoundProximityRadiusMeters.first()
            bloodhoundProximityIntensity = dataRepository.getBloodhoundProximityIntensity.first()
            enableBetaFeatures             = dataRepository.getEnableBetaFeatures.first()
            enableOnnxGunshotService       = dataRepository.getEnableOnnxGunshotService.first()
            onnxGunshotTriggerThresholdPercent = dataRepository.getOnnxGunshotTriggerThresholdPercent.first()
            enableOnnxUasService           = dataRepository.getEnableOnnxUasService.first()
            onnxUasTriggerThresholdPercent = dataRepository.getOnnxUasTriggerThresholdPercent.first()

            // CRM
            enableCrmService = dataRepository.getEnableCrmService.first()

            lowAtmPressureAlertThreshold = dataRepository.getLowAtmPressureAlertThreshold.first()
            highAtmPressureAlertThreshold = dataRepository.getHighAtmPressureAlertThreshold.first()

                        // --- Multicast options ---
            multicastAddress = dataRepository.getMulticastAddress.first()
            multicastProtocol = dataRepository.getMulticastProtocol.first()
            multicastPort = dataRepository.getMulticastPort.first()

            // --- Sitx options ---
            sitxTakUrl = sitxRepo.baseUrl.first()
            sitxTakClientId = sitxRepo.clientId.first() ?: ""
            sitxTakGroup = dataRepository.getSelectedSitxGroup.first()

            // --- Reporting intervals / strategy ---
            enableDynamicReportingStrategy = dataRepository.getEnableDynamicReportingStrategy.first()
            constantReportingInterval      = dataRepository.getConstantReportingInterval.first()
            stationaryReportingInterval    = dataRepository.getStationaryReportingInterval.first()
            onFootReportingInterval        = dataRepository.getOnFootReportingInterval.first()
            vehicleReportingInterval       = dataRepository.getVehicleReportingInterval.first()
            whileAlertingReportingInterval = dataRepository.getWhileAlertingReportingInterval.first()

            // No verified speed is available during startup.
            val dynamicReportingInterval = calcDynamicReportingInterval(
                false,
                null,
                whileAlertingReportingInterval,
                onFootReportingInterval,
                vehicleReportingInterval,
                stationaryReportingInterval
            )
            dataRepository.storeDynamicReportingInterval(dynamicReportingInterval)
            val reportingUpdateInterval =
                if (enableDynamicReportingStrategy) { dynamicReportingInterval } else { constantReportingInterval }

            dataRepository.storeReportingUpdateInterval(reportingUpdateInterval)
            CotDispatchService.desiredReportingIntervalSecs = reportingUpdateInterval
            CotDispatchService.actualReportingIntervalSecs = reportingUpdateInterval
            CotDispatchService.desiredLocationObservationIntervalSecs =
                reportingUpdateInterval // Startup has neither an activity classification nor a verified speed.

            // --- External endpoints / integrations ---
            enableTakEndpoint              = dataRepository.getEnableTakEndpoint.first()
            enableEsriEndpoint             = dataRepository.getEnableEsriEndpoint.first()
            enableTabletCommandEndpoint    = dataRepository.getEnableTabletCommandEndpoint.first()
            esriEndpoint                   = dataRepository.getEsriEndpoint.first()
            esriReportingUpdateInterval    = dataRepository.getEsriReportingUpdateInterval.first()
            tabletCommandEndpoint          = dataRepository.getTabletCommandEndpoint.first()
            tabletCommandApikey            = dataRepository.getTabletCommandApikey.first()
            tabletCommandReportingUpdateInterval = dataRepository.getTabletCommandReportingUpdateInterval.first()

            // --- Wi-Fi / battery optimization ---
            enableWifiBatteryOptimization      = dataRepository.getEnableWifiBatteryOptimization.first()
            PowerManagerService.isWifiOpt      = enableWifiBatteryOptimization

            enableAllWifiBatteryOptimization   = dataRepository.getEnableAllWifiBatteryOptimization.first()
            PowerManagerService.isAllWifiOpt   = enableAllWifiBatteryOptimization

            wifiSsidList         = dataRepository.getWifiSsidList.first()
            saveBattOnWifiString = generateSaveBattOnWifiString(enableWifiBatteryOptimization, enableAllWifiBatteryOptimization)

            // --- Battery alert thresholds ---
            battWarnPercent  = dataRepository.getBatteryWarnPercentage.first()
            battAlertPercent = dataRepository.getBatteryAlertPercentage.first()

            // --- SMS alerting ---
            enableSmsForAlerts     = dataRepository.getEnableSmsForAlerts.first()
            smsNumbersList         = dataRepository.getSmsNumbersList.first()
            smsNumbersListString   =
                convertSmsNumbersListToStringWithSeparator(dataRepository.getSmsNumbersList.first(), ",")

            selectedPhysioMetric = dataRepository.getSelectedPhysioMetric.first()

            enablePhysiologicalAlerts = dataRepository.getEnablePhysiologicalAlerts.first()
            restingHrHighAlertBpmThreshold = dataRepository.getRestingHrHighAlertBpmThreshold.first()
            restingHrLowAlertBpmThreshold = dataRepository.getRestingHrLowAlertBpmThreshold.first()
            highRestingHrWarningThresholdLength = dataRepository.getHighRestingHrWarningThresholdLength.first()
            lowRestingHrWarningThresholdLength = dataRepository.getLowRestingHrWarningThresholdLength.first()
            highRestingHrAlertThresholdLength = dataRepository.getHighRestingHrAlertThresholdLength.first()
            lowRestingHrAlertThresholdLength = dataRepository.getLowRestingHrAlertThresholdLength.first()
            exertionThresholdWarning = dataRepository.getExertionThresholdWarning.first()
            exertionThresholdAlert = dataRepository.getExertionThresholdAlert.first()
            exertionWarningThresholdLength = dataRepository.getExertionWarningThresholdLength.first()
            exertionAlertThresholdLength = dataRepository.getExertionAlertThresholdLength.first()
            //hrHighAlertBpmThreshold = dataRepository.getHrHighAlertBpmThreshold.first()
            //hrHighAlertNumSecsThreshold = dataRepository.getHrHighAlertNumSecsThreshold.first()

            // --- BLE beacons / localization ---
            enableBleBeaconService       = dataRepository.getEnableBleBeaconService.first()
            localizationBleBeaconItemList = dataRepository.getLocalizationBleBeaconItemList.first()

            // --- Ditto configuration ---
            dittoAppId    = dataRepository.getDittoAppId.first()
            dittoAuthToken = dataRepository.getDittoAuthToken.first()

            // --- Physiological profile / metrics ---
            birthYearValue           = dataRepository.getYearOfBirth.first()
            heightFeetValue    = dataRepository.getPhysiologicalMetricHeightFeet.first()
            heightInchesValue  = dataRepository.getPhysiologicalMetricHeightInches.first()
            weightValue        = dataRepository.getPhysiologicalMetricWeight.first()
            sexValue           = dataRepository.getPhysiologicalMetricSex.first()
            bloodTypeValue     = dataRepository.getPhysiologicalMetricBloodType.first()
//            allergyList        = dataRepository.getPhysiologicalMetricAllergyList.first()
            userTypeValue      = dataRepository.getPhysiologicalMetricUserType.first()
            restingHeartRate   = dataRepository.getRestingHeartRate.first()
            uniformWaistSize   = dataRepository.getPhysiologicalMetricUniformWaistSize.first()
            strideLength       = dataRepository.getPhysiologicalMetricStrideLength.first()
            uniformPantsLength = dataRepository.getPhysiologicalMetricUniformPantsLength.first()
            loadoutWeight      = dataRepository.getPhysiologicalMetricLoadoutWeight.first()
        }
    }

    private fun logSettings() {
        seedVariablesFromRepo()
        var logOutput = """
                    === === Configuration Settings === ===
                    ${String.format("%-25s %-30s", "[Settings]", "[Value]")}
                    === === === === === === === === === ==
                    ${String.format("%-25s %-30s", "Device ID:", device_uid)}
                    ${String.format("%-25s %-30s", "Callsign:", callsign)}
                    ${String.format("%-25s %-30s", "Team:", team)}
                    ${String.format("%-25s %-30s", "Role Category:", category)}
                    ${String.format("%-25s %-30s", "Role:", role)}
                    ${String.format("%-25s %-30s", "Teams:", teamEntriesValuesList.ltEntries)}
                    ${String.format("%-25s %-30s", "Colors:", teamEntriesValuesList.ltValues)}
                    ${String.format("%-25s %-30s", "Category Roles List:", categoryRolesEntryLists.toString())}

                    ${String.format("%-25s %-30s", "Enable Tak Endpoint:", enableTakEndpoint)}
                    ${String.format("%-25s %-30s", "Enable Sit(x) Endpoint:", enableSitxService.toString())}
                    ${String.format("%-25s %-30s", "Enable Esri Endpoint:", enableEsriEndpoint)}
                    ${String.format("%-25s %-30s", "Enable Tablet Command Endpoint:", enableTabletCommandEndpoint)}
                    
                    === === TAK Server Settings === ===
                    ${String.format("%-25s %-30s", "Number of Servers:", takServerList.size)}
                    
                    
                    """.trimIndent()

        for (i in takServerList.indices) {
            val takServer = takServerList.get(i)
            logOutput += """
                    ${String.format("%-25s %-30s", "Server $i Name:", takServer.name)}
                    ${String.format("%-25s %-30s", "Server $i Addr:", takServer.address)}
                    ${String.format("%-25s %-30s", "Server $i Port:", takServer.port)}
                    ${String.format("%-25s %-30s", "Server $i Uses Cert:", takServer.isP12Cert)}
                    ${String.format("%-25s %-30s", "Server $i ID:", takServer.serverId)}
                    ${String.format("%-25s %-30s", "Server $i User:", "configured=${takServer.username.isNotEmpty()}")}
                    ${String.format("%-25s %-30s", "Server $i Pswd:", "configured=${takServer.password.isNotEmpty()}")}
                    ${String.format("%-25s %-30s", "Server $i Cert:", "configured=${takServer.p12Cert.isNotEmpty()}")}
                    ${String.format("%-25s %-30s", "Server $i Cert Pswd:", "configured=${takServer.p12CertPassword.isNotEmpty()}")}
                       
                    
                    """.trimIndent()
        }

        logOutput += """
                    === === TAK SA Multicast Settings === ===
                    ${String.format("%-25s %-30s", "Enable Multicast on WiFi:", enableMulticastOnWifi.toString())}
                    ${String.format("%-25s %-30s", "Multicast Address:", multicastAddress)}
                    ${String.format("%-25s %-30s", "Multicast Output Protocol:", multicastProtocol)}
                    ${String.format("%-25s %-30s", "Multicast Server Port:", multicastPort)}
                    
                    === === Sitx TAK Server Settings === ===
                    ${String.format("%-25s %-30s", "Sitx TAK URL:", sitxTakUrl)}
                    ${String.format("%-25s %-30s", "Sitx TAK Client ID:", "${sitxTakClientId.take(3)}...(${sitxTakClientId.length})")}
                    
                    === === ESRI Server Settings === ===
                    ${String.format("%-25s %-30s", "ESRI Endpoint:", esriEndpoint)}
                    ${String.format("%-25s %-30s", "ESRI Reporting Interval:", esriReportingUpdateInterval)}
                    
                    === === Tablet Command Server Settings === ===
                    ${String.format("%-25s %-30s", "Tablet Command Endpoint:", tabletCommandEndpoint)}
                    ${String.format("%-25s %-30s", "Tablet Command Apikey:", "${tabletCommandApikey.take(3)}...(${tabletCommandApikey.length})")}
                    ${String.format("%-25s %-30s", "Tablet Command Reporting Interval:", tabletCommandReportingUpdateInterval)}
                    
                    === === Service Settings === ===
                    ${String.format("%-25s %-30s", "License Key:", license)}
                    ${String.format("%-25s %-30s", "Ditto App Id:", dittoAppId)}
                    ${String.format("%-25s %-30s", "Ditto Auth Token:", dittoAuthToken)}
                    ${String.format("%-25s %-30s", "Reporting Strategy:", if (enableDynamicReportingStrategy) "Dynamic" else "Constant")}
                    ${String.format("%-25s %-30s", "Constant Reporting Interval (s):", constantReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "Stationary Reporting Interval (s):", stationaryReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "Vehicle Reporting Interval (s):", vehicleReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "On Foot Reporting Interval (s):", onFootReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "While Alerting Reporting Interval (s):", whileAlertingReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "Enable Loc:", enableLocationService.toString())}
                    ${String.format("%-25s %-30s", "Enable Physio:", enablePhysiologicalServices.toString())}
                    ${String.format("%-25s %-30s", "Enable Activity Recognition:", enableActRecService.toString())}
                    ${String.format("%-25s %-30s", "Enable Skin Temp:", enableSkinTempService.toString())}
                    ${String.format("%-25s %-30s", "Enable Ditto:", enableDittoService.toString())}
                    ${String.format("%-25s %-30s", "Enable wifi opt:", enableWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "Enable all wifi:", enableAllWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "Wifi ssids list:", wifiSsidList.toString())}
                    ${String.format("%-25s %-30s", "Enable SMS For Alerts:", enableSmsForAlerts.toString())}
                    ${String.format("%-25s %-30s", "SMS Numbers:", smsNumbersList.toString())}
                    ${String.format("%-25s %-30s", "Selected Physio Metric:", selectedPhysioMetric)}
                    ${String.format("%-25s %-30s", "Enable Bloodhound Proximity Vibration:", enableBloodhoundProximityVibration.toString())}
                    ${String.format("%-25s %-30s", "Bloodhound Proximity Radius (m):", bloodhoundProximityRadiusMeters.toString())}
                    ${String.format("%-25s %-30s", "Bloodhound Proximity Vibration Intensity:", bloodhoundProximityIntensity)}
                    ${String.format("%-25s %-30s", "Enable Immersion Alerts:", enableImmersionAlerts.toString())}
                    ${String.format("%-25s %-30s", "Enable Low Atm Pressure Alert:", enableLowAtmPressureAlert.toString())}
                    ${String.format("%-25s %-30s", "Low Atm Pressure Alert Threshold (hPa):", lowAtmPressureAlertThreshold.toString())}
                    ${String.format("%-25s %-30s", "Enable High Atm Pressure Alert:", enableHighAtmPressureAlert.toString())}
                    ${String.format("%-25s %-30s", "High Atm Pressure Alert Threshold (hPa):", highAtmPressureAlertThreshold.toString())}
                    ${String.format("%-25s %-30s", "Enable Battery Alerts:", enableBatteryAlerts.toString())}
                    ${String.format("%-25s %-30s", "Enable Hail:", enableHailService.toString())}
                    ${String.format("%-25s %-30s", "Enable Batdok COT:", enableBatdokCot.toString())}
                    ${String.format("%-25s %-30s", "Enable Physiological Alerts:", enablePhysiologicalAlerts.toString())}
                    ${String.format("%-25s %-30s", "High Resting Heart Rate Threshold (bpm):", restingHrHighAlertBpmThreshold.toString())} 
                    ${String.format("%-25s %-30s", "High Resting Heart Rate Warning Threshold Length (minutes):", highRestingHrWarningThresholdLength.toString())}
                    ${String.format("%-25s %-30s", "High Resting Heart Rate Alert Threshold Length (minutes):", highRestingHrAlertThresholdLength.toString())}
                    ${String.format("%-25s %-30s", "Low Resting Heart Rate Threshold (bpm):", restingHrLowAlertBpmThreshold.toString())}
                    ${String.format("%-25s %-30s", "Low Resting Heart Rate Warning Threshold Length (minutes):", lowRestingHrWarningThresholdLength.toString())}
                    ${String.format("%-25s %-30s", "Low Resting Heart Rate Alert Threshold Length (minutes):", lowRestingHrAlertThresholdLength.toString())}
                    ${String.format("%-25s %-30s", "Exertion Warning Threshold (%):", exertionThresholdWarning.toString())}
                    ${String.format("%-25s %-30s", "Exertion Warning Threshold Length (secs):", exertionWarningThresholdLength.toString())}
                    ${String.format("%-25s %-30s", "Exertion Alert Threshold (%):", exertionThresholdAlert.toString())}
                    ${String.format("%-25s %-30s", "Exertion Alert Threshold Length (secs):", exertionAlertThresholdLength.toString())}
                    ${String.format("%-25s %-30s", "Battery Warn Percentage:", battWarnPercent.toString())}
                    ${String.format("%-25s %-30s", "Battery Alert Percentage:", battAlertPercent.toString())}
                    ${String.format("%-25s %-30s", "Enable BleBeacon:", enableBleBeaconService.toString())}
                    
                    === === Ble Beacons === ===
                    ${String.format("%-25s %-30s", "Number of Localization Ble Beacons:", localizationBleBeaconItemList.size)}
                    
                    
                    """.trimIndent()

        for (i in localizationBleBeaconItemList.indices) {
            val bleBeacon = localizationBleBeaconItemList.get(i)
            logOutput += """
                    ${String.format("%-25s %-30s", "Beacon $i Id:", "${bleBeacon.uuid}, ${bleBeacon.major}, ${bleBeacon.minor}")}
                    ${String.format("%-25s %-30s", "Beacon $i Location:", "${bleBeacon.lat}, ${bleBeacon.lon}, ${bleBeacon.alt}")}
                       
                    
                    """.trimIndent()
        }
        Log.v(TAG, logOutput)

        logOutput += """
                    === === Physiological User Metrics === ===
                    ${String.format("%-25s %-30s", "Birth Year:", birthYearValue)}
                    ${String.format("%-25s %-30s", "Height:", "$heightFeetValue ft $heightInchesValue inches")}
                    ${String.format("%-25s %-30s", "Weight:", weightValue)}
                    ${String.format("%-25s %-30s", "Sex:", sexValue)}
                    ${String.format("%-25s %-30s", "BloodType:", bloodTypeValue)}
                    ${String.format("%-25s %-30s", "UserType:", userTypeValue)}
                    ${String.format("%-25s %-30s", "UniformWaistSize:", uniformWaistSize)}
                    ${String.format("%-25s %-30s", "StrideLength:", strideLength)}
                    ${String.format("%-25s %-30s", "UniformPantsLength:", uniformPantsLength)}
                    ${String.format("%-25s %-30s", "LoadoutWeight:", loadoutWeight)}
                    ${String.format("%-25s %-30s", "RestingHeartRate:", restingHeartRate)}
                    
                    """.trimIndent()
        Log.v(TAG, logOutput)
    }

    private var permissionRequestInFlight = false
    private var lastRequestedPermSet: Set<String>? = null
    private var lastRequestUptimeMs: Long = 0L
    private val REQUEST_COOLDOWN_MS = 10_000L

    private fun collectBlockedServices() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dataRepository.blockedServices
                    .map { blocked ->
                        blocked.values.flatten().toSet()
                    }
                    .distinctUntilChanged()
                    .filter { permSet -> permSet.isNotEmpty() }
                    .collect { permSet ->
                        if (permissionRequestInFlight) return@collect
                        if (lastRequestedPermSet == permSet) return@collect

                        val now = SystemClock.uptimeMillis()
                        if (now - lastRequestUptimeMs < REQUEST_COOLDOWN_MS) return@collect

                        lastRequestedPermSet = permSet
                        permissionRequestInFlight = true
                        lastRequestUptimeMs = now

                        requestPermissionsLauncher.launch(permSet.toTypedArray())
                    }
            }
        }
    }

    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate(): Start...")
        mainActivity = this
        mainActivityIsInit = this
        device_uid = "WEAROS_" + Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID)

        val context = mainActivity.applicationContext
        dataRepository = (context.applicationContext as WearTakApplication).repo
        bhRepo = (context.applicationContext as WearTakApplication).bhRepo
        sitxRepo = (context.applicationContext as WearTakApplication).sitxRepo

        restrictionsManager = getSystemService(RESTRICTIONS_SERVICE) as RestrictionsManager
        seedVariablesFromRepo()
        val restrictions = restrictionsManager.applicationRestrictions
        val managedLocationSetting =
            if (restrictions?.containsKey("location_service_enabled") == true) {
                restrictions.getBoolean("location_service_enabled")
            } else {
                null
            }
        enableLocationService = runBlocking {
            dataRepository.initializeLocationServiceSetting(managedLocationSetting)
        }
        runBlocking {
            updateCepSustainOverridesMap()
            Handler(Looper.getMainLooper()).postDelayed({ runBlocking {
                CepProvider.getOrCreateEngine(applicationContext).updateSustainOverrides() }
            }, EVENT_SEND_DELAY_MS)
        }
        refreshUI(context)

        restrictionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
                    Log.i(TAG, "MainActivity onCreate() restrictionReceiver: Received new Managed Configurations from Knox")
                    handleManagedConfigUpdate(false)
                }
            }
        }
        registerReceiver(restrictionReceiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED))
        // Handle the initial configuration when the app starts
        handleManagedConfigUpdate(true)

        networkManager = NetworkManager.getInstance()
        networkManager?.init()
        takConnectService = TAKConnectService.getInstance()

        //Obtain version # from build.gradle
        try {
            val pInfo: PackageInfo =
                this.getPackageManager().getPackageInfo(this.getPackageName(), 0)
            versionNumber = pInfo.versionName.toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Version number not found")
        }

        installSplashScreen()

        setTheme(android.R.style.Theme_DeviceDefault)

        //printSensorDetails(this)

        // Request all necessary permissions
        collectBlockedServices()
        //startHealthCapabilitiesCollection()
        validateLicense(license)
        startReactiveServiceManager(applicationContext)
        WearTakCotIpcService.ensureStarted(applicationContext)
        collectAudioRecordingFlows()

        setContent {
            val sitxVm: SitxViewModel = viewModel()
            val settingsVm: WearTAKSettingsViewModel = viewModel()
            WearTAKCIVTheme {
                WearTAKCivApp(licenseState, sitxVm, settingsVm)
            }
        }

        //debugPressureRepoTest()
        Log.i(TAG, "MainActivity onCreate(): End.")
    }

    private fun debugPressureRepoTest() {
        // temporary pressure repository test
        // this manually inserts 2 fake pressure readings to verify:
        // 1) storeLatestPressure() updates latestPressure
        // 2) pressureHistory is capped correctly
        // 3) logging to pressure_test.log works

        lifecycleScope.launch {
            try {
                repeat(2) { idx ->

                    // create a simulated pressure reading
                    // second reading is slightly higher to mimic change
                    val reading = PressureReading(
                        pressure = 1013.25f + idx,
                        timestamp = System.currentTimeMillis()
                    )

                    // store reading in repository (updates latest + history)
                    dataRepository.storeLatestPressure(reading)

                    // write current repository state to file for verification
                    val logFile = File(filesDir, "pressure_test.log")
                    Utils.writeToLogFileLocation(
                        logFile,
                        "latest=${dataRepository.latestPressure.value}, historySize=${dataRepository.pressureHistory.value.size}"
                    )

                    // small delay to simulate time between sensor updates
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "pressure test failed", e)
            }
        }
    }

    private fun refreshUI(context: Context) {
        Log.d(TAG, "=== === Refreshing UI  === === ")

        requestCallsignComplicationUpdate(context)
        requestSosComplicationUpdate(context)
        requestStatusIconsComplicationUpdate(context)
        requestChatComplicationUpdate(context)
        requestBloodhoundComplicationUpdate(context)
        requestPhysioStatusAndAlertingComplicationUpdate(context)
        val logOutput = """
                    === === Physiological User Metrics === ===
                    ${String.format("%-25s %-30s", "Icon:", physioMetricIconComplication)}
                    ${String.format("%-25s %-30s", "Metric:", physioMetricStringComplication)}
                    """.trimIndent()
        Log.d(PhysioAlertsActivity.Companion.TAG, logOutput)
    }

    private fun takServerConnDetailsChanged(takServer1: DataRepository.TakServerItem, takServer2: DataRepository.TakServerItem) : Boolean {
        var changed = false
        if (takServer1.isEnabled != takServer2.isEnabled) {
            changed = true
        } else {
            if (takServer1.isP12Cert != takServer2.isP12Cert) {
                changed = true
            } else {
                if ((takServer1.address != takServer2.address) ||
                    (takServer1.port != takServer2.port)) {
                    changed = true
                } else {
                    if (takServer1.isP12Cert) {
                        if ((takServer1.p12Cert != takServer2.p12Cert) ||
                            (takServer1.p12CertPassword != takServer2.p12CertPassword)) {
                            changed = true
                        }
                    } else {
                        if ((takServer1.username != takServer2.username) ||
                            (takServer1.password != takServer2.password)) {
                            changed = true
                        }
                    }
                }
            }
        }
        return changed
    }

    fun vibrateWatch(context: Context, durationMs: Long, amplitude: Int) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.let {
            if (it.hasVibrator()) {
                /*
                val vibrationEffect = VibrationEffect.createOneShot(
                    100, // Duration in milliseconds
                    VibrationEffect.DEFAULT_AMPLITUDE
                )

                 */
                val vibrationEffect = VibrationEffect.createOneShot(
                    durationMs, // Duration in milliseconds
                    amplitude
                )
                it.vibrate(vibrationEffect)
            }
        }
    }

    fun convertWifiSsidListToStringWithSeparator(wifiSsidList: List<DataRepository.WifiSsidItem>, separatorStr: String): String {
        var result = ""
        if (!wifiSsidList.isEmpty()) {
            for (wifiSsid in wifiSsidList) {
                if ("".equals(result)) {
                    result = wifiSsid.name
                } else {
                    result = "$result$separatorStr${wifiSsid.name}"
                }
            }
        }
        return result
    }

    fun convertSmsNumbersListToStringWithSeparator(smsNumbersList: List<DataRepository.SmsNumber>, separatorStr: String): String {
        var result = ""
        if (!smsNumbersList.isEmpty()) {
            for (smsNumber in smsNumbersList) {
                if ("".equals(result)) {
                    result = smsNumber.telephoneNumber
                } else {
                    result = "$result$separatorStr${smsNumber.telephoneNumber}"
                }
            }
        }
        return result
    }

    private suspend fun reconcileManagedTakServers(
        configuredServers: List<DataRepository.TakServerItem>,
        managedConfigurationIsAuthoritative: Boolean
    ): Boolean {
        var changed = false
        val handler = Handler(Looper.getMainLooper())
        val uniqueManagedServers = configuredServers.distinctBy(TakServerEndpointIdentity::key)
        val existingServers = takServerList.toList()
        val existingMatches = TakServerManagedReconciler.matchExistingIndexes(
            configured = uniqueManagedServers,
            existing = existingServers
        )

        for ((managedIndex, managedServer) in uniqueManagedServers.withIndex()) {
            val endpointKey = TakServerEndpointIdentity.key(managedServer)
            val matchedExistingIndex = existingMatches[managedIndex]
            val matchedExisting = matchedExistingIndex?.let(existingServers::get)
            val index = matchedExisting?.serverId?.let { serverId ->
                takServerList.indexOfFirst { it.serverId == serverId }
            } ?: matchedExistingIndex ?: -1
            if (index >= 0) {
                val existing = takServerList[index]
                val managedWithStableId = managedServer.copy(serverId = existing.serverId)
                if (managedWithStableId != existing) {
                    changed = true
                    dataRepository.updateTakServerItem(index, managedWithStableId)
                    if (takServerConnDetailsChanged(existing, managedWithStableId)) {
                        handler.postDelayed({
                            EventBus.getDefault().post(MessageEvent(SERVER_CHANGED, index))
                        }, EVENT_SEND_DELAY_MS)
                    }
                    takServerList = dataRepository.awaitTakServerList()
                }
            } else if (dataRepository.addTakServerItem(managedServer) == DataRepository.TakServerAddResult.ADDED) {
                changed = true
                takServerList = dataRepository.awaitTakServerList()
                val addedIndex = takServerList.indexOfFirst {
                    TakServerEndpointIdentity.key(it) == endpointKey
                }
                if (addedIndex >= 0) {
                    handler.postDelayed({
                        EventBus.getDefault().post(MessageEvent(SERVER_ADDED, addedIndex))
                    }, EVENT_SEND_DELAY_MS)
                }
            }
        }

        if (managedConfigurationIsAuthoritative) {
            val managedKeys = uniqueManagedServers.map(TakServerEndpointIdentity::key).toSet()
            val staleIndexes = takServerList.indices.filter {
                TakServerEndpointIdentity.key(takServerList[it]) !in managedKeys
            }.sortedDescending()
            staleIndexes.forEachIndexed { order, staleIndex ->
                dataRepository.removeTakServerItem(takServerList[staleIndex])
                changed = true
                takServerList = dataRepository.awaitTakServerList()
                handler.postDelayed({
                    EventBus.getDefault().post(MessageEvent(SERVER_REMOVED, staleIndex))
                }, EVENT_SEND_DELAY_MS * (order + 1))
            }
        }
        return changed
    }

    private fun handleManagedConfigUpdate(calledOnCreate: Boolean) {
        Log.v(TAG, "=== === Handling Managed Config Update (calledOnCreate: $calledOnCreate) ===")
        // WEAR-508: ManagedConfiguration CEP overlay (`cep_configuration_json`) is owned
        // exclusively by the app-level CepManagedConfigReceiver -> ManagedConfigApplyWorker
        // pipeline. MainActivity only handles non-CEP scalar managed-config keys here.
        seedVariablesFromRepo()

        val context = mainActivity.applicationContext
        val appRestrictions = restrictionsManager.applicationRestrictions
        if (appRestrictions == null) {
            Log.w(TAG, "No managed configuration received. Returning...")
            return
        }

        Log.d(TAG, "Managed Configuration Item Size: ${appRestrictions.keySet().size}")

        /*
        for (key in appRestrictions.keySet()) {
            val value = appRestrictions.get(key)
            // Retrieve the runtime type name, if available
            val typeName = value?.javaClass?.simpleName ?: "null"
            Log.v(TAG, "Key: $key, Value: $value, Type: $typeName")
        }
         */

        val manifestRestrictions = restrictionsManager.getManifestRestrictions(packageName) ?: listOf()
        if (manifestRestrictions.size == 0) {
            Log.w(TAG, "Size of manifestRestrictions is 0. Returning...")
            return
        }

        /*
        for (entry in manifestRestrictions) {
            Log.v(TAG, "Manifest Key: ${entry.key}, Declared Type: ${entry.type}")
            // If the restriction is a bundle or bundle array, check nested restrictions.
            if (entry.type == RestrictionEntry.TYPE_BUNDLE ||
                entry.type == RestrictionEntry.TYPE_BUNDLE_ARRAY) {
                entry.restrictions?.forEach { nested ->
                    Log.v(TAG, "\tNested Key: ${nested.key}, Type: ${nested.type}")
                }
            }
        }
         */

        val managedNetwork = try {
            ManagedNetworkToggles.parse(appRestrictions)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid managed network configuration; update rejected", error)
            Toast.makeText(this, R.string.invalid_managed_network_configuration, Toast.LENGTH_LONG).show()
            return
        }

        // Store previous license before getting new license and validating it
        val prevLicenseIsValid = isLicenseValid()
        // Get the new settings from the restrictions manager or null, so that we only change dataRepository if there are changes
        license = appRestrictions.getString("license_key", license)
        val teamsNames = appRestrictions.getString("custom_teams_names", null)
        val teamsColors = appRestrictions.getString("custom_teams_colors", null)
        val categoryRolesJsonStr = appRestrictions.getString("custom_roles", null)

        val enableTakEndpointConfigUpdate = managedNetwork.tak ?: enableTakEndpoint
        val enableEsriEndpointConfigUpdate = managedNetwork.esri ?: enableEsriEndpoint
        val enableTabletCommandEndpointConfigUpdate =
            managedNetwork.tabletCommand ?: enableTabletCommandEndpoint

        var serverEnableTAKConnectService: Boolean = true
        var serverName: String? = null
        var serverAddress: String? = null
        var serverPort: String? = null
        var serverUseCert: Boolean = false
        var serverUsername: String? = null
        var serverPassword: String? = null
        var serverCertPassword: String? = null
        var serverCertificate: String? = null
        val takServerListConfig: MutableList<DataRepository.TakServerItem> = mutableListOf()
        val serverParcels = appRestrictions.getParcelableArray("tak_server_list")  // we don't know how many configurations will be set on the Knox Managed Configuration page, so we to loop through all the servers in the list.
        if (serverParcels != null) {
            val serverCount = serverParcels.size
            Log.v(TAG, "=== Number of Tak Server Connection Configurations: $serverCount ===")
            // Loop through each server connection
            for (i in serverParcels.indices) {
                // Each element should be a Bundle representing one server connection
                val serverBundle = serverParcels[i] as? Bundle
                if (serverBundle != null) {
                    Log.v(TAG, "=== Server Connection Conf [$i] ===")
                    // Iterate through all keys in this server connection Bundle
                    for (key in serverBundle.keySet()) {
                        val value = serverBundle.get(key)
                        // Format each key/value pair into a fixed-width layout for clarity.
                        Log.v(TAG, String.format("   %-30s : %s", key, value))
                    }

                    // set companion obj by looking for key in bundle, fallback on current
                    serverName = serverBundle.getString("tak_server_connection_name", null) // set companion obj by looking for key, fallback on current
                    serverEnableTAKConnectService = serverBundle.getBoolean("tak_server_connection_enabled", true)
                    serverAddress = serverBundle.getString("tak_server_address", null)
                    serverUseCert = serverBundle.getBoolean("tak_server_use_certificate", false)
                    serverPort = (serverBundle.getInt("tak_server_port", 8089)).toString()
                    serverUsername = serverBundle.getString("tak_server_username", null)
                    serverPassword = serverBundle.getString("tak_server_password", null)
                    serverCertPassword = serverBundle.getString("tak_server_cert_password", null)
                    serverCertificate = serverBundle.getString("tak_server_cert", null)
                    takServerListConfig.add(DataRepository.TakServerItem(serverEnableTAKConnectService, serverName, serverAddress, serverPort.toInt(), serverUseCert, serverUsername, serverPassword, serverCertificate, serverCertPassword))
                    Log.v(TAG, "========================================")
                } else {
                    Log.e(TAG, "Invalid server bundle at index $i")
                }
            }
        } else {
            Log.d(TAG, "tak_server_list is null or empty.")
        }

        val esriEndpointConfigUpdate = appRestrictions.getString("esri_endpoint", null)
        val esriReportingIntervalConfigUpdate = appRestrictions.getInt("esri_reporting_interval")
        Log.d(TAG, "esriEndpoint($esriEndpointConfigUpdate), esriReportingInterval($esriReportingIntervalConfigUpdate)")

        val tabletCommandEndpointConfigUpdate = appRestrictions.getString("tabletcommand_endpoint", null)
        val tabletCommandApikeyConfigUpdate = appRestrictions.getString("tabletcommand_apikey", null)
        val tabletCommandReportingIntervalConfigUpdate = appRestrictions.getInt("tabletcommand_reporting_interval", tabletCommandReportingUpdateInterval)
        Log.d(TAG, "tabletCommandEndpoint($tabletCommandEndpointConfigUpdate), tabletCommandApikey($tabletCommandApikeyConfigUpdate), tabletCommandReportingInterval($tabletCommandReportingIntervalConfigUpdate)")

        val sitxTakUrlConfigUpdate = appRestrictions.getString("sitx_tak_url", null)
        val sitxTakClientIdConfigUpdate = appRestrictions.getString("sitx_tak_client_id", null)

        val enableWifiBatteryOptimizationConfigUpdate = appRestrictions.getBoolean("wifiOptimization_service_enabled", enableWifiBatteryOptimization)
        val enableAllWifiBatteryOptimizationConfigUpdate = appRestrictions.getBoolean("wifiOptimization_all_enabled", enableAllWifiBatteryOptimization)
        val wifiOptimizationSsidString = appRestrictions.getString("wifiOptimization_ssid_list", convertWifiSsidListToStringWithSeparator(wifiSsidList, "||"))
        val wifiOptimizationSsidList: List<DataRepository.WifiSsidItem> = wifiOptimizationSsidString.split("||") // Split by two pipes, unlikely a random generated SSID has
            .map { it.trim() } // Trim whitespace from each SSID
            .filter { it.isNotEmpty() } // Remove any empty values
            .map { DataRepository.WifiSsidItem(it) } // Map each SSID to a WifiSsidItem

        /*
         *  Maps are used for debugging the status of all the Services.
         *   - knoxMap gives us the Knox Manage config value for each restriction.
         *        Null means it is unconfigured.
         *   - localMap gives the local companion object that we fall back to.
         *        This will be deprecated, but useful for the time being.
         *   - CfgUpdateMap gives use Boolean that is stored persistently in the
         *        data repository. If Knox is null, fall back on the companion object,
         *        which we recently obtained from persistent store in seedVariablesFromRepo()
         *
         *    Note that unconfigurable services will always be null (e.g. TakConnectService, CotDispatch).
         */

        val knoxMap      = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)
        val localMap     = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)
        val cfgUpdateMap = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)

        knoxMap[ServiceType.LOCATION] = appRestrictions.getNullableBoolean("location_service_enabled")
        knoxMap[ServiceType.PHYSIO] =  appRestrictions.getNullableBoolean("physiological_services_enabled")
        knoxMap[ServiceType.HEART_RATE] = appRestrictions.getNullableBoolean("heartrate_service_enabled")
        knoxMap[ServiceType.ACTIVITY_RECOGNITION] = appRestrictions.getNullableBoolean("activity_recognition_service_enabled")
        knoxMap[ServiceType.SKIN_TEMP] = appRestrictions.getNullableBoolean("skin_temp_service_enabled")
        knoxMap[ServiceType.DITTO] = appRestrictions.getNullableBoolean("ditto_service_enabled")
        knoxMap[ServiceType.CHAT] = appRestrictions.getNullableBoolean("chat_service_enabled")
        knoxMap[ServiceType.BLE_BEACON] = appRestrictions.getNullableBoolean("ble_beacon_service_enabled")
        knoxMap[ServiceType.HAIL]       = appRestrictions.getNullableBoolean("hail_service_enabled")
        knoxMap[ServiceType.SITX_SERVICE]       = appRestrictions.getNullableBoolean("sitx_service_enabled")
        knoxMap[ServiceType.GEOFENCE] = appRestrictions.getNullableBoolean("geofence_service_enabled")
        knoxMap[ServiceType.TAK_CONNECT]           = null
        knoxMap[ServiceType.COT_DISPATCH]          = null
        knoxMap[ServiceType.MOVEMENT_DETECTION]    = null
        knoxMap[ServiceType.BATTERY]               = null
        knoxMap[ServiceType.POWER_MGR]             = null

        runBlocking {
            dataRepository.updateDebugStageRemoteKnox(knoxMap)
        }

        localMap[ServiceType.LOCATION]   = enableLocationService
        localMap[ServiceType.PHYSIO]     = enablePhysiologicalServices
        localMap[ServiceType.HEART_RATE] = enableHeartrateService
        localMap[ServiceType.ACTIVITY_RECOGNITION] = enableActRecService
        localMap[ServiceType.SKIN_TEMP]  = enableSkinTempService
        localMap[ServiceType.DITTO]      = enableDittoService
        localMap[ServiceType.COMPANION_APP] = enableCompanionAppService
        localMap[ServiceType.BLE_BEACON] = enableBleBeaconService
        localMap[ServiceType.HAIL]        = enableHailService
        localMap[ServiceType.GEOFENCE] = enableGeofenceService
        localMap[ServiceType.SITX_SERVICE] = enableSitxService
        localMap[ServiceType.TAK_CONNECT]           = null
        localMap[ServiceType.COT_DISPATCH]          = null
        localMap[ServiceType.MOVEMENT_DETECTION]    = null
        localMap[ServiceType.BATTERY]               = null
//        localMap[ServiceType.BATTERY_ALERTS] = enableBatteryAlerts
        localMap[ServiceType.POWER_MGR]             = null

        runBlocking {
            dataRepository.updateDebugStageLocalPersisted(localMap)
        }

        val enableLocationServiceConfigUpdate =
            appRestrictions.getBoolean("location_service_enabled", enableLocationService)
        val enablePhysiologicalServicesConfigUpdate =
            appRestrictions.getBoolean("physiological_service_enabled", enablePhysiologicalServices)
        val enableHeartrateServiceConfigUpdate =
            appRestrictions.getBoolean("heartrate_service_enabled", enableHeartrateService)
        val enableActivityRecognitionServiceConfigUpdate =
            appRestrictions.getBoolean("activity_recognition_service_enabled", enableActRecService)
        val enableSkinTempServiceConfigUpdate =
            appRestrictions.getBoolean("skin_temp_service_enabled", enableSkinTempService)
        val enableDittoServiceConfigUpdate =
            managedNetwork.ditto ?: enableDittoService
        val enableBloodhoundProximityVibrationConfigUpdate = appRestrictions.getBoolean("enable_bloodhound_proximity_vibration", enableBloodhoundProximityVibration)
        val bloodhoundProximityRadiusMetersConfigUpdate = appRestrictions.getInt("bloodhound_proximity_radius_meters", bloodhoundProximityRadiusMeters)
        val bloodhoundProximityIntensityConfigUpdate = appRestrictions.getString("bloodhound_proximity_intensity", bloodhoundProximityIntensity) ?: bloodhoundProximityIntensity
        val enableBleBeaconServiceConfigUpdate =
            appRestrictions.getBoolean("ble_beacon_service_enabled", enableBleBeaconService)
        val enableHailServiceConfigUpdate = appRestrictions.getBoolean("hail_service_enabled", enableHailService)
        val enableSitxServiceConfigUpdate = managedNetwork.sitx ?: enableSitxService
        val enableImmersionAlertsConfigUpdate = appRestrictions.getBoolean("enable_immersion_alerts", enableImmersionAlerts)
        val enableLowAtmPressureAlertConfigUpdate = appRestrictions.getBoolean("enable_low_atm_pressure_alert", enableLowAtmPressureAlert)
        val enableHighAtmPressureAlertConfigUpdate = appRestrictions.getBoolean("enable_high_atm_pressure_alert", enableHighAtmPressureAlert)
        val lowAtmPressureAlertThresholdConfigUpdate = appRestrictions.getInt("low_atm_pressure_alert_threshold", lowAtmPressureAlertThreshold)
        val highAtmPressureAlertThresholdConfigUpdate = appRestrictions.getInt("high_atm_pressure_alert_threshold", highAtmPressureAlertThreshold)
        val enableBatdokCotConfigUpdate = appRestrictions.getBoolean("batdok_enabled", enableBatdokCot)
        val enableMulticastConfigUpdate = managedNetwork.multicast ?: enableMulticastOnWifi
        val enableGeofenceServiceConfigUpdate = appRestrictions.getBoolean("geofence_service_enabled", enableGeofenceService)

        cfgUpdateMap[ServiceType.LOCATION] = enableLocationServiceConfigUpdate
        cfgUpdateMap[ServiceType.PHYSIO]   = enablePhysiologicalServicesConfigUpdate
        cfgUpdateMap[ServiceType.HEART_RATE] = enableHeartrateServiceConfigUpdate
        cfgUpdateMap[ServiceType.ACTIVITY_RECOGNITION] = enableActivityRecognitionServiceConfigUpdate
        cfgUpdateMap[ServiceType.SKIN_TEMP] = enableSkinTempServiceConfigUpdate
        cfgUpdateMap[ServiceType.DITTO] = enableDittoServiceConfigUpdate
        cfgUpdateMap[ServiceType.BLE_BEACON] = enableBleBeaconServiceConfigUpdate
        cfgUpdateMap[ServiceType.HAIL]       = enableHailServiceConfigUpdate
        cfgUpdateMap[ServiceType.SITX_SERVICE] = enableSitxServiceConfigUpdate
        cfgUpdateMap[ServiceType.GEOFENCE] = enableGeofenceServiceConfigUpdate
        cfgUpdateMap[ServiceType.TAK_CONNECT]           = null
        cfgUpdateMap[ServiceType.COT_DISPATCH]          = null
        cfgUpdateMap[ServiceType.MOVEMENT_DETECTION]    = null
        cfgUpdateMap[ServiceType.BATTERY]               = null
        cfgUpdateMap[ServiceType.POWER_MGR]             = null

        runBlocking {
            dataRepository.updateDebugStageConfigUpdate(cfgUpdateMap)
        }

        val configJson = appRestrictions.getString("custom_developer_config_json", "")

        val dittoAppIdConfigUpdate = appRestrictions.getString("ditto_app_id", "")
        val dittoAuthTokenConfigUpdate = appRestrictions.getString("ditto_auth_token", "")

        val companionServiceSetting = resolveManagedCompanionServiceSetting(
            managedOverride = managedNetwork.companion,
            persistedValue = enableCompanionAppService
        )
        val enableCompanionAppServiceConfigUpdate = companionServiceSetting.enabled

        val enableDynamicReportingStrategyConfigUpdate = appRestrictions.getBoolean("enable_dynamic_reporting_strategy", enableDynamicReportingStrategy)
        val constantReportingIntervalConfigUpdate = appRestrictions.getInt("constant_reporting_interval", constantReportingInterval)
        val whileAlertingReportingIntervalConfigUpdate = appRestrictions.getInt("alerting_reporting_interval", whileAlertingReportingInterval)
        val onFootReportingIntervalConfigUpdate = appRestrictions.getInt("on_foot_walking_reporting_interval", onFootReportingInterval)
        val vehicleReportingIntervalConfigUpdate = appRestrictions.getInt("in_vehicle_reporting_interval", vehicleReportingInterval)
        val stationaryReportingIntervalConfigUpdate = appRestrictions.getInt("stationary_reporting_interval", stationaryReportingInterval)
        Log.d(TAG, "ConfigUpdate: $enableDynamicReportingStrategyConfigUpdate, constRI=$constantReportingIntervalConfigUpdate, $whileAlertingReportingIntervalConfigUpdate, $onFootReportingIntervalConfigUpdate, $vehicleReportingIntervalConfigUpdate, $stationaryReportingIntervalConfigUpdate")

        val enableSmsForAlertsConfigUpdate = appRestrictions.getBoolean("enable_sms", enableSmsForAlerts)
        val smsNumbersString = appRestrictions.getString("sms_numbers_comma_separated", convertSmsNumbersListToStringWithSeparator(smsNumbersList, ","))
        val smsNumbersListConfigUpdate: List<DataRepository.SmsNumber> = smsNumbersString.split(",")
            .map { it.trim() } // Trim whitespace from each SSID
            .filter { it.isNotEmpty() } // Remove any empty values
            .map { DataRepository.SmsNumber(it) } // Map each SSID to a WifiSsidItem

        val enableBatteryAlertsConfigUpdate = appRestrictions.getBoolean("enable_battery_alerts", enableBatteryAlerts)
        val battWarnPercentConfigUpdate = appRestrictions.getInt("batt_warn_percent", battWarnPercent)
        val battAlertPercentConfigUpdate = appRestrictions.getInt("batt_alert_percent", battAlertPercent)
        val enablePhysiologicalAlertsConfigUpdate = appRestrictions.getBoolean("enable_physiological_alerts", enablePhysiologicalAlerts)

        val physiologicalMetricsRestingHeartRateConfigUpdate = appRestrictions.getInt("physiological_metrics_resting_heart_rate", restingHeartRate)
        val physiologicalMetricsBirthYearServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_birth_year", birthYearValue)
        val physiologicalMetricsHeightFeetServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_height_feet", heightFeetValue)
        val physiologicalMetricsHeightInchesServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_height_inches", heightInchesValue)
        val physiologicalMetricsWeightServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_weight", weightValue)
        val physiologicalMetricsSexServiceConfigUpdate = appRestrictions.getString("physiological_metrics_sex", sexValue)
        val physiologicalMetricsBloodTypeServiceConfigUpdate = appRestrictions.getString("physiological_metrics_blood_type", bloodTypeValue)
        val physiologicalMetricsAllergiesServiceConfigUpdate = appRestrictions.getStringArray("physiological_metrics_allergies")
        val physiologicalMetricsUserTypeServiceConfigUpdate = appRestrictions.getString("physiological_metrics_user_type", userTypeValue)
        val physiologicalMetricsUniformWaistSizeConfigUpdate = appRestrictions.getInt("physiological_metrics_uniform_waist_size", uniformWaistSize)
        val physiologicalMetricsStrideLengthConfigUpdate = appRestrictions.getInt("physiological_metrics_stride_length", strideLength)
        val physiologicalMetricsUniformPantsLengthConfigUpdate = appRestrictions.getInt("physiological_metrics_uniform_pants_length", uniformPantsLength)
        val physiologicalMetricsLoadoutWeightConfigUpdate = appRestrictions.getInt("physiological_metrics_loadout_weight", loadoutWeight)
        val restingHrHighAlertBpmThresholdServiceConfigUpdate = appRestrictions.getInt("resting_hr_high_alert_bpm_threshold", restingHrHighAlertBpmThreshold)
        val restingHrLowAlertBpmThresholdServiceConfigUpdate = appRestrictions.getInt("resting_hr_low_alert_bpm_threshold", restingHrLowAlertBpmThreshold)
        val highRestingHrWarningThresholdLengthServiceConfigUpdate = appRestrictions.getInt("high_resting_hr_warning_threshold_length", highRestingHrWarningThresholdLength)
        val lowRestingHrWarningThresholdLengthServiceConfigUpdate = appRestrictions.getInt("low_resting_hr_warning_threshold_length", lowRestingHrWarningThresholdLength)
        val highRestingHrAlertThresholdLengthServiceConfigUpdate = appRestrictions.getInt("high_resting_hr_alert_threshold_length", highRestingHrAlertThresholdLength)
        val lowRestingHrAlertThresholdLengthServiceConfigUpdate = appRestrictions.getInt("low_resting_hr_alert_threshold_length", lowRestingHrAlertThresholdLength)

        val exertionThresholdWarningServiceConfigUpdate = appRestrictions.getInt("exertion_threshold_warning", exertionThresholdWarning)
        val exertionThresholdAlertServiceConfigUpdate = appRestrictions.getInt("exertion_threshold_alert", exertionThresholdAlert)
        val exertionWarningThresholdLengthServiceConfigUpdate = appRestrictions.getInt("exertion_warning_threshold_length", exertionWarningThresholdLength)
        val exertionAlertThresholdLengthServiceConfigUpdate = appRestrictions.getInt("exertion_alert_threshold_length", exertionAlertThresholdLength)

        // NEW: GeoJSON-based iBeacon parsing (replaces parcel/bundle array)
        val bleBeaconMutableList: MutableList<DataRepository.LocalizationBleBeaconItem> = mutableListOf()
        val geoJsonStr = appRestrictions.getString("localization_iBeacon_geojson", null)

        if (!geoJsonStr.isNullOrBlank()) {
            try {
                val parsed = parseIBeaconGeoJsonToItems(geoJsonStr)
                if (parsed.isNotEmpty()) {
                    bleBeaconMutableList.addAll(parsed)
                    Log.v(TAG, "=== Number of Localization iBeacons (GeoJSON): ${parsed.size} ===")
                } else {
                    Log.w(TAG, "GeoJSON parsed but produced 0 iBeacons. Leaving prior list untouched.")
                }
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "Failed to parse iBeacon GeoJSON (syntax): ${e.message}", e)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to parse iBeacon GeoJSON: ${t.message}", t)
            }
        } else {
//            Log.d(TAG, "localization_iBeacon_list is null or empty.")
        }

        // store settings persistently & send out events as necessary
        validateLicense(license) // validate the license

        runBlocking {
            var changed = false
            if (enableWifiBatteryOptimization != enableWifiBatteryOptimizationConfigUpdate) {
                enableWifiBatteryOptimization = enableWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableWifiBatteryOptimization(enableWifiBatteryOptimization)
                Log.d(
                    TAG,
                    "enableWifiBatteryOptimization changed to $enableWifiBatteryOptimization"
                )
                changed = true
            }
            if (enableAllWifiBatteryOptimization != enableAllWifiBatteryOptimizationConfigUpdate) {
                enableAllWifiBatteryOptimization = enableAllWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableAllWifiBatteryOptimization(
                    enableAllWifiBatteryOptimization
                )
                Log.d(
                    TAG,
                    "enableAllWifiBatteryOptimization changed to $enableAllWifiBatteryOptimization"
                )
                changed = true
            }
            if (wifiSsidList != wifiOptimizationSsidList) {
                Log.d(
                    TAG,
                    "MC wifiSsidList($wifiOptimizationSsidList), wifiSsidList($wifiSsidList)"
                )
                wifiSsidList = wifiOptimizationSsidList
                dataRepository.storeWifiSsidList(wifiSsidList)
                changed = true
            }
            if (changed) {
                // Send PowerManager Event only if there is a change in wifi optimization settings
                EventBus.getDefault()
                    .post(MessageEvent(OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED, 0))
            }

            if (categoryRolesJsonStr != null) {
                // 1. Prepare a Gson instance and the List<RoleCategoryEntry> type token
                val gson = Gson()
                val listType = object : TypeToken<List<CategoryRolesEntry>>() {}.type
                lateinit var entries: List<CategoryRolesEntry>

                try {
                    // 2. Parse the managed‐config JSON into your model class
                    entries = gson.fromJson(categoryRolesJsonStr, listType)
                    Log.i(TAG, "Attempting to parse the managed configs JSON String:  $entries")

                    // 3. Cache it into DataRepository (which now uses Gson under the hood)
                    dataRepository.storeCategoryRolesList(entries)

                } catch (e: JsonSyntaxException) {
                    Log.w(TAG, "Failed to parse managed‐config JSON, falling back", e)

                    // 4. Fallback to last‐stored in DataStore or to raw‐resource defaults
                    val fallback = dataRepository.getCategoryRolesList.first()
                    entries = if (fallback.isNotEmpty()) {
                        fallback
                    } else {
                        applicationContext.defaultRoleCategoryEntriesFromRaw()
                    }
                    dataRepository.storeCategoryRolesList(entries)
                }
                initCategoryAndRoleAndCategoryRolesList(entries)
            }

            if ((null != teamsNames) && (null != teamsColors)) {
                Log.v(TAG, "teamsNames: $teamsNames")
                Log.v(TAG, "teamsColors: $teamsColors")
                if (null != teamsNames) {
                    dataRepository.storeTeamNames(teamsNames)
                }
                if (null != teamsColors) {
                    dataRepository.storeTeamColors(teamsColors)
                }
                initTeamEntriesValuesList(teamsNames.split(","), teamsColors.split(","))
                initTeamAndTeamValue()
            }
            if ((null != categoryRolesJsonStr) || ((null != teamsNames) && (null != teamsColors))) {
                // Send out EventBus message even if there is no actual change
                EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
            }

            changed = reconcileManagedTakServers(
                takServerListConfig,
                serverParcels != null
            )

            if ((null != esriEndpointConfigUpdate) && (esriEndpoint != esriEndpointConfigUpdate)) {
                esriEndpoint = esriEndpointConfigUpdate
                dataRepository.storeEsriEndpoint(esriEndpointConfigUpdate)
            }
            if ((0 != esriReportingIntervalConfigUpdate) && (esriReportingUpdateInterval != esriReportingIntervalConfigUpdate)) {
                esriReportingUpdateInterval = esriReportingIntervalConfigUpdate
                dataRepository.storeEsriReportingUpdateInterval(esriReportingIntervalConfigUpdate)
            }
            if (managedNetwork.esri != null && enableEsriEndpointConfigUpdate != enableEsriEndpoint) {
                enableEsriEndpoint = enableEsriEndpointConfigUpdate
                dataRepository.storeEnableEsriEndpoint(enableEsriEndpointConfigUpdate)
            }

            if ((null != tabletCommandEndpointConfigUpdate) && (tabletCommandEndpoint != tabletCommandEndpointConfigUpdate)) {
                tabletCommandEndpoint = tabletCommandEndpointConfigUpdate
                dataRepository.storeTabletCommandEndpoint(tabletCommandEndpointConfigUpdate)
            }
            if ((null != tabletCommandApikeyConfigUpdate) && (tabletCommandApikey != tabletCommandApikeyConfigUpdate)) {
                tabletCommandApikey = tabletCommandApikeyConfigUpdate
                dataRepository.storeTabletCommandApikey(tabletCommandApikeyConfigUpdate)
            }
            if ((0 != tabletCommandReportingIntervalConfigUpdate) && (tabletCommandReportingUpdateInterval != tabletCommandReportingIntervalConfigUpdate)) {
                tabletCommandReportingUpdateInterval = tabletCommandReportingIntervalConfigUpdate
                dataRepository.storeTabletCommandReportingUpdateInterval(
                    tabletCommandReportingIntervalConfigUpdate
                )
            }
            if (managedNetwork.tabletCommand != null && enableTabletCommandEndpointConfigUpdate != enableTabletCommandEndpoint) {
                enableTabletCommandEndpoint = enableTabletCommandEndpointConfigUpdate
                dataRepository.storeEnableTabletCommandEndpoint(
                    enableTabletCommandEndpointConfigUpdate
                )
            }

            if (enableTakEndpointConfigUpdate != enableTakEndpoint) {
                enableTakEndpoint = enableTakEndpointConfigUpdate
            }

            if ((null != sitxTakUrlConfigUpdate) && (sitxTakUrl != sitxTakUrlConfigUpdate)) {
                sitxTakUrl = sitxTakUrlConfigUpdate
                sitxRepo.setBaseUrl(sitxTakUrlConfigUpdate)
            }
            if ((null != sitxTakClientIdConfigUpdate) && (sitxTakClientId != sitxTakClientIdConfigUpdate)) {
                sitxTakClientId = sitxTakClientIdConfigUpdate
                sitxRepo.setClientId(sitxTakClientIdConfigUpdate)
            }

            changed = false
            if (bleBeaconMutableList.size > 0) {
                if (bleBeaconMutableList.size != localizationBleBeaconItemList.size) {
                    changed = true
                } else {
                    for (i in bleBeaconMutableList.indices) {
                        val bleBeaconUpdate = bleBeaconMutableList[i]
                        val locBleBeacon = localizationBleBeaconItemList[i]
                        if ((bleBeaconUpdate.uuid != locBleBeacon.uuid) ||
                            (bleBeaconUpdate.major != locBleBeacon.major) ||
                            (bleBeaconUpdate.minor != locBleBeacon.minor) ||
                            (bleBeaconUpdate.lat != locBleBeacon.lat) ||
                            (bleBeaconUpdate.lon != locBleBeacon.lon) ||
                            (bleBeaconUpdate.alt != locBleBeacon.alt)
                        ) {
                            changed = true
                            break
                        }
                    }
                }
                if (true == changed) {
                    localizationBleBeaconItemList = bleBeaconMutableList.toList()
                    dataRepository.storeLocalizationBleBeaconList(localizationBleBeaconItemList)
                    Handler(Looper.getMainLooper()).postDelayed({
                        EventBus.getDefault().post(MessageEvent(LOCALIZATION_BEACONS_CHANGED, 0))
                    }, EVENT_SEND_DELAY_MS)
                }
            }

            dataRepository.storeLicense(license)
            if (!dittoAppIdConfigUpdate.isEmpty()) {
                if (dittoAppId != dittoAppIdConfigUpdate) {
                    dittoAppId = dittoAppIdConfigUpdate
                    dataRepository.storeDittoAppId(dittoAppIdConfigUpdate)
                }
            }
            if (!dittoAuthTokenConfigUpdate.isEmpty()) {
                if (dittoAuthToken != dittoAuthTokenConfigUpdate) {
                    dittoAuthToken = dittoAuthTokenConfigUpdate
                    dataRepository.storeDittoAuthToken(dittoAuthTokenConfigUpdate)
                }
            }

            dataRepository.storeEnableLocationService(enableLocationServiceConfigUpdate)
            dataRepository.storeEnableGeofenceService(enableGeofenceServiceConfigUpdate)
            dataRepository.storeEnablePhysiologicalServices(enablePhysiologicalServicesConfigUpdate)
            dataRepository.storeEnableHeartRateService(enableHeartrateServiceConfigUpdate)
            dataRepository.storeEnableActivityRecognitionService(
                enableActivityRecognitionServiceConfigUpdate
            )
            dataRepository.storeEnableSkinTempService(enableSkinTempServiceConfigUpdate)
            dataRepository.storeManagedNetworkToggles(
                tak = managedNetwork.tak,
                multicast = managedNetwork.multicast,
                sitx = managedNetwork.sitx,
                ditto = managedNetwork.ditto,
                companion = managedNetwork.companion
            )
            dataRepository.storeEnableBleBeaconService(enableBleBeaconServiceConfigUpdate)
            if (constantReportingInterval != constantReportingIntervalConfigUpdate) {
                constantReportingInterval = constantReportingIntervalConfigUpdate
                dataRepository.storeConstantReportingInterval(constantReportingIntervalConfigUpdate)
            }
            if (whileAlertingReportingInterval != whileAlertingReportingIntervalConfigUpdate) {
                whileAlertingReportingInterval = whileAlertingReportingIntervalConfigUpdate
                dataRepository.storeWhileAlertingReportingInterval(
                    whileAlertingReportingIntervalConfigUpdate
                )
            }
            if (onFootReportingInterval != onFootReportingIntervalConfigUpdate) {
                onFootReportingInterval = onFootReportingIntervalConfigUpdate
                dataRepository.storeOnFootReportingInterval(onFootReportingIntervalConfigUpdate)
            }
            if (vehicleReportingInterval != vehicleReportingIntervalConfigUpdate) {
                vehicleReportingInterval = vehicleReportingIntervalConfigUpdate
                dataRepository.storeVehicleReportingInterval(vehicleReportingIntervalConfigUpdate)
            }
            if (stationaryReportingInterval != stationaryReportingIntervalConfigUpdate) {
                stationaryReportingInterval = stationaryReportingIntervalConfigUpdate
                dataRepository.storeStationaryReportingInterval(
                    stationaryReportingIntervalConfigUpdate
                )
            }
            if (enableDynamicReportingStrategy != enableDynamicReportingStrategyConfigUpdate) {
                dataRepository.storeEnableDynamicReportingStrategy(
                    enableDynamicReportingStrategyConfigUpdate
                )
            }

            dataRepository.storeEnableImmersionAlerts(enableImmersionAlertsConfigUpdate)
            dataRepository.storeEnableLowAtmPressureAlert(enableLowAtmPressureAlertConfigUpdate)
            dataRepository.storeLowAtmPressureAlertThreshold(lowAtmPressureAlertThresholdConfigUpdate)
            dataRepository.storeEnableHighAtmPressureAlert(enableHighAtmPressureAlertConfigUpdate)
            dataRepository.storeHighAtmPressureAlertThreshold(highAtmPressureAlertThresholdConfigUpdate)
            dataRepository.storeEnableBloodhoundProximityVibration(enableBloodhoundProximityVibrationConfigUpdate)
            dataRepository.storeBloodhoundProximityRadiusMeters(bloodhoundProximityRadiusMetersConfigUpdate)
            if (appRestrictions.containsKey(UnitPrefs.KEY_RANGE_UNITS)) {
                runCatching {
                    dataRepository.storeUnitPrefs(
                        mapOf(UnitPrefs.KEY_RANGE_UNITS to appRestrictions.getString(UnitPrefs.KEY_RANGE_UNITS))
                    )
                }.onFailure { Log.w(TAG, "Ignoring invalid managed ${UnitPrefs.KEY_RANGE_UNITS}") }
            }
            dataRepository.storeBloodhoundProximityIntensity(bloodhoundProximityIntensityConfigUpdate)
            dataRepository.storeEnableHailService(enableHailServiceConfigUpdate)
            dataRepository.storeEnableBatdokCot(enableBatdokCotConfigUpdate)
            dataRepository.storeEnableSmsForAlerts(enableSmsForAlertsConfigUpdate)
            dataRepository.storeSmsNumbersList(smsNumbersListConfigUpdate)
            dataRepository.storeEnableBatteryAlerts(enableBatteryAlertsConfigUpdate)
            dataRepository.storeBatteryWarnPercentage(battWarnPercentConfigUpdate)
            dataRepository.storeBatteryAlertPercentage(battAlertPercentConfigUpdate)
            dataRepository.storeEnablePhysiologicalAlerts(enablePhysiologicalAlertsConfigUpdate)
            dataRepository.storeRestingHeartRate(physiologicalMetricsRestingHeartRateConfigUpdate)
            dataRepository.storeYearOfBirth(physiologicalMetricsBirthYearServiceConfigUpdate)
            dataRepository.storePhysiologicalMetricHeightFeet(
                physiologicalMetricsHeightFeetServiceConfigUpdate
            )
            dataRepository.storePhysiologicalMetricHeightInches(
                physiologicalMetricsHeightInchesServiceConfigUpdate
            )
            dataRepository.storePhysiologicalMetricWeight(
                physiologicalMetricsWeightServiceConfigUpdate
            )
            dataRepository.storePhysiologicalMetricSex(physiologicalMetricsSexServiceConfigUpdate)
            dataRepository.storePhysiologicalMetricBloodType(
                physiologicalMetricsBloodTypeServiceConfigUpdate
            )
//            if ((null != physiologicalMetricsAllergiesServiceConfigUpdate) && physiologicalMetricsAllergiesServiceConfigUpdate.isNotEmpty()) {
//                dataRepository.storePhysiologicalMetricAllergyList(
//                    physiologicalMetricsAllergiesServiceConfigUpdate.map { str ->
//                        DataRepository.AllergyItem(name = str)
//                    } ?: listOf(DataRepository.AllergyItem("N/A")))
//            }
            dataRepository.storePhysiologicalMetricUserType(
                physiologicalMetricsUserTypeServiceConfigUpdate
            )
            dataRepository.storeRestingHrHighAlertBpmThreshold(restingHrHighAlertBpmThresholdServiceConfigUpdate)
            dataRepository.storeRestingHrLowAlertBpmThreshold(restingHrLowAlertBpmThresholdServiceConfigUpdate)
            dataRepository.storeHighRestingHrWarningThresholdLength(highRestingHrWarningThresholdLengthServiceConfigUpdate)
            dataRepository.storeLowRestingHrWarningThresholdLength(lowRestingHrWarningThresholdLengthServiceConfigUpdate)
            dataRepository.storeHighRestingHrAlertThresholdLength(highRestingHrAlertThresholdLengthServiceConfigUpdate)
            dataRepository.storeLowRestingHrAlertThresholdLength(lowRestingHrAlertThresholdLengthServiceConfigUpdate)
            dataRepository.storeExertionThresholdWarning(exertionThresholdWarningServiceConfigUpdate)
            dataRepository.storeExertionThresholdAlert(exertionThresholdAlertServiceConfigUpdate)
            dataRepository.storeExertionWarningThresholdLength(exertionWarningThresholdLengthServiceConfigUpdate)
            dataRepository.storeExertionAlertThresholdLength(exertionAlertThresholdLengthServiceConfigUpdate)
            dataRepository.storePhysiologicalMetricUniformWaistSize(physiologicalMetricsUniformWaistSizeConfigUpdate)
            dataRepository.storePhysiologicalMetricStrideLength(physiologicalMetricsStrideLengthConfigUpdate)
            dataRepository.storePhysiologicalMetricUniformPantsLength(physiologicalMetricsUniformPantsLengthConfigUpdate)
            dataRepository.storePhysiologicalMetricLoadoutWeight(physiologicalMetricsLoadoutWeightConfigUpdate)
            //dataRepository.storeHrHighAlertBpmThreshold(hrHighAlertBpmThresholdConfigUpdate)
            //dataRepository.storeHrHighAlertNumSecsThreshold(hrHighAlertNumSecsThresholdConfigUpdate)

            if (!isStringEmptyJson(configJson)) {
                // Currently don't check for any changes but just post to dataRepository
                dataRepository.storeCustomDeveloperConfigJson(configJson)
                Handler(Looper.getMainLooper()).postDelayed({
                    EventBus.getDefault()
                        .post(MessageEvent(CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED, 0))
                }, EVENT_SEND_DELAY_MS)
            }

            // At the end, enable / disable services and then enable/disable based on license
            if (enableLocationServiceConfigUpdate != enableLocationService) {
                enableLocationService = enableLocationServiceConfigUpdate
                Log.d(TAG, "enableLocationService=$enableLocationService")
                if (!calledOnCreate) dataRepository.storeEnableLocationService(enableLocationService)
            }
            if (enableGeofenceServiceConfigUpdate != enableGeofenceService) {
                enableGeofenceService = enableGeofenceServiceConfigUpdate
                if (!calledOnCreate) dataRepository.storeEnableGeofenceService(enableGeofenceService)
            }
            if (enableSitxServiceConfigUpdate != enableSitxService) {
                enableSitxService = enableSitxServiceConfigUpdate
            }
            if (enablePhysiologicalServicesConfigUpdate != enablePhysiologicalServices) {
                enablePhysiologicalServices = enablePhysiologicalServicesConfigUpdate
                Log.d(TAG, "enablePhysiologicalServices=$enablePhysiologicalServices")
                if (!calledOnCreate) {
                    dataRepository.storeEnablePhysioServices(enablePhysiologicalServices)
                }
            }
            if (enableHeartrateServiceConfigUpdate != enableHeartrateService) {
                enableHeartrateService = enableHeartrateServiceConfigUpdate
                Log.d(TAG, "enableHeartRateService=$enableHeartrateService")
                if (!calledOnCreate) {
                    dataRepository.storeEnableHeartRateService(enableHeartrateService)
                }
            }
            if (enableActivityRecognitionServiceConfigUpdate != enableActRecService) {
                enableActRecService = enableActivityRecognitionServiceConfigUpdate
                Log.d(TAG, "enableActivityRecognitionService=$enableActRecService")
                if (!calledOnCreate) dataRepository.storeEnableActivityRecognitionService(
                    enableActRecService
                )
            }
            if (enableSkinTempServiceConfigUpdate != enableSkinTempService) {
                enableSkinTempService = enableSkinTempServiceConfigUpdate
                Log.d(TAG, "enableSkinTempService=$enableSkinTempService")
                if (!calledOnCreate) dataRepository.storeEnableSkinTempService(enableSkinTempService)
            }
            if (enableDittoServiceConfigUpdate != enableDittoService) {
                enableDittoService = enableDittoServiceConfigUpdate
                Log.d(TAG, "enableDittoService=$enableDittoService")
            }
            if (enableCompanionAppServiceConfigUpdate != enableCompanionAppService) {
                enableCompanionAppService = enableCompanionAppServiceConfigUpdate
                Log.d(TAG, "enableCompanionAppService=$enableCompanionAppService")
            }
            if (enableBleBeaconServiceConfigUpdate != enableBleBeaconService) {
                enableBleBeaconService = enableBleBeaconServiceConfigUpdate
                Log.d(TAG, "enableBleBeaconService=$enableBleBeaconService")
                if (!calledOnCreate) dataRepository.storeEnableBleBeaconService(
                    enableBleBeaconService
                )
            }
            if (enableImmersionAlertsConfigUpdate != enableImmersionAlerts) {
                enableImmersionAlerts = enableImmersionAlertsConfigUpdate
                Log.i(TAG, "enableImmersionAlertsConfigUpdate=$enableImmersionAlerts")
                if (!calledOnCreate) dataRepository.storeEnableImmersionAlerts(enableImmersionAlerts)
            }
            if (enableLowAtmPressureAlertConfigUpdate != enableLowAtmPressureAlert) {
                enableLowAtmPressureAlert = enableLowAtmPressureAlertConfigUpdate
                Log.i(TAG, "enableLowAtmPressureAlertConfigUpdate=$enableLowAtmPressureAlert")
                if (!calledOnCreate) dataRepository.storeEnableLowAtmPressureAlert(enableLowAtmPressureAlert)
            }
            if (enableHighAtmPressureAlertConfigUpdate != enableHighAtmPressureAlert) {
                enableHighAtmPressureAlert = enableHighAtmPressureAlertConfigUpdate
                Log.i(TAG, "enableHighAtmPressureAlertConfigUpdate=$enableHighAtmPressureAlert")
                if (!calledOnCreate) dataRepository.storeEnableHighAtmPressureAlert(enableHighAtmPressureAlert)
            }
            if (lowAtmPressureAlertThresholdConfigUpdate != lowAtmPressureAlertThreshold) {
                lowAtmPressureAlertThreshold = lowAtmPressureAlertThresholdConfigUpdate
                Log.i(TAG, "lowAtmPressureAlertThresholdConfigUpdate=$lowAtmPressureAlertThreshold")
                if (!calledOnCreate) dataRepository.storeLowAtmPressureAlertThreshold(lowAtmPressureAlertThreshold)
            }
            if (highAtmPressureAlertThresholdConfigUpdate != highAtmPressureAlertThreshold) {
                highAtmPressureAlertThreshold = highAtmPressureAlertThresholdConfigUpdate
                Log.i(TAG, "highAtmPressureAlertThresholdConfigUpdate=$highAtmPressureAlertThreshold")
                if (!calledOnCreate) dataRepository.storeHighAtmPressureAlertThreshold(highAtmPressureAlertThreshold)
            }

            if (enableDynamicReportingStrategy != enableDynamicReportingStrategyConfigUpdate) {
                enableDynamicReportingStrategy = enableDynamicReportingStrategyConfigUpdate
                Log.d(TAG, "enableDynamicReportingStrategy=$enableDynamicReportingStrategy")
                if (!calledOnCreate) dataRepository.getEnableMovementDetectionService.emit(
                    enableDynamicReportingStrategy
                )
            }
            if (enableBloodhoundProximityVibrationConfigUpdate != enableBloodhoundProximityVibration) {
                enableBloodhoundProximityVibration = enableBloodhoundProximityVibrationConfigUpdate
                Log.d(TAG, "enableBloodhoundProximityVibration=$enableBloodhoundProximityVibration")
                if (!calledOnCreate) {
                    dataRepository.storeEnableBloodhoundProximityVibration(enableBloodhoundProximityVibration)
                }
            }
            if (bloodhoundProximityRadiusMetersConfigUpdate != bloodhoundProximityRadiusMeters) {
                bloodhoundProximityRadiusMeters = bloodhoundProximityRadiusMetersConfigUpdate
                Log.d(TAG, "bloodhoundProximityRadiusMeters=$bloodhoundProximityRadiusMeters")
                if (!calledOnCreate) {
                    dataRepository.storeBloodhoundProximityRadiusMeters(bloodhoundProximityRadiusMeters)
                }
            }
            if (bloodhoundProximityIntensityConfigUpdate != bloodhoundProximityIntensity) {
                bloodhoundProximityIntensity = bloodhoundProximityIntensityConfigUpdate
                Log.d(TAG, "bloodhoundProximityIntensity=$bloodhoundProximityIntensity")
                if (!calledOnCreate) {
                    dataRepository.storeBloodhoundProximityIntensity(bloodhoundProximityIntensity)
                }
            }
            enableHailService = enableHailServiceConfigUpdate
            enableSitxService = enableSitxServiceConfigUpdate
            enableBatdokCot = enableBatdokCotConfigUpdate
            enableMulticastOnWifi = enableMulticastConfigUpdate

            enableSmsForAlerts = enableSmsForAlertsConfigUpdate
            smsNumbersList = smsNumbersListConfigUpdate
            if (enableBatteryAlertsConfigUpdate != enableBatteryAlerts) {
                enableBatteryAlerts = enableBatteryAlertsConfigUpdate
                Log.d(TAG, "enableBatteryAlerts=$enableBatteryAlerts")
                if (!calledOnCreate) dataRepository.storeEnableBatteryAlerts(enableBatteryAlerts)
            }
            battWarnPercent = battWarnPercentConfigUpdate
            battAlertPercent = battAlertPercentConfigUpdate
            if (enablePhysiologicalAlerts != enablePhysiologicalAlertsConfigUpdate) {
                enablePhysiologicalAlerts = enablePhysiologicalAlertsConfigUpdate
                Log.d(TAG, "enablePhysiologicalAlerts changed to $enablePhysiologicalAlerts")
                CepProvider.getOrCreateEngine(context).updatePhysioAlertProfile()
                Handler(Looper.getMainLooper()).postDelayed({
                    EventBus.getDefault().post(
                        MessageEvent(
                            PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED,
                            if (enablePhysiologicalAlerts) {
                                1
                            } else {
                                0
                            }
                        )
                    )
                }, EVENT_SEND_DELAY_MS)
            }
            if (restingHeartRate != physiologicalMetricsRestingHeartRateConfigUpdate) {
                Log.d(
                    TAG,
                    "Changed detected restingHeartRate(prev=$restingHeartRate, curr=$physiologicalMetricsRestingHeartRateConfigUpdate"
                )
                restingHeartRate = physiologicalMetricsRestingHeartRateConfigUpdate
            }
            if (birthYearValue != physiologicalMetricsBirthYearServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected birthYearValue(prev=$birthYearValue, curr=$physiologicalMetricsBirthYearServiceConfigUpdate)"
                )
                birthYearValue = physiologicalMetricsBirthYearServiceConfigUpdate
            }
            if (heightFeetValue != physiologicalMetricsHeightFeetServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected heightFeetValue(prev=$heightFeetValue, curr=$physiologicalMetricsHeightFeetServiceConfigUpdate)"
                )
                heightFeetValue = physiologicalMetricsHeightFeetServiceConfigUpdate
            }
            if (heightInchesValue != physiologicalMetricsHeightInchesServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected heightInchesValue(prev=$heightInchesValue, curr=$physiologicalMetricsHeightInchesServiceConfigUpdate)"
                )
                heightInchesValue = physiologicalMetricsHeightInchesServiceConfigUpdate
            }
            if (weightValue != physiologicalMetricsWeightServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected weightValue(prev=$weightValue, curr=$physiologicalMetricsWeightServiceConfigUpdate)"
                )
                weightValue = physiologicalMetricsWeightServiceConfigUpdate
            }
            if (sexValue != physiologicalMetricsSexServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected sexValue(prev=$sexValue, curr=$physiologicalMetricsSexServiceConfigUpdate)"
                )
                sexValue = physiologicalMetricsSexServiceConfigUpdate
            }
            if (bloodTypeValue != physiologicalMetricsBloodTypeServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected bloodTypeValue(prev=$bloodTypeValue, curr=$physiologicalMetricsBloodTypeServiceConfigUpdate)"
                )
                bloodTypeValue = physiologicalMetricsBloodTypeServiceConfigUpdate
            }
//            if ((null != physiologicalMetricsAllergiesServiceConfigUpdate) && physiologicalMetricsAllergiesServiceConfigUpdate.isNotEmpty()) {
//                val allergyListConfigUpdate =
//                    physiologicalMetricsAllergiesServiceConfigUpdate.map { str ->
//                        DataRepository.AllergyItem(name = str)
//                    } ?: listOf(DataRepository.AllergyItem("N/A"))
//                if (!listEqualsIgnoreOrder(allergyList, allergyListConfigUpdate)) {
//                    Log.d(
//                        TAG,
//                        "Change detected allergyList(prev=$allergyList, curr=$allergyListConfigUpdate)"
//                    )
//                    allergyList = allergyListConfigUpdate
//                }
//
//            }
            if (userTypeValue != physiologicalMetricsUserTypeServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected userTypeValue(prev=$userTypeValue, curr=$physiologicalMetricsUserTypeServiceConfigUpdate)"
                )
                userTypeValue = physiologicalMetricsUserTypeServiceConfigUpdate
            }

            if ((restingHeartRate != physiologicalMetricsRestingHeartRateConfigUpdate) ||
                (restingHrHighAlertBpmThreshold != restingHrHighAlertBpmThresholdServiceConfigUpdate) ||
                (restingHrLowAlertBpmThreshold != restingHrLowAlertBpmThresholdServiceConfigUpdate) ||
                (highRestingHrWarningThresholdLength != highRestingHrWarningThresholdLengthServiceConfigUpdate) ||
                (lowRestingHrWarningThresholdLength != lowRestingHrWarningThresholdLengthServiceConfigUpdate) ||
                (highRestingHrAlertThresholdLength != highRestingHrAlertThresholdLengthServiceConfigUpdate) ||
                (lowRestingHrAlertThresholdLength != lowRestingHrAlertThresholdLengthServiceConfigUpdate) ||
                (exertionThresholdAlert != exertionThresholdAlertServiceConfigUpdate) ||
                (exertionThresholdWarning != exertionThresholdWarningServiceConfigUpdate) ||
                (exertionWarningThresholdLength != exertionWarningThresholdLengthServiceConfigUpdate) ||
                (exertionAlertThresholdLength != exertionAlertThresholdLengthServiceConfigUpdate)) {
                Log.d(TAG, "Change detected in Physiological Alert thresholds")
                restingHrHighAlertBpmThreshold = restingHrHighAlertBpmThresholdServiceConfigUpdate
                restingHrLowAlertBpmThreshold = restingHrLowAlertBpmThresholdServiceConfigUpdate
                highRestingHrWarningThresholdLength = highRestingHrWarningThresholdLengthServiceConfigUpdate
                lowRestingHrWarningThresholdLength = lowRestingHrWarningThresholdLengthServiceConfigUpdate
                highRestingHrAlertThresholdLength = highRestingHrAlertThresholdLengthServiceConfigUpdate
                lowRestingHrAlertThresholdLength = lowRestingHrAlertThresholdLengthServiceConfigUpdate
                exertionThresholdAlert = exertionThresholdAlertServiceConfigUpdate
                exertionThresholdWarning = exertionThresholdWarningServiceConfigUpdate
                exertionWarningThresholdLength = exertionWarningThresholdLengthServiceConfigUpdate
                exertionAlertThresholdLength = exertionAlertThresholdLengthServiceConfigUpdate
                Handler(Looper.getMainLooper()).postDelayed({
                    EventBus.getDefault().post(MessageEvent(HR_ALERT_THRESHOLDS_CHANGED,
                        if (enablePhysiologicalAlerts) {
                            1
                        } else {
                            0
                        })) }, EVENT_SEND_DELAY_MS)
                updateCepSustainOverridesMap()
                Handler(Looper.getMainLooper()).postDelayed({ runBlocking {
                    CepProvider.getOrCreateEngine(context).updateHrProfile()
                    CepProvider.getOrCreateEngine(context).updateHrrProfile()
                    CepProvider.getOrCreateEngine(context).updateSustainOverrides()
                    CepProvider.getOrCreateEngine(context).updatePressureProfile()
                } }, EVENT_SEND_DELAY_MS)

            }
            if (uniformWaistSize != physiologicalMetricsUniformWaistSizeConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected uniformWaistSize(prev=$uniformWaistSize, curr=$physiologicalMetricsUniformWaistSizeConfigUpdate)"
                )
                uniformWaistSize = physiologicalMetricsUniformWaistSizeConfigUpdate
            }
            if (strideLength != physiologicalMetricsStrideLengthConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected strideLength(prev=$strideLength, curr=$physiologicalMetricsStrideLengthConfigUpdate)"
                )
                strideLength = physiologicalMetricsStrideLengthConfigUpdate
            }
            if (uniformPantsLength != physiologicalMetricsUniformPantsLengthConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected uniformPantsLength(prev=$uniformPantsLength, curr=$physiologicalMetricsUniformPantsLengthConfigUpdate)"
                )
                uniformPantsLength = physiologicalMetricsUniformPantsLengthConfigUpdate
            }
            if (loadoutWeight != physiologicalMetricsLoadoutWeightConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected loadoutWeight(prev=$loadoutWeight, curr=$physiologicalMetricsLoadoutWeightConfigUpdate)"
                )
                loadoutWeight = physiologicalMetricsLoadoutWeightConfigUpdate
            }

            /*
            if ((hrHighAlertBpmThreshold != hrHighAlertBpmThresholdConfigUpdate) || (hrHighAlertNumSecsThreshold != hrHighAlertNumSecsThresholdConfigUpdate)) {
                Log.d(
                    TAG,
                    "Change detected hrHighAlertBpmThreshold(prev=$hrHighAlertBpmThreshold, curr=$hrHighAlertBpmThresholdConfigUpdate) OR hrHighAlertNumSecsThreshold(prev=$hrHighAlertNumSecsThreshold, curr=$hrHighAlertNumSecsThresholdConfigUpdate)"
                )
                hrHighAlertBpmThreshold = hrHighAlertBpmThresholdConfigUpdate
                hrHighAlertNumSecsThreshold = hrHighAlertNumSecsThresholdConfigUpdate
                Handler(Looper.getMainLooper()).postDelayed({
                    EventBus.getDefault().post(
                        MessageEvent(
                            HR_ALERT_THRESHOLDS_CHANGED, if (enablePhysiologicalAlerts) {
                                1
                            } else {
                                0
                            }
                        )
                    )
                }, EVENT_SEND_DELAY_MS)
            }
             */
        }


        // ui needs to be refreshed with new settings changes
        refreshUI(context)

        // Log all settings to logcat
        //logSettings()
    }


    /*
     * geoJson format example:
     *
     *      {"type":"FeatureCollection","name":"Test-02","properties":{"uuid":"426c7565-4368-6172-6d42-6561636f6e74","setName":"Test-01","generatedAt":"2025-08-20T16:10:32.551Z"},"features":[{"type":"Feature","properties":{"major":1,"minor":2},"geometry":{"type":"Point","coordinates":[-87.967657,45.459174,98.5]}},{"type":"Feature","properties":{"major":3,"minor":4},"geometry":{"type":"Point","coordinates":[-87.669969,45.511368,54.3]}},{"type":"Feature","properties":{"major":4,"minor":5},"geometry":{"type":"Point","coordinates":[-87.799846,45.209974,52.9]}}]}
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseIBeaconGeoJsonToItems(
        geoJson: String
    ): List<DataRepository.LocalizationBleBeaconItem> {
        return try {
            val gson = Gson()

            val root = gson.fromJson(geoJson, Map::class.java) as? Map<String, Any?> ?: return emptyList()
            val collProps = (root["properties"] as? Map<String, Any?>).orEmpty()
            val collectionUuid = (collProps["uuid"] as? String)?.lowercase()

            val features = (root["features"] as? List<Map<String, Any?>>).orEmpty()

            features.mapNotNull { f ->
                val fProps = (f["properties"] as? Map<String, Any?>).orEmpty()
                val major = (fProps["major"] as? Number)?.toInt()
                val minor = (fProps["minor"] as? Number)?.toInt()

                // UUID precedence: collection-level -> per-feature
                val uuid = collectionUuid ?: (fProps["uuid"] as? String)?.lowercase()

                val geom = (f["geometry"] as? Map<String, Any?>).orEmpty()
                val coords = (geom["coordinates"] as? List<Any?>).orEmpty()

                // GeoJSON uses [lon, lat, alt]
                val lon = (coords.getOrNull(0) as? Number)?.toDouble()
                val lat = (coords.getOrNull(1) as? Number)?.toDouble()
                val alt = (coords.getOrNull(2) as? Number)?.toDouble() ?: 0.0

                if (uuid == null || major == null || major < 0 || minor == null || minor < 0 || lat == null || lon == null) {
                    null
                } else {
                    DataRepository.LocalizationBleBeaconItem(
                        uuid = uuid,
                        major = major,
                        minor = minor,
                        lat = lat,
                        lon = lon,
                        alt = alt
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /*
    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onMessageEvent(event: TakServerConnStatusEvent) {
        var toastText = ""
        val connEvent = event.connEvent
        if (connEvent == CONNECTING) {
            toastText = getString(R.string.connecting_to_toast, name)
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTING [$connEvent]")
        } else if (connEvent == CONNECTION_SUCCESS) {
            toastText = getString(R.string.connected_to_toast, name)
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTION_SUCCESS [$connEvent]")
        } else if (connEvent == CONNECTION_FAILURE) {
            toastText = getString(R.string.connection_failed)
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTION_FAILURE [$connEvent]")
        }
        //Log.d(TAG, "Showing toast w/ text: $toastText")
//        Toast.makeText(mainActivity, toastText, Toast.LENGTH_SHORT).show()
    }
    */
}

fun requestCallsignComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, CallsignComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

//TODO: Possibly move to chat service wherever it goes
fun requestChatComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, ChatComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

fun requestStatusIconsComplicationUpdate(context: Context) {
    Log.d(TAG, "requestStatusIconComplicationUpdate called")
    val componentName = ComponentName(context, StatusIconsComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}


//Contains functionality for all watch screens
@Composable
fun WearTAKCivApp(licenseLiveDataState: MutableLiveData<String>, sitxVm: SitxViewModel, settingsVm: WearTAKSettingsViewModel) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current

    // collect license from data repo so we can see if it changes.
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    val callsign by settingsVm.savedCallsign.collectAsState()
    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        if (isLicenseValid()) {
            navController.navigate("main_screen") {
//                popUpTo("invalid_license_screen") {
//                    inclusive = true
//                }
            }
        } else {
            navController.navigate("invalid_license_screen") {
                popUpTo("main_screen") {
                    inclusive = true
                }
            }
        }
    }

    //Log.d(TAG, "WearTAKCivApp composable called")
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (isLicenseValid()) "main_screen" else "invalid_license_screen"
    ) {
        composable("main_screen" ) {
            MainScreen(
                navController = navController,
                callsignState = callsign,
                dataRepository = dataRepository,
                bhRepo = bhRepo,
                sitxVm = sitxVm,
                settingsVm,
                context = context
            )
        }
//        composable(
//            "license_setting_screen") {
//            LicenseKeyScreen(navController, licenseSettingsState, context)
//        }
        composable(
            "invalid_license_screen") {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
        }
    }
}

//Contains functionality for main screen
@Composable
fun MainScreen(
    navController: NavController,
    callsignState: String,
    dataRepository: DataRepository, // Add dataRepository parameter,
    bhRepo: BloodhoundRepository,
    sitxVm: SitxViewModel,
    settingsVm: WearTAKSettingsViewModel,
    context: Context // Add context parameter
) {
    //Log.d(TAG, "MainScreen called")
    val dismissState = rememberSwipeToDismissBoxState()
    val hasUnreadMessages by dataRepository.hasUnread.collectAsState() // monitor the number of unread messages
    val isAlerting by SosActivity.isAlertingLiveData.observeAsState()
    val activeItem by bhRepo.activeBhItemUi.collectAsState()
    val northBearing by dataRepository.azimuthTrueDegSmoothed.collectAsStateWithLifecycle()
    val hasUnreadBloodhounds by dataRepository.hasUnreadBloodhounds.collectAsState()

    // Get the repo for gunshots
    val context = LocalContext.current
    val gunshotOnnxRepo = remember(context.applicationContext) {
        try {
            (context.applicationContext as WearTakApplication).gunshotOnnxRepo
        } catch (_: Throwable) {
            GunshotOnnxRepository(context.applicationContext)
        }
    }
    val uasRepo = remember(context.applicationContext) {
        try {
            (context.applicationContext as WearTakApplication).uasOnnxRepo
        } catch (_: Throwable) {
            UasOnnxRepository(context.applicationContext)
        }
    }

    LaunchedEffect(Unit) {
        dataRepository.refreshUnreadCount()
    }
    LaunchedEffect(activeItem, hasUnreadBloodhounds) {
        requestBloodhoundComplicationUpdate(context)
    }

    WearTAKCIVTheme {
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.unswipeable(),
            ) {
            Box(
                modifier = Modifier
                    .background(colors.background)
                    .fillMaxSize()
                    .padding(5.dp)
                    .unswipeable()
            ) {
                OrientationAware()
                val topMetricKey by settingsVm.savedSelectedPhysioMetric.collectAsState()
                HomeDashboardColumn {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(if (com.aitech.weartak_civ.data.TopDisplayMetric.fromKey(topMetricKey).physiological) 1f else 1.5f)
                            .padding(vertical = 0.dp)
                    ) {
                        requestPhysioStatusAndAlertingComplicationUpdate(context)
                        HeartRate(context, settingsVm)
                    }
//                    Spacer(
//                        modifier = Modifier.height(10.dp)
//                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
//                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        SOSButton(context, isAlerting)
                        Spacer(
                            modifier = Modifier.width(10.dp)
                        )
                        MarkerDropButton(dataRepository = dataRepository, context = context)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        requestChatComplicationUpdate(context)
                        FilledIconButton(
                            onClick = { context.startActivity(Intent(context, ChatActivity::class.java))
                            },
                            modifier = Modifier.size(45.dp),
                            colors = IconButtonColors(
                                containerColor = Color.DarkGray,
                                contentColor = Color.Unspecified,
                                disabledContentColor = Color.Gray,
                                disabledContainerColor = Color.Black
                            ),
                        ) {
                            if (hasUnreadMessages) {
                                requestChatComplicationUpdate(context)
                                Icon(
                                    painter = painterResource(R.drawable.chat_newmessage),
                                    contentDescription = "Favorite icon",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(25.dp),
                                )
                            } else {
                                requestChatComplicationUpdate(context)
                                Icon(
                                    painter = painterResource(R.drawable.chat_nomessage),
                                    contentDescription = "Favorite icon",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(25.dp),
                                )
                        }
                        }
                        Spacer(
                            modifier = Modifier.width(10.dp)
                        )
                        Callsign(context, callsignState)
                    }
                    HomeNavigationRow(activeItem, northBearing, hasUnreadBloodhounds,
                        onBloodhound = { context.startActivity(Intent(context, BloodhoundActivity::class.java)) },
                        onMap = { context.startActivity(Intent(context, MapActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                }
                TroubleshootingIcons(sitxVm, settingsVm)

                TimeWithSecondsAware(context)

                AudioDetectionPromptHost(
                    logTag = TAG,
                    logPrefix = "GS",
                    promptText = "Was that a gunshot?",
                    events = gunshotOnnxRepo.gunshotEvents,
                    emitFeedback = gunshotOnnxRepo::tryEmitGunshotFeedback
                )

                AudioDetectionPromptHost(
                    logTag = TAG,
                    logPrefix = "UAS",
                    promptText = "Was that a UAS?",
                    events = uasRepo.uasEvents,
                    emitFeedback = uasRepo::tryEmitUasFeedback
                )
            }
        }
    }
}

@Composable
fun TimeWithSecondsAware(context: Context) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isActive by remember { mutableStateOf(true) }
    var currentTime by remember { mutableStateOf(LocalTime.now()) }

    val is24Hour = remember { DateFormat.is24HourFormat(context) }

    val formatter = remember(is24Hour) {
//        if (is24Hour) {
            DateTimeFormatter.ofPattern("HH:mm:ss")
//        } else {
//            DateTimeFormatter.ofPattern("hh:mm:ss") // a = AM/PM
//        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isActive = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isActive) {
        while (isActive) {
            currentTime = LocalTime.now()
            delay(1000L)
        }
    }
    CurvedLayout(
        anchor = 90f,                 // bottom (6 o’clock)
        anchorType = AnchorType.Center,
        angularDirection = CurvedDirection.Angular.Reversed
    ) {
        curvedRow(modifier = CurvedModifier.padding()) {
            curvedText(currentTime.format(formatter),
                style = CurvedTextStyle())
        }
    }
}

fun findTeamIndex(teamName: String?) : Int {
    if (null == teamName) {
        return 0
    }
    val index = teamEntriesValuesList.ltEntries.indexOf(teamName)
    if (index == -1) {
        return 0
    }
    return index
}

fun displayServerDialogs(
    isNetworkConnected: Boolean,
    loadingDialog: String?,
    shouldShowLoadingDialog: MutableState<Boolean>,
    shouldShowConfirmDialog: MutableState<Boolean>,
    isServerConnected: MutableState<Boolean>,
    isDuplicate: MutableState<Boolean>) {
    Log.d(TAG, "displayServerDialogs isNetworkConnected=$isNetworkConnected, loadingDialog=$loadingDialog, shouldShowLoadingDialog=${shouldShowLoadingDialog.value}, shouldShowConfirmDialog=${shouldShowConfirmDialog.value}, isServerConnected=${isServerConnected.value}")
    isDuplicate.value = loadingDialog == TAK_SERVER_DUPLICATE_STRING
    if (isDuplicate.value) {
        shouldShowLoadingDialog.value = false
        shouldShowConfirmDialog.value = true
        isServerConnected.value = false
    } else if (isNetworkConnected) {
        if (loadingDialog == TAK_SERVER_CONNECTING_STRING) {
            Log.d(TAG, "displayServerDialogs: TAK_SERVER_CONNECTING_STRING")
            shouldShowLoadingDialog.value = true
            shouldShowConfirmDialog.value = false
        } else if (loadingDialog == TAK_SERVER_CONNECTED_STRING) {
            Log.d(TAG, "displayServerDialogs: TAK_SERVER_CONNECTED_STRING")
            shouldShowLoadingDialog.value = false
            shouldShowConfirmDialog.value = true
            isServerConnected.value = true
        } else if ((loadingDialog == TAK_SERVER_CONNECTION_FAILED_STRING) ||
            (loadingDialog == TAK_SERVER_DISCONNECTED_STRING)
        ) {
            Log.d(TAG, "displayServerDialogs: $loadingDialog")
            shouldShowLoadingDialog.value = false
            shouldShowConfirmDialog.value = true
            isServerConnected.value = false
        }
        Log.d(TAG, "displayServerDialogs: $loadingDialog")
    } else {
        Log.d(TAG, "displayServerDialogs: Network is NOT connected")
        shouldShowLoadingDialog.value = false
        shouldShowConfirmDialog.value = true
        isServerConnected.value = false
    }
    Log.d(TAG, "Exiting displayServerDialogs: isNetworkConnected=$isNetworkConnected, loadingDialog=$loadingDialog, shouldShowLoadingDialog=${shouldShowLoadingDialog.value}, shouldShowConfirmDialog=${shouldShowConfirmDialog.value}, isServerConnected=${isServerConnected.value}")
}

@Composable
fun alertChipColors(state: Utils.AlertChipState): Color = when (state) {
    Utils.AlertChipState.Emergency ->
        Color.Red
    Utils.AlertChipState.Warning   ->
        Color.Yellow
    Utils.AlertChipState.None      ->
        Color.Unspecified
}

fun alertText(alerts: List<DataRepository.CustomEmergencyAlert>): String {
    if (alerts.isEmpty()) return ""

    val lowestPriority = alerts.minOf { it.priority }

    return when (lowestPriority) {
        1 -> alertCategoryFromString(
            alerts
            .filter { it.priority == 1 }
            .maxByOrNull { it.timeMillis }
            ?.category
            ?: ""
        )

        2 -> alertCategoryFromString(
            alerts
            .maxByOrNull { it.timeMillis }
            ?.category
            ?: ""
        )

        else -> ""
    }
}

@Composable
fun selectedMetricIconFromKey(key: String?): Int {
    val icon = com.aitech.weartak_civ.data.TopDisplayMetric.fromKey(key).icon
    physioMetricIconComplication = icon
    return icon
}

@Composable
fun selectedMetricValueFromKey(key: String?): String {
    val string = topMetricValue(com.aitech.weartak_civ.data.TopDisplayMetric.fromKey(key), dataRepository)
    physioMetricStringComplication = string
    return string
}

@Composable
fun selectedMetricTitleFromKey(key: String?): String {
    val physioString = stringResource(com.aitech.weartak_civ.data.TopDisplayMetric.fromKey(key).label)
    physioMetricTitleComplication = physioString
    return physioString
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun HeartRate(context: Context, settingsVm: WearTAKSettingsViewModel) {
    val physioCheckedLive by settingsVm.savedEnablePhysiologicalServices.collectAsState()
    val physioAlertsCheckedLive by settingsVm.savedEnablePhysiologicalAlerts.collectAsState()
    val alerts by dataRepository.physioAndAutoGenAlertList.collectAsState() // or collectAsStateWithLifecycle()
    val metricKey by settingsVm.savedSelectedPhysioMetric.collectAsState()

    val chipState = alerts.alertChipState(physioCheckedLive && physioAlertsCheckedLive)
    val metric = com.aitech.weartak_civ.data.TopDisplayMetric.fromKey(metricKey)
    val containsAlert = physioCheckedLive && physioAlertsCheckedLive &&
        alerts.any { it.alertState == DataRepository.AlertState.ALERT }
    val value = selectedMetricValueFromKey(metricKey)

    HomeTopMetricCard(
        title = selectedMetricTitleFromKey(metricKey),
        value = value,
        metric = metric,
        iconRes = if (physioAlertsCheckedLive && containsAlert) R.drawable.warning
            else selectedMetricIconFromKey(metricKey),
        chipState = chipState,
        alert = if (containsAlert) alertText(alerts) else null,
        onClick = { context.startActivity(homeMetricIntent(context)) }
    )
}

@Composable
internal fun HomeTopMetricCard(
    title: String,
    value: String,
    metric: com.aitech.weartak_civ.data.TopDisplayMetric,
    iconRes: Int,
    chipState: Utils.AlertChipState,
    alert: String?,
    onClick: () -> Unit
) {
    val containsAlert = alert != null
    Column(
        modifier = Modifier
            .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        if (!containsAlert) {
            Text(
                text = title,
                fontSize = 11.sp
            )
        }
        Chip(
            modifier = if (metric.physiological || containsAlert) Modifier
                else Modifier.padding(horizontal = 24.dp),
            border = ChipDefaults.outlinedChipBorder(alertChipColors(chipState), borderWidth = 2.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            onClick = onClick,
            colors =  ChipDefaults.chipColors(
            backgroundColor = Color.Transparent,
            contentColor = Color.White,
        ),
            enabled = true,
            icon = if (!containsAlert && !metric.physiological) null else {
              {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = stringResource(R.string.top_display_metric),
                    tint = alertChipColors(chipState),
                    modifier = Modifier.size(19.dp),
                )
              }
            },
            label = {
                Row(modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!containsAlert) {
                        FittedTopMetricText(value, style = MaterialTheme.typography.body1.copy(
                            fontSize = if (metric.physiological) 16.sp else 13.sp,
                            textAlign = if (metric.physiological) TextAlign.Start else TextAlign.Center,
                            fontWeight = FontWeight.SemiBold, color = Color.White),
                            minFontSize = if (metric.physiological) 0.25.sp else 10.sp)
                    } else Text(
                        text = alert ?: value,
                        textAlign = TextAlign.Start,
                        fontSize = if (containsAlert) 12.sp else if (!metric.physiological) 14.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            },
        )
    }
}

enum class Connectivity { CELL, WIFI, BLUETOOTH, AIRPLANE, NONE }
enum class TakServer { PHONE_UPLINK, CONNECTED, CONNECTED_MULTICAST, SOME_SERVERS_CONNECTED, CONNECTING, DISCONNECTED }
//2 functions below are meant to connect to complication service, TODO: Is there a better way to do this?
fun readConnectivity(networkManager: NetworkManager?): Connectivity {
    if (networkManager == null) return Connectivity.NONE
    networkManager.checkNetworkConnected()

    val net = NetworkManager.isNetworkConnectedLiveData.value == true
    val cell = NetworkManager.isCellularConnectedLiveData.value == true
    val btooth = dataRepository.isCompanionConnected.value
    val airplaneOnSuccess = dataRepository.airplaneOnSuccess.value

    return when {
        btooth -> Connectivity.BLUETOOTH
        net && cell -> Connectivity.CELL
        net && !cell -> Connectivity.WIFI
        // net is now false for expressions below
        airplaneOnSuccess -> Connectivity.AIRPLANE
        else -> Connectivity.NONE
    }
}

fun readTakStatus(takConnectService: TAKConnectService?): TakServer {
    if (dataRepository.isCompanionConnected.value) return TakServer.PHONE_UPLINK
    if ((takConnectService == null) && (enableSitxService == false) && (enableMulticastOnWifi == false)) {
        return TakServer.DISCONNECTED
    }
    val takServerSettingList = takServerList
    val anyTakServerDisconnected = takServerDetails.any {
        it.connectionStatus.plain == 0
        it.connectionStatus.plain == 5
    }
    val endpoints = dataRepository.endpointConnected.value

    val connected = ((TAKConnectService.isAnyTakConnectedLiveData.value == true)&&(!anyTakServerDisconnected)) || (dataRepository.sitxStatus.value == SitxServerStatus.CONNECTED)
    val connecting = (TAKConnectService.isAnyTakConnectingLiveData.value == true) || (dataRepository.sitxStatus.value == SitxServerStatus.CONNECTING)
    val connectedMulticast = (dataRepository.multicastPublisherRunning.value) && (endpoints.equals (DataRepository.Endpoint.MULTICAST))
    val someServersConn = ((TAKConnectService.isAnyTakConnectedLiveData.value == true) && (takServerSettingList!!.size > 1) && (anyTakServerDisconnected) && (endpoints.contains(DataRepository.Endpoint.TAK_SERVER)))

    return when {
        connected -> TakServer.CONNECTED
        connecting -> TakServer.CONNECTING
        connectedMulticast -> TakServer.CONNECTED_MULTICAST
        someServersConn -> TakServer.SOME_SERVERS_CONNECTED
        else -> TakServer.DISCONNECTED
    }
}

fun buildComplicationString(
    context: Context,
    connectivity: Connectivity,
    tak: TakServer,
    locationEnabled: Boolean,
    coerceImpossible: Boolean = true // optional: see note below
): String {
    // If you never want to show "No Connect, Server Connected", coerce it:
    val effectiveTak = if (coerceImpossible && connectivity == Connectivity.NONE && tak == TakServer.CONNECTED) {
        TakServer.DISCONNECTED
    } else tak

    val connLabel = when (connectivity) {
        Connectivity.CELL -> getString(context, R.string.cell)
        Connectivity.WIFI -> getString(context, R.string.wifi)
        Connectivity.BLUETOOTH -> getString(context,R.string.bluetooth)
        Connectivity.AIRPLANE -> getString(context, R.string.airplane_mode)
        Connectivity.NONE -> getString(context, R.string.no_connection)
    }
    val takLabel = when (effectiveTak) {
        TakServer.CONNECTED -> getString(context, R.string.server_connected)
        TakServer.PHONE_UPLINK -> getString(context, R.string.phone_uplink_active)
        TakServer.CONNECTING -> getString(context, R.string.server_connecting)
        TakServer.CONNECTED_MULTICAST -> getString(context, R.string.server_connected_multicast)
        TakServer.SOME_SERVERS_CONNECTED -> getString(context, R.string.some_servers_connected)
        TakServer.DISCONNECTED -> getString(context, R.string.server_disconnected)
    }
    val locLabel = if (locationEnabled) getString(context, R.string.location_enabled) else getString(context, R.string.location_disabled)

    return "$connLabel, $takLabel, $locLabel"
}

fun overallComplicationString(context: Context): String {
    val connectivity = readConnectivity(networkManager)
    val tak = readTakStatus(takConnectService)
    Log.d(TAG, "tak complication = $tak")
    return buildComplicationString(context, connectivity, tak, enableLocationService)
}

fun chatComplicationString() {
//TODO: WEAR-450: See if this updates

        var hasUnreadMessages = false
        runBlocking {
            hasUnreadMessages = dataRepository.hasUnread.first()
        }
        if (hasUnreadMessages == true) {
            chatComplication = CHAT_ENABLED_NEW_MESSAGE
        } else {
            chatComplication = CHAT_ENABLED_NO_NEW_MESSAGE
        }
}

@Composable
fun TroubleshootingIcons(sitxVm: SitxViewModel, settingsVm: WearTAKSettingsViewModel) {
    val iconSizeSmall= 20.dp
    val context = LocalContext.current
    val openNetworkPreferences = {
        context.startActivity(
            Intent(context, WearTAKSettingsActivity::class.java).putExtra(
                WearTAKSettingsActivity.EXTRA_OPEN_NETWORK_PREFERENCES,
                true
            )
        )
    }
    networkManager?.checkNetworkConnected()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
            contentAlignment = Alignment.TopCenter
    ) {
        val locationCheckedLive by settingsVm.savedEnableLocationService.collectAsState()
        val battery by dataRepository.nonNullBatteryPercentage.collectAsState(initial = 100)
        //val takServer by remember{ mutableStateOf(enableTAKConnectService)}
        val anyTakServerConnected by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
        val anyTakServerConnecting by TAKConnectService.isAnyTakConnectingLiveData.observeAsState()
        val anyTakServerDisconnected = takServerDetails.any {
            it.connectionStatus.plain == 0 || it.connectionStatus.plain == 5
        }
        val takServerSettingList by settingsVm.savedTakServerList.collectAsState()
        val isConnected by NetworkManager.isNetworkConnectedLiveData.observeAsState()
        val cellConnected by NetworkManager.isCellularConnectedLiveData.observeAsState()
        val wifiConnected by NetworkManager.isWifiConnectedLiveData.observeAsState()
        val airplaneOnSuccess by dataRepository.airplaneOnSuccess.collectAsStateWithLifecycle()
//        val endpoints by dataRepository.endpointConnected.collectAsStateWithLifecycle()
//        val companionConnected = DataRepository.Endpoint.COMPANION_APP in endpoints
        val companionConnected by dataRepository.isCompanionConnected.collectAsStateWithLifecycle()
        val endpoints by dataRepository.endpointConnected.collectAsStateWithLifecycle()
        val multicastConnected by dataRepository.multicastPublisherRunning.collectAsStateWithLifecycle()
        val sitxEvent by sitxVm.lastEvent.collectAsStateWithLifecycle(initialValue=null)

        val sitxStatus by dataRepository.sitxStatus.collectAsStateWithLifecycle()
        val dittoSyncActive by com.aitech.weartak_civ.service.DittoService.syncActive.collectAsStateWithLifecycle()
        val priority by dataRepository.connectionStatusPreferences.priority
            .collectAsStateWithLifecycle(initialValue = com.aitech.weartak_civ.data.defaultConnectionPriority)
        val app = context.applicationContext as WearTakApplication
        val plugins by app.pluginRepository.plugins.collectAsStateWithLifecycle()
        val pluginToggles by app.pluginManager.settingsCoordinator.connectivityToggles.collectAsStateWithLifecycle()
        val pluginRouteStatuses by app.pluginManager.settingsCoordinator.connectionStatuses.collectAsStateWithLifecycle()
        val candidates = buildList {
            if (companionConnected) add(HomeConnectionStatus("companion", R.drawable.tak_companion_app_connected,
                R.string.companion_app, R.string.phone_uplink_active))
            if (anyTakServerConnected == true) {
                val partial = takServerSettingList.size > 1 && anyTakServerDisconnected
                add(HomeConnectionStatus("tak",
                    if (partial) R.drawable.tak_server_some_connected else R.drawable.tak_server_connected,
                    R.string.tak_servers, if (partial) R.string.some_servers_connected else R.string.server_connected))
            }
            if (sitxStatus == SitxServerStatus.CONNECTED) add(HomeConnectionStatus("sitx",
                R.drawable.ic_connection_sitx_logo, R.string.sitx_tak, R.string.plugin_route_connected))
            if (anyTakServerConnecting == true) add(HomeConnectionStatus("tak_connecting",
                R.drawable.tak_server_connecting, R.string.tak_servers, R.string.plugin_route_connecting))
            if (sitxEvent is SitxServiceEvent.NeedsUserAction || sitxEvent is SitxServiceEvent.Pending ||
                sitxEvent is SitxServiceEvent.Authorized || sitxEvent is SitxServiceEvent.SocketStatus) {
                add(HomeConnectionStatus("sitx_connecting", R.drawable.tak_server_connecting,
                    R.string.sitx_tak, R.string.plugin_route_connecting))
            }
            if (multicastConnected) add(HomeConnectionStatus("multicast", R.drawable.multicast_connected,
                R.string.tak_sa_multicast, R.string.plugin_route_connected))
            if (dittoSyncActive && DataRepository.Endpoint.DITTO in endpoints) {
                add(HomeConnectionStatus("ditto", R.drawable.ic_integration_ditto,
                    R.string.ditto, R.string.ditto_sync_active))
            }
            plugins.filter { it.isLoaded && pluginToggles[it.packageName]?.enabled != false }.forEach { plugin ->
                pluginRouteStatuses[plugin.packageName].orEmpty().values.forEach { route ->
                    if (pluginRouteIsActive(route.state)) {
                        add(HomeConnectionStatus(pluginRoutePriorityKey(plugin.packageName, route.routeId),
                            R.drawable.tak_server_connected, R.string.network_preferences,
                            pluginRouteStatusLabel(route.state), "${plugin.title}: ${route.label}", plugin.icon))
                    }
                }
            }
        }
        val selectedStatus = resolveHomeConnectionStatus(priority, candidates)

        // Icon on the top left curve
        HomeConnectionIcon(
            status = selectedStatus,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (40).dp, // Adjust this value based on the curve
                    y = (-10).dp // Adjust this value based on the curve
                )
                .size(25.dp) // Adjust icon size as needed
                .clickable(onClick = openNetworkPreferences)
        )
        //Log.d(TAG, "AnyTakServerConnected = $anyTakServerConnected; AnyTakServerConnecting = $anyTakServerConnecting")

        val wirelessConnPainter = when {
            companionConnected -> painterResource(R.drawable.bluetooth)

            cellConnected == true && wifiConnected == true ->
                painterResource(R.drawable.no_cell_wifi) // or a "conflict/unknown" icon if you have one

            cellConnected == true -> painterResource(R.drawable.cell)

            wifiConnected == true -> painterResource(R.drawable.wifi)

            (true == airplaneOnSuccess && false == isConnected) -> painterResource(R.drawable.airplane_mode)

            else -> painterResource(R.drawable.no_cell_wifi)
        }

//         Icon on the bottom left curve
        Icon(
            painter = wirelessConnPainter,
            contentDescription = stringResource(R.string.network_preferences),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 15.dp, y = 10.dp)
                .size(25.dp)
                .clickable(onClick = openNetworkPreferences),
            tint = if (isConnected == true) Color.White else Color.Unspecified
        )
        /*
        Icon(painter = wirelessConnPainter,
            contentDescription = "Bottom Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (15).dp, // Adjust this value
                    y = (10).dp // Adjust this value
                )
                .size(25.dp),
            tint = if (isConnected == true) Color.White else Color.Unspecified
        )

         */

        // Icon on the top right curve
        ReportingRateLocationIcon(
            enabled = locationCheckedLive == true,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(
                    x = (-26).dp,
                    y = (-10).dp
                ),
        )


        // Icon on the bottom right curve

        val batteryIcon = when {
            battery <= 20 -> R.drawable.battery_1_bar
            battery <= 40 -> R.drawable.battery_3
            battery <= 60 -> R.drawable.battery_4_bar
            battery <= 80 -> R.drawable.battery_5
            else -> R.drawable.battery_full
        }
        Icon(
            painter = painterResource(batteryIcon), // Replace with your icon
            contentDescription = "Bottom Right Icon",
            tint = if (battery!! <= 20) Color.Red else Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(
                    x = (-15).dp, // Adjust this value
                    y = (10).dp // Adjust this value
                )
                .size(iconSizeSmall)

        )
        requestStatusIconsComplicationUpdate(mainActivity.applicationContext)
    }
}


@Composable
fun Callsign(context: Context, callsignState: String) {

    val maxChar = 10
    AssistChip(
        onClick = {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
//            navController.navigate("settings_screen")
                  },
        leadingIcon = {Icon(
            painter = painterResource(R.drawable.settings),
            contentDescription = "Settings Icon",
            modifier = Modifier.padding(all = 0.dp)
        )},
        label = {
            FittedTopMetricText(callsignState, style = TextStyle(
                fontSize = if (callsignState.length <= maxChar) 15.sp else 13.sp,
                color = Color.White))
        },
        shape = RoundedCornerShape(40.dp),
        )
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun SOSButton(context: Context, isAlertingLiveData: Boolean?) {
//    val shouldShowDialog = remember { mutableStateOf(false) }
//    val successBoolean = remember {mutableStateOf(false)}
//    val shouldShowConfirmationDialog = remember { mutableStateOf(false) }
//    val isConfirmed = remember { mutableStateOf(false) }
//    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
//    val location = dataRepository.latestLocation.value
//    var locationChecked by remember{ mutableStateOf(enableLocationService)}
    var sosText = stringResource(R.string.sos)
    var sosLiveText = stringResource(R.string.sos_active)
    //Log.d(TAG, "SOSButtton")

//    if (shouldShowConfirmationDialog.value) {
//        ConfirmationDialog(
//            shouldShowConfirmationDialog,
//            rememberSwipeDismissableNavController(),
//            stringResource(R.string.sos_alert_prompt),
//            Icons.Default.Warning,
//            Color.Red,
//            "main_screen",
//            isConfirmed
//        )
//    }
//    if (isConfirmed.value) {
//        shouldShowDialog.value = true
//        if (takServer == true) {
//            if (location != null && locationChecked) {
//                successBoolean.value = true
//                SOSButtonEmergencyMessage()
//            }
//        } else {
//            if (MainActivity.enableDittoService && DittoService.isRunning.get()) {
//                Log.w(TAG, "Not connected to tak server, but Ditto is enabled and running. SOS will be sent through here.")
//                successBoolean.value = true
//                SOSButtonEmergencyMessage()
//            } else {
//                Log.e(TAG, "Not connected to tak server, Ditto is either not enabled or not running. Sending a failed SOS to the UI.")
//                successBoolean.value = false
//            }
//        }
//        SuccessDialog(shouldShowDialog = shouldShowDialog, successBoolean = successBoolean, isConfirmed)
//    }

    Button(
        onClick = {
            context.startActivity(Intent(context, SosActivity::class.java))
        },
        modifier = Modifier
            .border(
                width = 4.dp,
                color = Color.Red,
                shape = RoundedCornerShape(40.dp),
            )
            .size(110.dp, 60.dp),
        shape = RoundedCornerShape(40.dp),
        contentPadding = PaddingValues.Absolute(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isAlertingLiveData == true) Color.Red else Color.Transparent,
            contentColor = if (isAlertingLiveData == true) Color.White else Color.Red
        )
    ) {
        Icon(
            painterResource(R.drawable.tak_alert_icon),
            modifier = Modifier.size(35.dp),
            contentDescription = null,
            tint = Color.White
        )
    }
}

fun bloodhoundActiveComplication(): Int {
    val hasUnreadBloodhound = dataRepository.hasUnreadBloodhounds.value
    val icon: Int = when (hasUnreadBloodhound) {
        true -> (R.drawable.new_item_indicator)
        false -> (R.drawable.blank)
    }
    return icon
}

@Composable
internal fun HomeDashboardColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(colors.background)
        .padding(top = 20.dp, bottom = 24.dp).padding(5.dp), content = content)
}

@Composable
internal fun HomeNavigationRow(
    activeItem: Utils.BloodhoundItem?,
    northBearing: Double?,
    hasUnread: Boolean,
    onBloodhound: () -> Unit,
    onMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BloodhoundButton(activeItem, northBearing, hasUnread, onBloodhound, Modifier.weight(1f))
        FilledIconButton(onClick = onMap, modifier = Modifier.size(48.dp),
            colors = IconButtonColors(Color.DarkGray, Color.Unspecified, Color.Gray, Color.Black)) {
            Icon(painterResource(R.drawable.maps), contentDescription = stringResource(R.string.home_open_map),
                tint = Color.Unspecified, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun BloodhoundButton(
    activeItem: Utils.BloodhoundItem?,
    northBearing: Double?,
    hasUnreadBloodhound: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val properNorthBearing = Utils.normalize0to360(0.0 - (northBearing ?: 0.0))
    val needleOrientation = activeItem?.orientation ?: properNorthBearing
    val activeTarget = activeItem?.target ?: ""
    val activeDistanceText = com.aitech.weartak_civ.ui.formatter.UnitFormatter.distanceKm(
        activeItem?.distance, currentUnitPrefs(), separator = ""
    )
    val openDescription = stringResource(R.string.home_open_bloodhound)
    Row(
        modifier = modifier.heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = openDescription },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.padding(start = 20.dp).size(32.dp)) {
            Image(
                painter = painterResource(if (activeItem != null) R.drawable.compass_1 else R.drawable.compass_needle),
                contentDescription = stringResource(R.string.compass),
                modifier = Modifier.align(Alignment.Center).size(28.dp)
                    .graphicsLayer { rotationZ = needleOrientation.toFloat() }
            )
            if (hasUnreadBloodhound) {
                Image(
                    painter = painterResource(R.drawable.new_item_indicator),
                    contentDescription = stringResource(R.string.home_unread_bloodhound),
                    modifier = Modifier.align(Alignment.TopStart).size(8.dp)
                )
            }
        }
        Column (
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        ) {
            if (activeItem != null) {
                Text(
                    text = activeTarget,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    text = activeDistanceText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    text = stringResource(R.string.compass),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    text = "---",
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@SuppressLint("SuspiciousIndentation", "StateFlowValueCalledInComposition")
@Composable
fun MarkerDropButton(
    dataRepository: DataRepository,
    context: Context
) {
    IconButton(
        onClick = {
            context.startActivity(Intent(context, MarkerActivity::class.java))

            Log.d(TAG, "DropMarkerButton Clicked")
        },
        modifier = Modifier
            .border(
                width = 2.dp, color = Color.White, shape = CircleShape
            )
            .size(100.dp, 60.dp),
    ) {
        Icon(
            painterResource(R.drawable.markerdrop),
            contentDescription = "Marker Drop Button",
            modifier = Modifier.size(30.dp)
        )
    }
//    val shouldShowDialog = remember { mutableStateOf(false) }
//    val successBoolean = remember {mutableStateOf(false)}
//    val shouldShowConfirmationDialog = remember { mutableStateOf(false) }
//    val isConfirmed = remember { mutableStateOf(false) }
//    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
//    val location = dataRepository.latestLocation.value
//    var locationChecked by remember{ mutableStateOf(enableLocationService)}
//
//
//    // State for button color
//    var isPressed by remember { mutableStateOf(false) }
//    val buttonColor by animateColorAsState(
//        targetValue = if (isPressed) Color.Green else Color.White,
//        label = "ButtonColorAnimation"
//    )
//
//    val coroutineScope = rememberCoroutineScope()
//
//    if (shouldShowConfirmationDialog.value) {
//        ConfirmationDialog(
//            shouldShowConfirmationDialog,
//            rememberSwipeDismissableNavController(),
//            stringResource(R.string.marker_drop_prompt),
//            Icons.Default.LocationOn,
//            Color.Green,
//            "main_screen",
//            isConfirmed
//        )
//    }
//    if (isConfirmed.value) {
//        shouldShowDialog.value = true
//        if (takServer == true) {
//            if (location != null && locationChecked) {
//                successBoolean.value = true
//                EventBus.getDefault().post(MessageEvent(DROP_MARKER, 0))
//            }
//        } else {
//            successBoolean.value = false
//        }
//        SuccessDialog(shouldShowDialog = shouldShowDialog, successBoolean = successBoolean, isConfirmed)
//    }
//
//    IconButton(
//        onClick = {
//		    shouldShowConfirmationDialog.value = true
//            vibrateWatch(context)
//
//            isPressed = true
//            coroutineScope.launch {
//                delay(500)  // temporary color change
//                isPressed = false
//            }
//            Log.d(TAG, "DropMarkerButton Clicked")
//        },
//        modifier = Modifier
//            .border(
//                width = 2.dp,
//                color = Color.White,
//                shape = CircleShape
//            )
//            .size(100.dp),
//    ) {
//        Icon(
//            painterResource(R.drawable.markerdrop),
//            contentDescription = "Localized description",
//            modifier = Modifier.size(30.dp)
//        )
//    }
}

@Composable
fun ActivityDisabledScreen(
    navController: NavController,
    exitActivity: () -> Unit,
    titleText: String,
    description: String) {
    WearTAKCIVTheme {
        val ctx = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = titleText,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(5.dp))
            Text(
                text = description,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.size(10.dp))
            Button(
                onClick = {
                    Log.i(TAG, "Navigating to settings screen license valid: ${isLicenseValid()}")
                    ctx.startActivity(Intent(ctx, WearTAKSettingsActivity::class.java))
                    exitActivity()
//                    navController.navigate("invalid_license_screen") {
//                        popUpTo("invalid_license_screen") { inclusive = true }
//                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = stringResource(R.string.go_to_settings),
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = { exitActivity() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray,
                    contentColor = Color.White
                ),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.exit),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun rememberDisplayRotation(): ComposeState<Int> {
    val context = LocalContext.current
    val rotationState = rememberSaveable { mutableIntStateOf(currentRotation(context)) }

    DisposableEffect(Unit) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                // Most watches are single-display (DEFAULT_DISPLAY), but we refresh from whichever fired
                val disp = dm.getDisplay(displayId) ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
                val newRotation = disp?.rotation ?: return
                if (rotationState.intValue != newRotation) {
                    rotationState.intValue = newRotation
                }
            }
        }
        // Register on the main thread
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(listener) }
    }

    return rotationState
}

private fun currentRotation(context: Context): Int {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
    return display?.rotation ?: Surface.ROTATION_0
}

@Composable
fun OrientationAware() {
    val rotation by rememberDisplayRotation()
    // Log, send analytics, or update UI
    val label = when (rotation) {
        Surface.ROTATION_0 -> "ROTATION_0"
        Surface.ROTATION_90 -> "ROTATION_90"
        Surface.ROTATION_180 -> "ROTATION_180"
        Surface.ROTATION_270 -> "ROTATION_270"
        else -> "UNKNOWN"
    }

    // Example UI
    Log.d(TAG, "Screen Rotation: $label")
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true, showBackground = true)
@Composable
fun DefaultPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    var licenseState = MutableLiveData<String>("WEAROS-0")
    val sitxVm: SitxViewModel = viewModel()
    val settingsVm: WearTAKSettingsViewModel = viewModel()

    WearTAKCivApp(licenseState, sitxVm, settingsVm)
}

public fun Modifier.unswipeable() =
    this.then(
        Modifier.draggable(
            orientation = Orientation.Horizontal,
            enabled = true,
            state = DraggableState {}
        )
    )
