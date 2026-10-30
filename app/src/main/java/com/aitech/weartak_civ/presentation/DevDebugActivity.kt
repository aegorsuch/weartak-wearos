/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.aitech.weartak_civ.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.service.LocationDiagnosticSample
import com.aitech.weartak_civ.service.AppDiagnostics
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.IOException
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEBUG_SERVER_PORT = 8000

internal data class DebugDisplayInfo(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val gitBranch: String,
    val gitCommit: String,
    val transport: String,
    val ssid: String,
    val ipAddress: String,
    val port: Int
) {
    val debugUrl: String
        get() = "http://$ipAddress:$port/debug"

    val canShowQrCode: Boolean
        get() = ipAddress.isNotBlank() &&
                ipAddress != "N/A" &&
                ipAddress != "Unavailable"
}

private data class DebugRow(
    val label: String,
    val value: String
)

internal enum class DeveloperEntryId {
    BUILD, DIAGNOSTICS, DIAGNOSTICS_LOGS, GIT, IP, LOCATION, PORT, QR, SSID, STAY_AWAKE, TRANSPORT, VERSION, VERSION_CODE,
    WHILE_CHARGING, NETWORK_CHECKLIST
}

internal data class DeveloperEntry(val id: DeveloperEntryId, val label: String, val value: String = "")

internal fun sortedDeveloperEntries(entries: List<DeveloperEntry>, locale: Locale): List<DeveloperEntry> {
    val collator = Collator.getInstance(locale)
    fun group(entry: DeveloperEntry) =
        if (entry.id in setOf(DeveloperEntryId.DIAGNOSTICS, DeveloperEntryId.NETWORK_CHECKLIST,
                DeveloperEntryId.STAY_AWAKE)) 0 else 1
    val charging = entries.filter { it.id == DeveloperEntryId.WHILE_CHARGING }
    val sorted = entries.filterNot { it.id == DeveloperEntryId.WHILE_CHARGING }.sortedWith { left, right ->
        group(left).compareTo(group(right)).takeIf { it != 0 }
            ?: collator.compare(left.label, right.label)
    }.toMutableList()
    val manualIndex = sorted.indexOfFirst { it.id == DeveloperEntryId.STAY_AWAKE }
    sorted.addAll(if (manualIndex >= 0) manualIndex + 1 else sorted.size, charging)
    return sorted
}

@Composable
fun DebugScreen(
    navController: NavController
) {
    DebugScreen(
        dataRepository = dataRepository,
        onExit = {
            navController.navigate("settings_screen") {
                popUpTo("debug_tools") {
                    inclusive = true
                }

                launchSingleTop = true
            }
        }
    )
}

@Composable
private fun DebugScreen(
    dataRepository: DataRepository,
    onExit: () -> Unit
) {
    val developerEnabled by dataRepository.getEnableWearTakDevMode.collectAsState(initial = true)
    if (!developerEnabled) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    var showQrCode by remember {
        mutableStateOf(false)
    }
    var showLocationDiagnostics by remember { mutableStateOf(false) }
    var showAppDiagnostics by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appDiagnostics = (context.applicationContext as WearTakApplication).diagnostics
    val appDiagnosticsEnabled by appDiagnostics.enabled.collectAsStateWithLifecycle()
    val appDiagnosticsFailed by appDiagnostics.storageFailed.collectAsStateWithLifecycle()
    val checklistEnabled by dataRepository.networkPreferencesChecklistEnabled.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(appDiagnosticsFailed) {
        if (appDiagnosticsFailed)
            Toast.makeText(context, R.string.app_diagnostics_failed, Toast.LENGTH_LONG).show()
    }
    val diagnosticsEnabled by dataRepository.getBondedLocationDiagnosticsEnabled.collectAsState(initial = false)
    val diagnostics = (context.applicationContext as WearTakApplication).locationDiagnostics
    val watchSample by diagnostics.watch.collectAsState()
    val phoneSample by diagnostics.phone.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(diagnostics.exportJson().toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Unable to open the selected export destination")
            exportStatus = "Export complete"
        } catch (error: IOException) {
            exportStatus = "Export failed: ${error.message ?: "storage error"}"
            Toast.makeText(context, exportStatus, Toast.LENGTH_LONG).show()
        }
    }

