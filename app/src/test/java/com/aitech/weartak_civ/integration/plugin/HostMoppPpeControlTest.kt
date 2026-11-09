package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.ComponentName
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.MoppPpeStore
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultCode
import com.weartak.plugin.sdk.mopp.MoppPpeContract
import com.weartak.plugin.sdk.mopp.aidl.IMoppPpeControlV1
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HostMoppPpeControlTest {
    private lateinit var repo: DataRepository
    private val controls = mutableListOf<HostMoppPpeControl>()

    @Before fun setup(): Unit = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { prefs ->
            prefs.remove(MoppPpeStore.STATUS)
            prefs.remove(MoppPpeStore.SAVED_AT)
            prefs.remove(MoppPpeStore.ENABLED)
            prefs.remove(MoppPpeStore.REVISION)
            prefs.remove(MoppPpeStore.LAST_MUTATION)
            prefs.remove(MoppPpeStore.LAST_REQUEST)
        }
    }

    @After fun close() { controls.forEach { it.close() } }

    private fun control(
        authorized: () -> Boolean = { true },
        caller: () -> Int = { 42 },
    ) = HostMoppPpeControl(repo.moppPpeStore, 42, authorized, caller).also { controls += it }

    @Test fun requiresTypedReadAndCompletedHandshakeWithoutCreatingSavedDefault() {
        val control = control()
        assertFalse(MoppPluginAvailability.available.value)
        assertEquals(MoppPpeResultCode.INACTIVE_SESSION, control.setEnabled(0, "early", true).code)
        val read = control.read()
        assertTrue(read.accepted)
        assertNull(read.state!!.selection)
        assertNull(read.state!!.savedAtEpochMillis)
        assertTrue(read.state!!.enabled)
        assertFalse(read.state!!.contributing)
        assertFalse(MoppPluginAvailability.available.value)
        control.ready()
        assertTrue(MoppPluginAvailability.available.value)
    }

    @Test fun persistsCasAndIdempotenceWithoutFalseTimestamps() {
        val control = control()
        control.read()
        control.ready()
        val enabled = control.setEnabled(0, "enable", true).state!!
        assertNull(enabled.savedAtEpochMillis)
        val saved = control.save(1, "save", "2").state!!
        assertEquals(2L, saved.revision)
        assertEquals("2", saved.selection)
        assertNotNull(saved.savedAtEpochMillis)
        assertTrue(saved.contributing)
        assertEquals(saved, control.save(1, "save", "2").state)
        assertEquals(MoppPpeResultCode.INVALID_REQUEST, control.save(1, "save", "3").code)
        assertEquals(MoppPpeResultCode.REVISION_CONFLICT, control.save(1, "stale", "3").code)
        assertEquals(saved, control.read().state)
        val disabled = control.setEnabled(2, "disable", false).state!!
        assertEquals(saved.savedAtEpochMillis, disabled.savedAtEpochMillis)
        assertEquals("2", disabled.selection)
        assertTrue(disabled.enabled)
        assertTrue(disabled.contributing)
        assertEquals(3L, disabled.revision)
        assertEquals(disabled, control.setEnabled(2, "disable", false).state)
        assertEquals(MoppPpeResultCode.REVISION_CONFLICT, control.setEnabled(2, "stale-disable", false).code)
    }

    @Test fun checksUidAndTrustOnEveryCallAndRevokesReplacedLease() {
        var caller = 42
        var trusted = true
        val old = control({ trusted }, { caller })
        old.read()
        old.ready()
        caller = 99
        assertEquals(MoppPpeResultCode.UNAUTHORIZED, old.read().code)
        caller = 42
        trusted = false
        assertEquals(MoppPpeResultCode.UNAUTHORIZED, old.read().code)
        assertFalse(MoppPluginAvailability.available.value)
        trusted = true
        assertEquals(MoppPpeResultCode.INACTIVE_SESSION, old.read().code)
        val replacement = control()
        replacement.ready()
        replacement.read()
        old.close()
        assertTrue(MoppPluginAvailability.available.value)
        val third = control()
        assertEquals(MoppPpeResultCode.INACTIVE_SESSION, replacement.save(0, "late", "0").code)
        third.close()
        assertFalse(MoppPluginAvailability.available.value)
    }

    @Test fun preservesLegacySavedTimestampAcrossLossAndResume() = runBlocking {
        repo.legacyDataStore().edit {
            it[MoppPpeStore.STATUS] = "3"
            it[MoppPpeStore.ENABLED] = false
        }
        val first = control()
        first.read()
        first.ready()
        val saved = first.read().state!!
        assertTrue(saved.contributing)
        assertNull(saved.savedAtEpochMillis)
        assertTrue(repo.snapshotSelfStatusRemarks().contains("MOPP3"))
        first.close()
        assertFalse(repo.snapshotSelfStatusRemarks().contains("MOPP"))
        val second = control()
        second.ready()
        val resumed = second.read().state!!
        assertEquals(saved, resumed)
        assertTrue(repo.snapshotSelfStatusRemarks().contains("MOPP3"))
    }

    @Test fun legacyDisabledStateIsEffectivelyEnabledWithoutReadSideEffectsAndCanSave() = runBlocking {
        val dataStore = repo.legacyDataStore()
        dataStore.edit {
            it[MoppPpeStore.ENABLED] = false
            it[MoppPpeStore.STATUS] = "0"
            it[MoppPpeStore.SAVED_AT] = 123L
            it[MoppPpeStore.REVISION] = 7L
        }
        val before = dataStore.data.first()
        val control = control()
        control.ready()
        val state = control.read().state!!
        assertTrue(state.enabled)
        assertTrue(state.contributing)
        assertEquals("0", state.selection)
        assertEquals(123L, state.savedAtEpochMillis)
        assertEquals(7L, state.revision)
        assertTrue(repo.getEnableMopp.first())
        assertEquals(com.aitech.weartak_civ.data.MoppStatus.LEVEL_0, repo.getMoppStatus.first())
        assertEquals(before, dataStore.data.first())
        assertTrue(repo.snapshotSelfStatusRemarks().contains("MOPP0"))
        val saved = control.save(7, "save-disabled", "4")
        assertTrue(saved.accepted)
        assertEquals("4", saved.state!!.selection)
        assertEquals(8L, saved.state!!.revision)
        assertTrue(dataStore.data.first()[MoppPpeStore.ENABLED]!!)
        control.close()
        assertFalse(repo.selfStatusInputs().first().policy.moppEnabled)
        assertFalse(repo.moppPpeStore.read().contributing)
        assertEquals(saved.state!!.savedAtEpochMillis, repo.moppPpeStore.read().savedAtEpochMillis)
    }

    @Test fun legacyDisableDoesNotCreateSelectionOrTimestamp() {
        val control = control()
        control.read()
        control.ready()
        val enabled = control.setEnabled(0, "disable-unset", false).state!!
        assertTrue(enabled.enabled)
        assertNull(enabled.selection)
        assertNull(enabled.savedAtEpochMillis)
        assertFalse(enabled.contributing)
        val saved = control.save(1, "first-save", "none").state!!
        assertEquals("none", saved.selection)
        assertNotNull(saved.savedAtEpochMillis)
        assertTrue(saved.contributing)
    }

    @Test fun firstSaveNeedsNoEnable() {
        val control = control()
        control.read()
        control.ready()
        val saved = control.save(0, "first-save", "2")
        assertTrue(saved.accepted)
        assertEquals(1L, saved.state!!.revision)
        assertEquals("2", saved.state!!.selection)
        assertTrue(saved.state!!.contributing)
    }

    @Test fun boundCompatiblePluginNeedsTypedReadAndDeathRevokesBeforeManagerCleanup() {
        verifyBoundPluginHandshakeAndDeath(2)
    }

    @Test fun canonicalV3PluginUsesTypedReadAndDeathRevokesBeforeManagerCleanup() {
        verifyBoundPluginHandshakeAndDeath(3)
    }

    private fun verifyBoundPluginHandshakeAndDeath(apiVersion: Int) {
        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.repo } returns repo
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(app, Process.myUid()) } returns setOf(MoppPluginAvailability.PACKAGE)
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.onConnectionReady(any()) } returns true
        val component = ComponentName(MoppPluginAvailability.PACKAGE, ".PluginService")
        val connection = PluginConnection(app, ingress, component, Process.myUid(), manager,
            mockk<PluginTrust>(relaxed = true))
        val plugin = FakeWearTakPlugin(MoppPluginAvailability.PACKAGE, apiVersion = apiVersion)
        try {
            connection.onServiceConnected(component, plugin.asBinder())
            assertFalse(MoppPluginAvailability.available.value)
            awaitPluginLifecycle(MoppPluginAvailability.PACKAGE)
            val extras = plugin.lastInit!!.hostExtras
            assertTrue(extras.getBoolean(MoppPpeContract.EXTRA_CAPABILITY))
            val control = IMoppPpeControlV1.Stub.asInterface(extras.getBinder(MoppPpeContract.EXTRA_CONTROL))
            assertTrue(control.read().accepted)
            assertTrue(MoppPluginAvailability.available.value)
            connection.binderDeathRecipientForTest.binderDied()
            assertFalse(MoppPluginAvailability.available.value)
            assertEquals(MoppPpeResultCode.INACTIVE_SESSION, control.read().code)
        } finally {
            connection.tearDownBinder("test")
            awaitPluginLifecycle(MoppPluginAvailability.PACKAGE)
        }
    }

    @Test fun sameSelectionIsExplicitNewSaveAndLostAcknowledgementRetryIsNot() = runBlocking {
        var clock = 100L
        val store = MoppPpeStore(repo.legacyDataStore(), { true }, { clock })
        store.setEnabled(0, "enable", true)
        val first = store.save(1, "first", "none").state!!
        assertEquals(100L, first.savedAtEpochMillis)
        clock = 200
        assertEquals(first, store.save(1, "first", "none").state)
        val second = store.save(2, "second", "none").state!!
        assertEquals(200L, second.savedAtEpochMillis)
        val reopened = MoppPpeStore(repo.legacyDataStore(), { true }, { 300L })
        assertEquals(second, reopened.read())
        assertEquals(second, reopened.save(2, "second", "none").state)
        assertEquals(MoppPpeResultCode.INVALID_REQUEST, reopened.save(3, "", "2").code)
        assertEquals(MoppPpeResultCode.INVALID_REQUEST, reopened.save(3, "invalid", "5").code)
        assertEquals(second, reopened.read())
    }

    @Test fun failedPersistenceNeverAcknowledgesSuccessOrChangesAuthoritativeSelection() {
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("test disk failure")
        }
        val control = HostMoppPpeControl(MoppPpeStore(store, { false }), 42, { true }, { 42 })
        controls += control
        control.ready()
        assertTrue(control.read().accepted)
        val result = control.setEnabled(0, "enable", true)
        assertFalse(result.accepted)
        assertEquals(MoppPpeResultCode.PERSISTENCE_FAILED, result.code)
        assertNull(result.state)
        assertNull(control.read().state!!.selection)
    }

}
