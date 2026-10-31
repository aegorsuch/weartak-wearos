package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.viewmodel.MarkerViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkerBloodhoundActionsTest {
    @get:Rule val compose = createComposeRule()
    private val repository = mockk<BloodhoundRepository>(relaxed = true)
    private val marker = DataRepository.DroppedMarker(
        DataRepository.MarkerCotType.DROP, "pin", DataRepository.MarkerType.FRIENDLY,
        "Rally Point", "", 100L,
        DataRepository.LocationSnapshot(100L, 38.0, -77.0, null, null, null, null, null),
        false, false
    )

    private fun showMarker(active: Boolean) {
        MainActivity.Constants.bhRepo = repository
        val activeItem = if (active) mockk<BloodhoundRepository.BloodhoundItem> {
            every { uid } returns marker.uid
        } else null
        every { repository.activeBhItemMap } returns MutableStateFlow(activeItem)
        val viewModel = mockk<MarkerViewModel>()
        every { viewModel.markerVectorUi(0) } returns MutableStateFlow(null)
        compose.setContent {
            EditSelectedMarker(
                rememberSwipeDismissableNavController(), 0, LocalContext.current,
                viewModel, listOf(marker), mutableStateOf(false), mutableStateOf(false), {}
            )
        }
    }

    @Test fun savedMarkerStartUsesExistingActivationAndReturnsHome() {
        showMarker(false)
        compose.onNodeWithContentDescription("Bloodhound to Marker").performScrollTo().performClick()
        compose.runOnIdle {
            verify(exactly = 1) { repository.requestActivateBloodhound(marker.uid) }
            verify(exactly = 0) { repository.stopTracking(any()) }
            val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                intent.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    @Test fun savedMarkerStopUsesPickerApiWithoutInPositionOrNavigation() {
        showMarker(true)
        compose.onNodeWithContentDescription("Stop Bloodhound to Rally Point").performScrollTo().performClick()
        compose.onNodeWithText("Change Title").assertExists()
        compose.runOnIdle {
            verify(exactly = 1) { repository.stopTracking(marker.uid) }
            verify(exactly = 0) { repository.requestActivateBloodhound(any()) }
            verify(exactly = 0) { repository.requestInPosition(any()) }
            assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }
}
