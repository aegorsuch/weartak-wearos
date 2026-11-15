package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser
import com.aitech.weartak_civ.cot.CotEventTimeFactory
import com.aitech.weartak_civ.cot.CotTimeOffset
import com.aitech.weartak_civ.cot.CotXml
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal const val MAX_DATA_PACKAGE_BYTES = 8 * 1024 * 1024
private const val MAX_EXPANDED_BYTES = 16 * 1024 * 1024
private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 256 * 1024
private const val MAX_ENTRIES = 256
private const val MANIFEST_PATH = "MANIFEST/manifest.xml"

data class TakDataPackageRequest(
    val sha256: String,
    val size: Long,
    val senderUid: String? = null,
    val name: String = "",
    val ackUid: String? = null,
    val peerDownloadUrl: HttpUrl? = null
) {
    init {
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid package SHA-256" }
        require(size in 1..MAX_DATA_PACKAGE_BYTES.toLong()) { "Invalid package size" }
    }

    internal fun acknowledgement(deviceUid: String, nowMs: Long, success: Boolean, reason: String? = null): String? {
        val uid = ackUid ?: return null
        val recipient = requireNotNull(senderUid) { "Missing acknowledgement recipient" }
        val time = CotEventTimeFactory.at(nowMs, staleOffset = CotTimeOffset(seconds = 10))
        val ack = CotXml.element("ackresponse", listOf(
            "uid" to CotXml.escape(uid), "senderUid" to CotXml.escape(deviceUid),
            "success" to success, "tag" to CotXml.escape(name),
            "reason" to (reason ?: if (success) "Routes imported" else "Route package import failed"),
            "sha256" to sha256, "sizeInBytes" to size
        ))
        val destination = CotXml.element("marti", content =
            CotXml.element("dest", listOf("uid" to CotXml.escape(recipient))))
        return CotXml.event(
            listOf("version" to "2.0", "uid" to UUID.randomUUID().toString(),
                "type" to "b-f-t-a", "how" to "m-g", "time" to time.timeCot,
                "start" to time.startCot, "stale" to time.staleCot),
            listOf("lat" to 0, "lon" to 0, "hae" to 0, "ce" to 9999999, "le" to 9999999),
            ack + destination
        )
    }

    companion object {
        internal fun localOfferSummary(xml: String, peerAddress: String?): String? {
            if (!xml.contains("b-f-t-r")) return null
            val prefix = "Local package offer peer=$peerAddress"
            if (xml.length > MAX_MANIFEST_BYTES) return "$prefix metadata=oversized"
            var isOffer = false
            var summary: String? = null
            try {
                readPackageXml(xml) { parser ->
                    if (parser.eventType != XmlPullParser.START_TAG) return@readPackageXml
                    if (parser.depth == 1) {
                        isOffer = parser.name == "event" &&
                            parser.getAttributeValue(null, "type") == "b-f-t-r"
                    }
                    if (isOffer && parser.depth == 3 && parser.name == "fileshare") {
                        val url = parser.getAttributeValue(null, "senderUrl")?.toHttpUrlOrNull()
                        summary = "$prefix scheme=${url?.scheme} port=${url?.port} " +
                            "path=${url?.encodedPath?.take(256)} " +
                            "hostMatchesPeer=${url?.host == peerAddress} " +
                            "queryKeys=${url?.queryParameterNames?.sorted()?.joinToString(",") { key ->
                                if (key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}"))) key else "[invalid]"
                            }} queryCount=${url?.querySize} " +
                            "hashPresent=${!parser.getAttributeValue(null, "sha256").isNullOrBlank()} " +
                            "sizePresent=${!parser.getAttributeValue(null, "sizeInBytes").isNullOrBlank()}"
                    }
                }
            } catch (_: org.xmlpull.v1.XmlPullParserException) {
                return "$prefix metadata=invalid-xml"
            } catch (_: IllegalArgumentException) {
                return "$prefix metadata=rejected-xml"
            } catch (_: IOException) {
                return "$prefix metadata=unreadable-xml"
            }
            return summary
        }

        fun parse(xml: String, peerAddress: String? = null): TakDataPackageRequest {
            require(xml.length <= MAX_MANIFEST_BYTES) { "Package request is too large" }
            var request: TakDataPackageRequest? = null
            var isRequest = false
            var inDetail = false
            var ackUid: String? = null
            readPackageXml(xml) { parser ->
                if (parser.eventType != XmlPullParser.START_TAG) return@readPackageXml
                if (parser.depth == 1) {
                    isRequest = parser.name == "event" &&
                        parser.getAttributeValue(null, "type") == "b-f-t-r"
                }
                if (parser.depth == 2) inDetail = parser.name == "detail"
                if (isRequest && inDetail && parser.depth == 3 && parser.name == "fileshare") {
                    require(request == null) { "Multiple package offers" }
                    val hash = parser.getAttributeValue(null, "sha256").orEmpty()
                    require(hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid package SHA-256" }
                    val size = parser.getAttributeValue(null, "sizeInBytes")?.toLongOrNull()
                    require(size != null && size in 1..MAX_DATA_PACKAGE_BYTES.toLong()) {
                        "Invalid or oversized package"
                    }
                    val url = parser.getAttributeValue(null, "senderUrl").orEmpty().toHttpUrl()
                    if (peerAddress != null) {
                        validatePeerUrl(url, peerAddress)
                    } else require(url.isHttps && url.port == 8443 &&
                        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
                        url.encodedPath == "/Marti/sync/content" &&
                        url.queryParameterValues("hash") == listOf(hash)
                    ) { "Only TAK server content downloads are supported" }
                    val sender = parser.getAttributeValue(null, "senderUid")
                    val name = parser.getAttributeValue(null, "name").orEmpty()
                    require((sender == null || sender.length in 1..256) && name.length <= 256) {
                        "Oversized package identity"
                    }
                    request = TakDataPackageRequest(hash.lowercase(), size, sender, name,
                        peerDownloadUrl = url.takeIf { peerAddress != null })
                }
                if (isRequest && inDetail && parser.depth == 3 && parser.name == "ackrequest" &&
                    parser.getAttributeValue(null, "ackrequested") == "true"
                ) {
                    require(ackUid == null) { "Multiple package acknowledgements requested" }
                    ackUid = parser.getAttributeValue(null, "uid")
                    require(!ackUid.isNullOrBlank() && ackUid.length <= 256) { "Invalid acknowledgement UID" }
                }
            }
            val offer = requireNotNull(request) { "Missing package offer" }
            require(ackUid == null || !offer.senderUid.isNullOrBlank()) { "Missing package sender UID" }
            return offer.copy(ackUid = ackUid)
        }
    }
}

