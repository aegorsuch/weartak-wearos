package com.aitech.weartak_civ.presentation.viewmodel

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.PickerState
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
import com.aitech.weartak_civ.DEFAULT_CONSTANT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_CRM_MODERATE_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_CRM_POOR_MAX_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_ENABLE_CRM_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION
import com.aitech.weartak_civ.DEFAULT_ENABLE_BATDOK_COT
import com.aitech.weartak_civ.DEFAULT_ENABLE_BATTERY_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLOODHOUND_COMPASS_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
import com.aitech.weartak_civ.DEFAULT_ENABLE_CHAT_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_CHAT_COMMANDS
import com.aitech.weartak_civ.DEFAULT_ENABLE_COMPANION_APP_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_DITTO_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_ESRI_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_ENABLE_IMMERSION_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_LOCATION_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT
import com.aitech.weartak_civ.DEFAULT_ENABLE_MULTICAST_ON_WIFI
import com.aitech.weartak_civ.DEFAULT_ENABLE_ONNX_GUNSHOT
import com.aitech.weartak_civ.DEFAULT_ENABLE_ONNX_UAS
import com.aitech.weartak_civ.DEFAULT_ENABLE_PHYSIOLOGICAL_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES
import com.aitech.weartak_civ.DEFAULT_ENABLE_SITX_SERVICE
import com.aitech.weartak_civ.DEFAULT_ENABLE_SMS_ALERTS
import com.aitech.weartak_civ.DEFAULT_ENABLE_TABLET_COMMAND_ENDPOINT
import com.aitech.weartak_civ.DEFAULT_ENABLE_WIFI_BATTERY_OPTIMIZATION
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_LENGTH_SECS
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_LENGTH_SECS
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_HEIGHT_FEET
import com.aitech.weartak_civ.DEFAULT_HEIGHT_INCHES
import com.aitech.weartak_civ.DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
import com.aitech.weartak_civ.DEFAULT_MULTICAST_ADDRESS
import com.aitech.weartak_civ.DEFAULT_MULTICAST_PORT
import com.aitech.weartak_civ.DEFAULT_MULTICAST_PROTOCOL
import com.aitech.weartak_civ.DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
import com.aitech.weartak_civ.DEFAULT_ON_FOOT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_SELECTED_PHYSIO_METRIC
import com.aitech.weartak_civ.DEFAULT_STATIONARY_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_VEHICLE_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.Utils.SettingsUtils.CategoryRolesEntry
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.data.CrmThresholdRanges
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.SitxGroupStatus
import com.aitech.weartak_civ.data.DataRepository.SmsNumber
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.data.DataRepository.WifiSsidItem
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.birthYearValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodTypeValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodhoundProximityIntensity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodhoundProximityRadiusMeters
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.category
import com.aitech.weartak_civ.presentation.MainActivity.Constants.constantReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.crmModerateMaxThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.crmPoorMaxThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCot
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatteryAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBetaFeatures
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundProximityVibration
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableCompanionAppService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDynamicReportingStrategy
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHighAtmPressureAlert
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableImmersionAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLowAtmPressureAlert
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableMulticastOnWifi
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxGunshotService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxUasService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServices
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSitxService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSmsForAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.esriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionAlertThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdAlert
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdWarning
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionWarningThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightFeetValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightInchesValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highAtmPressureAlertThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrAlertThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrWarningThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.loadoutWeight
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowAtmPressureAlertThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrAlertThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrWarningThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastAddress
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastPort
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastProtocol
import com.aitech.weartak_civ.presentation.MainActivity.Constants.onFootReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.onnxGunshotTriggerThresholdPercent
import com.aitech.weartak_civ.presentation.MainActivity.Constants.onnxUasTriggerThresholdPercent
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrHighAlertBpmThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrLowAlertBpmThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.selectedPhysioMetric
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sexValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakGroup
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakUrl
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.strideLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandApikey
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformPantsLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformWaistSize
import com.aitech.weartak_civ.presentation.MainActivity.Constants.userTypeValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.weightValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidList
import com.aitech.weartak_civ.service.SitxTakService
import com.aitech.weartak_civ.service.TAKConnectService
import com.aitech.weartak_civ.data.takserver.TakServerId
import com.aitech.weartak_civ.data.takserver.TakServerRegistration
import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
import com.aitech.weartak_civ.service.sitx.SitxServiceEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.Int
import kotlin.String

internal suspend fun updateCompanionServiceSetting(repository: DataRepository, enabled: Boolean) {
    if (repository.getNetworkPreferencesLocked.first()) {
        Log.w(TAG, "Companion change rejected while network preferences are locked")
        return
    }
    if (!enabled) {
        repository.phoneManagedCompanionAddress.first()?.let { peer ->
            repository.clearPhoneManagedSettingsFromNetworkSettings(peer)?.applyToRuntimeConstants()
        }
    }
    repository.storeEnableCompanionAppService(enabled)
}

class WearTAKSettingsViewModel : ViewModel() {

    private suspend fun refreshPhysioCepRuntime(reloadSustainOverrides: Boolean = false) {
        if (reloadSustainOverrides) {
            dataRepository.rebuildCepSustainOverridesFromSettings()
        }
        CepProvider.getOrCreateEngine(
            WearTakApplication.AppCtx.app.applicationContext
        ).refreshPhysiologicalRuntime(reloadSustainOverrides)
    }

