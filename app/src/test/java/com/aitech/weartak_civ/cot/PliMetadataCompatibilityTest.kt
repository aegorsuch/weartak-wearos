package com.aitech.weartak_civ.cot

import android.app.Application
import android.os.Build
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.BatteryPowerObserver.DockType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PliMetadataCompatibilityTest {
    @Test
    fun directAndDittoIdentifyTheActualWatchAndWearTakBuild() {
        ShadowBuild.setManufacturer("Test & Watch")
        ShadowBuild.setModel("Model <A> \"One\"")
        for (xml in listOf(pli(), ditto())) {
            val root = parse(xml)
            val detail = if (root.tagName == "event") child(root, "detail") else root
            val takv = child(detail, "takv")
            assertEquals("${Build.MANUFACTURER} ${Build.MODEL}", takv.getAttribute("device"))
            assertEquals("WearTAK", takv.getAttribute("platform"))
            assertEquals(Build.VERSION.SDK_INT.toString(), takv.getAttribute("os"))
            assertEquals(BuildConfig.VERSION_NAME, takv.getAttribute("version"))
            assertEquals("72", child(detail, "status").getAttribute("battery"))
            assertEquals(0, detail.getElementsByTagName("biometrics").length)
            val device = child(child(detail, "physiology"), "device")
            assertEquals("65", child(device, "hr").textContent)
            assertEquals("97", child(device, "skt").textContent)
            assertEquals("0.25", child(device, "exert").textContent)
            assertEquals("Medic", child(detail, "__group").getAttribute("role"))
            assertEquals("Blue", child(detail, "__group").getAttribute("name"))
        }
    }

    @Test
    fun identityAndDittoAttributesRoundTripXmlMetacharactersWithoutChangingIdentity() {
        val value = "Test & <unit> \"quoted\" 'watch'"
        val snapshot = snapshot().let { it.copy(systemData = it.systemData.copy(deviceId = value)) }
        val event = parse(pli(snapshot, value, value, value, includeBatdok = true))
        assertEquals(value, event.getAttribute("uid"))
        for (detail in listOf(child(event, "detail"), parse(ditto(callsign = value, role = value, team = value, uid = value)))) {
            assertEquals(value, child(detail, "contact").getAttribute("callsign"))
            assertEquals(value, child(detail, "__group").getAttribute("role"))
            assertEquals(value, child(detail, "__group").getAttribute("name"))
            assertEquals(value, child(child(child(detail, "physiology"), "device"), "uid").textContent)
        }
        val ditto = child(parse(ditto(peer = value)), "ditto")
        assertEquals(value, ditto.getAttribute("a"))
        assertEquals(value, ditto.getAttribute("deviceName"))
        assertEquals(value, ditto.getAttribute("version"))
    }

    @Test
    fun structuredBatteryIsARealPercentageOrAbsentAndRemarksArePreserved() {
        for (battery in listOf(null, -1, 0, 1, 72, 100, 101)) {
            val snapshot = snapshot().let { it.copy(systemData = it.systemData.copy(batteryPercent = battery)) }
            val direct = detail(pli(snapshot))
            val ditto = parse(ditto(battery = battery ?: -1))
            for (detail in listOf(direct, ditto)) {
                val status = child(detail, "status")
                assertEquals(battery != null && battery in 0..100, status.hasAttribute("battery"))
                if (battery != null && battery in 0..100) {
                    assertEquals(battery.toString(), status.getAttribute("battery"))
                }
                val device = child(child(detail, "physiology"), "device")
                val biometricBattery = device.getElementsByTagName("bat")
                assertEquals(if (battery != null && battery in 0..100) 1 else 0, biometricBattery.length)
                if (biometricBattery.length == 1) assertEquals(battery.toString(), biometricBattery.item(0).textContent)
            }
            assertEquals("true", child(direct, "status").getAttribute("readiness"))
            assertEquals(REMARKS, child(direct, "remarks").textContent)
            assertEquals(REMARKS, child(ditto, "remarks").textContent)
        }
    }

    @Test
    fun defaultAndConfiguredGroupsStayImmediateDetailChildren() {
        for ((role, team) in listOf("Team Member" to "Orange", "Medic" to "Blue", "" to "")) {
            for (detail in listOf(detail(pli(role = role, team = team)), parse(ditto(role = role, team = team)))) {
                val group = child(detail, "__group")
                assertEquals(role, group.getAttribute("role"))
                assertEquals(team, group.getAttribute("name"))
            }
        }
    }

    @Test
    fun persistedDefaultAndCustomTeamColorsReachTheStandardGroup() = runBlocking {
        MainActivity.device_uid = "metadata-test-watch"
        MainActivity.versionNumber = BuildConfig.VERSION_NAME
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val originalCallsign = repo.getCallsign.first()
        val originalRole = repo.getRole.first()
        val originalTeam = repo.getTeam.first()
        val originalNames = repo.getTeamNames.first()
        val originalColors = repo.getTeamColors.first()
        try {
            repo.storeTeamNames("Orange,Blue")
            repo.storeTeamColors("Orange,Blue")
            repo.storeTeam("Orange")
            repo.storeRole("Team Member")
            val defaults = repo.snapshotSystem()
            assertEquals("Team Member", defaults.role)
            assertEquals("Orange", defaults.team)
            repo.storeCallsign("TEST WATCH")
            repo.storeRole("Medic")
            repo.storeTeamNames("Rescue & Support,Command")
            repo.storeTeamColors("Cyan,Blue")
            repo.storeTeam("Rescue & Support")
            val configured = repo.snapshotSystem()
            assertEquals("Medic", configured.role)
            assertEquals("Cyan", configured.team)
            val group = child(detail(pli(snapshot().copy(systemData = configured))), "__group")
            assertEquals("Medic", group.getAttribute("role"))
            assertEquals("Cyan", group.getAttribute("name"))
        } finally {
            repo.storeCallsign(originalCallsign)
            repo.storeRole(originalRole)
            repo.storeTeamNames(originalNames)
            repo.storeTeamColors(originalColors)
            repo.storeTeam(originalTeam)
        }
    }

    @Test
    fun legacyCompanionPliSchemaRemainsUnchangedAndHasNoGroupOrTakvMetadata() {
        val repo = CompanionAppRepository(RuntimeEnvironment.getApplication())
        val envelope = repo.buildPliEnvelopeFromSnapshot(snapshot(), 30)
        val json = JSONObject(repo.envelopeToJson(envelope))
        assertEquals("metadata-test-watch", json.getString("msgUid"))
        val payload = json.getJSONObject("payload")
        assertEquals("TEST WATCH", payload.getString("cs"))
        assertEquals(72, payload.getInt("bat"))
        for (field in listOf("role", "team", "__group", "takv", "device", "platform", "os", "version")) {
            assertFalse("Legacy PLI schema unexpectedly gained $field", payload.has(field))
        }

    }

    @Test fun capabilityAwareCompanionOmitsUntimedLegacyHeartRateOnlyAfterSelfStatus() {
        val repo = CompanionAppRepository(RuntimeEnvironment.getApplication())
        val old = JSONObject(repo.envelopeToJson(repo.buildPliEnvelopeFromSnapshot(snapshot(), 30)))
        val capable = JSONObject(repo.envelopeToJson(repo.buildPliEnvelopeFromSnapshot(
            snapshot(), 30, includeLegacyHeartRate = false)))
        assertEquals(65, old.getJSONObject("payload").getInt("hr"))
        assertFalse(capable.getJSONObject("payload").has("hr"))
        for (field in listOf("lat", "lon", "cs", "bat", "skt", "exr")) {
            assertEquals(old.getJSONObject("payload").get(field), capable.getJSONObject("payload").get(field))
        }
    }

    @Test
    fun directRoutesAndRawGatewayEnvelopePreserveTheSamePliMetadata() = runBlocking {
        val snapshot = snapshot()
        val xml = pli(snapshot)
        val sent = mutableListOf<String>()
        val dispatcher = CotEndpointDispatcher(
            sendTak = { cot, queued -> assertFalse(queued); sent += cot },
            sendSitx = { sent += it },
            sendDitto = { _, _ -> fail("Ditto publishes its separate track document for PLI") },
            sendMulticast = { fail("PLI should use the PLI multicast sender") },
            sendPliMulticast = { sent += it },
            sendCompanion = { fail("Direct mode must not use the legacy companion") },
        )
        assertTrue(dispatcher.dispatch(
            CotOutboundMessage(xml, CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot)),
            CotEndpointAvailability(tak = true, sitx = true, ditto = true, multicast = true, companion = false),
        ))
        assertEquals(List(3) { xml }, sent)
        val gateway = CompanionGatewaySession()
        gateway.negotiate(JSONObject().put("version", 1).put("session", "metadata-test"))
        assertTrue(gateway.confirm("metadata-test"))
        val envelope = JSONObject(gateway.envelope(xml))
        assertEquals(xml, envelope.getString("cot"))
        assertEquals(xml, gateway.accepts(envelope))
        val detail = detail(envelope.getString("cot"))
        assertEquals("WearTAK", child(detail, "takv").getAttribute("platform"))
        assertEquals("Medic", child(detail, "__group").getAttribute("role"))
        assertEquals("72", child(detail, "status").getAttribute("battery"))
    }

    private fun pli(
        snapshot: DataRepository.PliSnapshot = snapshot(),
        callsign: String = "TEST WATCH",
        role: String = snapshot.systemData.role,
        team: String = snapshot.systemData.team,
        includeBatdok: Boolean = false,
    ) = CotMessageBuilder.pli(snapshot, CotMessageBuilder.PliOptions(callsign, role, team, includeBatdok, ""))

    private fun ditto(
        battery: Int = 72,
        callsign: String = "TEST WATCH",
        role: String = "Medic",
        team: String = "Blue",
        uid: String = "metadata-test-watch",
        peer: String = "synthetic-peer",
    ) = CotDetailBuilder.dittoTrack(
        monitoredPhysio = snapshot().physioData.data, battery = battery, callsign = callsign, role = role, team = team, uid = uid,
        course = 0f, speed = 0f,
        dittoA = peer, dittoVersion = peer, dittoDeviceName = peer,
        remarks = REMARKS,
    )

    @Test fun companionPliNeverInventsCoordinatesForMissingFix() {
        val repo = CompanionAppRepository(RuntimeEnvironment.getApplication())
        for (location in listOf(null, snapshot().loc!!.copy(timeMillis = 0),
            snapshot().loc!!.copy(lat = Double.NaN))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                repo.buildPliEnvelopeFromSnapshot(snapshot().copy(loc = location), 30)
            }
        }
    }

    private fun snapshot() = DataRepository.PliSnapshot(
        loc = DataRepository.LocationSnapshot(1, 0.0, 0.0, 0.0, 5f, 5f, 0f, 0f),
        time = CotEventTime(0, 0, 0, START, START, "2026-10-22T12:02:00.000Z"),
        physioData = DataRepository.PhysioSnapshot(0, DataRepository.PhysioData(65, 97f, 30, 70, 60, .25, null, null)),
        systemData = DataRepository.SystemSnapshot(
            0, 72, "TEST WATCH", "metadata-test-watch", "synthetic-snapshot-version", 30,
            DataRepository.SystemStates(0, DockType.NONE, false, true, 30, 30), role = "Medic", team = "Blue",
        ),
        remarks = REMARKS,
    ).let { it.copy(monitoredPhysio = it.physioData.data) }

    private fun parse(xml: String): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(InputSource(StringReader(xml))).documentElement

    private fun detail(xml: String) = child(parse(xml), "detail")

    private fun child(parent: Element, name: String): Element {
        val children = (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == name }
        assertEquals("Expected one immediate $name child of ${parent.tagName}", 1, children.size)
        return children.single()
    }

    private companion object {
        const val REMARKS = "MOPP0; HR: 65 bpm @ 12:00:00Z; Skin: 97.0F @ 12:00:00Z; Exert: 25% @ 12:00:00Z"
        const val START = "2026-10-22T12:00:00.000Z"
    }
}
