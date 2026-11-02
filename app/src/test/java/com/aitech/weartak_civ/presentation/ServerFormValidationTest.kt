package com.aitech.weartak_civ.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormValidationTest {
    @Test
    fun requiredValuesAndPortBoundariesMatchAddAndEditRules() {
        fun valid(name: String = "Test", address: String = "example.invalid", port: String = "8089",
                  username: String = "test", password: String = "test-only", optional: Boolean = false) =
            serverFormIsValid(name, address, port, username, password, optional)

        assertTrue(valid())
        assertTrue(valid(port = "1"))
        assertTrue(valid(port = "65535"))
        listOf("", " ", "0", "-1", "65536", "2147483648", "8x", "8.5").forEach {
            assertFalse("Invalid port: $it", valid(port = it))
        }
        assertFalse(valid(name = " "))
        assertFalse(valid(address = " "))
        assertFalse(valid(username = ""))
        assertFalse(valid(password = ""))
        assertTrue(valid(username = "", password = "", optional = true))
        assertFalse(valid(port = "0", optional = true))
        assertFalse(valid(name = "", optional = true))
    }
}
