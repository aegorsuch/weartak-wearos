package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.integration.plugin.PluginLoadingStatus
import com.aitech.weartak_civ.integration.plugin.PluginUI
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
            PluginMenuEntry.BuiltIn(BuiltInPlugin.MOPP, "MOPP"),
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
