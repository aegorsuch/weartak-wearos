package com.aitech.weartak_civ.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.DEFAULT_ENABLE_BATDOK_COT
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.Locale

data class BatdokSharingConsent(val enabled: Boolean, val version: Int, val revision: Long) {
    val confirmed: Boolean get() = enabled && version == BatdokSharingStore.CONSENT_VERSION
}

class BatdokSharingStore(private val store: DataStore<Preferences>) {
    val consent = store.data.map {
        BatdokSharingConsent(it[ENABLED] ?: DEFAULT_ENABLE_BATDOK_COT, it[VERSION] ?: 0, it[REVISION] ?: 0)
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit {
            if (it[ENABLED] != enabled || !enabled) it[REVISION] = (it[REVISION] ?: 0) + 1
            it[ENABLED] = enabled
            if (!enabled) it.remove(VERSION)
        }
    }

    suspend fun setEnabledByUser(enabled: Boolean) {
        store.edit {
            it[ENABLED] = enabled
            if (enabled) it[VERSION] = CONSENT_VERSION else it.remove(VERSION)
            it[REVISION] = (it[REVISION] ?: 0) + 1
        }
    }

    suspend fun patientId(): String {
        val saved = store.edit {
            if (it[PATIENT_ID] == null) {
                val random = ByteArray(8).also(SecureRandom()::nextBytes)
                it[PATIENT_ID] = random.joinToString("") { byte -> "%02x".format(Locale.US, byte) }
            }
        }[PATIENT_ID]
        check(saved != null && saved.matches(Regex("[0-9a-f]{16}"))) { "Invalid persisted BATTAK patient identity" }
        return saved
    }

    companion object {
        const val CONSENT_VERSION = 1
        internal val ENABLED = booleanPreferencesKey("enable_batdok_cot")
        private val VERSION = intPreferencesKey("batdok_patient_sharing_consent_version")
        private val REVISION = longPreferencesKey("batdok_patient_sharing_revision")
        private val PATIENT_ID = stringPreferencesKey("batdok_patient_id")
    }
}
