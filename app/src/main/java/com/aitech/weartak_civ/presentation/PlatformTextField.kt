package com.aitech.weartak_civ.presentation

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material.MaterialTheme.colors

internal const val WEARTAK_TEXT_FIELD_TAG = "weartak_platform_text_field"

// Platform EditText instead of a Compose text field: on the Galaxy Watch Ultra 1 (Samsung
// HoneyBoard, forced fullscreen) the Compose field stopped showing typed text after the first
// character, while the platform field stayed in sync.
@Composable
internal fun PlatformTextField(
    string: String,
    label: String,
    keyboardType: KeyboardType,
    keyboardActions: KeyboardActions,
    autoFocus: Boolean = false
): TextFieldValue {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(string, TextRange(string.length)))
    }
    val actions by rememberUpdatedState(keyboardActions)
    val holder = remember { arrayOfNulls<EditText>(1) }
    var hadFocus by remember { mutableStateOf(false) }
    val primary = colors.primary

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = primary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    tag = WEARTAK_TEXT_FIELD_TAG
                    background = null
                    isSaveEnabled = false
                    setSingleLine(true)
                    inputType = inputTypeFor(keyboardType)
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    hint = label
                    setHintTextColor(primary.copy(alpha = 0.6f).toArgb())
                    setTextColor(primary.toArgb())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setText(value.text)
                    setSelection(
                        value.selection.start.coerceIn(0, text.length),
                        value.selection.end.coerceIn(0, text.length)
                    )
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            value = TextFieldValue(s?.toString().orEmpty(), TextRange(selectionStart, selectionEnd))
                        }
                    })
                    setOnEditorActionListener { view, actionId, event ->
                        val done = actionId == EditorInfo.IME_ACTION_DONE ||
                            (actionId == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                                event.action == KeyEvent.ACTION_DOWN)
                        if (!done) return@setOnEditorActionListener false
                        val scope = object : KeyboardActionScope {
                            override fun defaultKeyboardAction(imeAction: ImeAction) = hideKeyboard(view as EditText)
                        }
                        val onDone = actions.onDone
                        if (onDone != null) onDone.invoke(scope) else hideKeyboard(view as EditText)
                        true
                    }
                    holder[0] = this
                    if (autoFocus) {
                        post {
                            requestFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .border(1.dp, primary, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp)
                .onFocusChanged { state ->
                    // Callers clear Compose focus to dismiss the keyboard; mirror that onto the EditText.
                    if (hadFocus && !state.hasFocus) holder[0]?.let { hideKeyboard(it) }
                    hadFocus = state.hasFocus
                }
        )
    }
    return value
}

private fun hideKeyboard(editText: EditText) {
    editText.clearFocus()
    val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(editText.windowToken, 0)
}

internal fun inputTypeFor(keyboardType: KeyboardType): Int = when (keyboardType) {
    KeyboardType.Number -> InputType.TYPE_CLASS_NUMBER
    KeyboardType.Decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    KeyboardType.NumberPassword -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    KeyboardType.Phone -> InputType.TYPE_CLASS_PHONE
    KeyboardType.Email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    KeyboardType.Uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    KeyboardType.Password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else -> InputType.TYPE_CLASS_TEXT
}
