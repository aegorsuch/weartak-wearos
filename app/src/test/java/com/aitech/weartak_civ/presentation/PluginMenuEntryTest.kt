package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.integration.plugin.PluginLoadingStatus
import com.aitech.weartak_civ.integration.plugin.PluginUI
import com.aitech.weartak_civ.data.MoppStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginMenuEntryTest {
    @Test
    fun sortsBuiltInsAndInstalledPluginsTogetherIgnoringCase() {
        val entries = listOf(
            PluginMenuEntry.BuiltIn(BuiltInPlugin.SOMEWEAR_LABS, "Somewear Labs"),
            installed("zulu", "zulu"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.DITTO, "Ditto"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.EVERYWHERE_COMMS, "Everywhere Comms"),
            installed("alpha", "alpha"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.SENSOR_MANAGER, "Sensor Manager"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.COMMANDS, "Commands"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.COMPANION, "Companion Plugin"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.FALCON, "FALCON"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.DART, "DART"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.DATA_SYNC, "Data Sync"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.CRM, "CRM"),
            installed("com.weartak.plugin.mopp", "MOPP"),
        )

        assertEquals(
            listOf("alpha", "Commands", "Companion Plugin", "CRM", "DART", "Data Sync", "Ditto", "Everywhere Comms", "FALCON", "MOPP", "Sensor Manager", "Somewear Labs", "zulu"),
            entries.alphabetizedPluginEntries().map { it.title },
        )
        assertEquals("Somewear Labs", entries.first().title)
    }

    @Test
    fun duplicateNamesRetainDistinctKeysAndStableOrder() {
        val entries = listOf(
            PluginMenuEntry.BuiltIn(BuiltInPlugin.DITTO, "Ditto"),
            installed("DITTO", "DITTO"),
            installed("another-ditto", "ditto"),
        )

        val sorted = entries.alphabetizedPluginEntries()
        assertEquals(entries, sorted)
        assertEquals(3, sorted.map { it.key }.distinct().size)
    }

    @Test
    fun handlesEmptyList() {
        assertEquals(emptyList<PluginMenuEntry>(), emptyList<PluginMenuEntry>().alphabetizedPluginEntries())
    }

    @Test
    fun installedPluginsKeepPluginProvidedIdentityAndDisplayName() {
        val entries = listOf(
            pluginUi("org.example.telemetry", "Telemetry"),
            pluginUi("org.example.fieldtools", "Field Tools"),
        ).installedPluginMenuEntries()

        assertEquals(listOf("Telemetry", "Field Tools"), entries.map { it.title })
        assertEquals(
            listOf("org.example.telemetry", "org.example.fieldtools"),
            entries.map { (it as PluginMenuEntry.Installed).plugin.pluginId },
        )
    }

    @Test
    fun hostedRouteEncodesArbitraryPluginId() {
        assertEquals(
            "plugin_main_ui/org.example.field%2Ftools",
            hostedPluginMainUiRoute("org.example.field/tools"),
        )
    }

    @Test
    fun moppExistsOnlyAsInstalledPluginAndUsesNormalPluginRoute() {
        assertEquals(false, BuiltInPlugin.entries.any { it.name == "MOPP" })
        assertEquals(emptyList<PluginMenuEntry>(), emptyList<PluginUI>().installedPluginMenuEntries())
        val entries = listOf(pluginUi("com.weartak.plugin.mopp", "MOPP")).installedPluginMenuEntries()
        assertEquals(1, entries.size)
        assertEquals("installed:com.weartak.plugin.mopp", entries.single().key)
        assertEquals("plugin_main_ui/com.weartak.plugin.mopp", hostedPluginMainUiRoute(
            (entries.single() as PluginMenuEntry.Installed).plugin.pluginId,
        ))
    }

    @Test
    fun moppSubtitleUsesCurrentStatusWithoutChangingManifestFallback() {
        val plugin = pluginUi("com.weartak.plugin.mopp", "MOPP")
            .copy(menuSubtitle = "Protective posture")
        assertEquals("Protective posture", plugin.menuLabel(null, "None"))
        assertEquals(
            listOf("None", "MOPP0", "MOPP1", "MOPP2", "MOPP3", "MOPP4"),
            MoppStatus.entries.map { plugin.menuLabel(it, "None") },
        )
        assertEquals("Aucun", plugin.menuLabel(MoppStatus.NONE, "Aucun"))
        assertEquals("Protective posture", plugin.menuLabel)
    }

    @Test
    fun subtitleOverrideMatchesExactPackageNotTitleOrPluginId() {
        val plugin = pluginUi("com.weartak.plugin.mopp", "MOPP")
            .copy(packageName = "com.weartak.plugin.mopp.other", menuSubtitle = "Other status")
        assertEquals("Other status", plugin.menuLabel(MoppStatus.LEVEL_2, "None"))
        assertEquals(null, plugin.copy(pluginId = "com.weartak.plugin.gotenna").menuLabel(MoppStatus.NONE, "None"))
        assertEquals("MOPP2", plugin.copy(packageName = "com.weartak.plugin.mopp", pluginId = "other-id")
            .menuLabel(MoppStatus.LEVEL_2, "None"))
    }

    private fun installed(id: String, title: String) = PluginMenuEntry.Installed(
        pluginUi(id, title),
    )

    private fun pluginUi(id: String, title: String) = PluginUI(
        icon = null,
        title = title,
        desc = "",
        loadingStatus = PluginLoadingStatus.NOT_LOADED,
        pluginId = id,
        versionName = "",
        packageName = id,
    )
}
