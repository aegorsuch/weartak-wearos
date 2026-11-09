package com.aitech.weartak_civ.presentation

import android.app.Application
import android.location.Location
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.data.LocationInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TopMetricDisplayTest {
    @Test fun persistedMetricKeysSurviveProviderChangesAndRepositoryRecreation() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "test-watch"
        val repo = DataRepository(app)
        val key = stringPreferencesKey("selected_physio_metric")
        val locationKey = stringPreferencesKey("location_input")
        val original = repo.legacyDataStore().data.first()
        try {
            for (metric in TopDisplayMetric.entries) {
                // Seed the existing persisted format rather than rewriting it via the current setter.
                repo.legacyDataStore().edit { it[key] = metric.key }
                for (input in LocationInput.entries) {
                    val reopened = DataRepository(app)
                    assertEquals(metric.key, reopened.getSelectedPhysioMetric.first())
                    assertTrue(reopened.storeLocationInput(input))
                    assertEquals(metric.key, DataRepository(app).getSelectedPhysioMetric.first())
                    assertEquals(metric.key, repo.legacyDataStore().data.first()[key])
                }
            }
        } finally {
            repo.legacyDataStore().edit { prefs ->
                for (changedKey in listOf(locationKey, key)) {
                    val value = original[changedKey]
                    if (value == null) prefs.remove(changedKey) else prefs[changedKey] = value
                }
            }
        }
    }

    private fun value(metric: TopDisplayMetric, monitoring: Boolean = true, worn: Boolean = true,
                      location: Location? = null, locationEnabled: Boolean = true) =
        topMetricValue(metric, monitoring, worn, 80, .4f, 32f, 123,
            locationEnabled, location, "Disabled", "Off-Wrist", "No fix")

    @Test fun exactSixLocalizedSortedChoicesAndRemovedKeysFallBackWithoutChangingSteps() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(listOf("Exertion", "Heart Rate", "Lat/Long", "MGRS", "Skin Temp", "Steps"),
            topMetricChoices(app).map { it.second })
        MainActivity.device_uid = "test-watch"
        val repo = DataRepository(app)
        for (key in listOf("key_spo2", "key_spo2_tier", "key_calories", "key_floors", "key_crm", "unknown")) {
            repo.legacyDataStore().edit { it[stringPreferencesKey("selected_physio_metric")] = key }
            assertEquals("key_exertion", repo.getSelectedPhysioMetric.first())
        }
        repo.storeSelectedPhysioMetric("key_steps")
        assertEquals("key_steps", DataRepository(app).getSelectedPhysioMetric.first())
        repo.storeSelectedPhysioMetric("key_mgrs")
        assertEquals("key_mgrs", DataRepository(app).getSelectedPhysioMetric.first())
    }

    @Test fun staleCalorieKeysAreIgnoredWithoutChangingRetainedDailyMetrics() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "test-watch"
        val repo = DataRepository(app)
        repo.legacyDataStore().edit {
            it[stringPreferencesKey("selected_physio_metric")] = "key_calories"
            it[booleanPreferencesKey("enable_calories")] = true
            it[doublePreferencesKey("key_calories")] = 250.0
        }
        val reopened = DataRepository(app)
        assertEquals("key_exertion", reopened.getSelectedPhysioMetric.first())
        val hour = java.time.Instant.EPOCH
        reopened.updateDailyMetrics(hour, 123L, 6.7, 9.0)
        reopened.updateDailyMetrics(hour.plusSeconds(3600), null, null, null)
        assertEquals(DataRepository.DailyMetrics(hour.plusSeconds(3600), 123L, 6.7, 9.0),
            reopened.latestDailyMetrics.value)
        reopened.storeSelectedPhysioMetric("key_steps")
        assertEquals("key_steps", DataRepository(app).getSelectedPhysioMetric.first())
    }

    @Test fun disabledOverridesOffWristAndStaleMeasurementsIncludingSteps() {
        TopDisplayMetric.entries.filter { it.physiological }.forEach {
            assertEquals("Disabled", value(it, monitoring = false, worn = false))
        }

        listOf(TopDisplayMetric.EXERTION, TopDisplayMetric.HEART_RATE, TopDisplayMetric.SKIN_TEMP).forEach {
            assertEquals("Off-Wrist", value(it, worn = false))
        }
        assertEquals("123", value(TopDisplayMetric.STEPS, worn = false))
    }

    @Test fun missingWornObservationOrMissingHrNeverClaimsAConfirmedOffWristState() {
        fun hr(worn: Boolean, known: Boolean, enabled: Boolean = true) =
            topMetricValue(TopDisplayMetric.HEART_RATE, enabled, worn, null, -1f, null, null,
                false, null, "Disabled", "Off-Wrist", "No fix", wornKnown = known)
        assertEquals("---", hr(false, false))
        assertEquals("---", hr(true, true))
        assertEquals("Off-Wrist", hr(false, true))
        assertEquals("Disabled", hr(false, false, false))
    }

    @Test fun coordinatesIgnorePhysioStateAndNeverInventZeroForMissingFix() {
        val fix = Location("test").apply { time = 100; latitude = 41.881; longitude = -87.63 }
        assertEquals("41.88100/-87.63000", value(TopDisplayMetric.LAT_LONG, false, false, fix))
        assertEquals(Utils.latLonToMgrs(41.881, -87.63), value(TopDisplayMetric.MGRS, false, false, fix))
        for (metric in listOf(TopDisplayMetric.LAT_LONG, TopDisplayMetric.MGRS)) {
            assertEquals("No fix", value(metric))
            assertEquals("No fix", value(metric, location = fix, locationEnabled = false))
            assertEquals("No fix", value(metric, location = Location(fix).apply { latitude = Double.NaN }))
            assertEquals("No fix", value(metric, location = Location(fix).apply { time = 0 }))
        }
        assertEquals("0.00000/0.00000", value(TopDisplayMetric.LAT_LONG,
            location = Location(fix).apply { latitude = 0.0; longitude = 0.0 }))
    }

    @Test fun compactCoordinatesPreserveSignsAndFiveDecimalPrecision() {
        val fix = Location("test").apply { time = 100; latitude = -79.12345; longitude = -179.98765 }
        assertEquals("-79.12345/-179.98765", value(TopDisplayMetric.LAT_LONG, location = fix))
        assertEquals(Utils.latLonToMgrs(fix.latitude, fix.longitude),
            value(TopDisplayMetric.MGRS, location = fix))
        assertEquals(-79.12345, fix.latitude, 0.0)
        assertEquals(-179.98765, fix.longitude, 0.0)
    }
}
