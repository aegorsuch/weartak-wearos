/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.presentation

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import com.aitech.weartak_civ.OpenVpnPluginApplication
import com.aitech.weartak_civ.OpenVpnPluginRuntime
import com.aitech.weartak_civ.OpenVpnServerRoute
import com.aitech.weartak_civ.data.secure.openvpn.OpenVpnProfileRepository
import com.aitech.weartak_civ.service.openvpn.OpenVpnNativeBridge
import com.aitech.weartak_civ.service.openvpn.OpenVpnProfileRecord
import com.aitech.weartak_civ.service.openvpn.OpenVpnProfileSupport
import com.aitech.weartak_civ.service.openvpn.OpenVpnRuntimeState
import com.aitech.weartak_civ.service.openvpn.OpenVpnService
import com.aitech.weartak_civ.service.openvpn.OpenVpnStatus
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun OpenVpnSettingsPage() {
    val context = LocalContext.current
    val app = context.applicationContext as OpenVpnPluginApplication
    val profiles by app.openVpnProfiles.profiles.collectAsState(initial = emptyList())
    val runtime by OpenVpnService.runtimeState.collectAsState()
    val hostServicesAvailable by OpenVpnPluginRuntime.hostServicesAvailable.collectAsState()
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<OpenVpnServerRoute>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var clearLegacyPrompt by remember { mutableStateOf(false) }
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

    fun refreshServers() {
        scope.launch {
            try {
                val loadedServers = withContext(Dispatchers.IO) {
                    OpenVpnPluginRuntime.configuredServers()
                }
                if (!OpenVpnPluginRuntime.hostServicesAvailable.value) {
                    servers = emptyList()
                    return@launch
                }
                servers = loadedServers
                if (servers.any {
                        it.selectedProfileId != null &&
                            it.selectedPluginPackage != C.PLUGIN_PACKAGE
                    }
                ) {
                    feedback = "A previous OpenVPN route needs profile re-selection"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                servers = emptyList()
                feedback = "Could not load configured TAK servers"
            }
        }
    }
    LaunchedEffect(hostServicesAvailable) {
        if (hostServicesAvailable) refreshServers() else servers = emptyList()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text("OpenVPN", modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = when (runtime.status) {
                OpenVpnStatus.STOPPED -> "Disconnected"
                OpenVpnStatus.CONNECTING -> "Connecting"
                OpenVpnStatus.CONNECTED ->
                    "Connected: ${profiles.firstOrNull { it.id == runtime.profileId }?.name ?: "OpenVPN"}"
                OpenVpnStatus.STOPPING -> "Disconnecting"
                OpenVpnStatus.ERROR -> runtime.error ?: "Connection failed"
            },
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (runtime.status != OpenVpnStatus.STOPPED) {
            Chip(
                onClick = { OpenVpnService.disconnect() },
                label = { ChipLabel("Disconnect") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Chip(
            onClick = { importProfile.launch(arrayOf("*/*")) },
            label = { ChipLabel("Import .ovpn profile") },
            secondaryLabel = { ChipLabel("Encrypted in this plugin") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (feedback != null) Text(feedback!!, modifier = Modifier.padding(vertical = 6.dp))

        Text(
            "Profiles saved by the WearTAK app are not moved automatically. Re-import each source profile here.",
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Chip(
            onClick = { clearLegacyPrompt = true },
            label = { ChipLabel("Clear old app-stored profiles") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (profiles.isEmpty()) Text("No profiles imported")
        profiles.forEach { profile ->
            OpenVpnProfileEditor(
                profile = profile,
                runtime = runtime,
                onSaveCredentials = { username, password ->
                    scope.launch {
                        try {
                            val validation = validateCredentials(profile, username, password)
                            if (validation != OpenVpnNativeBridge.Result.OK) {
                                feedback = validationMessage(validation)
                            } else {
                                app.openVpnProfiles.saveCredentials(profile.id, username, password)
                                feedback = "Credentials saved"
                            }
                        } catch (_: Exception) {
                            feedback = "Could not save credentials"
                        }
                    }
                },
                onConnect = { username, password ->
                    scope.launch {
                        try {
                            if (profile.requiresCredentials) {
                                val validation = validateCredentials(profile, username, password)
                                if (validation != OpenVpnNativeBridge.Result.OK) {
                                    feedback = validationMessage(validation)
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
                    } else if (servers.any { it.selectedProfileId == profile.id }) {
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

        Text("TAK server routing", modifier = Modifier.padding(top = 12.dp))
        if (!hostServicesAvailable) {
            Text("WearTAK host transport unavailable. Routed TAK connections fail closed.")
        } else {
            Chip(
                onClick = ::refreshServers,
                label = { ChipLabel("Refresh server list") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (servers.isEmpty()) {
                Text(feedback ?: "No configured TAK servers")
            }
            servers.forEach { server ->
                Text(server.name, modifier = Modifier.padding(top = 8.dp))
                Chip(
                    onClick = {
                        selectRoute(server, null, scope) { message ->
                            feedback = message
                            refreshServers()
                        }
                    },
                    label = {
                        ChipLabel(if (server.selectedProfileId == null) "Direct (selected)" else "Use direct")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                profiles.forEach { profile ->
                    Chip(
                        onClick = {
                            selectRoute(server, profile.id, scope) { message ->
                                feedback = message
                                refreshServers()
                            }
                        },
                        label = {
                            ChipLabel(
                                if (server.selectedPluginPackage == C.PLUGIN_PACKAGE &&
                                    server.selectedProfileId == profile.id
                                ) "${profile.name} (OpenVPN selected)" else profile.name,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Text(
            "Only explicitly selected TAK server TCP uses this tunnel. Host retains TAK TLS, identity, trust, and socket ownership.",
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }

    if (clearLegacyPrompt) {
        AlertDialog(
            onDismissRequest = { clearLegacyPrompt = false },
            title = { Text("Clear old profiles?") },
            text = { Text("This permanently removes the encrypted OpenVPN profiles stored by the WearTAK app. They are not copied into this plugin.") },
            confirmButton = {
                Button(onClick = {
                    clearLegacyPrompt = false
                    scope.launch {
                        val response = withContext(Dispatchers.IO) {
                            OpenVpnPluginRuntime.hostServices?.clearLegacyProfiles()
                        }
                        feedback = when (response?.getString(C.KEY_STATUS)) {
                            C.OK -> "Old app-stored profiles cleared"
                            C.LOCKED -> "Host policy refused profile cleanup"
                            else -> "WearTAK host unavailable"
                        }
                    }
                }) { Text("Clear profiles") }
            },
            dismissButton = {
                Button(onClick = { clearLegacyPrompt = false }) { Text("Keep profiles") }
            },
        )
    }
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
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Chip(
                onClick = { onSaveCredentials(username, password) },
                label = { ChipLabel("Save credentials") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (runtime.profileId == profile.id && runtime.status == OpenVpnStatus.CONNECTED) {
            Text("Active tunnel")
        } else {
            Chip(
                onClick = { onConnect(username, password) },
                label = { ChipLabel("Connect") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Chip(onClick = onDelete, label = { ChipLabel("Delete profile") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChipLabel(text: String) {
    Text(text, color = Color.Black)
}

private fun selectRoute(
    server: OpenVpnServerRoute,
    profileId: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onComplete: (String?) -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        val response = OpenVpnPluginRuntime.hostServices?.selectServerProfile(server.serverId, profileId)
        val status = response?.getString(C.KEY_STATUS)
        withContext(Dispatchers.Main) {
            onComplete(
                when (status) {
                    C.OK -> "TAK server route updated"
                    C.LOCKED -> "Server routing is managed or locked"
                    C.NOT_FOUND -> "TAK server no longer exists"
                    C.UNAUTHORIZED -> "WearTAK refused OpenVPN route selection"
                    else -> "WearTAK host unavailable"
                },
            )
        }
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
