package com.aitech.weartak_civ.presentation

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.integration.plugin.PluginViewModel
import com.aitech.weartak_civ.market.MarketCatalogException
import com.aitech.weartak_civ.market.MarketInstallCoordinator
import com.aitech.weartak_civ.market.MarketInstallResult
import com.aitech.weartak_civ.market.MarketInstallState
import com.aitech.weartak_civ.market.MarketInstallStatusReceiver
import com.aitech.weartak_civ.market.MarketRefreshStatus
import com.aitech.weartak_civ.market.MarketPlugin
import kotlinx.coroutines.launch

@Composable
internal fun TakwerxMarketPage(
    navController: NavController,
    pluginViewModel: PluginViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val updateMonitor = remember(context.applicationContext) {
        (context.applicationContext as WearTakApplication).marketUpdateMonitor
    }
    val snapshot by updateMonitor.snapshot.collectAsStateWithLifecycle()
    val permissionNeeded = stringResource(R.string.market_permission_needed)
    val permissionSettingsError = stringResource(R.string.market_permission_settings_error)
    val installerConfirmation = stringResource(R.string.market_installer_confirmation)
    val installError = stringResource(R.string.market_install_error)
    val marketLocked = stringResource(R.string.market_locked)
    val installStateMessages = mapOf(
        MarketInstallState.INSTALL to stringResource(R.string.market_install),
        MarketInstallState.UPDATE to stringResource(R.string.market_update),
        MarketInstallState.UP_TO_DATE to stringResource(R.string.market_up_to_date),
        MarketInstallState.NEWER_VERSION_INSTALLED to stringResource(R.string.market_newer_installed),
        MarketInstallState.INCOMPATIBLE to stringResource(R.string.market_incompatible),
        MarketInstallState.SIGNER_MISMATCH to stringResource(R.string.market_signer_mismatch),
        MarketInstallState.PLAY_MANAGED_HOST to stringResource(R.string.market_play_managed),
        MarketInstallState.PACKAGE_QUERY_FAILED to stringResource(R.string.market_package_unavailable),
    )
    val catalogRepository = updateMonitor.catalogRepository
    val installer = remember(catalogRepository) {
        MarketInstallCoordinator(context, catalogRepository, updateMonitor.marketLock)
    }
    val scope = rememberCoroutineScope()
    val catalog = snapshot.catalog
    val loading = snapshot.status == MarketRefreshStatus.CHECKING
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var operationPackage by remember { mutableStateOf<String?>(null) }
    var activePackage by remember { mutableStateOf<String?>(null) }
    var installedRevision by remember { mutableIntStateOf(0) }
    val displayPlugins = remember(catalog, installedRevision) {
        catalog?.plugins.orEmpty().map { plugin ->
            try {
                MarketPluginDisplay(
                    plugin = plugin,
                    installState = installer.installState(plugin),
                    lastResult = MarketInstallStatusReceiver.lastStatus(context, plugin.packageName),
                )
            } catch (error: MarketCatalogException) {
                Log.e(TAG, "Cannot verify installed plugin state: ${plugin.packageName}", error)
                MarketPluginDisplay(
                    plugin = plugin,
                    installState = MarketInstallState.PACKAGE_QUERY_FAILED,
                    lastResult = null,
                )
            }
        }
    }

    fun refreshCatalog() {
        updateMonitor.refresh()
    }

    LaunchedEffect(snapshot.availableUpdates) {
        if (snapshot.availableUpdates.isNotEmpty()) updateMonitor.markUpdatesReviewed()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installedRevision++
                pluginViewModel.refreshBindings()
                pluginViewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun install(plugin: MarketPlugin) {
        scope.launch {
            operationMessage = null
            operationPackage = plugin.packageName
            MarketInstallStatusReceiver.clearStatus(context, plugin.packageName)
            installedRevision++
            activePackage = plugin.packageName
            try {
                when (val result = installer.install(plugin)) {
                    is MarketInstallResult.Submitted -> {
                        operationMessage = installerConfirmation
                    }
                    MarketInstallResult.PermissionRequired -> {
                        operationMessage = permissionNeeded
                        try {
                            context.startActivity(installer.installPermissionIntent())
                        } catch (error: Exception) {
                            Log.e(TAG, "Cannot open unknown-source install settings", error)
                            operationMessage = permissionSettingsError
                        }
                    }
                    MarketInstallResult.Locked -> {
                        operationMessage = marketLocked
                    }
                    is MarketInstallResult.Blocked -> {
                        operationMessage = installStateMessages.getValue(result.state)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Plugin install failed: ${plugin.packageName}", error)
                operationMessage = error.message ?: installError
            } finally {
                activePackage = null
                installedRevision++
            }
        }
    }

    WearTAKPageWithBackArrow(
        title = stringResource(R.string.takwerx_market_title),
        columnContent = {
            item {
                Chip(
                    onClick = ::refreshCatalog,
                    enabled = updateMonitor.isConfigured && !loading && snapshot.status != MarketRefreshStatus.LOCKED,
                    label = {
                        Text(stringResource(if (loading) R.string.market_loading else R.string.market_refresh))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val statusMessage = when (snapshot.status) {
                MarketRefreshStatus.LOCKED -> R.string.market_locked
                MarketRefreshStatus.NOT_CONFIGURED -> R.string.market_not_configured
                MarketRefreshStatus.OFFLINE -> R.string.market_offline
                MarketRefreshStatus.CHECKING -> if (catalog == null) R.string.market_loading else null
                MarketRefreshStatus.INVALID_CATALOG -> R.string.market_catalog_invalid
                MarketRefreshStatus.UNAVAILABLE -> R.string.market_catalog_unavailable
                MarketRefreshStatus.READY -> when {
                    snapshot.availableUpdates.isNotEmpty() -> null
                    catalog?.plugins.isNullOrEmpty() -> R.string.market_empty
                    else -> R.string.market_no_updates
                }
            }
            statusMessage?.let { message ->
                item {
                    Text(
                        stringResource(message),
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        color = if (snapshot.status == MarketRefreshStatus.INVALID_CATALOG ||
                            snapshot.status == MarketRefreshStatus.UNAVAILABLE
                        ) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
                    )
                }
            }

            displayPlugins.forEach { display ->
                item {
                    MarketPluginEntry(
                        plugin = display.plugin,
                        installState = display.installState,
                        canStartInstall = activePackage == null,
                        installing = activePackage == display.plugin.packageName,
                        installResult = if (operationPackage == display.plugin.packageName) {
                            display.lastResult?.second ?: operationMessage
                        } else {
                            display.lastResult?.second
                        },
                        onInstall = { install(display.plugin) },
                    )
                }
            }
        },
        navController = navController,
        screenTag = "takwerx_market",
    )
}

private data class MarketPluginDisplay(
    val plugin: MarketPlugin,
    val installState: MarketInstallState,
    val lastResult: Pair<String, String>?,
)

@Composable
internal fun MarketPluginEntry(
    plugin: MarketPlugin,
    installState: MarketInstallState,
    canStartInstall: Boolean,
    installing: Boolean,
    installResult: String?,
    onInstall: () -> Unit,
) {
    val download = installState == MarketInstallState.INSTALL || installState == MarketInstallState.UPDATE
    val current = installState == MarketInstallState.UP_TO_DATE ||
        installState == MarketInstallState.NEWER_VERSION_INSTALLED
    val statusText = stringResource(
        when (installState) {
            MarketInstallState.INSTALL -> R.string.market_install
            MarketInstallState.UPDATE -> R.string.market_update
            MarketInstallState.UP_TO_DATE -> R.string.market_up_to_date
            MarketInstallState.NEWER_VERSION_INSTALLED -> R.string.market_newer_installed
            MarketInstallState.INCOMPATIBLE -> R.string.market_incompatible
            MarketInstallState.SIGNER_MISMATCH -> R.string.market_signer_mismatch
            MarketInstallState.PLAY_MANAGED_HOST -> R.string.market_play_managed
            MarketInstallState.PACKAGE_QUERY_FAILED -> R.string.market_package_unavailable
        },
    )
    val detail = when {
        installing -> null
        !download && !current -> statusText
        else -> installResult
    }
    val progressDescription = stringResource(R.string.market_download_verifying)
    Chip(
        onClick = onInstall,
        enabled = canStartInstall && download,
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        plugin.hostUpdate -> stringResource(R.string.market_host_name)
                        plugin.publisher.trim().equals("aegorsuch", ignoreCase = true) -> plugin.name
                        else -> stringResource(R.string.market_plugin_by_publisher, plugin.name, plugin.publisher)
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics {
                            contentDescription = progressDescription
                        },
                    )
                } else if (download) {
                    Icon(painterResource(R.drawable.ic_market_download), contentDescription = statusText)
                } else if (current) {
                    Icon(Icons.Filled.Check, contentDescription = statusText)
                }
            }
        },
        secondaryLabel = detail?.let { message -> { Text(message) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val TAG = "TakwerxMarketPage"
