package com.aitech.weartak_civ.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal val defaultConnectionPriority = listOf(
    "companion", "tak", "sitx", "tak_connecting", "sitx_connecting", "multicast", "ditto"
)

internal fun connectionPriority(saved: List<String>, available: List<String>): List<String> =
    (saved + defaultConnectionPriority + available.sorted()).distinct().filter {
        it in defaultConnectionPriority || it.startsWith("plugin:") && it.length > "plugin:".length
    }

internal fun moveConnectionPriority(
    saved: List<String>, available: List<String>, key: String, offset: Int
): List<String> {
    require(offset == -1 || offset == 1)
    val order = connectionPriority(saved, available).toMutableList()
    val visible = order.filter { it in available }
    val index = visible.indexOf(key)
    if (index < 0) return order
    val neighbor = visible.getOrNull(index + offset) ?: return order
    val from = order.indexOf(key)
    val to = order.indexOf(neighbor)
    order[from] = neighbor
    order[to] = key
    return order
}

internal class ConnectionStatusPreferences(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("home_connection_status_priority")
    val priority = store.data.map { connectionPriority(decode(it[key]), emptyList()) }.distinctUntilChanged()

    suspend fun move(available: List<String>, route: String, offset: Int) {
        store.edit { it[key] = moveConnectionPriority(decode(it[key]), available, route, offset).joinToString("\n") }
    }

    suspend fun reset() {
        store.edit { it.remove(key) }
    }

    private fun decode(saved: String?): List<String> = saved?.split('\n').orEmpty()
}
