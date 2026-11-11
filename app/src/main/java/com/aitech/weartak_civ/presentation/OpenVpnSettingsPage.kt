/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.service.openvpn.OpenVpnNativeBridge
import com.aitech.weartak_civ.service.openvpn.OpenVpnProfileRecord
import com.aitech.weartak_civ.service.openvpn.OpenVpnProfileSupport
import com.aitech.weartak_civ.service.openvpn.OpenVpnRuntimeState
import com.aitech.weartak_civ.service.openvpn.OpenVpnService
import com.aitech.weartak_civ.service.openvpn.OpenVpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

@Composable
fun OpenVpnSettingsPage(
    navController: NavController,
    settingsVm: WearTAKSettingsViewModel,
    servers: List<DataRepository.TakServerItem>,
) {
    val context = LocalContext.current
    val app = context.applicationContext as WearTakApplication
    val profiles by app.openVpnProfiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val runtime by OpenVpnService.runtimeState.collectAsStateWithLifecycle()
    val phoneManaged by settingsVm.savedPhoneManagedSettings.collectAsStateWithLifecycle()
    val networkLocked by settingsVm.savedNetworkPreferencesLocked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<String?>(null) }
    val importProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                var source: ByteArray? = null
                var nativeConfig: ByteArray? = null
                try {
                    source = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use(::readBounded)
                            ?: throw IOException("Unable to read selected profile")
                    }
                    val parsed = OpenVpnProfileSupport.parse(
                        uri.lastPathSegment?.substringAfterLast(':') ?: "OpenVPN profile",
                        source,
                    )
                    val checkRecord = OpenVpnProfileRecord(
                        id = "validation",
                        name = parsed.name,
                        config = parsed.config,
                        requiresCredentials = parsed.requiresCredentials,
                        username = if (parsed.requiresCredentials) "validation-user" else null,
                        password = if (parsed.requiresCredentials) "validation-password" else null,
                    )
                    nativeConfig = OpenVpnProfileSupport.nativeConfig(
                        checkRecord, checkRecord.username, checkRecord.password,
                    )
                    val result = withContext(Dispatchers.IO) {
                        OpenVpnNativeBridge.validateProfile(requireNotNull(nativeConfig))
                    }
                    if (result != OpenVpnNativeBridge.Result.OK) {
                        throw IOException(validationMessage(result))
                    }
                    app.openVpnProfiles.import(parsed)
                    feedback = "Profile imported"
                } catch (error: Exception) {
                    feedback = error.message?.takeIf {
                        it.startsWith("Profile ") || it.startsWith("OpenVPN ")
                    } ?: "Could not import profile"
                } finally {
                    source?.fill(0)
                    nativeConfig?.fill(0)
                }
            }
        }
    }

    WearTAKPageWithBackArrow(
        title = "OpenVPN",
        columnContent = {
            item {
                Chip(
                    onClick = { importProfile.launch(arrayOf("*/*")) },
                    label = { Text("Import .ovpn profile") },
                    secondaryLabel = { Text("Encrypted on watch") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = when (runtime.status) {
                        OpenVpnStatus.STOPPED -> "Disconnected"
                        OpenVpnStatus.CONNECTING -> "Connecting"
                        OpenVpnStatus.CONNECTED -> "Connected: ${profiles.firstOrNull { it.id == runtime.profileId }?.name ?: "OpenVPN"}"
                        OpenVpnStatus.STOPPING -> "Disconnecting"
                        OpenVpnStatus.ERROR -> runtime.error ?: "Connection failed"
                    },
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            if (runtime.status == OpenVpnStatus.CONNECTED ||
                runtime.status == OpenVpnStatus.CONNECTING ||
                runtime.status == OpenVpnStatus.STOPPING ||
                runtime.status == OpenVpnStatus.ERROR) {
                item {
                    Chip(
                        onClick = { OpenVpnService.disconnect(context) },
                        label = { Text("Disconnect") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (feedback != null) {
                item { Text(feedback!!, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            if (profiles.isEmpty()) {
                item { Text("No profiles imported") }
            }
            profiles.forEach { profile ->
                item(key = "profile:${profile.id}") {
                    OpenVpnProfileEditor(
                        profile = profile,
                        runtime = runtime,
                        onSaveCredentials = { username, password ->
                            scope.launch {
                                try {
                                    val check = validateCredentials(profile, username, password)
                                    if (check != OpenVpnNativeBridge.Result.OK) {
                                        feedback = validationMessage(check)
                                        return@launch
                                    }
                                    app.openVpnProfiles.saveCredentials(profile.id, username, password)
                                    feedback = "Credentials saved"
                                } catch (_: Exception) {
                                    feedback = "Could not save credentials"
                                }
                            }
                        },
                        onConnect = { username, password ->
                            scope.launch {
                                try {
                                    if (profile.requiresCredentials) {
                                        val check = validateCredentials(profile, username, password)
                                        if (check != OpenVpnNativeBridge.Result.OK) {
                                            feedback = validationMessage(check)
                                            return@launch
                                        }
                                        app.openVpnProfiles.saveCredentials(profile.id, username, password)
                                    }
                                    OpenVpnService.connect(context, profile.id)
                                    feedback = null
                                } catch (_: Exception) {
                                    feedback = "Could not start OpenVPN service"
                                }
                            }
                        },
                        onDelete = {
                            if (runtime.profileId == profile.id && runtime.status != OpenVpnStatus.STOPPED) {
                                feedback = "Disconnect profile before deleting"
                            } else if (servers.any { it.openVpnProfileId == profile.id }) {
                                feedback = "Remove profile from TAK servers first"
                            } else {
                                scope.launch {
                                    try {
                                        app.openVpnProfiles.delete(profile.id)
                                        feedback = "Profile deleted"
                                    } catch (_: Exception) {
                                        feedback = "Could not delete profile"
                                    }
                                }
                            }
                        },
                    )
                }
            }
            if (servers.isNotEmpty()) {
                item { Text("TAK server transport", modifier = Modifier.padding(top = 8.dp)) }
            }
            if (phoneManaged || networkLocked) {
                item { Text("TAK server routing is managed or locked") }
            } else servers.forEach { server ->
                val serverId = server.serverId
                item(key = "server:${serverId ?: server.address}") {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(server.name)
                        Chip(
                            onClick = {
                                serverId?.let { settingsVm.setTakServerOpenVpnProfile(it, null) }
                            },
                            label = { Text(if (server.openVpnProfileId == null) "Direct (selected)" else "Use direct") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        profiles.forEach { profile ->
                            Chip(
                                onClick = {
                                    serverId?.let {
                                        settingsVm.setTakServerOpenVpnProfile(it, profile.id)
                                    }
                                },
                                label = {
                                    Text(
                                        if (server.openVpnProfileId == profile.id)
                                            "${profile.name} (OpenVPN selected)"
                                        else profile.name,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Only selected TAK server TCP is tunneled. DNS uses Android network; target must resolve to IPv4.",
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        },
        navController = navController,
        screenTag = "openvpn_settings",
    )
}

@Composable
private fun OpenVpnProfileEditor(
    profile: OpenVpnProfileRecord,
    runtime: OpenVpnRuntimeState,
    onSaveCredentials: (String, String) -> Unit,
    onConnect: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var username by remember(profile.id) { mutableStateOf(profile.username.orEmpty()) }
    var password by remember(profile.id) { mutableStateOf(profile.password.orEmpty()) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(profile.name)
        if (profile.requiresCredentials) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(256) },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(1024) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Chip(
                onClick = { onSaveCredentials(username, password) },
                label = { Text("Save credentials") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (runtime.profileId == profile.id && runtime.status == OpenVpnStatus.CONNECTED) {
            Text("Active tunnel")
        } else {
            Chip(
                onClick = { onConnect(username, password) },
                label = { Text("Connect") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Chip(
            onClick = onDelete,
            label = { Text("Delete profile") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private suspend fun validateCredentials(
    profile: OpenVpnProfileRecord,
    username: String,
    password: String,
): Int = withContext(Dispatchers.IO) {
    val config = OpenVpnProfileSupport.nativeConfig(profile, username, password)
    try {
        OpenVpnNativeBridge.validateProfile(config)
    } finally {
        config.fill(0)
    }
}

private fun readBounded(input: java.io.InputStream): ByteArray {
    val temporary = ByteArray(OpenVpnNativeBridge.MAX_PROFILE_BYTES + 1)
    var size = 0
    try {
        while (true) {
            val count = input.read(temporary, size, temporary.size - size)
            if (count < 0) break
            size += count
            if (size > OpenVpnNativeBridge.MAX_PROFILE_BYTES) {
                throw IOException("Profile exceeds 256 KiB")
            }
        }
        return temporary.copyOf(size)
    } finally {
        temporary.fill(0)
    }
}

private fun validationMessage(result: Int) = when (result) {
    OpenVpnNativeBridge.Result.INVALID_PROFILE -> "Profile is invalid or unsupported"
    OpenVpnNativeBridge.Result.UNSUPPORTED_AUTHENTICATION -> "Profile authentication is unsupported"
    else -> "Could not validate OpenVPN profile"
}
