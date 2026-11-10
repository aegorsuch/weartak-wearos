/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import com.aitech.weartak_civ.presentation.MainActivity
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.net.Network
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

object NetworkUtils {
    private const val TAG = "WearTAK-NetworkUtils"

    // tak.X.ops.ascentitech.com is not in default truststore, so we need to trust it. To do this, we need to obtain the trusted cert from the keystore once we have connected to the tak server.
    private fun createCustomOkHttpClient(keyStore: KeyStore): OkHttpClient {
        // Initialize a TrustManagerFactory with the custom Keystore
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        val trustManagers = trustManagerFactory.trustManagers
        val trustManager = trustManagers.first { it is X509TrustManager } as X509TrustManager

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .build()
    }

    suspend fun fetchJsonFromServer(url: String, keyStore: KeyStore): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = createCustomOkHttpClient(keyStore)
                val xHardwareId = MainActivity.device_uid
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Hardware-Id", xHardwareId)
                    .addHeader("X-API-KEY", "yFaPWEcH3Q4HtB2gA9qjOHOXiQFCrxshFvZebMdn3ZvCKmkOgOON6Geh3E0LXQxmuqns92Gd1xcR87TaA74Xg")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Unexpected response: ${response.code}")
                        return@withContext null
                    }
                    response.body?.string()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching JSON from server", e)
                null
            }
        }
    }

    fun buildJsonUrl(takServerIp: String, departmentIdentifier: String, jsonType: String): String {
        val url = "https://$takServerIp:18281/json/$departmentIdentifier/$jsonType.json"
        Log.v(TAG, "URL: $url")
        return url
    }

    object MulticastPublisher {
        private const val TAG = "WearTAK-MulticastPub"

        @Volatile private var running = false
        @Volatile private var currentNetwork: Network? = null
        private var socket: MulticastSocket? = null
        private var group: InetAddress? = null

        private var address: String = DEFAULT_MULTICAST_ADDRESS
        private var port: Int = DEFAULT_MULTICAST_PORT

        /**
         * Call when Wi-Fi becomes available. We resolve the Wi-Fi Network and bind the socket to it.
         */
        fun start(network: Network, address: String, port: Int): Boolean {
            Log.i(TAG, "MulticastPublisher starting → $address:$port")
            synchronized(this) {
                // No-op if already bound to this same Network and socket is alive
                if (running && (currentNetwork == network) && (socket != null) && !socket!!.isClosed &&
                    (address == MulticastPublisher.address) && (port == MulticastPublisher.port)) return true

                // If running but on a different (or stale) Network, rebuild
                stopLocked()

                try {
                    val ms = MulticastSocket(null)
                    socket = ms
                    ms.reuseAddress = true
                    ms.timeToLive = 1
                    ms.bind(null) // ephemeral source port

                    network.bindSocket(ms)  // ensure packets egress over this Wi-Fi network

                    group = java.net.InetAddress.getByName(address)
                    MulticastPublisher.address = address
                    MulticastPublisher.port = port
                    currentNetwork = network
                    running = true
                    Log.i(TAG, "MulticastPublisher started → $address:${MulticastPublisher.port}")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "start() error: ${e.message}", e)
                    stopLocked() // clean up partial state
                    return false
                }
            }
        }

        fun stop() {
            synchronized(this) {
                stopLocked()
            }
        }

        private fun stopLocked() {
            if (!running && socket == null && group == null && currentNetwork == null) return
            try {
                socket?.close()
            } catch (_: Throwable) { /* ignore */ }
            socket = null
            group = null
            currentNetwork = null
            running = false
            Log.i(TAG, "MulticastPublisher stopped")
        }

        /**
         * Non-blocking send. Safe to call from any thread.
         * PLI CoT is small; if you ever send big payloads, consider chunking.
         */
        @Synchronized
        fun sendCommandReply(cotXml: String, destination: String, destinationPort: Int): Boolean {
            if (!running || !MainActivity.enableMulticastOnWifi ||
                MainActivity.dataRepository.shouldSuppressWatchTransportNow()) return false
            val ms = socket ?: return false
            if (destinationPort !in 1..65535) return false
            val target = InetAddress.getByName(destination)
            if (!target.isMulticastAddress) return false
            val bytes = TakMulticastEndpoints.prepareOutbound(cotXml).toByteArray(Charsets.UTF_8)
            if (bytes.size > 65507) return false
            ms.send(DatagramPacket(bytes, bytes.size, target, destinationPort))
            return true
        }

        @Synchronized
        fun send(cotXml: String) {
            send(cotXml, allowCompanionHandoffExpiry = false)
        }

        @Synchronized
        fun sendFinalExpiredSelfPli(cotXml: String): Boolean =
            send(cotXml, allowCompanionHandoffExpiry = true)

        private fun send(cotXml: String, allowCompanionHandoffExpiry: Boolean): Boolean {
            if (!allowCompanionHandoffExpiry &&
                MainActivity.dataRepository.shouldSuppressWatchTransportNow()) return false
            //Log.i(TAG, "Checking socket")
            val ms = socket ?: return false
            //Log.i(TAG, "Checking group")
            val grp = group ?: return false
            //Log.i(TAG, "Checking multicast publisher running status")
            if (!running) return false
            //Log.i(TAG, "Checking if multicast is enabled: ${MainActivity.enableMulticastOnWifi}")
            if (!MainActivity.enableMulticastOnWifi) return false

            val bytes = TakMulticastEndpoints.prepareOutbound(cotXml).toByteArray(Charsets.UTF_8)
            // Keep messages small; UDP can fragment.
            if (bytes.size > 1300) {
                Log.w(TAG, "CoT payload ${bytes.size}B may fragment over UDP.")
            }
            try {
                val endpoint = TakMulticastEndpoints.outbound(cotXml, address, port)
                val destination = if (endpoint.address == address) grp else InetAddress.getByName(endpoint.address)
                ms.send(DatagramPacket(bytes, bytes.size, destination, endpoint.port))
                //Log.i(TAG, "Sent Datagram to the multicast socket! $cotXml")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Multicast send() error: ${e.message}", e)
                return false
            }
        }
    }
}