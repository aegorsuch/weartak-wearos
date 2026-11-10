/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service.openvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVpnProfileSupportTest {
    @Test
    fun externalCredentialFileIsRemovedAndReplacedWithPromptRequirement() {
        val profile = OpenVpnProfileSupport.parse(
            "mission.ovpn",
            "client\r\nremote vpn.example 1194\r\nauth-user-pass secrets.txt\r\n".encodeToByteArray(),
        )

        assertEquals("mission", profile.name)
        assertTrue(profile.requiresCredentials)
        assertTrue(profile.config.contains("auth-user-pass\n"))
        assertFalse(profile.config.contains("secrets.txt"))
    }

    @Test
    fun inlineCredentialBlockRemainsEncryptedProfileContent() {
        val profile = OpenVpnProfileSupport.parse(
            "inline.ovpn",
            "client\n<auth-user-pass>\nuser\npass\n</auth-user-pass>\n".encodeToByteArray(),
        )

        assertFalse(profile.requiresCredentials)
        assertTrue(profile.config.contains("<auth-user-pass>"))
    }

    @Test
    fun credentialsAreInjectedOnlyIntoTransientNativeConfiguration() {
        val profile = OpenVpnProfileSupport.parse(
            "field.ovpn",
            "client\nauth-user-pass\nremote vpn.example 1194\n".encodeToByteArray(),
        )
        val record = OpenVpnProfileRecord(
            id = "test",
            name = profile.name,
            config = profile.config,
            requiresCredentials = profile.requiresCredentials,
        )

        val transient = OpenVpnProfileSupport.nativeConfig(record, "operator", "secret")
            .toString(Charsets.UTF_8)

        assertTrue(profile.config.contains("auth-user-pass\n"))
        assertTrue(transient.contains("<auth-user-pass>\noperator\nsecret\n</auth-user-pass>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialLineInjection() {
        val record = OpenVpnProfileRecord(
            id = "test",
            name = "test",
            config = "client\nauth-user-pass\n",
            requiresCredentials = true,
        )
        OpenVpnProfileSupport.nativeConfig(record, "operator\nremote attacker", "secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateCredentialDirectives() {
        OpenVpnProfileSupport.parse(
            "duplicate.ovpn",
            "client\nauth-user-pass\n auth-user-pass file.txt\n".encodeToByteArray(),
        )
    }

    @Test
    fun rejectsExternalCertificateFilesWithActionableMessage() {
        try {
            OpenVpnProfileSupport.parse(
                "external-ca.ovpn",
                "client\nca ca.crt\n".encodeToByteArray(),
            )
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("embed certificates and keys"))
            return
        }
        throw AssertionError("Expected external certificate reference to be rejected")
    }
}
