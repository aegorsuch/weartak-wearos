package com.aitech.weartak_civ.presentation

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.MoppStatus
import com.aitech.weartak_civ.integration.plugin.HandlerExertion
import com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability
import com.aitech.weartak_civ.integration.plugin.PluginDetectionEventBridge
import com.aitech.weartak_civ.integration.plugin.PluginUI
import com.aitech.weartak_civ.integration.plugin.TrustedCommunityPlugin

internal enum class BuiltInPlugin(@DrawableRes val brandIconRes: Int? = null) {
    BATDOK(R.drawable.batdok),
    COMMANDS(R.drawable.ic_commands_megaphone),
    COMPANION(R.drawable.weartak_companion_plugin),
    DART(R.drawable.ic_integration_certus),
    DATA_SYNC(R.drawable.ic_data_sync),
    DITTO(R.drawable.ic_integration_ditto),
    ESRI(R.drawable.plugin_esri_logo),
    EVERYWHERE_COMMS(R.drawable.ic_integration_everywhere),
    FALCON(R.drawable.ic_integration_certus),
    SENSOR_MANAGER(R.drawable.ic_sensor_manager),
    SOMEWEAR_LABS(R.drawable.ic_integration_somewear),
    TABLET_COMMAND(R.drawable.plugin_tablet_command_logo);

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
        modifier = Modifier.testTag("settings_plugin_${kind.name.lowercase(java.util.Locale.ROOT)}"),
        appIcon = { BuiltInPluginBrandIcon(kind) },
        title = title,
        label = networkControlSubtitle(label, bonded),
        checked = checked,
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        enabled = !bonded
    )
}

@Composable
internal fun AudioDetectorPluginChip(
    kind: BuiltInPlugin,
    title: String,
    phoneManagedSettings: Boolean,
    checked: Boolean,
    installed: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onUninstall: () -> Unit,
    onInfo: () -> Unit,
) {
    val chip: @Composable () -> Unit = {
        BuiltInPluginToggleChip(
            kind = kind,
            phoneManagedSettings = phoneManagedSettings,
            title = title,
            label = stringResource(R.string.unfunded_request),
            checked = checked,
            onCheckedChange = onCheckedChange,
            onClick = onDetails,
        )
    }
    if (installed) {
        WearTAKSwipeToRevealChip(
            onPrimaryActionClick = onUninstall,
            onSecondaryActionClick = onInfo,
            chipType = { chip() },
            testTag = "settings_plugin_${kind.name.lowercase(java.util.Locale.ROOT)}_management",
        )
    } else {
        chip()
    }
}

internal sealed interface PluginMenuEntry {
    val key: String
    val title: String

    data class BuiltIn(val kind: BuiltInPlugin, override val title: String) : PluginMenuEntry {
        override val key = "builtin:${kind.name}"
    }

    data class Market(override val title: String) : PluginMenuEntry {
        override val key = "market"
    }

    data class Installed(val plugin: PluginUI) : PluginMenuEntry {
        override val key = "installed:${plugin.pluginId}"
        override val title = if (plugin.packageName == HandlerExertion.PACKAGE) "Handler" else plugin.title
    }
}

internal fun List<PluginMenuEntry>.alphabetizedPluginEntries(): List<PluginMenuEntry> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

internal fun List<PluginMenuEntry>.withoutSomewearPlaceholder(): List<PluginMenuEntry> =
    filterNot { it is PluginMenuEntry.BuiltIn && it.kind == BuiltInPlugin.SOMEWEAR_LABS }

internal fun List<PluginUI>.installedPluginMenuEntries(): List<PluginMenuEntry> =
    filterNot {
        it.packageName == PluginDetectionEventBridge.DART_PACKAGE ||
            it.packageName == PluginDetectionEventBridge.FALCON_PACKAGE
    }
        .map { PluginMenuEntry.Installed(it) }

internal fun PluginUI.menuLabel(moppStatus: MoppStatus?, noneLabel: String): String? =
    if (packageName == MoppPluginAvailability.PACKAGE && moppStatus != null) {
        if (moppStatus == MoppStatus.NONE) noneLabel else "MOPP${moppStatus.key}"
    } else {
        menuLabel
    }

internal fun PluginUI.dittoMenuLabel(dittoSubtitle: String?, fallback: String?): String? =
    if (packageName == TrustedCommunityPlugin.PACKAGE && isLoaded) dittoSubtitle ?: fallback else fallback

internal const val HOSTED_PLUGIN_MAIN_UI_ROUTE_PATTERN = "plugin_main_ui/{pluginId}"

internal fun hostedPluginMainUiRoute(pluginId: String): String =
    "plugin_main_ui/${android.net.Uri.encode(pluginId)}"
