package com.aitech.weartak_civ.integration.plugin

import org.junit.Assert.assertFalse
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

internal fun awaitPluginLifecycle(packageName: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    do {
        ShadowLooper.shadowMainLooper().idle()
        if (!PluginLifecycleWork.isBusy(packageName)) {
            ShadowLooper.shadowMainLooper().idle()
            if (!PluginLifecycleWork.isBusy(packageName)) return
        }
        Thread.sleep(5)
    } while (System.nanoTime() < deadline)
    assertFalse("Plugin lifecycle did not finish: $packageName", PluginLifecycleWork.isBusy(packageName))
}
