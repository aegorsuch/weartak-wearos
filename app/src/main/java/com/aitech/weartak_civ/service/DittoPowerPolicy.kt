package com.aitech.weartak_civ.service

internal data class DittoPowerState(
    val worn: Boolean? = null,
    val charging: Boolean = false,
    val battery: Int? = null,
    val lowBatteryThreshold: Int = 20,
    val sleeping: Boolean = false,
    val powerSave: Boolean = false,
    val alerting: Boolean = false,
)

internal enum class DittoPowerSuppression { OFF_WRIST, POWER }

internal class DittoPowerPolicy {
    private var urgentUntil = Long.MIN_VALUE

    fun resumeForAlert(now: Long) { urgentUntil = now + ALERT_GRACE_MS }

    fun suppressed(state: DittoPowerState, now: Long): Boolean =
        suppressionReason(state, now) != null

    fun suppressionReason(state: DittoPowerState, now: Long): DittoPowerSuppression? = when {
        state.charging || state.alerting || now < urgentUntil -> null
        state.sleeping || state.powerSave ||
            state.battery?.let { it <= state.lowBatteryThreshold } == true -> DittoPowerSuppression.POWER
        state.worn == false -> DittoPowerSuppression.OFF_WRIST
        else -> null
    }

    companion object {
        const val ALERT_GRACE_MS = 60_000L
    }
}
