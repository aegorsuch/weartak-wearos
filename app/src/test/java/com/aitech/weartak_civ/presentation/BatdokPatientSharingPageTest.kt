package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.BatdokSharingConsent
import com.aitech.weartak_civ.service.BatdokPatientStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatdokPatientSharingPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun legacyOnNeedsOffOnAndToggleAloneAuthorizesWithoutConfirmationControl() {
        val consent = mutableStateOf(BatdokSharingConsent(true, 0, 0))
        val interactions = mutableListOf<Boolean>()
        compose.setContent {
            Column {
                BatdokPatientToggle(consent.value, BatdokPatientStatus.WAITING_TAK, {
                    interactions += it
                    consent.value = BatdokSharingConsent(it, if (it) 1 else 0, interactions.size.toLong())
                })
            }
        }
        assertTrue(interactions.isEmpty())
        compose.onNodeWithTag("settings_batdok").assertIsOn()
        compose.onNodeWithText("BATTAK").assertExists()
        compose.onNodeWithText("ALL enabled TAK servers", substring = true).assertExists()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.batdok_patient_confirmation_required))
            .assertExists()
        compose.onNodeWithTag("settings_batdok").performClick().assertIsOff()
        assertFalse(consent.value.confirmed)
        compose.onNodeWithTag("settings_batdok").performClick().assertIsOn()
        assertTrue(consent.value.confirmed)
        assertEquals(listOf(false, true), interactions)
        compose.onNodeWithText("Share with all enabled TAK servers").assertDoesNotExist()
    }

    @Test fun staleServiceSuccessCannotOverrideOffOrLegacyDefault() {
        assertEquals(R.string.batdok_patient_disabled,
            batdokPatientStatusLabel(BatdokSharingConsent(false, 1, 1), BatdokPatientStatus.SENT))
        assertEquals(R.string.batdok_patient_confirmation_required,
            batdokPatientStatusLabel(BatdokSharingConsent(true, 0, 1), BatdokPatientStatus.SENT))
    }

    @Test fun failedToggleSaveIsVisibleWithoutChangingStoredState() {
        compose.setContent {
            Column {
                BatdokPatientToggle(BatdokSharingConsent(false, 0, 0),
                    BatdokPatientStatus.DISABLED, {}, saveFailed = true)
            }
        }
        compose.onNodeWithTag("settings_batdok").assertIsOff()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.batdok_patient_save_failed))
            .assertExists()
    }
}