//    val networkSnapshot by dataRepository
//        .networkSnapshot
//        .collectAsState()
    val networkSnapshot by NetworkManager
        .getInstance()
        .networkSnapshot
        .collectAsState()

    val wearTakInfo = dataRepository.wearTakInfo

    val debugInfo = DebugDisplayInfo(
        versionName = wearTakInfo.versionNumber,
        versionCode = wearTakInfo.versionCode,
        buildType = wearTakInfo.buildType,
        gitBranch = wearTakInfo.gitBranch,
        gitCommit = wearTakInfo.gitCommit,
        transport = networkSnapshot.type.name,
        ssid = networkSnapshot.ssid ?: "N/A",
        ipAddress = networkSnapshot.ip ?: "N/A",
        port = DEBUG_SERVER_PORT
    )

    fun navigateBack() {
        if (showAppDiagnostics) {
            showAppDiagnostics = false
        } else if (showLocationDiagnostics) {
            showLocationDiagnostics = false
        } else if (showQrCode) {
            showQrCode = false
        } else {
            onExit()
        }
    }

    BackHandler {
        navigateBack()
    }

    Scaffold {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (showAppDiagnostics) {
                AppDiagnosticsPage(
                    diagnostics = appDiagnostics,
                    onBack = { showAppDiagnostics = false }
                )
            } else if (showLocationDiagnostics) {
                DebugLocationDiagnosticsPage(
                    watch = watchSample,
                    phone = phoneSample,
                    enabled = diagnosticsEnabled,
                    exportStatus = exportStatus,
                    onExport = { exportLauncher.launch("wear-tak-location-diagnostics.json") },
                    onClear = {
                        diagnostics.clear()
                        exportStatus = "Diagnostics cleared"
                    },
                    onMarkEntry = { diagnostics.eventMarker("entry") },
                    onMarkExit = { diagnostics.eventMarker("exit") },
                    onBack = { showLocationDiagnostics = false }
                )
            } else if (showQrCode) {
                DebugQrPage(
                    debugInfo = debugInfo,
                    onBack = {
                        showQrCode = false
                    }
                )
            } else {
                DebugInformationPage(
                    debugInfo = debugInfo,
                    onBack = { navigateBack() },
                    onShowQrCode = {
                        showQrCode = true
                    },
                    onShowLocationDiagnostics = { showLocationDiagnostics = true },
                    onShowDiagnostics = { showAppDiagnostics = true },
                    diagnosticsEnabled = appDiagnosticsEnabled,
                    networkChecklistEnabled = checklistEnabled,
                    onNetworkChecklistEnabledChange = { enabled ->
                        scope.launch {
                            try {
                                dataRepository.storeNetworkPreferencesChecklistEnabled(enabled)
                            } catch (error: IOException) {
                                android.util.Log.e("DevDebugActivity", "Could not save network checklist preference", error)
                                Toast.makeText(context, R.string.network_preferences_checklist_save_failed, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDiagnosticsEnabledChange = { value ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { appDiagnostics.setEnabled(value) }
                            } catch (error: IOException) {
                                Toast.makeText(context, R.string.app_diagnostics_failed, Toast.LENGTH_LONG).show()
                            } catch (error: SecurityException) {
                                Toast.makeText(context, R.string.app_diagnostics_failed, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun DebugInformationPage(
    debugInfo: DebugDisplayInfo,
    onBack: () -> Unit,
    onShowQrCode: () -> Unit,
    onShowLocationDiagnostics: () -> Unit,
    onShowDiagnostics: () -> Unit = {},
    diagnosticsEnabled: Boolean = false,
    onDiagnosticsEnabledChange: (Boolean) -> Unit = {},
    networkChecklistEnabled: Boolean = false,
    onNetworkChecklistEnabledChange: (Boolean) -> Unit = {}
) {
    val entries = sortedDeveloperEntries(listOf(
        DeveloperEntry(DeveloperEntryId.BUILD, stringResource(R.string.debug_build), debugInfo.buildType),
        DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, stringResource(R.string.app_diagnostics)),
        DeveloperEntry(DeveloperEntryId.NETWORK_CHECKLIST, stringResource(R.string.network_preferences_checklist)),
        DeveloperEntry(DeveloperEntryId.DIAGNOSTICS_LOGS, stringResource(R.string.app_diagnostics_logs)),
        DeveloperEntry(DeveloperEntryId.GIT, stringResource(R.string.debug_git), "${debugInfo.gitBranch}@${debugInfo.gitCommit}"),
        DeveloperEntry(DeveloperEntryId.IP, stringResource(R.string.debug_ip), debugInfo.ipAddress),
        DeveloperEntry(DeveloperEntryId.LOCATION, stringResource(R.string.companion_location_diagnostics)),
        DeveloperEntry(DeveloperEntryId.PORT, stringResource(R.string.debug_port), debugInfo.port.toString()),
        DeveloperEntry(DeveloperEntryId.QR, stringResource(
            if (debugInfo.canShowQrCode) R.string.debug_show_qr else R.string.debug_no_ip)),
        DeveloperEntry(DeveloperEntryId.SSID, stringResource(R.string.debug_ssid), debugInfo.ssid),
        DeveloperEntry(DeveloperEntryId.STAY_AWAKE, stringResource(R.string.debug_stay_awake)),
        DeveloperEntry(DeveloperEntryId.WHILE_CHARGING, stringResource(R.string.debug_while_charging)),
        DeveloperEntry(DeveloperEntryId.TRANSPORT, stringResource(R.string.debug_transport), debugInfo.transport),
        DeveloperEntry(DeveloperEntryId.VERSION, stringResource(R.string.debug_version), debugInfo.versionName),
        DeveloperEntry(DeveloperEntryId.VERSION_CODE, stringResource(R.string.debug_version_code), debugInfo.versionCode.toString())
    ), LocalConfiguration.current.locales[0])

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 40.dp,
            bottom = 26.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.developer_preferences),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary
            )
        }

        items(entries, key = { it.id }) { entry ->
            when (entry.id) {
                DeveloperEntryId.STAY_AWAKE -> DebugStayAwakeSetting()
                DeveloperEntryId.NETWORK_CHECKLIST -> WearTAKToggleChip(
                    title = entry.label,
                    checkedLive = networkChecklistEnabled,
                    onCheckedChange = onNetworkChecklistEnabledChange
                )
                DeveloperEntryId.WHILE_CHARGING -> ChargingDeveloperSetting()
                DeveloperEntryId.DIAGNOSTICS -> WearTAKToggleChip(
                    title = entry.label,
                    checkedLive = diagnosticsEnabled,
                    onCheckedChange = onDiagnosticsEnabledChange
                )
                DeveloperEntryId.DIAGNOSTICS_LOGS, DeveloperEntryId.LOCATION, DeveloperEntryId.QR ->
                    Button(
                        onClick = when (entry.id) {
                            DeveloperEntryId.DIAGNOSTICS_LOGS -> onShowDiagnostics
                            DeveloperEntryId.LOCATION -> onShowLocationDiagnostics
                            else -> onShowQrCode
                        },
                        enabled = entry.id != DeveloperEntryId.QR || debugInfo.canShowQrCode,
                        modifier = Modifier.size(width = 148.dp, height = 48.dp)
                    ) { Text(entry.label, textAlign = TextAlign.Center, fontSize = 11.sp) }
                else -> DebugInformationRow(DebugRow(entry.label, entry.value))
            }
        }
        item {
            WearTAKBackButton(onClick = onBack)
        }
    }
}

@Composable
private fun AppDiagnosticsPage(diagnostics: AppDiagnostics, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed by diagnostics.storageFailed.collectAsState()
    var statusResource by remember { mutableStateOf<Int?>(null) }
    val status = statusResource?.let { stringResource(it) }.orEmpty()
    val failedMessage = stringResource(R.string.app_diagnostics_failed)
    val noPickerMessage = stringResource(R.string.app_diagnostics_no_picker)
    var busy by remember { mutableStateOf(false) }
    fun action(success: Int, exclusive: Boolean = true, block: suspend () -> Unit) {
        if (exclusive && busy) return
        if (exclusive) busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                statusResource = success
            } catch (error: IOException) {
                statusResource = R.string.app_diagnostics_failed
                Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
            } catch (error: SecurityException) {
                statusResource = R.string.app_diagnostics_failed
                Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
            } finally {
                if (exclusive) busy = false
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) action(R.string.app_diagnostics_exported) {
            val json = diagnostics.exportJson()
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Cannot open diagnostics destination")
        }
    }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        item {
            Text(stringResource(R.string.app_diagnostics_logs), fontSize = 15.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        if (failed || status.isNotEmpty()) item {
            Text(if (failed) stringResource(R.string.app_diagnostics_failed) else status,
                fontSize = 11.sp, textAlign = TextAlign.Center)
        }
        item {
            Button(onClick = {
                try {
                    export.launch("wear-tak-diagnostics.json")
                } catch (error: android.content.ActivityNotFoundException) {
                    statusResource = R.string.app_diagnostics_no_picker
                    Toast.makeText(context, noPickerMessage, Toast.LENGTH_LONG).show()
                } catch (error: SecurityException) {
                    statusResource = R.string.app_diagnostics_failed
                    Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
                }
            }, enabled = !busy, modifier = Modifier.size(width = 148.dp, height = 48.dp)) {
                Text(stringResource(R.string.app_diagnostics_export), textAlign = TextAlign.Center, fontSize = 11.sp)
            }
        }
        item {
            Button(onClick = { action(R.string.app_diagnostics_cleared) { diagnostics.clear() } }, enabled = !busy,
                modifier = Modifier.size(width = 148.dp, height = 48.dp)) {
                Text(stringResource(R.string.app_diagnostics_clear), textAlign = TextAlign.Center, fontSize = 11.sp)
            }
        }
        item { WearTAKBackButton(onClick = onBack) }
    }
}

@Composable
private fun DebugLocationDiagnosticsPage(
    watch: LocationDiagnosticSample,
    phone: LocationDiagnosticSample,
    enabled: Boolean,
    exportStatus: String,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onMarkEntry: () -> Unit,
    onMarkExit: () -> Unit,
    onBack: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        item {
            Text(
                stringResource(R.string.companion_location_diagnostics),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary
            )
        }
        item { DiagnosticSummary("Watch", watch) }
        item { DiagnosticSummary("Phone", phone) }
        item {
            Text(
                stringResource(
                    if (enabled) R.string.companion_location_diagnostics_enabled_hint
                    else R.string.companion_location_diagnostics_disabled_hint
                ),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface
            )
        }
        item {
            Button(onClick = onMarkEntry, modifier = Modifier.size(width = 112.dp, height = 40.dp)) {
                Text("Mark Entry", fontSize = 11.sp)
            }
        }
        item {
            Button(onClick = onMarkExit, modifier = Modifier.size(width = 112.dp, height = 40.dp)) {
                Text("Mark Exit", fontSize = 11.sp)
            }
        }
        item {
            Button(onClick = onExport, modifier = Modifier.size(width = 112.dp, height = 40.dp)) {
                Text("Export", fontSize = 11.sp)
            }
        }
        item {
            Button(onClick = onClear, modifier = Modifier.size(width = 112.dp, height = 40.dp)) {
                Text("Clear", fontSize = 11.sp)
            }
        }
        if (exportStatus.isNotBlank()) item {
            Text(exportStatus, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        item { WearTAKBackButton(onClick = onBack) }
    }
}

@Composable
internal fun DiagnosticSummary(label: String, sample: LocationDiagnosticSample) {
    val ageText = sample.ageMillis?.let { "${it / 1000}s" } ?: "unknown"
    val accuracyText = sample.horizontalAccuracyMeters?.let { "%.1f m".format(it) } ?: "unknown"
    val satellitesText = if (sample.satelliteStatusSupported == true) {
        "${sample.usedSatelliteCount ?: 0}/${sample.satelliteCount ?: 0} used"
    } else "unknown"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$label: ${sample.quality()}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary
        )
        sample.source?.let {
            Text("Source: $it", fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        Text(
            "Age $ageText; accuracy $accuracyText; $satellitesText",
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurface
        )
        Text(sample.reason(), fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DebugInformationRow(
    row: DebugRow
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = row.label,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = row.value,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DebugQrPage(
    debugInfo: DebugDisplayInfo,
    onBack: () -> Unit
) {
    val qrBitmap = remember(debugInfo.debugUrl) {
        generateQrCode(
            content = debugInfo.debugUrl,
            sizePx = 500
        )
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 44.dp,
            bottom = 30.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        item {
            Text(
                text = "Scan to open",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        item {
            Box(
                modifier = Modifier
                    .size(172.dp)
                    .background(
                        color = Color.White,
                        shape = CircleShape
                    )
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "WearTAK debug server QR code",
                    modifier = Modifier.size(156.dp)
                )
            }
        }

        item {
            Text(
                text = "${debugInfo.ipAddress}:${debugInfo.port}",
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface.copy(
                    alpha = 0.75f
                )
            )
        }

        item {
            WearTAKBackButton(onClick = onBack)
        }
    }
}

private fun generateQrCode(
    content: String,
    sizePx: Int
): Bitmap {
    require(content.isNotBlank()) {
        "QR code content cannot be blank"
    }

    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.MARGIN to 1
        )
    )

    val pixels = IntArray(
        matrix.width * matrix.height
    )

    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] =
                if (matrix[x, y]) {
                    AndroidColor.BLACK
                } else {
                    AndroidColor.WHITE
                }
        }
    }

    return Bitmap.createBitmap(
        pixels,
        matrix.width,
        matrix.height,
        Bitmap.Config.ARGB_8888
    )
}

@Preview(
    device = Devices.WEAR_OS_SMALL_ROUND,
    showBackground = true
)

@Composable
private fun PreviewDebugInformationPage() {
    WearTAKCIVTheme {
        DebugInformationPage(
            debugInfo = DebugDisplayInfo(
                versionName = "5.7.5",
                versionCode = 60,
                buildType = "release",
                gitBranch = "develop",
                gitCommit = "abc1234",
                transport = "WIFI",
                ssid = "Ascent-Lab",
                ipAddress = "192.168.1.84",
                port = DEBUG_SERVER_PORT
            ),
            onBack = {},
            onShowQrCode = {},
            onShowLocationDiagnostics = {}
        )
    }
}