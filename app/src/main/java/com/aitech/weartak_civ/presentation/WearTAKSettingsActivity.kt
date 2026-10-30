package com.aitech.weartak_civ.presentation

import kotlin.math.roundToInt
import com.aitech.weartak_civ.ui.formatter.UnitFormatter
import com.aitech.weartak_civ.ui.formatter.UnitPrefs
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import com.aitech.weartak_civ.command.ChatCommandAuthorizer
import com.aitech.weartak_civ.command.PendingServerEnrollment
import com.aitech.weartak_civ.command.ServerEnrollment
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat.getString
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.RevealState
import androidx.wear.compose.foundation.RevealValue
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rememberRevealState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.PickerState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.SwipeToRevealSecondaryAction
import androidx.wear.compose.material.SwipeToRevealUndoAction
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.rememberPickerState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_BASIC
import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED
import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.setAirplaneMode
import com.aitech.weartak_civ.data.takserver.SideloadedClientCert
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.SitxGroupStatus
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.data.takserver.TakServerRegistration
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.integration.plugin.PluginCompatibilityStatus
import com.aitech.weartak_civ.integration.plugin.PluginActivityLauncher
import com.aitech.weartak_civ.integration.plugin.PluginLoadingStatus
import com.aitech.weartak_civ.integration.plugin.PluginUI
import com.aitech.weartak_civ.integration.plugin.PluginViewModel
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.EVENT_SEND_DELAY_MS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.categoryRolesEntryLists
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakGroup
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.data.DittoPdlSource
import com.aitech.weartak_civ.service.DittoCommunityLicenseHelper
import com.aitech.weartak_civ.presentation.MainActivity.Constants.versionNumber
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.allergiesOptionsList
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.bloodTypeOptionsList
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.bloodhoundIntensityOptions
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.bloodhoundIntensityOptionsDataRepo
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.outputProtocolList
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.reportingStrategyOptions
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.sexOptionsList
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.userTypeOptionsList
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.SitxViewModel
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.service.SitxTakService
import com.aitech.weartak_civ.service.TAKConnectService
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus
import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.compose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.time.Year
import kotlin.Int
import kotlin.String

