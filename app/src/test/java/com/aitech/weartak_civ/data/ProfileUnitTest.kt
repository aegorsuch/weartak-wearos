package com.aitech.weartak_civ.data

import android.app.Application
import android.content.res.Configuration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProfileUnitTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun initializeDevice() {
        MainActivity.device_uid = "test-watch"
    }

    @Test fun choicesAreAlphabeticalAndManagedValuesMatchStableKeys() {
        val labels = ProfileUnit.entries.map { app.getString(it.labelRes) }
        assertEquals(listOf("Air Force", "Army", "Civilian", "Foreign National", "Marine Corps", "Navy", "None"), labels)
        assertEquals(labels.sorted(), labels)
        assertEquals(labels, app.resources.getStringArray(R.array.user_type_entries).toList())
        assertEquals(
            ProfileUnit.entries.map { it.storageValue },
            app.resources.getStringArray(R.array.user_type_entry_values).toList()
        )
        assertEquals("Unit", app.getString(R.string.user_type))
        assertEquals("none", app.getString(R.string.physiological_metrics_user_type_default))
    }

    @Test fun noneIsNullableWireValueNotLiteralNoneAndAllOtherValuesAreExact() {
        assertNull(ProfileUnit.NONE.batdokValue)
        assertEquals(
            listOf("Air Force", "Army", "Civilian", "Foreign National", "Marine Corps", "Navy"),
            ProfileUnit.entries.mapNotNull { it.batdokValue }
        )
        ProfileUnit.entries.forEach {
            assertEquals(it, ProfileUnit.fromStored(it.storageValue))
            assertEquals(it.storageValue, ProfileUnit.normalizeStored(app.getString(it.labelRes)))
        }
        assertEquals(ProfileUnit.NONE, ProfileUnit.fromStored("N/A"))
    }

    @Test fun fieldLabelIsConsistentAcrossExistingLocales() {
        listOf("en", "ar", "de", "es", "fr", "ja", "ko", "tr", "vi", "zh").forEach { language ->
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale(language)) }
            assertEquals("Unit", app.createConfigurationContext(config).getString(R.string.user_type))
        }
    }

    @Test fun legacyClassificationsAndUnknownValuesArePreservedWithoutGuessing() = runBlocking {
        val repo = DataRepository(app)
        val key = stringPreferencesKey("user_type")
        val legacy = listOf("Child", "Coalition Civilian", "Coalition Military",
            "Non-Coalition Civilian", "Non-Coalition Military", "NonCoalition Force",
            "NonCoalition Citizen", "Opposing Force Detainee", "custom", "0", "1")
        for (value in legacy) {
            repo.legacyDataStore().edit { it[key] = value }
            assertNull(ProfileUnit.fromStored(value))
            assertEquals(value, repo.getPhysiologicalMetricUserType.first())
            assertEquals(value, repo.legacyDataStore().data.first()[key])
        }
        repo.storePhysiologicalMetricUserType(ProfileUnit.NAVY.storageValue)
        assertEquals("navy", DataRepository(app).getPhysiologicalMetricUserType.first())
    }

    @Test fun unsetAndLegacyNaAreNoneAndManagedLabelsNormalizeOnWrite() = runBlocking {
        val repo = DataRepository(app)
        val key = stringPreferencesKey("user_type")
        repo.legacyDataStore().edit { it.remove(key) }
        assertEquals("none", repo.getPhysiologicalMetricUserType.first())
        repo.legacyDataStore().edit { it[key] = "N/A" }
        assertEquals("none", repo.getPhysiologicalMetricUserType.first())
        assertEquals("N/A", repo.legacyDataStore().data.first()[key])
        for (unit in ProfileUnit.entries) {
            repo.storePhysiologicalMetricUserType(app.getString(unit.labelRes))
            assertEquals(unit.storageValue, repo.legacyDataStore().data.first()[key])
            assertEquals(unit.storageValue, DataRepository(app).getPhysiologicalMetricUserType.first())
        }
    }
}
