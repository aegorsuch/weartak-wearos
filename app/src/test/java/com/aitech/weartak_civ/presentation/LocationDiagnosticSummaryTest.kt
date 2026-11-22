package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.aitech.weartak_civ.service.LocationDiagnosticSample
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w300dp-h300dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationDiagnosticSummaryTest {
    val compose = createComposeRule()
    @get:Rule val composeRules = org.junit.rules.RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    @Test fun inactiveReadoutDoesNotClaimToBeWaitingForSatellites() {
        compose.setContent {
            DiagnosticSummary("Watch", LocationDiagnosticSample(
                assessmentReason = "Diagnostics inactive; no active bonded Companion session"
            ))
        }
        compose.onNodeWithText("Watch: UNKNOWN").assertIsDisplayed()
        compose.onNodeWithText("Diagnostics inactive; no active bonded Companion session").assertIsDisplayed()
        compose.onNodeWithText("Waiting for satellite status").assertDoesNotExist()
        compose.onNodeWithText("Source:", substring = true).assertDoesNotExist()
    }

    @Test fun fusedReadoutPreservesObservedSourceWithoutClaimingSatelliteProvenanceAtLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(340f / 160f, 1.3f)) {
                DiagnosticSummary("Watch", LocationDiagnosticSample(
                    source = "fused", ageMillis = 2_000, horizontalAccuracyMeters = 7f,
                    providerAvailable = true
                ))
            }
        }
        compose.onNodeWithText("Source: fused").assertIsDisplayed()
        val reason = "Fix observed; satellite provenance unavailable for fused"
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(reason).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().didOverflowWidth)
        assertFalse(layouts.single().didOverflowHeight)
        compose.onNodeWithText("Watch: UNKNOWN").assertIsDisplayed()
    }
}
