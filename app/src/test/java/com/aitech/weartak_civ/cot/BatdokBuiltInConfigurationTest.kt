package com.aitech.weartak_civ.cot

import org.junit.Assert.*
import org.junit.Test

class BatdokBuiltInConfigurationTest {
    @Test fun compatibleConfigurationCreatesRealCodecWithoutImport() {
        val codec = BatdokBuiltInConfiguration.createCodecOrNull(
            "SYNTHETIC-NOT-OPERATIONAL", "c3ludGhldGljLXRlc3Qtc2FsdA==")
        requireNotNull(codec).use {
            assertEquals(
                "8pX/EyMtmRPVBBqhij3ILPm82l8y7pfomOvVUgw0zegAxnXReoTOnehQKiJNLnz+2Mw8+oeUcQE9oSROV787FA==",
                it.encode("""{"id":"0123456789abcdef","name":"SYNTHETIC"}"""))
        }
    }

    @Test fun incompleteAndInvalidConfigurationNeverBecomesFakeEncoder() {
        assertNull(BatdokBuiltInConfiguration.createCodecOrNull("", ""))
        listOf("" to "c2FsdA==", "SYNTHETIC" to "", "SYNTHETIC" to "!").forEach { (passphrase, salt) ->
            assertThrows(IllegalArgumentException::class.java) {
                BatdokBuiltInConfiguration.createCodecOrNull(passphrase, salt)
            }
        }
    }
}
