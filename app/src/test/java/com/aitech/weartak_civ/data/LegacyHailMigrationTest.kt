package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LegacyHailMigrationTest {
    private lateinit var repo: DataRepository

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "synthetic-watch"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { it.clear() }
    }

    @Test fun preservesSourceAndPersistsOneIdentityAcrossConcurrentReadsAndRestart() = runBlocking {
        repo.storeEnableHailService(true)
        repo.storeCombatId(1234)
        val license = stringPreferencesKey("hail_license_info_json_v1")
        val state = stringPreferencesKey("hail_license_state")
        repo.legacyDataStore().edit {
            it[license] = "synthetic-license-info"
            it[state] = "VALID"
        }

        val snapshots = coroutineScope { (1..10).map { async { repo.legacyHailMigration() } }.awaitAll() }
        assertEquals(1, snapshots.map { it.id }.distinct().size)
        assertEquals(true, snapshots.first().enabled)
        assertEquals(1234, snapshots.first().combatId)
        assertEquals(snapshots.first(), DataRepository(RuntimeEnvironment.getApplication()).legacyHailMigration())
        val preferences = repo.legacyDataStore().data.first()
        assertEquals("synthetic-license-info", preferences[license])
        assertEquals("VALID", preferences[state])
        assertEquals(1234, preferences[intPreferencesKey("hail_combat_id")])
    }

    @Test fun absentCombatIdRemainsAbsentAndCorruptMigrationIdFailsClosed() = runBlocking {
        val initial = repo.legacyHailMigration()
        assertFalse(initial.enabled)
        assertNull(initial.combatId)
        val key = stringPreferencesKey("hail_migration_id_v1")
        repo.legacyDataStore().edit { it[key] = "invalid" }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.legacyHailMigration() }
        }
        assertEquals("invalid", repo.legacyDataStore().data.first()[key])
    }

}
