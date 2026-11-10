/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.WifiSsidRcvd
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.requestStatusIconsComplicationUpdate
import com.aitech.weartak_civ.service.ConnectionDiagnostics
import com.aitech.weartak_civ.service.NetworkTransition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.greenrobot.eventbus.EventBus
import java.net.Inet4Address

class NetworkManager(val flags: Int) : ConnectivityManager.NetworkCallback(flags)  {

    private var mainActivity : MainActivity? = null
    private var connectivityManager : ConnectivityManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val multicastWifiCallback = MulticastWifiNetworkMonitor()
    val wifiNetwork: StateFlow<Network?> = multicastWifiCallback.wifiNetwork

    enum class NetworkTransport {
        WIFI,
        CELL,
        NONE
    }

    data class NetworkSnapshot(
        val type: NetworkTransport,
        val ssid: String?,
        val ip: String?,
    )

    private val _networkSnapshot = MutableStateFlow(
        NetworkSnapshot(
            type = NetworkTransport.NONE,
            ssid = null,
            ip = null
        )
    )

    val networkSnapshot: StateFlow<NetworkSnapshot> =
        _networkSnapshot.asStateFlow()

    private fun getIpAddress(network: Network?): String? {
        if (network == null) return null

        return connectivityManager
            ?.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { address ->
                !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
            }
            ?.hostAddress
    }

    fun init() {
        Log.d(TAG, "Initializing NetworkManager")
        mainActivity = MainActivity.Constants.getActivity()
        connectivityManager = mainActivity?.applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(networkRequest, this,
            Handler(Looper.getMainLooper())
        )
        // Local radio Wi-Fi need not provide internet or be Android's default network.
        val wifiRequest = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager?.registerNetworkCallback(wifiRequest, multicastWifiCallback, handler)
        checkNetworkConnected()
    }

    fun deInit() {
        if (null != connectivityManager) {
            connectivityManager?.unregisterNetworkCallback(this)
            connectivityManager?.unregisterNetworkCallback(multicastWifiCallback)
        }
        multicastWifiCallback.clear()
    }

    fun isNetworkConnected() : Boolean {
        return isNetworkConnected
    }

    fun isWifiConnected(): Boolean {
        return isWifi
    }

    fun checkNetworkConnected() {
        checkNetworkConnected(null, null)
    }

