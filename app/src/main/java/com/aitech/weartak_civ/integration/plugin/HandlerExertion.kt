package com.aitech.weartak_civ.integration.plugin

import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginStatusParcel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object HandlerExertion {
    const val PACKAGE = "com.weartak.plugin.handler"
    private const val KEY = "handler_exertion_fraction"
    private const val TIMEOUT_MS = 300L
    // A stuck Binder call retains the only slot until it returns, without blocking plugin teardown.
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
        Thread(task, "WearTAK-HandlerStatus").apply { isDaemon = true }
    })

    internal fun value(status: PluginStatusParcel?): Double? {
        if (status?.state != PluginLifecycleState.RUNNING) return null
        @Suppress("DEPRECATION")
        val value = status.extras?.get(KEY) as? Double ?: return null
        return value.takeIf { it.isFinite() && it in 0.0..1.0 }
    }

    suspend fun read(manager: PluginManager): Double? {
        val connection = manager.connectionFor(PACKAGE) ?: return null
        val result = CompletableDeferred<Double?>()
        val started = SystemClock.elapsedRealtime()
        try {
            worker.execute {
                try {
                    val value = value(connection.handlerStatus())
                    result.complete(value.takeIf { SystemClock.elapsedRealtime() - started <= TIMEOUT_MS })
                } catch (error: android.os.RemoteException) {
                    Log.w("HandlerExertion", "Handler status unavailable", error)
                    result.complete(null)
                } catch (error: RuntimeException) {
                    Log.w("HandlerExertion", "Handler status invalid", error)
                    result.complete(null)
                }
            }
        } catch (_: RejectedExecutionException) {
            Log.w("HandlerExertion", "Handler status worker busy")
            return null
        }
        val response = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        if (!result.isCompleted) Log.w("HandlerExertion", "Handler status timed out")
        return response
    }

    fun pli(snapshot: DataRepository.PliSnapshot, value: Double?): DataRepository.PliSnapshot {
        val monitored = snapshot.monitoredPhysio ?: return snapshot
        if (value == null || monitored.exertion?.let { it.isFinite() && it in 0.0..1.0 } != true) return snapshot
        return snapshot.copy(monitoredPhysio = monitored.copy(exertion = value))
    }

    fun emergency(snapshot: DataRepository.EmergencyEventSnapshot, value: Double?): DataRepository.EmergencyEventSnapshot {
        val physio = snapshot.physio ?: return snapshot
        if (value == null || physio.data.exertion?.let { it.isFinite() && it in 0.0..1.0 } != true) return snapshot
        return snapshot.copy(physio = physio.copy(data = physio.data.copy(exertion = value)))
    }
}