internal fun validatePeerUrl(url: HttpUrl, peerAddress: String) {
    val octets = peerAddress.split('.').map { it.toIntOrNull() }
    require(octets.size == 4 && octets.all { it != null && it in 0..255 } &&
        (octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168))) { "Peer download requires a private IPv4 source" }
    require(url.host == peerAddress && url.isHttps && url.port == 8443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
        url.encodedPath == "/getfile") { "Peer download must use source-bound HTTPS port 8443 /getfile" }
    // CommonCommo MissionPackageManager::getLocalUrl uses file=<transferId>&sender=<callsign>.
    require(url.queryParameterNames == setOf("file", "sender") && url.querySize == 2 &&
        url.queryParameterValues("file").singleOrNull()?.let {
            it.matches(Regex("[0-9]{1,10}")) && it.toLong() <= Int.MAX_VALUE
        } == true && url.queryParameterValues("sender").singleOrNull()?.let {
            it.length in 1..256 && it.none(Char::isISOControl)
        } == true) { "Unsupported peer download query; expected one file ID and sender" }
}

/**
 * ATAK serves local mission packages with a self-signed certificate that does not name the
 * phone's IP, so platform trust can never succeed. Package integrity comes from the offer's
 * mandatory SHA-256 and size, which downloadRoutes verifies before importing anything.
 * Never use this for TAK server connections.
 */
@android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal object PeerPackageTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) =
        throw java.security.cert.CertificateException("Peer package client authentication is unsupported")

    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Peer sent no certificate")
    }

    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
}

