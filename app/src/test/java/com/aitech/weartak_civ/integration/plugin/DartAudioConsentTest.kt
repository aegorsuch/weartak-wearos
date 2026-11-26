package com.aitech.weartak_civ.integration.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DartAudioConsentTest {
    @Test
    fun restoresSavedOptInButStartsFailClosed() = runBlocking {
        var saved = true
        val consent = DartAudioConsent({ saved }, { saved = it })
        assertFalse(consent.enabled.value)
        consent.initialize()
        assertTrue(consent.enabled.value)
        val restored = DartAudioConsent({ saved }, { saved = it })
        restored.initialize()
        assertTrue(restored.enabled.value)
        consent.setEnabled(false)
        assertFalse(saved)
        val restarted = DartAudioConsent({ saved }, { saved = it })
        restarted.initialize()
        assertFalse(restarted.enabled.value)
    }

    @Test
    fun optOutDuringSavedPreferenceReadDoesNotRestoreStaleConsent() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var saved = true
        val consent = DartAudioConsent({
            reading.complete(Unit)
            finish.await()
            true
        }, { saved = it })
        val loading = async { consent.initialize() }
        reading.await()
        val disabling = async { consent.setEnabled(false) }
        kotlinx.coroutines.yield()
        assertFalse(consent.enabled.value)
        finish.complete(Unit)
        loading.await()
        disabling.await()
        assertFalse(consent.enabled.value)
        assertFalse(saved)
    }

    @Test
    fun optOutDuringAnInFlightEnableCannotReopenConsent() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var saved = false
        val consent = DartAudioConsent({ saved }, {
            if (it) {
                writing.complete(Unit)
                finish.await()
            }
            saved = it
        })
        consent.initialize()
        val enabling = async { consent.setEnabled(true) }
        writing.await()
        val disabling = async { consent.setEnabled(false) }
        kotlinx.coroutines.yield()
        assertFalse(consent.enabled.value)
        finish.complete(Unit)
        enabling.await()
        disabling.await()
        assertFalse(consent.enabled.value)
        assertFalse(saved)
    }

    @Test
    fun failedReadAndWriteRemainFailClosed() = runBlocking {
        val unreadable = DartAudioConsent({ error("read failed") }, {})
        assertTrue(runCatching { unreadable.initialize() }.isFailure)
        assertFalse(unreadable.enabled.value)

        val consent = DartAudioConsent({ true }, { error("write failed") })
        consent.initialize()
        var revocations = 0
        consent.addRevocation { revocations++ }
        assertTrue(runCatching { consent.setEnabled(false) }.isFailure)
        assertFalse(consent.enabled.value)
        assertEquals(1, revocations)
    }

    @Test
    fun explicitOptOutCanPersistEvenWhenInitialReadFails() = runBlocking {
        var saved = true
        val consent = DartAudioConsent({ error("read failed") }, { saved = it })
        assertTrue(runCatching { consent.initialize() }.isFailure)
        consent.setEnabled(false)
        assertFalse(saved)
        assertFalse(consent.enabled.value)
        consent.initialize()
    }
}
