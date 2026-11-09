package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Icon
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.integration.plugin.HostedPluginMainUiResult
import com.aitech.weartak_civ.integration.plugin.HostedPluginMainUiCard
import com.aitech.weartak_civ.integration.plugin.PluginUI
import com.aitech.weartak_civ.integration.plugin.PluginViewModel
import com.aitech.weartak_civ.WearTakApplication
import com.weartak.plugin.sdk.MainUiActionContract as C

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
    val app = LocalContext.current.applicationContext as WearTakApplication
    val activeUid by app.bhRepo.activeUid.collectAsStateWithLifecycle()

    LaunchedEffect(pluginId, plugin?.isLoaded, changeRevisions[pluginId], activeUid) {
        if (plugin?.isLoaded == true) {
            pluginVm.refreshHostedMainUi(pluginId)
        }
    }

    HostedPluginMainUiScreen(
        title = plugin?.title ?: pluginId,
        result = when {
            plugin == null -> HostedPluginMainUiResult.Failed(stringResource(R.string.plugin_not_installed))
            plugin.isLoaded -> result
            else -> null
        },
        onBack = { navController.popBackStack() },
        onAction = { pluginVm.invokeHostedMainUiAction(pluginId, it) },
        onLoad = if (plugin != null && !plugin.isLoaded) {
            { pluginVm.loadPlugin(plugin.pluginId) }
        } else null,
    )
}

internal fun hostedPluginBodyRows(cards: List<HostedPluginMainUiCard>): List<List<HostedPluginMainUiCard>> {
    val rows = mutableListOf<MutableList<HostedPluginMainUiCard>>()
    cards.filter { it.action?.actionPlacement != C.HEADER_END }.forEach { card ->
        val group = card.action?.actionGroup
        val previous = rows.lastOrNull()
        if (group != null && previous != null && previous.size < 3 &&
            previous.last().action?.actionGroup == group) {
            previous.add(card)
        } else {
            rows.add(mutableListOf(card))
        }
    }
    return rows
}

@Composable
internal fun HostedPluginMainUiScreen(
    title: String,
    result: HostedPluginMainUiResult?,
    onBack: () -> Unit,
    onAction: (String) -> Unit,
    onLoad: (() -> Unit)? = null,
) {
    val header = (result as? HostedPluginMainUiResult.Ready)?.cards
        ?.firstOrNull { it.action?.actionPlacement == C.HEADER_END }
    WearTakScreenDimensions(
        text = title,
        rowContent = { if (header == null) WearTAKBackButton(onBack) },
        titleAction = if (header != null) {
            { HostedPluginIconAction(header) { onAction(header.cardId) } }
        } else null,
        reserveBottomSpace = header == null,
        titleActionAtEnd = header != null,
        columnContent = {
            when {
                onLoad != null -> item {
                    Chip(
                        onClick = onLoad,
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
                        hostedPluginBodyRows(result.cards).forEach { row ->
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                ) {
                                    if (row.first().action?.actionPresentation == C.ICON) {
                                        Row(
                                            modifier = Modifier.align(Alignment.CenterHorizontally),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            row.forEach { card ->
                                                HostedPluginIconAction(card) { onAction(card.cardId) }
                                            }
                                        }
                                        row.forEach { card ->
                                            card.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                        }
                                    } else {
                                        val card = row.single()
                                        if (card.action?.showTitle != false) {
                                            Text(
                                                card.title,
                                                fontWeight = if (card.action == null) FontWeight.Bold else FontWeight.Normal,
                                                modifier = if (card.action == null) Modifier.semantics { heading() }
                                                    else Modifier,
                                            )
                                        }
                                        card.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                        card.action?.let { action ->
                                            Chip(
                                                onClick = { onAction(card.cardId) },
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
            }
        },
    )
}

@Composable
internal fun HostedPluginIconAction(card: HostedPluginMainUiCard, onClick: () -> Unit) {
    val action = requireNotNull(card.action)
    when (action.actionIcon) {
        C.BLOODHOUND -> MapActionIconButton(R.drawable.bloodhound, action.actionLabel, action.enabled, onClick)
        C.TARGET -> MapActionIconButton(R.drawable.lock_on_self, action.actionLabel, action.enabled, onClick)
        C.NOTE, C.BACK, C.STOP -> MapStyleActionButton(
            action.actionLabel, action.enabled, onClick,
            backgroundColor = if (action.actionIcon == C.STOP) Color(0xFFB3261E) else Color.DarkGray,
        ) {
            when (action.actionIcon) {
                C.STOP -> Box(Modifier.size(10.dp).background(Color.White))
                C.BACK -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(22.dp))
                else -> Icon(Icons.Filled.Edit, null, Modifier.size(22.dp))
            }
        }
        else -> error("Validated hosted icon required")
    }
}
