package com.aitech.weartak_civ.presentation

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.MoppStatus
import com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability
import com.aitech.weartak_civ.integration.plugin.PluginUI

internal enum class BuiltInPlugin(@DrawableRes val brandIconRes: Int? = null) {
    COMMANDS(R.drawable.ic_commands_megaphone),
    COMPANION(R.drawable.weartak_companion_plugin),
    CRM(R.drawable.ic_plugin_gauge),
    DART(R.drawable.ic_integration_certus),
    DATA_SYNC(R.drawable.ic_data_sync),
    DITTO(R.drawable.ic_integration_ditto),
    EVERYWHERE_COMMS(R.drawable.ic_integration_everywhere),
    FALCON(R.drawable.ic_integration_certus),
    SENSOR_MANAGER(R.drawable.ic_sensor_manager),
    SOMEWEAR_LABS(R.drawable.ic_integration_somewear);

    val locksDuringPhoneManagement: Boolean
        get() = when (this) {
            DATA_SYNC, DITTO, EVERYWHERE_COMMS, SOMEWEAR_LABS -> true
            else -> false
        }
}

@Composable
internal fun BuiltInPluginToggleChip(
    kind: BuiltInPlugin,
    phoneManagedSettings: Boolean,
    title: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val bonded = phoneManagedSettings && kind.locksDuringPhoneManagement
    WearTAKSplitToggleChip(
        appIcon = { BuiltInPluginBrandIcon(kind) },
        title = title,
        label = networkControlSubtitle(label, bonded),
        checked = checked,
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        enabled = !bonded
    )
}

internal sealed interface PluginMenuEntry {
    val key: String
    val title: String

    data class BuiltIn(val kind: BuiltInPlugin, override val title: String) : PluginMenuEntry {
        override val key = "builtin:${kind.name}"
    }

    data class Installed(val plugin: PluginUI) : PluginMenuEntry {
        override val key = "installed:${plugin.pluginId}"
        override val title = plugin.title
    }
}

internal fun List<PluginMenuEntry>.alphabetizedPluginEntries(): List<PluginMenuEntry> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

internal fun List<PluginUI>.installedPluginMenuEntries(): List<PluginMenuEntry> =
    map { PluginMenuEntry.Installed(it) }

internal fun PluginUI.menuLabel(moppStatus: MoppStatus?, noneLabel: String): String? =
    if (packageName == MoppPluginAvailability.PACKAGE && moppStatus != null) {
        if (moppStatus == MoppStatus.NONE) noneLabel else "MOPP${moppStatus.key}"
    } else {
        menuLabel
    }

internal const val HOSTED_PLUGIN_MAIN_UI_ROUTE_PATTERN = "plugin_main_ui/{pluginId}"

internal fun hostedPluginMainUiRoute(pluginId: String): String =
    "plugin_main_ui/${android.net.Uri.encode(pluginId)}"
