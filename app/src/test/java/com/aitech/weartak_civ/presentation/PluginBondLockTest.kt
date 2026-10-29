package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginBondLockTest {
    @get:Rule val compose = createComposeRule()

    private val lockedKinds = setOf(BuiltInPlugin.DITTO, BuiltInPlugin.EVERYWHERE_COMMS,
        BuiltInPlugin.SOMEWEAR_LABS, BuiltInPlugin.DATA_SYNC)

    @Test fun onlyFourStableBuiltInIdsAreBondLocked() {
        assertEquals(lockedKinds, BuiltInPlugin.entries.filter { it.locksDuringPhoneManagement }.toSet())
    }

    @Test fun bondDisablesBothActionsReplacesAccessibleSubtitleAndReleaseRestoresIt() {
        var kind by mutableStateOf(BuiltInPlugin.DITTO)
        var bonded by mutableStateOf(false)
        var original by mutableStateOf("Original status")
        var checked by mutableStateOf(true)
        var toggles = 0
        var details = 0
        compose.setContent {
            WearTAKCIVTheme {
                BuiltInPluginToggleChip(kind, bonded, "Transport", original, checked,
                    { checked = it; toggles++ }, { details++ })
            }
        }
        for (next in lockedKinds) {
            for (subtitle in listOf("Original status", "")) {
                compose.runOnIdle { kind = next; original = subtitle; bonded = true; checked = true }
                compose.onNodeWithText("Bonded").assertIsDisplayed()
                if (subtitle.isNotEmpty()) compose.onNodeWithText(subtitle).assertDoesNotExist()
                compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
                    .performTouchInput { click() }
                compose.onNodeWithText("Transport").assertIsNotEnabled().performTouchInput { click() }
                compose.runOnIdle { assertEquals(0, toggles); assertEquals(0, details) }
                compose.onAllNodes(hasText("Bonded")).assertCountEquals(1)
                compose.runOnIdle { bonded = false }
                compose.onNodeWithText("Bonded").assertDoesNotExist()
                if (subtitle.isNotEmpty()) compose.onNodeWithText(subtitle).assertIsDisplayed()
                compose.onNodeWithText("Transport").assertIsEnabled()
                compose.onNode(isToggleable()).assertIsEnabled().assertIsOn()
            }
        }
        compose.onNode(isToggleable()).performClick().assertIsOff()
        compose.onNodeWithText("Transport").performClick()
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(1, details) }
    }

    @Test fun otherBuiltInsAndCompanionRemainToggleableWithOriginalSubtitles() {
        var kind by mutableStateOf(BuiltInPlugin.COMMANDS)
        var checked by mutableStateOf(true)
        var toggles = 0
        var details = 0
        compose.setContent {
            WearTAKCIVTheme {
                if (kind == BuiltInPlugin.COMPANION) {
                    CompanionPluginChip(checked, "Original status", { checked = it; toggles++ }, { details++ })
                } else BuiltInPluginToggleChip(kind, true, "Other entry", "Original status", checked,
                    { checked = it; toggles++ }, { details++ })
            }
        }
        for (next in BuiltInPlugin.entries.filterNot { it in lockedKinds }) {
            compose.runOnIdle { kind = next; checked = true }
            compose.onNodeWithText("Bonded").assertDoesNotExist()
            compose.onNodeWithText("Original status").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNode(isToggleable()).assertIsEnabled().performClick().assertIsOff()
        }
        compose.runOnIdle {
            assertEquals(BuiltInPlugin.entries.size - lockedKinds.size, toggles)
            assertEquals(toggles, details)
        }
    }

    @Test fun bloodhoundMenuLabelIsRenamedInEverySupportedLocale() {
        val context = RuntimeEnvironment.getApplication()
        for (language in listOf("en", "ar", "de", "es", "fr", "ja", "ko", "tr", "vi", "zh")) {
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(language))
            val localized = context.createConfigurationContext(configuration)
            assertEquals(language, "Bloodhound", localized.getString(R.string.bloodhound_compass))
        }
    }

    @Test fun bondedPluginLabelsFit192dpRound() = verifyFit(192)

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun bondedPluginLabelsFit227dpRound() = verifyFit(227)

    private fun verifyFit(size: Int) {
        val names = mapOf(BuiltInPlugin.DITTO to "Ditto Edge Sync",
            BuiltInPlugin.EVERYWHERE_COMMS to "Everywhere",
            BuiltInPlugin.SOMEWEAR_LABS to "Somewear Labs", BuiltInPlugin.DATA_SYNC to "Data Sync")
        var kind by mutableStateOf(BuiltInPlugin.DITTO)
        compose.setContent {
            WearTAKCIVTheme {
                Box(Modifier.requiredSize(size.dp).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center) {
                    BuiltInPluginToggleChip(kind, true, names.getValue(kind), "Original status",
                        true, {}, {})
                }
            }
        }
        for (next in lockedKinds) {
            compose.runOnIdle { kind = next }
            compose.onNodeWithText(names.getValue(next), useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Bonded", useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Bonded overflows at $size", layouts.single().hasVisualOverflow)
        }
    }
}
