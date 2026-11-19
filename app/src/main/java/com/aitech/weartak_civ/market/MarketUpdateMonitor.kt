package com.aitech.weartak_civ.market

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

internal enum class MarketRefreshStatus {
    NOT_CONFIGURED,
    OFFLINE,
    CHECKING,
    READY,
    INVALID_CATALOG,
    UNAVAILABLE,
}

internal data class MarketUpdateSnapshot(
    val status: MarketRefreshStatus,
    val catalog: MarketCatalog? = null,
    val availableUpdates: List<MarketPlugin> = emptyList(),
    val showUpdateWarning: Boolean = false,
)

internal object MarketUpdatePolicy {
    fun releaseKey(updates: Collection<MarketPlugin>): String =
        updates.sortedBy { it.packageName }
            .joinToString("|") { "${it.packageName}:${it.versionCode}" }

    fun shouldShowWarning(updates: Collection<MarketPlugin>, reviewedReleaseKey: String?): Boolean =
        updates.isNotEmpty() && releaseKey(updates) != reviewedReleaseKey
}

internal class MarketUpdateMonitor(
    context: Context,
    internal val catalogRepository: MarketCatalogRepository =
        MarketCatalogRepository(MarketConfiguration.fromBuildConfig()),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val installer = MarketInstallCoordinator(appContext, catalogRepository)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var started = false
    private var internetAvailable = false
    private var refreshJob: Job? = null

    private val _snapshot = MutableStateFlow(
        MarketUpdateSnapshot(
            status = if (MarketConfiguration.fromBuildConfig().isConfigured) {
                MarketRefreshStatus.OFFLINE
            } else {
                MarketRefreshStatus.NOT_CONFIGURED
            },
        ),
    )
    val snapshot: StateFlow<MarketUpdateSnapshot> = _snapshot.asStateFlow()
    val isConfigured: Boolean = MarketConfiguration.fromBuildConfig().isConfigured

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            setInternetAvailable(
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }

        override fun onLost(network: Network) {
            setInternetAvailable(hasValidatedInternet())
        }
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        if (!isConfigured) return

        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Cannot monitor default network for Market catalog updates", error)
        }
        setInternetAvailable(hasValidatedInternet())
    }

    fun refresh() {
        if (!isConfigured) {
            _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.NOT_CONFIGURED)
            return
        }
        synchronized(lock) {
            if (!internetAvailable) {
                _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.OFFLINE)
                return
            }
            if (refreshJob?.isActive == true) return
            _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.CHECKING)
            refreshJob = scope.launch { refreshCatalog() }
        }
    }

    fun markUpdatesReviewed() {
        synchronized(lock) {
            val current = _snapshot.value
            val releaseKey = MarketUpdatePolicy.releaseKey(current.availableUpdates)
            if (releaseKey.isEmpty()) return
            preferences.edit().putString(REVIEWED_RELEASE_KEY, releaseKey).apply()
            _snapshot.value = current.copy(showUpdateWarning = false)
        }
    }

    private suspend fun refreshCatalog() {
        try {
            val catalog = catalogRepository.fetchCatalog(appContext.packageName)
            val updates = mutableListOf<MarketPlugin>()
            var verificationFailed = false
            catalog.plugins.forEach { plugin ->
                try {
                    if (installer.installState(plugin) == MarketInstallState.UPDATE) {
                        updates += plugin
                    }
                } catch (error: MarketCatalogException) {
                    verificationFailed = true
                    Log.e(TAG, "Cannot verify installed package for Market update: ${plugin.packageName}", error)
                }
            }
            val reviewedKey = preferences.getString(REVIEWED_RELEASE_KEY, null)
            _snapshot.value = MarketUpdateSnapshot(
                status = if (verificationFailed) MarketRefreshStatus.UNAVAILABLE else MarketRefreshStatus.READY,
                catalog = catalog,
                availableUpdates = updates,
                showUpdateWarning = MarketUpdatePolicy.shouldShowWarning(updates, reviewedKey),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: MarketCatalogException) {
            Log.e(TAG, "Could not load signed Market catalog", error)
            if (error.invalidCatalog) {
                _snapshot.value = MarketUpdateSnapshot(status = MarketRefreshStatus.INVALID_CATALOG)
            } else {
                _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.UNAVAILABLE)
            }
        } catch (error: IOException) {
            Log.i(TAG, "Market catalog unavailable without internet access", error)
            _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.OFFLINE)
        } catch (error: Exception) {
            Log.e(TAG, "Could not check signed Market catalog", error)
            _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.UNAVAILABLE)
        }
    }

    private fun setInternetAvailable(available: Boolean) {
        val becameAvailable = synchronized(lock) {
            val changed = !internetAvailable && available
            internetAvailable = available
            if (!available && _snapshot.value.status != MarketRefreshStatus.NOT_CONFIGURED) {
                _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.OFFLINE)
            }
            changed
        }
        if (becameAvailable) refresh()
    }

    private fun hasValidatedInternet(): Boolean {
        val activeNetwork = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun close() {
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // The callback was not registered.
        }
        scope.cancel()
    }

    private companion object {
        const val PREFERENCES = "takwerx_market_updates"
        const val REVIEWED_RELEASE_KEY = "reviewed_release_key"
        const val TAG = "TakwerxMarket"
    }
}
