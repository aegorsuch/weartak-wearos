package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BloodhoundActionLabelTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    private val item = Utils.BloodhoundItem(
        target = "Alice", uid = "alice", callsign = "Leader", timeReceived = 1,
        distance = 10.0, orientation = 0f, bloodhounding = false
    )

    private fun showDetail(item: Utils.BloodhoundItem) {
        val repository = mockk<BloodhoundRepository>()
        val app = mockk<WearTakApplication>()
        val context = mockk<Context>()
        every { repository.bloodhoundItemUi(item.uid) } returns MutableStateFlow(item)
        every { repository.bloodhoundMap } returns MutableStateFlow(
            mapOf(item.uid to mockk<BloodhoundRepository.BloodhoundItem>(relaxed = true))
        )
        every { repository.requestPresentation(any(), any()) } returns Unit
        every { repository.releasePresentation(any()) } returns Unit
        every { app.bhRepo } returns repository
        every { context.applicationContext } returns app
        compose.setContent {
            BloodhoundScreenDetailed(rememberSwipeDismissableNavController(), item.uid, context, {})
        }
    }

    @Test fun ordinaryTargetSaysStartBloodhoundInsteadOfRgr() {
        showDetail(item)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Start Bloodhound"))
        compose.onNodeWithText("Start Bloodhound").assertIsDisplayed()
        compose.onNodeWithText("RGR").assertDoesNotExist()
    }

    @Test fun remoteAlertRetainsItsSpecificActionAndLocationGuard() {
        showDetail(item.copy(isRemoteAlert = true, hasLocation = false))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Bloodhound to Alert"))
        compose.onNodeWithText("Bloodhound to Alert").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Start Bloodhound").assertDoesNotExist()
    }

    @Test fun activeTargetRetainsInPositionAction() {
        showDetail(item.copy(bloodhounding = true))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("nPos"))
        compose.onNodeWithText("nPos").assertIsDisplayed()
        compose.onNodeWithText("Start Bloodhound").assertDoesNotExist()
    }
}