class WearTAKSettingsActivity : ComponentActivity() {
    companion object Constants {
        const val EXTRA_OPEN_NETWORK_PREFERENCES = "extra_open_network_preferences"
        const val EXTRA_OPEN_SETTINGS_PAGE = "extra_open_settings_page"
        const val EXTRA_ENROLLMENT_TOKEN = "extra_enrollment_token"
        val TAG = "WearTAK-" + WearTAKSettingsActivity::class.java.simpleName
        val callsignState = MutableLiveData<String>("")
        val licenseState = MutableLiveData<String>("")
        val sexOptionsList : List<Int> = listOf(
            (R.string.n_a),
            (R.string.male),
            (R.string.female)
        )
        val bloodTypeOptionsList : List<Int> = listOf(
            (R.string.n_a),
            (R.string.a),
            (R.string.a_negative),
            (R.string.b),
            (R.string.b_negative),
            (R.string.ab),
            (R.string.ab_negative),
            (R.string.o),
            (R.string.o_negative))
        val allergiesOptionsList : List<Int> = listOf(
            (R.string.n_a),
            (R.string.antibiotics),
            (R.string.anti_inflammatory_ibuprofen),
            (R.string.antiseizure),
            (R.string.aspirin),
            (R.string.insulin),
            (R.string.muscle_relaxers),
            (R.string.sulfa_drugs))
        val userTypeOptionsList : List<Int> = listOf(
            (R.string.n_a),
            (R.string.child),
            (R.string.coalition_civilian),
            (R.string.coalition_military),
            (R.string.non_coalition_civilian),
            (R.string.non_coalition_military),
            (R.string.opposing_force_detainee))
        val bloodhoundIntensityOptions: List<Int> = listOf(
            (R.string.bloodhound_proximity_intensity_basic),
            (R.string.bloodhound_proximity_intensity_escalated),
            (R.string.bloodhound_proximity_intensity_persistent)
        )
        val bloodhoundIntensityOptionsDataRepo: List<String> = listOf(
            BLOODHOUND_PROXIMITY_INTENSITY_BASIC,
            BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED,
            BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT
        )
        val reportingStrategyOptions = listOf(
            (R.string.constant_reporting),
            (R.string.dynamic_reporting))
        val outputProtocolList = listOf(
            (R.string.udp))

    }
    private var enrollmentToken by mutableStateOf<String?>(null)
    private var shortcutRoute by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enrollmentToken = intent.getStringExtra(EXTRA_ENROLLMENT_TOKEN)
        shortcutRoute = settingsShortcutRoute(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "WearTAKSettingsActivity onCreate()")
        enrollmentToken = intent.getStringExtra(EXTRA_ENROLLMENT_TOKEN)
        shortcutRoute = settingsShortcutRoute(intent)
//        WearTAKSettingsActivity.Constants.settingsActivity = this
        enableEdgeToEdge()
        setContent {
            val sitxVm: SitxViewModel = viewModel()
            val settingsVm: WearTAKSettingsViewModel = viewModel ()
            val pluginVm: PluginViewModel = viewModel ()
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WearTAKSettingsNav(
                        settingsVm = settingsVm,
                        sitxVm = sitxVm,
                        pluginVm = pluginVm,
                        openNetworkPreferences = intent.getBooleanExtra(
                            EXTRA_OPEN_NETWORK_PREFERENCES,
                            false
                        ),
                        enrollmentToken = enrollmentToken,
                        openSettingsPage = shortcutRoute,
                        onEnrollmentHandled = { enrollmentToken = null },
                        onExit = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
fun WearTAKSettingsNav(
    settingsVm: WearTAKSettingsViewModel,
    sitxVm: SitxViewModel,
    pluginVm: PluginViewModel,
    openNetworkPreferences: Boolean = false,
    openSettingsPage: String? = null,
    enrollmentToken: String? = null,
    onEnrollmentHandled: () -> Unit = {},
    onExit: () -> Unit
) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    var handledShortcut by rememberSaveable { mutableStateOf<String?>(null) }
//    var selectedCategoryAndRoleIndex by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // collect license from data repo so we can see if it changes.
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    val savedCallsign by settingsVm.savedCallsign.collectAsState()
    val savedTeam by settingsVm.savedTeam.collectAsState()
    val savedRole by settingsVm.savedRole.collectAsState()
    val phoneManagedSettings by settingsVm.savedPhoneManagedSettings.collectAsState()
    val selectedLocationInput by dataRepository.getLocationInput.collectAsState(initial = null)
    val locationInputStatus by dataRepository.locationInputStatus.collectAsState()

    val savedBirthYear by settingsVm.savedBirthYear.collectAsState()
    val savedHeightFeet by settingsVm.savedHeightFeet.collectAsState()
    val savedHeightInches by settingsVm.savedHeightInches.collectAsState()
    val savedWeight by settingsVm.savedWeight.collectAsState()
    val savedSex by settingsVm.savedSex.collectAsState()
    val savedBloodType by settingsVm.savedBloodType.collectAsState()
    val savedAllergies by settingsVm.savedAllergies.collectAsState()
    val savedUserType by settingsVm.savedUserType.collectAsState()

    val savedUniformWaistSize by settingsVm.savedUniformWaistSize.collectAsState()
    val savedStrideLength by settingsVm.savedStrideLength.collectAsState()
    val savedUniformPantsLength by settingsVm.savedUniformPantsLength.collectAsState()
    val savedLoadoutWeight by settingsVm.savedLoadoutWeight.collectAsState()

    val enableDynamicReporting by settingsVm.savedEnableDynamicReporting.collectAsState()
    val constantReportingInterval by settingsVm.savedConstantReportingInterval.collectAsState()
    val stationaryReportingInterval by settingsVm.savedStationaryReportingInterval.collectAsState()
    val onFootReportingInterval by settingsVm.savedOnFootReportingInterval.collectAsState()
    val vehicleReportingInterval by settingsVm.savedVehicleReportingInterval.collectAsState()
    val whileAlertingReportingInterval by settingsVm.savedWhileAlertingReportingInterval.collectAsState()

    val enableWifi by settingsVm.savedEnableWifiBatteryOptimization.collectAsState()
    val enableAllWifi by settingsVm.savedEnableAllWifiBatteryOptimization.collectAsState()
    var displaySecondaryText = when {
        enableWifi && enableAllWifi -> getString(context, R.string.all_wifi_connections)
        enableWifi && !enableAllWifi -> getString(context, R.string.some_wifi_connections)
        else -> getString(context, R.string.no_wifi_connections)
    }
    val wifiSsidList by settingsVm.savedWifiSsidList.collectAsState()

    val enablePhysiologicalServices by settingsVm.savedEnablePhysiologicalServices.collectAsState()
    val enableBatdokCot by settingsVm.savedEnableBatdokCot.collectAsState()
    val takServerList by settingsVm.savedTakServerList.collectAsState()

    val enableMulticast by settingsVm.savedEnableMulticast.collectAsState()
    val enableTakEndpoint by settingsVm.savedEnableTakEndpoint.collectAsState()
    val multicastAddress by settingsVm.savedMulticastAddress.collectAsState()
    val multicastProtocol by settingsVm.savedMulticastProtocol.collectAsState()
    val multicastPort by settingsVm.savedMulticastPort.collectAsState()

    val enableSitx by settingsVm.savedEnableSitx.collectAsState()
    val sitxUrl by settingsVm.savedSitxTakUrl.collectAsState()
    val sitxGroup by settingsVm.savedSitxGroup.collectAsState()
    val sitxGroupsList by settingsVm.savedSitxGroupList.collectAsState()
    val sitxGroupStatus by settingsVm.sitxGroupStatus.collectAsState()
    val sitxEvent by settingsVm.lastSitxEvent.collectAsState()

    val enableCompanionAppService by settingsVm.savedEnableCompanionAppService.collectAsState()
    val enableChatCommands by settingsVm.savedEnableChatCommands.collectAsState()
    val enableSensorManager by settingsVm.savedEnableSensorManager.collectAsState()
    val chatCommandCallsigns by settingsVm.savedChatCommandCallsigns.collectAsState()
    var enrollPrefill by remember { mutableStateOf<ServerEnrollment?>(null) }
    LaunchedEffect(enrollmentToken) {
        if (enrollmentToken != null) {
            PendingServerEnrollment.take(enrollmentToken)?.let {
                enrollPrefill = it
                navController.navigate("new_server_screen") { launchSingleTop = true }
            }
            onEnrollmentHandled()
        }
    }
    val enableSmsAlerts by settingsVm.savedEnableSmsForAlerts.collectAsState()
    val smsNumberList by settingsVm.savedSmsNumbersList.collectAsState()
    val enableDittoService by settingsVm.savedEnableDittoService.collectAsState()
    val dittoPdlSource by dataRepository.getDittoPdlSource.collectAsState(initial = DittoPdlSource.COMMUNITY)
    val dittoCustomPdl by dataRepository.getDittoCustomPdl.collectAsState(initial = "")
    val enableTabletCommandEndpoint by settingsVm.savedEnableTabletCommandEndpoint.collectAsState()
    val tabletCommandEndpoint by settingsVm.savedTabletCommandEndpoint.collectAsState()
    val tabletCommandAPIKey by settingsVm.savedTabletCommandApikey.collectAsState()
    val enableEsriEndpoint by settingsVm.savedEnableEsriEndpoint.collectAsState()
    val esriEndpoint by settingsVm.savedEsriEndpoint.collectAsState()

    val enableBatteryAlerts by settingsVm.savedEnableBatteryAlerts.collectAsState()
    val enablePhysioAlerts by settingsVm.savedEnablePhysiologicalAlerts.collectAsState()
    val enableEnvironmentalAlerts by settingsVm.savedEnableEnvironmentalAlerts.collectAsState()
    val highHrThreshold by settingsVm.savedRestingHrHighAlertBpmThreshold.collectAsState()
    val highHrWarningLength by settingsVm.savedHighRestingHrWarningThresholdLength.collectAsState()
    val highHrAlertLength by settingsVm.savedHighRestingHrAlertThresholdLength.collectAsState()
    val lowHrThreshold by settingsVm.savedRestingHrLowAlertBpmThreshold.collectAsState()
    val lowHrWarningLength by settingsVm.savedLowRestingHrWarningThresholdLength.collectAsState()
    val lowHrAlertLength by settingsVm.savedLowRestingHrAlertThresholdLength.collectAsState()
    val exertionWarningThreshold by settingsVm.savedExertionThresholdWarning.collectAsState()
    val exertionWarningLength by settingsVm.savedExertionWarningThresholdLength.collectAsState()
    val exertionAlertThreshold by settingsVm.savedExertionThresholdAlert.collectAsState()
    val exertionAlertLength by settingsVm.savedExertionAlertThresholdLength.collectAsState()

    val isDevMode by settingsVm.savedEnableWearTakDevMode.collectAsState()
    val networkPreferencesLocked by settingsVm.savedNetworkPreferencesLocked.collectAsState()
    val phoneManagedNotice = stringResource(R.string.phone_managed_by_paired_phone)
    val networkLockedNotice = stringResource(R.string.network_settings_locked)
    val showPhoneManagedNotice = {
        Toast.makeText(
            context,
            phoneManagedNotice,
            Toast.LENGTH_SHORT
        ).show()
    }
    val enableImmersionAlerts by settingsVm.savedEnableImmersionAlerts.collectAsState()
    val enableLowAtmPressureAlert by settingsVm.savedEnableLowAtmPressureAlert.collectAsState()
    val lowAtmPressureAlertThreshold by settingsVm.savedLowAtmPressureAlertThreshold.collectAsState()
    val enableHighAtmPressureAlert by settingsVm.savedEnableHighAtmPressureAlert.collectAsState()
    val highAtmPressureAlertThreshold by settingsVm.savedHighAtmPressureAlertThreshold.collectAsState()

    val enableBloodhoundProximityVibration by settingsVm.savedEnableBloodhoundProximityVibration.collectAsState()
    val bloodhoundProximityRadius by settingsVm.savedBloodhoundProximityRadiusMeters.collectAsState()
    val bloodhoundProximityIntensity by settingsVm.savedBloodhoundProximityIntensity.collectAsState()

    val pluginList by pluginVm.plugins.collectAsState()

    val enableGunshotDetection by settingsVm.savedEnableOnnxGunshotService.collectAsState()
    val gunshotTriggerThreshold by settingsVm.savedOnnxGunshotTriggerThresholdPercent.collectAsState()
    val enableUasDetection by settingsVm.savedEnableOnnxUasService.collectAsState()
    val uasTriggerThreshold by settingsVm.savedOnnxUasTriggerThresholdPercent.collectAsState()
    val crmThresholdValues by settingsVm.savedCrmThresholdRanges.collectAsState()
    val enableCrm by settingsVm.savedEnableCrmService.collectAsState()
    val wearTakInfo by settingsVm.wearTakInfo.collectAsState()

    LaunchedEffect(phoneManagedSettings, networkPreferencesLocked) {
        val route = navController.currentDestination?.route.orEmpty()
        val identityOrReportingRoute = route in setOf(
            "my_callsign",
            "my_team",
            "my_role",
            "role_category_screen/{index}",
            "reporting_strategy",
            "reporting_strategy_select",
            "constant_reporting_interval_screen",
            "stationary_reporting_interval_screen",
            "on_foot_reporting_interval_screen",
            "vehicle_reporting_interval_screen",
            "while_alerting_reporting_interval_screen"
        )
        val serverRoute = route in setOf(
            "tak_servers",
            "new_server_screen",
            "edit_server_screen/{index}"
        )
        if (phoneManagedSettings && identityOrReportingRoute) {
            navController.navigate("callsign_and_device_preferences") {
                popUpTo("settings_screen") { inclusive = false }
                launchSingleTop = true
            }
        } else if (serverRoute && (phoneManagedSettings || networkPreferencesLocked)) {
            val destination = if (networkPreferencesLocked) "settings_screen" else "network_preferences"
            navController.navigate(destination) {
                popUpTo("settings_screen") { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(openNetworkPreferences, openSettingsPage) {
        val route = openSettingsPage ?: "network_preferences".takeIf { openNetworkPreferences }
        if (route != null && route != handledShortcut) {
            handledShortcut = route
            navController.navigate(route)
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "settings_screen"
    ) {
        composable("settings_screen") {
            val rootSwipeState = rememberSwipeToDismissBoxState()
            LaunchedEffect(rootSwipeState.currentValue) {
                if (rootSwipeState.currentValue == SwipeToDismissValue.Dismissed) {
                    onExit()
                }
            }

            SwipeToDismissBox(state = rootSwipeState) { isBackground ->
                if (!isBackground) {
                    WearTAKPageWithBackArrow(
                        stringResource(R.string.weartak_preferences),
                        {
                            item {
                                WearTAKTitleChip(
                                    title = stringResource(R.string.callsign_and_device_preferences),
                                    route = "callsign_and_device_preferences",
                                    navController = navController
                                )
                            }
                            item {
                                if (networkPreferencesLocked) {
                                    WearTAKLockedTitleChip(
                                        title = stringResource(R.string.network_preferences),
                                        lockedState = stringResource(R.string.locked),
                                        onClick = {
                                            Toast.makeText(
                                                context,
                                                networkLockedNotice,
                                                Toast.LENGTH_SHORT
                                            ).apply {
                                                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 50)
                                            }.show()
                                        }
                                    )
                                } else {
                                    WearTAKTitleChip(
                                        title = stringResource(R.string.network_preferences),
                                        route = "network_preferences",
                                        navController = navController
                                    )
                                }
                            }
                            item {
                                WearTAKTitleChip(
                                    title = stringResource(R.string.alerting_preferences),
                                    route = "alerting_preferences",
                                    navController = navController
                                )
                            }
                            item {
                                WearTAKTitleChip(
                                    title = stringResource(R.string.tool_preferences),
                                    route = "tool_preferences",
                                    navController = navController
                                )
                            }
                            item {
                                WearTAKTitleChip(
                                    title = stringResource(R.string.admin_preferences),
                                    route = "admin_preferences",
                                    enabled = isDevMode,
                                    navController = navController
                                )
                            }
                            item {
                                WearTAKTitleChip(
                                    title = stringResource(R.string.developer_preferences),
                                    route = "debug_tools",
                                    enabled = isDevMode,
                                    navController = navController
                                )
                            }
                            item {
                                WearTAKDebugVersionText(
                                    wearTakInfo = wearTakInfo,
                                    devModeEnabled = isDevMode,
                                    settingsVm = settingsVm
                                )
                            }
                        },
                        navController,
                        true,
                        onExit
                    )
                }
            }
        }
        composable("ditto_pdl") {
            LaunchedEffect(Unit) {
                com.aitech.weartak_civ.service.DittoService.onDittoScreenOpened()
            }
            DittoPdlSelectionPage(
                navController = navController,
                selectedSource = dittoPdlSource
            )
        }

        composable("ditto_custom_pdl") {
            LaunchedEffect(Unit) {
                com.aitech.weartak_civ.service.DittoService.onDittoScreenOpened()
            }
            DittoCustomPdlEntryPage(navController)
        }
        //Callsign and Device Settings vvv
        composable("callsign_and_device_preferences") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.callsign_and_device_preferences),
                columnContent = {
                    item {
                        if (phoneManagedSettings) {
                            WearTAKLockedTitleChip(
                                title = stringResource(R.string.my_callsign),
                                lockedState = "${savedCallsign} • ${stringResource(R.string.phone_managed)}",
                                onClick = showPhoneManagedNotice
                            )
                        } else {
                            WearTAKTitleChipWithState(
                                route = "my_callsign",
                                title = stringResource(R.string.my_callsign),
                                state = savedCallsign,
                                navController = navController
                            )
                        }
                    }
                    item {
                        if (phoneManagedSettings) {
                            WearTAKLockedTitleChip(
                                title = stringResource(R.string.my_team),
                                lockedState = "${savedTeam} • ${stringResource(R.string.phone_managed)}",
                                onClick = showPhoneManagedNotice
                            )
                        } else {
                            WearTAKTitleChipWithState(
                                route = "my_team",
                                title = stringResource(R.string.my_team),
                                state = savedTeam,
                                navController = navController
                            )
                        }
                    }
                    item {
                        if (phoneManagedSettings) {
                            WearTAKLockedTitleChip(
                                title = stringResource(R.string.my_role),
                                lockedState = "${savedRole} • ${stringResource(R.string.phone_managed)}",
                                onClick = showPhoneManagedNotice
                            )
                        } else {
                            WearTAKTitleChipWithState(
                                route = "my_role",
                                title = stringResource(R.string.my_role),
                                state = savedRole,
                                navController = navController
                            )
                        }
                    }
                    item {
                    WearTAKTitleChip(
                        route = "my_user_metrics",
                        title = stringResource(R.string.my_user_metrics),
                        navController = navController
                    )}
                    item {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        thickness = 1.dp,
                        color = Color.White
                    )}
                    item {
                        if (phoneManagedSettings) {
                            WearTAKLockedTitleChip(
                                title = stringResource(R.string.reporting_strategy),
                                lockedState = "${constantReportingInterval}s • ${stringResource(R.string.phone_managed)}",
                                onClick = showPhoneManagedNotice
                            )
                        } else {
                            WearTAKTitleChip(
                                route = "reporting_strategy",
                                title = stringResource(R.string.reporting_strategy),
                                navController = navController
                            )
                        }
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "save_batt_on_wifi",
                            title = stringResource(R.string.save_battery_on_wifi),
                            navController = navController,
                            state = displaySecondaryText,
                        )
                    }
                    item {
                        WearTAKToggleChip(
                            checkedLive = enablePhysiologicalServices,
                            onCheckedChange = { settingsVm.storeEnablePhysiologicalServices(it) },
                            title = stringResource(R.string.physiological_monitoring),
                            description = stringResource(R.string.on_off)
                        )
                    }
                    item{
                        WearTAKToggleChip(
                            checkedLive = enableBatdokCot,
                            onCheckedChange = { settingsVm.storeEnableBatdokCot(it) },
                            appIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.batdok),
                                    tint = Color.Unspecified,
                                    contentDescription = "null",
                                    modifier = Modifier
                                        .size(20.dp),
                                )
                            },
                            title = stringResource(R.string.batdok),
                            description = stringResource(R.string.on_off)
                        )
                    }
                },
                navController,
            )
        }
        composable("my_callsign") {
            if (phoneManagedSettings) {
                WearTAKPageWithBackArrow(
                    stringResource(R.string.my_callsign),
                    columnContent = {
                        item {
                            WearTAKLockedTitleChip(
                                title = savedCallsign,
                                lockedState = stringResource(R.string.phone_managed),
                                onClick = showPhoneManagedNotice
                            )
                        }
                    },
                    navController = navController
                )
            } else {
                WearTAKStringEntryPage(
                    navController = navController,
                    label = stringResource(R.string.callsign),
                    item = savedCallsign,
                    confirmFunction = {
                        settingsVm.storeCallsign(it)
                    },
                )
            }
        }
        composable("my_team") {
            if (phoneManagedSettings) {
                WearTAKPageWithBackArrow(
                    stringResource(R.string.my_team),
                    columnContent = {
                        item {
                            WearTAKLockedTitleChip(
                                title = savedTeam,
                                lockedState = stringResource(R.string.phone_managed),
                                onClick = showPhoneManagedNotice
                            )
                        }
                    },
                    navController = navController
                )
            } else {
                WearTAKSelectionPage(
                    settingsVm.findTeamIndex(savedTeam),
                    stringResource(R.string.my_team),
                    navController,
                    teamEntriesValuesList.ltEntries,
                    { settingsVm.storeTeam(it) }
                )
            }
        }
        composable("my_role") {
            if (phoneManagedSettings) {
                WearTAKPageWithBackArrow(
                    stringResource(R.string.my_role),
                    columnContent = {
                        item {
                            WearTAKLockedTitleChip(
                                title = savedRole,
                                lockedState = stringResource(R.string.phone_managed),
                                onClick = showPhoneManagedNotice
                            )
                        }
                    },
                    navController = navController
                )
            } else {
                WearTAKPageWithBackArrow(
                    stringResource(R.string.my_role),
                    {
                        itemsIndexed(categoryRolesEntryLists) { index, lrCategory ->
                            run {
                                WearTAKTitleChip(
                                    title = lrCategory.category,
                                    route = "role_category_screen/$index",
                                    navController = navController
                                )
                            }
                        }
                    },
                    navController,
                )
            }
        }
        composable(
            "role_category_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            var selectedCategoryAndRoleIndex by remember {
                mutableStateOf(settingsVm.findRoleSelectionFromString(savedRole))
            }
            val categoryRoleListSetting = categoryRolesEntryLists.getOrNull(index)
            val roleList = categoryRoleListSetting?.roles.orEmpty()
            if (phoneManagedSettings) {
                WearTAKPageWithBackArrow(
                    stringResource(R.string.my_role),
                    columnContent = {
                        item {
                            WearTAKLockedTitleChip(
                                title = savedRole,
                                lockedState = stringResource(R.string.phone_managed),
                                onClick = showPhoneManagedNotice
                            )
                        }
                    },
                    navController = navController
                )
            } else {
                WearTAKDoubleSelectionPage(
                    navController = navController,
                    categoryIndex = index,
                    selectedCategoryAndRoleIndex = selectedCategoryAndRoleIndex,
                    onRoleSelected =  { selectedCategoryAndRoleIndex = it },
                    title = stringResource(R.string.role) + "-" + categoryRoleListSetting?.category,
                    list = roleList,
                    confirmFunctionCategory = { selection ->
                        val entry = categoryRolesEntryLists[selection.first]

                        selectedCategoryAndRoleIndex = selection

                        settingsVm.storeRoleCategory(entry.category)
                        settingsVm.storeRole(entry.roles[selection.second])
                    }
                )
            }
        }
        composable("my_user_metrics") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.my_user_metrics),
                columnContent = {
                    item {
                        WearTAKTitleChip(
                            route = "medical_profile_batdok",
                            title = stringResource(R.string.medical_profile_batdok),
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChip(
                            route = "gait_tracking",
                            title = stringResource(R.string.gait_tracking),
                            navController = navController
                        )
                    }
                },
                navController,
            )
        }
        composable("medical_profile_batdok") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.medical_profile_batdok),
                columnContent = {
                    item {
                        WearTAKTitleChipWithState(
                            route = "birth_year",
                            title = stringResource(R.string.birth_year),
                            state = savedBirthYear,
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "height",
                            title = stringResource(R.string.height),
                            state = savedHeightFeet,
                            navController = navController,
                            formatState = { "${savedHeightFeet ?: 0}'" + savedHeightInches + "''" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "weight",
                            title = stringResource(R.string.weight),
                            state = savedWeight,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} lbs" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "sex",
                            title = stringResource(R.string.sex),
                            state = savedSex,
                            navController = navController
                        )
                    }
                    item{
                    WearTAKTitleChipWithState(
                        route = "blood_type",
                        title = stringResource(R.string.blood_type),
                        state = savedBloodType,
                        navController = navController
                    )
                        }
                    item {
                        WearTAKTitleChipWithState(
                            route = "allergies",
                            title = stringResource(R.string.allergies),
                            state = savedAllergies,
                            navController = navController,
                            formatState = { value ->
                                value.takeIf { it!!.isNotEmpty() }?.joinToString(", ") { it }
                                    ?: "None"
                            }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "user_type",
                            title = stringResource(R.string.user_type),
                            state = savedUserType,
                            navController = navController
                        )
                    }
                },
                navController,
            )
        }
        composable("birth_year") {
            WearTAKIntEntryPage(
                navController,
                savedBirthYear,
                stringResource(R.string.birth_year),
                Year.now().value,
                1900,
                context,
                { settingsVm.storeYearOfBirth(it) }
            )
        }
        composable("height") {
            WearTAKPicker(
                title = stringResource(R.string.height),
                navController = navController,
                columnOneState = savedHeightFeet,
                columnTwoState = savedHeightInches,
                columnOneAmount = 8,
                columnTwoAmount = 12,
                columnOneText = "'",
                columnTwoText = "''",
                onClickConfirmOne = {
                   val columnOneIndex = settingsVm.findPickerIndex(dataName = savedHeightFeet, picker = it)
                    settingsVm.storePhysiologicalMetricHeightFeet(columnOneIndex+1)
                },
                onClickConfirmTwo = {
                    val columnTwoIndex = settingsVm.findPickerIndex(dataName = savedHeightFeet, picker = it)
                    settingsVm.storePhysiologicalMetricHeightInches(columnTwoIndex)
                }
            )
        }
        composable("weight") {
            WearTAKIntEntryPage(
                navController,
                savedWeight,
                stringResource(R.string.weight),
                1000,
                0,
                context,
                { settingsVm.storeWeight(it) }
            )
        }
        composable("sex") {
            Log.d(TAG, "sex = $savedSex")
            val sexList = sexOptionsList.map { stringResource(id = it) }

            WearTAKSelectionPage(
                settingsVm.findRadioIndex(savedSex, sexList),
                stringResource(R.string.sex),
                navController,
                sexList,
                { settingsVm.storeSex(it) }
            )
        }
        composable("blood_type") {
            val bloodTypeList = bloodTypeOptionsList.map { stringResource(id = it) }

            WearTAKSelectionPage(
                settingsVm.findRadioIndex(savedBloodType, bloodTypeList),
                stringResource(R.string.blood_type),
                navController,
                bloodTypeList,
                { settingsVm.storeBloodType(it) }
            )
        }
        composable("allergies") {
            val allergiesList = allergiesOptionsList.map { stringResource(id = it) }

            WearTAKMultipleSelectionPage(
                stringResource(R.string.allergies),
                navController,
                savedAllergies,
                allergiesList,
                { settingsVm.storeAllergies(it) },
            )
        }
        composable("user_type") {
            val userTypeList = userTypeOptionsList.map { stringResource(id = it) }

            WearTAKSelectionPage(
                settingsVm.findRadioIndex(savedUserType, userTypeList),
                stringResource(R.string.user_type),
                navController,
                userTypeList,
                { settingsVm.storeUserType(it) }
            )
        }
        composable("gait_tracking") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.gait_tracking),
                columnContent = {
                    item {
                        WearTAKTitleChipWithState(
                            route = "uniform_waist_size",
                            title = stringResource(R.string.uniform_waist_size),
                            state = savedUniformWaistSize,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}''" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "stride_length",
                            title = stringResource(R.string.stride_length),
                            state = savedStrideLength,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}''" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "uniform_pants_length",
                            title = stringResource(R.string.uniform_pants_length),
                            state = savedUniformPantsLength,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}''" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "loadout_weight",
                            title = stringResource(R.string.loadout_weight),
                            state = savedLoadoutWeight,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} lbs" }
                        )
                    }
                },
                navController,
            )
        }
        composable("uniform_waist_size") {
            WearTAKIntEntryPage(
                navController,
                savedUniformWaistSize,
                stringResource(R.string.uniform_waist_size),
                1000,
                0,
                context,
                { settingsVm.storeUniformWaistSize(it) }
            )
        }
        composable("stride_length") {
            WearTAKIntEntryPage(
                navController,
                savedStrideLength,
                stringResource(R.string.stride_length),
                1000,
                0,
                context,
                { settingsVm.storeStrideLength(it) }
            )
        }
        composable("uniform_pants_length") {
            WearTAKIntEntryPage(
                navController,
                savedUniformPantsLength,
                stringResource(R.string.uniform_pants_length),
                1000,
                0,
                context,
                { settingsVm.storeUniformPantsLength(it) }
            )
        }
        composable("loadout_weight") {
            WearTAKIntEntryPage(
                navController,
                savedLoadoutWeight,
                stringResource(R.string.loadout_weight),
                1000,
                0,
                context,
                { settingsVm.storeLoadoutWeight(it) }
            )
        }

        composable("reporting_strategy") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.reporting_strategy),
                columnContent = {
                    item {
                        if (phoneManagedSettings) {
                            WearTAKLockedTitleChip(
                                title = stringResource(R.string.location_input),
                                lockedState = stringResource(R.string.phone_managed),
                                onClick = showPhoneManagedNotice
                            )
                        } else {
                            WearTAKTitleChipWithState(
                                route = "location_input",
                                title = stringResource(R.string.location_input),
                                navController = navController,
                                state = selectedLocationInput,
                                formatState = {
                                    selectedLocationInput?.let { stringResource(it.label) }
                                        ?: stringResource(R.string.location_input_unavailable)
                                }
                            )
                        }
                    }
                    if (phoneManagedSettings) {
                        item {
                            WearTAKLockedTitleChip(
                                title = stringResource(R.string.reporting_strategy),
                                lockedState = "${constantReportingInterval}s • ${stringResource(R.string.phone_managed)}",
                                onClick = showPhoneManagedNotice
                            )
                        }
                    } else {
                        item {
                            WearTAKTitleChipWithState(
                                route = "reporting_strategy_select",
                                title = stringResource(R.string.reporting_strategy),
                                navController = navController,
                                state = enableDynamicReporting,
                                formatState = {
                                    if (enableDynamicReporting) {
                                        stringResource(R.string.dynamic_reporting)
                                    } else {
                                        stringResource(R.string.constant_reporting)
                                    }
                                }
                            )
                        }
                        item {
                            Spacer(Modifier.height(5.dp))
                        }
                        if (enableDynamicReporting) {
                            item { WearTAKTitleChipWithState(
                                route = "stationary_reporting_interval_screen",
                                title = stringResource(R.string.stationary_reporting_interval),
                                state = stationaryReportingInterval, navController = navController,
                            ) }
                            item { WearTAKTitleChipWithState(
                                route = "on_foot_reporting_interval_screen",
                                title = stringResource(R.string.on_foot_reporting_interval),
                                state = onFootReportingInterval, navController = navController,
                            ) }
                            item { WearTAKTitleChipWithState(
                                route = "vehicle_reporting_interval_screen",
                                title = stringResource(R.string.vehicle_reporting_interval),
                                state = vehicleReportingInterval, navController = navController,
                            ) }
                            item { WearTAKTitleChipWithState(
                                route = "while_alerting_reporting_interval_screen",
                                title = stringResource(R.string.while_alerting_reporting_interval),
                                state = whileAlertingReportingInterval, navController = navController,
                            ) }
                        } else {
                            item { WearTAKTitleChipWithState(
                                route = "constant_reporting_interval_screen",
                                title = stringResource(R.string.constant_reporting_interval),
                                state = constantReportingInterval, navController = navController,
                            ) }
                        }
                    }
                },
                navController,
            )
        }
        composable("location_input") {
            if (phoneManagedSettings) {
                WearTAKPageWithBackArrow(stringResource(R.string.location_input), columnContent = {
                    item { WearTAKLockedTitleChip(
                        title = stringResource(R.string.location_input),
                        lockedState = stringResource(R.string.phone_managed),
                        onClick = showPhoneManagedNotice
                    ) }
                }, navController)
            } else {
                LocationInputSettingsPage(selectedLocationInput, locationInputStatus,
                    navController) { dataRepository.storeLocationInput(it) }
            }
        }
        composable("reporting_strategy_select") {
            val reportingStrategyList = reportingStrategyOptions.map { stringResource(id = it) }

            WearTAKSelectionPage(
                settingsVm.findRadioIndex(
                    if (enableDynamicReporting){ stringResource(R.string.dynamic_reporting)} else {stringResource(R.string.constant_reporting)},
                    reportingStrategyList
                ),
                stringResource(R.string.reporting_strategy),
                navController,
                reportingStrategyList,
                { settingsVm.storeEnableDynamicReporting(it == getString(context, R.string.dynamic_reporting)) }
            )
        }
        composable(
            "constant_reporting_interval_screen") {
            WearTAKIntEntryPage(
                navController,
                constantReportingInterval,
                stringResource(R.string.constant_reporting_interval),
                1000000,
                1,
                context,
                { settingsVm.storeConstantReportingInterval(it) }
            )
        }
        composable(
            "stationary_reporting_interval_screen") {
            WearTAKIntEntryPage(
                navController,
                stationaryReportingInterval,
                stringResource(R.string.stationary_reporting_interval),
                1000000,
                1,
                context,
                { settingsVm.storeStationaryReportingInterval(it) }
            )
        }
        composable(
            "on_foot_reporting_interval_screen") {
            WearTAKIntEntryPage(
                navController,
                onFootReportingInterval,
                stringResource(R.string.on_foot_reporting_interval),
                1000000,
                1,
                context,
                { settingsVm.storeOnFootReportingInterval(it) }
            )
        }
        composable(
            "vehicle_reporting_interval_screen") {
            WearTAKIntEntryPage(
                navController,
                vehicleReportingInterval,
                stringResource(R.string.vehicle_reporting_interval),
                1000000,
                1,
                context,
                { settingsVm.storeVehicleReportingInterval(it) }
            )
        }
        composable(
            "while_alerting_reporting_interval_screen") {
            WearTAKIntEntryPage(
                navController,
                whileAlertingReportingInterval,
                stringResource(R.string.while_alerting_reporting_interval),
                1000000,
                1,
                context,
                { settingsVm.storeWhileAlertingReportingInterval(it) }
            )
        }
        composable(
            "save_batt_on_wifi"
        ){
            val radioOptions = listOf(stringResource(R.string.all_wifi_connections), stringResource(R.string.no_wifi_connections), stringResource(R.string.some_wifi_connections))
            val index =  settingsVm.findRadioIndex(displaySecondaryText, radioOptions)
            var selectedRadioIndex by remember { mutableStateOf(index) }
            val scrollState = rememberScalingLazyListState()
            val scope = rememberCoroutineScope()

            WearTAKPageWithBackArrow(
                title = stringResource(R.string.save_battery_on_wifi),
                columnContent = {
                    item {
                        Text(
                            text = stringResource(R.string.slows_update_rate_on_enabled_wifi_networks),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .padding(horizontal = 10.dp)
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.optimize_on),
                            textAlign = TextAlign.Left,
                            modifier = Modifier
                                .padding(bottom = 3.dp)
                                .padding(horizontal = 10.dp)
                        )
                    }
                    itemsIndexed(radioOptions){index, ltItem ->
                        run {
                            WearTAKSelectableChip(
                                selected = index == selectedRadioIndex,
                                onClick = { selectedRadioIndex = index;
                                    if (selectedRadioIndex == 2 /* Some wifi connections */) {
                                        settingsVm.storeEnableWifiBatteryOptimization(true)
                                        settingsVm.storeEnableAllWifiBatteryOptimization(false)
                                        scope.launch {it.animateScrollToItem(radioOptions.size + 2) }
                                    } else if (selectedRadioIndex == 0 /* all wifi connections */) {
                                        settingsVm.storeEnableWifiBatteryOptimization(true)
                                        settingsVm.storeEnableAllWifiBatteryOptimization(true)
                                    } else {
                                        settingsVm.storeEnableWifiBatteryOptimization(false)
                                        settingsVm.storeEnableAllWifiBatteryOptimization(false)
                                    }
                                    displaySecondaryText = radioOptions[selectedRadioIndex]

                                },
                                title = ltItem,
                                list = radioOptions
                            )
                        }
                    }
                    if (selectedRadioIndex == 2 /* some wifi connections */) {
                        item {
                        Spacer(Modifier.height(5.dp))
                            }
                        item {
                            WearTAKTitleChip(
                                title = stringResource(R.string.edit_optimized_wifi_connections),
                                route = "wifi_ssid_screen",
                                navController = navController
                            )
                        }
                    }
                },
                navController = navController,
            )
        }
        composable(
            "wifi_ssid_screen"
        ) {
            WearTAKPageWithBackArrow(
                title = stringResource(R.string.enabled_wifi_connections),
                columnContent = {
                    item {
                        LazyColumn(
                            modifier = Modifier
                                .height(100.dp)
                        ) {
                            Log.d(TAG, "SSID List = $wifiSsidList")
                            itemsIndexed(wifiSsidList) { index, wifiSsidItem ->
                                WearTAKSwipeToRevealChip(
                                    onPrimaryActionClick = {
                                        Log.d(TAG, "Remove wifiSsidItem ($wifiSsidItem)")
                                        val mutableWifiSsidList = wifiSsidList.toMutableList()
                                        mutableWifiSsidList.removeAt(index)
                                        settingsVm.storeWifiSsidList(mutableWifiSsidList.toList())
                                    },
                                    onSecondaryActionClick = {
                                        navController.navigate("edit_wifi_ssid_setting_screen/$index")
                                    },
                                    chipType = {
                                        WearTAKTitleChip(
                                            title = wifiSsidItem.name,
                                            route = "edit_wifi_ssid_setting_screen/$index",
                                            onClick = {
                                                it.first.launch { it.second.animateTo(targetValue = RevealValue.RightRevealing) }
                                            },
                                            navController = navController
                                        )
                                    },
                                )
                            }

                            item {
                                Spacer(Modifier.height(10.dp))
                                WearTAKTitleChip(
                                    icon = { Icon(Icons.Filled.Add, contentDescription = "Add") },
                                    title = stringResource(R.string.add_network),
                                    route = "new_wifi_ssid_setting_screen",
                                    navController = navController
                                )
                            }

                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                },
                navController = navController,
            )
        }
        composable(
            "new_wifi_ssid_setting_screen") {
            val newSsidString by remember{ mutableStateOf("")}

            WearTAKStringEntryPage(
                navController = navController,
                item = newSsidString,
                label = stringResource(R.string.wifi_ssid),
                confirmFunction = {
                    val mutableWifiSsidList = wifiSsidList.toMutableList()
                    mutableWifiSsidList.add(DataRepository.WifiSsidItem(it))
                    settingsVm.storeWifiSsidList(mutableWifiSsidList.toList())
                    Log.d(TAG, "storing wifiSsidList: $wifiSsidList")
                }
            )
        }
        composable(
            "edit_wifi_ssid_setting_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "edit_wifi_ssid_setting_screen index: $index")
            WearTAKStringEntryPage(
                navController = navController,
                item = wifiSsidList.getOrNull(index)?.name.orEmpty(),
                label = stringResource(R.string.wifi_ssid),
                confirmFunction = {
                    val mutableWifiSsidList = wifiSsidList.toMutableList()
                    mutableWifiSsidList[index] = DataRepository.WifiSsidItem(it)
                    settingsVm.storeWifiSsidList(mutableWifiSsidList.toList())
                }
            )
        }
        //Callsign and Device Settings ^^^

        //Network Settings vvv
        composable("network_preferences") {
            val checklistEnabled by dataRepository.networkPreferencesChecklistEnabled
                .collectAsStateWithLifecycle(initialValue = false)
            val runtimeDetails by settingsVm.takServerRuntimeDetails.collectAsStateWithLifecycle()
            val offBodyWaiting by TAKConnectService.offBodyTakWaiting.collectAsStateWithLifecycle()
            val statuses = runtimeDetails.associate { detail ->
                val status by detail.connectionStatusLiveData.observeAsState(TAK_SERVER_DISCONNECTED_STRING)
                detail.serverId to status
            }
            val connected = statuses.filterValues { it == TAK_SERVER_CONNECTED_STRING }.keys
            val takStatusOverride = takNetworkStatusOverride(enableTakEndpoint,
                takServerList.filter { it.isEnabled }.map {
                    statuses[it.serverId] ?: TAK_SERVER_DISCONNECTED_STRING
                }, offBodyWaiting)
            val takSubtitleOverride = takStatusOverride?.let { stringResource(it) }
            val pluginToggles by pluginVm.connectivityToggles.collectAsStateWithLifecycle()
            val companionStatus = companionPluginStatus(enableCompanionAppService)
            val dittoPowerSuppressionReason by com.aitech.weartak_civ.service.DittoService.powerSuppressionReason
                .collectAsStateWithLifecycle()
            val dittoSubtitle = stringResource(dittoPdlSubtitle(dittoPdlSource, dittoCustomPdl.isNotBlank()))
            val dittoSuppression = dittoSuppressionLabel(enableDittoService, dittoPowerSuppressionReason)
            val suppressedLabel = dittoSuppression?.let { stringResource(it) }
            val enabledLabel = stringResource(R.string.enabled)
            val disabledLabel = stringResource(R.string.disabled)
            val takIndicator = TakServerIndicator.resolve(
                hasServer = takServerList.isNotEmpty(),
                masterEnabled = enableTakEndpoint,
                anyConnected = takServerList.any { it.isEnabled && it.serverId in connected }
            )
            val takConnectedCount = if (enableTakEndpoint) takServerList.count { it.isEnabled && it.serverId in connected } else 0
            val rows = (listOf(
                NetworkConnectivityRow("tak", stringResource(R.string.tak_servers),
                    takSubtitleOverride ?: stringResource(R.string.network_server_ratio, takConnectedCount, takServerList.size),
                    enableTakEndpoint, settingsVm::storeEnableTakEndpoint,
                    statusDescription = stringResource(R.string.tak_status_with_count,
                        takSubtitleOverride ?: stringResource(takIndicator.label),
                        androidx.compose.ui.res.pluralStringResource(R.plurals.network_server_count,
                            takServerList.size, takConnectedCount, takServerList.size)),
                    iconRes = takIndicator.drawable),
                NetworkConnectivityRow("multicast", stringResource(R.string.tak_sa_multicast),
                    "", enableMulticast, settingsVm::storeEnableMulticast),
                NetworkConnectivityRow("sitx", stringResource(R.string.sitx_tak),
                    when {
                        sitxGroupStatus == SitxGroupStatus.CONNECTED && sitxGroup != null ->
                            stringResource(R.string.network_connected_group, sitxGroup!!.name)
                        sitxGroup != null -> stringResource(R.string.network_disconnected_group, sitxGroup!!.name)
                        else -> stringResource(R.string.network_no_group)
                    }, enableSitx, settingsVm::storeEnableSitx),
                NetworkConnectivityRow("ditto", stringResource(R.string.ditto),
                    suppressedLabel ?: dittoSubtitle,
                    enableDittoService, settingsVm::storeEnableDittoService,
                    statusDescription = if (suppressedLabel != null) "$dittoSubtitle, $suppressedLabel" else dittoSubtitle,
                    suppressed = dittoSuppression != null,
                    suppressionLabel = dittoSuppression ?: R.string.connection_route_suppressed),
                NetworkConnectivityRow("companion", stringResource(R.string.companion_app),
                    "", enableCompanionAppService, settingsVm::storeEnableCompanionAppService,
                    statusDescription = companionStatus)
            ) + pluginList.mapNotNull { plugin ->
                val toggle = pluginToggles[plugin.packageName] ?: return@mapNotNull null
                if (!plugin.isLoaded) return@mapNotNull null
                NetworkConnectivityRow("plugin:${plugin.packageName}", plugin.title,
                    if (toggle.enabled) enabledLabel else disabledLabel, toggle.enabled,
                    { pluginVm.setConnectivityEnabled(plugin.packageName, toggle, it) },
                    pluginIcon = plugin.icon)
            }).alphabetizedConnections(LocalConfiguration.current.locales[0])
            WearTAKPageWithBackArrow(
                "Network Preferences",
                columnContent = {
                    networkPreferencesChecklist(checklistEnabled, rows) { row ->
                        NetworkConnectivityChip(
                            row = row,
                            networkPreferencesLocked = networkPreferencesLocked,
                            phoneManagedSettings = phoneManagedSettings,
                            navigate = { navController.navigate(it) },
                            showPhoneManagedNotice = showPhoneManagedNotice,
                            showNetworkLockedNotice = {
                                Toast.makeText(context, networkLockedNotice, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    item {
                        if (phoneManagedSettings) {
                            WearTAKLockedTitleChip(
                                title = stringResource(R.string.tak_servers),
                                lockedState = stringResource(R.string.network_control_bonded),
                                onClick = showPhoneManagedNotice
                            )
                        } else {
                            WearTAKTitleChipWithState(
                                route = "tak_servers",
                                title = stringResource(R.string.tak_servers),
                                state = takSubtitleOverride.orEmpty(),
                                navController = navController
                            )
                        }
                    }
                    item {
                        MulticastNetworkPreference(
                            enabled = enableMulticast,
                            phoneManagedSettings = phoneManagedSettings,
                            navController = navController,
                            showPhoneManagedNotice = showPhoneManagedNotice
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "sitx_tak_screen",
                            title = stringResource(R.string.sitx_tak),
                            icon = { SitxBrandIcon(contentDescription = null) },
                            state = enableSitx,
                            navController = navController,
                            formatState = {
                                if (enableSitx) {
                                    stringResource(R.string.enabled)
                                } else {
                                    stringResource(R.string.disabled)
                                }
                            }
                        )
                    }
                    item {
                    WearTAKToggleChip(
                    checkedLive = enableSmsAlerts,
                    onCheckedChange = { settingsVm.storeEnableSmsForAlerts(it) },
                    title = stringResource(R.string.send_sms_over_alerts),
                    description = stringResource(R.string.on_off)
                    )
                        }
                    if (enableSmsAlerts){
                        item {
                            WearTAKTitleChip(
                                route = "sms_number_list",
                                title = stringResource(R.string.sms_recipients),
                                navController = navController
                            )
                        }
                    }
                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            thickness = 1.dp,
                            color = Color.White
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "tablet_command",
                            title = stringResource(R.string.tablet_command),
                            state = enableTabletCommandEndpoint,
                            navController = navController,
                            formatState = {
                                if (enableTabletCommandEndpoint) {
                                    stringResource(R.string.enabled)
                                } else {
                                    stringResource(R.string.disabled)
                                }
                            }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "esri",
                            title = stringResource(R.string.esri),
                            state = enableEsriEndpoint,
                            navController = navController,
                            formatState = {
                                if (enableEsriEndpoint) {
                                    stringResource(R.string.enabled)
                                } else {
                                    stringResource(R.string.disabled)
                                }
                            }
                        )
                    }
                },
                navController,
            )
        }
        composable("tak_servers") {
            val runtimeDetails by settingsVm.takServerRuntimeDetails.collectAsStateWithLifecycle()
            val offBodyWaiting by TAKConnectService.offBodyTakWaiting.collectAsStateWithLifecycle()
            val disconnectedStatus = remember { androidx.lifecycle.MutableLiveData(TAK_SERVER_DISCONNECTED_STRING) }
            WearTAKPageWithBackArrow(
                stringResource(R.string.tak_servers_title),
                {
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableTakEndpoint,
                            onCheckedChange = settingsVm::storeEnableTakEndpoint,
                            title = stringResource(R.string.tak_servers),
                            description = networkControlSubtitle("", phoneManagedSettings),
                            enabled = !networkPreferencesLocked && !phoneManagedSettings
                        )
                    }
                    itemsIndexed(
                        takServerList.alphabetizedServerEntries { it.name },
                        key = { _, entry -> entry.value.serverId ?: entry.index }
                    ) { _, (index, takServerItem) ->
                        var takServerChecked by remember(takServerItem.serverId, takServerItem.isEnabled) {
                            mutableStateOf(takServerItem.isEnabled)
                        }
                        val takServerStatus by (runtimeDetails
                            .firstOrNull { it.serverId == takServerItem.serverId }
                            ?.connectionStatusLiveData ?: disconnectedStatus)
                            .observeAsState(initial = TAK_SERVER_DISCONNECTED_STRING)
                        Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
                        WearTAKSwipeToRevealChip(
                            onPrimaryActionClick = {
                                Log.d(
                                    TAG,
                                    "Remove TAK server serverId=${takServerItem.serverId} " +
                                        "address=${takServerItem.address}:${takServerItem.port}"
                                )
                                val mutableTakServerList = takServerList.toMutableList()
                                mutableTakServerList.removeAt(index)
                                settingsVm.storeTakServerList(mutableTakServerList.toList())
                                Log.d(TAG, "onPrimaryActionClick index($index): REMOVE")
                                settingsVm.removeTakServerItem(takServerItem)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    EventBus.getDefault().post(
                                        MessageEvent(SERVER_REMOVED, index)
                                    )
                                }, EVENT_SEND_DELAY_MS)
                            },
                            onSecondaryActionClick = {
                                navController.navigate("edit_server_screen/$index")
                            },
                            chipType = {
                                WearTAKSplitToggleChip(
                                    appIcon = {
                                        Image(
                                            painter = if (takServerStatus == TAK_SERVER_CONNECTED_STRING) painterResource(
                                                R.drawable.tak_server_connected
                                            ) else if (takServerStatus == TAK_SERVER_CONNECTING_STRING) painterResource(
                                                R.drawable.tak_server_connecting
                                            ) else painterResource(R.drawable.tak_server_disconnected),
                                            contentDescription = "Top Left Icon",
                                            modifier = Modifier
                                                .size(22.dp)
                                                .padding(end = 2.dp),
                                        )
                                    },
                                    title = takServerItem.name,
                                    label = stringResource(takServerStatusLabel(
                                        enableTakEndpoint && takServerChecked, takServerStatus, offBodyWaiting)),
                                    checked = takServerChecked,
                                    onCheckedChange = {
                                        Log.d(TAG, "onCheckedChange index($index): $it")
                                        takServerChecked = it
                                        Log.d(
                                            TAG,
                                            "index($index): takServerChecked($takServerChecked)"
                                        )
                                        takServerList[index].isEnabled = it
                                        val mutableTakServerList =
                                            takServerList.toMutableList()
                                        mutableTakServerList.get(index).isEnabled = it
                                        settingsVm.updateTakServerItem(index, takServerItem)
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            EventBus.getDefault().post(
                                                MessageEvent(SERVER_TOGGLED, index)
                                            )
                                        }, EVENT_SEND_DELAY_MS)
                                    },
                                    onClick = { it.first.launch { it.second.animateTo(targetValue = RevealValue.RightRevealing) } },
                                )
                            },
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                },
                navController,
                titleAction = {
                    WearTAKTitleAddButton(
                        contentDescription = stringResource(R.string.add_tak_server),
                        onClick = {
                            navController.navigate("new_server_screen") { launchSingleTop = true }
                        }
                    )
                },
            )
        }

        composable("new_server_screen"){
            val isNetworkConnected = remember { mutableStateOf(false) }
            val prefill = remember { enrollPrefill }
            DisposableEffect(Unit) { onDispose { enrollPrefill = null } }
            var name by remember { mutableStateOf(prefill?.name.orEmpty()) }
            var address by remember { mutableStateOf(prefill?.address.orEmpty()) }
            var port by remember { mutableStateOf(prefill?.port?.toString() ?: "8089") }
            var username by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var isP12Cert = false
            val serverSettingsChanged = remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val certStatus = rememberSideloadedCertStatus(address)
            val credentialsOptional = certStatus is SideloadedClientCert.Status.Found

            WearTAKPageWithConfirmCancel(
                title = stringResource(R.string.new_server),
                columnContent = {
                    item {
                        name = WearTAKOutlinedTextField(
                            string = name,
                            label = stringResource(R.string.connection_name),
                            keyboardType = KeyboardType.Text
                        ).text
                    }
                    item {
                        address = WearTAKOutlinedTextField(
                            string = address,
                            label = stringResource(R.string.address_url),
                            keyboardType = KeyboardType.Text
                        ).text
                    }
                    item {
                        port = WearTAKOutlinedTextField(
                            string = port,
                            label = stringResource(R.string.port),
                            keyboardType = KeyboardType.Number,
                        ).text
                    }
                    if (certStatus != SideloadedClientCert.Status.None) {
                        item { SideloadedCertNotice(certStatus) }
                    }
                    item {
                        username = WearTAKOutlinedTextField(
                            string = username,
                            label = stringResource(
                                if (credentialsOptional) R.string.username_optional else R.string.username
                            ),
                            keyboardType = KeyboardType.Text
                        ).text
                    }
                    item {
                        password = WearTAKOutlinedTextField(
                            string = password,
                            label = stringResource(
                                if (credentialsOptional) R.string.password_optional else R.string.password
                            ),
                            keyboardType = KeyboardType.Text
                        ).text
                    }
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                },
                navController = navController,
                onClickCancel = {},
                onClickConfirm = {
                    if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() &&
                        (credentialsOptional || (username.isNotBlank() && password.isNotBlank()))) {
                        val takServerItem = DataRepository.TakServerItem(true, name, address.trim(), port.toInt(),
                            isP12Cert, username, password, "", "")
                        settingsVm.saveAndRegisterTakServer(takServerItem)
                        serverSettingsChanged.value = true
                    } else {
                        Toast.makeText(
                            mainActivity,
                            mainActivity.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
                    }
                },
                loadingDialogBehavior = {
                    if (serverSettingsChanged.value) {
                        val registration by settingsVm.serverRegistrationState.collectAsStateWithLifecycle()
                        WearTAKRegistrationDialogs(registration, serverSettingsChanged, navController)
                    }
                }
            )
        }
        composable(
            "edit_server_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "edit_server_screen index: $index")
            Log.d(TAG, "Edited TAK server list size=${takServerList.size}")
            var name by remember { mutableStateOf(takServerList.getOrNull(index)?.name.orEmpty()) }
            var address by remember { mutableStateOf(takServerList.getOrNull(index)?.address.orEmpty()) }
            var port by remember { mutableStateOf(takServerList.getOrNull(index)?.port.toString().orEmpty()) }
            var username by remember { mutableStateOf(takServerList.getOrNull(index)?.username.orEmpty()) }
            var password by remember { mutableStateOf(takServerList.getOrNull(index)?.password.orEmpty()) }
            var isP12Cert by remember { mutableStateOf(takServerList.get(index)?.isP12Cert ?: false) }
            val serverConnSettingsChanged = remember { mutableStateOf(false) }
            val runtimeDetails by settingsVm.takServerRuntimeDetails.collectAsStateWithLifecycle()
            val offBodyWaiting by TAKConnectService.offBodyTakWaiting.collectAsStateWithLifecycle()
            val disconnectedStatus = remember { androidx.lifecycle.MutableLiveData(TAK_SERVER_DISCONNECTED_STRING) }
            val savedServer = takServerList.getOrNull(index)
            val connectionStatus by (runtimeDetails
                .firstOrNull { it.serverId == savedServer?.serverId }
                ?.connectionStatusLiveData ?: disconnectedStatus)
                .observeAsState(TAK_SERVER_DISCONNECTED_STRING)
            val certStatus = rememberSideloadedCertStatus(address)
            val credentialsOptional = certStatus is SideloadedClientCert.Status.Found
            Log.d(TAG, "address=$address, serverSettingsChanged=${serverConnSettingsChanged.value}")

            WearTAKPageWithConfirmCancel(
                title = stringResource(R.string.edit_server),
                columnContent = {
                    item {
                        Text(
                            stringResource(takServerStatusLabel(
                                enableTakEndpoint && savedServer?.isEnabled == true,
                                connectionStatus, offBodyWaiting)),
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(5.dp),
                        )
                    }
                    item {
                        name = WearTAKOutlinedTextField(
                            string = name,
                            label = stringResource(R.string.connection_name),
                            keyboardType = KeyboardType.Text
                        ).text
                    }
                    if (isP12Cert) {
                        item {
                            address = WearTAKOutlinedTextField(
                                string = address,
                                label = stringResource(R.string.address_url),
                                keyboardType = KeyboardType.Text
                            ).text
                        }
                        item {
                            port = WearTAKOutlinedTextField(
                                string = port,
                                label = stringResource(R.string.port),
                                keyboardType = KeyboardType.Number
                            ).text
                        }
                    } else {
                        item {
                            address = WearTAKOutlinedTextField(
                                string = address,
                                label = stringResource(R.string.address_url),
                                keyboardType = KeyboardType.Text
                            ).text
                        }
                        item {
                            port = WearTAKOutlinedTextField(
                                string = port,
                                label = stringResource(R.string.port),
                                keyboardType = KeyboardType.Number
                            ).text
                        }
                        if (certStatus != SideloadedClientCert.Status.None) {
                            item { SideloadedCertNotice(certStatus) }
                        }
                        item {
                            username = WearTAKOutlinedTextField(
                                string = username,
                                label = stringResource(
                                    if (credentialsOptional) R.string.username_optional else R.string.username
                                ),
                                keyboardType = KeyboardType.Text
                            ).text
                        }
                        item {
                            password = WearTAKOutlinedTextField(
                                string = password,
                                label = stringResource(
                                    if (credentialsOptional) R.string.password_optional else R.string.password
                                ),
                                keyboardType = KeyboardType.Text
                            ).text
                        }
                    }
                    if (isP12Cert) {
                        item {
                            Text(
                                text = stringResource(R.string.using_client_certificate),
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(5.dp)
                            )
                        }
                    }
                    item {Spacer(modifier = Modifier.height(10.dp))}
                },
                navController = navController,
                onClickCancel = { Log.d(TAG, "EditServerPage Settings NOT saved")
                   },
                onClickConfirm = {
                    if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() &&
                        (if (isP12Cert || credentialsOptional) { true } else { username.isNotBlank() } ) &&
                        (if (isP12Cert || credentialsOptional) { true } else { password.isNotBlank() } ) ) {
                        val takServerDetails = takServerList.get(index)
                        Log.d(TAG, "EditServerPage: onClickConfirm all settings good")
                        Log.d(
                            TAG,
                            "TAK server edit serverId=${takServerDetails.serverId} " +
                                "name=$name address=$address:$port isP12=$isP12Cert"
                        )
                        if (name != takServerDetails.name || address != takServerDetails.address || port != takServerDetails.port.toString() ||
                            username != takServerDetails.username || password != takServerDetails.password ||
                            isP12Cert != takServerDetails.isP12Cert) {
                            if (name == takServerDetails.name) {
                                // Name is same, so something else in address, port, username, or password has changed
                                serverConnSettingsChanged.value = true
                                Log.d(TAG, "serverConnSettingsChanged set to true")
                            } else { // Name is different
                                if (address != takServerDetails.address || port.toInt() != takServerDetails.port ||
                                    username != takServerDetails.username || password != takServerDetails.password ||
                                    isP12Cert != takServerDetails.isP12Cert) {
                                    // Apart from name, something else is different
                                    serverConnSettingsChanged.value = true
                                    Log.d(TAG, "serverConnSettingsChanged set to true with name being different")
                                }
                            }
                            val changedTakServerDetail: DataRepository.TakServerItem =
                                if (isP12Cert == true) {
                                    takServerDetails.copy(name = name, address = address.trim(), port = port.toInt(), isP12Cert = isP12Cert)
                                } else {
                                    takServerDetails.copy(name = name, address = address.trim(), port = port.toInt(), isP12Cert = isP12Cert,
                                        username = username, password = password, p12Cert = "", p12CertPassword = "")
                                }

                            if (serverConnSettingsChanged.value) {
                                settingsVm.saveAndRegisterTakServer(changedTakServerDetail, index)
                            } else {
                                settingsVm.updateTakServerItem(index, changedTakServerDetail)
                                navController.navigate("tak_servers") {
                                    popUpTo(("tak_servers")) {
                                        inclusive = true
                                    }
                                }
                            }
                        } else {
                            Log.d(TAG, "EditServerPage onClickConfirm: nothing has changed")
                            serverConnSettingsChanged.value = false
                            navController.navigate("tak_servers") {
                                popUpTo(("tak_servers")) {
                                    inclusive = true
                                }
                            }
                        }
                    } else {
                        Toast.makeText(mainActivity, mainActivity.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
                    }
                },
                loadingDialogBehavior = {
                    if (serverConnSettingsChanged.value) {
                        val registration by settingsVm.serverRegistrationState.collectAsStateWithLifecycle()
                        WearTAKRegistrationDialogs(registration, serverConnSettingsChanged, navController)
                    }
                }
            )
        }
        composable("tak_sa_multicast"){
            WearTAKPageWithBackArrow(
                title = stringResource(R.string.tak_sa_multicast),
                columnContent = {
                    item {
                        MulticastNetworkToggle(
                            enabled = enableMulticast,
                            phoneManagedSettings = phoneManagedSettings,
                            networkPreferencesLocked = networkPreferencesLocked,
                            onCheckedChange = settingsVm::storeEnableMulticast
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "multicast_address",
                            title = stringResource(R.string.address),
                            state = multicastAddress,
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "multicast_protocol",
                            title =  stringResource(R.string.output_protocol),
                            state = multicastProtocol,
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "multicast_port",
                            title =  stringResource(R.string.port),
                            state = multicastPort,
                            navController = navController
                        )
                    }
                },
                navController = navController,
            )
        }
        composable("multicast_address"){
            WearTAKStringEntryPage(
                navController = navController,
                item = multicastAddress,
                label =  stringResource(R.string.address),
                confirmFunction = { settingsVm.storeMulticastAddress(it) }
            ) 
        }
        composable("multicast_protocol"){
            val outputProtocol = outputProtocolList.map { stringResource(id = it) }

            WearTAKSelectionPage(
                settingsVm.findRadioIndex(multicastProtocol, outputProtocol),
                stringResource(R.string.output_protocol),
                navController,
                outputProtocol,
                { settingsVm.storeMulticastProtocol(it) }
            )
        }
        composable("multicast_port"){
            WearTAKIntEntryPage(
                navController = navController,
                item = multicastPort,
                label =  stringResource(R.string.port),
                maximum = 1000000000,
                minimum = 0,
                context = context,
                confirmFunction = { settingsVm.storeMulticastPort(it)}
            )
        }

        composable("sitx_tak_screen") {
//            val nameSecondary = if (enableSitxService) sitxStatusText(sitxEvent) else "Service not enabled"
            val showRemoveConfigurationDialog = remember { mutableStateOf(false) }

            WearTAKPageWithBackArrow(
                title = stringResource(R.string.sitx_tak),
                columnContent = {
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableSitx,
                            onCheckedChange = { settingsVm.storeEnableSitx(it) },
                            title = stringResource(R.string.sitx_tak),
                            description = stringResource(R.string.service_on_off)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "sitx_tak_url_screen",
                            title = stringResource(R.string.address),
                            state = "$sitxUrl.sitx.io",
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "sitx_tak_group_screen",
                            title =  stringResource(R.string.group),
                            state = sitxGroup?.name ?: "No Group Selected",
                            navController = navController
                        )
                    }
                    //TODO: Add conditional and reauth button
                    item {
                        WearTAKTitleChipWithState(
                            route = "sitx_status_authorization_screen",
                            title =  "Sit(x) State",
                            state = settingsVm.sitxStatusText(sitxEvent),
                            navController = navController
                        )
                    }
                    item{
                        Button(
                            onClick = {
                                runBlocking {
                                    SitxServiceCommandBus.trySend(SitxServiceCommand.BeginAuth(
                                        baseUrl = sitxRepo.baseUrl.first(),
                                        clientId = sitxRepo.clientId.first() ?: "",
                                        callsign = callsign,
                                        deviceName = device_uid,
                                        deviceId = device_uid
                                    )
                                    )
                                }
                            },
                            contentPadding = PaddingValues(10.dp),
                            colors = ButtonDefaults.buttonColors(colors.primary),
                        ) {
                            Text(
                                text = "Re-Auth",
                                color = Color.Black
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { showRemoveConfigurationDialog.value = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.remove),
                                color = Color.White
                            )
                        }
                    }
                },
                navController = navController,
            )

            WearTAKAlertDialog(
                shouldShowDialog = showRemoveConfigurationDialog,
                modifier = Modifier.wrapContentHeight(),
                icon = {
                    Icon(
                        Icons.Default.Warning,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.LightGray
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.remove_sitx_configuration_confirmation),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRemoveConfigurationDialog.value = false
                            settingsVm.removeSitxConfiguration()
                        },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text(
                            text = stringResource(R.string.remove),
                            color = Color.White
                        )
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showRemoveConfigurationDialog.value = false },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) {
                        Text(text = stringResource(R.string.dismiss))
                    }
                }
            )
        }
        composable("sitx_tak_url_screen") {
            WearTAKStringEntryPage(
                navController = navController,
                item = sitxUrl,
                label =  stringResource(R.string.address),
                confirmFunction = { settingsVm.storeSitxTakUrl(it) }
            )
        }

        composable("sitx_tak_group_screen"){
            WearTAKSitxGroupSetting(
                navController,
                sitxGroupsList,
                sitxGroup,
                sitxGroupStatus,
                settingsVm
            )
        }

        composable("sitx_status_authorization_screen") {
            WearTAKSitxStatusAuthorizationScreen(navController, sitxEvent)
        }

        composable("chat_commands") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.chat_commands),
                columnContent = {
                    item {
                        WearTAKTitleChip(
                            route = "chat_command_callsigns",
                            title = stringResource(R.string.chat_command_callsigns),
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChip(
                            route = "chat_command_profiles",
                            title = stringResource(R.string.command_profiles),
                            navController = navController
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.chat_command_examples),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    itemsIndexed(
                        listOf(
                            R.string.chat_command_example_callsign,
                            R.string.chat_command_example_location_fused,
                            R.string.chat_command_example_location_gnss,
                            R.string.chat_command_example_role,
                            R.string.chat_command_example_team,
                        )
                    ) { _, example ->
                        Text(
                            text = stringResource(example),
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            softWrap = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                },
                navController,
            )
        }

        composable("chat_command_profiles") {
            CommandProfilesPage(navController)
        }
        composable("chat_command_profile/{name}") { entry ->
            CommandProfilesPage(navController, entry.arguments?.getString("name"))
        }

        composable("sensor_manager") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.sensor_manager),
                columnContent = {
                    item {
                        Text(stringResource(R.string.unfunded_request), modifier = Modifier.padding(8.dp))
                    }
                    item {
                        Text(stringResource(R.string.sensor_manager_preview), modifier = Modifier.padding(8.dp))
                    }
                    item {
                        Chip(
                            onClick = {},
                            label = { Text(stringResource(R.string.scan_sensor)) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ChipDefaults.chipColors(backgroundColor = Color.DarkGray, contentColor = Color.White)
                        )
                    }
                },
                navController,
            )
        }

        composable("chat_command_callsigns") {
            val sortedCallsigns = ChatCommandAuthorizer.sortedAllowList(chatCommandCallsigns)

            WearTAKPageWithBackArrow(
                title = stringResource(R.string.chat_command_callsigns),
                columnContent = {
                    if (sortedCallsigns.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_chat_command_callsigns),
                                modifier = Modifier.padding(8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        items(
                            count = sortedCallsigns.size,
                            key = { index -> sortedCallsigns[index] }
                        ) { index ->
                            val callsign = sortedCallsigns[index]
                            CommandCallsignRow(
                                callsign = callsign,
                                onEdit = {
                                    navController.navigate("chat_command_callsign_edit/${Uri.encode(callsign)}")
                                },
                                onRemove = {
                                    settingsVm.storeChatCommandCallsigns(
                                        ChatCommandAuthorizer.sortedAllowList(
                                            chatCommandCallsigns.filterNot {
                                                ChatCommandAuthorizer.normalize(it) ==
                                                    ChatCommandAuthorizer.normalize(callsign)
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    }
                    item {
                        Chip(
                            onClick = { navController.navigate("chat_command_callsign_add") },
                            label = { Text(stringResource(R.string.add_callsign)) },
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ChipDefaults.chipColors(
                                backgroundColor = Color.DarkGray,
                                contentColor = Color.White
                            )
                        )
                    }
                },
                navController = navController
            )

        }
        composable("chat_command_callsign_add") {
            CommandCallsignEntryPage(navController, chatCommandCallsigns) {
                settingsVm.storeChatCommandCallsigns(it)
            }
        }
        composable("chat_command_callsign_edit/{callsign}") { entry ->
            CommandCallsignEntryPage(
                navController,
                chatCommandCallsigns,
                originalCallsign = requireNotNull(entry.arguments?.getString("callsign"))
            ) {
                settingsVm.storeChatCommandCallsigns(it)
            }
        }
        composable("sms_number_list"){
            val focusManager = LocalFocusManager.current
            var textfield = ""
            WearTAKStringEntryPage(
                navController = navController,
                item = settingsVm.convertSmsNumbersListToStringWithSeparator(smsNumberList, ","),
                label =  stringResource(R.string.sms_recipients),
                confirmFunction = {
                    textfield = it
                    if (it != settingsVm.convertSmsNumbersListToStringWithSeparator(smsNumberList, ",")) {
                        val smsNumbersListConversion: List<DataRepository.SmsNumber> =
                            it.split(",")
                                .map { it.trim() } // Trim whitespace from each SSID
                                .filter { it.isNotEmpty() } // Remove any empty values
                                .map { DataRepository.SmsNumber(it) }
                        Log.d(TAG, "Storing smsNumbersList ${smsNumbersListConversion}")
                        settingsVm.storeSmsNumbersList(smsNumbersListConversion)
                    }
                },
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (textfield.contains("[A-Za-z!\"#$%&'()*./:;\\\\<=>?@^_`{|}~]".toRegex())) {
                            Toast.makeText(
                                mainActivity,
                                getString(context, R.string.invalid_characters),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            focusManager.clearFocus()
                        }
                    }
                )
            )
        }
        composable("tablet_command"){
            WearTAKPageWithBackArrow(
                title =  stringResource(R.string.tablet_command),
                columnContent = {
                    //TODO: BUG!! Value doesn't persist on boot (also a develop issue)
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableTabletCommandEndpoint,
                            onCheckedChange = {
                                settingsVm.storeEnableTabletCommandEndpoint(it)
                                Log.d(TAG, "Storing enable tablet command endpoint: $it")
                            },
                            title =  stringResource(R.string.tablet_command),
                            description = stringResource(R.string.on_off)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "tablet_command_endpoint",
                            title =  stringResource(R.string.endpoint),
                            state = tabletCommandEndpoint,
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "tablet_command_api_key",
                            title =  stringResource(R.string.api_key),
                            state = tabletCommandAPIKey,
                            navController = navController
                        )
                    }
                },
                navController = navController,
            )
        }
        composable("tablet_command_endpoint"){
            WearTAKStringEntryPage(
                navController = navController,
                item = tabletCommandEndpoint,
                label =  stringResource(R.string.endpoint),
                confirmFunction = { settingsVm.storeTabletCommandEndpoint(it) }
            )
        }
        composable("tablet_command_api_key"){
            WearTAKStringEntryPage(
                navController = navController,
                item = tabletCommandAPIKey,
                label =  stringResource(R.string.api_key),
                confirmFunction = { settingsVm.storeTabletCommandApikey(it) }
            )
        }
        composable("esri"){
            WearTAKPageWithBackArrow(
                title =  stringResource(R.string.esri),
                columnContent = {
                    //TODO: BUG!! Value doesn't persist on boot (also a develop issue)
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableEsriEndpoint,
                            onCheckedChange = {
                                settingsVm.storeEnableEsriEndpoint(it)
                                Log.d(TAG, "Storing enable esri endpoint: $it")
                            },
                            title = "ESRI",
                            description = stringResource(R.string.on_off)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "esri_endpoint",
                            title =  stringResource(R.string.endpoint),
                            state = esriEndpoint,
                            navController = navController
                        )
                    }
                },
                navController = navController,
            )
        }
        composable("esri_endpoint"){
            WearTAKStringEntryPage(
                navController = navController,
                item = esriEndpoint,
                label =  stringResource(R.string.endpoint),
                confirmFunction = { settingsVm.storeEsriEndpoint(it) }
            )
        }
        //Network Settings ^^^

        //Alerting Settings vvv
        composable("alerting_preferences") {
            val entries = alertPreferenceEntries(context, "alerting_preferences")
            WearTAKPageWithBackArrow(
                stringResource(R.string.alerting_preferences),
                columnContent = {
                    items(entries.size) { index ->
                        val (route, label) = entries[index]
                        if (route == "battery_alerts") {
                            WearTAKToggleChip(
                                checkedLive = enableBatteryAlerts,
                                onCheckedChange = { settingsVm.storeEnableBatteryAlerts(it) },
                                title = label,
                                description = stringResource(R.string.on_off)
                            )
                        } else {
                            WearTAKTitleChip(route = route, title = label, navController = navController)
                        }
                    }
                },
                navController,
            )
        }
        composable("physiological_alerts") {
            val entries = alertPreferenceEntries(context, "physiological_alerts")
            WearTAKPageWithBackArrow(
                stringResource(R.string.physiological_alerts),
                columnContent = {
                    item {
                        WearTAKToggleChip(
                            checkedLive = enablePhysioAlerts,
                            onCheckedChange = settingsVm::storeEnablePhysiologicalAlerts,
                            title = stringResource(R.string.physiological_alerts),
                            description = stringResource(R.string.on_off)
                        )
                    }
                    items(entries.size) { index ->
                        val (route, label) = entries[index]
                        WearTAKTitleChip(route = route, title = label, navController = navController)
                    }
                },
                navController,
            )
        }

        composable("heart_rate_alerts") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.heart_rate_alerts),
                columnContent = {
                    //TODO: Do we want to add resting hr?
//                    WearTAKTitleChipWithState(
//                        route = "resting_hr",
//                        title = "Resting Heart Rate ",
//                        state =
//                        navController = navController
//                    )
                    item {
                        Text(
                            text = stringResource(R.string.high_resting_heart_rate),
                            textAlign = TextAlign.Left,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(bottom = 3.dp)
                                .padding(horizontal = 10.dp)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "high_resting_hr_threshold",
                            title = stringResource(R.string.high_hr_threshold),
                            state = highHrThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}" + " " + stringResource(R.string.bpm) }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "high_resting_hr_warning_length",
                            title = stringResource(R.string.warning_length),
                            state = highHrWarningLength,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} " + stringResource(R.string.minutes) }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "high_resting_hr_alert_length",
                            title = stringResource(R.string.alert_length),
                            state = highHrAlertLength,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} " + stringResource(R.string.minutes) }
                        )
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                    }
                    item {
                        Text(
                            text = stringResource(R.string.low_resting_heart_rate),
                            textAlign = TextAlign.Left,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(bottom = 3.dp)
                                .padding(horizontal = 10.dp)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "low_resting_hr_threshold",
                            title = stringResource(R.string.low_hr_threshold),
                            state = lowHrThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} bpm" }
                        )
                    }
                    item {
                    WearTAKTitleChipWithState(
                        route = "low_resting_hr_warning_length",
                        title = stringResource(R.string.warning_length),
                        state = lowHrWarningLength,
                            navController = navController,
                        formatState = { value -> "${value ?: 0} " + stringResource(R.string.minutes) }
                    )}
                    item {
                        WearTAKTitleChipWithState(
                            route = "low_resting_hr_alert_length",
                            title = stringResource(R.string.alert_length),
                            state = lowHrAlertLength,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} " + stringResource(R.string.minutes) }
                        )
                    }
                },
                navController,
            )
        }
        composable("high_resting_hr_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = highHrThreshold,
                label = stringResource(R.string.high_hr_threshold),
                maximum = 300,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeRestingHrHighAlertBpmThreshold(it)}
            )
        }
        composable("high_resting_hr_warning_length"){
            WearTAKIntEntryPage(
                navController = navController,
                item = highHrWarningLength,
                label = stringResource(R.string.warning_length),
                maximum = 300,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeHighRestingHrWarningThresholdLength(it)}
            )
        }
        composable("high_resting_hr_alert_length"){
            WearTAKIntEntryPage(
                navController = navController,
                item = highHrAlertLength,
                label = stringResource(R.string.alert_length),
                maximum = 300,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeHighRestingHrAlertThresholdLength(it)}
            )
        }
        composable("low_resting_hr_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = lowHrThreshold,
                label = stringResource(R.string.low_hr_threshold),
                maximum = 300,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeRestingHrLowAlertBpmThreshold(it)}
            )
        }
        composable("low_resting_hr_warning_length"){
            WearTAKIntEntryPage(
                navController = navController,
                item = lowHrWarningLength,
                label = stringResource(R.string.warning_length),
                maximum = 300,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeLowRestingHrWarningThresholdLength(it)}
            )
        }
        composable("low_resting_hr_alert_length"){
            WearTAKIntEntryPage(
                navController = navController,
                item = lowHrAlertLength,
                label = stringResource(R.string.alert_length),
                maximum = 300,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeLowRestingHrAlertThresholdLength(it)}
            )
        }
        composable("exertion_alerts") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.exertion_alerts),
                columnContent = {
                    item {
                        WearTAKTitleChipWithState(
                            route = "exertion_warning_threshold",
                            title = stringResource(R.string.exertion_warning_threshold),
                            state = exertionWarningThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}%" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "exertion_warning_length",
                            title = stringResource(R.string.warning_length),
                            state = exertionWarningLength,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} " + stringResource(R.string.seconds) }
                        )
                    }
                    item {
                    Spacer(Modifier.height(10.dp))}
                    item {
                        WearTAKTitleChipWithState(
                            route = "exertion_alert_threshold",
                            title = stringResource(R.string.exertion_alert_threshold),
                            state = exertionAlertThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}%" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "exertion_alert_length",
                            title = stringResource(R.string.alert_length),
                            state = exertionAlertLength,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}" + stringResource(R.string.seconds) }
                        )
                    }
                },
                navController,
            )
        }
        composable("exertion_warning_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = exertionWarningThreshold,
                label = stringResource(R.string.exertion_warning_threshold),
                maximum = 201,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeExertionThresholdWarning(it)}
            )
        }
        composable("exertion_warning_length"){
            WearTAKIntEntryPage(
                navController = navController,
                item = exertionWarningLength,
                label = stringResource(R.string.warning_length),
                maximum = 1201,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeExertionWarningThresholdLength(it)}
            )
        }
        composable("exertion_alert_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = exertionAlertThreshold,
                label = stringResource(R.string.exertion_alert_threshold),
                maximum = 1201,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeExertionThresholdAlert(it)}
            )
        }
        composable("exertion_alert_length"){
            WearTAKIntEntryPage(
                navController = navController,
                item = exertionAlertLength,
                label = stringResource(R.string.alert_length),
                maximum = 1201,
                minimum = 0,
                context = context,
                confirmFunction = {settingsVm.storeExertionAlertThresholdLength(it)}
            )
        }
        composable("environmental_alerts") {
            val entries = alertPreferenceEntries(context, "environmental_alerts")
            WearTAKPageWithBackArrow(
                stringResource(R.string.environmental_alerts),
                columnContent = {
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableEnvironmentalAlerts,
                            onCheckedChange = settingsVm::storeEnableEnvironmentalAlerts,
                            title = stringResource(R.string.environmental_alerts),
                            description = stringResource(R.string.on_off)
                        )
                    }
                    items(entries.size) { index ->
                        val (route, label) = entries[index]
                        WearTAKTitleChip(route = route, title = label, navController = navController)
                    }
                },
                navController,
            )
        }
        composable("immersion_alerts") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.immersion_alerts),
                columnContent = {
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableEnvironmentalAlerts && enableImmersionAlerts,
                            enabled = enableEnvironmentalAlerts,
                            onCheckedChange = { settingsVm.storeEnableImmersionAlerts(it) },
                            title = stringResource(R.string.immersion_alerts),
                            description = stringResource(R.string.service_on_off)
                        )
                    }
                },
                navController,
            )
        }
        composable("atm_pressure_alerts") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.atm_pressure_alerts),
                columnContent = {
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableEnvironmentalAlerts && enableLowAtmPressureAlert,
                            enabled = enableEnvironmentalAlerts,
                            onCheckedChange = { settingsVm.storeEnableLowAtmPressureAlert(it) },
                            title = stringResource(R.string.low_atm_pressure_alert),
                            description = stringResource(R.string.on_off)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "low_pressure_threshold",
                            title = stringResource(R.string.low_atm_pressure_threshold),
                            state = lowAtmPressureAlertThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} hPa" }
                        )
                    }
                    item {
                    Spacer(Modifier.height(10.dp))}
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableEnvironmentalAlerts && enableHighAtmPressureAlert,
                            enabled = enableEnvironmentalAlerts,
                            onCheckedChange = { settingsVm.storeEnableHighAtmPressureAlert(it) },
                            title = stringResource(R.string.high_atm_pressure_alert),
                            description = stringResource(R.string.on_off)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "high_pressure_threshold",
                            title = stringResource(R.string.high_atm_pressure_threshold),
                            state = highAtmPressureAlertThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0} hPa" }
                        )
                    }
                },
                navController,
            )
        }
        composable("low_pressure_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = lowAtmPressureAlertThreshold,
                label = stringResource(R.string.low_atm_pressure_threshold),
                maximum = 100000,
                minimum = 0,
                context = context,
                confirmFunction = { settingsVm.storeLowAtmPressureAlertThreshold(it)}
            )
        }
        composable("high_pressure_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = highAtmPressureAlertThreshold,
                label = stringResource(R.string.high_atm_pressure_threshold),
                maximum = 100000,
                minimum = 0,
                context = context,
                confirmFunction = { settingsVm.storeHighAtmPressureAlertThreshold(it)}
            )
        }
        //Alerting Settings ^^^

        //Tool Settings vvv
        composable("tool_preferences") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.tool_preferences),
                columnContent = {
                    item {
                        WearTAKTitleChip(
                            route = "bloodhound_compass",
                            title = stringResource(R.string.bloodhound_compass),
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChip(
                            route = "display_preferences",
                            title = stringResource(R.string.display_preferences),
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChip(
                            route = "plugins",
                            title = stringResource(R.string.plugins),
                            navController = navController
                        )
                    }
                },
                navController,
            )
        }
        composable("display_preferences") {
            val unitPrefs = currentUnitPrefs()
            WearTAKPageWithBackArrow(
                stringResource(R.string.display_preferences),
                columnContent = {
                    item {
                        WearTAKTitleChipWithState(
                            route = "display_units",
                            title = stringResource(R.string.units),
                            state = unitPrefs.isImperial,
                            formatState = { imperial ->
                                stringResource(if (imperial == true) R.string.units_imperial else R.string.units_metric)
                            },
                            navController = navController
                        )
                    }
                },
                navController,
            )
        }
        composable("display_units") {
            val unitPrefs = currentUnitPrefs()
            val options = listOf(
                Triple(UnitPrefs.RANGE_IMPERIAL, R.string.units_imperial, R.string.units_imperial_desc),
                Triple(UnitPrefs.RANGE_METRIC, R.string.units_metric, R.string.units_metric_desc),
            )
            val titles = options.map { stringResource(it.second) }
            WearTAKPageWithBackArrow(
                stringResource(R.string.units),
                {
                    itemsIndexed(options) { index, option ->
                        WearTAKSelectableChip(
                            selected = unitPrefs.rangeUnits == option.first,
                            onClick = { settingsVm.storeRangeUnits(option.first) },
                            title = titles[index],
                            secondaryTitle = stringResource(option.third),
                            combined = true,
                            list = titles
                        )
                    }
                },
                navController,
            )
        }
        composable("bloodhound_compass") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.bloodhound_compass),
                columnContent = {
                    item {
                        WearTAKToggleChip(
                            checkedLive = enableBloodhoundProximityVibration,
                            onCheckedChange = { settingsVm.storeEnableBloodhoundProximityVibration(it) },
                            title = stringResource(R.string.bloodhound_proximity_vibration),
                            description = stringResource(R.string.on_off)
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "bloodhound_proximity_radius",
                            title = stringResource(R.string.bloodhound_proximity_radius),
                            state = bloodhoundProximityRadius,
                            formatState = { value ->
                                UnitFormatter.shortDistance((value ?: 0).toDouble(), currentUnitPrefs())
                            },
                            navController = navController
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "bloodhound_proximity_intensity",
                            title = stringResource(R.string.bloodhound_proximity_intensity),
                            state = bloodhoundProximityIntensity,
                            formatState = { when (bloodhoundProximityIntensity) {
                                BLOODHOUND_PROXIMITY_INTENSITY_BASIC -> stringResource(R.string.bloodhound_proximity_intensity_basic)
                                BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED -> stringResource(R.string.bloodhound_proximity_intensity_escalated)
                                BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT -> stringResource(R.string.bloodhound_proximity_intensity_persistent)
                                else -> stringResource(R.string.error)
                                }
                            },
                            navController = navController,
                        )
                    }
                },
                navController,
            )
        }
        composable("bloodhound_proximity_radius"){
            // Stored in meters; imperial users enter feet.
            val imperial = currentUnitPrefs().isImperial
            WearTAKIntEntryPage(
                navController = navController,
                item = if (imperial) (bloodhoundProximityRadius / UnitFormatter.METERS_PER_FOOT).roundToInt() else bloodhoundProximityRadius,
                label = stringResource(R.string.bloodhound_proximity_radius) + if (imperial) " (ft)" else " (m)",
                maximum = if (imperial) (100 / UnitFormatter.METERS_PER_FOOT).toInt() else 100,
                minimum = 0,
                context = context,
                confirmFunction = {
                    settingsVm.storeBloodhoundProximityRadiusMeters(
                        if (imperial) (it * UnitFormatter.METERS_PER_FOOT).roundToInt().coerceIn(0, 100) else it
                    )
                }
            )
        }
        composable("bloodhound_proximity_intensity"){
            val bloodhoundList = bloodhoundIntensityOptions.map { stringResource(id = it) }

            val index = settingsVm.findRadioIndex(bloodhoundProximityIntensity, bloodhoundIntensityOptionsDataRepo)
            var selectedRadioIndex by remember { mutableStateOf(index) }

            WearTAKPageWithBackArrow(
                stringResource(R.string.bloodhound_proximity_intensity),
                {
                    itemsIndexed(bloodhoundList) { index, ltItem ->
                        run {
                            WearTAKSelectableChip(
                                selected = index == selectedRadioIndex,
                                onClick = {
                                    selectedRadioIndex = index;
                                    settingsVm.storeBloodhoundProximityIntensity(
                                        when (selectedRadioIndex) {
                                            0 -> BLOODHOUND_PROXIMITY_INTENSITY_BASIC
                                            1 -> BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED
                                            2 -> BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT
                                            else -> BLOODHOUND_PROXIMITY_INTENSITY_BASIC
                                        }
                                    )
                                },
                                title = ltItem,
                                secondaryTitle = when (index){
                                    0 -> stringResource(R.string.bloodhound_proximity_intensity_basic_desc)
                                    1 -> stringResource(R.string.bloodhound_proximity_intensity_escalated_desc)
                                    2 -> stringResource(R.string.bloodhound_proximity_intensity_persistent_desc)
                                    else -> stringResource(R.string.error)
                                },
                                combined = true,
                                list = bloodhoundList
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(15.dp))
                    }
                },
                navController,
            )

        }
        composable(COMPANION_DETAILS_ROUTE) {
            val diagnosticsEnabled by settingsVm.savedBondedLocationDiagnosticsEnabled.collectAsState()
            CompanionDetailsPage(
                companionPluginStatus(enableCompanionAppService),
                navController,
                diagnosticsEnabled,
                settingsVm::storeBondedLocationDiagnosticsEnabled
            )
        }

        composable("plugins") {
            val pluginToggles by pluginVm.connectivityToggles.collectAsStateWithLifecycle()
            val companionStatus = companionPluginStatus(enableCompanionAppService)
            val enableDataSync by settingsVm.savedEnableDataSync.collectAsState()
            val pluginMenuScope = rememberCoroutineScope()
            val menuEntries = (
                listOf(
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.COMMANDS, stringResource(R.string.chat_commands)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.COMPANION, stringResource(R.string.companion_app)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.CRM, "CRM"),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.DART, stringResource(R.string.gunshot_detection)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.DATA_SYNC, stringResource(R.string.data_sync)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.DITTO, stringResource(R.string.ditto)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.EVERYWHERE_COMMS, stringResource(R.string.everywhere_comms)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.FALCON, stringResource(R.string.uas_detection)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.SENSOR_MANAGER, stringResource(R.string.sensor_manager)),
                    PluginMenuEntry.BuiltIn(BuiltInPlugin.SOMEWEAR_LABS, stringResource(R.string.somewear_labs)),
                ) + pluginList.installedPluginMenuEntries()
            ).alphabetizedPluginEntries()
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        pluginVm.refresh()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }
            var showPopup = remember { mutableStateOf(false) }
            val shouldShowDialog = remember { mutableStateOf(false) }
            val isConfirmed = remember { mutableStateOf(false) }
            val successBoolean = remember { mutableStateOf(true) }

            var pluginitem =  remember { mutableStateOf(PluginUI(
                icon = null,
                title = "",
                desc = "",
                loadingStatus = PluginLoadingStatus.NOT_LOADED,
                pluginId = "",
                versionName = "",
                packageName = ""
            )) }
            WearTAKPageWithBackArrow(
                stringResource(R.string.plugins),
                columnContent = {
                    itemsIndexed(items = menuEntries, key = { _, entry -> entry.key }) { _, entry ->
                        when (entry) {
                            is PluginMenuEntry.BuiltIn -> when (entry.kind) {
                                BuiltInPlugin.COMPANION -> {
                                    CompanionPluginChip(
                                        enabled = enableCompanionAppService,
                                        status = companionStatus,
                                        onToggle = { settingsVm.storeEnableCompanionAppService(it) },
                                        onDetails = { navController.navigate(COMPANION_DETAILS_ROUTE) }
                                    )
                                }
                                BuiltInPlugin.DATA_SYNC -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(if (enableDataSync) R.string.enabled else R.string.data_sync_paused),
                                        checked = enableDataSync,
                                        onCheckedChange = settingsVm::storeEnableDataSync,
                                        onClick = { navController.navigate("data_sync") }
                                    )
                                }
                                BuiltInPlugin.COMMANDS -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(R.string.unfunded_request),
                                        checked = enableChatCommands,
                                        onCheckedChange = { settingsVm.storeEnableChatCommands(it) },
                                        onClick = { navController.navigate("chat_commands") }
                                    )
                                }
                                BuiltInPlugin.DITTO -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(dittoPdlSubtitle(dittoPdlSource, dittoCustomPdl.isNotBlank())),
                                        checked = enableDittoService,
                                        onCheckedChange = { settingsVm.storeEnableDittoService(it) },
                                        onClick = { navController.navigate("ditto_pdl") }
                                    )
                                }
                                BuiltInPlugin.SENSOR_MANAGER -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(R.string.unfunded_request),
                                        checked = enableSensorManager,
                                        onCheckedChange = { settingsVm.storeEnableSensorManager(it) },
                                        onClick = { navController.navigate("sensor_manager") }
                                    )
                                }
                                BuiltInPlugin.DART -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(R.string.unfunded_request),
                                        checked = enableGunshotDetection,
                                        onCheckedChange = { settingsVm.storeEnableOnnxGunshotService(it) },
                                        onClick = { navController.navigate("gunshot_detection") }
                                    )
                                }
                                BuiltInPlugin.FALCON -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(R.string.unfunded_request),
                                        checked = enableUasDetection,
                                        onCheckedChange = { settingsVm.storeEnableOnnxUasService(it) },
                                        onClick = { navController.navigate("uav_detection") }
                                    )
                                }
                                BuiltInPlugin.CRM -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(R.string.unfunded_request),
                                        checked = enableCrm,
                                        onCheckedChange = { settingsVm.storeEnableCrmService(it) },
                                        onClick = { navController.navigate("crm_thresholds") }
                                    )
                                }
                                BuiltInPlugin.EVERYWHERE_COMMS, BuiltInPlugin.SOMEWEAR_LABS -> {
                                    BuiltInPluginToggleChip(
                                        kind = entry.kind,
                                        phoneManagedSettings = phoneManagedSettings,
                                        title = entry.title,
                                        label = stringResource(R.string.unfunded_request),
                                        checked = false,
                                        onCheckedChange = {},
                                        onClick = {},
                                    )
                                }
                            }
                            is PluginMenuEntry.Installed -> {
                                val item = entry.plugin
                                WearTAKSwipeToRevealChip(
                                    onPrimaryActionClick = {
                                        pluginitem.value = item
                                        shouldShowDialog.value = true
                                    },
                                    onSecondaryActionClick = {
                                        pluginitem.value = item
                                        showPopup.value = true
                                    },
                                    chipType = { reveal ->
                                        WearTAKSplitToggleChip(
                                            appIcon = {
                                                item.icon.let {
                                                    AndroidView(
                                                        modifier = Modifier
                                                            .size(25.dp)
                                                            .padding(end = 3.dp),
                                                        factory = { context ->
                                                            ImageView(context).apply {
                                                                setImageDrawable(it)
                                                            }
                                                        }
                                                    )
                                                }
                                            },
                                            title = item.title,
                                            label = item.menuLabel,
                                            modifier = Modifier.semantics {
                                                if (item.menuLabel == null && item.desc.isNotBlank()) {
                                                    contentDescription = item.desc
                                                }
                                            },
                                            checked = item.isLoaded,
                                            onCheckedChange = {
                                                when (it) {
                                                    true -> pluginVm.loadPlugin(item.pluginId)
                                                    false -> pluginVm.unloadPlugin(item.pluginId)
                                                }
                                            },
                                            onClick = {
                                                pluginMenuScope.launch {
                                                    if (pluginVm.supportsHostedMainUi(item.pluginId)) {
                                                        navController.navigate(hostedPluginMainUiRoute(item.pluginId))
                                                    } else if (!PluginActivityLauncher.launch(context, item.packageName)) {
                                                        reveal.first.launch {
                                                            reveal.second.animateTo(targetValue = RevealValue.RightRevealing)
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                        pluginToggles[item.packageName]?.takeIf { item.isLoaded }?.let { toggle ->
                                            NetworkConnectivityChip(
                                                row = NetworkConnectivityRow(
                                                    key = "plugin:${item.packageName}",
                                                    title = item.title,
                                                    subtitle = stringResource(R.string.network_plugin_forwarding),
                                                    checked = toggle.enabled,
                                                    toggle = { pluginVm.setConnectivityEnabled(item.packageName, toggle, it) },
                                                    pluginIcon = item.icon
                                                ),
                                                networkPreferencesLocked = networkPreferencesLocked,
                                                phoneManagedSettings = phoneManagedSettings,
                                                navigate = {}, showPhoneManagedNotice = showPhoneManagedNotice,
                                                showNetworkLockedNotice = {}
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                navController,
            )

            WearTAKPluginPopupBox(
                showPopup,
                pluginitem.value.title,
                description = pluginitem.value.desc,
                versionNumber = pluginitem.value.versionName,
                compatibility = pluginitem.value.compatibility,
                loadingStatus = pluginitem.value.loadingStatus,
                errorMessage = pluginitem.value.errorMessage
            )

            WearTAKAlertDialog(
                shouldShowDialog = shouldShowDialog,
                modifier = Modifier.fillMaxSize(),
                icon = {
                    Icon(
                        Icons.Default.Warning,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.LightGray
                    )
                },
                text = {
                    Text(
                        "Remove ${pluginitem.value.title}?",
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            shouldShowDialog.value = false
                            isConfirmed.value = true
                        },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Green,
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
                            text = stringResource(R.string.dismiss),
                        )
                    }
                }
            )

            if (isConfirmed.value) {
                Log.d(TAG, "plugin removed isSuccess")
                LaunchedEffect(Unit) {
                    delay(1000) // Delay for 5 seconds
                    isConfirmed.value = false // Dismiss the dialog
                    successBoolean.value = false // Dismiss the dialog
                    Log.d(TAG, "plugin removed called")
                    pluginVm.removePlugin(pluginitem.value.pluginId)
//                    pluginVm.uninstallPlugin(pluginitem.value.pluginId)
                }
                //TODO: Commenting this out until successboolean is acgtually connected
//                WearTAKSuccessFailDialog(
//                    isConfirmed,
//                    successBoolean
//                )
            }
        }
        composable(
            route = HOSTED_PLUGIN_MAIN_UI_ROUTE_PATTERN,
            arguments = listOf(navArgument("pluginId") { type = NavType.StringType }),
        ) { entry ->
            val pluginId = entry.arguments?.getString("pluginId").orEmpty()
            HostedPluginMainUiPage(
                pluginId = pluginId,
                plugins = pluginList,
                pluginVm = pluginVm,
                navController = navController,
            )
        }
        composable("data_sync") {
            val serverMissions by dataRepository.takMissions.collectAsState()
            val enableDataSync by settingsVm.savedEnableDataSync.collectAsState()
            LaunchedEffect(Unit) {
                TAKConnectService.refreshMissionListsOnScreenEntry()
            }

            WearTAKPageWithBackArrow(
                stringResource(R.string.data_sync),
                columnContent = {
                    if (!enableDataSync) {
                        item { Text(stringResource(R.string.data_sync_paused)) }
                    }
                    if (serverMissions.isEmpty()) {
                        item {
                            Text("No TAK Server missions available")
                        }
                    }

                    serverMissions.forEach { server ->
                        item {
                            Text(
                                text = server.serverName,
                                modifier = Modifier.padding(8.dp)
                            )
                        }

                        if (server.missions.isEmpty()) {
                            item {
                                Text(
                                    text = "No missions found",
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        itemsIndexed(
                            items = server.missions,
                            key = { _, missionState -> "${server.serverId}:${missionState.mission.name}" }
                        ) { _, missionState ->
                            WearTAKSplitToggleChip(
                                title = missionState.mission.name,
                                label = if (missionState.subscribed) {
                                    "Subscribed"
                                } else {
                                    "Not subscribed"
                                },
                                checked = missionState.subscribed,
                                enabled = enableDataSync,
                                onCheckedChange = { subscribed ->
                                    if (subscribed) {
                                        TAKConnectService.subscribeToTakMission(
                                            serverId = server.serverId,
                                            missionName = missionState.mission.name
                                        )
                                    } else {
                                        TAKConnectService.unsubscribeFromTakMission(
                                            serverId = server.serverId,
                                            missionName = missionState.mission.name
                                        )
                                    }
                                },
                                onClick = {}
                            )
                        }
                    }
                },
                navController
            )
        }
        //Tool Settings ^^^

        //Admin Preferences vvv
        composable("admin_preferences") {
            if (!isDevMode) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else WearTAKPageWithBackArrow(
                stringResource(R.string.admin_preferences),
                columnContent = {
                    item {
                        WearTAKToggleChip(
                            checkedLive = networkPreferencesLocked,
                            onCheckedChange = { settingsVm.storeNetworkPreferencesLocked(it) },
                            title = stringResource(R.string.network_settings_lock),
                        )
                    }
                },
                navController,
            )
        }
        //Admin Preferences ^^^
        composable("gunshot_detection") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.gunshot_detection),
                columnContent = {
                    item {
                        WearTAKTitleChipWithState(
                            route = "gunshot_trigger_threshold",
                            title = stringResource(R.string.gunshot_trigger_threshold),
                            state = gunshotTriggerThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}%" }
                        )
                    }
                },
                navController,
            )
        }
        composable("gunshot_trigger_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = gunshotTriggerThreshold,
                label = stringResource(R.string.gunshot_trigger_threshold),
                maximum = 100,
                minimum = 0,
                context = context,
                confirmFunction = { settingsVm.storeOnnxGunshotTriggerThresholdPercent(it)}
            )
        }
        composable("uav_detection") {
            WearTAKPageWithBackArrow(
                stringResource(R.string.uas_detection),
                columnContent = {
                    item {
                        WearTAKTitleChipWithState(
                            route = "uav_trigger_threshold",
                            title = stringResource(R.string.uas_trigger_threshold),
                            state = uasTriggerThreshold,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}%" }
                        )
                    }
                },
                navController,
            )
        }
        composable("uav_trigger_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = uasTriggerThreshold,
                label = stringResource(R.string.uas_trigger_threshold),
                maximum = 100,
                minimum = 0,
                context = context,
                confirmFunction = { settingsVm.storeOnnxUasTriggerThresholdPercent(it)}
            )
        }

        composable("crm_thresholds") {
            WearTAKPageWithBackArrow(
                "CRM Thresholds",
                columnContent = {
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { context.startActivity(Intent(context, CrmReserveActivity::class.java)) },
                            label = { Text(stringResource(R.string.crm_reserve)) })
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "poor_max_crm_threshold",
                            title = "Poor Maximum",
                            state = crmThresholdValues.poorMax,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}%" }
                        )
                    }
                    item {
                        WearTAKTitleChipWithState(
                            route = "moderate_max_crm_threshold",
                            title = "Moderate Maximum",
                            state = crmThresholdValues.moderateMax,
                            navController = navController,
                            formatState = { value -> "${value ?: 0}%" }
                        )
                    }
                },
                navController,
            )
        }
        composable("poor_max_crm_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = crmThresholdValues.poorMax,
                label = "Poor Maximum",
                maximum = 100,
                minimum = 0,
                context = context,
                confirmFunction = { settingsVm.storeCrmThresholdRanges(it, crmThresholdValues.moderateMax,)}
            )
        }
        composable("moderate_max_crm_threshold"){
            WearTAKIntEntryPage(
                navController = navController,
                item = crmThresholdValues.moderateMax,
                label = "Moderate Maximum",
                maximum = 100,
                minimum = 0,
                context = context,
                confirmFunction = { settingsVm.storeCrmThresholdRanges(crmThresholdValues.poorMax, it)}
            )
        }

        composable("debug_tools") {
            DebugScreen(navController)
        }
    }
}

