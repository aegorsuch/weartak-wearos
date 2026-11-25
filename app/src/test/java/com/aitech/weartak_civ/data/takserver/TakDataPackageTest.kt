package com.aitech.weartak_civ.data.takserver

import android.app.Application
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TakDataPackageTest {
    private val route = """
        <event uid="route-1" type="b-m-r"><detail>
        <contact callsign="Test route"/><remarks source="sender-1">A &amp; B</remarks>
        <link point="34,-117,100" callsign="Start"/>
        <link point="34.5,-117.5,150"/>
        <link point="35,-118,200" callsign="Finish"/>
        </detail></event>
    """.trimIndent()
    private val path = "route-1/route-1.cot"
    private fun manifest(contents: String = """<Content ignore="false" zipEntry="$path"/>""") =
        """<MissionPackageManifest version="2"><Configuration>
            <Parameter name="onReceiveImport" value="true"/>
            <Parameter name="onReceiveDelete" value="true"/>
            </Configuration><Contents>$contents</Contents></MissionPackageManifest>"""
    private fun zip(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { stream ->
                entries.forEach { (name, text) ->
                    stream.putNextEntry(ZipEntry(name))
                    stream.write(text.toByteArray())
                    stream.closeEntry()
                }
            }
        }.toByteArray()
    private fun sample(xml: String = route) = zip("MANIFEST/manifest.xml" to manifest(), path to xml)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun offer(bytes: ByteArray) = TakDataPackageRequest(hash(bytes), bytes.size.toLong())
    private fun request(
        hash: String = "a".repeat(64),
        size: String = "123",
        url: String = "https://192.0.2.1:8443/Marti/sync/content?hash=$hash"
    ) = """<event uid="offer" type="b-f-t-r"><detail>
        <fileshare filename="route.zip" sha256="$hash" sizeInBytes="$size" senderUrl="$url"/>
        </detail></event>"""

    @Test fun localOfferDiagnosticsExcludeCredentialsQueriesAndHashValues() {
        val xml = request(
            url = "http://private-user:private-password@10.1.2.3:8080/Marti/sync/content?hash=${"a".repeat(64)}&amp;token=private-token"
        )
        val summary = requireNotNull(TakDataPackageRequest.localOfferSummary(xml, "10.1.2.3"))
        assertEquals("Local package offer peer=10.1.2.3 scheme=http port=8080 " +
            "path=/Marti/sync/content hostMatchesPeer=true queryKeys=hash,token queryCount=2 " +
            "hashPresent=true sizePresent=true", summary)
        listOf("private-user", "private-password", "private-token", "a".repeat(64), "?").forEach {
            assertFalse(summary.contains(it))
        }
        assertNull(TakDataPackageRequest.localOfferSummary(route, "10.1.2.3"))
    }

    @Test fun localOfferDiagnosticsRejectUnsafeXmlWithoutDumpingIt() {
        val xml = """<!DOCTYPE event [<!ENTITY credential "private-secret">]>
            <event type="b-f-t-r"><detail><fileshare name="&credential;"/></detail></event>"""
        val summary = requireNotNull(TakDataPackageRequest.localOfferSummary(xml, "10.1.2.3"))
        assertEquals("Local package offer peer=10.1.2.3 metadata=rejected-xml", summary)
    }

    @Test fun acceptsOnlySourceBoundPrivateHttpsCommonCommoPeerShape() {
        val url = "https://10.0.0.84:8443/getfile?file=9&amp;sender=ATAK"
        val parsed = TakDataPackageRequest.parse(request(url = url), "10.0.0.84")
        assertEquals("/getfile", parsed.peerDownloadUrl?.encodedPath)
        assertEquals("9", parsed.peerDownloadUrl?.queryParameter("file"))
        assertThrows(IllegalArgumentException::class.java) { TakDataPackageRequest.parse(request(url = url)) }
        listOf(
            url.replace("10.0.0.84", "10.0.0.85"),
            url.replace("https:", "http:"),
            url.replace(":8443", ":8080"),
            url.replace("/getfile", "/getinfo"),
            url.replace("file=9", "file=-1"),
            url.replace("file=9", "file=2147483648"),
            url.replace("file=9", "file=9&amp;file=10"),
            url.replace("file=9", "token=9"),
            url.replace("&amp;sender=ATAK", ""),
            "$url&amp;redirect=http%3A%2F%2Flocalhost",
            "$url#fragment"
        ).forEach { bad ->
            assertThrows("Must reject constrained peer URL", IllegalArgumentException::class.java) {
                TakDataPackageRequest.parse(request(url = bad), "10.0.0.84")
            }
        }
        listOf("127.0.0.1", "169.254.169.254", "8.8.8.8", "224.0.0.1", "::1", "localhost").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                TakDataPackageRequest.parse(request(url =
                    "https://$bad:8443/getfile?file=9&amp;sender=ATAK"), bad)
            }
        }
    }

    @Test fun peerDownloadUsesSameBoundedHashAndArchiveValidationWithoutServerFallback() {
        val bytes = sample()
        val parsed = TakDataPackageRequest.parse(request(hash(bytes), bytes.size.toString(),
            "https://10.0.0.84:8443/getfile?file=9&amp;sender=ATAK"), "10.0.0.84")
        val url = requireNotNull(parsed.peerDownloadUrl)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(url, chain.request().url)
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val peer = TakDataPackageClient("10.0.0.84", client, url)
        assertEquals(3, peer.downloadRoutes(parsed, "multicast:peer:10.0.0.84").single().points.size)
        assertThrows(IllegalArgumentException::class.java) {
            peer.downloadRoutes(parsed.copy(sha256 = "0".repeat(64)), "peer")
        }
        val server = TakDataPackageClient("configured.example", client)
        assertEquals("configured.example", server.downloadUrl(parsed).host)
        assertEquals("/Marti/sync/content", server.downloadUrl(parsed).encodedPath)
    }

    @Test fun peerClientAcceptsSelfSignedPhoneCertOnlyForSenderIpAndUsesOnlyBoundWifiWithoutRedirects() {
        val network = io.mockk.mockk<android.net.Network>()
        val sockets = javax.net.SocketFactory.getDefault()
        io.mockk.every { network.socketFactory } returns sockets
        val parsed = TakDataPackageRequest.parse(request(url =
            "https://10.0.0.84:8443/getfile?file=9&amp;sender=ATAK"), "10.0.0.84")
        val peer = TakDataPackageClient.peer(parsed, "10.0.0.84", network).client
        assertSame(sockets, peer.socketFactory)
        assertSame(PeerPackageTrustManager, peer.x509TrustManager)
        val session = io.mockk.mockk<javax.net.ssl.SSLSession>()
        assertTrue(peer.hostnameVerifier.verify("10.0.0.84", session))
        assertFalse(peer.hostnameVerifier.verify("10.0.0.85", session))
        assertFalse(peer.hostnameVerifier.verify("configured.example", session))
        val cert = io.mockk.mockk<java.security.cert.X509Certificate>()
        PeerPackageTrustManager.checkServerTrusted(arrayOf(cert), "ECDHE_RSA")
        assertThrows(java.security.cert.CertificateException::class.java) {
            PeerPackageTrustManager.checkServerTrusted(emptyArray(), "ECDHE_RSA")
        }
        assertThrows(java.security.cert.CertificateException::class.java) {
            PeerPackageTrustManager.checkClientTrusted(arrayOf(cert), "RSA")
        }
        val server = TakDataPackageClient("configured.example", javax.net.ssl.SSLContext.getDefault(),
            OkHttpClient().x509TrustManager!!, OkHttpClient().hostnameVerifier).client
        assertNotSame(PeerPackageTrustManager, server.x509TrustManager)
        assertEquals(java.net.Proxy.NO_PROXY, peer.proxy)
        assertFalse(peer.followRedirects)
        assertFalse(peer.followSslRedirects)
        assertTrue(peer.interceptors.isEmpty())
        assertTrue(peer.networkInterceptors.isEmpty())
        assertEquals(30000, peer.callTimeoutMillis)
        assertEquals("10.0.0.84", peer.dns.lookup("10.0.0.84").single().hostAddress)
        assertThrows(IllegalArgumentException::class.java) { peer.dns.lookup("other.example") }
    }

    @Test fun importsOnlyManifestListedRoutesWithServerIdentityAndOrderedPoints() {
        val bytes = zip(
            "MANIFEST/manifest.xml" to manifest(),
            path to route,
            "unlisted.cot" to route.replace("route-1", "hidden-route"),
            "notes.txt" to "not imported"
        )
        val imported = TakDataPackageImporter.routes(bytes, "server-1").single()
        assertEquals("route-1", imported.uid)
        assertEquals("Test route", imported.name)
        assertEquals("server-1", imported.serverId)
        assertEquals(listOf("Start", null, "Finish"), imported.points.map { it.name })
        assertEquals(listOf(34.0, 34.5, 35.0), imported.points.map { it.latitude })
        assertEquals("sender-1", imported.sender)
        assertEquals("server-2", TakDataPackageImporter.routes(bytes, "server-2").single().serverId)
    }

    @Test fun supportsMultipleRoutesAndIgnoresOtherCotAndIgnoredEntries() {
        val contents = """<Content zipEntry="$path"/><Content zipEntry="second.cot"/>
            <Content zipEntry="marker.cot"/><Content ignore="true" zipEntry="ignored.cot"/>"""
        val bytes = zip(
            "MANIFEST/manifest.xml" to manifest(contents),
            path to route,
            "second.cot" to route.replace("route-1", "route-2"),
            "marker.cot" to "<event uid='marker' type='b-m-p-s-m'><detail/></event>"
        )
        assertEquals(listOf("route-1", "route-2"),
            TakDataPackageImporter.routes(bytes, "server").map { it.uid })
    }

    @Test fun rejectsTraversalAbsolutePathsBackslashesAndUnsafeManifestPaths() {
        listOf("../route.cot", "/route.cot", "C:/route.cot", "a\\route.cot", "a/./b.cot",
            "a//b.cot", "a/../b.cot").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                TakDataPackageImporter.routes(zip("MANIFEST/manifest.xml" to manifest(), bad to route), "s")
            }
            assertThrows(IllegalArgumentException::class.java) {
                TakDataPackageImporter.routes(zip("MANIFEST/manifest.xml" to
                    manifest("""<Content zipEntry="$bad"/>"""), path to route), "s")
            }
        }
    }

    @Test fun rejectsMissingInvalidOrEmptyManifestAndMalformedRoutesWithoutPartialImport() {
        listOf(
            zip(path to route),
            zip("MANIFEST/manifest.xml" to "<not-a-manifest/>", path to route),
            zip("MANIFEST/manifest.xml" to manifest(""), path to route),
            zip("MANIFEST/manifest.xml" to manifest()),
            sample("<event uid='empty' type='b-m-r'><detail/></event>"),
            zip("MANIFEST/manifest.xml" to manifest(
                """<Content zipEntry="$path"/><Content zipEntry="$path"/>"""), path to route),
            zip("MANIFEST/manifest.xml" to manifest(
                """<Content zipEntry="$path"/><Content zipEntry="other.cot"/>"""),
                path to route, "other.cot" to route)
        ).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { TakDataPackageImporter.routes(bytes, "s") }
        }
    }

    @Test fun rejectsDtdsAndExternalEntitiesInManifestAndRoute() {
        val declaration = """<!DOCTYPE x [<!ENTITY ext SYSTEM "file:///not-to-be-read">]>"""
        listOf(
            zip("MANIFEST/manifest.xml" to declaration + manifest(), path to route),
            sample(declaration + route)
        ).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { TakDataPackageImporter.routes(bytes, "s") }
        }
    }

    @Test fun boundsCompressedExpandedEntryCountAndXmlDepth() {
        assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes(ByteArray(MAX_DATA_PACKAGE_BYTES + 1), "s")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes(zip("MANIFEST/manifest.xml" to manifest(),
                path to "x".repeat(4 * 1024 * 1024 + 1)), "s")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes(zip("MANIFEST/manifest.xml" to "x".repeat(256 * 1024 + 1)), "s")
        }
        val entryCountError = assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes(zip(*(0..256).map { "file-$it" to "" }.toTypedArray()), "s")
        }
        assertEquals("Too many package entries", entryCountError.message)
        val expandedSizeError = assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes(zip("MANIFEST/manifest.xml" to manifest(),
                *(0..4).map { "file-$it" to "x".repeat(4 * 1024 * 1024) }.toTypedArray()), "s")
        }
        assertEquals("Package content exceeds size limit", expandedSizeError.message)
        assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes(sample("<a>".repeat(33) + "</a>".repeat(33)), "s")
        }
    }

    @Test fun boundsTotalRoutePointsAndRejectsMalformedArchiveData() {
        val xml = "<event uid='many' type='b-m-r'><detail>" +
            "<link point='34,-117'/>".repeat(10_001) + "</detail></event>"
        val tooMany = assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes(sample(xml), "s")
        }
        assertEquals("Too many route points in package", tooMany.message)
        assertEquals(10_000, TakDataPackageImporter.routes(
            sample(xml.replaceFirst("<link point='34,-117'/>", "")), "s"
        ).single().points.size)
        val notZip = assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageImporter.routes("Not an archive".toByteArray(), "s")
        }
        assertEquals("Missing TAK package manifest", notZip.message)
    }

    @Test fun parsesObservedServerOfferButRejectsUnboundedUntrustedOrMalformedOffers() {
        val parsed = TakDataPackageRequest.parse(request())
        assertEquals("a".repeat(64), parsed.sha256)
        assertEquals(123L, parsed.size)
        assertEquals(MAX_DATA_PACKAGE_BYTES.toLong(),
            TakDataPackageRequest.parse(request(size = MAX_DATA_PACKAGE_BYTES.toString())).size)
        listOf("0", "-1", "8388609", "NaN").forEach {
            assertThrows(IllegalArgumentException::class.java) { TakDataPackageRequest.parse(request(size = it)) }
        }
        listOf("http://server:8443/Marti/sync/content", "https://server:8443/getfile",
            "https://user:pass@server:8443/Marti/sync/content",
            "https://server:443/Marti/sync/content").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                TakDataPackageRequest.parse(request(url = "$it?hash=${"a".repeat(64)}"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageRequest.parse(request(hash = "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageRequest.parse(request().replace("b-f-t-r", "b-m-r"))
        }
    }

    @Test fun acknowledgementsEscapeIdentitiesRouteOnlyToSenderAndReportActualOutcome() {
        val xml = request()
            .replace("</detail>", """<ackrequest uid="ack&amp;1" ackrequested="true"/></detail>""")
            .replace("filename=", """senderUid="sender&amp;1" name="Route &quot;A&quot;" filename=""")
        val offer = TakDataPackageRequest.parse(xml)
        assertEquals("ack&1", offer.ackUid)
        for (success in listOf(true, false)) {
            val ack = requireNotNull(offer.acknowledgement("watch&1", 1_000L, success))
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(ack))
            val values = mutableMapOf<String, Map<String, String>>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    values[parser.name] = (0 until parser.attributeCount).associate {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    }
                }
                parser.next()
            }
            assertEquals("b-f-t-a", values["event"]?.get("type"))
            assertEquals("1970-01-01T00:00:11.000Z", values["event"]?.get("stale"))
            assertEquals("ack&1", values["ackresponse"]?.get("uid"))
            assertEquals("watch&1", values["ackresponse"]?.get("senderUid"))
            assertEquals(success.toString(), values["ackresponse"]?.get("success"))
            assertEquals("Route \"A\"", values["ackresponse"]?.get("tag"))
            assertEquals("sender&1", values["dest"]?.get("uid"))
        }
        assertNull(TakDataPackageRequest.parse(request()).acknowledgement("watch", 1_000L, true))
        assertThrows(IllegalArgumentException::class.java) {
            TakDataPackageRequest.parse(xml.replace("""senderUid="sender&amp;1" """, ""))
        }
    }

    private fun client(bytes: ByteArray, status: Int = 200) = TakDataPackageClient(
        "configured.example",
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                assertEquals("configured.example", chain.request().url.host)
                assertEquals(8443, chain.request().url.port)
                assertEquals("/Marti/sync/content", chain.request().url.encodedPath)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("test")
                    .body(bytes.toResponseBody("application/zip".toMediaType())).build()
            }.build()
    )

    @Test fun downloadsFromConfiguredOriginAndVerifiesHashAndSizeBeforeImporting() {
        val bytes = sample()
        assertEquals("route-1", client(bytes).downloadRoutes(offer(bytes), "s").single().uid)
        assertEquals("configured.example", client(bytes).downloadUrl(
            TakDataPackageRequest.parse(request())).host)
        assertThrows(IllegalArgumentException::class.java) {
            client(bytes).downloadRoutes(offer(bytes).copy(size = bytes.size + 1L), "s")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client(bytes).downloadRoutes(offer(bytes).copy(sha256 = "0".repeat(64)), "s")
        }
        listOf(302, 401, 403, 404, 500).forEach {
            assertThrows(IOException::class.java) { client(bytes, it).downloadRoutes(offer(bytes), "s") }
        }
        assertThrows(IOException::class.java) {
            client(ByteArray(MAX_DATA_PACKAGE_BYTES + 1)).downloadRoutes(offer(bytes), "s")
        }
    }

    @Test fun uploadsOnlyToConfiguredServerAndRequiresMatchingResponseHash() {
        val packageBytes = sample()
        val expectedHash = hash(packageBytes)
        fun uploadClient(replyHash: String, status: Int = 200) = TakDataPackageClient(
            "configured.example",
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("configured.example", request.url.host)
                    assertEquals(8443, request.url.port)
                    assertEquals("/Marti/sync/missionupload", request.url.encodedPath)
                    assertEquals("watch-1", request.url.queryParameter("creatorUid"))
                    assertEquals(expectedHash, request.url.queryParameter("hash"))
                    val payload = Buffer()
                    requireNotNull(request.body).writeTo(payload)
                    val multipart = payload.readByteArray()
                    assertTrue(multipart.toString(Charsets.ISO_8859_1).contains("name=\"assetfile\""))
                    assertTrue(multipart.toList().windowed(packageBytes.size).any { it == packageBytes.toList() })
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(status).message("test")
                        .body("https://advertised.example/Marti/sync/content?hash=$replyHash"
                            .toResponseBody("text/plain".toMediaType())).build()
                }.build()
        )
        assertEquals(expectedHash, uploadClient(expectedHash).upload(packageBytes, "watch-1"))
        assertThrows(IOException::class.java) { uploadClient("0".repeat(64)).upload(packageBytes, "watch-1") }
        assertThrows(IOException::class.java) { uploadClient(expectedHash, 302).upload(packageBytes, "watch-1") }
    }

    @Test fun importsOptionalLocalCaptureWithoutKeepingItInTheRepository() {
        val sample = System.getenv("WEARTAK_ROUTE_SAMPLE")
        assumeTrue("Optional local Data Package capture not supplied", sample != null)
        val routes = TakDataPackageImporter.routes(File(requireNotNull(sample)).readBytes(), "local-test")
        assertTrue(routes.isNotEmpty())
        assertTrue(routes.all { it.serverId == "local-test" && it.points.isNotEmpty() })
    }
}
