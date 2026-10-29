package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.integration.plugin.HostedPluginMainUiResult
import com.aitech.weartak_civ.integration.plugin.PluginUI
import com.aitech.weartak_civ.integration.plugin.PluginViewModel

@Composable
internal fun HostedPluginMainUiPage(
    pluginId: String,
    plugins: List<PluginUI>,
    pluginVm: PluginViewModel,
    navController: NavController,
) {
    val plugin = plugins.firstOrNull { it.pluginId == pluginId }
    val hostedUiByPlugin by pluginVm.hostedMainUi.collectAsStateWithLifecycle()
    val changeRevisions by pluginVm.mainUiChangeRevisions.collectAsStateWithLifecycle()
    val result = hostedUiByPlugin[pluginId]

    LaunchedEffect(pluginId, plugin?.isLoaded, changeRevisions[pluginId]) {
        if (plugin?.isLoaded == true) {
            pluginVm.refreshHostedMainUi(pluginId)
        }
    }

    WearTAKPageWithBackArrow(
        title = plugin?.title ?: pluginId,
        navController = navController,
        columnContent = {
            when {
                plugin == null -> item {
                    Text(stringResource(R.string.plugin_not_installed))
                }
                !plugin.isLoaded -> item {
                    Chip(
                        onClick = { pluginVm.loadPlugin(plugin.pluginId) },
                        label = { Text(stringResource(R.string.plugin_page_load)) },
                    )
                }
                result == null -> item {
                    Text(stringResource(R.string.plugin_page_loading))
                }
                else -> when (result) {
                    HostedPluginMainUiResult.NotConnected -> item {
                        Text(stringResource(R.string.plugin_not_connected))
                    }
                    HostedPluginMainUiResult.Unsupported -> item {
                        Text(stringResource(R.string.plugin_hosted_ui_unsupported))
                    }
                    is HostedPluginMainUiResult.Failed -> item {
                        Text(result.message)
                    }
                    is HostedPluginMainUiResult.Ready -> {
                        if (result.cards.isEmpty()) {
                            item { Text(stringResource(R.string.plugin_page_empty)) }
                        }
                        result.cards.forEach { card ->
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                ) {
                                    Text(card.title)
                                    card.subtitle?.takeIf { it.isNotBlank() }?.let {
                                        Text(it)
                                    }
                                    card.action?.let { action ->
                                        Chip(
                                            onClick = {
                                                pluginVm.invokeHostedMainUiAction(
                                                    pluginId,
                                                    card.cardId,
                                                )
                                            },
                                            enabled = action.enabled,
                                            label = { Text(action.actionLabel) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
