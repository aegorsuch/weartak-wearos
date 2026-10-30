/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.format.DateFormat
import android.util.Log
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.cot.ChatMessage
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.cot.ChatRoomOrdering
import com.aitech.weartak_civ.cot.ChatDestinations
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.ChatInboxMerge
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

class ChatService : Service() {
    private lateinit var dataRepository: DataRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatRoomMap = LinkedHashMap<String, ChatRoom>() // in memory "inbox" roomId → ChatRoom

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val TAG = "WearTAK-${ChatService::class.java.simpleName}"
        private const val CHAT_SERVICE_NOTIFICATION_ID = 101
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        @Volatile private var _instance: ChatService? = null
        fun getInstance(): ChatService? = _instance
        fun markRoomReadStatic(id: String) { _instance?.markRoomRead(id) }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ChatService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            CHAT_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        _instance = this
        EventBus.getDefault().register(this)
        dataRepository = MainActivity.dataRepository
        purgeSensitiveEnrollments()
        com.aitech.weartak_civ.command.SensitiveEnrollment.purgeNotifications(this)
        openConversation(ChatDestinations.allRooms(getString(R.string.chat_all_rooms)))

        startSendChatReplyCollector()
        startAutomatedChatCollector()
    }

    private fun startSendChatReplyCollector() {
        serviceScope.launch {
            dataRepository.chatRequestFromUI.collect { req ->
                Log.i(TAG, "Received outgoing chat request")
                handleOutgoingChatRequest(req, seedRoomIfMissing = false)
            }
        }
    }

    private fun startAutomatedChatCollector() {
        serviceScope.launch {
            dataRepository.automatedChatRequest.collect { req ->
                Log.i(TAG, "Received automated chat request")
                handleOutgoingChatRequest(req, seedRoomIfMissing = true)
            }
        }
    }

    private suspend fun handleOutgoingChatRequest(
        req: DataRepository.SendChatRequest,
        seedRoomIfMissing: Boolean
    ) {
        val uid = req.dstUid
        val title = req.dstChatroom
        val message = req.message
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(message)) {
            purgeSensitiveEnrollments()
            return
        }
        val timeMillis = req.timeMillis
        if (uid.isBlank() || title.isBlank() || message.isBlank()) return

        synchronized(chatRoomMap) {
            val existingChatRoom = chatRoomMap[uid]
            val room = when {
                existingChatRoom != null -> existingChatRoom
                seedRoomIfMissing -> ChatRoom(
                    uid = uid,
                    title = title,
                    messageList = emptyList(),
                    newMessagesPresent = false,
                    timeLastOpened = timeMillis,
                    timeLastClosed = timeMillis
                )
                else -> null
            } ?: return

            val mergedMessages = room.messageList + ChatMessage(message, MainActivity.device_uid, MainActivity.callsign, timeMillis)
            val mergedRoom = room.copy(
                title = title,
                messageList = mergedMessages,
                newMessagesPresent = false,
                timeLastClosed = timeMillis
            )
            chatRoomMap[uid] = mergedRoom
            val sortedInbox = getInbox()
            publishInboxToRepo()
            logChatRooms(sortedInbox)
        }
        dataRepository.sendChatMessage(uid, title, message, timeMillis)
    }

    fun resolveChatRoom(uid: String, cs: String): ChatRoom = synchronized(chatRoomMap) {
        openConversation(ChatDestinations.direct(uid, cs, MainActivity.device_uid))
    }

    fun openConversation(room: ChatRoom): ChatRoom = synchronized(chatRoomMap) {
        require(room.uid.isNotBlank() && room.title.isNotBlank()) { "Invalid conversation identity" }
        val existing = chatRoomMap[room.uid]
        require(existing == null || existing.isGroup == room.isGroup) { "Conversation type does not match selected recipient" }
        val resolved = existing?.copy(
            participantUids = if (room.isGroup) existing.participantUids else room.participantUids
        ) ?: room
        chatRoomMap[room.uid] = resolved
        publishInboxToRepo()
        resolved
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ChatService onDestroy()")
        isRunning.set(false)
        EventBus.getDefault().unregister(this)
        _instance = null
        stopForegroundServiceCompat()
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ChatService onStartCommand()")
        return START_NOT_STICKY
    }

    /*
    private fun startForegroundService() {
        Log.d(TAG, "Starting $TAG as a ForegroundService")
        val channelId = createNotificationChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("$TAG running")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(CHAT_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set $TAG as ForegroundService", e)
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "Chat_Service"
        val channelName = "$TAG Background Channel"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
            lightColor = Color.GRAY
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)
        return channelId
    }
    */

    // update the inbox
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onTakChat(event: TakChatEvent) = synchronized(chatRoomMap) {
        // Direct TAK ingestion supplies parsed rooms; legacy sources still supply raw XML only.
        val incomingRooms = event.parsedRooms ?: CotMessageParser.parseChatRooms(
            event.rawXml,
            myUid = MainActivity.device_uid,
            myCallsign = MainActivity.callsign,
            sourceServerId = event.sourceServerId,
            receivedAt = event.receivedAt,
        )
        Log.v(TAG, "Received TakChatEvent")

        // 2) Merge into in-memory inbox
        incomingRooms.forEach { rawRoom ->
            val incoming = rawRoom.copy(messageList = rawRoom.messageList.filterNot {
                com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(it.message)
            })
            if (incoming.messageList.isEmpty()) return@forEach
            chatRoomMap[incoming.uid] = ChatInboxMerge.merge(chatRoomMap[incoming.uid], incoming)
        }

        // 3) Sort & publish once
        val sortedInbox = getInbox()

        // 4) Notify the data layer (updates per‐room booleans correctly)
        publishInboxToRepo()

        // 5) (Optional) log for debugging
        logChatRooms(sortedInbox)
    }

    private fun publishInboxToRepo() {
        val list = getInbox()
        dataRepository.setChatRooms(list)

        CoroutineScope(Dispatchers.Default).launch {
            dataRepository.refreshUnreadCount()
        }
    }

    fun purgeSensitiveEnrollments() = synchronized(chatRoomMap) {
        chatRoomMap.replaceAll { _, room ->
            val messages = room.messageList.filterNot {
                com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(it.message)
            }
            room.copy(messageList = messages,
                newMessagesPresent = messages.any { it.timeReceived > room.timeLastClosed })
        }
        publishInboxToRepo()
    }

    /* Call publishInboxToRepo() wherever you previously did
       dataRepository.setChatRooms(…) – typically in onTakChat(). */

    private fun logChatRooms(rooms: List<ChatRoom>) {
        Log.d(TAG, "===== Chat Rooms (${rooms.size}) =====")
        rooms.forEachIndexed { roomIdx, room ->
            // Header for this room
            Log.d(TAG, "Room ${roomIdx + 1}/${rooms.size}:")
            Log.d(TAG, "  uid:    ${room.uid}")
            Log.d(TAG, "  title:  ${room.title}")
            Log.d(TAG, "  unread: ${room.newMessagesPresent}")
            Log.d(TAG, "  messages (${room.messageList.size}):")

            // Each message in the room
            room.messageList.forEachIndexed { msgIdx, msg ->
                val timeStr = DateFormat.format("yyyy-MM-dd HH:mm:ss", msg.timeReceived)
                Log.d(TAG, "    ${msgIdx + 1}. [$timeStr] ${msg.callsign} → ${msg.uid}")
                Log.d(TAG, "        message length=${msg.message.length}")
            }
            Log.d(TAG, "----------------------------------")
        }
        Log.d(TAG, "===== End of Chat Rooms =====")
    }

    /**
     * Returns all chat rooms sorted by latest message activity, including local sends.
     */
    fun getInbox(): List<ChatRoom> = synchronized(chatRoomMap) {
        ChatRoomOrdering.byLatestActivity(chatRoomMap.values)
    }

    /**
     * Returns the total number of unread chat messages across all rooms.
     * A message is “unread” if its timeReceived is later than the room’s timeLastClosed.
     */
    fun getUnreadMsgsCount(): Int = synchronized(chatRoomMap) {
        chatRoomMap.values.sumOf { room ->
            room.messageList.count { msg ->
                msg.timeReceived > room.timeLastClosed
            }
        }
    }

    /**
     * Permanently removes a chat room (and all its messages) from the inbox.
     *
     * @param roomId the UID of the room to delete
     * @return true if the room existed and was removed, false otherwise
     */
    fun deleteChatRoom(roomId: String): Boolean = synchronized(chatRoomMap) {
        val existed = chatRoomMap.remove(roomId) != null
        if (existed) {
            Log.d(TAG, "Deleted chat room: $roomId")
            // TODO: notify your UI or repository that the inbox changed
        }
        existed
    }

    /**
     * Clears out every chat room and message from memory.
     */
    fun clearAllChatRooms() = synchronized(chatRoomMap) {
        chatRoomMap.clear()
        Log.d(TAG, "Cleared all chat rooms")
        // TODO: notify UI / repo of full wipe
    }

    /**
     * Call this when the user opens a room to mark everything up to now as “read.”
     */
    fun markRoomRead(roomId: String) = synchronized(chatRoomMap) {
        chatRoomMap[roomId]?.let { room ->
            room.timeLastClosed = System.currentTimeMillis()
            room.newMessagesPresent = false
        }
    }
}