@Composable
fun bloodhoundIntensityText1(intensity: String?): String {
    return when (intensity) {
        BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED -> stringResource(R.string.bloodhound_proximity_intensity_escalated)
        BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT -> stringResource(R.string.bloodhound_proximity_intensity_persistent)
        else -> stringResource(R.string.bloodhound_proximity_intensity_basic)
    }
}

@Composable
fun WearTAKAlertDialog(
    shouldShowDialog: MutableState<Boolean>,
    shouldShowLoadingDialog: MutableState<Boolean> = mutableStateOf(false),
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
    title: @Composable () -> Unit = {},
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    ){
    if (shouldShowDialog.value) {
        shouldShowLoadingDialog.value = false
        AlertDialog(
            containerColor = colors.background,
            modifier = modifier,
            onDismissRequest = {
                shouldShowDialog.value = false
            },
            icon = { icon() },
            title = { title() },
            text = { text() },
            confirmButton = { confirmButton() },
            dismissButton = { dismissButton() }
        )
    }
}

@Composable
fun WearTAKSuccessFailDialog(
    shouldShowSuccessDialog: MutableState<Boolean>,
    successBoolean : MutableState<Boolean>
) {
    Dialog(onDismissRequest = { shouldShowSuccessDialog.value = false }) {
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
//                    successImage()
                    Icon(
                        Icons.Filled.Check,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Green
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.success),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
//                    additionalText()
                } else {
                    Icon(
                        Icons.Rounded.Clear,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Red
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Failed",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun WearTAKLoadingDialog(
    shouldShowDialog: MutableState<Boolean>,
    secondaryText: String
) {
    if (shouldShowDialog.value) {
        Dialog(
            onDismissRequest = { shouldShowDialog.value = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {

                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = secondaryText,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// Used for higher-level settings that direct you somewhere else, typically somewhere with multiple settings options
@Composable
fun WearTAKTitleChip(
    icon: @Composable () -> Unit = {}, // Optional icon
    title: String, // Text on the chip
    route: String, //where chip will direct to in navhost
    onClick: () -> Unit = {}, // Optional additional behavior when clicked
    navController: NavController, //Navcontroller passed from navhost
    enabled: Boolean = true // When false the chip is shown greyed out and cannot be opened
){
    Chip(
        onClick = {
            onClick()
            navController.navigate(route)
        },
        enabled = enabled,
        label = { Text(text = title) },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray,
            contentColor = Color.White,
            secondaryContentColor = Color.LightGray
        ),
        icon = { icon() },
        modifier = Modifier
            .fillMaxWidth()
//            .padding(bottom = 7.dp)
    )
}

@Composable
fun WearTAKLockedTitleChip(
    title: String,
    lockedState: String,
    onClick: () -> Unit
) {
    Chip(
        onClick = onClick,
        enabled = true,
        label = {
            Text(
                text = title,
                color = Color.Gray
            )
        },
        secondaryLabel = {
            Text(
                text = lockedState,
                color = Color.Gray
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray.copy(alpha = 0.6f),
            contentColor = Color.Gray,
            secondaryContentColor = Color.Gray
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// Used for directing to a settings page, usually with a directly editable value inside. State used directly reflects this value
@Composable
fun <T> WearTAKTitleChipWithState(
    route: String, //where chip will direct to in navhost
    title: String, // Upper text on the chip
    state: (T), // Lower text on the chip, can be any value
    icon: @Composable () -> Unit = {}, //Optional icon
    onClick: () -> Unit = {}, // Optional additional behavior when clicked
    navController: NavController, //Navcontroller passed from navhost
    formatState: @Composable (T?) -> String = { it?.toString() ?: "" } //Optional formatting of state text, necessary for non-string state values
) {

    Chip(
        onClick = {
            onClick()
            navController.navigate(route) },
        enabled = true,
        label = { Text(text = title) },
        secondaryLabel = {
            Text(
                text = formatState(state),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        icon = { icon() },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray,
            contentColor = Color.White,
            secondaryContentColor = Color.LightGray
        ),
        modifier = Modifier
            .fillMaxWidth()
//            .padding(bottom = 7.dp)
    )
}

@Composable
internal fun BuiltInPluginBrandIcon(plugin: BuiltInPlugin) {
    plugin.brandIconRes?.let { resource ->
        Image(
            painter = painterResource(resource),
            contentDescription = null,
            modifier = Modifier.padding(end = 4.dp).size(24.dp),
        )
    }
}

//Used for services and settings with a boolean state of ON/OFF
@Composable
fun WearTAKToggleChip(
    checkedLive: Boolean?, //Boolean state UI should reference to show enabled/disabled
    onCheckedChange: (Boolean) -> Unit, //What function/behavior should be called when toggle changes
    appIcon: @Composable () -> Unit = {}, //Optional icon
    title: String, //Upper text of chip
    description: String = "", //Lower text of chip; omitted when empty
    enabled: Boolean = true,
    statusDescription: String = "", //Accessibility-only status; announced after On/Off when it adds information beyond the visible description
) {
    val onLabel = stringResource(R.string.toggle_state_on)
    val offLabel = stringResource(R.string.toggle_state_off)
    val announced = toggleStateAnnouncement(checkedLive == true, onLabel, offLabel, statusDescription, description)
    ToggleChip(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (announced != null) Modifier.semantics { stateDescription = announced } else Modifier
            ),
//            .padding(bottom = 7.dp),
        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = if (description.isNotEmpty()) {
            { Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else null,
        appIcon = {appIcon()},
        checked = checkedLive == true,
        colors =
            ToggleChipDefaults.toggleChipColors(
                uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
            ),
        toggleControl = {
            Switch(
                checked = checkedLive == true,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

//Radio button chip, typically rendered within a list that references a state change to know which chip to have selected.  Can display either as separate chips or one block of radio chips
@Composable
fun <T> WearTAKSelectableChip(
    selected: Boolean, //Is this chip currently selected
    onClick: () -> Unit, //What function/behavior happens when chip is selected
    title: String, //Text on chip
    secondaryTitle: String = "",
    combined: Boolean = false, //When true, chips will combine to look like one long list rather than seperate chips
    list: List<T> //What list the chip should reference, specifically for when chips are combined,
){
    SelectableChip(
        modifier = Modifier
            .fillMaxWidth(),
        selected = selected,
        onClick = { onClick() },
        label = {
            // The primary label should have a maximum 3 lines of text
            Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        secondaryLabel = {
            // The primary label should have a maximum 3 lines of text
            if (secondaryTitle.isNotEmpty()) Text(secondaryTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        enabled = true,
        shape =
            if (combined)
                when (title) {
                    list.first() -> RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
                    list.last() -> RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
                    else -> RoundedCornerShape(0.dp)
                } else MaterialTheme.shapes.large
    )
}
// Used for items that combine an enable toggle with a separate configuration action.
@Composable
fun WearTAKSplitToggleChip(
    appIcon: @Composable () -> Unit = {}, //Optional icon
    title: String, //Upper text of chip
    label: String?, //Lower text of chip
    checked: Boolean, //Whether toggle is enabled or not
    onCheckedChange: (Boolean) -> Unit, //What function/behavior happens when chip is toggled
    onClick: () -> Unit, //What happens when selectable side of chip is clicked
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    ){
    SplitToggleChip(
        label = {
            appIcon()
            Text(title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier
                .align(Alignment.CenterVertically))
        },
        secondaryLabel = label?.let { label -> @Composable {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } },
        checked = checked,
        toggleControl = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.size(25.dp)
            )
        },
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        onClick = { onClick() } ,
        modifier = modifier
            .fillMaxWidth()
//            .padding(bottom = 3.dp)
    )
}
//Used when an item in a list needs to be individually edited.
// Typically the primary option is to delete it, and secondary is to edit the item. The chip that expands can be any chip type (toggle, radio, etc)
@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun WearTAKSwipeToRevealChip (
    onPrimaryActionClick: () -> Unit, //What happens when 3 dots are selected
    onSecondaryActionClick: () -> Unit, //What happens when trash can is selected
    chipType: @Composable (Pair<CoroutineScope, RevealState>) -> Unit = {}, //What kind of chip displays (also requires its own chip composable values)
    revealState: RevealState = rememberRevealState(), //Used for revealing the chip
    scope: CoroutineScope = rememberCoroutineScope() //Used for revealing the chip
){
    SwipeToRevealChip(
        revealState = revealState,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                // Use custom actions to make the primary and secondary actions accessible
                customActions =
                    listOf(
                        CustomAccessibilityAction("Delete TAK Server") {
                            /* Add the primary action click handler here */
                            true
                        },
                        CustomAccessibilityAction("Edit TAK Server") {
                            /* Add the secondary click handler here */
                            true
                        }
                    )
            },
        primaryAction = {
            SwipeToRevealPrimaryAction(
                revealState = revealState,
                icon = { Icon(SwipeToRevealDefaults.Delete, stringResource(R.string.delete)) },
                label = { Text(stringResource(R.string.delete)) },
                onClick = { onPrimaryActionClick(); scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealed)}}
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
        undoPrimaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for primary action */ }
            )
        },
        undoSecondaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for secondary action */ }
            )
        },
        onFullSwipe = {onPrimaryActionClick() }
    ) {
        chipType(scope to revealState)
    }
}
// Used when multiple items in a list can be selected at the same time.  Currently only used for allergy selection
@Composable
fun <T> WearTAKMultipleChoiceCheckboxChip(
    selectedIndices: SnapshotStateList<Int>, //Tells chip if it is a part of the selected chips
    index: Int, //Which multi-select chip is being selected in group
    dataItem: String, //Text that displays in chip
    checked: Boolean, //Whether this chip is selected
    list: List<T>, //What list the chip should reference, specifically for when chips are combined
    onClick: () -> Unit //Optional function/behavior that triggers when selected
){
    ToggleChip(
        modifier = Modifier.fillMaxWidth(),
        checked = checked,
        onCheckedChange = { checked ->
            if (checked) selectedIndices += index
            else selectedIndices -= index
            onClick()
        },
        toggleControl = { Checkbox(checked = checked, enabled = true,  onCheckedChange = null) },
        label = {
            // The primary label should have a maximum 3 lines of text
            Text(dataItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        enabled = true,
        shape =
            if (dataItem == list.first())
                RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
            else if (dataItem == list.last())
                RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
            else
                RoundedCornerShape(0.dp)
    )
}

//Used for any textfield used in the app
@Composable
fun WearTAKOutlinedTextField(
    string: String, //Textfield text
    label: String, //What the textfield title is
    keyboardType: KeyboardType = KeyboardType.Unspecified, //Optional keyboard type, necessary for non-string text
    keyboardActions: KeyboardActions = KeyboardActions.Default, //Optional action when textfield is confirmed, for example if you'd like to run a Toast
    autoFocus: Boolean = false
): TextFieldValue = PlatformTextField(string, label, keyboardType, keyboardActions, autoFocus)

//Used when the user should have the ability to cancel whatever they entered
@Composable
fun WearTAKConfirmCancelButtons(
    onClickCancel: () -> Unit, //Action that happens when clicking X button
    onClickConfirm: () -> Unit //Action that happens when clicking check button
) {
    Box(
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            IconButton(
                onClick = onClickCancel,
                modifier = Modifier
                    .size(50.dp)
                    .border(1.dp, color = Color.Black, shape = CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Red,
                    containerColor = Color.DarkGray,
                ),
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close icon")

            }
            Spacer(modifier = Modifier.width(20.dp))
            IconButton(
                onClick = onClickConfirm,
                modifier = Modifier
                    .size(50.dp)
                    .border(1.dp, color = Color.Black, shape = CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Black,
                    containerColor = colors.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Confirm icon",
                    tint = Color.Black
                )

            }
        }
    }
}

//Used to return to previous page
@Composable
fun WearTAKBackButton(onClick: () -> Unit /* What happens when pressed */) {
    Button(
        onClick = { onClick() },
        modifier = Modifier.size(height = 48.dp, width = 80.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Gray,
            contentColor = Color.Red
        ),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Go back to Main Settings Screen",
        )
    }
}
@Composable
fun DittoPdlSelectionPage(
    navController: NavController,
    selectedSource: DittoPdlSource
) {
    val scope = rememberCoroutineScope()
    val showPdlHelp = remember { mutableStateOf(false) }
    val savedCustomPdl by dataRepository.getDittoCustomPdl.collectAsState(initial = "")
    val communityTitle = stringResource(R.string.community_pdl)
    val customTitle = stringResource(R.string.custom_pdl)
    val pdlOptions = listOf(communityTitle, customTitle)

    WearTAKPageWithBackArrow(
        title = stringResource(R.string.ditto_identity),
        columnContent = {
            item {
                WearTAKSelectableChip(
                    selected = selectedSource == DittoPdlSource.COMMUNITY,
                    onClick = {
                        scope.launch {
                            dataRepository.storeDittoPdlSource(
                                DittoPdlSource.COMMUNITY
                            )
                        }
                    },
                    title = communityTitle,
                    secondaryTitle = stringResource(R.string.community_pdl),
                    combined = true,
                    list = pdlOptions
                )
            }

            item {
                WearTAKSelectableChip(
                    selected = selectedSource == DittoPdlSource.CUSTOM,
                    onClick = {
                        navController.navigate("ditto_custom_pdl")
                    },
                    title = customTitle,
                    secondaryTitle = savedCustomPdl.ifBlank {
                        stringResource(R.string.no_custom_pdl_saved)
                    },
                    combined = true,
                    list = pdlOptions
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showPdlHelp.value = true
                    },
                    label = {
                        Text(stringResource(R.string.pdl_help))
                    },
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.pdl_help_summary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.pdl_help),
                            modifier = Modifier.size(18.dp),
                            tint = Color.LightGray
                        )
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    )
                )
            }
        },
        navController = navController
    )

    DittoPdlHelpDialog(showPdlHelp)
}

@Composable
fun DittoPdlHelpDialog(
    shouldShowDialog: MutableState<Boolean>
) {
    if (shouldShowDialog.value) {
        Dialog(
            onDismissRequest = {
                shouldShowDialog.value = false
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colors.background
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(22.dp),
                        tint = Color.LightGray
                    )

                    Text(
                        text = stringResource(R.string.pdl_help_instructions),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 32.dp, bottom = 56.dp)
                            .verticalScroll(rememberScrollState()),
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )

                    Button(
                        onClick = {
                            shouldShowDialog.value = false
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .width(112.dp)
                            .height(40.dp),
                        contentPadding = PaddingValues(
                            horizontal = 12.dp,
                            vertical = 0.dp
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.dismiss),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DittoCustomPdlEntryPage(
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedCustomPdl by dataRepository.getDittoCustomPdl.collectAsState(
        initial = ""
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val textField = WearTAKOutlinedTextField(
            string = savedCustomPdl,
            label = stringResource(R.string.custom_pdl)
        )

        Row(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            WearTAKConfirmCancelButtons(
                onClickCancel = {
                    navController.popBackStack()
                },
                onClickConfirm = {
                    val value = textField.text.trim()

                    val license = try {
                        DittoCommunityLicenseHelper.parse(value)
                    } catch (error: Exception) {
                        Log.e(TAG, "Custom PDL is invalid", error)

                        Toast.makeText(
                            context,
                            "Invalid PDL",
                            Toast.LENGTH_LONG
                        ).show()

                        return@WearTAKConfirmCancelButtons
                    }

                    if (license.isExpired) {
                        Toast.makeText(
                            context,
                            "Expired PDL",
                            Toast.LENGTH_LONG
                        ).show()

                        return@WearTAKConfirmCancelButtons
                    }

                    scope.launch {
                        dataRepository.storeDittoCustomPdl(value)
                        dataRepository.storeDittoPdlSource(
                            DittoPdlSource.CUSTOM
                        )

                        Toast.makeText(
                            context,
                            "PDL saved successfully",
                            Toast.LENGTH_LONG
                        ).show()

                        navController.popBackStack(
                            route = "ditto_pdl",
                            inclusive = true
                        )
                    }
                }
            )
        }
    }
}

//Used wherever a string value needs to be edited
@Composable
fun WearTAKStringEntryPage(
    navController: NavController, //Navcontroller passed from navhost
    item: String, //What value is being edited by textfield
    label: String, //Title of the textfield
    confirmFunction: (String) -> Unit, //What happens when string is confirmed,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    validate: (String) -> String? = { null },
    confirmOnImeDone: Boolean = false,
    autoFocus: Boolean = false
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        var textField = TextFieldValue()

        fun confirm() {
            focusManager.clearFocus()
            val error = validate(textField.text)
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                return
            }
            confirmFunction(textField.text)
            navController.popBackStack()
        }

        textField = WearTAKOutlinedTextField(
            item,
            label,
            keyboardActions = if (confirmOnImeDone) KeyboardActions(onDone = { confirm() }) else keyboardActions,
            autoFocus = autoFocus
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            WearTAKConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "$label Settings NOT saved")
                    navController.popBackStack()
                },
                onClickConfirm = { confirm() })
        }
    }
}

//Used wherever an int value needs to be edited
@Composable
fun WearTAKIntEntryPage(
    navController: NavController, //Navcontroller passed from navhost
    item: Int, //What value is being edited by textfield
    label: String, //Title of the textfield
    maximum: Int = 0, //Maximum value a user can enter, toast appears when exceeded
    minimum: Int = 0, //Minimum value a user can enter, toast appears when under
    context: Context, //Passed context for toasts
    confirmFunction: (Int) -> Unit //What happens when string is confirmed
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        var textField = TextFieldValue()
        val focusManager = LocalFocusManager.current

        fun key() = run {
            if (textField.text.isEmpty()) {
                Toast.makeText(
                    mainActivity,
                    context.getString(R.string.please_enter_a_value),
                    Toast.LENGTH_LONG
                ).show()
            } else if (textField.text.toInt() > maximum) {
                Toast.makeText(
                    mainActivity,
                    context.getString(R.string.value_exceeds_maximum_please_re_enter),
                    Toast.LENGTH_LONG
                ).show()
            } else if (textField.text.toInt() < minimum) {
                Toast.makeText(
                    mainActivity,
                    " Value is less than minimum, please re-enter", Toast.LENGTH_LONG
                ).show()
            } else {
                focusManager.clearFocus()
            }
        }
        textField = WearTAKOutlinedTextField(
            item.toString(),
            label,
                KeyboardType.Number ,
                KeyboardActions(
                    onDone = {
                        key()
                    }
                )
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            WearTAKConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "$label Settings NOT saved")
                    navController.popBackStack()
                },
                onClickConfirm = {
                    if (textField.text.isEmpty()) {
                        Toast.makeText(
                            mainActivity,
                            context.getString(R.string.please_enter_a_value),
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (textField.text.isDigitsOnly() ){
                        if (textField.text.toInt() > maximum) {
                            Toast.makeText(
                                mainActivity,
                                context.getString(R.string.value_exceeds_maximum_please_re_enter),
                                Toast.LENGTH_LONG
                            ).show()
                        } else if (textField.text.toInt() < minimum) {
                            Toast.makeText(
                                mainActivity,
                                " Value is less than minimum, please re-enter", Toast.LENGTH_LONG
                            ).show()
                        } else {
                            confirmFunction(textField.text.toInt())
                            navController.popBackStack()
                        }
                    } else {
                        Toast.makeText(
                            mainActivity,
                            " Value is not a number", Toast.LENGTH_LONG
                        ).show()
                    }
                })
        }
    }
}

@Composable
fun WearTAKPicker(
    title: String,
    navController: NavController,
    columnOneState: Int?,
    columnTwoState: Int?,
    columnOneAmount: Int,
    columnTwoAmount: Int,
    columnOneText: String = "",
    columnTwoText: String = "",
    onClickConfirmOne: (PickerState) -> Unit,
    onClickConfirmTwo: (PickerState) -> Unit,
){
    var selectedColumn by remember { mutableStateOf(0) }
    val textStyle = MaterialTheme.typography.display1

    val firstColumnState =
        rememberPickerState(initialNumberOfOptions = columnOneAmount, initiallySelectedOption = columnOneState!!.toInt()-1)
    val firstColumnContentDescription by remember {
        derivedStateOf { "${firstColumnState.selectedOption + 1} first column" }
    }
    val secondColumnState =
        rememberPickerState(initialNumberOfOptions = columnTwoAmount, initiallySelectedOption = columnTwoState!!.toInt())
    val secondColumnContentDescription by remember {
        derivedStateOf { "${secondColumnState.selectedOption} second column" }
    }
    @Composable
    fun Option(column: Int, text: String) =
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                style = textStyle,
                color =
                    if (selectedColumn == column) colors.secondary
                    else colors.onBackground,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .wrapContentSize()
                        .pointerInteropFilter {
                            if (it.action == MotionEvent.ACTION_DOWN) selectedColumn =
                                column
                            true
                        },
            )
        }
    WearTAKPageWithConfirmCancel(
        title = title,
        columnContent = {
            item{
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Picker(
                        readOnly = selectedColumn != 0,
                        state = firstColumnState,
                        modifier = Modifier.size(64.dp, 100.dp),
                        contentDescription = firstColumnContentDescription,
                        option = { columnOne: Int -> Option(0, "%2d".format(columnOne + 1)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = columnOneText, style = textStyle, color = colors.onBackground)
                    Spacer(Modifier.width(8.dp))
                    Picker(
                        readOnly = selectedColumn != 1,
                        state = secondColumnState,
                        modifier = Modifier.size(64.dp, 100.dp),
                        contentDescription = secondColumnContentDescription,
                        option = { columnTwo: Int -> Option(1, "%02d".format(columnTwo)) },
                    )
                    Text(text = columnTwoText, style = textStyle, color = colors.onBackground)
                }
            }
        },
        navController = navController,
        onClickConfirm = {
            onClickConfirmOne(firstColumnState)
            onClickConfirmTwo(secondColumnState)
            navController.popBackStack()
        },
        onClickCancel = {
            navController.popBackStack()
        },
    )
}
@Composable
fun WearTakScreenDimensions(
    columnContent: ScalingLazyListScope.(ScalingLazyListState) -> Unit,
    rowContent: @Composable () -> Unit,
    text: String,
    titleAction: (@Composable () -> Unit)? = null,
) {
    WearTAKCIVTheme {
        val listState = rememberScalingLazyListState()
        Scaffold(
            positionIndicator = {
                PositionIndicator(scalingLazyListState = listState)
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
//                    .padding(bottom = 16.dp),
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp)
//                        .fillMaxHeight(.7f)
                        .align(Alignment.TopCenter),

                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        if (titleAction == null) {
                            Text(
                                text = text,
                                fontSize = if (text.length!! < 20) 17.sp else 15.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp)
                                    .padding(bottom = 8.dp)
                            )
                        } else {
                            // Title and action share one line; the title ellipsizes rather than wrapping.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp)
                                    .padding(bottom = 8.dp)
                            ) {
                                Text(
                                    text = text,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                titleAction()
                            }
                        }
                    }
                   columnContent(listState)
                }
                Row(modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)) {
                    rowContent()
                }
            }
        }
    }
}

