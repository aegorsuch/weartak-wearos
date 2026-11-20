package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import com.aitech.weartak_civ.presentation.viewmodel.TakGovLinkPhase
import com.aitech.weartak_civ.presentation.viewmodel.TakGovLinkUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round")
class TakGovLinkPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeLinkShowsCodeAndRegistrationUrlWithoutOpenPageInstructions() {
        compose.setContent {
            TakGovLinkPage(
                navController = rememberNavController(),
                state = TakGovLinkUiState(
                    linked = false,
                    userCode = "ABCD-EFGH",
                    phase = TakGovLinkPhase.AWAITING_APPROVAL,
                ),
                onStartLinking = {},
                onCancelLinking = {},
                onRemoveLink = {},
            )
        }

        compose.onNodeWithText("ABCD-EFGH").assertExists()
        compose.onNodeWithText("https://tak.gov/register-device").assertExists()
        compose.onNodeWithText("Open the registration page and enter this code to link your account.")
            .assertDoesNotExist()
        compose.onNodeWithText("Open TAK.gov registration").assertDoesNotExist()
    }
}
