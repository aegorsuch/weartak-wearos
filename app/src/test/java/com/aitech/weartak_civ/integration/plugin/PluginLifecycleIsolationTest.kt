package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginLifecycleIsolationTest {
    private val pkg = "com.example.isolation"
    private val app = mockk<WearTakApplication>(relaxed = true)
    private val ingress = mockk<PluginIngressPort>(relaxed = true)
    private val settings = mockk<PluginSettingsCoordinator>(relaxed = true)
    private val trust = mockk<PluginTrust>(relaxed = true)
    private val captured = mutableListOf<PluginConnection>()
    private var nextPlugin: FakeWearTakPlugin? = null
    private fun manager() = PluginManager(app, ingress, trust, settings)

    @Before fun setup() {
        PluginDiagnostics.resetForTest()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("isolation-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        every { app.getSharedPreferences(any(), any()) } returns prefs
        every { app.packageName } returns "host"
        val pm = mockk<PackageManager>(relaxed = true)
        val service = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = pkg
                name = ".Service"
                permission = com.weartak.plugin.sdk.WearTakPluginContract.PERMISSION_BIND_PLUGIN
            }
        }
        every { pm.queryIntentServices(any<Intent>(), any<PackageManager.ResolveInfoFlags>()) } returns listOf(service)
        every { pm.getPackageUid(any(), any<PackageManager.PackageInfoFlags>()) } returns android.os.Process.myUid()
        every { app.packageManager } returns pm
        every { ingress.packagesForUid(any(), any()) } returns setOf(pkg)
        every { app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } answers {
            val connection = secondArg<PluginConnection>()
            captured += connection
            nextPlugin?.let { connection.onServiceConnected(ComponentName(pkg, ".Service"), it.asBinder()) }
            true
        }
    }

    @Test fun blockedCleanupCannotDelayUnloadOrOverlapRapidReload() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var cleanupOnMain = true
        var oldCallback: IWearTakHostCallback? = null
        val old = object : FakeWearTakPlugin(pkg) {
            override fun registerHostCallback(callback: IWearTakHostCallback?) {
                oldCallback = callback
                super.registerHostCallback(callback)
            }
            override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
                cleanupOnMain = Looper.myLooper() == Looper.getMainLooper()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                super.unregisterHostCallback(callback)
            }
        }
        nextPlugin = old
        val manager = manager()
        try {
            manager.refreshBindings()
            awaitPluginLifecycle(pkg)
            val oldConnection = captured.single()
            val services = C.fromHostExtras(old.lastInit!!.hostExtras)!!
            manager.unbindPackage(pkg)
            assertFalse(manager.hasConnectionForTest(pkg))
            assertFalse(manager.isEnabled(pkg))
            assertEquals(PluginLoadingStatus.NOT_LOADED,
                PluginStatusMapper.mapLoadingStatus(pkg, PluginDiagnostics.snapshot()))
            assertEquals(C.CLOSED, services.capabilities.getString(C.KEY_STATUS))
            oldCallback!!.onPluginMainUiStateChanged()
            assertNull(PluginDiagnostics.hostCallbackState.value.mainUiStateChangeCountByPlugin[pkg])
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(cleanupOnMain)
            var mainResponsive = false
            android.os.Handler(Looper.getMainLooper()).post { mainResponsive = true }
            ShadowLooper.shadowMainLooper().idle()
            assertTrue(mainResponsive)

            nextPlugin = FakeWearTakPlugin(pkg)
            assertTrue(manager.bindPackage(pkg))
            assertEquals(1, captured.size)
            assertTrue(PluginLifecycleWork.isBusy(pkg))
            release.countDown()
            awaitPluginLifecycle(pkg)
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1))
            awaitPluginLifecycle(pkg)
            assertEquals(2, captured.size)
            assertTrue(pkg in PluginDiagnostics.snapshot().connected)
            oldConnection.onServiceDisconnected(null)
            oldConnection.onServiceConnected(null, old.asBinder())
            assertTrue(manager.hasConnectionForTest(pkg))
            assertEquals(1, old.startCount())
        } finally {
            release.countDown()
            manager.shutdownAll()
            awaitPluginLifecycle(pkg)
        }
    }

    @Test fun frozenInitializationTimesOutLocallyWithoutAllocatingReplacementWorker() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var initializedOnMain = true
        val plugin = object : FakeWearTakPlugin(pkg) {
            override fun getPluginInfo(): com.weartak.plugin.sdk.model.PluginInfoParcel {
                initializedOnMain = Looper.myLooper() == Looper.getMainLooper()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getPluginInfo()
            }
        }
        nextPlugin = plugin
        val manager = manager()
        try {
            manager.refreshBindings()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(initializedOnMain)
            assertEquals(PluginLoadingStatus.BINDING,
                PluginStatusMapper.mapLoadingStatus(pkg, PluginDiagnostics.snapshot()))
            assertFalse(PluginStatusMapper.isLoaded(PluginLoadingStatus.BINDING))
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10))
            assertFalse(manager.hasConnectionForTest(pkg))
            assertTrue(PluginLifecycleWork.isBusy(pkg))
            assertEquals("lifecycle_timeout", PluginDiagnostics.snapshot().lastAdmissionDetailByPackage[pkg])
            assertTrue(manager.bindPackage(pkg))
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1))
            assertEquals(1, captured.size)
            manager.unbindPackage(pkg)
            release.countDown()
            awaitPluginLifecycle(pkg)
            assertEquals(0, plugin.startCount())
            assertFalse(pkg in PluginDiagnostics.snapshot().connected)
            verify(exactly = 0) { settings.onPluginConnected(any(), any(), any()) }
        } finally {
            release.countDown()
            manager.shutdownAll()
            awaitPluginLifecycle(pkg)
        }
    }

    @Test fun disabledChoiceSurvivesDiscoveryRefreshNewManagerAndLateCallbacks() {
        val manager = manager()
        manager.refreshBindings()
        assertEquals(1, captured.size)
        val old = captured.single()
        manager.unbindPackage(pkg)
        awaitPluginLifecycle(pkg)
        manager.refreshBindings()
        val restored = manager()
        restored.refreshBindings()
        assertFalse(restored.isEnabled(pkg))
        assertEquals(1, captured.size)
        old.onBindingDied(null)
        old.onServiceConnected(null, FakeWearTakPlugin(pkg).asBinder())
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMinutes(1))
        assertEquals(1, captured.size)
        nextPlugin = FakeWearTakPlugin(pkg)
        assertTrue(restored.bindPackage(pkg))
        awaitPluginLifecycle(pkg)
        assertTrue(restored.isEnabled(pkg))
        assertTrue(pkg in PluginDiagnostics.snapshot().connected)
        restored.shutdownAll()
        awaitPluginLifecycle(pkg)
        assertTrue(manager().isEnabled(pkg)) // service shutdown is not a user disable
    }

    @Test fun bindingWithoutServiceCallbackTimesOutAndCanBeDisabled() {
        val manager = manager()
        manager.refreshBindings()
        assertEquals(PluginLoadingStatus.BINDING,
            PluginStatusMapper.mapLoadingStatus(pkg, PluginDiagnostics.snapshot()))
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(10))
        assertFalse(manager.hasConnectionForTest(pkg))
        manager.unbindPackage(pkg)
        manager.refreshBindings()
        assertEquals(1, captured.size)
    }

    @Test fun lifecycleCapacityHasNoQueueAndRetainsSlotsUntilCallsReallyReturn() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val packages = (1..4).map { "blocked-$it" }
        try {
            packages.forEach { id ->
                assertTrue(PluginLifecycleWork.execute(id) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(PluginLifecycleWork.execute("overflow") { fail("Unexpected extra worker") })
            assertFalse(PluginLifecycleWork.execute(packages.first()) { fail("Overlapping package") })
            packages.forEach { assertTrue(PluginLifecycleWork.isBusy(it)) }
            assertFalse(PluginLifecycleWork.isBusy("overflow"))
        } finally {
            release.countDown()
            packages.forEach(::awaitPluginLifecycle)
        }
        assertTrue(PluginLifecycleWork.execute("recovered") {})
        awaitPluginLifecycle("recovered")
    }

    private fun FakeWearTakPlugin.startCount() = lifecycleCalls.count { it == "startPlugin" }
}
