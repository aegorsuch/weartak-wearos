package com.aitech.weartak_civ.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DeveloperPreferencesOrderTest {
    @Test fun offWristToggleUsesLocalizedTopGroupWithoutSplittingChargingPair() {
        val entries = listOf(
            DeveloperEntry(DeveloperEntryId.BUILD, "Build"),
            DeveloperEntry(DeveloperEntryId.STAY_CONNECTED_OFF_WRIST, "Stay connected off-wrist"),
            DeveloperEntry(DeveloperEntryId.WHILE_CHARGING, "While charging"),
            DeveloperEntry(DeveloperEntryId.STAY_AWAKE, "Stay awake"),
            DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, "Logging")
        )
        assertEquals(listOf(DeveloperEntryId.DIAGNOSTICS, DeveloperEntryId.STAY_AWAKE,
            DeveloperEntryId.WHILE_CHARGING, DeveloperEntryId.STAY_CONNECTED_OFF_WRIST,
            DeveloperEntryId.BUILD), sortedDeveloperEntries(entries, Locale.ENGLISH).map { it.id })
    }

    @Test fun checklistSortsBetweenLoggingAndStayAwakeInTopToggleGroup() {
        val entries = listOf(
            DeveloperEntry(DeveloperEntryId.STAY_AWAKE, "Stay awake"),
            DeveloperEntry(DeveloperEntryId.NETWORK_CHECKLIST, "Network Preferences Checklist"),
            DeveloperEntry(DeveloperEntryId.BUILD, "Build"),
            DeveloperEntry(DeveloperEntryId.WHILE_CHARGING, "While charging"),
            DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, "Logging")
        )
        assertEquals(listOf(DeveloperEntryId.DIAGNOSTICS, DeveloperEntryId.NETWORK_CHECKLIST,
            DeveloperEntryId.STAY_AWAKE, DeveloperEntryId.WHILE_CHARGING, DeveloperEntryId.BUILD),
            sortedDeveloperEntries(entries, Locale.ENGLISH).map { it.id })
    }

    @Test fun chargingAlwaysFollowsManualRegardlessOfLocalizedLabel() {
        val entries = listOf(
            DeveloperEntry(DeveloperEntryId.WHILE_CHARGING, "A"),
            DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, "Logging"),
            DeveloperEntry(DeveloperEntryId.STAY_AWAKE, "Z"),
            DeveloperEntry(DeveloperEntryId.BUILD, "Build")
        )
        assertEquals(listOf(DeveloperEntryId.DIAGNOSTICS, DeveloperEntryId.STAY_AWAKE,
            DeveloperEntryId.WHILE_CHARGING,
            DeveloperEntryId.BUILD),
            sortedDeveloperEntries(entries, Locale.GERMAN).map { it.id })
    }

    @Test fun diagnosticsAndActionsSortWithoutLosingTheirAssociations() {
        val entries = listOf(
            DeveloperEntry(DeveloperEntryId.STAY_AWAKE, "Stay awake"),
            DeveloperEntry(DeveloperEntryId.VERSION, "Version", "5.8"),
            DeveloperEntry(DeveloperEntryId.LOCATION, "Location Diagnostics"),
            DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, "Logging"),
            DeveloperEntry(DeveloperEntryId.BUILD, "Build", "release")
        )
        val sorted = sortedDeveloperEntries(entries, Locale.ENGLISH)
        assertEquals(listOf("Logging", "Stay awake", "Build", "Location Diagnostics", "Version"),
            sorted.map { it.label })
        assertEquals(entries.toSet(), sorted.toSet())
        assertEquals(DeveloperEntryId.DIAGNOSTICS, sorted.first().id)
        assertEquals("release", sorted[2].value)
    }

    @Test fun localizedLabelsDetermineOrderRatherThanEnglishOrEnumPosition() {
        val entries = listOf(
            DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, "\u00c4"),
            DeveloperEntry(DeveloperEntryId.STAY_AWAKE, "Z"),
            DeveloperEntry(DeveloperEntryId.BUILD, "A")
        )
        assertEquals(listOf("Z", "\u00c4", "A"),
            sortedDeveloperEntries(entries, Locale.forLanguageTag("sv")).map { it.label })
        assertEquals(listOf("\u00c4", "Z", "A"),
            sortedDeveloperEntries(entries, Locale.GERMAN).map { it.label })
    }
}
