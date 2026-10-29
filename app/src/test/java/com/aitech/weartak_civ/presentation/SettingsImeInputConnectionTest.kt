package com.aitech.weartak_civ.presentation

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsImeInputConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var connection: InputConnection
    private val editor = EditorInfo()
    private var value = TextFieldValue()
    private var recomposition by mutableStateOf(0)
    private var doneCount = 0

    private fun showField(
        initial: String = "",
        keyboardType: KeyboardType = KeyboardType.Unspecified,
        actions: KeyboardActions = KeyboardActions.Default
    ) {
        compose.setContent {
            recomposition
            value = WearTAKOutlinedTextField(initial, "Callsign", keyboardType, actions)
        }
        val field = compose.platformField()
        compose.runOnUiThread {
            field.requestFocus()
            connection = requireNotNull(field.onCreateInputConnection(editor))
        }
    }

    private fun assertText(expected: String) {
        compose.waitForIdle()
        assertEquals(expected, compose.platformText())
        compose.runOnIdle {
            assertEquals(expected, value.text)
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
            assertEquals(expected, extracted.text.toString())
            assertEquals(expected.length, extracted.selectionEnd)
        }
    }

    @Test fun everyImeKeystrokeReachesFieldExtractedTextAndReturnedValue() {
        showField()
        assertEquals(InputType.TYPE_CLASS_TEXT, editor.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(EditorInfo.IME_ACTION_DONE, editor.imeOptions and EditorInfo.IME_MASK_ACTION)
        val typed = StringBuilder()
        for (ch in "ODINX") {
            typed.append(ch)
            compose.runOnUiThread { assertTrue(connection.setComposingText(typed, 1)) }
            assertText(typed.toString())
        }
        compose.runOnUiThread { assertTrue(connection.commitText("ODIN-ATAK", 1)) }
        assertText("ODIN-ATAK")
    }

    @Test fun batchedTypingDeletionAndRecompositionPreserveInput() {
        showField()
        compose.runOnUiThread {
            connection.beginBatchEdit()
            connection.commitText("ODIN", 1)
            connection.commitText("-ATAK", 1)
            connection.endBatchEdit()
        }
        assertText("ODIN-ATAK")
        compose.runOnIdle { recomposition++ }
        assertText("ODIN-ATAK")
        compose.runOnUiThread { assertTrue(connection.deleteSurroundingText(5, 0)) }
        assertText("ODIN")
    }

    @Test fun prefilledValueStartsWithCursorAtEndAndAcceptsReplacement() {
        showField("ODIN-ATAK")
        assertText("ODIN-ATAK")
        compose.runOnUiThread {
            connection.setSelection(0, 9)
            connection.setComposingText("ODIN-TAKX", 1)
            connection.finishComposingText()
        }
        assertText("ODIN-TAKX")
    }

    @Test fun keyboardDoneRunsSuppliedActionAndNumberFieldsUseNumericKeyboard() {
        showField("5", KeyboardType.Number, KeyboardActions(onDone = { doneCount++ }))
        assertEquals(InputType.TYPE_CLASS_NUMBER, editor.inputType and InputType.TYPE_MASK_CLASS)
        compose.pressPlatformImeDone()
        assertEquals(1, doneCount)
        assertEquals("5", compose.platformText())
    }
}
