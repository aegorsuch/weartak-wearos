package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.aitech.weartak_civ.cot.CompanionGatewaySession
import com.aitech.weartak_civ.data.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionSelfStatusServiceTest {
    private val moppLease = Any()

    @Before fun compatibleMoppPlugin() {
        MoppPluginAvailability.acquire(moppLease)
        MoppPluginAvailability.activate(moppLease)
    }

    @After fun removeMoppPlugin() { MoppPluginAvailability.release(moppLease) }

    private val input = SelfStatusInputs(SelfStatusPolicy(
        true, SavedMoppStatus(MoppStatus.LEVEL_0, null), false, false, false, false, 30, 60
    ), null, null)

    @Test fun capabilityRequiresBondedReadyTransportAndOnlyAcceptsVersionOne() {
        val service = CompanionAppService()
        val companion = mockk<CompanionAppRepository>()
        every { companion.companionAppReady } returns MutableStateFlow(true)
        every { companion.gateway } returns CompanionGatewaySession()
        setLazy(service, "capRepo", companion)
        val peer = mockk<BluetoothDevice>()
        every { peer.bondState } returns BluetoothDevice.BOND_NONE
        set(service, "connectedDevice", peer)
        set(service, "gattServer", mockk<BluetoothGattServer>())
        set(service, "txCharacteristic", mockk<BluetoothGattCharacteristic>())
        set(service, "notificationsEnabled", true)
        val sender = get(service, "selfStatus") as CompanionSelfStatus
        fun receive(version: Int) {
            CompanionAppService::class.java.getDeclaredMethod("handleCompanionLine", String::class.java).apply {
                isAccessible = true
                invoke(service, """{"msg_type":"self_status_capability","version":$version}""")
            }
        }
        val generation = (get(service, "bloodhoundConnectionGeneration") as AtomicLong).get()
        receive(1)
        assertNull(sender.sendIfDue(generation, "watch", input, 1000, 0) { true })
        every { peer.bondState } returns BluetoothDevice.BOND_BONDED
        receive(2)
        assertNull(sender.sendIfDue(generation, "watch", input, 1000, 0) { true })
        receive(1)
        assertEquals(true, sender.sendIfDue(generation, "watch", input, 1000, 0) { true })
    }

    @Test fun gatewayAdmitsOnlyCapabilityNegotiatedCurrentGenerationStatusNotOtherCommands() {
        val service = CompanionAppService()
        val companion = mockk<CompanionAppRepository>()
        val gateway = mockk<CompanionGatewaySession>()
        every { companion.gateway } returns gateway
        every { gateway.session } returns "gateway"
        setLazy(service, "capRepo", companion)
        val sender = get(service, "selfStatus") as CompanionSelfStatus
        var frame = JSONObject()
        sender.negotiate(7)
        sender.sendIfDue(7, "watch", input, 1000, 0) { frame = JSONObject(it); true }
        fun allowed(json: JSONObject, generation: Long = 7): Boolean =
            CompanionAppService::class.java.getDeclaredMethod(
                "gatewayAllowsControl", JSONObject::class.java, Long::class.javaPrimitiveType
            ).let { method -> method.isAccessible = true; method.invoke(service, json, generation) as Boolean }
        assertTrue(allowed(frame))
        assertFalse(allowed(frame, 6))
        assertFalse(allowed(JSONObject().put("msgType", "set_settings")))
        assertFalse(allowed(JSONObject().put("msgType", "self_status")))
        assertTrue(allowed(JSONObject().put("msgType", "gateway_cot").put("session", "gateway")))
        assertFalse(allowed(JSONObject().put("msgType", "gateway_cot").put("session", "old")))
        sender.reset()
        assertFalse(allowed(frame))
    }

    @Test fun productionTickQueuesOnlyNegotiatedReadyGenerationAndUnbondStopsIt() {
        val service = CompanionAppService()
        val companion = mockk<CompanionAppRepository>()
        val ready = MutableStateFlow(true)
        every { companion.companionAppReady } returns ready
        every { companion.gateway } returns CompanionGatewaySession()
        setLazy(service, "capRepo", companion)
        val peer = mockk<BluetoothDevice>()
        every { peer.bondState } returns BluetoothDevice.BOND_BONDED
        every { peer.address } returns "00:11:22:33:44:55"
        set(service, "connectedDevice", peer)
        set(service, "gattServer", mockk<BluetoothGattServer>())
        set(service, "txCharacteristic", mockk<BluetoothGattCharacteristic>())
        set(service, "notificationsEnabled", true)
        set(service, "selfStatusInputs", input)
        MainActivity.device_uid = "watch"
        val sender = get(service, "selfStatus") as CompanionSelfStatus
        val generation = get(service, "bloodhoundConnectionGeneration") as AtomicLong
        val frames = get(service, "bloodhoundFrames") as Channel<*>
        fun tick() {
            CompanionAppService::class.java.getDeclaredMethod("sendSelfStatusIfDue").apply {
                isAccessible = true; invoke(service)
            }
        }
        tick()
        assertTrue(frames.tryReceive().isFailure)
        sender.negotiate(generation.get())
        tick()
        val frame = frames.tryReceive().getOrNull()!!
        val frameGeneration = frame.javaClass.getDeclaredField("generation").apply { isAccessible = true }
        assertEquals(generation.get(), frameGeneration.getLong(frame))
        assertTrue(sender.active(generation.get()))
        fun ppe(frame: Any) = JSONObject(String(
            frame.javaClass.getDeclaredField("bytes").apply { isAccessible = true }.get(frame) as ByteArray,
            Charsets.UTF_8,
        )).getJSONObject("payload")
        val moppGeneration = frame.javaClass.getDeclaredField("moppGeneration").apply { isAccessible = true }
        assertFalse(ppe(frame).isNull("ppe"))
        MoppPluginAvailability.release(moppLease)
        assertFalse(MoppPluginAvailability.isCurrent(moppGeneration.get(frame) as Long?))
        assertTrue(MoppPluginAvailability.isCurrent(null))
        tick()
        val cleared = frames.tryReceive().getOrNull()!!
        assertTrue(ppe(cleared).isNull("ppe"))
        every { peer.bondState } returns BluetoothDevice.BOND_NONE
        tick()
        assertFalse(sender.active(generation.get()))
        assertTrue(frames.tryReceive().isFailure)
        every { peer.bondState } returns BluetoothDevice.BOND_BONDED
        generation.incrementAndGet()
        tick()
        assertTrue(frames.tryReceive().isFailure)
        sender.negotiate(generation.get())
        tick()
        assertNotNull(frames.tryReceive().getOrNull())
    }

    private fun setLazy(service: CompanionAppService, name: String, value: Any) {
        set(service, "$name\$delegate", lazy { value })
    }
    private fun set(service: CompanionAppService, name: String, value: Any) {
        CompanionAppService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
    }
    private fun get(service: CompanionAppService, name: String): Any =
        CompanionAppService::class.java.getDeclaredField(name).let { it.isAccessible = true; requireNotNull(it.get(service)) }
}
