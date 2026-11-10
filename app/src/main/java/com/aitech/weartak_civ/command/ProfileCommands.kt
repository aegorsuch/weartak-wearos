/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.command

import java.time.Year

/** Where validated profile values are written. Implemented over DataRepository at runtime. */
interface IdentityProfileStore {
    suspend fun setCallsign(value: String)
    suspend fun setTeam(value: String)
    suspend fun setRole(category: String, role: String)
}

interface ProfileStore : IdentityProfileStore {
    suspend fun setHeight(feet: Int, inches: Int)
    suspend fun setWeight(pounds: Int)
    suspend fun setBirthYear(year: Int)
    suspend fun setSex(value: String)
    suspend fun setUserType(value: String)
    suspend fun setPantsLength(value: Int)
    suspend fun setWaistSize(value: Int)
}

class IdentityProfileChange private constructor(
    private val applyValue: suspend (IdentityProfileStore) -> Unit
) {
    suspend fun apply(store: IdentityProfileStore) = applyValue(store)

    companion object {
        fun parse(
            key: String,
            value: String,
            teams: List<String>,
            roles: List<Pair<String, String>>
        ): IdentityProfileChange? = when (key) {
            "callsign" -> ChatCommandValidation.callsign(value)
                ?.let { v -> IdentityProfileChange { it.setCallsign(v) } }
            "team" -> teams.firstOrNull { it.equals(value.trim(), ignoreCase = true) }
                ?.let { v -> IdentityProfileChange { it.setTeam(v) } }
            "role" -> roles.firstOrNull { it.second.equals(value.trim(), ignoreCase = true) }
                ?.let { r -> IdentityProfileChange { it.setRole(r.first, r.second) } }
            else -> null
        }
    }
}

/** The values the settings screens offer, so chat commands accept exactly what the UI does. */
class ProfileOptions(
    val teams: () -> List<String>,
    val roles: () -> List<Pair<String, String>>,
    val sexes: () -> List<String>,
    val userTypes: () -> List<String>
)

/**
 * Handles the per-field commands (`setteam`, `setrole`, `setheight`, ...) and `setprofile`, which
 * takes any combination of fields. Every value is validated before anything is stored, so a bad
 * value changes nothing.
 */
class ProfileHandler(
    private val keys: Set<String>,
    private val identityLocked: suspend () -> Boolean,
    private val options: ProfileOptions,
    private val store: ProfileStore,
    private val currentYear: () -> Int = { Year.now().value }
) : ChatCommandHandler {

    private class Change(val identity: Boolean, val apply: suspend (ProfileStore) -> Unit)

    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val args = command.args
        if (args.isEmpty()) {
            return ChatCommandResult(false, "${command.name} needs ${keys.sorted().joinToString(", ") { "$it=<value>" }}")
        }
        val unknown = args.keys - keys
        if (unknown.isNotEmpty()) {
            return ChatCommandResult(false, "${command.name} does not take ${unknown.sorted().joinToString(", ")}")
        }

        val changes = mutableListOf<Change>()
        for ((key, value) in args) {
            changes += parse(key, value) ?: return ChatCommandResult(false, "Invalid $key; nothing changed")
        }
        if (changes.any { it.identity } && identityLocked()) {
            return ChatCommandResult(false, "Settings are managed; nothing changed")
        }
        changes.forEach { it.apply(store) }
        return ChatCommandResult(true, "Updated ${args.keys.sorted().joinToString(", ")}")
    }

    private fun parse(key: String, value: String): Change? = when (key) {
        "callsign", "team", "role" -> IdentityProfileChange.parse(
            key, value, if (key == "team") options.teams() else emptyList(),
            if (key == "role") options.roles() else emptyList()
        )
            ?.let { change -> Change(true) { change.apply(it) } }
        "height" -> ChatCommandValidation.height(value)?.let { h -> Change(false) { it.setHeight(h.first, h.second) } }
        "weight" -> intIn(value, 1, 1000)?.let { v -> Change(false) { it.setWeight(v) } }
        "birthyear" -> intIn(value, 1900, currentYear())?.let { v -> Change(false) { it.setBirthYear(v) } }
        "sex" -> match(value, options.sexes())?.let { v -> Change(false) { it.setSex(v) } }
        "usertype" -> match(value, options.userTypes())?.let { v -> Change(false) { it.setUserType(v) } }
        "pants" -> intIn(value, 20, 50)?.let { v -> Change(false) { it.setPantsLength(v) } }
        "waist" -> intIn(value, 20, 60)?.let { v -> Change(false) { it.setWaistSize(v) } }
        else -> null
    }

    private fun match(value: String, allowed: List<String>) =
        allowed.firstOrNull { it.equals(value.trim(), ignoreCase = true) }

    private fun intIn(value: String, min: Int, max: Int) = value.trim().toIntOrNull()?.takeIf { it in min..max }

    companion object {
        val COMMAND_KEYS: Map<String, Set<String>> = mapOf(
            "setteam" to setOf("team"),
            "setrole" to setOf("role"),
            "setheight" to setOf("height"),
            "setweight" to setOf("weight"),
            "setbirthyear" to setOf("birthyear"),
            "setsex" to setOf("sex"),
            "setusertype" to setOf("usertype"),
            "setpants" to setOf("pants"),
            "setuniformpantslengthinches" to setOf("pants"),
            "setwaist" to setOf("waist"),
            "setwaistsize" to setOf("waist"),
            "setprofile" to setOf(
                "callsign", "team", "role", "height", "weight", "birthyear", "sex", "usertype", "pants", "waist"
            )
        )
    }
}
