/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandTest {
    private fun parsed(text: String) = (ChatCommandParser.parse(text) as ChatCommandParse.Parsed).command
    private fun malformed(text: String) = ChatCommandParser.parse(text) is ChatCommandParse.Malformed

    @Test
    fun ordinaryChatIsNeverACommand() {
        assertEquals(ChatCommandParse.NotACommand, ChatCommandParser.parse("hello there"))
        assertEquals(ChatCommandParse.NotACommand, ChatCommandParser.parse("see #weartakcommandsetcallsign callsign=X"))
    }

    @Test
    fun parsesNameCaseInsensitivelyAndArguments() {
        val c = parsed("  #WearTAKCommandSetCallsign Callsign=ALPHA-1 ")
        assertEquals("setcallsign", c.name)
        assertEquals(mapOf("callsign" to "ALPHA-1"), c.args)
    }

    @Test
    fun parsesQuotedValuesWithSpacesAndEscapes() {
        val c = parsed("#weartakcommandenrollserver name=\"TAK \\\"Ops\\\"\" url=cloud.tak-ops.com port=8089")
        assertEquals("TAK \"Ops\"", c.args["name"])
        assertEquals("cloud.tak-ops.com", c.args["url"])
        assertEquals("8089", c.args["port"])
    }

    @Test
    fun commandWithoutArgumentsParses() {
        assertEquals(emptyMap<String, String>(), parsed("#weartakcommandping").args)
    }

    @Test fun enrollmentPositionalOnlySplitsFirstFourCommas() {
        for (prefix in listOf("#weartakcommandenrollserver", "#WearTAKCommand enrollserver")) {
            val args = parsed("$prefix  TAK Ops , host.example , 8089 , user name ,  a,b=c,\"d\"  ").args
            assertEquals("TAK Ops", args["name"])
            assertEquals("host.example", args["url"])
            assertEquals("8089", args["port"])
            assertEquals("user name", args["username"])
            assertEquals("a,b=c,\"d\"", args["password"])
            assertEquals(setOf("name", "url", "port"), parsed("$prefix TAK,host,8089").args.keys)
            assertEquals(setOf("name", "url", "port"), parsed("$prefix TAK,host,8089, , ").args.keys)
        }
    }

    @Test fun enrollmentMissingFieldsAndBoundsAreRejectedWithoutEcho() {
        for (args in listOf("N,h", "N,h,8089,u", "N,h,8089,u,", "N,h,8089,,p", ",h,8089,u,p",
            "N,,8089,u,p", "N,h,,u,p", "N,h,8089,u,a\u0000b", "N,h,8089,u," + "x".repeat(768))) {
            val result = ChatCommandParser.parse("#weartakcommand enrollserver $args")
            assertTrue(result is ChatCommandParse.Malformed)
            assertTrue(!result.toString().contains(args))
        }
        val legacy = parsed("#weartakcommand enrollserver name=N url=host username=user password=\"a,b c\"")
        assertEquals("a,b c", legacy.args["password"])
        assertEquals(null, legacy.args["port"])
    }

    @Test
    fun onlyPdlCommandValueHasLargerBoundedLimit() {
        val value = "a".repeat(ChatCommandParser.MAX_PDL_LENGTH)
        assertEquals(value, parsed("#weartakcommandsetpdl $value").args["pdl"])
        assertEquals(mapOf("source" to "community"), parsed("#weartakcommandsetpdl community").args)
        assertTrue(malformed("#weartakcommandsetpdl ${value}a"))
        assertTrue(malformed("#weartakcommandsetpdl"))
        assertTrue(malformed("#weartakcommandsetpdl community enabled=true"))
        assertTrue(malformed("#weartakcommandsetpdl source=community"))
        assertTrue(malformed("#weartakcommandsetpdl pdl=abc"))
        assertTrue(malformed("#weartakcommandsetpdl \"abc\""))
        assertTrue(malformed("#weartakcommandsetcallsign pdl=$value"))
        assertTrue(malformed("#weartakcommandsetpdl x y"))
        assertTrue(malformed("#weartakcommandsetpdl $value ${"a".repeat(1100)}"))
        assertTrue(malformed("#weartakcommandsetdittopdl pdl=$value"))
    }

    @Test
    fun rejectsMalformedInput() {
        assertTrue(malformed("#weartakcommand"))
        assertTrue(malformed("#weartakcommand9 a=b"))
        assertTrue(malformed("#weartakcommandx novalue"))
        assertTrue(malformed("#weartakcommandx a="))
        assertTrue(malformed("#weartakcommandx a=1 a=2"))
        assertTrue(malformed("#weartakcommandx a=\"open"))
        assertTrue(malformed("#weartakcommandx a=\"q\"junk"))
        assertTrue(malformed("#weartakcommandx 1a=b"))
        assertTrue(malformed("#weartakcommandx a=${"v".repeat(129)}"))
        assertTrue(malformed("#weartakcommandx " + (1..13).joinToString(" ") { "k$it=v" }))
        assertTrue(malformed("#weartakcommandx " + "a=b ".repeat(200)))
        assertTrue(malformed("#weartakcommandx a=b\u0007c"))
    }

    @Test
    fun authorizerRequiresAllowedFreshUniqueSender() {
        val auth = ChatCommandAuthorizer(maxAgeMs = 1000)
        val allowed = listOf("Alpha-1", " BRAVO ")
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("Eve", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("alpha-1", emptyList(), "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "old", 1_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "future", 20_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "zero", 0, 10_000))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("ALPHA-1", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.DUPLICATE, auth.authorize("alpha-1", allowed, "m1", 10_000, 10_100))
        assertEquals(ChatCommandDecision.DUPLICATE, auth.authorize("bravo", allowed, "m1", 10_000, 10_100))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("bravo", allowed, "different-sender:m1", 10_000, 10_100))
    }

    @Test
    fun rejectedMessagesAreNotRememberedAsDuplicates() {
        val auth = ChatCommandAuthorizer(maxAgeMs = 1000)
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("eve", listOf("a"), "m", 10_000, 10_000))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("a", listOf("a"), "m", 10_000, 10_000))
    }

    @Test
    fun allowListParsingTrimsAndDeduplicates() {
        assertEquals(listOf("A", "b"), ChatCommandAuthorizer.parseAllowList(" A, b ,a,, "))
    }

    @Test
    fun allowListIsSortedCaseInsensitivelyAndDeduplicated() {
        assertEquals(
            listOf("Alpha", "BRAVO", "zulu"),
            ChatCommandAuthorizer.sortedAllowList(listOf("zulu", "Alpha", "BRAVO", "alpha", " bravo "))
        )
    }

    @Test
    fun newCallsignsUseTheCommandValidationRulesAndDisplayUppercase() {
        assertEquals("ODIN-1", ChatCommandAuthorizer.validatedCallsign("  odin-1  "))
        assertEquals("A B._-9", ChatCommandAuthorizer.validatedCallsign("a b._-9"))
        assertEquals("A".repeat(32), ChatCommandAuthorizer.validatedCallsign("a".repeat(32)))
        assertEquals(null, ChatCommandAuthorizer.validatedCallsign(""))
        assertEquals(null, ChatCommandAuthorizer.validatedCallsign("A".repeat(33)))
        assertEquals(null, ChatCommandAuthorizer.validatedCallsign("bad;name"))
    }

    @Test
    fun legacyCommaSeparatedCallsignsRemainAvailable() {
        val legacy = "ODIN-ATAK-S9FE,ODIN-ATAK"
        assertEquals(
            listOf("ODIN-ATAK-S9FE", "ODIN-ATAK"),
            ChatCommandAuthorizer.parseAllowList(legacy)
        )
        assertEquals(
            listOf("ODIN-ATAK", "ODIN-ATAK-S9FE"),
            ChatCommandAuthorizer.sortedAllowList(ChatCommandAuthorizer.parseAllowList(legacy))
        )
    }
}