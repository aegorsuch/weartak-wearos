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

    @Test
    fun meshPromptRequiresKnownRequestedRuntimeAndMissingPermissions() {
        fun mesh(runtime: Int, permissions: Int) = status(Bundle().apply {
            putInt("com.weartak.plugin.ditto.runtimeState.v1", runtime)
            putInt("com.weartak.plugin.ditto.meshPermissions.v1", permissions)
        })
        assertNull(DittoMeshStatus.parse(status(Bundle())))
        assertNull(DittoMeshStatus.parse(status(Bundle().apply {
            putString("com.weartak.plugin.ditto.runtimeState.v1", "3")
            putInt("com.weartak.plugin.ditto.meshPermissions.v1", 2)
        })))
        assertNull(DittoMeshStatus.parse(mesh(9, 2)))
        assertNull(DittoMeshStatus.parse(mesh(2, 9)))
        assertEquals(false, DittoMeshStatus.parse(mesh(1, 2))!!.canPrompt)
        for (runtime in 2..4) assertEquals(true, DittoMeshStatus.parse(mesh(runtime, 2))!!.canPrompt)
        assertEquals(false, DittoMeshStatus.parse(mesh(5, 2))!!.canPrompt)
        assertEquals(true, DittoMeshStatus.parse(mesh(5, 2))!!.needsRecovery)
        assertEquals(true, DittoMeshStatus.parse(mesh(3, 3))!!.needsRecovery)
        assertEquals(false, DittoMeshStatus.parse(mesh(1, 3))!!.needsRecovery)
        assertEquals(false, DittoMeshStatus.parse(mesh(3, 1))!!.canPrompt)
    }

    @Test
    fun meshStatusCannotBeReplacedByLateReadsOrAfterClose() {
        fun mesh(runtime: Int, permissions: Int) = status(Bundle().apply {
            putInt("com.weartak.plugin.ditto.runtimeState.v1", runtime)
            putInt("com.weartak.plugin.ditto.meshPermissions.v1", permissions)
        })
        val state = DittoPdlModeState()
        val revision = state.readRevision()
        state.update(mesh(3, 3))
        state.completeRead(revision, mesh(3, 2))
        assertEquals(DittoMeshStatus(3, 3), state.mesh.value)
        assertEquals(false, state.isReadCurrent(revision))
        val current = state.readRevision()
        state.close()
        state.completeRead(current, mesh(3, 2))
        assertNull(state.mesh.value)
        assertEquals(false, state.isReadCurrent(current))
    }

    @Test
    fun permissionAttemptDoesNotRepeatUntilGrantsAreObserved() {
        val guard = DittoMeshPromptGuard()
        val missing = DittoMeshStatus(3, 2)
        assertEquals(false, guard.claim(null))
        assertEquals(true, guard.claim(missing))
        assertEquals(false, guard.claim(missing))
        guard.observe(DittoMeshStatus(3, 3))
        assertEquals(false, guard.claim(missing))
        guard.observe(DittoMeshStatus(3, 1))
        assertEquals(true, guard.claim(missing))
    }
}
