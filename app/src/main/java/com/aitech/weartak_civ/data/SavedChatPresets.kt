/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.savedChatPresetsDataStore by preferencesDataStore(name = "saved_chat_presets")

/** User-saved chat quick replies. Same rules as saved Manual Alerts. */
object SavedChatPresets {
    const val MAX_COUNT = SavedCustomAlerts.MAX_COUNT
    const val MAX_LENGTH = SavedCustomAlerts.MAX_LENGTH

    fun add(existing: List<String>, text: String, builtIns: List<String>): List<String>? {
        val normalized = SavedCustomAlerts.normalize(text) ?: return null
        if (existing.size >= MAX_COUNT) return null
        if ((builtIns + existing).any { it.equals(normalized, ignoreCase = true) }) return null
        return existing + normalized
    }

    fun remove(existing: List<String>, text: String): List<String> =
        existing.filterNot { it.equals(text.trim(), ignoreCase = true) }
}

class SavedChatPresetsStore(context: Context) {
    private val dataStore = context.applicationContext.savedChatPresetsDataStore
    private val gson = Gson()
    private val key = stringPreferencesKey("presets_json")
    private val listType = object : TypeToken<List<String>>() {}.type

    val presets: Flow<List<String>> = dataStore.data.map { decode(it[key]) }

    suspend fun add(text: String, builtIns: List<String>): Boolean {
        var added = false
        dataStore.edit { prefs ->
            SavedChatPresets.add(decode(prefs[key]), text, builtIns)?.let {
                prefs[key] = gson.toJson(it)
                added = true
            }
        }
        return added
    }

    suspend fun remove(text: String) {
        dataStore.edit { prefs -> prefs[key] = gson.toJson(SavedChatPresets.remove(decode(prefs[key]), text)) }
    }

    private fun decode(json: String?): List<String> = runCatching {
        gson.fromJson<List<String>>(json ?: "[]", listType).orEmpty().mapNotNull(SavedCustomAlerts::normalize)
    }.getOrDefault(emptyList())
}
