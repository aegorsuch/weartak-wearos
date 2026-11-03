package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.service.DittoLiveStatus
import com.aitech.weartak_civ.service.DittoRecoveryStatus

internal fun ScalingLazyListScope.dittoDiagnosticItems(status: DittoLiveStatus) {
    dittoDiagnosticPeerRows(status).forEach { (label, count, tag) ->
        item {
            DittoDiagnosticValue(stringResource(label),
                count?.toString() ?: stringResource(R.string.ditto_status_unavailable), tag)
        }
    }
    item {
        DittoDiagnosticValue(stringResource(R.string.ditto_ble_recovery), stringResource(when (status.recovery) {
            DittoRecoveryStatus.ACTIVE -> R.string.ditto_recovery_active
            DittoRecoveryStatus.TIMED_OUT -> R.string.ditto_recovery_timed_out
            DittoRecoveryStatus.USER_PAUSED -> R.string.ditto_recovery_user_paused
            DittoRecoveryStatus.INELIGIBLE -> R.string.ditto_recovery_ineligible
            DittoRecoveryStatus.STOPPED -> R.string.ditto_recovery_stopped
            DittoRecoveryStatus.UNAVAILABLE -> R.string.ditto_status_unavailable
        }), "recovery")
    }
}

internal fun dittoDiagnosticPeerRows(status: DittoLiveStatus): List<Triple<Int, Int?, String>> = buildList {
    val peers = status.peers
    val transports = status.transports
    add(Triple(R.string.ditto_direct_peers, peers?.total, "total"))
    if (transports?.bluetooth == true) add(Triple(R.string.ditto_ble_peers, peers?.bluetooth, "ble"))
    if (transports?.lan == true) add(Triple(R.string.ditto_lan_peers, peers?.lan, "lan"))
    if (transports?.p2pWifi == true) add(Triple(R.string.ditto_p2p_peers, peers?.p2pWifi, "p2p"))
    if (transports?.websocket == true) add(Triple(R.string.ditto_websocket_peers, peers?.websocket, "websocket"))
}

@Composable
private fun DittoDiagnosticValue(label: String, value: String, tag: String) {
    Text(
        text = "$label $value",
        style = MaterialTheme.typography.body2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("ditto-diagnostics-$tag").semantics(mergeDescendants = true) {},
    )
}
