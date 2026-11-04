package com.aitech.weartak_civ.presentation

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.integration.plugin.HostPkcs12ImportControl
import com.aitech.weartak_civ.integration.plugin.HostServerPackageControl
import com.weartak.plugin.sdk.serverpackage.*
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageStageRequestParcel
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12StageRequestParcel
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.util.UUID
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Separate application ID/Application, synthetic bytes and memory backend; never starts TAK. */
class NearbyImportFixtureActivity : ComponentActivity() {
    private var control: HostPkcs12ImportControl? = null
    private var packages: HostServerPackageControl? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearTAKCIVTheme {
                var handle by remember { mutableStateOf<String?>(null) }
                var session by remember { mutableStateOf<HostPkcs12ImportControl?>(null) }
                var export by remember { mutableStateOf(false) }
                val backend = remember { FixtureBackend() }
                val pluginReady by FixturePluginBridgeService.ready.collectAsState()
                var launchPlugin by remember { mutableStateOf(false) }
                LaunchedEffect(pluginReady, launchPlugin) {
                    if (pluginReady && launchPlugin) {
                        launchPlugin = false
                        FixturePluginBridgeService.control?.allowFromWearerLaunch()
                        startActivity(android.content.Intent().setComponent(android.content.ComponentName(
                            "com.weartak.plugin.nearbydrop", "com.weartak.plugin.nearbydrop.MainActivity")))
                    }
                }
                if (handle == null) {
                    WearTAKPageWithBackArrow("Offline fixture", {
                        item { Text("Synthetic data only") }
                        item { ImportActionText("Open Nearby plugin", true) {
                            launchPlugin = true
                            startForegroundService(android.content.Intent(this@NearbyImportFixtureActivity,
                                FixturePluginBridgeService::class.java))
                        } }
                        item { ImportActionText("Synthetic receive in plugin", pluginReady) { injectFixture() } }
                        item { ImportActionText("Client identity", true) { stage(0) } }
                        item { ImportActionText("Private CA", true) { stage(1) } }
                        item { ImportActionText("Shared server", true) { stage(2) } }
                        item { ImportActionText("Private server", true) { stage(3) } }
                        item { ImportActionText("Fresh enrollment", true) { stage(4) } }
                        item { ImportActionText("Sender export", true) { stage(5) } }
                    }, rememberNavController(), exitActivity = true, exitFunction = ::finish)
                } else {
                    val close = {
                        control?.close()
                        packages?.close()
                        handle = null
                    }
                    if (export) ServerPackageExportPage(requireNotNull(packages), requireNotNull(handle), backend, close)
                    else if (requireNotNull(session).packageMode)
                        ServerPackageReviewPage(requireNotNull(session), requireNotNull(handle), backend, close)
                    else CertificateReviewPage(requireNotNull(session), requireNotNull(handle), backend, close)
                }

                fun begin(mode: Int) {
                    control?.close()
                    packages?.close()
                    export = mode == 5
                    if (mode >= 2) {
                        val next = HostServerPackageControl(Process.myUid(), { true }, { token ->
                            val importer = requireNotNull(packages).imports
                            if (importer.claimReview(token)) { session = importer; handle = token }
                        }, { token ->
                            if (packages?.claimExport(token) == true) { handle = token }
                        })
                        packages = next
                        next.allowFromWearerLaunch()
                        if (export) next.requestExport(UUID.randomUUID().toString())
                        else {
                            val bytes = backend.packageFixture(mode)
                            val staged = next.stageServerPackage(ServerPackageStageRequestParcel(UUID.randomUUID().toString(), bytes))
                            next.requestReview(staged.handle)
                        }
                        return
                    }
                    val next = HostPkcs12ImportControl(Process.myUid(), { true }, { token ->
                        if (control?.claimReview(token) == true) { session = control; handle = token }
                    })
                    control = next
                    next.allowReviewFromWearerLaunch()
                    val staged = next.stagePkcs12(Pkcs12StageRequestParcel(UUID.randomUUID().toString(), fixturePkcs12(mode == 1)))
                    next.requestReview(staged.handle)
                }
                SideEffect { stageAction = ::begin }
            }
        }
    }

    private var stageAction: ((Int) -> Unit)? = null
    private fun injectFixture() {
        val bytes = FixturePluginBridgeService.backend.takeSyntheticReceive()
        if (bytes == null) {
            android.util.Log.e("NearbyFixture", "Prepare a synthetic export before receive")
            return
        }
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken("com.weartak.plugin.nearbydrop.debug.IFixtureReceive")
                    data.writeString("WEARTAK-NEARBY-DROP-SYNTHETIC-FIXTURE-V1")
                    data.writeByteArray(bytes)
                    check(requireNotNull(binder).transact(android.os.IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                    check(reply.readInt() == 0) { "Synthetic receive refused" }
                    FixturePluginBridgeService.control?.allowFromWearerLaunch()
                    startActivity(android.content.Intent().setComponent(android.content.ComponentName(
                        FixturePluginBridgeService.PLUGIN, "${FixturePluginBridgeService.PLUGIN}.MainActivity")))
                } catch (_: Exception) {
                    android.util.Log.e("NearbyFixture", "Synthetic receive injection failed")
                } finally { bytes.fill(0); data.recycle(); reply.recycle(); unbindService(this) }
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) { bytes.fill(0) }
        }
        if (!bindService(android.content.Intent("com.weartak.plugin.nearbydrop.debug.FIXTURE_RECEIVE")
                .setComponent(android.content.ComponentName(FixturePluginBridgeService.PLUGIN,
                    "${FixturePluginBridgeService.PLUGIN}.FixtureReceiveService")), connection, BIND_AUTO_CREATE)) {
            bytes.fill(0)
            android.util.Log.e("NearbyFixture", "Debug receive service unavailable")
        }
    }
    private fun stage(mode: Int) { stageAction?.invoke(mode) }
    override fun onDestroy() { control?.close(); packages?.close(); super.onDestroy() }

    private inner class FixtureBackend : CertificateReviewBackend, ServerExporter {
        private var server = DataRepository.TakServerItem(true, "Event fixture", "event.example", 8089,
            false, "", "", "", "", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        override suspend fun allowed() = true
        override suspend fun destinations() = listOf(server)
        override suspend fun snapshot(server: DataRepository.TakServerItem) = CertificateDestination(server.copy(), null)
        override suspend fun export(destination: CertificateDestination, identity: Boolean, credentials: Boolean): ExportedServerPackage {
            val bundle = ServerPackage("Synthetic event", "event.example", 8089, ServerPackageTrustMode.PUBLIC_CA,
                if (identity) ServerPackageIdentityMode.SHARED_IDENTITY else ServerPackageIdentityMode.RECEIVER_ENROLLMENT,
                clientPkcs12 = if (identity) fixturePkcs12(false) else null,
                clientPkcs12Password = if (identity) "fixture-only".toByteArray() else null,
                username = if (credentials) "synthetic" else null,
                password = if (credentials) "fixture-only".toByteArray() else null)
            return try { ExportedServerPackage(ServerPackageCodec.encode(bundle)) { true } } finally { bundle.wipe() }
        }
        override suspend fun apply(destination: CertificateDestination?, proposed: DataRepository.TakServerItem,
            ca: String?, authorized: () -> Boolean, complete: () -> Boolean): Boolean {
            check(authorized())
            val before = server
            server = proposed
            if (!complete()) { server = before; return false }
            return true
        }

        fun packageFixture(mode: Int): ByteArray {
            val caBytes = if (mode == 3) {
                val raw = fixturePkcs12(true)
                try {
                    val store = KeyStore.getInstance("PKCS12")
                    raw.inputStream().use { store.load(it, "fixture-only".toCharArray()) }
                    listOf(store.getCertificate("ca").encoded)
                } finally { raw.fill(0) }
            } else emptyList()
            val bundle = ServerPackage("Synthetic event", "event.example", 8089,
                if (mode == 3) ServerPackageTrustMode.PRIVATE_CA else ServerPackageTrustMode.PUBLIC_CA,
                if (mode == 4) ServerPackageIdentityMode.RECEIVER_ENROLLMENT else ServerPackageIdentityMode.SHARED_IDENTITY,
                caBytes, if (mode == 4) null else fixturePkcs12(false),
                if (mode == 4) null else "fixture-only".toByteArray(),
                "synthetic", "fixture-only".toByteArray())
            return try { ServerPackageCodec.encode(bundle) } finally { bundle.wipe() }
        }
    }

}

internal fun fixturePkcs12(trust: Boolean): ByteArray {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name(if (trust) "CN=Synthetic Event CA" else "CN=Synthetic Event Client")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 60_000),
            Date(now + 86_400_000), name, pair.public)
        if (trust) builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val cert = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
        val password = "fixture-only".toCharArray()
        return try {
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                if (trust) setCertificateEntry("ca", cert)
                else setKeyEntry("client", pair.private, password, arrayOf(cert))
            }
            ByteArrayOutputStream().use { store.store(it, password); it.toByteArray() }
        } finally { password.fill('\u0000') }
}
