package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.*
import com.aitech.weartak_civ.DEFAULT_BIRTH_YEAR
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BatdokSharingStoreTest {
    private lateinit var repo: DataRepository
    @Before fun setup(): Unit = runBlocking {
        MainActivity.device_uid = "synthetic-watch"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { it.clear() }
    }

    @Test fun oldDefaultOnAndManagedOnNeverGrantExpandedConsent() = runBlocking {
        assertTrue(repo.getEnableBatdokCot.first())
        assertFalse(repo.batdokSharing.consent.first().confirmed)
        repo.storeEnableBatdokCot(true)
        assertFalse(repo.batdokSharing.consent.first().confirmed)
        repo.legacyDataStore().edit { it[intPreferencesKey("batdok_patient_sharing_consent_version")] = 999 }
        assertFalse(repo.batdokSharing.consent.first().confirmed)
    }

    @Test fun explicitTogglePersistsButOffRevokesAndProgrammaticOnCannotReauthorize() = runBlocking {
        repo.batdokSharing.setEnabledByUser(false)
        assertFalse(repo.batdokSharing.consent.first().confirmed)
        repo.batdokSharing.setEnabledByUser(true)
        val confirmed = repo.batdokSharing.consent.first()
        assertTrue(confirmed.confirmed)
        val reopened = DataRepository(RuntimeEnvironment.getApplication())
        assertEquals(confirmed, reopened.batdokSharing.consent.first())
        repo.storeEnableBatdokCot(false)
        assertFalse(repo.batdokSharing.consent.first().confirmed)
        repo.storeEnableBatdokCot(true)
        assertFalse(repo.batdokSharing.consent.first().confirmed)
        assertNotEquals(confirmed.revision, repo.batdokSharing.consent.first().revision)
        repo.batdokSharing.setEnabledByUser(true)
        assertTrue(repo.batdokSharing.consent.first().confirmed)
    }

    @Test fun concurrentAndRestartedIdentityNeverChangesAcrossHeartbeatOrToggle() = runBlocking {
        val ids = coroutineScope { (1..10).map { async { repo.batdokSharing.patientId() } }.awaitAll() }
        assertEquals(1, ids.distinct().size)
        assertTrue(ids.first().matches(Regex("[0-9a-f]{16}")))
        repo.batdokSharing.setEnabledByUser(true)
        repo.storeEnableBatdokCot(false)
        assertEquals(ids.first(), DataRepository(RuntimeEnvironment.getApplication()).batdokSharing.patientId())
        repo.legacyDataStore().edit { it[stringPreferencesKey("batdok_patient_id")] = "invalid" }
        try {
            repo.batdokSharing.patientId()
            fail("Invalid persisted patient ID must block, not regenerate")
        } catch (_: IllegalStateException) {
            assertEquals("invalid", repo.legacyDataStore().data.first()[stringPreferencesKey("batdok_patient_id")])
        }
    }

    @Test fun birthYearRequiresExplicitProvenanceNotDefaultLegacyOrManagedFallback() = runBlocking {
        assertNull(repo.confirmedBirthYear.first())
        repo.legacyDataStore().edit { it[intPreferencesKey("year_of_birth")] = 1980 }
        assertNull(repo.confirmedBirthYear.first())
        repo.storeYearOfBirth(DEFAULT_BIRTH_YEAR, explicitlySupplied = false)
        assertNull(repo.confirmedBirthYear.first())
        repo.storeYearOfBirth(1980)
        assertEquals(1980, repo.confirmedBirthYear.first())
        repo.storeYearOfBirth(1980, explicitlySupplied = false)
        assertEquals(1980, repo.confirmedBirthYear.first())
        repo.storeYearOfBirth(1979, explicitlySupplied = false)
        assertNull(repo.confirmedBirthYear.first())
        repo.storeYearOfBirth(DEFAULT_BIRTH_YEAR)
        assertEquals(DEFAULT_BIRTH_YEAR, DataRepository(RuntimeEnvironment.getApplication()).confirmedBirthYear.first())
    }
}
