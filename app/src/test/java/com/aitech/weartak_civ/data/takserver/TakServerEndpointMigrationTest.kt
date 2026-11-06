package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.command.ChatP12Validator
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.presentation.serverFormIsValid
import com.aitech.weartak_civ.presentation.requiresExplicitEndpointMigration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakServerEndpointMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "fixture-only"
    private val encoded by lazy { TestClientP12.p12(password) }
    private val ca by lazy { TakServerCaTrust.encode(TestClientP12.ca()) }
    private val original = TakServerItem(false, "ODIN", "10.0.0.120", 8089,
        false, "", "", "", "", "synthetic-server", "epoch-one")

    private fun prepare(
        server: TakServerItem = original,
        certDir: File? = null,
        certificate: String? = ca,
        address: String = "10.0.0.157",
        port: Int = 8089,
        cache: (String) -> ByteArray = { error("Cache must not be used") },
    ) = TakServerEndpointMigration.prepare(server, server.name, address, port, certificate, certDir, cache)

    private fun assertIdentity(result: TakServerItem, source: String = encoded, secret: String = password) {
        assertEquals(original.copy(address = "10.0.0.157", isP12Cert = true,
            p12Cert = source, p12CertPassword = secret, strictServerTrust = true), result)
        assertEquals(ChatP12Validator.validate(source, secret).fingerprint,
            ChatP12Validator.validate(result.p12Cert, result.p12CertPassword).fingerprint)
    }

    @Test fun embeddedIdentityWinsAndAllUnrelatedFieldsSurvive() {
        val saved = original.copy(isP12Cert = true, p12Cert = encoded, p12CertPassword = password,
            username = "fixture-user", password = "fixture-password")
        val result = prepare(saved)
        assertEquals(saved.copy(address = "10.0.0.157", strictServerTrust = true), result)
    }

    @Test fun binarySideloadWithPasswordPersistsExactIdentityWithoutChangingFiles() {
        val dir = folder.newFolder()
        val bytes = Base64.getDecoder().decode(encoded)
        val cert = File(dir, "${original.address}.p12").apply { writeBytes(bytes) }
        val pwd = File(dir, "${original.address}.pwd").apply { writeText("$password\n") }
        assertIdentity(prepare(certDir = dir))
        assertArrayEquals(bytes, cert.readBytes())
        assertEquals("$password\n", pwd.readText())
        assertFalse(File(dir, "10.0.0.157.p12").exists())
    }

    @Test fun base64SideloadUsesDefaultPasswordAndSurvivesProcessRestartAsEmbeddedP12() {
        val source = TestClientP12.p12("atakatak")
        val dir = folder.newFolder()
        File(dir, "${original.address}.p12.b64").writeText(source.chunked(64).joinToString("\n"))
        val result = prepare(certDir = dir)
        assertIdentity(result, source, "atakatak")
        TakServerP12Validator.validate(listOf(result))
        val movedAgain = prepare(result, address = "10.0.0.158")
        assertEquals(result.p12Cert, movedAgain.p12Cert)
        assertEquals(result.serverId, movedAgain.serverId)
    }

    @Test fun addressEditLosesSideloadExemptionButExplicitMigrationKeepsWorkingIdentity() {
        val source = TestClientP12.p12("atakatak")
        val dir = folder.newFolder()
        File(dir, "${original.address}.p12").writeBytes(Base64.getDecoder().decode(source))
        assertTrue(SideloadedClientCert.detect(dir, original.address) is SideloadedClientCert.Status.Found)
        assertEquals(SideloadedClientCert.Status.None, SideloadedClientCert.detect(dir, "10.0.0.157"))
        assertFalse(serverFormIsValid(original.name, "10.0.0.157", "8089", "", "", false))
        assertTrue(serverFormIsValid(original.name, "10.0.0.157", "8089", "", "", true))
        val migrated = prepare(certDir = dir)
        assertIdentity(migrated, source, "atakatak")
        TakServerP12Validator.validate(listOf(migrated))
    }

    @Test fun certificateOnlyEndpointEditsRequireExplicitMigrationButEnrollmentCredentialsRemainAvailable() {
        assertTrue(requiresExplicitEndpointMigration(true, false, true, "", ""))
        assertFalse(requiresExplicitEndpointMigration(true, true, true, "", ""))
        assertTrue(requiresExplicitEndpointMigration(true, false, false, "user", ""))
        assertFalse(requiresExplicitEndpointMigration(true, false, false, "user", "password"))
        assertFalse(requiresExplicitEndpointMigration(false, false, true, "", ""))
    }

    @Test fun cachedEnrollmentUsesOldEndpointPortAndEpochNotNewEndpoint() {
        val source = TestClientP12.p12("atakatak")
        val expectedFile = TakServerEndpointMigration.cacheFileName(original.address, original.port, original.identityEpoch)
        val result = prepare(cache = {
            assertEquals(expectedFile, it)
            Base64.getDecoder().decode(source)
        })
        assertIdentity(result, source, "atakatak")
        assertNotEquals(expectedFile, TakServerEndpointMigration.cacheFileName(original.address, 8090, original.identityEpoch))
        assertNotEquals(expectedFile, TakServerEndpointMigration.cacheFileName(original.address, original.port, "epoch-two"))
    }

    @Test fun strictProfileSkipsSideloadAndPortOnlyMigrationPreservesCacheIdentity() {
        val dir = folder.newFolder()
        File(dir, "${original.address}.p12").writeText("invalid")
        val source = TestClientP12.p12("atakatak")
        val result = prepare(original.copy(strictServerTrust = true), dir, address = original.address, port = 8090,
            cache = { Base64.getDecoder().decode(source) })
        assertEquals(8090, result.port)
        assertEquals(source, result.p12Cert)
    }

    @Test fun missingCaAndUnchangedNormalizedEndpointFailBeforeReadingIdentity() {
        assertThrows(IllegalArgumentException::class.java) { prepare(certificate = null) }
        assertThrows(IllegalArgumentException::class.java) { prepare(address = " 10.0.0.120 ") }
        assertThrows(IllegalArgumentException::class.java) { prepare(certificate = "invalid") }
    }

    @Test fun invalidDestinationAndNameFailBeforeReadingIdentity() {
        val unexpectedCacheRead = { _: String -> error("Invalid destination must fail before identity lookup") }
        assertThrows(IllegalArgumentException::class.java) {
            TakServerEndpointMigration.prepare(original, "ODIN", "", 8089, ca, null, unexpectedCacheRead)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TakServerEndpointMigration.prepare(original, " ", "10.0.0.157", 8089, ca, null, unexpectedCacheRead)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TakServerEndpointMigration.prepare(original, "ODIN", "10.0.0.157", 65536, ca, null, unexpectedCacheRead)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TakServerEndpointMigration.prepare(original, "x".repeat(129), "10.0.0.157", 8089, ca, null, unexpectedCacheRead)
        }
    }

    @Test fun missingUnreadableWrongPasswordAndOversizedIdentityNeverFallBackToAnotherIdentity() {
        assertThrows(java.io.FileNotFoundException::class.java) {
            prepare(cache = { throw java.io.FileNotFoundException("synthetic") })
        }
        val dir = folder.newFolder()
        val cert = File(dir, "${original.address}.p12")
        cert.writeText("invalid")
        assertThrows(Exception::class.java) { prepare(certDir = dir) }
        cert.writeBytes(Base64.getDecoder().decode(encoded))
        assertThrows(Exception::class.java) { prepare(certDir = dir) }
        cert.writeBytes(ByteArray(Pkcs12CertificateImport.MAX_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { prepare(certDir = dir) }
    }

    @Test fun expiredTrustOnlyAndMultipleKeyIdentitiesAreRefused() {
        val expired = TestClientP12.p12(password, System.currentTimeMillis() - 172_800_000)
        assertThrows(Exception::class.java) {
            prepare(original.copy(isP12Cert = true, p12Cert = expired, p12CertPassword = password))
        }
        val store = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(Base64.getDecoder().decode(encoded)), password.toCharArray())
        }
        store.setKeyEntry("second", store.getKey("client", password.toCharArray()),
            password.toCharArray(), store.getCertificateChain("client"))
        fun serialized() = ByteArrayOutputStream().use {
            store.store(it, password.toCharArray())
            Base64.getEncoder().encodeToString(it.toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepare(original.copy(isP12Cert = true, p12Cert = serialized(), p12CertPassword = password))
        }
        store.deleteEntry("client")
        store.deleteEntry("second")
        store.setCertificateEntry("trust", TestClientP12.ca())
        assertThrows(IllegalArgumentException::class.java) {
            prepare(original.copy(isP12Cert = true, p12Cert = serialized(), p12CertPassword = password))
        }
    }
}
