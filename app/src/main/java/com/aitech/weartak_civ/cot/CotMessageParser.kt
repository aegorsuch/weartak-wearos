package com.aitech.weartak_civ.cot

import java.io.StringReader
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Logging-only ownership clues; explicit channel stamps are not treated as authorization data. */
data class CotIngressDiagnostics(
    val uid: String?,
    val type: String?,
    val eventTime: Long?,
    val teamName: String?,
    val teamRole: String?,
    val flowTagNames: List<String>,
    val explicitChannelStamps: List<String>
)

object CotMessageParser {
    private val urgentDetail = Regex(
        "<(?:[\\w.-]+:)?(?:emergency|__emergency|alert|__chat|__chatreceipt|ackrequest)\\b" +
            "|\\b(?:emergency|alert)\\s*=",
        RegexOption.IGNORE_CASE
    )
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    /** Live remote PLI, not a dropped a-* point or a reflected watch position. */
    fun parseCompanionUserOrNull(
        rawXml: String,
        watchUid: String,
        phoneUid: String
    ): CotUserPositionEvent? {
        if (watchUid.isBlank() || phoneUid.isBlank() ||
            phoneUid.length > 512 || phoneUid.any(Char::isISOControl)
        ) return null
        val document = parseDocumentOrNull(rawXml) ?: return null
        val root = document.documentElement ?: return null
        if (root.tagName != "event") return null
        val details = root.getElementsByTagName("detail")
        if (details.length > 1) return null
        val detail = details.item(0) as? Element
        if (detail != null) {
            val identities = detail.getElementsByTagName("uid")
            for (index in 0 until identities.length) {
                if ((identities.item(index) as Element).getAttribute("Droid") in setOf(watchUid, phoneUid)) {
                    return null
                }
            }
            if (detail.getElementsByTagName("emergency").length > 0) return null
            // Match physiology and legacy biometrics device identity even if an upstream UID is rewritten.
            val devices = detail.getElementsByTagName("device")
            for (index in 0 until devices.length) {
                val device = devices.item(index) as Element
                if (device.getElementsByTagName("model").item(0)?.textContent == "WEAROS" ||
                    device.getElementsByTagName("uid").item(0)?.textContent in setOf(watchUid, phoneUid)
                ) return null
            }
        }
        val type = root.getAttribute("type")
        if (!type.startsWith("a-")) return null
        val event = parseUserPosition(rawXml, type) ?: return null
        if (event.uid != root.getAttribute("uid")) return null
        if (event.uid.isBlank() || event.uid.length > 512 || event.uid.any(Char::isISOControl) ||
            event.uid == watchUid || event.uid == phoneUid ||
            event.time <= 0 || event.start <= 0 || event.stale <= 0 ||
            !event.point.lat.isFinite() || event.point.lat !in -90.0..90.0 ||
            !event.point.lon.isFinite() || event.point.lon !in -180.0..180.0 ||
            !event.point.hae.isFinite() || !event.point.ce.isFinite() || !event.point.le.isFinite() ||
            event.detail.creator?.uid in setOf(watchUid, phoneUid)
        ) return null
        return event
    }

    fun parseUserPositionOrNull(rawXml: String): CotUserPositionEvent? {
        val type = eventAttribute(rawXml, "type") ?: return null
        if (!isUserPositionType(type)) return null
        return parseUserPosition(rawXml, type)
    }

    private fun parseUserPosition(rawXml: String, type: String): CotUserPositionEvent? {
        val uid = eventAttribute(rawXml, "uid") ?: return null
        val time = eventAttribute(rawXml, "time") ?: return null
        val start = eventAttribute(rawXml, "start") ?: time
        val stale = eventAttribute(rawXml, "stale") ?: time

        return CotUserPositionEvent(
            version = eventAttribute(rawXml, "version") ?: "",
            uid = uid,
            team = extractAttribute(rawXml, "__group", "name") ?: "",
            type = type,
            role = extractAttribute(rawXml, "__group", "role") ?: "",
            how = eventAttribute(rawXml, "how") ?: "",
            time = parseIsoToEpochMs(time),
            start = parseIsoToEpochMs(start),
            stale = parseIsoToEpochMs(stale),
            access = eventAttribute(rawXml, "access"),
            point = pointOf(rawXml) ?: return null,
            detail = detailOf(rawXml),
        )
    }

