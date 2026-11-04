package com.aitech.weartak_civ.data

import android.app.Application
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.command.ChatP12Validator
import com.aitech.weartak_civ.data.takserver.TestClientP12
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReviewedCertificateApplyTest {
    private lateinit var repo: DataRepository
    private val secrets = InMemoryTakServerSecrets()
    private val identity by lazy { ChatP12Validator.validate(TestClientP12.p12("fixture-only"), "fixture-only") }
    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(RestrictionsManager::class.java)).setApplicationRestrictions(Bundle())
        MainActivity.device_uid = "synthetic-watch"
        repo = DataRepository(context)
        repo.takServerSecretsOverride = secrets.repository
        coEvery { secrets.repository.getApprovedCa(any(), any(), any()) } returns null
        coEvery { secrets.repository.removeApprovedCa(any()) } returns Unit
        repo.legacyDataStore().edit { it.clear() }
        repo.storeTakServerList(emptyList())
        repo.saveChatEnrollment("Event", "tak.example", 8089, "fixture-user", "fixture-password")
        Unit
    }

    @Test fun explicitApplyChangesOnlyChosenIdentityAndNeverDeviceIdentity() = runBlocking {
        val before = repo.awaitTakServerList().single()
        assertTrue(repo.applyReviewedCertificate(before, identity, null) {})
        assertEquals(before.copy(isP12Cert = true, p12Cert = identity.base64, p12CertPassword = identity.password),
            repo.awaitTakServerList().single())
        assertEquals("synthetic-watch", MainActivity.device_uid)
    }

    @Test fun staleDestinationManagedPolicyAndCancelledLeaseDoNotWrite() = runBlocking {
        val before = repo.awaitTakServerList().single()
        repo.updateTakServerItem(0, before.copy(address = "other.example"))
        assertTrue(runCatching { repo.applyReviewedCertificate(before, identity, null) {} }
            .exceptionOrNull() is DataRepository.StaleCertificateDestinationException)
        val current = repo.awaitTakServerList().single()
        assertTrue(runCatching {
            repo.applyReviewedCertificate(current, identity, null, stillAuthorized = { false }) {}
        }.isFailure)
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(RestrictionsManager::class.java))
            .setApplicationRestrictions(Bundle().apply { putParcelableArray("tak_server_list", emptyArray<Bundle>()) })
        assertTrue(runCatching { repo.applyReviewedCertificate(current, identity, null) {} }.isFailure)
        assertEquals(current, repo.awaitTakServerList().single())
    }

    @Test fun runtimeFailureOrCancellationAtCommitRollBackExactSettings() = runBlocking {
        val before = repo.awaitTakServerList()
        var calls = 0
        assertFalse(repo.applyReviewedCertificate(before.single(), identity, null) {
            if (++calls == 1) error("synthetic failure")
        })
        assertEquals(2, calls)
        assertEquals(before, repo.awaitTakServerList())
        assertFalse(repo.applyReviewedCertificate(before.single(), identity, null, complete = { false }) {})
        assertEquals(before, repo.awaitTakServerList())
    }

    @Test fun newDestinationRollsBackOnFailureAndDuplicateCannotReplace() = runBlocking {
        val before = repo.awaitTakServerList()
        val new = before.single().copy(name = "Other", address = "new.example", serverId = null)
        assertFalse(repo.applyReviewedServer(null, new, null, stillAuthorized = { true }, complete = { true },
            apply = { error("synthetic failure") }, rollback = {}))
        assertEquals(before, repo.awaitTakServerList())
        assertTrue(runCatching {
            repo.applyReviewedServer(null, before.single().copy(serverId = null), null,
                stillAuthorized = { true }, complete = { true }, apply = {})
        }.exceptionOrNull() is DataRepository.StaleCertificateDestinationException)
    }
}
