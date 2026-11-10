/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.command

import com.aitech.weartak_civ.data.SavedCustomAlerts
import com.aitech.weartak_civ.ui.formatter.UnitPrefs
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

data class PresetServer(
    val name: String? = null,
    val url: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null
)

/**
 * A profile and server list provisioned onto the watch as a JSON file; never part of the repository.
 * [unitPrefs] uses ATAK keys (`rab_rng_units_pref`: "1" Metric, "0" Imperial); [customAlerts] are
 * saved Manual Alert labels merged into the watch's list.
 */
data class ProfilePreset(
    val profile: Map<String, String>? = null,
    val servers: List<PresetServer>? = null,
    val unitPrefs: Map<String, String>? = null,
    val customAlerts: List<String>? = null
)

object ProfilePresetParser {
    private val NAME = Regex("[a-z0-9][a-z0-9\\-]{0,31}")

    fun validName(name: String?): String? = name?.trim()?.lowercase()?.takeIf { NAME.matches(it) }

    fun parse(json: String): ProfilePreset? = try {
        Gson().fromJson(json, ProfilePreset::class.java)
    } catch (error: JsonSyntaxException) {
        null
    }
}

fun interface PresetSource {
    suspend fun load(name: String): String?
}

fun interface PresetUnitPrefsSink {
    suspend fun apply(updates: Map<String, String>)
}

fun interface PresetCustomAlertsSink {
    /** Returns how many alerts were newly added. */
    suspend fun merge(alerts: List<String>): Int
}

fun interface PresetServerSink {
    /** Returns true when added, false when the server was already present. */
    suspend fun add(name: String, address: String, port: Int, username: String, password: String): Boolean
}

/**
 * `#weartakcommandloadprofile name=odin-weartak` applies a preset stored on the watch. Credentials
 * come from that file, never from chat, and nothing from it is logged or sent back.
 */
class LoadProfileHandler(
    private val source: PresetSource,
    private val profileHandler: ProfileHandler,
    private val serverSink: PresetServerSink,
    private val networkLocked: suspend () -> Boolean,
    private val unitPrefsSink: PresetUnitPrefsSink? = null,
    private val customAlertsSink: PresetCustomAlertsSink? = null
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        if (command.args.keys != setOf("name")) return ChatCommandResult(false, "loadprofile needs name=<preset>")
        val name = ProfilePresetParser.validName(command.args["name"])
            ?: return ChatCommandResult(false, "Invalid preset name")
        val preset = source.load(name)?.let(ProfilePresetParser::parse)
            ?: return ChatCommandResult(false, "Preset $name not found on this watch")

        val servers = preset.servers.orEmpty()
        val validated = servers.map { s ->
            val serverName = ChatCommandValidation.serverName(s.name)
            val address = ChatCommandValidation.serverAddress(s.url)
            val port = ChatCommandValidation.port(s.port?.toString())
            if (serverName == null || address == null || port == null ||
                s.username.isNullOrBlank() || s.password.isNullOrEmpty()) {
                return ChatCommandResult(false, "Preset $name has an invalid server entry; nothing changed")
            }
            ServerEntry(serverName, address, port, s.username, s.password)
        }
        if (validated.isNotEmpty() && networkLocked()) {
            return ChatCommandResult(false, "Network settings are locked; nothing changed")
        }
        val unitPrefs = preset.unitPrefs.orEmpty()
        if (unitPrefs.isNotEmpty() && runCatching { UnitPrefs.DEFAULT.withUpdates(unitPrefs) }.isFailure) {
            return ChatCommandResult(false, "Preset $name has invalid unitPrefs; nothing changed")
        }
        val customAlerts = preset.customAlerts.orEmpty().map {
            SavedCustomAlerts.normalize(it)
                ?: return ChatCommandResult(false, "Preset $name has an invalid custom alert; nothing changed")
        }
        if (customAlerts.size > SavedCustomAlerts.MAX_COUNT) {
            return ChatCommandResult(false, "Preset $name has too many custom alerts; nothing changed")
        }

        val profile = preset.profile.orEmpty()
        var profileSummary = "no profile"
        if (profile.isNotEmpty()) {
            val result = profileHandler.execute(ChatCommand("setprofile", profile.mapKeys { it.key.lowercase() }), request)
            if (!result.ok) return ChatCommandResult(false, "Preset $name profile rejected: ${result.message}")
            profileSummary = "profile applied"
        }

        var added = 0
        validated.forEach { if (serverSink.add(it.name, it.address, it.port, it.username, it.password)) added++ }
        val extras = buildString {
            if (unitPrefs.isNotEmpty() && unitPrefsSink != null) {
                unitPrefsSink.apply(unitPrefs)
                append(", units applied")
            }
            if (customAlerts.isNotEmpty() && customAlertsSink != null) {
                append(", ${customAlertsSink.merge(customAlerts)} custom alerts added")
            }
        }
        return ChatCommandResult(
            true,
            "Loaded $name: $profileSummary, $added of ${validated.size} servers added$extras"
        )
    }

    private class ServerEntry(val name: String, val address: String, val port: Int, val username: String, val password: String)
}
