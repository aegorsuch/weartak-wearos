package com.aitech.weartak_civ.integration.plugin

internal object PluginStatusMapper {
    fun mapLoadingStatus(
        packageName: String,
        snapshot: PluginDiagnostics.Snapshot,
    ): PluginLoadingStatus {
        return when {
            packageName in snapshot.rejected -> PluginLoadingStatus.ERROR
            packageName in snapshot.connected -> PluginLoadingStatus.LOADED
            packageName in snapshot.bound -> PluginLoadingStatus.BINDING
            packageName in snapshot.discovered -> PluginLoadingStatus.NOT_LOADED
            else -> PluginLoadingStatus.NOT_INSTALLED
        }
    }

    fun mapCompatibility(
        packageName: String,
        snapshot: PluginDiagnostics.Snapshot,
    ): PluginCompatibilityStatus {
        val rejection = snapshot.lastAdmissionDetailByPackage[packageName]?.lowercase().orEmpty()
        return when {
            rejection.contains("api_version_mismatch") -> PluginCompatibilityStatus.INCOMPATIBLE
            packageName in snapshot.connected -> PluginCompatibilityStatus.COMPATIBLE
            packageName in snapshot.rejected -> PluginCompatibilityStatus.UNKNOWN
            else -> PluginCompatibilityStatus.UNKNOWN
        }
    }

    fun isLoaded(status: PluginLoadingStatus): Boolean = status == PluginLoadingStatus.LOADED

    fun isError(status: PluginLoadingStatus): Boolean = status == PluginLoadingStatus.ERROR
}
