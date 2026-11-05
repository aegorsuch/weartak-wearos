package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.cot.*
import com.aitech.weartak_civ.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BatdokPatientPublisherTest {
    private var now = 1_793_829_600_000L
    private var state = BatdokPublishingInput(
        BatdokSharingConsent(true, BatdokSharingStore.CONSENT_VERSION, 1),
        true, BatdokPatientProfile("SYNTHETIC", "army", 2000),
        DataRepository.LocationSnapshot(now, 0.1, 0.2, 10.0, 5f, 6f, null, null),
        SelfStatusInputs(SelfStatusPolicy(false, null, true, true, true, true, 28, 60),
            PhysioSample(72, now), PhysioSample(93f, now))
    )
    private val sent = mutableListOf<String>()
    private val payloads = mutableListOf<String>()
    private var idRequests = 0
    private var beforeWrite: () -> Unit = {}
    private var writeResult: BatdokTakWrite? = null
    private var codec: BatdokPatientEncoder? = BatdokPatientEncoder { payloads += it; "SYNTHETIC-CIPHERTEXT" }
    private val publisher = BatdokPatientPublisher(
        input = { state },
        patientId = { idRequests++; "0123456789abcdef" },
        encoder = { codec },
        sendToTak = { xml, allowed ->
            beforeWrite()
            if (writeResult != null) writeResult!!
            else if (allowed()) { sent += xml; BatdokTakWrite(1, 1) } else BatdokTakWrite(0, 1)
        },
        nowMillis = { now }
    )

    @Test fun defaultOnUnknownVersionAndMissingCodecNeverCreateIdentityOrSend() = runBlocking {
        state = state.copy(consent = BatdokSharingConsent(true, 0, 1))
        assertEquals(BatdokPatientStatus.CONFIRMATION_REQUIRED, publisher.publish())
        state = state.copy(consent = BatdokSharingConsent(true, 999, 1))
        assertEquals(BatdokPatientStatus.CONFIRMATION_REQUIRED, publisher.publish())
        state = state.copy(consent = BatdokSharingConsent(false, 1, 1))
        assertEquals(BatdokPatientStatus.DISABLED, publisher.publish())
        state = state.copy(consent = BatdokSharingConsent(true, 1, 1))
        codec = null
        assertEquals(BatdokPatientStatus.NOT_PROVISIONED, publisher.publish())
        assertEquals(0, idRequests)
        assertTrue(sent.isEmpty())
    }

    @Test fun sameIdFreshUpdatesAfterReconnectAndNoOfflineReplay() = runBlocking {
        state = state.copy(allowed = false)
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        assertEquals(0, idRequests)
        state = state.copy(allowed = true)
        assertEquals(BatdokPatientStatus.SENT, publisher.publish())
        now += 25_000
        state = state.copy(physiology = state.physiology.copy(heartRate = PhysioSample(81, now)))
        assertEquals(BatdokPatientStatus.SENT, publisher.publish())
        assertEquals(2, sent.size)
        assertEquals("0123456789abcdef", JSONObject(payloads[0]).getString("id"))
        assertEquals("0123456789abcdef", JSONObject(payloads[1]).getString("id"))
        assertEquals("81", JSONObject(payloads[1]).getJSONObject("atmistReport")
            .getJSONArray("vitalSigns").getJSONObject(0).getString("value"))
        state = state.copy(allowed = false)
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        now += 25_000
        state = state.copy(allowed = true)
        assertEquals(BatdokPatientStatus.SENT, publisher.publish())
        assertEquals(3, sent.size)
        state = state.copy(consent = BatdokSharingConsent(false, 0, 2))
        assertEquals(BatdokPatientStatus.DISABLED, publisher.publish())
        assertEquals(3, sent.size)
    }

    @Test fun revocationOffAndOnCannotReleaseQueuedOldPayload() = runBlocking {
        beforeWrite = { state = state.copy(consent = BatdokSharingConsent(true, 1, 3)) }
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        assertTrue(sent.isEmpty())
        beforeWrite = { state = state.copy(consent = BatdokSharingConsent(false, 0, 4)) }
        publisher.publish()
        assertTrue(sent.isEmpty())
    }

    @Test fun changedPolicyStaleSampleChangedCallsignOrRouteBlocksQueuedMessage() = runBlocking {
        val original = state
        val mutations: List<() -> Unit> = listOf(
            { state = state.copy(allowed = false) },
            { state = state.copy(physiology = state.physiology.copy(policy = state.physiology.policy.copy(worn = false))) },
            { state = state.copy(profile = state.profile.copy(callsign = "NEW")) },
            { now += 10_000 },
            { state = state.copy(physiology = state.physiology.copy(heartRate = null)) }
        )
        for (mutation in mutations) {
            state = original
            now = original.location!!.timeMillis
            beforeWrite = mutation
            publisher.publish()
            assertTrue(sent.isEmpty())
        }
        state = original.copy(physiology = original.physiology.copy(heartRate = PhysioSample(72, now - 119_999)))
        beforeWrite = { now += 2 }
        publisher.publish()
        assertTrue(sent.isEmpty())
    }

    @Test fun failuresAreWrappedWithSanitizedStageCodes() = runBlocking {
        codec = BatdokPatientEncoder { throw javax.crypto.BadPaddingException("SECRET-DETAIL") }
        val encryption = runCatching { publisher.publish() }.exceptionOrNull()
        assertEquals("E:BadPaddingException", batdokFailureCode(encryption!!))
        assertFalse(batdokFailureCode(encryption).contains("SECRET"))
        codec = BatdokPatientEncoder { "SYNTHETIC-CIPHERTEXT" }
        val failingId = BatdokPatientPublisher(
            input = { state }, patientId = { throw java.io.IOException("datastore") },
            encoder = { codec }, sendToTak = { _, _ -> BatdokTakWrite(1, 1) }, nowMillis = { now }
        )
        assertEquals("I:IOException", batdokFailureCode(runCatching { failingId.publish() }.exceptionOrNull()!!))
        val failingSend = BatdokPatientPublisher(
            input = { state }, patientId = { "0123456789abcdef" },
            encoder = { codec }, sendToTak = { _, _ -> throw org.json.JSONException("x") }, nowMillis = { now }
        )
        assertEquals("T:JSONException", batdokFailureCode(runCatching { failingSend.publish() }.exceptionOrNull()!!))
        val failingConfig = BatdokPatientPublisher(
            input = { state }, patientId = { "0123456789abcdef" },
            encoder = { throw IllegalArgumentException("salt") }, sendToTak = { _, _ -> BatdokTakWrite(1, 1) }, nowMillis = { now }
        )
        assertEquals("C:IllegalArgumentException", batdokFailureCode(runCatching { failingConfig.publish() }.exceptionOrNull()!!))
        assertEquals("U:IllegalStateException", batdokFailureCode(IllegalStateException("x")))
        assertEquals("U:Exception", batdokFailureCode(object : Exception("anon") {}))
        val cancelling = BatdokPatientPublisher(
            input = { state }, patientId = { throw kotlinx.coroutines.CancellationException("stop") },
            encoder = { codec }, sendToTak = { _, _ -> BatdokTakWrite(1, 1) }, nowMillis = { now }
        )
        assertTrue(runCatching { cancelling.publish() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        val fatal = BatdokPatientPublisher(
            input = { state }, patientId = { throw OutOfMemoryError() },
            encoder = { codec }, sendToTak = { _, _ -> BatdokTakWrite(1, 1) }, nowMillis = { now }
        )
        assertTrue(runCatching { fatal.publish() }.exceptionOrNull() is OutOfMemoryError)
        assertTrue(sent.isEmpty())
    }

    @Test fun outcomeDetailDistinguishesActualWritesFromEachWaitCause() = runBlocking {
        assertEquals(BatdokPatientStatus.SENT, publisher.publish())
        assertEquals("streams=1/1", publisher.lastDetail)
        writeResult = BatdokTakWrite(2, 3)
        assertEquals(BatdokPatientStatus.SENT, publisher.publish())
        assertEquals("streams=2/3", publisher.lastDetail)
        writeResult = BatdokTakWrite(0, 0)
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        assertEquals("no_connected_stream", publisher.lastDetail)
        writeResult = BatdokTakWrite(0, 2)
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        assertEquals("write_failed", publisher.lastDetail)
        writeResult = BatdokTakWrite(0, 0, "off_wrist")
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        assertEquals("off_wrist", publisher.lastDetail)
        writeResult = null
        beforeWrite = { state = state.copy(profile = state.profile.copy(callsign = "CHANGED")) }
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        assertEquals("revalidation_rejected", publisher.lastDetail)
        beforeWrite = {}
        state = state.copy(profile = state.profile.copy(callsign = "SYNTHETIC"), allowed = false, waitReason = "no_tak_connected")
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        assertEquals("no_tak_connected", publisher.lastDetail)
        state = state.copy(waitReason = null)
        publisher.publish()
        assertEquals("not_allowed", publisher.lastDetail)
        state = state.copy(allowed = true, location = null)
        assertEquals(BatdokPatientStatus.LOCATION_UNAVAILABLE, publisher.publish())
        assertEquals("", publisher.lastDetail)
    }

    @Test fun outcomeLogIsFixedFormatWithoutIdentityPayloadOrLocation() = runBlocking {
        assertEquals(BatdokPatientStatus.SENT, publisher.publish())
        val line = batdokOutcomeLog(BatdokPatientStatus.SENT, publisher.lastDetail)
        assertEquals("BATTAK patient outcome: SENT streams=1/1", line)
        listOf("0123456789abcdef", "SYNTHETIC", "0.1", "0.2", "CIPHERTEXT").forEach { assertFalse(line.contains(it)) }
        assertEquals("BATTAK patient outcome: LOCATION_UNAVAILABLE",
            batdokOutcomeLog(BatdokPatientStatus.LOCATION_UNAVAILABLE, ""))
    }

    @Test fun missingInvalidOrFutureLocationAndEmptyIdentityDoNotSend() = runBlocking {
        val original = state
        for (location in listOf(null, original.location!!.copy(timeMillis = 0),
            original.location!!.copy(timeMillis = now + 30_001), original.location!!.copy(lat = Double.NaN),
            original.location!!.copy(lat = 91.0), original.location!!.copy(lon = Double.POSITIVE_INFINITY))) {
            state = original.copy(location = location)
            assertEquals(BatdokPatientStatus.LOCATION_UNAVAILABLE, publisher.publish())
        }
        state = original.copy(profile = original.profile.copy(callsign = ""))
        assertEquals(BatdokPatientStatus.CALLSIGN_REQUIRED, publisher.publish())
        assertTrue(sent.isEmpty())
    }

    @Test fun lastValidLocationOfAnyAgeIsSentWithOriginalTimeAndAccuracy() = runBlocking {
        val original = state
        for (age in listOf(0L, 120_001L, 6L * 60 * 60 * 1000, 30L * 24 * 60 * 60 * 1000)) {
            sent.clear(); payloads.clear()
            val fix = original.location!!.copy(timeMillis = now - age, ce = 42f)
            state = original.copy(location = fix)
            assertEquals(BatdokPatientStatus.SENT, publisher.publish())
            val loc = JSONObject(payloads.single()).getJSONObject("location")
            assertEquals(fix.timeMillis, loc.getLong("timestamp"))
            assertTrue(sent.single().contains("ce=\"42.0\""))
        }
    }

    @Test fun oldLocationStillObeysConsentRouteCallsignAndCodecGates() = runBlocking {
        val old = state.copy(location = state.location!!.copy(timeMillis = now - 86_400_000L))
        state = old.copy(consent = BatdokSharingConsent(false, 1, 1))
        assertEquals(BatdokPatientStatus.DISABLED, publisher.publish())
        state = old.copy(consent = BatdokSharingConsent(true, 0, 1))
        assertEquals(BatdokPatientStatus.CONFIRMATION_REQUIRED, publisher.publish())
        state = old.copy(allowed = false)
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        state = old.copy(profile = old.profile.copy(callsign = ""))
        assertEquals(BatdokPatientStatus.CALLSIGN_REQUIRED, publisher.publish())
        state = old
        beforeWrite = { state = state.copy(location = state.location!!.copy(timeMillis = now - 1)) }
        assertEquals(BatdokPatientStatus.WAITING_TAK, publisher.publish())
        codec = null
        state = old
        assertEquals(BatdokPatientStatus.NOT_PROVISIONED, publisher.publish())
        assertTrue(sent.isEmpty())
    }
}
