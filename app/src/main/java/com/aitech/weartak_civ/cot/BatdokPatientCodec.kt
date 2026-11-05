package com.aitech.weartak_civ.cot

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

fun interface BatdokPatientEncoder {
    fun encode(json: String): String
}

/** Independent wire codec. No vendor defaults or operational encryption parameters are bundled. */
class BatdokPatientCodec(passphrase: CharArray, salt: ByteArray) : BatdokPatientEncoder, AutoCloseable {
    private val key: ByteArray

    init {
        require(passphrase.isNotEmpty() && salt.isNotEmpty()) { "BATTAK encryption parameters required" }
        val spec = PBEKeySpec(passphrase, salt, 65_536, 256)
        key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    @Synchronized override fun encode(json: String): String {
        check(!closed) { "BATTAK codec is closed" }
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            output.toByteArray()
        }
        // Legacy receiver protocol requires ECB; this is not authenticated application encryption.
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(compressed))
    }

    private var closed = false
    @Synchronized override fun close() {
        key.fill(0)
        closed = true
    }
}
