package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.command.ChatP12Validator
import com.aitech.weartak_civ.command.ValidatedChatP12
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

/** Transport-independent classifier extracted from the NFC certificate review path. */
object Pkcs12CertificateImport {
    const val MAX_BYTES = 32700

    sealed class Opened {
        class Client(val identity: ValidatedChatP12, val certificate: X509Certificate) : Opened() {
            override fun toString() = "Client"
        }
        class Trust(val certificates: List<X509Certificate>) : Opened() {
            override fun toString() = "Trust"
        }
    }

    fun open(bytes: ByteArray, password: String): Opened {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        require(password.isNotEmpty() && password.length <= 256)
        val owned = bytes.copyOf()
        val chars = password.toCharArray()
        try {
            val store = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(owned).use { store.load(it, chars) }
            val aliases = store.aliases().toList()
            if (aliases.any(store::isKeyEntry)) {
                val identity = ChatP12Validator.validateBytes(owned, password, MAX_BYTES, maxPasswordChars = 256)
                val certificate = store.getCertificate(aliases.single(store::isKeyEntry)) as? X509Certificate
                    ?: error("Missing client certificate")
                return Opened.Client(identity, certificate)
            }
            require(aliases.isNotEmpty())
            return Opened.Trust(aliases.map {
                require(store.isCertificateEntry(it))
                val certificate = store.getCertificate(it) as? X509Certificate
                    ?: error("Unsupported certificate")
                TakServerCaTrust.parse(certificate.encoded)
            }.distinctBy(TakServerCaTrust::fingerprint))
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid PKCS12 certificate or password")
        } finally {
            chars.fill('\u0000')
            owned.fill(0)
        }
    }
}
