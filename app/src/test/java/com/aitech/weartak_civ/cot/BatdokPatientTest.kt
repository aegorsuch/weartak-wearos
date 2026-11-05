package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BatdokPatientTest {
    private val now = Instant.parse("2026-11-04T22:00:00Z").toEpochMilli()
    private val location = DataRepository.LocationSnapshot(now, 0.1, 0.2, 10.0, 5f, 6f, null, null)
    private val profile = BatdokPatientProfile("SYNTHETIC <&\"'", "army", 2000)
    private val physio = SelfStatusInputs(
        SelfStatusPolicy(false, null, true, true, true, true, 28, 60),
        PhysioSample(72, now - 1_000), PhysioSample(93f, now - 2_000)
    )

    @Test fun codecMatchesIndependentPythonPbkdf2GzipAndDotNetAesFixture() {
        // Synthetic non-operational parameters; fixture generated independently of this implementation.
        val codec = BatdokPatientCodec("SYNTHETIC-NOT-OPERATIONAL".toCharArray(), "synthetic-test-salt".toByteArray())
        assertEquals(
            "8pX/EyMtmRPVBBqhij3ILPm82l8y7pfomOvVUgw0zegAxnXReoTOnehQKiJNLnz+2Mw8+oeUcQE9oSROV787FA==",
            codec.encode("""{"id":"0123456789abcdef","name":"SYNTHETIC"}""")
        )
        val input = """{"name":"SYNTHETIC \u0394","vitalSigns":[]}"""
        val encoded = codec.encode(input)
        assertFalse(encoded.contains('\n'))
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
            PBEKeySpec("SYNTHETIC-NOT-OPERATIONAL".toCharArray(), "synthetic-test-salt".toByteArray(), 65_536, 256)
        )
        val decrypt = Cipher.getInstance("AES/ECB/PKCS5Padding")
        decrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encoded, "AES"))
        val decoded = GZIPInputStream(ByteArrayInputStream(decrypt.doFinal(Base64.getDecoder().decode(encoded))))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertEquals(input, decoded)
        codec.close()
        assertThrows(IllegalStateException::class.java) { codec.encode(input) }
    }

    @Test fun profileUsesExactUnitCallsignAndAgeWithFreshHrOnlyAndMillis() {
        val patient = JSONObject(BatdokPatient.json("0123456789abcdef", profile, location, physio, now))
        assertEquals(profile.callsign, patient.getString("name"))
        assertEquals(profile.callsign, patient.getString("alias"))
        assertEquals("Army", patient.getString("unit"))
        assertFalse(patient.has("sex"))
        assertFalse(patient.has("gender"))
        val loc = patient.getJSONObject("location")
        assertEquals(location.lat, loc.getDouble("latitude"), 0.0)
        assertEquals(now, loc.getLong("timestamp"))
        val atmist = patient.getJSONObject("atmistReport")
        assertEquals(26, atmist.getInt("age"))
        assertEquals(now, atmist.getLong("timeOfIncident"))
        listOf("mechanismsOfInjury", "injuriesSustained", "treatmentsGiven").forEach {
            assertEquals(0, atmist.getJSONArray(it).length())
        }
        val vitals = atmist.getJSONArray("vitalSigns")
        assertEquals(1, vitals.length())
        assertEquals("HR", vitals.getJSONObject(0).getString("name"))
        assertEquals("72", vitals.getJSONObject(0).getString("value"))
        assertEquals("bpm", vitals.getJSONObject(0).getString("unit"))
        assertEquals(now - 1_000, vitals.getJSONObject(0).getLong("timestamp"))
        val nextYear = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli()
        assertEquals(27, JSONObject(BatdokPatient.json("0123456789abcdef",
            profile, location, physio, nextYear)).getJSONObject("atmistReport").getInt("age"))
    }

    @Test fun unknownAgeOmitsWholeOptionalReportNotNewbornOrDefaultAge() {
        for (birthYear in listOf(null, 1899, 2027)) {
            val patient = JSONObject(BatdokPatient.json("0123456789abcdef",
                profile.copy(confirmedBirthYear = birthYear), location, physio, now))
            assertFalse(patient.has("atmistReport"))
            assertTrue(patient.has("id"))
            assertTrue(patient.has("location"))
        }
        val newborn = JSONObject(BatdokPatient.json("0123456789abcdef",
            profile.copy(confirmedBirthYear = 2026), location, physio, now))
        assertEquals(0, newborn.getJSONObject("atmistReport").getInt("age"))
    }

    @Test fun noneAndLegacyTypesNeverBecomeLiteralUnitOrBranchInference() {
        listOf("none", "N/A", "Coalition Military", "Child", "0").forEach {
            assertFalse(JSONObject(BatdokPatient.json("0123456789abcdef",
                profile.copy(unit = it), location, physio, now)).has("unit"))
        }
    }

    @Test fun hrPolicyAndExactFreshnessBoundsAreAppliedNotRawSamples() {
        val inputs = listOf(
            physio.copy(policy = physio.policy.copy(monitoring = false)),
            physio.copy(policy = physio.policy.copy(worn = false)),
            physio.copy(policy = physio.policy.copy(heartRateEnabled = false)),
            physio.copy(heartRate = null),
            physio.copy(heartRate = PhysioSample(19, now)),
            physio.copy(heartRate = PhysioSample(251, now)),
            physio.copy(heartRate = PhysioSample(72, now - 120_001)),
            physio.copy(heartRate = PhysioSample(72, now + 30_001))
        )
        inputs.forEach {
            val patient = JSONObject(BatdokPatient.json("0123456789abcdef", profile, location, it, now))
            assertEquals(0, patient.getJSONObject("atmistReport").getJSONArray("vitalSigns").length())
        }
        listOf(now - 120_000, now + 30_000).forEach {
            val patient = JSONObject(BatdokPatient.json("0123456789abcdef",
                profile, location, physio.copy(heartRate = PhysioSample(72, it)), now))
            assertEquals(1, patient.getJSONObject("atmistReport").getJSONArray("vitalSigns").length())
        }
    }

    @Test fun cotHasSeparateStableIdentityMatchingPayloadCoordinatesAndBoundedExpiry() {
        val xml = BatdokPatient.cot("0123456789abcdef", profile.callsign, location, "SYNTHETIC-ENCODED", now)
        val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray())).documentElement
        assertEquals("0123456789abcdef", event.getAttribute("uid"))
        assertEquals("a-f-G-U-C", event.getAttribute("type"))
        assertEquals(now + 60_000, Instant.parse(event.getAttribute("stale")).toEpochMilli())
        assertEquals("SYNTHETIC-ENCODED", event.getElementsByTagName("batdokpayload").item(0).textContent)
        val contact = event.getElementsByTagName("contact").item(0) as org.w3c.dom.Element
        assertEquals(profile.callsign, contact.getAttribute("callsign"))
        val point = event.getElementsByTagName("point").item(0) as org.w3c.dom.Element
        assertEquals(location.lat, point.getAttribute("lat").toDouble(), 0.0)
        assertEquals(location.lon, point.getAttribute("lon").toDouble(), 0.0)
    }
}
