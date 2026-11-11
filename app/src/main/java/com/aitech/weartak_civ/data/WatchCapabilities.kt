package com.aitech.weartak_civ.data

import android.content.Context
import android.os.Build
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.WearTakApplication
import org.json.JSONArray
import org.json.JSONObject

/** Additive protocol. Features are supported protocol versions, never inferred from app versions. */
object WatchCapabilities {
    fun software(context: Context): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject().put("packageName", context.packageName)
            .put("versionName", info.versionName ?: "unknown").put("versionCode", info.longVersionCode)
            .put("gitCommit", BuildConfig.GIT_COMMIT).put("buildTime", BuildConfig.BUILD_TIMESTAMP_UTC)
            .put("androidSdk", Build.VERSION.SDK_INT).put("androidRelease", Build.VERSION.RELEASE)
            .put("osBuild", Build.DISPLAY).put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
    }
    fun snapshot(app: WearTakApplication, requestId: String, watchUid: String, voiceDeliveryAvailable: Boolean = false, receiverCount: Int? = null, standaloneReceiver: JSONObject? = null): JSONObject {
        require(requestId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        val plugins = app.pluginManager.companionPluginInventory()
        val receivers = receiverCount ?: plugins.count { it.getBoolean("loaded") && it.getJSONObject("features").optInt("voice_channels_v1") == 1 }
        val voice = voiceDeliveryAvailable && receivers == 1
        val reasons = JSONObject()
        if (!voice) reasons.put("voice_channels_v1", when {
            !voiceDeliveryAvailable -> "HOST_VOICE_STAGING_UNAVAILABLE"
            receivers == 0 -> "VOICE_PLUGIN_NOT_READY"
            else -> "AMBIGUOUS_VOICE_RECEIVERS"
        })
        return JSONObject().put("msgType", "watch_capabilities").put("version", 1)
            .put("requestId", requestId).put("watchUid", watchUid).put("software", software(app))
                            .put("features", JSONObject().put("capabilities_v1", 1)
                .put("watch_settings_v1", 1).put("phone_managed_settings_v1", 1)
                .put("unit_prefs_v1", 1).put("self_status_v1", 1)
                .put("voice_channels_v1", if (voice) 1 else 0))
            .put("plugins", JSONArray(plugins)).put("unavailableReasons", reasons)
            .put("voiceReceivers", JSONArray().apply { standaloneReceiver?.let { put(it) } })
    }
}
