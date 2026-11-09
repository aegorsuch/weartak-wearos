package com.aitech.weartak_civ.integration.plugin

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.Log

internal object PluginActivityLauncher {
    fun launch(context: Context, packageName: String): Boolean {
        return try {
            // Resolve by package even when unloaded plugins have no cached capabilities.
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                Log.d(TAG, "No launchable activity for plugin package=$packageName")
                false
            } else {
                if (context is android.app.Activity) {
                    // Keep host in the foreground task so explicit plugin review can return to it.
                    intent.flags = intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                }
                context.startActivity(intent)
                (context.applicationContext as? com.aitech.weartak_civ.WearTakApplication)
                    ?.pluginManager?.connectionFor(packageName)?.allowCertificateReviewFromWearerLaunch()
                true
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Plugin activity no longer available package=$packageName", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Plugin activity launch denied package=$packageName", e)
            false
        }
    }

    private const val TAG = "WearTAK-PluginActivity"
}