internal class TakDataPackageClient(
    private val address: String,
    internal val client: OkHttpClient,
    private val peerUrl: HttpUrl? = null,
    private val session: TakHttpSession? = null
) {
    companion object {
        internal fun peer(offer: TakDataPackageRequest, peerAddress: String,
                          network: android.net.Network): TakDataPackageClient {
            val url = requireNotNull(offer.peerDownloadUrl)
            validatePeerUrl(url, peerAddress)
            val tls = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(PeerPackageTrustManager), null)
            }
            return TakDataPackageClient(peerAddress, OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .sslSocketFactory(tls.socketFactory, PeerPackageTrustManager)
                .hostnameVerifier { hostname, _ -> hostname == peerAddress }
                .proxy(java.net.Proxy.NO_PROXY)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        require(hostname == peerAddress) { "Peer hostname changed" }
                        return listOf(java.net.InetAddress.getByAddress(
                            peerAddress.split('.').map { it.toInt().toByte() }.toByteArray()))
                    }
                })
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).build(), url)
        }
    }
    constructor(
        address: String,
        sslContext: SSLContext,
        trustManager: X509TrustManager,
        hostnameVerifier: HostnameVerifier,
        session: TakHttpSession? = null
    ) : this(
        address,
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build(),
        session = session
    )

    internal fun downloadUrl(offer: TakDataPackageRequest): HttpUrl =
        // Server offers can advertise an IP instead of the configured TLS hostname.
        // Never send the client's certificate to the advertised host.
        peerUrl ?: HttpUrl.Builder().scheme("https").host(address).port(8443)
            .addPathSegments("Marti/sync/content")
            .addQueryParameter("hash", offer.sha256)
            .build()

    fun downloadRoutes(offer: TakDataPackageRequest, serverId: String): List<TakRoute> =
        TakDataPackageImporter.routes(downloadBytes(offer), serverId)

    fun downloadBytes(offer: TakDataPackageRequest): ByteArray {
        val request = Request.Builder().url(downloadUrl(offer)).get().build()
        (session?.route(client) ?: client).newCall(request).withSessionResponse(session) { response ->
            if (!response.isSuccessful) throw IOException("Package download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty package response")
            if (body.contentLength() > MAX_DATA_PACKAGE_BYTES) throw IOException("Oversized package response")
            val bytes = body.byteStream().use { it.readBounded(MAX_DATA_PACKAGE_BYTES) }
            require(bytes.size.toLong() == offer.size) { "Package size mismatch" }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            require(hash == offer.sha256) { "Package SHA-256 mismatch" }
            return bytes
        }
    }
}