// Watches certs/<address>.p12 (as side-loaded over adb) while the user types the address.
@Composable
fun rememberSideloadedCertStatus(address: String): SideloadedClientCert.Status {
    val context = LocalContext.current
    val status by produceState<SideloadedClientCert.Status>(SideloadedClientCert.Status.None, address) {
        value = withContext(Dispatchers.IO) {
            SideloadedClientCert.detect(
                context.getExternalFilesDir(null)?.let { java.io.File(it, "certs") },
                address
            )
        }
    }
    return status
}

@Composable
fun SideloadedCertNotice(status: SideloadedClientCert.Status) {
    val text = when (status) {
        is SideloadedClientCert.Status.Found ->
            stringResource(R.string.cert_on_watch, status.commonName)
        SideloadedClientCert.Status.Unreadable -> stringResource(R.string.cert_on_watch_unreadable)
        SideloadedClientCert.Status.None -> return
    }
    Text(
        text = text,
        textAlign = TextAlign.Center,
        fontSize = 12.sp,
        color = if (status is SideloadedClientCert.Status.Unreadable) colors.error else colors.onSurface,
        modifier = Modifier.padding(5.dp)
    )
}

//Used for any page that needs a title and back button, used for most screens
@Composable
fun WearTAKPageWithBackArrow(
    title: String, //Screen title
    columnContent: ScalingLazyListScope.(ScalingLazyListState) -> Unit, //Screen contents
    navController: NavController, //Navcontroller passed from navhost,
    exitActivity: Boolean = false,
    exitFunction: () -> Unit = {},
    titleAction: (@Composable () -> Unit)? = null,
) {
    WearTakScreenDimensions(
        columnContent = { columnContent(it) },
        rowContent = {
            WearTAKBackButton(
                onClick = {
                    if (exitActivity) {
                        exitFunction()
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        },
        title,
        titleAction
    )
}

// Compact circular "+" used beside a page title, e.g. to add a TAK server.
@Composable
fun WearTAKTitleAddButton(contentDescription: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colors.primary)
            .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

//Used for any page that needs a title and back button, used for most screens
@Composable
fun WearTAKPageWithConfirmCancel(
    title: String, //Screen title
    columnContent: ScalingLazyListScope.() -> Unit, //Screen contents
    navController: NavController, //Navcontroller passed from navhost
    onClickConfirm: () -> Unit, //Behavior when user confirms entry
    onClickCancel: () -> Unit = {}, //Optional Behavior when user cancels entry
    loadingDialogBehavior: @Composable () -> Unit = {}
) {
    WearTakScreenDimensions(
        columnContent = { columnContent() },
        rowContent = {
            WearTAKConfirmCancelButtons(
                onClickCancel = {
                    onClickCancel()
                    navController.popBackStack()
                },
                onClickConfirm = { onClickConfirm()}
            )
            loadingDialogBehavior()
        },
        title
    )
}

//Used for (single) selection screens
@Composable
fun <T> WearTAKSelectionPage(
    index: Int, //Which chip should be selected
    title: String, //Title of screen
    navController: NavController, //Navcontroller passed from navhost
    list: List<T>, //List used for populating selection chips
    confirmFunction: (T) -> Unit, //What happens when a chip is selected
){
    var selectedRadioIndex by remember { mutableStateOf(index) }

    WearTAKPageWithBackArrow(
        title,
        {
            itemsIndexed(list){ index, ltItem ->
                run {
                    WearTAKSelectableChip(
                        selected = index == selectedRadioIndex,
                        onClick = { selectedRadioIndex = index;
                            confirmFunction(list[selectedRadioIndex]) },
                        title = ltItem.toString(),
                        combined = true,
                        list = list
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(15.dp))
            }
        },
    navController,
    )
}

@Composable
fun WearTAKDoubleSelectionPage(
    navController: NavController,
    categoryIndex: Int,
    selectedCategoryAndRoleIndex: Pair<Int, Int>?,
    onRoleSelected: (Pair<Int, Int>) -> Unit,
    title: String,
    list: List<String>,
    confirmFunctionCategory: (Pair<Int, Int>) -> Unit,
) {
    WearTakScreenDimensions(
        columnContent = {
            itemsIndexed(list) { index, item ->
                val isSelected =
                    selectedCategoryAndRoleIndex?.first == categoryIndex &&
                            selectedCategoryAndRoleIndex.second == index

                WearTAKSelectableChip(
                    selected = isSelected,
                    onClick = {
                        onRoleSelected(categoryIndex to index)
                    },
                    title = item,
                    combined = true,
                    list = list
                )
            }

            item {
                Spacer(modifier = Modifier.height(15.dp))
            }
        },
        rowContent = {
            WearTAKConfirmCancelButtons(
                onClickCancel = { navController.popBackStack() },
                onClickConfirm = {
                    selectedCategoryAndRoleIndex?.let { selection ->
                        confirmFunctionCategory(selection)

                        navController.popBackStack()
                    }
                }
            )
        },
        text = title
    )
}

//Used for multi-selection pages, currently only works for string lists
@Composable
fun WearTAKMultipleSelectionPage(
    title: String, //Screen title
    navController: NavController, //Navcontroller passed from navhost
    repoList: List<String>, //List of stored selected values from data repo
    nameList: List<String>, //List of what selection chips should be named
    confirmFunction: (List<String>) -> Unit, //What happens when a chip is selected
){

    val initialIndices = remember(repoList) {
        repoList.mapNotNull { item ->
            nameList.indexOf(item)
                .takeIf { it >= 0 }
        }
    }

    val selectedIndices = remember {
        // start with whatever LiveData says
        mutableStateListOf<Int>().apply { addAll(initialIndices) }
    }

    LaunchedEffect(repoList) {
        selectedIndices.clear()
        selectedIndices.addAll(initialIndices)
    }
    WearTAKPageWithBackArrow(
        title,
        {
            itemsIndexed(nameList) { index, dataItem ->
                run {
                    var checked = index in selectedIndices
                    WearTAKMultipleChoiceCheckboxChip(
                        selectedIndices,
                        index,
                        dataItem,
                        checked,
                        nameList,
                        {
                            val selectedItems = selectedIndices.map { idx -> nameList[idx] }
                            confirmFunction(selectedItems.toList())
                            Log.d("DataStoreDebug", "${selectedItems.toList()}")
                        })
                }
            }
           item { Spacer(modifier = Modifier.height(15.dp)) }
        },
        navController,
    )
}

//For personal weartak use, not plugin architecture
@Composable
fun WearTAKDebugVersionText(
    wearTakInfo: DataRepository.WearTakInfo,
    devModeEnabled: Boolean,
    onDebugToggled: ((Boolean) -> Unit)? = null, // optional hook if you want to do extra stuff
    settingsVm: WearTAKSettingsViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = colors

    var versionTapCount by remember { mutableStateOf(0) }
    var lastVersionTapTime by remember { mutableStateOf(0L) }
    var currentToast by remember { mutableStateOf<Toast?>(null) }

    val tapsRequired = 8
    val timeoutMs = 1500L   // max gap between taps

    fun showTopToast(message: String) {
        // Cancel any old toast so they don't queue up
        currentToast?.cancel()

        val toast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 50)
        toast.show()
        currentToast = toast
    }

    Column(
        modifier = Modifier.padding(top = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            modifier = Modifier.clickable {
                val now = System.currentTimeMillis()

                if (now - lastVersionTapTime > timeoutMs) {
                    versionTapCount = 0
                }

                versionTapCount++
                lastVersionTapTime = now

                if (versionTapCount in 5 until tapsRequired) {
                    val remaining = tapsRequired - versionTapCount
                    val msg = if (remaining == 1) {
                        "1 more tap to toggle debug"
                    } else {
                        "$remaining more taps to toggle debug"
                    }
                    showTopToast(msg)
                }

                if (versionTapCount >= tapsRequired) {
                    versionTapCount = 0

                    coroutineScope.launch {
                        val newValue = !devModeEnabled

                        settingsVm.storeEnableWearTakDevMode(newValue)

                        Log.d(TAG, "~~~ DEBUG MODE ${if (newValue) "ENABLED" else "DISABLED"} ~~~")

                        withContext(Dispatchers.Main) {
                            val toastText = if (newValue) {
                                "Debug tools enabled"
                            } else {
                                "Debug tools disabled"
                            }
                            showTopToast(toastText)
                        }

                        onDebugToggled?.invoke(newValue)
                    }
                }
            },
            text = stringResource(
                R.string.version,
                wearTakInfo.versionNumber
            ),
            fontSize = 11.sp,
            color = if (devModeEnabled) Color.Red else colors.primary,
        )

        if (devModeEnabled) {
            Text(
                text = "Code ${wearTakInfo.versionCode}",
                fontSize = 9.sp,
                color = Color.Red,
            )

            Text(
                text = "${wearTakInfo.gitBranch}@${wearTakInfo.gitCommit}",
                fontSize = 9.sp,
                color = Color.Red,
                maxLines = 1,
            )

            Text(
                text = wearTakInfo.buildType,
                fontSize = 9.sp,
                color = Color.Red,
            )
        }
    }
}


// Custom Screens
@Composable
private fun WearTAKRegistrationDialogs(
    registration: TakServerRegistration.State,
    serverDetailsChanged: MutableState<Boolean>,
    navController: NavController,
) {
    val shouldShowLoadingDialog = remember(registration) { mutableStateOf(registration.isLoading) }
    val shouldShowConfirmDialog = remember(registration) { mutableStateOf(!registration.isLoading) }
    val isServerConnected = remember(registration) {
        mutableStateOf(registration.status == TakServerRegistration.Status.CONNECTED)
    }
    val isDuplicate = remember(registration) {
        mutableStateOf(registration.status == TakServerRegistration.Status.DUPLICATE)
    }
    val isNetworkConnected = remember(NetworkManager.isNetworkConnected) {
        mutableStateOf(NetworkManager.isNetworkConnected)
    }
    WearTAKLoadingDialog(shouldShowLoadingDialog, stringResource(R.string.registering_device_with_tak_server))
    WearTAKServerDialog(
        shouldShowDialog = shouldShowConfirmDialog,
        shouldShowLoadingDialog = shouldShowLoadingDialog,
        isNetworkConnected = isNetworkConnected,
        navController = navController,
        isServerConnected = isServerConnected,
        serverDetailsChanged = serverDetailsChanged,
        failure = registration.failure,
        onRetry = { TAKConnectService.retryServerRegistration() },
        isDuplicate = isDuplicate,
    )
}

@Composable
fun WearTAKServerDialog(
    shouldShowDialog: MutableState<Boolean>,
    shouldShowLoadingDialog: MutableState<Boolean>,
    isNetworkConnected: MutableState<Boolean>,
    navController: NavController,
    isServerConnected: MutableState<Boolean>,
    serverDetailsChanged: MutableState<Boolean>,
    isDuplicate: MutableState<Boolean>,
    failure: com.aitech.weartak_civ.data.takserver.TakConnectionFailure? = null,
    onRetry: () -> Unit
    ){
    WearTAKAlertDialog(
        shouldShowDialog = shouldShowDialog,
        shouldShowLoadingDialog = shouldShowLoadingDialog,
        modifier = Modifier.wrapContentHeight(),
        title ={
            if (isDuplicate.value) {
                Text(stringResource(R.string.duplicate_tak_server), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            } else if (isServerConnected.value) {
                Text(stringResource(R.string.registration_complete), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
            } else {
                Text(stringResource(R.string.registration_failed), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp))
            }
        },
        text = {
            if (!isDuplicate.value) {
                if (isServerConnected.value) {
                    Text(stringResource(R.string.tak_server_registration_succeeded), textAlign = TextAlign.Center)
                } else {
                    Text(failure?.displayMessage(LocalContext.current) ?: stringResource(
                        if (isNetworkConnected.value) R.string.registration_failed
                        else R.string.network_disconnected_try_later),
                        textAlign = TextAlign.Center, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Log.d(TAG, "ServerRegistrationDialog confirmButton: isNetworkConnected=${isNetworkConnected.value}, isServerConnected=${isServerConnected.value}, shouldShowDialog=${shouldShowDialog.value}")
            if (!isDuplicate.value && isNetworkConnected.value && !isServerConnected.value) {
                // Create retry button only if network is connected && server is NOT connected
                Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is shown")
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(0.45f),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.LightGray,
                    ),
                ) {
                    Text(text = stringResource(R.string.retry), fontSize = if(stringResource(R.string.retry).length!!<9) 15.sp else 13.sp, color = Color.Black)
                }
            } else {
                Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is NOT shown")
            }
        },
        dismissButton = {
            Button(
                onClick = {
                    shouldShowDialog.value = false
                    serverDetailsChanged.value = false
                    isDuplicate.value = false
                    navController.navigate("tak_servers") {
                        popUpTo(("tak_servers")) {
                            inclusive = true
                        }
                    }
                },
                contentPadding = PaddingValues(0.dp),
                modifier = if (isServerConnected.value) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.45f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray,
                )
            ) {
                if (isServerConnected.value) {
                    Text(text = stringResource(R.string.okay))
                } else {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
fun WearTAKSitxGroupSetting (
    navController: NavController,
    dataList: List<SitxTakService.TakServerItem>,
    sitxGroup: SitxTakService.TakServerItem?,
    sitxGroupStatus: SitxGroupStatus,
    settingsVm: WearTAKSettingsViewModel
) {
    val groupChanged = remember { mutableStateOf(false) }
    val initialIndex = settingsVm.findSitxGroupIndex(sitxGroup, dataList)
    var selectedRadioIndex by remember(initialIndex) { mutableStateOf(initialIndex) }
    WearTAKPageWithBackArrow(
        "Sit(x) Group",
        {
            if (dataList.isNullOrEmpty()) {
                item {
                    CircularProgressIndicator()
                    Text(
                        text = "Loading Sit(x) Groups",
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(vertical = 10.dp, horizontal = 20.dp)
//                                .align(Alignment.CenterHorizontally)
                    )
                }
            }
            itemsIndexed(dataList){ index, ltItem ->
                run {
                    WearTAKSelectableChip(
                        selected = index == selectedRadioIndex,
                        onClick = { selectedRadioIndex = index },
                        title = ltItem.name,
                        combined = true,
                        list = dataList
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(15.dp))
            }
        },
        navController,
        exitActivity = true,
        exitFunction = {
            if (selectedRadioIndex != -1) {
                if (sitxGroup?.flowTag != dataList[selectedRadioIndex].flowTag) {
                    groupChanged.value = true
                    sitxTakGroup = dataList[selectedRadioIndex]
                    Log.d(TAG, "sitxGroupSelected = $sitxTakGroup")
                    runBlocking {
                        SitxServiceCommandBus.trySend(
                            SitxServiceCommand.SwitchGroup(sitxTakGroup?.flowTag ?: "")
                        )
                    }
                    Log.d(
                        TAG,
                        "RadioChoiceSetting Settings saved (Sit(x) Group): ${sitxTakGroup}"
                    )
                } else {
                    navController.navigate("sitx_tak_screen") {
                        popUpTo(("sitx_tak_screen")) {
                            inclusive = true
                        }
                    }
                }
            } else {
                Toast.makeText(mainActivity, "Please select a group", Toast.LENGTH_LONG).show()
            }
        }
    )
    if (groupChanged.value) {
        Log.d(TAG, "loadingDialog: $sitxGroupStatus")
        val shouldShowLoadingDialog = remember { mutableStateOf(false) }
        val shouldShowConfirmDialog = remember { mutableStateOf(false) }
        val isGroupConnected = remember { mutableStateOf(false) }

        settingsVm.displaySitxGroupDialog(sitxGroupStatus, shouldShowLoadingDialog, shouldShowConfirmDialog, isGroupConnected)
        WearTAKLoadingDialog(shouldShowLoadingDialog, "Selecting Group")
        WearTAKAlertDialog(
            shouldShowDialog = shouldShowConfirmDialog,
            shouldShowLoadingDialog = shouldShowLoadingDialog,
            modifier = Modifier.wrapContentHeight(),
            text = {
                if (isGroupConnected.value) {
                    Text("Group Connected", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                } else {
                    Text("Group Connection Failed", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp))
                }
            },
            confirmButton = {
                if (!isGroupConnected.value) {
                    Button(
                        onClick = { shouldShowConfirmDialog.value = false
                            groupChanged.value = false
                        },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.LightGray,
                        ),
                    ) {
                        Text(text = stringResource(R.string.retry), fontSize = 15.sp, color = Color.Black)
                    }
                } else {
                    Log.d(TAG, "SitxGroupRegistrationDialog confirmButton: RetryButton is NOT shown")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        shouldShowConfirmDialog.value = false
                        groupChanged.value = false
                        navController.navigate("sitx_tak_screen") {
                            popUpTo(("sitx_tak_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = if (isGroupConnected.value) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.45f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    if (isGroupConnected.value) {
                        Text(text = stringResource(R.string.okay))
                    } else {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }
        )
        if (sitxGroupStatus == SitxGroupStatus.CONNECTED){
            settingsVm.setSelectedSitxGroup(sitxTakGroup)
            Log.d(TAG, "$sitxGroup")
        }
    }
}

@Composable
fun WearTAKSitxStatusAuthorizationScreen(navController: NavController, sitxEvent: SitxServiceEvent?) {
    var title = ""
    var secondaryText = ""
    var userCode = ""

    when (val e = sitxEvent) {
        is SitxServiceEvent.NeedsUserAction -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Needs User Action: (${e.userCode}) and (${e.verificationUriComplete})")
            title = "Authorize Device"
            userCode = e.userCode
            secondaryText = "Authorize device at: ${e.verificationUriComplete} and then press Authorize below"
        }
        is SitxServiceEvent.Pending -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Pending:(${e.userCode}) and (${e.verificationUriComplete}) with (${e.message})")
            title = "Pending Auth"
            userCode = e.userCode
            secondaryText = "Device authorization pending: ${e.message}"
        }
        is SitxServiceEvent.Error -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Error: ${e.message}")
            title = "Error"
            secondaryText = "Error: ${e.message}"
        }
        is SitxServiceEvent.Info -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Info: ${e.message}")
            title = "Info"
            secondaryText = "Info: ${e.message}"
        }
        is SitxServiceEvent.Authorized -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Authorized: ${e.message}")
            title = "Device Authorized"
            secondaryText = "Device Authorized: ${e.message}"
        }
        is SitxServiceEvent.SocketsConnected -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) SocketsConnected(${e.count}): ${e.message}")
            title = "Sit(x) Connected"
            secondaryText = "Connected to Sit(x): ${e.message}"
        }
        is SitxServiceEvent.SocketStatus -> {
            title = "Socket Status"
            Log.d(TAG, "SitxStatusAuthorizationScreen Sit(x) SocketStatus(flowTag=${e.flowTag}, status=${e.status}): ${e.message}")
            secondaryText = "Connected to Sit(x) w/ ${e.status}: ${e.message}"
        }
        is SitxServiceEvent.Disconnected -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Disconnected")
            title = "Sit(x) Disconnected"
            secondaryText = "Disconnected from Sit(x)"
        }
        else -> Unit
    }

    WearTAKCIVTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(vertical = 10.dp, horizontal = 20.dp)
                    .align(Alignment.CenterHorizontally)
            )
            if (sitxEvent is SitxServiceEvent.NeedsUserAction) {
                Text(
                    text = userCode,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 25.sp,
                    modifier = Modifier
                        .padding(vertical = 7.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }
            Text(
                text = secondaryText,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 14.sp,
            )
            Spacer(modifier = Modifier.size(5.dp))
            Row(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = {
                        navController.navigate("sitx_tak_screen") {
                            popUpTo("sitx_tak_screen") { inclusive = true }
                        }
                    },
                    contentPadding = PaddingValues(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.dismiss),
                    )
                }

                if (sitxEvent is SitxServiceEvent.NeedsUserAction) {
                    Spacer(modifier = Modifier.size(10.dp))

                    Button(
                        onClick = {
                            SitxServiceCommandBus.trySend(SitxServiceCommand.PollDeviceToken)
                            //TODO: Might be a better method of this but it works for now
                            navController.navigate("sitx_tak_group_screen") {
                                popUpTo(("sitx_tak_group_screen")) {
                                    inclusive = true
                                }
                            }
                        },
                        contentPadding = PaddingValues(10.dp),
                        colors = ButtonDefaults.buttonColors(colors.primary),
                    ) {
                        Text(
                            text = "Authorize",
                            color = Color.Black
                        )
                    }
                } else if (sitxEvent is SitxServiceEvent.Error) {
                    Spacer(modifier = Modifier.size(10.dp))

                    Button(
                        onClick = {
                            runBlocking {
                                SitxServiceCommandBus.trySend(SitxServiceCommand.BeginAuth(
                                    baseUrl = sitxRepo.baseUrl.first(),
                                    clientId = sitxRepo.clientId.first() ?: "",
                                    callsign = callsign,
                                    deviceName = device_uid,
                                    deviceId = device_uid
                                )
                                )
                            }
                        },
                        contentPadding = PaddingValues(10.dp),
                        colors = ButtonDefaults.buttonColors(colors.primary),
                    ) {
                        Text(
                            text = "Re-Auth",
                            color = Color.Black
                        )
                    }
                }
            }
        }
        // Render the dialog ONCE, using the remembered state (no inline mutableStateOf(true))
    }
}

@Composable
fun WearTAKPluginPopupBox(
    showPopup: MutableState<Boolean>,
    titleText: String,
    description: String,
    versionNumber: String,
    compatibility: PluginCompatibilityStatus,
    loadingStatus: PluginLoadingStatus,
    errorMessage: String
) {
    if (showPopup.value) {
        Dialog(
            onDismissRequest = { showPopup.value = false },
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.DarkGray),
                shape = RectangleShape,
                modifier = Modifier
                    .fillMaxHeight(.6f)
                    .fillMaxWidth(.8f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = description,
                        maxLines = 2,
                        fontSize = 11.sp,
                        lineHeight = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (loadingStatus == PluginLoadingStatus.ERROR){
                        Column(
                            Modifier
                                .background(Color.Red.copy(alpha = 0.5f))
                                .padding(5.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = "ERROR: $errorMessage",
                                color = Color.White,
                                maxLines = 4,
                                fontSize = 12.sp,
                                lineHeight = 11.sp
                            )
                        }
                    } else {
                        Column(
                            Modifier
                                .background(Color.Green)
                                .padding(5.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = "Version: $versionNumber",
                                color = Color.Black,
                                maxLines = 3,
                                fontSize = 10.sp,
                                lineHeight = 11.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Is $compatibility with WearTAK ${MainActivity.Constants.versionNumber} and is $loadingStatus",
                                color = Color.Black,
                                maxLines = 2,
                                fontSize = 10.sp,
                                lineHeight = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
//TODO: WEAR-450 see if you can use the other version of this
@Composable
fun GenericOutlinedTextField(
    liveDataState: MutableLiveData<String>,
    label: String,
    placeholder: String
): TextFieldValue {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(liveDataState.value ?: "null"))
    }
    OutlinedTextField(
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        label = { Text(text = label, color = colors.primary) },
        placeholder = { Text(text = placeholder, color = colors.primary) },
        textStyle = TextStyle(color = colors.primary)
    )
    return textFieldValue
}

@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKTitleChipPreview() {
    WearTAKTitleChip(
        icon = { Icon(painterResource(R.drawable.bloodhound), "Bloodhound") },
        title = "Title Chip",
        route = "",
        onClick = {  },
        navController = rememberNavController()
    )
}
@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKTitleChipWithStatePreview() {
    WearTAKTitleChipWithState(
        icon = { Icon(painterResource(R.drawable.bloodhound), "Bloodhound") },
        title = "Title Chip",
        route = "",
        onClick = {  },
        state = "State",
        navController = rememberNavController()
    )
}
@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKToggleChipPreview() {
    WearTAKToggleChip(
        checkedLive = true,
        onCheckedChange = { },
        title = "Toggle Chip",
        description = stringResource(R.string.on_off)
    )
}
@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKSelectableChipPreview() {
    WearTAKSelectableChip(
        selected = true,
        onClick = { },
        title = "Selectable Chip",
        combined = false,
        list = listOf(""),
    )
}

@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKSplitToggleChipPreview() {
    WearTAKSplitToggleChip(
        title = "Split Toggle Chip",
        label = "Secondary Label",
        checked = true,
        onCheckedChange = {},
        onClick = { }
    )
}
@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKSwipeToRevealChipPreview() {
    WearTAKSwipeToRevealChip(
        onPrimaryActionClick = {  },
        onSecondaryActionClick = { },
        chipType = { WearTAKTitleChip(
            title = "Title Chip",
            route = "",
            onClick = {  },
            navController = rememberNavController()
        )},
    )
}
@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKMultipleChoiceCheckboxChipPreview() {
    val selectedIndices = remember {
        // start with whatever LiveData says
        mutableStateListOf<Int>().apply {  }
    }
    WearTAKMultipleChoiceCheckboxChip(
        selectedIndices = selectedIndices,
        index = 0,
        dataItem = "Multi Select Chip",
        checked = true,
        list = listOf(""),
        onClick = {}
    )
}
@Preview(widthDp = 192, heightDp = 70, showBackground = false)
@Composable
fun WearTAKOutlinedTextFieldPreview() {
    WearTAKOutlinedTextField(
        string = "Textfield",
        label = "Textfield",
    )
}
@Preview(widthDp = 192, heightDp = 50, showBackground = false)
@Composable
fun WearTAKConfirmCancelButtonsPreview() {
    WearTAKConfirmCancelButtons(
        onClickCancel = {  },
        onClickConfirm = {  }
    )
}
@Preview(widthDp = 100, heightDp = 50, showBackground = false)
@Composable
fun WearTAKBackButtonPreview() {
    WearTAKBackButton(
        onClick = {  }
    )
}
@Preview(widthDp = 192, heightDp = 192, showBackground = true)
@Composable
fun WearTAKStringEntryPagePreview() {
    WearTAKStringEntryPage(
        navController = rememberNavController(),
        item = "String Entry",
        label = "String Entry Page",
        confirmFunction = {  }
    )
}
@Preview(widthDp = 192, heightDp = 192, showBackground = true)
@Composable
fun WearTAKIntEntryPagePreview() {
    WearTAKIntEntryPage(
        navController = rememberNavController(),
        item = 1,
        label = "Int Entry Page",
        maximum = 2,
        minimum = 0,
        context = LocalContext.current,
        confirmFunction = {}
    )
}
@Preview(widthDp = 192, heightDp = 192, showBackground = true)
@Composable
fun WearTAKPickerPreview() {
    WearTAKPicker(
        title = stringResource(R.string.height),
        navController = rememberNavController(),
        columnOneState = 0,
        columnTwoState = 0,
        columnOneAmount = 8,
        columnTwoAmount = 12,
        columnOneText = "",
        columnTwoText = "",
        onClickConfirmOne = {},
        onClickConfirmTwo = {}
    )
}
@Preview(widthDp = 192, heightDp = 192, showBackground = true)
@Composable
fun WearTAKPageWithBackArrowPreview() {
    WearTAKPageWithBackArrow(
        title = "Page with Back Arrow",
        columnContent = {},
        navController = rememberNavController(),
    )
}
@Preview(widthDp = 192, heightDp = 192, showBackground = true)
@Composable
fun WearTAKPageWithConfirmCancelPreview() {
    WearTAKPageWithConfirmCancel(
        title = "Page with Confirm Cancel Buttons",
        columnContent = {},
        navController = rememberNavController(),
        {},
        {}
    )
}
@Preview(widthDp = 192, heightDp = 300, showBackground = true)
@Composable
fun WearTAKSelectionPagePreview() {
    WearTAKSelectionPage(
        index = 1,
        title = "Selection Page",
        navController = rememberNavController(),
        list = listOf("Item 1", "Item 2", "Item 3"),
        confirmFunction = {}
    )
}
@Preview(widthDp = 192, heightDp = 300, showBackground = true)
@Composable
fun WearTAKDoubleSelectionPagePreview() {
    var pair = (0 to 1)
    WearTAKDoubleSelectionPage(
        navController = rememberNavController(),
        categoryIndex = 0,
        selectedCategoryAndRoleIndex = pair,
        onRoleSelected =  { pair = it },
        title = "Double Selection Screen",
        list = listOf("Item 1", "Item 2", "Item 3"),
        confirmFunctionCategory = { }
    )
}
@Preview(widthDp = 192, heightDp = 300, showBackground = true)
@Composable
fun WearTAKMultipleSelectionPagePreview() {
    WearTAKMultipleSelectionPage(
        title = "Multiple Selection Page",
        navController = rememberNavController(),
        repoList = listOf("Item 1", "Item 2", "Item 3"),
        nameList = listOf("Item 1", "Item 2", "Item 3"),
        confirmFunction = {  }
    )
}
