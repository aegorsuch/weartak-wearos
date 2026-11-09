package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.di.CepProvider
import com.weartak.plugin.sdk.model.CepRuleEventParcel
import com.weartak.plugin.sdk.v2.WearTakPluginV2Contract
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v2.model.CepRuleStreamEventParcel
import com.weartak.plugin.sdk.v2.model.DetectionEventParcel
import com.weartak.plugin.sdk.v2.model.HostCapabilitiesV2Parcel
import com.weartak.plugin.sdk.v2.model.TaskCommandCompletionParcel
import com.weartak.plugin.sdk.v2.model.TaskCommandProgressParcel
import com.weartak.plugin.sdk.v2.model.TaskCommandSubmitParcel
import com.weartak.plugin.sdk.v2.model.V2HostRejectionCode
import com.weartak.plugin.sdk.v2.model.V2HostResultParcel
import com.weartak.plugin.sdk.v2.validation.CepRulesV2Validator

/**
 * v2 host control plane. CEP streams are bridged into the existing CEP ingress; detection and
 * task/command lanes return [V2HostRejectionCode.NOT_SUPPORTED] until downstream consumers exist.
 */
class HostControlV2Impl(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val pluginPackage: String,
    private val expectedPluginUid: Int,
    private val isActive: () -> Boolean = { true },
) : IWearTakPluginHostControlV2.Stub() {

    private fun verifyCaller(): Boolean {
        if (!isActive()) return false
        val uid = Binder.getCallingUid()
        if (uid != expectedPluginUid) {
            Log.w(TAG, "v2 control call uid mismatch: expected=$expectedPluginUid got=$uid pkg=$pluginPackage")
            return false
        }
        return true
    }

    private fun senderPackages(): Set<String> = ingress.packagesForUid(app, Binder.getCallingUid())

    override fun applyCepRuleEvent(event: CepRuleStreamEventParcel?): V2HostResultParcel {
        if (!verifyCaller() || event == null) return rejected(V2HostRejectionCode.UNSPECIFIED)
        val validated = when (val v = CepRulesV2Validator.validateStreamEvent(event)) {
            is com.weartak.plugin.sdk.validation.ContractValidationResult.Err -> {
                Log.w(TAG, "applyCepRuleEvent validation: ${v.issues}")
                return rejected(V2HostRejectionCode.UNSPECIFIED)
            }
            is com.weartak.plugin.sdk.validation.ContractValidationResult.Ok -> v.value
        }
        val bridge = CepRuleEventParcel(
            operation = validated.operation,
            pluginId = validated.pluginId,
            sensorKey = validated.sensorKey,
            ruleSetId = validated.ruleSetId,
            sequence = validated.sequence,
            rulesJson = validated.rulesJson,
        )
        val outcome = ingress.applyCepRuleEventBlocking(
            pluginId = pluginPackage,
            event = bridge,
            senderUid = Binder.getCallingUid(),
            senderPackages = senderPackages(),
        )
        return outcome.toV2Result()
    }

    override fun publishDetectionEvent(event: DetectionEventParcel?): V2HostResultParcel {
        if (!verifyCaller() || event == null) return rejected(V2HostRejectionCode.UNSPECIFIED)
        PluginDiagnostics.recordUnsupportedV2Call(pluginPackage, "publishDetectionEvent", event.toString())
        Log.i(TAG, "publishDetectionEvent not supported pluginId=$pluginPackage event=$event")
        return notSupported()
    }

    override fun submitTaskCommand(request: TaskCommandSubmitParcel?): V2HostResultParcel {
        if (!verifyCaller() || request == null) return rejected(V2HostRejectionCode.UNSPECIFIED)
        PluginDiagnostics.recordUnsupportedV2Call(pluginPackage, "submitTaskCommand", request.toString())
        Log.i(TAG, "submitTaskCommand not supported pluginId=$pluginPackage request=$request")
        return notSupported()
    }

    override fun reportTaskCommandProgress(progress: TaskCommandProgressParcel?): V2HostResultParcel {
        if (!verifyCaller() || progress == null) return rejected(V2HostRejectionCode.UNSPECIFIED)
        PluginDiagnostics.recordUnsupportedV2Call(pluginPackage, "reportTaskCommandProgress", progress.toString())
        Log.i(TAG, "reportTaskCommandProgress not supported pluginId=$pluginPackage progress=$progress")
        return notSupported()
    }

    override fun reportTaskCommandCompletion(completion: TaskCommandCompletionParcel?): V2HostResultParcel {
        if (!verifyCaller() || completion == null) return rejected(V2HostRejectionCode.UNSPECIFIED)
        PluginDiagnostics.recordUnsupportedV2Call(
            pluginPackage,
            "reportTaskCommandCompletion",
            completion.toString(),
        )
        Log.i(TAG, "reportTaskCommandCompletion not supported pluginId=$pluginPackage completion=$completion")
        return notSupported()
    }

    override fun getHostCapabilities(): HostCapabilitiesV2Parcel {
        if (!verifyCaller()) {
            return HostCapabilitiesV2Parcel()
        }
        return HostCapabilitiesV2Parcel(
            contractApiVersion = WearTakPluginV2Contract.CONTRACT_API_VERSION,
            cepRules = true,
            detectionEvents = false,
            taskCommands = false,
        )
    }

    private fun rejected(code: Int): V2HostResultParcel =
        V2HostResultParcel(accepted = false, rejectionCode = code)

    private fun notSupported(): V2HostResultParcel = rejected(V2HostRejectionCode.NOT_SUPPORTED)

    private fun CepProvider.ApplyResult.toV2Result(): V2HostResultParcel =
        when (this) {
            is CepProvider.ApplyResult.Applied,
            is CepProvider.ApplyResult.NoOp,
            is CepProvider.ApplyResult.Superseded -> V2HostResultParcel(accepted = true)
            is CepProvider.ApplyResult.Rejected -> V2HostResultParcel(
                accepted = false,
                rejectionCode = V2HostRejectionCode.UNSPECIFIED,
            )
        }

    companion object {
        private const val TAG = "CEP-HostControlV2"
    }
}
