package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginStatusParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DittoPdlModeTest {
    private val key = "com.weartak.plugin.ditto.pdlMode.v1"

    private fun status(extras: Bundle) = PluginStatusParcel(PluginLifecycleState.RUNNING, extras = extras)
    private fun mode(value: Int) = status(Bundle().apply { putInt(key, value) })

    @Test
    fun acceptsKnownModesAndRejectsMissingWrongOrUnknownValues() {
        val state = DittoPdlModeState()
        listOf(
            1 to DittoPdlMode.COMMUNITY,
            2 to DittoPdlMode.PRIVATE,
            3 to DittoPdlMode.NOT_CONFIGURED,
        ).forEach { (value, expected) ->
            state.update(mode(value))
            assertEquals(expected, state.mode.value)
        }
        state.update(mode(4))
        assertNull(state.mode.value)
        state.update(status(Bundle().apply { putString(key, "private") }))
        assertNull(state.mode.value)
        state.update(status(Bundle()))
        assertNull(state.mode.value)
    }

    @Test
    fun callbackSupersedesOutstandingReadAndCloseFencesLateResults() {
        val state = DittoPdlModeState()
        state.completeRead(state.readRevision(), mode(1))
        assertEquals(DittoPdlMode.COMMUNITY, state.mode.value)
        val beforeCallback = state.readRevision()
        state.update(mode(2))
        state.completeRead(beforeCallback, mode(1))
        assertEquals(DittoPdlMode.PRIVATE, state.mode.value)

        val beforeClose = state.readRevision()
        state.close()
        state.completeRead(beforeClose, mode(3))
        state.update(mode(1))
        assertNull(state.mode.value)
    }
}
