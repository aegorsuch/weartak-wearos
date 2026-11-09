package com.aitech.weartak_civ.presentation

import android.app.Application
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilitySemanticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rootExportsTagsWithoutHidingTextOrBuiltInToggleState() {
        val checked = mutableStateOf(false)
        compose.setContent {
            WearTAKCIVTheme {
                Column(Modifier.automationRoot("settings_test")) {
                    WearTAKToggleChip(checked.value, { checked.value = it }, title = "Enabled transport",
                        testTag = "enabled_toggle")
                    WearTAKToggleChip(true, { error("Disabled toggle dispatched") }, title = "Managed transport",
                        enabled = false, testTag = "disabled_toggle")
                    WearTAKTitleAddButton("Add TAK server", Modifier.testTag("tak_server_add")) {}
                }
            }
        }
        compose.onNodeWithTag("settings_test").assert(
            SemanticsMatcher.expectValue(SemanticsPropertiesAndroid.TestTagsAsResourceId, true))
        compose.onNodeWithTag("enabled_toggle").assertTextContains("Enabled transport")
            .assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("disabled_toggle").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("tak_server_add").assertContentDescriptionEquals("Add TAK server").assertHasClickAction()
    }

    @Test
    fun nativeServerFieldsHaveResourceIdsHintsAndNonvisualValidationErrors() {
        val invalid = mutableStateOf(true)
        val fields = listOf(
            R.id.tak_server_name to "Connection name",
            R.id.tak_server_address to "Address",
            R.id.tak_server_port to "Port",
            R.id.tak_server_username to "Username",
            R.id.tak_server_password to "Password",
        )
        compose.setContent {
            WearTAKCIVTheme {
                Column(Modifier.automationRoot("tak_server_add_form")) {
                    fields.forEach { (id, label) ->
                        WearTAKOutlinedTextField("", label, fieldId = id,
                            accessibilityError = if (invalid.value) "Enter valid values" else null)
                    }
                }
            }
        }
        compose.runOnIdle {
            fields.forEach { (id, label) ->
                val field = compose.activity.findViewById<EditText>(id)
                assertNotNull(field)
                assertEquals(label, field.hint)
                assertNull(field.contentDescription)
                val info = field.createAccessibilityNodeInfo()
                assertTrue(info.isContentInvalid)
                assertEquals("Enter valid values", info.error)
            }
            invalid.value = false
        }
        compose.runOnIdle {
            fields.forEach { (id, _) ->
                val info = compose.activity.findViewById<EditText>(id).createAccessibilityNodeInfo()
                assertFalse(info.isContentInvalid)
                assertNull(info.error)
            }
        }
    }

    @Test
    fun serverActionsDispatchThroughAccessibilityInsteadOfReportingNoopSuccess() {
        var deletes = 0
        var edits = 0
        compose.setContent {
            WearTAKCIVTheme {
                Column(Modifier.automationRoot("tak_servers")) {
                    WearTAKSwipeToRevealChip(
                        onPrimaryActionClick = { deletes++ },
                        onSecondaryActionClick = { edits++ },
                        testTag = "tak_server_test",
                        chipType = {
                            WearTAKSplitToggleChip(title = "Test server", label = "Disconnected", checked = false,
                                onCheckedChange = {}, onClick = {})
                        })
                }
            }
        }
        val actions = compose.onNodeWithTag("tak_server_test").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(listOf("Delete", "Edit"), actions.map { it.label })
            assertTrue(actions[1].action())
            assertEquals(1, edits)
            assertEquals(0, deletes)
            assertTrue(actions[0].action())
            assertEquals(1, deletes)
        }
    }

    @Test
    fun formConfirmAndCancelRetainDistinctActionsAndLocalizedLabels() {
        var confirms = 0
        var cancels = 0
        compose.setContent {
            WearTAKCIVTheme {
                Column(Modifier.automationRoot("tak_server_edit_form")) {
                    WearTAKConfirmCancelButtons({ cancels++ }, { confirms++ })
                }
            }
        }
        compose.onNodeWithTag("form_cancel").assertContentDescriptionEquals("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, confirms); assertEquals(1, cancels) }
        compose.onNodeWithTag("form_confirm").assertContentDescriptionEquals("Confirm").performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Test
    fun sosConfirmationCancelNeverDispatchesAndConfirmDispatchesOnlyInjectedCallback() {
        val show = mutableStateOf(true)
        val confirmed = mutableStateOf(false)
        val alerting = MutableLiveData(false)
        var dispatches = 0
        compose.setContent {
            WearTAKCIVTheme {
                ConfirmationAlertPage(show, confirmed, alerting, { dispatches++ }, {})
            }
        }
        compose.onNodeWithTag("sos_confirmation").assert(
            SemanticsMatcher.expectValue(SemanticsPropertiesAndroid.TestTagsAsResourceId, true))
        compose.onNodeWithTag("sos_cancel").assertTextContains("Cancel").performClick()
        compose.runOnIdle {
            assertFalse(show.value)
            assertFalse(confirmed.value)
            assertEquals(0, dispatches)
            show.value = true
        }
        compose.onNodeWithTag("sos_confirm").assertTextContains("Confirm").performClick()
        compose.runOnIdle {
            assertTrue(confirmed.value)
            assertEquals(1, dispatches)
        }
    }

    @Test
    fun manualAlertSelectionAndBackOnlyCallInjectedCallbacks() {
        var selected: String? = null
        var backs = 0
        compose.setContent {
            WearTAKCIVTheme {
                ManualAlertListScreen(listOf("Test alert"), emptyList(), { selected = it },
                    { error("Unexpected save") }, { error("Unexpected deletion") }, { backs++ })
            }
        }
        compose.onNodeWithTag("manual_alert_list").assert(
            SemanticsMatcher.expectValue(SemanticsPropertiesAndroid.TestTagsAsResourceId, true))
        compose.onNodeWithTag("manual_alert_back").performClick()
        compose.runOnIdle { assertEquals(1, backs); assertNull(selected) }
        compose.onNodeWithTag("manual_alert_builtin_0").performClick()
        compose.runOnIdle { assertEquals("Test alert", selected) }
        compose.onNodeWithTag("manual_alert_add").assertContentDescriptionEquals(
            compose.activity.getString(R.string.manual_alert_add_hint))
    }

    @Test
    fun manualAlertValidationAndDeleteCancelStaySeparateFromDispatch() {
        var saves = 0
        var deletions = 0
        compose.setContent {
            WearTAKCIVTheme {
                ManualAlertListScreen(emptyList(), listOf("Saved test alert"),
                    { error("Editing presets must not dispatch an alert") },
                    { saves++; false }, { deletions++ }, {})
            }
        }
        compose.onNodeWithTag("manual_alert_add").performClick()
        compose.onNodeWithTag("manual_alert_draft").performTextInput("Saved test alert")
        compose.onNodeWithTag("manual_alert_save").performClick()
        compose.onNodeWithTag("manual_alert_draft").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error,
                compose.activity.getString(R.string.manual_alert_validation_error)))
        compose.runOnIdle { assertEquals(1, saves); assertEquals(0, deletions) }
        compose.onNodeWithTag("manual_alert_add_cancel").performClick()
        compose.onNodeWithTag("manual_alert_saved_0_delete").performClick()
        compose.onNodeWithTag("manual_alert_delete_cancel").performClick()
        compose.runOnIdle { assertEquals(0, deletions) }
        compose.onNodeWithTag("manual_alert_saved_0_delete").performClick()
        compose.onNodeWithTag("manual_alert_delete_confirm").performClick()
        compose.runOnIdle { assertEquals(1, deletions) }
    }

    @Test
    fun sosOpenerHasNameAndActiveStateWithoutStartingAnActivity() {
        compose.setContent {
            WearTAKCIVTheme { SOSButton(compose.activity, true) }
        }
        compose.onNodeWithTag("sos_open").assertHasClickAction()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.sos))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                compose.activity.getString(R.string.sos_active)))
    }
}