internal object TakDataPackageImporter {
    /** Structural validation shared by both importers; a manifest action is only a hint. */
    private fun checkedEntries(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size in 1..MAX_DATA_PACKAGE_BYTES) { "Invalid package size" }
        val entries = mutableMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        var expandedBytes = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(names.size < MAX_ENTRIES) { "Too many package entries" }
                validateEntryPath(entry.name, entry.isDirectory)
                require(names.add(entry.name)) { "Duplicate package entry" }
                val limit = if (entry.name == MANIFEST_PATH) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                val content = zip.readBounded(minOf(limit, MAX_EXPANDED_BYTES - expandedBytes))
                expandedBytes += content.size
                if (!entry.isDirectory) {
                    entries[entry.name] = content
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    fun voiceCandidate(bytes: ByteArray): Boolean {
        val entries = checkedEntries(bytes)
        val manifest = requireNotNull(entries[MANIFEST_PATH]) { "Missing TAK package manifest" }
        var valid = false
        var hint = false
        var inContents = false
        val selected = mutableSetOf<String>()
        readPackageXml(manifest.toString(Charsets.UTF_8)) { parser ->
            if (parser.eventType != XmlPullParser.START_TAG) return@readPackageXml
            if (parser.depth == 1) {
                valid = parser.name == "MissionPackageManifest" && parser.getAttributeValue(null, "version") == "2"
                require(valid) { "Unsupported package manifest" }
            }
            if (parser.depth == 2) inContents = parser.name == "Contents"
            if (parser.name == "Parameter" && parser.getAttributeValue(null, "name") == "onReceiveAction") {
                hint = parser.getAttributeValue(null, "value") == "com.atakmap.android.gbr.multicastvoice.sharing.downloaded"
            }
            if (inContents && parser.name == "Content" && parser.depth == 3) {
                val path = requireNotNull(parser.getAttributeValue(null, "zipEntry"))
                validateEntryPath(path, false)
                val ignore = parser.getAttributeValue(null, "ignore") ?: "false"
                require(ignore == "true" || ignore == "false")
                if (ignore == "false") {
                    require(selected.add(path) && entries.containsKey(path)) { "Missing or duplicate manifest entry" }
                }
            }
        }
        require(valid)
        return selected.any { path ->
            val content = entries.getValue(path)
            val json = if (content.size in 1..32700 && content.first() == '{'.code.toByte())
                runCatching { org.json.JSONObject(content.toString(Charsets.UTF_8)) }.getOrNull() else null
            val jsonCandidate = json != null && json.optJSONObject("missionId")?.optString("uuid")?.isNotBlank() == true &&
                json.optJSONArray("channels") != null && json.optString("missionType") == "COMBINED"
            // Binary semantic parsing stays in the Voice importer. An offer is not an applied config.
            jsonCandidate || (hint && path.endsWith("_proto") && content.size in 4..131072 && content.first() == 10.toByte())
        }
    }

    fun routes(bytes: ByteArray, serverId: String): List<TakRoute> {
        require(serverId.isNotBlank()) { "Missing package source server" }
        val entries = checkedEntries(bytes)
        val manifest = requireNotNull(entries[MANIFEST_PATH]) { "Missing TAK package manifest" }
        val selected = mutableSetOf<String>()
        var validManifest = false
        var inContents = false
        readPackageXml(manifest.toString(Charsets.UTF_8)) { parser ->
            if (parser.eventType != XmlPullParser.START_TAG) return@readPackageXml
            if (parser.depth == 1) {
                validManifest = parser.name == "MissionPackageManifest" &&
                    parser.getAttributeValue(null, "version") == "2"
                require(validManifest) { "Unsupported package manifest" }
            }
            if (parser.depth == 2) inContents = parser.name == "Contents"
            if (inContents && parser.name == "Content" && parser.depth == 3) {
                val path = requireNotNull(parser.getAttributeValue(null, "zipEntry")) {
                    "Missing manifest content path"
                }
                validateEntryPath(path, false)
                val ignore = parser.getAttributeValue(null, "ignore") ?: "false"
                require(ignore == "true" || ignore == "false") { "Invalid manifest ignore flag" }
                if (ignore == "false" && path.endsWith(".cot", ignoreCase = true)) {
                    require(selected.add(path)) { "Duplicate manifest content" }
                }
            }
        }
        require(validManifest) { "Invalid package manifest" }
        val routes = selected.mapNotNull { path ->
            val content = requireNotNull(entries[path]) { "Missing manifest CoT entry" }
            val xml = content.toString(Charsets.UTF_8)
            var isRoute = false
            readPackageXml(xml) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 1) {
                    isRoute = parser.name == "event" &&
                        parser.getAttributeValue(null, "type") == "b-m-r"
                }
            }
            if (isRoute) {
                requireNotNull(TakRouteCotParser.parse(xml, serverId)) { "Invalid package route" }
            } else null
        }
        require(routes.isNotEmpty()) { "Package contains no supported routes" }
        require(routes.map { it.uid }.distinct().size == routes.size) { "Duplicate route UID in package" }
        require(routes.sumOf { it.points.size } <= 10_000) { "Too many route points in package" }
        return routes
    }
}

private fun validateEntryPath(path: String, directory: Boolean) {
    val name = if (directory) path.removeSuffix("/") else path
    require(name.isNotEmpty() && name.length <= 1024 &&
        name.none { it == '\\' || it == ':' || it.isISOControl() } &&
        name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    ) { "Unsafe package entry path" }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        require(output.size() + read <= limit) { "Package content exceeds size limit" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun readPackageXml(xml: String, visit: (XmlPullParser) -> Unit) {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    parser.setInput(StringReader(xml))
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        require(parser.eventType != XmlPullParser.DOCDECL &&
            (parser.eventType != XmlPullParser.ENTITY_REF ||
                parser.name in setOf("amp", "lt", "gt", "quot", "apos"))
        ) { "DTD and entity references are not supported in packages" }
        require(parser.depth <= 32) { "Package XML is too deeply nested" }
        visit(parser)
        parser.nextToken()
    }
}
