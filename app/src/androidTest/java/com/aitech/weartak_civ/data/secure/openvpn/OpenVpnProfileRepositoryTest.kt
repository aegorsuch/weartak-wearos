/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.data.secure.openvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.service.openvpn.OpenVpnProfileSupport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenVpnProfileRepositoryTest {
    @Test
    fun importedCredentialsPersistEncryptedAndDeleteCleanly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = OpenVpnProfileRepository(context)
        val username = "synthetic-openvpn-user"
        val password = "synthetic-openvpn-password"
        val imported = repository.import(
            OpenVpnProfileSupport.parse(
                "synthetic.ovpn",
                "client\nremote vpn.example 1194\nauth-user-pass\n".encodeToByteArray(),
            ),
        )

        try {
            repository.saveCredentials(imported.id, username, password)

            val restored = repository.get(imported.id)
            assertEquals(username, restored?.username)
            assertEquals(password, restored?.password)

            val storeFile = File(context.filesDir, "datastore/openvpn_profiles.preferences_pb")
            val persistedBytes = storeFile.readBytes()
            val persistedData = persistedBytes.toString(Charsets.ISO_8859_1)
            assertFalse(persistedData.contains(username))
            assertFalse(persistedData.contains(password))
        } finally {
            repository.delete(imported.id)
        }

        assertNull(repository.get(imported.id))
    }
}
