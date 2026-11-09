package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.wear.compose.material.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.service.ChargingWirelessDebugging
import com.aitech.weartak_civ.service.ChargingStayAwake
import com.aitech.weartak_civ.service.DebugStayAwakePreferences
import com.aitech.weartak_civ.service.DebugStayAwakeService

@Composable
fun DebugStayAwakeSetting() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { DebugStayAwakePreferences(context) }
    val enabled by remember(preferences) { preferences.enabledFlow }.collectAsState(initial = preferences.enabled)
    val active by DebugStayAwakeService.active.collectAsState()
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) DebugStayAwakeService.restoreIfEnabled(context)
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    WearTAKToggleChip(
        title = stringResource(R.string.debug_stay_awake),
        description = if (enabled && !active) stringResource(R.string.debug_stay_awake_waiting) else "",
        checkedLive = enabled,
        onCheckedChange = { DebugStayAwakeService.setEnabled(context, it) })
}

@Composable
fun ChargingDeveloperSetting() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { DebugStayAwakePreferences(context) }
    var permitted by remember(context) { mutableStateOf(ChargingWirelessDebugging.hasPermission(context)) }
    val enabled by remember(preferences) { preferences.chargingEnabledFlow }
        .collectAsState(initial = preferences.chargingEnabled)
    DisposableEffect(context, lifecycleOwner) {
        fun refreshPermission() {
            permitted = ChargingWirelessDebugging.hasPermission(context)
        }
        refreshPermission()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column {
        WearTAKToggleChip(
            title = stringResource(R.string.debug_while_charging),
            description = stringResource(
                if (permitted) R.string.debug_while_charging_description
                else R.string.debug_while_charging_needs_grant),
            checkedLive = enabled,
            onCheckedChange = { ChargingStayAwake.setEnabled(context, it) })
        if (!permitted) Text(stringResource(R.string.debug_wireless_grant, BuildConfig.APPLICATION_ID))
    }
}
