package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import com.aitech.weartak_civ.presentation.MainActivity
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MoppSettingsTest {
    @Before fun initializeDevice(): Unit = runBlocking {
        MainActivity.device_uid = "test-watch"
        DataRepository(RuntimeEnvironment.getApplication()).legacyDataStore().edit {
            it.remove(stringPreferencesKey("mopp_status"))
            it.remove(longPreferencesKey("mopp_saved_at"))
            it.remove(booleanPreferencesKey("enable_mopp"))
            it.remove(booleanPreferencesKey("enable_data_sync"))
        }
    }

    @Test fun selfStatusOnlyUsesPersistedSelectionAndActualSampleTimes() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        assertNull(repo.selfStatusInputs().first().policy.savedMopp)
        repo.legacyDataStore().edit { it[stringPreferencesKey("mopp_status")] = "2" }
        val legacy = repo.selfStatusInputs().first().policy.savedMopp!!
        assertEquals(MoppStatus.LEVEL_2, legacy.status)
        assertNull(legacy.savedAtMillis)
        val before = System.currentTimeMillis()
        repo.storeMoppStatus(MoppStatus.NONE)
        val saved = repo.selfStatusInputs().first().policy.savedMopp!!
        assertEquals(MoppStatus.NONE, saved.status)
        assertTrue(saved.savedAtMillis!! in before..System.currentTimeMillis())
        repo.storeLatestHeartRate(72)
        assertNull(repo.selfStatusInputs().first().heartRate)
        repo.storeLatestHeartRate(73, 123456L)
        assertEquals(PhysioSample(73, 123456L), repo.selfStatusInputs().first().heartRate)
        repo.storeLatestSkinTemp(93.4f, 123400L)
        assertEquals(PhysioSample(93.4f, 123400L), repo.selfStatusInputs().first().skinTemperature)
        repo.recordHeartRateSample(74, 123500L)
        assertEquals(PhysioSample(74, 123500L), repo.selfStatusInputs().first().heartRate)
        assertEquals(73, repo.latestHeartRate.value)
        repo.storeLatestHeartRate(-1)
        assertNull(repo.selfStatusInputs().first().heartRate)
    }

    @Test fun noneIsDefaultAndDistinctFromZeroAndInvalidPersistedValues() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        assertEquals(MoppStatus.NONE, repo.getMoppStatus.first())
        assertTrue(repo.getEnableMopp.first())
        assertEquals(listOf("none", "0", "1", "2", "3", "4"), MoppStatus.entries.map { it.key })
        repo.legacyDataStore().edit { it[stringPreferencesKey("mopp_status")] = "invalid" }
        assertEquals(MoppStatus.NONE, repo.getMoppStatus.first())
        assertNotEquals(MoppStatus.NONE, MoppStatus.LEVEL_0)
    }

    @Test fun everyLevelPersistsAndLegacyDisableCannotDisablePublishingOrChangeSavedTime() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        MoppStatus.entries.forEach {
            repo.storeEnableMopp(true)
            repo.storeMoppStatus(it)
            val saved = repo.selfStatusInputs().first().policy.savedMopp
            repo.storeEnableMopp(false)
            assertEquals(it, DataRepository(app).getMoppStatus.first())
            assertTrue(repo.getEnableMopp.first())
            assertEquals(saved, repo.selfStatusInputs().first().policy.savedMopp)
        }
    }

    @Test fun dataSyncDefaultsOnAndPausePreservesSubscriptionsAndProgress() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        assertTrue(repo.getEnableDataSync.first())
        repo.persistMissionSubscribed("server", "mission")
        repo.persistMissionSyncProgress("server", "mission", 1234L, setOf("change"))
        val saved = repo.persistedMissionSubscriptions.first()
        repo.storeEnableDataSync(false)
        assertFalse(DataRepository(app).getEnableDataSync.first())
        assertEquals(saved, repo.persistedMissionSubscriptions.first())
        repo.storeEnableDataSync(true)
        assertEquals(saved, DataRepository(app).persistedMissionSubscriptions.first())
    }
}
