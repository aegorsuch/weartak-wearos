/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TakServerManagedReconcilerTest {
    @Test
    fun matchesEndpointEditsByUniqueNameAndRenamesByEndpoint() {
        val existing = listOf(
            server("Alpha", "old-alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val configured = listOf(
            server("Alpha", "new-alpha.example", null),
            server("Renamed Bravo", "bravo.example", null)
        )

        assertEquals(
            listOf(0, 1),
            TakServerManagedReconciler.matchExistingIndexes(configured, existing)
        )
    }

    @Test
    fun preservesIdentityAcrossReorderingAndSimultaneousFieldEdits() {
        val existing = listOf(
            server("Alpha", "alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val reordered = listOf(
            server("Bravo", "bravo.example", null),
            server("Alpha", "alpha.example", null)
        )
        val fullyEditedInPlace = listOf(
            server("New Alpha", "new-alpha.example", null),
            server("New Bravo", "new-bravo.example", null)
        )

        assertEquals(
            listOf(1, 0),
            TakServerManagedReconciler.matchExistingIndexes(reordered, existing)
        )
        assertEquals(
            listOf(0, 1),
            TakServerManagedReconciler.matchExistingIndexes(fullyEditedInPlace, existing)
        )
    }

    @Test
    fun pairsTheRemainingServerAfterAnotherServerMoves() {
        val existing = listOf(
            server("Alpha", "alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val configured = listOf(
            server("Bravo", "bravo.example", null),
            server("New Alpha", "new-alpha.example", null)
        )

        assertEquals(
            listOf(1, 0),
            TakServerManagedReconciler.matchExistingIndexes(configured, existing)
        )
    }

    @Test
    fun explicitIdsWinWhenNamesEndpointsAndOrderChange() {
        val existing = listOf(
            server("Alpha", "alpha.example", SERVER_A),
            server("Bravo", "bravo.example", SERVER_B)
        )
        val configured = listOf(
            server("Renamed Bravo", "new-bravo.example", SERVER_B),
            server("Renamed Alpha", "new-alpha.example", SERVER_A)
        )

        assertEquals(
            listOf(1, 0),
            TakServerManagedReconciler.matchExistingIndexes(configured, existing)
        )
    }

    private fun server(name: String, address: String, serverId: String?) = TakServerItem(
        isEnabled = true,
        name = name,
        address = address,
        port = 8089,
        isP12Cert = false,
        username = "user",
        password = "password",
        p12Cert = "",
        p12CertPassword = "",
        serverId = serverId
    )

    private companion object {
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
