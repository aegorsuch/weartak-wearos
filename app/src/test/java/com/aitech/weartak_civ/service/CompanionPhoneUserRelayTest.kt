package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.cot.CompanionGatewaySession
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionPhoneUserRelayTest {
    private val service = CompanionAppService()
    private val scheduler = TestCoroutineScheduler()
    private val job = SupervisorJob()
    private val now = System.currentTimeMillis()
    private lateinit var repo: DataRepository
    private var bonded = true
    private var ready = true

    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        repo = DataRepository(app)
        repo.legacyDataStore().edit { it.clear() }
        val device = mockk<BluetoothDevice>()
        every { device.address } returns "AA:BB"
        every { device.bondState } answers {
            if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE
        }
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        every { cap.gateway } returns CompanionGatewaySession()
        every { cap.companionAppReady } answers { MutableStateFlow(ready) }
        inject("repo\$delegate", lazyOf(repo))
        inject("capRepo\$delegate", lazyOf(cap))
        inject("serviceScope", CoroutineScope(job + StandardTestDispatcher(scheduler)))
        inject("connectedDevice", device)
        inject("notificationsEnabled", true)
        inject("gattServer", mockk<BluetoothGattServer>())
        inject("txCharacteristic", mockk<BluetoothGattCharacteristic>())
    }

    @After fun cleanup() {
        job.cancel()
    }

    @Test fun ingestAndRemovalAreSourceScopedAndNeverTouchDroppedPoints() = runBlocking {
        val network = CotMessageParser.parseUserPositionOrNull(cot("shared", now - 1))!!
        repo.applyReceivedUserEvent(network, "TAK", "tak-test")
        repo.appendDroppedMarker(DataRepository.DroppedMarker(
            DataRepository.MarkerCotType.DROP, "shared", DataRepository.MarkerType.HOSTILE,
            "Local", "", now, null, false, false
        ))

        handle("phone_user_cot", userPayload(cot("shared")))
        assertEquals("companion-phone-users", repo.receivedUserList.value.single().sourceServerId)
        handle("phone_user_remove", JSONObject().put("targetUid", "shared").put("phoneUid", "phone-self"))
        assertEquals("tak-test", repo.receivedUserList.value.single().sourceServerId)
        assertEquals("Local", repo.droppedMarkerList.value.single().title)
    }

    @Test fun stalePliAndExpiryPreserveNetworkCopyAndNewerCompanionPosition() = runBlocking {
        repo.applyReceivedUserEvent(
            CotMessageParser.parseUserPositionOrNull(cot("shared", now - 1))!!, "TAK", "tak-test"
        )
        handle("phone_user_cot", userPayload(cot("shared", stale = now + 10_000)))
        handle("phone_user_cot", userPayload(cot("shared", time = now - 2, stale = now - 1)))
        assertEquals("companion-phone-users", repo.receivedUserList.value.single().sourceServerId)
        repo.pruneCompanionUsers(now + 10_001)
        assertEquals("tak-test", repo.receivedUserList.value.single().sourceServerId)
    }

    @Test fun disconnectClearsOnlyCompanionCopyAndReconnectSnapshotCanRepopulate() {
        handle("phone_user_cot", userPayload(cot("remote")))
        invoke("advanceCompanionGeneration")
        assertTrue(repo.receivedUserList.value.isEmpty())
        handle("phone_user_cot", userPayload(cot("remote")))
        assertEquals("remote", repo.receivedUserList.value.single().uid)
    }

    @Test fun onlyReadyBondedSingleWatchAcceptsValidRemotePli() {
        bonded = false
        handle("phone_user_cot", userPayload(cot("remote")))
        assertTrue(repo.receivedUserList.value.isEmpty())
        bonded = true
        ready = false
        handle("phone_user_cot", userPayload(cot("remote")))
        assertTrue(repo.receivedUserList.value.isEmpty())
        ready = true

        listOf(
            cot("test-watch"),
            cot("phone-self"),
            cot("bad").replace("lat='38'", "lat='NaN'"),
            cot("bad").replace("lat='38'", "lat='91'"),
            cot("remote") + "<bad/>",
            cot("remote").replace("</detail>", "<uid Droid='test-watch'/></detail>"),
            cot("remote").replace("</detail>", "<uid Droid='phone-self'/></detail>"),
            cot("remote").replace("</detail>", "<emergency type='911'/></detail>"),
            cot("remote").replace("</detail>", "<biometrics><device><model>WEAROS</model></device></biometrics></detail>"),
            cot("remote").replace("</detail>", "<physiology><device><model>WEAROS</model></device></physiology></detail>")
        ).forEach { handle("phone_user_cot", userPayload(it)) }
        assertTrue(repo.receivedUserList.value.isEmpty())

        val genericMarkerPli = cot("remote")
            .replace("<detail><contact endpoint='*:-1:stcp' callsign='Remote'/></detail>", "")
        handle("phone_user_cot", userPayload(genericMarkerPli))
        assertEquals("remote", repo.receivedUserList.value.single().uid)

        repo.clearCompanionUsers()
        handle("phone_user_cot", userPayload(cot("dropped-point", type = "b-m-p-s-p-i")))
        assertTrue(repo.receivedUserList.value.isEmpty())

        val groupPli = cot("remote", type = "a-h-G-U")
            .replace(" endpoint='*:-1:stcp'", "")
            .replace("</detail>", "<__group name='Cyan' role='Team Member'/></detail>")
        handle("phone_user_cot", userPayload(groupPli))
        assertEquals("a-h-G-U", repo.receivedUserList.value.single().type)
        bonded = false
        handle("phone_user_remove", JSONObject().put("targetUid", "remote").put("phoneUid", "phone-self"))
        assertEquals(1, repo.receivedUserList.value.size)
    }

    @Test fun sourceStateIsBoundedTo512Users() {
        repeat(515) { index ->
            val event = CotMessageParser.parseCompanionUserOrNull(
                cot("user-$index"), "test-watch", "phone-self"
            )!!
            repo.receiveCompanionUserEvent(event, now + index)
        }
        assertEquals(512, repo.receivedUserList.value.size)
    }

    @Test fun phoneUidIsRequiredAndPhoneOrWatchIdentityCannotBeRemoved() {
        handle("phone_user_cot", JSONObject().put("cot", cot("remote")))
        assertTrue(repo.receivedUserList.value.isEmpty())
        handle("phone_user_cot", userPayload(cot("remote")))
        assertEquals(1, repo.receivedUserList.value.size)

        val watchIdentity = CotMessageParser.parseCompanionUserOrNull(
            cot("remote"), "test-watch", "phone-self"
        )!!.copy(uid = "test-watch")
        val phoneIdentity = watchIdentity.copy(uid = "phone-self")
        repo.receiveCompanionUserEvent(watchIdentity, now)
        repo.receiveCompanionUserEvent(phoneIdentity, now)
        handle("phone_user_remove", JSONObject().put("targetUid", "remote").put("phoneUid", ""))
        handle("phone_user_remove", JSONObject().put("targetUid", "test-watch").put("phoneUid", "phone-self"))
        handle("phone_user_remove", JSONObject().put("targetUid", "phone-self").put("phoneUid", "phone-self"))
        assertEquals(setOf("remote", "test-watch", "phone-self"), repo.receivedUserList.value.map { it.uid }.toSet())
    }

    private fun cot(
        uid: String,
        time: Long = now,
        stale: Long = now + 300_000,
        type: String = "a-f-G-U-C"
    ) = """<event uid='$uid' type='$type' time='${Instant.ofEpochMilli(time)}'
        start='${Instant.ofEpochMilli(time)}' stale='${Instant.ofEpochMilli(stale)}'>
        <point lat='38' lon='-77' hae='10' ce='1' le='1'/>
        <detail><contact endpoint='*:-1:stcp' callsign='Remote'/></detail></event>"""

    private fun userPayload(xml: String) = JSONObject().put("cot", xml).put("phoneUid", "phone-self")

    private fun handle(type: String, payload: JSONObject) {
        val method = CompanionAppService::class.java.getDeclaredMethod(
            "handleCompanionLine", String::class.java
        ).apply { isAccessible = true }
        method.invoke(service, JSONObject().put("msg_type", type).put("payload", payload).toString())
        scheduler.runCurrent()
    }

    private fun invoke(name: String) {
        CompanionAppService::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(service)
    }

    private fun inject(name: String, value: Any) {
        CompanionAppService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }
}
