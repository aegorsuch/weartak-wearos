package com.aitech.weartak_civ.cot

import android.os.Build
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.DataRepository
import java.util.Locale

object CotDetailBuilder {
    fun takv(): String = CotXml.element("takv", listOf(
        "os" to Build.VERSION.SDK_INT,
        "version" to CotXml.escape(BuildConfig.VERSION_NAME),
        "device" to CotXml.escape("${Build.MANUFACTURER} ${Build.MODEL}"),
        "platform" to "WearTAK",
    ))

    fun status(battery: Int?, readiness: Boolean = false): String = CotXml.element("status", buildList {
        if (readiness) add("readiness" to "true")
        battery?.takeIf { it in 0..100 }?.let { add("battery" to it) }
    })

    // Keep device identity even without vitals: CoT consumers use it to identify WearTAK.
    fun physiology(uid: String, physio: DataRepository.PhysioData?, battery: Int?): String {
        fun number(value: Number, format: String) =
            String.format(Locale.US, format, value.toDouble()).trimEnd('0').trimEnd('.')
        return "<physiology><device><model>WEAROS</model><uid>${CotXml.escape(uid)}</uid>" +
            buildString {
                physio?.heartRateBpm?.takeIf { it in 20..250 }?.let { append("<hr>$it</hr>") }
                physio?.skinTempF?.takeIf { it.isFinite() && it in 59f..113f }?.let {
                    append("<skt>${number(it, "%.1f")}</skt>")
                }
                physio?.exertion?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let {
                    append("<exert>${number(it, "%.2f")}</exert>")
                }
                battery?.takeIf { it in 0..100 }?.let { append("<bat>$it</bat>") }
            } + "</device></physiology>"
    }

    fun dittoTrack(
        monitoredPhysio: DataRepository.PhysioData?,
        battery: Int,
        callsign: String,
        role: String,
        team: String,
        uid: String,
        course: Float,
        speed: Float,
        altitudeSource: String = "GPS",
        geopointSource: String = "GPS",
        dittoA: String,
        dittoIp: String = "0.0.0.0",
        dittoVersion: String,
        dittoDeviceName: String,
        endpoint: String = "*:-1:stcp",
        remarks: String = "",
    ): String = CotXml.element("detail", content =
        takv() +
            CotXml.element("contact", listOf("endpoint" to CotXml.escape(endpoint), "callsign" to CotXml.escape(callsign))) +
            CotXml.element("uid", listOf("Droid" to CotXml.escape(uid))) +
            CotXml.element("precisionlocation", listOf(
                "altsrc" to CotXml.escape(altitudeSource), "geopointsrc" to CotXml.escape(geopointSource),
            )) +
            CotXml.element("__group", listOf("role" to CotXml.escape(role), "name" to CotXml.escape(team))) +
            status(battery) +
            "<remarks>${CotXml.escape(remarks)}</remarks>" +
            physiology(uid, monitoredPhysio, battery) +
            CotXml.element("track", listOf("course" to course, "speed" to speed)) +
            CotXml.element("ditto", listOf(
                "a" to CotXml.escape(dittoA), "ip" to CotXml.escape(dittoIp),
                "version" to CotXml.escape(dittoVersion), "deviceName" to CotXml.escape(dittoDeviceName),
            )),
    )
}
