package com.aitech.weartak_civ.cot

data class ChatMessage(
    val message: String,
    val uid: String,
    val callsign: String,
    val timeReceived: Long,
    val sourceServerId: String? = null,
    val messageId: String? = null,
    val messageTime: Long = timeReceived,
)

data class ChatRoom(
    val uid: String,
    val title: String,
    val messageList: List<ChatMessage>,
    var newMessagesPresent: Boolean,
    val timeLastOpened: Long,
    var timeLastClosed: Long,
    val isGroup: Boolean = false,
    val participantUids: List<String> = emptyList(),
)

fun findChatRoomFromUserUid(userUid: String, chatRooms: List<ChatRoom>): Int =
    chatRooms.indexOfFirst { it.uid == userUid }

data class CotMarkerEvent(
    val version: String,
    val uid: String,
    val type: String,
    val how: String,
    val time: Long,
    val start: Long,
    val stale: Long,
    val access: String?,
    val point: CotPoint,
    val detail: CotMarkerDetail,
)

data class CotEmergencyEvent(
    val uid: String,
    val type: String,
    val time: Long,
    val start: Long,
    val stale: Long,
    val point: CotPoint?,
    val senderUid: String?,
    val senderCallsign: String?,
    val category: String?,
    val description: String?,
    val cancelled: Boolean,
) {
    fun isFrom(deviceUid: String): Boolean =
        uid == deviceUid || senderUid == deviceUid || uid == "$deviceUid-9-1-1"

    val hasLocation: Boolean
        get() = point?.let {
            it.lat.isFinite() && it.lon.isFinite() &&
                it.lat in -90.0..90.0 && it.lon in -180.0..180.0 &&
                !(it.lat == 0.0 && it.lon == 0.0 && it.ce >= 1_000_000.0)
        } == true
}

data class CotUserPositionEvent(
    val version: String,
    val uid: String,
    val team: String,
    val type: String,
    val role: String,
    val how: String,
    val time: Long,
    val start: Long,
    val stale: Long,
    val access: String?,
    val point: CotPoint,
    val detail: CotMarkerDetail,
)

data class CotMarkerMapItem(val cotEvent: CotMarkerEvent) {
    val uid: String = cotEvent.uid
    val type: String = cotEvent.type
    val callsign: String? = cotEvent.detail.markerCallsign
    val point: CotPoint = cotEvent.point
    val creator: CotCreator? = cotEvent.detail.creator
    val link: CotLink? = cotEvent.detail.link
}

data class CotPoint(
    val lat: Double,
    val lon: Double,
    val hae: Double,
    val ce: Double,
    val le: Double,
)

data class CotMarkerDetail(
    val markerCallsign: String?,
    val link: CotLink?,
    val creator: CotCreator?,
    val usericon: String?,
    val remarks: String?,
    val contact: String?,
    val circleRadiusMeters: Double? = null,
    val isCamera: Boolean = false,
    val hasUrgentDetail: Boolean = false,
)

internal fun CotMarkerDetail.canCoalesceTrack(type: String, how: String, sourceId: String?): Boolean =
    !sourceId.isNullOrBlank() && !sourceId.startsWith("plugin:") &&
        (type.startsWith("a-") || type.startsWith("u-d-")) &&
        how.startsWith("m-", ignoreCase = true) && !hasUrgentDetail && remarks.isNullOrBlank() &&
        link?.uid.isNullOrBlank()

/**
 * Passive SA: another user's or a sensor's track position. Its projection may wait for a
 * viewer because the source-owned store always keeps the newest copy.
 */
internal fun CotMarkerDetail.isPassiveTrack(type: String, sourceId: String?): Boolean =
    sourceId?.startsWith("plugin:") != true && type.startsWith("a-") && !hasUrgentDetail

data class CotDeletion(val uid: String, val time: Long)

data class CotLink(
    var uid: String?,
    var productionTime: Long?,
    var type: String?,
    var parentCallsign: String?,
    var relation: String?,
)

data class CotCreator(
    var uid: String?,
    var callsign: String?,
    var time: Long?,
    var type: String?,
)

class CotDetail(elementName: String = "detail") {
    private var elementName: String = elementName
        set(value) {
            require(value.isNotBlank()) { "Element name cannot be blank" }
            field = value
        }
    private val attributes = LinkedHashMap<String, String>()
    private val children = mutableListOf<CotDetail>()
    private var innerText: String? = null

    fun elementName(): String = elementName

    fun setElementName(name: String): CotDetail = apply { elementName = name }

    fun setAttribute(name: String, value: String?): CotDetail = apply {
        if (name.isNotBlank() && value != null) attributes[name] = value
    }

    fun removeAttribute(name: String): String? = attributes.remove(name)

    fun clearAttributes(): CotDetail = apply { attributes.clear() }

    fun setInnerText(text: String?): CotDetail = apply {
        children.clear()
        innerText = text
    }

    fun addChild(child: CotDetail): CotDetail = apply {
        require(child.elementName().isNotBlank()) { "Child element must have a name" }
        innerText = null
        children.add(child)
    }

    fun getChildren(): List<CotDetail> = children.toList()

    fun toXmlString(): String = buildString { appendXml(this) }

    private fun appendXml(output: Appendable) {
        output.append('<').append(elementName)
        attributes.forEach { (name, value) ->
            output.append(' ').append(name).append("='").append(CotXml.escape(value)).append('\'')
        }
        val text = innerText
        when {
            text != null -> output.append('>').append(CotXml.escape(text)).append("</").append(elementName).append('>')
            children.isEmpty() -> output.append("/>")
            else -> {
                output.append('>')
                children.forEach { it.appendXml(output) }
                output.append("</").append(elementName).append('>')
            }
        }
    }
}
