package com.aitech.weartak_civ.presentation

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material.Text
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

@Composable
fun BatdokPatientToggle(
    consent: BatdokSharingConsent,
    status: BatdokPatientStatus,
    onToggle: (Boolean) -> Unit,
    saveFailed: Boolean = false
) {
    WearTAKToggleChip(
        checkedLive = consent.enabled,
        onCheckedChange = onToggle,
        appIcon = { BuiltInPluginBrandIcon(BuiltInPlugin.BATDOK) },
        title = stringResource(R.string.batdok),
        description = stringResource(if (saveFailed) R.string.batdok_patient_save_failed
            else batdokPatientStatusLabel(consent, status)),
        testTag = "settings_batdok"
    )
    Text(stringResource(R.string.batdok_patient_sharing_explanation))
}
