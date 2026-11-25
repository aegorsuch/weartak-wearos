package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoCommandSettingsTest {
    @Test fun atomicSavePreservesOmittedToggleAndCommunityCredential() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val original = MessageDigest.getInstance("SHA-256").digest(repo.communityDittoPdl().toByteArray())
        repo.storeEnableDittoService(false)
        repo.storeDittoCommandSettings(DittoPdlSource.CUSTOM, "synthetic-pdl")
        assertEquals("synthetic-pdl", repo.getEffectiveDittoPdl.first())
        assertEquals(DittoPdlSource.CUSTOM, repo.getDittoPdlSource.first())
        assertFalse(repo.getEnableDittoService.first())
        repo.storeDittoCommandSettings(DittoPdlSource.COMMUNITY, null)
        assertFalse(repo.getEnableDittoService.first())
        assertEquals(DittoPdlSource.COMMUNITY, repo.getDittoPdlSource.first())
        assertEquals("synthetic-pdl", repo.getDittoCustomPdl.first())
        assertArrayEquals(original,
            MessageDigest.getInstance("SHA-256").digest(repo.getEffectiveDittoPdl.first().toByteArray()))
    }

    @Test fun migrationSelectionPreservesCustomAndDetectsChanges() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeDittoCommandSettings(DittoPdlSource.CUSTOM, "")
        val empty = repo.readDittoPdlMigrationSelection()
        assertEquals(DittoPdlSource.CUSTOM, empty.source)
        assertEquals("", empty.identity)
        assertTrue(repo.isDittoPdlMigrationSelectionCurrent(empty))

        repo.storeDittoCommandSettings(DittoPdlSource.CUSTOM, "invalid-synthetic-pdl")
        assertFalse(repo.isDittoPdlMigrationSelectionCurrent(empty))
        val invalid = repo.readDittoPdlMigrationSelection()
        assertEquals("invalid-synthetic-pdl", invalid.identity)
        assertTrue(repo.isDittoPdlMigrationSelectionCurrent(invalid))
        repo.storeDittoPdlSource(DittoPdlSource.COMMUNITY)
        assertFalse(repo.isDittoPdlMigrationSelectionCurrent(invalid))
        assertEquals("invalid-synthetic-pdl", repo.getDittoCustomPdl.first())
        repo.storeDittoPdlSource(DittoPdlSource.CUSTOM)
        assertEquals("invalid-synthetic-pdl", repo.readDittoPdlMigrationSelection().identity)
        assertFalse(repo.isDittoPdlMigrationSelectionCurrent(invalid))
    }

    @Test fun migrationAcknowledgmentIsSerializedWithSelectionWriters() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val other = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeDittoCommandSettings(DittoPdlSource.CUSTOM, "synthetic-pdl")
        val selection = repo.readDittoPdlMigrationSelection()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val acknowledgment = async(Dispatchers.IO) {
            repo.withCurrentDittoPdlMigrationSelection(selection) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "ack"
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val writer = async(Dispatchers.IO) {
                writerStarted.countDown()
                other.storeDittoPdlSource(DittoPdlSource.COMMUNITY)
                writerFinished.countDown()
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertEquals("ack", acknowledgment.await())
            writer.await()
            assertNull(repo.withCurrentDittoPdlMigrationSelection(selection) {
                fail("Changed selection must not be acknowledged")
            })
        } finally {
            release.countDown()
        }
    }
}
