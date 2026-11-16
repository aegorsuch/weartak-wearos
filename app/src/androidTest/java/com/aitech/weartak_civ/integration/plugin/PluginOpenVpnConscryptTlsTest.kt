package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as C
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginOpenVpnConscryptTlsTest {
    @Test
    fun remoteProviderCarriesHostOwnedTlsAndRejectsWrongHostAndUntrustedPeer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hostContext = instrumentation.targetContext
        val testContext = instrumentation.context
        assertNotEquals(hostContext.applicationInfo.uid, testContext.applicationInfo.uid)

        val providerConnection = bindRemoteProvider(hostContext, testContext)
        val providerBinder = requireNotNull(providerConnection.binder)
        val provider = IOpenVpnRawTcpProviderV1.Stub.asInterface(providerBinder)
        val registry = PluginOpenVpnProviderRegistry()
        val providerUid = testContext.applicationInfo.uid
        val servers = mutableListOf<ServerRun>()
        try {
            val clientIdentity = newClientIdentity()
            val trustedServer = newServer(clientIdentity.certificate)
            val untrustedServer = newServer(clientIdentity.certificate)
            val trustedSocket = trustedServer.newSocket()
            val wrongHostSocket = trustedServer.newSocket()
            val untrustedSocket = untrustedServer.newSocket()
            servers += startServer(trustedSocket, clientIdentity.certificate, echo = true)
            servers += startServer(wrongHostSocket, clientIdentity.certificate, echo = false)
            servers += startServer(untrustedSocket, clientIdentity.certificate, echo = false)
            configureRemoteProvider(providerBinder, servers.map { it.port })

            assertEquals(C.PROVIDER_API_VERSION, provider.apiVersion)
            assertTrue(registry.register(C.PLUGIN_PACKAGE, providerUid, provider))

            val process = readRemoteProcess(providerBinder)
            assertNotEquals(Process.myPid(), process.pid)
            assertEquals(providerUid, process.uid)
            assertEquals(hostContext.applicationInfo.uid, process.lastCallingUid)

            val hostTrust = TakServerCaTrust.streamingTrustManager(
                host = "localhost",
                enrolled = null,
                approvedCa = TakServerCaTrust.encode(trustedServer.caCertificate),
                allowEnrolledTrust = false,
            )
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(clientIdentity.keyManagers, arrayOf(hostTrust), SecureRandom())
            }
            val wrongHostTrust = TakServerCaTrust.streamingTrustManager(
                host = "wrong.example",
                enrolled = null,
                approvedCa = TakServerCaTrust.encode(trustedServer.caCertificate),
                allowEnrolledTrust = false,
            )
            val wrongHostContext = SSLContext.getInstance("TLS").apply {
                init(clientIdentity.keyManagers, arrayOf(wrongHostTrust), SecureRandom())
            }
            val socketFactory = requireNotNull(
                registry.socketFactory(
                    pluginPackage = C.PLUGIN_PACKAGE,
                    serverId = "synthetic-server",
                    profileId = "synthetic-profile",
                    serverAddress = "localhost",
                    serverPort = trustedSocket.localPort,
                    allowedPorts = servers.map { it.port }.toSet(),
                    network = null,
                    connectionGeneration = 1,
                    allowed = { true },
                ),
            )

            val raw = socketFactory.createSocket()
            raw.connect(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), trustedSocket.localPort),
                5_000,
            )
            assertTrue(raw.isConnected)
            (clientContext.socketFactory.createSocket(
                raw, "localhost", trustedSocket.localPort, true,
            ) as SSLSocket).use { tls ->
                TakServerCaTrust.configureHostnameVerification(tls)
                tls.startHandshake()
                tls.outputStream.write(0x5a)
                tls.outputStream.flush()
                assertEquals(0x33, tls.inputStream.read())
                assertEquals("localhost", tls.session.peerHost)
            }
            servers[0].joinAndRethrow()
            assertEquals("localhost", servers[0].requestedServerName.get())
            assertTrue("Host-owned client identity was not presented", servers[0].clientIdentitySeen.get())

            assertHandshakeRejected(
                socketFactory, wrongHostContext, servers[1].port, "wrong.example",
            )
            servers[1].join()

            assertHandshakeRejected(
                socketFactory, clientContext, servers[2].port, "localhost",
            )
            servers[2].join()
        } finally {
            registry.unregister(C.PLUGIN_PACKAGE, providerUid)
            unbind(hostContext, providerConnection)
            servers.forEach { it.close() }
        }
    }

    private fun bindRemoteProvider(hostContext: Context, testContext: Context): RemoteServiceConnection {
        val component = ComponentName(testContext, SyntheticOpenVpnProviderService::class.java)
        val connection = RemoteServiceConnection()
        assertTrue(hostContext.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
        assertTrue("Synthetic provider bind timed out", connection.ready.await(10, TimeUnit.SECONDS))
        assertNotNull(connection.binder)
        return connection
    }

    private fun unbind(context: Context, connection: RemoteServiceConnection) {
        runCatching { context.unbindService(connection) }
    }

    private fun readRemoteProcess(binder: IBinder): RemoteProcess {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            assertTrue(binder.transact(TRANSACTION_DIAGNOSTICS, data, reply, 0))
            reply.readException()
            return RemoteProcess(reply.readInt(), reply.readInt(), reply.readInt())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun configureRemoteProvider(binder: IBinder, ports: List<Int>) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(ports.size)
            ports.forEach(data::writeInt)
            assertTrue(binder.transact(TRANSACTION_CONFIGURE, data, reply, 0))
            reply.readException()
            assertEquals(0, reply.readInt())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun assertHandshakeRejected(
        factory: javax.net.SocketFactory,
        context: SSLContext,
        port: Int,
        peerHost: String,
    ) {
        val raw = factory.createSocket()
        raw.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5_000)
        val tls = context.socketFactory.createSocket(raw, peerHost, port, true) as SSLSocket
        TakServerCaTrust.configureHostnameVerification(tls)
        try {
            tls.startHandshake()
            throw AssertionError("TLS unexpectedly accepted $peerHost on port $port")
        } catch (_: javax.net.ssl.SSLException) {
            // The wrong hostname or untrusted CA must fail during the TLS handshake.
        } finally {
            tls.close()
        }
    }

    private fun newClientIdentity(): ClientIdentity {
        val keyPair = newKeyPair()
        val certificate = certificate(
            subject = "CN=WearTAK host",
            publicKey = keyPair.public,
            issuer = "CN=WearTAK host",
            issuerKey = keyPair,
            ca = false,
            clientAuth = true,
        )
        val keyManagers = keyManagers("wear-tak-host", keyPair, arrayOf(certificate))
        return ClientIdentity(
            certificate = certificate,
            keyManagers = keyManagers,
        )
    }

    private fun newServer(clientCertificate: X509Certificate): SyntheticServer {
        val caKey = newKeyPair()
        val caName = "CN=Synthetic TAK CA"
        val caCertificate = certificate(
            subject = caName,
            publicKey = caKey.public,
            issuer = caName,
            issuerKey = caKey,
            ca = true,
        )
        val serverKey = newKeyPair()
        val serverCertificate = certificate(
            subject = "CN=Synthetic TAK server",
            publicKey = serverKey.public,
            issuer = caName,
            issuerKey = caKey,
            ca = false,
            dnsSan = "localhost",
        )
        val keyManagers = keyManagers("synthetic-server", serverKey, arrayOf(serverCertificate, caCertificate))
        val serverTrust = clientTrustManager(clientCertificate)
        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(serverTrust), SecureRandom())
        }
        return SyntheticServer(context, caCertificate)
    }

    private fun startServer(
        listener: SSLServerSocket,
        clientCertificate: X509Certificate,
        echo: Boolean,
    ): ServerRun {
        val requestedServerName = AtomicReference<String?>()
        val clientIdentitySeen = AtomicReference(false)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                (listener.accept() as SSLSocket).use { peer ->
                    peer.startHandshake()
                    val session = peer.session as ExtendedSSLSession
                    requestedServerName.set(
                        session.requestedServerNames.filterIsInstance<javax.net.ssl.SNIHostName>()
                            .firstOrNull()?.asciiName,
                    )
                    clientIdentitySeen.set(session.peerCertificates.any { it == clientCertificate })
                    if (echo) {
                        if (peer.inputStream.read() != 0x5a) throw IOException("Unexpected TLS fixture payload")
                        peer.outputStream.write(0x33)
                        peer.outputStream.flush()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }
        return ServerRun(listener, thread, requestedServerName, clientIdentitySeen, failure)
    }

    private fun keyManagers(
        alias: String,
        keyPair: KeyPair,
        chain: Array<X509Certificate>,
    ): Array<KeyManager> {
        val password = "test-key".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry(alias, keyPair.private, password, chain)
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }.keyManagers
    }

    private fun clientTrustManager(clientCertificate: X509Certificate): X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing host client certificate")
                if (leaf != clientCertificate) throw CertificateException("Unexpected host client certificate")
                leaf.checkValidity()
                leaf.verify(clientCertificate.publicKey)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("Synthetic TAK server does not trust peer servers")

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(clientCertificate)
        }

    private fun certificate(
        subject: String,
        publicKey: java.security.PublicKey,
        issuer: String,
        issuerKey: KeyPair,
        ca: Boolean,
        dnsSan: String? = null,
        clientAuth: Boolean = false,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuer),
            BigInteger.valueOf(serial.incrementAndGet()),
            Date(now - 60_000),
            Date(now + 3_600_000),
            X500Name(subject),
            publicKey,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            .addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(
                    if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign
                    else KeyUsage.digitalSignature or KeyUsage.keyEncipherment,
                ),
            )
        if (dnsSan != null) {
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, dnsSan)),
            )
        }
        if (clientAuth) {
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth),
            )
        } else if (!ca) {
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
        }
        return JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.private)),
        )
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private data class ClientIdentity(
        val certificate: X509Certificate,
        val keyManagers: Array<KeyManager>,
    )

    private data class SyntheticServer(
        val context: SSLContext,
        val caCertificate: X509Certificate,
    ) {
        fun newSocket(): SSLServerSocket =
            (context.serverSocketFactory.createServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"),
            ) as SSLServerSocket).apply {
                needClientAuth = true
                soTimeout = 10_000
                enabledProtocols = arrayOf("TLSv1.3")
            }
    }

    private data class RemoteProcess(val pid: Int, val uid: Int, val lastCallingUid: Int)

    private class RemoteServiceConnection : ServiceConnection {
        val ready = CountDownLatch(1)
        @Volatile var binder: IBinder? = null
            private set

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            ready.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    private class ServerRun(
        private val listener: SSLServerSocket,
        private val thread: Thread,
        val requestedServerName: AtomicReference<String?>,
        val clientIdentitySeen: AtomicReference<Boolean>,
        private val failure: AtomicReference<Throwable?>,
    ) {
        val port: Int get() = listener.localPort

        fun join() {
            thread.join(10_000)
            assertTrue("Synthetic TLS server did not finish", !thread.isAlive)
        }

        fun joinAndRethrow() {
            join()
            failure.get()?.let { throw AssertionError("Synthetic TLS server failed", it) }
        }

        fun close() {
            listener.close()
            thread.join(1_000)
        }
    }

    private companion object {
        const val DESCRIPTOR = "com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1"
        const val TRANSACTION_DIAGNOSTICS = IBinder.FIRST_CALL_TRANSACTION + 100
        const val TRANSACTION_CONFIGURE = IBinder.FIRST_CALL_TRANSACTION + 101
        val serial = java.util.concurrent.atomic.AtomicLong()
    }
}
