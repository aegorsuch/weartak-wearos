package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication

/**
 * Plugin install/update hook.
 *
 * The host performs an initial scan at app startup; this receiver makes newly installed or
 * updated plugins visible without requiring a full host restart.
 */
class PluginPackageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "received package event: action=$action data=${intent.data}")
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) {
            return
        }
        if (action == Intent.ACTION_PACKAGE_ADDED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return
        }
        val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        val app = context.applicationContext as? WearTakApplication ?: return
        val pendingResult = goAsync()
        Log.i(TAG, "package changed: $pkg - refreshing while preserving enabled state")
        app.pluginRepository.onPackageChanged(pkg)

        // Android can deliver the package broadcast before the newly installed service is
        // queryable. Retry after package publication while keeping the receiver alive.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                Log.i(TAG, "retrying plugin discovery after package change: $pkg")
                app.pluginRepository.onPackageChanged(pkg)
            } finally {
                pendingResult.finish()
            }
        }, DISCOVERY_RETRY_DELAY_MS)
    }

    companion object {
        private const val TAG = "CEP-PluginPkgChanged"
        private const val DISCOVERY_RETRY_DELAY_MS = 750L
    }
}
