package com.aitech.weartak_civ.presentation

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState

internal const val COMPANION_DETAILS_ROUTE = "companion_details"

internal enum class CompanionBluetoothState { ON, OFF, PERMISSION_REQUIRED, UNAVAILABLE }

@StringRes
internal fun companionStatusLabel(
    enabled: Boolean,
    bluetooth: CompanionBluetoothState,
    connection: BleConnectionState
): Int = when {
    !enabled -> R.string.companion_status_off
    bluetooth == CompanionBluetoothState.PERMISSION_REQUIRED -> R.string.companion_status_permission
    bluetooth == CompanionBluetoothState.UNAVAILABLE -> R.string.companion_status_unavailable
    bluetooth == CompanionBluetoothState.OFF -> R.string.companion_status_bluetooth_off
    connection == BleConnectionState.SERVICES_READY -> R.string.companion_status_connected
    connection == BleConnectionState.CONNECTION_FAILED -> R.string.companion_status_failed
    connection == BleConnectionState.ERROR -> R.string.companion_status_error
    connection == BleConnectionState.DISCONNECTING -> R.string.companion_status_disconnecting
    connection in listOf(BleConnectionState.CONNECTING, BleConnectionState.CONNECTED) -> R.string.companion_status_connecting
    else -> R.string.companion_status_waiting
}

internal fun readCompanionBluetoothState(context: Context): CompanionBluetoothState {
    val permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED })
        return CompanionBluetoothState.PERMISSION_REQUIRED
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return CompanionBluetoothState.UNAVAILABLE
        if (adapter.isEnabled) CompanionBluetoothState.ON else CompanionBluetoothState.OFF
    } catch (error: SecurityException) {
        Log.w("CompanionPluginUi", "Bluetooth permission changed while reading adapter", error)
        CompanionBluetoothState.PERMISSION_REQUIRED
    }
}

@Composable
internal fun companionPluginStatus(enabled: Boolean): String {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as WearTakApplication
    val connection by app.capRepo.bleConnectionState.collectAsState()
    var bluetooth by remember(context) { mutableStateOf(readCompanionBluetoothState(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                bluetooth = readCompanionBluetoothState(context)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) bluetooth = readCompanionBluetoothState(context)
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            context.unregisterReceiver(receiver)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return stringResource(companionStatusLabel(enabled, bluetooth, connection))
}

@Composable
internal fun CompanionPluginChip(enabled: Boolean, status: String, onToggle: (Boolean) -> Unit, onDetails: () -> Unit) {
    WearTAKSplitToggleChip(
        modifier = Modifier.testTag("settings_companion"),
        appIcon = {
            Image(
                painter = painterResource(R.drawable.weartak_companion_plugin),
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp).size(24.dp)
            )
        },
        title = stringResource(R.string.companion_app),
        label = status,
        checked = enabled,
        onCheckedChange = onToggle,
        onClick = onDetails
    )
}

@Composable
internal fun CompanionDetailsPage(
    status: String,
    navController: NavController,
    diagnosticsEnabled: Boolean,
    onDiagnosticsEnabledChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    WearTAKPageWithBackArrow(
        stringResource(R.string.companion_app),
        columnContent = {
            item { Text(status, modifier = Modifier.padding(8.dp)) }
            item {
                Text(stringResource(R.string.companion_connection_guidance),
                    modifier = Modifier.padding(8.dp))
            }
            item {
                Chip(
                    onClick = { openCompanionBluetoothSettings(context) },
                    label = { Text(stringResource(R.string.companion_bluetooth_settings)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                WearTAKToggleChip(
                    testTag = "settings_companion_location_diagnostics",
                    checkedLive = diagnosticsEnabled,
                    onCheckedChange = onDiagnosticsEnabledChanged,
                    title = stringResource(R.string.companion_location_diagnostics)
                )
            }
            item {
                Text(stringResource(R.string.companion_location_diagnostics_guidance),
                    modifier = Modifier.padding(8.dp))
            }
            item {
                Chip(
                    enabled = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.companion_team_lead_priority)) },
                    secondaryLabel = { Text(stringResource(R.string.companion_not_available_yet)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        navController
    )
}

internal fun openCompanionBluetoothSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    } catch (error: ActivityNotFoundException) {
        Log.e("CompanionPluginUi", "Bluetooth settings screen unavailable", error)
        Toast.makeText(context, R.string.companion_bluetooth_settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
