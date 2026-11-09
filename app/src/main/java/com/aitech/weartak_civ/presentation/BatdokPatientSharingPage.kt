package com.aitech.weartak_civ.presentation

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.BatdokSharingConsent
import com.aitech.weartak_civ.service.BatdokPatientStatus

@StringRes
fun batdokPatientStatusLabel(consent: BatdokSharingConsent, status: BatdokPatientStatus): Int = when {
    !consent.enabled -> R.string.batdok_patient_disabled
    !consent.confirmed -> R.string.batdok_patient_confirmation_required
    else -> when (status) {
        BatdokPatientStatus.LOCATION_UNAVAILABLE -> R.string.batdok_patient_location_required
        BatdokPatientStatus.CALLSIGN_REQUIRED -> R.string.batdok_patient_callsign_required
        BatdokPatientStatus.SENT -> R.string.batdok_patient_sent
        BatdokPatientStatus.FAILED -> R.string.batdok_patient_failed
        BatdokPatientStatus.WAITING_TAK -> R.string.batdok_patient_waiting_tak
        else -> R.string.batdok_patient_not_provisioned
    }
}

/** Only states the user must act on are shown on the chip; the rest are announced to accessibility only. */
fun batdokPatientStatusNeedsAction(@StringRes label: Int): Boolean = label !in setOf(
    R.string.batdok_patient_disabled, R.string.batdok_patient_sent, R.string.batdok_patient_waiting_tak
)

@Composable
fun BatdokPatientToggle(
    consent: BatdokSharingConsent,
    status: BatdokPatientStatus,
    onToggle: (Boolean) -> Unit,
    saveFailed: Boolean = false,
    failureCode: String? = null
) {
    val label = if (saveFailed) R.string.batdok_patient_save_failed else batdokPatientStatusLabel(consent, status)
    val statusText = stringResource(label).let {
        if (label == R.string.batdok_patient_failed && !failureCode.isNullOrBlank()) "$it [$failureCode]" else it
    }
    val actionable = batdokPatientStatusNeedsAction(label)
    WearTAKToggleChip(
        checkedLive = consent.enabled,
        onCheckedChange = onToggle,
        appIcon = { BuiltInPluginBrandIcon(BuiltInPlugin.BATDOK) },
        title = stringResource(R.string.batdok),
        description = if (actionable) statusText else "",
        statusDescription = if (actionable) "" else statusText,
        testTag = "settings_batdok"
    )
}
