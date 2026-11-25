package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.pli.v1.PliDetailContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PliDetailXmlTest {
    private fun detail(tag: String = "hail", text: String? = null, attributes: Bundle = Bundle()) =
        Bundle().apply {
            putString(PliDetailContractV1.KEY_TAG, tag)
            putBundle(PliDetailContractV1.KEY_ATTRIBUTES, attributes)
            if (text != null) putString(PliDetailContractV1.KEY_TEXT, text)
        }

    @Test fun escapesProviderValuesInsteadOfAcceptingXml() {
        val attrs = Bundle().apply { putString("version", "1'&") }
        assertEquals(
            "<hail version='1&apos;&amp;'>&lt;injected/&gt;</hail>",
            PliDetailXml.encode(listOf(detail(text = "<injected/>", attributes = attrs)), setOf("hail")),
        )
    }

    @Test fun rejectsReservedUnregisteredAndDuplicateTags() {
        assertFalse(PliDetailXml.validTag("contact"))
        assertFalse(PliDetailXml.validTag("XMLtest"))
        assertThrows(IllegalArgumentException::class.java) {
            PliDetailXml.encode(listOf(detail("contact")), setOf("contact"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PliDetailXml.encode(listOf(detail()), setOf("other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PliDetailXml.encode(listOf(detail(), detail()), setOf("hail"))
        }
    }

    @Test fun rejectsInvalidTextAndAttributeTypes() {
        val badAttribute = Bundle().apply { putInt("version", 1) }
        assertThrows(IllegalArgumentException::class.java) {
            PliDetailXml.encode(listOf(detail(attributes = badAttribute)), setOf("hail"))
        }
        for (text in listOf("\u0000", "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) {
                PliDetailXml.encode(listOf(detail(text = text)), setOf("hail"))
            }
        }
    }

    @Test fun enforcesEscapedUtf8ByteLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            PliDetailXml.encode(listOf(detail(text = "&".repeat(2000))), setOf("hail"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PliDetailXml.encode(listOf(detail(text = "\u00e9".repeat(4090))), setOf("hail"))
        }
    }
}
