package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.data.CrmDisplayState

internal enum class TopMetricAlertSource { NONE, PHYSIO, DETECTION, CRM, DITTO_BLE }

internal fun resolveTopMetricAlert(
    physioState: AlertChipState,
    detectionPending: Boolean,
    crmStatus: CrmDisplayState?,
    dittoWarning: Boolean = false
): TopMetricAlertSource = when {
    physioState == AlertChipState.Emergency -> TopMetricAlertSource.PHYSIO
    crmStatus == CrmDisplayState.POOR -> TopMetricAlertSource.CRM
    physioState == AlertChipState.Warning -> TopMetricAlertSource.PHYSIO
    detectionPending -> TopMetricAlertSource.DETECTION
    crmStatus == CrmDisplayState.MODERATE -> TopMetricAlertSource.CRM
    dittoWarning -> TopMetricAlertSource.DITTO_BLE
    else -> TopMetricAlertSource.NONE
}

internal data class HomeStatusWarning(
    val key: String,
    val source: TopMetricAlertSource,
    val label: String,
    val chipState: AlertChipState,
    val onReview: () -> Unit
)

@Composable
internal fun WarningReviewDialog(
    title: String,
    chipState: AlertChipState,
    tag: String,
    backTag: String,
    onDismiss: () -> Unit,
    detail: String? = null,
    body: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.automationRoot(tag).verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(painterResource(R.drawable.warning), contentDescription = null,
                    tint = alertChipColors(chipState), modifier = Modifier.size(22.dp))
                Text(title, modifier = Modifier.fillMaxWidth().semantics { heading() },
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = Color.White)
                if (detail != null) {
                    Text(detail, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        fontSize = 14.sp, color = Color.White)
                }
                body()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    actions()
                    Box(Modifier.automationRoot(backTag)) {
                        WearTAKBackButton(onClick = onDismiss, compact = true)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WarningReviewAction(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ChipColors = ChipDefaults.primaryChipColors()
) {
    Chip(
        modifier = modifier.heightIn(min = 48.dp).automationRoot(tag),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp),
        label = {
            FittedTopMetricText(label,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                minFontSize = 10.sp, maxLines = 2)
        },
        colors = colors
    )
}