    fun checkNetworkConnected(network: Network?, networkCapabilities: NetworkCapabilities?) {
        //Log.d(TAG, "network (${network?.toString()}); networkCapabilities (${connectivityManager?.getNetworkCapabilities(network)?.toString()})")
        //Log.d(TAG, "activeNetwork (${connectivityManager?.activeNetwork?.toString()}); activeNetworkCapabilities (${connectivityManager?.getNetworkCapabilities(connectivityManager?.activeNetwork)?.toString()})")
        //writeToLogFileLocation(logFile, "network (${network?.toString()}); networkCapabilities (${connectivityManager?.getNetworkCapabilities(network)?.toString()})")
        //writeToLogFileLocation(logFile, "activeNetwork (${connectivityManager?.activeNetwork?.toString()}); activeNetworkCapabilities (${connectivityManager?.getNetworkCapabilities(connectivityManager?.activeNetwork)?.toString()})")
        val activeNetwork = network ?: connectivityManager?.activeNetwork
        val activeNetworkCapabilities = networkCapabilities ?: connectivityManager?.getNetworkCapabilities(activeNetwork)
        ConnectionDiagnostics.network(NetworkTransition.SNAPSHOT, activeNetwork != null, activeNetworkCapabilities)
        var networkInfoChanged = false
        var currWifiSsid : String? = null
        //Log.d(TAG, "checkNetworkConnected called w/ activeNetwork (${activeNetwork?.toString()}); activeNetworkCapabilities (${activeNetworkCapabilities?.toString()})")
        //writeToLogFileLocation(logFile, "checkNetworkConnected called w/ activeNetwork (${activeNetwork?.toString()}); activeNetworkCapabilities (${activeNetworkCapabilities?.toString()})")
        // TODO: Need to test with SSIDs of hidden networks
        // If wifi is connected but the wifi AP is NOT connected to internet, we still show as connected to wifi.
        if ((null != activeNetwork) && (null != activeNetworkCapabilities)) {
            if (isNetworkConnected == false) {
                isNetworkConnected = true
                networkInfoChanged = true
                isNetworkConnectedLiveData.postValue(isNetworkConnected)
                requestStatusIconsComplicationUpdate(context)
            }

            val currIsWifi = activeNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val currWifiInfo = if (currIsWifi) { activeNetworkCapabilities.transportInfo as WifiInfo } else { null }
            if (null != currWifiInfo) {
                currWifiSsid = currWifiInfo.ssid
                // Valid SSIDs are surrounded by double quotes and this removes them
                // NOTE: We can also get <unknown ssid> and this will not change it
                if ("<" != currWifiSsid.take(1)) {
                    currWifiSsid = currWifiSsid.substring(1, currWifiSsid.length - 1)
                }
            }
            // We get <unknown ssid> and then null when switching from wifi to cellular; Similarly <unknown ssid> and then SSID when no network to Wifi
            if (isWifi != currIsWifi) {
                isWifi = currIsWifi
                networkInfoChanged = true
                isWifiConnectedLiveData.postValue(isWifi)
                requestStatusIconsComplicationUpdate(context)
            }
            val currIsCellular = activeNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (isCellular != currIsCellular) {
                isCellular = currIsCellular
                networkInfoChanged = true
                isCellularConnectedLiveData.postValue(isCellular)
                requestStatusIconsComplicationUpdate(context)
            }
        } else {
            if (isNetworkConnected == true) {
                isNetworkConnected = false
                networkInfoChanged = true
                isNetworkConnectedLiveData.postValue(isNetworkConnected)
                requestStatusIconsComplicationUpdate(context)
            }

            if (isWifi == true) {
                isWifi = false
                networkInfoChanged = true
                isWifiConnectedLiveData.postValue(isWifi)
                requestStatusIconsComplicationUpdate(context)
            }

            if (isCellular == true) {
                isCellular = false
                networkInfoChanged = true
                isCellularConnectedLiveData.postValue(isCellular)
                requestStatusIconsComplicationUpdate(context)
            }
        }

        val transport = when {
            isWifi -> NetworkTransport.WIFI
            isCellular -> NetworkTransport.CELL
            else -> NetworkTransport.NONE
        }

        _networkSnapshot.value = NetworkSnapshot(
            type = transport,
            ssid = if (transport == NetworkTransport.WIFI) {
                currWifiSsid ?: connectedWifiSsid
            } else {
                null
            },
            ip = getIpAddress(activeNetwork)
        )

        //Log.i(TAG, "isNetworkConnected = $isNetworkConnected; isWifi = $isWifi; isCellular = $isCellular")
        //writeToLogFileLocation(logFile, "isNetworkConnected = $isNetworkConnected; isWifi = $isWifi; isCellular = $isCellular")
        if (networkInfoChanged) {
            Log.d(TAG, "Change detected in network connection information.Sending NwConnEvent")
            //writeToLogFileLocation(logFile, "Change detected in network connection information.Sending NwConnEvent")
            EventBus.getDefault().post(
                NwConnEvent(
                    isNetworkConnected, if (isWifi == true) {
                        NwConnEvent.Companion.WIFI
                    } else {
                        NwConnEvent.Companion.CELLULAR
                    }
                )
            )
            if (currWifiSsid != connectedWifiSsid) {
                Log.d(TAG, "Change in wifi SSID detected before($connectedWifiSsid) current($currWifiSsid)")
                // Note we could be storing <unknown_ssid>
                // TODO: What happens if the actual SSID is called <unknown_ssid>. For now, this case is not being handled
                connectedWifiSsid = currWifiSsid
                EventBus.getDefault().post(WifiSsidRcvd(connectedWifiSsid))
            }
        } else {
            //Log.d(TAG, "No change detected in network connection information.So NOT sending NwConnEvent")
            //writeToLogFileLocation(logFile, "No change detected in network connection information.So NOT sending NwConnEvent")
            // If checkNetworkConnected is called with both params null, we only change SSID if isNetworkConnected is false (i.e., currWifiSsid == null)
            if ((null == network) && (null == networkCapabilities)) {
                if ((null == currWifiSsid) && (currWifiSsid != connectedWifiSsid)) {
                    Log.d(TAG, "Change in wifi SSID detected before($connectedWifiSsid) current($currWifiSsid)")
                    connectedWifiSsid = currWifiSsid
                    EventBus.getDefault().post(WifiSsidRcvd(connectedWifiSsid))
                }
            } else {
                if (currWifiSsid != connectedWifiSsid) {
                    Log.d(TAG, "Change in wifi SSID detected before($connectedWifiSsid) current($currWifiSsid)")
                    connectedWifiSsid = currWifiSsid
                    EventBus.getDefault().post(WifiSsidRcvd(connectedWifiSsid))
                }
            }
        }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, networkCapabilities)
        ConnectionDiagnostics.network(NetworkTransition.CAPABILITIES, true, networkCapabilities)
//        Log.d(TAG, "onCapabilitiesChanged called")
        //writeToLogFileLocation(logFile, "onCapabilitiesChanged called")
        checkNetworkConnected(network, networkCapabilities)
    }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        ConnectionDiagnostics.network(NetworkTransition.AVAILABLE, true, null)
        Log.d(TAG, "onAvailable called")
        //writeToLogFileLocation(logFile, "onAvailable called")
        checkNetworkConnected()
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        ConnectionDiagnostics.network(NetworkTransition.LOST, false, null)
        Log.d(TAG, "onLost called")
        //writeToLogFileLocation(logFile, "onLost called")
        checkNetworkConnected()
        // Immediately checking network connectivity when previously only on cellular via checkNetworkConnected sometimes returns network (especially cellular) as still being connected.
        // Hence we call checkNetworkConnected function twice with delay of 2s and 10s. Never seen 2s fail to give the correct information but just in case, we do a repeat after 8 seconds.
        handler.postDelayed({ Log.d(TAG, "Calling checkNetworkConnected from onLost with delay $INITIAL_ON_LOST_DELAY_MS"); checkNetworkConnected() }, INITIAL_ON_LOST_DELAY_MS)
        handler.postDelayed({ Log.d(TAG, "Calling checkNetworkConnected from onLost with delay $RECHECK_ON_LOST_DELAY_MS"); checkNetworkConnected() }, RECHECK_ON_LOST_DELAY_MS)
        //handler.postDelayed({ Log.d(TAG, "Calling checkNetworkConnected from onLost with delay $RECHECK_ON_LOST_DELAY_MS"); writeToLogFileLocation(logFile, "Calling checkNetworkConnected from onLost with delay $RECHECK_ON_LOST_DELAY_MS"); checkNetworkConnected() }, RECHECK_ON_LOST_DELAY_MS)
    }

    override fun onUnavailable() {
        super.onUnavailable()
        ConnectionDiagnostics.network(NetworkTransition.UNAVAILABLE, false, null)
        Log.d(TAG, "onUnavailable called")
        checkNetworkConnected()
    }

    companion object {
        private val TAG = "WearTAK-" + NetworkManager::class.java.simpleName
        private val NETWORK_MANAGER = NetworkManager(FLAG_INCLUDE_LOCATION_INFO)
        public fun getInstance() : NetworkManager {
            return NETWORK_MANAGER
        }
        private var INITIAL_ON_LOST_DELAY_MS = 2000L
        private var RECHECK_ON_LOST_DELAY_MS = 10000L
        var isNetworkConnected : Boolean = false
        val isNetworkConnectedLiveData = MutableLiveData<Boolean>(false)
        var isWifi : Boolean = false
        val isWifiConnectedLiveData = MutableLiveData<Boolean>(false)
        var connectedWifiSsid : String? = null
        var isCellular : Boolean = false
        val isCellularConnectedLiveData = MutableLiveData<Boolean>(false)
        val context: Context = MainActivity.Constants.mainActivity.applicationContext



    }
}