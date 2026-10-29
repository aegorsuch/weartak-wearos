package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.Utils.PhysioUtils.convertFarenheitToCelsius
import com.aitech.weartak_civ.data.DataRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object CotMessageBuilder {
    private const val CHAT_STALE_MILLIS = 24L * 60 * 60 * 1000

    data class PliOptions(
        val callsign: String,
        val role: String,
        val team: String,
        val includeBatdok: Boolean,
        val hailDetail: String,
        val dittoMetadata: CotDetailBuilder.DittoMetadata? = null,
    )

    fun pli(snapshot: DataRepository.PliSnapshot, options: PliOptions): String {
        val location = requireNotNull(snapshot.loc?.takeIf(EmergencyLocationPolicy::usable)) {
            "Position PLI requires a valid location fix"
        }
        val physio = snapshot.physioData.data
        val time = snapshot.time
        val lat = location.lat
        val lon = location.lon
        val hae = location.hae?.takeIf { it.isFinite() } ?: 0.0
        val ce = location.ce?.takeIf { it.isFinite() }?.toInt() ?: 9999
        val le = location.le?.takeIf { it.isFinite() }?.toInt() ?: 9999
        val course = location.course?.toInt() ?: 0
        val speed = location.speed?.toInt() ?: 0
        val uid = snapshot.systemData.deviceId
        val heartRate = rectifiedInt(physio.heartRateBpm)
        val skinTemp = rectifiedNumber(physio.skinTempF, "%.1f", negIsInvalid = false, zeroIsInvalid = false)
        val batdok = if (options.includeBatdok) batdokDetail(physio, time, heartRate, skinTemp) else ""
        val physiology = CotDetailBuilder.physiology(uid, snapshot.monitoredPhysio, snapshot.systemData.batteryPercent)

        val status = CotDetailBuilder.status(snapshot.systemData.batteryPercent, readiness = true)

        val point = listOf(
            "lat" to "%.6f".format(lat), "lon" to "%.6f".format(lon), "hae" to "%.1f".format(hae),
            "ce" to ce, "le" to le,
        )
        val detail =
            CotDetailBuilder.takv() + status +
                    (location.coordinateSource?.let { provider ->
                        CotXml.element("precisionlocation", listOf(
                            "geopointsrc" to if (provider == android.location.LocationManager.NETWORK_PROVIDER) "NETWORK" else provider.uppercase(),
                        ))
                    } ?: "") +
                    (location.takeIf { time.time - it.timeMillis > 3_000L }?.let {
                        CotXml.element("weartak_location", listOf("timeMillis" to it.timeMillis))
                    } ?: "") +
                    "<remarks>${CotXml.escape(snapshot.remarks)}</remarks>$batdok$physiology" +
                    CotXml.element(
                        "contact",
                        listOf(
                            "endpoint" to "*:-1:stcp",
                            "callsign" to CotXml.escape(options.callsign)
                        )
                    ) +
                    CotXml.element(
                        "__group",
                        listOf(
                            "role" to CotXml.escape(options.role),
                            "name" to CotXml.escape(options.team)
                        )
                    ) +
                    CotXml.element(
                        "track",
                        listOf(
                            "course" to course,
                            "speed" to speed
                        )
                    ) +
                    options.hailDetail +
                    (options.dittoMetadata?.let { CotDetailBuilder.ditto(it) } ?: "")
        return CotXml.event(listOf(
            "version" to "2.0", "uid" to CotXml.escape(uid), "type" to "a-f-G-U-C", "time" to time.startCot,
            "start" to time.startCot, "stale" to time.staleCot, "how" to "m-g",
        ), point, detail)
    }

    fun expiredSelfPli(
        deviceUid: String,
        callsign: String,
        nowMillis: Long,
        location: DataRepository.LocationSnapshot? = null,
    ): String {
        require(deviceUid.isNotBlank())
        // Reuse the last fix so peers don't briefly redraw the expiring marker at 0,0.
        val fix = location?.takeIf {
            it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0
        }
        val time = CotEventTimeFactory.at(nowMillis, staleOffset = CotTimeOffset())
        val detail = CotXml.element(
            "contact",
            listOf(
                "endpoint" to "*:-1:stcp",
                "callsign" to CotXml.escape(callsign),
            ),
        )
        return CotXml.event(
            listOf(
                "version" to "2.0",
                "uid" to CotXml.escape(deviceUid),
                "type" to "a-f-G-U-C",
                "time" to time.timeCot,
                "start" to time.startCot,
                "stale" to time.staleCot,
                "how" to "m-g",
            ),
            listOf(
                "lat" to String.format(java.util.Locale.US, "%.6f", fix?.lat ?: 0.0),
                "lon" to String.format(java.util.Locale.US, "%.6f", fix?.lon ?: 0.0),
                "hae" to String.format(java.util.Locale.US, "%.1f", fix?.hae?.takeIf { it.isFinite() } ?: 0.0),
                "ce" to 9999999,
                "le" to 9999999,
            ),
            detail,
        )
    }

    fun emergency(snapshot: DataRepository.EmergencyEventSnapshot): String {
        val location = requireNotNull(snapshot.location?.takeIf {
            it.lat.isFinite() && it.lat in -90.0..90.0 &&
                it.lon.isFinite() && it.lon in -180.0..180.0
        }) { "Positional emergency requires a valid fix; send location-unavailable chat instead" }
        val point = listOf(
                "lat" to location.lat, "lon" to location.lon,
                "hae" to (location.hae?.takeIf { it.isFinite() } ?: 0.0),
                "ce" to normalizedError(location.ce), "le" to normalizedError(location.le),
        )
        val physio = snapshot.physio?.data
        val standardType = StandardEmergencyType.fromDisplayName(snapshot.description)
        val alertPhysiology = CotXml.element(
            "physiology",
            listOf(
                "alertUid" to snapshot.uid,
                "alertState" to snapshot.alertState.name,
                "alertCategory" to snapshot.category,
                "alertPriority" to snapshot.priority,
                "alertDescription" to snapshot.description,
            ),
            CotXml.element("device", content =
                CotXml.element("model", content = "WEAROS") +
                        CotXml.element("uid", content = snapshot.system.deviceId) +
                        CotXml.element("hr", content = rectifiedInt(physio?.heartRateBpm)) +
                        CotXml.element("skt", content = rectifiedNumber(physio?.skinTempF, "%.1f", negIsInvalid = false, zeroIsInvalid = false)) +
                        CotXml.element("exert", content = rectifiedNumber(physio?.exertion, "%.2f", zeroIsInvalid = false))
            )
        )
        val locationDetail = CotXml.element("weartak_location", listOf("timeMillis" to location.timeMillis))
        val detail = locationDetail + if (snapshot.alertState == DataRepository.AlertState.CANCEL) {
            alertPhysiology + CotXml.element("emergency", listOf("cancel" to "true"), snapshot.system.callsign)
        } else {
            alertPhysiology + CotXml.element("link", listOf("uid" to snapshot.system.deviceId, "type" to "a-f-G-U-C", "relation" to "p-p")) +
                    CotXml.element("contact", listOf("callsign" to "${snapshot.system.callsign}&#10;${snapshot.description}")) +
                    CotXml.element(
                        "emergency",
                        listOf("type" to (standardType?.cotDescription ?: snapshot.description)),
                        snapshot.system.callsign
                    ) +
                    CotXml.element("usericon", listOf("iconsetpath" to "911 Alert")) +
                    CotXml.element("color", listOf("argb" to "-1"))
        }
        val type = when {
            snapshot.alertState == DataRepository.AlertState.CANCEL -> "b-a-o-can"
            else -> standardType?.cotType ?: "b-a-o"
        }
        return CotXml.event(listOf(
            "version" to "2.0", "uid" to snapshot.uid, "type" to type, "time" to snapshot.time.startCot,
            "start" to snapshot.time.startCot, "stale" to snapshot.time.staleCot, "how" to "h-e", "access" to "Undefined",
        ), point, detail)
    }

    fun marker(snapshot: DataRepository.MarkerSnapshot, title: String, iconSetPath: String): String {
        val location = snapshot.loc
        val point = listOf(
            "lat" to location?.lat, "lon" to location?.lon, "hae" to location?.hae,
            "ce" to normalizedError(location?.ce), "le" to normalizedError(location?.le),
        )
        val detail = CotXml.element("status", listOf("readiness" to "true", "battery" to snapshot.system.batteryPercent)) +
                CotXml.element("precisionlocation", listOf("altsrc" to "SRTM1")) +
                CotXml.element("link", listOf(
                    "uid" to snapshot.system.deviceId, "production_time" to snapshot.time.startCot, "type" to "a-f-G-U-C",
                    "parent_callsign" to snapshot.system.callsign, "relation" to "p-p",
                )) + CotXml.element("color", listOf("argb" to "-1")) +
                CotXml.element("usericon", listOf("iconsetpath" to iconSetPath)) +
                CotXml.element("remarks", content = snapshot.remark) + CotXml.element("contact", listOf("callsign" to title))
        return CotXml.event(listOf(
            "version" to "2.0", "uid" to snapshot.uid, "type" to snapshot.markerType.type,
            "time" to snapshot.time.startCot, "start" to snapshot.time.startCot, "stale" to snapshot.time.staleCot,
            "how" to "h-g-i-g-o", "access" to "Undefined",
        ), point, detail)
    }

    fun ownedPointDeletion(
        targetUid: String,
        location: DataRepository.LocationSnapshot?,
        nowMillis: Long = System.currentTimeMillis(),
        eventUid: String = UUID.randomUUID().toString(),
    ): String {
        require(targetUid.isNotBlank() && targetUid.length <= 512 && targetUid.none(Char::isISOControl))
        require(eventUid.isNotBlank() && eventUid != targetUid)
        val time = CotEventTimeFactory.at(nowMillis, staleOffset = CotTimeOffset(days = 1))
        val point = location?.takeIf {
            it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0
        }
        return CotXml.event(
            listOf("version" to "2.0", "uid" to CotXml.escape(eventUid), "type" to "t-x-d-d",
                "time" to time.timeCot, "start" to time.startCot, "stale" to time.staleCot, "how" to "h-g-i-g-o"),
            listOf("lat" to (point?.lat ?: 0.0), "lon" to (point?.lon ?: 0.0),
                "hae" to (point?.hae?.takeIf { it.isFinite() } ?: 0.0), "ce" to 9999999, "le" to 9999999),
            CotXml.element("link", listOf("uid" to CotXml.escape(targetUid),
                "relation" to "none", "type" to "none")) + CotXml.element("__forcedelete"),
        )
    }

    fun chat(
        snapshot: DataRepository.ChatEventSnapshot,
        senderCallsign: String,
        senderUid: String,
        messageId: String = UUID.randomUUID().toString(),
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val cotFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val time = cotFormat.format(Date(nowMillis))
        // Match ATAK GeoChat (stale = time + 1 day); TAK Servers with ignoreStaleMessages drop already-stale chat.
        val stale = cotFormat.format(Date(nowMillis + CHAT_STALE_MILLIS))
        val location = snapshot.location
        val point = listOf(
            "lat" to (location?.lat ?: 0.0), "lon" to (location?.lon ?: 0.0), "hae" to (location?.hae ?: 0.0),
            "ce" to normalizedError(location?.ce, 999_999f), "le" to normalizedError(location?.le, 999_999f),
        )
        require(snapshot.roomUid.isNotBlank() && snapshot.roomTitle.isNotBlank()) { "Missing chat destination" }
        val namedGroup = snapshot.isGroup && snapshot.roomUid != "All Chat Rooms"
        val recipients = if (namedGroup) {
            snapshot.participantUids.filter { it.isNotBlank() && it != senderUid && it != snapshot.roomUid }.distinct()
                .also { require(it.isNotEmpty()) { "Group chat has no known recipients" } }
        } else listOf(snapshot.roomUid)
        val chatGroup = CotXml.element("chatgrp",
            listOf("uid0" to CotXml.escape(senderUid), "id" to CotXml.escape(snapshot.roomUid)) +
                recipients.mapIndexed { index, uid -> "uid${index + 1}" to CotXml.escape(uid) })
        val chat = CotXml.element("__chat", listOf(
            "parent" to "RootContactGroup", "groupOwner" to "false", "messageId" to CotXml.escape(messageId),
            "chatroom" to CotXml.escape(snapshot.roomTitle), "id" to CotXml.escape(snapshot.roomUid),
            "senderCallsign" to CotXml.escape(senderCallsign),
        ), chatGroup)
        val marti = if (snapshot.roomUid == "All Chat Rooms") "" else {
            CotXml.element("marti", content = if (namedGroup) {
                recipients.joinToString("") { CotXml.element("dest", listOf("uid" to CotXml.escape(it))) }
            } else if (snapshot.participantUids.isNotEmpty()) {
                CotXml.element("dest", listOf("uid" to CotXml.escape(snapshot.roomUid)))
            } else CotXml.element("dest", listOf("callsign" to CotXml.escape(snapshot.roomTitle))))
        }
        val detail = chat +
                CotXml.element("link", listOf("uid" to CotXml.escape(senderUid), "type" to "a-f-G-U-C", "relation" to "p-p")) +
                CotXml.element("remarks", listOf("source" to CotXml.escape(senderUid), "to" to CotXml.escape(snapshot.roomUid), "time" to time),
                    CotXml.escape(snapshot.message)) + marti
        return CotXml.event(listOf(
            "version" to "2.0", "uid" to CotXml.escape("GeoChat.$senderUid.${snapshot.roomUid}.$messageId"), "type" to "b-t-f",
            "time" to time, "start" to time, "stale" to stale, "how" to "h-g-i-g-o", "access" to "Undefined",
        ), point, detail)
    }

    private fun batdokDetail(physio: DataRepository.PhysioData, time: CotEventTime, heartRate: String, skinTemp: String): String {
        val celsius = String.format("%.1f", convertFarenheitToCelsius(physio.skinTempF)).removeSuffix(".0")
        return when {
            physio.heartRateBpm == -1 && physio.skinTempF == -1f -> ""
            physio.heartRateBpm == -1 -> "<_atmist_ age=\"${physio.ageYears}\" timeOfIncident=\"${time.startCot}\"><vitalSign index=\"0\" timestamp=\"${time.startCot}\">(Temp,$skinTemp (F) $celsius (C),${time.time})</vitalSign></_atmist_>"
            physio.skinTempF == -1f -> "<_atmist_ age=\"${physio.ageYears}\" timeOfIncident=\"${time.startCot}\"><vitalSign index=\"0\" timestamp=\"${time.startCot}\">(HR,$heartRate,${time.time})</vitalSign></_atmist_>"
            else -> "<_atmist_ age=\"${physio.ageYears}\" timeOfIncident=\"${time.startCot}\"><vitalSign index=\"0\" timestamp=\"${time.startCot}\">(HR,$heartRate,${time.time})</vitalSign><vitalSign index=\"1\" timestamp=\"${time.startCot}\">(Temp,$skinTemp (F) $celsius (C),${time.time})</vitalSign></_atmist_>"
        }
    }

    private fun rectifiedInt(value: Int?): String = if (value == null || value < 0) "N/A" else value.toString()

    private fun rectifiedNumber(value: Number?, format: String, negIsInvalid: Boolean = true, zeroIsInvalid: Boolean = true): String {
        if (value == null || negIsInvalid && value.toDouble() < 0 || zeroIsInvalid && value.toDouble() == 0.0) return "N/A"
        val formatted = String.format(format, value.toDouble())
        return when {
            format.endsWith("1f") && formatted.endsWith(".0") -> formatted.removeSuffix(".0")
            format.endsWith("2f") && formatted.endsWith(".00") -> formatted.removeSuffix(".00")
            else -> formatted
        }
    }

    private fun normalizedError(value: Float?, fallback: Float = UNKNOWN_LOCATION_ERROR_METERS): Float =
        value?.takeIf { it.isFinite() && it >= 0f } ?: fallback

    private const val UNKNOWN_LOCATION_ERROR_METERS = 9_999_999f
}
