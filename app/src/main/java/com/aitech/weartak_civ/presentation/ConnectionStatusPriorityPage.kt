package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.connectionPriority

@Composable
internal fun ConnectionStatusPriorityPage(
    priority: List<String>,
    pluginRoutes: Map<String, String>,
    onMove: (List<String>, String, Int) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    val tak = stringResource(R.string.tak_servers)
    val sitx = stringResource(R.string.sitx_tak)
    val titles = linkedMapOf(
        "companion" to stringResource(R.string.companion_app),
        "tak" to stringResource(R.string.connection_status_priority_connected, tak),
        "sitx" to stringResource(R.string.connection_status_priority_connected, sitx),
        "tak_connecting" to stringResource(R.string.connection_status_priority_connecting, tak),
        "sitx_connecting" to stringResource(R.string.connection_status_priority_connecting, sitx),
        "multicast" to stringResource(R.string.tak_sa_multicast),
        "ditto" to stringResource(R.string.ditto)
    ).apply { putAll(pluginRoutes) }
    val available = titles.keys.toList()
    val order = connectionPriority(priority, available).filter { it in titles }
    WearTakScreenDimensions(text = stringResource(R.string.connection_status_priority),
        rowContent = { WearTAKBackButton(onClick = onBack) }, columnContent = {
        item { Text(stringResource(R.string.connection_status_priority_help),
            style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
        itemsIndexed(order, key = { _, key -> key }) { index, key ->
            val title = titles.getValue(key)
            Column {
                Text(stringResource(R.string.connection_status_priority_position, index + 1, title),
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val up = stringResource(R.string.connection_status_move_up_description, title)
                    val down = stringResource(R.string.connection_status_move_down_description, title)
                    Button(onClick = { onMove(available, key, -1) }, enabled = index > 0,
                        modifier = Modifier.size(72.dp, 48.dp).semantics { contentDescription = up }) {
                        Text(stringResource(R.string.connection_status_move_up), fontSize = 11.sp,
                            textAlign = TextAlign.Center)
                    }
                    Button(onClick = { onMove(available, key, 1) }, enabled = index < order.lastIndex,
                        modifier = Modifier.size(72.dp, 48.dp).semantics { contentDescription = down }) {
                        Text(stringResource(R.string.connection_status_move_down), fontSize = 11.sp,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
        item(key = "reset") { Button(onClick = onReset, modifier = Modifier.size(148.dp, 48.dp)) {
            Text(stringResource(R.string.connection_status_reset), textAlign = TextAlign.Center)
        } }
    })
}
