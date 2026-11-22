package com.aitech.weartak_civ.service

import android.location.Location
import com.aitech.weartak_civ.cot.CotDetailBuilder
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.CotXml
import com.aitech.weartak_civ.cot.SelfPliStalePolicy
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.integration.plugin.HandlerExertion
import com.aitech.weartak_civ.presentation.MainActivity
import com.weartak.plugin.sdk.dittoruntime.DittoRuntimeValidator
import com.weartak.plugin.sdk.dittoruntime.model.DittoCotParcel
import java.time.Instant

/** Host-only capture; eligibility and generation must be rechecked before publication. */
internal data class DittoTrackSnapshot(
    val eventTime: Long,
    val staleTime: Long,
    val location: DataRepository.LocationSnapshot,
    val uid: String,
    val callsign: String,
    val role: String,
    val team: String,
    val battery: Int,
    val remarks: String,
    val monitoredPhysio: DataRepository.PhysioData?,
    val moppGeneration: Long?,
    val intervalSeconds: Int,
) {
    companion object {
        /** Callers must enforce publication eligibility before capture and recheck it before sending. */
        suspend fun capture(
            repository: DataRepository,
            freshLocation: Location?,
            fallbackUid: String,
            readExertion: suspend () -> Double?,
        ): DittoTrackSnapshot? {
            val freshSnapshot = freshLocation?.let { repository.snapshotOutboundAnnouncement(it) }
            val timeMs = freshLocation?.time ?: System.currentTimeMillis()
            val interval = CotDispatchService.actualReportingIntervalSecs.coerceAtLeast(1)
            val staleOffset = SelfPliStalePolicy.offsetMillis(
                companionBonded = repository.hasCompanionEverBeenReady(),
                effectiveIntervalSeconds = interval,
                standaloneOffsetMillis = (2L * interval + 15) * 1_000L,
            )
            val location = if (freshSnapshot != null) freshSnapshot.loc else repository.snapshotPliLocation()
            if (location == null) return null
            val pli = HandlerExertion.pli(freshSnapshot ?: repository.snapshotPli(), readExertion())
            val battery = freshSnapshot?.systemData?.batteryPercent ?: repository.latestBatteryPercentage.value ?: -1
            val callsign = freshSnapshot?.systemData?.callsign ?: MainActivity.callsign
            val role = freshSnapshot?.systemData?.role ?: MainActivity.role
            val team = freshSnapshot?.systemData?.team ?: MainActivity.teamValue
            val uid = freshSnapshot?.systemData?.deviceId ?: fallbackUid
            return DittoTrackSnapshot(
                eventTime = timeMs, staleTime = timeMs + staleOffset, location = location,
                uid = uid, callsign = callsign, role = role, team = team, battery = battery,
                remarks = pli.remarks, monitoredPhysio = pli.monitoredPhysio,
                moppGeneration = pli.moppGeneration, intervalSeconds = interval,
            )
        }
    }

    fun detail(metadata: CotDetailBuilder.DittoMetadata?): String = CotDetailBuilder.dittoTrack(
        monitoredPhysio = monitoredPhysio,
        battery = battery,
        callsign = callsign,
        role = role,
        team = team,
        uid = uid,
        course = location.course ?: 0.0F,
        speed = location.speed ?: 0.0F,
        altitudeSource = location.coordinateSource?.uppercase() ?: "UNKNOWN",
        geopointSource = location.coordinateSource?.uppercase() ?: "UNKNOWN",
        endpoint = "*:-1:stcp",
        dittoA = metadata?.peerId,
        dittoVersion = metadata?.version,
        dittoDeviceName = metadata?.deviceName,
        remarks = remarks,
    )

    /** Private PLI profile; never forward as CoT or send to an unverified runtime. */
    fun toPluginPli(): DittoCotParcel {
        require(uid.isNotBlank() && callsign.isNotBlank() &&
            uid.none(Char::isISOControl) && callsign.none(Char::isISOControl))
        require(eventTime > 0 && staleTime >= eventTime && location.timeMillis > 0)
        require(location.lat.isFinite() && location.lat in -90.0..90.0 &&
            location.lon.isFinite() && location.lon in -180.0..180.0)
        val hae = location.hae ?: 0.0
        val ce = location.ce?.takeUnless { it.isNaN() } ?: 9999999.0f
        val le = location.le?.takeUnless { it.isNaN() } ?: 9999999.0f
        require(hae.isFinite() && ce.isFinite() && le.isFinite() &&
            (location.speed ?: 0.0f).isFinite() && (location.course ?: 0.0f).isFinite())
        val detail = detail(null).replace(
            "</detail>",
            CotXml.element("weartak_location", listOf("timeMillis" to location.timeMillis)) + "</detail>",
        )
        val xml = CotXml.DECLARATION + CotXml.element(
            "event",
            listOf(
                "version" to "2.0", "uid" to CotXml.escape(uid), "type" to "a-f-G-U-C",
                "time" to Instant.ofEpochMilli(eventTime), "start" to Instant.ofEpochMilli(eventTime),
                "stale" to Instant.ofEpochMilli(staleTime), "how" to "m-g", "access" to "Undefined",
            ),
            CotXml.element("point", listOf(
                "lat" to location.lat, "lon" to location.lon, "hae" to hae,
                "ce" to ce.toDouble(), "le" to le.toDouble(),
            )) + detail,
        )
        val parcel = DittoCotParcel(xml)
        require(DittoRuntimeValidator.isValidCot(parcel)) { "Invalid or oversized plugin PLI" }
        require(CotMessageParser.parseDocumentOrNull(xml) != null) { "Malformed plugin PLI" }
        return parcel
    }
}
