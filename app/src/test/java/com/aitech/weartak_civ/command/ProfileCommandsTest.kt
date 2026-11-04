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

class ProfileCommandsTest {
    private val calls = mutableListOf<String>()
    private var locked = false

    private val store = object : ProfileStore {
        override suspend fun setCallsign(value: String) { calls += "callsign=$value" }
        override suspend fun setTeam(value: String) { calls += "team=$value" }
        override suspend fun setRole(category: String, role: String) { calls += "role=$category/$role" }
        override suspend fun setHeight(feet: Int, inches: Int) { calls += "height=$feet/$inches" }
        override suspend fun setWeight(pounds: Int) { calls += "weight=$pounds" }
        override suspend fun setBirthYear(year: Int) { calls += "birthyear=$year" }
        override suspend fun setSex(value: String) { calls += "sex=$value" }
        override suspend fun setUserType(value: String) { calls += "usertype=$value" }
        override suspend fun setPantsLength(value: Int) { calls += "pants=$value" }
        override suspend fun setWaistSize(value: Int) { calls += "waist=$value" }
    }
    private val options = ProfileOptions(
        teams = { listOf("White", "Dark Green", "Orange") },
        roles = { listOf("MIL" to "Team Member", "MIL" to "K9", "LEO" to "Detective") },
        sexes = { listOf("N/A", "Male", "Female") },
        userTypes = { listOf("Air Force", "Army", "Civilian", "Foreign National", "Marine Corps", "Navy", "None") }
    )

    private fun run(name: String, text: String): ChatCommandResult = runBlocking {
        val command = (ChatCommandParser.parse("#weartakcommand$name $text") as ChatCommandParse.Parsed).command
        val request = ChatCommandRequest("u", "me", "", "m", 0, 0, "s")
        ProfileHandler(ProfileHandler.COMMAND_KEYS.getValue(name), { locked }, options, store, { 2026 })
            .execute(command, request)
    }

    @Test
    fun setProfileAppliesEverythingTheUserAsked() {
        val r = run(
            "setprofile",
            "callsign=ODIN-WEARTAK team=\"dark green\" role=k9 height=5'8\" weight=150 pants=30 waist=30 " +
                "birthyear=1988 sex=male usertype=Civilian"
        )
        assertTrue(r.message, r.ok)
        assertEquals(
            setOf(
                "callsign=ODIN-WEARTAK", "team=Dark Green", "role=MIL/K9", "height=5/8", "weight=150",
                "pants=30", "waist=30", "birthyear=1988", "sex=Male", "usertype=Civilian"
            ),
            calls.toSet()
        )
    }

    @Test
    fun oneBadValueChangesNothing() {
        val r = run("setprofile", "team=\"Dark Green\" weight=99999")
        assertFalse(r.ok)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun unknownTeamRoleSexAndUserTypeAreRejected() {
        assertFalse(run("setteam", "team=Purple").ok)
        assertFalse(run("setrole", "role=Wizard").ok)
        assertFalse(run("setsex", "sex=Other").ok)
        assertFalse(run("setusertype", "usertype=Admin").ok)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun unitChoicesAreAcceptedAndLegacyClassificationsCannotBeNewlyAssigned() {
        options.userTypes().forEach { unit ->
            assertTrue(run("setusertype", "usertype=\"${unit.lowercase()}\"").ok)
        }
        assertEquals(options.userTypes().map { "usertype=$it" }, calls)
        calls.clear()
        listOf("Child", "Coalition Civilian", "Coalition Military", "NonCoalition Force",
            "Non-Coalition Civilian", "Non-Coalition Military", "Opposing Force Detainee").forEach {
            assertFalse(run("setprofile", "callsign=NEW usertype=\"$it\"").ok)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun perFieldCommandsOnlyTakeTheirOwnField() {
        assertFalse(run("setteam", "team=Orange weight=150").ok)
        assertTrue(run("setteam", "team=orange").ok)
        assertEquals(listOf("team=Orange"), calls)
    }

    @Test
    fun longUniformAliasesUseTheSameSingleFieldValidationAndWrites() {
        assertTrue(run("setuniformpantslengthinches", "pants=30").ok)
        assertTrue(run("setwaistsize", "waist=32").ok)
        assertEquals(listOf("pants=30", "waist=32"), calls)
        assertFalse(run("setuniformpantslengthinches", "pants=19").ok)
        assertFalse(run("setwaistsize", "waist=61").ok)
        assertFalse(run("setwaistsize", "waist=32 callsign=BAD").ok)
        assertEquals(listOf("pants=30", "waist=32"), calls)
    }

    @Test
    fun missingArgumentsAreRejected() {
        assertFalse(ProfileHandler(setOf("team"), { false }, options, store).let { h ->
            runBlocking { h.execute(ChatCommand("setteam", emptyMap()), ChatCommandRequest("u", "me", "", "m", 0, 0, "s")).ok }
        })
    }

    @Test
    fun numericRangesAreEnforced() {
        assertFalse(run("setweight", "weight=0").ok)
        assertFalse(run("setbirthyear", "birthyear=1899").ok)
        assertFalse(run("setbirthyear", "birthyear=2027").ok)
        assertFalse(run("setpants", "pants=5").ok)
        assertFalse(run("setwaist", "waist=abc").ok)
        assertTrue(run("setbirthyear", "birthyear=2026").ok)
    }

    @Test
    fun heightFormats() {
        assertEquals(5 to 8, ChatCommandValidation.height("5'8\""))
        assertEquals(5 to 8, ChatCommandValidation.height("5'8"))
        assertEquals(6 to 0, ChatCommandValidation.height("6-0"))
        assertNull(ChatCommandValidation.height("5'12"))
        assertNull(ChatCommandValidation.height("9'0"))
        assertNull(ChatCommandValidation.height("tall"))
    }

    @Test
    fun identityFieldsAreRefusedWhenManagedButMetricsAreNot() {
        locked = true
        assertFalse(run("setteam", "team=Orange").ok)
        assertFalse(run("setprofile", "weight=150 callsign=X").ok)
        assertTrue(calls.isEmpty())
        assertTrue(run("setweight", "weight=150").ok)
        assertEquals(listOf("weight=150"), calls)
    }
}