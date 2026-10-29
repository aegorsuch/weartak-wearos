package com.aitech.weartak_civ.presentation

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule

internal typealias ActivityComposeRule = AndroidComposeTestRule<*, ComponentActivity>

internal fun ActivityComposeRule.platformField(): EditText {
    waitForIdle()
    var field: EditText? = null
    runOnUiThread { field = activity.window.decorView.findViewWithTag(WEARTAK_TEXT_FIELD_TAG) }
    return requireNotNull(field) { "Platform text field not found" }
}

internal fun ActivityComposeRule.platformText(): String {
    val field = platformField()
    var text = ""
    runOnUiThread { text = field.text.toString() }
    return text
}

internal fun ActivityComposeRule.typePlatformText(value: String) {
    val field = platformField()
    runOnUiThread { field.text.insert(field.selectionEnd.coerceAtLeast(0), value) }
    waitForIdle()
}

internal fun ActivityComposeRule.replacePlatformText(value: String) {
    val field = platformField()
    runOnUiThread {
        field.setText(value)
        field.setSelection(value.length)
    }
    waitForIdle()
}

internal fun ActivityComposeRule.pressPlatformImeDone() {
    val field = platformField()
    // TextView.onEditorAction runs the registered OnEditorActionListener first.
    runOnUiThread { field.onEditorAction(EditorInfo.IME_ACTION_DONE) }
    waitForIdle()
}
