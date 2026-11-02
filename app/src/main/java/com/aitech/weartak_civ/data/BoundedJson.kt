package com.aitech.weartak_civ.data

internal fun requireJsonNestingWithinLimit(value: String, maximumDepth: Int = 32) {
    var depth = 0
    var inString = false
    var escaped = false
    value.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> require(++depth <= maximumDepth) {
                    "JSON nesting exceeds $maximumDepth levels"
                }
                '}', ']' -> require(--depth >= 0) { "Invalid JSON nesting" }
            }
        }
    }
}
