package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.command.ChatP12Validator
import com.aitech.weartak_civ.command.ValidatedChatP12
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import java.io.File
import java.security.MessageDigest
import java.util.Base64

internal object TakServerEndpointMigration {
    private const val PASSWORD = "atakatak"
    private const val MAX_BYTES = Pkcs12CertificateImport.MAX_BYTES

    fun cacheFileName(address: String, port: Int, identityEpoch: String?): String {
        val endpoint = TakServerEndpointIdentity.key(address, port) + (identityEpoch?.let { ":$it" } ?: "")
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray())
        return "tak_${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}.jks"
    }

    fun changed(server: TakServerItem, address: String, port: Int) =
        TakServerEndpointIdentity.key(server) != TakServerEndpointIdentity.key(address, port)

    fun prepare(
        expected: TakServerItem,
        name: String,
        address: String,
        port: Int,
        reviewedCa: String?,
        certDir: File?,
        readCache: (String) -> ByteArray,
    ): TakServerItem {
        require(changed(expected, address, port)) { "Endpoint has not changed" }
        requireNotNull(expected.serverId)
        require(name.isNotBlank() && name.length <= 128) { "Invalid server name" }
        require(port in 1..65535) { "Invalid server port" }
        okhttp3.HttpUrl.Builder().scheme("https").host(address.trim()).port(port).build()
        // Client chains are identity material, never approval for the new endpoint.
        TakServerCaTrust.decodeAll(requireNotNull(reviewedCa) { "Review the new endpoint CA first" })
        val identity = resolveIdentity(expected, certDir, readCache)
        return expected.copy(name = name, address = address.trim(), port = port,
            isP12Cert = true, p12Cert = identity.base64, p12CertPassword = identity.password,
            strictServerTrust = true)
    }

    private fun resolveIdentity(
        expected: TakServerItem,
        certDir: File?,
        readCache: (String) -> ByteArray,
    ): ValidatedChatP12 {
        if (expected.isP12Cert && expected.p12Cert.isNotBlank()) {
            require(expected.p12Cert.length <= MAX_BYTES * 2) { "Client certificate is too large" }
            return validate(Base64.getDecoder().decode(expected.p12Cert.replace("\\/", "/")
                .replace("\r", "").replace("\n", "").trim()), expected.p12CertPassword.ifEmpty { PASSWORD })
        }
        if (!expected.strictServerTrust && certDir != null) {
            val host = expected.address.trim()
            require(host.isNotEmpty() && host != "." && host != ".." &&
                '/' !in host && '\\' !in host)
            val binary = File(certDir, "$host.p12")
            val encoded = File(certDir, "$host.p12.b64")
            if (binary.exists() || encoded.exists()) {
                val passwordFile = File(certDir, "$host.pwd")
                val password = if (passwordFile.exists()) {
                    require(passwordFile.length() <= 1024) { "Client password file is too large" }
                    passwordFile.readText().trim().ifEmpty { PASSWORD }
                } else PASSWORD
                val bytes = if (binary.exists()) {
                    binary.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
                } else {
                    require(encoded.length() <= MAX_BYTES * 2) { "Client certificate is too large" }
                    Base64.getDecoder().decode(encoded.readText().replace("\\s+".toRegex(), ""))
                }
                return validate(bytes, password)
            }
        }
        return validate(readCache(cacheFileName(expected.address, expected.port, expected.identityEpoch)), PASSWORD)
    }

    private fun validate(bytes: ByteArray, password: String): ValidatedChatP12 = try {
        ChatP12Validator.validateBytes(bytes, password, MAX_BYTES, maxPasswordChars = 256)
    } finally {
        bytes.fill(0)
    }
}
