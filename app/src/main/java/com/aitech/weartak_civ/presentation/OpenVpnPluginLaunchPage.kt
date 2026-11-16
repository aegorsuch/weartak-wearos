package com.aitech.weartak_civ.presentation

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract

@Composable
fun OpenVpnPluginLaunchPage(navController: NavController) {
    val context = LocalContext.current
    var pluginMissing by remember { mutableStateOf(false) }
    WearTAKPageWithBackArrow(
        title = "OpenVPN plugin",
        columnContent = {
            item {
                Text("Profile import, encrypted storage, and tunnel controls are provided by the optional plugin.")
            }
            item {
                Chip(
                    onClick = {
                        val intent = context.packageManager
                            .getLaunchIntentForPackage(OpenVpnHostContract.PLUGIN_PACKAGE)
                        if (intent == null) {
                            pluginMissing = true
                        } else {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    label = { Text("Open OpenVPN plugin") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (pluginMissing) {
                item { Text("Install WearTAK OpenVPN plugin first") }
            }
        },
        navController = navController,
        screenTag = "openvpn_plugin",
    )
}
