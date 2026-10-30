package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import com.aitech.weartak_civ.data.secure.takserver.TakServerSecretsRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory stand-in for the encrypted TAK server store, which needs Android Keystore. */
class InMemoryTakServerSecrets {
    val live = MutableStateFlow<String?>(null)
    val snapshot = MutableStateFlow<String?>(null)

    val repository: TakServerSecretsRepository = mockk {
        every { takServerListJson } returns live
        every { takServerListRead } returns live.map { json -> json?.let { DecryptResult.Success(1, it) } }
        every { phoneManagedSnapshotRead } returns
            snapshot.map { json -> json?.let { DecryptResult.Success(1, it) } }
        coEvery { setTakServerListJson(any()) } answers { live.value = firstArg() }
        coEvery { setPhoneManagedSnapshotJson(any()) } answers { snapshot.value = firstArg() }
        coEvery { getApprovedCa(any(), any(), any()) } returns null
        coEvery { removeApprovedCa(any()) } returns Unit
    }

    fun liveServers(): List<DataRepository.TakServerItem> = parse(live.value)

    fun snapshotServers(): List<DataRepository.TakServerItem> = parse(snapshot.value)

    private fun parse(json: String?): List<DataRepository.TakServerItem> =
        if (json.isNullOrEmpty()) emptyList()
        else Gson().fromJson(json, object : TypeToken<List<DataRepository.TakServerItem>>() {}.type)

    companion object {
        fun server(name: String, cert: String = "", certPassword: String = "") =
            DataRepository.TakServerItem(
                isEnabled = true,
                name = name,
                address = "$name.example",
                port = 8089,
                isP12Cert = cert.isNotEmpty(),
                username = "",
                password = "",
                p12Cert = cert,
                p12CertPassword = certPassword
            )
    }
}
