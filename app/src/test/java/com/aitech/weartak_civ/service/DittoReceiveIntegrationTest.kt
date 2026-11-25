package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.net.Network
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.RouteFollow
import com.aitech.weartak_civ.TakMulticastEndpoints
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.WifiMulticastReceiver
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MapUserRolePreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.net.MulticastSocket
import java.net.DatagramPacket
import java.net.InetAddress
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoReceiveIntegrationTest {

    class ChatDelivery(private val service: ChatService, val events: MutableList<TakChatEvent>) {
        val outboundDitto = mutableListOf<DittoDocumentEvent>()
        @Subscribe fun chat(event: TakChatEvent) {
            events += event
            service.onTakChat(event)
        }
        @Subscribe fun outbound(event: DittoDocumentEvent) { outboundDitto += event }
        fun unreadCount(): Int = service.getUnreadMsgsCount()
    }

    private class Fixture(val scope: TestScope) {
        val app: Application = RuntimeEnvironment.getApplication()
        val repo: DataRepository
        val bh: BloodhoundRepository
        val chats = mutableListOf<TakChatEvent>()
        val time = System.currentTimeMillis() - 1000
        val delivery: ChatDelivery
        private var transportAdaptersPrepared = false
        private var bloodhoundServiceScope: kotlinx.coroutines.CoroutineScope? = null

        init {
            WearTakApplication.AppCtx.init(app)
            MainActivity.device_uid = "watch"
            MainActivity.versionNumber = "test"
            repo = DataRepository(app, projectionScope = scope.backgroundScope)
            MainActivity.mainActivity = mockk { every { applicationContext } returns app }
            MainActivity.dataRepository = repo
            val preferences = object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())
                private val mutex = Mutex()
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    mutex.withLock { transform(data.value).also { data.value = it } }
            }
            bh = BloodhoundRepository(app, preferences, projectionScope = scope.backgroundScope)
            val chatService = Robolectric.buildService(ChatService::class.java).get()
            ChatService::class.java.getDeclaredField("dataRepository").apply {
                isAccessible = true
                set(chatService, repo)
            }
            delivery = ChatDelivery(chatService, chats)
            EventBus.getDefault().register(delivery)
        }

        suspend fun emit(vararg docs: Map<String, Any?>) {
            for (doc in docs) {
                TAKConnectService.handleInboundCotWithResult(
                    DittoCotDocument.decode(doc).xml,
                    "plugin:com.weartak.plugin.ditto",
                )
            }
        }

        fun drain() {
            scope.advanceTimeBy(1_500)
            scope.runCurrent()
        }

        fun doc(uid: String = "point", type: String = "a-h-G", at: Long = time,
                detail: String = "<detail><contact callsign='Remote point'/></detail>") =
            mapOf<String, Any?>(
                "_id" to uid, "_v" to 2, "_r" to false, "a" to "remote-peer",
                "AuthorId" to "alice", "b" to at, "d" to uid,
                "g" to "2.0", "h" to 1.0, "i" to 50.0, "j" to 38.0, "k" to 1.0,
                "l" to -77.0, "n" to at, "o" to at + 600_000,
                "p" to "m-g", "q" to "Undefined", "r" to detail, "w" to type
            )

        fun chat(uid: String = "chat", recipient: String = "watch", sender: String = "alice",
                 text: String = "Hello") = doc(uid, "b-t-f", detail =
            """<detail><__chat parent='RootContactGroup' id='$recipient' chatroom='$recipient' senderCallsign='$sender'
                messageId='$uid'><chatgrp id='$recipient' uid0='$sender' uid1='$recipient'/></__chat>
                <remarks to='$recipient'>$text</remarks></detail>""")

        fun route(at: Long = time) = doc("route", "b-m-r", at, detail =
            "<detail><contact callsign='Patrol'/><link point='38,-77,50' callsign='Start'/>" +
                "<link point='39,-78,60' callsign='End'/></detail>")

        fun alarm(at: Long = time, cancelled: Boolean = false) = doc("alarm",
            if (cancelled) "b-a-o-can" else "b-a-o-tbl", at, detail =
                "<detail><link uid='alice' relation='p-p'/>" +
                    "<emergency cancel='$cancelled' type='Troops in Contact'>ALICE</emergency></detail>")

        fun prepareTransportAdapters() {
            MainActivity.mainActivity = mockk {
                every { applicationContext } returns app
            }
            MainActivity.dataRepository = repo
            MainActivity.Constants.callsign = "WATCH"
            mockkObject(TAKConnectService.Companion, recordPrivateCalls = true)
            every { TAKConnectService.Companion getProperty "bhRepo" } returns bh
            transportAdaptersPrepared = true
        }

        fun startUnreadCollector() {
            val service = Robolectric.buildService(BloodhoundService::class.java).get()
            val scope = kotlinx.coroutines.CoroutineScope(Job() + Dispatchers.Unconfined)
            bloodhoundServiceScope = scope
            BloodhoundService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
                set(service, scope)
            }
            BloodhoundService::class.java.getDeclaredField("repo\$delegate").apply {
                isAccessible = true
                set(service, lazyOf(repo))
            }
            BloodhoundService::class.java.getDeclaredField("bhRepo\$delegate").apply {
                isAccessible = true
                set(service, lazyOf(bh))
            }
            BloodhoundService::class.java.getDeclaredMethod("collectBloodEventFlow").apply {
                isAccessible = true
                invoke(service)
            }
        }

        suspend fun tcp(vararg documents: Map<String, Any?>) {
            val raw = documents.joinToString("\n", postfix = "\n") { DittoCotDocument.decode(it).xml }
            val ready = java.util.concurrent.CountDownLatch(1)
            val socket = mockk<SSLSocket>(relaxed = true) {
                every { inputStream } answers {
                    check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
                }
            }
            val detail = TAKConnectService.TakServerDetail(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", true, "Synthetic", "tak.invalid",
                isP12Cert = false, username = "", password = "", p12Cert = null, p12CertPassword = null)
            val attempt = TAKConnectService.ConnectionAttempt(0)
            detail.runtime.currentAttempt = attempt
            TAKConnectService.Companion.javaClass.getDeclaredMethod("startCotReader",
                TAKConnectService.TakServerDetail::class.java, SSLSocket::class.java,
                TAKConnectService.ConnectionAttempt::class.java).apply {
                isAccessible = true
                invoke(TAKConnectService.Companion, detail, socket, attempt)
            }
            val reader = requireNotNull(detail.runtime.readerJob)
            ready.countDown()
            withContext(Dispatchers.Default) { withTimeout(10_000) { reader.join() } }
        }

        suspend fun multicast(vararg documents: Map<String, Any?>) {
            val input = documents.map { DittoCotDocument.decode(it).xml.toByteArray(Charsets.UTF_8) }.iterator()
            val socket = mockk<MulticastSocket>(relaxed = true)
            every { socket.receive(any()) } answers {
                if (!input.hasNext()) throw kotlinx.coroutines.CancellationException("Synthetic packets consumed")
                firstArg<DatagramPacket>().apply {
                    val bytes = input.next()
                    bytes.copyInto(data)
                    length = bytes.size
                    address = InetAddress.getByName("192.0.2.1")
                    port = 6969
                }
            }
            val receiver = WifiMulticastReceiver(
                listOf(WifiMulticastReceiver.Subscription(TakMulticastEndpoints.directCot, socket))
            ) {}
            val service = Robolectric.buildService(TAKConnectService::class.java).get()
            try {
                withContext(Dispatchers.Default) { withTimeout(10_000) {
                    service.collectMulticastConfigurations(
                        flowOf(TAKConnectService.MulticastConfig(mockk<Network>(), true, "239.2.3.1", 6969)),
                        updatePublisher = { _, _ -> }, openReceiver = { receiver })
                } }
            } finally {
                TAKConnectService::class.java.getDeclaredField("scope").apply {
                    isAccessible = true
                    (get(service) as kotlinx.coroutines.CoroutineScope).coroutineContext[Job]?.cancel()
                }
            }
        }

        suspend fun close() {
            bloodhoundServiceScope?.coroutineContext?.get(Job)?.cancel()
            if (transportAdaptersPrepared) unmockkObject(TAKConnectService.Companion)
            EventBus.getDefault().unregister(delivery)
            bh.removeAllFromMenu()
        }
    }

    @Test fun currentPhonePliKeepsItsStandardRoleVisibleAfterLeoFilterIsSaved() = runTest {
        val f = Fixture(this)
        f.prepareTransportAdapters()
        try {
            val preferences = f.app.getSharedPreferences("map-role-ditto-test", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            MapUserRolePreferences(preferences).saveLeoRoles(setOf("TOC"))
            for (collection in listOf("track", "mapitem")) {
                f.emit(f.doc("phone-$collection", "a-f-G-U-C",
                    detail = "<detail><contact callsign='PHONE'/>" +
                        "<__group name='Dark Green' role='K9'/></detail>"))
            }
            f.drain()
            val reopened = MapUserRolePreferences(preferences)
            val users = f.repo.receivedUserList.value
            assertEquals(2, users.size)
            users.forEach { user ->
                assertEquals("PHONE", user.title)
                assertEquals("Dark Green", user.team)
                assertEquals("K9", user.role)
                assertNotNull(user.location)
                assertTrue(user.sourceServerId!!.startsWith("plugin:"))
                assertTrue(user.role in reopened.standardRoles() || user.role in reopened.leoRoles())
            }
            reopened.saveStandardRoles(reopened.standardRoles() - "K9")
            val filtered = MapUserRolePreferences(preferences)
            assertTrue(users.none { it.role in filtered.standardRoles() || it.role in filtered.leoRoles() })
            assertEquals(setOf("TOC"), filtered.leoRoles())
        } finally { f.close() }
    }

    @Test fun tcpMulticastAndPluginCotIngressExposeOneEntityPerUidAndOneNotificationPerRevision() = runTest {
        val f = Fixture(this)
        f.prepareTransportAdapters()
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) } returns Unit
        f.startUnreadCollector()
        val updates = java.util.concurrent.CopyOnWriteArrayList<BloodhoundRepository.BloodhoundUpdate>()
        val notifications = backgroundScope.launch(Dispatchers.Unconfined) {
            f.bh.bloodhoundEvent.filterNotNull().collect { updates += it }
        }
        try {
            val docs = arrayOf(f.doc("alice", "a-f-G-U-C"), f.doc(), f.route(), f.chat(), f.alarm())
            f.tcp(*docs)
            assertEquals(2, updates.size)
            verify(exactly = 2) { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) }
            f.multicast(*docs)
            f.emit(*docs)
            f.drain()
            verify(exactly = 2) { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) }
            assertEquals(1, f.repo.receivedUserList.value.size)
            assertEquals(1, f.repo.receivedMarkerList.value.size)
            assertEquals(1, f.bh.remoteAlerts.value.size)
            assertEquals(1, f.repo.chatRoomsFlow.value.single().messageList.size)
            assertEquals(1, f.delivery.unreadCount())
            assertEquals(2, f.repo.bloodhoundUnreadCount.value)
            assertEquals(1, withContext(Dispatchers.Default) {
                withTimeout(5000) { f.repo.takRoutes.first { it.size == 1 } }
            }.size)
            assertEquals(3, f.repo.takRouteCopies.size)
            assertEquals(2, updates.size)
            // Same text and timestamp are not message identity.
            f.multicast(f.chat("another-message"), f.doc("another-point"), f.alarm() + mapOf(
                "_id" to "another-alarm", "d" to "another-alarm"))
            assertEquals(2, f.repo.chatRoomsFlow.value.single().messageList.size)
            assertEquals(2, f.bh.remoteAlerts.value.size)
            assertEquals(2, f.repo.receivedMarkerList.value.size)
            verify(exactly = 4) { com.aitech.weartak_civ.Utils.vibrateWatch(any(), any(), any(), any()) }
        } finally {
            unmockkObject(com.aitech.weartak_civ.Utils)
            notifications.cancel()
            f.close()
        }
    }

    @Test fun crossTransportOlderCopiesCannotUndoNewerUpdatesOrEmergencyCancellation() = runTest {
        val f = Fixture(this)
        f.prepareTransportAdapters()
        val updates = java.util.concurrent.CopyOnWriteArrayList<BloodhoundRepository.BloodhoundUpdate>()
        val notifications = backgroundScope.launch(Dispatchers.Unconfined) {
            f.bh.bloodhoundEvent.filterNotNull().collect { updates += it }
        }
        try {
            val newer = f.time + 100
            f.tcp(f.doc("alice", "a-f-G-U-C", newer), f.doc(at = newer), f.route(newer), f.alarm(newer))
            f.multicast(f.doc("alice", "a-f-G-U-C"), f.doc(), f.route(), f.alarm())
            f.emit(f.doc("alice", "a-f-G-U-C"), f.doc(), f.route(), f.alarm())
            f.drain()
            assertEquals(newer, f.repo.receivedUserList.value.single().timeReceived)
            assertEquals(newer, f.repo.receivedMarkerList.value.single().timeReceived)
            assertEquals(newer, withContext(Dispatchers.Default) {
                withTimeout(5000) { f.repo.takRoutes.first { it.isNotEmpty() } }.single().eventTime
            })
            assertEquals(2, updates.size)
            f.multicast(f.alarm(newer + 100, cancelled = true))
            f.tcp(f.alarm(newer))
            f.emit(f.alarm(newer))
            f.drain()
            assertTrue(f.bh.remoteAlerts.value.isEmpty())
            f.emit(f.alarm(newer + 200))
            f.drain()
            assertEquals(newer + 200, f.bh.remoteAlerts.value.getValue("alarm").bhEvent.mapItem.cotEvent.time)
            assertEquals(3, updates.size)
        } finally { notifications.cancel(); f.close() }
    }

    @Test fun identicalSourceFallbackPreservesRouteNavigationAndDoesNotRepeatNotifications() = runTest {
        val f = Fixture(this)
        f.prepareTransportAdapters()
        val updates = java.util.concurrent.CopyOnWriteArrayList<BloodhoundRepository.BloodhoundUpdate>()
        val notifications = backgroundScope.launch(Dispatchers.Unconfined) {
            f.bh.bloodhoundEvent.filterNotNull().collect { updates += it }
        }
        try {
            f.tcp(f.route(), f.alarm(), f.doc())
            f.multicast(f.route(), f.alarm(), f.doc())
            val routes = withContext(Dispatchers.Default) {
                withTimeout(5000) { f.repo.takRoutes.first { it.size == 1 } }
            }
            assertTrue(f.bh.startRoute(routes.single(), RouteFollow.StartMode.BEGINNING, null))
            val sessionUid = f.bh.routeFollow.value!!.uid
            val before = updates.size
            f.repo.removeTakRoute(routes.single().serverId, "route")
            val fallback = withContext(Dispatchers.Default) { withTimeout(5000) {
                f.repo.takRoutes.first { it.singleOrNull()?.serverId != routes.single().serverId }
            } }
            assertEquals(1, fallback.size)
            f.bh.reconcileRoutes(fallback)
            assertEquals(sessionUid, f.bh.routeFollow.value!!.uid)
            assertEquals(sessionUid, f.bh.activeUid.value)
            f.bh.clearSource("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            assertEquals(1, f.bh.remoteAlerts.value.size)
            assertEquals(before, updates.size)
        } finally { notifications.cancel(); f.close() }
    }

}
