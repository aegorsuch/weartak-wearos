package com.aitech.weartak_civ.data

import android.app.Application
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.data.takserver.ReviewedServerRuntimeGate
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.data.takserver.TakServerEndpointMigration
import com.aitech.weartak_civ.data.takserver.TestClientP12
import com.aitech.weartak_civ.presentation.CertificateDestination
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.RepositoryCertificateReview
import kotlinx.coroutines.CancellationException
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
class EndpointMigrationApplyTest {
    private lateinit var repo: DataRepository
    private val secrets = InMemoryTakServerSecrets()
    private val ca by lazy { TakServerCaTrust.encode(TestClientP12.ca()) }
    private val newCa by lazy { TakServerCaTrust.encode(TestClientP12.ca("New reviewed CA")) }

    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(RestrictionsManager::class.java)).setApplicationRestrictions(Bundle())
        MainActivity.device_uid = "synthetic-migration-watch"
        repo = DataRepository(context)
        repo.takServerSecretsOverride = secrets.repository
        repo.legacyDataStore().edit { it.clear() }
        repo.storeTakServerList(listOf(InMemoryTakServerSecrets.server("odin", TestClientP12.p12("fixture-only"), "fixture-only")
            .copy(isEnabled = false, address = "10.0.0.120",
                username = "fixture-user", password = "fixture-password")))
        val saved = repo.awaitTakServerList().single()
        repo.setApprovedServerCa(requireNotNull(saved.serverId), saved.address, saved.port, ca)
    }

    private fun proposed(before: DataRepository.TakServerItem) =
        TakServerEndpointMigration.prepare(before, before.name, "10.0.0.157", 8089, newCa, null) {
            error("Embedded identity must be used")
        }

    private suspend fun apply(
        before: DataRepository.TakServerItem,
        authorized: () -> Boolean = { true },
        complete: () -> Boolean = { true },
        runtime: suspend (String) -> Unit = {},
    ) = repo.applyReviewedServer(before, proposed(before), newCa, ca, checkCaSnapshot = true,
        stillAuthorized = authorized, complete = complete, apply = runtime, rollback = {})

    @Test fun explicitMigrationBindsOnlyReviewedCaAndKeepsIdentityAndEnableSetting() = runBlocking {
        val before = repo.awaitTakServerList().single()
        assertTrue(apply(before))
        val after = repo.awaitTakServerList().single()
        assertEquals(proposed(before), after)
        assertEquals(newCa, repo.getApprovedServerCa(requireNotNull(after.serverId), after.address, after.port))
        assertNull(repo.getApprovedServerCa(requireNotNull(after.serverId), before.address, before.port))
        assertFalse(after.isEnabled)
    }

    @Test fun runtimeFailureAndCommitCancellationRestoreExactProfileAndOldCa() = runBlocking {
        val before = repo.awaitTakServerList().single()
        assertFalse(apply(before, runtime = { error("Synthetic runtime failure") }))
        assertEquals(before, repo.awaitTakServerList().single())
        assertEquals(ca, repo.getApprovedServerCa(requireNotNull(before.serverId), before.address, before.port))
        assertNull(repo.getApprovedServerCa(requireNotNull(before.serverId), "10.0.0.157", 8089))
        assertFalse(apply(before, complete = { false }))
        assertEquals(before, repo.awaitTakServerList().single())
        assertEquals(ca, repo.getApprovedServerCa(requireNotNull(before.serverId), before.address, before.port))
    }

    @Test fun coroutineCancellationAlsoRestoresOldEndpointAndCa() = runBlocking {
        val before = repo.awaitTakServerList().single()
        try {
            apply(before, runtime = { throw CancellationException("Synthetic cancellation") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(before, repo.awaitTakServerList().single())
            assertEquals(ca, repo.getApprovedServerCa(requireNotNull(before.serverId), before.address, before.port))
        }
    }

    @Test fun staleCaProfileDuplicateAndManagedPolicyCannotOverwriteConfiguration() = runBlocking {
        val before = repo.awaitTakServerList().single()
        assertTrue(runCatching { apply(before, authorized = { false }) }.isFailure)
        repo.setApprovedServerCa(requireNotNull(before.serverId), before.address, before.port, newCa)
        assertTrue(runCatching { apply(before) }.exceptionOrNull() is DataRepository.StaleCertificateDestinationException)
        repo.setApprovedServerCa(requireNotNull(before.serverId), before.address, before.port, ca)
        repo.updateTakServerItem(0, before.copy(name = "Changed"))
        assertTrue(runCatching { apply(before) }.exceptionOrNull() is DataRepository.StaleCertificateDestinationException)
        repo.updateTakServerItem(0, before)
        repo.addTakServerItem(InMemoryTakServerSecrets.server("other").copy(address = "10.0.0.157"))
        val snapshot = repo.awaitTakServerList()
        assertTrue(runCatching { apply(before) }.exceptionOrNull() is DataRepository.StaleCertificateDestinationException)
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(RestrictionsManager::class.java))
            .setApplicationRestrictions(Bundle().apply { putParcelableArray("tak_server_list", emptyArray<Bundle>()) })
        assertTrue(runCatching { apply(before) }.isFailure)
        assertEquals(snapshot, repo.awaitTakServerList())
        assertEquals(ca, repo.getApprovedServerCa(requireNotNull(before.serverId), before.address, before.port))
    }

    @Test fun runtimeGateHoldsNewEndpointUntilCommitAndRestoresOnFailure() = runBlocking {
        val before = repo.awaitTakServerList().single()
        val next = proposed(before)
        var restored = 0
        val backend = RepositoryCertificateReview(repo,
            register = {
                assertTrue(ReviewedServerRuntimeGate.blocked(next.address, next.port))
                error("Synthetic preparation failure")
            },
            restore = { restored++ }, afterCommit = { fail("Must not connect after rollback") })
        assertFalse(backend.apply(CertificateDestination(before, ca), next, newCa, { true }, { true }))
        assertFalse(ReviewedServerRuntimeGate.blocked(next.address, next.port))
        assertTrue(restored > 0)
        assertEquals(before, repo.awaitTakServerList().single())
        assertEquals(ca, repo.getApprovedServerCa(requireNotNull(before.serverId), before.address, before.port))
    }
}
