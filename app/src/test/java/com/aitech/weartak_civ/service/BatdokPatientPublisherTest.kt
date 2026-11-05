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
    private var codec: BatdokPatientEncoder? = BatdokPatientEncoder { payloads += it; "SYNTHETIC-CIPHERTEXT" }
    private val publisher = BatdokPatientPublisher(
        input = { state },
        patientId = { idRequests++; "0123456789abcdef" },
        encoder = { codec },
        sendToTak = { xml, allowed -> beforeWrite(); allowed().also { if (it) sent += xml } },
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

    @Test fun staleInvalidOrFutureLocationAndEmptyIdentityDoNotSend() = runBlocking {
        val original = state
        for (location in listOf(null, original.location!!.copy(timeMillis = now - 120_001),
            original.location!!.copy(timeMillis = now + 30_001), original.location!!.copy(lat = Double.NaN))) {
            state = original.copy(location = location)
            assertEquals(BatdokPatientStatus.LOCATION_UNAVAILABLE, publisher.publish())
        }
        state = original.copy(profile = original.profile.copy(callsign = ""))
        assertEquals(BatdokPatientStatus.CALLSIGN_REQUIRED, publisher.publish())
        assertTrue(sent.isEmpty())
    }
}
