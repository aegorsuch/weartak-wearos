package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StayConnectedOffWristSettingTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() = runBlocking {
        MainActivity.device_uid = "off-wrist-preference-test"
        DataRepository(app).legacyDataStore().edit { it.clear() }
        Unit
    }

    @Test fun defaultsOffAndBothChoicesPersistWithoutChangingOtherPreferences() = runBlocking {
        val repository = DataRepository(app)
        assertFalse(repository.stayConnectedOffWrist.first())
        val before = repository.legacyDataStore().data.first().asMap()
        repository.storeStayConnectedOffWrist(true)
        assertTrue(DataRepository(app).stayConnectedOffWrist.first())
        assertEquals(before, repository.legacyDataStore().data.first().asMap()
            .filterKeys { it.name != "stay_connected_off_wrist" })
        repository.storeStayConnectedOffWrist(false)
        assertFalse(DataRepository(app).stayConnectedOffWrist.first())
    }

    @Test fun developerTogglePersistsBothDirectionsAndReflectsExternalChanges() {
        val repository = DataRepository(app)
        val title = app.getString(R.string.debug_stay_connected_off_wrist)
        compose.setContent {
            val enabled by repository.stayConnectedOffWrist.collectAsState(initial = false)
            val scope = rememberCoroutineScope()
            DebugInformationPage(
                DebugDisplayInfo("test", 1, "release", "test", "test", "WIFI", "test", "N/A", 8000),
                {}, {}, {}, stayConnectedOffWrist = enabled,
                onStayConnectedOffWristChange = { scope.launch { repository.storeStayConnectedOffWrist(it) } })
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(app.getString(R.string.debug_stay_connected_off_wrist_description)).assertExists()
        compose.onNodeWithText(title).assertIsOff().performClick()
        compose.waitUntil { runBlocking { repository.stayConnectedOffWrist.first() } }
        compose.onNodeWithText(title).assertIsOn().performClick()
        compose.waitUntil { runBlocking { !repository.stayConnectedOffWrist.first() } }
        compose.onNodeWithText(title).assertIsOff()
        runBlocking { repository.storeStayConnectedOffWrist(true) }
        compose.waitUntil {
            compose.onAllNodes(isToggleable() and isOn() and hasText(title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).assertIsOn()
    }
}
