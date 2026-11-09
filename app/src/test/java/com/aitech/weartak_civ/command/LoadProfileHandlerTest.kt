/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 */

package com.aitech.weartak_civ.command

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadProfileHandlerTest {
    private val profileCalls = mutableListOf<String>()
    private val added = mutableListOf<String>()
    private var networkLocked = false
    private var identityLocked = false
    private val unitUpdates = mutableListOf<Map<String, String>>()
    private val mergedAlerts = mutableListOf<String>()

    private val store = object : ProfileStore {
        override suspend fun setCallsign(value: String) { profileCalls += "callsign=$value" }
        override suspend fun setTeam(value: String) { profileCalls += "team=$value" }
        override suspend fun setRole(category: String, role: String) { profileCalls += "role=$role" }
        override suspend fun setHeight(feet: Int, inches: Int) { profileCalls += "height=$feet/$inches" }
        override suspend fun setWeight(pounds: Int) { profileCalls += "weight=$pounds" }
        override suspend fun setBirthYear(year: Int) { profileCalls += "birthyear=$year" }
        override suspend fun setSex(value: String) { profileCalls += "sex=$value" }
        override suspend fun setUserType(value: String) { profileCalls += "usertype=$value" }
        override suspend fun setPantsLength(value: Int) { profileCalls += "pants=$value" }
        override suspend fun setWaistSize(value: Int) { profileCalls += "waist=$value" }
    }
    private val options = ProfileOptions(
        teams = { listOf("Dark Green") },
        roles = { listOf("MIL" to "K9") },
        sexes = { listOf("Male") },
        userTypes = { listOf("Civilian") }
    )
    private val profileHandler = ProfileHandler(
        ProfileHandler.COMMAND_KEYS.getValue("setprofile"), { identityLocked }, options, store, { 2026 }
    )

    private val presetJson = """
        {"profile":{"callsign":"ODIN-WEARTAK","team":"Dark Green","role":"K9","height":"5'8\"","weight":"150"},
         "servers":[
           {"name":"NCTAK","url":"takserver.nctak.gov","port":8089,"username":"u1","password":"hunter2"},
           {"name":"Try TAK","url":"takserver.trytak.org","port":8089,"username":"u2","password":"p2"}]}
    """.trimIndent()

    private fun run(args: Map<String, String>, json: String? = presetJson, existing: Set<String> = emptySet()): ChatCommandResult = runBlocking {
        val handler = LoadProfileHandler(
            source = { json },
            profileHandler = profileHandler,
            serverSink = { name, _, _, _, _ -> (name !in existing).also { if (it) added += name } },
            networkLocked = { networkLocked },
            unitPrefsSink = { unitUpdates += it },
            customAlertsSink = { alerts -> alerts.filter { it !in mergedAlerts }.also { mergedAlerts += it }.size }
        )
        handler.execute(ChatCommand("loadprofile", args), ChatCommandRequest("u", "me", "", "m", 0, 0, "s"))
    }

    @Test
    fun appliesProfileAndServersWithoutLeakingCredentials() {
        val r = run(mapOf("name" to "ODIN-WEARTAK"))
        assertTrue(r.message, r.ok)
        assertEquals(listOf("NCTAK", "Try TAK"), added)
        assertTrue(profileCalls.contains("team=Dark Green") && profileCalls.contains("height=5/8"))
        assertFalse(r.message.contains("hunter2") || r.message.contains("u1"))
    }

    @Test
    fun alreadyPresentServersAreNotDuplicated() {
        val r = run(mapOf("name" to "odin-weartak"), existing = setOf("NCTAK"))
        assertTrue(r.ok)
        assertEquals(listOf("Try TAK"), added)
        assertTrue(r.message.contains("1 of 2"))
    }

    @Test
    fun missingPresetAndBadNamesAreRejected() {
        assertFalse(run(mapOf("name" to "odin-weartak"), json = null).ok)
        assertFalse(run(mapOf("name" to "../secrets")).ok)
        assertFalse(run(mapOf("name" to "x", "extra" to "y")).ok)
        assertFalse(run(emptyMap()).ok)
        assertTrue(added.isEmpty() && profileCalls.isEmpty())
    }

    @Test
    fun invalidPresetChangesNothing() {
        assertFalse(run(mapOf("name" to "odin"), json = "{not json").ok)
        val badServer = """{"profile":{"team":"Dark Green"},"servers":[{"name":"X","url":"https://bad/","port":8089,"username":"u","password":"p"}]}"""
        assertFalse(run(mapOf("name" to "odin"), json = badServer).ok)
        val badProfile = """{"profile":{"team":"Nope"},"servers":[{"name":"X","url":"h.com","port":8089,"username":"u","password":"p"}]}"""
        assertFalse(run(mapOf("name" to "odin"), json = badProfile).ok)
        assertTrue(added.isEmpty() && profileCalls.isEmpty())
    }

    @Test
    fun lockedNetworkBlocksServersAndNothingIsApplied() {
        networkLocked = true
        assertFalse(run(mapOf("name" to "odin")).ok)
        assertTrue(added.isEmpty() && profileCalls.isEmpty())
    }

    @Test
    fun appliesUnitPrefsAndMergesCustomAlerts() {
        val json = """{"unitPrefs":{"rab_rng_units_pref":"0"},"customAlerts":[" Pick me up ","Help"]}"""
        val r = run(mapOf("name" to "odin"), json = json)
        assertTrue(r.message, r.ok)
        assertEquals(listOf(mapOf("rab_rng_units_pref" to "0")), unitUpdates)
        assertEquals(listOf("Pick me up", "Help"), mergedAlerts)
        assertTrue(r.message.contains("units applied") && r.message.contains("2 custom alerts added"))
    }

    @Test
    fun invalidUnitsOrAlertsChangeNothing() {
        val badUnits = """{"profile":{"team":"Dark Green"},"unitPrefs":{"rab_rng_units_pref":"7"},"customAlerts":["Help"]}"""
        assertFalse(run(mapOf("name" to "odin"), json = badUnits).ok)
        val badAlert = """{"profile":{"team":"Dark Green"},"unitPrefs":{"rab_rng_units_pref":"0"},"customAlerts":["  "]}"""
        assertFalse(run(mapOf("name" to "odin"), json = badAlert).ok)
        assertTrue(profileCalls.isEmpty() && unitUpdates.isEmpty() && mergedAlerts.isEmpty())
    }

    @Test
    fun obsoleteCalorieFieldsDoNotPreventPresetImport() {
        val json = """
            {"profile":{"callsign":"ODIN"},
             "calories":{"enabled":true,"daily":250},
             "selected_physio_metric":"key_calories",
             "key_calories":true,
             "unitPrefs":{"rab_rng_units_pref":"0"}}
        """.trimIndent()
        val result = run(mapOf("name" to "odin"), json)
        assertTrue(result.message, result.ok)
        assertEquals(listOf("callsign=ODIN"), profileCalls)
        assertEquals(listOf(mapOf("rab_rng_units_pref" to "0")), unitUpdates)
    }

    @Test
    fun presetNamesAreStrict() {
        assertEquals("odin-weartak", ProfilePresetParser.validName(" ODIN-WEARTAK "))
        assertNull(ProfilePresetParser.validName("a/b"))
        assertNull(ProfilePresetParser.validName("-x"))
        assertNull(ProfilePresetParser.validName(""))
    }
}
