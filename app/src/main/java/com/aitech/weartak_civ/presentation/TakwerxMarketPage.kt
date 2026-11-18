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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.integration.plugin.PluginViewModel
import com.aitech.weartak_civ.market.MarketCatalog
import com.aitech.weartak_civ.market.MarketCatalogException
import com.aitech.weartak_civ.market.MarketCatalogRepository
import com.aitech.weartak_civ.market.MarketConfiguration
import com.aitech.weartak_civ.market.MarketInstallCoordinator
import com.aitech.weartak_civ.market.MarketInstallResult
import com.aitech.weartak_civ.market.MarketInstallState
import com.aitech.weartak_civ.market.MarketInstallStatusReceiver
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
    val configuration = remember { MarketConfiguration.fromBuildConfig() }
    val catalogRepository = remember(configuration) { MarketCatalogRepository(configuration) }
    val installer = remember(catalogRepository) { MarketInstallCoordinator(context, catalogRepository) }
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<MarketCatalog?>(null) }
    var loading by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
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
        if (!configuration.isConfigured) return
        scope.launch {
            loading = true
            catalogError = null
            try {
                catalog = catalogRepository.fetchCatalog(context.packageName)
            } catch (error: Exception) {
                Log.e(TAG, "Could not load signed plugin catalog", error)
                catalogError = error.message ?: context.getString(R.string.market_catalog_error)
                catalog = null
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(configuration) {
        refreshCatalog()
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
                operationMessage = context.getString(R.string.market_permission_needed)
                try {
                    context.startActivity(installer.installPermissionIntent())
                } catch (error: Exception) {
                    Log.e(TAG, "Cannot open unknown-source install settings", error)
                    operationMessage = context.getString(R.string.market_permission_settings_error)
                }
                return@launch
            }

            activePackage = plugin.packageName
            operationMessage = context.getString(R.string.market_download_verifying)
            try {
                when (val result = installer.install(plugin)) {
                    is MarketInstallResult.Submitted -> {
                        operationMessage = context.getString(R.string.market_installer_confirmation)
                    }
                    MarketInstallResult.PermissionRequired -> {
                        operationMessage = context.getString(R.string.market_permission_needed)
                        try {
                            context.startActivity(installer.installPermissionIntent())
                        } catch (error: Exception) {
                            Log.e(TAG, "Cannot open unknown-source install settings", error)
                            operationMessage = context.getString(R.string.market_permission_settings_error)
                        }
                    }
                    is MarketInstallResult.Blocked -> {
                        operationMessage = context.getString(
                            when (result.state) {
                                MarketInstallState.INSTALL -> R.string.market_install
                                MarketInstallState.UPDATE -> R.string.market_update
                                MarketInstallState.UP_TO_DATE -> R.string.market_up_to_date
                                MarketInstallState.NEWER_VERSION_INSTALLED -> R.string.market_newer_installed
                                MarketInstallState.INCOMPATIBLE -> R.string.market_incompatible
                                MarketInstallState.SIGNER_MISMATCH -> R.string.market_signer_mismatch
                                MarketInstallState.PLAY_MANAGED_HOST -> R.string.market_play_managed
                                MarketInstallState.PACKAGE_QUERY_FAILED -> R.string.market_package_unavailable
                            }
                        )
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Plugin install failed: ${plugin.packageName}", error)
                operationMessage = error.message ?: context.getString(R.string.market_install_error)
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
                    enabled = configuration.isConfigured && !loading,
                    label = {
                        Text(stringResource(if (loading) R.string.market_loading else R.string.market_refresh))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!configuration.isConfigured) {
                item {
                    Text(
                        stringResource(R.string.market_not_configured),
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        color = MaterialTheme.colors.onSurface,
                    )
                }
            } else if (loading && catalog == null) {
                item { Text(stringResource(R.string.market_loading), modifier = Modifier.padding(8.dp)) }
            } else if (catalogError != null) {
                item {
                    Text(
                        catalogError.orEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        color = MaterialTheme.colors.error,
                    )
                }
            } else if (catalog?.plugins.isNullOrEmpty()) {
                item { Text(stringResource(R.string.market_empty), modifier = Modifier.padding(8.dp)) }
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
