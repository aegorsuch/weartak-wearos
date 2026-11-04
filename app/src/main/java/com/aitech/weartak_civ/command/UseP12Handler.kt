package com.aitech.weartak_civ.command

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory

class ValidatedChatP12(val base64: String, val password: String, val fingerprint: String, val expiry: Long) {
    override fun toString() = "ValidatedChatP12(fingerprint=$fingerprint, expiry=$expiry)"
}

object ChatP12Validator {
    const val MAX_BYTES = 8192

    fun validate(encoded: String, password: String, now: Long = System.currentTimeMillis()): ValidatedChatP12 {
        require(encoded.length <= MAX_BYTES * 2 && password.isNotEmpty() && password.length <= 128)
        val bytes = if (encoded.length % 2 == 0 && encoded.all { it.digitToIntOrNull(16) != null }) {
            ByteArray(encoded.length / 2) { encoded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            Base64.getDecoder().decode(encoded)
        }
        return try {
            validateBytes(bytes, password, now = now)
        } finally { bytes.fill(0) }
    }

    fun validateBytes(
        bytes: ByteArray,
        password: String,
        maxBytes: Int = MAX_BYTES,
        now: Long = System.currentTimeMillis(),
        maxPasswordChars: Int = 128,
    ): ValidatedChatP12 {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes)
        require(password.isNotEmpty() && password.length <= maxPasswordChars)
        val ownedBytes = bytes.copyOf()
        try {
            val chars = password.toCharArray()
            try {
                val store = KeyStore.getInstance("PKCS12")
                ByteArrayInputStream(ownedBytes).use { store.load(it, chars) }
                val aliases = store.aliases().toList().filter { store.isKeyEntry(it) }
                require(aliases.size == 1)
                val alias = aliases.single()
                val key = store.getKey(alias, chars) as? PrivateKey ?: error("Missing client key")
                val chain = requireNotNull(store.getCertificateChain(alias))
                require(chain.isNotEmpty())
                val certificates = chain.map { it as X509Certificate }
                certificates.forEach { it.checkValidity(Date(now)) }
                certificates.zipWithNext().forEach { (certificate, issuer) ->
                    require(certificate.issuerX500Principal == issuer.subjectX500Principal && issuer.basicConstraints >= 0)
                    certificate.verify(issuer.publicKey)
                }
                val leaf = certificates.first()
                require(leaf.basicConstraints < 0)
                require(leaf.extendedKeyUsage?.let {
                    "1.3.6.1.5.5.7.3.2" in it || "2.5.29.37.0" in it
                } != false)
                require(leaf.keyUsage?.getOrNull(0) != false)
                val algorithm = when (key.algorithm) {
                    "RSA" -> "SHA256withRSA"
                    "EC" -> "SHA256withECDSA"
                    else -> error("Unsupported client key")
                }
                val proof = byteArrayOf(87, 101, 97, 114, 84, 65, 75)
                val signed = Signature.getInstance(algorithm).run { initSign(key); update(proof); sign() }
                require(Signature.getInstance(algorithm).run {
                    initVerify(leaf.publicKey); update(proof); verify(signed)
                })
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).init(store, chars)
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                return ValidatedChatP12(Base64.getEncoder().encodeToString(ownedBytes), password,
                    fingerprint, certificates.minOf { it.notAfter.time })
            } finally { chars.fill('\u0000') }
        } finally { ownedBytes.fill(0) }
    }
}

class UseP12Handler(
    private val isLocked: suspend () -> Boolean,
    private val serverExists: suspend (String) -> Boolean,
    private val install: suspend (String, ValidatedChatP12) -> Boolean
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val server = command.args["server"]?.takeIf { it.isNotBlank() }
            ?: return ChatCommandResult(false, "P12 not changed: server=<configured-id> required")
        if (command.args.keys.any { it !in setOf("p12", "server", "password") }) {
            return ChatCommandResult(false, "P12 not changed: invalid arguments")
        }
        if (isLocked()) return ChatCommandResult(false, "P12 not changed: settings locked")
        if (!serverExists(server)) return ChatCommandResult(false, "P12 not changed: unknown server ID")
        val p12 = try {
            withContext(Dispatchers.Default) {
                ChatP12Validator.validate(command.args["p12"].orEmpty(), command.args["password"] ?: "atakatak")
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { return ChatCommandResult(false, "P12 not changed: invalid certificate or password") }
        return if (install(server, p12)) {
            ChatCommandResult(true, "P12 installed: sha256=${p12.fingerprint} expiry=${Instant.ofEpochMilli(p12.expiry)}")
        } else ChatCommandResult(false, "P12 not changed: apply failed")
    }
}