    fun parseMarkerOrNull(rawXml: String): CotMarkerEvent? {
        val type = eventAttribute(rawXml, "type") ?: return null
        if (!isMarkerType(type)) return null
        val uid = eventAttribute(rawXml, "uid")?.takeIf(String::isNotBlank) ?: return null
        val time = eventAttribute(rawXml, "time") ?: return null
        val start = eventAttribute(rawXml, "start") ?: time
        val stale = eventAttribute(rawXml, "stale") ?: time
        val timeMs = parseIsoToEpochMs(time)
        val startMs = parseIsoToEpochMs(start)
        val staleMs = parseIsoToEpochMs(stale)
        if (timeMs <= 0 || startMs <= 0 || staleMs <= 0) return null
        val point = pointOf(rawXml) ?: return null
        if (!point.lat.isFinite() || point.lat !in -90.0..90.0 ||
            !point.lon.isFinite() || point.lon !in -180.0..180.0 ||
            !point.hae.isFinite() || !point.ce.isFinite() || !point.le.isFinite()
        ) return null

        return CotMarkerEvent(
            version = eventAttribute(rawXml, "version") ?: "",
            uid = uid,
            type = type,
            how = eventAttribute(rawXml, "how") ?: "",
            time = timeMs,
            start = startMs,
            stale = staleMs,
            access = eventAttribute(rawXml, "access"),
            point = point,
            detail = detailOf(rawXml),
        )
    }

