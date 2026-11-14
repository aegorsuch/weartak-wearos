package com.aitech.weartak_civ.data

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.integration.plugin.PluginManager
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WatchCapabilitiesTest {
    private val nonce = "45963c97-13ae-4ef3-a2bd-7e65f6f3ae67"
    private fun app(plugins: List<JSONObject>): WearTakApplication {
        val manager = mockk<PluginManager>()
        every { manager.companionPluginInventory() } returns plugins
        val pm = mockk<PackageManager>()
        every { pm.getPackageInfo("test.watch", 0) } returns PackageInfo().apply {
            versionName = "5.8.4-test"; longVersionCode = 584
        }
        return mockk<WearTakApplication>().also {
            every { it.packageName } returns "test.watch"
            every { it.packageManager } returns pm
            every { it.pluginManager } returns manager
        }
    }
    private fun voice(loaded: Boolean, version: Int = 1) = JSONObject().put("loaded", loaded)
        .put("features", JSONObject().put("voice_channels_v1", version))
    @Test fun advertisesOnlyExactlyOneLoadedCompatibleReceiver() {
        for (plugins in listOf(emptyList(), listOf(voice(false)), listOf(voice(true, 2)), listOf(voice(true), voice(true)))) {
            assertEquals(0, WatchCapabilities.snapshot(app(plugins), nonce, "watch-1").getJSONObject("features").getInt("voice_channels_v1"))
        }
        assertEquals(0, WatchCapabilities.snapshot(app(listOf(voice(true))), nonce, "watch-1").getJSONObject("features").getInt("voice_channels_v1"))
        assertEquals("HOST_VOICE_STAGING_UNAVAILABLE", WatchCapabilities.snapshot(app(listOf(voice(true))), nonce, "watch-1").getJSONObject("unavailableReasons").getString("voice_channels_v1"))
        val result = WatchCapabilities.snapshot(app(listOf(voice(true))), nonce, "watch-1", voiceDeliveryAvailable = true)
        assertEquals(1, result.getJSONObject("features").getInt("voice_channels_v1"))
        assertEquals(nonce, result.getString("requestId")); assertEquals("watch-1", result.getString("watchUid"))
        assertEquals(584L, result.getJSONObject("software").getLong("versionCode"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedNonce() {
        WatchCapabilities.snapshot(app(emptyList()), "invalid", "watch-1")
    }
}
