package com.aitech.weartak_civ.integration.plugin

import android.content.ActivityNotFoundException
import android.os.BadParcelableException
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.takserver.TakConnectionCloser
import com.aitech.weartak_civ.service.TAKConnectService
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C
import com.weartak.plugin.sdk.host.v1.IWearTakHostServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import java.io.IOException
import java.util.concurrent.Semaphore

internal class HostServicesV1Impl(
    app: WearTakApplication,
    pluginPackage: String,
    private val expectedUid: Int,
    private val mapAction: (PluginMapRequest) -> Bundle = PluginMapActions(app, pluginPackage)::execute,
    private val servers: () -> List<PluginTakServer> = { TAKConnectService.pluginRestServers() },
    private val endpoint: (String?) -> PluginTakHttpClient = { TAKConnectService.pluginRestClient(it) },
) : IWearTakHostServices.Stub() {
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val httpPermit = Semaphore(1)

    override fun getCapabilities(): Bundle = guarded {
        C.result(C.OK).apply {
            putInt(C.KEY_VERSION, C.VERSION)
            putStringArrayList(C.KEY_MAP_ACTIONS, arrayListOf(C.ACTION_BLOODHOUND, C.ACTION_TARGET))
            putBoolean(C.KEY_TAK_HTTPS, true)
            putParcelableArrayList(C.KEY_SERVERS, ArrayList(servers().map { server ->
                Bundle().apply {
                    putString(C.KEY_SERVER_ID, server.id)
                    putString(C.KEY_SERVER_NAME, server.name)
                }
            }))
        }
    }

    override fun requestMapAction(request: Bundle?): Bundle = guarded {
        val parsed = PluginMapRequest.parse(requireNotNull(request) { "Request required" })
        synchronized(lock) {
            if (closed) C.result(C.CLOSED) else mapAction(parsed)
        }
    }

    override fun requestTakServer(request: Bundle?): Bundle = guarded {
        val parsed = PluginHttpRequest.parse(requireNotNull(request) { "Request required" })
        if (!httpPermit.tryAcquire()) return@guarded C.result(C.BUSY)
        try {
            if (!globalHttpPermits.tryAcquire()) return@guarded C.result(C.BUSY)
            try {
                runBlocking(Dispatchers.IO) {
                    endpoint(parsed.serverId).execute(parsed) callBlock@ { call, execute ->
                        synchronized(lock) {
                            if (closed) return@callBlock C.result(C.CLOSED)
                            calls.add(call)
                        }
                        try {
                            execute()
                        } finally {
                            synchronized(lock) { calls.remove(call) }
                        }
                    }
                }
            } finally {
                globalHttpPermits.release()
            }
        } finally {
            httpPermit.release()
        }
    }

    private inline fun guarded(block: () -> Bundle): Bundle {
        if (Binder.getCallingUid() != expectedUid) {
            Log.w(TAG, "Host services rejected UID mismatch")
            return C.result(C.UNAUTHORIZED)
        }
        synchronized(lock) { if (closed) return C.result(C.CLOSED) }
        val identity = Binder.clearCallingIdentity()
        try {
            val result = block()
            return synchronized(lock) { if (closed) C.result(C.CLOSED) else result }
        } catch (error: BadParcelableException) {
            Log.w(TAG, "Invalid host services parcel", error)
            return C.result(C.INVALID_REQUEST, "Invalid request parcel")
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Invalid host services request", error)
            return C.result(C.INVALID_REQUEST, error.message.orEmpty())
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Host services unavailable", error)
            return C.result(C.UNAVAILABLE, "Connected TAK server or TLS session unavailable")
        } catch (error: PluginResponseTooLarge) {
            Log.w(TAG, "Plugin response too large")
            return C.result(C.RESPONSE_TOO_LARGE, error.message.orEmpty())
        } catch (error: IOException) {
            Log.w(TAG, "Plugin HTTPS failed", error)
            return C.result(C.NETWORK_ERROR, "TAK HTTPS request failed")
        } catch (error: SecurityException) {
            Log.w(TAG, "Host service action denied", error)
            return C.result(C.UNAVAILABLE, "Host action denied")
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Host map activity unavailable", error)
            return C.result(C.UNAVAILABLE, "Host map activity unavailable")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    fun close() {
        val pending = synchronized(lock) {
            closed = true
            calls.toList()
        }
        TakConnectionCloser.execute { pending.forEach(Call::cancel) }
    }

    companion object {
        private const val TAG = "PluginHostServices"
        private val globalHttpPermits = Semaphore(4)
    }
}
