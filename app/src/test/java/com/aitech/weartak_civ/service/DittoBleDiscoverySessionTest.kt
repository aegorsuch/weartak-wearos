package com.aitech.weartak_civ.service

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlSource
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.*
import live.ditto.*
import live.ditto.transports.DittoTransportConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoBleDiscoverySessionTest {
    private class Fixture(
        scope: TestScope,
        initiallyEnabled: Boolean = true,
        persisted: String? = null,
        boot: Int = 7
    ) : AutoCloseable {
        val application = mockk<Application>(relaxed = true)
        val repo = mockk<DataRepository>()
        val ditto = mockk<Ditto>(relaxed = true)
        val presence = mockk<Presence>()
        val observer = mockk<DittoPresenceObserver>(relaxed = true)
        val callback = slot<(DittoPresenceGraph) -> Unit>()
        val master = MutableStateFlow(initiallyEnabled)
        val companion = MutableStateFlow(false)
        val reconfiguring = MutableStateFlow(false)
        val wrist = MutableStateFlow<Boolean?>(null)
        val appId = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
        val token = MutableStateFlow("")
        val source = MutableStateFlow(DittoPdlSource.COMMUNITY)
        val pdl = MutableStateFlow("")
        val changes = mutableListOf<Boolean>()
        val warnings = mutableListOf<BleLinkWarning>()
        val livePeers = mutableListOf<DittoDirectPeers>()
        val liveRecovery = mutableListOf<DittoRecoveryStatus>()
        val stored = mutableListOf<String?>()
        val actions = MutableSharedFlow<DataRepository.DittoBleRecoveryAction>(extraBufferCapacity = 4)
        var radio = true
        var config = DittoTransportConfig().apply {
            enableAllPeerToPeer()
            peerToPeer.bluetoothLe.enabled = false
            peerToPeer.lan.mdnsEnabled = false
            connect.websocketUrls = mutableSetOf("wss://example.invalid/sync")
            connect.retryIntervalMs = 12_345L
        }
        val original = config.clone()
        val session: DittoBleDiscoverySession

        init {
            every { repo.latestDeviceWornObservation } returns wrist
            every { repo.getDittoAppId } returns appId
            every { repo.getDittoAuthToken } returns token
            every { repo.getDittoPdlSource } returns source
            every { repo.getDittoCustomPdl } returns pdl
            every { repo.setDittoBleLinkWarning(any()) } answers { warnings += firstArg<BleLinkWarning>() }
            coEvery { repo.storeDittoBleRecoveryState(any()) } answers { stored += firstArg<String?>() }
            every { repo.dittoBleRecoveryActions } returns actions
            every { repo.getEnableDittoService } returns master
            every { ditto.presence } returns presence
            every { presence.graph } returns graph()
            every { presence.observe(capture(callback)) } returns observer
            every { ditto.transportConfig } answers { config }
            every { ditto.transportConfig = any() } answers {
                config = firstArg()
                changes += config.peerToPeer.bluetoothLe.enabled
            }
            session = DittoBleDiscoverySession(application, repo, ditto, scope,
                combine(master, companion, reconfiguring) { enabled, connected, changing ->
                    enabled && !connected && !changing
                }, elapsedClock = { scope.testScheduler.currentTime },
                radioProbe = { radio }, jitter = { 0.5 }, persistedRecovery = persisted, bootCount = { boot },
                peersChanged = { livePeers += it }, statusChanged = { liveRecovery += it })
            session.start()
        }

        @Test fun liveStatusUsesExistingObserverAndObservingNeverRequestsRecovery() = runTest {
            Fixture(this, persisted = "TIMED_OUT").use { f ->
                runCurrent()
                assertEquals(DittoRecoveryStatus.TIMED_OUT, f.liveRecovery.last())
                assertEquals(DittoDirectPeers(0, 0, 0, 0, 0), f.livePeers.last())
                val before = f.changes.toList()
                repeat(3) { assertEquals(DittoRecoveryStatus.TIMED_OUT, f.liveRecovery.last()) }
                assertEquals(before, f.changes)
                verify(exactly = 1) { f.presence.observe(any<(DittoPresenceGraph) -> Unit>()) }
                f.callback.captured(graph(listOf(connection("ble", "watch", "phone", DittoConnectionType.Bluetooth))))
                runCurrent()
                assertEquals(DittoDirectPeers(1, 1, 0, 0, 0), f.livePeers.last())
                assertEquals(DittoRecoveryStatus.ACTIVE, f.liveRecovery.last())
                f.callback.captured(graph())
                runCurrent()
                assertEquals(DittoDirectPeers(0, 0, 0, 0, 0), f.livePeers.last())
                f.adapter(BluetoothAdapter.STATE_OFF)
                runCurrent()
                assertEquals(DittoRecoveryStatus.INELIGIBLE, f.liveRecovery.last())
            }
        }

        @Test fun diagnosticsPublishAtCaptureTimeNotFromQueuedPreGateDiscoveryGraphs() = runTest {
            Fixture(this).use { f ->
                runCurrent()
                f.callback.captured(graph(listOf(connection("ble", "watch", "phone", DittoConnectionType.Bluetooth))))
                assertEquals(DittoDirectPeers(1, 1, 0, 0, 0), f.livePeers.last())
                f.callback.captured(graph())
                assertEquals(DittoDirectPeers(0, 0, 0, 0, 0), f.livePeers.last())
                val captures = f.livePeers.size
                runCurrent()
                assertEquals("draining the discovery queue cannot republish an older snapshot", captures, f.livePeers.size)
            }
        }

        fun adapter(state: Int) {
            val receiver = slot<BroadcastReceiver>()
            verify { application.registerReceiver(capture(receiver), any(), any<Int>()) }
            receiver.captured.onReceive(application, Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, state))
        }

        override fun close() = session.close()
    }

    @Test fun idleDisablesOnlyBleAndNeverRestartsSyncOrChangesOtherTransports() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            assertTrue(f.config.peerToPeer.bluetoothLe.enabled)
            assertFalse(f.original.peerToPeer.bluetoothLe.enabled)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            assertEquals(f.original.peerToPeer.lan, f.config.peerToPeer.lan)
            assertEquals(f.original.peerToPeer.wifiAware, f.config.peerToPeer.wifiAware)
            assertEquals(f.original.connect, f.config.connect)
            assertEquals(f.original.listen, f.config.listen)
            assertEquals(f.original.global, f.config.global)
            advanceTimeBy(24 * 60 * 60 * 1_000L)
            runCurrent()
            // Bounded retry windows only, then a hard stop at the 30 min no-link budget.
            assertTrue(f.changes.zipWithNext().all { (a, b) -> a != b })
            assertEquals(28, f.changes.size)
            assertFalse(f.config.peerToPeer.bluetoothLe.enabled)
            assertEquals(listOf(BleLinkWarning.DISCONNECTED), f.warnings)
            assertEquals("TIMED_OUT", f.stored.last())
            assertEquals(f.original.peerToPeer.lan, f.config.peerToPeer.lan)
            assertEquals(f.original.connect, f.config.connect)
            verify(exactly = 0) { f.ditto.startSync() }
            verify(exactly = 0) { f.ditto.stopSync() }
        }
    }

    @Test fun resumedUiWristOnAndActualSettingsChangesReopenDiscovery() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val lifecycle = slot<Application.ActivityLifecycleCallbacks>()
            verify { f.application.registerActivityLifecycleCallbacks(capture(lifecycle)) }
            lifecycle.captured.onActivityResumed(mockk<Activity>())
            runCurrent()
            assertEquals(listOf(true, false, true), f.changes)
            advanceTimeBy(30_000)
            runCurrent()
            f.wrist.value = true
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.wrist.value = false
            runCurrent()
            assertFalse(f.changes.last())
            f.session.onUiOpened()
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.appId.emit("changed")
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.appId.emit("changed")
            runCurrent()
            assertFalse(f.changes.last())
            f.token.value = "changed"
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.source.value = DittoPdlSource.CUSTOM
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.pdl.value = "changed"
            runCurrent()
            assertTrue(f.changes.last())
        }
    }

    @Test fun localBlePeersKeepTransportOnButLanAndIndirectBlePeersDoNot() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            f.callback.captured(graph(listOf(connection("local", "watch", "phone", DittoConnectionType.Bluetooth))))
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(listOf(true), f.changes)
            val lan = connection("lan", "watch", "tablet", DittoConnectionType.AccessPoint)
            val indirect = connection("indirect", "tablet", "phone", DittoConnectionType.Bluetooth)
            f.callback.captured(graph(listOf(lan, indirect)))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.callback.captured(graph(listOf(lan, indirect)))
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.callback.captured(graph(listOf(lan)))
            runCurrent()
            assertFalse(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("retry window despite topology cooldown", listOf(true, false, true), f.changes)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("LAN peer does not keep BLE on", listOf(true, false, true, false), f.changes)
        }
    }

    @Test fun masterCompanionAndReconfigurationGatesOverrideAllTriggers() = runTest {
        Fixture(this, initiallyEnabled = false).use { f ->
            runCurrent()
            f.session.onActivityResumed(mockk<Activity>())
            f.wrist.value = true
            runCurrent()
            assertTrue(f.changes.isEmpty())
            f.master.value = true
            runCurrent()
            assertEquals(listOf(true), f.changes)
            f.companion.value = true
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.callback.captured(graph(listOf(connection("ble", "watch", "phone", DittoConnectionType.Bluetooth))))
            f.session.onActivityResumed(mockk<Activity>())
            f.pdl.value = "changed"
            runCurrent()
            assertFalse(f.changes.last())
            f.callback.captured(graph())
            f.companion.value = false
            runCurrent()
            assertTrue(f.changes.last())
            f.reconfiguring.value = true
            runCurrent()
            assertFalse(f.changes.last())
            f.session.onActivityResumed(mockk<Activity>())
            runCurrent()
            assertFalse(f.changes.last())
        }
    }

    @Test fun closingUnregistersEventsAndLateCallbacksCannotEnableBle() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.close()
        verify(exactly = 1) { f.observer.close() }
        verify(exactly = 1) { f.application.unregisterActivityLifecycleCallbacks(f.session) }
        f.callback.captured(graph(listOf(connection("ble", "watch", "phone", DittoConnectionType.Bluetooth))))
        f.session.onActivityResumed(mockk<Activity>())
        f.wrist.value = true
        f.appId.emit("changed")
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.changes)
    }

    @Test fun snapshotCountsDirectBleConnectionsOnceAndIgnoresMetadataAndRemoteBle() {
        val direct = connection("direct", "phone", "watch", DittoConnectionType.Bluetooth)
        val indirect = connection("indirect", "phone", "tablet", DittoConnectionType.Bluetooth)
        val local = peer("watch", listOf(direct))
        val graph = presenceGraph(local, listOf(peer("phone", listOf(direct, indirect))))
        val snapshot = blePeerSnapshot(graph)
        assertEquals(1, snapshot.localBleConnections)
        assertEquals(2, snapshot.topology.size)
        assertEquals(snapshot, blePeerSnapshot(graph.copy(
            remotePeers = listOf(peer("renamed", listOf(direct, indirect)))
        )))
        assertEquals(snapshot, blePeerSnapshot(presenceGraph(
            peer("watch", listOf(connection("new-direct-id", "watch", "phone", DittoConnectionType.Bluetooth))),
            listOf(peer("phone", listOf(
                connection("new-direct-id", "watch", "phone", DittoConnectionType.Bluetooth),
                connection("new-indirect-id", "tablet", "phone", DittoConnectionType.Bluetooth)
            )))
        )))
    }

    @Test fun sdkRoleAndConnectionIdChangesNeverReapplyConfigOrDropAHealthyLink() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            repeat(87) { index ->
                f.callback.captured(graph(listOf(connection(
                    "physical-$index", if (index % 2 == 0) "watch" else "phone",
                    if (index % 2 == 0) "phone" else "watch", DittoConnectionType.Bluetooth
                ))))
                runCurrent()
                advanceTimeBy(36_000)
                runCurrent()
            }
            assertEquals(listOf(true), f.changes)
            verify(exactly = 0) { f.ditto.startSync() }
            verify(exactly = 0) { f.ditto.stopSync() }
        }
    }

    @Test fun idleLanConnectionIdAndRoleChurnCannotReopenDiscoveryEvenAfterCooldown() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            f.callback.captured(graph(listOf(connection(
                "first", "watch", "phone", DittoConnectionType.AccessPoint
            ))))
            advanceTimeBy(DittoBleDiscovery.WINDOW_MS)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            advanceTimeBy(DittoBleDiscovery.TOPOLOGY_BACKOFF_MS)
            repeat(87) { index ->
                f.callback.captured(graph(listOf(connection(
                    "new-physical-$index", "phone", "watch", DittoConnectionType.AccessPoint
                ))))
                runCurrent()
                advanceTimeBy(36_000)
                runCurrent()
            }
            // Only the retry ladder (starts 0, 60, 150, 270, then every 150 s) runs, until the
            // 30 min budget stops it: LAN churn neither reopens nor resets the budget.
            assertEquals(28, f.changes.size)
            assertTrue(f.changes.zipWithNext().all { (a, b) -> a != b })
            assertFalse(f.changes.last())
            assertEquals(listOf(BleLinkWarning.DISCONNECTED), f.warnings)
        }
    }

    @Test fun adapterBroadcastsAreOrderedOffCancelsAndOnReopensDuringCooldown() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.radio = false
            f.adapter(BluetoothAdapter.STATE_TURNING_OFF)
            f.adapter(BluetoothAdapter.STATE_OFF)
            runCurrent()
            advanceTimeBy(600_000)
            f.session.onUiOpened()
            f.wrist.value = true
            runCurrent()
            assertEquals("no retry or trigger while adapter is off", listOf(true, false), f.changes)
            f.radio = true
            f.adapter(BluetoothAdapter.STATE_TURNING_ON)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.adapter(BluetoothAdapter.STATE_ON)
            runCurrent()
            assertEquals(listOf(true, false, true), f.changes)
            advanceTimeBy(10_000)
            // Fast toggle: OFF then ON queued before processing must both apply, in order.
            f.adapter(BluetoothAdapter.STATE_OFF)
            f.adapter(BluetoothAdapter.STATE_ON)
            runCurrent()
            assertEquals(listOf(true, false, true, false, true), f.changes)
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue("ladder reset by adapter ON", f.changes.last())
            verify(exactly = 0) { f.ditto.stopSync() }
        }
    }

    @Test fun missingPermissionBlocksDiscoveryUntilResumeReprobesIt() = runTest {
        val f = Fixture(this, initiallyEnabled = false)
        f.radio = false
        f.close()
        val second = DittoBleDiscoverySession(f.application, f.repo, f.ditto, this,
            f.master, elapsedClock = { testScheduler.currentTime },
            radioProbe = { f.radio }, jitter = { 0.5 })
        second.start()
        f.master.value = true
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(f.changes.none { it })
        f.radio = true
        second.onActivityResumed(mockk<Activity>())
        runCurrent()
        assertTrue(f.changes.last())
        second.close()
    }

    @Test fun serviceRestartUnregistersReceiverAndOldTimersCannotTouchNewSession() = runTest {
        val f = Fixture(this)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        f.close()
        val receiver = slot<BroadcastReceiver>()
        verify { f.application.registerReceiver(capture(receiver), any(), any<Int>()) }
        verify(exactly = 1) { f.application.unregisterReceiver(receiver.captured) }
        val oldCallback = f.callback.captured
        val restarted = DittoBleDiscoverySession(f.application, f.repo, f.ditto, this,
            f.master, elapsedClock = { testScheduler.currentTime },
            radioProbe = { f.radio }, jitter = { 0.5 })
        restarted.start()
        runCurrent()
        assertEquals(listOf(true, false, true), f.changes)
        receiver.captured.onReceive(f.application, Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        oldCallback(graph(listOf(connection("ble", "watch", "phone", DittoConnectionType.Bluetooth))))
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals("stale receiver/observer must not affect restart", listOf(true, false, true), f.changes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(true, false, true, false), f.changes)
        restarted.close()
    }

    @Test fun persistedTimeoutSurvivesServiceRestartAndOnlyRetryNowReopens() = runTest {
        Fixture(this, persisted = "TIMED_OUT").use { f ->
            runCurrent()
            f.session.onActivityResumed(mockk<Activity>())
            f.session.onUiOpened()
            f.wrist.value = true
            f.adapter(BluetoothAdapter.STATE_OFF)
            f.adapter(BluetoothAdapter.STATE_ON)
            advanceTimeBy(3_600_000)
            runCurrent()
            assertTrue(f.changes.isEmpty())
            assertEquals(BleLinkWarning.DISCONNECTED, f.warnings.last())
            f.actions.emit(DataRepository.DittoBleRecoveryAction.RETRY_NOW)
            runCurrent()
            assertEquals(listOf(true), f.changes)
            assertEquals(BleLinkWarning.NONE, f.warnings.last())
            assertTrue(f.stored.last()!!.startsWith("v1;ACTIVE;0;1;"))
            verify(exactly = 0) { f.ditto.stopSync() }
        }
    }

    @Test fun corruptOrUnreadableRecordFailsClosedPausedUntilRetryNow() = runTest {
        listOf("ACTIVE;600000;1;0;7", "v2;ACTIVE;0;1;0;7", "v1;ACTIVE;x;1;0;7", BLE_BUDGET_UNREADABLE).forEach { bad ->
            Fixture(this, persisted = bad).use { f ->
                runCurrent()
                f.session.onActivityResumed(mockk<Activity>())
                f.session.onUiOpened()
                f.wrist.value = true
                f.adapter(BluetoothAdapter.STATE_OFF)
                f.adapter(BluetoothAdapter.STATE_ON)
                advanceTimeBy(3_600_000)
                runCurrent()
                assertTrue("$bad must not grant a fresh budget", f.changes.isEmpty())
                assertEquals(BleLinkWarning.PAUSED, f.warnings.last())
                assertEquals("bad record replaced by a well-formed paused one", "USER_PAUSED", f.stored.last())
                verify(exactly = 0) { f.ditto.stopSync() }
                f.actions.emit(DataRepository.DittoBleRecoveryAction.RETRY_NOW)
                runCurrent()
                assertEquals(listOf(true), f.changes)
                assertEquals(BleLinkWarning.NONE, f.warnings.last())
                assertTrue(f.stored.last()!!.startsWith("v1;ACTIVE;0;1;"))
            }
        }
    }

    @Test fun sameBootRecreationContinuesTheBudgetIncludingDowntime() = runTest {
        val first = Fixture(this)
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        first.close()
        runCurrent()
        val saved = first.stored.last()
        assertEquals("v1;ACTIVE;600000;1;600000;7", saved)
        advanceTimeBy(100_000) // service dead
        Fixture(this, persisted = saved).use { f ->
            runCurrent()
            assertEquals(listOf(true), f.changes)
            advanceTimeBy(1_800_000 - 700_000 - 1)
            runCurrent()
            assertTrue(f.warnings.none { it == BleLinkWarning.DISCONNECTED })
            advanceTimeBy(1)
            runCurrent()
            assertEquals("continuous zero-link budget ends at 30 min absolute",
                BleLinkWarning.DISCONNECTED, f.warnings.last())
            assertFalse(f.changes.last())
        }
    }

    @Test fun rebootCarriesUsedBudgetWithoutRefundAndSpentBudgetRestoresTimedOut() = runTest {
        Fixture(this, persisted = "v1;ACTIVE;600000;1;0;7", boot = 8).use { f ->
            advanceTimeBy(1_199_999)
            runCurrent()
            assertTrue(f.warnings.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(BleLinkWarning.DISCONNECTED, f.warnings.last())
        }
        Fixture(this, persisted = "v1;ACTIVE;1700000;1;0;7").use { f ->
            advanceTimeBy(DittoBleDiscovery.WINDOW_MS)
            runCurrent()
            assertTrue("spent same-boot budget never opens a window", f.changes.isEmpty())
            assertEquals(listOf(BleLinkWarning.DISCONNECTED), f.warnings)
        }
    }

    @Test fun pauseActionPersistsAndKeepsOtherTransportsAndSyncUntouched() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            f.actions.emit(DataRepository.DittoBleRecoveryAction.PAUSE)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            assertEquals("USER_PAUSED", f.stored.last())
            assertEquals(BleLinkWarning.PAUSED, f.warnings.last())
            f.session.onActivityResumed(mockk<Activity>())
            f.wrist.value = true
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            assertEquals(f.original.peerToPeer.lan, f.config.peerToPeer.lan)
            assertEquals(f.original.peerToPeer.wifiAware, f.config.peerToPeer.wifiAware)
            assertEquals(f.original.connect, f.config.connect)
            verify(exactly = 0) { f.ditto.stopSync() }
            verify(exactly = 0) { f.ditto.startSync() }
        }
    }

    @Test fun explicitDittoDisableClearsPauseAndReEnableStartsANewBudget() = runTest {
        Fixture(this, persisted = "USER_PAUSED").use { f ->
            runCurrent()
            assertTrue(f.changes.isEmpty())
            f.master.value = false
            runCurrent()
            assertEquals(BleLinkWarning.NONE, f.warnings.last())
            assertEquals(null, f.stored.last())
            f.master.value = true
            runCurrent()
            assertEquals(listOf(true), f.changes)
        }
    }

    companion object {
        private fun connection(id: String, first: String, second: String, type: DittoConnectionType) =
            DittoConnection::class.java.declaredConstructors.single { it.parameterCount == 7 }.newInstance(
                id, byteArrayOf(), byteArrayOf(), first, second, type, null
            ) as DittoConnection

        // SDK snapshot constructors are Kotlin-internal; create real field-backed fixtures.
        private fun peer(key: String, connections: List<DittoConnection>) =
            DittoPeer::class.java.declaredConstructors.single { it.parameterCount == 12 }.newInstance(
                mockk<DittoAddress>(), connections, "Test", "4.14.6", true, false,
                emptyMap<String, Any?>(), "Android", byteArrayOf(), key, emptyMap<String, Any?>(), 0
            ) as DittoPeer

        private fun presenceGraph(local: DittoPeer, remote: List<DittoPeer>) =
            DittoPresenceGraph::class.java.getDeclaredConstructor(DittoPeer::class.java, List::class.java)
                .newInstance(local, remote)

        private fun graph(connections: List<DittoConnection> = emptyList()) =
            presenceGraph(peer("watch", connections), emptyList())
    }
}
