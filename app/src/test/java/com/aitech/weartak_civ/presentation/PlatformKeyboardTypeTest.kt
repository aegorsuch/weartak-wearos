package com.aitech.weartak_civ.presentation

import android.text.InputType
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformKeyboardTypeTest {
    @Test fun textAndNumericPasswordAdaptersPreserveMasking() {
        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            inputTypeFor(KeyboardType.Password)
        )
        assertEquals(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            inputTypeFor(KeyboardType.NumberPassword)
        )
    }

    @Test fun ordinaryKeyboardAdaptersKeepTheirPlatformTypes() {
        val expected = mapOf(
            KeyboardType.Unspecified to InputType.TYPE_CLASS_TEXT,
            KeyboardType.Text to InputType.TYPE_CLASS_TEXT,
            KeyboardType.Number to InputType.TYPE_CLASS_NUMBER,
            KeyboardType.Decimal to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            KeyboardType.Phone to InputType.TYPE_CLASS_PHONE,
            KeyboardType.Email to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            KeyboardType.Uri to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        )
        expected.forEach { (type, inputType) -> assertEquals(inputType, inputTypeFor(type)) }
    }
}
