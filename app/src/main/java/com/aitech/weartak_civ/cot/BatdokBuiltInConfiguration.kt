package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.BuildConfig
import java.util.Base64

/** Private-build interoperability configuration is recoverable from the APK, not a secret store. */
object BatdokBuiltInConfiguration {
    fun createCodecOrNull(): BatdokPatientCodec? =
        createCodecOrNull(BuildConfig.BATTAK_INTEROP_PASSPHRASE, BuildConfig.BATTAK_INTEROP_SALT_BASE64)

    internal fun createCodecOrNull(passphrase: String, saltBase64: String): BatdokPatientCodec? {
        if (passphrase.isEmpty() && saltBase64.isEmpty()) return null
        require(passphrase.isNotEmpty() && saltBase64.isNotEmpty()) { "Incomplete BATTAK build configuration" }
        val chars = passphrase.toCharArray()
        val salt = try {
            Base64.getDecoder().decode(saltBase64)
        } catch (_: IllegalArgumentException) {
            chars.fill('\u0000')
            throw IllegalArgumentException("Invalid BATTAK build configuration")
        }
        return try {
            BatdokPatientCodec(chars, salt)
        } finally {
            chars.fill('\u0000')
            salt.fill(0)
        }
    }
}
