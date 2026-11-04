package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.RestrictionsManager
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.InMemoryTakServerSecrets
import com.aitech.weartak_civ.data.secure.crypto.AppKeyManager
import com.aitech.weartak_civ.data.secure.takserver.TakServerEncryptedDataStore
import com.aitech.weartak_civ.data.secure.takserver.TakServerSecretsRepository
import com.aitech.weartak_civ.data.takserver.Pkcs12CertificateImport
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.data.takserver.TestClientP12
import com.aitech.weartak_civ.presentation.*
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportContract
import com.weartak.plugin.sdk.serverpackage.*
import com.weartak.plugin.sdk.serverpackage.aidl.IWearTakServerPackageControlV1
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageStageRequestParcel
import java.util.Base64
import java.util.UUID
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
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
class ServerPackageFlowTest {
    private lateinit var repo: DataRepository
    private val secrets = InMemoryTakServerSecrets()
    private val context get() = RuntimeEnvironment.getApplication()
    private val pkcs12 by lazy { TestClientP12.p12("fixture-only") }
    @Before fun setup() = runBlocking {
        shadowOf(context.getSystemService(RestrictionsManager::class.java)).setApplicationRestrictions(Bundle())
        MainActivity.device_uid = "synthetic-recipient-uid"
        repo = DataRepository(context).apply { takServerSecretsOverride = secrets.repository }
        repo.legacyDataStore().edit { it.clear() }
        repo.storeTakServerList(emptyList())
        Unit
    }

