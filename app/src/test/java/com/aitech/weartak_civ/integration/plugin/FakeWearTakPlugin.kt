package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3

/**
 * Minimal in-process [IWearTakPlugin] for host bind/lifecycle tests (no plugin APK).
 */
class FakeWearTakPlugin(
    private val packageName: String,
    private val apiVersion: Int = WearTakPluginContract.CONTRACT_API_VERSION,
) : IWearTakPlugin.Stub() {

    var pluginCapabilities: PluginCapabilitiesParcel = PluginCapabilitiesParcel(hostedSettingsUi = true)
    private var settingsSchema: SettingsSchemaParcel = SettingsSchemaParcel()
    private var mainUiSchema: PluginMainUiSchemaParcel = PluginMainUiSchemaParcel()
    private val settingsValues = Bundle()
    val hostSettingsReceived = mutableListOf<HostSettingsParcel>()
    val lifecycleCalls = mutableListOf<String>()
    var initializePluginCalls = 0
        private set
    var initializePluginV2Calls = 0
        private set
    var initializePluginV3Calls = 0
        private set
    var lastInit: com.weartak.plugin.sdk.model.PluginInitParcel? = null
        private set
    var stopCalls = 0
        private set
    var shutdownCalls = 0
        private set

    override fun getPluginInfo(): PluginInfoParcel =
        PluginInfoParcel(
            pluginId = packageName,
            displayName = "Fake",
            versionName = "1.0",
            packageName = packageName,
        )

    override fun getCapabilities(): PluginCapabilitiesParcel = pluginCapabilities

    override fun getApiVersion(): Int = apiVersion

    override fun getStatus(): PluginStatusParcel =
        PluginStatusParcel(state = PluginLifecycleState.RUNNING)

    override fun initializePlugin(init: com.weartak.plugin.sdk.model.PluginInitParcel?, control: IWearTakPluginControl?) {
        initializePluginCalls++
        lifecycleCalls += "initializePlugin"
    }

    override fun initializePluginV2(
        init: com.weartak.plugin.sdk.model.PluginInitParcel?,
        control: IWearTakPluginControl?,
        controlV2: IWearTakPluginHostControlV2?,
    ) {
        initializePluginV2Calls++
        lastInit = init
        lifecycleCalls += "initializePluginV2"
    }

    override fun initializePluginV3(
        init: com.weartak.plugin.sdk.model.PluginInitParcel?,
        control: IWearTakPluginControl?,
        controlV2: IWearTakPluginHostControlV2?,
        controlV3: IWearTakPluginHostControlV3?,
    ) {
        initializePluginV3Calls++
        lastInit = init
        lifecycleCalls += "initializePluginV3"
    }

    override fun startPlugin() {
        lifecycleCalls += "startPlugin"
    }

    override fun stopPlugin() {
        stopCalls++
        lifecycleCalls += "stopPlugin"
    }

    override fun shutdownPlugin() {
        shutdownCalls++
        lifecycleCalls += "shutdownPlugin"
    }

    override fun registerHostCallback(callback: IWearTakHostCallback?) {
        lifecycleCalls += "registerHostCallback"
    }

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
        lifecycleCalls += "unregisterHostCallback"
    }

    override fun getSettingsSchema(): SettingsSchemaParcel = settingsSchema

    fun setSettingsSchemaForTest(schema: SettingsSchemaParcel) {
        settingsSchema = schema
    }

    override fun getSettingsValues(): Bundle = Bundle(settingsValues)

    override fun applySettings(values: Bundle?) {
        if (values != null) {
            val r = SettingsApplyValidator.mergeSettings(settingsSchema, settingsValues, values)
            if (r is com.weartak.plugin.sdk.validation.ContractValidationResult.Ok) {
                settingsValues.clear()
                settingsValues.putAll(r.value.values)
            }
        }
    }

    override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
        val prev = Bundle(settingsValues)
        val result = SettingsApplyValidator.applySettingsWithResult(
            settingsSchema,
            prev,
            values,
            hostSettingsReceived.lastOrNull(),
            applyMode,
        )
        if (result.success) {
            settingsValues.clear()
            settingsValues.putAll(result.values)
        }
        return result
    }

    override fun onHostSettingsChanged(hostSettings: HostSettingsParcel?) {
        if (hostSettings != null) hostSettingsReceived += hostSettings
    }

    override fun getMainUiSchema(): PluginMainUiSchemaParcel = mainUiSchema

    fun setMainUiSchemaForTest(schema: PluginMainUiSchemaParcel) {
        mainUiSchema = schema
    }

    override fun getMainUiState(): Bundle = Bundle()

    val mainUiActions = mutableListOf<Pair<String?, Bundle?>>()

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
        mainUiActions += actionId to args?.let { Bundle(it) }
    }
}
