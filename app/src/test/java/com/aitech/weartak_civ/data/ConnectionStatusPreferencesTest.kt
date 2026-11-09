package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConnectionStatusPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun defaultsRetainLegacyStagesAndAppendNewRoutesDeterministically() {
        assertEquals(listOf("companion", "tak", "sitx", "tak_connecting", "sitx_connecting",
            "multicast", "ditto", "plugin:a:radio", "plugin:z:mesh"),
            connectionPriority(emptyList(), listOf("plugin:z:mesh", "plugin:a:radio")))
        assertEquals(listOf("tak") + defaultConnectionPriority.filterNot { it == "tak" },
            connectionPriority(listOf("bogus", "", "disconnected", "tak", "tak"), emptyList()))
    }

    @Test fun movingVisibleEntriesPreservesTemporarilyUnavailablePluginPosition() {
        val saved = listOf("companion", "plugin:offline:mesh", "ditto") + defaultConnectionPriority
        val moved = moveConnectionPriority(saved, defaultConnectionPriority, "ditto", -1)
        assertEquals(listOf("ditto", "plugin:offline:mesh", "companion"), moved.take(3))
        assertEquals(moved, moveConnectionPriority(moved, defaultConnectionPriority, "ditto", -1))
        assertEquals(moved, moveConnectionPriority(moved, defaultConnectionPriority, "unknown", 1))
    }

    @Test fun orderSurvivesActualStoreRestartAndResetLeavesOtherPreferencesUntouched() = runBlocking {
        val file = File(folder.root, "priority.preferences_pb")
        val unrelated = booleanPreferencesKey("network_preferences_checklist_enabled")
        var saved = emptyList<String>()
        repeat(2) { restart ->
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
            try {
                val preferences = ConnectionStatusPreferences(store)
                if (restart == 0) {
                    store.edit { it[unrelated] = true }
                    assertEquals(defaultConnectionPriority, preferences.priority.first())
                    preferences.move(defaultConnectionPriority, "ditto", -1)
                    saved = preferences.priority.first()
                    assertTrue(saved.indexOf("ditto") < saved.indexOf("multicast"))
                } else {
                    assertEquals(saved, preferences.priority.first())
                    preferences.reset()
                    assertEquals(defaultConnectionPriority, preferences.priority.first())
                    assertEquals(true, store.data.first()[unrelated])
                }
            } finally { job.cancelAndJoin() }
        }
    }
}