    private fun host() = HostServerPackageControl(42, { true }, {}, {}, { 42 })
    private fun proxy(host: HostServerPackageControl): IWearTakServerPackageControlV1 =
        IWearTakServerPackageControlV1.Stub.asInterface(object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
                host.asBinder().transact(code, data, reply, flags)
        })

    @Test fun configuredExportThroughBinderToActualReviewAdapterPreservesRecipientIdentity() = runBlocking {
        val source = DataRepository.TakServerItem(true, "Synthetic event", "event.example", 8089,
            true, "fixture-user", "fixture-password", pkcs12, "fixture-only")
        repo.addTakServerItem(source)
        val exporter = RepositoryServerExporter(context, repo)
        val selected = exporter.snapshot(repo.awaitTakServerList().single())
        val ca = TestClientP12.ca()
        secrets.cas[requireNotNull(selected.server.serverId)] = Triple("event.example", 8089, TakServerCaTrust.encode(ca))
        val snapshot = exporter.snapshot(selected.server)
        val sender = host()
        val outgoing = proxy(sender)
        sender.allowFromWearerLaunch()
        val exportHandle = requireNotNull(outgoing.requestExport(UUID.randomUUID().toString()).handle)
        assertTrue(sender.claimExport(exportHandle))
        val exported = exporter.export(snapshot, identity = true, credentials = true)
        assertTrue(sender.approveExport(exportHandle, exported.bytes, exported.authorized))
        sender.dismissPendingExport(exportHandle)
        val transfer = outgoing.takeExport(exportHandle)
        assertEquals(ServerPackageTakeCode.OK, transfer.code)

        // A separate encrypted-store stand-in models the recipient, with no network callback.
        val recipientSecrets = InMemoryTakServerSecrets()
        val recipient = DataRepository(context).apply { takServerSecretsOverride = recipientSecrets.repository }
        recipient.storeTakServerList(emptyList())
        val receiver = host()
        val incoming = proxy(receiver)
        receiver.allowFromWearerLaunch()
        val stage = incoming.stageServerPackage(ServerPackageStageRequestParcel(UUID.randomUUID().toString(),
            requireNotNull(transfer.encodedPackage)))
        val handle = requireNotNull(stage.handle)
        incoming.requestReview(handle)
        assertTrue(receiver.imports.claimReview(handle))
        val decoded = receiver.imports.openPackage(handle)
        val identity = Pkcs12CertificateImport.open(requireNotNull(decoded.clientPkcs12),
            requireNotNull(decoded.clientPkcs12Password).toString(Charsets.UTF_8)) as Pkcs12CertificateImport.Opened.Client
        val proposed = packageServer(decoded, null, identity, requireNotNull(decoded.username),
            requireNotNull(decoded.password).toString(Charsets.UTF_8))
        var registered: String? = null
        val review = RepositoryCertificateReview(recipient, { registered = it }, {}, {})
        assertTrue(review.apply(null, proposed, TakServerCaTrust.encodeAll(decoded.caCertificates.map(TakServerCaTrust::parse)),
            { receiver.imports.isLive(handle) }, { receiver.imports.completeApplied(handle) }))
        val saved = recipient.awaitTakServerList().single()
        assertEquals(proposed.copy(serverId = saved.serverId), saved)
        assertEquals(saved.serverId, registered)
        assertEquals(TakServerCaTrust.encode(ca), recipient.getApprovedServerCa(requireNotNull(saved.serverId), saved.address, saved.port))
        assertEquals(Pkcs12ImportState.APPLIED, incoming.getStatus(handle).state)
        assertEquals("synthetic-recipient-uid", MainActivity.device_uid)
        assertEquals(ServerPackageTakeCode.TERMINAL, outgoing.takeExport(exportHandle).code)
        decoded.wipe()
        sender.close(); receiver.close()
    }

    @Test fun enrollmentGetsFreshCacheIdentityAndSeparatePkcs12StaysDisabled() {
        val enrollment = ServerPackage("Event", "event.example", 8089, 1, ServerPackageIdentityMode.RECEIVER_ENROLLMENT)
        val first = packageServer(enrollment, null, null, "receiver", "fixture")
        val second = packageServer(enrollment, null, null, "receiver", "fixture")
        assertNotNull(first.identityEpoch)
        assertNotEquals(first.identityEpoch, second.identityEpoch)
        assertFalse(first.isP12Cert)
        assertEquals("", first.p12Cert)
        assertTrue(runCatching { packageServer(enrollment, null, null, "", "") }.isFailure)
        val separate = ServerPackage("Event", "event.example", 8089, 1, ServerPackageIdentityMode.ADMIN_PKCS12_SEPARATE)
        assertFalse(packageServer(separate, null, null, "", "").isEnabled)
    }

    @Test fun privateCaSnapshotManagedDestinationAndRuntimeFailuresRollbackExactly() = runBlocking {
        repo.saveChatEnrollment("Event", "event.example", 8089, "fixture-user", "fixture-password")
        val before = repo.awaitTakServerList().single()
        val ca = TakServerCaTrust.encodeAll(listOf(TestClientP12.ca("First"), TestClientP12.ca("Second")))
        val key = requireNotNull(before.serverId)
        secrets.cas[key] = Triple(before.address, before.port, ca)
        val restorationHolds = mutableListOf<Boolean>()
        val review = RepositoryCertificateReview(repo, { error("synthetic runtime failure") },
            { restorationHolds += com.aitech.weartak_civ.data.takserver.ReviewedServerRuntimeGate.blocked(
                before.address, before.port) }, {})
        assertFalse(review.apply(CertificateDestination(before, ca), before.copy(password = "new-fixture"),
            null, { true }, { true }))
        assertEquals(listOf(true, false), restorationHolds)
        assertEquals(before, repo.awaitTakServerList().single())
        assertEquals(ca, repo.getApprovedServerCa(key, before.address, before.port))
        val stable = RepositoryCertificateReview(repo, {}, {}, {})
        assertFalse(stable.apply(CertificateDestination(before, ca), before.copy(password = "new-fixture"),
            null, { true }, { false }))
        assertEquals(before, repo.awaitTakServerList().single())
        assertEquals(ca, repo.getApprovedServerCa(key, before.address, before.port))
        assertTrue(runCatching { stable.apply(CertificateDestination(before, null), before, null, { true }, { true }) }
            .exceptionOrNull() is DataRepository.StaleCertificateDestinationException)
        shadowOf(context.getSystemService(RestrictionsManager::class.java)).setApplicationRestrictions(
            Bundle().apply { putParcelableArray("tak_server_list", emptyArray<Bundle>()) })
        assertTrue(runCatching { stable.apply(CertificateDestination(before, ca), before, null, { true }, { true }) }.isFailure)
        val exporter = RepositoryServerExporter(context, repo)
        assertTrue(runCatching { exporter.export(CertificateDestination(before, ca), false, false) }
            .exceptionOrNull() is DataRepository.ManagedServerExportException)
        assertEquals(before, repo.awaitTakServerList().single())
    }

    @Test fun failedRecoveryIsExplicitAndNeverCompletesStage() = runBlocking {
        repo.saveChatEnrollment("Event", "event.example", 8089, "fixture-user", "fixture-password")
        val before = repo.awaitTakServerList().single()
        var completed = false
        val backend = RepositoryCertificateReview(repo, { error("synthetic runtime failure") },
            { error("synthetic restore failure") }, {})
        val result = runCatching { backend.apply(CertificateDestination(before, null),
            before.copy(password = "changed-fixture"), null, { true }, { completed = true; true }) }
        assertTrue(result.exceptionOrNull() is DataRepository.CertificateRecoveryException)
        assertFalse(completed)
    }

    @Test fun obsoleteCaCannotReturnWhenEndpointIsRestoredAndPublicApplyRemovesPrivateTrust() = runBlocking {
        repo.saveChatEnrollment("Event", "event.example", 8089, "fixture-user", "fixture-password")
        val before = repo.awaitTakServerList().single()
        val key = requireNotNull(before.serverId)
        val ca = TakServerCaTrust.encode(TestClientP12.ca())
        secrets.cas[key] = Triple(before.address, before.port, ca)
        repo.updateTakServerItem(0, before.copy(address = "different.example"))
        repo.updateTakServerItem(0, before)
        assertNull(repo.getApprovedServerCa(key, before.address, before.port))
        secrets.cas[key] = Triple(before.address, before.port, ca)
        val review = RepositoryCertificateReview(repo, {}, {}, {})
        assertTrue(review.apply(CertificateDestination(before, ca), before, null, { true }, { true }))
        assertNull(repo.getApprovedServerCa(key, before.address, before.port))
    }

    @Test fun badPasswordAndCaIdentityMismatchNeverReachApply() {
        val bytes = Base64.getDecoder().decode(pkcs12)
        try {
            assertTrue(runCatching { Pkcs12CertificateImport.open(bytes, "wrong-fixture") }.isFailure)
            val ca = TestClientP12.ca()
            val store = java.security.KeyStore.getInstance("PKCS12").apply {
                load(null, null); setCertificateEntry("ca", ca)
            }

            val trust = java.io.ByteArrayOutputStream().use { store.store(it, "fixture-only".toCharArray()); it.toByteArray() }
            val classified = Pkcs12CertificateImport.open(trust, "fixture-only")
            assertTrue(classified is Pkcs12CertificateImport.Opened.Trust)
            val shared = ServerPackage("Event", "event.example", 8089, 1, ServerPackageIdentityMode.SHARED_IDENTITY,
                clientPkcs12 = trust, clientPkcs12Password = "fixture-only".toByteArray())
            assertTrue(runCatching { packageServer(shared, null, null, "", "") }.isFailure)
            shared.wipe()
        } finally { bytes.fill(0) }
    }

    @Test fun frozenPackagePasswordLimitIsSupportedWithoutChangingChatLimit() {
        val password = "f".repeat(256)
        val encoded = TestClientP12.p12(password)
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            assertTrue(Pkcs12CertificateImport.open(bytes, password) is Pkcs12CertificateImport.Opened.Client)
            assertTrue(runCatching { com.aitech.weartak_civ.command.ChatP12Validator.validate(encoded, password) }.isFailure)
            assertTrue(runCatching { Pkcs12CertificateImport.open(bytes, password + "f") }.isFailure)
        } finally { bytes.fill(0) }
    }

    @Test fun provisionalSettingsCannotStartNetworkUntilSuccessfulCommit() = runBlocking {
        repo.saveChatEnrollment("Event", "event.example", 8089, "fixture-user", "fixture-password")
        val before = repo.awaitTakServerList().single()
        val gate = com.aitech.weartak_civ.data.takserver.ReviewedServerRuntimeGate
        var starts = 0
        val backend = RepositoryCertificateReview(repo,
            { assertTrue(gate.blocked(before.address, before.port)) }, {},
            { assertFalse(gate.blocked(before.address, before.port)); starts++ })
        assertFalse(backend.apply(CertificateDestination(before, null), before.copy(password = "next-fixture"),
            null, { true }, { false }))
        assertEquals(0, starts)
        assertFalse(gate.blocked(before.address, before.port))
        assertEquals(before, repo.awaitTakServerList().single())
        assertTrue(backend.apply(CertificateDestination(before, null), before.copy(password = "next-fixture"),
            null, { true }, { assertTrue(gate.blocked(before.address, before.port)); true }))
        assertEquals(1, starts)
    }

    @Test fun postCommitConnectionFailureKeepsSavedStatusAndSurfacesError() = runBlocking {
        repo.saveChatEnrollment("Event", "event.example", 8089, "fixture-user", "fixture-password")
        val before = repo.awaitTakServerList().single()
        var committed = false
        val backend = RepositoryCertificateReview(repo, {}, {}, { error("synthetic start failure") })
        val outcome = runCatching { backend.apply(CertificateDestination(before, null),
            before.copy(password = "next-fixture"), null, { true }, { committed = true; true }) }
        assertTrue(outcome.exceptionOrNull() is SavedServerStartException)
        assertTrue(committed)
        assertEquals("next-fixture", repo.awaitTakServerList().single().password)
        assertFalse(com.aitech.weartak_civ.data.takserver.ReviewedServerRuntimeGate.blocked(before.address, before.port))
    }

    @Test fun cancelledExpiredOrLostSessionDuringPrepareRestoresRepositoryAndCa() = runBlocking {
        repo.saveChatEnrollment("Event", "event.example", 8089, "fixture-user", "fixture-password")
        repo.saveChatEnrollment("Unrelated", "other.example", 8089, "other-user", "other-fixture")
        val before = repo.awaitTakServerList()
        val target = before.first()
        val id = requireNotNull(target.serverId)
        val oldCa = TakServerCaTrust.encode(TestClientP12.ca("Original"))
        val nextCa = TestClientP12.ca("Replacement")
        secrets.cas[id] = Triple(target.address, target.port, oldCa)
        for (termination in listOf("cancel", "expiry", "session")) {
            var now = 100L
            var live = true
            val host = HostServerPackageControl(42, { live }, {}, {}, { 42 }, { now })
            try {
                val client = proxy(host)
                val bytes = ServerPackageCodec.encode(ServerPackage("Replacement", target.address, target.port,
                    ServerPackageTrustMode.PRIVATE_CA, ServerPackageIdentityMode.RECEIVER_ENROLLMENT,
                    caCertificates = listOf(nextCa.encoded)))
                val handle = requireNotNull(client.stageServerPackage(
                    ServerPackageStageRequestParcel(UUID.randomUUID().toString(), bytes)).handle)
                bytes.fill(0)
                host.allowFromWearerLaunch()
                client.requestReview(handle)
                assertTrue(host.imports.claimReview(handle))
                val decoded = host.imports.openPackage(handle)
                try {
                    var starts = 0
                    val backend = RepositoryCertificateReview(repo, {
                        assertEquals("Replacement", repo.awaitTakServerList().first().name)
                        when (termination) {
                            "cancel" -> client.cancel(handle)
                            "expiry" -> now += Pkcs12ImportContract.MAX_STAGE_LIFETIME_MS
                            else -> live = false
                        }
                    }, {}, { starts++ })
                    assertFalse(termination, backend.apply(CertificateDestination(target, oldCa),
                        packageServer(decoded, CertificateDestination(target, oldCa), null, "next-user", "next-fixture"),
                        TakServerCaTrust.encode(nextCa), { host.imports.isLive(handle) },
                        { host.imports.completeApplied(handle) }))
                    assertEquals(termination, before, repo.awaitTakServerList())
                    assertEquals(termination, oldCa, repo.getApprovedServerCa(id, target.address, target.port))
                    assertEquals(0, starts)
                    assertFalse(host.imports.completeApplied(handle))
                    assertFalse(com.aitech.weartak_civ.data.takserver.ReviewedServerRuntimeGate
                        .blocked(target.address, target.port))
                    if (termination != "session") assertEquals(
                        if (termination == "expiry") Pkcs12ImportState.EXPIRED else Pkcs12ImportState.CANCELLED,
                        client.getStatus(handle).state)
                } finally { decoded.wipe() }
            } finally { host.close() }
        }
    }

    @Test fun readyExportRechecksActualSourceAndCaBeforeOneUseTake() = runBlocking {
        repo.saveChatEnrollment("Event", "event.example", 8089, "fixture-user", "fixture-password")
        val source = repo.awaitTakServerList().single()
        val id = requireNotNull(source.serverId)
        val ca = TakServerCaTrust.encode(TestClientP12.ca())
        secrets.cas[id] = Triple(source.address, source.port, ca)
        val exporter = RepositoryServerExporter(context, repo)
        for (change in listOf("server", "ca", "policy")) {
            val host = host()
            try {
                val client = proxy(host)
                host.allowFromWearerLaunch()
                val handle = requireNotNull(client.requestExport(UUID.randomUUID().toString()).handle)
                assertTrue(host.claimExport(handle))
                val prepared = exporter.export(exporter.snapshot(source), identity = false, credentials = true)
                assertTrue(host.approveExport(handle, prepared.bytes, prepared.authorized))
                when (change) {
                    "server" -> repo.updateTakServerItem(0, source.copy(password = "changed-fixture"))
                    "ca" -> secrets.cas.remove(id)
                    else -> shadowOf(context.getSystemService(RestrictionsManager::class.java))
                        .setApplicationRestrictions(Bundle().apply {
                            putParcelableArray("tak_server_list", emptyArray<Bundle>())
                        })
                }
                val taken = client.takeExport(handle)
                assertEquals(change, ServerPackageTakeCode.UNAUTHORIZED, taken.code)
                assertNull(taken.encodedPackage)
                assertEquals(ServerPackageExportState.REJECTED, client.getExportStatus(handle).state)
                assertEquals(ServerPackageTakeCode.TERMINAL, client.takeExport(handle).code)
            } finally {
                host.close()
                shadowOf(context.getSystemService(RestrictionsManager::class.java)).setApplicationRestrictions(Bundle())
                repo.updateTakServerItem(0, source)
                secrets.cas[id] = Triple(source.address, source.port, ca)
            }
        }

        @Test fun binderReviewPersistsEncryptedServerAndMultiCaAndRollsBackOnDisk() = runBlocking {
            AeadConfig.register()
            val aead = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java)
            // Only Android Keystore key wrapping is substituted; serializer and disk DataStore are real.
            mockkConstructor(AppKeyManager::class)
            every { anyConstructed<AppKeyManager>().getAead(any(), any()) } returns aead
            val host = host()
            var savedId: String? = null
            var realSecrets: TakServerSecretsRepository? = null
            try {
                val encrypted = TakServerSecretsRepository(TakServerEncryptedDataStore(context))
                realSecrets = encrypted
                val actual = DataRepository(context).apply { takServerSecretsOverride = encrypted }
                actual.storeTakServerList(emptyList())
                actual.saveChatEnrollment("Original", "event.example", 8089, "old-user", "old-fixture")
                val before = actual.awaitTakServerList().single()
                val id = requireNotNull(before.serverId)
                savedId = id
                val oldCa = TakServerCaTrust.encode(TestClientP12.ca("Original"))
                encrypted.setApprovedCa(id, before.address, before.port, oldCa)
                val cas = listOf(TestClientP12.ca("First"), TestClientP12.ca("Second"))
                val bytes = ServerPackageCodec.encode(ServerPackage("Replacement", before.address, before.port,
                    ServerPackageTrustMode.PRIVATE_CA, ServerPackageIdentityMode.RECEIVER_ENROLLMENT,
                    caCertificates = cas.map { it.encoded }))
                val client = proxy(host)
                val handle = requireNotNull(client.stageServerPackage(
                    ServerPackageStageRequestParcel(UUID.randomUUID().toString(), bytes)).handle)
                bytes.fill(0)
                host.allowFromWearerLaunch()
                client.requestReview(handle)
                assertTrue(host.imports.claimReview(handle))
                val decoded = host.imports.openPackage(handle)
                try {
                    val destination = CertificateDestination(before, oldCa)
                    val proposed = packageServer(decoded, destination, null, "receiver-user", "receiver-fixture-password")
                    val ca = TakServerCaTrust.encodeAll(decoded.caCertificates.map(TakServerCaTrust::parse))
                    val failed = RepositoryCertificateReview(actual, { error("synthetic runtime failure") }, {}, {})
                    assertFalse(failed.apply(destination, proposed, ca, { host.imports.isLive(handle) },
                        { host.imports.completeApplied(handle) }))
                    val reloaded = DataRepository(context).apply {
                        takServerSecretsOverride = TakServerSecretsRepository(TakServerEncryptedDataStore(context))
                    }
                    assertEquals(before, reloaded.awaitTakServerList().single())
                    assertEquals(oldCa, reloaded.getApprovedServerCa(id, before.address, before.port))
                    assertEquals(Pkcs12ImportState.IN_REVIEW, client.getStatus(handle).state)
                    val approved = RepositoryCertificateReview(actual, {}, {}, {})
                    assertTrue(approved.apply(destination, proposed, ca, { host.imports.isLive(handle) },
                        { host.imports.completeApplied(handle) }))
                    assertEquals(proposed.copy(serverId = id), reloaded.awaitTakServerList().single())
                    assertEquals(ca, reloaded.getApprovedServerCa(id, before.address, before.port))
                    assertEquals(Pkcs12ImportState.APPLIED, client.getStatus(handle).state)
                    assertEquals(Pkcs12ImportState.APPLIED, client.cancel(handle).state)
                    val disk = java.io.File(context.filesDir, "datastore/takserver_secrets.preferences_pb")
                    assertTrue(disk.isFile)
                    val persisted = disk.readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse(persisted.contains("receiver-fixture-password"))
                    assertFalse(persisted.contains("receiver-user"))
                    assertFalse(persisted.contains(ca))
                } finally { decoded.wipe() }
            } finally {
                host.close()
                savedId?.let { realSecrets?.removeApprovedCa(it) }
                realSecrets?.setTakServerListJson(null)
                unmockkConstructor(AppKeyManager::class)
            }
        }
    }
}
