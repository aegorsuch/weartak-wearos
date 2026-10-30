package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.takserver.TakHttpSession
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import com.weartak.plugin.sdk.host.v1.IWearTakHostServices
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HostServicesV1ImplTest {
    private fun service(
        uid: Int = Process.myUid(),
        map: (PluginMapRequest) -> Bundle = { C.result(C.OK) },
        endpoint: (String?) -> PluginTakHttpClient = { error("Unavailable") },
    ) = HostServicesV1Impl(mockk<WearTakApplication>(), "plugin", uid, map,
        { listOf(PluginTakServer("server", "Server")) }, endpoint)

    private fun request() = Bundle().apply {
        putString(C.KEY_PATH, "${C.REST_PREFIX}test/submit")
    }

    @Test fun discoverySurvivesParcelAndOldHostsAndUnknownVersionsReturnNull() {
        assertNull(C.fromHostExtras(Bundle()))
        val service = service()
        val extras = Bundle().apply {
            putInt(C.EXTRA_VERSION, C.VERSION)
            putBinder(C.EXTRA_BINDER, service.asBinder())
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(extras)
            parcel.setDataPosition(0)
            val discovered = C.fromHostExtras(parcel.readBundle()!!)!!
            assertEquals(C.VERSION, discovered.capabilities.getInt(C.KEY_VERSION))
            assertTrue(discovered.capabilities.getBoolean(C.KEY_TAK_HTTPS))
            assertEquals(listOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET),
                discovered.capabilities.getStringArrayList(C.KEY_MAP_ACTIONS))
        } finally {
            parcel.recycle()
        }
        extras.putInt(C.EXTRA_VERSION, 2)
        assertNull(C.fromHostExtras(extras))
    }

    @Test fun remoteProxyMarshalsRequestAndResponseBundles() {
        val service = service(map = { parsed ->
            C.result(C.OK).apply { putString(C.KEY_UID, parsed.uid) }
        })
        val binder = mockk<IBinder>()
        every { binder.queryLocalInterface(any()) } returns null
        every { binder.transact(any(), any(), any(), any()) } answers {
            service.asBinder().transact(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        val proxy = IWearTakHostServices.Stub.asInterface(binder)
        val response = proxy.requestMapAction(Bundle().apply {
            putString(C.KEY_ACTION, C.ACTION_TARGET)
            putString(C.KEY_UID, "incident")
        })
        assertEquals(C.OK, response.getString(C.KEY_STATUS))
        assertEquals("incident", response.getString(C.KEY_UID))
    }

    @Test fun wrongUidAndRetainedBinderAfterCloseCannotInvokeServices() {
        var invocations = 0
        val wrongUid = service(uid = Process.myUid() + 1, map = {
            invocations++
            C.result(C.OK)
        })
        assertEquals(C.UNAUTHORIZED, wrongUid.capabilities.getString(C.KEY_STATUS))
        assertEquals(C.UNAUTHORIZED, wrongUid.requestMapAction(Bundle()).getString(C.KEY_STATUS))
        assertEquals(C.UNAUTHORIZED, wrongUid.requestTakServer(request()).getString(C.KEY_STATUS))
        val closed = service(map = { invocations++; C.result(C.OK) })
        closed.close()
        assertEquals(C.CLOSED, closed.capabilities.getString(C.KEY_STATUS))
        assertEquals(C.CLOSED, closed.requestMapAction(Bundle()).getString(C.KEY_STATUS))
        assertEquals(C.CLOSED, closed.requestTakServer(request()).getString(C.KEY_STATUS))
        assertEquals(0, invocations)
    }

    @Test fun invalidRequestAndUnavailableEndpointReturnExplicitStatus() {
        val service = service()
        assertEquals(C.INVALID_REQUEST, service.requestMapAction(Bundle()).getString(C.KEY_STATUS))
        assertEquals(C.INVALID_REQUEST, service.requestTakServer(null).getString(C.KEY_STATUS))
        assertEquals(C.UNAVAILABLE, service.requestTakServer(request()).getString(C.KEY_STATUS))
    }

    @Test fun concurrentHttpIsBoundedAndCloseCancelsInFlightWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("old".toResponseBody()).build()
        }.build()
        val endpoint = PluginTakHttpClient(PluginTakServer("server", "Server"),
            "localhost", TakHttpSession { true }, client)
        val service = service(endpoint = { endpoint })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Bundle> { service.requestTakServer(request()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(C.BUSY, service.requestTakServer(request()).getString(C.KEY_STATUS))
            service.close()
            release.countDown()
            val result = pending.get(5, TimeUnit.SECONDS).getString(C.KEY_STATUS)
            assertTrue(result == C.CLOSED || result == C.NETWORK_ERROR)
        } finally {
            release.countDown()
            executor.shutdownNow()
            service.close()
        }
    }
}
