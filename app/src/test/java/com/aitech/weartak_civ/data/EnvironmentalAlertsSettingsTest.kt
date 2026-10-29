package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EnvironmentalAlertsSettingsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun resetSettings(): Unit = runBlocking {
        MainActivity.device_uid = "test-watch"
        DataRepository(app).legacyDataStore().edit { prefs ->
            listOf("enable_environmental_alerts", "enable_immersion_alerts",
                "enable_low_atm_pressure_alert", "enable_high_atm_pressure_alert").forEach {
                prefs.remove(booleanPreferencesKey(it))
            }
        }
    }

    @Test fun freshInstallDefaultsOff() = runBlocking {
        val repo = DataRepository(app)
        assertFalse(repo.getEnableEnvironmentalAlerts.first())
        assertFalse(repo.getEnableImmersionAlerts.first())
        assertFalse(repo.getEnableLowAtmPressureAlert.first())
        assertFalse(repo.getEnableHighAtmPressureAlert.first())
        assertFalse(repo.effectiveEnableImmersionAlerts.first())
        assertFalse(repo.effectiveEnableLowAtmPressureAlert.first())
        assertFalse(repo.effectiveEnableHighAtmPressureAlert.first())
    }

    private fun upgrade(seed: Preferences, expected: Boolean) = runBlocking {
        val file = File(temporaryFolder.newFolder(), "data.preferences_pb")
        val oldJob = SupervisorJob()
        val oldStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + oldJob), produceFile = { file }
        )
        oldStore.updateData { seed }
        oldJob.cancelAndJoin()
        repeat(2) {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(
                migrations = listOf(EnvironmentalAlertsPreferenceMigration()),
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }
            )
            try {
                val prefs = store.data.first()
                assertEquals(expected, prefs[ENVIRONMENTAL_ALERTS_ENABLED])
                seed.asMap().forEach { (key, value) -> assertEquals(value, prefs.asMap()[key]) }
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun upgradeInfersMasterFromAnyExplicitlyEnabledSubtype() {
        listOf("enable_immersion_alerts", "enable_low_atm_pressure_alert",
            "enable_high_atm_pressure_alert").forEach { key ->
            upgrade(preferencesOf(booleanPreferencesKey(key) to true), true)
        }
    }

    @Test fun migrationDefaultsFreshAndPreviouslyDisabledInstallsOff() {
        upgrade(emptyPreferences(), false)
        upgrade(preferencesOf(booleanPreferencesKey("enable_immersion_alerts") to false), false)
    }

    @Test fun explicitMasterChoiceIsNeverOverwrittenOnRestart() {
        upgrade(preferencesOf(ENVIRONMENTAL_ALERTS_ENABLED to false,
            booleanPreferencesKey("enable_immersion_alerts") to true), false)
        upgrade(preferencesOf(ENVIRONMENTAL_ALERTS_ENABLED to true,
            booleanPreferencesKey("enable_immersion_alerts") to false), true)
    }

    @Test fun masterPersistsAcrossRepositoriesAndRestoresIndividualChoices() = runBlocking {
        val picker = DataRepository(app)
        val settings = DataRepository(app)
        picker.storeEnableImmersionAlerts(true)
        picker.storeEnableLowAtmPressureAlert(false)
        picker.storeEnableHighAtmPressureAlert(true)
        picker.storeLowAtmPressureAlertThreshold(800)
        picker.storeHighAtmPressureAlertThreshold(1100)

        picker.storeEnableEnvironmentalAlerts(false)
        assertFalse(settings.getEnableEnvironmentalAlerts.first())
        assertFalse(settings.effectiveEnableImmersionAlerts.first())
        assertFalse(settings.effectiveEnableLowAtmPressureAlert.first())
        assertFalse(settings.effectiveEnableHighAtmPressureAlert.first())
        assertTrue(settings.getEnableImmersionAlerts.first())
        assertFalse(settings.getEnableLowAtmPressureAlert.first())
        assertTrue(settings.getEnableHighAtmPressureAlert.first())

        settings.storeEnableEnvironmentalAlerts(true)
        assertTrue(picker.getEnableEnvironmentalAlerts.first())
        assertTrue(picker.effectiveEnableImmersionAlerts.first())
        assertFalse(picker.effectiveEnableLowAtmPressureAlert.first())
        assertTrue(picker.effectiveEnableHighAtmPressureAlert.first())
        assertEquals(800, picker.getLowAtmPressureAlertThreshold.first())
        assertEquals(1100, picker.getHighAtmPressureAlertThreshold.first())
        assertTrue(DataRepository(app).getEnableEnvironmentalAlerts.first())
        picker.storeEnableImmersionAlerts(false)
        picker.storeEnableHighAtmPressureAlert(false)
        assertTrue(picker.getEnableEnvironmentalAlerts.first())
    }
}
