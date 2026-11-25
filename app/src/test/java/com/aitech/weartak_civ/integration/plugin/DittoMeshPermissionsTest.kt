package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class DittoMeshPermissionsTest {
    @Test
    fun freshStatusCannotLaunchAfterPauseOrDisableAndOnlyLaunchesOnce() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        val activity = controller.get()
        val manager = mockk<PluginManager>()
        val connection = mockk<PluginConnection>()
        val mesh = MutableStateFlow<DittoMeshStatus?>(DittoMeshStatus(3, 2))
        val callbacks = mutableListOf<(DittoMeshStatus?) -> Unit>()
        var enabled = true
        var bindingCurrent = true
        every { manager.dittoReadyConnection } returns MutableStateFlow(connection)
        every { manager.isEnabled(TrustedCommunityPlugin.PACKAGE) } answers { enabled }
        every { connection.dittoMeshStatus } returns mesh
        every { connection.mayRequestDittoMeshPermissions() } answers { bindingCurrent }
        every { connection.readFreshDittoMeshStatus(any()) } answers {
            callbacks.add(firstArg())
            true
        }
        DittoMeshPermissions.observe(activity, manager)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbacks.size)

        controller.pause()
        callbacks.removeAt(0)(DittoMeshStatus(3, 2))
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        enabled = false
        callbacks.removeAt(0)(DittoMeshStatus(3, 2))
        assertNull(shadowOf(activity).nextStartedActivity)

        enabled = true
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        bindingCurrent = false
        callbacks.removeAt(0)(DittoMeshStatus(3, 2))
        assertNull(shadowOf(activity).nextStartedActivity)
        bindingCurrent = true
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        callbacks.removeAt(0)(DittoMeshStatus(3, 2))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("com.weartak.plugin.ditto.action.REQUEST_MESH_PERMISSIONS_V1", intent.action)
        assertEquals(TrustedCommunityPlugin.PACKAGE, intent.`package`)
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        callbacks.removeAt(0)(DittoMeshStatus(3, 2))
        assertNull(shadowOf(activity).nextStartedActivity)

        mesh.value = DittoMeshStatus(3, 1)
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }
}
