package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h300dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnvironmentalAlertsViewModelTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: DataRepository
    private val engine = mockk<CepEngine>(relaxed = true)

    @Before fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "test-watch"
        WearTakApplication.AppCtx.init(app)
        MainActivity.mainActivity = mockk<MainActivity>(relaxed = true).also {
            every { it.applicationContext } returns app
        }
        MainActivity.sitxRepo = mockk {
            every { baseUrl } returns flowOf("")
        }
        repo = DataRepository(app)
        MainActivity.dataRepository = repo
        repo.storeEnableEnvironmentalAlerts(true)
        mockkObject(CepProvider)
        coEvery { CepProvider.getOrCreateEngine(any()) } returns engine
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
        unmockkObject(CepProvider)
    }

    @Test fun independentScreenViewModelsSharePersistedToggleAndRefreshPressureRuntime() {
        val picker = WearTAKSettingsViewModel()
        val settings = WearTAKSettingsViewModel()
        compose.setContent {
            val pickerEnabled by picker.savedEnableEnvironmentalAlerts.collectAsState()
            val settingsEnabled by settings.savedEnableEnvironmentalAlerts.collectAsState()
            WearTAKCIVTheme {
                Column {
                    MetricSettingsControl("Picker", pickerEnabled,
                        picker::storeEnableEnvironmentalAlerts, {})
                    WearTAKToggleChip(settingsEnabled, settings::storeEnableEnvironmentalAlerts,
                        title = "Settings")
                }
            }
        }
        compose.waitUntil {
            picker.savedEnableEnvironmentalAlerts.value && settings.savedEnableEnvironmentalAlerts.value
        }
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitUntil {
            !picker.savedEnableEnvironmentalAlerts.value && !settings.savedEnableEnvironmentalAlerts.value
        }
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onNodeWithText("Settings").assertIsOff()
        assertFalse(runBlocking { DataRepository(RuntimeEnvironment.getApplication())
            .getEnableEnvironmentalAlerts.first() })
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil {
            picker.savedEnableEnvironmentalAlerts.value && settings.savedEnableEnvironmentalAlerts.value
        }
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onNodeWithText("Settings").assertIsOn()
        verify(timeout = 5000, exactly = 2) { engine.updatePressureProfile() }
    }
}
