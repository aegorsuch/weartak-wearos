/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.command

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandProcessorTest {
    private val now = 1_000_000L
    private val replies = mutableListOf<String>()
    private val audits = mutableListOf<String>()
    private var enabled = true
    private var locked = false
    private val storedCallsigns = mutableListOf<String>()
    private val prompts = mutableListOf<ServerEnrollment>()
    private val locationInputs = mutableListOf<com.aitech.weartak_civ.data.LocationInput>()
    private val enrollments = mutableListOf<List<String>>()

    private fun processor() = ChatCommandProcessor(
        isEnabled = { enabled },
        allowedCallsigns = { listOf("Alpha-1") },
        handlers = mapOf(
            "setcallsign" to SetCallsignHandler({ locked }, { storedCallsigns += it }),
            "setlocationinput" to SetLocationInputHandler({ locked }, { locationInputs += it; true }),
            "enrollserver" to EnrollServerHandler({ locked }, { prompts += it },
                { name, host, port, user, password ->
                    enrollments += listOf(name, host, port.toString(), user, password)
                    ChatCommandResult(true, "Server $name added")
                })
        ),
        reply = { _, text -> replies += text },
        audit = { audits += it },
        now = { now }
    )

    private fun request(text: String, sender: String = "Alpha-1", id: String? = "m1") =
        ChatCommandRequest("uid", sender, text, id, now, now, "srv")

    @Test
    fun ordinaryChatAndDisabledFeatureAreNotConsumed() = runBlocking {
        val p = processor()
        assertFalse(p.handle(request("hello")))
        enabled = false
        assertFalse(p.handle(request("#weartakcommandsetcallsign callsign=X")))
        assertTrue(storedCallsigns.isEmpty() && replies.isEmpty())
    }

    @Test
    fun unlistedSenderIsSwallowedSilently() = runBlocking {
        assertTrue(processor().handle(request("#weartakcommandsetcallsign callsign=X", sender = "Eve")))
        assertTrue(storedCallsigns.isEmpty() && replies.isEmpty())
    }

    @Test
    fun setCallsignAppliesForAllowedSender() = runBlocking {
        assertTrue(processor().handle(request("#weartakcommandsetcallsign callsign=ODIN-2")))
        assertEquals(listOf("ODIN-2"), storedCallsigns)
        assertEquals(listOf("Callsign set to ODIN-2"), replies)
    }

    @Test fun locationInputRequiresAllowedSenderExactValuesAndUnlockedSettingsAndDeduplicates() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandsetlocationinput input=enhancedgnss", sender = "Eve", id = "unknown"))
        assertTrue(locationInputs.isEmpty())
        assertTrue(replies.isEmpty())
        val command = request("#weartakcommandsetlocationinput input=enhancedgnss")
        p.handle(command)
        p.handle(command.copy(sourceServerId = "ditto:peer"))
        p.handle(request("#weartakcommandsetlocationinput input=gnss", id = "gnss"))
        assertEquals(listOf(com.aitech.weartak_civ.data.LocationInput.FUSED,
            com.aitech.weartak_civ.data.LocationInput.INTERNAL_GNSS), locationInputs)
        assertEquals(listOf("Location Input set to Fused Location", "Location Input set to Internal GNSS"), replies)
        for (args in listOf("input=network", "input=enhancedgnss extra=1", "", "input=fused")) {
            p.handle(request("#weartakcommandsetlocationinput $args", id = "invalid-$args"))
        }
        assertEquals(2, locationInputs.size)
        locked = true
        p.handle(request("#weartakcommandsetlocationinput input=enhancedgnss", id = "locked"))
        assertEquals(2, locationInputs.size)
        assertTrue(replies.last().contains("managed"))
    }

    @Test
    fun replayedMessageRunsOnce() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandsetcallsign callsign=ODIN-2"))
        p.handle(request("#weartakcommandsetcallsign callsign=ODIN-2"))
        assertEquals(1, storedCallsigns.size)
    }

    @Test fun sharedSenderMessageIdentityDeduplicatesAllRoutesButNotDifferentSenders() = runBlocking {
        val p = processor()
        val original = request("#weartakcommandsetcallsign callsign=ODIN-2")
        listOf("srv", "ditto:peer", "multicast:224.10.10.1:17012", "sitx:flow",
            "companion-gateway", "companion-bluetooth").forEach {
            p.handle(original.copy(sourceServerId = it))
        }
        assertEquals(1, storedCallsigns.size)
        p.handle(original.copy(senderUid = "another-sender"))
        assertEquals(2, storedCallsigns.size)
        p.handle(original.copy(messageId = null))
        assertEquals(2, storedCallsigns.size)
    }

    @Test fun credentialCommandsNeverBecomeLocalHistoryWhenDisabled() = runBlocking {
        enabled = false
        val p = processor()
        assertTrue(p.handle(request("#weartakcommandusep12 ab server=x")))
        assertTrue(p.handle(request("#weartakcommandsetpdl community")))
        assertTrue(p.handle(request("#weartakcommand usep12 ab server=x")))
        assertTrue(p.handle(request("#weartakcommand setpdl community")))
        assertTrue(p.handle(request("#weartakcommand enrollserver N,h,8089,u,p")))
        assertTrue(p.handle(request("#weartakcommandenrollserver malformed")))
        assertTrue(replies.isEmpty())
    }

    @Test
    fun invalidCallsignAndLockedSettingsAreRejected() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandsetcallsign callsign=bad;name", id = "a"))
        locked = true
        p.handle(request("#weartakcommandsetcallsign callsign=OK", id = "b"))
        assertTrue(storedCallsigns.isEmpty())
        assertEquals(2, replies.size)
    }

    @Test
    fun enrollServerPromptsAndRejectsPartialCredentials() = runBlocking {
        PendingServerEnrollment.clear()
        val p = processor()
        p.handle(request("#weartakcommandenrollserver name=\"TAK Ops\" url=cloud.tak-ops.com port=8089", id = "a"))
        assertEquals(1, prompts.size)
        assertEquals("TAK Ops", prompts[0].name)
        assertEquals("cloud.tak-ops.com", prompts[0].address)
        assertEquals(8089, prompts[0].port)

        p.handle(request("#weartakcommandenrollserver name=X url=h.com password=secret", id = "b"))
        assertEquals(1, prompts.size)
        assertFalse(replies.any { it.contains("secret") })
        assertFalse(audits.any { it.contains("secret") })
    }

    @Test fun credentialsAreAcceptedOnEveryExistingChannelWithoutEchoAndOnlyOnce() = runBlocking {
        val p = processor()
        val text = "#weartakcommand enrollserver Ops,host.example,8089,operator,fake-secret,with commas"
        for (source in listOf("srv", "multicast:224.10.10.1:17012", "ditto:peer", "sitx:flow",
            "companion-gateway", "companion-phone")) {
            val req = request(text, id = source).copy(sourceServerId = source, replyIsGroup = true)
            p.handle(req)
            p.handle(req)
        }
        assertEquals(6, enrollments.size)
        assertTrue(enrollments.all { it.last() == "fake-secret,with commas" })
        assertTrue(replies.all { it == "Server Ops added" })
        assertFalse((replies + audits).any { it.contains("fake-secret") })
        locked = true
        p.handle(request(text, id = "locked"))
        assertEquals(6, enrollments.size)
        assertTrue(replies.last().contains("locked"))
        p.handle(request(text, sender = "Eve", id = "unlisted"))
        p.handle(request(text, id = "stale").copy(sentAtMs = 1))
        assertEquals(7, replies.size)
    }

    @Test fun quotedEnrollmentCredentialsReachRegistrationUnchangedWithoutEcho() = runBlocking {
        val password = "  fake_\\secret\"!  "
        val escapedPassword = password.replace("\\", "\\\\").replace("\"", "\\\"")
        for ((index, prefix) in listOf("#weartakcommandenrollserver", "#WearTAKCommand enrollserver").withIndex()) {
            processor().handle(request(
                "$prefix name=Ops url=host.example username=alex.gorsuchait-wt password=\"$escapedPassword\"",
                id = "quoted-$index"))
        }
        assertEquals(2, enrollments.size)
        assertTrue(enrollments.all {
            it == listOf("Ops", "host.example", "8089", "alex.gorsuchait-wt", password)
        })
        assertTrue(prompts.isEmpty())
        assertEquals(listOf("Server Ops added", "Server Ops added"), replies)
        assertFalse((replies + audits).any { it.contains("fake_") })
    }

    @Test fun unquotedEnrollmentCredentialsPreservePunctuationAndBackslashes() = runBlocking {
        val password = "fake_\\secret!"
        processor().handle(request(
            "#weartakcommandenrollserver name=Ops url=host.example username=alex.gorsuchait-wt password=$password"))
        assertEquals(listOf(listOf("Ops", "host.example", "8089", "alex.gorsuchait-wt", password)), enrollments)
        assertTrue(prompts.isEmpty())
        assertFalse((replies + audits).any { it.contains("fake_") })
    }

    @Test
    fun enrollServerValidatesArguments() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandenrollserver name=X url=https://h.com", id = "a"))
        p.handle(request("#weartakcommandenrollserver name=X url=h.com port=70000", id = "b"))
        p.handle(request("#weartakcommandenrollserver url=h.com", id = "c"))
        assertTrue(prompts.isEmpty())
        assertEquals(3, replies.size)
    }

    @Test
    fun unknownAndMalformedCommandsGetReplies() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandreboot now=1", id = "a"))
        p.handle(request("#weartakcommandsetcallsign broken", id = "b"))
        assertEquals(2, replies.size)
        assertTrue(replies[0].startsWith("Unknown command"))
    }

    @Test
    fun pendingEnrollmentRequiresMatchingTokenAndIsSingleUse() {
        PendingServerEnrollment.clear()
        val e = PendingServerEnrollment.offer("N", "h.com", 8089)
        assertNull(PendingServerEnrollment.take("wrong"))
        assertNull(PendingServerEnrollment.take(null))
        assertNotNull(PendingServerEnrollment.take(e.token))
        assertNull(PendingServerEnrollment.take(e.token))
    }
}