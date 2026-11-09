package com.aitech.weartak_civ.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultCode
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultParcel
import com.weartak.plugin.sdk.mopp.model.MoppPpeStateParcel
import kotlinx.coroutines.flow.first

internal class MoppPpeStore(
    private val store: DataStore<Preferences>,
    private val available: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(): MoppPpeStateParcel = snapshot(store.data.first())

    suspend fun save(revision: Long, mutationId: String?, selection: String?): MoppPpeResultParcel =
        mutate(revision, mutationId, "save:$revision:$selection", selection, null)

    suspend fun setEnabled(revision: Long, mutationId: String?, enabled: Boolean): MoppPpeResultParcel =
        mutate(revision, mutationId, "enable:$revision:$enabled", null, enabled)

    private suspend fun mutate(
        revision: Long,
        mutationId: String?,
        fingerprint: String,
        selection: String?,
        enabled: Boolean?,
    ): MoppPpeResultParcel {
        if (revision < 0 || mutationId.isNullOrBlank() || mutationId.length > 128 ||
            mutationId.any(Char::isISOControl) ||
            (enabled == null && MoppStatus.entries.none { it.key == selection })) {
            return MoppPpeResultParcel(false, MoppPpeResultCode.INVALID_REQUEST, null)
        }
        var code = MoppPpeResultCode.OK
        val persisted = store.edit { prefs ->
            code = when {
                prefs[LAST_MUTATION] == mutationId ->
                    if (prefs[LAST_REQUEST] == fingerprint) MoppPpeResultCode.OK
                    else MoppPpeResultCode.INVALID_REQUEST
                (prefs[REVISION] ?: 0L) != revision -> MoppPpeResultCode.REVISION_CONFLICT
                revision == Long.MAX_VALUE -> MoppPpeResultCode.INVALID_REQUEST
                else -> {
                    // Legacy toggle requests still participate in CAS, but cannot disable publishing.
                    prefs[ENABLED] = true
                    if (enabled == null) {
                        prefs[STATUS] = requireNotNull(selection)
                        prefs[SAVED_AT] = now()
                    }
                    prefs[REVISION] = revision + 1
                    prefs[LAST_MUTATION] = mutationId
                    prefs[LAST_REQUEST] = fingerprint
                    MoppPpeResultCode.OK
                }
            }
        }
        return MoppPpeResultParcel(code == MoppPpeResultCode.OK, code, snapshot(persisted))
    }

    private fun snapshot(prefs: Preferences): MoppPpeStateParcel {
        val selection = prefs[STATUS]?.takeIf { key -> MoppStatus.entries.any { it.key == key } }
        return MoppPpeStateParcel(
            revision = prefs[REVISION] ?: 0L,
            selection = selection,
            savedAtEpochMillis = prefs[SAVED_AT].takeIf { selection != null },
            enabled = true,
            contributing = selection != null && available(),
            lastMutationId = prefs[LAST_MUTATION],
        )
    }

    companion object {
        val STATUS = stringPreferencesKey("mopp_status")
        val SAVED_AT = longPreferencesKey("mopp_saved_at")
        val ENABLED = booleanPreferencesKey("enable_mopp")
        val REVISION = longPreferencesKey("mopp_revision")
        val LAST_MUTATION = stringPreferencesKey("mopp_last_mutation")
        val LAST_REQUEST = stringPreferencesKey("mopp_last_request")

        fun recordLegacyWrite(prefs: MutablePreferences) {
            val revision = prefs[REVISION] ?: 0L
            check(revision < Long.MAX_VALUE) { "MOPP revision exhausted" }
            prefs[REVISION] = revision + 1
            prefs.remove(LAST_MUTATION)
            prefs.remove(LAST_REQUEST)
        }
    }
}
