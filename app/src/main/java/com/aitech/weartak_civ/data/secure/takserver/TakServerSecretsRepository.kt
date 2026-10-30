/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.takserver

import kotlinx.coroutines.flow.Flow
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.data.takserver.TakServerId
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl

class TakServerSecretsRepository(private val store: TakServerEncryptedDataStore) {
    private data class ApprovedCa(val serverId: String, val host: String, val port: Int, val certificate: String)

    private fun caKey(serverId: String) =
        stringPreferencesKey("approved_ca_${requireNotNull(TakServerId.normalizeOrNull(serverId))}")

    private fun host(address: String) = HttpUrl.Builder().scheme("https").host(address.trim()).build().host

    suspend fun getApprovedCa(serverId: String, address: String, port: Int): String? {
        val read = store.encryptedStringResult(caKey(serverId)).first() ?: return null
        val json = when (read) {
            is DecryptResult.Success -> read.plaintext
            is DecryptResult.Failure -> throw IllegalStateException("Unable to decrypt approved server CA", read.error)
        }
        val approved = requireNotNull(Gson().fromJson(json, ApprovedCa::class.java))
        require(approved.serverId == serverId) { "Server CA identity mismatch" }
        return approved.certificate.takeIf { approved.host == host(address) && approved.port == port }
    }

    suspend fun setApprovedCa(serverId: String, address: String, port: Int, certificate: String?) {
        val json = certificate?.let {
            TakServerCaTrust.decode(it)
            require(port in 1..65535)
            Gson().toJson(ApprovedCa(serverId, host(address), port, it))
        }
        store.setEncryptedString(caKey(serverId), json)
    }

    suspend fun removeApprovedCa(serverId: String) = store.setEncryptedString(caKey(serverId), null)

    val takServerListJson: Flow<String?> = store.encryptedString(TakServerSecretsKeys.TAK_SERVER_LIST_JSON)
    val takServerListRead: Flow<DecryptResult?> =
        store.encryptedStringResult(TakServerSecretsKeys.TAK_SERVER_LIST_JSON)

    suspend fun setTakServerListJson(json: String?) =
        store.setEncryptedString(TakServerSecretsKeys.TAK_SERVER_LIST_JSON, json)

    val phoneManagedSnapshotRead: Flow<DecryptResult?> =
        store.encryptedStringResult(TakServerSecretsKeys.PHONE_MANAGED_TAK_SERVER_SNAPSHOT_JSON)

    suspend fun setPhoneManagedSnapshotJson(json: String?) =
        store.setEncryptedString(TakServerSecretsKeys.PHONE_MANAGED_TAK_SERVER_SNAPSHOT_JSON, json)
}
