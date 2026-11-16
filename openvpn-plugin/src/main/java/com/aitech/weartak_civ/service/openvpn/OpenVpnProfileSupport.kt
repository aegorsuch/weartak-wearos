/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service.openvpn

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class OpenVpnImportedProfile(
    val name: String,
    val config: String,
    val requiresCredentials: Boolean,
)

object OpenVpnProfileSupport {
    private val authUserPassDirective = Regex("""^\s*auth-user-pass(?:\s+[^#;]+)?\s*(?:[#;].*)?$""", RegexOption.IGNORE_CASE)
    private val externalFileDirective = Regex(
        """^\s*(?:ca|cert|key|pkcs12|tls-auth|tls-crypt|secret|crl-verify|dh|extra-certs|config|askpass)\s+\S+.*$""",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_USERNAME_LENGTH = 256
    private const val MAX_PASSWORD_LENGTH = 1024

    fun parse(name: String, bytes: ByteArray): OpenVpnImportedProfile {
        require(bytes.isNotEmpty() && bytes.size <= OpenVpnNativeBridge.MAX_PROFILE_BYTES) {
            "Profile must be between 1 byte and 256 KiB"
        }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        require('\u0000' !in text) { "Profile contains invalid text" }

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        var insideAuthBlock = false
        var requiresCredentials = false
        var authDirectiveCount = 0
        val normalized = lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.equals("<auth-user-pass>", ignoreCase = true)) {
                insideAuthBlock = true
                return@mapNotNull line
            }
            if (trimmed.equals("</auth-user-pass>", ignoreCase = true)) {
                insideAuthBlock = false
                return@mapNotNull line
            }
            if (!insideAuthBlock && externalFileDirective.matches(line)) {
                throw IllegalArgumentException("Profile references external files; embed certificates and keys first")
            }
            if (!insideAuthBlock && authUserPassDirective.matches(line)) {
                authDirectiveCount++
                requiresCredentials = true
                return@mapNotNull "auth-user-pass"
            }
            line
        }.joinToString("\n").trim() + "\n"

        require(!insideAuthBlock) { "Profile has an unterminated auth-user-pass block" }
        require(authDirectiveCount <= 1) { "Profile has duplicate auth-user-pass directives" }
        require(normalized.toByteArray(StandardCharsets.UTF_8).size <= OpenVpnNativeBridge.MAX_PROFILE_BYTES) {
            "Profile exceeds 256 KiB"
        }
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".ovpn").trim()
            .take(MAX_NAME_LENGTH)
        require(safeName.isNotBlank()) { "Profile name is empty" }
        return OpenVpnImportedProfile(safeName, normalized, requiresCredentials)
    }

    fun nativeConfig(profile: OpenVpnProfileRecord, username: String?, password: String?): ByteArray {
        if (!profile.requiresCredentials) {
            return profile.config.toByteArray(StandardCharsets.UTF_8)
        }
        val user = username.orEmpty()
        val pass = password.orEmpty()
        require(user.isNotEmpty() && user.length <= MAX_USERNAME_LENGTH) { "Enter valid OpenVPN username" }
        require(pass.isNotEmpty() && pass.length <= MAX_PASSWORD_LENGTH) { "Enter valid OpenVPN password" }
        require(user.none(::isLineBreakOrNul) && pass.none(::isLineBreakOrNul)) {
            "OpenVPN credentials contain invalid characters"
        }

        val lines = profile.config.lineSequence().map { line ->
            if (authUserPassDirective.matches(line)) {
                "<auth-user-pass>\n$user\n$pass\n</auth-user-pass>"
            } else {
                line
            }
        }.toList()
        return lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun isLineBreakOrNul(char: Char) = char == '\n' || char == '\r' || char == '\u0000'
}
