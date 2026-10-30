package com.aitech.weartak_civ.data

import android.app.Application
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.data.takserver.TakServerEndpointIdentity
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatEnrollmentSaveTest {
    private lateinit var repo: DataRepository
    private val secrets = InMemoryTakServerSecrets()

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "test-watch"
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(RestrictionsManager::class.java)).setApplicationRestrictions(Bundle())
        repo = DataRepository(context)
        repo.takServerSecretsOverride = secrets.repository
        repo.legacyDataStore().edit { it.clear() }
        repo.storeTakServerList(emptyList())
        MainActivity.mainActivity = io.mockk.mockk {
            io.mockk.every { applicationContext } returns context
        }
    }

    @After fun cleanup() = runBlocking {
        repo.legacyDataStore().edit { it.clear() }
        repo.setPhoneManagementSessionActive(false)
    }

    @Test fun newServerIsEnabledAndUpdatesByNameOrEndpointKeepStableIdentity() = runBlocking {
        repo.storeEnableTakEndpoint(false)
        val added = repo.saveChatEnrollment("Ops", "host.example", 8089, "operator", "fake-secret")
        assertEquals(DataRepository.ChatEnrollmentStatus.ADDED, added.status)
        val original = repo.awaitTakServerList().single()
        assertTrue(original.isEnabled)
        assertEquals("fake-secret", original.password)
        assertEquals(DataRepository.ChatEnrollmentStatus.UPDATED,
            repo.saveChatEnrollment("oPS", "other.example", 8090, "new", "next,secret").status)
        val byName = repo.awaitTakServerList().single()
        assertEquals(original.serverId, byName.serverId)
        assertEquals("other.example", byName.address)
        assertEquals("next,secret", byName.password)
        assertEquals(DataRepository.ChatEnrollmentStatus.UPDATED,
            repo.saveChatEnrollment("Renamed", "OTHER.EXAMPLE", 8090, "third", "third-secret").status)
        val byEndpoint = repo.awaitTakServerList().single()
        assertEquals(original.serverId, byEndpoint.serverId)
        assertEquals("Renamed", byEndpoint.name)
        assertFalse(byEndpoint.toString().contains("third-secret"))
        assertFalse(repo.getEnableTakEndpoint.first()) // Adding a server does not overwrite a toggle choice.
    }

    @Test fun p12UpdatePreservesCertificateModeAndEveryUneditedField() = runBlocking {
        repo.storeTakServerList(listOf(DataRepository.TakServerItem(
            false, "Ops", "host.example", 8089, true, "old-user", "old-password",
            "c3ludGhldGljLWNlcnRpZmljYXRl", "certificate-password", "stable-p12-id")))
        val before = repo.awaitTakServerList().single()
        assertEquals(DataRepository.ChatEnrollmentStatus.UPDATED,
            repo.saveChatEnrollment("OPS", "other.example", 8090, "new-user", "new-password").status)
        assertEquals(before.copy(name = "OPS", address = "other.example", port = 8090,
            username = "new-user", password = "new-password"), repo.awaitTakServerList().single())
    }

    @Test fun enrollmentModeUpdateClearsCachedCertificateLikeUiCredentialEdit() = runBlocking {
        repo.storeTakServerList(listOf(DataRepository.TakServerItem(
            true, "Ops", "host.example", 8089, false, "old-user", "old-password",
            "c3ludGhldGljLWNlcnRpZmljYXRl", "certificate-password", "stable-enrollment-id")))
        val before = repo.awaitTakServerList().single()
        assertEquals(DataRepository.ChatEnrollmentStatus.UPDATED,
            repo.saveChatEnrollment("Ops", "host.example", 8089, "new-user", "new-password").status)
        assertEquals(before.copy(username = "new-user", password = "new-password",
            p12Cert = "", p12CertPassword = ""), repo.awaitTakServerList().single())
    }

    @Test fun conflictingNameAndEndpointChangeNothing() = runBlocking {
        repo.saveChatEnrollment("One", "one.example", 8089, "u", "p")
        repo.saveChatEnrollment("Two", "two.example", 8089, "u", "p")
        val before = repo.awaitTakServerList()
        assertEquals(DataRepository.ChatEnrollmentStatus.CONFLICT,
            repo.saveChatEnrollment("one", "TWO.EXAMPLE", 8089, "changed", "fake-secret").status)
        assertEquals(before, repo.awaitTakServerList())
    }

    @Test fun locksAndEvenEmptyMdmAuthorityPreventSaving() = runBlocking {
        repo.storeNetworkPreferencesLocked(true)
        assertEquals(DataRepository.ChatEnrollmentStatus.LOCKED,
            repo.saveChatEnrollment("Ops", "host", 8089, "u", "p").status)
        repo.storeNetworkPreferencesLocked(false)
        val restrictions = Bundle().apply { putParcelableArray("tak_server_list", emptyArray<Bundle>()) }
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(RestrictionsManager::class.java))
            .setApplicationRestrictions(restrictions)
        assertTrue(repo.chatEnrollmentBlocked())
        assertEquals(DataRepository.ChatEnrollmentStatus.MANAGED,
            repo.saveChatEnrollment("Ops", "host", 8089, "u", "p").status)
        assertTrue(repo.awaitTakServerList().isEmpty())
    }

    @Test fun activePhoneManagementPreventsEnrollmentWithoutMutatingCredentials() = runBlocking {
        repo.saveChatEnrollment("Ops", "host", 8089, "u", "original")
        val before = repo.awaitTakServerList()
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        assertTrue(repo.chatEnrollmentBlocked())
        assertEquals(DataRepository.ChatEnrollmentStatus.LOCKED,
            repo.saveChatEnrollment("Ops", "host", 8089, "new", "forbidden").status)
        assertEquals(before, repo.awaitTakServerList())
    }

    @Test fun policyIsRecheckedAfterWaitingForMutationLockAndConcurrentAddsDoNotDuplicate() = runBlocking {
        TakServerEndpointIdentity.mutationMutex.lock()
        try {
            val waiting = async { repo.saveChatEnrollment("Ops", "host", 8089, "u", "p") }
            kotlinx.coroutines.yield()
            repo.storeNetworkPreferencesLocked(true)
            TakServerEndpointIdentity.mutationMutex.unlock()
            assertEquals(DataRepository.ChatEnrollmentStatus.LOCKED, waiting.await().status)
        } finally {
            if (TakServerEndpointIdentity.mutationMutex.isLocked) TakServerEndpointIdentity.mutationMutex.unlock()
        }
        repo.storeNetworkPreferencesLocked(false)
        val first = async { repo.saveChatEnrollment("Ops", "host", 8089, "u", "p") }
        val second = async { repo.saveChatEnrollment("ops", "HOST", 8089, "u2", "p2") }
        assertEquals(setOf(DataRepository.ChatEnrollmentStatus.ADDED, DataRepository.ChatEnrollmentStatus.UPDATED),
            setOf(first.await().status, second.await().status))
        assertEquals(1, repo.awaitTakServerList().size)
    }

    @Test fun runtimeStartsRegistrationForAddedAndUpdatedServersButNotRefusedOnes() = runBlocking {
        val registered = mutableListOf<String>()
        val password = "  fake_\\secret\"!  "
        val first = com.aitech.weartak_civ.service.ChatCommandRuntime.enrollServer(
            repo, "Ops", "host", 8089, "alex.gorsuchait-wt", password) { id ->
            val saved = repo.awaitTakServerList().single()
            assertTrue(saved.isEnabled)
            assertEquals("alex.gorsuchait-wt", saved.username)
            assertEquals(password, saved.password)
            registered += id
        }
        assertEquals("Server Ops added", first.message)
        val second = com.aitech.weartak_civ.service.ChatCommandRuntime.enrollServer(
            repo, "Ops", "host", 8089, "u", "replacement-secret") { registered += it }
        assertEquals("Server Ops updated", second.message)
        assertEquals(2, registered.size)
        assertEquals(registered.first(), registered.last())
        repo.storeNetworkPreferencesLocked(true)
        val refused = com.aitech.weartak_civ.service.ChatCommandRuntime.enrollServer(
            repo, "Ops", "host", 8089, "u", "forbidden-secret") { error("Must not connect") }
        assertFalse(refused.ok)
        assertEquals("replacement-secret", repo.awaitTakServerList().single().password)
    }
}
