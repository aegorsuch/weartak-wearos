package com.aitech.weartak_civ.integration.plugin

import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.WearTakApplication
import com.weartak.voice.api.IVoicePackageDevGatewayV1
import org.json.JSONObject

/** Opt-in DEV-only adapter; never treats a standalone app as a registered SDK plugin. */
class VoiceStandaloneAdapter(private val app: WearTakApplication) {
    private val prefs = app.getSharedPreferences("voice_standalone_test", Context.MODE_PRIVATE)
    @Volatile private var gateway: IVoicePackageDevGatewayV1? = null
    @Volatile private var binding: VoicePackageBroker.Binding? = null
    private var bound = false
    private var epoch = 0L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!BuildConfig.DEBUG || !prefs.getBoolean("enabled", false) || service == null) return
            val remote = IVoicePackageDevGatewayV1.Stub.asInterface(service)
            val generation = epoch
            PluginLifecycleWork.execute(PACKAGE) {
            try {
                check(app.packageManager.checkSignatures(app.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH)
                val uid = app.packageManager.getPackageUid(PACKAGE, 0)
                gateway = remote
                val control = app.voicePackages.newBinding(PACKAGE, uid) { epoch == generation && gateway === remote && bound }
                binding = control
                remote.initialize(app.packageName, android.os.Process.myUid(), control)
            } catch (_: Exception) { android.os.Handler(android.os.Looper.getMainLooper()).post { if (epoch == generation) disconnect() } }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { gateway = null; binding?.close(); binding = null }
        override fun onBindingDied(name: ComponentName?) { disconnect() }
        override fun onNullBinding(name: ComponentName?) { disconnect() }
    }
    fun startIfEnabled() { if (BuildConfig.DEBUG && prefs.getBoolean("enabled", false)) runCatching { setEnabled(true) } }
    fun setEnabled(enabled: Boolean) {
        check(BuildConfig.DEBUG) { "Standalone bridge is for development builds only" }
        disconnect(); prefs.edit().putBoolean("enabled", enabled).apply()
        if (!enabled) return
        check(app.packageManager.checkSignatures(app.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) {
            "Standalone app must use the same development signing key as WearTAK Dev"
        }
        bound = true
        val ok = app.bindService(Intent().setComponent(ComponentName(PACKAGE, SERVICE)), connection, Context.BIND_AUTO_CREATE)
        if (!ok) { bound = false; error("Standalone voice app is not installed") }
    }
    private fun disconnect() {
        epoch++; binding?.close(); binding = null
        gateway = null
        if (bound) { bound = false; runCatching { app.unbindService(connection) } }
    }
    fun inventory(): JSONObject? {
        if (!BuildConfig.DEBUG) return null
        val info = runCatching { app.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull()
        return JSONObject().put("kind", "STANDALONE_DEV").put("packageName", PACKAGE)
            .put("installed", info != null).put("enabled", prefs.getBoolean("enabled", false))
            .put("loaded", binding?.ready() == true).put("versionName", info?.versionName ?: "")
            .put("versionCode", info?.longVersionCode ?: 0)
    }
    companion object {
        const val PACKAGE = "se.takcampus.voice.standalone.dev"
        const val SERVICE = "se.takcampus.voice.watch.VoicePackageDevService"
    }
}
