package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.cot.CotXml
import com.weartak.plugin.sdk.pli.v1.PliDetailContractV1

/** Validates and encodes a single provider's untrusted PLI detail response. */
internal object PliDetailXml {
    const val MAX_TAGS = 4
    const val MAX_ATTRIBUTES = 16
    const val MAX_BYTES = 8192

    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9._-]{0,63}")
    private val reservedTags = setOf(
        "event", "point", "detail", "takv", "status", "precisionlocation",
        "weartak_location", "remarks", "_atmist_", "physiology", "contact",
        "__group", "track", "ditto", "uid", "link", "emergency", "usericon",
        "color", "__chat", "marti", "__forcedelete",
    )

    fun validTag(tag: String): Boolean =
        namePattern.matches(tag) && tag.lowercase(java.util.Locale.ROOT) !in reservedTags &&
            !tag.startsWith("xml", ignoreCase = true)

    fun encode(details: List<Bundle>, allowedTags: Set<String>): String {
        require(details.size <= MAX_TAGS) { "Too many PLI details" }
        val seen = mutableSetOf<String>()
        val xml = StringBuilder()
        for (detail in details) {
            val tag = detail.get(PliDetailContractV1.KEY_TAG) as? String
                ?: throw IllegalArgumentException("Missing detail tag")
            require(validTag(tag) && tag in allowedTags && seen.add(tag)) { "Invalid detail tag" }
            val attributes = detail.get(PliDetailContractV1.KEY_ATTRIBUTES) as? Bundle
                ?: throw IllegalArgumentException("Missing detail attributes")
            require(attributes.size() <= MAX_ATTRIBUTES) { "Too many detail attributes" }
            val entries = attributes.keySet().sorted().map { key ->
                require(namePattern.matches(key) && !key.startsWith("xml", ignoreCase = true)) {
                    "Invalid attribute name"
                }
                val value = attributes.get(key) as? String
                    ?: throw IllegalArgumentException("Invalid attribute value")
                require(value.length <= MAX_BYTES && isValidXmlText(value)) { "Invalid attribute text" }
                key to CotXml.escape(value)
            }
            val rawText = detail.get(PliDetailContractV1.KEY_TEXT)
            val text = if (rawText != null) {
                val value = rawText as? String ?: throw IllegalArgumentException("Invalid detail text")
                require(value.length <= MAX_BYTES && isValidXmlText(value)) { "Invalid detail text" }
                CotXml.escape(value)
            } else null
            xml.append(CotXml.element(tag, entries, text))
            require(xml.length <= MAX_BYTES && xml.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "PLI detail response too large"
            }
        }
        return xml.toString()
    }

    private fun isValidXmlText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (codePoint != 0x9 && codePoint != 0xA && codePoint != 0xD &&
                codePoint !in 0x20..0xD7FF && codePoint !in 0xE000..0xFFFD &&
                codePoint !in 0x10000..0x10FFFF
            ) return false
            index += Character.charCount(codePoint)
        }
        return true
    }
}
