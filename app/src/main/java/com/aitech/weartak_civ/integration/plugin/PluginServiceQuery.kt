package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.weartak.plugin.sdk.WearTakPluginContract

/**
 * PackageManager query for plugin services — extracted for unit tests without booting
 * [com.aitech.weartak_civ.WearTakApplication].
 */
internal object PluginServiceQuery {
    internal const val VOICE_DEV_PERMISSION = "se.takcampus.voice.permission.BIND_PLUGIN_DEV"
    internal fun allowedBindPermission(permission: String?, packageName: String, debugHost: Boolean = com.aitech.weartak_civ.BuildConfig.DEBUG): Boolean =
        permission == WearTakPluginContract.PERMISSION_BIND_PLUGIN ||
            (debugHost && packageName == "se.takcampus.voice.watch.dev" && permission == VOICE_DEV_PERMISSION)

    const val MENU_SUBTITLE = "com.weartak.plugin.MENU_SUBTITLE"

    fun menuSubtitle(pm: PackageManager, component: ComponentName): String? {
        return try {
            val service = pm.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
            service.metaData?.getString(MENU_SUBTITLE)?.takeIf { it.isNotBlank() }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w("PluginServiceQuery", "Plugin service unavailable: $component", e)
            null
        } catch (e: SecurityException) {
            Log.w("PluginServiceQuery", "Plugin service metadata denied: $component", e)
            null
        }
    }

    fun discoverPluginComponents(pm: PackageManager, hostPackage: String): List<ComponentName> {
        val intent = Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE)
        val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.ResolveInfoFlags.of(
                (PackageManager.GET_META_DATA or PackageManager.MATCH_DEFAULT_ONLY).toLong()
            )
            pm.queryIntentServices(intent, flags)
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        val out = LinkedHashMap<String, ComponentName>()
        for (ri in resolves) {
            val si = ri.serviceInfo ?: continue
            val pkg = si.packageName
            if (pkg == hostPackage) continue
            if (!allowedBindPermission(si.permission, pkg)) continue
            out.putIfAbsent(pkg, ComponentName(si.packageName, si.name))
        }
        return out.values.toList()
    }
}
