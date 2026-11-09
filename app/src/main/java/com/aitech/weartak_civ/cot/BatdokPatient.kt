package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository.LocationSnapshot
import com.aitech.weartak_civ.data.ProfileUnit
import com.aitech.weartak_civ.data.SelfStatusInputs
import com.aitech.weartak_civ.data.selfStatusSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

// birthYear is the profile year (user-saved, or the default fallback); receivers cannot distinguish them.
data class BatdokPatientProfile(val callsign: String, val unit: String, val birthYear: Int?)

object BatdokPatient {
    const val STALE_SECONDS = 60

    fun json(
        id: String,
        profile: BatdokPatientProfile,
        location: LocationSnapshot,
        physiology: SelfStatusInputs,
        nowMillis: Long,
        reportTimeMillis: Long = nowMillis
    ): String {
        require(id.matches(Regex("[0-9a-f]{16}"))) { "BATTAK patient identity must be 16 hex digits" }
        require(profile.callsign.isNotBlank()) { "BATTAK patient callsign required" }
        require(EmergencyLocationPolicy.usable(location)) { "BATTAK patient location required" }
        val year = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).year
        val age = profile.birthYear?.takeIf { it in 1900..year }?.let { year - it }
        val filtered = selfStatusSnapshot(physiology, nowMillis).physio
        return JSONObject().apply {
            put("id", id)
            put("name", profile.callsign)
            put("alias", profile.callsign)
            ProfileUnit.fromStored(profile.unit)?.batdokValue?.let { put("unit", it) }
            put("location", JSONObject().apply {
                put("latitude", location.lat)
                put("longitude", location.lon)
                location.hae?.takeIf(Double::isFinite)?.let { put("altitude", it) }
                put("timestamp", location.timeMillis)
            })
            if (age != null) {
                val vitals = JSONArray()
                filtered?.hr?.let {
                    vitals.put(JSONObject().put("name", "HR").put("value", it.toString())
                        .put("unit", "bpm").put("timestamp", filtered.measuredAt))
                }
                // Skin temperature is not the receiver's clinical/core "Temp" measurement.
                // No verified skin-temperature wire field exists; omit rather than mislabel it.
                put("atmistReport", JSONObject().put("age", age).put("timeOfIncident", reportTimeMillis)
                    .put("mechanismsOfInjury", JSONArray()).put("injuriesSustained", JSONArray())
                    .put("vitalSigns", vitals).put("treatmentsGiven", JSONArray()))
            }
        }.toString()
    }

    fun cot(id: String, callsign: String, location: LocationSnapshot, payload: String, nowMillis: Long): String {
        val time = CotEventTimeFactory.at(nowMillis, staleOffset = CotTimeOffset(seconds = STALE_SECONDS))
        fun number(value: Double?) = value?.takeIf(Double::isFinite)?.toString() ?: "9999999.0"
        return "<event version=\"2.0\" uid=\"${CotXml.escape(id)}\" type=\"a-f-G-U-C\" how=\"m-g\"" +
            " time=\"${time.timeCot}\" start=\"${time.startCot}\" stale=\"${time.staleCot}\">" +
            "<point lat=\"${location.lat}\" lon=\"${location.lon}\" hae=\"${number(location.hae)}\"" +
            " ce=\"${number(location.ce?.toDouble())}\" le=\"${number(location.le?.toDouble())}\"/>" +
            "<detail><contact callsign=\"${CotXml.escape(callsign)}\"/>" +
            "<batdokpayload>${CotXml.escape(payload)}</batdokpayload></detail></event>"
    }
}
