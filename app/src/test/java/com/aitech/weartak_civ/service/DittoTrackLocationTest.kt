package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.MoppStatus
import com.aitech.weartak_civ.data.CompanionSelfStatus
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.cot.CotDetailBuilder
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.cot.EmergencyLocationPolicy
import com.aitech.weartak_civ.events.OutboundDittoTrackRefresh
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoTrackLocationTest {
    private val moppLease = Any()

    @Before fun compatibleMoppPlugin() {
        MoppPluginAvailability.acquire(moppLease)
        MoppPluginAvailability.activate(moppLease)
    }

    @After fun removeMoppPlugin() { MoppPluginAvailability.release(moppLease) }

    @Test fun hostSnapshotCaptureWorksWithoutRunningDittoService() = runBlocking {
        MainActivity.device_uid = "fresh-watch"
        MainActivity.callsign = "SCHEDULED"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeCallsign("FRESH")
        val priorInterval = CotDispatchService.actualReportingIntervalSecs
        CotDispatchService.actualReportingIntervalSecs = 15
        val fix = Location("fused").apply {
            latitude = 41.123456789
            longitude = -87.123456789
            altitude = 100.25
            accuracy = 4f
            time = System.currentTimeMillis() - 10_000
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try {
            assertNull(DittoTrackSnapshot.capture(repo, null, "fallback") { null })
            val fresh = requireNotNull(DittoTrackSnapshot.capture(repo, fix, "fallback") { null })
            assertEquals(fix.time, fresh.eventTime)
            assertEquals(fix.time, fresh.location.timeMillis)
            assertEquals(fix.time + 45_000, fresh.staleTime)
            assertEquals("fresh-watch", fresh.uid)
            assertEquals("FRESH", fresh.callsign)
            assertEquals(4f, fresh.location.ce!!, 0f)

            repo.storeLatestLocation(fix)
            val scheduled = requireNotNull(DittoTrackSnapshot.capture(repo, null, "fallback") { null })
            assertEquals("fallback", scheduled.uid)
            assertEquals("SCHEDULED", scheduled.callsign)
            assertEquals(fix.time, scheduled.location.timeMillis)
            assertTrue(scheduled.eventTime > fix.time)
            assertTrue(scheduled.location.ce!! > 4f)
            assertEquals(scheduled.eventTime + 45_000, scheduled.staleTime)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = priorInterval
        }
    }

    @Test fun capturedTrackDetailUsesRuntimeMetadataWithoutAddingFixTimeExtension() {
        val snapshot = DittoTrackSnapshot(
            eventTime = 20_000L,
            staleTime = 65_000L,
            location = DataRepository.LocationSnapshot(
                timeMillis = 10_000L, lat = 41.0, lon = -87.0, hae = 123.0,
                ce = 5f, le = 8f, course = 90f, speed = 3f, coordinateSource = "fused",
            ),
            uid = "watch", callsign = "A&B", role = "Medic", team = "Blue", battery = 80,
            remarks = "ready", monitoredPhysio = null, moppGeneration = 4L, intervalSeconds = 15,
        )
        val detail = snapshot.detail(CotDetailBuilder.DittoMetadata("peer-1", "watch-sdk", "1.2"))
        assertTrue(detail.contains("callsign='A&amp;B'"))
        assertTrue(detail.contains("altsrc='FUSED'"))
        assertTrue(detail.contains("<track course='90.0' speed='3.0'/>"))
        assertTrue(detail.contains("<ditto a='peer-1' ip='0.0.0.0' version='1.2' deviceName='watch-sdk'/>"))
        assertFalse(detail.contains("<weartak_location"))
        assertFalse(detail.contains("<hr>"))
    }

    @Test fun pluginPliPreservesTrackValuesAndEachOriginalFixTime() {
        val metadata = CotDetailBuilder.DittoMetadata("peer-1", "watch-sdk", "1.2")
        val fixTime = 1_700_000_000_123L
        val originalLocation = DataRepository.LocationSnapshot(
            timeMillis = fixTime, lat = 41.123456789123, lon = -87.987654321987,
            hae = 123.456789, ce = 5.123456f, le = 8.654321f,
            course = 90.75f, speed = 3.125f, coordinateSource = "fused",
        )
        for (age in listOf(0L, 2_999L, 3_000L, 3_001L, 60_000L)) {
            val eventTime = fixTime + age
            val location = requireNotNull(EmergencyLocationPolicy.forDispatch(originalLocation, eventTime))
            val snapshot = DittoTrackSnapshot(
                eventTime, eventTime + 45_000L, location,
                "watch&1", "A&B\u00e9", "Medic", "Blue", 80,
                "ready <safe>", null, 4L, 15,
            )
            val xml = snapshot.toPluginPli().xml
            val root = requireNotNull(CotMessageParser.parseDocumentOrNull(xml)).documentElement
            assertEquals("watch&1", root.getAttribute("uid"))
            assertEquals(eventTime, Instant.parse(root.getAttribute("time")).toEpochMilli())
            assertEquals(eventTime, Instant.parse(root.getAttribute("start")).toEpochMilli())
            assertEquals(eventTime + 45_000L, Instant.parse(root.getAttribute("stale")).toEpochMilli())
            val point = root.getElementsByTagName("point").item(0) as org.w3c.dom.Element
            assertEquals(location.lat, point.getAttribute("lat").toDouble(), 0.0)
            assertEquals(location.lon, point.getAttribute("lon").toDouble(), 0.0)
            assertEquals(location.hae!!, point.getAttribute("hae").toDouble(), 0.0)
            assertEquals(location.ce!!.toDouble(), point.getAttribute("ce").toDouble(), 0.0)
            assertEquals(location.le!!.toDouble(), point.getAttribute("le").toDouble(), 0.0)
            val marker = "<weartak_location timeMillis='$fixTime'/>"
            val detail = snapshot.detail(null)
            assertTrue(xml.endsWith(detail.removeSuffix("</detail>") + marker + "</detail></event>"))
            assertFalse(xml.contains("<ditto "))
            assertEquals(1, root.getElementsByTagName("weartak_location").length)
            assertEquals(snapshot.detail(metadata), detail.replace(
                "</detail>",
                CotDetailBuilder.ditto(metadata) + "</detail>",
            ))
            assertTrue(detail.contains("callsign='A&amp;B\u00e9'"))
            assertTrue(detail.contains("<track course='90.75' speed='3.125'/>"))
            if (age == 60_000L) assertTrue(location.ce!! > originalLocation.ce!!)
        }
    }

    @Test fun pluginPliRejectsOversizeAndMalformedPayloads() {
        val snapshot = DittoTrackSnapshot(
            eventTime = 20_000L, staleTime = 65_000L,
            location = DataRepository.LocationSnapshot(timeMillis = 20_000L, lat = 41.0, lon = -87.0,
                hae = null, ce = Float.NaN, le = null, course = null, speed = null),
            uid = "watch", callsign = "A", role = "Medic", team = "Blue", battery = 80,
            remarks = "", monitoredPhysio = null, moppGeneration = null, intervalSeconds = 15,
        )
        val point = requireNotNull(CotMessageParser.parseDocumentOrNull(snapshot.toPluginPli().xml))
            .getElementsByTagName("point").item(0) as org.w3c.dom.Element
        assertEquals(0.0, point.getAttribute("hae").toDouble(), 0.0)
        assertEquals(9999999.0, point.getAttribute("ce").toDouble(), 0.0)
        assertEquals(9999999.0, point.getAttribute("le").toDouble(), 0.0)
        val remaining = 32_700 - snapshot.toPluginPli().xml.toByteArray(Charsets.UTF_8).size
        assertEquals(32_700, snapshot.copy(remarks = "x".repeat(remaining)).toPluginPli()
            .xml.toByteArray(Charsets.UTF_8).size)
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(remarks = "x".repeat(remaining) + "\u00e9").toPluginPli()
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(remarks = "\u0001").toPluginPli()
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(callsign = "A\tB").toPluginPli()
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(location = snapshot.location.copy(speed = Float.POSITIVE_INFINITY)).toPluginPli()
        }
    }

    @Test fun readinessRefreshUsesActualFixCurrentIdentityAndPrivacyWithoutLegacyCacheReplay() = runBlocking {
        MainActivity.device_uid = "fresh-watch"
        MainActivity.callsign = "OLD"
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val repo = spyk(DataRepository(app))
        repo.setCompanionAppConnected()
        repo.setCompanionAppDisconnected()
        repo.resumeWatchTransportsImmediately()
        val previousReportingInterval = CotDispatchService.actualReportingIntervalSecs
        CotDispatchService.actualReportingIntervalSecs = 15
        repo.storeEnableLocationService(true)
        repo.storeEnableDittoService(true)
        repo.storeCallsign("CURRENT")
        repo.storeTeam("Blue")
        repo.storeRole("Medic")
        repo.storeEnablePhysiologicalServices(false)
        repo.storeLatestHeartRate(180, System.currentTimeMillis())
        repo.storeEnableMopp(true)
        repo.storeMoppStatus(MoppStatus.LEVEL_3)
        val service = spyk(Robolectric.buildService(DittoService::class.java).get())
        fun field(name: String) = DittoService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("dataRepository").set(service, repo)
        field("dittoVersion").set(service, "test")
        field("dittoDeviceName").set(service, "Configured SDK watch")
        @Suppress("UNCHECKED_CAST")
        val startup = field("startupResult").get(null) as MutableStateFlow<Boolean?>
        val previous = startup.value
        val scope = field("ioScope").get(service) as CoroutineScope
        val documents = mutableListOf<Map<String, Any?>>()
        every { service.writeDocument("track", any()) } answers { documents += secondArg<Map<String, Any?>>() }
        DittoService.localPeerKeyString = "test-peer"
        val fix = Location("fused").apply {
            latitude = 41.0; longitude = -87.0; altitude = 123.0; accuracy = 5f
            time = System.currentTimeMillis() - 1000
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        suspend fun refresh() {
            service.onOutboundTrackRefresh(OutboundDittoTrackRefresh(fix))
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }
        try {
            startup.value = false
            repo.setEndpointConnected(DataRepository.Endpoint.DITTO)
            refresh()
            assertTrue(documents.isEmpty())
            startup.value = true
            refresh()
            val document = documents.single()
            assertEquals(fix.time + 45_000L, document["o"])
            val pairedPli = repo.snapshotPli()
            assertEquals(45_000L, pairedPli.time.stale - pairedPli.time.time)
            assertEquals(fix.time, document["b"])
            assertEquals(fix.time, document["n"])
            assertEquals("CURRENT", document["authorCs"])
            assertEquals("fresh-watch", document["_id"])
            assertEquals(41.0, document["j"] as Double, 0.0)
            assertTrue((document["r"] as String).contains("role='Medic'"))
            assertFalse((document["r"] as String).contains("180"))
            assertTrue((document["r"] as String).contains("<remarks>MOPP3</remarks>"))
            assertTrue((document["r"] as String).contains(
                "<ditto a='test-peer' ip='0.0.0.0' version='test' deviceName='Configured SDK watch'/>"))
            val decoded = DittoCotDocument.decode(document)
            val position = requireNotNull(CotMessageParser.parseUserPositionOrNull(decoded.xml))
            assertEquals("fresh-watch", position.uid)
            assertEquals("CURRENT", position.detail.markerCallsign)
            assertEquals("Blue", position.team)
            assertEquals("Medic", position.role)
            assertEquals(fix.time, position.time)
            assertEquals(fix.time + 45_000L, position.stale)
            assertEquals(fix.latitude, position.point.lat, 0.0)
            assertEquals(fix.longitude, position.point.lon, 0.0)
            assertEquals(fix.altitude, position.point.hae, 0.0)
            for (tag in listOf("hr", "skt", "exert")) assertFalse((document["r"] as String).contains("<$tag>"))
            assertEquals("MOPP3", pairedPli.remarks)
            assertNull(repo.latestLocation.value)
            repo.setCompanionAppConnected()
            refresh()
            assertEquals(1, documents.size)
            repo.setCompanionAppDisconnected()
            shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION)
            refresh()
            assertEquals(1, documents.size)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousReportingInterval
            startup.value = previous
            repo.setCompanionAppDisconnected()
            repo.clearCompanionAppReadinessHistory()
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test fun independentTrackPublisherWaitsForFixAndReusesLastKnownPosition() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.callsign = "WATCH"
        MainActivity.role = "Team Member"
        MainActivity.teamValue = "Blue"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val service = spyk(Robolectric.buildService(DittoService::class.java).get())
        fun field(name: String) = DittoService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("dataRepository").set(service, repo)
        field("dittoVersion").set(service, "test")
        field("dittoDeviceName").set(service, "Configured SDK watch")
        DittoService.localPeerKeyString = "test-peer"
        val documents = mutableListOf<Map<String, Any?>>()
        every { service.writeDocument("track", any()) } answers { documents += secondArg<Map<String, Any?>>() }
        val scope = field("ioScope").get(service) as CoroutineScope
        suspend fun tick() {
            DittoService::class.java.getDeclaredMethod("updateTrackDocument").apply { isAccessible = true }.invoke(service)
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }
        try {
            tick()
            assertTrue(documents.isEmpty())
            repo.storeLatestLocation(Location("fused"))
            tick()
            assertTrue(documents.isEmpty())
            val fix = Location("fused").apply {
                time = System.currentTimeMillis() - 60_000
                latitude = 41.879815
                longitude = -87.642469
                accuracy = 5f
            }
            repo.storeLatestLocation(fix)
            repo.storeLatestLocation(Location("fused").apply { latitude = Double.NaN })
            tick()
            assertEquals(1, documents.size)
            assertEquals(41.879815, documents.single()["j"] as Double, 0.0)
            assertEquals(-87.642469, documents.single()["l"] as Double, 0.0)
            assertTrue((documents.single()["h"] as Double) > 5.0)
            assertTrue((documents.single()["r"] as String).contains("timeMillis='${fix.time}'"))
            repo.storeEnableMopp(true)
            repo.storeEnablePhysiologicalServices(true)
            repo.storeEnableHeartRateService(true)
            repo.storeEnableSkinTempService(true)
            repo.storeLatestIsDeviceWorn(true)
            repo.storePhysiologicalMetricAge(30)
            repo.storeRestingHeartRate(60)
            val measuredAt = System.currentTimeMillis()
            repo.storeLatestHeartRate(79, measuredAt)
            repo.storeLatestSkinTemp(91.1f, measuredAt - 1000)
            for (status in MoppStatus.entries) {
                repo.storeMoppStatus(status)
                val sender = CompanionSelfStatus().apply { negotiate(1) }
                var payload = JSONObject()
                sender.sendIfDue(1, "test-watch", repo.selfStatusInputs().first(),
                    System.currentTimeMillis(), 0) {
                    payload = JSONObject(it).getJSONObject("payload"); true
                }
                assertEquals(if (status == MoppStatus.NONE) "None" else "MOPP ${status.key}",
                    payload.getJSONObject("ppe").getString("level"))
                assertEquals(79, payload.getJSONObject("physio").getInt("hr"))
                val snapshot = repo.snapshotPli()
                assertEquals(status != MoppStatus.NONE, snapshot.remarks.startsWith("MOPP${status.key}; "))
                assertTrue(snapshot.remarks.contains("HR: 79 bpm @ "))
                assertTrue(snapshot.remarks.contains("; Skin: 91.1F @ "))
                assertTrue(snapshot.remarks.contains("; Exert: "))
                val direct = CotMessageBuilder.pli(snapshot,
                    CotMessageBuilder.PliOptions("WATCH", "Team Member", "Blue", false, ""))
                assertTrue(direct.contains("<remarks>${snapshot.remarks}</remarks>"))
                val physiology = CotDetailBuilder.physiology("test-watch", snapshot.monitoredPhysio,
                    snapshot.systemData.batteryPercent)
                assertTrue(physiology.contains("<hr>79</hr><skt>91.1</skt><exert>"))
                assertTrue(direct.contains(physiology))
                tick()
                assertTrue((documents.last()["r"] as String).contains("<remarks>${snapshot.remarks}</remarks>"))
                assertTrue((documents.last()["r"] as String).contains(physiology))
            }
            val savedMopp = repo.moppPpeStore.read()
            val sender = CompanionSelfStatus().apply { negotiate(1) }
            val companionPpe = mutableListOf<JSONObject>()
            fun sendStatus(elapsed: Long) {
                sender.sendIfDue(1, "test-watch", runBlocking { repo.selfStatusInputs().first() },
                    System.currentTimeMillis(), elapsed) {
                    companionPpe += JSONObject(it).getJSONObject("payload")
                    true
                }
            }
            sendStatus(0)
            MoppPluginAvailability.release(moppLease)
            tick()
            assertFalse((documents.last()["r"] as String).contains("MOPP"))
            assertFalse(repo.snapshotPli().remarks.contains("MOPP"))
            sendStatus(1)
            assertTrue(companionPpe.last().isNull("ppe"))
            assertEquals(savedMopp.savedAtEpochMillis, repo.moppPpeStore.read().savedAtEpochMillis)
            assertEquals(savedMopp.selection, repo.moppPpeStore.read().selection)
            assertTrue(repo.getEnableMopp.first())
            MoppPluginAvailability.acquire(moppLease)
            MoppPluginAvailability.activate(moppLease)
            tick()
            assertTrue((documents.last()["r"] as String).contains("MOPP4"))
            sendStatus(2)
            assertEquals(companionPpe.first().getJSONObject("ppe").toString(),
                companionPpe.last().getJSONObject("ppe").toString())
            val now = System.currentTimeMillis()
            var snapshot = repo.snapshotPli()
            fun assertLegacy(snapshot: DataRepository.PliSnapshot, hasVitals: Boolean) {
                val legacy = LegacyPliPayloadBuilder.build(snapshot,
                    LegacyPliPayloadBuilder.Options("WATCH", "Medic", "Blue", true, true, true))
                val esri = JSONObject(legacy.esriGeoJson!!).getJSONArray("features")
                    .getJSONObject(0).getJSONObject("properties").getJSONArray("biometrics").getJSONObject(0)
                val tablet = JSONObject(legacy.tabletCommandJson!!).getJSONArray("biometrics").getJSONObject(0)
                for (biometrics in listOf(esri, tablet)) {
                    assertEquals(if (hasVitals) 79 else -1, biometrics.getInt("hr"))
                    assertEquals(if (hasVitals) 91.1 else -1.0, biometrics.getDouble("skintemp"), 0.0)
                    assertEquals(snapshot.monitoredPhysio?.exertion ?: -1.0,
                        biometrics.getDouble("exertion"), .005)
                    for (field in listOf("hribi", "hrv", "spo2")) assertEquals(-1, biometrics.getInt(field))
                    assertEquals("", biometrics.getString("spo2measuretimeutc"))
                }
            }
            assertLegacy(snapshot, true)
            for (invalidTime in listOf(now - 120_001, now + 31_000)) {
                repo.storeLatestHeartRate(79, invalidTime)
                repo.storeLatestSkinTemp(91.1f, invalidTime)
                snapshot = repo.snapshotPli()
                assertLegacy(snapshot, false)
                tick()
                val detail = documents.last()["r"] as String
                assertTrue(detail.contains(CotDetailBuilder.physiology("test-watch", snapshot.monitoredPhysio,
                    snapshot.systemData.batteryPercent)))
                for (tag in listOf("hr", "skt", "exert")) assertFalse(detail.contains("<$tag>"))
            }
            repo.storeLatestHeartRate(79, now)
            repo.storeLatestSkinTemp(91.1f, now)
            repo.storeEnableHeartRateService(false)
            snapshot = repo.snapshotPli()
            assertNull(snapshot.monitoredPhysio!!.heartRateBpm)
            assertNull(snapshot.monitoredPhysio!!.exertion)
            assertEquals(91.1f, snapshot.monitoredPhysio!!.skinTempF)
            repo.storeEnableHeartRateService(true)
            repo.storeEnableSkinTempService(false)
            snapshot = repo.snapshotPli()
            assertEquals(79, snapshot.monitoredPhysio!!.heartRateBpm)
            assertNull(snapshot.monitoredPhysio!!.skinTempF)
            repo.storeEnableSkinTempService(true)
            repo.storeLatestIsDeviceWorn(false)
            assertNull(repo.snapshotPli().monitoredPhysio)
            assertLegacy(repo.snapshotPli(), false)
            repo.storeLatestIsDeviceWorn(true)
            repo.storeEnableMopp(false)
            repo.storeEnablePhysiologicalServices(false)
            tick()
            assertTrue((documents.last()["r"] as String).contains("MOPP4"))
            MoppPluginAvailability.release(moppLease)
            tick()
            assertTrue((documents.last()["r"] as String).contains("<remarks></remarks>"))
            assertNull(repo.snapshotPli().monitoredPhysio)
            assertLegacy(repo.snapshotPli(), false)
            for (tag in listOf("hr", "skt", "exert")) assertFalse((documents.last()["r"] as String).contains("<$tag>"))
            val published = documents.size
            repo.setCompanionAppConnected()
            tick()
            assertEquals(published, documents.size)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }
}
