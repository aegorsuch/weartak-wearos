package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1
import com.weartak.plugin.sdk.openvpn.v1.IWearTakOpenVpnHostServicesV1
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract as OpenVpnC
import com.weartak.plugin.sdk.v3.WearTakPluginV3Contract
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenVpnPluginServiceBindingTest {
    @Test
    fun hostCanBindAndDrivePluginLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = FakeOpenVpnHostServices()
        val connected = CountDownLatch(1)
        val plugin = AtomicReference<IWearTakPlugin?>()
        val failure = AtomicReference<Throwable?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                plugin.set(IWearTakPlugin.Stub.asInterface(binder))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onBindingDied(name: ComponentName) {
                failure.set(IllegalStateException("OpenVPN plugin binding died"))
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                failure.set(IllegalStateException("OpenVPN plugin returned a null binder"))
                connected.countDown()
            }
        }
        val intent = Intent().setClassName(
            OpenVpnHostContract.PLUGIN_PACKAGE,
            "com.aitech.weartak_civ.OpenVpnPluginService",
        )

        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("OpenVPN plugin service did not bind", connected.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            val service = requireNotNull(plugin.get())
            assertEquals(WearTakPluginV3Contract.CONTRACT_API_VERSION, service.apiVersion)
            assertSecurityException { service.startPlugin() }
            assertSecurityException {
                service.initializePluginV3(
                    PluginInitParcel(
                        hostPackageName = context.packageName,
                        hostUid = context.applicationInfo.uid + 1,
                        pluginIdExpected = OpenVpnHostContract.PLUGIN_PACKAGE,
                        hostExtras = Bundle(),
                    ),
                    null,
                    null,
                    null,
                )
            }
            assertSecurityException {
                service.initializePluginV3(
                    PluginInitParcel(
                        hostPackageName = "com.aitech.weartak_civ",
                        hostUid = context.applicationInfo.uid,
                        pluginIdExpected = OpenVpnHostContract.PLUGIN_PACKAGE,
                        hostExtras = Bundle(),
                    ),
                    null,
                    null,
                    null,
                )
            }
            service.initializePluginV3(initParcel(context, host), null, null, null)
            assertEquals(PluginLifecycleState.READY, service.status.state)
            service.startPlugin()
            assertEquals(PluginLifecycleState.RUNNING, service.status.state)
            assertEquals(OpenVpnC.RUNTIME_STOPPED, host.runtimeStateAtRegistration.get())
            assertEquals(0, host.runtimeStatePublishCalls.get())
            service.stopPlugin()
            assertEquals(PluginLifecycleState.STOPPED, service.status.state)
            service.shutdownPlugin()
            assertEquals(PluginLifecycleState.SHUTDOWN, service.status.state)
            assertEquals(0, host.runtimeStatePublishCalls.get())
            assertSecurityException { service.startPlugin() }
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun repeatedInitializationKeepsProviderAndReplacementRevokesOldProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (service, connection) = bindPlugin(context)
        val firstHost = FakeOpenVpnHostServices()
        val secondHost = FakeOpenVpnHostServices()
        try {
            service.initializePluginV3(initParcel(context, firstHost), null, null, null)
            service.startPlugin()
            service.initializePluginV3(initParcel(context, firstHost), null, null, null)
            service.startPlugin()
            assertEquals(1, firstHost.registerCalls.get())

            service.initializePluginV3(initParcel(context, secondHost), null, null, null)
            assertEquals(1, firstHost.unregisterCalls.get())
            service.startPlugin()
            assertEquals(1, secondHost.registerCalls.get())
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun deadHostUnregisterFailureDoesNotSkipLocalShutdown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (service, connection) = bindPlugin(context)
        val host = FakeOpenVpnHostServices(failOnUnregister = true)
        try {
            service.initializePluginV3(initParcel(context, host), null, null, null)
            service.startPlugin()
            service.shutdownPlugin()
            assertTrue(host.unregisterCalls.get() > 0)
            assertSecurityException { service.startPlugin() }
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun delayedRegistrationCannotResurrectStoppedLifecycle() {
        assertDelayedRegistrationCancelled(
            terminalAction = { it.stopPlugin() },
            expectedState = PluginLifecycleState.STOPPED,
        )
    }

    @Test
    fun delayedRegistrationCannotResurrectShutdownLifecycle() {
        assertDelayedRegistrationCancelled(
            terminalAction = { it.shutdownPlugin() },
            expectedState = PluginLifecycleState.SHUTDOWN,
        )
    }

    private fun assertDelayedRegistrationCancelled(
        terminalAction: (IWearTakPlugin) -> Unit,
        expectedState: PluginLifecycleState,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (service, connection) = bindPlugin(context)
        val host = FakeOpenVpnHostServices(delayRegistration = true)
        val startFailure = AtomicReference<Throwable?>()
        val starter = Thread {
            try {
                service.startPlugin()
            } catch (error: Throwable) {
                startFailure.set(error)
            }
        }
        try {
            service.initializePluginV3(initParcel(context, host), null, null, null)
            starter.start()
            assertTrue("Provider registration did not block as expected", host.awaitRegistration())

            terminalAction(service)
            assertEquals(expectedState, service.status.state)
            host.releaseRegistration()
            starter.join(TimeUnit.SECONDS.toMillis(5))

            assertFalse("Plugin start call did not finish", starter.isAlive)
            assertNull(startFailure.get())
            assertEquals(1, host.unregisterCalls.get())
            assertEquals(expectedState, service.status.state)
        } finally {
            host.releaseRegistration()
            if (starter.isAlive) starter.join(TimeUnit.SECONDS.toMillis(5))
            context.unbindService(connection)
        }
    }

    private fun bindPlugin(context: Context): Pair<IWearTakPlugin, ServiceConnection> {
        val connected = CountDownLatch(1)
        val plugin = AtomicReference<IWearTakPlugin?>()
        val failure = AtomicReference<Throwable?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                plugin.set(IWearTakPlugin.Stub.asInterface(binder))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onBindingDied(name: ComponentName) {
                failure.set(IllegalStateException("OpenVPN plugin binding died"))
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                failure.set(IllegalStateException("OpenVPN plugin returned a null binder"))
                connected.countDown()
            }
        }
        val intent = Intent().setClassName(
            OpenVpnHostContract.PLUGIN_PACKAGE,
            "com.aitech.weartak_civ.OpenVpnPluginService",
        )
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("OpenVPN plugin service did not bind", connected.await(10, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        return requireNotNull(plugin.get()) to connection
    }

    private fun initParcel(context: Context, host: FakeOpenVpnHostServices) = PluginInitParcel(
        hostPackageName = context.packageName,
        hostUid = context.applicationInfo.uid,
        pluginIdExpected = OpenVpnHostContract.PLUGIN_PACKAGE,
        hostExtras = Bundle().apply {
            putInt(OpenVpnC.EXTRA_VERSION, OpenVpnC.API_VERSION)
            putBinder(OpenVpnC.EXTRA_BINDER, host.asBinder())
        },
    )

    private class FakeOpenVpnHostServices(
        private val failOnUnregister: Boolean = false,
        private val delayRegistration: Boolean = false,
    ) : IWearTakOpenVpnHostServicesV1.Stub() {
        val registerCalls = AtomicInteger()
        val unregisterCalls = AtomicInteger()
        val runtimeStatePublishCalls = AtomicInteger()
        val runtimeStateAtRegistration = AtomicInteger(-1)
        private val registrationEntered = CountDownLatch(1)
        private val registrationRelease = CountDownLatch(if (delayRegistration) 1 else 0)

        override fun getCapabilities() = OpenVpnC.result(OpenVpnC.OK)
        override fun getConfiguredServers() = OpenVpnC.result(OpenVpnC.OK)
        override fun selectServerProfile(serverId: String?, profileId: String?) =
            OpenVpnC.result(OpenVpnC.OK)

        override fun registerProvider(provider: IOpenVpnRawTcpProviderV1?): Bundle {
            registerCalls.incrementAndGet()
            registrationEntered.countDown()
            if (!registrationRelease.await(5, TimeUnit.SECONDS)) {
                throw RemoteException("Synthetic registration was not released")
            }
            runtimeStateAtRegistration.set(
                provider?.getRuntimeState("synthetic-stopped-profile") ?: -1,
            )
            return OpenVpnC.result(OpenVpnC.OK)
        }

        fun awaitRegistration(): Boolean = registrationEntered.await(5, TimeUnit.SECONDS)

        fun releaseRegistration() = registrationRelease.countDown()

        override fun unregisterProvider(): Bundle {
            unregisterCalls.incrementAndGet()
            if (failOnUnregister) throw RemoteException("Synthetic host peer loss")
            return OpenVpnC.result(OpenVpnC.OK)
        }

        override fun publishRuntimeState(profileId: String?, state: Int) =
            OpenVpnC.result(OpenVpnC.OK).also { runtimeStatePublishCalls.incrementAndGet() }

        override fun clearLegacyProfiles() = OpenVpnC.result(OpenVpnC.OK)
    }

    private inline fun assertSecurityException(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            return
        }
        throw AssertionError("Expected SecurityException")
    }
}
