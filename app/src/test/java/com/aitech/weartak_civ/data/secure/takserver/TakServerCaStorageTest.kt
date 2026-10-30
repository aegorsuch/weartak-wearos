package com.aitech.weartak_civ.data.secure.takserver

import androidx.datastore.preferences.core.Preferences
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.data.takserver.TakServerCaTrustTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TakServerCaStorageTest {
    private val values = mutableMapOf<String, MutableStateFlow<DecryptResult?>>()
    private val writtenKeys = mutableSetOf<String>()
    private val store = mockk<TakServerEncryptedDataStore> {
        every { encryptedString(any()) } returns MutableStateFlow(null)
        every { encryptedStringResult(any()) } answers {
            values.getOrPut(firstArg<Preferences.Key<String>>().name) { MutableStateFlow(null) }
        }
        coEvery { setEncryptedString(any(), any()) } answers {
            writtenKeys += firstArg<Preferences.Key<String>>().name
            values.getOrPut(firstArg<Preferences.Key<String>>().name) { MutableStateFlow(null) }.value =
                secondArg<String?>()?.let { DecryptResult.Success(1, it) }
        }
    }
    private val repository = TakServerSecretsRepository(store)
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val certificate = TakServerCaTrust.encode(TakServerCaTrustTest.certificate())

    @Test fun approvalsAreBoundToIdNormalizedHostAndStreamingPort() = runBlocking {
        repository.setApprovedCa(id, " TAK.EXAMPLE ", 8089, certificate)
        assertEquals(certificate, repository.getApprovedCa(id, "tak.example", 8089))
        assertNull(repository.getApprovedCa(other, "tak.example", 8089))
        assertNull(repository.getApprovedCa(id, "other.example", 8089))
        assertNull(repository.getApprovedCa(id, "tak.example", 8090))
        assertTrue(writtenKeys.all { it.startsWith("approved_ca_") })
        assertFalse(writtenKeys.contains(TakServerSecretsKeys.TAK_SERVER_LIST_JSON.name))
        assertFalse(writtenKeys.contains(TakServerSecretsKeys.PHONE_MANAGED_TAK_SERVER_SNAPSHOT_JSON.name))
    }

    @Test fun removalPersistsAndDoesNotAffectOtherServers() = runBlocking {
        repository.setApprovedCa(id, "tak.example", 8089, certificate)
        repository.setApprovedCa(other, "tak.example", 8089, certificate)
        repository.removeApprovedCa(id)
        assertNull(repository.getApprovedCa(id, "tak.example", 8089))
        assertEquals(certificate, repository.getApprovedCa(other, "tak.example", 8089))
    }

    @Test fun corruptOrDisplacedEncryptedRecordFailsRatherThanFallingBack() = runBlocking {
        repository.setApprovedCa(id, "tak.example", 8089, certificate)
        values["approved_ca_$other"] = MutableStateFlow(values.getValue("approved_ca_$id").value)
        try {
            repository.getApprovedCa(other, "tak.example", 8089)
            fail("Displaced CA record accepted")
        } catch (_: IllegalArgumentException) { }
        values.getValue("approved_ca_$id").value = DecryptResult.Failure(IllegalStateException("test"))
        try {
            repository.getApprovedCa(id, "tak.example", 8089)
            fail("Unreadable trust fell back")
        } catch (_: IllegalStateException) { }
    }
}
