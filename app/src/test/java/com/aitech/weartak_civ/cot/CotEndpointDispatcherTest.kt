package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.BatteryPowerObserver.DockType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CotEndpointDispatcherTest {
    @Test
    fun pliUsesTrackedTakPathWithoutChangingOtherMessageDispatch() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = CotEndpointDispatcher(
            sendTak = { _, _ -> calls += "untracked" }, sendSitx = {}, sendDitto = { _, _ -> },
            sendMulticast = {}, sendPliMulticast = {}, sendCompanion = {},
            sendTrackedTakPli = { calls += "tracked" }
        )
        val available = CotEndpointAvailability(true, false, false, false, false)
        dispatcher.dispatch(CotOutboundMessage("<pli/>", CotMessageKind.PLI,
            CotCompanionPayload.Pli(pliSnapshot())), available, readyPliOnly = true)
        dispatcher.dispatch(CotOutboundMessage("<chat/>", CotMessageKind.CHAT,
            CotCompanionPayload.Chat(chatSnapshot())), available)
        assertEquals(listOf("tracked", "untracked"), calls)
    }

    @Test
    fun chatCanExcludeDittoWithoutExcludingOtherPlugins() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = CotEndpointDispatcher(
            sendTak = { _, _ -> }, sendSitx = {}, sendDitto = { _, _ -> },
            sendMulticast = {}, sendPliMulticast = {}, sendCompanion = {},
            sendPlugin = { calls += "all plugins" },
            sendPluginWithoutDitto = { calls += "other plugins" },
        )
        val message = CotOutboundMessage("<chat/>", CotMessageKind.CHAT,
            CotCompanionPayload.Chat(chatSnapshot()), excludeDittoPlugin = true)
        assertTrue(dispatcher.dispatch(message,
            CotEndpointAvailability(false, false, false, false, false, plugin = true)))
        assertEquals(listOf("other plugins"), calls)
    }

    @Test
    fun trackedDittoOfferDoesNotCompleteUntilReceiverCallsBack() = runBlocking {
        var completion: ((Boolean) -> Unit)? = null
        var result: Boolean? = null
        var offered = false
        val dispatcher = CotEndpointDispatcher(
            sendTak = { _, _ -> }, sendSitx = {}, sendDitto = { _, _ -> error("untracked route") },
            sendTrackedDitto = { _, _, callback -> completion = callback },
            sendMulticast = {}, sendPliMulticast = {}, sendCompanion = {},
        )
        val message = CotOutboundMessage("<marker/>", CotMessageKind.MARKER, CotCompanionPayload.OwnedPointDeletion("test"),
            dittoCompletion = { result = it }, onDittoOffered = { offered = true })
        assertTrue(dispatcher.dispatch(message, CotEndpointAvailability(false, false, true, false, false)))
        assertTrue(offered)
        assertEquals(null, result)
        completion!!(true)
        assertEquals(true, result)
    }

    @Test
    fun ownedDeletionUsesDirectRoutesWithoutDittoAndNeverFallsThroughReadyCompanion() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = CotEndpointDispatcher(
            sendTak = { _, _ -> calls += "TAK" },
            sendSitx = { calls += "SITX" },
            sendDitto = { _, _ -> calls += "Ditto" },
            sendMulticast = { calls += "multicast" },
            sendPliMulticast = { calls += "PLI multicast" },
            sendCompanion = { calls += "legacy companion CoT" },
            sendPlugin = { calls += "plugin" },
        )
        val message = CotOutboundMessage("<owned-deletion/>", CotMessageKind.MARKER_CLEAR,
            CotCompanionPayload.OwnedPointDeletion("own-pin"))
        assertTrue(dispatcher.dispatch(message, allAvailable.copy(companion = false, plugin = true)))
        assertEquals(listOf("plugin", "multicast", "TAK", "SITX"), calls)
        calls.clear()
        assertEquals(false, dispatcher.dispatch(message, allAvailable.copy(plugin = true)))
        assertTrue(calls.isEmpty())
        assertEquals(false, dispatcher.dispatch(message,
            CotEndpointAvailability(false, false, false, false, false)))
        assertTrue(calls.isEmpty())
    }
    @Test
    fun dispatchesChatOnlyToReadyCompanion() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        val snapshot = chatSnapshot()

        val sent = dispatcher.dispatch(
            CotOutboundMessage("<chat/>", CotMessageKind.CHAT, CotCompanionPayload.Chat(snapshot)),
            allAvailable,
        )

        assertTrue(sent)
        assertEquals(
            listOf("companion:Chat"),
            calls,
        )
    }

    @Test
    fun chatUsesDirectRoutesWhenCompanionIsDisconnected() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        dispatcher.dispatch(
            CotOutboundMessage("<chat/>", CotMessageKind.CHAT, CotCompanionPayload.Chat(chatSnapshot())),
            allAvailable.copy(companion = false),
        )
        assertEquals(
            listOf("multicast:<chat/>", "ditto:chat:<chat/>", "tak:true:<chat/>", "sitx:<chat/>"),
            calls,
        )
    }

    @Test
    fun pliUsesOnlyTheReadyCompanionRoute() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        val snapshot = pliSnapshot()

        dispatcher.dispatch(
            CotOutboundMessage("<pli/>", CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot)),
            CotEndpointAvailability(tak = true, sitx = true, ditto = true, multicast = true, companion = true),
        )

        assertEquals(listOf("companion:Pli"), calls)
    }

    @Test
    fun routeReadyPliNeverCallsUnavailableLegacyRoutesOrDittoMapitem() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        val message = CotOutboundMessage("<pli/>", CotMessageKind.PLI, CotCompanionPayload.Pli(pliSnapshot()))
        assertEquals(false, dispatcher.dispatch(message,
            CotEndpointAvailability(false, false, true, false, false), readyPliOnly = true))
        assertTrue(calls.isEmpty())
        assertTrue(dispatcher.dispatch(message,
            CotEndpointAvailability(false, false, false, true, false), readyPliOnly = true))
        assertEquals(listOf("pliMulticast:<pli/>"), calls)
    }

    @Test
    fun routeReadyPliRetainsExclusiveCompanionAndDoesNotQueueTak() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        val message = CotOutboundMessage("<pli/>", CotMessageKind.PLI, CotCompanionPayload.Pli(pliSnapshot()))
        dispatcher.dispatch(message, allAvailable, readyPliOnly = true)
        assertEquals(listOf("companion:Pli"), calls)
        calls.clear()
        dispatcher.dispatch(message, allAvailable.copy(companion = false), readyPliOnly = true)
        assertEquals(listOf("tak:false:<pli/>", "sitx:<pli/>", "pliMulticast:<pli/>"), calls)
    }

    @Test
    fun normalPeriodicPliPolicyIsUnchanged() = runBlocking {
        val calls = mutableListOf<String>()
        dispatcher(calls).dispatch(
            CotOutboundMessage("<pli/>", CotMessageKind.PLI, CotCompanionPayload.Pli(pliSnapshot())),
            CotEndpointAvailability(false, false, false, false, false))
        assertEquals(listOf("sitx:<pli/>", "pliMulticast:<pli/>"), calls)
    }

    @Test
    fun finalSelfPliExpiryUsesOnlyConnectedTakAndMulticastRoutes() = runBlocking {
        val calls = mutableListOf<String>()
        val message = CotOutboundMessage("<expired-pli/>", CotMessageKind.PLI, CotCompanionPayload.Pli(pliSnapshot()))
        val dispatcher = dispatcher(calls)

        assertTrue(dispatcher.dispatchFinalSelfPliExpiry(
            message,
            CotEndpointAvailability(tak = true, sitx = true, ditto = true, multicast = true, companion = false),
            sendTakExpiry = { calls += "final-tak:$it"; true },
            sendMulticastExpiry = { calls += "final-multicast:$it"; true },
        ))
        assertEquals(listOf("final-tak:<expired-pli/>", "final-multicast:<expired-pli/>"), calls)

        calls.clear()
        assertFalse(dispatcher.dispatchFinalSelfPliExpiry(
            message,
            CotEndpointAvailability(tak = false, sitx = true, ditto = true, multicast = false, companion = false),
            sendTakExpiry = { calls += "unexpected-tak"; true },
            sendMulticastExpiry = { calls += "unexpected-multicast"; true },
        ))
        assertTrue(calls.isEmpty())
    }

    private fun dispatcher(calls: MutableList<String>) = CotEndpointDispatcher(
        sendTak = { xml, queue -> calls += "tak:$queue:$xml" },
        sendSitx = { xml -> calls += "sitx:$xml" },
        sendDitto = { collection, xml -> calls += "ditto:$collection:$xml" },
        sendMulticast = { xml -> calls += "multicast:$xml" },
        sendPliMulticast = { xml -> calls += "pliMulticast:$xml" },
        sendCompanion = { payload -> calls += "companion:${payload::class.simpleName}" },
    )

    private fun pliSnapshot() = DataRepository.PliSnapshot(location(), time(), physio(), system())
    private fun chatSnapshot() = DataRepository.ChatEventSnapshot("ignored", "room", "Room", "Hello", time(), location(), physio(), system())
    private fun location() = DataRepository.LocationSnapshot(0, 41.0, -87.0, 100.0, 4f, 5f, 45f, 3f)
    private fun time() = CotEventTime(0, 0, 0, START, START, STALE)
    private fun physio() = DataRepository.PhysioSnapshot(0, DataRepository.PhysioData(72, 98.6f, 30, 70, 60, .25, null, null))
    private fun system() = DataRepository.SystemSnapshot(0, 80, "EAGLE", "watch-1", "1", 10, DataRepository.SystemStates(0, DockType.NONE, false, true, 10, 10))

    private val allAvailable = CotEndpointAvailability(true, true, true, true, true)

    private companion object {
        const val START = "2026-08-19T12:00:00.000Z"
        const val STALE = "2026-08-19T12:00:30.000Z"
    }
}
