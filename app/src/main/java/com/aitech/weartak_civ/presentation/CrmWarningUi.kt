package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.CrmInferenceState
import com.aitech.weartak_civ.data.CrmInferenceStatus
import com.aitech.weartak_civ.data.CrmThresholdRanges
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class CrmHomeWarning(val score: Int, val status: CrmDisplayState) {
    // Red is a presentation status only, never an emergency event or dispatch instruction.
    val chipState: AlertChipState
        get() = if (status == CrmDisplayState.POOR) AlertChipState.Emergency else AlertChipState.Warning
}

internal fun crmHomeWarning(
    inference: CrmInferenceState,
    thresholds: CrmThresholdRanges,
    enabled: Boolean,
    worn: Boolean,
    nowMillis: Long
): CrmHomeWarning? {
    val value = inference.value ?: return null
    if (!enabled || !worn || inference.status != CrmInferenceStatus.ACCEPTED ||
        !value.isFinite() || value !in 0f..1f || inference.updatedAtMillis <= 0 ||
        nowMillis - inference.updatedAtMillis !in 0L..30_000L) return null
    val score = inference.displayScore ?: return null
    val status = thresholds.stateFor(score)
    return if (status == CrmDisplayState.GOOD) null else CrmHomeWarning(score, status)
}

@Composable
internal fun rememberCrmHomeWarning(
    inference: CrmInferenceState,
    thresholds: CrmThresholdRanges,
    enabled: Boolean,
    worn: Boolean,
    currentTimeMillis: () -> Long = System::currentTimeMillis
): CrmHomeWarning? {
    val owner = LocalLifecycleOwner.current
    var resumeVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeVersion++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var now by remember(inference.updatedAtMillis, resumeVersion) { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(inference.updatedAtMillis, resumeVersion) {
        now = currentTimeMillis()
        val remaining = 30_000L - (now - inference.updatedAtMillis)
        if (remaining >= 0L) {
            delay(remaining + 1L)
            now = currentTimeMillis()
        }
    }
    return crmHomeWarning(inference, thresholds, enabled, worn, now)
}
