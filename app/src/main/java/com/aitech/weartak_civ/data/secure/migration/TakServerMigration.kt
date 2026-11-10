/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.data.secure.takserver.TakServerSecretsRepository
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import com.aitech.weartak_civ.data.takserver.TakServerEndpointIdentity
import com.aitech.weartak_civ.data.takserver.TakServerId
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

private val TAK_SERVER_LIST_KEY = stringPreferencesKey("tak_server_list")

class TakServerMigration(
    private val legacyDataStore: DataStore<Preferences>,
    private val secretsRepository: TakServerSecretsRepository
) {
    private val gson = Gson()

    suspend fun runIfNeeded() = TakServerEndpointIdentity.mutationMutex.withLock {
        when (val encrypted = secretsRepository.takServerListRead.first()) {
            is DecryptResult.Success -> if (encrypted.plaintext.isNotBlank()) {
                val uniqueJson = deduplicateJson(encrypted.plaintext)
                if (uniqueJson != encrypted.plaintext) {
                    secretsRepository.setTakServerListJson(uniqueJson)
                }
                return@withLock
            }
            is DecryptResult.Failure -> {
                throw IllegalStateException("Unable to decrypt TAK server list", encrypted.error)
            }
            null -> Unit
        }

        val legacy = legacyDataStore.data.map { it[TAK_SERVER_LIST_KEY] }.first()
        if (!legacy.isNullOrBlank()) {
            secretsRepository.setTakServerListJson(deduplicateJson(legacy))
            legacyDataStore.edit { it.remove(TAK_SERVER_LIST_KEY) }
        }
    }

    private fun deduplicateJson(json: String): String {
        val type = object : TypeToken<List<TakServerItem>>() {}.type
        val items = gson.fromJson<List<TakServerItem>>(json, type)
        return gson.toJson(migrateItems(items))
    }

    internal companion object {
        fun migrateItems(items: List<TakServerItem>): List<TakServerItem> {
            val usedIds = mutableSetOf<String>()
            return items
                .distinctBy(TakServerEndpointIdentity::key)
                .map { item ->
                    item.copy(serverId = TakServerId.ensureUnique(item.serverId, usedIds))
                }
        }
    }
}
