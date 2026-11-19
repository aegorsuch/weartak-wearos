package com.aitech.weartak_civ.market

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Key
import java.security.KeyFactorySpi
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.SignatureSpi
import java.security.spec.KeySpec

@RunWith(RobolectricTestRunner::class)
class MarketCatalogParserTest {
    private val hostPackage = "com.aitech.weartak_civ"

    @Test
    fun acceptsSignedCommunityReleaseAndNormalizesHashes() {
        val keys = newKeyPair()
        val catalog = parse(signedPluginCatalog(pluginRecord(), keys), keys)

        assertEquals(1, catalog.plugins.size)
        assertEquals(MarketPublisherType.COMMUNITY, catalog.plugins.single().publisherType)
        assertTrue(catalog.plugins.single().apkSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun rejectsCatalogSignedByUnexpectedKey() {
        val keys = newKeyPair()
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(pluginRecord(), keys), newKeyPair())
        }
    }

    @Test
    fun bypassesPlatformKeyFactoryEvenWhenItAdvertisesEdDsaFirst() =
        acceptsWithConflictingProvider("KeyFactory", RejectingKeyFactory::class.java.name, "Ed25519", "EdDSA")

    @Test
    fun bypassesPlatformSignatureEvenWhenItAdvertisesEdDsaFirst() =
        acceptsWithConflictingProvider("Signature", RejectingSignature::class.java.name, "Ed25519", "NONEwithEdDSA")

    private fun acceptsWithConflictingProvider(service: String, implementation: String, vararg algorithms: String) {
        val keys = newKeyPair()
        val envelope = signedPluginCatalog(pluginRecord(), keys)
        val provider = object : Provider("ConflictingMarketProvider", 1.0, "Rejects platform EdDSA lookup") {
            init {
                algorithms.forEach { put("$service.$it", implementation) }
            }
        }
        assertEquals(1, Security.insertProviderAt(provider, 1))
        try {
            assertEquals(1, parse(envelope, keys).plugins.size)
        } finally {
            Security.removeProvider(provider.name)
        }
    }

    class RejectingKeyFactory : KeyFactorySpi() {
        override fun engineGeneratePublic(keySpec: KeySpec): PublicKey =
            throw AssertionError("Platform key factory must not decode Market keys")
        override fun engineGeneratePrivate(keySpec: KeySpec): PrivateKey = throw AssertionError()
        override fun <T : KeySpec> engineGetKeySpec(key: Key, keySpec: Class<T>): T = throw AssertionError()
        override fun engineTranslateKey(key: Key): Key = throw AssertionError()
    }

    class RejectingSignature : SignatureSpi() {
        override fun engineInitVerify(publicKey: PublicKey) {
            throw AssertionError("Platform signature must not verify Market catalogs")
        }
        override fun engineInitSign(privateKey: PrivateKey) { throw AssertionError() }
        override fun engineUpdate(value: Byte) { throw AssertionError() }
        override fun engineUpdate(bytes: ByteArray, offset: Int, length: Int) { throw AssertionError() }
        override fun engineSign(): ByteArray = throw AssertionError()
        override fun engineVerify(signature: ByteArray): Boolean = throw AssertionError()
        override fun engineSetParameter(parameter: String, value: Any) { throw AssertionError() }
        override fun engineGetParameter(parameter: String): Any = throw AssertionError()
    }

    @Test
    fun rejectsCommunityEntryTargetingWearTakHost() {
        val keys = newKeyPair()
        val record = pluginRecord().apply {
            put("packageName", hostPackage)
            put("hostUpdate", true)
        }
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(record, keys), keys)
        }
    }

    @Test
    fun acceptsOfficialHostUpdateEntry() {
        val keys = newKeyPair()
        val record = pluginRecord().apply {
            put("publisherType", "official")
            put("packageName", hostPackage)
            put("hostUpdate", true)
        }
        val catalog = parse(signedPluginCatalog(record, keys), keys)

        assertEquals(hostPackage, catalog.plugins.single().packageName)
        assertTrue(catalog.plugins.single().hostUpdate)
    }

    @Test
    fun rejectsOfficialHostUpdateForAnotherApplication() {
        val keys = newKeyPair()
        val record = pluginRecord().apply {
            put("publisherType", "official")
            put("hostUpdate", true)
        }
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(record, keys), keys)
        }
    }

    @Test
    fun rejectsHttpArtifactUrlsAndDuplicatePackages() {
        val keys = newKeyPair()
        val insecure = pluginRecord().put("apkUrl", "http://plugins.example/release.apk")
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(insecure, keys), keys)
        }

        val duplicate = JSONObject()
            .put("schemaVersion", 1)
            .put("plugins", JSONArray().put(pluginRecord()).put(pluginRecord().put("id", "other")))
        assertThrows(MarketCatalogException::class.java) {
            parse(signedEnvelope(duplicate, keys), keys)
        }
    }

    @Test
    fun buildConfigurationIsDisabledWithoutBothValues() {
        assertFalse(MarketConfiguration("", "").isConfigured)
        assertFalse(MarketConfiguration("https://catalog.example/index.json", "").isConfigured)
        assertTrue(MarketConfiguration("https://catalog.example/index.json", "public-key").isConfigured)
    }

    private fun parse(envelope: ByteArray, keys: KeyPair) =
        MarketCatalogParser.parse(
            envelopeBytes = envelope,
            publicKeyBase64 = Base64.encodeToString(keys.public.encoded, Base64.NO_WRAP),
            hostPackage = hostPackage,
        )

    private fun signedPluginCatalog(payload: JSONObject, keys: KeyPair): ByteArray =
        signedEnvelope(JSONObject().put("schemaVersion", 1).put("plugins", JSONArray().put(payload)), keys)

    private fun signedEnvelope(payload: JSONObject, keys: KeyPair): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private)
        signer.update(bytes)
        return JSONObject()
            .put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun pluginRecord() = JSONObject()
        .put("id", "community-weather")
        .put("name", "Weather")
        .put("description", "Field weather plugin")
        .put("publisher", "Community Maintainers")
        .put("publisherType", "community")
        .put("packageName", "org.example.weartak.weather")
        .put("versionName", "1.2.0")
        .put("versionCode", 12)
        .put("minHostVersionCode", 5840000)
        .put("maxHostVersionCode", JSONObject.NULL)
        .put("apkUrl", "https://releases.example/weather.apk")
        .put("apkSha256", "A".repeat(64))
        .put("signerSha256", "B".repeat(64))
        .put("sourceUrl", "https://github.com/example/weather")
        .put("hostUpdate", false)
}
