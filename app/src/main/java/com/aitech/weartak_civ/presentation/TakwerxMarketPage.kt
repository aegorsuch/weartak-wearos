package com.aitech.weartak_civ.presentation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.integration.plugin.PluginViewModel
import com.aitech.weartak_civ.market.MarketCatalogException
import com.aitech.weartak_civ.market.MarketInstallCoordinator
import com.aitech.weartak_civ.market.MarketInstallResult
import com.aitech.weartak_civ.market.MarketInstallState
import com.aitech.weartak_civ.market.MarketInstallStatusReceiver
import com.aitech.weartak_civ.market.MarketRefreshStatus
import com.aitech.weartak_civ.market.MarketPlugin
import com.aitech.weartak_civ.market.MarketPublisherType
import com.aitech.weartak_civ.market.MarketSecurityPolicy
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
    val downloadVerifying = stringResource(R.string.market_download_verifying)
    val installerConfirmation = stringResource(R.string.market_installer_confirmation)
    val installError = stringResource(R.string.market_install_error)
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
    val installer = remember(catalogRepository) { MarketInstallCoordinator(context, catalogRepository) }
    val scope = rememberCoroutineScope()
    val catalog = snapshot.catalog
    val loading = snapshot.status == MarketRefreshStatus.CHECKING
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var activePackage by remember { mutableStateOf<String?>(null) }
    var installedRevision by remember { mutableIntStateOf(0) }
    val displayPlugins = remember(catalog, installedRevision) {
        catalog?.plugins.orEmpty().map { plugin ->
            try {
                MarketPluginDisplay(
                    plugin = plugin,
                    installed = installer.installedPackage(plugin.packageName),
                    installState = installer.installState(plugin),
                    lastResult = MarketInstallStatusReceiver.lastStatus(context, plugin.packageName),
                )
            } catch (error: MarketCatalogException) {
                Log.e(TAG, "Cannot verify installed plugin state: ${plugin.packageName}", error)
                MarketPluginDisplay(
                    plugin = plugin,
                    installed = null,
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
            MarketInstallStatusReceiver.clearStatus(context, plugin.packageName)
            if (!installer.canInstallPackages()) {
                operationMessage = permissionNeeded
                try {
                    context.startActivity(installer.installPermissionIntent())
                } catch (error: Exception) {
                    Log.e(TAG, "Cannot open unknown-source install settings", error)
                    operationMessage = permissionSettingsError
                }
                return@launch
            }

            activePackage = plugin.packageName
            operationMessage = downloadVerifying
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
                Text(
                    stringResource(R.string.market_intro),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colors.onSurface,
                )
            }
            item {
                Chip(
                    onClick = ::refreshCatalog,
                    enabled = updateMonitor.isConfigured && !loading,
                    label = {
                        Text(stringResource(if (loading) R.string.market_loading else R.string.market_refresh))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val statusMessage = when (snapshot.status) {
                MarketRefreshStatus.NOT_CONFIGURED -> R.string.market_not_configured
                MarketRefreshStatus.OFFLINE -> R.string.market_offline
                MarketRefreshStatus.CHECKING -> if (catalog == null) R.string.market_loading else null
                MarketRefreshStatus.INVALID_CATALOG -> R.string.market_catalog_invalid
                MarketRefreshStatus.UNAVAILABLE -> R.string.market_catalog_unavailable
                MarketRefreshStatus.READY -> when {
                    snapshot.availableUpdates.isNotEmpty() -> R.string.market_update_available
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

            operationMessage?.let { message ->
                item {
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        color = MaterialTheme.colors.onSurface,
                    )
                }
            }
            displayPlugins.forEach { display ->
                item {
                    MarketPluginEntry(
                        plugin = display.plugin,
                        installedVersionName = display.installed?.versionName
                            ?: display.installed?.versionCode?.toString(),
                        installState = display.installState,
                        installerPermission = installer.canInstallPackages(),
                        busy = activePackage == display.plugin.packageName,
                        installResult = display.lastResult?.second,
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
    val installed: com.aitech.weartak_civ.market.InstalledMarketPackage?,
    val installState: MarketInstallState,
    val lastResult: Pair<String, String>?,
)

@Composable
private fun MarketPluginEntry(
    plugin: MarketPlugin,
    installedVersionName: String?,
    installState: MarketInstallState,
    installerPermission: Boolean,
    busy: Boolean,
    installResult: String?,
    onInstall: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            plugin.name,
            style = MaterialTheme.typography.title3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colors.onSurface,
        )
        Text(
            stringResource(
                R.string.market_publisher_version,
                stringResource(
                    if (plugin.publisherType == MarketPublisherType.OFFICIAL) {
                        R.string.market_official
                    } else {
                        R.string.market_community
                    },
                ),
                plugin.publisher,
                plugin.versionName,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colors.onSurface,
        )
        plugin.sourceUrl?.let { sourceUrl ->
            val sourceHost = Uri.parse(sourceUrl).host.orEmpty()
            Chip(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                    } catch (error: ActivityNotFoundException) {
                        Log.w(TAG, "No app can open plugin source link", error)
                        Toast.makeText(context, R.string.market_source_unavailable, Toast.LENGTH_LONG).show()
                    }
                },
                label = { Text(stringResource(R.string.market_source)) },
                secondaryLabel = { Text(sourceHost, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            plugin.description,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colors.onSurface,
        )
        Text(
            stringResource(
                if (MarketSecurityPolicy.hostCompatible(plugin, BuildConfig.VERSION_CODE.toLong())) {
                    R.string.market_compatible
                } else {
                    R.string.market_incompatible
                },
            ),
            color = if (installState == MarketInstallState.INCOMPATIBLE) {
                MaterialTheme.colors.error
            } else {
                MaterialTheme.colors.onSurface
            },
        )
        if (installedVersionName != null) {
            Text(
                stringResource(R.string.market_installed_version, installedVersionName),
                color = MaterialTheme.colors.onSurface,
            )
        }
        if (installResult != null) {
            Text(installResult, color = MaterialTheme.colors.onSurface)
        }
        val actionText = stringResource(
            when (installState) {
                MarketInstallState.INSTALL -> if (installerPermission) R.string.market_install else R.string.market_permission_needed
                MarketInstallState.UPDATE -> if (installerPermission) R.string.market_update else R.string.market_permission_needed
                MarketInstallState.UP_TO_DATE -> R.string.market_up_to_date
                MarketInstallState.NEWER_VERSION_INSTALLED -> R.string.market_newer_installed
                MarketInstallState.INCOMPATIBLE -> R.string.market_incompatible
                MarketInstallState.SIGNER_MISMATCH -> R.string.market_signer_mismatch
                MarketInstallState.PLAY_MANAGED_HOST -> R.string.market_play_managed
                MarketInstallState.PACKAGE_QUERY_FAILED -> R.string.market_package_unavailable
            },
        )
        Chip(
            onClick = onInstall,
            enabled = !busy && (installState == MarketInstallState.INSTALL ||
                installState == MarketInstallState.UPDATE),
            label = { Text(if (busy) stringResource(R.string.market_download_verifying) else actionText) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val TAG = "TakwerxMarketPage"
