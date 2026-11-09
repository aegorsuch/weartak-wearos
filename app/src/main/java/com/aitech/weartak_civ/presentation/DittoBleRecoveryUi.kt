package com.aitech.weartak_civ.presentation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.service.BleLinkWarning

/**
 * User-chosen copy for this watch's local BLE recovery budget, not global mesh health.
 * Wi-Fi/LAN sync may still be healthy; diagnostics remain BLE-specific.
 */
@StringRes
internal fun dittoBleWarningLabel(warning: BleLinkWarning): Int? = when (warning) {
    BleLinkWarning.NONE -> null
    BleLinkWarning.DISCONNECTED, BleLinkWarning.PAUSED -> R.string.ditto_edge_sync_offline
}

@Composable
internal fun DittoBleRecoveryDialog(
    warning: BleLinkWarning,
    onRetryNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = dittoBleWarningLabel(warning) ?: return
    WarningReviewDialog(
        title = stringResource(title),
        chipState = Utils.AlertChipState.Warning,
        tag = "ditto_ble_recovery_dialog",
        backTag = "ditto_ble_recovery_back",
        onDismiss = onDismiss
    ) {
        WarningReviewAction(stringResource(R.string.ditto_edge_sync_retry), "ditto_ble_retry_now",
            onRetryNow, Modifier.weight(1f))
    }
}

@Composable
internal fun DittoBleRecoverySettingsChip(warning: BleLinkWarning, onClick: () -> Unit) {
    val label = dittoBleWarningLabel(warning) ?: return
    Chip(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).automationRoot("ditto_ble_recovery_settings"),
        onClick = onClick,
        icon = {
            Icon(
                painter = painterResource(R.drawable.warning),
                contentDescription = null,
                tint = alertChipColors(Utils.AlertChipState.Warning),
                modifier = Modifier.size(19.dp)
            )
        },
        label = { Text(stringResource(label), maxLines = 2) },
        border = ChipDefaults.outlinedChipBorder(alertChipColors(Utils.AlertChipState.Warning), borderWidth = 2.dp),
        colors = ChipDefaults.chipColors(backgroundColor = Color.Transparent, contentColor = Color.White)
    )
}
