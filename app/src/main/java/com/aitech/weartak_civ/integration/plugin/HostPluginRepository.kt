package com.aitech.weartak_civ.integration.plugin

import android.content.pm.PackageManager
import android.content.ComponentName
import android.os.Build
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host-side adapter that projects plugin binder state into UI-friendly plugin records.
 * No UI code depends on this class directly yet; it is the clean backend seam for the UI team.
 */
class HostPluginRepository(
    private val app: WearTakApplication,
    private val manager: PluginManager,
) : PluginRepository, PluginManager.PluginTopologyListener {

    private val identityStore = PluginIdentityStore()

    private val _plugins = MutableStateFlow<List<PluginUI>>(emptyList())
    override val plugins: StateFlow<List<PluginUI>> = _plugins.asStateFlow()

    private val _selectedPlugin = MutableStateFlow<PluginUI?>(null)
    override val selectedPlugin: StateFlow<PluginUI?> = _selectedPlugin.asStateFlow()

    private val _operationOutcome = MutableStateFlow<PluginRepositoryOutcome?>(null)
    override val operationOutcome: StateFlow<PluginRepositoryOutcome?> = _operationOutcome.asStateFlow()

    private val stateLock = Any()
    private var selectedPackageName: String? = null

    private companion object {
        const val UNKNOWN_PLUGIN_IDENTIFIER = "Unknown plugin identifier"
    }

    init {
        manager.addTopologyListener(this)
        refresh()
    }

    override fun onPluginTopologyChanged() {
        refresh()
    }

    override fun refresh() {
        val diagnostics = PluginDiagnostics.snapshot()
        val discoveredComponents = manager.discoverPluginComponents().associateBy { it.packageName }
        val discoveredPackageNames = discoveredComponents.keys
        synchronized(stateLock) {
            discoveredPackageNames.forEach { packageName ->
                val connection = manager.connectionFor(packageName)
                val info = connection?.pluginInfoSnapshot()
                if (info != null) {
                    identityStore.rememberDeclaredPluginId(packageName, info.pluginId)
                } else {
                    identityStore.rememberPackage(packageName)
                }
            }

            val visiblePackages = identityStore.visiblePackageNames(discoveredPackageNames)
            val records = visiblePackages.map { packageName ->
                buildPluginUi(packageName, diagnostics, discoveredComponents[packageName])
            }.sortedWith(compareBy<PluginUI> { it.title.lowercase() }.thenBy { it.packageName })

            selectedPackageName = selectedPackageName?.takeIf { it in visiblePackages }
            _plugins.value = records
            _selectedPlugin.value = selectedPackageName?.let { packageName ->
                records.firstOrNull { it.packageName == packageName }
            }
        }
    }

    override fun refreshBindings() {
        manager.refreshBindings()
    }

    override fun addPlugin(pluginId: String) {
        val packageName = resolvePackageName(pluginId)
        if (packageName == null) {
            publishFailure("addPlugin", pluginId, UNKNOWN_PLUGIN_IDENTIFIER)
            return
        }
        synchronized(stateLock) {
            identityStore.setHidden(packageName, false)
        }
        publishSuccess("addPlugin", pluginId, packageName)
        refresh()
    }

    override fun select(pluginId: String?) {
        if (pluginId == null) {
            synchronized(stateLock) {
                selectedPackageName = null
                _selectedPlugin.value = null
            }
            return
        }
        val packageName = resolvePackageName(pluginId)
        if (packageName == null) {
            synchronized(stateLock) {
                selectedPackageName = null
                _selectedPlugin.value = null
            }
            publishFailure("select", pluginId, UNKNOWN_PLUGIN_IDENTIFIER)
            return
        }
        synchronized(stateLock) {
            selectedPackageName = packageName
            _selectedPlugin.value = _plugins.value.firstOrNull { it.packageName == packageName }
        }
        publishSuccess("select", pluginId, packageName)
    }

    override fun loadPlugin(pluginId: String) {
        Log.d("PluginRepo", "Plugin load called")

        val packageName = resolvePackageName(pluginId)
        if (packageName == null) {
            publishFailure("loadPlugin", pluginId, UNKNOWN_PLUGIN_IDENTIFIER)
            return
        }
        if (!manager.bindPackage(packageName)) {
            publishFailure("loadPlugin", pluginId, "Unable to bind package $packageName")
            return
        }
        publishSuccess("loadPlugin", pluginId, packageName)
        refresh()
    }

    override fun unloadPlugin(pluginId: String) {
        val packageName = resolvePackageName(pluginId)
        if (packageName == null) {
            publishFailure("unloadPlugin", pluginId, UNKNOWN_PLUGIN_IDENTIFIER)
            return
        }
        manager.unbindPackage(packageName)
        publishSuccess("unloadPlugin", pluginId, packageName)
        refresh()
    }

    override fun removePlugin(pluginId: String) {
        val packageName = resolvePackageName(pluginId)
        if (packageName == null) {
            publishFailure("removePlugin", pluginId, UNKNOWN_PLUGIN_IDENTIFIER)
            return
        }
        synchronized(stateLock) {
            identityStore.setHidden(packageName, true)
            if (selectedPackageName == packageName) {
                selectedPackageName = null
                _selectedPlugin.value = null
            }
        }
        manager.unbindPackage(packageName)
        publishSuccess("removePlugin", pluginId, packageName)
        refresh()
    }

    override fun uninstallPlugin(pluginId: String): PluginUninstallRequest {
        val packageName = resolvePackageName(pluginId)
        if (packageName == null) {
            return PluginUninstallRequest.Failure(
                pluginId = pluginId,
                reason = UNKNOWN_PLUGIN_IDENTIFIER,
            )
        }
        return PluginUninstallRequest.Ready(packageName = packageName)
    }

    override fun close() {
        manager.removeTopologyListener(this)
    }

    internal fun onPackageRemoved(packageName: String) {
        synchronized(stateLock) {
            identityStore.removePackage(packageName)
            if (selectedPackageName == packageName) {
                selectedPackageName = null
                _selectedPlugin.value = null
            }
        }
        refresh()
    }

    private fun buildPluginUi(
        packageName: String,
        diagnostics: PluginDiagnostics.Snapshot,
        component: ComponentName?,
    ): PluginUI {
        val connection = manager.connectionFor(packageName)
        val info = connection?.pluginInfoSnapshot()
        val packageInfo = runCatching { loadPackageInfo(packageName) }.getOrNull()
        val pluginId = synchronized(stateLock) {
            if (info?.pluginId != null) {
                identityStore.rememberDeclaredPluginId(packageName, info.pluginId)
            } else {
                identityStore.rememberPackage(packageName)
            }
            identityStore.lastKnownPluginId(packageName)
        }
        val title = info?.displayName
            ?: packageInfo?.applicationInfo?.loadLabel(app.packageManager)?.toString()
            ?: packageName
        val versionName = info?.versionName
            ?: packageInfo?.versionName
            ?: "unknown"
        val desc = info?.description.orEmpty()
        val status = PluginStatusMapper.mapLoadingStatus(packageName, diagnostics)
        val compatibility = PluginStatusMapper.mapCompatibility(packageName, diagnostics)
        val errorMessage = buildErrorMessage(packageName, diagnostics)
        val icon = PluginIconResolver.resolve(app, packageName)
        return PluginUI(
            icon = icon,
            title = title,
            desc = desc,
            loadingStatus = status,
            errorMessage = errorMessage,
            pluginId = pluginId,
            versionName = versionName,
            packageName = packageName,
            compatibility = compatibility,
            isLoaded = PluginStatusMapper.isLoaded(status),
            isEnabled = manager.isEnabled(packageName),
            menuSubtitle = component?.let { PluginServiceQuery.menuSubtitle(app.packageManager, it) },
        )
    }

    private fun buildErrorMessage(
        packageName: String,
        diagnostics: PluginDiagnostics.Snapshot,
    ): String {
        diagnostics.lastAdmissionDetailByPackage[packageName]?.let { if (it.isNotBlank()) return it }
        val callback = PluginDiagnostics.hostCallbackState.value
        callback.lastPluginErrorByPlugin[packageName]?.message?.takeIf { it.isNotBlank() }?.let { return it }
        return if (packageName in diagnostics.rejected) {
            "Plugin rejected"
        } else {
            "None"
        }
    }

    private fun resolvePackageName(pluginId: String): String? = synchronized(stateLock) {
        identityStore.resolvePackageName(pluginId)
    }

    private fun publishSuccess(
        action: String,
        pluginId: String,
        packageName: String,
    ) {
        _operationOutcome.value = PluginRepositoryOutcome.Success(
            action = action,
            pluginId = pluginId,
            packageName = packageName,
        )
    }

    private fun publishFailure(
        action: String,
        pluginId: String,
        reason: String,
    ) {
        _operationOutcome.value = PluginRepositoryOutcome.Failure(
            action = action,
            pluginId = pluginId,
            reason = reason,
        )
    }

    private fun loadPackageInfo(packageName: String): android.content.pm.PackageInfo? = runCatching {
        val pm = app.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
    }.getOrNull()
}
