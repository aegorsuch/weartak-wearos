package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlFrozenSelection
import com.aitech.weartak_civ.data.DittoPdlSource
import com.aitech.weartak_civ.integration.plugin.HostPdlSnapshotV1
import com.aitech.weartak_civ.integration.plugin.HostPointCutoverV1
import com.aitech.weartak_civ.integration.plugin.HostCutoverBindings
import com.aitech.weartak_civ.cot.DittoPointCutoverSnapshot
import com.aitech.weartak_civ.cot.DittoPointMutationFence
import com.weartak.plugin.sdk.cutover.v1.CutoverCompletionContractV1 as Completion
import com.weartak.plugin.sdk.pointcutover.v1.PointCutoverContractV1 as Points
import com.weartak.plugin.sdk.pdlsnapshot.v1.PdlSnapshotContractV1 as Snapshot
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoPdlFreezeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun completionRequiresRealSameBindingSnapshotAndPointAcknowledgements() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
                temporaryFolder.root.resolve("completion.preferences_pb")
            }
            val context = RuntimeEnvironment.getApplication()
            AeadConfig.register()
            val aead = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java)
            val repository = DataRepository(context, pdlDataStore = store, pdlSnapshotAead = { aead },
                pdlCommunityIdentity = { "community" })
            val frozen = repository.freezeDittoPdlForMigration { true }
            val pointSnapshots = DittoPointCutoverSnapshot(
                context.getSharedPreferences("completion-local", Context.MODE_PRIVATE),
                context.getSharedPreferences("completion-remote", Context.MODE_PRIVATE),
                DittoPointMutationFence(context.getSharedPreferences("completion-fence", Context.MODE_PRIVATE)),
                { true },
            )
            val pdl = HostPdlSnapshotV1(
                "session", 42, { true }, { true }, { true },
                repository::readFrozenDittoPdlSnapshot,
                { snapshot, action -> repository.withCurrentFrozenDittoPdlSnapshot(snapshot, action) },
                { 42 },
            )
            val points = HostPointCutoverV1(
                "session", 42, { true }, { true }, { true }, pointSnapshots, context.cacheDir, { 42 },
                exportAllowed = { repository.readDittoPdlHandoffState()?.completed == false },
            )
            val completion = requireNotNull(HostCutoverBindings.open(
                "session", 42, { true }, { true }, { true }, repository, pdl, points, { 42 },
            ))
            assertEquals(Completion.UNAVAILABLE, completion.commitCutover("session", frozen.cutoverId))
            val pdlData = pdl.read("session", "pdl")
            assertTrue(Snapshot.isValidReadResult(pdlData, "pdl"))
            assertEquals(Snapshot.OK, pdl.acknowledge("session", "pdl", pdlData.getByteArray(Snapshot.KEY_NONCE)))
            assertEquals(Completion.UNAVAILABLE, completion.commitCutover("session", frozen.cutoverId))
            val pointData = points.read("session", "points")
            assertTrue(Points.isValidReadResult(pointData, "points"))
            pointData.getParcelable<ParcelFileDescriptor>(Points.KEY_PAYLOAD)!!.close()
            assertEquals(Points.OK, points.acknowledge("session", "points", pointData.getByteArray(Points.KEY_NONCE)))
            assertEquals(Completion.OK, completion.commitCutover("session", frozen.cutoverId))
            assertTrue(requireNotNull(repository.readDittoPdlHandoffState()).completed)
            assertEquals(Points.UNAVAILABLE, points.read("session", "points").getInt(Points.KEY_CODE))
            assertEquals(Points.UNAVAILABLE, points.acknowledge("session", "points", pointData.getByteArray(Points.KEY_NONCE)))
            completion.close()
            assertEquals(Snapshot.INACTIVE_SESSION, pdl.read("session", "pdl").getInt(Snapshot.KEY_CODE))
            assertEquals(Points.INACTIVE_SESSION, points.read("session", "points").getInt(Points.KEY_CODE))
            val newPdl = HostPdlSnapshotV1(
                "new-session", 42, { true }, { true }, { true },
                repository::readFrozenDittoPdlSnapshot,
                { snapshot, action -> repository.withCurrentFrozenDittoPdlSnapshot(snapshot, action) },
                { 42 },
            )
            val newPoints = HostPointCutoverV1(
                "new-session", 42, { true }, { true }, { true }, pointSnapshots, context.cacheDir, { 42 },
                exportAllowed = { repository.readDittoPdlHandoffState()?.completed == false },
            )
            val rebound = requireNotNull(HostCutoverBindings.open(
                "new-session", 42, { true }, { true }, { true }, repository, newPdl, newPoints, { 42 },
            ))
            assertEquals(Completion.COMPLETED, rebound.readCutover("new-session").getInt(Completion.KEY_PHASE))
            assertEquals(Snapshot.UNAVAILABLE, newPdl.read("new-session", "pdl").getInt(Snapshot.KEY_CODE))
            assertEquals(Points.UNAVAILABLE, newPoints.read("new-session", "points").getInt(Points.KEY_CODE))
            assertEquals(Completion.RECOVERY_REQUIRED, rebound.commitCutover("new-session", "f".repeat(32)))
            assertFalse(rebound.hasReconciliationGrant())
            assertEquals(Completion.OK, rebound.commitCutover("new-session", frozen.cutoverId))
            assertTrue(rebound.hasReconciliationGrant())
            rebound.close()
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun freezePersistsAndRejectsEveryLegacyWriter() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        val file = temporaryFolder.root.resolve("pdl.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        val context = RuntimeEnvironment.getApplication()
        AeadConfig.register()
        val aead = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java)
        val first = DataRepository(context, pdlDataStore = store, pdlSnapshotAead = { aead },
            pdlCommunityIdentity = { "original-community" })
        val second = DataRepository(context, pdlDataStore = store, pdlSnapshotAead = { aead },
            pdlCommunityIdentity = { "new-build-community" })
        first.storeDittoCommandSettings(DittoPdlSource.COMMUNITY, "synthetic-pdl")
        val before = first.readDittoPdlMigrationSelection()
        try {
            first.freezeDittoPdlForMigration { false }
            fail("Freeze requires quiescence proof")
        } catch (_: IllegalStateException) { }
        assertFalse(first.isDittoPdlMigrationFrozen())
        val frozen = first.freezeDittoPdlForMigration { true }
        assertEquals(before.source, frozen.selection.source)
        assertEquals("original-community", frozen.selection.identity)
        assertEquals(before.revision, frozen.selection.revision)
        assertEquals("original-community", frozen.communityIdentity)
        assertEquals("synthetic-pdl", frozen.customIdentity)
        assertFalse(frozen.initiallyEnabled)
        assertEquals(32, frozen.cutoverId.length)
        assertTrue(second.isDittoPdlMigrationFrozen())
        assertEquals("original-community", second.readFrozenDittoPdlSnapshot().communityIdentity)
        assertFalse(store.data.first().asMap().values.contains("original-community"))
        val encryptedSnapshot = requireNotNull(store.data.first()[stringPreferencesKey("ditto_pdl_frozen_snapshot")])
        assertFalse(encryptedSnapshot.contains("original-community"))
        assertFalse(encryptedSnapshot.contains("synthetic-pdl"))
        var caller = 42
        val endpoint = HostPdlSnapshotV1(
            "session", 42, { true }, { true }, { true },
            readFrozen = first::readFrozenDittoPdlSnapshot,
            withCurrent = { snapshot, action -> first.withCurrentFrozenDittoPdlSnapshot(snapshot, action) },
            callingUid = { caller },
        )
        val payload = endpoint.read("session", "transfer")
        assertTrue(Snapshot.isValidReadResult(payload, "transfer"))
        assertEquals("original-community", payload.getString(Snapshot.KEY_COMMUNITY_IDENTITY))
        assertEquals("synthetic-pdl", payload.getString(Snapshot.KEY_CUSTOM_IDENTITY))
        val nonce = requireNotNull(payload.getByteArray(Snapshot.KEY_NONCE))
        caller = 43
        assertEquals(Snapshot.UNAUTHORIZED, endpoint.acknowledge("session", "transfer", nonce))
        caller = 42
        assertEquals(Snapshot.OK, endpoint.acknowledge("session", "transfer", nonce))
        assertNull(endpoint.withAcknowledgedSnapshot(42, "wrong-session") { true })
        assertEquals(true, endpoint.withAcknowledgedSnapshot(42, "session") { true })

        suspend fun rejected(write: suspend () -> Unit) {
            try {
                write()
                fail("Frozen legacy writer must fail")
            } catch (_: IllegalStateException) { }
        }
        rejected { second.storeDittoPdlSource(DittoPdlSource.COMMUNITY) }
        rejected { second.storeDittoCustomPdl("replacement") }
        rejected { second.storeDittoCommandSettings(DittoPdlSource.COMMUNITY, "replacement") }
        assertEquals("synthetic-pdl", first.getDittoCustomPdl.first())
        assertEquals(frozen.cutoverId, second.freezeDittoPdlForMigration { true }.cutoverId)
        assertEquals("original-community", second.freezeDittoPdlForMigration { true }.communityIdentity)
        store.edit { it[stringPreferencesKey("ditto_custom_pdl")] = "unexpected-change" }
        rejected { second.readFrozenDittoPdlSnapshot() }
        assertEquals(Snapshot.CHANGED, endpoint.acknowledge("session", "transfer", nonce))
        assertEquals(encryptedSnapshot, store.data.first()[stringPreferencesKey("ditto_pdl_frozen_snapshot")])
        store.edit { it[stringPreferencesKey("ditto_custom_pdl")] = "synthetic-pdl" }
        assertEquals("synthetic-pdl", second.readFrozenDittoPdlSnapshot().customIdentity)
        var proofCalled = false
        val wrongId = DittoPdlFrozenSelection(
            frozen.selection, (if (frozen.cutoverId[0] == '0') "1" else "0") + frozen.cutoverId.drop(1),
            frozen.communityIdentity, frozen.customIdentity, frozen.initiallyEnabled
        )
        assertFalse(second.completeDittoPdlHandoff(wrongId) { proofCalled = true; true })
        assertFalse(proofCalled)
        assertFalse(second.completeDittoPdlHandoff(frozen) { false })
        assertFalse(requireNotNull(second.readDittoPdlHandoffState()).completed)
        assertTrue(second.completeDittoPdlHandoff(frozen) { true })
        assertEquals(Snapshot.CHANGED, endpoint.read("session", "transfer").getInt(Snapshot.KEY_CODE))
        val afterCompletion = HostPdlSnapshotV1(
            "session", 42, { true }, { true }, { true },
            readFrozen = first::readFrozenDittoPdlSnapshot,
            withCurrent = { snapshot, action -> first.withCurrentFrozenDittoPdlSnapshot(snapshot, action) },
            callingUid = { caller },
        )
        assertEquals(Snapshot.UNAVAILABLE, afterCompletion.read("session", "transfer").getInt(Snapshot.KEY_CODE))
        endpoint.close()
        assertFalse(second.completeDittoPdlHandoff(frozen) { true })
        rejected { second.freezeDittoPdlForMigration { true } }
        job.cancelAndJoin()

        val restartJob = SupervisorJob()
        try {
            val reopened = PreferenceDataStoreFactory.create(scope = CoroutineScope(restartJob + Dispatchers.IO)) { file }
            val restarted = DataRepository(context, pdlDataStore = reopened, pdlSnapshotAead = { aead })
            assertTrue(restarted.isDittoPdlMigrationFrozen())
            val state = requireNotNull(restarted.readDittoPdlHandoffState())
            assertTrue(state.completed)
            assertEquals(frozen.cutoverId, state.cutoverId)
            assertEquals("synthetic-pdl", restarted.getDittoCustomPdl.first())
            rejected { restarted.storeDittoPdlSource(DittoPdlSource.COMMUNITY) }
            rejected { restarted.freezeDittoPdlForMigration { true } }
            rejected { restarted.readFrozenDittoPdlSnapshot() }
        } finally {
            restartJob.cancelAndJoin()
        }
    }
}