    fun findRadioIndex(dataName: String?, dataList: List<String>): Int {
        if (null == dataName) {
            return 0
        }
        val index = dataList.indexOf(dataName)
        if (index == -1) {
            return 0
        }
        return index
    }

    fun findPickerIndex(dataName: Int?, picker: PickerState) : Int {
        if (null == dataName) {
            return 0
        }
        val index = picker.selectedOption
        if (index == -1) {
            return 0
        }
        return index
    }

    //Callsign and Device Preferences vvv
    val savedCallsign: StateFlow<String> =
        dataRepository.getCallsign.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "WEAROS-0"
        )

    val savedPhoneManagedSettings: StateFlow<Boolean> =
        dataRepository.phoneManagedSettingsLocked.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private fun isPhoneManagedSettingsActive() = savedPhoneManagedSettings.value

    fun storeCallsign(newCallsign: String) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeCallsign(newCallsign)
            callsign = newCallsign
        }
    }

    fun findTeamIndex(teamName: String?): Int {
        if (null == teamName) {
            return 0
        }
        val index = teamEntriesValuesList.ltEntries.indexOf(teamName)
        if (index == -1) {
            return 0
        }
        return index
    }

    val savedTeam: StateFlow<String> =
        dataRepository.getTeam.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "Orange"
        )

    fun storeTeam(newTeam: String) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeTeam(newTeam)
            teamValue = newTeam
        }
    }

    var categoryRolesEntryLists: List<CategoryRolesEntry> = emptyList()
    fun findRoleSelectionFromString(roleString: String?): Pair<Int, Int>? {
        if (roleString == null) return null
        for ((categoryIndex, categoryRole) in MainActivity.Constants.categoryRolesEntryLists.withIndex()) {
            val roleIndex = categoryRole.roles.indexOf(roleString)
            if (roleIndex != -1) return Pair(categoryIndex, roleIndex)
        }
        return null
    }

    val savedRoleCategory: StateFlow<String> =
        dataRepository.getRoleCategory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "MIL"
        )

    fun storeRoleCategory(newCategory: String) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeRoleCategory(newCategory)
            category = newCategory
        }
    }

    val savedRole: StateFlow<String> =
        dataRepository.getRole.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeRole(newRole: String) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeRole(newRole)
            role = newRole
        }
    }

    val savedBirthYear: StateFlow<Int> =
        dataRepository.getYearOfBirth.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun storeYearOfBirth(year: Int) {
        viewModelScope.launch {
            dataRepository.storeYearOfBirth(year)
            birthYearValue = year
        }
    }
    val savedHeightFeet: StateFlow<Int> =
        dataRepository.getPhysiologicalMetricHeightFeet.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_HEIGHT_FEET
        )

    fun storePhysiologicalMetricHeightFeet(heightfeet: Int) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricHeightFeet(heightfeet)
            heightFeetValue = heightfeet
        }
    }
    val savedHeightInches: StateFlow<Int> =
        dataRepository.getPhysiologicalMetricHeightInches.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_HEIGHT_INCHES
        )

    fun storePhysiologicalMetricHeightInches(heightInches: Int) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricHeightInches(heightInches)
            heightInchesValue = heightInches
        }
    }

    val savedWeight: StateFlow<Int> =
        dataRepository.getPhysiologicalMetricWeight.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun storeWeight(weight: Int) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricWeight(weight)
            weightValue = weight
        }
    }

    val savedSex: StateFlow<String> =
        dataRepository.getPhysiologicalMetricSex.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeSex(sex: String) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricSex(sex)
            sexValue = sex
        }
    }

    val savedBloodType: StateFlow<String> =
        dataRepository.getPhysiologicalMetricBloodType.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeBloodType(type: String) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricBloodType(type)
            bloodTypeValue = type
        }
    }

    val savedAllergies: StateFlow<List<String>> =
        dataRepository.getPhysiologicalMetricAllergyListString.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun storeAllergies(list: List<String>) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricAllergyListString(list)
