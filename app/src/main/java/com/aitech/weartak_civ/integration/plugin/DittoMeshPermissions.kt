package com.aitech.weartak_civ.integration.plugin

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class DittoMeshPromptGuard {
    private val attemptedState = MutableStateFlow(false)
    val attempted = attemptedState.asStateFlow()

    fun observe(status: DittoMeshStatus?) {
        if (status?.permissions == 1) attemptedState.value = false
    }

    fun claim(status: DittoMeshStatus?): Boolean {
        if (attemptedState.value || status?.canPrompt != true) return false
        attemptedState.value = true
        return true
    }
}

internal object DittoMeshPermissions {
    private const val ACTION = "com.weartak.plugin.ditto.action.REQUEST_MESH_PERMISSIONS_V1"
    private val handler = Handler(Looper.getMainLooper())
    private val guard = DittoMeshPromptGuard()
    val attempted = guard.attempted
    private var reading = false
    private var resumedActivity: WeakReference<ComponentActivity>? = null

    fun observe(activity: ComponentActivity, manager: PluginManager) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                resumedActivity = WeakReference(activity)
                try {
                    manager.dittoReadyConnection.collectLatest { connection ->
                        if (connection != null) {
                            readAndPrompt(activity, manager, connection, 0, force = true)
                            connection.dittoMeshStatus.collect { status ->
                                if (status?.permissions == 1) {
                                    guard.observe(status)
                                }
                                if (!guard.attempted.value && (status == null || status.canPrompt)) {
                                    readAndPrompt(activity, manager, connection, 0, force = false)
                                }
                            }
                        }
                    }
                } finally {
                    if (resumedActivity?.get() === activity) resumedActivity = null
                }
            }
        }
    }

    private fun readAndPrompt(
        activity: ComponentActivity,
        manager: PluginManager,
        connection: PluginConnection,
        retries: Int,
        force: Boolean,
    ) {
        if (reading || (!force && guard.attempted.value) || !eligible(activity, manager, connection)) return
        reading = true
        val started = connection.readFreshDittoMeshStatus { status ->
            reading = false
            val foreground = resumedActivity?.get()
            if (foreground != null && eligible(foreground, manager, connection) && guard.claim(status)) {
                try {
                    foreground.startActivity(Intent(ACTION).setPackage(TrustedCommunityPlugin.PACKAGE))
                } catch (error: ActivityNotFoundException) {
                    Log.w("WearTAK-DittoMesh", "Permission activity unavailable", error)
                } catch (error: SecurityException) {
                    Log.w("WearTAK-DittoMesh", "Permission activity denied", error)
                }
            } else if (status == null && retries < 3 &&
                connection.dittoMeshStatus.value?.canPrompt == true && foreground != null) {
                handler.postDelayed({
                    readAndPrompt(foreground, manager, connection, retries + 1, force)
                }, 500)
            }
        }
        if (!started) {
            reading = false
            if (retries < 3) {
                handler.postDelayed({
                    resumedActivity?.get()?.let { readAndPrompt(it, manager, connection, retries + 1, force) }
                }, 500)
            } else {
                Log.w("WearTAK-DittoMesh", "Permission status unavailable")
            }
        }
    }

    private fun eligible(activity: ComponentActivity, manager: PluginManager, connection: PluginConnection): Boolean =
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            manager.isEnabled(TrustedCommunityPlugin.PACKAGE) && connection.mayRequestDittoMeshPermissions()
}
