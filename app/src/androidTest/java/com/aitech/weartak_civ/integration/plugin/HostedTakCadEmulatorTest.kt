package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.takserver.TakHttpSession
import com.aitech.weartak_civ.presentation.HostedPluginMainUiScreen
import com.aitech.weartak_civ.service.BloodhoundService
import com.weartak.plugin.sdk.MainUiActionContract
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Optional installed-plugin fixture: no sockets, credentials, sensors, or production preview hooks. */
class HostedTakCadEmulatorTest {
    @Test fun installedPluginRendersAccessibleTopActionsAndTracksOnlyItsIncident() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as WearTakApplication
        val pluginPackage = "com.weartak.plugin.takcad"
        val uid = app.packageManager.getApplicationInfo(pluginPackage, 0).uid
        val bound = CountDownLatch(1)
        lateinit var plugin: IWearTakPlugin
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                plugin = IWearTakPlugin.Stub.asInterface(service)
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val server = PluginTakServer("training-server", "Training server")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val read = request.method == "GET" && request.url.queryParameter("fn") == "getIncidents"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (read) 200 else 403).message(if (read) "OK" else "Read-only fixture")
                .body((if (read) INCIDENTS else "{}").toResponseBody()).build()
        }.build()
        val endpoint = PluginTakHttpClient(server, "training.invalid", TakHttpSession { true }, client)
        val services = HostServicesV1Impl(app, pluginPackage, uid,
            servers = { listOf(server) }, endpoint = { endpoint })
        val actionFailure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> actionFailure.set(error) })
        val wasRunning = BloodhoundService.isRunning.get()
        // Use the service's real activation request lane without requiring emulator location sensors.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            app.bhRepo.requestActivateBloodhound.collect { app.bhRepo.activate(it) }
        }
        val intent = Intent("com.weartak.plugin.action.PLUGIN_SERVICE")
            .setComponent(ComponentName(pluginPackage, "$pluginPackage.CadService"))
        val wasEnabled = app.pluginManager.isEnabled(pluginPackage)
        var scenario: ActivityScenario<ComponentActivity>? = null
        var bindingRegistered = false
        try {
            // Prevent normal discovery from replacing this Binder's isolated host-services attachment.
            instrumentation.runOnMainSync { app.pluginManager.unbindPackage(pluginPackage) }
            waitUntil(10_000) { !PluginLifecycleWork.isBusy(pluginPackage) }
            app.bhRepo.stopTracking("plugin:$pluginPackage:training-incident")
            bindingRegistered = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue(bindingRegistered)
            assertTrue(bound.await(10, TimeUnit.SECONDS))
            val ingress = PluginIngressPort(app)
            plugin.initializePluginV2(PluginInitParcel(
                hostPackageName = app.packageName, hostUid = Process.myUid(),
                pluginIdExpected = pluginPackage,
                hostExtras = Bundle().apply {
                    putInt(C.EXTRA_VERSION, C.VERSION)
                    putBinder(C.EXTRA_BINDER, services.asBinder())
                    putInt(MainUiActionContract.HOST_PRESENTATION_VERSION_KEY,
                        MainUiActionContract.PRESENTATION_VERSION)
                },
            ), HostControlImpl(app, ingress, pluginPackage, uid), null)
            plugin.startPlugin()
            waitUntil(15_000) {
                plugin.mainUiSchema.cards.any { it.content.getString("actionId") == "takcad.incident.open" }
            }
            plugin.invokeMainUiAction("takcad.incident.open", Bundle().apply {
                putString("incidentId", "training-incident")
            })
            val cards = HostedPluginMainUiContract.parse(plugin.mainUiSchema)
            val icons = cards.filter { it.action?.actionGroup == "takcad.incident.actions" }
            assertEquals(listOf("NOTE", "BLOODHOUND", "TARGET"), icons.map { it.action!!.actionIcon })
            assertEquals(listOf("Active Incident", "Address", "Notes", "Responders", "Summary"),
                cards.filter { it.action == null }.map { it.title })
            val pageIntent = Intent(app, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            fun render(pageCards: List<HostedPluginMainUiCard>): ActivityScenario<ComponentActivity> {
                check(instrumentation.uiAutomation.injectInputEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP), true))
                check(instrumentation.uiAutomation.injectInputEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP), true))
                return ActivityScenario.launch<ComponentActivity>(pageIntent).also { page ->
                    page.onActivity { activity ->
                        activity.setTurnScreenOn(true)
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        activity.setContent {
                            HostedPluginMainUiScreen("TAK CAD", HostedPluginMainUiResult.Ready(pageCards), {}, { id ->
                                val action = pageCards.first { it.cardId == id }.action!!
                                scope.launch { plugin.invokeMainUiAction(action.actionId, action.args) }
                            })
                        }
                    }
                }
            }
            scenario = render(cards)
            waitUntil(5_000) {
                val descriptions = accessibilityDescriptions(instrumentation.uiAutomation.rootInActiveWindow)
                icons.all { it.action!!.actionLabel in descriptions }
            }
            val visibleText = accessibilityText(instrumentation.uiAutomation.rootInActiveWindow)
            assertTrue(visibleText.intersect(setOf("DEV", "SIM", "ACTIVE")).isEmpty())
            screenshot(instrumentation, app, "takcad-incident-icons.png")
            swipeList(instrumentation, forward = true)
            Thread.sleep(400)
            screenshot(instrumentation, app, "takcad-incident-address.png")
            scrollUntil(instrumentation) { accessibilityText(it).any { text -> text.startsWith("Summary") } }
            screenshot(instrumentation, app, "takcad-incident-responders.png")
            swipeList(instrumentation, forward = true)
            Thread.sleep(400)
            screenshot(instrumentation, app, "takcad-incident-fields.png")
            scrollUntil(instrumentation, forward = false) {
                icons.all { card -> card.action!!.actionLabel in accessibilityDescriptions(it) }
            }

            BloodhoundService.isRunning.set(true)
            val label = icons.first { it.action!!.actionIcon == "BLOODHOUND" }.action!!.actionLabel
            val button = requireNotNull(findDescription(instrumentation.uiAutomation.rootInActiveWindow, label))
            clickNode(instrumentation, button)
            waitUntil(10_000) {
                actionFailure.get()?.let { throw AssertionError("Hosted action failed", it) }
                app.bhRepo.activeUid.value == "plugin:$pluginPackage:training-incident"
            }
            assertEquals("training-incident", PluginMapActions(app, pluginPackage).activeIncidentUid())
            // The plugin queries the live capability before emitting its next schema revision.
            waitUntil(10_000) {
                HostedPluginMainUiContract.parse(plugin.mainUiSchema).any { it.action?.actionIcon == "STOP" }
            }
            instrumentation.waitForIdleSync()
            screenshot(instrumentation, app, "takcad-bloodhound-main.png")
            plugin.invokeMainUiAction("takcad.incident.open", Bundle().apply {
                putString("incidentId", "training-incident")
            })
            waitUntil(10_000) {
                HostedPluginMainUiContract.parse(plugin.mainUiSchema)
                    .any { it.action?.actionIcon == "STOP" && it.action.enabled }
            }
            val stopCards = HostedPluginMainUiContract.parse(plugin.mainUiSchema)
            scenario?.close()
            scenario = render(stopCards)
            val stopLabel = stopCards.first { it.action?.actionIcon == "STOP" }.action!!.actionLabel
            waitUntil(5_000) {
                stopLabel in accessibilityDescriptions(instrumentation.uiAutomation.rootInActiveWindow)
            }
            screenshot(instrumentation, app, "takcad-incident-stop.png")
            val stop = requireNotNull(findDescription(instrumentation.uiAutomation.rootInActiveWindow, stopLabel))
            clickNode(instrumentation, stop)
            waitUntil(10_000) { app.bhRepo.activeUid.value == null }
            actionFailure.get()?.let { throw AssertionError("Hosted action failed", it) }
        } finally {
            try {
                scenario?.close()
                app.bhRepo.stopTracking("plugin:$pluginPackage:training-incident")
                services.close()
                scope.cancel()
                BloodhoundService.isRunning.set(wasRunning)
                if (bound.count == 0L) plugin.stopPlugin()
            } finally {
                try {
                    if (bindingRegistered) app.unbindService(connection)
                } finally {
                    if (wasEnabled) instrumentation.runOnMainSync { app.pluginManager.bindPackage(pluginPackage) }
                }
            }
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Fixture condition timed out" }
            Thread.sleep(100)
        }
    }

    private fun accessibilityDescriptions(node: AccessibilityNodeInfo?): Set<String> {
        if (node == null) return emptySet()
        return buildSet {
            node.contentDescription?.let { add(it.toString()) }
            for (index in 0 until node.childCount) addAll(accessibilityDescriptions(node.getChild(index)))
        }
    }

    private fun accessibilityText(node: AccessibilityNodeInfo?): Set<String> {
        if (node == null) return emptySet()
        return buildSet {
            node.text?.let { add(it.toString()) }
            for (index in 0 until node.childCount) addAll(accessibilityText(node.getChild(index)))
        }
    }

    private fun scrollUntil(
        instrumentation: android.app.Instrumentation,
        forward: Boolean = true,
        condition: (AccessibilityNodeInfo?) -> Boolean,
    ) {
        repeat(6) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (condition(root)) return
            val list = requireNotNull(findScrollable(root))
            val direction = if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            val action = if (list.actionList.any { it.id == direction }) direction
                else if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (!list.performAction(action)) swipeList(instrumentation, forward)
            Thread.sleep(400)
        }
        check(condition(instrumentation.uiAutomation.rootInActiveWindow)) { "Fixture field not visible" }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || node.isScrollable) return node
        for (index in 0 until node.childCount) findScrollable(node.getChild(index))?.let { return it }
        return null
    }

    private fun findDescription(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) {
            findDescription(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun clickNode(instrumentation: android.app.Instrumentation, node: AccessibilityNodeInfo) {
        check(node.isEnabled && node.isVisibleToUser) { "Fixture action is disabled or hidden" }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        // Preview platform accessibility can reject actions after the test activity is recreated.
        val bounds = Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            Thread.sleep(16)
        }
    }

    private fun swipeList(instrumentation: android.app.Instrumentation, forward: Boolean) {
        val display = instrumentation.targetContext.resources.displayMetrics
        val down = SystemClock.uptimeMillis()
        val start = display.heightPixels * if (forward) 0.78f else 0.30f
        val end = display.heightPixels * if (forward) 0.30f else 0.78f
        for (step in 0..12) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                display.widthPixels / 2f, start + (end - start) * step / 12f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            Thread.sleep(16)
        }
        val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            display.widthPixels / 2f, end, 0)
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try { check(instrumentation.uiAutomation.injectInputEvent(up, true)) }
        finally { up.recycle() }
    }

    private fun screenshot(
        instrumentation: android.app.Instrumentation,
        context: Context,
        name: String,
    ) {
        instrumentation.waitForIdleSync()
        var screenshot: Bitmap? = null
        waitUntil(5_000) {
            screenshot?.recycle()
            screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val image = requireNotNull(screenshot)
            (0 until image.height step 10).any { y ->
                (0 until image.width step 10).count { x ->
                    (image.getPixel(x, y) and 0x00ffffff) != 0
                } > 10
            }
        }
        val image = requireNotNull(screenshot)
        File(context.filesDir, name).outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    private companion object {
        const val INCIDENTS = """[{"uid":"training-incident","incidentName":"Training incident","status":"ACTIVE","details":"Read-only training incident","location":{"coords":{"latitudeDeg":38.0,"longitudeDeg":-77.0}},"personnelResponding":[{"uid":"training-person"}],"notes":[{"uid":"training-note","creator":"TRAINING","timestamp":"2026-10-31T12:00:00Z","info":"Existing training note"}]}]"""
    }
}