//            allergyList = list
        }
    }

    val savedUserType: StateFlow<String> =
        dataRepository.getPhysiologicalMetricUserType.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeUserType(type: String) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricUserType(type)
            userTypeValue = type
        }
    }

    val savedUniformWaistSize: StateFlow<Int> =
        dataRepository.getPhysiologicalMetricUniformWaistSize.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun storeUniformWaistSize(size: Int) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricUniformWaistSize(size)
            uniformWaistSize = size
        }
    }

    val savedStrideLength: StateFlow<Int> =
        dataRepository.getPhysiologicalMetricStrideLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun storeStrideLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricStrideLength(length)
            strideLength = length
        }
    }

    val savedUniformPantsLength: StateFlow<Int> =
        dataRepository.getPhysiologicalMetricUniformPantsLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun storeUniformPantsLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricUniformPantsLength(length)
            uniformPantsLength = length
        }
    }

    val savedLoadoutWeight: StateFlow<Int> =
        dataRepository.getPhysiologicalMetricLoadoutWeight.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun storeLoadoutWeight(weight: Int) {
        viewModelScope.launch {
            dataRepository.storePhysiologicalMetricLoadoutWeight(weight)
            loadoutWeight = weight
        }
    }

    val savedEnableDynamicReporting: StateFlow<Boolean> =
        dataRepository.getEnableDynamicReportingStrategy.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun storeEnableDynamicReporting(enableDynamicRS: Boolean) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeEnableDynamicReportingStrategy(enableDynamicRS)
            enableDynamicReportingStrategy = enableDynamicRS
        }
    }

    val savedConstantReportingInterval: StateFlow<Int> =
        dataRepository.getConstantReportingInterval.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_CONSTANT_REPORTING_INTERVAL
        )

    fun storeConstantReportingInterval(interval: Int) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeConstantReportingInterval(interval)
            constantReportingInterval = interval
        }
    }

    val savedStationaryReportingInterval: StateFlow<Int> =
        dataRepository.getStationaryReportingInterval.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_STATIONARY_REPORTING_INTERVAL
        )

    fun storeStationaryReportingInterval(interval: Int) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeStationaryReportingInterval(interval)
            stationaryReportingInterval = interval
        }
    }

    val savedOnFootReportingInterval: StateFlow<Int> =
        dataRepository.getOnFootReportingInterval.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ON_FOOT_REPORTING_INTERVAL
        )

    fun storeOnFootReportingInterval(interval: Int) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeOnFootReportingInterval(interval)
            onFootReportingInterval = interval
        }
    }

    val savedVehicleReportingInterval: StateFlow<Int> =
        dataRepository.getVehicleReportingInterval.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_VEHICLE_REPORTING_INTERVAL
        )

    fun storeVehicleReportingInterval(interval: Int) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeVehicleReportingInterval(interval)
            vehicleReportingInterval = interval
        }
    }

    val savedWhileAlertingReportingInterval: StateFlow<Int> =
        dataRepository.getWhileAlertingReportingInterval.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
        )

    fun storeWhileAlertingReportingInterval(interval: Int) {
        if (isPhoneManagedSettingsActive()) return
        viewModelScope.launch {
            dataRepository.storeWhileAlertingReportingInterval(interval)
            whileAlertingReportingInterval = interval
        }
    }


    val savedEnableWifiBatteryOptimization: StateFlow<Boolean> =
        dataRepository.getEnableWifiBatteryOptimization.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_WIFI_BATTERY_OPTIMIZATION
        )

    fun storeEnableWifiBatteryOptimization(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableWifiBatteryOptimization(enable)
            enableWifiBatteryOptimization = enable
        }
    }

    val savedEnableAllWifiBatteryOptimization: StateFlow<Boolean> =
        dataRepository.getEnableAllWifiBatteryOptimization.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION
        )

    fun storeEnableAllWifiBatteryOptimization(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableAllWifiBatteryOptimization(enable)
            enableAllWifiBatteryOptimization = enable
        }
    }

    val savedWifiSsidList: StateFlow<List<WifiSsidItem>> =
        dataRepository.getWifiSsidList.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun storeWifiSsidList(newList: List<WifiSsidItem>) {
        viewModelScope.launch {
            dataRepository.storeWifiSsidList(newList)
            wifiSsidList = newList
        }
    }

    val savedEnablePhysiologicalServices: StateFlow<Boolean> =
        dataRepository.getEnablePhysiologicalServices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES
        )

    fun storeEnablePhysiologicalServices(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnablePhysiologicalServices(enable)
            enablePhysiologicalServices = enable
        }
    }

    val savedEnableBatdokCot: StateFlow<Boolean> =
        dataRepository.getEnableBatdokCot.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_BATDOK_COT
        )

    fun storeEnableBatdokCot(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableBatdokCot(enable)
            enableBatdokCot = enable
        }
    }

    val savedEnableLocationService: StateFlow<Boolean> =
        dataRepository.getEnableLocationService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_LOCATION_SERVICE
        )

    ///Callsign and Device Preferences ^^^

    //Network Preferences vvv
    val savedTakServerList: StateFlow<List<TakServerItem>> =
        dataRepository.getTakServerList.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun storeTakServerList(newList: List<TakServerItem>) {
        if (isPhoneManagedSettingsActive() || savedNetworkPreferencesLocked.value) return
        viewModelScope.launch {
            dataRepository.storeTakServerList(newList)
            takServerList = newList
        }
    }
    fun addTakServerItem(item: TakServerItem) {
        if (isPhoneManagedSettingsActive() || savedNetworkPreferencesLocked.value) return
        viewModelScope.launch {
            dataRepository.addTakServerItem(item)
        }
    }
    fun removeTakServerItem(item: TakServerItem) {
        if (isPhoneManagedSettingsActive() || savedNetworkPreferencesLocked.value) return
        viewModelScope.launch {
            dataRepository.removeTakServerItem(item)
        }
    }
    fun updateTakServerItem(oldItemIndex: Int, newItem: TakServerItem) {
        if (isPhoneManagedSettingsActive() || savedNetworkPreferencesLocked.value) return
        viewModelScope.launch {
            if (dataRepository.updateTakServerItem(oldItemIndex, newItem) == DataRepository.TakServerUpdateResult.UPDATED) {
                takServerList = dataRepository.awaitTakServerList()
            }
        }
    }
    val serverRegistrationState: StateFlow<TakServerRegistration.State> =
        TAKConnectService.serverRegistration.state
    val takServerRuntimeDetails = TAKConnectService.takServerRuntimeDetails

    fun saveAndRegisterTakServer(item: TakServerItem, index: Int? = null) {
        val serverId = TakServerId.ensure(item.serverId)
        val savedItem = item.copy(serverId = serverId)
        val ticket = TAKConnectService.serverRegistration.prepare(serverId)
        val job = viewModelScope.launch {
            TAKConnectService.serverRegistration.saveAndConnect(
                serverId,
                save = {
                    val result = if (index == null) {
                        when (dataRepository.addTakServerItem(savedItem)) {
                            DataRepository.TakServerAddResult.ADDED ->
                                TakServerRegistration.SaveResult.SAVED
                            DataRepository.TakServerAddResult.DUPLICATE ->
                                TakServerRegistration.SaveResult.DUPLICATE
                        }
                    } else {
                        when (dataRepository.updateTakServerItem(index, savedItem)) {
                            DataRepository.TakServerUpdateResult.UPDATED ->
                                TakServerRegistration.SaveResult.SAVED
                            DataRepository.TakServerUpdateResult.DUPLICATE ->
                                TakServerRegistration.SaveResult.DUPLICATE
                            DataRepository.TakServerUpdateResult.INVALID_INDEX ->
                                TakServerRegistration.SaveResult.INVALID
                        }
                    }
                    if (result == TakServerRegistration.SaveResult.SAVED) {
                        takServerList = dataRepository.awaitTakServerList()
                    }
                    result
                },
                connect = { TAKConnectService.getInstance().registerServer(serverId) },
                ticket = ticket,
            )
        }
        job.invokeOnCompletion { error ->
            if (error != null) TAKConnectService.serverRegistration.failPending(ticket)
        }
    }
    val savedEnableMulticast: StateFlow<Boolean> =
        dataRepository.getEnableMulticast.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_MULTICAST_ON_WIFI
        )

    fun storeEnableMulticast(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableMulticast(enable)
            enableMulticastOnWifi = dataRepository.getEnableMulticast.first()
        }
    }

    val savedMulticastAddress: StateFlow<String> =
        dataRepository.getMulticastAddress.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_MULTICAST_ADDRESS
        )

    fun storeMulticastAddress(address: String) {
        viewModelScope.launch {
            dataRepository.storeMulticastAddress(address)
            multicastAddress = address
        }
    }

    val savedMulticastProtocol: StateFlow<String> =
        dataRepository.getMulticastProtocol.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_MULTICAST_PROTOCOL
        )

    fun storeMulticastProtocol(protocol: String) {
        viewModelScope.launch {
            dataRepository.storeMulticastProtocol(protocol)
            multicastProtocol = protocol
        }
    }

    val savedMulticastPort: StateFlow<Int> =
        dataRepository.getMulticastPort.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_MULTICAST_PORT
        )

    fun storeMulticastPort(port: Int) {
        viewModelScope.launch {
            dataRepository.storeMulticastPort(port)
            multicastPort = port
        }
    }

    val savedEnableSitx: StateFlow<Boolean> =
        dataRepository.getEnableSitxService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_SITX_SERVICE
        )

    fun storeEnableSitx(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableSitxService(enable)
            enableSitxService = dataRepository.getEnableSitxService.first()
        }
    }

    fun removeSitxConfiguration() {
        viewModelScope.launch {
            dataRepository.storeEnableSitxService(false)
            enableSitxService = false
            sitxRepo.clearSecrets(keepBaseUrl = false)
            dataRepository.setSelectedSitxGroup(null)
            dataRepository.setSitxGroups(emptyList())
            dataRepository.setSitxDisconnected()
            sitxTakUrl = ""
            sitxTakGroup = null
        }
    }

