package com.aitech.weartak_civ.integration.plugin

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginActivityLauncherTest {
    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context> {
        every { packageManager } returns this@PluginActivityLauncherTest.packageManager
        every { applicationContext } returns mockk<Context>(relaxed = true)
        every { startActivity(any()) } returns Unit
    }
    private val packageName = "com.weartak.plugin.sample"

    @Test fun activityLaunchKeepsHostInForegroundTaskForExplicitReviewReturn() {
        val activity = mockk<android.app.Activity> {
            every { packageManager } returns this@PluginActivityLauncherTest.packageManager
            every { startActivity(any()) } returns Unit
            every { applicationContext } returns mockk<Context>(relaxed = true)
        }
        val intent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        every { packageManager.getLaunchIntentForPackage(packageName) } returns intent
        assertTrue(PluginActivityLauncher.launch(activity, packageName))
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun launchesEachPluginsOwnPackageWithoutRequiringLoadedCapabilities() {
        for (pluginPackage in listOf(packageName, "com.weartak.plugin.another")) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(pluginPackage, "$pluginPackage.MainActivity")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            every { packageManager.getLaunchIntentForPackage(pluginPackage) } returns intent

            assertTrue(PluginActivityLauncher.launch(context, pluginPackage))

            verify(exactly = 1) { context.startActivity(intent) }
        }
    }

    @Test
    fun missingLauncherReturnsFalseForRevealFallback() {
        every { packageManager.getLaunchIntentForPackage(packageName) } returns null

        assertFalse(PluginActivityLauncher.launch(context, packageName))

        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun activityRemovedBetweenResolutionAndLaunchReturnsFalse() {
        every { packageManager.getLaunchIntentForPackage(packageName) } returns Intent()
        every { context.startActivity(any()) } throws ActivityNotFoundException("Removed")

        assertFalse(PluginActivityLauncher.launch(context, packageName))
    }

    @Test
    fun inaccessibleActivityReturnsFalse() {
        every { packageManager.getLaunchIntentForPackage(packageName) } returns Intent()
        every { context.startActivity(any()) } throws SecurityException("Not exported")

        assertFalse(PluginActivityLauncher.launch(context, packageName))
    }

    @Test
    fun deniedPackageResolutionReturnsFalse() {
        every { packageManager.getLaunchIntentForPackage(packageName) } throws SecurityException("Denied")

        assertFalse(PluginActivityLauncher.launch(context, packageName))

        verify(exactly = 0) { context.startActivity(any()) }
    }
}