    fun parseEmergencyOrNull(rawXml: String): CotEmergencyEvent? {
        val type = eventAttribute(rawXml, "type") ?: return null
        if (!isEmergencyType(type)) return null
        // Emergency details contain sender text, not an assignment or a marker.
        val document = parseDocumentOrNull(rawXml) ?: return null
        val root = document.documentElement.takeIf { it.tagName == "event" } ?: return null
        fun Element.attribute(name: String) = getAttribute(name).takeIf { it.isNotBlank() }
        fun child(name: String) = root.getElementsByTagName(name).item(0) as? Element
        val uid = root.attribute("uid") ?: return null
        val time = root.attribute("time")?.let(::parseIsoToEpochMs)?.takeIf { it > 0 } ?: return null
        val start = root.attribute("start")?.let(::parseIsoToEpochMs) ?: time
        val stale = root.attribute("stale")?.let(::parseIsoToEpochMs) ?: return null
        if (start <= 0 || stale <= 0) return null
        val emergency = child("emergency")
        val standardType = StandardEmergencyType.fromCotType(type)
        val link = root.getElementsByTagName("link").let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .firstOrNull { it.attribute("relation") in listOf("p-p", "p-r") }
        }
        val senderText = emergency?.textContent?.trim()?.takeIf(String::isNotBlank)
        val physiology = child("physiology") ?: child("biometrics")
        val physiologyDeviceUid = (physiology?.getElementsByTagName("uid")?.item(0) as? Element)
            ?.textContent?.trim()?.takeIf(String::isNotBlank)
        val creator = child("creator")
        val point = child("point")?.let { p ->
            val lat = p.attribute("lat")?.toDoubleOrNull()
            val lon = p.attribute("lon")?.toDoubleOrNull()
            if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0
            ) return null
            CotPoint(
                lat, lon,
                p.attribute("hae")?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 9999999.0,
                p.attribute("ce")?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 9999999.0,
                p.attribute("le")?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 9999999.0
            )
        }
        return CotEmergencyEvent(
            uid, type, time, start, stale, point,
            // WearTAK cancellations carry the sender only in physiology (legacy biometrics)/device/uid.
            link?.attribute("uid") ?: creator?.attribute("uid") ?: physiologyDeviceUid,
            link?.attribute("parent_callsign") ?: creator?.attribute("callsign")
                ?: senderText ?: child("contact")?.attribute("callsign")?.substringBefore('\n'),
            standardType?.displayName ?: physiology?.attribute("alertCategory") ?: physiology?.attribute("alertType")
                ?: emergency?.attribute("type") ?: type,
            emergency?.attribute("type") ?: physiology?.attribute("alertDescription")
                ?: child("remarks")?.textContent?.trim()
                ?: child("contact")?.attribute("callsign")?.substringAfter('\n', "")?.takeIf(String::isNotBlank)
                ?: standardType?.displayName,
            type == "b-a-o-can" || emergency?.attribute("cancel")?.lowercase() in listOf("true", "1")
        )
    }

    fun isEmergencyType(type: String): Boolean =
        type == "b-a-o" || type.startsWith("b-a-o-") || StandardEmergencyType.fromCotType(type) != null

    fun isUserPositionType(type: String): Boolean =
        type == "a-f-G-U-C" || type.startsWith("a-f-G-U-C-") ||
            type == "a-f-G-E" || type.startsWith("a-f-G-E-")

    fun isMarkerType(type: String): Boolean =
        (CotAffiliation.isAtomType(type) && !isUserPositionType(type)) ||
            type in setOf("b-m-p-s-p-i", "b-m-p-s-m", "u-d-c-c", "b-i-v")

    internal fun isCamera(rawXml: String): Boolean {
        val root = parseDocumentOrNull(rawXml)?.documentElement ?: return false
        if (root.tagName != "event") return false
        if (root.getAttribute("type") == "b-i-v") return true
        val children = root.childNodes
        for (index in 0 until children.length) {
            val detail = children.item(index) as? Element ?: continue
            if (detail.tagName != "detail") continue
            val fields = detail.childNodes
            for (field in 0 until fields.length) {
                if ((fields.item(field) as? Element)?.tagName == "__video") return true
            }
        }
        return false
    }

    fun isGenericMapType(type: String): Boolean =
        isMarkerType(type) && type !in setOf("a-f-G", "a-h-G", "a-n-G", "a-u-G", "b-m-p-s-p-i",
            "a-f-A-M-F-Q", "a-u-A-M-F-Q", "a-h-A-M-F-Q")

    fun parseDeletionOrNull(rawXml: String): CotDeletion? {
        val root = parseDocumentOrNull(rawXml)?.documentElement ?: return null
        if (root.tagName != "event" || root.getAttribute("type") != "t-x-d-d") return null
        val time = parseIsoToEpochMs(root.getAttribute("time")).takeIf { it > 0 } ?: return null
        val links = root.getElementsByTagName("link")
        val target = (0 until links.length).mapNotNull { links.item(it) as? Element }
            .firstOrNull { it.getAttribute("relation") == "none" }?.getAttribute("uid")
            ?: root.getAttribute("uid")
        if (target.isBlank() || target.length > 512 || target.any(Char::isISOControl)) return null
        return CotDeletion(target, time)
    }

    fun eventTypeOrNull(rawXml: String): String? = eventAttribute(rawXml, "type")

    fun eventUidOrNull(rawXml: String): String? = eventAttribute(rawXml, "uid")

    fun eventTimeOrNull(rawXml: String): Long? =
        eventAttribute(rawXml, "time")
            ?.let(::parseIsoToEpochMs)
            ?.takeIf { it > 0L }

    fun ingressDiagnostics(rawXml: String): CotIngressDiagnostics = CotIngressDiagnostics(
        uid = eventUidOrNull(rawXml),
        type = eventTypeOrNull(rawXml),
        eventTime = eventTimeOrNull(rawXml),
        teamName = extractAttribute(rawXml, "__group", "name"),
        teamRole = extractAttribute(rawXml, "__group", "role"),
        flowTagNames = flowTagNames(rawXml),
        explicitChannelStamps = explicitChannelStamps(rawXml)
    )

    fun isChatMessage(rawXml: String): Boolean =
        rawXml.contains("<__chat") && rawXml.contains("<remarks")

    fun isValidGatewayChat(rawXml: String): Boolean {
        val document = parseDocumentOrNull(rawXml) ?: return false
        val root = document.documentElement.takeIf { it.tagName == "event" } ?: return false
        if (root.getAttribute("type") != "b-t-f" ||
            root.getAttribute("uid").isBlank() ||
            parseIsoToEpochMs(root.getAttribute("time")) <= 0L ||
            parseIsoToEpochMs(root.getAttribute("start").ifBlank { root.getAttribute("time") }) <= 0L ||
            parseIsoToEpochMs(root.getAttribute("stale")) <= 0L
        ) return false
        val chat = root.getElementsByTagName("__chat").item(0) as? Element ?: return false
        val group = chat.getElementsByTagName("chatgrp").item(0) as? Element ?: return false
        val sender = group.getAttribute("uid0")
        val room = chat.getAttribute("id")
        if (sender.isBlank() || room.isBlank() ||
            group.getAttribute("id").let { it.isNotBlank() && it != room } ||
            chat.getAttribute("senderCallsign").isBlank() ||
            chat.getAttribute("messageId").isBlank() ||
            chat.getAttribute("chatroom").isBlank()
        ) return false
        val recipients = (0 until group.attributes.length)
            .map { group.attributes.item(it) }
            .filter { it.nodeName.matches(Regex("uid[1-9][0-9]*")) }
            .map { it.nodeValue }.filter { it.isNotBlank() && it != sender }.toMutableSet()
        val marti = root.getElementsByTagName("marti").item(0) as? Element
        if (marti != null) {
            val destinations = marti.getElementsByTagName("dest")
            for (index in 0 until destinations.length) {
                val recipient = (destinations.item(index) as? Element)?.getAttribute("uid").orEmpty()
                if (recipient.isNotBlank() && recipient != sender) recipients += recipient
            }
        }
        val remarks = root.getElementsByTagName("remarks")
        if (remarks.length == 0 ||
            (remarks.item(0) as? Element)?.textContent?.isNotBlank() != true
        ) return false
        val broadcast = room == "All Chat Rooms" ||
            (remarks.item(0) as? Element)?.getAttribute("to") == "All Chat Rooms"
        val remarkTo = (remarks.item(0) as? Element)?.getAttribute("to").orEmpty()
        if (remarkTo.isNotBlank() && remarkTo != sender && remarkTo != "All Chat Rooms")
            recipients += remarkTo
        return broadcast || recipients.isNotEmpty()
    }

    fun isChatForRecipient(rawXml: String, myUid: String, myCallsign: String): Boolean =
        parseChatRooms(rawXml, myUid, myCallsign).isNotEmpty()

    fun parseChatRooms(
        rawXml: String,
        myUid: String,
        myCallsign: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis()
    ): List<ChatRoom> {
        val document = parseDocumentOrNull(rawXml) ?: return emptyList()
        if (document.documentElement.tagName != "event") return emptyList()

        val rooms = mutableListOf<ChatRoom>()
        val chatElements = document.getElementsByTagName("__chat")
        val remarkElements = document.getElementsByTagName("remarks")
        for (chatIndex in 0 until chatElements.length) {
            val chat = chatElements.item(chatIndex) as? Element ?: continue
            val group = chat.getElementsByTagName("chatgrp").item(0) as? Element
            val senderUid = group?.getAttribute("uid0")?.takeIf { it.isNotBlank() } ?: continue
            if (senderUid == myUid) continue
            val senderCallsign = chat.getAttribute("senderCallsign").takeIf { it.isNotBlank() } ?: continue
            val participants = (0 until group.attributes.length).map { group.attributes.item(it) }
                .filter { it.nodeName.matches(Regex("uid\\d+")) }
                .map { it.nodeValue }.filter { it.isNotBlank() }.distinct()
            val conversationUid = chat.getAttribute("id").ifBlank { group.getAttribute("id") }
            if (group.getAttribute("id").isNotBlank() && group.getAttribute("id") != conversationUid) continue
            val broadcast = conversationUid == ALL_CHAT_ROOMS
            val isGroup = conversationUid.isNotBlank() && conversationUid != myUid &&
                conversationUid != senderUid
            val title = chat.getAttribute("chatroom").takeIf { it.isNotBlank() }
            if (isGroup && !broadcast && title == null) continue
            val messageId = chat.getAttribute("messageId").takeIf { it.isNotBlank() }
            for (remarkIndex in 0 until remarkElements.length) {
                val remark = remarkElements.item(remarkIndex) as? Element ?: continue
                val recipient = remark.getAttribute("to")
                // ATAK group remarks usually have no "to"; chatgrp carries the member UIDs.
                val addressedToMe = recipient == myCallsign || recipient == myUid
                val legacyBroadcast = recipient == ALL_CHAT_ROOMS && conversationUid.isBlank()
                val accepted = when {
                    broadcast || legacyBroadcast -> true
                    isGroup -> myUid in participants
                    else -> addressedToMe || myUid in participants
                }
                val text = remark.textContent.trim()
                if (accepted && text.isNotBlank()) {
                    rooms += chatRoom(
                        senderUid,
                        senderCallsign,
                        text,
                        sourceServerId,
                        receivedAt,
                        messageId,
                        if (isGroup) conversationUid else if (legacyBroadcast) ALL_CHAT_ROOMS else senderUid,
                        if (isGroup) title ?: ALL_CHAT_ROOMS else if (legacyBroadcast) ALL_CHAT_ROOMS else senderCallsign,
                        isGroup || legacyBroadcast,
                        if (isGroup && !broadcast) participants else emptyList()
                    )
                }
            }
        }
        return rooms
    }

    private fun detailOf(rawXml: String) = CotMarkerDetail(
        markerCallsign = extractAttribute(rawXml, "contact", "callsign") ?: "",
        creator = creatorOf(rawXml),
        link = linkOf(rawXml),
        usericon = null,
        remarks = extractElementText(rawXml, "remarks"),
        contact = extractAttribute(rawXml, "contact", "endpoint")?.takeIf(String::isNotBlank),
        circleRadiusMeters = if (eventTypeOrNull(rawXml) == "u-d-c-c") circleRadius(rawXml) else null,
        isCamera = isCamera(rawXml),
        hasUrgentDetail = urgentDetail.containsMatchIn(rawXml),
    )

    private fun circleRadius(rawXml: String): Double? {
        val major = extractAttribute(rawXml, "ellipse", "major")?.toDoubleOrNull() ?: return null
        val minor = extractAttribute(rawXml, "ellipse", "minor")?.toDoubleOrNull() ?: return null
        return major.takeIf { it.isFinite() && minor.isFinite() && it > 0 && it <= 100_000 && it == minor }
    }

    private fun pointOf(rawXml: String): CotPoint? {
        val lat = extractAttribute(rawXml, "point", "lat")?.toDoubleOrNull() ?: return null
        val lon = extractAttribute(rawXml, "point", "lon")?.toDoubleOrNull() ?: return null
        val hae = extractAttribute(rawXml, "point", "hae")?.toDoubleOrNull() ?: return null
        val ce = extractAttribute(rawXml, "point", "ce")?.toDoubleOrNull() ?: return null
        val le = extractAttribute(rawXml, "point", "le")?.toDoubleOrNull() ?: return null
        return CotPoint(lat, lon, hae, ce, le)
    }

    private fun creatorOf(rawXml: String) = CotCreator(
        uid = extractAttribute(rawXml, "creator", "uid"),
        callsign = extractAttribute(rawXml, "creator", "callsign"),
        time = extractAttribute(rawXml, "creator", "time")?.let(::parseIsoToEpochMs),
        type = extractAttribute(rawXml, "creator", "type"),
    )

    private fun linkOf(rawXml: String) = CotLink(
        uid = extractAttribute(rawXml, "link", "uid"),
        productionTime = extractAttribute(rawXml, "link", "production_time")?.let(::parseIsoToEpochMs),
        type = extractAttribute(rawXml, "link", "type"),
        parentCallsign = extractAttribute(rawXml, "link", "parent_callsign"),
        relation = extractAttribute(rawXml, "link", "relation"),
    )

    private fun chatRoom(
        senderUid: String,
        senderCallsign: String,
        message: String,
        sourceServerId: String?,
        receivedAt: Long,
        messageId: String? = null,
        conversationUid: String = senderUid,
        conversationTitle: String = senderCallsign,
        isGroup: Boolean = false,
        participantUids: List<String> = emptyList()
    ) = ChatRoom(
        uid = conversationUid,
        title = conversationTitle,
        messageList = listOf(
            ChatMessage(
                message = message,
                uid = senderUid,
                callsign = senderCallsign,
                timeReceived = receivedAt,
                sourceServerId = sourceServerId,
                messageId = messageId
            )
        ),
        newMessagesPresent = true,
        timeLastOpened = 0L,
        timeLastClosed = 0L,
        isGroup = isGroup,
        participantUids = participantUids,
    )

    internal fun parseDocumentOrNull(rawXml: String) = try {
        val cleaned = rawXml.trimStart { it <= ' ' || it == '\uFEFF' }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            trySetAttribute(ACCESS_EXTERNAL_DTD, "")
            trySetAttribute(ACCESS_EXTERNAL_SCHEMA, "")
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
            setErrorHandler(org.xml.sax.helpers.DefaultHandler())
        }.parse(InputSource(StringReader(cleaned)))
    } catch (_: Exception) {
        null
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Some Android XML implementations do not expose every hardening feature.
        }
    }

    private fun DocumentBuilderFactory.trySetAttribute(name: String, value: String) {
        try {
            setAttribute(name, value)
        } catch (_: Exception) {
            // Some Android XML implementations do not expose every hardening attribute.
        }
    }

    private fun eventAttribute(xml: String, name: String): String? = extractAttribute(xml, "event", name)

    // Only private, compiled schema names become keys; never retain incoming XML or values.
    private val attributePatterns = ConcurrentHashMap<Pair<String, String>, Regex>()

    private fun extractAttribute(xml: String, elementName: String, attributeName: String): String? {
        val pattern = attributePatterns.computeIfAbsent(elementName to attributeName) { (element, attribute) ->
            """<${Regex.escape(element)}\b[^>]*?\b${Regex.escape(attribute)}\s*=\s*(['"])(.*?)\1"""
                .toRegex(RegexOption.DOT_MATCHES_ALL)
        }
        val value = pattern.find(xml)?.groupValues?.getOrNull(2) ?: return null
        if ('&' !in value) return value
        val element = parseDocumentOrNull(xml)?.getElementsByTagName(elementName)?.item(0) as? Element
        return element?.getAttribute(attributeName) ?: value
    }

    private fun extractElementText(xml: String, elementName: String): String? {
        val pattern = """<${Regex.escape(elementName)}\b[^>]*>(.*?)</${Regex.escape(elementName)}>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return pattern.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun flowTagNames(rawXml: String): List<String> {
        val flowTag = TAG_PATTERN.findAll(rawXml)
            .map(MatchResult::value)
            .firstOrNull { it.startsWith("<_flow-tags_", ignoreCase = true) }
            ?: return emptyList()
        return ATTRIBUTE_PATTERN.findAll(flowTag)
            .map { it.groupValues[1] }
            .filterNot { it.equals("_flow-tags_", ignoreCase = true) }
            .distinct()
            .toList()
    }

    private fun explicitChannelStamps(rawXml: String): List<String> =
        TAG_PATTERN.findAll(rawXml)
            .flatMap { tag -> CHANNEL_ATTRIBUTE_PATTERN.findAll(tag.value) }
            .map { match -> "${match.groupValues[1]}=${match.groupValues[3]}" }
            .distinct()
            .toList()

    private fun parseIsoToEpochMs(value: String): Long {
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            val withZulu = if (value.endsWith("Z", ignoreCase = true)) value else "${value}Z"
            try {
                return Instant.parse(withZulu).toEpochMilli()
            } catch (_: Exception) {
                return try {
                    Instant.parse(withZulu.substringBefore('.', withZulu)).toEpochMilli()
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }

    private const val ALL_CHAT_ROOMS = "All Chat Rooms"
    private val TAG_PATTERN = """<[^>]+>""".toRegex()
    private val ATTRIBUTE_PATTERN =
        """\b([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*(['"])(.*?)\2""".toRegex()
    private val CHANNEL_ATTRIBUTE_PATTERN =
        """\b(channel|channelId|channelUid|channelName|bitpos)\s*=\s*(['"])(.*?)\2"""
            .toRegex(RegexOption.IGNORE_CASE)
}
