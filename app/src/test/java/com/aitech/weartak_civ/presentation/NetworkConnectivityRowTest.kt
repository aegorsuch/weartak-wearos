package com.aitech.weartak_civ.presentation

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class NetworkConnectivityRowTest {
    @Test fun nativeAndDynamicRowsShareLocalizedOrderingRegardlessOfToggleState() {
        val names = listOf("WearTAK Companion", "TAK Servers", "Somewear", "Ditto Edge Sync", "Sit(x) TAK", "TAK SA Multicast")
        val rows = names.mapIndexed { index, name ->
            NetworkConnectivityRow(index.toString(), name, "", index % 2 == 0, {})
        }
        assertEquals(listOf("Ditto Edge Sync", "Sit(x) TAK", "Somewear", "TAK SA Multicast", "TAK Servers", "WearTAK Companion"),
            rows.alphabetizedConnections(Locale.US).map { it.title })
        assertEquals(listOf("Äther", "Zulu"),
            listOf("Zulu", "Äther").map { NetworkConnectivityRow(it, it, "", false, {}) }
                .alphabetizedConnections(Locale.GERMAN).map { it.title })
    }
}
