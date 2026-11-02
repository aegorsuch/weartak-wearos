package com.aitech.weartak_civ.presentation

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses only a test host and injected callbacks; never launches WearTAK services or sends alerts. */
class PlatformAccessibilityTreeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun exportedComposeActionsAndNativeFieldsExistInAndroidAccessibilityTree() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalInfo = automation.serviceInfo
        val originalFlags = originalInfo.flags
        automation.serviceInfo = originalInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try {
            compose.setContent {
                WearTAKCIVTheme {
                    Column(Modifier.automationRoot("tak_server_add_form")) {
                        WearTAKTitleAddButton("Add TAK server", Modifier.testTag("tak_server_add")) {}
                        WearTAKOutlinedTextField("", "Connection name", fieldId = R.id.tak_server_name)
                        WearTAKConfirmCancelButtons({}, {})
                    }
                }
            }
            val expected = setOf("tak_server_add", "form_confirm", "form_cancel")
            compose.waitUntil(10_000) {
                val nodes = flatten(automation.rootInActiveWindow)
                expected.all { id -> nodes.any { it.viewIdResourceName == id && it.isClickable } } &&
                    nodes.any { it.viewIdResourceName?.endsWith(":id/tak_server_name") == true && it.isEditable }
            }
            val nodes = flatten(automation.rootInActiveWindow)
            assertEquals("Add TAK server", nodes.single { it.viewIdResourceName == "tak_server_add" }.contentDescription)
            assertEquals("Confirm", nodes.single { it.viewIdResourceName == "form_confirm" }.contentDescription)
            assertEquals("Cancel", nodes.single { it.viewIdResourceName == "form_cancel" }.contentDescription)
        } finally {
            automation.serviceInfo = originalInfo.apply { flags = originalFlags }
        }
    }

    private fun flatten(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) +
            (0 until node.childCount).flatMap { flatten(node.getChild(it)) }
}
