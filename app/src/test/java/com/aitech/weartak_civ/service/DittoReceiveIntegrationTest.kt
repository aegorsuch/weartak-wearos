package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.cot.DittoPointLedger
import com.aitech.weartak_civ.cot.DittoPointIngress
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import io.mockk.spyk
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.net.Network
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.cot.DittoChatDocument
import com.aitech.weartak_civ.cot.DittoCotReceiver
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.command.ChatCommandParser
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.CompanionGatewaySession
import com.aitech.weartak_civ.data.RouteFollow
import com.aitech.weartak_civ.TakMulticastEndpoints
import com.aitech.weartak_civ.WifiMulticastReceiver
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.integration.plugin.PluginCotEgress
import com.aitech.weartak_civ.integration.plugin.PluginCotIngress
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MapUserRolePreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.ByteArrayInputStream
import java.net.MulticastSocket
import java.net.DatagramPacket
import java.net.InetAddress
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import live.ditto.DittoQueryResult
import live.ditto.DittoQueryResultItem
import live.ditto.DittoStore
import live.ditto.DittoStoreObserver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoReceiveIntegrationTest {
    private class CommitResultPreferences(
        private val delegate: SharedPreferences,
        private val fail: () -> Boolean
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun commit(): Boolean {
                    check(editor.commit())
                    return !fail()
                }
            }
        }
    }

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
        val logs = mutableListOf<String>()
        val store = mockk<DittoStore>()
        val callbacks = linkedMapOf<String, (DittoQueryResult, () -> Unit) -> Unit>()
        val pending = mutableListOf<CompletableDeferred<Unit>>()
        val handles = mutableListOf<DittoStoreObserver>()
        val results = mutableListOf<DittoQueryResult>()
        val journal = app.getSharedPreferences("ditto-test", Context.MODE_PRIVATE)
        val time = System.currentTimeMillis() - 1000
        var observers: DittoStoreObservers
        val delivery: ChatDelivery
        private var transportAdaptersPrepared = false
        private var bloodhoundServiceScope: kotlinx.coroutines.CoroutineScope? = null

        init {
            MainActivity.device_uid = "watch"
            MainActivity.versionNumber = "test"
            DittoService.localPeerKeyString = "local-peer"
            repo = DataRepository(app)
            MainActivity.mainActivity = mockk { every { applicationContext } returns app }
            MainActivity.dataRepository = repo
            val preferences = object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())
                private val mutex = Mutex()
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    mutex.withLock { transform(data.value).also { data.value = it } }
            }
            bh = BloodhoundRepository(app, preferences)
            val chatService = Robolectric.buildService(ChatService::class.java).get()
            ChatService::class.java.getDeclaredField("dataRepository").apply {
                isAccessible = true
                set(chatService, repo)
            }
            delivery = ChatDelivery(chatService, chats)
            EventBus.getDefault().register(delivery)
            every {
                store.registerObserver(any(), any(), any<(DittoQueryResult, () -> Unit) -> Unit>())
            } answers {
                callbacks[firstArg()] = thirdArg()
                mockk<DittoStoreObserver>(relaxed = true).also { handles += it }
            }
            observers = start()
        }

        fun start(preferences: SharedPreferences = journal, repository: DataRepository = repo,
                  uid: String = "watch", peer: String = "local-peer",
                  commands: (suspend (com.aitech.weartak_civ.command.ChatCommandRequest) -> Boolean)? = null) =
            DittoStoreObservers(store, scope, DittoCotReceiver(
            repository, bh, preferences, { uid }, { "WATCH" }, { peer },
            chatCommandHandler = commands,
            log = { message, _ -> logs += message }
        ))

        suspend fun drop(uid: String = "point") {
            repo.appendDroppedMarker(DataRepository.DroppedMarker(
                DataRepository.MarkerCotType.DROP, uid, DataRepository.MarkerType.HOSTILE,
                "Local point", "", time, null, true, true))
        }

        fun ownedDoc(uid: String = "point", at: Long = time) =
            doc(uid, at = at) + ("AuthorId" to "watch") + ("a" to "local-peer")

        fun contracted(raw: Map<String, Any?> = doc(), deleted: Boolean = false): Map<String, Any?> {
            val doc = DittoCotDocument.decode(raw)
            return raw + mapOf(
                "_r" to deleted,
                DittoPointLedger.METADATA to mapOf(
                    "version" to 1, "owner" to doc.author, "revision" to doc.fingerprint,
                    "deleted" to deleted, "deletedAt" to time + 500
                )
            )
        }

        fun publisher(writes: MutableList<Map<String, Any?>>, repository: DataRepository = repo): DittoService {
            val service = spyk(Robolectric.buildService(DittoService::class.java).get())
            DittoService::class.java.getDeclaredField("dataRepository").apply {
                isAccessible = true
                set(service, repository)
            }
            every { service.writeDocument(any(), any()) } answers {
                assertEquals("mapitem", firstArg<String>())
                writes += secondArg<Map<String, Any?>>()
            }
            return service
        }

        suspend fun clear(uid: String = "point", queueRetry: Boolean? = null) {
            MainActivity.Constants.bhRepo = bh
            val service = spyk(Robolectric.buildService(CotDispatchService::class.java).get())
            val host = mockk<WearTakApplication>(relaxed = true)
            every { host.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } returns false
            every { service.applicationContext } returns host
            val companion = mockk<CompanionAppRepository>(relaxed = true)
            every { companion.gateway } returns CompanionGatewaySession()
            val dispatcher = mockk<CotEndpointDispatcher>()
            coEvery { dispatcher.dispatch(any(), any(), any()) } returns false
            CotDispatchService::class.java.getDeclaredField("endpointDispatcher\$delegate").apply {
                isAccessible = true; set(service, lazyOf(dispatcher))
            }
            CotDispatchService::class.java.getDeclaredField("capRepo\$delegate").apply {
                isAccessible = true; set(service, lazyOf(companion))
            }
            CotDispatchService::class.java.getDeclaredField("dataRepository").apply {
                isAccessible = true
                set(service, repo)
            }
            val queue = java.util.concurrent.CopyOnWriteArrayList<DataRepository.DroppedMarker>()
            if (queueRetry != null) {
                queue += repo.droppedMarkerList.value.single { it.uid == uid }
                    .copy(cotType = DataRepository.MarkerCotType.CLEAR)
                CotDispatchService::class.java.getDeclaredField("pendingMarkerCotList").apply {
                    isAccessible = true
                    set(service, queue)
                }
            }
            try {
                suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                    if (queueRetry == null) {
                        CotDispatchService::class.java.declaredMethods.single { it.name == "handleClearMarkerUid" }
                            .apply { isAccessible = true }.invoke(service, uid, continuation)
                    } else {
                        CotDispatchService::class.java.declaredMethods.single { it.name == "sendPendingMarkerCots" }
                            .apply { isAccessible = true }.invoke(service, continuation)
                    }
                }
                if (queueRetry != null) assertEquals(queueRetry, queue.any { it.uid == uid })
            } finally {
                (CotDispatchService::class.java.getDeclaredField("serviceScope")
                    .apply { isAccessible = true }.get(service) as kotlinx.coroutines.CoroutineScope)
                    .coroutineContext[Job]?.cancel()
            }
        }

        fun emit(collection: String = "mapitem", vararg docs: Map<String, Any?>) {
            val result = mockk<DittoQueryResult>(relaxed = true)
            every { result.items } returns docs.map { doc ->
                mockk<DittoQueryResultItem> {
                    every { value } returns doc.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
                }
            }
            emitResult(collection, result)
        }

        fun emitResult(collection: String, result: DittoQueryResult) {
            results += result
            val completed = CompletableDeferred<Unit>()
            pending += completed
            callbacks.getValue("SELECT * FROM $collection")(result) { completed.complete(Unit) }
        }

        suspend fun drain() {
            withContext(Dispatchers.Default) { withTimeout(10_000) { pending.forEach { it.await() } } }
            pending.clear()
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

        fun nativeChat(uid: String = "chat", recipient: String = "watch", sender: String = "alice",
                       text: String = "Hello", members: String? = null): Map<String, Any?> {
            val raw = chat(uid, recipient, sender, text).let {
                if (members == null) it else it + ("r" to (it.getValue("r") as String)
                    .replace("uid1='$recipient'", members))
            }
            return DittoChatDocument.encode(DittoCotDocument.decode(raw).xml, "remote-peer")
        }

        fun sensitiveChat(uid: String = "enroll",
                          text: String = "#weartakcommand enrollserver password=private-secret") =
            nativeChat(uid).let { raw ->
                raw + mapOf("msg" to text, "r" to (raw["r"] as String).replace("Hello", text))
            }

        fun trackEvictions(onEvict: (String, String) -> Unit) {
            every { store.collection(any()) } answers {
                val name = firstArg<String>()
                mockk<live.ditto.DittoCollection> {
                    every { find(any()) } answers {
                        val query = firstArg<String>()
                        mockk<live.ditto.DittoPendingCursorOperation> {
                            every { evict() } answers {
                                onEvict(name, query)
                                emptyList()
                            }
                        }
                    }
                }
            }
        }

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
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", false, "Synthetic", "tak.invalid",
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
            observers.close()
            bloodhoundServiceScope?.coroutineContext?.get(Job)?.cancel()
            if (transportAdaptersPrepared) unmockkObject(TAKConnectService.Companion)
            EventBus.getDefault().unregister(delivery)
            results.forEach { verify(exactly = 1) { it.close() } }
            bh.removeAllFromMenu()
        }
    }

    @Test fun currentPhonePliKeepsItsStandardRoleVisibleAfterLeoFilterIsSaved() = runTest {
        val f = Fixture(this)
        try {
            val preferences = f.app.getSharedPreferences("map-role-ditto-test", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            MapUserRolePreferences(preferences).saveLeoRoles(setOf("TOC"))
            for (collection in listOf("track", "mapitem")) {
                f.emit(collection, f.doc("phone-$collection", "a-f-G-U-C",
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
                assertTrue(user.sourceServerId!!.startsWith("ditto:"))
                assertTrue(user.role in reopened.standardRoles() || user.role in reopened.leoRoles())
            }
            reopened.saveStandardRoles(reopened.standardRoles() - "K9")
            val filtered = MapUserRolePreferences(preferences)
            assertTrue(users.none { it.role in filtered.standardRoles() || it.role in filtered.leoRoles() })
            assertEquals(setOf("TOC"), filtered.leoRoles())
            assertTrue(f.logs.toString(), f.logs.none { it.contains("Rejected") })
        } finally { f.close() }
    }

    @Test fun initialSdkSnapshotsReachPliPointsRoutesChatsAndEmergencyRepositories() = runTest {
        val f = Fixture(this)
        try {
            f.emit("track", f.doc("alice", "a-f-G-U-C",
                detail = "<detail><contact callsign='ALICE'/><__group name='Cyan' role='Medic'/></detail>"))
            f.emit("mapitem", f.doc(), f.chat(), f.doc("route", "b-m-r", detail =
                "<detail><contact callsign='Patrol'/><link point='38,-77,50' callsign='Start'/>" +
                    "<link point='39,-78,60' callsign='End'/></detail>"))
            f.emit("alert", f.doc("alarm", "b-a-o-tbl", detail =
                "<detail><contact callsign='ALICE'/><link uid='alice' relation='p-p'/>" +
                    "<emergency type='Troops in Contact'>ALICE</emergency></detail>"))
            f.drain()
            assertEquals("ALICE", f.repo.receivedUserList.value.single().title)
            assertEquals("Remote point", f.repo.receivedMarkerList.value.single().title)
            assertTrue(f.bh.isUidValid("point"))
            val route = withTimeout(5000) { f.repo.takRoutes.first { it.isNotEmpty() } }.single()
            assertEquals("Patrol", route.name)
            assertEquals(listOf(38.0, 39.0), route.points.map { it.latitude })
            assertTrue(route.serverId.startsWith("ditto:"))
            assertEquals("Hello", f.chats.single().parsedRooms!!.single().messageList.single().message)
            assertEquals("Hello", f.repo.chatRoomsFlow.value.single().messageList.single().message)
            assertTrue(f.delivery.outboundDitto.isEmpty())
            assertTrue(f.bh.isUidValid("alarm"))
            assertTrue(f.logs.toString(), f.logs.none { it.contains("Rejected") })
        } finally { f.close() }
    }

    @Test fun nativeTacticalSenderIsNotEventUidAndMarkersRemainDistinct() = runTest {
        val f = Fixture(this)
        try {
            val uid = "ff1f3217-fcfd-488f-b0fa-e95a24d05da3"
            val native = (f.doc(uid) - "AuthorId") + mapOf(
                "d" to "ANDROID-2ded908bebdd29f7", "e" to "TABLET",
                "j" to 41.879815, "l" to -87.642469, "i" to 156.933,
                "h" to 19.4, "k" to 9999999.0, "f" to true
            )
            assertEquals(uid, DittoCotDocument.decode(native).uid)
            assertEquals("ANDROID-2ded908bebdd29f7", DittoCotDocument.decode(native).author)
            f.emit("mapitem", native, native + ("_id" to "second-marker"), f.doc("legacy"))
            f.emit("alert", (f.alarm() - "AuthorId") + ("d" to "alice"))
            f.drain()
            assertEquals(setOf(uid, "second-marker", "legacy"),
                f.repo.receivedMarkerList.value.map { it.uid }.toSet())
            assertEquals(1, f.bh.remoteAlerts.value.size)
            assertTrue(f.logs.toString(), f.logs.none { it.contains("Rejected") })
            f.emit("alert", (f.alarm(f.time + 1, cancelled = true) - "AuthorId") + ("d" to "alice"))
            f.drain()
            assertTrue(f.bh.remoteAlerts.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun nativeTacticalAuthorAndPeerEchoesAreIgnoredAndAuthorConflictsRejected() = runTest {
        val f = Fixture(this)
        try {
            val native = (f.doc("native") - "AuthorId") + ("d" to "alice")
            f.emit("mapitem",
                native + mapOf("_id" to "self-author", "d" to "watch"),
                native + mapOf("_id" to "self-peer", "a" to "local-peer"),
                native + mapOf("_id" to "conflicting-author", "AuthorId" to "bob"),
                native + mapOf("_id" to "missing-sender") - "d",
                native + mapOf("_id" to ""),
                native + ("AuthorId" to "alice"))
            f.drain()
            assertEquals(listOf("native"), f.repo.receivedMarkerList.value.map { it.uid })
            assertTrue(f.logs.any { it.contains("author mismatch") })
            val prefs = f.app.getSharedPreferences("ditto-point-watermarks-v1", Context.MODE_PRIVATE)
            val record = org.json.JSONObject(prefs.getString(DittoCotDocument.decode(native).key, null)!!)
            assertEquals("alice", record.getString("owner"))
            f.emit("mapitem", native + mapOf("d" to "bob", "b" to f.time + 1))
            f.drain()
            assertTrue(f.logs.any { it.contains("Cross-owner") })
            assertEquals(f.time, f.repo.receivedMarkerList.value.single().timeReceived)
        } finally { f.close() }
    }

    @Test fun nativeIdentityMigrationPreservesLegacyPointRevisionAndDeletionOwnership() = runTest {
        val f = Fixture(this)
        try {
            val legacy = f.contracted()
            f.emit("mapitem", legacy)
            f.drain()
            val native = f.contracted(f.doc(at = f.time + 1) + ("d" to "alice"))
            f.emit("mapitem", native)
            f.drain()
            assertEquals(f.time + 1, f.repo.receivedMarkerList.value.single().timeReceived)
            f.emit("mapitem", f.contracted(f.doc(at = f.time + 2) + ("d" to "alice"), deleted = true))
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            f.emit("mapitem", legacy, native)
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun recipientsGroupMembershipSelfOriginAndCommandTextAreFilteredWithoutExecution() = runTest {
        val f = Fixture(this)
        try {
            f.repo.storeEnableChatCommands(true)
            f.repo.storeChatCommandCallsigns(listOf("alice"))
            f.repo.storeCallsign("WATCH")
            f.emit("mapitem", f.chat("private-other", "bob"), f.chat("own-chat", sender = "watch"),
                f.chat("from-local-peer") + ("a" to "local-peer"),
                f.chat("local-author") + ("AuthorId" to "watch"),
                f.chat("command", text = "${ChatCommandParser.PREFIX}setcallsign callsign=CHANGED"),
                f.chat("private-command", "bob",
                    text = "${ChatCommandParser.PREFIX}setcallsign callsign=PRIVATE"),
                f.chat("all", "All Chat Rooms"),
                f.chat("group", "team").let {
                    it + ("r" to (it.getValue("r") as String).replace(
                        "uid1='team'", "uid1='watch' uid2='bob'"))
                },
                f.doc("watch", "a-f-G-U-C"), f.doc("own-marker") +
                    ("r" to "<detail><creator uid='watch'/></detail>"))
            f.drain()
            assertEquals(setOf("command", "all", "group"),
                f.chats.map { it.parsedRooms!!.single().messageList.single().messageId }.toSet())
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertTrue(f.repo.receivedUserList.value.isEmpty())
            assertTrue(f.logs.none { it.contains("COMMAND") })
            assertEquals("WATCH", f.repo.getCallsign.first())
            assertTrue(f.delivery.outboundDitto.isEmpty())
        } finally { f.close() }
    }

    @Test fun duplicateCallbacksAcrossCollectionsAndRestartDoNotReplayChatsOrOlderLiveState() = runTest {
        val f = Fixture(this)
        try {
            val pli = f.doc("alice", "a-f-G-U-C")
            f.emit("track", pli)
            f.emit("mapitem", pli, f.chat())
            f.drain()
            val receivedAt = f.repo.receivedUserList.value.single().receivedAt
            f.emit("track", pli)
            f.emit("mapitem", pli, f.chat())
            f.drain()
            assertEquals(1, f.chats.size)
            assertEquals(1, f.repo.chatRoomsFlow.value.single().messageList.size)
            assertEquals(receivedAt, f.repo.receivedUserList.value.single().receivedAt)
            f.observers.close()
            f.observers = f.start()
            f.emit("mapitem", f.chat(), f.doc("point", at = f.time + 500))
            f.drain()
            f.emit("mapitem", f.doc("point", at = f.time), f.chat())
            f.drain()
            assertEquals(f.time + 500, f.repo.receivedMarkerList.value.single().timeReceived)
            assertEquals(1, f.chats.size)
            // Recreate receiver and clear live state as on process restart: equal latest live data rehydrates.
            f.observers.close()
            f.repo.clearReceivedUsers()
            f.observers = f.start()
            f.emit("track", pli)
            f.drain()
            assertEquals("alice", f.repo.receivedUserList.value.single().uid)
        } finally { f.close() }
    }

    @Test fun nativeChatCommandsRetainVerifiedRoomAndNeverEnterInbox() = runTest {
        val f = Fixture(this)
        try {
            var runs = 0
            val replies = mutableListOf<com.aitech.weartak_civ.command.ChatCommandRequest>()
            val processor = com.aitech.weartak_civ.command.ChatCommandProcessor(
                { true }, { listOf("alice") },
                mapOf("setcallsign" to com.aitech.weartak_civ.command.ChatCommandHandler { _, _ ->
                    runs++
                    com.aitech.weartak_civ.command.ChatCommandResult(true, "Callsign set")
                }), reply = { request, _ -> replies += request }, audit = {}, now = { f.time })
            f.observers.close()
            f.observers = f.start(commands = processor::handle)
            val text = "#weartakcommandsetcallsign callsign=UPDATED"
            f.emit("chat", f.nativeChat("direct", text = text),
                f.nativeChat("group", "team", text = text, members = "uid1='watch' uid2='alice'"),
                f.nativeChat("all", "All Chat Rooms", text = text),
                f.nativeChat("other", "someone-else", text = text),
                f.nativeChat("unverified", "team", text = text, members = "uid1='bob'"),
                f.nativeChat("own", recipient = "bob", sender = "watch", text = text))
            f.drain()
            assertEquals(3, runs)
            assertTrue(f.chats.isEmpty())
            assertTrue(f.repo.chatRoomsFlow.value.isEmpty())
            assertEquals(listOf("alice", "team", "All Chat Rooms"), replies.map { it.replyRoomUid })
            assertTrue(replies.all { it.sourceServerId.startsWith("ditto:") })
            processor.handle(replies.first().copy(sourceServerId = "multicast:224.10.10.1:17012"))
            assertEquals(3, runs)
        } finally { f.close() }
    }

    @Test fun nativeDittoCanReenableTakAndMulticastThroughProductionCommandRuntime() = runTest {
        val f = Fixture(this)
        try {
            f.repo.storeNetworkPreferencesLocked(false)
            f.repo.storeEnableChatCommands(true)
            f.repo.storeChatCommandCallsigns(listOf("alice"))
            f.repo.storeEnableTakEndpoint(false)
            f.repo.storeEnableMulticast(false)
            mockkObject(ChatCommandReplies)
            val outcomes = mutableListOf<String>()
            coEvery { ChatCommandReplies.send(any(), any(), any(), any()) } coAnswers {
                outcomes += arg<String>(3)
                true
            }
            val handler = ChatCommandRuntime.build(f.app, f.repo)
            f.observers.close()
            f.observers = f.start(commands = handler)
            f.emit("chat",
                f.nativeChat("enable-tak", text = "#weartakcommand enabletakservers"),
                f.nativeChat("enable-multicast", text = "#weartakcommand enablemulticast"))
            f.drain()
            assertTrue(outcomes.toString(),
                f.repo.getEnableTakEndpoint.first())
            assertTrue(f.repo.getEnableMulticast.first())
            assertTrue(f.chats.isEmpty())
            coVerify(exactly = 2) {
                ChatCommandReplies.send(any(), any(), match { it.sourceServerId.startsWith("ditto:") }, any())
            }
        } finally {
            f.repo.storeEnableTakEndpoint(true)
            f.repo.storeEnableMulticast(true)
            io.mockk.unmockkObject(ChatCommandReplies)
            f.close()
        }
    }

    @Test fun sensitivePublicationGuardsCoverNativeLegacyAndMalformedCommands() = runTest {
        val f = Fixture(this)
        val publisher = f.publisher(mutableListOf())
        try {
            listOf("#weartakcommand enrollserver password=private-secret",
                "#WEARTAKCOMMANDENROLLSERVER", "#WearTakCommand enrollserver-bad").forEach { text ->
                val native = f.sensitiveChat(text = text)
                val legacy = f.chat(text = text)
                assertFalse(publisher.publishDocument("chat", native))
                assertFalse(publisher.publishDocument("mapitem", legacy))
                val xml = DittoCotDocument.decode(legacy).xml
                assertEquals("", DittoCotDocument.decode(legacy).fingerprint)
                try {
                    DittoChatDocument.encode(xml, "local-peer")
                    fail("Sensitive GeoChat must not be encoded for publication")
                } catch (error: IllegalArgumentException) {
                    assertFalse(error.message.orEmpty().contains("private-secret"))
                }
                publisher.onDittoDoc(DittoDocumentEvent("chat", xml))
                publisher.onDittoDoc(DittoDocumentEvent("mapitem", xml))
            }
            val job = (DittoService::class.java.getDeclaredField("ioScope")
                .apply { isAccessible = true }.get(publisher) as kotlinx.coroutines.CoroutineScope)
                .coroutineContext[Job]!!
            job.children.toList().forEach { it.join() }
            verify(exactly = 0) { publisher.writeDocument(any(), any()) }
        } finally {
            (DittoService::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
                .get(publisher) as kotlinx.coroutines.CoroutineScope).coroutineContext[Job]?.cancel()
            f.close()
        }
    }

    @Test fun sensitiveCommandsRunBeforeLocalEvictionAndJournalOnlyIdentifiersAndTimes() = runTest {
        val f = Fixture(this)
        try {
            val order = mutableListOf<String>()
            f.trackEvictions { collection, query -> order += "evict:$collection:$query" }
            f.observers.close()
            f.observers = f.start(commands = {
                order += "command:${it.messageId}"
                true
            })
            val native = f.sensitiveChat()
            val legacy = f.chat("legacy-enroll", text = "#weartakcommandenrollserver password=private-secret")
            f.emit("chat", native)
            f.emit("mapitem", legacy)
            f.drain()
            assertEquals(4, order.size)
            assertTrue(order.indexOf("command:enroll") < order.indexOf("evict:chat:_id == \"enroll\""))
            assertTrue(order.indexOf("command:legacy-enroll") <
                order.indexOf("evict:mapitem:_id == \"legacy-enroll\""))
            assertEquals(2, f.journal.all.size)
            assertTrue(f.journal.all.values.all {
                (it as String).matches(Regex("[0-9]+:[0-9]+:"))
            })
            f.observers.close()
            f.observers = f.start(commands = { fail("Enrollment must not replay after restart"); true })
            f.emit("chat", native + mapOf("msg" to "#weartakcommand enrollserver password=changed",
                "r" to (native["r"] as String).replace("private-secret", "changed")))
            f.emit("mapitem", legacy)
            f.drain()
            assertEquals(6, order.size)
            assertTrue(f.chats.isEmpty())
            assertTrue(f.repo.chatRoomsFlow.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun sensitiveStartupSnapshotIsProcessedThenScrubbedWithoutWaitingForResync() = runTest {
        val f = Fixture(this)
        try {
            val order = mutableListOf<String>()
            f.trackEvictions { _, _ -> order += "evict" }
            val result = mockk<DittoQueryResult>(relaxed = true)
            every { result.items } returns listOf(mockk<DittoQueryResultItem> {
                every { value } returns f.sensitiveChat().mapValues { requireNotNull(it.value) }
            })
            f.results += result
            f.observers.close()
            every {
                f.store.registerObserver(any(), any(), any<(DittoQueryResult, () -> Unit) -> Unit>())
            } answers {
                if (firstArg<String>() == "SELECT * FROM chat") {
                    thirdArg<(DittoQueryResult, () -> Unit) -> Unit>()(result) { order += "next" }
                }
                mockk<DittoStoreObserver>(relaxed = true)
            }
            f.observers = f.start(commands = { order += "command"; true })
            runCurrent()
            assertEquals(listOf("command", "evict", "next"), order)
            assertTrue(f.chats.isEmpty())
        } finally { f.close() }
    }

    @Test fun sensitiveCleanupCoversMalformedMismatchExpiryEchoAndHiddenSnapshots() = runTest {
        val f = Fixture(this)
        try {
            val evictions = mutableListOf<Pair<String, String>>()
            f.trackEvictions { collection, query -> evictions += collection to query }
            val docs = listOf(
                f.sensitiveChat("missing") - "b",
                f.sensitiveChat("msg-only") + ("r" to f.nativeChat()["r"]),
                f.nativeChat("detail-only") + ("r" to f.sensitiveChat("detail-only")["r"]),
                f.sensitiveChat("expired") + ("o" to f.time),
                f.sensitiveChat("echo") + ("a" to "local-peer"),
                f.sensitiveChat("removed") + ("_r" to true),
                f.sensitiveChat("invisible") + ("visible" to false),
                f.sensitiveChat("future") + ("b" to f.time + 900_000),
                f.sensitiveChat("broken-detail") + ("r" to "<detail><broken")
            )
            f.emit("chat", *docs.toTypedArray())
            f.emit("mapitem", f.chat("broken-legacy",
                text = "#weartakcommand enrollserver password=private-secret") - "b",
                f.doc("malformed-legacy") + ("r" to "<detail><remarks>#WEARTAKCOMMANDENROLLSERVER private-secret"))
            f.drain()
            assertEquals(11, evictions.size)
            assertTrue(evictions.all { !it.second.contains("private-secret") })
            assertTrue(f.chats.isEmpty())
            assertTrue(f.journal.all.isEmpty())
            assertFalse(f.logs.any { it.contains("private-secret") })
        } finally { f.close() }
    }

    @Test fun sensitiveCleanupRunsWithChatDisabledOrCompanionSuppressionAndRetriesSafely() = runTest {
        val f = Fixture(this)
        try {
            var attempts = 0
            f.trackEvictions { _, _ ->
                attempts++
                if (attempts == 1) throw IllegalStateException("private-secret")
            }
            f.repo.storeEnableChatService(false)
            f.emit("chat", f.sensitiveChat())
            f.drain()
            assertEquals(1, attempts)
            f.repo.setCompanionAppConnected()
            f.emit("chat", f.sensitiveChat(), f.sensitiveChat("suppressed"))
            f.emit("mapitem", f.chat("suppressed-legacy",
                text = "#weartakcommand enrollserver password=private-secret"))
            f.drain()
            assertEquals(4, attempts)
            assertTrue(f.chats.isEmpty())
            assertTrue(ShadowLog.getLogsForTag("WearTAK-DittoObservers").any {
                it.msg.contains("cleanup failed") && it.throwable == null
            })
            assertFalse(ShadowLog.getLogs().any {
                it.msg.contains("private-secret") || it.throwable?.message?.contains("private-secret") == true
            })
        } finally {
            f.repo.setCompanionAppDisconnected()
            f.repo.storeEnableChatService(true)
            f.close()
        }
    }

    @Test fun sensitiveCancellationAndQueuedObserverCloseStillEvictEveryDocument() = runTest {
        val f = Fixture(this)
        try {
            val evictions = mutableListOf<String>()
            f.trackEvictions { _, query -> evictions += query }
            f.observers.close()
            f.observers = f.start(commands = { throw kotlinx.coroutines.CancellationException("private-secret") })
            f.emit("chat", f.sensitiveChat(), f.sensitiveChat("unvisited"))
            f.drain()
            assertEquals(2, evictions.size)
            f.emit("chat", f.sensitiveChat("queued"))
            f.observers.close()
            runCurrent()
            assertEquals(3, evictions.size)
            assertTrue(f.chats.isEmpty())
            assertTrue(f.journal.all.isEmpty())
        } finally { f.close() }
    }

    @Test fun sensitiveCleanupRunsEvenWhenSnapshotSizeLimitsRejectIngestion() = runTest {
        val f = Fixture(this)
        try {
            var attempts = 0
            f.trackEvictions { _, _ -> attempts++ }
            f.emit("chat", f.sensitiveChat() + ("r" to
                ("#weartakcommand enrollserver " + "x".repeat(DittoStoreObservers.MAX_SNAPSHOT_CHARACTERS))))
            f.drain()
            assertEquals(1, attempts)
            f.emit("chat", *List(DittoStoreObservers.MAX_SNAPSHOT_DOCUMENTS + 1) { index ->
                if (index == 0) f.sensitiveChat("oversized-count") else mapOf("_id" to "ordinary-$index")
            }.toTypedArray())
            f.drain()
            assertEquals(2, attempts)
            assertTrue(f.chats.isEmpty())
        } finally { f.close() }
    }

    @Test fun chatCollectionUsesNormalInboxAndDeduplicatesOtherTransportsInEitherOrder() = runTest {
        val f = Fixture(this)
        try {
            f.prepareTransportAdapters()
            f.tcp(f.chat("tcp-first"))
            f.emit("chat", f.nativeChat("tcp-first"), f.nativeChat("ditto-first"))
            f.drain()
            f.multicast(f.chat("ditto-first"))
            f.emit("mapitem", f.chat("ditto-first"))
            f.emit("chat", f.nativeChat("tcp-first"), f.nativeChat("ditto-first"))
            f.drain()
            val messages = f.repo.chatRoomsFlow.value.single().messageList
            assertEquals(listOf("tcp-first", "ditto-first"), messages.map { it.messageId })
            assertTrue(f.repo.chatRoomsFlow.value.single().newMessagesPresent)
            f.observers.close()
            f.observers = f.start()
            f.emit("chat", f.nativeChat("tcp-first"), f.nativeChat("ditto-first"))
            f.drain()
            assertEquals(messages, f.repo.chatRoomsFlow.value.single().messageList)
            assertTrue(f.delivery.outboundDitto.isEmpty())
        } finally { f.close() }
    }

    @Test fun chatCollectionFiltersAddressingEchoesExpiryAndInvalidDocuments() = runTest {
        val f = Fixture(this)
        try {
            f.emit("chat",
                f.nativeChat("direct"), f.nativeChat("other", "bob"),
                f.nativeChat("own-sender", recipient = "bob", sender = "watch"),
                f.nativeChat("own-peer") + ("a" to "local-peer"),
                f.nativeChat("expired") + ("o" to f.time),
                f.nativeChat("future") + mapOf("b" to f.time + 600_000, "o" to f.time + 900_000),
                f.doc(), f.nativeChat("missing-id").let {
                    it + ("r" to (it.getValue("r") as String).replace("messageId=\"missing-id\"", ""))
                },
                f.nativeChat("all", "All Chat Rooms"),
                f.nativeChat("group", "team", members = "uid1='watch' uid2='bob'"),
                f.nativeChat("not-in-group", "team", members = "uid1='bob' uid2='charlie'"))
            f.drain()
            assertEquals(setOf("direct", "all", "group"),
                f.chats.flatMap { it.parsedRooms.orEmpty() }.flatMap { it.messageList }
                    .map { it.messageId }.toSet())
            assertTrue(f.logs.any { it.contains("Invalid GeoChat") || it.contains("Missing Ditto chat") })
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun chatCollectionRespectsChatSettingAndBondedCompanionSuppression() = runTest {
        val f = Fixture(this)
        try {
            f.repo.storeEnableChatService(false)
            f.emit("chat", f.nativeChat())
            f.drain()
            assertTrue(f.chats.isEmpty())
            f.repo.storeEnableChatService(true)
            f.emit("chat", f.nativeChat())
            f.drain()
            assertEquals(1, f.chats.size)
            f.repo.setCompanionAppConnected()
            f.emit("chat", f.nativeChat("suppressed"))
            f.drain()
            assertEquals(1, f.chats.size)
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            assertFalse(publisher.publishDocument("chat", f.nativeChat("outgoing")))
            assertTrue(writes.isEmpty())
        } finally { f.close() }
    }

    @Test fun officialPluginChatWithoutWearTakExtensionsReachesAddressedInboxOnly() = runTest {
        val f = Fixture(this)
        val nativeFields = setOf("_id", "_v", "_r", "a", "b", "d", "e", "msg",
            "room", "roomId", "parent", "authorType", "authorLoc")
        fun native(id: String, room: String = "watch") =
            f.nativeChat(id, room).filterKeys { it in nativeFields }
        try {
            f.emit("chat", native("native-direct"), native("native-broadcast", "All Chat Rooms"),
                native("private", "bob"), native("unknown-group", "team"),
                native("expired") + ("b" to f.time - DittoChatDocument.TTL_MILLIS))
            f.drain()
            assertEquals(setOf("native-direct", "native-broadcast"),
                f.repo.chatRoomsFlow.value.flatMap { it.messageList }.map { it.messageId }.toSet())
            assertEquals("alice", f.repo.chatRoomsFlow.value.first { !it.isGroup }.uid)
            val knownGroup = CotMessageParser.parseChatRooms(DittoCotDocument.decode(f.chat("seed", "team").let {
                it + ("r" to (it.getValue("r") as String).replace(
                    "uid1='team'", "uid1='watch' uid2='bob'"))
            }).xml, "watch", "WATCH").single()
            f.delivery.chat(TakChatEvent("", parsedRooms = listOf(knownGroup)))
            f.emit("chat", native("known-group", "team"))
            f.drain()
            assertEquals(listOf("seed", "known-group"),
                f.repo.chatRoomsFlow.value.first { it.uid == "team" }.messageList.map { it.messageId })
            f.emit("chat", native("mismatch") + ("r" to f.nativeChat("other").getValue("r")))
            f.drain()
            assertTrue(f.logs.any { it.contains("envelope/detail mismatch") })
        } finally { f.close() }
    }

    @Test fun officialSixFieldGeoPointAndUnknownAltitudeReachInbox() = runTest {
        val f = Fixture(this)
        val fields = setOf("_id", "_v", "_r", "a", "b", "d", "e", "msg",
            "room", "roomId", "parent", "authorType", "authorLoc")
        fun native(id: String, location: String) =
            f.nativeChat(id).filterKeys { it in fields } + ("authorLoc" to location)
        val exact = native("six-fields", "41.879815,-87.642469,156.933,HAE,19.4,9999999.0")
        val unknown = native("nan-alt", " 41.879815 , -87.642469 , NaN , HAE , NaN , NaN ")
        try {
            fun point(doc: Map<String, Any?>) =
                CotMessageParser.parseDocumentOrNull(DittoChatDocument.decode(doc).xml)!!
                    .getElementsByTagName("point").item(0) as org.w3c.dom.Element
            val known = point(exact)
            assertEquals("156.933", known.getAttribute("hae"))
            assertEquals("19.4", known.getAttribute("ce"))
            assertEquals("9999999.0", known.getAttribute("le"))
            val missing = point(unknown)
            assertEquals("9999999.0", missing.getAttribute("hae"))
            assertEquals("999999.0", missing.getAttribute("ce"))
            assertEquals("999999.0", missing.getAttribute("le"))
            for (alt in listOf("Infinity", "-Infinity", "", "NaN")) {
                assertEquals("9999999.0", point(native("optional", "41,-87,$alt,HAE")).getAttribute("hae"))
            }
            assertEquals("9999999.0", point(native("agl", "41,-87,100,AGL")).getAttribute("hae"))
            point(native("two", "41,-87"))
            point(native("extra", "41,-87,100,HAE,5,6,ignored"))
            assertThrows(IllegalArgumentException::class.java) {
                point(native("bad-lat", "NaN,-87,100,HAE,5,6"))
            }
            f.emit("chat", exact, unknown)
            f.drain()
            assertEquals(setOf("six-fields", "nan-alt"),
                f.repo.chatRoomsFlow.value.flatMap { it.messageList }.map { it.messageId }.toSet())
        } finally { f.close() }
    }

    @Test fun outgoingChatCallbackPreservesGeoChatIdentityAndAllDestinationTypes() = runTest {
        val f = Fixture(this)
        val writes = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        val publisher = f.publisher(writes)
        val completed = CompletableDeferred<Unit>()
        every { publisher.writeDocument(any(), any()) } answers {
            assertEquals("chat", firstArg<String>())
            writes += secondArg<Map<String, Any?>>()
            if (writes.size == 3) completed.complete(Unit)
            Unit
        }
        try {
            MainActivity.Constants.callsign = "WATCH"
            val destinations = listOf(
                Triple("bob", "Bob", false),
                Triple("team", "Team & Friends", true),
                Triple("All Chat Rooms", "All Chat Rooms", true)
            )
            val xmlByRoom = destinations.associate { (uid, title, group) ->
                val snapshot = f.repo.snapshotChatEvent(uid, title, "Hello <team> & friends",
                    isGroup = group, participantUids = if (group) listOf("watch", "bob", "charlie") else emptyList())
                uid to CotMessageBuilder.chat(snapshot, "WATCH", "watch", "message-$uid", f.time)
            }
            xmlByRoom.values.forEach {
                publisher.onDittoDoc(DittoDocumentEvent("chat", it))
            }
            withContext(Dispatchers.Default) { withTimeout(10_000) { completed.await() } }
            val documents = writes.associateBy { it["_id"] }
            xmlByRoom.forEach { (room, original) ->
                val document = documents.getValue("message-$room")
                val decoded = DittoChatDocument.decode(document)
                assertEquals(CotMessageParser.parseChatRooms(original, "bob", "BOB", receivedAt = f.time),
                    CotMessageParser.parseChatRooms(decoded.xml, "bob", "BOB", receivedAt = f.time))
                assertEquals("local-peer", decoded.peer)
                assertEquals("watch", decoded.author)
                assertEquals("WATCH", document["e"])
                assertEquals(f.time, document["b"])
                assertEquals(f.time + 86_400_000, document["o"])
                val remote = CotMessageParser.parseChatRooms(decoded.xml, "bob", "BOB").single()
                assertEquals("message-$room", remote.messageList.single().messageId)
                assertEquals("Hello <team> & friends", remote.messageList.single().message)
                assertTrue(CotMessageParser.parseChatRooms(decoded.xml, "watch", "WATCH").isEmpty())
                if (room == "team") assertEquals(listOf("watch", "bob", "charlie"), remote.participantUids)
            }
        } finally {
            (DittoService::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
                .get(publisher) as kotlinx.coroutines.CoroutineScope).coroutineContext[Job]?.cancel()
            f.close()
        }
    }

    @Test fun chatRetentionUsesOneCombinedLocalEvictionAndVerifiesItActuallySucceeded() = runTest {
        val f = Fixture(this)
        try {
            val publisher = f.publisher(mutableListOf())
            val collection = mockk<live.ditto.DittoCollection>()
            val operation = mockk<live.ditto.DittoPendingCursorOperation>()
            every { f.store.collection("chat") } returns collection
            every { collection.find(DittoChatRetention.expiredQuery(f.time)) } returns operation
            every { operation.evict() } returns emptyList()
            every { operation.limit(1) } returns operation
            every { operation.exec() } returns emptyList()
            publisher.evictExpiredChatDocuments(f.store, f.time)
            verify(exactly = 1) { operation.evict() }
            every { operation.exec() } returns listOf(mockk())
            assertThrows(IllegalStateException::class.java) {
                publisher.evictExpiredChatDocuments(f.store, f.time)
            }
            every { operation.evict() } throws IllegalStateException("synthetic eviction failure")
            assertThrows(IllegalStateException::class.java) {
                publisher.evictExpiredChatDocuments(f.store, f.time)
            }
            assertTrue(f.delivery.outboundDitto.isEmpty())
        } finally { f.close() }
    }

    @Test fun allFourCollectionsRejectExpiredIngestWithoutPhysicalCleanup() = runTest {
        val f = Fixture(this)
        try {
            f.emit("track", f.doc("expired-track", "a-f-G-U-C") + ("o" to f.time))
            f.emit("mapitem", f.doc("expired-point") + ("o" to f.time))
            f.emit("alert", f.alarm() + ("o" to f.time))
            f.emit("chat", f.nativeChat("expired-chat") + ("o" to f.time),
                (f.nativeChat("expired-native-chat") - "o" - "r" - "n") +
                    ("b" to f.time - DittoChatDocument.TTL_MILLIS))
            f.drain()
            assertTrue(f.repo.receivedUserList.value.isEmpty())
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertTrue(f.bh.remoteAlerts.value.isEmpty())
            assertTrue(f.chats.isEmpty())
            verify(exactly = 0) { f.store.collection(any()) }
            f.emit("alert", f.alarm(at = f.time - 1))
            f.drain()
            assertTrue(f.bh.remoteAlerts.value.isEmpty())
            assertTrue(f.logs.any { it == "Ignoring expired Ditto emergency snapshot" })
        } finally { f.close() }
    }

    @Test fun expiredNewerAlertClearsItsPriorLiveStateAndCannotReplayAfterRestart() = runTest {
        val f = Fixture(this)
        try {
            f.emit("alert", f.alarm())
            f.drain()
            assertTrue(f.bh.isUidValid("alarm"))
            f.emit("alert", f.alarm(at = f.time + 100) + ("o" to f.time + 200))
            f.drain()
            assertFalse(f.bh.isUidValid("alarm"))
            f.observers.close()
            f.observers = f.start()
            f.emit("alert", f.alarm())
            f.drain()
            assertFalse(f.bh.isUidValid("alarm"))
            f.emit("alert", f.alarm(at = f.time + 500))
            f.drain()
            assertTrue(f.bh.isUidValid("alarm"))
        } finally { f.close() }
    }

    @Test fun expiredAlertCannotRemoveOtherTransportCopy() = runTest {
        val f = Fixture(this)
        try {
            val event = CotMessageParser.parseEmergencyOrNull(
                DittoCotDocument.decode(f.alarm(at = f.time - 1)).xml
            )!!
            f.bh.applyEmergencyEvent(event, "tak.example", "tak-server", f.time, "watch")
            f.emit("alert", f.alarm())
            f.drain()
            f.emit("alert", f.alarm(at = f.time + 100) + ("o" to f.time + 200))
            f.drain()
            assertEquals("tak-server", f.bh.remoteAlerts.value.getValue("alarm").bhEvent.sourceServerId)
        } finally { f.close() }
    }

    @Test fun expiredFutureMalformedOversizedAndControlDocumentsCannotReachApplication() = runTest {
        val f = Fixture(this)
        try {
            val bad = listOf(
                f.doc("expired") + ("o" to f.time),
                f.doc("future", at = f.time + 900_000),
                f.doc("nan") + ("j" to Double.NaN),
                f.doc("outside") + ("l" to 181.0),
                f.doc("missing") - "b",
                f.doc("version") + ("_v" to 3),
                f.doc("mismatch") + ("d" to "other"),
                f.doc("malformed", detail = "<detail><contact></detail>"),
                f.doc("injection", detail = "<detail/><event uid='evil'/>"),
                f.doc("nested", detail = "<detail><event uid='evil'/></detail>"),
                f.doc("entity", detail = "<!DOCTYPE detail [<!ENTITY x SYSTEM 'file:///not-read'>]><detail>&x;</detail>"),
                f.doc("large", detail = "<detail>${"x".repeat(DittoCotDocument.MAX_PAYLOAD_BYTES)}</detail>"),
                f.doc("control", "t-x-g-c"),
                f.doc("package", "b-f-t-r")
            )
            f.emit("mapitem", *bad.toTypedArray(), f.doc("valid"))
            f.drain()
            assertEquals(listOf("valid"), f.repo.receivedMarkerList.value.map { it.uid })
            assertTrue(f.chats.isEmpty())
            assertTrue(f.logs.count { it.contains("Rejected") } >= 12)
        } finally { f.close() }
    }

    @Test fun cotDeleteRemovesOnlyThatPeersCopyAndRejectsReplay() = runTest {
        val f = Fixture(this)
        try {
            val original = f.doc("remote")
            f.emit("mapitem", original)
            f.drain()
            assertEquals(listOf("remote"), f.repo.receivedMarkerList.value.map { it.uid })
            f.emit("mapitem", f.doc("remote", "t-x-d-d", at = f.time + 1))
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            f.emit("mapitem", original)
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun tombstonesQueryDisappearanceAndStaleCopiesNeverAuthorizePointDeletion() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.doc())
            f.drain()
            f.emit("mapitem")
            f.drain()
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            f.emit("mapitem", f.doc() + ("_r" to true), f.doc("invisible") + ("visible" to false))
            f.drain()
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            f.emit("mapitem", f.doc("point", at = f.time - 1000) + ("o" to f.time))
            f.drain()
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            assertTrue(f.bh.isUidValid("point"))
        } finally { f.close() }
    }

    @Test fun remoteSameUidCannotRecreateLocallyDeletedWatchPoint() = runTest {
        val f = Fixture(this)
        try {
            f.repo.appendDroppedMarker(DataRepository.DroppedMarker(
                DataRepository.MarkerCotType.DROP, "point", DataRepository.MarkerType.HOSTILE,
                "Local", "", f.time, null, true, true))
            f.emit("mapitem", f.doc())
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertTrue(f.repo.deleteDroppedPointLocally("point", f.bh))
            f.emit("mapitem", f.doc(at = f.time + 100))
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun closingAllObserversFencesQueuedAndLateCallbacksAndRestartRegistersFreshHandles() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.doc())
            f.observers.close()
            runCurrent()
            f.emit("mapitem", f.doc("late"))
            runCurrent()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertEquals(4, f.handles.size)
            f.handles.forEach { verify(exactly = 1) { it.close() } }
            f.pending.clear()
            f.observers = f.start()
            f.emit("mapitem", f.doc("after-restart"))
            f.drain()
            assertEquals("after-restart", f.repo.receivedMarkerList.value.single().uid)
            assertEquals(8, f.handles.size)
        } finally { f.close() }
    }

    @Test fun oversizedSnapshotIsRejectedWithoutStoppingOtherCollectionObservers() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", *List(DittoStoreObservers.MAX_SNAPSHOT_DOCUMENTS + 1) { f.doc() }.toTypedArray())
            f.emit("track", f.doc("alice", "a-f-G-U-C"))
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertEquals("alice", f.repo.receivedUserList.value.single().uid)
            assertTrue(ShadowLog.getLogsForTag("WearTAK-DittoObservers").any {
                it.msg.contains("Rejected Ditto mapitem snapshot") && it.throwable == null
            })
        } finally { f.close() }
    }

    @Test fun serviceDestroyStopsObserversAndScheduledPliAndRejectsFurtherCallbacks() = runTest {
        val f = Fixture(this)
        val service = Robolectric.buildService(DittoService::class.java).get()
        val handler = mockk<Handler>(relaxed = true)
        val thread = mockk<HandlerThread>(relaxed = true)
        fun set(name: String, value: Any) {
            DittoService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        set("dataRepository", f.repo)
        set("storeObservers", f.observers)
        set("dittoUpdateHandler", handler)
        set("dittoUpdateThread", thread)
        try {
            service.onDestroy()
            f.emit("mapitem", f.doc("after-stop"))
            runCurrent()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            verify(exactly = 1) { handler.removeCallbacks(any()) }
            verify(exactly = 1) { thread.quitSafely() }
            f.handles.forEach { verify(exactly = 1) { it.close() } }
            assertFalse(DittoService.isRunning.get())
        } finally { f.close() }
    }

    @Test fun partialObserverRegistrationFailureClosesPreviouslyRegisteredHandle() = runTest {
        val f = Fixture(this)
        try {
            f.observers.close()
            every {
                f.store.registerObserver("SELECT * FROM mapitem", any(),
                    any<(DittoQueryResult, () -> Unit) -> Unit>())
            } throws IllegalStateException("synthetic registration failure")
            assertThrows(IllegalStateException::class.java) { f.start() }
            assertEquals(5, f.handles.size)
            f.handles.forEach { verify(exactly = 1) { it.close() } }
        } finally { f.close() }
    }

    @Test fun byteLimitIsInclusiveAndMultiByteOversizeDoesNotBlockAValidDocument() = runTest {
        val f = Fixture(this)
        try {
            val base = f.doc("limit-ok")
            val detail = base.getValue("r") as String
            val room = DittoCotDocument.MAX_PAYLOAD_BYTES -
                DittoCotDocument.decode(base).xml.toByteArray(Charsets.UTF_8).size
            val exact = base + ("r" to detail.replace("</detail>", "${"x".repeat(room)}</detail>"))
            val over = f.doc("too-big!") +
                ("r" to detail.replace("</detail>", "${"\u4e00".repeat(room / 3 + 1)}</detail>"))
            f.emit("mapitem", exact, over)
            f.drain()
            assertEquals(listOf("limit-ok"), f.repo.receivedMarkerList.value.map { it.uid })
            assertTrue(f.logs.any { it.contains("Oversized") })
        } finally { f.close() }
    }

    @Test fun expiredNewerRevisionWatermarksCannotBeRolledBackByDelayedLongerLivedPoint() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.doc())
            f.drain()
            f.emit("mapitem", f.doc(at = f.time + 100) + ("o" to f.time + 200))
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertFalse(f.bh.isUidValid("point"))
            f.observers.close()
            f.observers = f.start()
            f.emit("mapitem", f.doc())
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertFalse(f.bh.isUidValid("point"))
        } finally { f.close() }
    }

    @Test fun companionTransportPausesDirectDittoReceiveAndPublishWithoutConsumingReplayState() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            val incoming = f.doc("remote-point")
            f.repo.setCompanionAppConnected()
            f.emit("mapitem", incoming)
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertFalse(publisher.publishDocument("mapitem", f.ownedDoc()))
            assertTrue(writes.isEmpty())

            f.repo.setCompanionAppDisconnected()
            assertTrue("The disconnect grace suppresses direct publication", f.repo.shouldSuppressWatchTransportNow())
            f.emit("mapitem", incoming)
            f.drain()
            assertEquals(listOf("remote-point"), f.repo.receivedMarkerList.value.map { it.uid })
            assertTrue(f.repo.resumeWatchTransportsImmediately())
            assertTrue(publisher.publishDocument("mapitem", f.ownedDoc()))
            assertEquals(1, writes.size)
        } finally { f.close() }
    }

    @Test fun receiveNeverRequestsOutboundChatOrDittoRelay() = runTest {
        val f = Fixture(this)
        var outbound = 0
        val chatSend = launch(start = CoroutineStart.UNDISPATCHED) {
            f.repo.chatRequestSend.collect { outbound++ }
        }
        val automated = launch(start = CoroutineStart.UNDISPATCHED) {
            f.repo.automatedChatRequest.collect { outbound++ }
        }
        try {
            f.emit("mapitem", f.chat(), f.doc())
            f.emit("track", f.doc("alice", "a-f-G-U-C"))
            f.drain()
            assertEquals(0, outbound)
            assertTrue(f.delivery.outboundDitto.isEmpty())
        } finally {
            chatSend.cancel()
            automated.cancel()
            f.close()
        }
    }

    @Test fun emergencyCancellationSurvivesReplayAndRestartWithoutSuppressingANewerAlert() = runTest {
        val f = Fixture(this)
        try {
            fun alert(at: Long, cancelled: Boolean = false) = f.doc(
                "alarm", if (cancelled) "b-a-o-can" else "b-a-o-tbl", at,
                "<detail><link uid='alice' relation='p-p'/>" +
                    "<emergency cancel='$cancelled' type='Troops in Contact'>ALICE</emergency></detail>")
            f.emit("alert", alert(f.time))
            f.drain()
            assertTrue(f.bh.isUidValid("alarm"))
            // Even an expired cancellation must not be misread as a new active alert.
            f.emit("alert", alert(f.time + 100, true) + ("o" to f.time + 200))
            f.drain()
            assertFalse(f.bh.isUidValid("alarm"))
            f.observers.close()
            f.observers = f.start()
            f.emit("alert", alert(f.time))
            f.drain()
            assertFalse(f.bh.isUidValid("alarm"))
            f.emit("alert", alert(f.time + 500))
            f.drain()
            assertTrue(f.bh.isUidValid("alarm"))
            f.emit("alert", f.doc("watch-9-1-1", "b-a-o-tbl"))
            f.drain()
            assertFalse(f.bh.isUidValid("watch-9-1-1"))
        } finally { f.close() }
    }

    @Test fun expiredDittoRevisionCannotRemoveOtherTransportPointOrBloodhoundCopy() = runTest {
        val f = Fixture(this)
        try {
            val decoded = DittoCotDocument.decode(f.doc())
            f.repo.receiveMarkerEvent(CotMessageParser.parseMarkerOrNull(decoded.xml)!!,
                f.bh, "tak.example", "tak-server", f.time)
            f.emit("mapitem", f.doc())
            f.drain()
            f.emit("mapitem", f.doc(at = f.time + 100) + ("o" to f.time + 200))
            f.drain()
            assertEquals("tak-server", f.repo.receivedMarkerList.value.single().sourceServerId)
            assertTrue(f.bh.isUidValid("point"))
        } finally { f.close() }
    }

    @Test fun nativeMaterializationFailureClosesResultAndAllowsTheNextSnapshot() = runTest {
        val f = Fixture(this)
        try {
            val failure = mockk<DittoQueryResult>(relaxed = true)
            every { failure.items } throws IllegalStateException("synthetic materialization failure")
            f.emitResult("mapitem", failure)
            f.drain()
            f.emit("mapitem", f.doc())
            f.drain()
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            assertTrue(ShadowLog.getLogsForTag("WearTAK-DittoObservers").any {
                it.msg.contains("Rejected Ditto mapitem snapshot") && it.throwable == null
            })
        } finally { f.close() }
    }

    @Test fun fullUnexpiredReplayJournalRejectsNewIdentitiesInsteadOfEvictingWatermarks() = runTest {
        val f = Fixture(this)
        try {
            f.observers.close()
            val editor = f.journal.edit()
            repeat(DittoCotReceiver.MAX_JOURNAL_ENTRIES) {
                editor.putString("synthetic-$it", "${f.time}:${f.time + 600_000}:hash")
            }
            assertTrue(editor.commit())
            f.observers = f.start()
            f.emit("mapitem", f.doc())
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertTrue(f.logs.any { it.contains("journal full") })
            assertEquals(DittoCotReceiver.MAX_JOURNAL_ENTRIES, f.journal.all.size)
        } finally { f.close() }
    }

    @Test fun sameUidPeersHaveIndependentRevisionsAndExpiryPreservesTheOtherPeer() = runTest {
        val f = Fixture(this)
        try {
            val second = f.doc(at = f.time + 100) + ("a" to "second-peer") +
                ("r" to "<detail><contact callsign='Second peer'/></detail>")
            f.emit("mapitem", f.doc(), second)
            f.drain()
            assertEquals("Second peer", f.repo.receivedMarkerList.value.single().title)
            f.emit("mapitem", f.doc(at = f.time + 200) + ("o" to f.time + 300))
            f.drain()
            assertEquals("Second peer", f.repo.receivedMarkerList.value.single().title)
            assertTrue(f.bh.isUidValid("point"))
            assertEquals(2, f.journal.all.size)
            f.emit("mapitem", f.doc(at = f.time - 1000) + ("a" to "second-peer"))
            f.drain()
            assertEquals("Second peer", f.repo.receivedMarkerList.value.single().title)
        } finally { f.close() }
    }

    @Test fun oneUnmaterializableDocumentLogsDiagnosticButDoesNotDiscardRemainingValidSnapshot() = runTest {
        val f = Fixture(this)
        try {
            val broken = mockk<DittoQueryResultItem>()
            every { broken.value } throws IllegalArgumentException("synthetic invalid document")
            val valid = mockk<DittoQueryResultItem>()
            every { valid.value } returns f.doc().mapValues { requireNotNull(it.value) }
            val result = mockk<DittoQueryResult>(relaxed = true)
            every { result.items } returns listOf(broken, valid)
            f.emitResult("mapitem", result)
            f.drain()
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            assertTrue(ShadowLog.getLogsForTag("WearTAK-DittoObservers").any {
                it.msg.contains("unmaterializable") && it.throwable == null
            })
        } finally { f.close() }
    }

    @Test fun lateCallbackFromOldRegistrationAfterRestartCannotInsertAnEntity() = runTest {
        val f = Fixture(this)
        try {
            val oldCallback = f.callbacks.getValue("SELECT * FROM mapitem")
            f.observers.close()
            f.observers = f.start()
            val oldResult = mockk<DittoQueryResult>(relaxed = true)
            f.results += oldResult
            oldCallback(oldResult) { fail("Closed observer must not signal next") }
            f.emit("mapitem", f.doc("current"))
            f.drain()
            verify(exactly = 0) { oldResult.items }
            assertEquals(listOf("current"), f.repo.receivedMarkerList.value.map { it.uid })
        } finally { f.close() }
    }

    @Test fun failedJournalCommitIsExplicitAndDoesNotDiscardNextValidDocument() = runTest {
        val f = Fixture(this)
        try {
            f.observers.close()
            val preferences = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { preferences.all } returns emptyMap()
            every { preferences.edit() } returns editor
            every { editor.putString(any(), any()) } returns editor
            every { editor.commit() } returnsMany listOf(false, true)
            f.observers = f.start(preferences)
            f.emit("mapitem", f.doc(), f.doc("next-valid"))
            f.drain()
            // Repository delivery precedes journal commit; persistence failure is not rollback.
            assertEquals(setOf("point", "next-valid"), f.repo.receivedMarkerList.value.map { it.uid }.toSet())
            assertTrue(f.logs.any { it.contains("Could not persist Ditto replay watermark") })
            verify(exactly = 2) { editor.commit() }
        } finally { f.close() }
    }

    @Test fun durableWatermarkWithoutLiveMutationRehydratesPointsButDoesNotRestoreRecordedChat() = runTest {
        val f = Fixture(this)
        try {
            f.observers.close()
            val point = DittoCotDocument.decode(f.doc())
            val chat = DittoCotDocument.decode(f.chat())
            val editor = f.journal.edit()
            listOf(point, chat).forEach {
                editor.putString(it.key, "${it.time}:${it.stale}:${it.fingerprint}")
            }
            assertTrue(editor.commit())
            f.observers = f.start()
            f.emit("mapitem", f.doc(), f.chat())
            f.drain()
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            assertTrue(f.chats.isEmpty())
            assertTrue(f.repo.chatRoomsFlow.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun capacityPruningKeepsObservedLongerLifetimeWatermarkAndRejectsStillSyncedOlderPoint() = runTest {
        val f = Fixture(this)
        try {
            val point = f.doc()
            f.emit("mapitem", point)
            f.drain()
            f.emit("mapitem", f.doc(at = f.time + 100) + ("o" to f.time + 200))
            f.drain()
            f.observers.close()
            val editor = f.journal.edit()
            repeat(DittoCotReceiver.MAX_JOURNAL_ENTRIES - 1) {
                editor.putString("expired-$it", "${f.time}:${f.time}:hash")
            }
            assertTrue(editor.commit())
            f.observers = f.start()
            f.emit("mapitem", f.doc("new-identity"))
            f.drain()
            assertEquals(2, f.journal.all.size)
            f.observers.close()
            f.observers = f.start()
            f.emit("mapitem", point)
            f.drain()
            assertEquals(listOf("new-identity"), f.repo.receivedMarkerList.value.map { it.uid })
        } finally { f.close() }
    }

    @Test fun documentedPliRetentionLimitUnseenLongerLivedOldRevisionCanReturnAfterPruningAndRestart() = runTest {
        val f = Fixture(this)
        try {
            // This process only observes a newer short-lived revision, not the older long-lived one.
            f.emit("track", f.doc("alice", "a-f-G-U-C", f.time + 100) + ("o" to f.time + 200))
            f.drain()
            assertTrue(f.repo.receivedUserList.value.isEmpty())
            f.observers.close()
            val editor = f.journal.edit()
            repeat(DittoCotReceiver.MAX_JOURNAL_ENTRIES - 1) {
                editor.putString("expired-$it", "${f.time}:${f.time}:hash")
            }
            assertTrue(editor.commit())
            f.observers = f.start()
            f.emit("mapitem", f.doc("new-identity"))
            f.drain()
            assertEquals(1, f.journal.all.size)
            f.observers.close()
            // A process restart also discards the repository's in-memory source-removal watermark.
            f.repo.clearReceivedUsers()
            f.observers = f.start()
            f.emit("track", f.doc("alice", "a-f-G-U-C"))
            f.drain()
            assertEquals(listOf("alice"), f.repo.receivedUserList.value.map { it.uid })
        } finally { f.close() }
    }

    @Test fun actualTcpMulticastAndDittoAdaptersExposeOneEntityPerUidAndOneNotificationPerRevision() = runTest {
        val f = Fixture(this)
        f.prepareTransportAdapters()
        f.startUnreadCollector()
        val updates = java.util.concurrent.CopyOnWriteArrayList<BloodhoundRepository.BloodhoundUpdate>()
        val notifications = backgroundScope.launch(Dispatchers.Unconfined) {
            f.bh.bloodhoundEvent.filterNotNull().collect { updates += it }
        }
        try {
            val docs = arrayOf(f.doc("alice", "a-f-G-U-C"), f.doc(), f.route(), f.chat(), f.alarm())
            f.tcp(*docs)
            assertEquals(2, updates.size)
            f.multicast(*docs)
            f.emit("mapitem", *docs)
            f.drain()
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
        } finally {
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
            f.emit("mapitem", f.doc("alice", "a-f-G-U-C"), f.doc(), f.route(), f.alarm())
            f.drain()
            assertEquals(newer, f.repo.receivedUserList.value.single().timeReceived)
            assertEquals(newer, f.repo.receivedMarkerList.value.single().timeReceived)
            assertEquals(newer, withContext(Dispatchers.Default) {
                withTimeout(5000) { f.repo.takRoutes.first { it.isNotEmpty() } }.single().eventTime
            })
            assertEquals(2, updates.size)
            f.multicast(f.alarm(newer + 100, cancelled = true))
            f.tcp(f.alarm(newer))
            f.emit("alert", f.alarm(newer))
            f.drain()
            assertTrue(f.bh.remoteAlerts.value.isEmpty())
            f.emit("alert", f.alarm(newer + 200))
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

    @Test fun allDittoDataTypesAndOwnEchoesNeverInvokeOutboundDispatcherStoreOrPluginHooks() = runTest {
        val f = Fixture(this)
        mockkConstructor(CotEndpointDispatcher::class)
        mockkObject(PluginCotEgress, PluginCotIngress)
        var outbound = 0
        val sends = launch(start = CoroutineStart.UNDISPATCHED) { f.repo.chatRequestSend.collect { outbound++ } }
        val automated = launch(start = CoroutineStart.UNDISPATCHED) {
            f.repo.automatedChatRequest.collect { outbound++ }
        }
        try {
            f.emit("mapitem", f.doc(), f.chat(), f.route(), f.alarm())
            f.emit("track", f.doc("alice", "a-f-G-U-C"), f.doc("watch", "a-f-G-U-C"),
                f.doc("self-peer", "a-f-G-U-C") + ("a" to "local-peer"),
                f.doc("self-author", "a-f-G-U-C") + ("AuthorId" to "watch"))
            f.drain()
            assertEquals(0, outbound)
            assertEquals(1, f.repo.receivedUserList.value.size)
            assertTrue(f.delivery.outboundDitto.isEmpty())
            coVerify(exactly = 0) { anyConstructed<CotEndpointDispatcher>().dispatch(any(), any()) }
            verify(exactly = 0) { f.store.write(any<(live.ditto.DittoWriteTransaction) -> Unit>()) }
            verify(exactly = 0) { PluginCotEgress.offer(any(), any()) }
            coVerify(exactly = 0) { PluginCotIngress.offer(any(), any()) }
        } finally {
            sends.cancel()
            automated.cancel()
            unmockkConstructor(CotEndpointDispatcher::class)
            unmockkObject(PluginCotEgress, PluginCotIngress)
            f.close()
        }
    }

    @Test fun localClearProducesRetainedSameDocumentTombstoneAndReceiverDeletesOnlyDittoCopy() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            publisher.publishDocument("mapitem", f.ownedDoc())
            val live = writes.single()
            assertEquals(false, live["_r"])
            f.clear()
            assertTrue(f.repo.droppedMarkerList.value.isEmpty())
            publisher.retryPointPublications()
            val tombstone = writes.last()
            assertEquals(true, tombstone["_r"])
            assertEquals(live["_id"], tombstone["_id"])
            assertEquals(live["b"], tombstone["b"])
            assertEquals(live["r"], tombstone["r"])
            val remote = DataRepository(f.app)
            f.observers.close()
            f.observers = f.start(repository = remote, uid = "receiving-watch", peer = "receiving-peer")
            val decoded = DittoCotDocument.decode(live)
            remote.receiveMarkerEvent(CotMessageParser.parseMarkerOrNull(decoded.xml)!!,
                f.bh, "TAK", "tak-copy", f.time)
            f.emit("mapitem", live)
            f.drain()
            f.emit("mapitem", tombstone)
            f.drain()
            assertEquals("tak-copy", remote.receivedMarkerList.value.single().sourceServerId)
            assertTrue(f.bh.isUidValid("point"))
            f.emit("mapitem", tombstone, live)
            f.drain()
            assertEquals("tak-copy", remote.receivedMarkerList.value.single().sourceServerId)
        } finally { f.close() }
    }

    @Test fun deletionBeforeLiveAndReplayAcrossRestartAreTerminalButNewUidIsAllowed() = runTest {
        val f = Fixture(this)
        try {
            val live = f.contracted()
            val deleted = f.contracted(deleted = true)
            f.emit("mapitem", deleted)
            f.drain()
            f.observers.close()
            f.repo.clearReceivedMarkers()
            f.observers = f.start()
            f.emit("mapitem", live, f.contracted(f.doc(at = f.time + 300)),
                f.contracted(f.doc("new-uid")))
            f.drain()
            assertEquals(listOf("new-uid"), f.repo.receivedMarkerList.value.map { it.uid })
            assertFalse(f.bh.isUidValid("point"))
        } finally { f.close() }
    }

    @Test fun staleDeletionAndCrossOwnerOrRevisionMismatchCannotEraseCurrentPoint() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.contracted())
            f.drain()
            f.emit("mapitem", f.contracted(f.doc(at = f.time + 200)))
            f.drain()
            val mismatch = f.contracted(deleted = true).toMutableMap()
            mismatch[DittoPointLedger.METADATA] = mapOf(
                "version" to 1, "owner" to "alice", "revision" to "bad", "deleted" to true)
            f.emit("mapitem", f.contracted(deleted = true),
                f.contracted(f.doc(at = f.time + 300) + ("AuthorId" to "bob"), true), mismatch)
            f.drain()
            assertEquals(f.time + 200, f.repo.receivedMarkerList.value.single().timeReceived)
            assertTrue(f.logs.any { it.contains("Cross-owner") })
            assertTrue(f.logs.any { it.contains("revision mismatch") })
        } finally { f.close() }
    }

    @Test fun deletingPeerDoesNotRemoveDifferentPeerSameUidOrLocallyOwnedPoint() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.contracted(), f.contracted(f.doc(at = f.time + 100) + ("a" to "peer-two")))
            f.drain()
            f.emit("mapitem", f.contracted(deleted = true))
            f.drain()
            assertEquals("ditto:${DittoCotDocument.digest("peer-two")}",
                f.repo.receivedMarkerList.value.single().sourceServerId)
            f.drop("local-only")
            f.emit("mapitem", f.contracted(f.doc("local-only"), true))
            f.drain()
            assertEquals("local-only", f.repo.droppedMarkerList.value.single().uid)
        } finally { f.close() }
    }

    @Test fun pendingDeleteSurvivesWriteFailureAndRestartWithoutRepublishingTheLiveRevision() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            publisher.publishDocument("mapitem", f.ownedDoc())
            f.clear()
            every { publisher.writeDocument(any(), any()) } throws IllegalStateException("synthetic offline store")
            publisher.retryPointPublications()
            assertTrue(ShadowLog.getLogsForTag("WearTAK-DittoService").any {
                it.msg.contains("pending") && it.throwable?.message == "synthetic offline store"
            })
            val recreatedPublisher = f.publisher(writes, DataRepository(f.app))
            recreatedPublisher.retryPointPublications()
            assertEquals(true, writes.last()["_r"])
            val size = writes.size
            recreatedPublisher.publishDocument("mapitem", f.ownedDoc(at = f.time + 100))
            recreatedPublisher.retryPointPublications()
            assertEquals(size, writes.size)
            assertThrows(IllegalArgumentException::class.java) {
                f.repo.dittoPointLedger.created("point", "watch")
            }
        } finally { f.close() }
    }

    @Test fun foreignOrUnpublishedPointClearNeverPublishesAMeshDelete() = runTest {
        val f = Fixture(this)
        try {
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            publisher.publishDocument("mapitem", f.ownedDoc("foreign"))
            f.clear("foreign")
            f.drop("never-published")
            f.clear("never-published")
            publisher.retryPointPublications()
            assertTrue(writes.isEmpty())
            assertTrue(f.repo.droppedMarkerList.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun failedDurableDeletionWatermarkDoesNotMutateRepositoryAndRetrySucceeds() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.contracted())
            f.drain()
            var fail = true
            val real = f.app.getSharedPreferences("ditto-point-watermarks-v1", Context.MODE_PRIVATE)
            val prefs = CommitResultPreferences(real) { fail }
            val ingress = DittoPointIngress(prefs)
            DataRepository::class.java.getDeclaredField("dittoPointIngress\$delegate").apply {
                isAccessible = true
                set(f.repo, lazyOf(ingress))
            }
            f.emit("mapitem", f.contracted(deleted = true))
            f.drain()
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            assertTrue(f.logs.any { it.contains("Could not persist Ditto point watermark") })
            f.emit("mapitem", f.contracted(f.doc("blocked-1")), f.contracted(f.doc("blocked-2")))
            f.drain()
            assertEquals(1, real.all.size)
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            fail = false
            f.emit("mapitem", f.contracted(f.doc("other")))
            f.drain()
            val key = DittoCotDocument.decode(f.doc()).key
            assertFalse(org.json.JSONObject(real.getString(key, null)!!).getBoolean("deleted"))
            f.emit("mapitem", f.contracted(deleted = true))
            f.drain()
            assertEquals("other", f.repo.receivedMarkerList.value.single().uid)
        } finally { f.close() }
    }

    @Test fun actualOutboundDocumentCallbackRecordsPublishedOwnershipThenClearPublishesDeletion() = runTest {
        val f = Fixture(this)
        val writes = mutableListOf<Map<String, Any?>>()
        val publisher = f.publisher(writes)
        val completed = CompletableDeferred<Unit>()
        every { publisher.writeDocument(any(), any()) } answers {
            writes += secondArg<Map<String, Any?>>()
            completed.complete(Unit)
            Unit
        }
        try {
            f.drop()
            MainActivity.Constants.callsign = "WATCH"
            DittoService.localPeerKeyString = "local-peer"
            publisher.onDittoDoc(DittoDocumentEvent("mapitem", DittoCotDocument.decode(f.ownedDoc()).xml))
            withContext(Dispatchers.Default) { withTimeout(10_000) { completed.await() } }
            assertTrue(DittoPointLedger.METADATA in writes.single())
            assertEquals("point", writes.single()["_id"])
            assertEquals("watch", writes.single()["d"])
            assertEquals("watch", writes.single()["AuthorId"])
            assertEquals("WATCH", writes.single()["e"])
            f.clear()
            publisher.retryPointPublications()
            assertEquals(true, writes.last()["_r"])
        } finally {
            (DittoService::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
                .get(publisher) as kotlinx.coroutines.CoroutineScope).coroutineContext[Job]?.cancel()
            f.close()
        }
    }

    @Test fun actualOutboundAlertUsesPublisherIdentityRatherThanEmergencyUid() = runTest {
        val f = Fixture(this)
        val writes = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val publisher = f.publisher(mutableListOf())
        val completed = CompletableDeferred<Unit>()
        every { publisher.writeDocument(any(), any()) } answers {
            assertEquals("alert", firstArg<String>())
            writes += secondArg<Map<String, Any?>>()
            if (writes.size == 2) completed.complete(Unit)
            Unit
        }
        try {
            MainActivity.Constants.callsign = "WATCH"
            listOf(false, true).forEach { cancelled ->
                val raw = f.alarm(cancelled = cancelled) + ("AuthorId" to "watch")
                publisher.onDittoDoc(DittoDocumentEvent("alert", DittoCotDocument.decode(raw).xml))
            }
            withContext(Dispatchers.Default) { withTimeout(10_000) { completed.await() } }
            writes.forEach {
                assertEquals("alarm", it["_id"])
                assertEquals("watch", it["d"])
                assertEquals("watch", it["AuthorId"])
                assertEquals("WATCH", it["e"])
                assertEquals("watch", DittoCotDocument.decode(it).author)
            }
            assertEquals(setOf("b-a-o-tbl", "b-a-o-can"), writes.map { it["w"] }.toSet())
        } finally {
            (DittoService::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
                .get(publisher) as kotlinx.coroutines.CoroutineScope).coroutineContext[Job]?.cancel()
            f.close()
        }
    }

    @Test fun failedLocalIntentCommitLeavesPointAndQueueEligibleForExplicitClearRetry() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            publisher.publishDocument("mapitem", f.ownedDoc())
            var fail = true
            // Android can update in-memory preferences even when commit reports failure.
            val preferences = CommitResultPreferences(
                f.app.getSharedPreferences("ditto-local-points-v1", Context.MODE_PRIVATE)
            ) { fail }
            val ledger = DittoPointLedger(preferences)
            val field = DataRepository::class.java.getDeclaredField("dittoPointLedger\$delegate")
                .apply { isAccessible = true }
            field.set(f.repo, lazyOf(ledger))
            f.clear(queueRetry = true)
            assertEquals("point", f.repo.droppedMarkerList.value.single().uid)
            publisher.retryPointPublications()
            assertEquals(1, writes.size)
            repeat(3) {
                val failure = runCatching { f.drop("blocked-$it") }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("Could not repair"))
            }
            assertEquals(1, preferences.all.size)
            fail = false
            ledger.created("additional", "watch")
            val repaired = preferences.getString(DittoCotDocument.digest("point"), null)!!
            assertFalse(org.json.JSONObject(repaired).getBoolean("deleted"))
            f.clear(queueRetry = false)
            publisher.retryPointPublications()
            assertTrue(f.repo.droppedMarkerList.value.isEmpty())
            assertEquals(true, writes.last()["_r"])
        } finally { f.close() }
    }

    @Test fun deletionLedgerCapacityDoesNotEvictOldTombstonesOrPartiallyDeleteANewIdentity() = runTest {
        val f = Fixture(this)
        try {
            f.observers.close()
            val prefs = f.app.getSharedPreferences("ditto-point-watermarks-v1", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            repeat(DittoPointIngress.MAX_ENTRIES) {
                editor.putString("permanent-$it",
                    """{"owner":"alice","time":1,"revision":"hash","deleted":true}""")
            }
            assertTrue(editor.commit())
            f.observers = f.start(repository = DataRepository(f.app))
            f.emit("mapitem", f.contracted(deleted = true))
            f.drain()
            assertEquals(DittoPointIngress.MAX_ENTRIES, prefs.all.size)
            assertTrue(f.logs.any { it.contains("permanent point ledger full") })
        } finally { f.close() }
    }

    @Test fun localPublisherFailureBeforeWriteIsRetriedAndADeleteSupersedesPendingLiveEdit() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            every { publisher.writeDocument(any(), any()) } throws IllegalStateException("offline")
            assertThrows(IllegalStateException::class.java) {
                publisher.publishDocument("mapitem", f.ownedDoc())
            }
            f.clear()
            val restarted = f.publisher(writes, DataRepository(f.app))
            restarted.retryPointPublications()
            assertEquals(1, writes.size)
            assertEquals(true, writes.single()["_r"])
        } finally { f.close() }
    }

    @Test fun permanentDeletedPointDoesNotReturnWhenReceiveDedupJournalHasBeenPruned() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.contracted(deleted = true))
            f.drain()
            f.observers.close()
            assertTrue(f.journal.edit().clear().commit())
            val restartedRepo = DataRepository(f.app)
            f.observers = f.start(repository = restartedRepo)
            f.emit("mapitem", f.doc(), f.contracted(f.doc(at = f.time + 300)))
            f.drain()
            assertTrue(restartedRepo.receivedMarkerList.value.isEmpty())
            assertFalse(f.bh.isUidValid("point"))
        } finally { f.close() }
    }

    @Test fun failedWriteAcknowledgementCommitKeepsPendingPublicationForRetry() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            var commits = 0
            val preferences = CommitResultPreferences(
                f.app.getSharedPreferences("ditto-local-points-v1", Context.MODE_PRIVATE)
            ) { ++commits == 2 }
            DataRepository::class.java.getDeclaredField("dittoPointLedger\$delegate").apply {
                isAccessible = true
                set(f.repo, lazyOf(DittoPointLedger(preferences)))
            }
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            assertThrows(IllegalStateException::class.java) {
                publisher.publishDocument("mapitem", f.ownedDoc())
            }
            assertEquals(1, writes.size)
            publisher.retryPointPublications()
            assertEquals(2, writes.size)
            assertEquals(DittoCotDocument.decode(writes[0]), DittoCotDocument.decode(writes[1]))
            assertEquals(writes[0][DittoPointLedger.METADATA], writes[1][DittoPointLedger.METADATA])
            publisher.retryPointPublications()
            assertEquals(2, writes.size)
        } finally { f.close() }
    }

    @Test fun durableLocalOriginCannotBeReintroducedAsADittoCopyAfterRepositoryRestart() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            f.clear()
            f.observers.close()
            val restarted = DataRepository(f.app)
            f.observers = f.start(repository = restarted)
            f.emit("mapitem", f.doc(), f.contracted())
            f.drain()
            assertTrue(restarted.receivedMarkerList.value.isEmpty())
            assertFalse(f.bh.isUidValid("point"))
        } finally { f.close() }
    }

    @Test fun persistedDeletionBeforeRepositoryMutationCompletesFromRestartedObserverSnapshot() = runTest {
        val f = Fixture(this)
        try {
            f.emit("mapitem", f.contracted())
            f.drain()
            val ingress = f.repo.dittoPointIngress
            assertTrue(ingress.accept(ingress.decode(f.contracted(deleted = true))))
            assertEquals("point", f.repo.receivedMarkerList.value.single().uid)
            f.observers.close()
            f.observers = f.start()
            f.emit("mapitem", f.contracted(), f.contracted(deleted = true), f.contracted(deleted = true))
            f.drain()
            assertTrue(f.repo.receivedMarkerList.value.isEmpty())
            assertFalse(f.bh.isUidValid("point"))
        } finally { f.close() }
    }

    @Test fun localOwnershipCapacityRejectsNewDropWithoutEvictingPriorOriginOrPublishing() = runTest {
        val f = Fixture(this)
        try {
            val prefs = f.app.getSharedPreferences("ditto-local-points-v1", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            repeat(DittoPointLedger.MAX_ENTRIES) {
                editor.putString("origin-$it", """{"uid":"old-$it","owner":"watch","deleted":true}""")
            }
            assertTrue(editor.commit())
            val failure = runCatching { f.drop() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("local point ledger full"))
            assertTrue(f.repo.droppedMarkerList.value.isEmpty())
            val writes = mutableListOf<Map<String, Any?>>()
            f.publisher(writes).publishDocument("mapitem", f.ownedDoc())
            assertTrue(writes.isEmpty())
            assertEquals(DittoPointLedger.MAX_ENTRIES, prefs.all.size)
        } finally { f.close() }
    }

    @Test fun localPublicationRecordByteLimitFailsClosedBeforeAnyStoreWrite() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            val writes = mutableListOf<Map<String, Any?>>()
            val doc = f.ownedDoc() + ("r" to "<detail><remarks>${"\"".repeat(200_000)}</remarks></detail>")
            val publisher = f.publisher(writes)
            assertThrows(IllegalArgumentException::class.java) { publisher.publishDocument("mapitem", doc) }
            assertTrue(writes.isEmpty())
            publisher.retryPointPublications()
            assertTrue(writes.isEmpty())
        } finally { f.close() }
    }

    @Test fun pendingDeletionIsNotReplayedIntoADifferentLocalPeerStore() = runTest {
        val f = Fixture(this)
        try {
            f.drop()
            val writes = mutableListOf<Map<String, Any?>>()
            val publisher = f.publisher(writes)
            publisher.publishDocument("mapitem", f.ownedDoc())
            f.clear()
            DittoService.localPeerKeyString = "different-peer-store"
            publisher.retryPointPublications()
            assertEquals(1, writes.size)
            assertTrue(ShadowLog.getLogs().any { it.msg.contains("publication pending") })
            DittoService.localPeerKeyString = "local-peer"
            publisher.retryPointPublications()
            assertEquals(true, writes.last()["_r"])
        } finally { f.close() }
    }
}