//    val sitxEvent by sitxVm.lastEvent.collectAsStateWithLifecycle(initialValue=null)


    val savedSitxTakUrl: StateFlow<String> =
        sitxRepo.baseUrl.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeSitxTakUrl(url: String) {
        viewModelScope.launch {
            sitxRepo.setBaseUrl(url)
            sitxTakUrl = url
        }
    }

    val savedSitxGroup: StateFlow<SitxTakService.TakServerItem?> =
        dataRepository.getSelectedSitxGroup.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun setSelectedSitxGroup (item: SitxTakService.TakServerItem?) {
        viewModelScope.launch {
            dataRepository.setSelectedSitxGroup(item)
            sitxTakGroup = item
        }
    }

    val savedSitxGroupList: StateFlow<List<SitxTakService.TakServerItem>> =
        dataRepository.sitxGroups.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun findSitxGroupIndex(dataName: SitxTakService.TakServerItem?, dataList: List<SitxTakService.TakServerItem>) : Int {
        if (null == dataName) {
            return -1
        }
        val index = dataList.indexOf(dataName)

        return index
    }

fun displaySitxGroupDialog(
    loadingDialog: DataRepository.SitxGroupStatus?,
    shouldShowLoadingDialog: MutableState<Boolean>,
    shouldShowConfirmDialog: MutableState<Boolean>,
    isGroupConnected: MutableState<Boolean>
) {
    if (loadingDialog == DataRepository.SitxGroupStatus.CONNECTING) {
            Log.d(TAG, "displaySitxGroupDialog: CONNECTING")
            shouldShowLoadingDialog.value = true
            shouldShowConfirmDialog.value = false
        } else if (loadingDialog == DataRepository.SitxGroupStatus.CONNECTED) {
            Log.d(TAG, "displaySitxGroupDialog: CONNECTED")
            shouldShowLoadingDialog.value = false
            shouldShowConfirmDialog.value = true
            isGroupConnected.value = true
        } else if ((loadingDialog == DataRepository.SitxGroupStatus.FAILED) ||
            (loadingDialog == DataRepository.SitxGroupStatus.DISCONNECTED)
        ) {
            Log.d(TAG, "displaySitxGroupDialog: $loadingDialog")
            shouldShowLoadingDialog.value = false
            shouldShowConfirmDialog.value = true
            isGroupConnected.value = false
        }
        Log.d(TAG, "displaySitxGroupDialog: $loadingDialog")
}

    val sitxGroupStatus: StateFlow<SitxGroupStatus> =
        dataRepository.sitxGroupStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SitxGroupStatus.DISCONNECTED
        )
    private val _lastEvent = MutableStateFlow<SitxServiceEvent?>(null)
    val lastSitxEvent: StateFlow<SitxServiceEvent?> = _lastEvent.asStateFlow()

    init {
        viewModelScope.launch {
            SitxServiceEventBus.events.collect { event ->
                _lastEvent.value = event
            }
        }
    }

    fun sitxStatusText(sitxEvent: SitxServiceEvent?): String {
        return when (sitxEvent) {
            is SitxServiceEvent.NeedsUserAction -> "Needs User Action"
            is SitxServiceEvent.Pending -> "Pending Authorization"
            is SitxServiceEvent.Authorized -> "Device Authorized"
            is SitxServiceEvent.SocketStatus -> when (sitxEvent.status) {
                "CONNECTED" -> "Connected"
                "CONNECTING" -> "Connecting"
                "FAILED" -> "Error"
                else -> "Disconnected"
            }
            is SitxServiceEvent.SocketsConnected -> "Connected"
            is SitxServiceEvent.Info -> "Info"
            is SitxServiceEvent.Error -> "Error"
            is SitxServiceEvent.Disconnected -> "Disconnected"
            else -> "Unknown"
        }
    }

    val savedEnableCompanionAppService: StateFlow<Boolean> =
        dataRepository.getEnableCompanionAppService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_COMPANION_APP_SERVICE
        )

    val savedEnableChatCommands: StateFlow<Boolean> =
        dataRepository.getEnableChatCommands.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_CHAT_COMMANDS
        )

    val savedBondedLocationDiagnosticsEnabled: StateFlow<Boolean> =
        dataRepository.getBondedLocationDiagnosticsEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun storeBondedLocationDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch { dataRepository.storeBondedLocationDiagnosticsEnabled(enabled) }
    }

    fun storeEnableChatCommands(enable: Boolean) {
        viewModelScope.launch { dataRepository.storeEnableChatCommands(enable) }
    }

    val savedEnableSensorManager: StateFlow<Boolean> =
        dataRepository.getEnableSensorManager.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun storeEnableSensorManager(enable: Boolean) {
        viewModelScope.launch { dataRepository.storeEnableSensorManager(enable) }
    }

    val savedChatCommandCallsigns: StateFlow<List<String>> =
        dataRepository.getChatCommandCallsigns.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun storeChatCommandCallsigns(callsigns: List<String>) {
        viewModelScope.launch { dataRepository.storeChatCommandCallsigns(callsigns) }
    }

    fun storeEnableCompanionAppService(enable: Boolean) {
        viewModelScope.launch {
            updateCompanionServiceSetting(dataRepository, enable)
            enableCompanionAppService = dataRepository.getEnableCompanionAppService.first()
        }
    }

    val savedEnableSmsForAlerts: StateFlow<Boolean> =
        dataRepository.getEnableSmsForAlerts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_SMS_ALERTS
        )

    fun storeEnableSmsForAlerts(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableSmsForAlerts(enable)
            enableSmsForAlerts = enable
        }
    }

    val savedSmsNumbersList: StateFlow<List<SmsNumber>> =
        dataRepository.getSmsNumbersList.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun storeSmsNumbersList(newList: List<SmsNumber>) {
        viewModelScope.launch {
            dataRepository.storeSmsNumbersList(newList)
            smsNumbersList = newList
        }
    }

    fun convertSmsNumbersListToStringWithSeparator(smsNumbersList: List<SmsNumber>, separatorStr: String): String {
        var result = ""
        if (smsNumbersList.isNotEmpty()) {
            for (smsNumber in smsNumbersList) {
                result = if ("" == result) {
                    smsNumber.telephoneNumber
                } else {
                    "$result$separatorStr${smsNumber.telephoneNumber}"
                }
            }
        }
        return result
    }

    val savedEnableDittoService: StateFlow<Boolean> =
        dataRepository.getEnableDittoService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_DITTO_SERVICE
        )

    fun storeEnableDittoService(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableDittoService(enable)
            enableDittoService = dataRepository.getEnableDittoService.first()
        }
    }

    val savedEnableTakEndpoint = dataRepository.getEnableTakEndpoint.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    fun storeEnableTakEndpoint(enabled: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableTakEndpoint(enabled)
            MainActivity.enableTakEndpoint = dataRepository.getEnableTakEndpoint.first()
        }
    }

    val savedEnableTabletCommandEndpoint: StateFlow<Boolean> =
        dataRepository.getEnableTabletCommandEndpoint.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_TABLET_COMMAND_ENDPOINT
        )

    fun storeEnableTabletCommandEndpoint(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableTabletCommandEndpoint(enable)
            enableTabletCommandEndpoint = enable
        }
    }

    val savedTabletCommandEndpoint: StateFlow<String> =
        dataRepository.getTabletCommandEndpoint.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeTabletCommandEndpoint(endpoint: String) {
        viewModelScope.launch {
            dataRepository.storeTabletCommandEndpoint(endpoint)
            tabletCommandEndpoint = endpoint
        }
    }

    val savedTabletCommandApikey: StateFlow<String> =
        dataRepository.getTabletCommandApikey.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeTabletCommandApikey(key: String) {
        viewModelScope.launch {
            dataRepository.storeTabletCommandApikey(key)
            tabletCommandApikey = key
        }
    }

    val savedEnableEsriEndpoint: StateFlow<Boolean> =
        dataRepository.getEnableEsriEndpoint.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_ESRI_ENDPOINT
        )

    fun storeEnableEsriEndpoint(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableEsriEndpoint(enable)
            enableEsriEndpoint = enable
        }
    }

    val savedEsriEndpoint: StateFlow<String> =
        dataRepository.getEsriEndpoint.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    fun storeEsriEndpoint(endpoint: String) {
        viewModelScope.launch {
            dataRepository.storeEsriEndpoint(endpoint)
            esriEndpoint = endpoint
        }
    }
    //Network Preferences ^^^

    //Alerting Preferences vvv
    val savedEnableBatteryAlerts: StateFlow<Boolean> =
        dataRepository.getEnableBatteryAlerts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_BATTERY_ALERTS
        )

    fun storeEnableBatteryAlerts(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableBatteryAlerts(enable)
            enableBatteryAlerts = enable
        }
    }

    val savedEnablePhysiologicalAlerts: StateFlow<Boolean> =
        dataRepository.getEnablePhysiologicalAlerts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_PHYSIOLOGICAL_ALERTS
        )

    fun storeEnablePhysiologicalAlerts(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnablePhysiologicalAlerts(enable)
            enablePhysiologicalAlerts = enable
            refreshPhysioCepRuntime()
        }
    }

    val savedRestingHrHighAlertBpmThreshold: StateFlow<Int> =
        dataRepository.getRestingHrHighAlertBpmThreshold.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_HIGH_RESTING_HR_THRESHOLD
        )

    fun storeRestingHrHighAlertBpmThreshold(threshold: Int) {
        viewModelScope.launch {
            dataRepository.storeRestingHrHighAlertBpmThreshold(threshold)
            restingHrHighAlertBpmThreshold = threshold
            refreshPhysioCepRuntime()
        }
    }
    val savedHighRestingHrWarningThresholdLength: StateFlow<Int> =
        dataRepository.getHighRestingHrWarningThresholdLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_HIGH_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
        )

    fun storeHighRestingHrWarningThresholdLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storeHighRestingHrWarningThresholdLength(length)
            highRestingHrWarningThresholdLength = length
            refreshPhysioCepRuntime(reloadSustainOverrides = true)
        }
    }
    val savedHighRestingHrAlertThresholdLength: StateFlow<Int> =
        dataRepository.getHighRestingHrAlertThresholdLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_HIGH_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
        )

    fun storeHighRestingHrAlertThresholdLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storeHighRestingHrAlertThresholdLength(length)
            highRestingHrAlertThresholdLength = length
            refreshPhysioCepRuntime(reloadSustainOverrides = true)
        }
    }

    val savedRestingHrLowAlertBpmThreshold: StateFlow<Int> =
        dataRepository.getRestingHrLowAlertBpmThreshold.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_LOW_RESTING_HR_THRESHOLD
        )

    fun storeRestingHrLowAlertBpmThreshold(threshold: Int) {
        viewModelScope.launch {
            dataRepository.storeRestingHrLowAlertBpmThreshold(threshold)
            restingHrLowAlertBpmThreshold = threshold
            refreshPhysioCepRuntime()
        }
    }

    val savedLowRestingHrWarningThresholdLength: StateFlow<Int> =
        dataRepository.getLowRestingHrWarningThresholdLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_LOW_RESTING_HR_WARNING_THRESHOLD_LENGTH_MINUTES
        )

    fun storeLowRestingHrWarningThresholdLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storeLowRestingHrWarningThresholdLength(length)
            lowRestingHrWarningThresholdLength = length
            refreshPhysioCepRuntime(reloadSustainOverrides = true)
        }
    }

    val savedLowRestingHrAlertThresholdLength: StateFlow<Int> =
        dataRepository.getLowRestingHrAlertThresholdLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_LOW_RESTING_HR_ALERT_THRESHOLD_LENGTH_MINUTES
        )

    fun storeLowRestingHrAlertThresholdLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storeLowRestingHrAlertThresholdLength(length)
            lowRestingHrAlertThresholdLength = length
            refreshPhysioCepRuntime(reloadSustainOverrides = true)
        }
    }
    val savedExertionThresholdWarning: StateFlow<Int> =
        dataRepository.getExertionThresholdWarning.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
        )

    fun storeExertionThresholdWarning(threshold: Int) {
        viewModelScope.launch {
            dataRepository.storeExertionThresholdWarning(threshold)
            exertionThresholdWarning = threshold
            refreshPhysioCepRuntime()
        }
    }
    val savedExertionThresholdAlert: StateFlow<Int> =
        dataRepository.getExertionThresholdAlert.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
        )

    fun storeExertionThresholdAlert(threshold: Int) {
        viewModelScope.launch {
            dataRepository.storeExertionThresholdAlert(threshold)
            exertionThresholdAlert = threshold
            refreshPhysioCepRuntime()
        }
    }
    val savedExertionWarningThresholdLength: StateFlow<Int> =
        dataRepository.getExertionWarningThresholdLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_EXERTION_WARNING_THRESHOLD_LENGTH_SECS
        )

    fun storeExertionWarningThresholdLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storeExertionWarningThresholdLength(length)
            exertionWarningThresholdLength = length
            refreshPhysioCepRuntime(reloadSustainOverrides = true)
        }
    }
    val savedExertionAlertThresholdLength: StateFlow<Int> =
        dataRepository.getExertionAlertThresholdLength.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_EXERTION_ALERT_THRESHOLD_LENGTH_SECS
        )

    fun storeExertionAlertThresholdLength(length: Int) {
        viewModelScope.launch {
            dataRepository.storeExertionAlertThresholdLength(length)
            exertionAlertThresholdLength = length
            refreshPhysioCepRuntime(reloadSustainOverrides = true)
        }
    }
    val savedEnableWearTakDevMode: StateFlow<Boolean> =
        dataRepository.getEnableWearTakDevMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    fun storeEnableWearTakDevMode(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableWearTakDevMode(enable)
            enableBetaFeatures = enable
        }
    }

    val savedNetworkPreferencesLocked: StateFlow<Boolean> =
        dataRepository.getNetworkPreferencesLocked.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    fun storeNetworkPreferencesLocked(locked: Boolean) {
        viewModelScope.launch {
            dataRepository.storeNetworkPreferencesLocked(locked)
        }
    }

    val savedEnableEnvironmentalAlerts: StateFlow<Boolean> =
        dataRepository.getEnableEnvironmentalAlerts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun storeEnableEnvironmentalAlerts(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableEnvironmentalAlerts(enable)
            CepProvider.getOrCreateEngine(
                WearTakApplication.AppCtx.app.applicationContext
            ).updatePressureProfile()
        }
    }

    val savedEnableImmersionAlerts: StateFlow<Boolean> =
        dataRepository.getEnableImmersionAlerts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_IMMERSION_ALERTS
        )

    fun storeEnableImmersionAlerts(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableImmersionAlerts(enable)
            enableImmersionAlerts = enable
        }
    }

    val savedEnableLowAtmPressureAlert: StateFlow<Boolean> =
        dataRepository.getEnableLowAtmPressureAlert.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_LOW_ATM_PRESSURE_ALERT
        )

    fun storeEnableLowAtmPressureAlert(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableLowAtmPressureAlert(enable)
            enableLowAtmPressureAlert = enable
        }
    }

    val savedLowAtmPressureAlertThreshold: StateFlow<Int> =
        dataRepository.getLowAtmPressureAlertThreshold.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_LOW_ATM_PRESSURE_THRESHOLD
        )

    fun storeLowAtmPressureAlertThreshold(threshold: Int) {
        viewModelScope.launch {
            dataRepository.storeLowAtmPressureAlertThreshold(threshold)
            lowAtmPressureAlertThreshold = threshold
        }
    }

    val savedEnableHighAtmPressureAlert: StateFlow<Boolean> =
        dataRepository.getEnableHighAtmPressureAlert.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_HIGH_ATM_PRESSURE_ALERT
        )

    fun storeEnableHighAtmPressureAlert(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableHighAtmPressureAlert(enable)
            enableHighAtmPressureAlert = enable
        }
    }

    val savedHighAtmPressureAlertThreshold: StateFlow<Int> =
        dataRepository.getHighAtmPressureAlertThreshold.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_HIGH_ATM_PRESSURE_THRESHOLD
        )

    fun storeHighAtmPressureAlertThreshold(threshold: Int) {
        viewModelScope.launch {
            dataRepository.storeHighAtmPressureAlertThreshold(threshold)
            highAtmPressureAlertThreshold = threshold
        }
    }
    //Alerting Preferences ^^^

    //Tool Preferences vvv
    val savedEnableBloodhoundProximityVibration: StateFlow<Boolean> =
        dataRepository.getEnableBloodhoundProximityVibration.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
        )

    fun storeEnableBloodhoundProximityVibration(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableBloodhoundProximityVibration(enable)
            enableBloodhoundProximityVibration = enable
        }
    }
    val savedBloodhoundProximityRadiusMeters: StateFlow<Int> =
        dataRepository.getBloodhoundProximityRadiusMeters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
        )

    fun storeBloodhoundProximityRadiusMeters(radiusMeters: Int) {
        viewModelScope.launch {
            dataRepository.storeBloodhoundProximityRadiusMeters(radiusMeters)
            bloodhoundProximityRadiusMeters = radiusMeters
        }
    }

    fun storeRangeUnits(rangeUnits: Int) {
        viewModelScope.launch {
            dataRepository.storeUnitPrefs(
                mapOf(com.aitech.weartak_civ.ui.formatter.UnitPrefs.KEY_RANGE_UNITS to rangeUnits.toString())
            )
        }
    }
    val savedBloodhoundProximityIntensity: StateFlow<String> =
        dataRepository.getBloodhoundProximityIntensity.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
        )

    fun storeBloodhoundProximityIntensity(intensity: String) {
        viewModelScope.launch {
            dataRepository.storeBloodhoundProximityIntensity(intensity)
            bloodhoundProximityIntensity = intensity
        }
    }
    //Tool Preferences ^^^

    //Beta Features vvv
    val savedEnableOnnxGunshotService: StateFlow<Boolean> =
        dataRepository.getEnableOnnxGunshotService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_ONNX_GUNSHOT
        )

    fun storeEnableOnnxGunshotService(enableOnnxGunshot: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableOnnxGunshotService(enableOnnxGunshot)
            enableOnnxGunshotService = enableOnnxGunshot
        }
    }

    val savedOnnxGunshotTriggerThresholdPercent: StateFlow<Int> =
        dataRepository.getOnnxGunshotTriggerThresholdPercent.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ONNX_GUNSHOT_TRIGGER_THRESHOLD_PERCENT
        )

    fun storeOnnxGunshotTriggerThresholdPercent(thresholdPercent: Int) {
        viewModelScope.launch {
            dataRepository.storeOnnxGunshotTriggerThresholdPercent(thresholdPercent)
            onnxGunshotTriggerThresholdPercent = thresholdPercent
        }
    }

    val savedEnableOnnxUasService: StateFlow<Boolean> =
        dataRepository.getEnableOnnxUasService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_ONNX_UAS
        )

    fun storeEnableOnnxUasService(enableOnnxUas: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableOnnxUasService(enableOnnxUas)
            enableOnnxUasService = enableOnnxUas
        }
    }

    val savedOnnxUasTriggerThresholdPercent: StateFlow<Int> =
        dataRepository.getOnnxUasTriggerThresholdPercent.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ONNX_UAS_TRIGGER_THRESHOLD_PERCENT
        )

    fun storeOnnxUasTriggerThresholdPercent(thresholdPercent: Int) {
        viewModelScope.launch {
            dataRepository.storeOnnxUasTriggerThresholdPercent(thresholdPercent)
            onnxUasTriggerThresholdPercent = thresholdPercent
        }
    }

    val savedEnableMopp: StateFlow<Boolean> = dataRepository.getEnableMopp.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun storeEnableMopp(enabled: Boolean) {
        viewModelScope.launch { dataRepository.storeEnableMopp(enabled) }
    }

    val savedEnableDataSync: StateFlow<Boolean> = dataRepository.getEnableDataSync.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun storeEnableDataSync(enabled: Boolean) {
        viewModelScope.launch { dataRepository.storeEnableDataSync(enabled) }
    }

    val savedEnableCrmService: StateFlow<Boolean> =
        dataRepository.getEnableCrmService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_CRM_SERVICE
        )

    fun storeEnableCrmService(enable: Boolean) {
        viewModelScope.launch {
            dataRepository.storeEnableCrmService(enable)
            MainActivity.enableCrmService = enable
        }
    }

    val savedCrmThresholdRanges: StateFlow<CrmThresholdRanges> =
        dataRepository.crmThresholdRanges.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CrmThresholdRanges(DEFAULT_CRM_POOR_MAX_THRESHOLD, DEFAULT_CRM_MODERATE_MAX_THRESHOLD)
        )

    fun storeCrmThresholdRanges( poorMax: Int, moderateMax: Int,) {
        viewModelScope.launch {
            dataRepository.storeCrmThresholdRanges(poorMax, moderateMax)
            crmPoorMaxThreshold = poorMax
            crmModerateMaxThreshold = moderateMax
        }
    }
    private val _wearTakInfo = MutableStateFlow(
        DataRepository.WearTakInfo(
            versionNumber=  "",
            versionCode= 0,
            gitBranch= "",
            gitCommit= "",
            buildType= "",
    )
    )
    val wearTakInfo: StateFlow<DataRepository.WearTakInfo> = _wearTakInfo

    init {
        viewModelScope.launch {
            // Reference value from repository function
            _wearTakInfo.value = dataRepository.wearTakInfo
        }
    }
    //Beta features ^^^

    //PhysioAlertsActivity, maybe move to own repo

    val savedSelectedPhysioMetric: StateFlow<String> =
        dataRepository.getSelectedPhysioMetric.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_SELECTED_PHYSIO_METRIC
        )

    fun storeSelectedPhysioMetric( metric: String) {
        viewModelScope.launch {
            dataRepository.storeSelectedPhysioMetric(metric)
            selectedPhysioMetric = metric
        }
    }
}
