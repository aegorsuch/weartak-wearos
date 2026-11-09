package com.aitech.weartak_civ.integration.plugin

import kotlinx.coroutines.flow.StateFlow

/**
 * Backend plugin adapter contract for the host UI.
 *
 * The repository exposes host-side plugin discovery, visibility, load/unload, and uninstall
 * entry points to the UI layer without leaking plugin manager details.
 */
interface PluginRepository {
    /** Current visible plugin records projected for the UI list. */
    val plugins: StateFlow<List<PluginUI>>
    /** The currently selected plugin row, if any. */
    val selectedPlugin: StateFlow<PluginUI?>
    /**
     * Last repository action outcome for stateful operations such as load, unload, remove,
     * and other backend completion reporting. This is not the uninstall request handoff.
     */
    val operationOutcome: StateFlow<PluginRepositoryOutcome?>

    /** Rebuild the visible plugin list from discovery and diagnostic state. */
    fun refresh()
    /** Rescan and bind discovered plugin services so install-time changes can load. */
    fun refreshBindings()
    /** Re-enable an installed plugin and keep it visible in the plugin list. */
    fun addPlugin(pluginId: String)
    /** Update the current selected plugin row using a plugin identifier. */
    fun select(pluginId: String?)
    /** Request that a plugin be bound and loaded by the host manager. */
    fun loadPlugin(pluginId: String)
    /** Request that a plugin be unbound and unloaded by the host manager. */
    fun unloadPlugin(pluginId: String)
    /** Unload an installed plugin while keeping it visible in the host list. */
    fun removePlugin(pluginId: String)
    /**
     * Resolve and authorize a plugin identifier into a package target for OS uninstall launch.
     * Returns a request object instead of using the replaying outcome flow so the UI can launch
     * the uninstall dialog exactly once.
     */
    fun uninstallPlugin(pluginId: String): PluginUninstallRequest
    /** Release repository listeners and background linkage. */
    fun close()
}
