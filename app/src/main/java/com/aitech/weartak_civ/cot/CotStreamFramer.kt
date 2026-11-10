/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.cot

enum class CotStreamDiscardReason {
    MALFORMED_PREFIX,
    OVERSIZED_EVENT,
    OVERSIZED_NON_COT_BUFFER
}

/** Frames complete CoT events from arbitrary socket chunks without owning socket lifecycle. */
class CotStreamFramer(
    private val maxBufferChars: Int = DEFAULT_MAX_BUFFER_CHARS,
    private val onDiscard: (CotStreamDiscardReason) -> Unit = {}
) {
    private val buffer = StringBuilder()

    init {
        require(maxBufferChars > 0) { "Maximum CoT buffer size must be positive" }
    }

    fun append(chunk: String): List<String> {
        buffer.append(chunk)
        val events = mutableListOf<String>()

        while (true) {
            val startIndex = buffer.indexOf(EVENT_OPEN)
            if (startIndex < 0) {
                if (buffer.length > maxBufferChars) {
                    discardBuffer(CotStreamDiscardReason.OVERSIZED_NON_COT_BUFFER)
                }
                break
            }
            if (startIndex > 0) buffer.delete(0, startIndex)

            val endIndex = buffer.indexOf(EVENT_CLOSE)
            val nestedStartIndex = buffer.indexOf(EVENT_OPEN, EVENT_OPEN.length)
            if (nestedStartIndex >= 0 && (endIndex < 0 || nestedStartIndex < endIndex)) {
                onDiscard(CotStreamDiscardReason.MALFORMED_PREFIX)
                buffer.delete(0, nestedStartIndex)
                continue
            }
            if (endIndex < 0) {
                if (buffer.length > maxBufferChars) {
                    discardBuffer(CotStreamDiscardReason.OVERSIZED_EVENT)
                }
                break
            }

            val messageEnd = endIndex + EVENT_CLOSE.length
            events += buffer.substring(0, messageEnd)
            buffer.delete(0, messageEnd)
        }

        return events
    }

    private fun discardBuffer(reason: CotStreamDiscardReason) {
        onDiscard(reason)
        buffer.clear()
    }

    private companion object {
        const val EVENT_OPEN = "<event"
        const val EVENT_CLOSE = "</event>"
        const val DEFAULT_MAX_BUFFER_CHARS = 1_048_576
    }
}
