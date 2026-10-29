package com.aitech.weartak_civ.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

internal val ENVIRONMENTAL_ALERTS_ENABLED = booleanPreferencesKey("enable_environmental_alerts")

internal class EnvironmentalAlertsPreferenceMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData[ENVIRONMENTAL_ALERTS_ENABLED] == null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (this[ENVIRONMENTAL_ALERTS_ENABLED] == null) {
                this[ENVIRONMENTAL_ALERTS_ENABLED] = listOf(
                    "enable_immersion_alerts",
                    "enable_low_atm_pressure_alert",
                    "enable_high_atm_pressure_alert"
                ).any { currentData[booleanPreferencesKey(it)] == true }
            }
        }

    override suspend fun cleanUp() = Unit
}
