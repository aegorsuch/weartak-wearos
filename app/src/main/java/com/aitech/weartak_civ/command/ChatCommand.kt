/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.command

/** Remote chat command. Values must never be included in diagnostics. */
data class ChatCommand(val name: String, val args: Map<String, String>) {
    override fun toString() = "ChatCommand(name=$name, keys=${args.keys})"
}

sealed interface ChatCommandParse {
    object NotACommand : ChatCommandParse
    data class Malformed(val reason: String) : ChatCommandParse
    data class Parsed(val command: ChatCommand) : ChatCommandParse
}

object ChatCommandParser {
    const val PREFIX = "#weartakcommand"
    const val MAX_LENGTH = 768
    const val MAX_ARGS = 12
    const val MAX_VALUE_LENGTH = 128
    const val MAX_PDL_LENGTH = 16_384
    const val MAX_PDL_COMMAND_LENGTH = 17_408
    private val NAME = Regex("[a-z][a-z0-9]{0,31}")
    private val KEY = Regex("[a-z][a-z0-9]{0,15}")

    /** Anything not starting with the prefix is ordinary chat and is never inspected further. */
    fun parse(text: String): ChatCommandParse {
        val body = text.trim()
        if (!body.startsWith(PREFIX, ignoreCase = true)) return ChatCommandParse.NotACommand
        if (body.length > MAX_PDL_COMMAND_LENGTH) return ChatCommandParse.Malformed("too long")
        val rest = body.substring(PREFIX.length).trimStart()
        val nameEnd = rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it }
        val name = rest.substring(0, nameEnd).lowercase()
        if (!NAME.matches(name)) return ChatCommandParse.Malformed("invalid command name")
        val isPdlCommand = name == "setpdl"
        val isP12Command = name == "usep12"
        if (body.length > if (isPdlCommand || isP12Command) MAX_PDL_COMMAND_LENGTH else MAX_LENGTH) {
            return ChatCommandParse.Malformed("too long")
        }
        if (name in NetworkMasterCommandHandler.COMMANDS) {
            if (rest.substring(nameEnd).isNotBlank()) {
                return ChatCommandParse.Malformed("$name takes no arguments")
            }
            return ChatCommandParse.Parsed(ChatCommand(name, emptyMap()))
        }
        if (name == "enrollserver") {
            val input = rest.substring(nameEnd).trim()
            if (!Regex("^(name|url|port|username|password)=", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
                val fields = input.split(',', limit = 5).map(String::trim)
                if (fields.size !in setOf(3, 5) || fields.take(3).any(String::isBlank) ||
                    fields.any { field -> field.any(Char::isISOControl) }) {
                    return ChatCommandParse.Malformed("enrollserver needs name, address, port[, username, password]")
                }
                val args = linkedMapOf("name" to fields[0], "url" to fields[1], "port" to fields[2])
                if (fields.size == 5) {
                    if (fields[3].isBlank() != fields[4].isBlank()) {
                        return ChatCommandParse.Malformed("Provide both username and password")
                    }
                    if (fields[3].isNotEmpty()) {
                        args["username"] = fields[3]
                        args["password"] = fields[4]
                    }
                }
                return ChatCommandParse.Parsed(ChatCommand(name, args))
            }
        }
        if (isPdlCommand) {
            val value = rest.substring(nameEnd).trim()
            if (value.isEmpty() || value.length > MAX_PDL_LENGTH || !Regex("[A-Za-z0-9_-]+={0,2}").matches(value)) {
                return ChatCommandParse.Malformed("setpdl needs community or one encoded PDL")
            }
            return ChatCommandParse.Parsed(ChatCommand(name,
                if (value == "community") mapOf("source" to value) else mapOf("pdl" to value)))
        }
        if (name == "toggleditto") {
            val value = rest.substring(nameEnd).trim()
            if (value !in setOf("on", "off")) return ChatCommandParse.Malformed("toggleditto needs on or off")
            return ChatCommandParse.Parsed(ChatCommand(name, mapOf("state" to value)))
        }
        if (isP12Command) {
            val input = rest.substring(nameEnd).trim()
            val value = input.takeWhile { !it.isWhitespace() }
            if (value.isEmpty() || value.length > MAX_PDL_LENGTH ||
                !Regex("[A-Za-z0-9+/=_-]+").matches(value)) {
                return ChatCommandParse.Malformed("usep12 needs encoded P12 and server=<configured-id>")
            }
            val tail = input.substring(value.length)
            val options = parse("${PREFIX}certoptions$tail")
            if (options !is ChatCommandParse.Parsed ||
                options.command.args.keys.any { it !in setOf("server", "password") }) {
                return ChatCommandParse.Malformed("usep12 needs encoded P12 server=<configured-id> [password=\"...\"]")
            }
            return ChatCommandParse.Parsed(ChatCommand(name, options.command.args + ("p12" to value)))
        }
        val args = LinkedHashMap<String, String>()
        var i = nameEnd
        while (true) {
            while (i < rest.length && rest[i].isWhitespace()) i++
            if (i >= rest.length) break
            val eq = rest.indexOf('=', i)
            if (eq < 0) return ChatCommandParse.Malformed("argument without value")
            val key = rest.substring(i, eq).lowercase()
            if (!KEY.matches(key)) return ChatCommandParse.Malformed("invalid argument name")
            if (key in args) return ChatCommandParse.Malformed("duplicate argument")
            if (args.size >= MAX_ARGS) return ChatCommandParse.Malformed("too many arguments")
            i = eq + 1
            val value = StringBuilder()
            if (i < rest.length && rest[i] == '"') {
                i++
                var closed = false
                while (i < rest.length) {
                    val c = rest[i++]
                    if (c == '\\' && i < rest.length && (rest[i] == '"' || rest[i] == '\\')) value.append(rest[i++])
                    else if (c == '"') { closed = true; break }
                    else value.append(c)
                }
                if (!closed) return ChatCommandParse.Malformed("unterminated quote")
                if (i < rest.length && !rest[i].isWhitespace()) return ChatCommandParse.Malformed("text after quote")
            } else {
                while (i < rest.length && !rest[i].isWhitespace()) value.append(rest[i++])
            }
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH || value.any { it.isISOControl() }) {
                return ChatCommandParse.Malformed("invalid argument value")
            }
            args[key] = value.toString()
        }
        return ChatCommandParse.Parsed(ChatCommand(name, args))
    }
}